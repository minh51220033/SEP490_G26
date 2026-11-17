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
@DisplayName("ProcessRefund")
@TestMethodOrder(MethodOrderer.MethodName.class)
public class RefundProcessReportTest {

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

    private RefundServiceImpl refundService;

    private Branch mockBranch;
    private User mockAdminUser;

    private StaffProfile mockAccountantStaff;

    private PatientProfile mockPatient;

    @BeforeEach
    void setUp() {
        refundService = new RefundServiceImpl(
                refundRepository, invoiceRepository, invoiceAuditLogRepository,
                patientProfileRepository, branchRepository, staffProfileRepository,
                staffBranchAssignmentRepository, userRepository, loyaltyService
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

        mockPatient = new PatientProfile();
        mockPatient.setProfileId(100L);
        mockPatient.setFullName("Trần Bệnh Nhân");
        mockPatient.setPatientCode("BN001");
        mockPatient.setPhoneNumber("0987654321");
    }


    @Test
    @DisplayName("UTCID01 - A - Hóa đơn bị hủy (CANCELLED) không được hoàn tiền")
    void UTCID01() throws Exception {
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
    @DisplayName("UTCID02 - N - Hoàn tiền dịch vụ với reduceInvoiceTotal = true giảm cả nghĩa vụ nợ và ghi 2 audit logs")
    void UTCID02() throws Exception {
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
    @DisplayName("UTCID03 - N - Khóa chống submit đúp hoàn tiền thông qua idempotencyKey")
    void UTCID03() throws Exception {
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

    @Test
    @DisplayName("UTCID04 - A - Phát hiện xung đột khi gửi cùng idempotencyKey nhưng khác hóa đơn hoặc số tiền hoàn")
    void UTCID04() throws Exception {
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
    @DisplayName("UTCID05 - A - Phát hiện xung đột Idempotency khi cùng key nhưng đổi cờ reduceInvoiceTotal")
    void UTCID05() throws Exception {
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
    @DisplayName("UTCID06 - A - Request hoàn tiền null")
    void UTCID06() throws Exception {
        assertThrows(IllegalArgumentException.class,()->refundService.processRefund(null,1L));verify(refundRepository,never()).save(any());
    }

    @Test
    @DisplayName("UTCID07 - A - Số tiền hoàn null")
    void UTCID07() throws Exception {
        RefundRequestDTO d=new RefundRequestDTO();d.setAmount(null);d.setReason("Hoàn");assertThrows(IllegalArgumentException.class,()->refundService.processRefund(d,1L));verify(refundRepository,never()).save(any());
    }

    @Test
    @DisplayName("UTCID08 - B - Số tiền hoàn 0")
    void UTCID08() throws Exception {
        RefundRequestDTO d=new RefundRequestDTO();d.setAmount(new BigDecimal("0"));d.setReason("Hoàn");assertThrows(IllegalArgumentException.class,()->refundService.processRefund(d,1L));verify(refundRepository,never()).save(any());
    }

    @Test
    @DisplayName("UTCID09 - B - Số tiền hoàn -0.01")
    void UTCID09() throws Exception {
        RefundRequestDTO d=new RefundRequestDTO();d.setAmount(new BigDecimal("-0.01"));d.setReason("Hoàn");assertThrows(IllegalArgumentException.class,()->refundService.processRefund(d,1L));verify(refundRepository,never()).save(any());
    }

    @Test
    @DisplayName("UTCID10 - A - Lý do hoàn chỉ khoảng trắng")
    void UTCID10() throws Exception {
        RefundRequestDTO d=new RefundRequestDTO();d.setAmount(BigDecimal.TEN);d.setReason(" ");assertThrows(IllegalArgumentException.class,()->refundService.processRefund(d,1L));verify(refundRepository,never()).save(any());
    }
}
