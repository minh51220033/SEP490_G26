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
@DisplayName("CreateExpense")
@TestMethodOrder(MethodOrderer.MethodName.class)
public class OperatingExpenseCreateReportTest {

    @Mock
    private BranchRepository branchRepository;

    @Mock
    private StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    @Mock
    private UserRepository userRepository;

    @Mock
    private OperatingExpenseRepository operatingExpenseRepository;

    @Mock
    private SystemAuditLogRepository systemAuditLogRepository;

    private OperatingExpenseServiceImpl operatingExpenseService;

    private Branch mockBranch;
    private User mockAdminUser;
    private User mockAccountantUser;

    @BeforeEach
    void setUp() {
        operatingExpenseService = new OperatingExpenseServiceImpl(
                operatingExpenseRepository, branchRepository, staffBranchAssignmentRepository, userRepository,
                systemAuditLogRepository
        );

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
    }


    private OperatingExpenseRequestDTO expenseInput(){OperatingExpenseRequestDTO d=new OperatingExpenseRequestDTO();d.setBranchId(1L);d.setCategory("  Điện nước  ");d.setAmount(new BigDecimal("500000"));d.setDescription("  Chi tháng 11  ");return d;}
    private void expenseAuth(){when(userRepository.findById(1L)).thenReturn(Optional.of(mockAdminUser));when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);when(branchRepository.findById(1L)).thenReturn(Optional.of(mockBranch));}

    @Test
    @DisplayName("UTCID01 - N - Khai báo chi phí vận hành thủ công thành công")
    void UTCID01() throws Exception {
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
    @DisplayName("UTCID02 - A - Phân quyền chi nhánh (Branch scoping) từ chối truy cập ngoài chi nhánh phân công")
    void UTCID02() throws Exception {
        when(staffBranchAssignmentRepository.findActiveBranchIdsByUserId(2L, AssignmentStatus.ACTIVE)).thenReturn(List.of(1L));
        
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
    @DisplayName("UTCID03 - A - Chưa đăng nhập không được tạo phiếu chi")
    void UTCID03() throws Exception {
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->operatingExpenseService.createManualExpense(new OperatingExpenseRequestDTO(),null));verify(operatingExpenseRepository,never()).save(any());
    }

    @Test
    @DisplayName("UTCID04 - N - Mặc định ngày hiện tại, chuyển khoản và trim dữ liệu")
    void UTCID04() throws Exception {
        expenseAuth();when(operatingExpenseRepository.save(any(OperatingExpense.class))).thenAnswer(i->i.getArgument(0));LocalDate before=LocalDate.now();OperatingExpense r=operatingExpenseService.createManualExpense(expenseInput(),1L);assertEquals("Điện nước",r.getCategory());assertEquals("Chi tháng 11",r.getDescription());assertEquals(RefundMethod.BANK_TRANSFER,r.getPaymentMethod());assertFalse(r.getExpenseDate().isBefore(before));assertFalse(r.getExpenseDate().isAfter(LocalDate.now()));assertEquals(mockAdminUser,r.getCreatedBy());verify(operatingExpenseRepository).save(r);
    }

    @Test
    @DisplayName("UTCID05 - A - Không tìm thấy người tạo phiếu chi")
    void UTCID05() throws Exception {
        assertThrows(IllegalArgumentException.class,()->operatingExpenseService.createManualExpense(expenseInput(),1L));verify(operatingExpenseRepository,never()).save(any());
    }

    @Test
    @DisplayName("UTCID06 - A - Không tìm thấy chi nhánh tạo phiếu chi")
    void UTCID06() throws Exception {
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAdminUser));when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);assertThrows(IllegalArgumentException.class,()->operatingExpenseService.createManualExpense(expenseInput(),1L));verify(operatingExpenseRepository,never()).save(any());
    }
}
