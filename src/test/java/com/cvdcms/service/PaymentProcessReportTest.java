package com.cvdcms.service;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.DisplayName;

import com.cvdcms.DTO.*;
import com.cvdcms.config.FinanceAccessInterceptor;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.impl.*;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("ProcessPayment")
@TestMethodOrder(MethodOrderer.MethodName.class)
public class PaymentProcessReportTest {

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private InvoiceAuditLogRepository invoiceAuditLogRepository;
    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PatientProfileRepository patientProfileRepository;
    @Mock
    private BranchRepository branchRepository;
    @Mock
    private StaffProfileRepository staffProfileRepository;
    @Mock
    private StaffBranchAssignmentRepository staffBranchAssignmentRepository;

    @Mock
    private PatientLoyaltyWalletRepository patientLoyaltyWalletRepository;
    @Mock
    private PointTransactionRepository pointTransactionRepository;
    @Mock
    private MembershipTierRepository membershipTierRepository;
    @Mock
    private UserRepository userRepository;

    private PaymentServiceImpl paymentService;

    private User mockAccountantUser;

    private StaffProfile mockAccountantStaff;

    private Branch mockBranch;
    private PatientProfile mockPatient;

    private MembershipTier mockTier;

    @BeforeEach
    void setUp() {

        paymentService = new PaymentServiceImpl(
                paymentRepository, invoiceRepository, invoiceAuditLogRepository,
                patientProfileRepository, branchRepository, staffProfileRepository,
                staffBranchAssignmentRepository, patientLoyaltyWalletRepository,
                pointTransactionRepository, membershipTierRepository, userRepository
        );

        mockBranch = new Branch();
        mockBranch.setBranchId(1L);
        mockBranch.setBranchName("Cơ sở Quận 1");

        mockTier = new MembershipTier();
        mockTier.setTierId(2L);
        mockTier.setTierName("Thành viên Bạc");
        mockTier.setDiscountPercent(new BigDecimal("5.00"));
        mockTier.setPointValue(new BigDecimal("10.00"));
        mockTier.setSpendPerPoint(new BigDecimal("10000.00"));
        mockTier.setMinTotalSpent(new BigDecimal("5000000.00"));

        mockPatient = new PatientProfile();
        mockPatient.setProfileId(1L);
        mockPatient.setPatientCode("BN000101");
        mockPatient.setFullName("Nguyễn Văn An");
        mockPatient.setPhoneNumber("0987654321");
        mockPatient.setDepositBalance(BigDecimal.ZERO);
        mockPatient.setMembershipTier(mockTier);

        mockAccountantUser = new User();
        mockAccountantUser.setUserId(1L);
        mockAccountantUser.setUsername("ketoan_an");
        mockAccountantUser.setStatus("ACTIVE");
        mockAccountantUser.setIsActive(true);

        mockAccountantStaff = new StaffProfile();
        mockAccountantStaff.setStaffId(1L);
        mockAccountantStaff.setStaffCode("NVKT01");
        mockAccountantStaff.setFullName("Kế Toán An");
        mockAccountantStaff.setStatus(StaffStatus.ACTIVE);
        mockAccountantStaff.setUser(mockAccountantUser);
    }

    private void mockCashierAuthSuccess() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAccountantUser));
        when(staffProfileRepository.findByUser_UserId(1L)).thenReturn(Optional.of(mockAccountantStaff));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
    }


    @Test
    @DisplayName("UTCID01 - N - Thu 300.000đ rồi 700.000đ: còn nợ lần lượt 700.000đ và 0đ, trạng thái chuyển PARTIAL -> PAID")
    void UTCID01() throws Exception {
        mockCashierAuthSuccess();
        
                Invoice invoice = new Invoice();
                invoice.setInvoiceId(100L);
                invoice.setInvoiceCode("HD20261024-001");
                invoice.setTotalAmount(new BigDecimal("1000000.00"));
                invoice.setPaidAmount(BigDecimal.ZERO);
                invoice.setRemainingDebt(new BigDecimal("1000000.00"));
                invoice.setStatus(InvoiceStatus.CONFIRMED);
                invoice.setPaymentStatus(InvoicePaymentStatus.UNPAID);
                invoice.setPatientProfile(mockPatient);
                invoice.setBranch(mockBranch);
        
                when(invoiceRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(invoice));
                when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
                when(patientLoyaltyWalletRepository.findByPatientProfile_ProfileId(1L)).thenReturn(Optional.empty());
                when(patientLoyaltyWalletRepository.save(any(PatientLoyaltyWallet.class))).thenAnswer(inv -> inv.getArgument(0));
        
                // Đợt 1: Thu 300.000đ
                PaymentRequestDTO payReq1 = new PaymentRequestDTO();
                payReq1.setInvoiceId(100L);
                payReq1.setAmount(new BigDecimal("300000.00"));
                payReq1.setPaymentMethod(PaymentMethod.CASH);
        
                Payment payment1 = paymentService.processPayment(payReq1, 1L);
                assertNotNull(payment1);
                assertEquals(new BigDecimal("300000.00"), invoice.getPaidAmount());
                assertEquals(new BigDecimal("700000.00"), invoice.getRemainingDebt());
                assertEquals(InvoicePaymentStatus.PARTIAL, invoice.getPaymentStatus());
        
                // Đợt 2: Thu 700.000đ
                PaymentRequestDTO payReq2 = new PaymentRequestDTO();
                payReq2.setInvoiceId(100L);
                payReq2.setAmount(new BigDecimal("700000.00"));
                payReq2.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        
                Payment payment2 = paymentService.processPayment(payReq2, 1L);
                assertNotNull(payment2);
                assertEquals(new BigDecimal("1000000.00"), invoice.getPaidAmount());
                assertEquals(BigDecimal.ZERO, invoice.getRemainingDebt());
                assertEquals(InvoicePaymentStatus.PAID, invoice.getPaymentStatus());
    }

    @Test
    @DisplayName("UTCID02 - B - Thu vượt số còn nợ, số tiền bằng 0 hoặc âm đều bị chặn")
    void UTCID02() throws Exception {
        mockCashierAuthSuccess();
                Invoice invoice = new Invoice();
                invoice.setInvoiceId(100L);
                invoice.setTotalAmount(new BigDecimal("1000000.00"));
                invoice.setPaidAmount(BigDecimal.ZERO);
                invoice.setRemainingDebt(new BigDecimal("1000000.00"));
                invoice.setStatus(InvoiceStatus.CONFIRMED);
                invoice.setBranch(mockBranch);
                invoice.setPatientProfile(mockPatient);
        
                // Số tiền bằng 0
                PaymentRequestDTO zeroReq = new PaymentRequestDTO();
                zeroReq.setInvoiceId(100L);
                zeroReq.setAmount(BigDecimal.ZERO);
                assertThrows(IllegalArgumentException.class, () -> paymentService.processPayment(zeroReq, 1L));
        
                // Số tiền âm
                PaymentRequestDTO negReq = new PaymentRequestDTO();
                negReq.setInvoiceId(100L);
                negReq.setAmount(new BigDecimal("-100000.00"));
                assertThrows(IllegalArgumentException.class, () -> paymentService.processPayment(negReq, 1L));
        
                // Thu vượt nợ (nợ 1.000.000đ, nộp 1.200.000đ)
                when(invoiceRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(invoice));
                PaymentRequestDTO overReq = new PaymentRequestDTO();
                overReq.setInvoiceId(100L);
                overReq.setAmount(new BigDecimal("1200000.00"));
                IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                        paymentService.processPayment(overReq, 1L));
                assertTrue(ex.getMessage().contains("vượt quá"));
    }

    @Test
    @DisplayName("UTCID03 - N - Request gửi lại với cùng transactionRef trả về giao dịch cũ, không tạo trùng lặp")
    void UTCID03() throws Exception {
        mockCashierAuthSuccess();
        
                Invoice invoice = new Invoice();
                invoice.setInvoiceId(103L);
                invoice.setTotalAmount(new BigDecimal("1000000.00"));
                invoice.setPaidAmount(new BigDecimal("500000.00"));
                invoice.setRemainingDebt(new BigDecimal("500000.00"));
                invoice.setStatus(InvoiceStatus.CONFIRMED);
                invoice.setBranch(mockBranch);
                invoice.setPatientProfile(mockPatient);
        
                Payment existingPayment = new Payment();
                existingPayment.setPaymentId(501L);
                existingPayment.setInvoice(invoice);
                existingPayment.setPatientProfile(mockPatient);
                existingPayment.setBranch(mockBranch);
                existingPayment.setAmount(new BigDecimal("500000.00"));
                existingPayment.setTransactionRef("VNPAY-DUP-9999");
                existingPayment.setPaymentType(PaymentType.PAYMENT);
                existingPayment.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        
                when(invoiceRepository.findByIdForUpdate(103L)).thenReturn(Optional.of(invoice));
                when(paymentRepository.findByTransactionRef("VNPAY-DUP-9999")).thenReturn(Optional.of(existingPayment));
        
                PaymentRequestDTO repeatReq = new PaymentRequestDTO();
                repeatReq.setInvoiceId(103L);
                repeatReq.setAmount(new BigDecimal("500000.00"));
                repeatReq.setTransactionRef("VNPAY-DUP-9999");
                repeatReq.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        
                Payment result = paymentService.processPayment(repeatReq, 1L);
        
                // Trả về đúng bản ghi giao dịch đã có, không tạo thêm
                assertSame(existingPayment, result);
                verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    @DisplayName("UTCID04 - N - Gửi lại thanh toán tiền mặt với idempotencyKey khi đã trả hết nợ trả về giao dịch cũ")
    void UTCID04() throws Exception {
        mockCashierAuthSuccess();
        
                Invoice invoice = new Invoice();
                invoice.setInvoiceId(105L);
                invoice.setTotalAmount(new BigDecimal("500000.00"));
                invoice.setPaidAmount(new BigDecimal("500000.00"));
                invoice.setRemainingDebt(BigDecimal.ZERO); // Đã hết nợ
                invoice.setPaymentStatus(InvoicePaymentStatus.PAID);
                invoice.setStatus(InvoiceStatus.CONFIRMED);
                invoice.setBranch(mockBranch);
                invoice.setPatientProfile(mockPatient);
        
                Payment existingCashPayment = new Payment();
                existingCashPayment.setPaymentId(601L);
                existingCashPayment.setInvoice(invoice);
                existingCashPayment.setPatientProfile(mockPatient);
                existingCashPayment.setBranch(mockBranch);
                existingCashPayment.setAmount(new BigDecimal("500000.00"));
                existingCashPayment.setPaymentType(PaymentType.PAYMENT);
                existingCashPayment.setPaymentMethod(PaymentMethod.CASH);
                existingCashPayment.setIdempotencyKey("KEY-CASH-UUID-123");
        
                when(invoiceRepository.findByIdForUpdate(105L)).thenReturn(Optional.of(invoice));
                when(paymentRepository.findByIdempotencyKey("KEY-CASH-UUID-123")).thenReturn(Optional.of(existingCashPayment));
        
                // Gửi lại request tiền mặt với cùng idempotencyKey
                PaymentRequestDTO cashReq = new PaymentRequestDTO();
                cashReq.setInvoiceId(105L);
                cashReq.setAmount(new BigDecimal("500000.00"));
                cashReq.setPaymentMethod(PaymentMethod.CASH);
                cashReq.setIdempotencyKey("KEY-CASH-UUID-123");
        
                Payment result = paymentService.processPayment(cashReq, 1L);
        
                // Phải trả về đúng giao dịch cũ mà không báo lỗi "đã trả hết nợ" và không ghi tiền lần hai
                assertSame(existingCashPayment, result);
                verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    @DisplayName("UTCID05 - A - Request thu tiền null")
    void UTCID05() throws Exception {
        assertThrows(IllegalArgumentException.class,()->paymentService.processPayment(null,1L));verifyNoInteractions(paymentRepository);
    }

    @Test
    @DisplayName("UTCID06 - A - Thiếu hóa đơn thanh toán")
    void UTCID06() throws Exception {
        PaymentRequestDTO d=new PaymentRequestDTO();d.setAmount(BigDecimal.TEN);assertThrows(IllegalArgumentException.class,()->paymentService.processPayment(d,1L));verifyNoInteractions(paymentRepository);
    }

    @Test
    @DisplayName("UTCID07 - A - Tiền thanh toán null")
    void UTCID07() throws Exception {
        PaymentRequestDTO d=new PaymentRequestDTO();d.setInvoiceId(1L);d.setAmount(null);assertThrows(IllegalArgumentException.class,()->paymentService.processPayment(d,1L));verify(paymentRepository,never()).save(any());
    }

    @Test
    @DisplayName("UTCID08 - B - Tiền thanh toán 0")
    void UTCID08() throws Exception {
        PaymentRequestDTO d=new PaymentRequestDTO();d.setInvoiceId(1L);d.setAmount(new BigDecimal("0"));assertThrows(IllegalArgumentException.class,()->paymentService.processPayment(d,1L));verify(paymentRepository,never()).save(any());
    }

    @Test
    @DisplayName("UTCID09 - B - Tiền thanh toán -0.01")
    void UTCID09() throws Exception {
        PaymentRequestDTO d=new PaymentRequestDTO();d.setInvoiceId(1L);d.setAmount(new BigDecimal("-0.01"));assertThrows(IllegalArgumentException.class,()->paymentService.processPayment(d,1L));verify(paymentRepository,never()).save(any());
    }

    @Test
    @DisplayName("UTCID10 - A - Hóa đơn cần thu không tồn tại")
    void UTCID10() throws Exception {
        PaymentRequestDTO d=new PaymentRequestDTO();d.setInvoiceId(99L);d.setAmount(BigDecimal.TEN);assertThrows(IllegalArgumentException.class,()->paymentService.processPayment(d,1L));verify(paymentRepository,never()).save(any());
    }
}
