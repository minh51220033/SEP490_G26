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
                pointTransactionRepository, userRepository, paymentRepository, refundRepository
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
        mockTier.setPointValue(new BigDecimal("1000.00"));
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

        // Preview chỉ được đổi 50 điểm (50 * 1000 = 50.000đ)
        InvoiceCalculationPreviewDTO preview = invoiceService.calculatePreview(req);
        assertEquals(new BigDecimal("50000.00"), preview.getPointDiscountAmount());
        assertEquals(new BigDecimal("450000.00"), preview.getTotalAmount());

        // Lưu: điểm chưa bị trừ tại thời điểm tạo hóa đơn (CONFIRMED) theo Requirement 1
        Invoice saved = invoiceService.createInvoice(req, 1L);
        assertEquals(new BigDecimal("50000.00"), saved.getPointDiscount());
        assertEquals(new BigDecimal("450000.00"), saved.getTotalAmount());
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
}
