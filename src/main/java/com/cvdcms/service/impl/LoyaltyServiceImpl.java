package com.cvdcms.service.impl;

import com.cvdcms.DTO.MemberCardDTO;
import com.cvdcms.DTO.PointAdjustmentDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.PointTransactionType;
import com.cvdcms.repository.*;
import com.cvdcms.service.LoyaltyService;
import com.cvdcms.service.MembershipTierService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * [Phân hệ]: Thẻ thành viên & Ví điểm tích lũy
 * [Vai trò]: Service triển khai nghiệp vụ quản lý ví điểm tích lũy của bệnh nhân, cộng điểm khi thanh toán, trừ điểm khi hoàn tiền, điều chỉnh điểm thủ công và tra cứu thẻ thành viên.
 */
@Service
@Transactional
public class LoyaltyServiceImpl implements LoyaltyService {

    private final PatientLoyaltyWalletRepository patientLoyaltyWalletRepository;
    private final PointTransactionRepository pointTransactionRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final MembershipTierRepository membershipTierRepository;
    private final MembershipTierService membershipTierService;

    public LoyaltyServiceImpl(
            PatientLoyaltyWalletRepository patientLoyaltyWalletRepository,
            PointTransactionRepository pointTransactionRepository,
            PatientProfileRepository patientProfileRepository,
            MembershipTierRepository membershipTierRepository,
            MembershipTierService membershipTierService
    ) {
        this.patientLoyaltyWalletRepository = patientLoyaltyWalletRepository;
        this.pointTransactionRepository = pointTransactionRepository;
        this.patientProfileRepository = patientProfileRepository;
        this.membershipTierRepository = membershipTierRepository;
        this.membershipTierService = membershipTierService;
    }

    /**
     * [Chức năng]: Lấy ví điểm tích lũy hiện có của bệnh nhân hoặc tự động khởi tạo ví mới với 0 điểm và 0 đ chi tiêu.
     * [Mục đích]: Đảm bảo mọi hồ sơ bệnh nhân hợp lệ đều có bản ghi PatientLoyaltyWallet trước khi thực hiện các giao dịch tích/đổi điểm.
     */
    @Override
    public PatientLoyaltyWallet getOrCreateWallet(Long profileId) {
        return patientLoyaltyWalletRepository.findByPatientProfile_ProfileId(profileId)
                .orElseGet(() -> {
                    PatientProfile patient = patientProfileRepository.findById(profileId)
                            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bệnh nhân ID: " + profileId));
                    PatientLoyaltyWallet wallet = new PatientLoyaltyWallet();
                    wallet.setPatientProfile(patient);
                    wallet.setCurrentPoints(0);
                    wallet.setTotalSpentAmount(BigDecimal.ZERO);
                    return patientLoyaltyWalletRepository.save(wallet);
                });
    }

    /**
     * [Chức năng]: Tổng hợp đầy đủ thông tin thẻ thành viên (MemberCardDTO) của một bệnh nhân theo profileId.
     * [Mục đích]: Kết hợp dữ liệu hồ sơ bệnh nhân, ví điểm, hạng thành viên hiện tại (hoặc hạng đủ điều kiện cao nhất), tiến độ lên hạng tiếp theo và danh sách quyền lợi để hiển thị trên màn hình chi tiết thẻ.
     */
    @Override
    @Transactional(readOnly = true)
    public MemberCardDTO getMemberCard(Long profileId) {
        PatientProfile patient = patientProfileRepository.findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bệnh nhân ID: " + profileId));

        PatientLoyaltyWallet wallet = patientLoyaltyWalletRepository.findByPatientProfile_ProfileId(profileId)
                .orElseGet(() -> {
                    PatientLoyaltyWallet w = new PatientLoyaltyWallet();
                    w.setPatientProfile(patient);
                    w.setCurrentPoints(0);
                    w.setTotalSpentAmount(BigDecimal.ZERO);
                    return w;
                });

        MembershipTier tier = patient.getMembershipTier();
        if (tier == null) {
            tier = membershipTierService.findHighestQualifyingTier(wallet.getTotalSpentAmount());
        }

        MemberCardDTO card = new MemberCardDTO();
        card.setProfileId(patient.getProfileId());
        card.setPatientCode(patient.getPatientCode());
        card.setFullName(patient.getFullName());
        card.setPhoneNumber(patient.getPhoneNumber());
        card.setGender(patient.getGender());
        card.setDob(patient.getDob());
        card.setAddress(patient.getAddress());
        card.setAvatarUrl(patient.getAvatarUrl());
        if (patient.getPreferredBranch() != null) {
            card.setPreferredBranchId(patient.getPreferredBranch().getBranchId());
            card.setPreferredBranchName(patient.getPreferredBranch().getBranchName());
        }

        if (tier != null) {
            card.setTierId(tier.getTierId());
            card.setTierCode(tier.getTierCode());
            card.setTierName(tier.getTierName());
            card.setDiscountPercent(tier.getDiscountPercent() != null ? tier.getDiscountPercent() : BigDecimal.ZERO);
            card.setSpendPerPoint(tier.getSpendPerPoint() != null ? tier.getSpendPerPoint() : new BigDecimal("10000.00"));
            card.setPointValue(tier.getPointValue() != null ? tier.getPointValue() : new BigDecimal("10.00"));
        }

        card.setCurrentPoints(wallet.getCurrentPoints() != null ? wallet.getCurrentPoints() : 0);
        card.setTotalSpentAmount(wallet.getTotalSpentAmount() != null ? wallet.getTotalSpentAmount() : BigDecimal.ZERO);
        card.setLastUpdated(wallet.getLastUpdated());

        // Tiến trình lên hạng tiếp theo
        calculateProgression(card, wallet.getTotalSpentAmount(), tier);

        // Quyền lợi thành viên
        buildBenefitsList(card, tier);

        return card;
    }

    /**
     * [Hàm hỗ trợ]: Tải danh sách hạng đang hoạt động và tính tiến độ lên hạng tiếp theo cho thẻ thành viên.
     */
    private void calculateProgression(MemberCardDTO card, BigDecimal totalSpent, MembershipTier currentTier) {
        List<MembershipTier> activeTiers = membershipTierRepository.findByIsActiveTrueOrderByMinTotalSpentAsc();
        calculateProgression(card, totalSpent, currentTier, activeTiers);
    }

    /**
     * [Hàm hỗ trợ]: Tính tên hạng kế tiếp, số tiền cần chi tiêu thêm và phần trăm hoàn thành tiến độ lên hạng từ danh sách hạng đang hoạt động đã nạp sẵn.
     */
    private void calculateProgression(MemberCardDTO card, BigDecimal totalSpent, MembershipTier currentTier, List<MembershipTier> activeTiers) {
        MembershipTier nextTier = null;
        if (activeTiers != null) {
            for (MembershipTier t : activeTiers) {
                if (t.getMinTotalSpent().compareTo(totalSpent) > 0) {
                    nextTier = t;
                    break;
                }
            }
        }

        if (nextTier != null) {
            card.setNextTierName(nextTier.getTierName());
            card.setNextTierMinSpent(nextTier.getMinTotalSpent());
            BigDecimal needed = nextTier.getMinTotalSpent().subtract(totalSpent);
            card.setAmountNeededForNextTier(needed.compareTo(BigDecimal.ZERO) > 0 ? needed : BigDecimal.ZERO);

            BigDecimal prevMin = (currentTier != null && currentTier.getMinTotalSpent() != null) ? currentTier.getMinTotalSpent() : BigDecimal.ZERO;
            BigDecimal range = nextTier.getMinTotalSpent().subtract(prevMin);
            if (range.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal currentInRange = totalSpent.subtract(prevMin);
                if (currentInRange.compareTo(BigDecimal.ZERO) < 0) currentInRange = BigDecimal.ZERO;
                int percent = currentInRange.multiply(new BigDecimal(100)).divide(range, 0, RoundingMode.HALF_UP).intValue();
                card.setProgressPercent(Math.min(percent, 99));
            } else {
                card.setProgressPercent(0);
            }
        } else {
            card.setNextTierName("Hạng cao nhất");
            card.setNextTierMinSpent(totalSpent);
            card.setAmountNeededForNextTier(BigDecimal.ZERO);
            card.setProgressPercent(100);
        }
    }

    /**
     * [Hàm hỗ trợ]: Xây dựng danh sách mô tả quyền lợi hiển thị trên thẻ thành viên dựa trên chiết khấu, tỷ lệ tích điểm và giá trị quy đổi của hạng hiện tại.
     */
    private void buildBenefitsList(MemberCardDTO card, MembershipTier tier) {
        List<String> benefits = new ArrayList<>();
        DecimalFormat currencyFmt = new DecimalFormat("#,###");

        if (tier != null) {
            if (tier.getDiscountPercent() != null && tier.getDiscountPercent().compareTo(BigDecimal.ZERO) > 0) {
                benefits.add("Chiết khấu " + tier.getDiscountPercent() + "% trên tổng hóa đơn viện phí và thủ thuật");
            } else {
                benefits.add("Tích lũy chi tiêu để nâng hạng và nhận chiết khấu đến 15%");
            }

            BigDecimal spend = tier.getSpendPerPoint() != null ? tier.getSpendPerPoint() : new BigDecimal("10000.00");
            benefits.add("Tích lũy 1 điểm cho mỗi " + currencyFmt.format(spend) + " đ thanh toán thành công");

            BigDecimal val = tier.getPointValue() != null ? tier.getPointValue() : new BigDecimal("10.00");
            benefits.add("Quy đổi 1 điểm = " + currencyFmt.format(val) + " đ giảm trừ trực tiếp khi lập hóa đơn viện phí");
            benefits.add("Ưu tiên áp dụng các chương trình khuyến mãi và voucher dành riêng cho hạng " + tier.getTierName());
        } else {
            benefits.add("Tích lũy điểm khi thanh toán dịch vụ nha khoa");
        }
        card.setBenefits(benefits);
    }

    /**
     * [Chức năng]: Lấy toàn bộ lịch sử biến động điểm tích lũy (PointTransaction) của một bệnh nhân theo thời gian giảm dần.
     * [Mục đích]: Phục vụ hiển thị bảng sao kê lịch sử tích điểm, đổi điểm và điều chỉnh điểm trên trang chi tiết thành viên.
     */
    @Override
    @Transactional(readOnly = true)
    public List<PointTransaction> getPointHistory(Long profileId) {
        return pointTransactionRepository.findByPatientProfile_ProfileIdOrderByCreatedAtDesc(profileId);
    }

    /**
     * [Chức năng]: Ghi nhận chi tiêu và cộng điểm tích lũy cho bệnh nhân từ một giao dịch thanh toán thành công.
     * [Mục đích]: Chống cộng điểm trùng theo paymentId, khóa bản ghi ví điểm (findByProfileIdForUpdate), tính điểm lũy kế theo spendPerPoint của hạng hiện tại, tạo giao dịch PointTransaction loại EARN và gọi MembershipTierService.recalculatePatientTier để tự động xét nâng hạng.
     */
    @Override
    public PointTransaction recordPaymentPoints(Payment payment) {
        if (payment == null || payment.getPaymentId() == null) {
            throw new IllegalArgumentException("Dữ liệu thanh toán không hợp lệ để tích điểm.");
        }

        // Chống cộng điểm lặp cho cùng một Payment ID
        if (pointTransactionRepository.existsByPayment_PaymentId(payment.getPaymentId())) {
            List<PointTransaction> existing = pointTransactionRepository.findByPayment_PaymentId(payment.getPaymentId());
            return existing.isEmpty() ? null : existing.get(0);
        }

        PatientProfile patient = payment.getPatientProfile();
        if (patient == null) {
            throw new IllegalArgumentException("Giao dịch thanh toán thiếu thông tin bệnh nhân.");
        }

        BigDecimal amount = payment.getAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return null; // Không cộng điểm cho giao dịch 0 đ
        }

        // Chống cộng điểm lặp cho cùng một giao dịch thanh toán
        if (payment.getPaymentId() != null && pointTransactionRepository.existsByPayment_PaymentId(payment.getPaymentId())) {
            return null;
        }

        // Lấy hoặc tạo ví với khóa cập nhật
        PatientLoyaltyWallet wallet = patientLoyaltyWalletRepository.findByProfileIdForUpdate(patient.getProfileId())
                .orElseGet(() -> {
                    PatientLoyaltyWallet w = new PatientLoyaltyWallet();
                    w.setPatientProfile(patient);
                    w.setCurrentPoints(0);
                    w.setTotalSpentAmount(BigDecimal.ZERO);
                    return patientLoyaltyWalletRepository.save(w);
                });

        BigDecimal spendPerPoint = new BigDecimal("10000.00");
        if (patient.getMembershipTier() != null && patient.getMembershipTier().getSpendPerPoint() != null) {
            spendPerPoint = patient.getMembershipTier().getSpendPerPoint();
        }

        BigDecimal oldSpent = wallet.getTotalSpentAmount() != null ? wallet.getTotalSpentAmount() : BigDecimal.ZERO;
        BigDecimal newSpent = oldSpent.add(amount);

        // Tính điểm lũy kế: đảm bảo các khoản thanh toán nhỏ không làm mất phần chi tiêu lẻ dồn lại
        int oldPointsEarned = oldSpent.divide(spendPerPoint, 0, RoundingMode.FLOOR).intValue();
        int newPointsEarned = newSpent.divide(spendPerPoint, 0, RoundingMode.FLOOR).intValue();
        int earnedPoints = newPointsEarned - oldPointsEarned;

        wallet.setTotalSpentAmount(newSpent);
        if (earnedPoints > 0) {
            wallet.setCurrentPoints(wallet.getCurrentPoints() + earnedPoints);
        }
        patientLoyaltyWalletRepository.save(wallet);

        PointTransaction pt = null;
        if (earnedPoints > 0) {
            pt = new PointTransaction();
            pt.setPatientProfile(patient);
            pt.setInvoice(payment.getInvoice());
            pt.setPayment(payment);
            pt.setTransactionType(PointTransactionType.EARN);
            pt.setPoints(earnedPoints);
            pt.setBalanceAfter(wallet.getCurrentPoints());
            pt.setReason("Tích " + earnedPoints + " điểm từ thanh toán (" + payment.getPaymentCode() + ")");
            pt = pointTransactionRepository.save(pt);
        }

        // Tự động kiểm tra và nâng hạng thành viên cao nhất đủ điều kiện
        membershipTierService.recalculatePatientTier(patient.getProfileId());

        return pt;
    }

    /**
     * [Chức năng]: Giảm trừ tổng chi tiêu tích lũy và thu hồi điểm tương ứng khi phát sinh phiếu hoàn tiền (Refund).
     * [Mục đích]: Chống trừ điểm trùng theo mã phiếu hoàn tiền, khóa ví điểm (findByProfileIdForUpdate), cập nhật lại số dư điểm không âm, ghi PointTransaction loại ADJUST và gọi MembershipTierService.recalculatePatientTier để đánh giá lại hạng thành viên.
     */
    @Override
    public PointTransaction recordRefundAdjustment(Refund refund) {
        if (refund == null || refund.getAmount() == null || refund.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        PatientProfile patient = refund.getPatientProfile();
        if (patient == null) {
            return null;
        }

        // Chống trừ điểm lặp cho cùng một phiếu hoàn tiền
        if (refund.getRefundCode() != null && pointTransactionRepository.existsByReasonContaining(refund.getRefundCode())) {
            return null;
        }

        PatientLoyaltyWallet wallet = patientLoyaltyWalletRepository.findByProfileIdForUpdate(patient.getProfileId())
                .orElse(null);
        if (wallet == null) {
            return null;
        }

        BigDecimal spendPerPoint = new BigDecimal("10000.00");
        if (patient.getMembershipTier() != null && patient.getMembershipTier().getSpendPerPoint() != null) {
            spendPerPoint = patient.getMembershipTier().getSpendPerPoint();
        }

        BigDecimal oldSpent = wallet.getTotalSpentAmount() != null ? wallet.getTotalSpentAmount() : BigDecimal.ZERO;
        BigDecimal newSpent = oldSpent.subtract(refund.getAmount()).max(BigDecimal.ZERO);

        int oldPointsEarned = oldSpent.divide(spendPerPoint, 0, RoundingMode.FLOOR).intValue();
        int newPointsEarned = newSpent.divide(spendPerPoint, 0, RoundingMode.FLOOR).intValue();
        int pointsToDeduct = oldPointsEarned - newPointsEarned;

        wallet.setTotalSpentAmount(newSpent);

        // Trừ điểm tương ứng
        if (pointsToDeduct > 0) {
            int newPoints = wallet.getCurrentPoints() - pointsToDeduct;
            wallet.setCurrentPoints(Math.max(newPoints, 0));
        }
        patientLoyaltyWalletRepository.save(wallet);

        PointTransaction pt = null;
        if (pointsToDeduct > 0) {
            pt = new PointTransaction();
            pt.setPatientProfile(patient);
            pt.setInvoice(refund.getInvoice());
            pt.setTransactionType(PointTransactionType.ADJUST);
            pt.setPoints(-pointsToDeduct);
            pt.setBalanceAfter(wallet.getCurrentPoints());
            pt.setReason("Điều chỉnh trừ " + pointsToDeduct + " điểm do hoàn tiền phiếu " + refund.getRefundCode());
            pt = pointTransactionRepository.save(pt);
        }

        // Cập nhật lại hạng nếu chi tiêu giảm
        membershipTierService.recalculatePatientTier(patient.getProfileId());

        return pt;
    }

    /**
     * [Chức năng]: Thực hiện cộng hoặc trừ điểm thủ công vào ví điểm bệnh nhân kèm lý do ghi nhận.
     * [Mục đích]: Kiểm tra số điểm khác 0 và có lý do hợp lệ, khóa ví điểm (findByProfileIdForUpdate), đảm bảo số dư sau điều chỉnh không âm rồi cập nhật ví và lưu PointTransaction loại ADJUST.
     */
    @Override
    public PointTransaction adjustPoints(PointAdjustmentDTO dto, Long staffUserId) {
        if (dto == null || dto.getProfileId() == null || dto.getPoints() == null) {
            throw new IllegalArgumentException("Dữ liệu điều chỉnh điểm không hợp lệ.");
        }
        if (dto.getPoints() == 0) {
            throw new IllegalArgumentException("Số điểm điều chỉnh phải khác 0.");
        }
        if (dto.getReason() == null || dto.getReason().trim().isEmpty()) {
            throw new IllegalArgumentException("Cần cung cấp lý do điều chỉnh điểm.");
        }

        PatientProfile patient = patientProfileRepository.findById(dto.getProfileId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bệnh nhân ID: " + dto.getProfileId()));

        PatientLoyaltyWallet wallet = patientLoyaltyWalletRepository.findByProfileIdForUpdate(patient.getProfileId())
                .orElseGet(() -> {
                    PatientLoyaltyWallet w = new PatientLoyaltyWallet();
                    w.setPatientProfile(patient);
                    w.setCurrentPoints(0);
                    w.setTotalSpentAmount(BigDecimal.ZERO);
                    return patientLoyaltyWalletRepository.save(w);
                });

        int newBalance = wallet.getCurrentPoints() + dto.getPoints();
        if (newBalance < 0) {
            throw new IllegalArgumentException("Số điểm sau điều chỉnh không được nhỏ hơn 0. Điểm hiện tại: " + wallet.getCurrentPoints());
        }

        wallet.setCurrentPoints(newBalance);
        patientLoyaltyWalletRepository.save(wallet);

        PointTransaction pt = new PointTransaction();
        pt.setPatientProfile(patient);
        pt.setTransactionType(PointTransactionType.ADJUST);
        pt.setPoints(dto.getPoints());
        pt.setBalanceAfter(newBalance);
        pt.setReason(dto.getReason().trim());

        return pointTransactionRepository.save(pt);
    }

    /**
     * [Chức năng]: Tìm kiếm danh sách thẻ thành viên bệnh nhân theo từ khóa và chi nhánh ưu tiên.
     * [Mục đích]: Truy vấn hàng loạt bệnh nhân và ví điểm để tránh N+1 query, tính toán hạng thành viên, tiến độ lên hạng và danh sách quyền lợi trả về danh sách MemberCardDTO cho màn hình tra cứu lễ tân.
     */
    @Override
    @Transactional(readOnly = true)
    public List<MemberCardDTO> searchMemberCards(String keyword, Long branchId) {
        String cleanKeyword = (keyword != null && !keyword.trim().isEmpty()) ? keyword.trim() : null;
        List<PatientProfile> patients = patientProfileRepository.searchActivePatientsForLoyalty(cleanKeyword, branchId);
        if (patients.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> profileIds = patients.stream().map(PatientProfile::getProfileId).toList();
        List<PatientLoyaltyWallet> walletList = patientLoyaltyWalletRepository.findByPatientProfile_ProfileIdIn(profileIds);
        Map<Long, PatientLoyaltyWallet> walletMap = walletList.stream()
                .filter(w -> w.getPatientProfile() != null && w.getPatientProfile().getProfileId() != null)
                .collect(Collectors.toMap(w -> w.getPatientProfile().getProfileId(), w -> w, (w1, w2) -> w1));

        List<MembershipTier> activeTiers = membershipTierRepository.findByIsActiveTrueOrderByMinTotalSpentAsc();

        List<MemberCardDTO> results = new ArrayList<>();
        for (PatientProfile patient : patients) {
            PatientLoyaltyWallet wallet = walletMap.get(patient.getProfileId());
            BigDecimal totalSpent = (wallet != null && wallet.getTotalSpentAmount() != null) ? wallet.getTotalSpentAmount() : BigDecimal.ZERO;
            Integer currentPoints = (wallet != null && wallet.getCurrentPoints() != null) ? wallet.getCurrentPoints() : 0;
            LocalDateTime lastUpdated = wallet != null ? wallet.getLastUpdated() : null;

            MembershipTier tier = patient.getMembershipTier();
            if (tier == null) {
                tier = membershipTierService.findHighestQualifyingTier(totalSpent);
            }

            MemberCardDTO card = new MemberCardDTO();
            card.setProfileId(patient.getProfileId());
            card.setPatientCode(patient.getPatientCode());
            card.setFullName(patient.getFullName());
            card.setPhoneNumber(patient.getPhoneNumber());
            card.setGender(patient.getGender());
            card.setDob(patient.getDob());
            card.setAddress(patient.getAddress());
            card.setAvatarUrl(patient.getAvatarUrl());
            if (patient.getPreferredBranch() != null) {
                card.setPreferredBranchId(patient.getPreferredBranch().getBranchId());
                card.setPreferredBranchName(patient.getPreferredBranch().getBranchName());
            }

            if (tier != null) {
                card.setTierId(tier.getTierId());
                card.setTierCode(tier.getTierCode());
                card.setTierName(tier.getTierName());
                card.setDiscountPercent(tier.getDiscountPercent() != null ? tier.getDiscountPercent() : BigDecimal.ZERO);
                card.setSpendPerPoint(tier.getSpendPerPoint() != null ? tier.getSpendPerPoint() : new BigDecimal("10000.00"));
                card.setPointValue(tier.getPointValue() != null ? tier.getPointValue() : new BigDecimal("10.00"));
            }

            card.setCurrentPoints(currentPoints);
            card.setTotalSpentAmount(totalSpent);
            card.setLastUpdated(lastUpdated);

            calculateProgression(card, totalSpent, tier, activeTiers);
            buildBenefitsList(card, tier);

            results.add(card);
        }
        return results;
    }
}
