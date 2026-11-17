package com.cvdcms;

import com.cvdcms.DTO.*;
import com.cvdcms.DTO.report.ReportDashboardDTO;
import com.cvdcms.config.FeaturePermissionInterceptor;
import com.cvdcms.controller.PaymentRestController;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.*;
import com.cvdcms.service.impl.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditRegressionUnitTest {

    @Mock private TreatmentPlanRepository treatmentPlanRepository;
    @Mock private TreatmentPlanStageRepository treatmentPlanStageRepository;
    @Mock private TreatmentPlanItemRepository treatmentPlanItemRepository;
    @Mock private PatientProfileRepository patientProfileRepository;
    @Mock private BranchRepository branchRepository;
    @Mock private StaffProfileRepository staffProfileRepository;
    @Mock private ServiceRepository serviceRepository;
    @Mock private GeminiAiService geminiAiService;
    @Mock private PerformedServiceRepository performedServiceRepository;
    @Mock private InvoiceItemRepository invoiceItemRepository;
    @Mock private TreatmentPlanInstallmentRepository treatmentPlanInstallmentRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private UserRepository userRepository;
    @Mock private StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    @Mock private LoyaltyService loyaltyService;
    @Mock private BranchBankAccountRepository branchBankAccountRepository;

    @Mock private InvoiceRepository invoiceRepository;
    @Mock private InvoiceAuditLogRepository invoiceAuditLogRepository;
    @Mock private RefundRepository refundRepository;
    @Mock private VisitRepository visitRepository;
    @Mock private PromotionRepository promotionRepository;
    @Mock private PatientVoucherRepository patientVoucherRepository;
    @Mock private PatientLoyaltyWalletRepository patientLoyaltyWalletRepository;
    @Mock private PointTransactionRepository pointTransactionRepository;
    @Mock private MembershipTierRepository membershipTierRepository;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private PrescriptionItemRepository prescriptionItemRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private BranchInventoryRepository branchInventoryRepository;
    @Mock private MedicationRepository medicationRepository;
    @Mock private StockIssueRepository stockIssueRepository;

    @Mock private OperatingExpenseRepository operatingExpenseRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private PaymentAllocationRepository paymentAllocationRepository;
    @Mock private PaymentAllocationService paymentAllocationService;
    @Mock private PaymentService paymentService;
    @Mock private RefundService refundService;

    private TreatmentPlanServiceImpl buildPlanService() {
        TreatmentPlanServiceImpl svc = new TreatmentPlanServiceImpl(
                treatmentPlanRepository,
                treatmentPlanStageRepository,
                treatmentPlanItemRepository,
                patientProfileRepository,
                branchRepository,
                staffProfileRepository,
                serviceRepository,
                performedServiceRepository,
                invoiceItemRepository,
                treatmentPlanInstallmentRepository
        );
        ReflectionTestUtils.setField(svc, "paymentRepository", paymentRepository);
        ReflectionTestUtils.setField(svc, "userRepository", userRepository);
        ReflectionTestUtils.setField(svc, "staffBranchAssignmentRepository", staffBranchAssignmentRepository);
        ReflectionTestUtils.setField(svc, "loyaltyService", loyaltyService);
        ReflectionTestUtils.setField(svc, "branchBankAccountRepository", branchBankAccountRepository);
        return svc;
    }

    private InvoiceServiceImpl buildInvoiceService() {
        InvoiceServiceImpl svc = new InvoiceServiceImpl(
                invoiceRepository, invoiceItemRepository, invoiceAuditLogRepository,
                visitRepository, patientProfileRepository, branchRepository,
                staffProfileRepository, staffBranchAssignmentRepository,
                serviceRepository, performedServiceRepository, promotionRepository,
                patientVoucherRepository, patientLoyaltyWalletRepository,
                pointTransactionRepository, userRepository, paymentRepository, refundRepository,
                prescriptionRepository, prescriptionItemRepository, materialBatchRepository,
                branchInventoryRepository, medicationRepository, stockIssueRepository
        );
        svc.setMembershipTierRepository(membershipTierRepository);
        return svc;
    }

    private RefundServiceImpl buildRefundService() {
        return new RefundServiceImpl(
                refundRepository, invoiceRepository, invoiceAuditLogRepository,
                patientProfileRepository, branchRepository, staffProfileRepository,
                staffBranchAssignmentRepository, userRepository
        );
    }

    private User activeUser(Long userId) {
        User u = new User();
        u.setUserId(userId);
        u.setIsActive(true);
        u.setStatus("ACTIVE");
        return u;
    }

    private StaffProfile activeStaff(Long staffId, User user) {
        StaffProfile s = new StaffProfile();
        s.setStaffId(staffId);
        s.setUser(user);
        s.setStaffCode("NV" + staffId);
        s.setFullName("Nhân viên " + staffId);
        s.setStatus(StaffStatus.ACTIVE);
        return s;
    }

    private Branch branch(Long id) {
        Branch b = new Branch();
        b.setBranchId(id);
        b.setBranchCode("CN0" + id);
        b.setBranchName("Chi nhánh " + id);
        return b;
    }

    private PatientProfile patient(Long id, BigDecimal depositBalance) {
        PatientProfile p = new PatientProfile();
        p.setProfileId(id);
        p.setPatientCode("BN00" + id);
        p.setFullName("Bệnh nhân " + id);
        p.setDepositBalance(depositBalance);
        return p;
    }

    @Test
    @DisplayName("B02/R01: Thu tiền trả góp tạo Payment ledger, trừ DEPOSIT_WALLET và chống idempotency")
    void b02_installmentPaymentCreatesPaymentAndHandlesWalletAndIdempotency() {
        TreatmentPlanServiceImpl planService = buildPlanService();
        Branch b = branch(1L);
        PatientProfile p = patient(10L, new BigDecimal("5000000"));
        User cashierUser = activeUser(20L);
        StaffProfile cashierStaff = activeStaff(200L, cashierUser);

        TreatmentPlan plan = new TreatmentPlan();
        plan.setPlanId(100L);
        plan.setPlanCode("KHDT-001");
        plan.setPatientProfile(p);
        plan.setBranch(b);
        plan.setFinalEstimatedCost(new BigDecimal("10000000"));
        plan.setTotalPaidAmount(BigDecimal.ZERO);
        plan.setStatus(TreatmentPlanStatus.IN_PROGRESS);
        plan.setInstallments(List.of());
        plan.setStages(List.of());

        when(treatmentPlanRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(plan));
        when(treatmentPlanRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(20L)).thenReturn(Optional.of(cashierUser));
        when(userRepository.hasActiveAdminRole(20L)).thenReturn(false);
        when(userRepository.hasActivePermission(20L, "PAYMENT_COLLECT")).thenReturn(true);
        when(staffProfileRepository.findByUser_UserId(20L)).thenReturn(Optional.of(cashierStaff));
        when(staffBranchAssignmentRepository.hasActiveAssignment(200L, 1L, AssignmentStatus.ACTIVE)).thenReturn(true);
        when(patientProfileRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(p));
        when(patientProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment saved = inv.getArgument(0);
            saved.setPaymentId(999L);
            return saved;
        });

        InstallmentPaymentRequestDTO req = new InstallmentPaymentRequestDTO();
        req.setPlanId(100L);
        req.setAmount(new BigDecimal("2000000"));
        req.setPaymentMethod(PaymentMethod.DEPOSIT_WALLET);
        req.setIdempotencyKey("INST-KEY-1");

        when(paymentRepository.findByIdempotencyKey("INST-KEY-1")).thenReturn(Optional.empty());

        TreatmentPlanResponseDTO res = planService.recordInstallmentPayment(100L, req, 20L);
        assertEquals(0, new BigDecimal("2000000").compareTo(res.getTotalPaidAmount()));
        assertEquals(0, new BigDecimal("3000000").compareTo(p.getDepositBalance()));
        verify(paymentRepository).save(argThat(pay ->
                pay.getPaymentType() == PaymentType.PAYMENT
                        && pay.getInvoice() == null
                        && pay.getPaymentMethod() == PaymentMethod.DEPOSIT_WALLET
                        && "INST-KEY-1".equals(pay.getIdempotencyKey())
        ));
        verify(loyaltyService, never()).recordPaymentPoints(any());

        // Duplicate idempotency key returns early without charging twice
        Payment existing = new Payment();
        existing.setPaymentId(999L);
        existing.setPaymentType(PaymentType.PAYMENT);
        existing.setPaymentMethod(PaymentMethod.DEPOSIT_WALLET);
        existing.setAmount(new BigDecimal("2000000"));
        existing.setPatientProfile(p);
        existing.setBranch(b);
        existing.setNotes("[KHDT: KHDT-001]");
        when(paymentRepository.findByIdempotencyKey("INST-KEY-1")).thenReturn(Optional.of(existing));
        planService.recordInstallmentPayment(100L, req, 20L);
        assertEquals(0, new BigDecimal("3000000").compareTo(p.getDepositBalance()));
        verify(paymentRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("B03/B04/B05: FeaturePermissionInterceptor chặn sai quyền cho trả góp, hoàn tiền, chi phí & tạm ứng")
    void b03_b04_b05_interceptorPermissionChecks() throws Exception {
        FeaturePermissionInterceptor interceptor = new FeaturePermissionInterceptor(userRepository);
        User nonAdmin = activeUser(30L);

        when(userRepository.hasActiveAdminRole(30L)).thenReturn(false);
        when(userRepository.findActiveRoleNamesByUserId(30L)).thenReturn(List.of("BAC_SI"));

        // B03: Doctor with TREATMENT_PLAN_MANAGE cannot hit installments/pay (requires PAYMENT_COLLECT)
        when(userRepository.findActivePermissionCodesByUserId(30L)).thenReturn(List.of("TREATMENT_PLAN_MANAGE"));
        MockHttpServletRequest reqInst = new MockHttpServletRequest("POST", "/api/doctor/treatment-plans/5/installments/pay");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", nonAdmin);
        reqInst.setSession(session);
        assertFalse(interceptor.preHandle(reqInst, new MockHttpServletResponse(), new Object()));

        // Cashier with PAYMENT_COLLECT can access installments/pay
        when(userRepository.findActivePermissionCodesByUserId(30L)).thenReturn(List.of("PAYMENT_COLLECT"));
        assertTrue(interceptor.preHandle(reqInst, new MockHttpServletResponse(), new Object()));

        // B04: Cashier without REFUND_MANAGE is denied on /api/payments/refund
        MockHttpServletRequest reqRefund = new MockHttpServletRequest("POST", "/api/payments/refund");
        reqRefund.setSession(session);
        assertFalse(interceptor.preHandle(reqRefund, new MockHttpServletResponse(), new Object()));

        // B05: User with PAYMENT_COLLECT is denied on /expenses and /payroll/advances
        MockHttpServletRequest reqExpense = new MockHttpServletRequest("GET", "/expenses");
        reqExpense.setSession(session);
        assertFalse(interceptor.preHandle(reqExpense, new MockHttpServletResponse(), new Object()));

        MockHttpServletRequest reqAdv = new MockHttpServletRequest("GET", "/payroll/advances");
        reqAdv.setSession(session);
        assertFalse(interceptor.preHandle(reqAdv, new MockHttpServletResponse(), new Object()));
    }

    @Test
    @DisplayName("B06: PaymentRestController trả 401 khi chưa đăng nhập và 403 khi khác chi nhánh")
    void b06_paymentRestControllerEnforcesAuthAndBranch() {
        PaymentRestController controller = new PaymentRestController(
                paymentService, refundService, paymentAllocationService
        );
        ReflectionTestUtils.setField(controller, "invoiceRepository", invoiceRepository);
        ReflectionTestUtils.setField(controller, "paymentRepository", paymentRepository);
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "staffBranchAssignmentRepository", staffBranchAssignmentRepository);

        ResponseEntity<?> unauthRes = controller.getPaymentAllocations(1L, new MockHttpSession());
        assertEquals(HttpStatus.UNAUTHORIZED, unauthRes.getStatusCode());

        User u = activeUser(55L);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", u);

        Branch invBranch = branch(2L);
        Invoice inv = new Invoice();
        inv.setInvoiceId(88L);
        inv.setBranch(invBranch);
        Payment pay = new Payment();
        pay.setPaymentId(1L);
        pay.setInvoice(inv);
        pay.setBranch(invBranch);

        when(paymentRepository.findById(1L)).thenReturn(Optional.of(pay));
        when(userRepository.hasActiveAdminRole(55L)).thenReturn(false);
        when(staffBranchAssignmentRepository.hasActiveUserAssignment(55L, 2L, AssignmentStatus.ACTIVE)).thenReturn(false);
        when(staffBranchAssignmentRepository.findActiveBranchIdsByUserId(55L, AssignmentStatus.ACTIVE)).thenReturn(List.of(1L));

        ResponseEntity<?> forbiddenRes = controller.getPaymentAllocations(1L, session);
        assertEquals(HttpStatus.FORBIDDEN, forbiddenRes.getStatusCode());
    }

    @Test
    @DisplayName("B08/B09: Hoàn tiền kiểm tra khớp bệnh nhân/chi nhánh với hóa đơn và không trừ trùng phụ thu")
    void b08_b09_refundMismatchRejectedAndSurchargeDeductedOnce() {
        RefundServiceImpl svc = buildRefundService();
        ReflectionTestUtils.setField(svc, "invoiceItemRepository", invoiceItemRepository);
        Branch b1 = branch(1L);
        PatientProfile p1 = patient(1L, BigDecimal.ZERO);
        User staffUser = activeUser(2L);
        StaffProfile staff = activeStaff(20L, staffUser);

        Invoice invoice = new Invoice();
        invoice.setInvoiceId(50L);
        invoice.setInvoiceCode("HD-050");
        invoice.setBranch(b1);
        invoice.setPatientProfile(p1);
        invoice.setStatus(InvoiceStatus.CONFIRMED);
        invoice.setTotalServiceAmount(new BigDecimal("1000000"));
        invoice.setTotalMedicineAmount(BigDecimal.ZERO);
        invoice.setSurchargeAmount(new BigDecimal("200000"));
        invoice.setSurchargePaidAmount(new BigDecimal("200000"));
        invoice.setDiscountAmount(BigDecimal.ZERO);
        invoice.setPromotionDiscount(BigDecimal.ZERO);
        invoice.setPointDiscount(BigDecimal.ZERO);
        invoice.setTotalAmount(new BigDecimal("1200000"));
        invoice.setPaidAmount(new BigDecimal("1200000"));
        invoice.setRemainingDebt(BigDecimal.ZERO);

        InvoiceItem item = new InvoiceItem();
        item.setItemId(501L);
        item.setInvoice(invoice);
        item.setItemType(InvoiceItemType.SERVICE);
        item.setFinalAmount(new BigDecimal("1000000"));
        item.setPayableAmount(new BigDecimal("1000000"));
        item.setPaidAmount(new BigDecimal("1000000"));
        item.setPaymentStatus(InvoiceItemPaymentStatus.PAID);
        invoice.setItems(List.of(item));

        when(invoiceRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(invoice));
        when(invoiceItemRepository.findByInvoice_InvoiceIdOrderByLineOrderAscItemIdAsc(50L)).thenReturn(List.of(item));
        when(userRepository.findById(2L)).thenReturn(Optional.of(staffUser));
        when(userRepository.hasActiveAdminRole(2L)).thenReturn(false);
        when(userRepository.hasActivePermission(2L, "REFUND_MANAGE")).thenReturn(true);
        when(staffProfileRepository.findByUser_UserId(2L)).thenReturn(Optional.of(staff));
        when(staffBranchAssignmentRepository.hasActiveAssignment(20L, 1L, AssignmentStatus.ACTIVE)).thenReturn(true);

        // B08: mismatched profileId rejected
        RefundRequestDTO wrongProfile = new RefundRequestDTO();
        wrongProfile.setInvoiceId(50L);
        wrongProfile.setProfileId(999L);
        wrongProfile.setBranchId(1L);
        wrongProfile.setAmount(new BigDecimal("300000"));
        wrongProfile.setRefundMethod(RefundMethod.CASH);
        wrongProfile.setReason("Sai bệnh nhân");
        assertThrows(IllegalArgumentException.class, () -> svc.processRefund(wrongProfile, 2L));

        // B09: refund 300k with reduceInvoiceTotal=true -> 200k surcharge rollback + 100k item/discount rollback => new totalAmount = 900k (NOT 700k!)
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> {
            Refund r = inv.getArgument(0);
            r.setRefundId(77L);
            return r;
        });

        RefundRequestDTO validRefund = new RefundRequestDTO();
        validRefund.setInvoiceId(50L);
        validRefund.setProfileId(1L);
        validRefund.setBranchId(1L);
        validRefund.setAmount(new BigDecimal("300000"));
        validRefund.setRefundMethod(RefundMethod.CASH);
        validRefund.setReduceInvoiceTotal(true);
        validRefund.setReason("Hoàn phụ thu và 100k dịch vụ");

        svc.processRefund(validRefund, 2L);
        assertEquals(0, BigDecimal.ZERO.compareTo(invoice.getSurchargeAmount()));
        assertEquals(0, new BigDecimal("100000").compareTo(invoice.getDiscountAmount()));
        assertEquals(0, new BigDecimal("900000").compareTo(invoice.getTotalAmount()));
        assertEquals(0, new BigDecimal("900000").compareTo(invoice.getPaidAmount()));
        assertEquals(0, new BigDecimal("900000").compareTo(item.getPayableAmount()));
    }

    @Test
    @DisplayName("B10/B11/B13: Điều chỉnh hóa đơn chặn giảm giá vượt dịch vụ, hủy hóa đơn hoàn usedCount promo voucher, pointValue=0 vô hiệu quy đổi")
    void b10_b11_b13_invoiceInvariantsAndLoyalty() {
        InvoiceServiceImpl svc = buildInvoiceService();
        Branch b1 = branch(1L);
        PatientProfile p1 = patient(1L, BigDecimal.ZERO);

        // B10: discount + promo + point > totalServiceAmount rejected
        Invoice inv = new Invoice();
        inv.setInvoiceId(60L);
        inv.setInvoiceCode("HD-060");
        inv.setBranch(b1);
        inv.setPatientProfile(p1);
        inv.setStatus(InvoiceStatus.CONFIRMED);
        inv.setTotalServiceAmount(new BigDecimal("500000"));
        inv.setTotalMedicineAmount(new BigDecimal("500000"));
        inv.setPromotionDiscount(new BigDecimal("200000"));
        inv.setPointDiscount(new BigDecimal("100000"));
        inv.setDiscountAmount(BigDecimal.ZERO);
        inv.setSurchargeAmount(BigDecimal.ZERO);
        inv.setTotalAmount(new BigDecimal("700000"));
        inv.setPaidAmount(BigDecimal.ZERO);
        inv.setRemainingDebt(new BigDecimal("700000"));

        User adminUser = activeUser(1L);
        StaffProfile adminStaff = activeStaff(10L, adminUser);

        when(invoiceRepository.findByIdForUpdate(60L)).thenReturn(Optional.of(inv));
        when(userRepository.findById(1L)).thenReturn(Optional.of(adminUser));
        when(staffProfileRepository.findByUser_UserId(1L)).thenReturn(Optional.of(adminStaff));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);

        InvoiceAdjustmentRequestDTO adj = new InvoiceAdjustmentRequestDTO();
        adj.setReason("Giảm quá dịch vụ");
        adj.setNewDiscountAmount(new BigDecimal("300000")); // 300k + 200k + 100k = 600k > 500k service total
        assertThrows(IllegalArgumentException.class, () -> svc.adjustInvoice(60L, adj, 1L));

        // B11: cancelling voucher invoice rolls back voucher promotion usedCount
        Promotion vPromo = new Promotion();
        vPromo.setPromotionId(500L);
        vPromo.setPromoCode("KM500");
        vPromo.setUsedCount(3);
        PatientVoucher voucher = new PatientVoucher();
        voucher.setVoucherId(600L);
        voucher.setVoucherCode("VC-600");
        voucher.setPromotion(vPromo);
        voucher.setStatus(VoucherStatus.USED);
        voucher.setExpiresAt(LocalDateTime.now().plusDays(10));

        inv.setVoucher(voucher);
        when(patientVoucherRepository.findByIdForUpdate(600L)).thenReturn(Optional.of(voucher));
        when(promotionRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(vPromo));
        when(invoiceAuditLogRepository.existsByInvoice_InvoiceIdAndActionType(60L, "VOUCHER_APPLIED")).thenReturn(true);
        when(invoiceAuditLogRepository.existsByInvoice_InvoiceIdAndActionType(60L, "PROMOTION_REVERTED")).thenReturn(false);

        InvoiceAdjustmentRequestDTO cancelReq = new InvoiceAdjustmentRequestDTO();
        cancelReq.setReason("Hủy đơn");
        cancelReq.setCancelInvoice(true);
        svc.adjustInvoice(60L, cancelReq, 1L);
        assertEquals(2, vPromo.getUsedCount());
        assertEquals(VoucherStatus.AVAILABLE, voucher.getStatus());

        // B13: MembershipTier with pointValue == 0 disables point discount rather than falling back to 1000
        MembershipTier zeroTier = new MembershipTier();
        zeroTier.setPointValue(BigDecimal.ZERO);
        zeroTier.setDiscountPercent(BigDecimal.ZERO);
        p1.setMembershipTier(zeroTier);
        PatientLoyaltyWallet wallet = new PatientLoyaltyWallet();
        wallet.setCurrentPoints(100);
        when(patientProfileRepository.findById(1L)).thenReturn(Optional.of(p1));
        when(patientLoyaltyWalletRepository.findByPatientProfile_ProfileId(1L)).thenReturn(Optional.of(wallet));

        InvoiceCreateRequestDTO previewReq = new InvoiceCreateRequestDTO();
        previewReq.setProfileId(1L);
        previewReq.setBranchId(1L);
        previewReq.setPointsToRedeem(50);
        InvoiceItemRequestDTO itemReq = new InvoiceItemRequestDTO();
        itemReq.setItemName("Dịch vụ");
        itemReq.setQuantity(BigDecimal.ONE);
        itemReq.setUnitPrice(new BigDecimal("500000"));
        previewReq.setItems(List.of(itemReq));

        InvoiceCalculationPreviewDTO preview = svc.calculatePreview(previewReq);
        assertEquals(0, preview.getPointsRedeemed());
        assertEquals(0, BigDecimal.ZERO.compareTo(preview.getPointDiscountAmount()));
    }

    @Test
    @DisplayName("B15: Danh mục ngưng hoạt động không hiển thị trong danh sách dịch vụ bác sĩ")
    void b15_inactiveCategoryServicesAreFiltered() {
        PerformedServiceServiceImpl svc = new PerformedServiceServiceImpl(
                performedServiceRepository, serviceRepository, staffProfileRepository,
                visitRepository, treatmentPlanItemRepository, treatmentPlanStageRepository,
                treatmentPlanRepository, null, null, null
        );

        ServiceCategory activeCat = new ServiceCategory();
        activeCat.setCategoryId(1L);
        activeCat.setCategoryName("Khám");
        activeCat.setCategoryType(ServiceType.PROCEDURE);
        activeCat.setIsActive(true);

        ServiceCategory inactiveCat = new ServiceCategory();
        inactiveCat.setCategoryId(2L);
        inactiveCat.setCategoryName("Ngưng dùng");
        inactiveCat.setCategoryType(ServiceType.PROCEDURE);
        inactiveCat.setIsActive(false);

        com.cvdcms.entity.Service s1 = new com.cvdcms.entity.Service();
        s1.setServiceId(1L);
        s1.setServiceCode("DV1");
        s1.setServiceName("Lấy cao răng");
        s1.setCategory(activeCat);
        s1.setBasePrice(new BigDecimal("200000"));
        s1.setStatus(ServiceStatus.ACTIVE);

        com.cvdcms.entity.Service s2 = new com.cvdcms.entity.Service();
        s2.setServiceId(2L);
        s2.setServiceCode("DV2");
        s2.setServiceName("Dịch vụ cũ");
        s2.setCategory(inactiveCat);
        s2.setBasePrice(new BigDecimal("500000"));
        s2.setStatus(ServiceStatus.ACTIVE);

        when(serviceRepository.findByStatusOrderByCategory_DisplayOrderAscServiceNameAsc(ServiceStatus.ACTIVE))
                .thenReturn(List.of(s1, s2));

        List<ServiceDTO> result = svc.getAllActiveServices();
        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getServiceId());
    }

    @Test
    @DisplayName("B16: Báo cáo doanh thu theo ngày trừ tiền hoàn để khớp với totalRealRevenue")
    void b16_dailyStatsSubtractRefundsToMatchNetRealRevenue() {
        ReportServiceImpl reportService = new ReportServiceImpl(
                invoiceRepository, paymentRepository, refundRepository,
                operatingExpenseRepository, visitRepository, appointmentRepository
        );
        LocalDate day = LocalDate.of(2026, 5, 10);
        LocalDateTime noon = day.atTime(12, 0);

        when(paymentRepository.sumRealRevenueByBranches(any(), any(), any())).thenReturn(new BigDecimal("1000000"));
        when(refundRepository.sumRefundAmountByBranches(any(), any(), any())).thenReturn(new BigDecimal("300000"));
        when(invoiceRepository.sumInvoiceBreakdownByBranches(any(), any(), any())).thenReturn(List.of());
        when(operatingExpenseRepository.sumActiveExpenses(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(visitRepository.countVisitsByStatusAndBranches(any(), any(), any())).thenReturn(List.of());

        when(invoiceRepository.findInvoiceAmountsForPeriod(any(), any(), any())).thenReturn(List.of());
        when(paymentRepository.findDailyRealPaymentsForPeriod(any(), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{noon, new BigDecimal("1000000")}));
        when(refundRepository.findDailyRefundsForPeriod(any(), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{noon, new BigDecimal("300000")}));
        when(visitRepository.findVisitCheckInDates(any(), any(), any())).thenReturn(List.of());

        ReportDashboardDTO dashboard = reportService.getDashboardReport(
                List.of(1L), day, day, "Chi nhánh 1", BigDecimal.valueOf(20)
        );
        assertEquals(0, new BigDecimal("700000").compareTo(dashboard.getTotalRealRevenue()));
        assertEquals(1, dashboard.getDailyStats().size());
        assertEquals(0, new BigDecimal("700000").compareTo(dashboard.getDailyStats().get(0).getRealRevenue()));
    }

    @Test
    @DisplayName("B19: Lịch sử phân bổ giữ nguyên remainingDebtAfter & statusAfter theo từng mốc thanh toán")
    void b19_paymentAllocationsComputeHistoricalSnapshots() {
        PaymentAllocationServiceImpl svc = new PaymentAllocationServiceImpl(
                paymentAllocationRepository, invoiceItemRepository, invoiceRepository
        );

        Invoice inv = new Invoice();
        inv.setInvoiceId(10L);
        InvoiceItem item = new InvoiceItem();
        item.setItemId(101L);
        item.setPayableAmount(new BigDecimal("1000000"));
        item.setRemainingDebt(BigDecimal.ZERO); // current debt is 0 after second payment

        Payment p1 = new Payment();
        p1.setPaymentId(1L);
        p1.setPaymentCode("PT01");
        p1.setPaymentMethod(PaymentMethod.CASH);
        p1.setPaidAt(LocalDateTime.of(2026, 5, 1, 9, 0));

        Payment p2 = new Payment();
        p2.setPaymentId(2L);
        p2.setPaymentCode("PT02");
        p2.setPaymentMethod(PaymentMethod.CASH);
        p2.setPaidAt(LocalDateTime.of(2026, 5, 1, 10, 0));

        PaymentAllocation a1 = new PaymentAllocation();
        a1.setAllocationId(11L);
        a1.setPayment(p1);
        a1.setInvoice(inv);
        a1.setInvoiceItem(item);
        a1.setItemType("SERVICE");
        a1.setItemTitle("Khám");
        a1.setLineOrder(1);
        a1.setAllocatedAmount(new BigDecimal("400000"));
        a1.setCreatedAt(LocalDateTime.of(2026, 5, 1, 9, 0));

        PaymentAllocation a2 = new PaymentAllocation();
        a2.setAllocationId(12L);
        a2.setPayment(p2);
        a2.setInvoice(inv);
        a2.setInvoiceItem(item);
        a2.setItemType("SERVICE");
        a2.setItemTitle("Khám");
        a2.setLineOrder(1);
        a2.setAllocatedAmount(new BigDecimal("600000"));
        a2.setCreatedAt(LocalDateTime.of(2026, 5, 1, 10, 0));

        when(paymentAllocationRepository.findByPayment_PaymentIdOrderByLineOrderAsc(1L)).thenReturn(List.of(a1));
        when(paymentAllocationRepository.findByInvoice_InvoiceIdOrderByLineOrderAscCreatedAtAsc(10L)).thenReturn(List.of(a1, a2));

        List<PaymentAllocationDTO> dtos = svc.getAllocationsByPaymentId(1L);
        assertEquals(1, dtos.size());
        assertEquals(0, new BigDecimal("600000").compareTo(dtos.get(0).getRemainingDebtAfter()));
        assertEquals("PARTIAL", dtos.get(0).getStatusAfter());
    }
}
