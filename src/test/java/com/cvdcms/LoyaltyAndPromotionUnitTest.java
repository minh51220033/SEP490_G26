package com.cvdcms;

import com.cvdcms.DTO.IssueVoucherDTO;
import com.cvdcms.DTO.MembershipTierDTO;
import com.cvdcms.DTO.PointAdjustmentDTO;
import com.cvdcms.DTO.PromotionDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.LoyaltyService;
import com.cvdcms.service.MembershipTierService;
import com.cvdcms.service.PromotionService;
import com.cvdcms.service.impl.LoyaltyServiceImpl;
import com.cvdcms.service.impl.MembershipTierServiceImpl;
import com.cvdcms.service.impl.PromotionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class LoyaltyAndPromotionUnitTest {

    @Mock
    private MembershipTierRepository membershipTierRepository;
    @Mock
    private PatientLoyaltyWalletRepository patientLoyaltyWalletRepository;
    @Mock
    private PointTransactionRepository pointTransactionRepository;
    @Mock
    private PatientProfileRepository patientProfileRepository;
    @Mock
    private PromotionRepository promotionRepository;
    @Mock
    private PatientVoucherRepository patientVoucherRepository;
    @Mock
    private BranchRepository branchRepository;
    @Mock
    private ServiceRepository serviceRepository;

    private MembershipTierService membershipTierService;
    private LoyaltyService loyaltyService;
    private PromotionService promotionService;

    private MembershipTier standardTier;
    private MembershipTier silverTier;
    private MembershipTier goldTier;
    private MembershipTier diamondTier;

    private PatientProfile mockPatient;
    private PatientLoyaltyWallet mockWallet;

    @BeforeEach
    void setUp() {
        membershipTierService = new MembershipTierServiceImpl(
                membershipTierRepository, patientLoyaltyWalletRepository, patientProfileRepository
        );
        loyaltyService = new LoyaltyServiceImpl(
                patientLoyaltyWalletRepository, pointTransactionRepository, patientProfileRepository,
                membershipTierRepository, membershipTierService
        );
        promotionService = new PromotionServiceImpl(
                promotionRepository, patientVoucherRepository, patientProfileRepository,
                membershipTierRepository, serviceRepository, branchRepository
        );

        standardTier = new MembershipTier();
        standardTier.setTierId(1L);
        standardTier.setTierCode("STANDARD");
        standardTier.setTierName("Tiêu chuẩn");
        standardTier.setMinTotalSpent(BigDecimal.ZERO);
        standardTier.setDiscountPercent(BigDecimal.ZERO);
        standardTier.setSpendPerPoint(new BigDecimal("10000.00"));
        standardTier.setPointValue(new BigDecimal("1000.00"));
        standardTier.setDisplayOrder(1);
        standardTier.setIsActive(true);

        silverTier = new MembershipTier();
        silverTier.setTierId(2L);
        silverTier.setTierCode("SILVER");
        silverTier.setTierName("Bạc");
        silverTier.setMinTotalSpent(new BigDecimal("5000000.00"));
        silverTier.setDiscountPercent(new BigDecimal("5.00"));
        silverTier.setSpendPerPoint(new BigDecimal("10000.00"));
        silverTier.setPointValue(new BigDecimal("1000.00"));
        silverTier.setDisplayOrder(2);
        silverTier.setIsActive(true);

        goldTier = new MembershipTier();
        goldTier.setTierId(3L);
        goldTier.setTierCode("GOLD");
        goldTier.setTierName("Vàng");
        goldTier.setMinTotalSpent(new BigDecimal("20000000.00"));
        goldTier.setDiscountPercent(new BigDecimal("10.00"));
        goldTier.setSpendPerPoint(new BigDecimal("10000.00"));
        goldTier.setPointValue(new BigDecimal("1000.00"));
        goldTier.setDisplayOrder(3);
        goldTier.setIsActive(true);

        diamondTier = new MembershipTier();
        diamondTier.setTierId(4L);
        diamondTier.setTierCode("DIAMOND");
        diamondTier.setTierName("Kim cương");
        diamondTier.setMinTotalSpent(new BigDecimal("50000000.00"));
        diamondTier.setDiscountPercent(new BigDecimal("15.00"));
        diamondTier.setSpendPerPoint(new BigDecimal("10000.00"));
        diamondTier.setPointValue(new BigDecimal("1000.00"));
        diamondTier.setDisplayOrder(4);
        diamondTier.setIsActive(true);

        mockPatient = new PatientProfile();
        mockPatient.setProfileId(10L);
        mockPatient.setFullName("Nguyễn Văn Bệnh Nhân");
        mockPatient.setMembershipTier(standardTier);

        mockWallet = new PatientLoyaltyWallet();
        mockWallet.setWalletId(100L);
        mockWallet.setPatientProfile(mockPatient);
        mockWallet.setCurrentPoints(50);
        mockWallet.setTotalSpentAmount(new BigDecimal("1000000.00"));
    }

    // ==================== FT19: Membership Tier Tests ====================

    @Test
    @DisplayName("FT19 - Tạo hạng thành viên hợp lệ thành công")
    void testCreateMembershipTier_Success() {
        MembershipTierDTO dto = new MembershipTierDTO();
        dto.setTierCode("PLATINUM");
        dto.setTierName("Bạch Kim");
        dto.setMinTotalSpent(new BigDecimal("35000000.00"));
        dto.setDiscountPercent(new BigDecimal("12.00"));
        dto.setSpendPerPoint(new BigDecimal("10000.00"));
        dto.setPointValue(new BigDecimal("1000.00"));
        dto.setDisplayOrder(5);
        dto.setIsActive(true);

        when(membershipTierRepository.existsByTierCode("PLATINUM")).thenReturn(false);
        when(membershipTierRepository.save(any(MembershipTier.class))).thenAnswer(i -> {
            MembershipTier t = i.getArgument(0);
            t.setTierId(5L);
            return t;
        });

        MembershipTier created = membershipTierService.createTier(dto);

        assertNotNull(created);
        assertEquals("PLATINUM", created.getTierCode());
        assertEquals(new BigDecimal("12.00"), created.getDiscountPercent());
        verify(membershipTierRepository).save(any(MembershipTier.class));
    }

    @Test
    @DisplayName("FT19 - Từ chối tạo hạng với mã trùng lặp")
    void testCreateMembershipTier_DuplicateCode_ThrowsException() {
        MembershipTierDTO dto = new MembershipTierDTO();
        dto.setTierCode("GOLD");
        dto.setTierName("Vàng mới");

        when(membershipTierRepository.existsByTierCode("GOLD")).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> membershipTierService.createTier(dto));
        assertTrue(ex.getMessage().contains("đã tồn tại"));
    }

    @Test
    @DisplayName("FT19 - Xếp hạng chỉ dựa vào tổng chi tiêu lũy kế, chọn hạng cao nhất đủ điều kiện")
    void testFindQualifyingTier_CumulativeSpendingOnly() {
        List<MembershipTier> activeTiers = List.of(standardTier, silverTier, goldTier, diamondTier);
        when(membershipTierRepository.findByIsActiveTrueOrderByMinTotalSpentAsc()).thenReturn(activeTiers);

        // Chi tiêu 25,000,000 VND -> đạt GOLD (ngưỡng 20M, chưa tới DIAMOND 50M)
        MembershipTier qualifying = membershipTierService.findHighestQualifyingTier(new BigDecimal("25000000.00"));
        assertNotNull(qualifying);
        assertEquals("GOLD", qualifying.getTierCode());

        // Chi tiêu 55,000,000 VND -> đạt DIAMOND
        qualifying = membershipTierService.findHighestQualifyingTier(new BigDecimal("55000000.00"));
        assertNotNull(qualifying);
        assertEquals("DIAMOND", qualifying.getTierCode());

        // Chi tiêu 3,000,000 VND -> STANDARD (chưa tới SILVER 5M)
        qualifying = membershipTierService.findHighestQualifyingTier(new BigDecimal("3000000.00"));
        assertNotNull(qualifying);
        assertEquals("STANDARD", qualifying.getTierCode());
    }

    @Test
    @DisplayName("FT19 - Không cho phép vô hiệu hóa hạng cơ sở duy nhất (chi tiêu tối thiểu 0 VND)")
    void testDeactivateBaseTier_ThrowsException() {
        when(membershipTierRepository.findById(1L)).thenReturn(Optional.of(standardTier));
        when(membershipTierRepository.findByIsActiveTrueOrderByMinTotalSpentAsc()).thenReturn(List.of(standardTier));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> membershipTierService.toggleTierStatus(1L));
        assertTrue(ex.getMessage().contains("Không thể vô hiệu hóa hạng"));
    }

    // ==================== FT19: Loyalty Points & Idempotency Tests ====================

    @Test
    @DisplayName("FT19 - Cộng điểm khi thanh toán thành công và chống cộng trùng lặp (Idempotent)")
    void testRecordPaymentPoints_Success_And_Idempotency() {
        mockPatient.setMembershipTier(silverTier);
        Invoice invoice = new Invoice();
        invoice.setInvoiceId(101L);
        invoice.setInvoiceCode("HD-001");
        invoice.setPatientProfile(mockPatient);

        Payment payment = new Payment();
        payment.setPaymentId(501L);
        payment.setInvoice(invoice);
        payment.setPatientProfile(mockPatient);
        payment.setAmount(new BigDecimal("2000000.00")); // 2M / 10,000 = 200 điểm

        when(pointTransactionRepository.existsByPayment_PaymentId(501L)).thenReturn(false);
        when(patientLoyaltyWalletRepository.findByProfileIdForUpdate(10L)).thenReturn(Optional.of(mockWallet));
        when(patientProfileRepository.findById(10L)).thenReturn(Optional.of(mockPatient));
        when(patientLoyaltyWalletRepository.findByPatientProfile_ProfileId(10L)).thenReturn(Optional.of(mockWallet));
        when(membershipTierRepository.findByIsActiveTrueOrderByMinTotalSpentAsc())
                .thenReturn(List.of(standardTier, silverTier, goldTier, diamondTier));

        loyaltyService.recordPaymentPoints(payment);

        // Điểm ban đầu 50 + 200 = 250
        assertEquals(250, mockWallet.getCurrentPoints());
        // Tổng chi tiêu ban đầu 1M + 2M = 3M
        assertEquals(new BigDecimal("3000000.00"), mockWallet.getTotalSpentAmount());
        verify(pointTransactionRepository).save(any(PointTransaction.class));
        verify(patientLoyaltyWalletRepository).save(mockWallet);

        // Gọi lần 2 với cùng payment -> Hệ thống kiểm tra existsByPayment_PaymentId và bỏ qua (idempotency)
        reset(patientLoyaltyWalletRepository, pointTransactionRepository);
        when(pointTransactionRepository.existsByPayment_PaymentId(501L)).thenReturn(true);

        loyaltyService.recordPaymentPoints(payment);

        verify(pointTransactionRepository, never()).save(any(PointTransaction.class));
        verify(patientLoyaltyWalletRepository, never()).save(any(PatientLoyaltyWallet.class));
    }

    @Test
    @DisplayName("FT19 - Khấu trừ điểm và chi tiêu tích lũy khi hoàn tiền")
    void testRecordRefundAdjustment() {
        mockPatient.setMembershipTier(silverTier);
        mockWallet.setCurrentPoints(300);
        mockWallet.setTotalSpentAmount(new BigDecimal("10000000.00"));

        Invoice invoice = new Invoice();
        invoice.setInvoiceId(102L);
        invoice.setInvoiceCode("HD-002");
        invoice.setPatientProfile(mockPatient);

        Refund refund = new Refund();
        refund.setRefundId(601L);
        refund.setInvoice(invoice);
        refund.setPatientProfile(mockPatient);
        refund.setAmount(new BigDecimal("1000000.00")); // 1M / 10,000 = 100 điểm cần trừ

        when(patientLoyaltyWalletRepository.findByProfileIdForUpdate(10L)).thenReturn(Optional.of(mockWallet));
        when(patientProfileRepository.findById(10L)).thenReturn(Optional.of(mockPatient));
        when(patientLoyaltyWalletRepository.findByPatientProfile_ProfileId(10L)).thenReturn(Optional.of(mockWallet));
        when(membershipTierRepository.findByIsActiveTrueOrderByMinTotalSpentAsc())
                .thenReturn(List.of(standardTier, silverTier, goldTier, diamondTier));

        loyaltyService.recordRefundAdjustment(refund);

        // Điểm còn 300 - 100 = 200
        assertEquals(200, mockWallet.getCurrentPoints());
        // Chi tiêu còn 10M - 1M = 9M
        assertEquals(new BigDecimal("9000000.00"), mockWallet.getTotalSpentAmount());
        verify(pointTransactionRepository).save(argThat(pt ->
                pt.getTransactionType() == PointTransactionType.ADJUST && pt.getPoints() == -100
        ));
    }

    @Test
    @DisplayName("FT19 - Nhân viên điều chỉnh điểm thủ công có ghi log giao dịch")
    void testAdjustPoints_Manual() {
        mockWallet.setCurrentPoints(100);

        PointAdjustmentDTO dto = new PointAdjustmentDTO();
        dto.setProfileId(10L);
        dto.setPoints(50);
        dto.setReason("Thưởng sinh nhật khách hàng");

        when(patientProfileRepository.findById(10L)).thenReturn(Optional.of(mockPatient));
        when(patientLoyaltyWalletRepository.findByProfileIdForUpdate(10L)).thenReturn(Optional.of(mockWallet));

        loyaltyService.adjustPoints(dto, 99L);

        assertEquals(150, mockWallet.getCurrentPoints());
        verify(pointTransactionRepository).save(argThat(pt ->
                pt.getTransactionType() == PointTransactionType.ADJUST
                        && pt.getPoints() == 50
                        && pt.getBalanceAfter() == 150
        ));
    }

    // ==================== FT20: Promotion & Voucher Tests ====================

    @Test
    @DisplayName("FT20 - Validate giá trị giảm % không được vượt quá 100%")
    void testCreatePromotion_PercentOver100_ThrowsException() {
        PromotionDTO dto = new PromotionDTO();
        dto.setPromoCode("SUPER105");
        dto.setPromoName("Siêu sale 105%");
        dto.setPromoType(PromotionType.PERCENTAGE);
        dto.setDiscountValue(new BigDecimal("105.00"));
        dto.setStartDate(LocalDateTime.now());
        dto.setEndDate(LocalDateTime.now().plusDays(10));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> promotionService.createPromotion(dto));
        assertTrue(ex.getMessage().contains("Tỷ lệ giảm phần trăm phải nằm trong khoảng"));
    }

    @Test
    @DisplayName("FT20 - Validate ngày kết thúc không được trước ngày bắt đầu")
    void testCreatePromotion_InvalidDates_ThrowsException() {
        PromotionDTO dto = new PromotionDTO();
        dto.setPromoCode("SUMMER2026");
        dto.setPromoName("Khuyến mãi hè");
        dto.setPromoType(PromotionType.PERCENTAGE);
        dto.setDiscountValue(new BigDecimal("10.00"));
        dto.setStartDate(LocalDateTime.of(2026, 7, 1, 0, 0));
        dto.setEndDate(LocalDateTime.of(2026, 6, 1, 0, 0)); // ngày kết thúc trước bắt đầu

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> promotionService.createPromotion(dto));
        assertTrue(ex.getMessage().contains("Thời gian kết thúc phải diễn ra sau"));
    }

    @Test
    @DisplayName("FT20 - Phát hành voucher cho danh sách bệnh nhân thành công")
    void testIssueVouchers_Success() {
        Promotion promo = new Promotion();
        promo.setPromotionId(201L);
        promo.setPromoCode("CHAMSOC2026");
        promo.setPromoName("Chăm sóc răng miệng");
        promo.setIsActive(true);
        promo.setEndDate(LocalDateTime.now().plusMonths(3));

        when(promotionRepository.findById(201L)).thenReturn(Optional.of(promo));
        when(patientProfileRepository.findById(10L)).thenReturn(Optional.of(mockPatient));
        when(patientVoucherRepository.save(any(PatientVoucher.class))).thenAnswer(i -> i.getArgument(0));

        IssueVoucherDTO issueDTO = new IssueVoucherDTO();
        issueDTO.setPromotionId(201L);
        issueDTO.setPatientProfileIds(List.of(10L));
        issueDTO.setExpiresAt(LocalDateTime.now().plusDays(30));

        List<PatientVoucher> vouchers = promotionService.issueVouchers(issueDTO);

        assertNotNull(vouchers);
        assertEquals(1, vouchers.size());
        assertEquals(VoucherStatus.AVAILABLE, vouchers.get(0).getStatus());
        assertEquals(mockPatient, vouchers.get(0).getPatientProfile());
        verify(patientVoucherRepository).save(any(PatientVoucher.class));
    }

    @Test
    @DisplayName("FT20 - Áp dụng điều kiện hạng thành viên trong khuyến mãi qua validatePromotionForInvoice")
    void testValidatePromotion_WithTierCondition() {
        Promotion promo = new Promotion();
        promo.setPromotionId(202L);
        promo.setPromoCode("VIP_GOLD");
        promo.setPromoType(PromotionType.PERCENTAGE);
        promo.setDiscountValue(new BigDecimal("20.00"));
        promo.setApplicableTier(goldTier); // Yêu cầu từ hạng GOLD trở lên (displayOrder 3)
        promo.setIsActive(true);

        Branch branch = new Branch();
        branch.setBranchId(1L);

        // Bệnh nhân hạng SILVER (displayOrder 2 < 3) -> Ném ngoại lệ vì không đủ điều kiện hạng
        mockPatient.setMembershipTier(silverTier);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                promotionService.validatePromotionForInvoice(promo, mockPatient, new BigDecimal("1000000.00"), Collections.emptyList(), branch)
        );
        assertTrue(ex.getMessage().contains("Chương trình chỉ áp dụng cho bệnh nhân từ hạng Vàng"));

        // Bệnh nhân hạng DIAMOND (displayOrder 4 >= 3) -> Thỏa điều kiện, không ném ngoại lệ
        mockPatient.setMembershipTier(diamondTier);
        assertDoesNotThrow(() ->
                promotionService.validatePromotionForInvoice(promo, mockPatient, new BigDecimal("1000000.00"), Collections.emptyList(), branch)
        );

        // Tính tiền giảm: 20% của 1,000,000 = 200,000
        BigDecimal discount = promotionService.calculateDiscount(promo, new BigDecimal("1000000.00"));
        assertEquals(new BigDecimal("200000.00"), discount);
    }

    @Test
    @DisplayName("FT19 - Bảo toàn chi tiêu lẻ tích lũy qua nhiều đợt thanh toán nhỏ (15.000 + 15.000 = 3 điểm)")
    void testRecordPaymentPoints_CumulativeFractionalSpendingPreserved() {
        mockPatient.setMembershipTier(standardTier); // spendPerPoint = 10,000 VND
        mockWallet.setCurrentPoints(0);
        mockWallet.setTotalSpentAmount(BigDecimal.ZERO);

        Invoice invoice = new Invoice();
        invoice.setInvoiceId(103L);
        invoice.setInvoiceCode("HD-003");
        invoice.setPatientProfile(mockPatient);

        // Thanh toán đợt 1: 15.000 VND -> Floor(15.000/10.000) - Floor(0) = 1 điểm. Còn dư 5.000 VND chi tiêu lẻ
        Payment payment1 = new Payment();
        payment1.setPaymentId(701L);
        payment1.setInvoice(invoice);
        payment1.setPatientProfile(mockPatient);
        payment1.setAmount(new BigDecimal("15000.00"));

        when(pointTransactionRepository.existsByPayment_PaymentId(701L)).thenReturn(false);
        when(patientLoyaltyWalletRepository.findByProfileIdForUpdate(10L)).thenReturn(Optional.of(mockWallet));
        when(patientProfileRepository.findById(10L)).thenReturn(Optional.of(mockPatient));
        when(patientLoyaltyWalletRepository.findByPatientProfile_ProfileId(10L)).thenReturn(Optional.of(mockWallet));
        when(membershipTierRepository.findByIsActiveTrueOrderByMinTotalSpentAsc())
                .thenReturn(List.of(standardTier, silverTier, goldTier, diamondTier));

        loyaltyService.recordPaymentPoints(payment1);
        assertEquals(1, mockWallet.getCurrentPoints());
        assertEquals(new BigDecimal("15000.00"), mockWallet.getTotalSpentAmount());

        // Thanh toán đợt 2: 15.000 VND nữa -> Tổng chi tiêu = 30.000 VND.
        // Floor(30.000/10.000) - Floor(15.000/10.000) = 3 - 1 = 2 điểm!
        Payment payment2 = new Payment();
        payment2.setPaymentId(702L);
        payment2.setInvoice(invoice);
        payment2.setPatientProfile(mockPatient);
        payment2.setAmount(new BigDecimal("15000.00"));

        when(pointTransactionRepository.existsByPayment_PaymentId(702L)).thenReturn(false);
        loyaltyService.recordPaymentPoints(payment2);

        // Tổng điểm nhận được qua 2 lần = 1 + 2 = 3 điểm (tương ứng chính xác 30.000 / 10.000, không mất phần lẻ!)
        assertEquals(3, mockWallet.getCurrentPoints());
        assertEquals(new BigDecimal("30000.00"), mockWallet.getTotalSpentAmount());
    }
}
