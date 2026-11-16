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
@DisplayName("CreateAdvance")
@TestMethodOrder(MethodOrderer.MethodName.class)
public class SalaryAdvanceCreateReportTest {

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
    private SalaryAdvanceRepository salaryAdvanceRepository;

    private SalaryAdvanceServiceImpl salaryAdvanceService;

    private Branch mockBranch;
    private User mockAdminUser;

    private StaffProfile mockAccountantStaff;

    @BeforeEach
    void setUp() {
        salaryAdvanceService = new SalaryAdvanceServiceImpl(
                salaryAdvanceRepository, operatingExpenseRepository, staffProfileRepository,
                staffBranchAssignmentRepository, branchRepository, userRepository
        );

        mockBranch = new Branch();
        mockBranch.setBranchId(1L);
        mockBranch.setBranchName("Cơ sở Cầu Giấy");

        mockAdminUser = new User();
        mockAdminUser.setUserId(1L);
        mockAdminUser.setUsername("admin");
        mockAdminUser.setIsActive(true);
        mockAdminUser.setStatus("ACTIVE");

        mockAccountantStaff = new StaffProfile();
        mockAccountantStaff.setStaffId(20L);
        mockAccountantStaff.setFullName("Nguyễn Văn Kế Toán");
        mockAccountantStaff.setStaffCode("KT01");
        mockAccountantStaff.setStatus(StaffStatus.ACTIVE);
        mockAccountantStaff.setBaseSalary(new BigDecimal("12000000"));
        mockAccountantStaff.setAllowanceAmount(new BigDecimal("1500000"));
    }


    private SalaryAdvanceRequestDTO advanceInput(){SalaryAdvanceRequestDTO d=new SalaryAdvanceRequestDTO();d.setStaffId(20L);d.setBranchId(1L);d.setAmount(new BigDecimal("2000000"));d.setReason("  Sinh hoạt  ");d.setNotes("  Ghi chú  ");return d;}

    @Test
    @DisplayName("UTCID01 - A - Tạo tạm ứng từ chối nhân viên không hoạt động hoặc không có phân công tại chi nhánh")
    void UTCID01() throws Exception {
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAdminUser));
                when(branchRepository.findById(1L)).thenReturn(Optional.of(mockBranch));
                when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        
                // Subcase 1: Nhân viên không ở trạng thái ACTIVE
                StaffProfile inactiveStaff = new StaffProfile();
                inactiveStaff.setStaffId(25L);
                inactiveStaff.setFullName("Nguyễn Văn Inactive");
                inactiveStaff.setStatus(StaffStatus.INACTIVE);
                when(staffProfileRepository.findById(25L)).thenReturn(Optional.of(inactiveStaff));
        
                SalaryAdvanceRequestDTO dto1 = new SalaryAdvanceRequestDTO();
                dto1.setStaffId(25L);
                dto1.setBranchId(1L);
                dto1.setAmount(new BigDecimal("2000000"));
                dto1.setReason("Cần tiền gấp");
        
                IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class, () ->
                        salaryAdvanceService.createAdvanceRequest(dto1, 1L)
                );
                assertTrue(ex1.getMessage().contains("không ở trạng thái hoạt động"));
        
                // Subcase 2: Nhân viên ACTIVE nhưng không phân công tại chi nhánh
                when(staffProfileRepository.findById(20L)).thenReturn(Optional.of(mockAccountantStaff));
                when(staffBranchAssignmentRepository.hasActiveAssignment(20L, 1L, AssignmentStatus.ACTIVE)).thenReturn(false);
        
                SalaryAdvanceRequestDTO dto2 = new SalaryAdvanceRequestDTO();
                dto2.setStaffId(20L);
                dto2.setBranchId(1L);
                dto2.setAmount(new BigDecimal("2000000"));
                dto2.setReason("Cần tiền gấp");
        
                IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class, () ->
                        salaryAdvanceService.createAdvanceRequest(dto2, 1L)
                );
                assertTrue(ex2.getMessage().contains("không có phân công hoạt động"));
    }

    @Test
    @DisplayName("UTCID02 - N - Admin tạo hộ yêu cầu tạm ứng cho nhân viên đủ điều kiện thành công")
    void UTCID02() throws Exception {
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAdminUser));
                when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
                when(staffProfileRepository.findById(20L)).thenReturn(Optional.of(mockAccountantStaff));
                when(branchRepository.findById(1L)).thenReturn(Optional.of(mockBranch));
                when(staffBranchAssignmentRepository.hasActiveAssignment(20L, 1L, AssignmentStatus.ACTIVE)).thenReturn(true);
                when(salaryAdvanceRepository.save(any(SalaryAdvance.class))).thenAnswer(inv -> inv.getArgument(0));
        
                SalaryAdvanceRequestDTO dto = new SalaryAdvanceRequestDTO();
                dto.setStaffId(20L);
                dto.setBranchId(1L);
                dto.setAmount(new BigDecimal("3500000"));
                dto.setReason("Tạm ứng sinh hoạt phí");
                dto.setPaymentMethod(RefundMethod.BANK_TRANSFER);
        
                SalaryAdvance created = salaryAdvanceService.createAdvanceRequest(dto, 1L);
        
                assertNotNull(created);
                assertEquals(new BigDecimal("3500000"), created.getAmount());
                assertEquals(SalaryAdvanceStatus.PENDING, created.getStatus());
                assertEquals(mockAccountantStaff, created.getStaff());
                assertEquals(mockAdminUser, created.getCreatedBy(), "Người tạo phiếu là Admin (tạo hộ)");
    }

    @Test
    @DisplayName("UTCID03 - A - Chưa đăng nhập không tạo tạm ứng lương")
    void UTCID03() throws Exception {
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->salaryAdvanceService.createAdvanceRequest(new SalaryAdvanceRequestDTO(),null));verify(salaryAdvanceRepository,never()).save(any());
    }

    @Test
    @DisplayName("UTCID04 - A - Tài khoản người tạo bị vô hiệu hóa")
    void UTCID04() throws Exception {
        mockAdminUser.setIsActive(false);when(userRepository.findById(1L)).thenReturn(Optional.of(mockAdminUser));assertThrows(org.springframework.security.access.AccessDeniedException.class,()->salaryAdvanceService.createAdvanceRequest(new SalaryAdvanceRequestDTO(),1L));verify(salaryAdvanceRepository,never()).save(any());
    }

    @Test
    @DisplayName("UTCID05 - A - Không tìm thấy tài khoản người tạo")
    void UTCID05() throws Exception {
        assertThrows(IllegalArgumentException.class,()->salaryAdvanceService.createAdvanceRequest(advanceInput(),1L));verify(salaryAdvanceRepository,never()).save(any());
    }

    @Test
    @DisplayName("UTCID06 - A - Không tìm thấy nhân viên nhận tạm ứng")
    void UTCID06() throws Exception {
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAdminUser));assertThrows(IllegalArgumentException.class,()->salaryAdvanceService.createAdvanceRequest(advanceInput(),1L));verify(salaryAdvanceRepository,never()).save(any());
    }

    @Test
    @DisplayName("UTCID07 - A - Không tìm thấy chi nhánh tạm ứng")
    void UTCID07() throws Exception {
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAdminUser));when(staffProfileRepository.findById(20L)).thenReturn(Optional.of(mockAccountantStaff));assertThrows(IllegalArgumentException.class,()->salaryAdvanceService.createAdvanceRequest(advanceInput(),1L));verify(salaryAdvanceRepository,never()).save(any());
    }

    @Test
    @DisplayName("UTCID08 - N - Trim lý do, ghi chú và khởi tạo số tiền chưa phân bổ")
    void UTCID08() throws Exception {
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAdminUser));when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);when(staffProfileRepository.findById(20L)).thenReturn(Optional.of(mockAccountantStaff));when(branchRepository.findById(1L)).thenReturn(Optional.of(mockBranch));when(staffBranchAssignmentRepository.hasActiveAssignment(20L,1L,AssignmentStatus.ACTIVE)).thenReturn(true);when(salaryAdvanceRepository.save(any(SalaryAdvance.class))).thenAnswer(i->i.getArgument(0));SalaryAdvance r=salaryAdvanceService.createAdvanceRequest(advanceInput(),1L);assertEquals("Sinh hoạt",r.getReason());assertEquals("Ghi chú",r.getNotes());assertEquals(RefundMethod.BANK_TRANSFER,r.getPaymentMethod());assertEquals(0,r.getAllocatedAmount().compareTo(BigDecimal.ZERO));assertEquals(0,r.getRemainingAmount().compareTo(new BigDecimal("2000000")));assertEquals(SalaryAdvanceStatus.PENDING,r.getStatus());verify(salaryAdvanceRepository).save(r);
    }
}
