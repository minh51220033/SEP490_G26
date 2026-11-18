package com.cvdcms.service.impl;

import com.cvdcms.DTO.PatientDebtSummaryDTO;
import com.cvdcms.DTO.report.*;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.BranchTaxConfig;
import com.cvdcms.entity.Invoice;
import com.cvdcms.entity.OperatingExpense;
import com.cvdcms.entity.Payment;
import com.cvdcms.entity.Refund;
import com.cvdcms.entity.Visit;
import com.cvdcms.entity.enums.AppointmentSource;
import com.cvdcms.entity.enums.PaymentMethod;
import com.cvdcms.entity.enums.PaymentType;
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
    private final PaymentAllocationRepository paymentAllocationRepository;
    private final BranchRepository branchRepository;

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
                null,
                null,
                null
        );
    }

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
        this(
                invoiceRepository,
                paymentRepository,
                refundRepository,
                operatingExpenseRepository,
                visitRepository,
                appointmentRepository,
                taxCalculationService,
                payrollPeriodRepository,
                patientProfileRepository,
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
            PatientProfileRepository patientProfileRepository,
            PaymentAllocationRepository paymentAllocationRepository,
            BranchRepository branchRepository
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
        this.paymentAllocationRepository = paymentAllocationRepository;
        this.branchRepository = branchRepository;
    }

    @Override
    public ReportDashboardDTO getDashboardReport(Collection<Long> branchIds, LocalDate startDate, LocalDate endDate, String branchName) {
        return getDashboardReport(branchIds, startDate, endDate, branchName, null);
    }

    @Override
    public ReportDashboardDTO getDashboardReport(Collection<Long> branchIds, LocalDate startDate, LocalDate endDate, String branchName, BigDecimal taxRate) {
        BigDecimal validatedTaxRate = validateRequestedTaxRate(taxRate);
        LocalDateTime startDt = startDate.atStartOfDay();
        LocalDateTime endDt = endDate.atTime(LocalTime.MAX);

        ReportDashboardDTO dto = new ReportDashboardDTO();
        dto.setSelectedBranchName(branchName != null ? branchName : "Tất cả chi nhánh");
        dto.setFilterStartDate(startDate);
        dto.setFilterEndDate(endDate);

        // 1. Thực thu theo dòng tiền thực tế (Cash/Bank/POS - Refunds)
        BigDecimal grossRealRevenue = toBigDecimal(paymentRepository.sumRealRevenueByBranches(branchIds, startDt, endDt));
        BigDecimal totalRefund = toBigDecimal(refundRepository.sumRefundAmountByBranches(branchIds, startDt, endDt));
        BigDecimal netRealRevenue = grossRealRevenue.subtract(totalRefund);
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

        // 3. Chi phí vận hành & Chênh lệch thu chi vận hành (gồm chi phí vận hành + BHXH 21.5% phần DN chịu từ các kỳ lương đã chi trả theo ngày chi)
        BigDecimal totalExpense = toBigDecimal(operatingExpenseRepository.sumActiveExpenses(branchIds, startDate, endDate));
        if (payrollPeriodRepository != null) {
            BigDecimal employerInsurance = toBigDecimal(payrollPeriodRepository.sumPaidEmployerInsuranceByPaidAt(
                    branchIds, startDt, endDt
            ));
            totalExpense = totalExpense.add(employerInsurance);
        }
        dto.setTotalExpenseAmount(totalExpense);
        dto.setOperatingMargin(netRealRevenue.subtract(dto.getTotalExpenseAmount()));

        // Thuế & Lợi nhuận sau thuế theo cấu hình pháp nhân / phân loại căn cứ
        BigDecimal fallbackDepositWallet = toBigDecimal(paymentRepository.sumDepositWalletUsedByBranches(branchIds, startDt, endDt));
        BigDecimal fallbackAdvanceDeposit = toBigDecimal(paymentRepository.sumAdvanceDepositByBranches(branchIds, startDt, endDt));
        TaxComputationSummary taxSummary = computeTaxComputationSummary(
                branchIds, startDate, endDate, validatedTaxRate,
                grossRealRevenue, fallbackAdvanceDeposit, fallbackDepositWallet,
                totalRefund, totalExpense, dto.getOperatingMargin()
        );
        applyTaxSummaryToDashboard(dto, taxSummary);

        // Thuế TNCN đã khấu trừ của nhân viên từ các bảng lương đã chi trả theo ngày chi
        if (payrollPeriodRepository != null) {
            BigDecimal paidPit = payrollPeriodRepository.sumPaidPitByPaidAt(
                    branchIds, startDt, endDt
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

    private static class BranchTaxAccum {
        BigDecimal directInvoiceCashIn = BigDecimal.ZERO;
        BigDecimal advanceDepositInflow = BigDecimal.ZERO;
        BigDecimal depositWalletUsed = BigDecimal.ZERO;
        BigDecimal invoiceRefundAmount = BigDecimal.ZERO;
        BigDecimal walletRefundAmount = BigDecimal.ZERO;
        BigDecimal operatingExpense = BigDecimal.ZERO;
        BigDecimal serviceBase = BigDecimal.ZERO;
        BigDecimal medicineBase = BigDecimal.ZERO;
        BigDecimal surchargeBase = BigDecimal.ZERO;
        BigDecimal unclassifiedInvoiceBase = BigDecimal.ZERO;

        BigDecimal netCash() {
            return directInvoiceCashIn
                    .add(advanceDepositInflow)
                    .subtract(invoiceRefundAmount)
                    .subtract(walletRefundAmount);
        }

        BigDecimal operatingMargin() {
            return netCash().subtract(operatingExpense);
        }

        BigDecimal netClassifiedRevenueBase() {
            return serviceBase.max(BigDecimal.ZERO)
                    .add(medicineBase.max(BigDecimal.ZERO))
                    .add(surchargeBase.max(BigDecimal.ZERO));
        }

        BigDecimal unsettledAdvanceDeposit() {
            return advanceDepositInflow
                    .subtract(depositWalletUsed)
                    .subtract(walletRefundAmount)
                    .max(BigDecimal.ZERO);
        }

        boolean hasAnyFinancialActivity() {
            return directInvoiceCashIn.compareTo(BigDecimal.ZERO) != 0
                    || advanceDepositInflow.compareTo(BigDecimal.ZERO) != 0
                    || depositWalletUsed.compareTo(BigDecimal.ZERO) != 0
                    || invoiceRefundAmount.compareTo(BigDecimal.ZERO) != 0
                    || walletRefundAmount.compareTo(BigDecimal.ZERO) != 0
                    || operatingExpense.compareTo(BigDecimal.ZERO) != 0
                    || serviceBase.compareTo(BigDecimal.ZERO) != 0
                    || medicineBase.compareTo(BigDecimal.ZERO) != 0
                    || surchargeBase.compareTo(BigDecimal.ZERO) != 0
                    || unclassifiedInvoiceBase.compareTo(BigDecimal.ZERO) != 0;
        }
    }

    private record BranchTaxOutcome(
            Long branchId,
            String branchName,
            boolean configured,
            boolean householdRegime,
            BigDecimal displayRate,
            BigDecimal estimatedTax,
            String basisNote
    ) {}

    private record TaxComputationSummary(
            boolean configured,
            BigDecimal displayTaxRate,
            BigDecimal estimatedTaxAmount,
            BigDecimal netProfitAfterTax,
            String regimeName,
            String calculationBasis,
            BigDecimal serviceTaxBase,
            BigDecimal medicineTaxBase,
            BigDecimal surchargeTaxBase,
            BigDecimal unclassifiedInvoiceBase,
            BigDecimal advanceDepositInflow,
            BigDecimal depositWalletUsed,
            BigDecimal invoiceRefundAmount,
            BigDecimal walletRefundAmount,
            BigDecimal unsettledAdvanceDeposit
    ) {}

    private record BranchInvoiceKey(Long branchId, Long invoiceId) {}

    private BigDecimal validateRequestedTaxRate(BigDecimal requestedTaxRate) {
        if (requestedTaxRate == null) {
            return null;
        }
        if (requestedTaxRate.compareTo(BigDecimal.ZERO) < 0 || requestedTaxRate.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("Thuế suất phải nằm trong khoảng 0% đến 100%");
        }
        return requestedTaxRate;
    }

    private TaxComputationSummary computeTaxComputationSummary(
            Collection<Long> branchIds,
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal validatedRequestedRate,
            BigDecimal fallbackGrossCash,
            BigDecimal fallbackAdvanceDeposit,
            BigDecimal fallbackDepositWallet,
            BigDecimal fallbackTotalRefund,
            BigDecimal fallbackTotalExpense,
            BigDecimal aggregateOperatingMargin
    ) {
        LocalDateTime startDt = startDate.atStartOfDay();
        LocalDateTime endDt = endDate.atTime(LocalTime.MAX);

        Map<Long, BranchTaxAccum> branchMap = new LinkedHashMap<>();
        boolean hasDetailedRows = collectBranchFinancialActivity(branchIds, startDate, endDate, startDt, endDt, branchMap);

        Map<Long, String> branchNames = resolveBranchNamesMap();
        Set<Long> targetBranchIds = resolveTargetBranchIds(branchIds, branchMap, branchNames);

        if (!hasDetailedRows && targetBranchIds.size() <= 1) {
            Long fallbackBranchId = targetBranchIds.isEmpty() ? null : targetBranchIds.iterator().next();
            BranchTaxAccum acc = new BranchTaxAccum();
            acc.advanceDepositInflow = nz(fallbackAdvanceDeposit);
            acc.depositWalletUsed = nz(fallbackDepositWallet);
            acc.directInvoiceCashIn = nz(fallbackGrossCash).subtract(acc.advanceDepositInflow);
            acc.invoiceRefundAmount = nz(fallbackTotalRefund);
            acc.operatingExpense = nz(fallbackTotalExpense);
            return evaluateSingleBranchFallback(
                    fallbackBranchId, branchNames, acc, endDate, validatedRequestedRate, aggregateOperatingMargin
            );
        }

        List<BranchTaxOutcome> outcomes = new ArrayList<>();
        for (Long bid : targetBranchIds) {
            BranchTaxAccum acc = branchMap.getOrDefault(bid, new BranchTaxAccum());
            String bName = branchNames.getOrDefault(bid, "Chi nhánh #" + bid);
            outcomes.add(computeSingleBranchTax(bid, bName, acc, endDate, validatedRequestedRate));
        }

        return summarizeBranchOutcomes(outcomes, branchMap.values(), aggregateOperatingMargin, validatedRequestedRate, targetBranchIds.size() <= 1);
    }

    private boolean collectBranchFinancialActivity(
            Collection<Long> branchIds,
            LocalDate startDate,
            LocalDate endDate,
            LocalDateTime startDt,
            LocalDateTime endDt,
            Map<Long, BranchTaxAccum> branchMap
    ) {
        boolean hasAny = false;

        List<Object[]> paymentRows = safeList(paymentRepository.sumPaymentsByBranchTypeAndMethod(branchIds, startDt, endDt));
        for (Object[] row : paymentRows) {
            Long bid = toLong(row[0]);
            if (bid == null) continue;
            hasAny = true;
            BranchTaxAccum acc = branchMap.computeIfAbsent(bid, k -> new BranchTaxAccum());
            PaymentType pType = (PaymentType) row[1];
            PaymentMethod pMethod = (PaymentMethod) row[2];
            BigDecimal amt = toBigDecimal(row[3]);
            if (pType == PaymentType.ADVANCE_DEPOSIT) {
                if (pMethod != PaymentMethod.DEPOSIT_WALLET) {
                    acc.advanceDepositInflow = acc.advanceDepositInflow.add(amt);
                }
            } else if (pType == PaymentType.PAYMENT) {
                if (pMethod == PaymentMethod.DEPOSIT_WALLET) {
                    acc.depositWalletUsed = acc.depositWalletUsed.add(amt);
                } else {
                    acc.directInvoiceCashIn = acc.directInvoiceCashIn.add(amt);
                }
            }
        }

        if (paymentAllocationRepository != null) {
            List<Object[]> allocRows = safeList(paymentAllocationRepository.sumAllocatedByBranchAndItemType(branchIds, startDt, endDt));
            for (Object[] row : allocRows) {
                Long bid = toLong(row[0]);
                if (bid == null) continue;
                hasAny = true;
                BranchTaxAccum acc = branchMap.computeIfAbsent(bid, k -> new BranchTaxAccum());
                String itemType = row[1] != null ? row[1].toString().trim().toUpperCase() : "";
                BigDecimal amt = toBigDecimal(row[2]);
                addAllocationBucket(acc, itemType, amt);
            }
        }

        List<Payment> unallocatedPayments = safeList(paymentRepository.findUnallocatedPaymentsForPeriod(branchIds, startDt, endDt));
        for (Payment p : unallocatedPayments) {
            Long bid = p.getBranch() != null ? p.getBranch().getBranchId() : null;
            if (bid == null) continue;
            hasAny = true;
            BranchTaxAccum acc = branchMap.computeIfAbsent(bid, k -> new BranchTaxAccum());
            allocateAmountByInvoice(acc, p.getInvoice(), nz(p.getAmount()), true);
        }

        // Tránh trừ trùng các khoản hoàn tiền hóa đơn đã giảm trừ trực tiếp trên payment_allocations trong kỳ
        Map<BranchInvoiceKey, BigDecimal> alreadyRolledBack = new HashMap<>();
        if (paymentAllocationRepository != null) {
            Map<BranchInvoiceKey, BigDecimal> allocPayMap = new HashMap<>();
            for (Object[] row : safeList(paymentRepository.sumAllocatedPaymentTotalsByBranchAndInvoice(branchIds, startDt, endDt))) {
                Long bid = toLong(row[0]);
                Long invId = toLong(row[1]);
                if (bid != null && invId != null) {
                    allocPayMap.put(new BranchInvoiceKey(bid, invId), toBigDecimal(row[2]));
                }
            }
            Map<BranchInvoiceKey, BigDecimal> remAllocMap = new HashMap<>();
            for (Object[] row : safeList(paymentAllocationRepository.sumAllocatedByBranchAndInvoice(branchIds, startDt, endDt))) {
                Long bid = toLong(row[0]);
                Long invId = toLong(row[1]);
                if (bid != null && invId != null) {
                    remAllocMap.put(new BranchInvoiceKey(bid, invId), toBigDecimal(row[2]));
                }
            }
            for (Map.Entry<BranchInvoiceKey, BigDecimal> entry : allocPayMap.entrySet()) {
                BigDecimal paidWithAlloc = entry.getValue();
                BigDecimal remainingAlloc = remAllocMap.getOrDefault(entry.getKey(), BigDecimal.ZERO);
                BigDecimal rolled = paidWithAlloc.subtract(remainingAlloc).max(BigDecimal.ZERO);
                if (rolled.compareTo(BigDecimal.ZERO) > 0) {
                    alreadyRolledBack.put(entry.getKey(), rolled);
                }
            }
        }

        List<Refund> periodRefunds = safeList(refundRepository.findAllRefundsForReport(branchIds, startDt, endDt));
        for (Refund r : periodRefunds) {
            Long bid = r.getBranch() != null ? r.getBranch().getBranchId() : null;
            if (bid == null) continue;
            hasAny = true;
            BranchTaxAccum acc = branchMap.computeIfAbsent(bid, k -> new BranchTaxAccum());
            BigDecimal refAmt = nz(r.getAmount());
            if (r.getInvoice() == null) {
                // Hoàn tiền từ số dư ví tạm ứng: không giảm trừ vào doanh thu dịch vụ/thuốc
                acc.walletRefundAmount = acc.walletRefundAmount.add(refAmt);
            } else {
                acc.invoiceRefundAmount = acc.invoiceRefundAmount.add(refAmt);
                BranchInvoiceKey key = new BranchInvoiceKey(bid, r.getInvoice().getInvoiceId());
                BigDecimal availableRolled = alreadyRolledBack.getOrDefault(key, BigDecimal.ZERO);
                BigDecimal consumedRolled = refAmt.min(availableRolled);
                if (consumedRolled.compareTo(BigDecimal.ZERO) > 0) {
                    alreadyRolledBack.put(key, availableRolled.subtract(consumedRolled));
                }
                BigDecimal extraToDeduct = refAmt.subtract(consumedRolled);
                if (extraToDeduct.compareTo(BigDecimal.ZERO) > 0) {
                    allocateAmountByInvoice(acc, r.getInvoice(), extraToDeduct, false);
                }
            }
        }

        for (Object[] row : safeList(operatingExpenseRepository.sumActiveExpensesByBranch(branchIds, startDate, endDate))) {
            Long bid = toLong(row[0]);
            if (bid == null) continue;
            hasAny = true;
            BranchTaxAccum acc = branchMap.computeIfAbsent(bid, k -> new BranchTaxAccum());
            acc.operatingExpense = acc.operatingExpense.add(toBigDecimal(row[1]));
        }

        if (payrollPeriodRepository != null) {
            for (Object[] row : safeList(payrollPeriodRepository.sumPaidEmployerInsuranceByBranchAndPaidAt(branchIds, startDt, endDt))) {
                Long bid = toLong(row[0]);
                if (bid == null) continue;
                hasAny = true;
                BranchTaxAccum acc = branchMap.computeIfAbsent(bid, k -> new BranchTaxAccum());
                acc.operatingExpense = acc.operatingExpense.add(toBigDecimal(row[1]));
            }
        }

        return hasAny;
    }

    private void addAllocationBucket(BranchTaxAccum acc, String itemType, BigDecimal amt) {
        switch (itemType) {
            case "SERVICE" -> acc.serviceBase = acc.serviceBase.add(amt);
            case "MEDICINE" -> acc.medicineBase = acc.medicineBase.add(amt);
            case "SURCHARGE" -> acc.surchargeBase = acc.surchargeBase.add(amt);
            default -> acc.unclassifiedInvoiceBase = acc.unclassifiedInvoiceBase.add(amt);
        }
    }

    private void allocateAmountByInvoice(BranchTaxAccum acc, Invoice inv, BigDecimal amount, boolean positive) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal signed = positive ? amount : amount.negate();
        if (inv == null) {
            acc.unclassifiedInvoiceBase = acc.unclassifiedInvoiceBase.add(signed);
            return;
        }
        BigDecimal rawSvc = nz(inv.getTotalServiceAmount());
        BigDecimal discount = nz(inv.getTotalDiscount());
        BigDecimal netSvc = rawSvc.subtract(discount).max(BigDecimal.ZERO);
        BigDecimal med = nz(inv.getTotalMedicineAmount()).max(BigDecimal.ZERO);
        BigDecimal surch = nz(inv.getSurchargeAmount()).max(BigDecimal.ZERO);
        BigDecimal denom = netSvc.add(med).add(surch);
        if (denom.compareTo(BigDecimal.ZERO) <= 0) {
            acc.unclassifiedInvoiceBase = acc.unclassifiedInvoiceBase.add(signed);
            return;
        }

        BigDecimal svcShare = BigDecimal.ZERO;
        BigDecimal medShare = BigDecimal.ZERO;
        BigDecimal surchShare = BigDecimal.ZERO;
        if (surch.compareTo(BigDecimal.ZERO) > 0) {
            svcShare = amount.multiply(netSvc).divide(denom, 2, RoundingMode.HALF_UP);
            medShare = amount.multiply(med).divide(denom, 2, RoundingMode.HALF_UP);
            surchShare = amount.subtract(svcShare).subtract(medShare);
        } else if (med.compareTo(BigDecimal.ZERO) > 0) {
            svcShare = amount.multiply(netSvc).divide(denom, 2, RoundingMode.HALF_UP);
            medShare = amount.subtract(svcShare);
        } else {
            svcShare = amount;
        }

        if (positive) {
            acc.serviceBase = acc.serviceBase.add(svcShare);
            acc.medicineBase = acc.medicineBase.add(medShare);
            acc.surchargeBase = acc.surchargeBase.add(surchShare);
        } else {
            acc.serviceBase = acc.serviceBase.subtract(svcShare);
            acc.medicineBase = acc.medicineBase.subtract(medShare);
            acc.surchargeBase = acc.surchargeBase.subtract(surchShare);
        }
    }

    private Map<Long, String> resolveBranchNamesMap() {
        Map<Long, String> names = new LinkedHashMap<>();
        if (branchRepository != null) {
            for (Branch b : safeList(branchRepository.findByIsActiveTrueOrderByBranchNameAsc())) {
                if (b != null && b.getBranchId() != null) {
                    names.put(b.getBranchId(), b.getBranchName());
                }
            }
        }
        return names;
    }

    private Set<Long> resolveTargetBranchIds(Collection<Long> branchIds, Map<Long, BranchTaxAccum> branchMap, Map<Long, String> branchNames) {
        Set<Long> target = new LinkedHashSet<>();
        if (branchIds != null && !branchIds.isEmpty()) {
            for (Long bid : branchIds) {
                if (bid != null) target.add(bid);
            }
        } else {
            target.addAll(branchNames.keySet());
        }
        target.addAll(branchMap.keySet());
        return target;
    }

    private TaxComputationSummary evaluateSingleBranchFallback(
            Long branchId,
            Map<Long, String> branchNames,
            BranchTaxAccum acc,
            LocalDate targetDate,
            BigDecimal validatedRequestedRate,
            BigDecimal aggregateOperatingMargin
    ) {
        String bName = branchId != null ? branchNames.getOrDefault(branchId, "Chi nhánh #" + branchId) : "Phạm vi báo cáo";
        BranchTaxOutcome outcome = computeSingleBranchTaxWithMargin(
                branchId, bName, acc, aggregateOperatingMargin, targetDate, validatedRequestedRate
        );
        BigDecimal estTax = outcome.estimatedTax();
        return new TaxComputationSummary(
                outcome.configured(),
                outcome.displayRate(),
                estTax,
                aggregateOperatingMargin.subtract(estTax),
                outcome.householdRegime()
                        ? "Hộ kinh doanh cá thể (Thuế khoán phân nhóm DV/Thuốc)"
                        : (outcome.configured() ? "Doanh nghiệp (TNDN tạm tính quản trị)" : "Doanh nghiệp (Chưa đủ cấu hình - Tạm tính quản trị)"),
                outcome.basisNote(),
                acc.serviceBase.max(BigDecimal.ZERO),
                acc.medicineBase.max(BigDecimal.ZERO),
                acc.surchargeBase.max(BigDecimal.ZERO),
                acc.unclassifiedInvoiceBase.max(BigDecimal.ZERO),
                acc.advanceDepositInflow,
                acc.depositWalletUsed,
                acc.invoiceRefundAmount,
                acc.walletRefundAmount,
                acc.unsettledAdvanceDeposit()
        );
    }

    private BranchTaxOutcome computeSingleBranchTax(
            Long branchId,
            String branchName,
            BranchTaxAccum acc,
            LocalDate targetDate,
            BigDecimal validatedRequestedRate
    ) {
        return computeSingleBranchTaxWithMargin(branchId, branchName, acc, acc.operatingMargin(), targetDate, validatedRequestedRate);
    }

    private BranchTaxOutcome computeSingleBranchTaxWithMargin(
            Long branchId,
            String branchName,
            BranchTaxAccum acc,
            BigDecimal branchOperatingMargin,
            LocalDate targetDate,
            BigDecimal validatedRequestedRate
    ) {
        Optional<BranchTaxConfig> configOpt = (branchId != null && branchId > 0 && taxCalculationService != null)
                ? taxCalculationService.getBranchTaxConfig(branchId, targetDate)
                : Optional.empty();

        BigDecimal svcAndSurchBase = acc.serviceBase.max(BigDecimal.ZERO).add(acc.surchargeBase.max(BigDecimal.ZERO));
        BigDecimal medBase = acc.medicineBase.max(BigDecimal.ZERO);
        BigDecimal unclassified = acc.unclassifiedInvoiceBase.max(BigDecimal.ZERO);
        BigDecimal unsettledAdv = acc.unsettledAdvanceDeposit();

        if (configOpt.isPresent()) {
            BranchTaxConfig config = configOpt.get();
            boolean isHousehold = "HOUSEHOLD".equalsIgnoreCase(config.getEntityType())
                    || "REVENUE_PERCENT".equalsIgnoreCase(config.getTaxRegime());

            if (isHousehold) {
                BigDecimal totalRevBase = svcAndSurchBase.add(medBase);
                if (validatedRequestedRate != null) {
                    boolean ok = unclassified.compareTo(BigDecimal.ZERO) == 0;
                    BigDecimal tax = totalRevBase.multiply(validatedRequestedRate)
                            .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
                    String note = branchName + ": HKD mô phỏng " + fmtRate(validatedRequestedRate) + "% trên doanh thu phân bổ ("
                            + fmtMoney(totalRevBase) + " đ -> thuế " + fmtMoney(tax) + " đ)"
                            + (ok ? "" : "; có " + fmtMoney(unclassified) + " đ chưa phân loại DV/Thuốc");
                    return new BranchTaxOutcome(branchId, branchName, ok, true, validatedRequestedRate, tax, note);
                }

                BigDecimal svcRate = config.getServiceTaxRate();
                BigDecimal medRate = config.getMedicineTaxRate();
                boolean missingSvc = svcAndSurchBase.compareTo(BigDecimal.ZERO) > 0 && svcRate == null;
                boolean missingMed = medBase.compareTo(BigDecimal.ZERO) > 0 && medRate == null;
                boolean bothMissing = svcRate == null && medRate == null;
                boolean hasUnclassified = unclassified.compareTo(BigDecimal.ZERO) > 0;
                boolean configured = !(missingSvc || missingMed || bothMissing || hasUnclassified);

                BigDecimal svcTax = (svcRate != null)
                        ? svcAndSurchBase.multiply(svcRate).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                BigDecimal medTax = (medRate != null)
                        ? medBase.multiply(medRate).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                BigDecimal tax = svcTax.add(medTax).setScale(0, RoundingMode.HALF_UP);

                BigDecimal displayRate;
                if (totalRevBase.compareTo(BigDecimal.ZERO) > 0) {
                    displayRate = tax.multiply(BigDecimal.valueOf(100))
                            .divide(totalRevBase, 2, RoundingMode.HALF_UP)
                            .stripTrailingZeros();
                } else {
                    displayRate = svcRate != null ? svcRate : (medRate != null ? medRate : BigDecimal.ZERO);
                }

                StringBuilder note = new StringBuilder(branchName).append(": HKD (");
                if (configured) {
                    note.append("DV ").append(fmtRate(svcRate != null ? svcRate : BigDecimal.ZERO))
                            .append("% x ").append(fmtMoney(svcAndSurchBase)).append(" đ; Thuốc ")
                            .append(fmtRate(medRate != null ? medRate : BigDecimal.ZERO))
                            .append("% x ").append(fmtMoney(medBase)).append(" đ");
                    if (unsettledAdv.compareTo(BigDecimal.ZERO) > 0) {
                        note.append("; tiền ứng trước chưa đối trừ ").append(fmtMoney(unsettledAdv)).append(" đ chưa tính vào DT khoán để tránh trùng khi cấn ví");
                    }
                    if (config.getLegalReference() != null && !config.getLegalReference().isBlank()) {
                        note.append(" | ").append(config.getLegalReference());
                    }
                } else {
                    note.append("CHƯA ĐỦ CĂN CỨ: ");
                    if (missingSvc || bothMissing) note.append("thiếu serviceTaxRate; ");
                    if (missingMed) note.append("thiếu medicineTaxRate; ");
                    if (hasUnclassified) note.append("có khoản thu HĐ chưa phân loại DV/Thuốc ").append(fmtMoney(unclassified)).append(" đ");
                }
                note.append(")");
                return new BranchTaxOutcome(branchId, branchName, configured, true, displayRate, tax, note.toString());
            }

            BigDecimal citRate = (validatedRequestedRate != null) ? validatedRequestedRate : config.getCitRate();
            if (citRate == null) {
                return new BranchTaxOutcome(
                        branchId,
                        branchName,
                        false,
                        false,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        branchName + ": Doanh nghiệp CHƯA ĐỦ CĂN CỨ (thiếu citRate hiệu lực)"
                );
            }
            BigDecimal taxableMargin = branchOperatingMargin.max(BigDecimal.ZERO);
            BigDecimal tax = taxableMargin.multiply(citRate).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
            StringBuilder note = new StringBuilder(branchName).append(": Doanh nghiệp TNDN ")
                    .append(fmtRate(citRate)).append("% trên thặng dư ")
                    .append(fmtMoney(taxableMargin)).append(" đ -> ").append(fmtMoney(tax)).append(" đ");
            if (acc.depositWalletUsed.compareTo(BigDecimal.ZERO) > 0 || acc.advanceDepositInflow.compareTo(BigDecimal.ZERO) > 0) {
                note.append(" (nạp ví ").append(fmtMoney(acc.advanceDepositInflow))
                        .append(" đ, cấn ví ").append(fmtMoney(acc.depositWalletUsed))
                        .append(" đ không đếm trùng)");
            }
            if (config.getLegalReference() != null && !config.getLegalReference().isBlank()) {
                note.append(" | ").append(config.getLegalReference());
            }
            return new BranchTaxOutcome(branchId, branchName, true, false, citRate, tax, note.toString());
        }

        // Thiếu BranchTaxConfig hiệu lực: luôn đánh dấu configured = false (chưa đủ căn cứ)
        BigDecimal provisionalRate = (validatedRequestedRate != null) ? validatedRequestedRate : BigDecimal.valueOf(20);
        BigDecimal taxableMargin = branchOperatingMargin.max(BigDecimal.ZERO);
        BigDecimal tax = taxableMargin.multiply(provisionalRate).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
        String basis = branchName + ": CHƯA ĐỦ CĂN CỨ CẤU HÌNH THUẾ (chưa có BranchTaxConfig hiệu lực ngày "
                + targetDate + "; tạm tính tham khảo " + fmtRate(provisionalRate) + "% trên thặng dư -> "
                + fmtMoney(tax) + " đ)";
        return new BranchTaxOutcome(branchId, branchName, false, false, provisionalRate, tax, basis);
    }

    private TaxComputationSummary summarizeBranchOutcomes(
            List<BranchTaxOutcome> outcomes,
            Collection<BranchTaxAccum> accums,
            BigDecimal totalOperatingMargin,
            BigDecimal validatedRequestedRate,
            boolean singleBranchScope
    ) {
        BigDecimal totalTax = BigDecimal.ZERO;
        boolean allConfigured = !outcomes.isEmpty();
        boolean anyHousehold = false;
        boolean anyEnterprise = false;
        Set<BigDecimal> distinctRates = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();

        for (BranchTaxOutcome o : outcomes) {
            totalTax = totalTax.add(o.estimatedTax());
            if (!o.configured()) {
                allConfigured = false;
            }
            if (o.householdRegime()) {
                anyHousehold = true;
            } else {
                anyEnterprise = true;
            }
            if (o.displayRate() != null) {
                distinctRates.add(o.displayRate().stripTrailingZeros());
            }
            notes.add(o.basisNote());
        }

        BigDecimal svcBase = BigDecimal.ZERO;
        BigDecimal medBase = BigDecimal.ZERO;
        BigDecimal surchBase = BigDecimal.ZERO;
        BigDecimal unclassifiedBase = BigDecimal.ZERO;
        BigDecimal advInflow = BigDecimal.ZERO;
        BigDecimal walletUsed = BigDecimal.ZERO;
        BigDecimal invRefund = BigDecimal.ZERO;
        BigDecimal walletRefund = BigDecimal.ZERO;
        BigDecimal unsettledAdv = BigDecimal.ZERO;

        for (BranchTaxAccum a : accums) {
            svcBase = svcBase.add(a.serviceBase.max(BigDecimal.ZERO));
            medBase = medBase.add(a.medicineBase.max(BigDecimal.ZERO));
            surchBase = surchBase.add(a.surchargeBase.max(BigDecimal.ZERO));
            unclassifiedBase = unclassifiedBase.add(a.unclassifiedInvoiceBase.max(BigDecimal.ZERO));
            advInflow = advInflow.add(a.advanceDepositInflow);
            walletUsed = walletUsed.add(a.depositWalletUsed);
            invRefund = invRefund.add(a.invoiceRefundAmount);
            walletRefund = walletRefund.add(a.walletRefundAmount);
            unsettledAdv = unsettledAdv.add(a.unsettledAdvanceDeposit());
        }

        BigDecimal displayRate;
        if (validatedRequestedRate != null) {
            displayRate = validatedRequestedRate;
        } else if (distinctRates.size() == 1) {
            displayRate = distinctRates.iterator().next();
        } else if (anyHousehold && !anyEnterprise) {
            BigDecimal totalRevBase = svcBase.add(medBase).add(surchBase);
            displayRate = totalRevBase.compareTo(BigDecimal.ZERO) > 0
                    ? totalTax.multiply(BigDecimal.valueOf(100)).divide(totalRevBase, 2, RoundingMode.HALF_UP).stripTrailingZeros()
                    : BigDecimal.ZERO;
        } else if (totalOperatingMargin.compareTo(BigDecimal.ZERO) > 0 && totalTax.compareTo(BigDecimal.ZERO) > 0) {
            displayRate = totalTax.multiply(BigDecimal.valueOf(100))
                    .divide(totalOperatingMargin, 2, RoundingMode.HALF_UP)
                    .stripTrailingZeros();
        } else {
            displayRate = distinctRates.isEmpty() ? BigDecimal.ZERO : distinctRates.iterator().next();
        }

        String regimeName;
        if (!allConfigured && anyHousehold && anyEnterprise) {
            regimeName = "Tổng hợp đa chế độ (Có chi nhánh thiếu cấu hình thuế)";
        } else if (!allConfigured && singleBranchScope) {
            regimeName = anyHousehold ? "Hộ kinh doanh (Thiếu căn cứ cấu hình)" : "Doanh nghiệp (Chưa đủ cấu hình thuế)";
        } else if (!allConfigured) {
            regimeName = "Tổng hợp đa chi nhánh (Chưa đủ cấu hình thuế)";
        } else if (anyHousehold && anyEnterprise) {
            regimeName = "Tổng hợp đa chế độ (Hộ KD & Doanh nghiệp)";
        } else if (anyHousehold) {
            regimeName = "Hộ kinh doanh cá thể (Thuế khoán phân nhóm DV/Thuốc)";
        } else {
            regimeName = singleBranchScope
                    ? "Doanh nghiệp (TNDN tạm tính quản trị)"
                    : "Tổng hợp nhiều chi nhánh (TNDN theo cấu hình)";
        }

        return new TaxComputationSummary(
                allConfigured,
                displayRate,
                totalTax,
                totalOperatingMargin.subtract(totalTax),
                regimeName,
                String.join(" | ", notes),
                svcBase,
                medBase,
                surchBase,
                unclassifiedBase,
                advInflow,
                walletUsed,
                invRefund,
                walletRefund,
                unsettledAdv
        );
    }

    private void applyTaxSummaryToDashboard(ReportDashboardDTO dto, TaxComputationSummary s) {
        dto.setTaxConfigured(s.configured());
        dto.setTaxRate(s.displayTaxRate());
        dto.setEstimatedTaxAmount(s.estimatedTaxAmount());
        dto.setNetProfitAfterTax(s.netProfitAfterTax());
        dto.setTaxRegimeName(s.regimeName());
        dto.setTaxCalculationBasis(s.calculationBasis());
        dto.setServiceTaxBase(s.serviceTaxBase());
        dto.setMedicineTaxBase(s.medicineTaxBase());
        dto.setSurchargeTaxBase(s.surchargeTaxBase());
        dto.setUnclassifiedInvoiceBase(s.unclassifiedInvoiceBase());
        dto.setAdvanceDepositInflow(s.advanceDepositInflow());
        dto.setDepositWalletUsed(s.depositWalletUsed());
        dto.setInvoiceRefundAmount(s.invoiceRefundAmount());
        dto.setWalletRefundAmount(s.walletRefundAmount());
        dto.setUnsettledAdvanceDeposit(s.unsettledAdvanceDeposit());
    }

    private void applyTaxSummaryToFinancial(FinancialReportDTO dto, TaxComputationSummary s) {
        dto.setTaxConfigured(s.configured());
        dto.setTaxRate(s.displayTaxRate());
        dto.setEstimatedTaxAmount(s.estimatedTaxAmount());
        dto.setNetProfitAfterTax(s.netProfitAfterTax());
        dto.setTaxRegimeName(s.regimeName());
        dto.setTaxCalculationBasis(s.calculationBasis());
        dto.setServiceTaxBase(s.serviceTaxBase());
        dto.setMedicineTaxBase(s.medicineTaxBase());
        dto.setSurchargeTaxBase(s.surchargeTaxBase());
        dto.setUnclassifiedInvoiceBase(s.unclassifiedInvoiceBase());
        dto.setInvoiceRefundAmount(s.invoiceRefundAmount());
        dto.setWalletRefundAmount(s.walletRefundAmount());
        dto.setUnsettledAdvanceDeposit(s.unsettledAdvanceDeposit());
    }

    private <T> List<T> safeList(List<T> list) {
        return list != null ? list : List.of();
    }

    private BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private Long toLong(Object obj) {
        if (obj instanceof Number num) {
            return num.longValue();
        }
        return null;
    }

    private String fmtRate(BigDecimal rate) {
        return rate != null ? rate.stripTrailingZeros().toPlainString() : "0";
    }

    private String fmtMoney(BigDecimal amt) {
        return String.format(Locale.US, "%,.0f", amt != null ? amt : BigDecimal.ZERO);
    }

    @Override
    public FinancialReportDTO getFinancialReport(Collection<Long> branchIds, LocalDate startDate, LocalDate endDate) {
        return getFinancialReport(branchIds, startDate, endDate, null);
    }

    @Override
    public FinancialReportDTO getFinancialReport(Collection<Long> branchIds, LocalDate startDate, LocalDate endDate, BigDecimal taxRate) {
        BigDecimal validatedTaxRate = validateRequestedTaxRate(taxRate);
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

        // 2. Dòng tiền theo phương thức thanh toán (chỉ tính PaymentType.PAYMENT cho các phương thức thanh toán viện phí, không cộng trùng khoản nạp cọc ADVANCE_DEPOSIT)
        dto.setCashAmount(toBigDecimal(paymentRepository.sumAmountByMethodAndBranches(branchIds, PaymentMethod.CASH, PaymentType.PAYMENT, startDt, endDt)));
        dto.setBankTransferAmount(toBigDecimal(paymentRepository.sumAmountByMethodAndBranches(branchIds, PaymentMethod.BANK_TRANSFER, PaymentType.PAYMENT, startDt, endDt)));
        dto.setPosCardAmount(toBigDecimal(paymentRepository.sumAmountByMethodAndBranches(branchIds, PaymentMethod.POS_CARD, PaymentType.PAYMENT, startDt, endDt)));
        dto.setDepositWalletAmount(toBigDecimal(paymentRepository.sumDepositWalletUsedByBranches(branchIds, startDt, endDt)));
        dto.setNewDepositAmount(toBigDecimal(paymentRepository.sumAdvanceDepositByBranches(branchIds, startDt, endDt)));

        // Thực thu theo dòng tiền thực tế = Cash + Bank + POS + NewDeposit (không tính thanh toán từ ví cọc)
        BigDecimal grossCollected = dto.getCashAmount().add(dto.getBankTransferAmount())
                .add(dto.getPosCardAmount()).add(dto.getNewDepositAmount());
        dto.setTotalCollected(grossCollected);

        // 3. Hoàn tiền & Chi phí (gồm chi phí vận hành + BHXH 21.5% phần DN chịu từ các kỳ lương đã chi trả)
        dto.setTotalRefundAmount(toBigDecimal(refundRepository.sumRefundAmountByBranches(branchIds, startDt, endDt)));
        dto.setNetRevenue(grossCollected.subtract(dto.getTotalRefundAmount()));

        BigDecimal activeExpenses = toBigDecimal(operatingExpenseRepository.sumActiveExpenses(branchIds, startDate, endDate));
        if (payrollPeriodRepository != null) {
            BigDecimal employerInsurance = toBigDecimal(payrollPeriodRepository.sumPaidEmployerInsuranceByPaidAt(
                    branchIds, startDt, endDt
            ));
            activeExpenses = activeExpenses.add(employerInsurance);
        }
        dto.setOperatingExpenses(activeExpenses);
        dto.setOperatingMargin(dto.getNetRevenue().subtract(dto.getOperatingExpenses()));

        // Thuế & Lợi nhuận sau thuế theo cấu hình pháp nhân / phân loại căn cứ
        TaxComputationSummary taxSummary = computeTaxComputationSummary(
                branchIds, startDate, endDate, validatedTaxRate,
                grossCollected, dto.getNewDepositAmount(), dto.getDepositWalletAmount(),
                dto.getTotalRefundAmount(), activeExpenses, dto.getOperatingMargin()
        );
        applyTaxSummaryToFinancial(dto, taxSummary);

        // Thuế TNCN đã khấu trừ của nhân viên từ các bảng lương đã chi trả theo ngày chi
        if (payrollPeriodRepository != null) {
            BigDecimal paidPit = payrollPeriodRepository.sumPaidPitByPaidAt(
                    branchIds, startDt, endDt
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

        // 2. Phân bổ thực thu thanh toán theo ngày (đã trừ hoàn tiền trong ngày để khớp KPI thực thu ròng)
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
        List<Object[]> refundAmounts = refundRepository.findDailyRefundsForPeriod(branchIds, startDt, endDt);
        if (refundAmounts != null) {
            for (Object[] r : refundAmounts) {
                if (r[0] != null) {
                    LocalDate d = ((LocalDateTime) r[0]).toLocalDate();
                    BigDecimal amt = toBigDecimal(r[1]);
                    DailyRevenueStat stat = map.get(d);
                    if (stat != null) {
                        stat.setRealRevenue(stat.getRealRevenue().subtract(amt));
                    }
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
        return exportReportToExcel(reportType, branchIds, startDate, endDate, branchName, null);
    }

    @Override
    public byte[] exportReportToExcel(String reportType, Collection<Long> branchIds, LocalDate startDate, LocalDate endDate, String branchName, BigDecimal taxRate) {
        BigDecimal validatedTaxRate = validateRequestedTaxRate(taxRate);
        LocalDateTime startDt = startDate.atStartOfDay();
        LocalDateTime endDt = endDate.atTime(LocalTime.MAX);

        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            ExcelStyleRegistry styles = new ExcelStyleRegistry(workbook);

            String safeType = (reportType != null) ? reportType.trim().toUpperCase() : "OVERVIEW";

            switch (safeType) {
                case "FINANCIAL" -> {
                    FinancialReportDTO financialDTO = getFinancialReport(branchIds, startDate, endDate, validatedTaxRate);
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
                    ReportDashboardDTO dashboardDTO = getDashboardReport(branchIds, startDate, endDate, branchName, validatedTaxRate);
                    buildDashboardSummarySheet(workbook, styles, dashboardDTO, branchName, startDate, endDate);
                    FinancialReportDTO financialDTO = getFinancialReport(branchIds, startDate, endDate, validatedTaxRate);
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
        String taxStatusNote = (dto.isTaxConfigured() ? "" : "[CHƯA ĐỦ CĂN CỨ] ") + dto.getTaxRegimeName() + " | " + dto.getTaxCalculationBasis();
        r = writeMetricRow(sheet, s, r, "Nghĩa vụ Thuế đơn vị tạm tính (" + dto.getTaxRate() + "%)", dto.getEstimatedTaxAmount(), "VNĐ", taxStatusNote);
        r = writeMetricRow(sheet, s, r, "   - Căn cứ Doanh thu Dịch vụ (đã phân bổ)", dto.getServiceTaxBase(), "VNĐ", "Dịch vụ đã thanh toán/cấn ví sau trừ hoàn HĐ");
        r = writeMetricRow(sheet, s, r, "   - Căn cứ Doanh thu Thuốc (đã phân bổ)", dto.getMedicineTaxBase(), "VNĐ", "Thuốc đã thanh toán/cấn ví sau trừ hoàn HĐ");
        r = writeMetricRow(sheet, s, r, "   - Căn cứ Phụ thu & Tiền cấn ví (DEPOSIT_WALLET)", dto.getSurchargeTaxBase().add(dto.getDepositWalletUsed()), "VNĐ", "Phụ thu (" + fmtMoney(dto.getSurchargeTaxBase()) + " đ) | Cấn ví (" + fmtMoney(dto.getDepositWalletUsed()) + " đ - không đếm trùng nạp ví)");
        r = writeMetricRow(sheet, s, r, "   - Nạp ví mới / Hoàn HĐ / Hoàn ví", dto.getAdvanceDepositInflow(), "VNĐ", "Hoàn HĐ: " + fmtMoney(dto.getInvoiceRefundAmount()) + " đ | Hoàn ví: " + fmtMoney(dto.getWalletRefundAmount()) + " đ | Chưa đối trừ: " + fmtMoney(dto.getUnsettledAdvanceDeposit()) + " đ");
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
        r = writeMetricRow(sheet, s, r, "2. Tiền thực thu từ khách hàng", dto.getTotalCollected(), "VNĐ", "Tiền mặt + Chuyển khoản + POS (đã bao gồm nạp cọc mới, không tính ví cọc)");
        r = writeMetricRow(sheet, s, r, "   - Thu bằng Tiền mặt (CASH)", dto.getCashAmount(), "VNĐ", "Dòng tiền mặt vào quỹ phòng khám");
        r = writeMetricRow(sheet, s, r, "   - Thu qua Chuyển khoản (BANK_TRANSFER)", dto.getBankTransferAmount(), "VNĐ", "Tiền vào tài khoản ngân hàng");
        r = writeMetricRow(sheet, s, r, "   - Thu qua Thẻ POS (POS_CARD)", dto.getPosCardAmount(), "VNĐ", "Quẹt thẻ máy POS");
        r = writeMetricRow(sheet, s, r, "   - Tiền nạp tạm ứng mới (ADVANCE_DEPOSIT)", dto.getNewDepositAmount(), "VNĐ", "Khách nạp cọc vào quỹ điều trị");
        r = writeMetricRow(sheet, s, r, "   - Thanh toán bằng ví cọc (DEPOSIT_WALLET)", dto.getDepositWalletAmount(), "VNĐ", "Trừ từ số dư ví cọc đã nạp (không tính trùng dòng tiền)");

        // 3. Hoàn trả & Chi phí
        r = writeMetricRow(sheet, s, r, "3. Tiền hoàn trả cho bệnh nhân", dto.getTotalRefundAmount(), "VNĐ", "Hoàn hóa đơn (" + fmtMoney(dto.getInvoiceRefundAmount()) + " đ) + Hoàn số dư ví (" + fmtMoney(dto.getWalletRefundAmount()) + " đ)");
        r = writeMetricRow(sheet, s, r, "4. Thực thu thuần sau hoàn trả", dto.getNetRevenue(), "VNĐ", "= Tổng thực thu (2) - Tiền hoàn trả (3)");
        r = writeMetricRow(sheet, s, r, "5. Chi phí vận hành hợp lệ", dto.getOperatingExpenses(), "VNĐ", "Chi phí mặt bằng, điện nước, lương nhân sự...");
        r = writeMetricRow(sheet, s, r, "6. Chênh lệch Thu - Chi vận hành (Lợi nhuận trước thuế)", dto.getOperatingMargin(), "VNĐ", "= Thực thu thuần (4) - Chi phí vận hành (5)");
        String finTaxStatus = (dto.isTaxConfigured() ? "" : "[CHƯA ĐỦ CĂN CỨ] ") + dto.getTaxRegimeName() + " | " + dto.getTaxCalculationBasis();
        r = writeMetricRow(sheet, s, r, "7. Nghĩa vụ Thuế đơn vị tạm tính (" + dto.getTaxRate() + "%)", dto.getEstimatedTaxAmount(), "VNĐ", finTaxStatus);
        r = writeMetricRow(sheet, s, r, "   - Căn cứ phân bổ: Dịch vụ", dto.getServiceTaxBase(), "VNĐ", "Thanh toán DV trực tiếp + cấn ví sau trừ hoàn HĐ");
        r = writeMetricRow(sheet, s, r, "   - Căn cứ phân bổ: Thuốc", dto.getMedicineTaxBase(), "VNĐ", "Thanh toán thuốc trực tiếp + cấn ví sau trừ hoàn HĐ");
        r = writeMetricRow(sheet, s, r, "   - Căn cứ phân bổ: Phụ thu", dto.getSurchargeTaxBase(), "VNĐ", "Phụ thu hóa đơn đã phân bổ (khoản ứng trước chưa đối trừ: " + fmtMoney(dto.getUnsettledAdvanceDeposit()) + " đ)");
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
