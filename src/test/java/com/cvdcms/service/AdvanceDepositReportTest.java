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
@DisplayName("AdvanceDeposit")
@TestMethodOrder(MethodOrderer.MethodName.class)
public class AdvanceDepositReportTest {

    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private InvoiceItemRepository invoiceItemRepository;
    @Mock
    private InvoiceAuditLogRepository invoiceAuditLogRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private RefundRepository refundRepository;
    @Mock
    private VisitRepository visitRepository;
    @Mock
    private PatientProfileRepository patientProfileRepository;
    @Mock
    private BranchRepository branchRepository;
    @Mock
    private StaffProfileRepository staffProfileRepository;
    @Mock
    private StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    @Mock
    private ServiceRepository serviceRepository;
    @Mock
    private PerformedServiceRepository performedServiceRepository;
    @Mock
    private PromotionRepository promotionRepository;
    @Mock
    private PatientVoucherRepository patientVoucherRepository;
    @Mock
    private PatientLoyaltyWalletRepository patientLoyaltyWalletRepository;
    @Mock
    private PointTransactionRepository pointTransactionRepository;
    @Mock
    private MembershipTierRepository membershipTierRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PrescriptionRepository prescriptionRepository;
    @Mock
    private PrescriptionItemRepository prescriptionItemRepository;
    @Mock
    private MaterialBatchRepository materialBatchRepository;
    @Mock
    private BranchInventoryRepository branchInventoryRepository;
    @Mock
    private MedicationRepository medicationRepository;
    @Mock
    private StockIssueRepository stockIssueRepository;

    private InvoiceServiceImpl invoiceService;
    private PaymentServiceImpl paymentService;

    private User mockAccountantUser;

    private StaffProfile mockAccountantStaff;

    private Branch mockBranch;
    private PatientProfile mockPatient;

    private MembershipTier mockTier;

    @BeforeEach
    void setUp() {
        invoiceService = new InvoiceServiceImpl(
                invoiceRepository, invoiceItemRepository, invoiceAuditLogRepository,
                visitRepository, patientProfileRepository, branchRepository,
                staffProfileRepository, staffBranchAssignmentRepository,
                serviceRepository, performedServiceRepository, promotionRepository,
                patientVoucherRepository, patientLoyaltyWalletRepository,
                pointTransactionRepository, userRepository, paymentRepository, refundRepository,
                prescriptionRepository, prescriptionItemRepository, materialBatchRepository,
                branchInventoryRepository, medicationRepository, stockIssueRepository
        );
        invoiceService.setMembershipTierRepository(membershipTierRepository);

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
    @DisplayName("UTCID01 - N - Nạp tạm ứng 1.000.000đ, dùng ví trả 400.000đ: số dư còn 600.000đ và không cộng trùng thực thu")
    void UTCID01() throws Exception {
        mockCashierAuthSuccess();
                when(patientProfileRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(mockPatient));
                when(branchRepository.findById(1L)).thenReturn(Optional.of(mockBranch));
                when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
                when(patientLoyaltyWalletRepository.findByPatientProfile_ProfileId(1L)).thenReturn(Optional.empty());
                when(patientLoyaltyWalletRepository.save(any(PatientLoyaltyWallet.class))).thenAnswer(inv -> inv.getArgument(0));
        
                // 1. Nạp cọc 1.000.000đ
                PaymentRequestDTO depositReq = new PaymentRequestDTO();
                depositReq.setProfileId(1L);
                depositReq.setBranchId(1L);
                depositReq.setAmount(new BigDecimal("1000000.00"));
                depositReq.setPaymentMethod(PaymentMethod.CASH);
        
                Payment deposit = paymentService.processAdvanceDeposit(depositReq, 1L);
                assertEquals(PaymentType.ADVANCE_DEPOSIT, deposit.getPaymentType());
                assertEquals(new BigDecimal("1000000.00"), mockPatient.getDepositBalance());
        
                // 2. Dùng ví tạm ứng trả 400.000đ cho hóa đơn
                Invoice invoice = new Invoice();
                invoice.setInvoiceId(101L);
                invoice.setTotalAmount(new BigDecimal("500000.00"));
                invoice.setPaidAmount(BigDecimal.ZERO);
                invoice.setRemainingDebt(new BigDecimal("500000.00"));
                invoice.setStatus(InvoiceStatus.CONFIRMED);
                invoice.setPatientProfile(mockPatient);
                invoice.setBranch(mockBranch);
                when(invoiceRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(invoice));
        
                PaymentRequestDTO walletPayReq = new PaymentRequestDTO();
                walletPayReq.setInvoiceId(101L);
                walletPayReq.setAmount(new BigDecimal("400000.00"));
                walletPayReq.setPaymentMethod(PaymentMethod.DEPOSIT_WALLET);
        
                Payment walletPay = paymentService.processPayment(walletPayReq, 1L);
                assertEquals(new BigDecimal("600000.00"), mockPatient.getDepositBalance());
                assertEquals(new BigDecimal("400000.00"), invoice.getPaidAmount());
                assertEquals(new BigDecimal("100000.00"), invoice.getRemainingDebt());
        
                // 3. Kiểm tra tính thực thu: paymentRepository.sumRealRevenue loại trừ DEPOSIT_WALLET
                // Đảm bảo khoản 400.000đ ví tạm ứng không bị cộng đúp vào tiền thu thực tế
                LocalDateTime start = LocalDateTime.now().minusDays(1);
                LocalDateTime end = LocalDateTime.now().plusDays(1);
                when(paymentRepository.sumRealRevenue(1L, start, end)).thenReturn(new BigDecimal("1000000.00")); // Chỉ tính 1.000.000 nạp tiền
                when(refundRepository.sumRefundAmount(1L, start, end)).thenReturn(BigDecimal.ZERO);
        
                FinancialMetricsDTO metrics = invoiceService.getFinancialMetrics(1L, start, end);
                assertEquals(new BigDecimal("1000000.00"), metrics.getTodayTotalCollected());
    }

    @Test
    @DisplayName("UTCID02 - A - Chặn nạp tạm ứng bằng DEPOSIT_WALLET và chặn dùng ví không gắn hóa đơn")
    void UTCID02() throws Exception {
        // Nạp cọc bằng chính DEPOSIT_WALLET
                PaymentRequestDTO depositWithWalletReq = new PaymentRequestDTO();
                depositWithWalletReq.setProfileId(1L);
                depositWithWalletReq.setBranchId(1L);
                depositWithWalletReq.setAmount(new BigDecimal("500000.00"));
                depositWithWalletReq.setPaymentMethod(PaymentMethod.DEPOSIT_WALLET);
        
                IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class, () ->
                        paymentService.processAdvanceDeposit(depositWithWalletReq, 1L));
                assertTrue(ex1.getMessage().contains("DEPOSIT_WALLET"));
        
                // Thanh toán bằng ví nhưng không có hóa đơn (invoiceId is null)
                PaymentRequestDTO walletNoInvoiceReq = new PaymentRequestDTO();
                walletNoInvoiceReq.setInvoiceId(null);
                walletNoInvoiceReq.setAmount(new BigDecimal("500000.00"));
                walletNoInvoiceReq.setPaymentMethod(PaymentMethod.DEPOSIT_WALLET);
        
                IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class, () ->
                        paymentService.processPayment(walletNoInvoiceReq, 1L));
                assertTrue(ex2.getMessage().contains("bắt buộc phải gắn với mã hóa đơn"));
    }

    @Test
    @DisplayName("UTCID03 - A - Request nạp tạm ứng null")
    void UTCID03() throws Exception {
        assertThrows(IllegalArgumentException.class,()->paymentService.processAdvanceDeposit(null,1L));verifyNoInteractions(paymentRepository);
    }

    @Test
    @DisplayName("UTCID04 - A - Số tiền nạp tạm ứng null")
    void UTCID04() throws Exception {
        PaymentRequestDTO d=new PaymentRequestDTO();d.setAmount(null);assertThrows(IllegalArgumentException.class,()->paymentService.processAdvanceDeposit(d,1L));verify(paymentRepository,never()).save(any());
    }

    @Test
    @DisplayName("UTCID05 - B - Số tiền nạp tạm ứng 0")
    void UTCID05() throws Exception {
        PaymentRequestDTO d=new PaymentRequestDTO();d.setAmount(new BigDecimal("0"));assertThrows(IllegalArgumentException.class,()->paymentService.processAdvanceDeposit(d,1L));verify(paymentRepository,never()).save(any());
    }

    @Test
    @DisplayName("UTCID06 - B - Số tiền nạp tạm ứng -0.01")
    void UTCID06() throws Exception {
        PaymentRequestDTO d=new PaymentRequestDTO();d.setAmount(new BigDecimal("-0.01"));assertThrows(IllegalArgumentException.class,()->paymentService.processAdvanceDeposit(d,1L));verify(paymentRepository,never()).save(any());
    }
}
