package com.cvdcms;

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
public class FinanceWorkflowUnitTest {

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
    private RefundServiceImpl refundService;
    private FinanceAccessInterceptor financeAccessInterceptor;

    private User mockAccountantUser;
    private User mockDoctorUser;
    private StaffProfile mockAccountantStaff;
    private StaffProfile mockDoctorStaff;
    private Branch mockBranch;
    private PatientProfile mockPatient;
    private Visit mockVisit;
    private com.cvdcms.entity.Service mockService;
    private PerformedService mockPerformedService;
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

        paymentService = new PaymentServiceImpl(
                paymentRepository, invoiceRepository, invoiceAuditLogRepository,
                patientProfileRepository, branchRepository, staffProfileRepository,
                staffBranchAssignmentRepository, patientLoyaltyWalletRepository,
                pointTransactionRepository, membershipTierRepository, userRepository
        );

        refundService = new RefundServiceImpl(
                refundRepository, invoiceRepository, invoiceAuditLogRepository,
                patientProfileRepository, branchRepository, staffProfileRepository,
                staffBranchAssignmentRepository, userRepository
        );

        financeAccessInterceptor = new FinanceAccessInterceptor(
                userRepository, staffBranchAssignmentRepository
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

        mockVisit = new Visit();
        mockVisit.setVisitId(10L);
        mockVisit.setVisitCode("VIS10001");
        mockVisit.setStatus(VisitStatus.WAITING_PAYMENT);
        mockVisit.setPatientProfile(mockPatient);
        mockVisit.setBranch(mockBranch);

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

        mockDoctorUser = new User();
        mockDoctorUser.setUserId(2L);
        mockDoctorUser.setUsername("bacsi_binh");
        mockDoctorUser.setStatus("ACTIVE");
        mockDoctorUser.setIsActive(true);

        mockDoctorStaff = new StaffProfile();
        mockDoctorStaff.setStaffId(2L);
        mockDoctorStaff.setStaffCode("NVBS01");
        mockDoctorStaff.setFullName("Bác Sĩ Bình");
        mockDoctorStaff.setStatus(StaffStatus.ACTIVE);
        mockDoctorStaff.setUser(mockDoctorUser);

        mockService = new com.cvdcms.entity.Service();
        mockService.setServiceId(100L);
        mockService.setServiceName("Trám răng thẩm mỹ Composite");
        mockService.setBasePrice(new BigDecimal("500000.00"));

        mockPerformedService = new PerformedService();
        mockPerformedService.setPerformedId(200L);
        mockPerformedService.setVisit(mockVisit);
        mockPerformedService.setPatientProfile(mockPatient);
        mockPerformedService.setBranch(mockBranch);
        mockPerformedService.setService(mockService);
        mockPerformedService.setQuantity(new BigDecimal("2"));
        mockPerformedService.setUnitPrice(new BigDecimal("500000.00"));
        mockPerformedService.setDiscountAmount(BigDecimal.ZERO);
        mockPerformedService.setFinalPrice(new BigDecimal("1000000.00"));
        mockPerformedService.setStatus(PerformedServiceStatus.COMPLETED);
    }

    private void mockCashierAuthSuccess() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAccountantUser));
        when(staffProfileRepository.findByUser_UserId(1L)).thenReturn(Optional.of(mockAccountantStaff));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
    }

    // =========================================================================
    // 1. Chưa đăng nhập gọi API thu tiền/tạm ứng bị từ chối (401)
    // =========================================================================
    @Test
    @DisplayName("Test 1: Chưa đăng nhập gọi API tài chính trả về HTTP 401 Unauthorized")
    void test1_unauthenticatedApiCallReturns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/payments/create");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = financeAccessInterceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
    }

    // =========================================================================
    // 2. Doctor/Receptionist không được sửa hoặc thu tiền ngoài quyền (403)
    // =========================================================================
    @Test
    @DisplayName("Test 2: Doctor/Receptionist gọi API/trang tài chính bị từ chối HTTP 403 Forbidden")
    void test2_doctorOrReceptionistCannotAdjustOrPayInvoices() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/payments/create");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", mockDoctorUser);
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(userRepository.findById(2L)).thenReturn(Optional.of(mockDoctorUser));
        when(userRepository.hasActiveAdminRole(2L)).thenReturn(false);
        when(userRepository.hasActiveFinanceRole(2L)).thenReturn(false);

        boolean allowed = financeAccessInterceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
    }

    // =========================================================================
    // 3. Kế toán không truy cập được chi nhánh ngoài phân công
    // =========================================================================
    @Test
    @DisplayName("Test 3: Kế toán không thể truy cập dữ liệu của chi nhánh ngoài phân công")
    void test3_accountantCannotAccessBranchOutsideActiveAssignment() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/invoices");
        request.setParameter("branchId", "99"); // Chi nhánh 99 không được phân công
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", mockAccountantUser);
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAccountantUser));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(false);
        when(userRepository.hasActiveFinanceRole(1L)).thenReturn(true);
        // Kế toán chỉ được phân công ở chi nhánh 1
        when(staffBranchAssignmentRepository.findActiveBranchIdsByUserId(1L, AssignmentStatus.ACTIVE))
                .thenReturn(List.of(1L));

        boolean allowed = financeAccessInterceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
    }

    // =========================================================================
    // 4. Một dịch vụ không thể bị lập hóa đơn hai lần
    // =========================================================================
    @Test
    @DisplayName("Test 4: Chặn lập hóa đơn trùng cho cùng một dịch vụ đã thực hiện")
    void test4_cannotInvoiceSamePerformedServiceTwice() {
        mockCashierAuthSuccess();
        when(visitRepository.findById(10L)).thenReturn(Optional.of(mockVisit));
        when(performedServiceRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(mockPerformedService));
        // Dịch vụ 200 đã nằm trong một hóa đơn còn hiệu lực khác
        when(invoiceItemRepository.existsByPerformedIdAndInvoiceNotCancelled(200L)).thenReturn(true);

        InvoiceCreateRequestDTO request = new InvoiceCreateRequestDTO();
        request.setVisitId(10L);
        InvoiceItemRequestDTO item = new InvoiceItemRequestDTO();
        item.setPerformedId(200L);
        item.setServiceId(100L);
        request.setItems(List.of(item));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                invoiceService.createInvoice(request, 1L));
        assertTrue(ex.getMessage().contains("đã được lập hóa đơn còn hiệu lực trước đó"));
    }

    // =========================================================================
    // 5. Bỏ tick dịch vụ thì preview và hóa đơn lưu đều không chứa dịch vụ đó
    // =========================================================================
    @Test
    @DisplayName("Test 5: Dịch vụ bỏ tick không được xuất hiện trong preview và hóa đơn lưu; không chọn gì thì báo lỗi")
    void test5_uncheckingServicesExcludesThemFromPreviewAndSavedInvoice() {
        mockCashierAuthSuccess();
        when(visitRepository.findById(10L)).thenReturn(Optional.of(mockVisit));
        when(performedServiceRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(mockPerformedService));
        when(invoiceItemRepository.existsByPerformedIdAndInvoiceNotCancelled(200L)).thenReturn(false);
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        // Form chỉ gửi dịch vụ 200 (dịch vụ thứ hai đã bị bỏ tick ở giao diện và không nằm trong items)
        InvoiceCreateRequestDTO request = new InvoiceCreateRequestDTO();
        request.setVisitId(10L);
        InvoiceItemRequestDTO item1 = new InvoiceItemRequestDTO();
        item1.setPerformedId(200L);
        item1.setServiceId(100L);
        item1.setUnitPrice(new BigDecimal("500000.00"));
        item1.setQuantity(new BigDecimal("2"));
        request.setItems(List.of(item1));

        InvoiceCalculationPreviewDTO preview = invoiceService.calculatePreview(request);
        assertEquals(new BigDecimal("1000000.00"), preview.getTotalServiceAmount());

        Invoice saved = invoiceService.createInvoice(request, 1L);
        assertNotNull(saved);
        assertEquals(1, saved.getItems().size());
        assertEquals(200L, saved.getItems().get(0).getPerformedService().getPerformedId());

        // Bỏ chọn tất cả (items rỗng)
        InvoiceCreateRequestDTO emptyRequest = new InvoiceCreateRequestDTO();
        emptyRequest.setVisitId(10L);
        emptyRequest.setItems(Collections.emptyList());

        assertThrows(IllegalArgumentException.class, () -> invoiceService.calculatePreview(emptyRequest));
        assertThrows(IllegalArgumentException.class, () -> invoiceService.createInvoice(emptyRequest, 1L));
    }

    // =========================================================================
    // 6. Preview khớp hóa đơn sau khi áp dụng ưu đãi hợp lệ
    // =========================================================================
    @Test
    @DisplayName("Test 6: Preview và Hóa đơn lưu khớp chính xác số tiền sau khi áp dụng giảm trừ thành viên")
    void test6_previewMatchesSavedInvoiceWithValidPromotions() {
        mockCashierAuthSuccess();
        when(visitRepository.findById(10L)).thenReturn(Optional.of(mockVisit));
        when(performedServiceRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(mockPerformedService));
        when(invoiceItemRepository.existsByPerformedIdAndInvoiceNotCancelled(200L)).thenReturn(false);
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        InvoiceCreateRequestDTO request = new InvoiceCreateRequestDTO();
        request.setVisitId(10L);
        InvoiceItemRequestDTO item = new InvoiceItemRequestDTO();
        item.setPerformedId(200L);
        item.setServiceId(100L);
        item.setUnitPrice(new BigDecimal("500000.00"));
        item.setQuantity(new BigDecimal("2"));
        request.setItems(List.of(item));

        InvoiceCalculationPreviewDTO preview = invoiceService.calculatePreview(request);
        Invoice saved = invoiceService.createInvoice(request, 1L);

        // Tổng dịch vụ: 1.000.000đ, Giảm Bạc 5%: 50.000đ -> Phải trả: 950.000đ
        assertEquals(new BigDecimal("1000000.00"), preview.getTotalServiceAmount());
        assertEquals(new BigDecimal("50000.00"), preview.getTotalDiscountAmount());
        assertEquals(new BigDecimal("950000.00"), preview.getTotalAmount());

        assertEquals(preview.getTotalServiceAmount(), saved.getTotalServiceAmount());
        assertEquals(preview.getTotalDiscountAmount(), saved.getDiscountAmount());
        assertEquals(preview.getTotalAmount(), saved.getTotalAmount());
        assertEquals(new BigDecimal("950000.00"), saved.getRemainingDebt());
    }

    // =========================================================================
    // 7. Hóa đơn 1.000.000đ, thu 300.000đ rồi 700.000đ: còn nợ lần lượt 700.000đ và 0đ
    // =========================================================================
    @Test
    @DisplayName("Test 7: Thu 300.000đ rồi 700.000đ: còn nợ lần lượt 700.000đ và 0đ, trạng thái chuyển PARTIAL -> PAID")
    void test7_sequentialPaymentsUpdateDebtAccurately() {
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

    // =========================================================================
    // 8. Thu vượt nợ, số tiền 0/âm đều bị chặn
    // =========================================================================
    @Test
    @DisplayName("Test 8: Thu vượt số còn nợ, số tiền bằng 0 hoặc âm đều bị chặn")
    void test8_overpaymentAndZeroOrNegativeAmountsAreBlocked() {
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

    // =========================================================================
    // 9. Nạp tạm ứng 1.000.000đ, dùng 400.000đ: số dư còn 600.000đ và không cộng trùng tiền thu
    // =========================================================================
    @Test
    @DisplayName("Test 9: Nạp tạm ứng 1.000.000đ, dùng ví trả 400.000đ: số dư còn 600.000đ và không cộng trùng thực thu")
    void test9_advanceDepositAndWalletUsageDoesNotDuplicateRealRevenue() {
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

    // =========================================================================
    // 10. Không thể nạp ví bằng chính ví hoặc dùng ví không có hóa đơn
    // =========================================================================
    @Test
    @DisplayName("Test 10: Chặn nạp tạm ứng bằng DEPOSIT_WALLET và chặn dùng ví không gắn hóa đơn")
    void test10_cannotDepositWalletUsingWalletOrUseWalletWithoutInvoice() {
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

    // =========================================================================
    // 11. Điều chỉnh/hủy/hoàn tiền không làm sai số tiền đã thu
    // =========================================================================
    @Test
    @DisplayName("Test 11: Điều chỉnh tổng tiền < đã thu, hủy hóa đơn đã thu tiền, và hoàn tiền vượt đã thu đều bị chặn")
    void test11_adjustCancelRefundDoesNotCorruptPaidAmount() {
        mockCashierAuthSuccess();

        Invoice invoice = new Invoice();
        invoice.setInvoiceId(102L);
        invoice.setInvoiceCode("HD102");
        invoice.setTotalAmount(new BigDecimal("1000000.00"));
        invoice.setPaidAmount(new BigDecimal("600000.00")); // Đã thu 600.000đ
        invoice.setRemainingDebt(new BigDecimal("400000.00"));
        invoice.setStatus(InvoiceStatus.CONFIRMED);
        invoice.setBranch(mockBranch);

        when(invoiceRepository.findByIdForUpdate(102L)).thenReturn(Optional.of(invoice));

        // 1. Điều chỉnh tổng tiền xuống 500.000đ (< 600.000đ đã thu)
        InvoiceAdjustmentRequestDTO adjustReq = new InvoiceAdjustmentRequestDTO();
        adjustReq.setNewTotalAmount(new BigDecimal("500000.00"));
        adjustReq.setReason("Giảm giá thêm sau thanh toán");
        IllegalArgumentException exAdjust = assertThrows(IllegalArgumentException.class, () ->
                invoiceService.adjustInvoice(102L, adjustReq, 1L));
        assertTrue(exAdjust.getMessage().contains("không được nhỏ hơn số tiền đã thanh toán"));

        // 2. Hủy hóa đơn khi đã có tiền thu 600.000đ
        IllegalStateException exCancel = assertThrows(IllegalStateException.class, () ->
                invoiceService.cancelInvoice(102L, "Khách không đồng ý", 1L));
        assertTrue(exCancel.getMessage().contains("Không thể hủy hóa đơn khi chưa xử lý hoàn tiền"));

        // 3. Hoàn tiền 700.000đ (> 600.000đ đã thu)
        RefundRequestDTO refundReq = new RefundRequestDTO();
        refundReq.setInvoiceId(102L);
        refundReq.setAmount(new BigDecimal("700000.00"));
        refundReq.setReason("Hoàn tiền dịch vụ");
        IllegalArgumentException exRefund = assertThrows(IllegalArgumentException.class, () ->
                refundService.processRefund(refundReq, 1L));
        assertTrue(exRefund.getMessage().contains("vượt quá"));
    }

    // =========================================================================
    // 12. Request đồng thời và request gửi lại không gây giao dịch trùng hoặc mất cập nhật
    // =========================================================================
    @Test
    @DisplayName("Test 12: Request gửi lại với cùng transactionRef trả về giao dịch cũ, không tạo trùng lặp")
    void test12_concurrentOrRepeatedRequestDoesNotDuplicateTransaction() {
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

    // =========================================================================
    // 15. Preview khớp 100% với hóa đơn lưu khi có giảm giá lâm sàng (2.000.000 - 200.000 = 1.800.000)
    // =========================================================================
    @Test
    @DisplayName("Test 15: Dịch vụ 2.000.000đ, giảm giá lâm sàng 200.000đ: Preview và Lưu đều ra 1.800.000đ")
    void test15_previewAndSaveMatchWithClinicalDiscount() {
        mockCashierAuthSuccess();

        // Tạo PerformedService giá 2.000.000đ, giảm giá lâm sàng 200.000đ
        com.cvdcms.entity.Service service2M = new com.cvdcms.entity.Service();
        service2M.setServiceId(105L);
        service2M.setServiceName("Trồng răng Implant");
        service2M.setBasePrice(new BigDecimal("2000000.00"));

        PerformedService ps = new PerformedService();
        ps.setPerformedId(205L);
        ps.setVisit(mockVisit);
        ps.setPatientProfile(mockPatient);
        ps.setBranch(mockBranch);
        ps.setService(service2M);
        ps.setQuantity(BigDecimal.ONE);
        ps.setUnitPrice(new BigDecimal("2000000.00"));
        ps.setDiscountAmount(new BigDecimal("200000.00")); // Giảm lâm sàng 200.000đ
        ps.setFinalPrice(new BigDecimal("1800000.00"));
        ps.setStatus(PerformedServiceStatus.COMPLETED);

        when(performedServiceRepository.findById(205L)).thenReturn(Optional.of(ps));
        when(performedServiceRepository.findByIdForUpdate(205L)).thenReturn(Optional.of(ps));
        when(visitRepository.findById(10L)).thenReturn(Optional.of(mockVisit));
        when(invoiceItemRepository.existsByPerformedIdAndInvoiceNotCancelled(205L)).thenReturn(false);
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // 1. Kiểm tra Preview
        InvoiceCreateRequestDTO request = new InvoiceCreateRequestDTO();
        request.setVisitId(10L);
        request.setProfileId(1L);
        request.setBranchId(1L);
        InvoiceItemRequestDTO itemDTO = new InvoiceItemRequestDTO();
        itemDTO.setPerformedId(205L);
        itemDTO.setUnitPrice(new BigDecimal("2000000.00"));
        itemDTO.setQuantity(BigDecimal.ONE);
        request.setItems(List.of(itemDTO));

        // Bệnh nhân không có ưu đãi tier
        mockPatient.setMembershipTier(null);

        InvoiceCalculationPreviewDTO preview = invoiceService.calculatePreview(request);
        // Preview phải tính (2.000.000 * 1) - 200.000 = 1.800.000đ
        assertEquals(new BigDecimal("1800000.00"), preview.getTotalServiceAmount());
        assertEquals(new BigDecimal("1800000.00"), preview.getTotalAmount());

        // 2. Kiểm tra Lưu hóa đơn
        Invoice saved = invoiceService.createInvoice(request, 1L);
        assertEquals(new BigDecimal("1800000.00"), saved.getTotalServiceAmount());
        assertEquals(new BigDecimal("1800000.00"), saved.getTotalAmount());
        assertEquals(new BigDecimal("1800000.00"), saved.getRemainingDebt());
    }

    // =========================================================================
    // 16. Preview và Lưu đều giới hạn điểm đổi theo số dư thực có trong ví
    // =========================================================================
    @Test
    @DisplayName("Test 16: Điểm đổi vượt số dư thực có được giới hạn nhất quán cả lúc preview và lúc lưu")
    void test16_previewAndSaveCapPointsToWallet() {
        mockCashierAuthSuccess();

        PerformedService ps = new PerformedService();
        ps.setPerformedId(206L);
        ps.setVisit(mockVisit);
        ps.setPatientProfile(mockPatient);
        ps.setBranch(mockBranch);
        ps.setService(mockService);
        ps.setQuantity(BigDecimal.ONE);
        ps.setUnitPrice(new BigDecimal("500000.00"));
        ps.setDiscountAmount(BigDecimal.ZERO);
        ps.setStatus(PerformedServiceStatus.COMPLETED);

        when(performedServiceRepository.findById(206L)).thenReturn(Optional.of(ps));
        when(performedServiceRepository.findByIdForUpdate(206L)).thenReturn(Optional.of(ps));
        when(visitRepository.findById(10L)).thenReturn(Optional.of(mockVisit));
        when(invoiceItemRepository.existsByPerformedIdAndInvoiceNotCancelled(206L)).thenReturn(false);
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Ví chỉ có 50 điểm
        PatientLoyaltyWallet wallet = new PatientLoyaltyWallet();
        wallet.setWalletId(1L);
        wallet.setPatientProfile(mockPatient);
        wallet.setCurrentPoints(50);
        wallet.setTotalSpentAmount(BigDecimal.ZERO);
        when(patientLoyaltyWalletRepository.findByPatientProfile_ProfileId(1L)).thenReturn(Optional.of(wallet));

        mockPatient.setMembershipTier(null);

        // Yêu cầu đổi 500 điểm (vượt quá 50 điểm thực có)
        InvoiceCreateRequestDTO req = new InvoiceCreateRequestDTO();
        req.setVisitId(10L);
        req.setProfileId(1L);
        req.setBranchId(1L);
        req.setPointsToRedeem(500);
        InvoiceItemRequestDTO item = new InvoiceItemRequestDTO();
        item.setPerformedId(206L);
        req.setItems(List.of(item));

        // Preview chỉ được đổi 50 điểm (50 * 10 = 500đ)
        InvoiceCalculationPreviewDTO preview = invoiceService.calculatePreview(req);
        assertEquals(new BigDecimal("500.00"), preview.getPointDiscountAmount());
        assertEquals(new BigDecimal("499500.00"), preview.getTotalAmount());

        // Lưu: điểm chưa bị trừ tại thời điểm tạo hóa đơn (CONFIRMED) theo Requirement 1
        Invoice saved = invoiceService.createInvoice(req, 1L);
        assertEquals(new BigDecimal("500.00"), saved.getPointDiscount());
        assertEquals(new BigDecimal("499500.00"), saved.getTotalAmount());
        assertEquals(50, wallet.getCurrentPoints()); // Vẫn còn 50 điểm trong ví

        // Điểm chỉ được trừ khi chốt thanh toán (finalizeInvoiceBenefits)
        when(patientLoyaltyWalletRepository.findByProfileIdForUpdate(1L)).thenReturn(Optional.of(wallet));
        invoiceService.finalizeInvoiceBenefits(saved);
        assertEquals(0, wallet.getCurrentPoints()); // Số dư về 0 sau khi chốt thanh toán
    }

    // =========================================================================
    // 17. Tính nhất quán của điều chỉnh hóa đơn (Dịch vụ 1.000.000đ, giảm 200.000đ -> tổng 800.000đ)
    // =========================================================================
    @Test
    @DisplayName("Test 17: Điều chỉnh hóa đơn: dịch vụ 1.000.000đ, giảm 200.000đ -> tổng là 800.000đ, cấm mâu thuẫn")
    void test17_adjustInvoiceEnforcesMathematicalConsistency() {
        mockCashierAuthSuccess();

        Invoice invoice = new Invoice();
        invoice.setInvoiceId(104L);
        invoice.setTotalServiceAmount(new BigDecimal("1000000.00"));
        invoice.setDiscountAmount(BigDecimal.ZERO);
        invoice.setPromotionDiscount(BigDecimal.ZERO);
        invoice.setPointDiscount(BigDecimal.ZERO);
        invoice.setTotalAmount(new BigDecimal("1000000.00"));
        invoice.setPaidAmount(BigDecimal.ZERO);
        invoice.setRemainingDebt(new BigDecimal("1000000.00"));
        invoice.setStatus(InvoiceStatus.CONFIRMED);
        invoice.setBranch(mockBranch);

        when(invoiceRepository.findByIdForUpdate(104L)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        // 1. Chỉ điều chỉnh newDiscountAmount = 200.000đ -> tổng mới tự động tính là 800.000đ
        InvoiceAdjustmentRequestDTO adjustReq1 = new InvoiceAdjustmentRequestDTO();
        adjustReq1.setNewDiscountAmount(new BigDecimal("200000.00"));
        adjustReq1.setReason("Áp dụng giảm giá tri ân khách hàng");

        Invoice adjusted1 = invoiceService.adjustInvoice(104L, adjustReq1, 1L);
        assertEquals(new BigDecimal("800000.00"), adjusted1.getTotalAmount());
        assertEquals(new BigDecimal("200000.00"), adjusted1.getDiscountAmount());
        assertEquals(new BigDecimal("800000.00"), adjusted1.getRemainingDebt());

        // 2. Điều chỉnh mâu thuẫn: giảm 200.000đ nhưng ép tổng mới là 900.000đ -> Bị từ chối
        InvoiceAdjustmentRequestDTO adjustReq2 = new InvoiceAdjustmentRequestDTO();
        adjustReq2.setNewDiscountAmount(new BigDecimal("200000.00"));
        adjustReq2.setNewTotalAmount(new BigDecimal("900000.00")); // Mâu thuẫn với 1.000.000 - 200.000 = 800.000
        adjustReq2.setReason("Số liệu lỗi");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                invoiceService.adjustInvoice(104L, adjustReq2, 1L));
        assertTrue(ex.getMessage().contains("Dữ liệu mâu thuẫn"));
    }

    // =========================================================================
    // 18. Chống gửi lặp bằng idempotencyKey riêng biệt cho tiền mặt kể cả khi nợ = 0
    // =========================================================================
    @Test
    @DisplayName("Test 18: Gửi lại thanh toán tiền mặt với idempotencyKey khi đã trả hết nợ trả về giao dịch cũ")
    void test18_idempotencyKeyWithCashAndFinishedDebtReturnsExisting() {
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

    // =========================================================================
    // 19. Hoàn tiền không có hóa đơn (hoàn tạm ứng) kiểm tra nguồn và trừ số dư ví
    // =========================================================================
    @Test
    @DisplayName("Test 19: Hoàn tiền tạm ứng không có hóa đơn kiểm tra và trừ số dư ví của bệnh nhân")
    void test19_advanceDepositRefundDeductsBalance() {
        mockCashierAuthSuccess();

        // Bệnh nhân có 1.000.000đ tạm ứng
        mockPatient.setDepositBalance(new BigDecimal("1000000.00"));
        when(patientProfileRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(mockPatient));
        when(branchRepository.findById(1L)).thenReturn(Optional.of(mockBranch));
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));

        // 1. Hoàn 400.000đ thành công
        RefundRequestDTO refundReq = new RefundRequestDTO();
        refundReq.setProfileId(1L);
        refundReq.setBranchId(1L);
        refundReq.setAmount(new BigDecimal("400000.00"));
        refundReq.setRefundMethod(RefundMethod.CASH);
        refundReq.setReason("Bệnh nhân tạm ngưng điều trị");

        Refund refund = refundService.processRefund(refundReq, 1L);
        assertNotNull(refund);
        assertEquals(new BigDecimal("400000.00"), refund.getAmount());
        assertEquals(new BigDecimal("600000.00"), mockPatient.getDepositBalance()); // Số dư còn lại 600.000đ

        // 2. Hoàn tiếp 700.000đ (> 600.000đ còn lại) -> Bị từ chối
        refundReq.setAmount(new BigDecimal("700000.00"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                refundService.processRefund(refundReq, 1L));
        assertTrue(ex.getMessage().contains("vượt quá số dư"));
    }


    // =========================================================================
    // 13. Thu hôm nay cho hóa đơn lập hôm qua vẫn được tính đúng vào thực thu hôm nay
    // =========================================================================
    @Test
    @DisplayName("Test 13: Giao dịch thu hôm nay cho hóa đơn lập hôm qua tính đúng vào thực thu hôm nay")
    void test13_paymentTodayForInvoiceYesterdayCountedInTodayRevenue() {
        LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        LocalDateTime todayEnd = LocalDateTime.now().withHour(23).withMinute(59).withSecond(59);

        // Giả sử có thanh toán 800.000đ được thực hiện hôm nay cho hóa đơn của ngày hôm qua
        when(paymentRepository.sumRealRevenue(1L, todayStart, todayEnd)).thenReturn(new BigDecimal("800000.00"));
        when(refundRepository.sumRefundAmount(1L, todayStart, todayEnd)).thenReturn(new BigDecimal("100000.00"));
        when(invoiceRepository.sumTotalAmount(1L, todayStart, todayEnd)).thenReturn(BigDecimal.ZERO); // Không lập hóa đơn nào hôm nay

        FinancialMetricsDTO metrics = invoiceService.getFinancialMetrics(1L, todayStart, todayEnd);

        // Thực thu hôm nay = 800.000 - 100.000 = 700.000đ
        assertEquals(new BigDecimal("700000.00"), metrics.getTodayTotalCollected());
        assertEquals(new BigDecimal("100000.00"), metrics.getTodayTotalRefunded());
    }

    // =========================================================================
    // 14. SQL nâng cấp giữ được dữ liệu hiện có
    // =========================================================================
    @Test
    @DisplayName("Test 14: Script SQL nâng cấp bảo toàn dữ liệu hiện có và không chứa lệnh DROP phá hủy")
    void test14_sqlMigrationScriptPreservesExistingData() throws Exception {
        Path migrationPath = Paths.get("database/migrations/V4__ft21_ft22_invoice_and_payment.sql");
        assertTrue(Files.exists(migrationPath), "File migration V4__ft21_ft22_invoice_and_payment.sql phải tồn tại");

        String sql = Files.readString(migrationPath).toUpperCase();

        // Không chứa lệnh DROP DATABASE hoặc DROP TABLE
        assertFalse(sql.contains("DROP DATABASE"), "Script nâng cấp không được DROP DATABASE");
        assertFalse(sql.contains("DROP TABLE"), "Script nâng cấp không được DROP TABLE");

        // Chứa các lệnh ALTER TABLE an toàn và tạo bảng audit log
        assertTrue(sql.contains("ALTER TABLE INVOICES") || sql.contains("ALTER TABLE `INVOICES`"));
        assertTrue(sql.contains("PAYMENTS"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS INVOICE_AUDIT_LOGS") || sql.contains("CREATE TABLE IF NOT EXISTS `INVOICE_AUDIT_LOGS`"));
    }

    // =========================================================================
    // 15. Bệnh nhân tự mua ngoài: Không tạo hóa đơn thuốc, không trừ kho
    // =========================================================================
    @Test
    @DisplayName("Test 15: Bệnh nhân tự mua ngoài: Cập nhật trạng thái EXTERNAL_PURCHASE, không sinh hóa đơn hay trừ kho")
    void test15_externalPurchaseChoiceDoesNotCreateInvoiceOrDeductStock() {
        Prescription presc = new Prescription();
        presc.setPrescriptionId(10L);
        presc.setPrescriptionCode("TOA10001");
        presc.setBranch(mockBranch);
        presc.setStatus(PrescriptionStatus.PENDING);

        when(prescriptionRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(presc));
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAccountantUser));
        when(staffProfileRepository.findByUser_UserId(1L)).thenReturn(Optional.of(mockAccountantStaff));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);

        invoiceService.recordPrescriptionChoice(10L, "EXTERNAL", "Mua tại nhà thuốc tư nhân", 1L);

        assertEquals(PrescriptionStatus.EXTERNAL_PURCHASE, presc.getStatus());
        assertEquals(PrescriptionPurchaseChoice.EXTERNAL, presc.getPurchaseChoice());
        verify(prescriptionRepository).save(presc);
        verifyNoInteractions(invoiceRepository);
        verifyNoInteractions(branchInventoryRepository);
    }

   
    // =========================================================================
    // 16. Mua thuốc tại phòng khám tạo hóa đơn thuốc riêng độc lập
    // =========================================================================
    @Test
    @DisplayName("Test 16: [Unit] Mua thuốc tại phòng khám tạo hóa đơn thuốc riêng với mã riêng, loại MEDICINE và tính bằng BigDecimal")
    void test16_clinicMedicinePurchaseCreatesSeparateMedicineInvoice() {
        Prescription presc = new Prescription();
        presc.setPrescriptionId(20L);
        presc.setPrescriptionCode("TOA20001");
        presc.setBranch(mockBranch);
        presc.setPatientProfile(mockPatient);
        presc.setStatus(PrescriptionStatus.PENDING);

        Material mat = new Material();
        mat.setMaterialId(50L);
        mat.setMaterialName("Paracetamol 500mg");
        mat.setUnit("Viên");

        Medication med = new Medication();
        med.setMedicationId(55L);
        med.setDrugCode("TH001");
        med.setDrugName("Paracetamol 500mg");
        med.setMaterial(mat);
        med.setUnit("Viên");
        med.setSalePrice(new BigDecimal("5000.00"));

        PrescriptionItem item1 = new PrescriptionItem();
        item1.setItemId(101L);
        item1.setPrescription(presc);
        item1.setMedication(med);
        item1.setDrugName("Paracetamol 500mg");
        item1.setUnit("Viên");
        item1.setQuantity(new BigDecimal("10.00"));
        item1.setBoughtQuantity(BigDecimal.ZERO);
        item1.setUnitPrice(new BigDecimal("5000.00"));

        presc.setItems(new java.util.ArrayList<>(List.of(item1)));

        BranchInventory inv = new BranchInventory();
        inv.setBranch(mockBranch);
        inv.setMaterial(mat);
        inv.setCurrentStock(new BigDecimal("100.00"));

        MaterialBatch batch = new MaterialBatch();
        batch.setBatchId(60L);
        batch.setBatchNumber("LO202610A");
        batch.setMaterial(mat);
        batch.setBranch(mockBranch);
        batch.setQuantityRemaining(new BigDecimal("50.00"));
        batch.setExpiryDate(LocalDate.now().plusMonths(6));

        when(prescriptionRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(presc));
        when(prescriptionItemRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(item1));
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAccountantUser));
        when(staffProfileRepository.findByUser_UserId(1L)).thenReturn(Optional.of(mockAccountantStaff));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        when(branchInventoryRepository.findForUpdate(eq(1L), eq(50L))).thenReturn(Optional.of(inv));
        when(materialBatchRepository.findAvailableBatchesFefoForUpdate(eq(1L), eq(50L), any(LocalDate.class)))
                .thenReturn(List.of(batch));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> {
            Invoice invObj = invocation.getArgument(0);
            invObj.setInvoiceId(88L);
            return invObj;
        });

        MedicineInvoiceCreateRequestDTO req = new MedicineInvoiceCreateRequestDTO();
        req.setPrescriptionId(20L);
        req.setNotes("Mua đợt 1");
        req.setItems(List.of(new MedicineInvoiceCreateRequestDTO.Item(101L, new BigDecimal("10.00"))));

        Invoice result = invoiceService.createMedicineInvoice(req, 1L);

        assertNotNull(result);
        assertEquals(InvoiceType.MEDICINE, result.getInvoiceType());
        assertTrue(result.getInvoiceCode().startsWith("HDT"));
        assertEquals(new BigDecimal("50000.00"), result.getTotalAmount());
        assertEquals(new BigDecimal("50000.00"), result.getRemainingDebt());
        assertEquals(new BigDecimal("10.00"), item1.getBoughtQuantity());
        assertEquals(PrescriptionStatus.CLINIC_PURCHASE_COMPLETED, presc.getStatus());
        verify(stockIssueRepository, times(1)).save(any(StockIssue.class));
    }

    // =========================================================================
    // 17. Chặn mua vượt quá số lượng còn lại của đơn thuốc
    // =========================================================================
    @Test
    @DisplayName("Test 17: [Unit] Mua thuốc vượt quá số lượng còn lại của đơn bác sĩ kê bị ném ngoại lệ IllegalStateException")
    void test17_buyingMoreThanRemainingQuantityThrowsException() {
        Prescription presc = new Prescription();
        presc.setPrescriptionId(30L);
        presc.setPrescriptionCode("TOA30001");
        presc.setBranch(mockBranch);
        presc.setPatientProfile(mockPatient);
        presc.setStatus(PrescriptionStatus.PENDING);

        PrescriptionItem item1 = new PrescriptionItem();
        item1.setItemId(102L);
        item1.setPrescription(presc);
        item1.setDrugName("Augmentin 1g");
        item1.setUnit("Viên");
        item1.setQuantity(new BigDecimal("14.00"));
        item1.setBoughtQuantity(new BigDecimal("10.00")); // Đã mua 10, chỉ còn 4
        item1.setUnitPrice(new BigDecimal("22000.00"));

        presc.setItems(new java.util.ArrayList<>(List.of(item1)));

        when(prescriptionRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(presc));
        when(prescriptionItemRepository.findByIdForUpdate(102L)).thenReturn(Optional.of(item1));
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAccountantUser));
        when(staffProfileRepository.findByUser_UserId(1L)).thenReturn(Optional.of(mockAccountantStaff));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);

        MedicineInvoiceCreateRequestDTO req = new MedicineInvoiceCreateRequestDTO();
        req.setPrescriptionId(30L);
        // Cố tình mua 5 (trong khi chỉ còn 4)
        req.setItems(List.of(new MedicineInvoiceCreateRequestDTO.Item(102L, new BigDecimal("5.00"))));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                invoiceService.createMedicineInvoice(req, 1L));

        assertTrue(ex.getMessage().contains("vượt quá số lượng còn lại"));
    }

    // =========================================================================
    // 18. Thiếu liên kết nguồn tồn kho: chặn bán và thông báo lỗi cấu hình
    // =========================================================================
    @Test
    @DisplayName("Test 18: [Unit] Thuốc bán tại phòng khám thiếu liên kết danh mục dược hoặc vật tư kho bị chặn bán và thông báo cấu hình thiếu")
    void test18_missingWarehouseLink_blocksSaleWithConfigurationError() {
        Prescription presc = new Prescription();
        presc.setPrescriptionId(40L);
        presc.setBranch(mockBranch);
        presc.setPatientProfile(mockPatient);
        presc.setStatus(PrescriptionStatus.PENDING);

        // Trường hợp A: PrescriptionItem không có Medication
        PrescriptionItem itemNoMed = new PrescriptionItem();
        itemNoMed.setItemId(201L);
        itemNoMed.setPrescription(presc);
        itemNoMed.setDrugName("Thuốc chưa gán danh mục");
        itemNoMed.setUnit("Viên");
        itemNoMed.setQuantity(new BigDecimal("5.00"));
        itemNoMed.setBoughtQuantity(BigDecimal.ZERO);
        itemNoMed.setUnitPrice(new BigDecimal("10000.00"));
        itemNoMed.setMedication(null);

        presc.setItems(new java.util.ArrayList<>(List.of(itemNoMed)));

        when(prescriptionRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(presc));
        when(prescriptionItemRepository.findByIdForUpdate(201L)).thenReturn(Optional.of(itemNoMed));
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAccountantUser));
        when(staffProfileRepository.findByUser_UserId(1L)).thenReturn(Optional.of(mockAccountantStaff));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);

        MedicineInvoiceCreateRequestDTO reqA = new MedicineInvoiceCreateRequestDTO();
        reqA.setPrescriptionId(40L);
        reqA.setItems(List.of(new MedicineInvoiceCreateRequestDTO.Item(201L, new BigDecimal("5.00"))));

        IllegalStateException exA = assertThrows(IllegalStateException.class, () ->
                invoiceService.createMedicineInvoice(reqA, 1L));
        assertTrue(exA.getMessage().contains("chưa được liên kết với danh mục dược phẩm"));

        // Trường hợp B: Medication không liên kết với Material kho
        Medication medNoMat = new Medication();
        medNoMat.setMedicationId(99L);
        medNoMat.setDrugCode("DUOC_NO_KHO");
        medNoMat.setDrugName("Thuốc không có kho");
        medNoMat.setMaterial(null);
        itemNoMed.setMedication(medNoMat);

        IllegalStateException exB = assertThrows(IllegalStateException.class, () ->
                invoiceService.createMedicineInvoice(reqA, 1L));
        assertTrue(exB.getMessage().contains("chưa được liên kết với vật tư/kho (Material)"));
    }

    // =========================================================================
    // 19. Đơn thuốc chưa bàn giao (ca khám đang trong tiến trình lâm sàng)
    // =========================================================================
    @Test
    @DisplayName("Test 19: [Unit] Đơn thuốc thuộc ca khám chưa bàn giao (đang khám IN_EXAM hoặc WAITING) bị chặn tạo hóa đơn thuốc")
    void test19_unhandedOverPrescription_inExamVisit_blocksSale() {
        Visit inExamVisit = new Visit();
        inExamVisit.setVisitId(500L);
        inExamVisit.setStatus(VisitStatus.IN_EXAM); // Bác sĩ đang khám dở
        inExamVisit.setBranch(mockBranch);
        inExamVisit.setPatientProfile(mockPatient);

        Prescription presc = new Prescription();
        presc.setPrescriptionId(50L);
        presc.setVisit(inExamVisit);
        presc.setBranch(mockBranch);
        presc.setPatientProfile(mockPatient);
        presc.setStatus(PrescriptionStatus.PENDING);

        when(prescriptionRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(presc));

        MedicineInvoiceCreateRequestDTO req = new MedicineInvoiceCreateRequestDTO();
        req.setPrescriptionId(50L);
        req.setItems(List.of(new MedicineInvoiceCreateRequestDTO.Item(101L, BigDecimal.ONE)));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                invoiceService.createMedicineInvoice(req, 1L));
        assertTrue(ex.getMessage().contains("chưa được bác sĩ xác nhận bàn giao"));
    }

    // =========================================================================
    // 20. Backend kiểm tra dòng thuốc thuộc đúng đơn thuốc, bệnh nhân, chi nhánh
    // =========================================================================
    @Test
    @DisplayName("Test 20: [Unit] Backend kiểm tra dòng thuốc không thuộc đơn thuốc hoặc sai chi nhánh bị từ chối")
    void test20_wrongOwnershipAndBranch_blocksSale() {
        Prescription presc = new Prescription();
        presc.setPrescriptionId(60L);
        presc.setBranch(mockBranch);
        presc.setPatientProfile(mockPatient);
        presc.setStatus(PrescriptionStatus.PENDING);

        Prescription otherPresc = new Prescription();
        otherPresc.setPrescriptionId(999L); // Đơn thuốc khác

        PrescriptionItem alienItem = new PrescriptionItem();
        alienItem.setItemId(301L);
        alienItem.setPrescription(otherPresc);
        alienItem.setDrugName("Thuốc lạ");
        alienItem.setQuantity(BigDecimal.TEN);
        alienItem.setBoughtQuantity(BigDecimal.ZERO);

        when(prescriptionRepository.findByIdForUpdate(60L)).thenReturn(Optional.of(presc));
        when(prescriptionItemRepository.findByIdForUpdate(301L)).thenReturn(Optional.of(alienItem));
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAccountantUser));
        when(staffProfileRepository.findByUser_UserId(1L)).thenReturn(Optional.of(mockAccountantStaff));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);

        MedicineInvoiceCreateRequestDTO req = new MedicineInvoiceCreateRequestDTO();
        req.setPrescriptionId(60L);
        req.setItems(List.of(new MedicineInvoiceCreateRequestDTO.Item(301L, BigDecimal.ONE)));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                invoiceService.createMedicineInvoice(req, 1L));
        assertTrue(ex.getMessage().contains("không thuộc đơn thuốc này"));
    }

    // =========================================================================
    // 21. Gửi lặp dòng thuốc trong yêu cầu bị ném ngoại lệ
    // =========================================================================
    @Test
    @DisplayName("Test 21: [Unit] Gửi trùng lặp cùng một ID dòng thuốc trong yêu cầu bị chặn ngay từ backend")
    void test21_duplicateItemInRequest_throwsIllegalArgumentException() {
        Prescription presc = new Prescription();
        presc.setPrescriptionId(70L);
        presc.setBranch(mockBranch);
        presc.setPatientProfile(mockPatient);
        presc.setStatus(PrescriptionStatus.PENDING);

        when(prescriptionRepository.findByIdForUpdate(70L)).thenReturn(Optional.of(presc));
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAccountantUser));
        when(staffProfileRepository.findByUser_UserId(1L)).thenReturn(Optional.of(mockAccountantStaff));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);

        MedicineInvoiceCreateRequestDTO req = new MedicineInvoiceCreateRequestDTO();
        req.setPrescriptionId(70L);
        // Gửi trùng lặp dòng ID 101L hai lần
        req.setItems(List.of(
                new MedicineInvoiceCreateRequestDTO.Item(101L, BigDecimal.ONE),
                new MedicineInvoiceCreateRequestDTO.Item(101L, new BigDecimal("2.00"))
        ));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                invoiceService.createMedicineInvoice(req, 1L));
        assertTrue(ex.getMessage().contains("bị gửi trùng lặp trong yêu cầu"));
    }

    // =========================================================================
    // 22. Lấy thuốc từ nhiều lô (Multi-batch FEFO): lưu đầy đủ từng lô và tạo StockIssue
    // =========================================================================
    @Test
    @DisplayName("Test 22: [Unit] Một dòng thuốc lấy từ nhiều lô: lưu đầy đủ từng lô và số lượng xuất trên hóa đơn và chứng từ xuất kho StockIssue")
    void test22_multiBatchFefoDraw_savesSeparateLinesForEachBatchAndCreatesStockIssue() {
        Prescription presc = new Prescription();
        presc.setPrescriptionId(80L);
        presc.setPrescriptionCode("TOA80001");
        presc.setBranch(mockBranch);
        presc.setPatientProfile(mockPatient);
        presc.setStatus(PrescriptionStatus.PENDING);

        Material mat = new Material();
        mat.setMaterialId(88L);
        mat.setMaterialName("Amoxicillin 500mg");
        mat.setUnit("Viên");

        Medication med = new Medication();
        med.setMedicationId(89L);
        med.setDrugCode("AMOX500");
        med.setDrugName("Amoxicillin 500mg");
        med.setMaterial(mat);
        med.setUnit("Viên");
        med.setSalePrice(new BigDecimal("3000.00"));

        PrescriptionItem item1 = new PrescriptionItem();
        item1.setItemId(401L);
        item1.setPrescription(presc);
        item1.setMedication(med);
        item1.setDrugName("Amoxicillin 500mg");
        item1.setUnit("Viên");
        item1.setQuantity(new BigDecimal("10.00"));
        item1.setBoughtQuantity(BigDecimal.ZERO);
        item1.setUnitPrice(new BigDecimal("3000.00"));
        presc.setItems(new java.util.ArrayList<>(List.of(item1)));

        // Tồn kho chi nhánh: 20 viên
        BranchInventory inv = new BranchInventory();
        inv.setBranch(mockBranch);
        inv.setMaterial(mat);
        inv.setCurrentStock(new BigDecimal("20.00"));

        // Lô 1 (hạn gần hơn): chỉ còn 4 viên
        MaterialBatch batch1 = new MaterialBatch();
        batch1.setBatchId(801L);
        batch1.setBatchNumber("BATCH-EARLY");
        batch1.setMaterial(mat);
        batch1.setBranch(mockBranch);
        batch1.setQuantityRemaining(new BigDecimal("4.00"));
        batch1.setExpiryDate(LocalDate.now().plusMonths(1));

        // Lô 2 (hạn xa hơn): còn 16 viên
        MaterialBatch batch2 = new MaterialBatch();
        batch2.setBatchId(802L);
        batch2.setBatchNumber("BATCH-LATER");
        batch2.setMaterial(mat);
        batch2.setBranch(mockBranch);
        batch2.setQuantityRemaining(new BigDecimal("16.00"));
        batch2.setExpiryDate(LocalDate.now().plusMonths(6));

        when(prescriptionRepository.findByIdForUpdate(80L)).thenReturn(Optional.of(presc));
        when(prescriptionItemRepository.findByIdForUpdate(401L)).thenReturn(Optional.of(item1));
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAccountantUser));
        when(staffProfileRepository.findByUser_UserId(1L)).thenReturn(Optional.of(mockAccountantStaff));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        when(branchInventoryRepository.findForUpdate(eq(1L), eq(88L))).thenReturn(Optional.of(inv));
        when(materialBatchRepository.findAvailableBatchesFefoForUpdate(eq(1L), eq(88L), any(LocalDate.class)))
                .thenReturn(List.of(batch1, batch2));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MedicineInvoiceCreateRequestDTO req = new MedicineInvoiceCreateRequestDTO();
        req.setPrescriptionId(80L);
        req.setItems(List.of(new MedicineInvoiceCreateRequestDTO.Item(401L, new BigDecimal("10.00"))));

        Invoice result = invoiceService.createMedicineInvoice(req, 1L);

        assertNotNull(result);
        // Kiểm tra hóa đơn thuốc có 2 dòng hóa đơn riêng biệt cho 2 lô
        assertEquals(2, result.getItems().size(), "Phải lưu đủ 2 dòng hóa đơn tương ứng với 2 lô thuốc");
        InvoiceItem line1 = result.getItems().get(0);
        assertEquals(new BigDecimal("4.00"), line1.getQuantity());
        assertEquals("BATCH-EARLY", line1.getBatch().getBatchNumber());

        InvoiceItem line2 = result.getItems().get(1);
        assertEquals(new BigDecimal("6.00"), line2.getQuantity());
        assertEquals("BATCH-LATER", line2.getBatch().getBatchNumber());

        // Kiểm tra tồn kho của từng lô
        assertEquals(0, BigDecimal.ZERO.compareTo(batch1.getQuantityRemaining()));
        assertEquals(BatchStatus.OUT_OF_STOCK, batch1.getStatus());
        assertEquals(new BigDecimal("10.00"), batch2.getQuantityRemaining()); // 16 - 6 = 10
        assertEquals(new BigDecimal("10.00"), inv.getCurrentStock()); // 20 - 10 = 10

        // Kiểm tra tạo phiếu xuất kho StockIssue
        verify(stockIssueRepository, times(1)).save(argThat(issue -> {
            assertNotNull(issue);
            assertTrue(issue.getIssueCode().startsWith("PX"));
            assertEquals(2, issue.getItems().size());
            assertEquals(new BigDecimal("4.00"), issue.getItems().get(0).getQuantity());
            assertEquals(new BigDecimal("6.00"), issue.getItems().get(1).getQuantity());
            return true;
        }));
    }

    // =========================================================================
    // 23. Mua đồng thời từ hai đơn dùng chung lô (Concurrency Protection)
    // =========================================================================
    @Test
    @DisplayName("Test 23: [Unit/Concurrency] Hai đơn thuốc khác nhau mua đồng thời từ một lô chung được bảo vệ, chặn bán âm khi hết tồn")
    void test23_concurrentPurchaseFromSharedBatch_protectsStockAgainstOverselling() {
        Material mat = new Material();
        mat.setMaterialId(90L);
        mat.setMaterialName("Ibuprofen 400mg");
        mat.setUnit("Viên");

        Medication med = new Medication();
        med.setMedicationId(91L);
        med.setDrugCode("IBU400");
        med.setDrugName("Ibuprofen 400mg");
        med.setMaterial(mat);
        med.setUnit("Viên");
        med.setSalePrice(new BigDecimal("3500.00"));

        // Lô thuốc chỉ còn đúng 10 viên
        final BigDecimal[] sharedStock = {new BigDecimal("10.00")};

        // Đơn thuốc A cần mua 7 viên
        Prescription prescA = new Prescription();
        prescA.setPrescriptionId(1001L);
        prescA.setBranch(mockBranch);
        prescA.setPatientProfile(mockPatient);
        prescA.setStatus(PrescriptionStatus.PENDING);
        PrescriptionItem itemA = new PrescriptionItem();
        itemA.setItemId(501L);
        itemA.setPrescription(prescA);
        itemA.setMedication(med);
        itemA.setDrugName("Ibuprofen 400mg");
        itemA.setUnit("Viên");
        itemA.setQuantity(new BigDecimal("7.00"));
        itemA.setBoughtQuantity(BigDecimal.ZERO);
        itemA.setUnitPrice(new BigDecimal("3500.00"));
        prescA.setItems(new java.util.ArrayList<>(List.of(itemA)));

        // Đơn thuốc B cũng cần mua 7 viên (Tổng cầu = 14 > 10 tồn kho)
        Prescription prescB = new Prescription();
        prescB.setPrescriptionId(1002L);
        prescB.setBranch(mockBranch);
        prescB.setPatientProfile(mockPatient);
        prescB.setStatus(PrescriptionStatus.PENDING);
        PrescriptionItem itemB = new PrescriptionItem();
        itemB.setItemId(502L);
        itemB.setPrescription(prescB);
        itemB.setMedication(med);
        itemB.setDrugName("Ibuprofen 400mg");
        itemB.setUnit("Viên");
        itemB.setQuantity(new BigDecimal("7.00"));
        itemB.setBoughtQuantity(BigDecimal.ZERO);
        itemB.setUnitPrice(new BigDecimal("3500.00"));
        prescB.setItems(new java.util.ArrayList<>(List.of(itemB)));

        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAccountantUser));
        when(staffProfileRepository.findByUser_UserId(1L)).thenReturn(Optional.of(mockAccountantStaff));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        when(prescriptionRepository.findByIdForUpdate(1001L)).thenReturn(Optional.of(prescA));
        when(prescriptionRepository.findByIdForUpdate(1002L)).thenReturn(Optional.of(prescB));
        when(prescriptionItemRepository.findByIdForUpdate(501L)).thenReturn(Optional.of(itemA));
        when(prescriptionItemRepository.findByIdForUpdate(502L)).thenReturn(Optional.of(itemB));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        // Giả lập khóa bi quan: mỗi lần findForUpdate trả về tồn kho hiện thời của mảng chia sẻ
        when(branchInventoryRepository.findForUpdate(eq(1L), eq(90L))).thenAnswer(inv -> {
            BranchInventory bi = new BranchInventory();
            bi.setBranch(mockBranch);
            bi.setMaterial(mat);
            bi.setCurrentStock(sharedStock[0]);
            return Optional.of(bi);
        });
        when(materialBatchRepository.findAvailableBatchesFefoForUpdate(eq(1L), eq(90L), any(LocalDate.class))).thenAnswer(inv -> {
            if (sharedStock[0].compareTo(BigDecimal.ZERO) <= 0) return List.of();
            MaterialBatch mb = new MaterialBatch();
            mb.setBatchId(999L);
            mb.setBatchNumber("BATCH-SHARED");
            mb.setMaterial(mat);
            mb.setBranch(mockBranch);
            mb.setQuantityRemaining(sharedStock[0]);
            mb.setExpiryDate(LocalDate.now().plusMonths(3));
            return List.of(mb);
        });

        // Ghi nhận cập nhật tồn kho an toàn
        doAnswer(inv -> {
            BranchInventory bi = inv.getArgument(0);
            sharedStock[0] = bi.getCurrentStock();
            return bi;
        }).when(branchInventoryRepository).save(any(BranchInventory.class));

        MedicineInvoiceCreateRequestDTO reqA = new MedicineInvoiceCreateRequestDTO();
        reqA.setPrescriptionId(1001L);
        reqA.setItems(List.of(new MedicineInvoiceCreateRequestDTO.Item(501L, new BigDecimal("7.00"))));

        MedicineInvoiceCreateRequestDTO reqB = new MedicineInvoiceCreateRequestDTO();
        reqB.setPrescriptionId(1002L);
        reqB.setItems(List.of(new MedicineInvoiceCreateRequestDTO.Item(502L, new BigDecimal("7.00"))));

        // Đơn thuốc A mua thành công 7 viên
        Invoice invA = invoiceService.createMedicineInvoice(reqA, 1L);
        assertNotNull(invA);
        assertEquals(new BigDecimal("3.00"), sharedStock[0], "Tồn kho sau khi Đơn A mua 7 viên phải còn đúng 3 viên");

        // Đơn thuốc B mua 7 viên ngay sau đó -> BỊ CHẶN do không đủ tồn kho (còn 3 < 7)
        IllegalStateException exB = assertThrows(IllegalStateException.class, () ->
                invoiceService.createMedicineInvoice(reqB, 1L));
        assertTrue(exB.getMessage().contains("không đủ tồn kho"), "Đơn B phải bị chặn và báo không đủ tồn kho");
        assertEquals(new BigDecimal("3.00"), sharedStock[0], "Tồn kho không bao giờ bị âm");
    }

    // =========================================================================
    // 24. Hủy hóa đơn thuốc: KHÔNG tự ý khôi phục boughtQuantity và KHÔNG tự nhập lại kho
    // =========================================================================
    @Test
    @DisplayName("Test 24: [Unit] Hủy hóa đơn thuốc sau khi xuất: KHÔNG tự ý khôi phục boughtQuantity của đơn và KHÔNG tự nhập lại kho")
    void test24_cancelInvoiceAfterDispensing_doesNotRestoreBoughtQuantityOrRestock() {
        Invoice invoice = new Invoice();
        invoice.setInvoiceId(77L);
        invoice.setInvoiceCode("HDT20261028-TEST");
        invoice.setInvoiceType(InvoiceType.MEDICINE);
        invoice.setStatus(InvoiceStatus.CONFIRMED);
        invoice.setPaymentStatus(InvoicePaymentStatus.UNPAID);
        invoice.setBranch(mockBranch);
        invoice.setTotalAmount(new BigDecimal("50000.00"));
        invoice.setPaidAmount(BigDecimal.ZERO);

        PrescriptionItem prescItem = new PrescriptionItem();
        prescItem.setItemId(701L);
        prescItem.setDrugName("Paracetamol 500mg");
        prescItem.setQuantity(new BigDecimal("10.00"));
        prescItem.setBoughtQuantity(new BigDecimal("10.00")); // Đã xuất vật lý cho người bệnh

        InvoiceItem invItem = new InvoiceItem();
        invItem.setItemType(InvoiceItemType.MEDICINE);
        invItem.setQuantity(new BigDecimal("10.00"));
        invItem.setPrescriptionItem(prescItem);
        invoice.setItems(List.of(invItem));

        when(invoiceRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(invoice));
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAccountantUser));
        when(staffProfileRepository.findByUser_UserId(1L)).thenReturn(Optional.of(mockAccountantStaff));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        // Thực hiện hủy hóa đơn tài chính
        Invoice cancelled = invoiceService.cancelInvoice(77L, "Khách hàng sai thông tin xuất VAT", 1L);

        assertEquals(InvoiceStatus.CANCELLED, cancelled.getStatus());
        // BẢO VỆ: boughtQuantity trên đơn thuốc lâm sàng KHÔNG được tự ý giảm về 0
        assertEquals(new BigDecimal("10.00"), prescItem.getBoughtQuantity(), "Số lượng đã mua không được giảm khi hủy hóa đơn tài chính");
        // BẢO VỆ: Không gọi lưu/khôi phục prescriptionItemRepository
        verify(prescriptionItemRepository, never()).save(any(PrescriptionItem.class));
        // BẢO VỆ: Không tự ý nhập lại kho branchInventoryRepository
        verify(branchInventoryRepository, never()).save(any(BranchInventory.class));
    }

    // =========================================================================
    // 25. Tiếp nhận đơn thuốc sau khi hóa đơn dịch vụ đã thanh toán (ca khám COMPLETED)
    // =========================================================================
    @Test
    @DisplayName("Test 25: [Unit] Sau khi hóa đơn dịch vụ đã thanh toán (ca khám COMPLETED), đơn thuốc vẫn truy cập và lập hóa đơn thuốc riêng bình thường")
    void test25_accessPrescriptionAfterServiceInvoicePaid_allowsPurchaseWithoutDummyServiceInvoice() {
        // Ca khám đã hoàn thành (Dịch vụ đã thanh toán xong)
        Visit completedVisit = new Visit();
        completedVisit.setVisitId(888L);
        completedVisit.setStatus(VisitStatus.COMPLETED);
        completedVisit.setBranch(mockBranch);
        completedVisit.setPatientProfile(mockPatient);

        Prescription presc = new Prescription();
        presc.setPrescriptionId(900L);
        presc.setPrescriptionCode("TOA90001");
        presc.setVisit(completedVisit);
        presc.setBranch(mockBranch);
        presc.setPatientProfile(mockPatient);
        presc.setStatus(PrescriptionStatus.PENDING);

        Material mat = new Material();
        mat.setMaterialId(10L);
        mat.setMaterialName("Thuốc súc miệng Kin");
        mat.setUnit("Chai");

        Medication med = new Medication();
        med.setMedicationId(11L);
        med.setDrugCode("KIN001");
        med.setDrugName("Thuốc súc miệng Kin");
        med.setMaterial(mat);
        med.setUnit("Chai");
        med.setSalePrice(new BigDecimal("130000.00"));

        PrescriptionItem item = new PrescriptionItem();
        item.setItemId(901L);
        item.setPrescription(presc);
        item.setMedication(med);
        item.setDrugName("Thuốc súc miệng Kin");
        item.setUnit("Chai");
        item.setQuantity(new BigDecimal("1.00"));
        item.setBoughtQuantity(BigDecimal.ZERO);
        item.setUnitPrice(new BigDecimal("130000.00"));
        presc.setItems(new java.util.ArrayList<>(List.of(item)));

        BranchInventory inv = new BranchInventory();
        inv.setBranch(mockBranch);
        inv.setMaterial(mat);
        inv.setCurrentStock(new BigDecimal("10.00"));

        MaterialBatch batch = new MaterialBatch();
        batch.setBatchId(12L);
        batch.setBatchNumber("KIN-L1");
        batch.setMaterial(mat);
        batch.setBranch(mockBranch);
        batch.setQuantityRemaining(new BigDecimal("10.00"));
        batch.setExpiryDate(LocalDate.now().plusMonths(12));

        // Kiểm tra getHandedOverPrescriptions cho phép truy cập đơn thuốc khi ca khám đã COMPLETED
        when(prescriptionRepository.findByVisit_VisitIdOrderByCreatedAtDesc(888L)).thenReturn(List.of(presc));
        List<PrescriptionResponseDTO> dtos = invoiceService.getHandedOverPrescriptions(1L, 888L, null);
        assertNotNull(dtos);
        assertEquals(1, dtos.size(), "Đơn thuốc của ca khám COMPLETED phải xuất hiện trong danh sách bàn giao");

        // Tiếp tục lập Hóa đơn thuốc riêng mà không cần lập thêm hóa đơn dịch vụ
        when(prescriptionRepository.findByIdForUpdate(900L)).thenReturn(Optional.of(presc));
        when(prescriptionItemRepository.findByIdForUpdate(901L)).thenReturn(Optional.of(item));
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAccountantUser));
        when(staffProfileRepository.findByUser_UserId(1L)).thenReturn(Optional.of(mockAccountantStaff));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        when(branchInventoryRepository.findForUpdate(eq(1L), eq(10L))).thenReturn(Optional.of(inv));
        when(materialBatchRepository.findAvailableBatchesFefoForUpdate(eq(1L), eq(10L), any(LocalDate.class)))
                .thenReturn(List.of(batch));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(i -> i.getArgument(0));

        MedicineInvoiceCreateRequestDTO req = new MedicineInvoiceCreateRequestDTO();
        req.setPrescriptionId(900L);
        req.setItems(List.of(new MedicineInvoiceCreateRequestDTO.Item(901L, BigDecimal.ONE)));

        Invoice medicineInv = invoiceService.createMedicineInvoice(req, 1L);
        assertNotNull(medicineInv);
        assertEquals(InvoiceType.MEDICINE, medicineInv.getInvoiceType());
        assertEquals(new BigDecimal("130000.00"), medicineInv.getTotalAmount());
    }

    // =========================================================================
    // 26. Cập nhật lựa chọn đơn thuốc: Mua một phần rồi chuyển Tự mua ngoài
    // =========================================================================
    @Test
    @DisplayName("Test 26: [Unit] Cập nhật lựa chọn đơn thuốc: Sau khi mua 1 phần tại PK, chuyển sang Tự mua ngoài và quay lại an toàn")
    void test26_recordPrescriptionChoice_partialPurchaseThenExternal() {
        Prescription presc = new Prescription();
        presc.setPrescriptionId(950L);
        presc.setBranch(mockBranch);
        presc.setStatus(PrescriptionStatus.CLINIC_PURCHASE_PARTIAL);

        PrescriptionItem item1 = new PrescriptionItem();
        item1.setItemId(951L);
        item1.setQuantity(new BigDecimal("10.00"));
        item1.setBoughtQuantity(new BigDecimal("4.00")); // Đã mua 4, còn 6

        presc.setItems(new java.util.ArrayList<>(List.of(item1)));

        when(prescriptionRepository.findByIdForUpdate(950L)).thenReturn(Optional.of(presc));
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAccountantUser));
        when(staffProfileRepository.findByUser_UserId(1L)).thenReturn(Optional.of(mockAccountantStaff));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);

        // Chuyển sang EXTERNAL
        invoiceService.recordPrescriptionChoice(950L, "EXTERNAL", null, 1L);
        assertEquals(PrescriptionStatus.EXTERNAL_PURCHASE, presc.getStatus());
        assertEquals(PrescriptionPurchaseChoice.EXTERNAL, presc.getPurchaseChoice());
        assertTrue(presc.getChoiceNote().contains("Đã mua một phần tại phòng khám"));

        // Chuyển lại CLINIC
        invoiceService.recordPrescriptionChoice(950L, "CLINIC", "Bệnh nhân đổi ý muốn mua nốt tại PK", 1L);
        assertEquals(PrescriptionStatus.CLINIC_PURCHASE_PARTIAL, presc.getStatus());
        assertEquals(PrescriptionPurchaseChoice.CLINIC, presc.getPurchaseChoice());
    }

    // =========================================================================
    // 27. Phát hành MỘT HÓA ĐƠN CHUNG gồm Dịch vụ và Thuốc (InvoiceType.MIXED)
    // =========================================================================
    @Test
    @DisplayName("Test 27: [Unit] Dịch vụ 500.000đ + thuốc 50.000đ => MỘT hóa đơn MIXED 550.000đ")
    void test27_createCommonInvoice_serviceAndMedicine_mixedType() {
        mockCashierAuthSuccess();
        mockPatient.setMembershipTier(null);
        when(visitRepository.findById(mockVisit.getVisitId())).thenReturn(Optional.of(mockVisit));

        // Dịch vụ 500.000đ
        mockPerformedService.setQuantity(BigDecimal.ONE);
        mockPerformedService.setUnitPrice(new BigDecimal("500000.00"));
        mockPerformedService.setFinalPrice(new BigDecimal("500000.00"));
        when(performedServiceRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(mockPerformedService));

        // Thuốc 50.000đ
        Prescription presc = new Prescription();
        presc.setPrescriptionId(900L);
        presc.setVisit(mockVisit);
        presc.setBranch(mockBranch);
        presc.setStatus(PrescriptionStatus.PENDING);

        Material mat = new Material();
        mat.setMaterialId(10L);
        mat.setMaterialName("Paracetamol 500mg");

        Medication med = new Medication();
        med.setMedicationId(11L);
        med.setMaterial(mat);
        med.setDrugName("Paracetamol 500mg");
        med.setSalePrice(new BigDecimal("5000.00"));

        PrescriptionItem pItem = new PrescriptionItem();
        pItem.setItemId(901L);
        pItem.setPrescription(presc);
        pItem.setMedication(med);
        pItem.setDrugName("Paracetamol 500mg");
        pItem.setQuantity(new BigDecimal("10.00"));
        pItem.setBoughtQuantity(BigDecimal.ZERO);
        pItem.setUnitPrice(new BigDecimal("5000.00"));
        presc.setItems(new java.util.ArrayList<>(List.of(pItem)));

        BranchInventory inv = new BranchInventory();
        inv.setBranch(mockBranch);
        inv.setMaterial(mat);
        inv.setCurrentStock(new BigDecimal("100.00"));

        MaterialBatch batch = new MaterialBatch();
        batch.setBatchId(12L);
        batch.setBatchNumber("PARA-01");
        batch.setMaterial(mat);
        batch.setBranch(mockBranch);
        batch.setQuantityRemaining(new BigDecimal("100.00"));
        batch.setExpiryDate(LocalDate.now().plusMonths(12));

        when(prescriptionRepository.findByIdForUpdate(900L)).thenReturn(Optional.of(presc));
        when(prescriptionItemRepository.findByIdForUpdate(901L)).thenReturn(Optional.of(pItem));
        when(branchInventoryRepository.findForUpdate(eq(1L), eq(10L))).thenReturn(Optional.of(inv));
        when(materialBatchRepository.findAvailableBatchesFefoForUpdate(eq(1L), eq(10L), any(LocalDate.class)))
                .thenReturn(List.of(batch));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(i -> i.getArgument(0));

        // Request: 1 dịch vụ + 10 viên thuốc (5.000đ/viên = 50.000đ)
        InvoiceCreateRequestDTO req = new InvoiceCreateRequestDTO();
        req.setVisitId(mockVisit.getVisitId());
        req.setProfileId(mockPatient.getProfileId());
        req.setBranchId(mockBranch.getBranchId());

        InvoiceItemRequestDTO sItem = new InvoiceItemRequestDTO();
        sItem.setPerformedId(200L);
        sItem.setServiceId(100L);
        sItem.setQuantity(BigDecimal.ONE);
        sItem.setUnitPrice(new BigDecimal("500000.00"));
        req.setItems(List.of(sItem));

        req.setPrescriptionId(900L);
        req.setBuyMedicineAtClinic(true);
        req.setMedicineItems(List.of(new MedicineItemRequestDTO(901L, new BigDecimal("10.00"))));

        Invoice invoice = invoiceService.createInvoice(req, 1L);

        assertNotNull(invoice);
        assertEquals(InvoiceType.MIXED, invoice.getInvoiceType(), "Hóa đơn gồm cả dịch vụ và thuốc phải là loại MIXED");
        assertEquals(new BigDecimal("500000.00"), invoice.getTotalServiceAmount());
        assertEquals(new BigDecimal("50000.00"), invoice.getTotalMedicineAmount());
        assertEquals(new BigDecimal("550000.00"), invoice.getTotalAmount(), "Tổng tiền phải bằng 500.000 + 50.000 = 550.000");
        assertEquals(2, invoice.getItems().size(), "Hóa đơn phải có chính xác 2 items (1 dịch vụ, 1 thuốc)");
        assertEquals(new BigDecimal("10.00"), pItem.getBoughtQuantity(), "Đơn thuốc phải được cập nhật boughtQuantity = 10");
        assertEquals(PrescriptionStatus.CLINIC_PURCHASE_COMPLETED, presc.getStatus());
        verify(stockIssueRepository).save(any(StockIssue.class));
    }

    // =========================================================================
    // 28. Không mua thuốc => Hóa đơn dịch vụ (SERVICE) 500.000đ
    // =========================================================================
    @Test
    @DisplayName("Test 28: [Unit] Không chọn mua thuốc => Hóa đơn SERVICE 500.000đ, totalMedicineAmount = 0")
    void test28_createCommonInvoice_serviceOnly_whenMedicineNotSelected() {
        mockCashierAuthSuccess();
        mockPatient.setMembershipTier(null);
        when(visitRepository.findById(mockVisit.getVisitId())).thenReturn(Optional.of(mockVisit));

        mockPerformedService.setQuantity(BigDecimal.ONE);
        mockPerformedService.setUnitPrice(new BigDecimal("500000.00"));
        mockPerformedService.setFinalPrice(new BigDecimal("500000.00"));
        when(performedServiceRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(mockPerformedService));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(i -> i.getArgument(0));

        InvoiceCreateRequestDTO req = new InvoiceCreateRequestDTO();
        req.setVisitId(mockVisit.getVisitId());
        req.setProfileId(mockPatient.getProfileId());
        req.setBranchId(mockBranch.getBranchId());

        InvoiceItemRequestDTO sItem = new InvoiceItemRequestDTO();
        sItem.setPerformedId(200L);
        sItem.setServiceId(100L);
        sItem.setQuantity(BigDecimal.ONE);
        sItem.setUnitPrice(new BigDecimal("500000.00"));
        req.setItems(List.of(sItem));
        req.setBuyMedicineAtClinic(false); // Không mua thuốc tại PK

        Invoice invoice = invoiceService.createInvoice(req, 1L);

        assertNotNull(invoice);
        assertEquals(InvoiceType.SERVICE, invoice.getInvoiceType());
        assertEquals(new BigDecimal("500000.00"), invoice.getTotalServiceAmount());
        assertEquals(new BigDecimal("0.00"), invoice.getTotalMedicineAmount());
        assertEquals(new BigDecimal("500000.00"), invoice.getTotalAmount());
        verify(prescriptionRepository, never()).findByIdForUpdate(anyLong());
        verify(stockIssueRepository, never()).save(any(StockIssue.class));
    }

    // =========================================================================
    // 29. Chỉ mua thuốc hợp lệ khi dịch vụ rỗng => Hóa đơn MEDICINE 50.000đ
    // =========================================================================
    @Test
    @DisplayName("Test 29: [Unit] Không có dịch vụ nhưng có thuốc hợp lệ => Hóa đơn MEDICINE 50.000đ")
    void test29_createCommonInvoice_medicineOnly_whenNoServices() {
        mockCashierAuthSuccess();
        when(visitRepository.findById(mockVisit.getVisitId())).thenReturn(Optional.of(mockVisit));

        Prescription presc = new Prescription();
        presc.setPrescriptionId(900L);
        presc.setVisit(mockVisit);
        presc.setBranch(mockBranch);
        presc.setStatus(PrescriptionStatus.PENDING);

        Material mat = new Material();
        mat.setMaterialId(10L);
        mat.setMaterialName("Amoxicillin 500mg");

        Medication med = new Medication();
        med.setMedicationId(11L);
        med.setMaterial(mat);
        med.setDrugName("Amoxicillin 500mg");
        med.setSalePrice(new BigDecimal("5000.00"));

        PrescriptionItem pItem = new PrescriptionItem();
        pItem.setItemId(901L);
        pItem.setPrescription(presc);
        pItem.setMedication(med);
        pItem.setDrugName("Amoxicillin 500mg");
        pItem.setQuantity(new BigDecimal("10.00"));
        pItem.setBoughtQuantity(BigDecimal.ZERO);
        pItem.setUnitPrice(new BigDecimal("5000.00"));
        presc.setItems(new java.util.ArrayList<>(List.of(pItem)));

        BranchInventory inv = new BranchInventory();
        inv.setBranch(mockBranch);
        inv.setMaterial(mat);
        inv.setCurrentStock(new BigDecimal("100.00"));

        MaterialBatch batch = new MaterialBatch();
        batch.setBatchId(12L);
        batch.setBatchNumber("AMOX-01");
        batch.setMaterial(mat);
        batch.setBranch(mockBranch);
        batch.setQuantityRemaining(new BigDecimal("100.00"));
        batch.setExpiryDate(LocalDate.now().plusMonths(12));

        when(prescriptionRepository.findByIdForUpdate(900L)).thenReturn(Optional.of(presc));
        when(prescriptionItemRepository.findByIdForUpdate(901L)).thenReturn(Optional.of(pItem));
        when(branchInventoryRepository.findForUpdate(eq(1L), eq(10L))).thenReturn(Optional.of(inv));
        when(materialBatchRepository.findAvailableBatchesFefoForUpdate(eq(1L), eq(10L), any(LocalDate.class)))
                .thenReturn(List.of(batch));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(i -> i.getArgument(0));

        // items rỗng, chỉ chọn mua thuốc
        InvoiceCreateRequestDTO req = new InvoiceCreateRequestDTO();
        req.setVisitId(mockVisit.getVisitId());
        req.setProfileId(mockPatient.getProfileId());
        req.setBranchId(mockBranch.getBranchId());
        req.setItems(Collections.emptyList());
        req.setPrescriptionId(900L);
        req.setBuyMedicineAtClinic(true);
        req.setMedicineItems(List.of(new MedicineItemRequestDTO(901L, new BigDecimal("10.00"))));

        Invoice invoice = invoiceService.createInvoice(req, 1L);

        assertNotNull(invoice);
        assertEquals(InvoiceType.MEDICINE, invoice.getInvoiceType(), "Hóa đơn chỉ có thuốc phải có loại MEDICINE");
        assertEquals(new BigDecimal("0.00"), invoice.getTotalServiceAmount());
        assertEquals(new BigDecimal("50000.00"), invoice.getTotalMedicineAmount());
        assertEquals(new BigDecimal("50000.00"), invoice.getTotalAmount());
    }

    // =========================================================================
    // 30. Mua một phần thuốc trong đơn
    // =========================================================================
    @Test
    @DisplayName("Test 30: [Unit] Mua một phần thuốc (kê 10, mua 4) => tính đúng 20.000đ, còn lại 6")
    void test30_createCommonInvoice_partialMedicinePurchase() {
        mockCashierAuthSuccess();
        when(visitRepository.findById(mockVisit.getVisitId())).thenReturn(Optional.of(mockVisit));

        Prescription presc = new Prescription();
        presc.setPrescriptionId(900L);
        presc.setVisit(mockVisit);
        presc.setBranch(mockBranch);
        presc.setStatus(PrescriptionStatus.PENDING);

        Material mat = new Material();
        mat.setMaterialId(10L);
        mat.setMaterialName("Paracetamol 500mg");

        Medication med = new Medication();
        med.setMedicationId(11L);
        med.setMaterial(mat);
        med.setDrugName("Paracetamol 500mg");
        med.setSalePrice(new BigDecimal("5000.00"));

        PrescriptionItem pItem = new PrescriptionItem();
        pItem.setItemId(901L);
        pItem.setPrescription(presc);
        pItem.setMedication(med);
        pItem.setDrugName("Paracetamol 500mg");
        pItem.setQuantity(new BigDecimal("10.00"));
        pItem.setBoughtQuantity(BigDecimal.ZERO);
        pItem.setUnitPrice(new BigDecimal("5000.00"));
        presc.setItems(new java.util.ArrayList<>(List.of(pItem)));

        BranchInventory inv = new BranchInventory();
        inv.setBranch(mockBranch);
        inv.setMaterial(mat);
        inv.setCurrentStock(new BigDecimal("100.00"));

        MaterialBatch batch = new MaterialBatch();
        batch.setBatchId(12L);
        batch.setBatchNumber("PARA-01");
        batch.setMaterial(mat);
        batch.setBranch(mockBranch);
        batch.setQuantityRemaining(new BigDecimal("100.00"));
        batch.setExpiryDate(LocalDate.now().plusMonths(12));

        when(prescriptionRepository.findByIdForUpdate(900L)).thenReturn(Optional.of(presc));
        when(prescriptionItemRepository.findByIdForUpdate(901L)).thenReturn(Optional.of(pItem));
        when(branchInventoryRepository.findForUpdate(eq(1L), eq(10L))).thenReturn(Optional.of(inv));
        when(materialBatchRepository.findAvailableBatchesFefoForUpdate(eq(1L), eq(10L), any(LocalDate.class)))
                .thenReturn(List.of(batch));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(i -> i.getArgument(0));

        InvoiceCreateRequestDTO req = new InvoiceCreateRequestDTO();
        req.setVisitId(mockVisit.getVisitId());
        req.setProfileId(mockPatient.getProfileId());
        req.setBranchId(mockBranch.getBranchId());
        req.setItems(Collections.emptyList());
        req.setPrescriptionId(900L);
        req.setBuyMedicineAtClinic(true);
        req.setMedicineItems(List.of(new MedicineItemRequestDTO(901L, new BigDecimal("4.00")))); // Mua 4 viên

        Invoice invoice = invoiceService.createInvoice(req, 1L);

        assertNotNull(invoice);
        assertEquals(new BigDecimal("20000.00"), invoice.getTotalMedicineAmount());
        assertEquals(new BigDecimal("4.00"), pItem.getBoughtQuantity());
        assertEquals(PrescriptionStatus.CLINIC_PURCHASE_PARTIAL, presc.getStatus());
    }

    // =========================================================================
    // 31. Preview khớp kết quả lưu và ưu đãi chỉ áp lên dịch vụ
    // =========================================================================
    @Test
    @DisplayName("Test 31: [Unit] Preview khớp kết quả lưu và ưu đãi thành viên chỉ giảm trên dịch vụ, không giảm trên thuốc")
    void test31_calculatePreview_matchesSavedInvoice_andDiscountsApplyToServiceOnly() {
        mockPatient.setMembershipTier(mockTier); // Giảm 5%
        when(patientProfileRepository.findById(mockPatient.getProfileId())).thenReturn(Optional.of(mockPatient));

        Prescription presc = new Prescription();
        presc.setPrescriptionId(900L);
        presc.setVisit(mockVisit);
        presc.setBranch(mockBranch);

        Medication med = new Medication();
        med.setMedicationId(11L);
        med.setDrugName("Paracetamol");
        med.setSalePrice(new BigDecimal("5000.00"));

        PrescriptionItem pItem = new PrescriptionItem();
        pItem.setItemId(901L);
        pItem.setPrescription(presc);
        pItem.setMedication(med);
        pItem.setQuantity(new BigDecimal("10.00"));
        pItem.setBoughtQuantity(BigDecimal.ZERO);
        pItem.setUnitPrice(new BigDecimal("5000.00"));
        presc.setItems(new java.util.ArrayList<>(List.of(pItem)));

        when(prescriptionItemRepository.findById(901L)).thenReturn(Optional.of(pItem));

        InvoiceCreateRequestDTO req = new InvoiceCreateRequestDTO();
        req.setProfileId(mockPatient.getProfileId());
        req.setVisitId(mockVisit.getVisitId());
        req.setPrescriptionId(900L);
        req.setBuyMedicineAtClinic(true);

        InvoiceItemRequestDTO sItem = new InvoiceItemRequestDTO();
        sItem.setPerformedId(200L);
        sItem.setServiceId(100L);
        sItem.setQuantity(BigDecimal.ONE);
        sItem.setUnitPrice(new BigDecimal("500000.00"));
        req.setItems(List.of(sItem));
        req.setMedicineItems(List.of(new MedicineItemRequestDTO(901L, new BigDecimal("10.00"))));

        InvoiceCalculationPreviewDTO preview = invoiceService.calculatePreview(req);

        assertNotNull(preview);
        assertEquals(new BigDecimal("500000.00"), preview.getTotalServiceAmount());
        assertEquals(new BigDecimal("50000.00"), preview.getTotalMedicineAmount());
        // Giảm 5% của 500.000 = 25.000. KHÔNG giảm 5% trên 50.000 thuốc!
        assertEquals(new BigDecimal("25000.00"), preview.getMemberDiscountAmount());
        // Tổng phải thanh toán: 500.000 - 25.000 + 50.000 = 525.000
        assertEquals(new BigDecimal("525000.00"), preview.getTotalAmount());
    }

    // =========================================================================
    // 32. Cách ly ca khám: Không cho phép gom đơn thuốc của ca khám khác
    // =========================================================================
    @Test
    @DisplayName("Test 32: [Unit] Bắt lỗi khi đơn thuốc không thuộc ca khám được chọn")
    void test32_visitIsolation_cannotUsePrescriptionFromDifferentVisit() {
        mockCashierAuthSuccess();
        when(visitRepository.findById(mockVisit.getVisitId())).thenReturn(Optional.of(mockVisit));

        Visit anotherVisit = new Visit();
        anotherVisit.setVisitId(999L);
        anotherVisit.setVisitCode("VIS999");

        Prescription prescOfAnotherVisit = new Prescription();
        prescOfAnotherVisit.setPrescriptionId(900L);
        prescOfAnotherVisit.setVisit(anotherVisit);
        prescOfAnotherVisit.setBranch(mockBranch);

        when(prescriptionRepository.findByIdForUpdate(900L)).thenReturn(Optional.of(prescOfAnotherVisit));

        InvoiceCreateRequestDTO req = new InvoiceCreateRequestDTO();
        req.setVisitId(mockVisit.getVisitId());
        req.setProfileId(mockPatient.getProfileId());
        req.setBranchId(mockBranch.getBranchId());
        req.setPrescriptionId(900L);
        req.setBuyMedicineAtClinic(true);
        req.setMedicineItems(List.of(new MedicineItemRequestDTO(901L, BigDecimal.ONE)));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> invoiceService.createInvoice(req, 1L));
        assertTrue(ex.getMessage().contains("không thuộc ca khám"));
    }

    // =========================================================================
    // 33. Thuốc chưa có giá bán hợp lệ phải báo lỗi cấu hình (không đoán giá)
    // =========================================================================
    @Test
    @DisplayName("Test 33: [Unit] Thuốc chưa có giá bán hợp lệ phải báo lỗi cấu hình, không đoán giá")
    void test33_medicationWithoutPriceThrowsException() {
        mockCashierAuthSuccess();
        when(visitRepository.findById(mockVisit.getVisitId())).thenReturn(Optional.of(mockVisit));

        Prescription presc = new Prescription();
        presc.setPrescriptionId(900L);
        presc.setVisit(mockVisit);
        presc.setBranch(mockBranch);

        Medication medWithoutPrice = new Medication();
        medWithoutPrice.setMedicationId(11L);
        medWithoutPrice.setDrugName("Thuốc Thử Nghiệm");
        medWithoutPrice.setSalePrice(null); // Không có giá

        PrescriptionItem pItem = new PrescriptionItem();
        pItem.setItemId(901L);
        pItem.setPrescription(presc);
        pItem.setMedication(medWithoutPrice);
        pItem.setUnitPrice(null); // Đơn thuốc cũng không có giá
        pItem.setQuantity(BigDecimal.ONE);
        pItem.setBoughtQuantity(BigDecimal.ZERO);
        presc.setItems(new java.util.ArrayList<>(List.of(pItem)));

        when(prescriptionRepository.findByIdForUpdate(900L)).thenReturn(Optional.of(presc));
        when(prescriptionItemRepository.findByIdForUpdate(901L)).thenReturn(Optional.of(pItem));

        InvoiceCreateRequestDTO req = new InvoiceCreateRequestDTO();
        req.setVisitId(mockVisit.getVisitId());
        req.setProfileId(mockPatient.getProfileId());
        req.setBranchId(mockBranch.getBranchId());
        req.setPrescriptionId(900L);
        req.setBuyMedicineAtClinic(true);
        req.setMedicineItems(List.of(new MedicineItemRequestDTO(901L, BigDecimal.ONE)));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> invoiceService.createInvoice(req, 1L));
        assertTrue(ex.getMessage().contains("chưa có giá bán hợp lệ"));
    }

    // =========================================================================
    // 34. Điều chỉnh hóa đơn bảo toàn số tiền thuốc
    // =========================================================================
    @Test
    @DisplayName("Test 34: [Unit] adjustInvoice bảo toàn totalMedicineAmount khi cập nhật phụ thu")
    void test34_adjustInvoice_preservesMedicineTotal() {
        mockCashierAuthSuccess();

        Invoice existingInvoice = new Invoice();
        existingInvoice.setInvoiceId(101L);
        existingInvoice.setInvoiceCode("HD101");
        existingInvoice.setInvoiceType(InvoiceType.MIXED);
        existingInvoice.setBranch(mockBranch);
        existingInvoice.setPatientProfile(mockPatient);
        existingInvoice.setTotalServiceAmount(new BigDecimal("500000.00"));
        existingInvoice.setTotalMedicineAmount(new BigDecimal("50000.00"));
        existingInvoice.setDiscountAmount(BigDecimal.ZERO);
        existingInvoice.setPromotionDiscount(BigDecimal.ZERO);
        existingInvoice.setPointDiscount(BigDecimal.ZERO);
        existingInvoice.setTotalAmount(new BigDecimal("550000.00"));
        existingInvoice.setPaidAmount(BigDecimal.ZERO);
        existingInvoice.setRemainingDebt(new BigDecimal("550000.00"));
        existingInvoice.setPaymentStatus(InvoicePaymentStatus.UNPAID);
        existingInvoice.setStatus(InvoiceStatus.CONFIRMED);

        when(invoiceRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(existingInvoice));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(i -> i.getArgument(0));

        // Điều chỉnh thêm phụ thu 10.000đ
        InvoiceAdjustmentRequestDTO req = new InvoiceAdjustmentRequestDTO();
        req.setNewDiscountAmount(BigDecimal.ZERO);
        req.setNewSurchargeAmount(new BigDecimal("10000.00"));
        req.setNewSurchargeDescription("Phụ thu ngoài giờ");
        req.setReason("Áp dụng phụ thu");

        Invoice adjusted = invoiceService.adjustInvoice(101L, req, 1L);

        assertNotNull(adjusted);
        assertEquals(new BigDecimal("50000.00"), adjusted.getTotalMedicineAmount(), "Tiền thuốc 50.000đ phải được giữ nguyên");
        assertEquals(new BigDecimal("500000.00"), adjusted.getTotalServiceAmount(), "Tiền dịch vụ 500.000đ giữ nguyên");
        // Tổng mới = 500.000 (dịch vụ) + 50.000 (thuốc) + 10.000 (phụ thu) = 560.000
        assertEquals(new BigDecimal("560000.00"), adjusted.getTotalAmount());
        assertEquals(new BigDecimal("560000.00"), adjusted.getRemainingDebt());
    }

    // =========================================================================
    // 35. Kiểm tra tỷ lệ quy đổi điểm mới: 1 điểm = 10 VNĐ (100 điểm = 1.000 VNĐ)
    //     Dịch vụ 700.000 VNĐ -> còn 699.000 VNĐ khi đổi 100 điểm
    // =========================================================================
    @Test
    @DisplayName("Test 35: Tỷ lệ đổi điểm 10 VNĐ/điểm: 100 điểm -> giảm 1.000 VNĐ, dịch vụ 700.000 VNĐ còn 699.000 VNĐ")
    void test35_pointConversionRate_100Points_1000Vnd_700kServiceRemaining699k() {
        mockCashierAuthSuccess();

        PerformedService ps700 = new PerformedService();
        ps700.setPerformedId(270L);
        ps700.setVisit(mockVisit);
        ps700.setPatientProfile(mockPatient);
        ps700.setBranch(mockBranch);
        ps700.setService(mockService);
        ps700.setQuantity(BigDecimal.ONE);
        ps700.setUnitPrice(new BigDecimal("700000.00"));
        ps700.setDiscountAmount(BigDecimal.ZERO);
        ps700.setStatus(PerformedServiceStatus.COMPLETED);

        when(performedServiceRepository.findById(270L)).thenReturn(Optional.of(ps700));
        when(performedServiceRepository.findByIdForUpdate(270L)).thenReturn(Optional.of(ps700));
        when(visitRepository.findById(10L)).thenReturn(Optional.of(mockVisit));
        when(invoiceItemRepository.existsByPerformedIdAndInvoiceNotCancelled(270L)).thenReturn(false);
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Khách hàng có 200 điểm trong ví
        PatientLoyaltyWallet wallet = new PatientLoyaltyWallet();
        wallet.setWalletId(10L);
        wallet.setPatientProfile(mockPatient);
        wallet.setCurrentPoints(200);
        wallet.setTotalSpentAmount(BigDecimal.ZERO);
        when(patientLoyaltyWalletRepository.findByPatientProfile_ProfileId(1L)).thenReturn(Optional.of(wallet));

        // Bệnh nhân có hạng thành viên với pointValue = 10.00
        MembershipTier tier10 = new MembershipTier();
        tier10.setTierId(99L);
        tier10.setTierName("Hạng chuẩn 10đ");
        tier10.setDiscountPercent(BigDecimal.ZERO);
        tier10.setPointValue(new BigDecimal("10.00"));
        tier10.setSpendPerPoint(new BigDecimal("10000.00"));
        mockPatient.setMembershipTier(tier10);

        // Yêu cầu đổi 100 điểm
        InvoiceCreateRequestDTO req = new InvoiceCreateRequestDTO();
        req.setVisitId(10L);
        req.setProfileId(1L);
        req.setBranchId(1L);
        req.setPointsToRedeem(100);
        InvoiceItemRequestDTO item = new InvoiceItemRequestDTO();
        item.setPerformedId(270L);
        req.setItems(List.of(item));

        // 1. Preview: Giảm 100 * 10 = 1.000 VNĐ. Dịch vụ 700.000 VNĐ -> còn 699.000 VNĐ
        InvoiceCalculationPreviewDTO preview = invoiceService.calculatePreview(req);
        assertEquals(new BigDecimal("1000.00"), preview.getPointDiscountAmount());
        assertEquals(new BigDecimal("699000.00"), preview.getTotalAmount());

        // 2. Tạo hóa đơn: pointDiscount = 1.000 VNĐ, totalAmount = 699.000 VNĐ
        Invoice saved = invoiceService.createInvoice(req, 1L);
        saved.setInvoiceId(999L);
        saved.setInvoiceCode("HD-TEST-100PT");
        assertEquals(new BigDecimal("1000.00"), saved.getPointDiscount());
        assertEquals(new BigDecimal("699000.00"), saved.getTotalAmount());
        assertEquals(200, wallet.getCurrentPoints()); // Chưa thanh toán nên chưa trừ điểm

        // 3. Thanh toán: trừ đúng 100 điểm
        when(patientLoyaltyWalletRepository.findByProfileIdForUpdate(1L)).thenReturn(Optional.of(wallet));
        invoiceService.finalizeInvoiceBenefits(saved);
        assertEquals(100, wallet.getCurrentPoints()); // 200 - 100 = 100

        // 4. Hủy hóa đơn hoàn lại đúng 100 điểm từ transaction REDEEM gốc
        PointTransaction redeemTx = new PointTransaction();
        redeemTx.setTransactionType(PointTransactionType.REDEEM);
        redeemTx.setPoints(100);
        when(pointTransactionRepository.existsByInvoice_InvoiceIdAndTransactionType(999L, PointTransactionType.REDEEM)).thenReturn(true);
        when(pointTransactionRepository.existsByInvoice_InvoiceIdAndTransactionTypeAndPointsGreaterThan(999L, PointTransactionType.ADJUST, 0)).thenReturn(false);
        when(pointTransactionRepository.findByInvoice_InvoiceId(999L)).thenReturn(List.of(redeemTx));
        when(invoiceRepository.findByIdForUpdate(999L)).thenReturn(Optional.of(saved));

        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAccountantUser));
        when(staffProfileRepository.findByUser_UserId(1L)).thenReturn(Optional.of(mockAccountantStaff));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);

        invoiceService.cancelInvoice(999L, "Khách hủy ca", 1L);
        assertEquals(200, wallet.getCurrentPoints()); // Đã hoàn lại đủ 100 điểm về 200
    }
}

