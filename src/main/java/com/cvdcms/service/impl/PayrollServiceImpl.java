package com.cvdcms.service.impl;

import com.cvdcms.DTO.PayrollItemUpdateDTO;
import com.cvdcms.DTO.PayrollPeriodCreateDTO;
import com.cvdcms.DTO.PitCalculationResult;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.repository.ServiceCommissionRepository;
import com.cvdcms.service.PayrollService;
import com.cvdcms.service.TaxCalculationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

@Service
@Transactional
public class PayrollServiceImpl implements PayrollService {

    private final PayrollPeriodRepository payrollPeriodRepository;
    private final PayrollItemRepository payrollItemRepository;
    private final SalaryAdvanceRepository salaryAdvanceRepository;
    private final SalaryAdvanceAllocationRepository salaryAdvanceAllocationRepository;
    private final AttendanceRecordRepository attendanceRecordRepository;
    private final OperatingExpenseRepository operatingExpenseRepository;
    private final ServiceCommissionRepository serviceCommissionRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;
    private final TaxCalculationService taxCalculationService;

    public PayrollServiceImpl(
            PayrollPeriodRepository payrollPeriodRepository,
            PayrollItemRepository payrollItemRepository,
            SalaryAdvanceRepository salaryAdvanceRepository,
            SalaryAdvanceAllocationRepository salaryAdvanceAllocationRepository,
            AttendanceRecordRepository attendanceRecordRepository,
            OperatingExpenseRepository operatingExpenseRepository,
            ServiceCommissionRepository serviceCommissionRepository,
            StaffProfileRepository staffProfileRepository,
            StaffBranchAssignmentRepository staffBranchAssignmentRepository,
            BranchRepository branchRepository,
            UserRepository userRepository
    ) {
        this(
                payrollPeriodRepository,
                payrollItemRepository,
                salaryAdvanceRepository,
                salaryAdvanceAllocationRepository,
                attendanceRecordRepository,
                operatingExpenseRepository,
                serviceCommissionRepository,
                staffProfileRepository,
                staffBranchAssignmentRepository,
                branchRepository,
                userRepository,
                null
        );
    }

    @Autowired
    public PayrollServiceImpl(
            PayrollPeriodRepository payrollPeriodRepository,
            PayrollItemRepository payrollItemRepository,
            SalaryAdvanceRepository salaryAdvanceRepository,
            SalaryAdvanceAllocationRepository salaryAdvanceAllocationRepository,
            AttendanceRecordRepository attendanceRecordRepository,
            OperatingExpenseRepository operatingExpenseRepository,
            ServiceCommissionRepository serviceCommissionRepository,
            StaffProfileRepository staffProfileRepository,
            StaffBranchAssignmentRepository staffBranchAssignmentRepository,
            BranchRepository branchRepository,
            UserRepository userRepository,
            TaxCalculationService taxCalculationService
    ) {
        this.payrollPeriodRepository = payrollPeriodRepository;
        this.payrollItemRepository = payrollItemRepository;
        this.salaryAdvanceRepository = salaryAdvanceRepository;
        this.salaryAdvanceAllocationRepository = salaryAdvanceAllocationRepository;
        this.attendanceRecordRepository = attendanceRecordRepository;
        this.operatingExpenseRepository = operatingExpenseRepository;
        this.serviceCommissionRepository = serviceCommissionRepository;
        this.staffProfileRepository = staffProfileRepository;
        this.staffBranchAssignmentRepository = staffBranchAssignmentRepository;
        this.branchRepository = branchRepository;
        this.userRepository = userRepository;
        this.taxCalculationService = taxCalculationService;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PayrollPeriod> getPeriods(
            Long branchId,
            List<Long> allowedBranchIds,
            Integer month,
            Integer year,
            PayrollStatus status,
            Pageable pageable
    ) {
        List<Long> targetBranchIds;
        if (allowedBranchIds != null) {
            if (allowedBranchIds.isEmpty()) {
                return new PageImpl<>(Collections.emptyList(), pageable, 0);
            }
            if (branchId != null) {
                if (!allowedBranchIds.contains(branchId)) {
                    return new PageImpl<>(Collections.emptyList(), pageable, 0);
                }
                targetBranchIds = List.of(branchId);
            } else {
                targetBranchIds = allowedBranchIds;
            }
        } else {
            targetBranchIds = branchId != null ? List.of(branchId) : null;
        }

        return payrollPeriodRepository.findFilteredPeriods(
                targetBranchIds,
                month,
                year,
                status,
                pageable
        );
    }

    @Override
    @Transactional(readOnly = true)
    public PayrollPeriod getPeriodDetail(Long periodId, Long currentUserId) {
        PayrollPeriod period = payrollPeriodRepository.findWithDetailsById(periodId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kỳ lương: " + periodId));

        validateBranchAccess(period.getBranch().getBranchId(), currentUserId);
        return period;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PayrollItem> getPeriodItems(Long periodId, Long currentUserId) {
        PayrollPeriod period = getPeriodDetail(periodId, currentUserId);
        return payrollItemRepository.findByPeriodIdWithStaff(period.getPeriodId());
    }

    @Override
    public PayrollPeriod createPeriod(PayrollPeriodCreateDTO dto, Long currentUserId) {
        if (currentUserId == null) {
            throw new AccessDeniedException("Người dùng chưa đăng nhập");
        }

        validateBranchAccess(dto.getBranchId(), currentUserId);

        if (payrollPeriodRepository.existsByBranch_BranchIdAndMonthAndYear(dto.getBranchId(), dto.getMonth(), dto.getYear())) {
            throw new IllegalStateException("Kỳ lương tháng " + dto.getMonth() + "/" + dto.getYear() + " cho chi nhánh này đã tồn tại");
        }

        Branch branch = branchRepository.findById(dto.getBranchId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh"));

        PayrollPeriod period = new PayrollPeriod();
        period.setBranch(branch);
        period.setMonth(dto.getMonth());
        period.setYear(dto.getYear());
        String periodName = dto.getPeriodName() != null && !dto.getPeriodName().isBlank()
                ? dto.getPeriodName().trim()
                : "Bảng lương tháng " + dto.getMonth() + "/" + dto.getYear() + " - " + branch.getBranchName();
        period.setPeriodName(periodName);
        period.setStatus(PayrollStatus.DRAFT);

        PayrollPeriod savedPeriod = payrollPeriodRepository.save(period);

        populatePeriodItems(savedPeriod, Collections.emptyMap());

        return savedPeriod;
    }

    private static class ManualAdjustment {
        final BigDecimal bonus;
        final BigDecimal deduction;
        final String note;

        ManualAdjustment(BigDecimal bonus, BigDecimal deduction, String note) {
            this.bonus = bonus;
            this.deduction = deduction;
            this.note = note;
        }
    }

    private void populatePeriodItems(PayrollPeriod period, Map<Long, ManualAdjustment> preservedAdjustments) {
        LocalDate periodStartDate = LocalDate.of(period.getYear(), period.getMonth(), 1);
        LocalDate periodEndDate = periodStartDate.plusMonths(1).minusDays(1);

        List<StaffBranchAssignment> assignments = staffBranchAssignmentRepository.findByBranchAndStatus(
                period.getBranch().getBranchId(),
                AssignmentStatus.ACTIVE
        );

        Set<Long> processedStaffIds = new HashSet<>();
        List<StaffProfile> targetStaffList = new ArrayList<>();

        for (StaffBranchAssignment assignment : assignments) {
            StaffProfile staff = assignment.getStaff();
            if (staff == null || staff.getStatus() != StaffStatus.ACTIVE) {
                continue;
            }

            // Kiểm tra tính hiệu lực của phân công trong khoảng thời gian của kỳ lương
            if (assignment.getStartDate() != null && assignment.getStartDate().isAfter(periodEndDate)) {
                continue;
            }
            if (assignment.getEndDate() != null && assignment.getEndDate().isBefore(periodStartDate)) {
                continue;
            }

            // Nếu nhân viên được phân công tại nhiều chi nhánh, chỉ chọn chi nhánh chính (isPrimary = true)
            List<StaffBranchAssignment> allActiveAssignments = staffBranchAssignmentRepository
                    .findByStaffStaffIdAndStatus(staff.getStaffId(), AssignmentStatus.ACTIVE);

            if (allActiveAssignments.size() > 1) {
                if (!Boolean.TRUE.equals(assignment.getIsPrimary())) {
                    continue; // Chi nhánh này không phải chi nhánh chính để trả lương kỳ này
                }
            }

            // Kiểm tra chống trùng lặp: nhân viên đã có bảng lương ở bất kỳ chi nhánh nào trong cùng tháng/năm chưa
            boolean alreadyHasPayrollInPeriod = payrollItemRepository
                    .existsByStaff_StaffIdAndPeriodMonthAndPeriodYear(staff.getStaffId(), period.getMonth(), period.getYear());
            if (alreadyHasPayrollInPeriod) {
                continue;
            }

            if (processedStaffIds.add(staff.getStaffId())) {
                targetStaffList.add(staff);
            }
        }

        for (StaffProfile staff : targetStaffList) {
            PayrollItem item = new PayrollItem();
            item.setPayrollPeriod(period);
            item.setStaff(staff);
            item.setPeriodMonth(period.getMonth());
            item.setPeriodYear(period.getYear());

            BigDecimal base = staff.getBaseSalary() != null ? staff.getBaseSalary() : BigDecimal.ZERO;
            BigDecimal allowance = staff.getAllowanceAmount() != null ? staff.getAllowanceAmount() : BigDecimal.ZERO;

            item.setBaseSalary(base);
            item.setActualBaseSalary(base); // Lương cố định tháng giữ nguyên
            item.setTotalAllowance(allowance);

            // Dữ liệu chấm công tham khảo thật nếu có
            List<AttendanceRecord> attendanceRecords = attendanceRecordRepository.findByStaffAndRange(
                    staff.getStaffId(),
                    periodStartDate,
                    periodEndDate
            );
            if (attendanceRecords != null && !attendanceRecords.isEmpty()) {
                long workedDays = attendanceRecords.stream()
                        .filter(a -> a.getStatus() == AttendanceStatus.ON_TIME
                                || a.getStatus() == AttendanceStatus.LATE
                                || a.getStatus() == AttendanceStatus.EARLY_LEAVE)
                        .count();
                item.setActualWorkDays(BigDecimal.valueOf(workedDays));
            } else {
                item.setActualWorkDays(BigDecimal.ZERO); // Không có dữ liệu, KHÔNG hard-code 26.00
            }

            // Giữ lại các khoản nhập tay của kế toán khi tính toán lại
            ManualAdjustment adj = preservedAdjustments != null ? preservedAdjustments.get(staff.getStaffId()) : null;
            BigDecimal bonus = adj != null && adj.bonus != null ? adj.bonus : BigDecimal.ZERO;
            BigDecimal deduction = adj != null && adj.deduction != null ? adj.deduction : BigDecimal.ZERO;
            String note = adj != null ? adj.note : null;

            item.setBonusAmount(bonus);
            item.setDeductionAmount(deduction);
            item.setNote(note);

            // Hoa hồng bác sĩ / nhân viên theo dịch vụ thực hiện trong kỳ
            BigDecimal commission = BigDecimal.ZERO;
            if (serviceCommissionRepository != null) {
                LocalDateTime startDateTime = periodStartDate.atStartOfDay();
                LocalDateTime endDateTime = periodEndDate.atTime(java.time.LocalTime.MAX);
                commission = serviceCommissionRepository.sumUnpaidCommissionsForStaffInPeriod(
                        staff.getStaffId(),
                        startDateTime,
                        endDateTime
                );
                if (commission == null) {
                    commission = BigDecimal.ZERO;
                }
            }
            item.setTotalCommission(commission);

            // Thu nhập Gross = Lương cơ bản + Phụ cấp + Thưởng + Hoa hồng
            BigDecimal grossSalary = base.add(allowance).add(bonus).add(item.getTotalCommission());
            item.setGrossSalary(grossSalary);
            item.setNonTaxableIncome(BigDecimal.ZERO);

            if (taxCalculationService != null) {
                // Tính BHXH người lao động, các khoản giảm trừ và thuế TNCN (PIT)
                PitCalculationResult pitResult = taxCalculationService.calculatePit(
                        grossSalary,
                        BigDecimal.ZERO,
                        staff,
                        periodStartDate
                );

                item.setInsuranceEmployeeDeduction(pitResult.getInsuranceEmployeeDeduction());
                item.setInsuranceEmployerAmount(pitResult.getInsuranceEmployerAmount());
                item.setPersonalDeductionAmount(pitResult.getPersonalDeductionAmount());
                item.setDependentDeductionAmount(pitResult.getDependentDeductionAmount());
                item.setTaxableIncome(pitResult.getTaxableIncome());
                item.setAssessableIncome(pitResult.getAssessableIncome());
                item.setPitAmount(pitResult.getPitAmount());
                item.setTaxPolicyCode(pitResult.getTaxPolicyCode());
                item.setTaxCalculationDetail(pitResult.getTaxCalculationDetail());

                // Lương Net = Gross - BHXH NLĐ - Thuế TNCN - Giảm trừ khác
                BigDecimal netSalary = grossSalary
                        .subtract(pitResult.getInsuranceEmployeeDeduction())
                        .subtract(pitResult.getPitAmount())
                        .subtract(deduction)
                        .max(BigDecimal.ZERO);
                item.setNetSalary(netSalary);
                item.setAdvanceAllocatedAmount(BigDecimal.ZERO);
                item.setRemainingPayable(netSalary);
            } else {
                item.setInsuranceEmployeeDeduction(BigDecimal.ZERO);
                item.setInsuranceEmployerAmount(BigDecimal.ZERO);
                item.setPersonalDeductionAmount(BigDecimal.ZERO);
                item.setDependentDeductionAmount(BigDecimal.ZERO);
                item.setTaxableIncome(grossSalary);
                item.setAssessableIncome(BigDecimal.ZERO);
                item.setPitAmount(BigDecimal.ZERO);

                BigDecimal netSalary = grossSalary.subtract(deduction).max(BigDecimal.ZERO);
                item.setNetSalary(netSalary);
                item.setAdvanceAllocatedAmount(BigDecimal.ZERO);
                item.setRemainingPayable(netSalary);
            }
            item.setPaymentStatus(PayrollItemStatus.UNPAID);
            BigDecimal netSalary = item.getNetSalary();

            // Khấu trừ tạm ứng lương và giữ chỗ (RESERVED)
            List<SalaryAdvanceAllocation> newAllocations = new ArrayList<>();
            BigDecimal allocatedAdvance = reserveAdvanceAllocationsForItem(item, netSalary, newAllocations);
            item.setAdvanceAllocatedAmount(allocatedAdvance);
            item.setRemainingPayable(netSalary.subtract(allocatedAdvance).max(BigDecimal.ZERO));

            PayrollItem savedItem = payrollItemRepository.save(item);
            for (SalaryAdvanceAllocation alloc : newAllocations) {
                alloc.setPayrollItem(savedItem != null ? savedItem : item);
                salaryAdvanceAllocationRepository.save(alloc);
            }

            if (serviceCommissionRepository != null && item.getTotalCommission().compareTo(BigDecimal.ZERO) > 0) {
                LocalDateTime startDateTime = periodStartDate.atStartOfDay();
                LocalDateTime endDateTime = periodEndDate.atTime(java.time.LocalTime.MAX);
                List<ServiceCommission> unpaidComms = serviceCommissionRepository.findUnpaidCommissionsForStaffInPeriod(
                        staff.getStaffId(),
                        CommissionStatus.PENDING,
                        startDateTime,
                        endDateTime
                );
                for (ServiceCommission c : unpaidComms) {
                    c.setPayrollItem(savedItem != null ? savedItem : item);
                    serviceCommissionRepository.save(c);
                }
            }
        }

        recalculatePeriodTotals(period.getPeriodId());
    }

    private BigDecimal reserveAdvanceAllocationsForItem(PayrollItem item, BigDecimal netSalary) {
        return reserveAdvanceAllocationsForItem(item, netSalary, null);
    }

    private BigDecimal reserveAdvanceAllocationsForItem(PayrollItem item, BigDecimal netSalary, List<SalaryAdvanceAllocation> outAllocations) {
        if (netSalary.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        List<SalaryAdvance> activeAdvances = salaryAdvanceRepository.findActiveAdvancesForSettlementForUpdate(
                item.getStaff().getStaffId(),
                SalaryAdvanceStatus.PAID,
                BigDecimal.ZERO
        );
        if (activeAdvances == null) {
            activeAdvances = Collections.emptyList();
        } else {
            activeAdvances = new ArrayList<>(activeAdvances);
            activeAdvances.sort(java.util.Comparator.comparing(SalaryAdvance::getAdvanceId));
        }

        BigDecimal availableSalary = netSalary;
        BigDecimal totalAllocated = BigDecimal.ZERO;

        for (SalaryAdvance advance : activeAdvances) {
            if (availableSalary.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }

            // Tính số tiền đã được giữ chỗ (RESERVED) bởi các kỳ lương khác
            BigDecimal reservedInOtherItems = salaryAdvanceAllocationRepository
                    .sumAllocatedByAdvanceAndStatusExcludingItem(
                            advance.getAdvanceId(),
                            AdvanceAllocationStatus.RESERVED,
                            item.getItemId()
                    );
            if (reservedInOtherItems == null) {
                reservedInOtherItems = BigDecimal.ZERO;
            }

            BigDecimal effectiveRemaining = advance.getRemainingAmount()
                    .subtract(reservedInOtherItems)
                    .max(BigDecimal.ZERO);

            if (effectiveRemaining.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal canAllocate = availableSalary.min(effectiveRemaining);

                SalaryAdvanceAllocation allocation = new SalaryAdvanceAllocation();
                allocation.setAdvance(advance);
                allocation.setAllocatedAmount(canAllocate);
                allocation.setStatus(AdvanceAllocationStatus.RESERVED);
                if (outAllocations != null) {
                    outAllocations.add(allocation);
                } else {
                    allocation.setPayrollItem(item);
                    salaryAdvanceAllocationRepository.save(allocation);
                }

                totalAllocated = totalAllocated.add(canAllocate);
                availableSalary = availableSalary.subtract(canAllocate);
            }
        }

        return totalAllocated;
    }

    private void recalculatePeriodTotals(Long periodId) {
        PayrollPeriod period = payrollPeriodRepository.findById(periodId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kỳ lương"));

        List<PayrollItem> items = payrollItemRepository.findByPayrollPeriod_PeriodId(periodId);

        BigDecimal totalSalary = BigDecimal.ZERO;
        BigDecimal totalBaseSalary = BigDecimal.ZERO;
        BigDecimal totalAllowance = BigDecimal.ZERO;
        BigDecimal totalBonus = BigDecimal.ZERO;
        BigDecimal totalCommission = BigDecimal.ZERO;
        BigDecimal totalDeduction = BigDecimal.ZERO;
        BigDecimal totalAdvanceAllocated = BigDecimal.ZERO;
        BigDecimal totalRemainingPayable = BigDecimal.ZERO;
        BigDecimal totalGrossSalary = BigDecimal.ZERO;
        BigDecimal totalInsuranceEmployee = BigDecimal.ZERO;
        BigDecimal totalInsuranceEmployer = BigDecimal.ZERO;
        BigDecimal totalPitAmount = BigDecimal.ZERO;
        String policyCode = null;

        for (PayrollItem item : items) {
            totalSalary = totalSalary.add(item.getNetSalary() != null ? item.getNetSalary() : BigDecimal.ZERO);
            totalBaseSalary = totalBaseSalary.add(item.getActualBaseSalary() != null ? item.getActualBaseSalary() : BigDecimal.ZERO);
            totalAllowance = totalAllowance.add(item.getTotalAllowance() != null ? item.getTotalAllowance() : BigDecimal.ZERO);
            totalBonus = totalBonus.add(item.getBonusAmount() != null ? item.getBonusAmount() : BigDecimal.ZERO);
            totalCommission = totalCommission.add(item.getTotalCommission() != null ? item.getTotalCommission() : BigDecimal.ZERO);
            totalDeduction = totalDeduction.add(item.getDeductionAmount() != null ? item.getDeductionAmount() : BigDecimal.ZERO);
            totalAdvanceAllocated = totalAdvanceAllocated.add(item.getAdvanceAllocatedAmount() != null ? item.getAdvanceAllocatedAmount() : BigDecimal.ZERO);
            totalRemainingPayable = totalRemainingPayable.add(item.getRemainingPayable() != null ? item.getRemainingPayable() : BigDecimal.ZERO);
            totalGrossSalary = totalGrossSalary.add(item.getGrossSalary() != null ? item.getGrossSalary() : BigDecimal.ZERO);
            totalInsuranceEmployee = totalInsuranceEmployee.add(item.getInsuranceEmployeeDeduction() != null ? item.getInsuranceEmployeeDeduction() : BigDecimal.ZERO);
            totalInsuranceEmployer = totalInsuranceEmployer.add(item.getInsuranceEmployerAmount() != null ? item.getInsuranceEmployerAmount() : BigDecimal.ZERO);
            totalPitAmount = totalPitAmount.add(item.getPitAmount() != null ? item.getPitAmount() : BigDecimal.ZERO);
            if (policyCode == null && item.getTaxPolicyCode() != null) {
                policyCode = item.getTaxPolicyCode();
            }
        }

        period.setTotalSalary(totalSalary);
        period.setTotalBaseSalary(totalBaseSalary);
        period.setTotalAllowance(totalAllowance);
        period.setTotalBonus(totalBonus);
        period.setTotalCommission(totalCommission);
        period.setTotalDeduction(totalDeduction);
        period.setTotalAdvanceAllocated(totalAdvanceAllocated);
        period.setTotalRemainingPayable(totalRemainingPayable);
        period.setTotalGrossSalary(totalGrossSalary);
        period.setTotalInsuranceEmployee(totalInsuranceEmployee);
        period.setTotalInsuranceEmployer(totalInsuranceEmployer);
        period.setTotalPitAmount(totalPitAmount);
        period.setTaxPolicyCode(policyCode);

        payrollPeriodRepository.save(period);
    }

    @Override
    public PayrollItem updateItem(Long itemId, PayrollItemUpdateDTO dto, Long currentUserId) {
        PayrollItem item = payrollItemRepository.findWithDetailsById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dòng lương: " + itemId));

        PayrollPeriod period = item.getPayrollPeriod();
        validateBranchAccess(period.getBranch().getBranchId(), currentUserId);

        if (period.getStatus() != PayrollStatus.DRAFT) {
            throw new IllegalStateException("Chỉ có thể chỉnh sửa dòng lương khi kỳ lương ở trạng thái Nháp (DRAFT)");
        }

        BigDecimal bonus = dto.getBonusAmount() != null ? dto.getBonusAmount() : BigDecimal.ZERO;
        BigDecimal deduction = dto.getDeductionAmount() != null ? dto.getDeductionAmount() : BigDecimal.ZERO;

        if (bonus.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Tiền thưởng không được âm");
        }
        if (deduction.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Khoản giảm trừ không được âm");
        }

        BigDecimal grossSalary = item.getActualBaseSalary()
                .add(item.getTotalAllowance())
                .add(bonus)
                .add(item.getTotalCommission());

        LocalDate periodDate = LocalDate.of(period.getYear(), period.getMonth(), 1);
        PitCalculationResult pitResult = taxCalculationService.calculatePit(
                grossSalary,
                item.getNonTaxableIncome() != null ? item.getNonTaxableIncome() : BigDecimal.ZERO,
                item.getStaff(),
                periodDate
        );

        BigDecimal mandatoryDeductions = pitResult.getInsuranceEmployeeDeduction().add(pitResult.getPitAmount());
        if (deduction.add(mandatoryDeductions).compareTo(grossSalary) > 0) {
            throw new IllegalArgumentException("Tổng các khoản giảm trừ (Bảo hiểm " + pitResult.getInsuranceEmployeeDeduction()
                    + " đ, Thuế TNCN " + pitResult.getPitAmount()
                    + " đ, Khấu trừ khác " + deduction + " đ) vượt quá tổng thu nhập Gross (" + grossSalary + " đ). Không cho phép lương Net âm.");
        }

        if ((bonus.compareTo(BigDecimal.ZERO) > 0 || deduction.compareTo(BigDecimal.ZERO) > 0)
                && (dto.getNote() == null || dto.getNote().trim().isEmpty())) {
            throw new IllegalArgumentException("Bắt buộc nhập lý do/ghi chú khi điều chỉnh thưởng hoặc giảm trừ lương.");
        }

        item.setGrossSalary(grossSalary);
        item.setBonusAmount(bonus);
        item.setDeductionAmount(deduction);
        item.setInsuranceEmployeeDeduction(pitResult.getInsuranceEmployeeDeduction());
        item.setInsuranceEmployerAmount(pitResult.getInsuranceEmployerAmount());
        item.setPersonalDeductionAmount(pitResult.getPersonalDeductionAmount());
        item.setDependentDeductionAmount(pitResult.getDependentDeductionAmount());
        item.setTaxableIncome(pitResult.getTaxableIncome());
        item.setAssessableIncome(pitResult.getAssessableIncome());
        item.setPitAmount(pitResult.getPitAmount());
        item.setTaxPolicyCode(pitResult.getTaxPolicyCode());
        item.setTaxCalculationDetail(pitResult.getTaxCalculationDetail());
        item.setNote(dto.getNote() != null ? dto.getNote().trim() : null);

        BigDecimal netSalary = grossSalary.subtract(mandatoryDeductions).subtract(deduction).max(BigDecimal.ZERO);
        item.setNetSalary(netSalary);

        // Xóa các allocation giữ chỗ cũ của dòng lương này để tính toán lại
        List<SalaryAdvanceAllocation> oldAllocations = salaryAdvanceAllocationRepository.findByPayrollItem_ItemId(itemId);
        salaryAdvanceAllocationRepository.deleteAll(oldAllocations);
        salaryAdvanceAllocationRepository.flush();

        BigDecimal allocatedAdvance = reserveAdvanceAllocationsForItem(item, netSalary);
        item.setAdvanceAllocatedAmount(allocatedAdvance);

        BigDecimal remainingPayable = netSalary.subtract(allocatedAdvance).max(BigDecimal.ZERO);
        item.setRemainingPayable(remainingPayable);

        PayrollItem savedItem = payrollItemRepository.save(item);
        recalculatePeriodTotals(period.getPeriodId());

        return savedItem;
    }

    @Override
    public PayrollPeriod recalculatePeriod(Long periodId, Long currentUserId) {
        PayrollPeriod period = getPeriodDetail(periodId, currentUserId);
        if (period.getStatus() != PayrollStatus.DRAFT) {
            throw new IllegalStateException("Chỉ có thể tính toán lại khi kỳ lương ở trạng thái Nháp (DRAFT)");
        }

        // Giữ lại các khoản thưởng, giảm trừ và ghi chú kế toán đã nhập tay
        List<PayrollItem> oldItems = payrollItemRepository.findByPayrollPeriod_PeriodId(periodId);
        Map<Long, ManualAdjustment> preserved = new HashMap<>();
        for (PayrollItem old : oldItems) {
            preserved.put(old.getStaff().getStaffId(), new ManualAdjustment(old.getBonusAmount(), old.getDeductionAmount(), old.getNote()));
        }

        if (serviceCommissionRepository != null) {
            serviceCommissionRepository.unbindCommissionsFromPeriod(periodId);
        }
        salaryAdvanceAllocationRepository.deleteByPeriodId(periodId);
        payrollItemRepository.deleteByPayrollPeriod_PeriodId(periodId);
        payrollPeriodRepository.flush();
        populatePeriodItems(period, preserved);

        return payrollPeriodRepository.findById(periodId).orElseThrow();
    }

    @Override
    public PayrollPeriod submitPeriod(Long periodId, Long currentUserId) {
        PayrollPeriod period = getPeriodDetail(periodId, currentUserId);
        if (period.getStatus() != PayrollStatus.DRAFT) {
            throw new IllegalStateException("Kỳ lương không ở trạng thái Nháp (DRAFT)");
        }

        List<PayrollItem> items = payrollItemRepository.findByPayrollPeriod_PeriodId(periodId);
        if (items.isEmpty()) {
            throw new IllegalStateException("Bảng lương chưa có nhân viên nào. Không thể trình duyệt.");
        }

        StaffProfile submitter = staffProfileRepository.findByUser_UserId(currentUserId).orElse(null);

        period.setStatus(PayrollStatus.SUBMITTED);
        period.setSubmittedBy(submitter);
        period.setSubmittedAt(LocalDateTime.now());
        period.setRejectionReason(null);

        return payrollPeriodRepository.save(period);
    }

    @Override
    public PayrollPeriod approvePeriod(Long periodId, Long currentUserId) {
        if (currentUserId == null || (!userRepository.hasActiveAdminRole(currentUserId)
                && !userRepository.hasActivePermission(currentUserId, "FINANCE_PAYROLL"))) {
            throw new AccessDeniedException("Bạn không có quyền phê duyệt bảng lương");
        }

        PayrollPeriod period = payrollPeriodRepository.findByIdForUpdate(periodId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kỳ lương: " + periodId));

        if (period.getStatus() != PayrollStatus.SUBMITTED) {
            throw new IllegalStateException("Kỳ lương phải ở trạng thái Chờ duyệt (SUBMITTED) mới có thể phê duyệt");
        }

        StaffProfile approver = staffProfileRepository.findByUser_UserId(currentUserId).orElse(null);

        period.setStatus(PayrollStatus.APPROVED);
        period.setApprovedBy(approver);
        period.setApprovedAt(LocalDateTime.now());
        period.setRejectionReason(null);

        return payrollPeriodRepository.save(period);
    }

    @Override
    public PayrollPeriod rejectPeriod(Long periodId, String reason, Long currentUserId) {
        if (currentUserId == null || (!userRepository.hasActiveAdminRole(currentUserId)
                && !userRepository.hasActivePermission(currentUserId, "FINANCE_PAYROLL"))) {
            throw new AccessDeniedException("Bạn không có quyền từ chối duyệt bảng lương");
        }

        PayrollPeriod period = payrollPeriodRepository.findByIdForUpdate(periodId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kỳ lương: " + periodId));

        if (period.getStatus() == PayrollStatus.PAID) {
            throw new IllegalStateException("Kỳ lương đã được chi trả (PAID), không thể từ chối hoặc trả về sửa đổi");
        }

        if (period.getStatus() != PayrollStatus.SUBMITTED && period.getStatus() != PayrollStatus.APPROVED) {
            throw new IllegalStateException("Chỉ có thể từ chối hoặc trả về sửa đổi đối với kỳ lương ở trạng thái Chờ duyệt (SUBMITTED) hoặc Đã duyệt (APPROVED)");
        }

        period.setStatus(PayrollStatus.DRAFT);
        period.setApprovedBy(null);
        period.setApprovedAt(null);
        period.setRejectionReason(reason != null ? reason.trim() : "Quản trị viên từ chối phê duyệt");

        operatingExpenseRepository.findByPayrollPeriod_PeriodId(periodId).ifPresent(expense -> {
            if (!Boolean.TRUE.equals(expense.getIsCancelled())) {
                expense.setIsCancelled(true);
                expense.setCancelledReason("Hủy phiếu chi do bảng lương bị từ chối/hủy");
                User canceller = currentUserId != null ? userRepository.findById(currentUserId).orElse(null) : null;
                expense.setCancelledBy(canceller);
                expense.setCancelledAt(LocalDateTime.now());
                operatingExpenseRepository.save(expense);
            }
        });

        return payrollPeriodRepository.save(period);
    }

    @Override
    public PayrollPeriod payPeriod(Long periodId, RefundMethod paymentMethod, String paymentReference, Long currentUserId) {
        if (currentUserId == null) {
            throw new AccessDeniedException("Người dùng chưa đăng nhập");
        }

        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng"));

        PayrollPeriod period = payrollPeriodRepository.findByIdForUpdate(periodId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kỳ lương: " + periodId));

        validateBranchAccess(period.getBranch().getBranchId(), currentUserId);

        if (period.getStatus() == PayrollStatus.PAID) {
            throw new IllegalStateException("Kỳ lương này đã được chi trả (PAID).");
        }

        if (period.getStatus() != PayrollStatus.APPROVED) {
            throw new IllegalStateException("Kỳ lương phải được phê duyệt (APPROVED) trước khi chi trả");
        }

        List<PayrollItem> items = payrollItemRepository.findByPayrollPeriod_PeriodId(periodId);
        if (items.isEmpty()) {
            throw new IllegalStateException("Bảng lương không có dòng dữ liệu nào");
        }

        // BẢO VỆ BẤT BIẾN VÀ ĐỐI SOÁT TOÀN DIỆN TRƯỚC KHI CHI TRẢ
        BigDecimal runningNet = BigDecimal.ZERO;
        BigDecimal runningAdvance = BigDecimal.ZERO;
        BigDecimal runningRemaining = BigDecimal.ZERO;

        List<SalaryAdvanceAllocation> allReservedAllocations = new ArrayList<>();

        for (PayrollItem item : items) {
            BigDecimal baseSal = (item.getActualBaseSalary() != null && item.getActualBaseSalary().compareTo(BigDecimal.ZERO) > 0)
                    ? item.getActualBaseSalary()
                    : (item.getBaseSalary() != null ? item.getBaseSalary() : BigDecimal.ZERO);
            BigDecimal allowance = item.getTotalAllowance() != null ? item.getTotalAllowance() : BigDecimal.ZERO;
            BigDecimal bonus = item.getBonusAmount() != null ? item.getBonusAmount() : BigDecimal.ZERO;
            BigDecimal comm = item.getTotalCommission() != null ? item.getTotalCommission() : BigDecimal.ZERO;
            BigDecimal gross = (item.getGrossSalary() != null && item.getGrossSalary().compareTo(BigDecimal.ZERO) > 0)
                    ? item.getGrossSalary()
                    : baseSal.add(allowance).add(bonus).add(comm);

            BigDecimal ins = item.getInsuranceEmployeeDeduction() != null ? item.getInsuranceEmployeeDeduction() : BigDecimal.ZERO;
            BigDecimal pit = item.getPitAmount() != null ? item.getPitAmount() : BigDecimal.ZERO;
            BigDecimal deduction = item.getDeductionAmount() != null ? item.getDeductionAmount() : BigDecimal.ZERO;
            BigDecimal expectedNet = gross.subtract(ins).subtract(pit).subtract(deduction).max(BigDecimal.ZERO);

            if (item.getNetSalary() == null || item.getNetSalary().compareTo(expectedNet) != 0) {
                throw new IllegalStateException("Lương Net của nhân sự " + (item.getStaff() != null ? item.getStaff().getFullName() : item.getItemId())
                        + " không nhất quán với cấu phần lương.");
            }

            BigDecimal itemAdvance = item.getAdvanceAllocatedAmount() != null ? item.getAdvanceAllocatedAmount() : BigDecimal.ZERO;
            BigDecimal expectedRemaining = expectedNet.subtract(itemAdvance).max(BigDecimal.ZERO);
            if (item.getRemainingPayable() == null || item.getRemainingPayable().compareTo(expectedRemaining) != 0) {
                throw new IllegalStateException("Số tiền thực trả của nhân sự " + (item.getStaff() != null ? item.getStaff().getFullName() : item.getItemId())
                        + " không khớp với Lương Net trừ Tạm ứng.");
            }

            if (itemAdvance.compareTo(BigDecimal.ZERO) > 0) {
                List<SalaryAdvanceAllocation> allocations = salaryAdvanceAllocationRepository
                        .findByPayrollItem_ItemIdAndStatusForUpdate(item.getItemId(), AdvanceAllocationStatus.RESERVED);

                if (allocations == null || allocations.isEmpty()) {
                    throw new IllegalStateException("Dòng lương của nhân sự " + (item.getStaff() != null ? item.getStaff().getFullName() : item.getItemId())
                            + " có số tiền tạm ứng đã duyệt (" + itemAdvance
                            + " đ) nhưng chưa tồn tại chi tiết giữ chỗ (allocation). Vui lòng trả bảng lương về Dự thảo để hệ thống đối soát và phân bổ lại tạm ứng.");
                }

                BigDecimal totalAllocated = allocations.stream()
                        .map(SalaryAdvanceAllocation::getAllocatedAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                if (totalAllocated.compareTo(itemAdvance) != 0) {
                    throw new IllegalStateException("Tổng số tiền giữ chỗ tạm ứng (" + totalAllocated + " đ) không khớp với số tiền đã duyệt ("
                            + itemAdvance + " đ) của nhân sự " + (item.getStaff() != null ? item.getStaff().getFullName() : item.getItemId())
                            + ". Vui lòng trả về Dự thảo để đối soát lại.");
                }

                allReservedAllocations.addAll(allocations);
            }

            runningNet = runningNet.add(expectedNet);
            runningAdvance = runningAdvance.add(itemAdvance);
            runningRemaining = runningRemaining.add(expectedRemaining);
        }

        if (period.getTotalSalary() != null && period.getTotalSalary().compareTo(runningNet) != 0) {
            throw new IllegalStateException("Tổng lương Net của kỳ (" + period.getTotalSalary() + ") không khớp tổng các dòng (" + runningNet + ").");
        }
        if (period.getTotalAdvanceAllocated() != null && period.getTotalAdvanceAllocated().compareTo(runningAdvance) != 0) {
            throw new IllegalStateException("Tổng tạm ứng của kỳ (" + period.getTotalAdvanceAllocated() + ") không khớp tổng các dòng (" + runningAdvance + ").");
        }
        if (period.getTotalRemainingPayable() != null && period.getTotalRemainingPayable().compareTo(runningRemaining) != 0) {
            throw new IllegalStateException("Tổng thực trả của kỳ (" + period.getTotalRemainingPayable() + ") không khớp tổng các dòng (" + runningRemaining + ").");
        }

        // KHÓA PESSIMISTIC TẤT CẢ PHIẾU TẠM ỨNG LIÊN QUAN THEO THỨ TỰ ID TĂNG DẦN (CHỐNG DEADLOCK & LOST UPDATE)
        List<Long> advanceIdsToLock = allReservedAllocations.stream()
                .map(a -> a.getAdvance().getAdvanceId())
                .distinct()
                .sorted()
                .toList();

        Map<Long, SalaryAdvance> lockedAdvances = new HashMap<>();
        for (Long advId : advanceIdsToLock) {
            SalaryAdvance lockedAdv = salaryAdvanceRepository.findByIdForUpdate(advId)
                    .orElseThrow(() -> new IllegalStateException("Không tìm thấy phiếu tạm ứng ID: " + advId));
            lockedAdvances.put(advId, lockedAdv);
        }

        // Kiểm tra số dư khả dụng thực tế của từng phiếu tạm ứng sau khi đã khóa
        for (Long advId : advanceIdsToLock) {
            SalaryAdvance lockedAdv = lockedAdvances.get(advId);
            BigDecimal totalNeededFromThisAdv = allReservedAllocations.stream()
                    .filter(a -> a.getAdvance().getAdvanceId().equals(advId))
                    .map(SalaryAdvanceAllocation::getAllocatedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (lockedAdv.getRemainingAmount().compareTo(totalNeededFromThisAdv) < 0) {
                throw new IllegalStateException("Khoản tạm ứng (" + lockedAdv.getAdvanceCode() + ") của nhân viên "
                        + (lockedAdv.getStaff() != null ? lockedAdv.getStaff().getFullName() : lockedAdv.getAdvanceId())
                        + " không đủ số dư để cấn trừ (Còn lại: " + lockedAdv.getRemainingAmount() + " đ, Yêu cầu cấn: "
                        + totalNeededFromThisAdv + " đ). Dữ liệu đã thay đổi sau khi duyệt, vui lòng trả về Dự thảo để đối soát lại.");
            }
        }

        // Cập nhật số dư và trạng thái từng phiếu tạm ứng
        for (Long advId : advanceIdsToLock) {
            SalaryAdvance lockedAdv = lockedAdvances.get(advId);
            BigDecimal totalNeededFromThisAdv = allReservedAllocations.stream()
                    .filter(a -> a.getAdvance().getAdvanceId().equals(advId))
                    .map(SalaryAdvanceAllocation::getAllocatedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            lockedAdv.setAllocatedAmount(lockedAdv.getAllocatedAmount().add(totalNeededFromThisAdv));
            lockedAdv.setRemainingAmount(lockedAdv.getRemainingAmount().subtract(totalNeededFromThisAdv));
            if (lockedAdv.getRemainingAmount().compareTo(BigDecimal.ZERO) == 0) {
                lockedAdv.setStatus(SalaryAdvanceStatus.SETTLED);
            }
            salaryAdvanceRepository.save(lockedAdv);
        }

        // Chuyển trạng thái allocation sang SETTLED
        for (SalaryAdvanceAllocation alloc : allReservedAllocations) {
            alloc.setAdvance(lockedAdvances.get(alloc.getAdvance().getAdvanceId()));
            alloc.setStatus(AdvanceAllocationStatus.SETTLED);
            salaryAdvanceAllocationRepository.save(alloc);
        }

        // Đánh dấu các dòng lương sang PAID
        for (PayrollItem item : items) {
            item.setPaymentStatus(PayrollItemStatus.PAID);
            payrollItemRepository.save(item);
        }

        StaffProfile payerStaff = staffProfileRepository.findByUser_UserId(currentUserId).orElse(null);

        period.setStatus(PayrollStatus.PAID);
        period.setPaidAt(LocalDateTime.now());
        period.setPaidBy(payerStaff);
        period.setPaymentMethod(paymentMethod != null ? paymentMethod : RefundMethod.BANK_TRANSFER);
        period.setPaymentReference(paymentReference != null ? paymentReference.trim() : null);

        PayrollPeriod savedPeriod = payrollPeriodRepository.save(period);

        // 2. Tạo phiếu chi vận hành (Operating Expense) nếu tổng thực trả > 0 và chưa tạo trước đó
        if (savedPeriod.getTotalRemainingPayable().compareTo(BigDecimal.ZERO) > 0) {
            if (!operatingExpenseRepository.existsByPayrollPeriod_PeriodId(savedPeriod.getPeriodId())) {
                OperatingExpense expense = new OperatingExpense();
                expense.setBranch(savedPeriod.getBranch());
                expense.setCategory("Chi trả lương nhân viên");
                expense.setAmount(savedPeriod.getTotalRemainingPayable());
                expense.setExpenseDate(LocalDate.now());
                expense.setRecipientName("Toàn thể nhân sự - Kỳ " + savedPeriod.getMonth() + "/" + savedPeriod.getYear());
                expense.setPaymentMethod(savedPeriod.getPaymentMethod());
                expense.setSourceType(ExpenseSourceType.PAYROLL);
                expense.setPayrollPeriod(savedPeriod);
                expense.setDescription("Thực chi trả bảng lương: " + savedPeriod.getPeriodName()
                        + " (Tổng Gross: " + savedPeriod.getTotalGrossSalary() + " VNĐ, BHXH NLĐ: " + savedPeriod.getTotalInsuranceEmployee()
                        + " VNĐ, Thuế TNCN: " + savedPeriod.getTotalPitAmount() + " VNĐ, Đã cấn trừ tạm ứng: "
                        + savedPeriod.getTotalAdvanceAllocated() + " VNĐ, Thực chi: " + savedPeriod.getTotalRemainingPayable() + " VNĐ)");
                expense.setCreatedBy(currentUser);
                expense.setIsCancelled(false);
                operatingExpenseRepository.save(expense);
            }
        }

        if (serviceCommissionRepository != null) {
            serviceCommissionRepository.markCommissionsAsPaidForPeriod(periodId);
        }

        return savedPeriod;
    }

    @Override
    public void deleteDraftPeriod(Long periodId, Long currentUserId) {
        PayrollPeriod period = getPeriodDetail(periodId, currentUserId);
        if (period.getStatus() != PayrollStatus.DRAFT) {
            throw new IllegalStateException("Chỉ có thể xóa kỳ lương ở trạng thái Nháp (DRAFT)");
        }

        operatingExpenseRepository.findByPayrollPeriod_PeriodId(periodId).ifPresent(expense -> {
            if (!Boolean.TRUE.equals(expense.getIsCancelled())) {
                expense.setIsCancelled(true);
                expense.setCancelledReason("Hủy phiếu chi do xóa bảng lương nháp");
                User canceller = currentUserId != null ? userRepository.findById(currentUserId).orElse(null) : null;
                expense.setCancelledBy(canceller);
                expense.setCancelledAt(LocalDateTime.now());
                operatingExpenseRepository.save(expense);
            }
        });

        if (serviceCommissionRepository != null) {
            serviceCommissionRepository.unbindCommissionsFromPeriod(periodId);
        }

        salaryAdvanceAllocationRepository.deleteByPeriodId(periodId);
        payrollItemRepository.deleteByPayrollPeriod_PeriodId(periodId);
        payrollPeriodRepository.delete(period);
    }

    private void validateBranchAccess(Long branchId, Long currentUserId) {
        if (currentUserId == null) {
            throw new AccessDeniedException("Người dùng chưa đăng nhập");
        }
        if (userRepository.hasActiveAdminRole(currentUserId)) {
            return;
        }
        boolean hasAssignment = staffBranchAssignmentRepository.hasActiveUserAssignment(
                currentUserId, branchId, AssignmentStatus.ACTIVE);
        if (!hasAssignment) {
            boolean isMultiBranch = staffBranchAssignmentRepository.findActiveBranchIdsByUserId(currentUserId, AssignmentStatus.ACTIVE).isEmpty()
                    && userRepository.findById(currentUserId).map(u -> u.getBranch() == null || branchId.equals(u.getBranch().getBranchId())).orElse(false);
            if (!isMultiBranch) {
                throw new AccessDeniedException("Bạn không có quyền truy cập dữ liệu của chi nhánh này");
            }
        }
    }
}
