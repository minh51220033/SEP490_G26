package com.cvdcms;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.LoyaltyService;
import com.cvdcms.service.impl.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class FinanceFt24Ft25Ft32UnitTest {

    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private InvoiceAuditLogRepository invoiceAuditLogRepository;
    @Mock
    private PatientProfileRepository patientProfileRepository;
    @Mock
    private BranchRepository branchRepository;
    @Mock
    private StaffProfileRepository staffProfileRepository;
    @Mock
    private StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private RefundRepository refundRepository;
    @Mock
    private LoyaltyService loyaltyService;

    @Mock
    private OperatingExpenseRepository operatingExpenseRepository;
    @Mock
    private PayrollPeriodRepository payrollPeriodRepository;
    @Mock
    private PayrollItemRepository payrollItemRepository;
    @Mock
    private SalaryAdvanceRepository salaryAdvanceRepository;
    @Mock
    private ServiceCommissionRepository serviceCommissionRepository;
    @Mock
    private CommissionPolicyRepository commissionPolicyRepository;
    @Mock
    private ServiceRepository serviceRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private OwnerDistributionRepository ownerDistributionRepository;
    @Mock
    private OwnerDistributionItemRepository ownerDistributionItemRepository;
    @Mock
    private SalaryAdvanceAllocationRepository salaryAdvanceAllocationRepository;
    @Mock
    private AttendanceRecordRepository attendanceRecordRepository;
    @Mock
    private SystemAuditLogRepository systemAuditLogRepository;

    private DebtServiceImpl debtService;
    private RefundServiceImpl refundService;
    private OperatingExpenseServiceImpl operatingExpenseService;
    private SalaryAdvanceServiceImpl salaryAdvanceService;
    private PayrollServiceImpl payrollService;
    private OwnerDistributionServiceImpl ownerDistributionService;
    private CommissionPolicyServiceImpl commissionPolicyService;

    private Branch mockBranch;
    private User mockAdminUser;
    private User mockAccountantUser;
    private StaffProfile mockAccountantStaff;
    private StaffProfile mockDoctorStaff;
    private PatientProfile mockPatient;

    @BeforeEach
    void setUp() {
        debtService = new DebtServiceImpl(invoiceRepository, patientProfileRepository);
        refundService = new RefundServiceImpl(
                refundRepository, invoiceRepository, invoiceAuditLogRepository,
                patientProfileRepository, branchRepository, staffProfileRepository,
                staffBranchAssignmentRepository, userRepository, loyaltyService
        );
        operatingExpenseService = new OperatingExpenseServiceImpl(
                operatingExpenseRepository, branchRepository, staffBranchAssignmentRepository, userRepository,
                systemAuditLogRepository
        );
        salaryAdvanceService = new SalaryAdvanceServiceImpl(
                salaryAdvanceRepository, operatingExpenseRepository, staffProfileRepository,
                staffBranchAssignmentRepository, branchRepository, userRepository
        );
        payrollService = new PayrollServiceImpl(
                payrollPeriodRepository, payrollItemRepository, salaryAdvanceRepository,
                salaryAdvanceAllocationRepository, attendanceRecordRepository,
                operatingExpenseRepository, serviceCommissionRepository,
                staffProfileRepository, staffBranchAssignmentRepository, branchRepository,
                userRepository
        );
        ownerDistributionService = new OwnerDistributionServiceImpl(
                ownerDistributionRepository, staffProfileRepository, branchRepository, userRepository,
                staffBranchAssignmentRepository
        );
        commissionPolicyService = new CommissionPolicyServiceImpl(
                commissionPolicyRepository, serviceRepository, roleRepository,
                staffProfileRepository, userRepository
        );

        lenient().when(payrollItemRepository.save(any(PayrollItem.class))).thenAnswer(inv -> {
            PayrollItem item = inv.getArgument(0);
            if (item.getItemId() == null) {
                item.setItemId(999L);
            }
            return item;
        });

        mockBranch = new Branch();
        mockBranch.setBranchId(1L);
        mockBranch.setBranchName("Cơ sở Cầu Giấy");

        mockAdminUser = new User();
        mockAdminUser.setUserId(1L);
        mockAdminUser.setUsername("admin");
        mockAdminUser.setIsActive(true);
        mockAdminUser.setStatus("ACTIVE");

        mockAccountantUser = new User();
        mockAccountantUser.setUserId(2L);
        mockAccountantUser.setUsername("ketoan");
        mockAccountantUser.setIsActive(true);
        mockAccountantUser.setStatus("ACTIVE");

        mockAccountantStaff = new StaffProfile();
        mockAccountantStaff.setStaffId(20L);
        mockAccountantStaff.setFullName("Nguyễn Văn Kế Toán");
        mockAccountantStaff.setStaffCode("KT01");
        mockAccountantStaff.setStatus(StaffStatus.ACTIVE);
        mockAccountantStaff.setBaseSalary(new BigDecimal("12000000"));
        mockAccountantStaff.setAllowanceAmount(new BigDecimal("1500000"));

        mockDoctorStaff = new StaffProfile();
        mockDoctorStaff.setStaffId(30L);
        mockDoctorStaff.setFullName("Bác Sĩ Lê Hoàng");
        mockDoctorStaff.setStaffCode("BS01");
        mockDoctorStaff.setStatus(StaffStatus.ACTIVE);
        mockDoctorStaff.setBaseSalary(new BigDecimal("25000000"));
        mockDoctorStaff.setAllowanceAmount(new BigDecimal("3000000"));

        mockPatient = new PatientProfile();
        mockPatient.setProfileId(100L);
        mockPatient.setFullName("Trần Bệnh Nhân");
        mockPatient.setPatientCode("BN001");
        mockPatient.setPhoneNumber("0987654321");
    }

    // =========================================================================
    // NHÓM 1: FT24 - CÔNG NỢ, HOÀN TIỀN, ĐIỀU CHỈNH NỢ
    // =========================================================================

    @Test
    @DisplayName("Case 1: Công nợ chỉ tính từ hóa đơn còn nợ, loại bỏ CANCELLED và không cộng dồn từ Treatment Plan")
    void testCase1_DebtsCalculationExcludesCancelledAndPlans() {
        PatientDebtSummaryDTO dto = new PatientDebtSummaryDTO(
                100L, "BN001", "Trần Bệnh Nhân", "0987654321",
                BigDecimal.ZERO, 1L, new BigDecimal("5000000"), new BigDecimal("2000000"), new BigDecimal("3000000"),
                LocalDateTime.now()
        );
        Page<PatientDebtSummaryDTO> page = new PageImpl<>(List.of(dto));

        when(invoiceRepository.findPatientDebtsByBranch(eq(1L), any(), any(), any()))
                .thenReturn(page);

        Page<PatientDebtSummaryDTO> result = debtService.getPatientDebts(1L, List.of(1L), null, null, PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());
        PatientDebtSummaryDTO item = result.getContent().get(0);
        assertEquals(new BigDecimal("3000000"), item.getTotalDebt());
        assertEquals(new BigDecimal("2000000"), item.getTotalPaid());
        assertEquals(new BigDecimal("5000000"), item.getTotalBilled());
    }

    @Test
    @DisplayName("Case 2: Hóa đơn bị hủy (CANCELLED) không được hoàn tiền")
    void testCase2_CancelledInvoiceCannotBeRefunded() {
        Invoice invoice = new Invoice();
        invoice.setInvoiceId(10L);
        invoice.setStatus(InvoiceStatus.CANCELLED);
        invoice.setPaidAmount(new BigDecimal("2000000"));

        when(invoiceRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(invoice));

        RefundRequestDTO req = new RefundRequestDTO();
        req.setInvoiceId(10L);
        req.setAmount(new BigDecimal("1000000"));
        req.setRefundMethod(RefundMethod.CASH);
        req.setReason("Khách không hài lòng");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                refundService.processRefund(req, 1L)
        );
        assertTrue(ex.getMessage().contains("đã bị hủy"));
    }

    @Test
    @DisplayName("Case 3: Hoàn tiền dịch vụ với reduceInvoiceTotal = true giảm cả nghĩa vụ nợ và ghi 2 audit logs")
    void testCase3_RefundWithReduceInvoiceTotal() {
        Invoice invoice = new Invoice();
        invoice.setInvoiceId(10L);
        invoice.setStatus(InvoiceStatus.CONFIRMED);
        invoice.setBranch(mockBranch);
        invoice.setPatientProfile(mockPatient);
        invoice.setTotalServiceAmount(new BigDecimal("10000000"));
        invoice.setTotalMedicineAmount(BigDecimal.ZERO);
        invoice.setDiscountAmount(BigDecimal.ZERO);
        invoice.setPromotionDiscount(BigDecimal.ZERO);
        invoice.setPointDiscount(BigDecimal.ZERO);
        invoice.setTotalAmount(new BigDecimal("10000000"));
        invoice.setPaidAmount(new BigDecimal("6000000"));
        invoice.setRemainingDebt(new BigDecimal("4000000"));
        invoice.setPaymentStatus(InvoicePaymentStatus.PARTIAL);

        when(invoiceRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAdminUser));
        when(staffProfileRepository.findByUser_UserId(1L)).thenReturn(Optional.of(mockAccountantStaff));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));

        RefundRequestDTO req = new RefundRequestDTO();
        req.setInvoiceId(10L);
        req.setAmount(new BigDecimal("2000000"));
        req.setRefundMethod(RefundMethod.BANK_TRANSFER);
        req.setReason("Hủy dịch vụ nha chu");
        req.setReduceInvoiceTotal(true); // Giảm cả nghĩa vụ nợ

        Refund refund = refundService.processRefund(req, 1L);

        assertNotNull(refund);
        assertEquals(new BigDecimal("2000000"), refund.getAmount());
        // PaidAmount giảm từ 6tr -> 4tr
        assertEquals(new BigDecimal("4000000"), invoice.getPaidAmount());
        // TotalAmount giảm từ 10tr -> 8tr
        assertEquals(new BigDecimal("8000000"), invoice.getTotalAmount());
        // RemainingDebt vẫn là 8tr - 4tr = 4tr (không bị tăng lên 6tr)
        assertEquals(new BigDecimal("4000000"), invoice.getRemainingDebt());

        // Kiểm tra đủ 2 Audit Logs: REFUND và ADJUST
        verify(invoiceAuditLogRepository, times(2)).save(any(InvoiceAuditLog.class));
    }

    @Test
    @DisplayName("Case 4: Khóa chống submit đúp hoàn tiền thông qua idempotencyKey")
    void testCase4_RefundIdempotencyGuard() {
        Refund existing = new Refund();
        existing.setRefundId(55L);
        existing.setIdempotencyKey("KEY-XYZ-123");
        existing.setAmount(new BigDecimal("500000"));
        existing.setBranch(mockBranch);
        existing.setPatientProfile(mockPatient);
        Invoice inv = new Invoice();
        inv.setInvoiceId(10L);
        existing.setInvoice(inv);
        existing.setReduceInvoiceTotal(false);

        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAdminUser));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        when(staffProfileRepository.findByUser_UserId(1L)).thenReturn(Optional.of(mockAccountantStaff));
        when(refundRepository.findByIdempotencyKey("KEY-XYZ-123")).thenReturn(Optional.of(existing));

        RefundRequestDTO req = new RefundRequestDTO();
        req.setIdempotencyKey("KEY-XYZ-123");
        req.setInvoiceId(10L);
        req.setAmount(new BigDecimal("500000"));
        req.setReason("Khách yêu cầu hoàn tiền");

        Refund result = refundService.processRefund(req, 1L);
        assertEquals(55L, result.getRefundId());
        verify(invoiceRepository, never()).findById(any());
    }

    // =========================================================================
    // NHÓM 2: FT25 - CHI PHÍ VẬN HÀNH
    // =========================================================================

    @Test
    @DisplayName("Case 5: Khai báo chi phí vận hành thủ công thành công")
    void testCase5_CreateManualOperatingExpense() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAdminUser));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(mockBranch));
        when(operatingExpenseRepository.save(any(OperatingExpense.class))).thenAnswer(inv -> inv.getArgument(0));

        OperatingExpenseRequestDTO dto = new OperatingExpenseRequestDTO();
        dto.setBranchId(1L);
        dto.setCategory("Chi phí thuê mặt bằng");
        dto.setAmount(new BigDecimal("25000000"));
        dto.setExpenseDate(LocalDate.now());
        dto.setRecipientName("Chủ nhà số 15 Cầu Giấy");
        dto.setPaymentMethod(RefundMethod.BANK_TRANSFER);
        dto.setDescription("Tiền thuê nhà tháng 10/2026");

        OperatingExpense expense = operatingExpenseService.createManualExpense(dto, 1L);

        assertNotNull(expense);
        assertEquals(ExpenseSourceType.MANUAL, expense.getSourceType());
        assertEquals(new BigDecimal("25000000"), expense.getAmount());
        assertFalse(expense.getIsCancelled());
    }

    @Test
    @DisplayName("Case 6: Ngăn chặn sửa/hủy phiếu chi từ Bảng lương hoặc Tạm ứng từ màn hình chi phí vận hành")
    void testCase6_PreventCancellingSystemOperatingExpense() {
        OperatingExpense systemExpense = new OperatingExpense();
        systemExpense.setExpenseId(100L);
        systemExpense.setSourceType(ExpenseSourceType.PAYROLL); // Tạo từ bảng lương
        systemExpense.setIsCancelled(false);
        systemExpense.setBranch(mockBranch);

        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAdminUser));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        when(operatingExpenseRepository.findById(100L)).thenReturn(Optional.of(systemExpense));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                operatingExpenseService.cancelExpense(100L, "Kế toán muốn hủy", 1L)
        );
        assertTrue(ex.getMessage().contains("Không thể hủy phiếu chi tự động"));
    }

    // =========================================================================
    // NHÓM 3: FT32 - BẢNG LƯƠNG, TẠM ỨNG & PHÂN CHIA LỢI NHUẬN
    // =========================================================================

    @Test
    @DisplayName("Case 7: Lương cố định tháng lấy trực tiếp từ StaffProfile, ngày công tham khảo không tự trừ lương")
    void testCase7_FixedMonthlySalaryWithoutAttendancePenalty() {
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        when(payrollPeriodRepository.existsByBranch_BranchIdAndMonthAndYear(1L, 10, 2026)).thenReturn(false);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(mockBranch));

        // Nhân viên có lương cơ bản 12tr, phụ cấp 1.5tr
        StaffBranchAssignment assignment = new StaffBranchAssignment();
        assignment.setStaff(mockAccountantStaff);
        assignment.setBranch(mockBranch);
        assignment.setStatus(AssignmentStatus.ACTIVE);
        assignment.setStartDate(LocalDate.of(2026, 1, 1));
        assignment.setIsPrimary(true);

        when(staffBranchAssignmentRepository.findByBranchAndStatus(1L, AssignmentStatus.ACTIVE))
                .thenReturn(List.of(assignment));
        when(salaryAdvanceRepository.findActiveAdvancesForSettlementForUpdate(any(), any(), any()))
                .thenReturn(Collections.emptyList());

        when(payrollItemRepository.save(any(PayrollItem.class))).thenAnswer(inv -> {
            PayrollItem item = inv.getArgument(0);
            item.setItemId(101L);
            return item;
        });

        when(payrollPeriodRepository.save(any(PayrollPeriod.class))).thenAnswer(inv -> {
            PayrollPeriod p = inv.getArgument(0);
            p.setPeriodId(10L);
            return p;
        });
        when(payrollPeriodRepository.findById(10L)).thenReturn(Optional.of(new PayrollPeriod()));

        PayrollPeriodCreateDTO createDTO = new PayrollPeriodCreateDTO();
        createDTO.setBranchId(1L);
        createDTO.setMonth(10);
        createDTO.setYear(2026);

        PayrollPeriod period = payrollService.createPeriod(createDTO, 1L);
        assertNotNull(period);

        // Verify item saved with actualBaseSalary = baseSalary (không bị trừ lương theo ngày công)
        verify(payrollItemRepository).save(argThat(item ->
                item.getActualBaseSalary().compareTo(new BigDecimal("12000000")) == 0 &&
                item.getTotalAllowance().compareTo(new BigDecimal("1500000")) == 0 &&
                item.getNetSalary().compareTo(new BigDecimal("13500000")) == 0
        ));
    }

    @Test
    @DisplayName("Case 8: Khấu trừ tạm ứng vào bảng lương tự động và không làm lương thực chi âm")
    void testCase8_SalaryAdvanceDeductionCannotMakeRemainingNegative() {
        SalaryAdvance advance = new SalaryAdvance();
        advance.setAdvanceId(1L);
        advance.setStaff(mockAccountantStaff);
        advance.setAmount(new BigDecimal("20000000")); // Tạm ứng 20tr (lớn hơn lương net 13.5tr)
        advance.setRemainingAmount(new BigDecimal("20000000"));
        advance.setStatus(SalaryAdvanceStatus.PAID);

        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(mockBranch));

        StaffBranchAssignment assignment = new StaffBranchAssignment();
        assignment.setStaff(mockAccountantStaff);
        assignment.setBranch(mockBranch);
        assignment.setStatus(AssignmentStatus.ACTIVE);
        assignment.setStartDate(LocalDate.of(2026, 1, 1));
        assignment.setIsPrimary(true);

        when(staffBranchAssignmentRepository.findByBranchAndStatus(1L, AssignmentStatus.ACTIVE))
                .thenReturn(List.of(assignment));
        when(salaryAdvanceRepository.findActiveAdvancesForSettlementForUpdate(eq(20L), eq(SalaryAdvanceStatus.PAID), eq(BigDecimal.ZERO)))
                .thenReturn(List.of(advance));

        when(payrollItemRepository.save(any(PayrollItem.class))).thenAnswer(inv -> {
            PayrollItem item = inv.getArgument(0);
            item.setItemId(102L);
            return item;
        });

        when(payrollPeriodRepository.save(any(PayrollPeriod.class))).thenAnswer(inv -> {
            PayrollPeriod p = inv.getArgument(0);
            p.setPeriodId(11L);
            return p;
        });
        when(payrollPeriodRepository.findById(11L)).thenReturn(Optional.of(new PayrollPeriod()));

        PayrollPeriodCreateDTO createDTO = new PayrollPeriodCreateDTO();
        createDTO.setBranchId(1L);
        createDTO.setMonth(10);
        createDTO.setYear(2026);

        payrollService.createPeriod(createDTO, 1L);

        // Net = 13.5tr, tạm ứng 20tr -> chỉ khấu trừ tối đa 13.5tr, thực trả = 0đ (không âm)
        verify(payrollItemRepository).save(argThat(item ->
                item.getNetSalary().compareTo(new BigDecimal("13500000")) == 0 &&
                item.getAdvanceAllocatedAmount().compareTo(new BigDecimal("13500000")) == 0 &&
                item.getRemainingPayable().compareTo(BigDecimal.ZERO) == 0
        ));
    }

    @Test
    @DisplayName("Case 9: Chi tạm ứng (payAdvance) tạo 1 phiếu chi vận hành duy nhất")
    void testCase9_PayAdvanceCreatesSingleOperatingExpense() {
        SalaryAdvance advance = new SalaryAdvance();
        advance.setAdvanceId(5L);
        advance.setAdvanceCode("TU123");
        advance.setStaff(mockAccountantStaff);
        advance.setBranch(mockBranch);
        advance.setAmount(new BigDecimal("5000000"));
        advance.setReason("Chi tiêu cá nhân");
        advance.setStatus(SalaryAdvanceStatus.APPROVED);

        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAdminUser));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        when(salaryAdvanceRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(advance));
        when(salaryAdvanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(operatingExpenseRepository.existsBySalaryAdvance_AdvanceId(5L)).thenReturn(false);

        SalaryAdvance paid = salaryAdvanceService.payAdvance(5L, RefundMethod.BANK_TRANSFER, 1L);

        assertEquals(SalaryAdvanceStatus.PAID, paid.getStatus());
        assertEquals(new BigDecimal("5000000"), paid.getRemainingAmount());

        // Kiểm tra OperatingExpense được tạo
        verify(operatingExpenseRepository, times(1)).save(argThat(exp ->
                exp.getSourceType() == ExpenseSourceType.SALARY_ADVANCE &&
                exp.getAmount().compareTo(new BigDecimal("5000000")) == 0 &&
                exp.getSalaryAdvance().getAdvanceId().equals(5L)
        ));
    }

    @Test
    @DisplayName("Case 10: Chi trả bảng lương (payPeriod) tạo 1 phiếu chi vận hành và tất toán tạm ứng")
    void testCase10_PayPeriodCreatesOperatingExpenseAndSettlesAdvances() {
        PayrollPeriod period = new PayrollPeriod();
        period.setPeriodId(99L);
        period.setPeriodName("Bảng lương 10/2026");
        period.setBranch(mockBranch);
        period.setMonth(10);
        period.setYear(2026);
        period.setStatus(PayrollStatus.APPROVED);
        period.setTotalSalary(new BigDecimal("15000000"));
        period.setTotalBaseSalary(new BigDecimal("15000000"));
        period.setTotalAllowance(BigDecimal.ZERO);
        period.setTotalBonus(BigDecimal.ZERO);
        period.setTotalCommission(BigDecimal.ZERO);
        period.setTotalDeduction(BigDecimal.ZERO);
        period.setTotalAdvanceAllocated(new BigDecimal("5000000"));
        period.setTotalRemainingPayable(new BigDecimal("10000000"));

        PayrollItem item = new PayrollItem();
        item.setItemId(1L);
        item.setPayrollPeriod(period);
        item.setStaff(mockAccountantStaff);
        item.setBaseSalary(new BigDecimal("15000000"));
        item.setTotalAllowance(BigDecimal.ZERO);
        item.setBonusAmount(BigDecimal.ZERO);
        item.setDeductionAmount(BigDecimal.ZERO);
        item.setNetSalary(new BigDecimal("15000000"));
        item.setAdvanceAllocatedAmount(new BigDecimal("5000000"));
        item.setRemainingPayable(new BigDecimal("10000000"));

        SalaryAdvance activeAdv = new SalaryAdvance();
        activeAdv.setAdvanceId(5L);
        activeAdv.setAmount(new BigDecimal("5000000"));
        activeAdv.setAllocatedAmount(BigDecimal.ZERO);
        activeAdv.setRemainingAmount(new BigDecimal("5000000"));
        activeAdv.setStatus(SalaryAdvanceStatus.PAID);

        SalaryAdvanceAllocation alloc = new SalaryAdvanceAllocation();
        alloc.setAllocationId(501L);
        alloc.setPayrollItem(item);
        alloc.setAdvance(activeAdv);
        alloc.setAllocatedAmount(new BigDecimal("5000000"));
        alloc.setStatus(AdvanceAllocationStatus.RESERVED);

        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAdminUser));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        when(payrollPeriodRepository.findByIdForUpdate(99L)).thenReturn(Optional.of(period));
        when(payrollPeriodRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(payrollItemRepository.findByPayrollPeriod_PeriodId(99L)).thenReturn(List.of(item));
        when(salaryAdvanceAllocationRepository.findByPayrollItem_ItemIdAndStatusForUpdate(1L, AdvanceAllocationStatus.RESERVED))
                .thenReturn(List.of(alloc));
        when(salaryAdvanceRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(activeAdv));
        when(operatingExpenseRepository.existsByPayrollPeriod_PeriodId(99L)).thenReturn(false);

        PayrollPeriod paidPeriod = payrollService.payPeriod(99L, RefundMethod.BANK_TRANSFER, "REF-123", 1L);

        assertEquals(PayrollStatus.PAID, paidPeriod.getStatus());
        // Tạm ứng đã được khấu trừ hết và đổi status thành SETTLED
        assertEquals(BigDecimal.ZERO, activeAdv.getRemainingAmount());
        assertEquals(SalaryAdvanceStatus.SETTLED, activeAdv.getStatus());

        // Kiểm tra tạo đúng 1 phiếu chi OperatingExpense từ PAYROLL
        verify(operatingExpenseRepository, times(1)).save(argThat(exp ->
                exp.getSourceType() == ExpenseSourceType.PAYROLL &&
                exp.getAmount().compareTo(new BigDecimal("10000000")) == 0 &&
                exp.getPayrollPeriod().getPeriodId().equals(99L)
        ));
    }

    @Test
    @DisplayName("Case 11: Cho phép tất toán bảng lương có thực chi = 0đ mà không tạo phiếu chi 0đ")
    void testCase11_ZeroPayablePayrollDoesNotCreateZeroExpense() {
        PayrollPeriod period = new PayrollPeriod();
        period.setPeriodId(101L);
        period.setBranch(mockBranch);
        period.setMonth(10);
        period.setYear(2026);
        period.setStatus(PayrollStatus.APPROVED);
        period.setTotalSalary(BigDecimal.ZERO);
        period.setTotalBaseSalary(BigDecimal.ZERO);
        period.setTotalAllowance(BigDecimal.ZERO);
        period.setTotalBonus(BigDecimal.ZERO);
        period.setTotalCommission(BigDecimal.ZERO);
        period.setTotalDeduction(BigDecimal.ZERO);
        period.setTotalAdvanceAllocated(BigDecimal.ZERO);
        period.setTotalRemainingPayable(BigDecimal.ZERO); // Thực chi = 0đ do đã trừ hết qua tạm ứng

        PayrollItem item = new PayrollItem();
        item.setItemId(101L);
        item.setStaff(mockAccountantStaff);
        item.setBaseSalary(BigDecimal.ZERO);
        item.setTotalAllowance(BigDecimal.ZERO);
        item.setBonusAmount(BigDecimal.ZERO);
        item.setDeductionAmount(BigDecimal.ZERO);
        item.setNetSalary(BigDecimal.ZERO);
        item.setAdvanceAllocatedAmount(BigDecimal.ZERO);
        item.setRemainingPayable(BigDecimal.ZERO);

        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAdminUser));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        when(payrollPeriodRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(period));
        when(payrollPeriodRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(payrollItemRepository.findByPayrollPeriod_PeriodId(101L)).thenReturn(List.of(item));

        PayrollPeriod paid = payrollService.payPeriod(101L, RefundMethod.BANK_TRANSFER, "REF-0", 1L);

        assertEquals(PayrollStatus.PAID, paid.getStatus());
        // KHÔNG được tạo phiếu chi 0đ
        verify(operatingExpenseRepository, never()).save(any());
    }

    @Test
    @DisplayName("Case 12: Luồng phê duyệt bảng lương bắt buộc qua Admin (SUBMITTED -> APPROVED)")
    void testCase12_PayrollApprovalWorkflowEnforced() {
        PayrollPeriod period = new PayrollPeriod();
        period.setPeriodId(102L);
        period.setStatus(PayrollStatus.SUBMITTED);

        // User không có quyền admin bị từ chối
        when(userRepository.hasActiveAdminRole(2L)).thenReturn(false);
        assertThrows(AccessDeniedException.class, () ->
                payrollService.approvePeriod(102L, 2L)
        );

        // User có quyền admin được duyệt
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        when(payrollPeriodRepository.findByIdForUpdate(102L)).thenReturn(Optional.of(period));
        when(payrollPeriodRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PayrollPeriod approved = payrollService.approvePeriod(102L, 1L);
        assertEquals(PayrollStatus.APPROVED, approved.getStatus());
    }

    @Test
    @DisplayName("Case 13: Phân chia lợi nhuận chủ phòng khám tách biệt hoàn toàn khỏi OperatingExpense")
    void testCase13_OwnerDistributionSeparateFromOperatingExpense() {
        OwnerDistribution dist = new OwnerDistribution();
        dist.setDistributionId(88L);
        dist.setStatus(OwnerDistributionStatus.APPROVED);
        dist.setTotalDistributedAmount(new BigDecimal("100000000"));

        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        when(ownerDistributionRepository.findWithDetailsById(88L)).thenReturn(Optional.of(dist));
        when(ownerDistributionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OwnerDistribution paidDist = ownerDistributionService.payDistribution(88L, RefundMethod.BANK_TRANSFER, "DIST-001", 1L);

        assertEquals(OwnerDistributionStatus.PAID, paidDist.getStatus());
        // Tuyệt đối không tạo OperatingExpense
        verify(operatingExpenseRepository, never()).save(any());
    }

    @Test
    @DisplayName("Case 14: Phân quyền chi nhánh (Branch scoping) từ chối truy cập ngoài chi nhánh phân công")
    void testCase14_BranchScopingDeniesUnauthorizedAccess() {
        when(userRepository.hasActiveAdminRole(2L)).thenReturn(false);
        when(staffBranchAssignmentRepository.hasActiveUserAssignment(2L, 999L, AssignmentStatus.ACTIVE)).thenReturn(false);

        OperatingExpenseRequestDTO dto = new OperatingExpenseRequestDTO();
        dto.setBranchId(999L);
        when(userRepository.findById(2L)).thenReturn(Optional.of(mockAccountantUser));

        assertThrows(AccessDeniedException.class, () ->
                operatingExpenseService.createManualExpense(dto, 2L)
        );
    }

    @Test
    @DisplayName("Case 15: Chống trừ trùng tạm ứng qua 2 kỳ lương liên tiếp (Sequential advance allocation)")
    void testCase15_SequentialAdvanceAllocationDoubleCountPrevented() {
        SalaryAdvance advance = new SalaryAdvance();
        advance.setAdvanceId(10L);
        advance.setStaff(mockAccountantStaff);
        advance.setAmount(new BigDecimal("5000000"));
        advance.setRemainingAmount(new BigDecimal("5000000"));
        advance.setStatus(SalaryAdvanceStatus.PAID);

        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(mockBranch));

        StaffBranchAssignment assignment = new StaffBranchAssignment();
        assignment.setStaff(mockAccountantStaff);
        assignment.setBranch(mockBranch);
        assignment.setStatus(AssignmentStatus.ACTIVE);
        assignment.setStartDate(LocalDate.of(2026, 1, 1));
        assignment.setIsPrimary(true);

        when(staffBranchAssignmentRepository.findByBranchAndStatus(1L, AssignmentStatus.ACTIVE))
                .thenReturn(List.of(assignment));
        when(salaryAdvanceRepository.findActiveAdvancesForSettlementForUpdate(eq(20L), eq(SalaryAdvanceStatus.PAID), eq(BigDecimal.ZERO)))
                .thenReturn(List.of(advance));

        when(payrollItemRepository.save(any(PayrollItem.class))).thenAnswer(inv -> {
            PayrollItem item = inv.getArgument(0);
            item.setItemId(101L);
            return item;
        });

        // Kỳ tháng 10: Chưa có kỳ nào giữ chỗ -> allocation = 5tr
        when(salaryAdvanceAllocationRepository.sumAllocatedByAdvanceAndStatusExcludingItem(
                eq(10L), eq(AdvanceAllocationStatus.RESERVED), any()))
                .thenReturn(BigDecimal.ZERO);

        when(payrollPeriodRepository.save(any(PayrollPeriod.class))).thenAnswer(inv -> {
            PayrollPeriod p = inv.getArgument(0);
            p.setPeriodId(101L);
            return p;
        });
        when(payrollPeriodRepository.findById(101L)).thenReturn(Optional.of(new PayrollPeriod()));

        PayrollPeriodCreateDTO createMonth10 = new PayrollPeriodCreateDTO();
        createMonth10.setBranchId(1L);
        createMonth10.setMonth(10);
        createMonth10.setYear(2026);

        payrollService.createPeriod(createMonth10, 1L);

        // Verify kỳ tháng 10 tạo reservation 5tr
        verify(salaryAdvanceAllocationRepository).save(argThat(alloc ->
                alloc.getAllocatedAmount().compareTo(new BigDecimal("5000000")) == 0 &&
                alloc.getStatus() == AdvanceAllocationStatus.RESERVED
        ));

        // Bây giờ kỳ tháng 11 được tạo trước khi tháng 10 được thanh toán:
        // sumAllocatedByAdvanceAndStatusExcludingItem trả về 5tr (đã bị tháng 10 giữ chỗ)
        when(salaryAdvanceAllocationRepository.sumAllocatedByAdvanceAndStatusExcludingItem(
                eq(10L), eq(AdvanceAllocationStatus.RESERVED), any()))
                .thenReturn(new BigDecimal("5000000"));

        when(payrollPeriodRepository.save(any(PayrollPeriod.class))).thenAnswer(inv -> {
            PayrollPeriod p = inv.getArgument(0);
            p.setPeriodId(102L);
            return p;
        });
        when(payrollPeriodRepository.findById(102L)).thenReturn(Optional.of(new PayrollPeriod()));

        PayrollPeriodCreateDTO createMonth11 = new PayrollPeriodCreateDTO();
        createMonth11.setBranchId(1L);
        createMonth11.setMonth(11);
        createMonth11.setYear(2026);

        payrollService.createPeriod(createMonth11, 1L);

        // Verify kỳ tháng 11: khoản tạm ứng khả dụng = 5tr - 5tr = 0tr -> advanceAllocatedAmount = 0
        verify(payrollItemRepository).save(argThat(item ->
                item.getPayrollPeriod().getPeriodId().equals(102L) &&
                item.getAdvanceAllocatedAmount().compareTo(BigDecimal.ZERO) == 0 &&
                item.getRemainingPayable().compareTo(new BigDecimal("13500000")) == 0
        ));
    }

    @Test
    @DisplayName("Case 16: Ngăn tính lương trùng cho một nhân viên trên nhiều chi nhánh trong cùng tháng/năm")
    void testCase16_PreventDuplicateSalaryAcrossBranchesForStaffInSameMonth() {
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(mockBranch));

        StaffBranchAssignment assignment = new StaffBranchAssignment();
        assignment.setStaff(mockAccountantStaff);
        assignment.setBranch(mockBranch);
        assignment.setStatus(AssignmentStatus.ACTIVE);
        assignment.setStartDate(LocalDate.of(2026, 1, 1));
        assignment.setIsPrimary(true);

        when(staffBranchAssignmentRepository.findByBranchAndStatus(1L, AssignmentStatus.ACTIVE))
                .thenReturn(List.of(assignment));

        // Nhân viên đã có bảng lương tháng 10/2026 tại chi nhánh khác
        when(payrollItemRepository.existsByStaff_StaffIdAndPeriodMonthAndPeriodYear(20L, 10, 2026)).thenReturn(true);

        when(payrollPeriodRepository.save(any(PayrollPeriod.class))).thenAnswer(inv -> {
            PayrollPeriod p = inv.getArgument(0);
            p.setPeriodId(105L);
            return p;
        });
        when(payrollPeriodRepository.findById(105L)).thenReturn(Optional.of(new PayrollPeriod()));

        PayrollPeriodCreateDTO createDTO = new PayrollPeriodCreateDTO();
        createDTO.setBranchId(1L);
        createDTO.setMonth(10);
        createDTO.setYear(2026);

        payrollService.createPeriod(createDTO, 1L);

        // Nhân viên bị bỏ qua, không được tạo dòng lương mới ở chi nhánh 1
        verify(payrollItemRepository, never()).save(argThat(item ->
                item.getStaff().getStaffId().equals(20L)
        ));
    }

    @Test
    @DisplayName("Case 17: Kế toán không có quyền duyệt hoặc từ chối tạm ứng lương (Chỉ Admin)")
    void testCase17_AccountantCannotApproveOrRejectSalaryAdvance() {
        when(userRepository.hasActiveAdminRole(2L)).thenReturn(false);

        // Kế toán duyệt tạm ứng -> bị AccessDeniedException
        assertThrows(AccessDeniedException.class, () ->
                salaryAdvanceService.approveAdvance(5L, 2L)
        );

        // Kế toán từ chối tạm ứng -> bị AccessDeniedException
        assertThrows(AccessDeniedException.class, () ->
                salaryAdvanceService.rejectAdvance(5L, "Từ chối", 2L)
        );
    }

    @Test
    @DisplayName("Case 18: Kế toán không có quyền bật/tắt hoặc chỉnh sửa chính sách hoa hồng")
    void testCase18_AccountantCannotModifyCommissionPolicy() {
        when(userRepository.hasActiveAdminRole(2L)).thenReturn(false);

        assertThrows(AccessDeniedException.class, () ->
                commissionPolicyService.togglePolicyStatus(1L, false, 2L)
        );

        CommissionPolicyDTO req = new CommissionPolicyDTO();
        assertThrows(AccessDeniedException.class, () ->
                commissionPolicyService.savePolicy(req, 2L)
        );
    }

    @Test
    @DisplayName("Case 19: Phát hiện xung đột khi gửi cùng idempotencyKey nhưng khác hóa đơn hoặc số tiền hoàn")
    void testCase19_IdempotencyKeyConflictDetection() {
        Refund existing = new Refund();
        existing.setRefundId(55L);
        existing.setIdempotencyKey("KEY-XYZ-999");
        existing.setAmount(new BigDecimal("500000"));
        existing.setBranch(mockBranch);
        existing.setPatientProfile(mockPatient);
        existing.setReduceInvoiceTotal(false);
        Invoice inv = new Invoice();
        inv.setInvoiceId(10L);
        existing.setInvoice(inv);

        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAdminUser));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        when(staffProfileRepository.findByUser_UserId(1L)).thenReturn(Optional.of(mockAccountantStaff));
        when(refundRepository.findByIdempotencyKey("KEY-XYZ-999")).thenReturn(Optional.of(existing));

        RefundRequestDTO req = new RefundRequestDTO();
        req.setIdempotencyKey("KEY-XYZ-999");
        req.setInvoiceId(10L);
        req.setAmount(new BigDecimal("1000000")); // Khác số tiền so với giao dịch gốc đã lưu
        req.setReason("Khách yêu cầu hoàn tiền");
        req.setReduceInvoiceTotal(false);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                refundService.processRefund(req, 1L)
        );
        assertTrue(ex.getMessage().toLowerCase().contains("khác") || ex.getMessage().toLowerCase().contains("xung đột"));
    }

    @Test
    @DisplayName("Case 20A: Phân phối lợi nhuận chủ sở hữu từ chối trùng lặp người nhận")
    void testCase20A_OwnerDistributionDuplicateRecipientRejected() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAdminUser));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        when(staffProfileRepository.findById(20L)).thenReturn(Optional.of(mockAccountantStaff));

        OwnerDistributionRequestDTO dto = new OwnerDistributionRequestDTO();
        dto.setDistributableAmount(new BigDecimal("20000000"));
        dto.setRetainedAmount(new BigDecimal("5000000")); // Ngân sách khả dụng = 15tr

        OwnerDistributionItemDTO item1 = new OwnerDistributionItemDTO();
        item1.setRecipientStaffId(20L);
        item1.setAmount(new BigDecimal("5000000"));

        OwnerDistributionItemDTO item2 = new OwnerDistributionItemDTO();
        item2.setRecipientStaffId(20L); // Trùng staffId 20L
        item2.setAmount(new BigDecimal("5000000"));

        dto.setItems(List.of(item1, item2)); // Tổng = 10tr <= 15tr ngân sách, chỉ vi phạm trùng người nhận

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                ownerDistributionService.createProposal(dto, 1L)
        );
        assertTrue(ex.getMessage().toLowerCase().contains("trùng lặp"));
    }

    @Test
    @DisplayName("Case 20B: Phân phối lợi nhuận chủ sở hữu từ chối vượt ngân sách khả dụng")
    void testCase20B_OwnerDistributionBudgetOverrunRejected() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAdminUser));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        when(staffProfileRepository.findById(20L)).thenReturn(Optional.of(mockAccountantStaff));
        when(staffProfileRepository.findById(30L)).thenReturn(Optional.of(mockDoctorStaff));

        OwnerDistributionRequestDTO dto = new OwnerDistributionRequestDTO();
        dto.setDistributableAmount(new BigDecimal("20000000"));
        dto.setRetainedAmount(new BigDecimal("5000000")); // Ngân sách khả dụng = 15tr

        OwnerDistributionItemDTO item1 = new OwnerDistributionItemDTO();
        item1.setRecipientStaffId(20L);
        item1.setAmount(new BigDecimal("10000000"));

        OwnerDistributionItemDTO item2 = new OwnerDistributionItemDTO();
        item2.setRecipientStaffId(30L); // Khác staffId (20L vs 30L)
        item2.setAmount(new BigDecimal("10000000")); // Tổng = 20tr > 15tr ngân sách

        dto.setItems(List.of(item1, item2));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                ownerDistributionService.createProposal(dto, 1L)
        );
        assertTrue(ex.getMessage().toLowerCase().contains("vượt quá"));
    }

    @Test
    @DisplayName("Case 21: Cập nhật chi phí vận hành thủ công và chặn sửa phiếu chi hệ thống (PAYROLL)")
    void testCase21_UpdateManualExpenseAndPreventSystemExpenseEdit() {
        OperatingExpense manualExp = new OperatingExpense();
        manualExp.setExpenseId(201L);
        manualExp.setSourceType(ExpenseSourceType.MANUAL);
        manualExp.setCategory("Văn phòng phẩm");
        manualExp.setAmount(new BigDecimal("500000"));
        manualExp.setIsCancelled(false);
        manualExp.setBranch(mockBranch);

        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAdminUser));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        when(operatingExpenseRepository.findById(201L)).thenReturn(Optional.of(manualExp));
        when(operatingExpenseRepository.save(any(OperatingExpense.class))).thenAnswer(inv -> inv.getArgument(0));

        OperatingExpenseRequestDTO updateDTO = new OperatingExpenseRequestDTO();
        updateDTO.setCategory("Văn phòng phẩm cao cấp");
        updateDTO.setAmount(new BigDecimal("750000"));
        updateDTO.setDescription("Sửa số tiền do mua thêm");

        OperatingExpense updated = operatingExpenseService.updateManualExpense(201L, updateDTO, "Sửa số tiền do mua thêm", 1L);
        assertEquals(new BigDecimal("750000"), updated.getAmount());
        assertEquals("Văn phòng phẩm cao cấp", updated.getCategory());
        verify(systemAuditLogRepository, times(1)).save(any(SystemAuditLog.class));

        // Chặn sửa phiếu chi từ PAYROLL
        OperatingExpense systemExp = new OperatingExpense();
        systemExp.setExpenseId(202L);
        systemExp.setSourceType(ExpenseSourceType.PAYROLL);
        systemExp.setBranch(mockBranch);
        when(operatingExpenseRepository.findById(202L)).thenReturn(Optional.of(systemExp));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                operatingExpenseService.updateManualExpense(202L, updateDTO, "Sửa", 1L)
        );
        assertTrue(ex.getMessage().contains("Chỉ có thể chỉnh sửa phiếu chi thủ công"));
    }

    @Test
    @DisplayName("Case 22: Phát hiện xung đột Idempotency khi cùng key nhưng đổi cờ reduceInvoiceTotal")
    void testCase22_RefundIdempotencyConflictOnReduceInvoiceTotal() {
        Refund existing = new Refund();
        existing.setRefundId(66L);
        existing.setIdempotencyKey("KEY-XYZ-FLAG");
        existing.setAmount(new BigDecimal("500000"));
        existing.setBranch(mockBranch);
        existing.setPatientProfile(mockPatient);
        existing.setReduceInvoiceTotal(false);
        Invoice inv = new Invoice();
        inv.setInvoiceId(10L);
        existing.setInvoice(inv);

        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAdminUser));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        when(staffProfileRepository.findByUser_UserId(1L)).thenReturn(Optional.of(mockAccountantStaff));
        when(refundRepository.findByIdempotencyKey("KEY-XYZ-FLAG")).thenReturn(Optional.of(existing));

        RefundRequestDTO req = new RefundRequestDTO();
        req.setIdempotencyKey("KEY-XYZ-FLAG");
        req.setInvoiceId(10L);
        req.setAmount(new BigDecimal("500000"));
        req.setReason("Khách yêu cầu hoàn tiền");
        req.setReduceInvoiceTotal(true); // Đổi từ false sang true

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                refundService.processRefund(req, 1L)
        );
        assertTrue(ex.getMessage().toLowerCase().contains("xung đột"));
    }

    @Test
    @DisplayName("Case 23: Chặn sửa, bật tắt, xóa chính sách hoa hồng khi actor null hoặc không phải Admin")
    void testCase23_CommissionPolicyRejectsNullOrNonAdminActor() {
        CommissionPolicyDTO dto = new CommissionPolicyDTO();
        dto.setPolicyName("Hoa hồng bác sĩ");
        dto.setCommissionValue(new BigDecimal("10"));

        // Actor null bị AccessDeniedException
        assertThrows(AccessDeniedException.class, () ->
                commissionPolicyService.savePolicy(dto, null)
        );
        assertThrows(AccessDeniedException.class, () ->
                commissionPolicyService.togglePolicyStatus(1L, true, null)
        );
        assertThrows(AccessDeniedException.class, () ->
                commissionPolicyService.deletePolicy(1L, null)
        );
    }
}
