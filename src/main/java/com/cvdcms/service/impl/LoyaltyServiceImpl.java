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
import java.util.ArrayList;
import java.util.List;

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

    private void calculateProgression(MemberCardDTO card, BigDecimal totalSpent, MembershipTier currentTier) {
        List<MembershipTier> activeTiers = membershipTierRepository.findByIsActiveTrueOrderByMinTotalSpentAsc();
        MembershipTier nextTier = null;
        for (MembershipTier t : activeTiers) {
            if (t.getMinTotalSpent().compareTo(totalSpent) > 0) {
                nextTier = t;
                break;
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

    @Override
    @Transactional(readOnly = true)
    public List<PointTransaction> getPointHistory(Long profileId) {
        return pointTransactionRepository.findByPatientProfile_ProfileIdOrderByCreatedAtDesc(profileId);
    }

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

    @Override
    public PointTransaction recordRefundAdjustment(Refund refund) {
        if (refund == null || refund.getAmount() == null || refund.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        PatientProfile patient = refund.getPatientProfile();
        if (patient == null) {
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

    @Override
    @Transactional(readOnly = true)
    public List<MemberCardDTO> searchMemberCards(String keyword, Long branchId) {
        List<PatientProfile> patients = patientProfileRepository.findAll();
        List<MemberCardDTO> results = new ArrayList<>();

        String kw = keyword != null ? keyword.trim().toLowerCase() : "";
        for (PatientProfile p : patients) {
            if (!"ACTIVE".equalsIgnoreCase(p.getStatus())) {
                continue;
            }
            if (branchId != null && p.getPreferredBranch() != null && !branchId.equals(p.getPreferredBranch().getBranchId())) {
                continue;
            }

            boolean match = kw.isEmpty()
                    || (p.getPatientCode() != null && p.getPatientCode().toLowerCase().contains(kw))
                    || (p.getFullName() != null && p.getFullName().toLowerCase().contains(kw))
                    || (p.getPhoneNumber() != null && p.getPhoneNumber().contains(kw));

            if (match) {
                results.add(getMemberCard(p.getProfileId()));
            }
        }
        return results;
    }
}
