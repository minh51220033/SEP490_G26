package com.cvdcms.service.impl;

import com.cvdcms.DTO.PayrollItemUpdateDTO;
import com.cvdcms.DTO.PayrollPeriodCreateDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.repository.ServiceCommissionRepository;
import com.cvdcms.service.PayrollService;
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

/**
 * [Phân hệ]: Tài chính - Bảng lương nhân sự
 * [Vai trò]: Triển khai toàn bộ vòng đời kỳ lương (khởi tạo, đồng bộ chấm công/hoa hồng/tạm ứng, điều chỉnh dòng lương, trình duyệt, phê duyệt, từ chối, chi trả kèm đối soát bất biến, tất toán tạm ứng và sinh phiếu chi tự động).
 */
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
            UserRepository userRepository
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
    }

    /**
     * [Chức năng]: Tra cứu phân trang danh sách kỳ lương theo chi nhánh, tháng, năm và trạng thái trong phạm vi chi nhánh được phép.
     * [Mục đích]: Giới hạn truy vấn trong allowedBranchIds để chống xem chéo chi nhánh và gọi PayrollPeriodRepository.findFilteredPeriods.
     */
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

    /**
     * [Chức năng]: Lấy thông tin chi tiết một kỳ lương theo ID kèm kiểm tra quyền truy cập chi nhánh.
     * [Mục đích]: Tải đầy đủ entity PayrollPeriod qua findWithDetailsById và gọi validateBranchAccess trước khi trả dữ liệu.
     */
    @Override
    @Transactional(readOnly = true)
    public PayrollPeriod getPeriodDetail(Long periodId, Long currentUserId) {
        PayrollPeriod period = payrollPeriodRepository.findWithDetailsById(periodId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kỳ lương: " + periodId));

        validateBranchAccess(period.getBranch().getBranchId(), currentUserId);
        return period;
    }

    /**
     * [Chức năng]: Lấy danh sách các dòng lương chi tiết (PayrollItem) của một kỳ lương và tự động đồng bộ ngày công thực tế khi kỳ ở trạng thái DRAFT.
     * [Mục đích]: Kiểm tra quyền truy cập kỳ lương, tải các PayrollItem kèm thông tin nhân sự và cập nhật actualWorkDays từ dữ liệu chấm công nếu có sai khác ở bản nháp.
     */
    @Override
    public List<PayrollItem> getPeriodItems(Long periodId, Long currentUserId) {
        PayrollPeriod period = getPeriodDetail(periodId, currentUserId);
        List<PayrollItem> items = payrollItemRepository.findByPeriodIdWithStaff(period.getPeriodId());
        if (period.getStatus() == PayrollStatus.DRAFT
                && attendanceRecordRepository != null
                && period.getYear() != null
                && period.getMonth() != null) {
            LocalDate periodStartDate = LocalDate.of(period.getYear(), period.getMonth(), 1);
            LocalDate periodEndDate = periodStartDate.plusMonths(1).minusDays(1);
            for (PayrollItem item : items) {
                if (item.getStaff() == null || item.getStaff().getStaffId() == null) {
                    continue;
                }
                BigDecimal syncedWorkDays = resolveActualWorkDays(item.getStaff().getStaffId(), periodStartDate, periodEndDate);
                if (item.getActualWorkDays() == null || item.getActualWorkDays().compareTo(syncedWorkDays) != 0) {
                    item.setActualWorkDays(syncedWorkDays);
                    payrollItemRepository.save(item);
                }
            }
        }
        return items;
    }

    /**
     * [Chức năng]: Khởi tạo mới một kỳ bảng lương ở trạng thái Nháp (DRAFT) cho chi nhánh theo tháng/năm và tự động tính dòng lương cho nhân sự đủ điều kiện.
     * [Mục đích]: Kiểm tra quyền FINANCE_PAYROLL, quyền chi nhánh và chặn tạo trùng kỳ lương cùng chi nhánh/tháng/năm, lưu PayrollPeriod và gọi populatePeriodItems.
     */
    @Override
    public PayrollPeriod createPeriod(PayrollPeriodCreateDTO dto, Long currentUserId) {
        if (currentUserId == null) {
            throw new AccessDeniedException("Người dùng chưa đăng nhập");
        }
        requirePayrollPermission(currentUserId);

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

    /**
     * [Hàm hỗ trợ]: Nạp và tính toán toàn bộ dòng lương cho nhân sự ACTIVE thuộc chi nhánh (ưu tiên chi nhánh chính, chống trùng tháng/năm), khóa và gắn hoa hồng PENDING trong tháng, giữ chỗ khấu trừ tạm ứng (RESERVED) và cập nhật tổng kỳ lương.
     */
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
        targetStaffList.sort(Comparator.comparing(StaffProfile::getStaffId));

        LocalDateTime periodStartDateTime = periodStartDate.atStartOfDay();
        LocalDateTime periodEndDateExclusive = periodEndDate.plusDays(1).atStartOfDay();

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

            // Dữ liệu chấm công đồng bộ từ bảng chấm công Quản lý nhân sự
            item.setActualWorkDays(resolveActualWorkDays(staff.getStaffId(), periodStartDate, periodEndDate));

            // Giữ lại các khoản nhập tay của kế toán khi tính toán lại
            ManualAdjustment adj = preservedAdjustments != null ? preservedAdjustments.get(staff.getStaffId()) : null;
            BigDecimal bonus = adj != null && adj.bonus != null ? adj.bonus : BigDecimal.ZERO;
            BigDecimal deduction = adj != null && adj.deduction != null ? adj.deduction : BigDecimal.ZERO;
            String note = adj != null ? adj.note : null;

            item.setBonusAmount(bonus);
            item.setDeductionAmount(deduction);
            item.setNote(note);

            // Khóa và tải duy nhất một tập hoa hồng PENDING chưa gắn kỳ lương trong [đầu tháng, đầu tháng sau)
            List<ServiceCommission> pendingComms = Collections.emptyList();
            BigDecimal commission = BigDecimal.ZERO;
            if (serviceCommissionRepository != null) {
                List<ServiceCommission> lockedComms = serviceCommissionRepository.findPendingUnboundCommissionsForStaffInPeriodForUpdate(
                        staff.getStaffId(),
                        periodStartDateTime,
                        periodEndDateExclusive
                );
                if (lockedComms != null && !lockedComms.isEmpty()) {
                    pendingComms = lockedComms;
                    for (ServiceCommission sc : pendingComms) {
                        if (sc.getCommissionAmount() != null) {
                            commission = commission.add(sc.getCommissionAmount());
                        }
                    }
                }
            }
            item.setTotalCommission(commission);

            // Thu nhập Gross = Lương cơ bản + Phụ cấp + Thưởng + Hoa hồng
            BigDecimal grossSalary = base.add(allowance).add(bonus).add(item.getTotalCommission());
            item.setGrossSalary(grossSalary);
            item.setNonTaxableIncome(BigDecimal.ZERO);

            resetLegacyDeductionsToZero(item);

            // Lương Net = Gross - Giảm trừ khác
            BigDecimal computedNetSalary = grossSalary
                    .subtract(deduction)
                    .max(BigDecimal.ZERO);
            item.setNetSalary(computedNetSalary);
            item.setAdvanceAllocatedAmount(BigDecimal.ZERO);
            item.setRemainingPayable(computedNetSalary);
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

            if (!pendingComms.isEmpty()) {
                PayrollItem targetItem = savedItem != null ? savedItem : item;
                for (ServiceCommission c : pendingComms) {
                    c.setPayrollItem(targetItem);
                    serviceCommissionRepository.save(c);
                }
            }
        }

        recalculatePeriodTotals(period.getPeriodId());
    }

    /**
     * [Hàm hỗ trợ]: Giữ chỗ cấn trừ tạm ứng (RESERVED) cho một dòng lương và lưu trực tiếp các bản ghi phân bổ vào DB.
     */
    private BigDecimal reserveAdvanceAllocationsForItem(PayrollItem item, BigDecimal netSalary) {
        return reserveAdvanceAllocationsForItem(item, netSalary, null);
    }

    /**
     * [Hàm hỗ trợ]: Khóa bi quan các khoản tạm ứng PAID còn số dư của nhân viên theo thứ tự ID tăng dần, tính số dư khả dụng (trừ phần đang RESERVED ở kỳ khác) và tạo phân bổ RESERVED tương ứng không vượt quá lương Net.
     */
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

    /**
     * [Hàm hỗ trợ]: Cộng dồn toàn bộ các cấu phần lương (lương cơ bản, phụ cấp, thưởng, hoa hồng, Gross, giảm trừ, Net, tạm ứng, thực nhận) từ các PayrollItem và lưu tổng vào PayrollPeriod.
     */
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
        period.setTotalInsuranceEmployee(BigDecimal.ZERO);
        period.setTotalInsuranceEmployer(BigDecimal.ZERO);
        period.setTotalPitAmount(BigDecimal.ZERO);
        period.setTaxPolicyCode(null);

        payrollPeriodRepository.save(period);
    }

    /**
     * [Chức năng]: Cập nhật tiền thưởng, khoản giảm trừ và ghi chú cho một dòng lương khi kỳ lương đang ở trạng thái DRAFT.
     * [Mục đích]: Khóa kỳ lương (findByIdForUpdate), kiểm tra quyền FINANCE_PAYROLL và chi nhánh, xác thực không âm/không vượt Gross/bắt buộc có ghi chú khi điều chỉnh, tính lại lương Net, phân bổ lại tạm ứng RESERVED và cập nhật tổng kỳ lương.
     */
    @Override
    public PayrollItem updateItem(Long itemId, PayrollItemUpdateDTO dto, Long currentUserId) {
        PayrollItem item = payrollItemRepository.findWithDetailsById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dòng lương: " + itemId));

        PayrollPeriod period = payrollPeriodRepository.findByIdForUpdate(item.getPayrollPeriod().getPeriodId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kỳ lương."));
        validateBranchAccess(period.getBranch().getBranchId(), currentUserId);
        requirePayrollPermission(currentUserId);

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

        if (deduction.compareTo(grossSalary) > 0) {
            throw new IllegalArgumentException("Khoản giảm trừ (" + deduction
                    + " đ) vượt quá tổng thu nhập Gross (" + grossSalary + " đ). Không cho phép lương Net âm.");
        }

        if ((bonus.compareTo(BigDecimal.ZERO) > 0 || deduction.compareTo(BigDecimal.ZERO) > 0)
                && (dto.getNote() == null || dto.getNote().trim().isEmpty())) {
            throw new IllegalArgumentException("Bắt buộc nhập lý do/ghi chú khi điều chỉnh thưởng hoặc giảm trừ lương.");
        }

        item.setGrossSalary(grossSalary);
        item.setBonusAmount(bonus);
        item.setDeductionAmount(deduction);
        resetLegacyDeductionsToZero(item);
        item.setNote(dto.getNote() != null ? dto.getNote().trim() : null);

        BigDecimal netSalary = grossSalary.subtract(deduction).max(BigDecimal.ZERO);
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

    /**
     * [Chức năng]: Tính lại tổng hoa hồng, lương Gross, Net, phân bổ tạm ứng và thực nhận của một dòng lương DRAFT sau khi hoa hồng dịch vụ thay đổi.
     * [Mục đích]: Được ServiceCommissionService gọi khi hủy hoặc tính lại hoa hồng dịch vụ, khóa kỳ lương đảm bảo đang DRAFT, cộng lại các hoa hồng PENDING còn gắn và cập nhật tổng kỳ lương.
     */
    @Override
    public void recalculateDraftItemAfterCommissionChange(Long payrollItemId) {
        if (payrollItemId == null) {
            return;
        }
        PayrollItem item = payrollItemRepository.findWithDetailsById(payrollItemId).orElse(null);
        if (item == null || item.getPayrollPeriod() == null) {
            return;
        }
        PayrollPeriod period = payrollPeriodRepository.findByIdForUpdate(item.getPayrollPeriod().getPeriodId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kỳ lương."));
        if (period.getStatus() != PayrollStatus.DRAFT) {
            throw new IllegalStateException("Chỉ có thể tính lại dòng lương khi kỳ lương ở trạng thái Nháp (DRAFT)");
        }

        BigDecimal newCommission = BigDecimal.ZERO;
        if (serviceCommissionRepository != null) {
            List<ServiceCommission> bound = serviceCommissionRepository.findByPayrollItem_ItemId(payrollItemId);
            if (bound != null) {
                for (ServiceCommission sc : bound) {
                    if (sc.getStatus() == CommissionStatus.PENDING
                            && (sc.getPerformedService() == null || sc.getPerformedService().getStatus() == PerformedServiceStatus.COMPLETED)
                            && sc.getCommissionAmount() != null) {
                        newCommission = newCommission.add(sc.getCommissionAmount());
                    }
                }
            }
        }
        item.setTotalCommission(newCommission);

        BigDecimal base = item.getActualBaseSalary() != null ? item.getActualBaseSalary() : BigDecimal.ZERO;
        BigDecimal allowance = item.getTotalAllowance() != null ? item.getTotalAllowance() : BigDecimal.ZERO;
        BigDecimal bonus = item.getBonusAmount() != null ? item.getBonusAmount() : BigDecimal.ZERO;
        BigDecimal deduction = item.getDeductionAmount() != null ? item.getDeductionAmount() : BigDecimal.ZERO;
        BigDecimal grossSalary = base.add(allowance).add(bonus).add(newCommission);
        item.setGrossSalary(grossSalary);

        resetLegacyDeductionsToZero(item);

        item.setNetSalary(grossSalary.subtract(deduction).max(BigDecimal.ZERO));

        List<SalaryAdvanceAllocation> oldAllocations = salaryAdvanceAllocationRepository.findByPayrollItem_ItemId(payrollItemId);
        if (oldAllocations != null && !oldAllocations.isEmpty()) {
            salaryAdvanceAllocationRepository.deleteAll(oldAllocations);
            salaryAdvanceAllocationRepository.flush();
        }

        BigDecimal netSalary = item.getNetSalary();
        BigDecimal allocatedAdvance = reserveAdvanceAllocationsForItem(item, netSalary);
        item.setAdvanceAllocatedAmount(allocatedAdvance);
        item.setRemainingPayable(netSalary.subtract(allocatedAdvance).max(BigDecimal.ZERO));

        payrollItemRepository.save(item);
        payrollItemRepository.flush();
        recalculatePeriodTotals(period.getPeriodId());
    }

    /**
     * [Chức năng]: Tính toán lại toàn bộ kỳ bảng lương DRAFT (đồng bộ lại nhân sự, chấm công, hoa hồng, tạm ứng) nhưng vẫn bảo toàn các khoản thưởng/giảm trừ/ghi chú kế toán đã nhập tay.
     * [Mục đích]: Khóa kỳ lương (findByIdForUpdate), lưu tạm điều chỉnh thủ công, gỡ liên kết hoa hồng/xóa allocation và dòng lương cũ, sau đó nạp lại toàn bộ qua populatePeriodItems.
     */
    @Override
    public PayrollPeriod recalculatePeriod(Long periodId, Long currentUserId) {
        requirePayrollPermission(currentUserId);
        PayrollPeriod period = payrollPeriodRepository.findByIdForUpdate(periodId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kỳ lương: " + periodId));
        validateBranchAccess(period.getBranch().getBranchId(), currentUserId);
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

        // deleteByPeriodId xóa persistence context -> nạp lại PayrollPeriod managed trước khi populate lại
        PayrollPeriod reloadedPeriod = payrollPeriodRepository.findByIdForUpdate(periodId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kỳ lương: " + periodId));
        populatePeriodItems(reloadedPeriod, preserved);

        return payrollPeriodRepository.findById(periodId).orElseThrow();
    }

    /**
     * [Chức năng]: Trình duyệt kỳ bảng lương, chuyển trạng thái từ DRAFT sang SUBMITTED.
     * [Mục đích]: Khóa kỳ lương, kiểm tra quyền FINANCE_PAYROLL và chi nhánh, đảm bảo bảng lương có nhân sự và đối soát khớp 100% chi tiết hoa hồng đã gắn (reconcileBoundCommissionsForPeriod) trước khi lưu người trình duyệt.
     */
    @Override
    public PayrollPeriod submitPeriod(Long periodId, Long currentUserId) {
        requirePayrollPermission(currentUserId);
        PayrollPeriod period = payrollPeriodRepository.findByIdForUpdate(periodId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kỳ lương: " + periodId));
        validateBranchAccess(period.getBranch().getBranchId(), currentUserId);
        if (period.getStatus() != PayrollStatus.DRAFT) {
            throw new IllegalStateException("Kỳ lương không ở trạng thái Nháp (DRAFT)");
        }

        List<PayrollItem> items = payrollItemRepository.findByPayrollPeriod_PeriodId(periodId);
        if (items.isEmpty()) {
            throw new IllegalStateException("Bảng lương chưa có nhân viên nào. Không thể trình duyệt.");
        }
        reconcileBoundCommissionsForPeriod(period, items);

        StaffProfile submitter = staffProfileRepository.findByUser_UserId(currentUserId).orElse(null);

        period.setStatus(PayrollStatus.SUBMITTED);
        period.setSubmittedBy(submitter);
        period.setSubmittedAt(LocalDateTime.now());
        period.setRejectionReason(null);

        return payrollPeriodRepository.save(period);
    }

    /**
     * [Chức năng]: Phê duyệt kỳ bảng lương đang chờ duyệt, chuyển trạng thái từ SUBMITTED sang APPROVED.
     * [Mục đích]: Kiểm tra quyền Admin/FINANCE_PAYROLL, khóa kỳ lương (findByIdForUpdate), xác nhận trạng thái SUBMITTED và ghi nhận người phê duyệt cùng thời điểm duyệt.
     */
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
        List<PayrollItem> items = payrollItemRepository.findByPayrollPeriod_PeriodId(periodId);

        StaffProfile approver = staffProfileRepository.findByUser_UserId(currentUserId).orElse(null);

        period.setStatus(PayrollStatus.APPROVED);
        period.setApprovedBy(approver);
        period.setApprovedAt(LocalDateTime.now());
        period.setRejectionReason(null);

        return payrollPeriodRepository.save(period);
    }

    /**
     * [Chức năng]: Từ chối hoặc trả về chỉnh sửa kỳ bảng lương từ SUBMITTED/APPROVED về lại trạng thái DRAFT kèm lý do.
     * [Mục đích]: Kiểm tra quyền Admin/FINANCE_PAYROLL, khóa kỳ lương, chặn trả về nếu đã PAID, xóa thông tin duyệt cũ và tự động hủy phiếu chi liên kết (nếu có) trước khi lưu.
     */
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

    /**
     * [Chức năng]: Xác nhận chi trả kỳ bảng lương đã phê duyệt (APPROVED -> PAID), tất toán tạm ứng, đánh dấu hoa hồng PAID và tự động tạo phiếu chi vận hành.
     * [Mục đích]: Khóa kỳ lương, đối soát toàn bộ bất biến số liệu (hoa hồng, Gross/Net/tạm ứng/thực trả của từng dòng và tổng kỳ), khóa bi quan các phiếu tạm ứng theo thứ tự ID tăng dần chống deadlock để trừ số dư/chuyển SETTLED, cập nhật PayrollItem/PayrollPeriod sang PAID và sinh OperatingExpense nguồn PAYROLL nếu tổng thực chi > 0.
     */
    @Override
    public PayrollPeriod payPeriod(Long periodId, RefundMethod paymentMethod, String paymentReference, Long currentUserId) {
        if (currentUserId == null) {
            throw new AccessDeniedException("Người dùng chưa đăng nhập");
        }
        requirePayrollPermission(currentUserId);

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
        reconcileBoundCommissionsForPeriod(period, items);

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

            BigDecimal deduction = item.getDeductionAmount() != null ? item.getDeductionAmount() : BigDecimal.ZERO;
            BigDecimal expectedNet = gross.subtract(deduction).max(BigDecimal.ZERO);

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
                        + " (Tổng Gross: " + savedPeriod.getTotalGrossSalary() + " VNĐ, Đã cấn trừ tạm ứng: "
                        + savedPeriod.getTotalAdvanceAllocated() + " VNĐ, Thực chi: " + savedPeriod.getTotalRemainingPayable() + " VNĐ)");
                expense.setCreatedBy(currentUser);
                expense.setIsCancelled(false);
                operatingExpenseRepository.save(expense);
            }
        }

        if (serviceCommissionRepository != null) {
            payrollPeriodRepository.flush();
            serviceCommissionRepository.markCommissionsAsPaidForPeriod(periodId);
        }

        return savedPeriod;
    }

    /**
     * [Chức năng]: Xóa vĩnh viễn một kỳ bảng lương đang ở trạng thái Nháp (DRAFT) và giải phóng toàn bộ dữ liệu liên kết.
     * [Mục đích]: Khóa kỳ lương, kiểm tra quyền FINANCE_PAYROLL/chi nhánh và trạng thái DRAFT, hủy phiếu chi liên kết (nếu có), gỡ gắn hoa hồng, xóa phân bổ tạm ứng, xóa các dòng lương và xóa bản ghi PayrollPeriod.
     */
    @Override
    public void deleteDraftPeriod(Long periodId, Long currentUserId) {
        requirePayrollPermission(currentUserId);
        PayrollPeriod period = payrollPeriodRepository.findByIdForUpdate(periodId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kỳ lương: " + periodId));
        validateBranchAccess(period.getBranch().getBranchId(), currentUserId);
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
        payrollPeriodRepository.flush();

        // deleteByPeriodId xóa persistence context -> nạp lại entity managed trước khi xóa
        payrollPeriodRepository.findByIdForUpdate(periodId).ifPresent(payrollPeriodRepository::delete);
    }

    /**
     * [Chức năng]: Tìm danh sách hoa hồng PENDING phát sinh muộn trong tháng của các nhân sự thuộc kỳ lương nhưng chưa được gắn vào kỳ lương này.
     * [Mục đích]: Hiển thị cảnh báo trên màn hình chi tiết kỳ lương đã trình duyệt/duyệt/chi trả để kế toán biết có hoa hồng phát sinh bổ sung sau khi chốt số liệu.
     */
    @Override
    @Transactional(readOnly = true)
    public List<ServiceCommission> getLateUnboundCommissionsForPeriod(Long periodId, Long currentUserId) {
        if (serviceCommissionRepository == null) {
            return Collections.emptyList();
        }
        PayrollPeriod period = getPeriodDetail(periodId, currentUserId);
        if (period.getYear() == null || period.getMonth() == null) {
            return Collections.emptyList();
        }
        List<PayrollItem> items = payrollItemRepository.findByPayrollPeriod_PeriodId(period.getPeriodId());
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> staffIds = items.stream()
                .map(i -> i.getStaff() != null ? i.getStaff().getStaffId() : null)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (staffIds.isEmpty()) {
            return Collections.emptyList();
        }
        LocalDate periodStartDate = LocalDate.of(period.getYear(), period.getMonth(), 1);
        LocalDateTime startDateTime = periodStartDate.atStartOfDay();
        LocalDateTime endDateExclusive = periodStartDate.plusMonths(1).atStartOfDay();
        List<ServiceCommission> late = serviceCommissionRepository.findUnboundPendingCommissionsForStaffListInPeriod(
                staffIds, startDateTime, endDateExclusive);
        return late != null ? late : Collections.emptyList();
    }

    /**
     * [Hàm hỗ trợ]: Đối soát toàn bộ bản ghi hoa hồng đã gắn với các dòng lương của kỳ (đều phải PENDING, dịch vụ COMPLETED và tổng tiền khớp chính xác với từng PayrollItem và PayrollPeriod).
     */
    private void reconcileBoundCommissionsForPeriod(PayrollPeriod period, List<PayrollItem> items) {
        if (serviceCommissionRepository == null || period == null || items == null) {
            return;
        }
        List<ServiceCommission> boundAll = serviceCommissionRepository.findByPayrollItem_PayrollPeriod_PeriodId(period.getPeriodId());
        Map<Long, List<ServiceCommission>> boundByItemId = new HashMap<>();
        if (boundAll != null) {
            for (ServiceCommission sc : boundAll) {
                if (sc.getPayrollItem() != null && sc.getPayrollItem().getItemId() != null) {
                    boundByItemId.computeIfAbsent(sc.getPayrollItem().getItemId(), k -> new ArrayList<>()).add(sc);
                }
            }
        }

        BigDecimal runningCommission = BigDecimal.ZERO;
        for (PayrollItem item : items) {
            BigDecimal itemCommission = item.getTotalCommission() != null ? item.getTotalCommission() : BigDecimal.ZERO;
            runningCommission = runningCommission.add(itemCommission);

            List<ServiceCommission> itemBound = boundByItemId.getOrDefault(item.getItemId(), Collections.emptyList());
            BigDecimal boundSum = BigDecimal.ZERO;
            for (ServiceCommission sc : itemBound) {
                if (sc.getStatus() != CommissionStatus.PENDING) {
                    throw new IllegalStateException("Hoa hồng ID " + sc.getCommissionId() + " gắn vào bảng lương không ở trạng thái PENDING.");
                }
                if (sc.getPerformedService() != null && sc.getPerformedService().getStatus() != PerformedServiceStatus.COMPLETED) {
                    throw new IllegalStateException("Hoa hồng ID " + sc.getCommissionId() + " thuộc dịch vụ không còn ở trạng thái COMPLETED. Vui lòng tính lại bảng lương.");
                }
                boundSum = boundSum.add(sc.getCommissionAmount() != null ? sc.getCommissionAmount() : BigDecimal.ZERO);
            }
            if (itemCommission.compareTo(boundSum) != 0) {
                throw new IllegalStateException("Tổng hoa hồng của nhân sự "
                        + (item.getStaff() != null ? item.getStaff().getFullName() : item.getItemId())
                        + " (" + itemCommission + " đ) không khớp với chi tiết hoa hồng đã gắn (" + boundSum
                        + " đ). Vui lòng tính toán lại bảng lương trước khi chốt.");
            }
        }

        if (period.getTotalCommission() != null && period.getTotalCommission().compareTo(runningCommission) != 0) {
            throw new IllegalStateException("Tổng hoa hồng của kỳ (" + period.getTotalCommission()
                    + ") không khớp tổng các dòng (" + runningCommission + ").");
        }
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra người dùng có vai trò Admin hoặc quyền FINANCE_PAYROLL đang hoạt động trước khi thực hiện thao tác thay đổi bảng lương.
     */
    private void requirePayrollPermission(Long currentUserId) {
        if (currentUserId == null || (!userRepository.hasActiveAdminRole(currentUserId)
                && !userRepository.hasActivePermission(currentUserId, "FINANCE_PAYROLL"))) {
            throw new AccessDeniedException("Bạn không có quyền quản lý bảng lương (FINANCE_PAYROLL)");
        }
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra người dùng có quyền truy cập chi nhánh mục tiêu thông qua vai trò Admin, phân công ACTIVE hoặc chi nhánh mặc định.
     */
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
            boolean matchesUserBranch = staffBranchAssignmentRepository.findActiveBranchIdsByUserId(currentUserId, AssignmentStatus.ACTIVE).isEmpty()
                    && userRepository.findById(currentUserId).map(u -> u.getBranch() != null && branchId.equals(u.getBranch().getBranchId())).orElse(false);
            if (!matchesUserBranch) {
                throw new AccessDeniedException("Bạn không có quyền truy cập dữ liệu của chi nhánh này");
            }
        }
    }

    /**
     * [Hàm hỗ trợ]: Đếm số ngày công làm việc thực tế của nhân viên trong khoảng thời gian kỳ lương dựa trên dữ liệu chấm công (có check-in hoặc trạng thái ON_TIME/LATE/EARLY_LEAVE).
     */
    private BigDecimal resolveActualWorkDays(Long staffId, LocalDate periodStartDate, LocalDate periodEndDate) {
        if (attendanceRecordRepository == null || staffId == null || periodStartDate == null || periodEndDate == null) {
            return BigDecimal.ZERO;
        }
        List<AttendanceRecord> attendanceRecords = attendanceRecordRepository.findByStaffAndRange(
                staffId,
                periodStartDate,
                periodEndDate
        );
        if (attendanceRecords == null || attendanceRecords.isEmpty()) {
            return BigDecimal.ZERO;
        }
        long workedDays = attendanceRecords.stream()
                .filter(a -> a.getCheckInAt() != null
                        || a.getStatus() == AttendanceStatus.ON_TIME
                        || a.getStatus() == AttendanceStatus.LATE
                        || a.getStatus() == AttendanceStatus.EARLY_LEAVE)
                .count();
        return BigDecimal.valueOf(workedDays);
    }

    /**
     * [Hàm hỗ trợ]: Đặt toàn bộ các trường khấu trừ bảo hiểm và thuế TNCN cũ về 0/null theo chính sách tính lương đơn giản hiện hành.
     */
    private void resetLegacyDeductionsToZero(PayrollItem item) {
        item.setInsuranceEmployeeDeduction(BigDecimal.ZERO);
        item.setInsuranceEmployerAmount(BigDecimal.ZERO);
        item.setInsuranceParticipationStatus(InsuranceParticipationStatus.EXEMPT);
        item.setInsuranceBaseSalarySnapshot(BigDecimal.ZERO);
        item.setInsuranceEmployeeRateSnapshot(BigDecimal.ZERO);
        item.setInsuranceEmployerRateSnapshot(BigDecimal.ZERO);
        item.setInsuranceBasisNote(null);
        item.setPersonalDeductionAmount(BigDecimal.ZERO);
        item.setDependentDeductionAmount(BigDecimal.ZERO);
        item.setTaxableIncome(BigDecimal.ZERO);
        item.setAssessableIncome(BigDecimal.ZERO);
        item.setPitAmount(BigDecimal.ZERO);
        item.setTaxPolicyCode(null);
        item.setPitCalculationMethod(null);
        item.setPitScheduleSnapshot(null);
        item.setTaxCalculationDetail(null);
    }
}
