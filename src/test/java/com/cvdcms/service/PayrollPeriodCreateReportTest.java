package com.cvdcms.service;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.DisplayName;

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
@DisplayName("CreatePeriod")
@TestMethodOrder(MethodOrderer.MethodName.class)
public class PayrollPeriodCreateReportTest {

    @Mock
    private BranchRepository branchRepository;
    @Mock
    private StaffProfileRepository staffProfileRepository;
    @Mock
    private StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    @Mock
    private UserRepository userRepository;

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
    private SalaryAdvanceAllocationRepository salaryAdvanceAllocationRepository;
    @Mock
    private AttendanceRecordRepository attendanceRecordRepository;

    private PayrollServiceImpl payrollService;

    private Branch mockBranch;

    private StaffProfile mockAccountantStaff;

    @BeforeEach
    void setUp() {
        payrollService = new PayrollServiceImpl(
                payrollPeriodRepository, payrollItemRepository, salaryAdvanceRepository,
                salaryAdvanceAllocationRepository, attendanceRecordRepository,
                operatingExpenseRepository, serviceCommissionRepository,
                staffProfileRepository, staffBranchAssignmentRepository, branchRepository,
                userRepository
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

        mockAccountantStaff = new StaffProfile();
        mockAccountantStaff.setStaffId(20L);
        mockAccountantStaff.setFullName("Nguyễn Văn Kế Toán");
        mockAccountantStaff.setStaffCode("KT01");
        mockAccountantStaff.setStatus(StaffStatus.ACTIVE);
        mockAccountantStaff.setBaseSalary(new BigDecimal("12000000"));
        mockAccountantStaff.setAllowanceAmount(new BigDecimal("1500000"));
    }


    private PayrollPeriodCreateDTO periodInput(){PayrollPeriodCreateDTO d=new PayrollPeriodCreateDTO();d.setBranchId(1L);d.setMonth(11);d.setYear(2026);return d;}
    private void emptyPeriodSave(){java.util.concurrent.atomic.AtomicReference<PayrollPeriod> stored=new java.util.concurrent.atomic.AtomicReference<>();when(payrollPeriodRepository.save(any(PayrollPeriod.class))).thenAnswer(i->{PayrollPeriod r=i.getArgument(0);r.setPeriodId(1L);stored.set(r);return r;});when(payrollPeriodRepository.findById(1L)).thenAnswer(i->Optional.of(stored.get()));}

    @Test
    @DisplayName("UTCID01 - N - Lương cố định tháng lấy trực tiếp từ StaffProfile, ngày công tham khảo không tự trừ lương")
    void UTCID01() throws Exception {
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
    @DisplayName("UTCID02 - B - Khấu trừ tạm ứng vào bảng lương tự động và không làm lương thực chi âm")
    void UTCID02() throws Exception {
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
    @DisplayName("UTCID03 - N - Chống trừ trùng tạm ứng qua 2 kỳ lương liên tiếp (Sequential advance allocation)")
    void UTCID03() throws Exception {
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
    @DisplayName("UTCID04 - A - Ngăn tính lương trùng cho một nhân viên trên nhiều chi nhánh trong cùng tháng/năm")
    void UTCID04() throws Exception {
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
    @DisplayName("UTCID05 - A - Chưa đăng nhập không tạo kỳ lương")
    void UTCID05() throws Exception {
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->payrollService.createPeriod(new PayrollPeriodCreateDTO(),null));verify(payrollPeriodRepository,never()).save(any());
    }

    @Test
    @DisplayName("UTCID06 - A - Không tạo trùng kỳ lương theo chi nhánh, tháng, năm")
    void UTCID06() throws Exception {
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);when(payrollPeriodRepository.existsByBranch_BranchIdAndMonthAndYear(1L,11,2026)).thenReturn(true);PayrollPeriodCreateDTO d=new PayrollPeriodCreateDTO();d.setBranchId(1L);d.setMonth(11);d.setYear(2026);assertThrows(IllegalStateException.class,()->payrollService.createPeriod(d,1L));verify(payrollPeriodRepository,never()).save(any());
    }

    @Test
    @DisplayName("UTCID07 - A - Không tìm thấy chi nhánh tạo kỳ lương")
    void UTCID07() throws Exception {
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);assertThrows(IllegalArgumentException.class,()->payrollService.createPeriod(periodInput(),1L));verify(payrollPeriodRepository,never()).save(any());
    }

    @Test
    @DisplayName("UTCID08 - N - Không có phân công thì tạo kỳ DRAFT không có dòng lương")
    void UTCID08() throws Exception {
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);when(branchRepository.findById(1L)).thenReturn(Optional.of(mockBranch));emptyPeriodSave();PayrollPeriod r=payrollService.createPeriod(periodInput(),1L);assertEquals(PayrollStatus.DRAFT,r.getStatus());assertEquals(Integer.valueOf(11),r.getMonth());assertEquals(Integer.valueOf(2026),r.getYear());assertTrue(r.getPeriodName().contains("11/2026"));verify(payrollItemRepository,never()).save(any());
    }

    @Test
    @DisplayName("UTCID09 - N - Tên kỳ lương được trim khi người dùng truyền vào")
    void UTCID09() throws Exception {
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);when(branchRepository.findById(1L)).thenReturn(Optional.of(mockBranch));emptyPeriodSave();PayrollPeriodCreateDTO d=periodInput();d.setPeriodName("  Lương tháng 11  ");assertEquals("Lương tháng 11",payrollService.createPeriod(d,1L).getPeriodName());
    }
}
