package com.cvdcms.service.impl;

import com.cvdcms.DTO.PatientDebtSummaryDTO;
import com.cvdcms.DTO.report.*;
import com.cvdcms.entity.BranchTaxConfig;
import com.cvdcms.entity.Invoice;
import com.cvdcms.entity.OperatingExpense;
import com.cvdcms.entity.Payment;
import com.cvdcms.entity.Refund;
import com.cvdcms.entity.Visit;
import com.cvdcms.entity.enums.AppointmentSource;
import com.cvdcms.entity.enums.PaymentMethod;
import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.entity.enums.VisitType;
import com.cvdcms.repository.*;
import com.cvdcms.service.ReportService;
import com.cvdcms.service.TaxCalculationService;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@Transactional(readOnly = true)
public class ReportServiceImpl implements ReportService {

    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final OperatingExpenseRepository operatingExpenseRepository;
    private final VisitRepository visitRepository;
    private final AppointmentRepository appointmentRepository;
    private final TaxCalculationService taxCalculationService;
    private final PayrollPeriodRepository payrollPeriodRepository;
    private final PatientProfileRepository patientProfileRepository;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public ReportServiceImpl(
            InvoiceRepository invoiceRepository,
            PaymentRepository paymentRepository,
            RefundRepository refundRepository,
            OperatingExpenseRepository operatingExpenseRepository,
            VisitRepository visitRepository,
            AppointmentRepository appointmentRepository
    ) {
        this(
                invoiceRepository,
                paymentRepository,
                refundRepository,
                operatingExpenseRepository,
                visitRepository,
                appointmentRepository,
                new TaxCalculationServiceImpl(null, null),
                null,
                null
        );
    }

    @Autowired
    public ReportServiceImpl(
            InvoiceRepository invoiceRepository,
            PaymentRepository paymentRepository,
            RefundRepository refundRepository,
            OperatingExpenseRepository operatingExpenseRepository,
            VisitRepository visitRepository,
            AppointmentRepository appointmentRepository,
            TaxCalculationService taxCalculationService,
            PayrollPeriodRepository payrollPeriodRepository,
            PatientProfileRepository patientProfileRepository
    ) {
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.operatingExpenseRepository = operatingExpenseRepository;
        this.visitRepository = visitRepository;
        this.appointmentRepository = appointmentRepository;
        this.taxCalculationService = taxCalculationService;
        this.payrollPeriodRepository = payrollPeriodRepository;
        this.patientProfileRepository = patientProfileRepository;
    }

    @Override
    public ReportDashboardDTO getDashboardReport(Collection<Long> branchIds, LocalDate startDate, LocalDate endDate, String branchName) {
        return getDashboardReport(branchIds, startDate, endDate, branchName, BigDecimal.valueOf(20));
    }

    @Override
    public ReportDashboardDTO getDashboardReport(Collection<Long> branchIds, LocalDate startDate, LocalDate endDate, String branchName, BigDecimal taxRate) {
        LocalDateTime startDt = startDate.atStartOfDay();
        LocalDateTime endDt = endDate.atTime(LocalTime.MAX);

        ReportDashboardDTO dto = new ReportDashboardDTO();
        dto.setSelectedBranchName(branchName != null ? branchName : "Tất cả chi nhánh");
        dto.setFilterStartDate(startDate);
        dto.setFilterEndDate(endDate);

        // 1. Thực thu theo dòng tiền thực tế (Cash/Bank/POS - Refunds)
        BigDecimal grossRealRevenue = toBigDecimal(paymentRepository.sumRealRevenueByBranches(branchIds, startDt, endDt));
        BigDecimal totalRefund = toBigDecimal(refundRepository.sumRefundAmountByBranches(branchIds, startDt, endDt));
        BigDecimal netRealRevenue = grossRealRevenue.subtract(totalRefund).max(BigDecimal.ZERO);
        dto.setTotalRealRevenue(netRealRevenue);
        dto.setTotalRefundAmount(totalRefund);

        // 2. Tổng giá trị hóa đơn & công nợ phát sinh trong kỳ
        List<Object[]> invBreakdown = invoiceRepository.sumInvoiceBreakdownByBranches(branchIds, startDt, endDt);
        if (!invBreakdown.isEmpty() && invBreakdown.get(0) != null) {
            Object[] row = invBreakdown.get(0);
            dto.setTotalInvoiceAmount(toBigDecimal(row[6]));
            dto.setTotalDebtAmount(toBigDecimal(row[7]));
            if (row.length > 8) {
                dto.setTotalVatAmount(toBigDecimal(row[8]));
            }
        }

        // 3. Chi phí vận hành & Chênh lệch thu chi vận hành (gồm chi phí vận hành + BHXH 21.5% phần DN chịu từ các kỳ lương đã chi trả)
        BigDecimal totalExpense = toBigDecimal(operatingExpenseRepository.sumActiveExpenses(branchIds, startDate, endDate));
        if (payrollPeriodRepository != null) {
            BigDecimal employerInsurance = toBigDecimal(payrollPeriodRepository.sumPaidEmployerInsurance(
                    branchIds,
                    startDate.getMonthValue(), startDate.getYear(),
                    endDate.getMonthValue(), endDate.getYear()
            ));
            totalExpense = totalExpense.add(employerInsurance);
        }
        dto.setTotalExpenseAmount(totalExpense);
        dto.setOperatingMargin(netRealRevenue.subtract(dto.getTotalExpenseAmount()));

        // Thuế & Lợi nhuận sau thuế theo cấu hình pháp nhân / chính sách
        applyDashboardTaxCalculation(dto, branchIds, endDate, taxRate);

        // Thuế TNCN đã khấu trừ của nhân viên từ các bảng lương đã chi trả
        if (payrollPeriodRepository != null) {
            BigDecimal paidPit = payrollPeriodRepository.sumPaidPit(
                    branchIds,
                    startDate.getMonthValue(), startDate.getYear(),
                    endDate.getMonthValue(), endDate.getYear()
            );
            dto.setTotalPitWithheld(paidPit != null ? paidPit : BigDecimal.ZERO);
        }

        // 4. Lượt khám & Tỷ lệ hoàn tất
        List<Object[]> visitStatuses = visitRepository.countVisitsByStatusAndBranches(branchIds, startDt, endDt);
        long totalVisits = 0;
        long completed = 0;
        long cancelled = 0;
        long inProgress = 0;

        for (Object[] r : visitStatuses) {
            VisitStatus st = (VisitStatus) r[0];
            long count = ((Number) r[1]).longValue();
            totalVisits += count;
            if (st == VisitStatus.COMPLETED) {
                completed += count;
            } else if (st == VisitStatus.CANCELLED) {
                cancelled += count;
            } else {
                inProgress += count;
            }
        }

        dto.setTotalVisits(totalVisits);
        dto.setCompletedVisits(completed);
        dto.setCancelledVisits(cancelled);
        dto.setInProgressVisits(inProgress);

        double rateVal = totalVisits > 0 ? (double) completed / totalVisits * 100.0 : 0.0;
        dto.setCompletionRate(BigDecimal.valueOf(rateVal).setScale(1, RoundingMode.HALF_UP).doubleValue());

        // 5. Thống kê theo ngày (cho biểu đồ xu hướng)
        dto.setDailyStats(buildDailyStats(branchIds, startDate, endDate));

        // 6. Bệnh nhân mới trong kỳ
        dto.setNewPatientsCount(countNewPatients(branchIds, startDate, endDate));

        return dto;
    }

    @Override
    public long countTodayAppointments(Collection<Long> branchIds, LocalDate date) {
        if (date == null) date = LocalDate.now();
        return appointmentRepository.countActiveAppointmentsByBranchesAndDate(branchIds, date);
    }

    @Override
    public long countWaitingPatients(Collection<Long> branchIds, LocalDate date) {
        if (date == null) date = LocalDate.now();
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.atTime(LocalTime.MAX);
        return visitRepository.countWaitingVisitsByBranchesAndDate(branchIds, start, end);
    }

    @Override
    public long countNewPatients(Collection<Long> branchIds, LocalDate startDate, LocalDate endDate) {
        if (patientProfileRepository == null) {
            return 0L;
        }
        LocalDateTime startDt = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime endDt = endDate != null ? endDate.atTime(LocalTime.MAX) : null;
        return patientProfileRepository.countNewPatientsByBranchesAndPeriod(branchIds, startDt, endDt);
    }

    private void applyDashboardTaxCalculation(ReportDashboardDTO dto, Collection<Long> branchIds, LocalDate targetDate, BigDecimal requestedTaxRate) {
        Long singleBranchId = (branchIds != null && branchIds.size() == 1) ? branchIds.iterator().next() : null;
        Optional<BranchTaxConfig> configOpt = (singleBranchId != null && taxCalculationService != null)
                ? taxCalculationService.getBranchTaxConfig(singleBranchId, targetDate)
                : Optional.empty();

        if (configOpt.isPresent()) {
            BranchTaxConfig config = configOpt.get();
            dto.setTaxConfigured(true);
            if ("HOUSEHOLD".equalsIgnoreCase(config.getEntityType()) || "REVENUE_PERCENT".equalsIgnoreCase(config.getTaxRegime())) {
                BigDecimal svcRate = config.getServiceTaxRate() != null ? config.getServiceTaxRate() : BigDecimal.valueOf(2.0);
                dto.setTaxRate(svcRate);
                dto.setTaxRegimeName("Hộ kinh doanh cá thể (Thuế khoán trên doanh thu)");
                dto.setTaxCalculationBasis(config.getLegalReference() != null ? config.getLegalReference() : "Thông tư 40/2021/TT-BTC: 2% trên doanh thu dịch vụ y tế");
                BigDecimal estimatedTax = dto.getTotalRealRevenue().multiply(svcRate).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
                dto.setEstimatedTaxAmount(estimatedTax);
                dto.setNetProfitAfterTax(dto.getOperatingMargin().subtract(estimatedTax));
            } else {
                BigDecimal rate = (requestedTaxRate != null && requestedTaxRate.compareTo(BigDecimal.ZERO) >= 0)
                        ? requestedTaxRate
                        : (config.getCitRate() != null ? config.getCitRate() : BigDecimal.valueOf(20));
                dto.setTaxRate(rate);
                dto.setTaxRegimeName("Doanh nghiệp (TNDN tạm tính quản trị)");
                dto.setTaxCalculationBasis(config.getLegalReference() != null ? config.getLegalReference() : "Luật Thuế TNDN 14/2008/QH12 (Tạm tính theo thặng dư vận hành)");
                BigDecimal taxableIncome = dto.getOperatingMargin().max(BigDecimal.ZERO);
                BigDecimal estimatedTax = taxableIncome.multiply(rate).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
                dto.setEstimatedTaxAmount(estimatedTax);
                dto.setNetProfitAfterTax(dto.getOperatingMargin().subtract(estimatedTax));
            }
        } else {
            // Chi nhánh chưa cấu hình hồ sơ thuế riêng hoặc xem toàn hệ thống: Tạm tính quản trị
            dto.setTaxConfigured(false);
            BigDecimal rate = (requestedTaxRate != null && requestedTaxRate.compareTo(BigDecimal.ZERO) >= 0) ? requestedTaxRate : BigDecimal.valueOf(20);
            dto.setTaxRate(rate);
            if (singleBranchId != null) {
                dto.setTaxRegimeName("Doanh nghiệp (TNDN tạm tính quản trị)");
                dto.setTaxCalculationBasis("Chi nhánh chưa có hồ sơ thuế riêng. Tạm tính quản trị " + rate.stripTrailingZeros().toPlainString() + "% theo Luật Thuế TNDN 14/2008/QH12.");
            } else {
                dto.setTaxRegimeName("Tổng hợp toàn hệ thống (TNDN tạm tính quản trị)");
                dto.setTaxCalculationBasis("Tạm tính quản trị " + rate.stripTrailingZeros().toPlainString() + "% trên thặng dư vận hành dương phục vụ quản trị nội bộ");
            }
            BigDecimal taxableIncome = dto.getOperatingMargin().max(BigDecimal.ZERO);
            BigDecimal estimatedTax = taxableIncome.multiply(rate).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
            dto.setEstimatedTaxAmount(estimatedTax);
            dto.setNetProfitAfterTax(dto.getOperatingMargin().subtract(estimatedTax));
        }
    }

    @Override
    public FinancialReportDTO getFinancialReport(Collection<Long> branchIds, LocalDate startDate, LocalDate endDate) {
        return getFinancialReport(branchIds, startDate, endDate, BigDecimal.valueOf(20));
    }

    @Override
    public FinancialReportDTO getFinancialReport(Collection<Long> branchIds, LocalDate startDate, LocalDate endDate, BigDecimal taxRate) {
        LocalDateTime startDt = startDate.atStartOfDay();
        LocalDateTime endDt = endDate.atTime(LocalTime.MAX);

        FinancialReportDTO dto = new FinancialReportDTO();

        // 1. Phân tích giá trị hóa đơn
        List<Object[]> invBreakdown = invoiceRepository.sumInvoiceBreakdownByBranches(branchIds, startDt, endDt);
        if (!invBreakdown.isEmpty() && invBreakdown.get(0) != null) {
            Object[] row = invBreakdown.get(0);
            dto.setTotalServiceAmount(toBigDecimal(row[0]));
            dto.setTotalMedicineAmount(toBigDecimal(row[1]));
            dto.setSurchargeAmount(toBigDecimal(row[2]));
            dto.setDiscountAmount(toBigDecimal(row[3]));
            dto.setPromotionDiscount(toBigDecimal(row[4]));
            dto.setPointDiscount(toBigDecimal(row[5]));
            dto.setTotalInvoiceAmount(toBigDecimal(row[6]));
            dto.setPeriodDebt(toBigDecimal(row[7]));
            if (row.length > 8) {
                dto.setTotalVatAmount(toBigDecimal(row[8]));
            }
            dto.setTotalDiscount(dto.getDiscountAmount().add(dto.getPromotionDiscount()).add(dto.getPointDiscount()));
        }

        // 2. Dòng tiền theo phương thức thanh toán
        dto.setCashAmount(toBigDecimal(paymentRepository.sumAmountByMethodAndBranches(branchIds, PaymentMethod.CASH, null, startDt, endDt)));
        dto.setBankTransferAmount(toBigDecimal(paymentRepository.sumAmountByMethodAndBranches(branchIds, PaymentMethod.BANK_TRANSFER, null, startDt, endDt)));
        dto.setPosCardAmount(toBigDecimal(paymentRepository.sumAmountByMethodAndBranches(branchIds, PaymentMethod.POS_CARD, null, startDt, endDt)));
        dto.setDepositWalletAmount(toBigDecimal(paymentRepository.sumDepositWalletUsedByBranches(branchIds, startDt, endDt)));
        dto.setNewDepositAmount(toBigDecimal(paymentRepository.sumAdvanceDepositByBranches(branchIds, startDt, endDt)));

        // Thực thu theo dòng tiền thực tế = Cash + Bank + POS + Cọc mới
        BigDecimal grossCollected = dto.getCashAmount().add(dto.getBankTransferAmount())
                .add(dto.getPosCardAmount()).add(dto.getNewDepositAmount());
        dto.setTotalCollected(grossCollected);

        // 3. Hoàn tiền & Chi phí (gồm chi phí vận hành + BHXH 21.5% phần DN chịu từ các kỳ lương đã chi trả)
        dto.setTotalRefundAmount(toBigDecimal(refundRepository.sumRefundAmountByBranches(branchIds, startDt, endDt)));
        dto.setNetRevenue(grossCollected.subtract(dto.getTotalRefundAmount()).max(BigDecimal.ZERO));

        BigDecimal activeExpenses = toBigDecimal(operatingExpenseRepository.sumActiveExpenses(branchIds, startDate, endDate));
        if (payrollPeriodRepository != null) {
            BigDecimal employerInsurance = toBigDecimal(payrollPeriodRepository.sumPaidEmployerInsurance(
                    branchIds,
                    startDate.getMonthValue(), startDate.getYear(),
                    endDate.getMonthValue(), endDate.getYear()
            ));
            activeExpenses = activeExpenses.add(employerInsurance);
        }
        dto.setOperatingExpenses(activeExpenses);
        dto.setOperatingMargin(dto.getNetRevenue().subtract(dto.getOperatingExpenses()));

        // Thuế & Lợi nhuận sau thuế theo cấu hình pháp nhân / chính sách
        applyFinancialTaxCalculation(dto, branchIds, endDate, taxRate);

        // Thuế TNCN đã khấu trừ của nhân viên từ các bảng lương đã chi trả
        if (payrollPeriodRepository != null) {
            BigDecimal paidPit = payrollPeriodRepository.sumPaidPit(
                    branchIds,
                    startDate.getMonthValue(), startDate.getYear(),
                    endDate.getMonthValue(), endDate.getYear()
            );
            dto.setTotalPitWithheld(paidPit != null ? paidPit : BigDecimal.ZERO);
        }

        // 4. Danh sách công nợ chi tiết hiện tại của bệnh nhân
        List<PatientDebtSummaryDTO> patientDebts = invoiceRepository.findAllPatientDebtsByBranches(branchIds);
        dto.setPatientDebts(patientDebts != null ? patientDebts : new ArrayList<>());

        BigDecimal currentOutstanding = dto.getPatientDebts().stream()
                .map(PatientDebtSummaryDTO::getTotalRemainingDebt)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        dto.setCurrentTotalOutstandingDebt(currentOutstanding);

        return dto;
    }

    private void applyFinancialTaxCalculation(FinancialReportDTO dto, Collection<Long> branchIds, LocalDate targetDate, BigDecimal requestedTaxRate) {
        Long singleBranchId = (branchIds != null && branchIds.size() == 1) ? branchIds.iterator().next() : null;
        Optional<BranchTaxConfig> configOpt = (singleBranchId != null && taxCalculationService != null)
                ? taxCalculationService.getBranchTaxConfig(singleBranchId, targetDate)
                : Optional.empty();

        if (configOpt.isPresent()) {
            BranchTaxConfig config = configOpt.get();
            dto.setTaxConfigured(true);
            if ("HOUSEHOLD".equalsIgnoreCase(config.getEntityType()) || "REVENUE_PERCENT".equalsIgnoreCase(config.getTaxRegime())) {
                BigDecimal svcRate = config.getServiceTaxRate() != null ? config.getServiceTaxRate() : BigDecimal.valueOf(2.0);
                dto.setTaxRate(svcRate);
                dto.setTaxRegimeName("Hộ kinh doanh cá thể (Thuế khoán trên doanh thu)");
                dto.setTaxCalculationBasis(config.getLegalReference() != null ? config.getLegalReference() : "Thông tư 40/2021/TT-BTC: 2% trên doanh thu dịch vụ y tế");
                BigDecimal estimatedTax = dto.getNetRevenue().multiply(svcRate).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
                dto.setEstimatedTaxAmount(estimatedTax);
                dto.setNetProfitAfterTax(dto.getOperatingMargin().subtract(estimatedTax));
            } else {
                BigDecimal rate = (requestedTaxRate != null && requestedTaxRate.compareTo(BigDecimal.ZERO) >= 0)
                        ? requestedTaxRate
                        : (config.getCitRate() != null ? config.getCitRate() : BigDecimal.valueOf(20));
                dto.setTaxRate(rate);
                dto.setTaxRegimeName("Doanh nghiệp (TNDN tạm tính quản trị)");
                dto.setTaxCalculationBasis(config.getLegalReference() != null ? config.getLegalReference() : "Luật Thuế TNDN 14/2008/QH12 (Tạm tính theo thặng dư vận hành)");
                BigDecimal taxableIncome = dto.getOperatingMargin().max(BigDecimal.ZERO);
                BigDecimal estimatedTax = taxableIncome.multiply(rate).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
                dto.setEstimatedTaxAmount(estimatedTax);
                dto.setNetProfitAfterTax(dto.getOperatingMargin().subtract(estimatedTax));
            }
        } else {
            // Chi nhánh chưa cấu hình hồ sơ thuế riêng hoặc xem toàn hệ thống: Tạm tính quản trị
            dto.setTaxConfigured(false);
            BigDecimal rate = (requestedTaxRate != null && requestedTaxRate.compareTo(BigDecimal.ZERO) >= 0) ? requestedTaxRate : BigDecimal.valueOf(20);
            dto.setTaxRate(rate);
            if (singleBranchId != null) {
                dto.setTaxRegimeName("Doanh nghiệp (TNDN tạm tính quản trị)");
                dto.setTaxCalculationBasis("Chi nhánh chưa có hồ sơ thuế riêng. Tạm tính quản trị " + rate.stripTrailingZeros().toPlainString() + "% theo Luật Thuế TNDN 14/2008/QH12.");
            } else {
                dto.setTaxRegimeName("Tổng hợp toàn hệ thống (TNDN tạm tính quản trị)");
                dto.setTaxCalculationBasis("Tạm tính quản trị " + rate.stripTrailingZeros().toPlainString() + "% trên thặng dư vận hành dương phục vụ quản trị nội bộ");
            }
            BigDecimal taxableIncome = dto.getOperatingMargin().max(BigDecimal.ZERO);
            BigDecimal estimatedTax = taxableIncome.multiply(rate).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
            dto.setEstimatedTaxAmount(estimatedTax);
            dto.setNetProfitAfterTax(dto.getOperatingMargin().subtract(estimatedTax));
        }
    }

    @Override
    public OperationalReportDTO getOperationalReport(Collection<Long> branchIds, LocalDate startDate, LocalDate endDate) {
        LocalDateTime startDt = startDate.atStartOfDay();
        LocalDateTime endDt = endDate.atTime(LocalTime.MAX);

        OperationalReportDTO dto = new OperationalReportDTO();

        // 1. Thống kê theo trạng thái khám
        List<Object[]> visitStatuses = visitRepository.countVisitsByStatusAndBranches(branchIds, startDt, endDt);
        long total = 0;
        for (Object[] r : visitStatuses) {
            VisitStatus st = (VisitStatus) r[0];
            long count = ((Number) r[1]).longValue();
            total += count;
            if (st == VisitStatus.COMPLETED) dto.setCompletedVisits(count);
            else if (st == VisitStatus.CANCELLED) dto.setCancelledVisits(count);
            else if (st == VisitStatus.WAITING) dto.setWaitingVisits(count);
            else if (st == VisitStatus.IN_EXAM) dto.setInExamVisits(count);
            else if (st == VisitStatus.IN_TREATMENT) dto.setInTreatmentVisits(count);
            else if (st == VisitStatus.WAITING_PAYMENT) dto.setWaitingPaymentVisits(count);
        }
        dto.setTotalVisits(total);
        double rate = total > 0 ? (double) dto.getCompletedVisits() / total * 100.0 : 0.0;
        dto.setCompletionRate(BigDecimal.valueOf(rate).setScale(1, RoundingMode.HALF_UP).doubleValue());

        // 2. Thống kê theo hình thức tiếp nhận (VisitType)
        List<Object[]> visitTypes = visitRepository.countVisitsByTypeAndBranches(branchIds, startDt, endDt);
        for (Object[] r : visitTypes) {
            VisitType vt = (VisitType) r[0];
            long count = ((Number) r[1]).longValue();
            if (vt == VisitType.WALK_IN) dto.setWalkInVisits(count);
            else if (vt == VisitType.APPOINTMENT) dto.setAppointmentVisits(count);
            else if (vt == VisitType.EMERGENCY) dto.setEmergencyVisits(count);
        }

        // 3. Thống kê theo kênh đặt lịch hẹn (AppointmentSource)
        List<Object[]> apptSources = appointmentRepository.countAppointmentsBySourceAndBranches(branchIds, startDate, endDate);
        for (Object[] r : apptSources) {
            AppointmentSource src = (AppointmentSource) r[0];
            long count = ((Number) r[1]).longValue();
            if (src == AppointmentSource.ONLINE) dto.setOnlineAppointments(count);
            else if (src == AppointmentSource.RECEPTIONIST) dto.setReceptionistAppointments(count);
            else if (src == AppointmentSource.PHONE) dto.setPhoneAppointments(count);
            else if (src == AppointmentSource.CHATBOT) dto.setChatbotAppointments(count);
        }

        // 4. Thống kê nguồn khách hàng (customerSource từ PatientProfile)
        List<Object[]> sourceResults = visitRepository.countPatientSourcesByVisitsAndBranches(branchIds, startDt, endDt);
        long totalSourcesCount = 0;
        List<CustomerSourceStat> sources = new ArrayList<>();

        for (Object[] r : sourceResults) {
            String name = (String) r[0];
            if (name == null || name.trim().isEmpty()) {
                name = "Chưa xác định";
            } else {
                name = name.trim();
            }
            long count = ((Number) r[1]).longValue();
            totalSourcesCount += count;
            sources.add(new CustomerSourceStat(name, count, 0.0));
        }

        Map<String, Long> groupedSources = new LinkedHashMap<>();
        for (CustomerSourceStat s : sources) {
            groupedSources.put(s.getSourceName(), groupedSources.getOrDefault(s.getSourceName(), 0L) + s.getPatientCount());
        }

        List<CustomerSourceStat> finalSources = new ArrayList<>();
        for (Map.Entry<String, Long> entry : groupedSources.entrySet()) {
            double p = totalSourcesCount > 0 ? (entry.getValue() * 100.0 / totalSourcesCount) : 0.0;
            p = BigDecimal.valueOf(p).setScale(1, RoundingMode.HALF_UP).doubleValue();
            finalSources.add(new CustomerSourceStat(entry.getKey(), entry.getValue(), p));
        }
        finalSources.sort((a, b) -> Long.compare(b.getPatientCount(), a.getPatientCount()));
        dto.setCustomerSources(finalSources);

        return dto;
    }

    private List<DailyRevenueStat> buildDailyStats(Collection<Long> branchIds, LocalDate startDate, LocalDate endDate) {
        LocalDateTime startDt = startDate.atStartOfDay();
        LocalDateTime endDt = endDate.atTime(LocalTime.MAX);

        Map<LocalDate, DailyRevenueStat> map = new LinkedHashMap<>();
        LocalDate cur = startDate;
        while (!cur.isAfter(endDate)) {
            map.put(cur, new DailyRevenueStat(cur, BigDecimal.ZERO, BigDecimal.ZERO, 0L));
            cur = cur.plusDays(1);
        }

        // 1. Phân bổ doanh số hóa đơn theo ngày
        List<Object[]> invAmounts = invoiceRepository.findInvoiceAmountsForPeriod(branchIds, startDt, endDt);
        for (Object[] r : invAmounts) {
            if (r[0] != null) {
                LocalDate d = ((LocalDateTime) r[0]).toLocalDate();
                BigDecimal amt = toBigDecimal(r[1]);
                DailyRevenueStat stat = map.get(d);
                if (stat != null) {
                    stat.setInvoiceAmount(stat.getInvoiceAmount().add(amt));
                }
            }
        }

        // 2. Phân bổ thực thu thanh toán theo ngày
        List<Object[]> pmtAmounts = paymentRepository.findDailyRealPaymentsForPeriod(branchIds, startDt, endDt);
        for (Object[] r : pmtAmounts) {
            if (r[0] != null) {
                LocalDate d = ((LocalDateTime) r[0]).toLocalDate();
                BigDecimal amt = toBigDecimal(r[1]);
                DailyRevenueStat stat = map.get(d);
                if (stat != null) {
                    stat.setRealRevenue(stat.getRealRevenue().add(amt));
                }
            }
        }

        // 3. Phân bổ lượt khám theo ngày
        List<LocalDateTime> visitDates = visitRepository.findVisitCheckInDates(branchIds, startDt, endDt);
        for (LocalDateTime vt : visitDates) {
            if (vt != null) {
                LocalDate d = vt.toLocalDate();
                DailyRevenueStat stat = map.get(d);
                if (stat != null) {
                    stat.setVisitCount(stat.getVisitCount() + 1);
                }
            }
        }

        return new ArrayList<>(map.values());
    }

    // =========================================================================
    // XUẤT BÁO CÁO EXCEL CHUẨN (.XLSX)
    // =========================================================================
    @Override
    public byte[] exportReportToExcel(String reportType, Collection<Long> branchIds, LocalDate startDate, LocalDate endDate, String branchName) {
        return exportReportToExcel(reportType, branchIds, startDate, endDate, branchName, BigDecimal.valueOf(20));
    }

    @Override
    public byte[] exportReportToExcel(String reportType, Collection<Long> branchIds, LocalDate startDate, LocalDate endDate, String branchName, BigDecimal taxRate) {
        LocalDateTime startDt = startDate.atStartOfDay();
        LocalDateTime endDt = endDate.atTime(LocalTime.MAX);

        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            ExcelStyleRegistry styles = new ExcelStyleRegistry(workbook);

            String safeType = (reportType != null) ? reportType.trim().toUpperCase() : "OVERVIEW";

            switch (safeType) {
                case "FINANCIAL" -> {
                    FinancialReportDTO financialDTO = getFinancialReport(branchIds, startDate, endDate, taxRate);
                    buildFinancialSummarySheet(workbook, styles, financialDTO, branchName, startDate, endDate);
                    buildInvoicesDetailSheet(workbook, styles, branchIds, startDt, endDt);
                    buildDebtsDetailSheet(workbook, styles, financialDTO.getPatientDebts(), branchName);
                }
                case "OPERATIONAL" -> {
                    OperationalReportDTO operationalDTO = getOperationalReport(branchIds, startDate, endDate);
                    buildOperationalSummarySheet(workbook, styles, operationalDTO, branchName, startDate, endDate);
                    buildVisitsDetailSheet(workbook, styles, branchIds, startDt, endDt);
                }
                case "DEBTS" -> {
                    List<PatientDebtSummaryDTO> debts = invoiceRepository.findAllPatientDebtsByBranches(branchIds);
                    buildDebtsDetailSheet(workbook, styles, debts, branchName);
                }
                default -> { // "OVERVIEW" or "ALL"
                    ReportDashboardDTO dashboardDTO = getDashboardReport(branchIds, startDate, endDate, branchName, taxRate);
                    buildDashboardSummarySheet(workbook, styles, dashboardDTO, branchName, startDate, endDate);
                    FinancialReportDTO financialDTO = getFinancialReport(branchIds, startDate, endDate, taxRate);
                    buildFinancialSummarySheet(workbook, styles, financialDTO, branchName, startDate, endDate);
                    OperationalReportDTO operationalDTO = getOperationalReport(branchIds, startDate, endDate);
                    buildOperationalSummarySheet(workbook, styles, operationalDTO, branchName, startDate, endDate);
                    buildInvoicesDetailSheet(workbook, styles, branchIds, startDt, endDt);
                    buildDebtsDetailSheet(workbook, styles, financialDTO.getPatientDebts(), branchName);
                }
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Lỗi xuất báo cáo Excel tổng hợp: ", e);
            throw new RuntimeException("Không thể tạo file Excel báo cáo: " + e.getMessage(), e);
        }
    }

    private void buildDashboardSummarySheet(XSSFWorkbook wb, ExcelStyleRegistry s, ReportDashboardDTO dto, String branchName, LocalDate start, LocalDate end) {
        Sheet sheet = wb.createSheet("Tổng quan Quản trị");
        sheet.setDisplayGridlines(true);

        int r = writeReportHeader(sheet, s, "BÁO CÁO TỔNG QUAN DOANH THU & HOẠT ĐỘNG", branchName, start, end);

        // Bảng 1: Chỉ tiêu tổng quan
        Row rHeader = sheet.createRow(r++);
        createHeaderCell(rHeader, 0, "CHỈ TIÊU TỔNG QUAN", s.headerStyle);
        createHeaderCell(rHeader, 1, "GIÁ TRỊ", s.headerStyle);
        createHeaderCell(rHeader, 2, "ĐƠN VỊ", s.headerStyle);
        createHeaderCell(rHeader, 3, "GHI CHÚ NGHIỆP VỤ", s.headerStyle);

        r = writeMetricRow(sheet, s, r, "Thực thu dòng tiền (Net Revenue)", dto.getTotalRealRevenue(), "VNĐ", "Tiền mặt + Chuyển khoản + POS - Hoàn tiền (không tính ví cọc)");
        r = writeMetricRow(sheet, s, r, "Tổng giá trị hóa đơn phát sinh", dto.getTotalInvoiceAmount(), "VNĐ", "Tổng viện phí đã xuất (trừ hóa đơn hủy)");
        r = writeMetricRow(sheet, s, r, "Tổng thuế VAT đầu ra phát sinh", dto.getTotalVatAmount(), "VNĐ", "Thuế GTGT phát sinh từ bán lẻ thuốc (Dịch vụ nha khoa không chịu thuế GTGT)");
        r = writeMetricRow(sheet, s, r, "Công nợ phát sinh trong kỳ", dto.getTotalDebtAmount(), "VNĐ", "Số tiền chưa thanh toán của các hóa đơn lập trong kỳ");
        r = writeMetricRow(sheet, s, r, "Tổng số lượt khám tiếp nhận", dto.getTotalVisits(), "Lượt", "Đếm theo lượt khám thực tế (tránh trùng)");
        r = writeMetricRow(sheet, s, r, "Lượt khám hoàn tất", dto.getCompletedVisits(), "Lượt", "Trạng thái COMPLETED");
        r = writeMetricRow(sheet, s, r, "Tỷ lệ hoàn tất lượt khám", dto.getCompletionRate(), "%", "(Hoàn tất / Tổng lượt khám) * 100%");
        r = writeMetricRow(sheet, s, r, "Tổng chi phí vận hành hợp lệ", dto.getTotalExpenseAmount(), "VNĐ", "Chi phí hợp lệ phát sinh (đã trừ phiếu chi hủy)");
        r = writeMetricRow(sheet, s, r, "Chênh lệch Thu - Chi vận hành (Lợi nhuận trước thuế)", dto.getOperatingMargin(), "VNĐ", "Thực thu - Chi phí vận hành");
        r = writeMetricRow(sheet, s, r, "Nghĩa vụ Thuế đơn vị tạm tính (" + dto.getTaxRate() + "%)", dto.getEstimatedTaxAmount(), "VNĐ", dto.getTaxRegimeName() + " | " + dto.getTaxCalculationBasis());
        r = writeMetricRow(sheet, s, r, "Lợi nhuận ròng sau thuế (Ước tính quản trị)", dto.getNetProfitAfterTax(), "VNĐ", "= Lợi nhuận trước thuế - Thuế tạm tính");
        r = writeMetricRow(sheet, s, r, "Thuế TNCN đã khấu trừ của nhân viên", dto.getTotalPitWithheld(), "VNĐ", "Thuế TNCN khấu trừ từ lương các kỳ đã chi trả trong kỳ lọc (Khoản giữ hộ nhà nước)");

        r += 2;
        // Bảng 2: Diễn biến theo ngày
        Row trendHeader = sheet.createRow(r++);
        createHeaderCell(trendHeader, 0, "Ngày", s.headerStyle);
        createHeaderCell(trendHeader, 1, "Doanh số hóa đơn (VNĐ)", s.headerStyle);
        createHeaderCell(trendHeader, 2, "Thực thu dòng tiền (VNĐ)", s.headerStyle);
        createHeaderCell(trendHeader, 3, "Số lượt khám", s.headerStyle);

        BigDecimal sumInv = BigDecimal.ZERO;
        BigDecimal sumReal = BigDecimal.ZERO;
        long sumVis = 0;

        for (DailyRevenueStat stat : dto.getDailyStats()) {
            Row row = sheet.createRow(r++);
            createCell(row, 0, stat.getDate().format(DATE_FMT), s.dateCenterStyle);
            createNumericCell(row, 1, stat.getInvoiceAmount(), s.currencyStyle);
            createNumericCell(row, 2, stat.getRealRevenue(), s.currencyStyle);
            createNumericCell(row, 3, stat.getVisitCount(), s.integerStyle);

            sumInv = sumInv.add(stat.getInvoiceAmount());
            sumReal = sumReal.add(stat.getRealRevenue());
            sumVis += stat.getVisitCount();
        }

        Row totalRow = sheet.createRow(r++);
        createCell(totalRow, 0, "TỔNG CỘNG", s.totalHeaderStyle);
        createNumericCell(totalRow, 1, sumInv, s.totalCurrencyStyle);
        createNumericCell(totalRow, 2, sumReal, s.totalCurrencyStyle);
        createNumericCell(totalRow, 3, sumVis, s.totalIntegerStyle);

        autoSizeColumns(sheet, 4);
    }

    private void buildFinancialSummarySheet(XSSFWorkbook wb, ExcelStyleRegistry s, FinancialReportDTO dto, String branchName, LocalDate start, LocalDate end) {
        Sheet sheet = wb.createSheet("Tài chính & Dòng tiền");
        sheet.setDisplayGridlines(true);

        int r = writeReportHeader(sheet, s, "BÁO CÁO TÀI CHÍNH, DOANH THU & DÒNG TIỀN", branchName, start, end);

        Row rHeader = sheet.createRow(r++);
        createHeaderCell(rHeader, 0, "KHOẢN MỤC TÀI CHÍNH", s.headerStyle);
        createHeaderCell(rHeader, 1, "SỐ TIỀN (VNĐ)", s.headerStyle);
        createHeaderCell(rHeader, 2, "TỶ LỆ / ĐƠN VỊ", s.headerStyle);
        createHeaderCell(rHeader, 3, "DIỄN GIẢI KẾ TOÁN", s.headerStyle);

        // 1. Phân tích doanh số
        r = writeMetricRow(sheet, s, r, "1. Tổng giá trị hóa đơn (Viện phí)", dto.getTotalInvoiceAmount(), "VNĐ", "Tổng giá trị sau khi đã trừ giảm giá, voucher, điểm");
        r = writeMetricRow(sheet, s, r, "   - Doanh thu dịch vụ khám & điều trị", dto.getTotalServiceAmount(), "VNĐ", "Dịch vụ nha khoa, thủ thuật (KCT thuế GTGT - K9 Đ5 Luật Thuế GTGT)");
        r = writeMetricRow(sheet, s, r, "   - Doanh thu đơn thuốc", dto.getTotalMedicineAmount(), "VNĐ", "Tiền thuốc phát theo đơn");
        r = writeMetricRow(sheet, s, r, "   - Tổng thuế VAT đầu ra phát sinh", dto.getTotalVatAmount(), "VNĐ", "Thuế GTGT phát sinh từ bán lẻ thuốc");
        r = writeMetricRow(sheet, s, r, "   - Phụ thu khác", dto.getSurchargeAmount(), "VNĐ", "Phí phụ thu phát sinh");
        r = writeMetricRow(sheet, s, r, "   - Tổng giảm trừ (Ưu đãi, Voucher, Điểm)", dto.getTotalDiscount(), "VNĐ", "Chiết khấu thành viên + Mã KM + Đổi điểm");

        // 2. Cơ cấu dòng tiền thu
        r = writeMetricRow(sheet, s, r, "2. Tiền thực thu từ khách hàng", dto.getTotalCollected(), "VNĐ", "Tiền mặt + Chuyển khoản + POS + Nạp cọc mới");
        r = writeMetricRow(sheet, s, r, "   - Thu bằng Tiền mặt (CASH)", dto.getCashAmount(), "VNĐ", "Dòng tiền mặt vào quỹ phòng khám");
        r = writeMetricRow(sheet, s, r, "   - Thu qua Chuyển khoản (BANK_TRANSFER)", dto.getBankTransferAmount(), "VNĐ", "Tiền vào tài khoản ngân hàng");
        r = writeMetricRow(sheet, s, r, "   - Thu qua Thẻ POS (POS_CARD)", dto.getPosCardAmount(), "VNĐ", "Quẹt thẻ máy POS");
        r = writeMetricRow(sheet, s, r, "   - Tiền nạp tạm ứng mới (ADVANCE_DEPOSIT)", dto.getNewDepositAmount(), "VNĐ", "Khách nạp cọc vào quỹ điều trị");
        r = writeMetricRow(sheet, s, r, "   - Thanh toán bằng ví cọc (DEPOSIT_WALLET)", dto.getDepositWalletAmount(), "VNĐ", "Trừ từ số dư ví cọc đã nạp (không tính trùng dòng tiền)");

        // 3. Hoàn trả & Chi phí
        r = writeMetricRow(sheet, s, r, "3. Tiền hoàn trả cho bệnh nhân", dto.getTotalRefundAmount(), "VNĐ", "Hoàn hóa đơn hoặc rút số dư cọc");
        r = writeMetricRow(sheet, s, r, "4. Thực thu thuần sau hoàn trả", dto.getNetRevenue(), "VNĐ", "= Tổng thực thu (2) - Tiền hoàn trả (3)");
        r = writeMetricRow(sheet, s, r, "5. Chi phí vận hành hợp lệ", dto.getOperatingExpenses(), "VNĐ", "Chi phí mặt bằng, điện nước, lương nhân sự...");
        r = writeMetricRow(sheet, s, r, "6. Chênh lệch Thu - Chi vận hành (Lợi nhuận trước thuế)", dto.getOperatingMargin(), "VNĐ", "= Thực thu thuần (4) - Chi phí vận hành (5)");
        r = writeMetricRow(sheet, s, r, "7. Nghĩa vụ Thuế đơn vị tạm tính (" + dto.getTaxRate() + "%)", dto.getEstimatedTaxAmount(), "VNĐ", dto.getTaxRegimeName() + " | " + dto.getTaxCalculationBasis());
        r = writeMetricRow(sheet, s, r, "8. Lợi nhuận ròng sau thuế (Ước tính quản trị)", dto.getNetProfitAfterTax(), "VNĐ", "= Lợi nhuận trước thuế (6) - Thuế tạm tính (7)");
        r = writeMetricRow(sheet, s, r, "9. Thuế TNCN đã khấu trừ của nhân viên", dto.getTotalPitWithheld(), "VNĐ", "Khoản thuế TNCN giữ lại từ lương để nộp thay vào ngân sách nhà nước");

        // 4. Công nợ
        r = writeMetricRow(sheet, s, r, "10. Công nợ hóa đơn phát sinh trong kỳ", dto.getPeriodDebt(), "VNĐ", "Số nợ chưa thu của các hóa đơn lập trong kỳ lọc");
        r = writeMetricRow(sheet, s, r, "11. Tổng công nợ lũy kế hiện tại", dto.getCurrentTotalOutstandingDebt(), "VNĐ", "Toàn bộ công nợ còn tồn đọng của tất cả bệnh nhân");

        autoSizeColumns(sheet, 4);
    }

    private void buildInvoicesDetailSheet(XSSFWorkbook wb, ExcelStyleRegistry s, Collection<Long> branchIds, LocalDateTime start, LocalDateTime end) {
        Sheet sheet = wb.createSheet("Chi tiết Hóa đơn");
        sheet.setDisplayGridlines(true);

        List<Invoice> invoices = invoiceRepository.findAllInvoicesForReport(branchIds, start, end);

        Row titleRow = sheet.createRow(0);
        titleRow.createCell(0).setCellValue("DANH SÁCH HÓA ĐƠN VIỆN PHÍ PHÁT SINH TRONG KỲ");
        titleRow.getCell(0).setCellStyle(s.titleStyle);

        Row h = sheet.createRow(2);
        createHeaderCell(h, 0, "STT", s.headerStyle);
        createHeaderCell(h, 1, "Mã Hóa Đơn", s.headerStyle);
        createHeaderCell(h, 2, "Ngày Lập", s.headerStyle);
        createHeaderCell(h, 3, "Chi Nhánh", s.headerStyle);
        createHeaderCell(h, 4, "Mã Bệnh Nhân", s.headerStyle);
        createHeaderCell(h, 5, "Họ Tên Bệnh Nhân", s.headerStyle);
        createHeaderCell(h, 6, "SĐT", s.headerStyle);
        createHeaderCell(h, 7, "Tổng Tiền HĐ (VNĐ)", s.headerStyle);
        createHeaderCell(h, 8, "Đã Thanh Toán (VNĐ)", s.headerStyle);
        createHeaderCell(h, 9, "Nợ Còn Lại (VNĐ)", s.headerStyle);
        createHeaderCell(h, 10, "Trạng Thái Thu", s.headerStyle);
        createHeaderCell(h, 11, "Thu Ngân Lập", s.headerStyle);

        int r = 3;
        int stt = 1;
        BigDecimal sumTotal = BigDecimal.ZERO;
        BigDecimal sumPaid = BigDecimal.ZERO;
        BigDecimal sumDebt = BigDecimal.ZERO;

        for (Invoice inv : invoices) {
            Row row = sheet.createRow(r++);
            createCell(row, 0, String.valueOf(stt++), s.textCenterStyle);
            createCell(row, 1, sanitize(inv.getInvoiceCode()), s.textCenterStyle);
            createCell(row, 2, inv.getIssuedAt() != null ? inv.getIssuedAt().format(DATETIME_FMT) : "", s.dateCenterStyle);
            createCell(row, 3, sanitize(inv.getBranch() != null ? inv.getBranch().getBranchName() : ""), s.textStyle);
            createCell(row, 4, sanitize(inv.getPatientProfile() != null ? inv.getPatientProfile().getPatientCode() : ""), s.textCenterStyle);
            createCell(row, 5, sanitize(inv.getPatientProfile() != null ? inv.getPatientProfile().getFullName() : ""), s.textStyle);
            createCell(row, 6, sanitize(inv.getPatientProfile() != null ? inv.getPatientProfile().getPhoneNumber() : ""), s.textCenterStyle);

            BigDecimal tot = inv.getTotalAmount() != null ? inv.getTotalAmount() : BigDecimal.ZERO;
            BigDecimal paid = inv.getPaidAmount() != null ? inv.getPaidAmount() : BigDecimal.ZERO;
            BigDecimal debt = inv.getRemainingDebt() != null ? inv.getRemainingDebt() : BigDecimal.ZERO;

            createNumericCell(row, 7, tot, s.currencyStyle);
            createNumericCell(row, 8, paid, s.currencyStyle);
            createNumericCell(row, 9, debt, s.currencyStyle);

            createCell(row, 10, inv.getPaymentStatus() != null ? inv.getPaymentStatus().name() : "", s.textCenterStyle);
            createCell(row, 11, sanitize(inv.getCashier() != null ? inv.getCashier().getFullName() : ""), s.textStyle);

            sumTotal = sumTotal.add(tot);
            sumPaid = sumPaid.add(paid);
            sumDebt = sumDebt.add(debt);
        }

        Row totalRow = sheet.createRow(r);
        createCell(totalRow, 0, "TỔNG CỘNG", s.totalHeaderStyle);
        for (int c = 1; c <= 6; c++) createCell(totalRow, c, "", s.totalHeaderStyle);
        createNumericCell(totalRow, 7, sumTotal, s.totalCurrencyStyle);
        createNumericCell(totalRow, 8, sumPaid, s.totalCurrencyStyle);
        createNumericCell(totalRow, 9, sumDebt, s.totalCurrencyStyle);
        createCell(totalRow, 10, "", s.totalHeaderStyle);
        createCell(totalRow, 11, "", s.totalHeaderStyle);

        autoSizeColumns(sheet, 12);
    }

    private void buildDebtsDetailSheet(XSSFWorkbook wb, ExcelStyleRegistry s, List<PatientDebtSummaryDTO> debts, String branchName) {
        Sheet sheet = wb.createSheet("Công nợ Bệnh nhân");
        sheet.setDisplayGridlines(true);

        Row titleRow = sheet.createRow(0);
        titleRow.createCell(0).setCellValue("DANH SÁCH CHI TIẾT CÔNG NỢ BỆNH NHÂN HIỆN TẠI - " + branchName);
        titleRow.getCell(0).setCellStyle(s.titleStyle);

        Row h = sheet.createRow(2);
        createHeaderCell(h, 0, "STT", s.headerStyle);
        createHeaderCell(h, 1, "Mã Bệnh Nhân", s.headerStyle);
        createHeaderCell(h, 2, "Họ và Tên", s.headerStyle);
        createHeaderCell(h, 3, "Số Điện Thoại", s.headerStyle);
        createHeaderCell(h, 4, "Số HĐ Còn Nợ", s.headerStyle);
        createHeaderCell(h, 5, "Tổng Tiền HĐ (VNĐ)", s.headerStyle);
        createHeaderCell(h, 6, "Đã Thanh Toán (VNĐ)", s.headerStyle);
        createHeaderCell(h, 7, "Nợ Còn Lại (VNĐ)", s.headerStyle);
        createHeaderCell(h, 8, "Số Dư Ví Cọc (VNĐ)", s.headerStyle);
        createHeaderCell(h, 9, "Lần Khám / HĐ Cuối", s.headerStyle);

        int r = 3;
        int stt = 1;
        BigDecimal sumTotal = BigDecimal.ZERO;
        BigDecimal sumPaid = BigDecimal.ZERO;
        BigDecimal sumDebt = BigDecimal.ZERO;
        BigDecimal sumDeposit = BigDecimal.ZERO;

        for (PatientDebtSummaryDTO d : debts) {
            Row row = sheet.createRow(r++);
            createCell(row, 0, String.valueOf(stt++), s.textCenterStyle);
            createCell(row, 1, sanitize(d.getPatientCode()), s.textCenterStyle);
            createCell(row, 2, sanitize(d.getFullName()), s.textStyle);
            createCell(row, 3, sanitize(d.getPhoneNumber()), s.textCenterStyle);
            createNumericCell(row, 4, d.getUnpaidInvoiceCount(), s.integerStyle);

            BigDecimal tot = d.getTotalInvoiceAmount() != null ? d.getTotalInvoiceAmount() : BigDecimal.ZERO;
            BigDecimal paid = d.getTotalPaidAmount() != null ? d.getTotalPaidAmount() : BigDecimal.ZERO;
            BigDecimal debt = d.getTotalRemainingDebt() != null ? d.getTotalRemainingDebt() : BigDecimal.ZERO;
            BigDecimal dep = d.getDepositBalance() != null ? d.getDepositBalance() : BigDecimal.ZERO;

            createNumericCell(row, 5, tot, s.currencyStyle);
            createNumericCell(row, 6, paid, s.currencyStyle);
            createNumericCell(row, 7, debt, s.currencyStyle);
            createNumericCell(row, 8, dep, s.currencyStyle);
            createCell(row, 9, d.getLastInvoiceDate() != null ? d.getLastInvoiceDate().format(DATE_FMT) : "", s.dateCenterStyle);

            sumTotal = sumTotal.add(tot);
            sumPaid = sumPaid.add(paid);
            sumDebt = sumDebt.add(debt);
            sumDeposit = sumDeposit.add(dep);
        }

        Row totalRow = sheet.createRow(r);
        createCell(totalRow, 0, "TỔNG CỘNG (" + debts.size() + " BỆNH NHÂN)", s.totalHeaderStyle);
        for (int c = 1; c <= 4; c++) createCell(totalRow, c, "", s.totalHeaderStyle);
        createNumericCell(totalRow, 5, sumTotal, s.totalCurrencyStyle);
        createNumericCell(totalRow, 6, sumPaid, s.totalCurrencyStyle);
        createNumericCell(totalRow, 7, sumDebt, s.totalCurrencyStyle);
        createNumericCell(totalRow, 8, sumDeposit, s.totalCurrencyStyle);
        createCell(totalRow, 9, "", s.totalHeaderStyle);

        autoSizeColumns(sheet, 10);
    }

    private void buildOperationalSummarySheet(XSSFWorkbook wb, ExcelStyleRegistry s, OperationalReportDTO dto, String branchName, LocalDate start, LocalDate end) {
        Sheet sheet = wb.createSheet("Vận hành & Nguồn khách");
        sheet.setDisplayGridlines(true);

        int r = writeReportHeader(sheet, s, "BÁO CÁO HOẠT ĐỘNG VẬN HÀNH & NGUỒN KHÁCH HÀNG", branchName, start, end);

        // Bảng 1: Lượt khám & Trạng thái
        Row rHeader = sheet.createRow(r++);
        createHeaderCell(rHeader, 0, "CHỈ TIÊU VẬN HÀNH", s.headerStyle);
        createHeaderCell(rHeader, 1, "SỐ LƯỢT", s.headerStyle);
        createHeaderCell(rHeader, 2, "TỶ LỆ (%)", s.headerStyle);
        createHeaderCell(rHeader, 3, "ĐỊNH NGHĨA", s.headerStyle);

        r = writeMetricRow(sheet, s, r, "1. Tổng số lượt khám (Visits)", dto.getTotalVisits(), "Lượt", "Đếm theo từng lượt khám thực tế");
        r = writeMetricRow(sheet, s, r, "   - Khám hoàn tất (COMPLETED)", dto.getCompletedVisits(), "Lượt", "Đã hoàn thành toàn bộ quy trình khám & điều trị");
        r = writeMetricRow(sheet, s, r, "   - Hủy lượt khám (CANCELLED)", dto.getCancelledVisits(), "Lượt", "Bệnh nhân hủy hoặc bác sĩ hủy ca");
        r = writeMetricRow(sheet, s, r, "   - Đang chờ khám (WAITING)", dto.getWaitingVisits(), "Lượt", "Bệnh nhân đang chờ tiếp nhận vào ghế");
        r = writeMetricRow(sheet, s, r, "   - Đang khám lâm sàng (IN_EXAM)", dto.getInExamVisits(), "Lượt", "Bác sĩ đang khám tổng quát/chẩn đoán");
        r = writeMetricRow(sheet, s, r, "   - Đang điều trị ghế nha (IN_TREATMENT)", dto.getInTreatmentVisits(), "Lượt", "Đang thực hiện thủ thuật điều trị");
        r = writeMetricRow(sheet, s, r, "   - Chờ thanh toán viện phí (WAITING_PAYMENT)", dto.getWaitingPaymentVisits(), "Lượt", "Đã khám xong, chuyển thu ngân lập phiếu thu");
        r = writeMetricRow(sheet, s, r, "2. Tỷ lệ hoàn tất lượt khám", dto.getCompletionRate(), "%", "(COMPLETED / Tổng số lượt khám) * 100%");

        // Bảng 2: Kênh tiếp nhận
        r += 1;
        Row rHeader2 = sheet.createRow(r++);
        createHeaderCell(rHeader2, 0, "KÊNH TIẾP NHẬN & ĐẶT HẸN", s.headerStyle);
        createHeaderCell(rHeader2, 1, "SỐ LƯỢT", s.headerStyle);
        createHeaderCell(rHeader2, 2, "ĐƠN VỊ", s.headerStyle);
        createHeaderCell(rHeader2, 3, "PHÂN LOẠI", s.headerStyle);

        r = writeMetricRow(sheet, s, r, "Khách đến trực tiếp vãng lai (WALK_IN)", dto.getWalkInVisits(), "Lượt", "Đến phòng khám không qua đặt lịch trước");
        r = writeMetricRow(sheet, s, r, "Khách có lịch hẹn trước (APPOINTMENT)", dto.getAppointmentVisits(), "Lượt", "Đã đặt lịch hẹn");
        r = writeMetricRow(sheet, s, r, "Ca cấp cứu nha khoa (EMERGENCY)", dto.getEmergencyVisits(), "Lượt", "Tiếp nhận khẩn cấp");
        r = writeMetricRow(sheet, s, r, "Đặt hẹn qua Portal trực tuyến (ONLINE)", dto.getOnlineAppointments(), "Lượt", "Bệnh nhân tự đặt qua website/app");
        r = writeMetricRow(sheet, s, r, "Đặt hẹn qua Lễ tân (RECEPTIONIST)", dto.getReceptionistAppointments(), "Lượt", "Lễ tân ghi nhận lịch hẹn");
        r = writeMetricRow(sheet, s, r, "Đặt hẹn qua Tổng đài / Hotline (PHONE)", dto.getPhoneAppointments(), "Lượt", "Tư vấn viên tiếp nhận cuộc gọi");
        r = writeMetricRow(sheet, s, r, "Đặt hẹn qua Trợ lý ảo (CHATBOT)", dto.getChatbotAppointments(), "Lượt", "AI Chatbot tiếp nhận");

        // Bảng 3: Nguồn khách hàng
        r += 1;
        Row rHeader3 = sheet.createRow(r++);
        createHeaderCell(rHeader3, 0, "NGUỒN KHÁCH HÀNG (CUSTOMER SOURCE)", s.headerStyle);
        createHeaderCell(rHeader3, 1, "SỐ BỆNH NHÂN", s.headerStyle);
        createHeaderCell(rHeader3, 2, "TỶ LỆ (%)", s.headerStyle);
        createHeaderCell(rHeader3, 3, "NGUỒN DỮ LIỆU", s.headerStyle);

        for (CustomerSourceStat cs : dto.getCustomerSources()) {
            Row row = sheet.createRow(r++);
            createCell(row, 0, sanitize(cs.getSourceName()), s.textStyle);
            createNumericCell(row, 1, cs.getPatientCount(), s.integerStyle);
            createNumericCell(row, 2, BigDecimal.valueOf(cs.getPercentage()), s.percentStyle);
            createCell(row, 3, "Hồ sơ bệnh nhân (customer_source)", s.textStyle);
        }

        autoSizeColumns(sheet, 4);
    }

    private void buildVisitsDetailSheet(XSSFWorkbook wb, ExcelStyleRegistry s, Collection<Long> branchIds, LocalDateTime start, LocalDateTime end) {
        Sheet sheet = wb.createSheet("Chi tiết Lượt khám");
        sheet.setDisplayGridlines(true);

        List<Visit> visits = visitRepository.findAllVisitsForReport(branchIds, start, end);

        Row titleRow = sheet.createRow(0);
        titleRow.createCell(0).setCellValue("DANH SÁCH CHI TIẾT CÁC LƯỢT KHÁM TRONG KỲ");
        titleRow.getCell(0).setCellStyle(s.titleStyle);

        Row h = sheet.createRow(2);
        createHeaderCell(h, 0, "STT", s.headerStyle);
        createHeaderCell(h, 1, "Mã Lượt Khám", s.headerStyle);
        createHeaderCell(h, 2, "Thời Gian Tiếp Nhận", s.headerStyle);
        createHeaderCell(h, 3, "Chi Nhánh", s.headerStyle);
        createHeaderCell(h, 4, "Mã Bệnh Nhân", s.headerStyle);
        createHeaderCell(h, 5, "Họ và Tên", s.headerStyle);
        createHeaderCell(h, 6, "SĐT", s.headerStyle);
        createHeaderCell(h, 7, "Hình Thức Tiếp Nhận", s.headerStyle);
        createHeaderCell(h, 8, "Bác Sĩ Phụ Trách", s.headerStyle);
        createHeaderCell(h, 9, "Trạng Thái Khám", s.headerStyle);
        createHeaderCell(h, 10, "Triệu Chứng Ban Đầu", s.headerStyle);

        int r = 3;
        int stt = 1;

        for (Visit v : visits) {
            Row row = sheet.createRow(r++);
            createCell(row, 0, String.valueOf(stt++), s.textCenterStyle);
            createCell(row, 1, sanitize(v.getVisitCode()), s.textCenterStyle);
            createCell(row, 2, v.getCheckInAt() != null ? v.getCheckInAt().format(DATETIME_FMT) : "", s.dateCenterStyle);
            createCell(row, 3, sanitize(v.getBranch() != null ? v.getBranch().getBranchName() : ""), s.textStyle);
            createCell(row, 4, sanitize(v.getPatientProfile() != null ? v.getPatientProfile().getPatientCode() : ""), s.textCenterStyle);
            createCell(row, 5, sanitize(v.getPatientProfile() != null ? v.getPatientProfile().getFullName() : ""), s.textStyle);
            createCell(row, 6, sanitize(v.getPatientProfile() != null ? v.getPatientProfile().getPhoneNumber() : ""), s.textCenterStyle);
            createCell(row, 7, v.getVisitType() != null ? v.getVisitType().name() : "", s.textCenterStyle);
            createCell(row, 8, sanitize(v.getPrimaryDoctor() != null ? v.getPrimaryDoctor().getFullName() : "Chưa phân công"), s.textStyle);
            createCell(row, 9, v.getStatus() != null ? v.getStatus().name() : "", s.textCenterStyle);
            createCell(row, 10, sanitize(v.getInitialSymptoms()), s.textStyle);
        }

        autoSizeColumns(sheet, 11);
    }

    private int writeReportHeader(Sheet sheet, ExcelStyleRegistry s, String title, String branchName, LocalDate start, LocalDate end) {
        Row titleRow = sheet.createRow(0);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(title);
        titleCell.setCellStyle(s.titleStyle);

        Row clinicRow = sheet.createRow(1);
        clinicRow.createCell(0).setCellValue("HỆ THỐNG NHA KHOA CƯỜNG VIỆT (CV-DCMS)");
        clinicRow.getCell(0).setCellStyle(s.subtitleStyle);

        Row infoRow = sheet.createRow(2);
        infoRow.createCell(0).setCellValue("Cơ sở áp dụng: " + (branchName != null ? branchName : "Toàn chuỗi")
                + " | Kỳ báo cáo: " + start.format(DATE_FMT) + " đến " + end.format(DATE_FMT)
                + " | Thời điểm xuất: " + LocalDateTime.now().format(DATETIME_FMT));
        infoRow.getCell(0).setCellStyle(s.metaStyle);

        return 4;
    }

    private int writeMetricRow(Sheet sheet, ExcelStyleRegistry s, int r, String metricName, Object value, String unit, String desc) {
        Row row = sheet.createRow(r);
        createCell(row, 0, metricName, s.textStyle);
        if (value instanceof BigDecimal bd) {
            createNumericCell(row, 1, bd, s.currencyStyle);
        } else if (value instanceof Number num) {
            createNumericCell(row, 1, num.longValue(), s.integerStyle);
        } else {
            createCell(row, 1, String.valueOf(value), s.textStyle);
        }
        createCell(row, 2, unit, s.textCenterStyle);
        createCell(row, 3, desc, s.textStyle);
        return r + 1;
    }

    private void createHeaderCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private void createCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(style);
    }

    private void createNumericCell(Row row, int col, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value != null) {
            cell.setCellValue(value.doubleValue());
        } else {
            cell.setCellValue(0.0);
        }
        cell.setCellStyle(style);
    }

    private void createNumericCell(Row row, int col, long value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private BigDecimal toBigDecimal(Object obj) {
        if (obj == null) return BigDecimal.ZERO;
        if (obj instanceof BigDecimal bd) return bd;
        if (obj instanceof Number num) return BigDecimal.valueOf(num.doubleValue());
        try {
            return new BigDecimal(obj.toString());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private String sanitize(String val) {
        if (val == null) return "";
        val = val.trim();
        if (val.startsWith("=") || val.startsWith("+") || val.startsWith("-") || val.startsWith("@")) {
            return "'" + val;
        }
        return val;
    }

    private void autoSizeColumns(Sheet sheet, int colCount) {
        for (int i = 0; i < colCount; i++) {
            sheet.autoSizeColumn(i);
            int curWidth = sheet.getColumnWidth(i);
            sheet.setColumnWidth(i, Math.max(curWidth + 1200, 3500));
        }
    }

    private static class ExcelStyleRegistry {
        final CellStyle titleStyle;
        final CellStyle subtitleStyle;
        final CellStyle metaStyle;
        final CellStyle headerStyle;
        final CellStyle textStyle;
        final CellStyle textCenterStyle;
        final CellStyle dateCenterStyle;
        final CellStyle currencyStyle;
        final CellStyle integerStyle;
        final CellStyle percentStyle;
        final CellStyle totalHeaderStyle;
        final CellStyle totalCurrencyStyle;
        final CellStyle totalIntegerStyle;

        ExcelStyleRegistry(XSSFWorkbook wb) {
            DataFormat df = wb.createDataFormat();

            Font fontBase = wb.createFont();
            fontBase.setFontName("Segoe UI");
            fontBase.setFontHeightInPoints((short) 10);

            Font fontBold = wb.createFont();
            fontBold.setFontName("Segoe UI");
            fontBold.setFontHeightInPoints((short) 10);
            fontBold.setBold(true);

            Font fontTitle = wb.createFont();
            fontTitle.setFontName("Segoe UI");
            fontTitle.setFontHeightInPoints((short) 15);
            fontTitle.setBold(true);
            fontTitle.setColor(IndexedColors.DARK_TEAL.getIndex());

            Font fontSub = wb.createFont();
            fontSub.setFontName("Segoe UI");
            fontSub.setFontHeightInPoints((short) 11);
            fontSub.setBold(true);

            Font fontMeta = wb.createFont();
            fontMeta.setFontName("Segoe UI");
            fontMeta.setFontHeightInPoints((short) 9);
            fontMeta.setItalic(true);
            fontMeta.setColor(IndexedColors.GREY_50_PERCENT.getIndex());

            Font fontHeader = wb.createFont();
            fontHeader.setFontName("Segoe UI");
            fontHeader.setFontHeightInPoints((short) 10);
            fontHeader.setBold(true);
            fontHeader.setColor(IndexedColors.WHITE.getIndex());

            titleStyle = wb.createCellStyle();
            titleStyle.setFont(fontTitle);

            subtitleStyle = wb.createCellStyle();
            subtitleStyle.setFont(fontSub);

            metaStyle = wb.createCellStyle();
            metaStyle.setFont(fontMeta);

            headerStyle = wb.createCellStyle();
            headerStyle.setFont(fontHeader);
            headerStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte) 15, (byte) 118, (byte) 110}, null));
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            setBorder(headerStyle, BorderStyle.THIN);

            textStyle = wb.createCellStyle();
            textStyle.setFont(fontBase);
            setBorder(textStyle, BorderStyle.THIN);

            textCenterStyle = wb.createCellStyle();
            textCenterStyle.setFont(fontBase);
            textCenterStyle.setAlignment(HorizontalAlignment.CENTER);
            setBorder(textCenterStyle, BorderStyle.THIN);

            dateCenterStyle = wb.createCellStyle();
            dateCenterStyle.setFont(fontBase);
            dateCenterStyle.setAlignment(HorizontalAlignment.CENTER);
            setBorder(dateCenterStyle, BorderStyle.THIN);

            currencyStyle = wb.createCellStyle();
            currencyStyle.setFont(fontBase);
            currencyStyle.setDataFormat(df.getFormat("#,##0"));
            currencyStyle.setAlignment(HorizontalAlignment.RIGHT);
            setBorder(currencyStyle, BorderStyle.THIN);

            integerStyle = wb.createCellStyle();
            integerStyle.setFont(fontBase);
            integerStyle.setDataFormat(df.getFormat("#,##0"));
            integerStyle.setAlignment(HorizontalAlignment.RIGHT);
            setBorder(integerStyle, BorderStyle.THIN);

            percentStyle = wb.createCellStyle();
            percentStyle.setFont(fontBase);
            percentStyle.setDataFormat(df.getFormat("0.0%"));
            percentStyle.setAlignment(HorizontalAlignment.RIGHT);
            setBorder(percentStyle, BorderStyle.THIN);

            totalHeaderStyle = wb.createCellStyle();
            totalHeaderStyle.setFont(fontBold);
            totalHeaderStyle.setFillForegroundColor(IndexedColors.LIGHT_TURQUOISE.getIndex());
            totalHeaderStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            setBorder(totalHeaderStyle, BorderStyle.MEDIUM);

            totalCurrencyStyle = wb.createCellStyle();
            totalCurrencyStyle.setFont(fontBold);
            totalCurrencyStyle.setDataFormat(df.getFormat("#,##0"));
            totalCurrencyStyle.setFillForegroundColor(IndexedColors.LIGHT_TURQUOISE.getIndex());
            totalCurrencyStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            totalCurrencyStyle.setAlignment(HorizontalAlignment.RIGHT);
            setBorder(totalCurrencyStyle, BorderStyle.MEDIUM);

            totalIntegerStyle = wb.createCellStyle();
            totalIntegerStyle.setFont(fontBold);
            totalIntegerStyle.setDataFormat(df.getFormat("#,##0"));
            totalIntegerStyle.setFillForegroundColor(IndexedColors.LIGHT_TURQUOISE.getIndex());
            totalIntegerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            totalIntegerStyle.setAlignment(HorizontalAlignment.RIGHT);
            setBorder(totalIntegerStyle, BorderStyle.MEDIUM);
        }

        private void setBorder(CellStyle cs, BorderStyle b) {
            cs.setBorderTop(b);
            cs.setBorderBottom(b);
            cs.setBorderLeft(b);
            cs.setBorderRight(b);
        }
    }
}
