package com.cvdcms.service.impl;

import com.cvdcms.DTO.MembershipTierDTO;
import com.cvdcms.entity.MembershipTier;
import com.cvdcms.entity.PatientLoyaltyWallet;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.repository.MembershipTierRepository;
import com.cvdcms.repository.PatientLoyaltyWalletRepository;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.service.MembershipTierService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@Transactional
public class MembershipTierServiceImpl implements MembershipTierService {

    private final MembershipTierRepository membershipTierRepository;
    private final PatientLoyaltyWalletRepository patientLoyaltyWalletRepository;
    private final PatientProfileRepository patientProfileRepository;

    public MembershipTierServiceImpl(
            MembershipTierRepository membershipTierRepository,
            PatientLoyaltyWalletRepository patientLoyaltyWalletRepository,
            PatientProfileRepository patientProfileRepository
    ) {
        this.membershipTierRepository = membershipTierRepository;
        this.patientLoyaltyWalletRepository = patientLoyaltyWalletRepository;
        this.patientProfileRepository = patientProfileRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MembershipTier> getAllTiers() {
        return membershipTierRepository.findAllByOrderByDisplayOrderAsc();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MembershipTier> getActiveTiers() {
        return membershipTierRepository.findByIsActiveTrueOrderByDisplayOrderAsc();
    }

    @Override
    @Transactional(readOnly = true)
    public MembershipTier getTierById(Long tierId) {
        return membershipTierRepository.findById(tierId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hạng thành viên với ID: " + tierId));
    }

    @Override
    @Transactional(readOnly = true)
    public MembershipTier getTierByCode(String tierCode) {
        return membershipTierRepository.findByTierCode(tierCode)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hạng thành viên với mã: " + tierCode));
    }

    @Override
    public MembershipTier createTier(MembershipTierDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("Dữ liệu hạng thành viên không được rỗng.");
        }
        String cleanCode = dto.getTierCode().trim().toUpperCase();
        if (membershipTierRepository.existsByTierCode(cleanCode)) {
            throw new IllegalArgumentException("Mã hạng thành viên '" + cleanCode + "' đã tồn tại trong hệ thống.");
        }

        validateTierDto(dto);

        MembershipTier tier = new MembershipTier();
        tier.setTierCode(cleanCode);
        tier.setTierName(dto.getTierName().trim());
        tier.setMinTotalSpent(dto.getMinTotalSpent());
        tier.setDiscountPercent(dto.getDiscountPercent());
        tier.setSpendPerPoint(dto.getSpendPerPoint());
        tier.setPointValue(dto.getPointValue());
        tier.setDisplayOrder(dto.getDisplayOrder());
        tier.setIsActive(dto.getIsActive() != null ? dto.getIsActive() : true);

        return membershipTierRepository.save(tier);
    }

    @Override
    public MembershipTier updateTier(Long tierId, MembershipTierDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("Dữ liệu cập nhật không được rỗng.");
        }
        MembershipTier existing = getTierById(tierId);
        String cleanCode = dto.getTierCode().trim().toUpperCase();

        if (membershipTierRepository.existsByTierCodeAndTierIdNot(cleanCode, tierId)) {
            throw new IllegalArgumentException("Mã hạng thành viên '" + cleanCode + "' đã được sử dụng bởi hạng khác.");
        }

        validateTierDto(dto);

        existing.setTierCode(cleanCode);
        existing.setTierName(dto.getTierName().trim());
        existing.setMinTotalSpent(dto.getMinTotalSpent());
        existing.setDiscountPercent(dto.getDiscountPercent());
        existing.setSpendPerPoint(dto.getSpendPerPoint());
        existing.setPointValue(dto.getPointValue());
        existing.setDisplayOrder(dto.getDisplayOrder());
        if (dto.getIsActive() != null) {
            // Không cho vô hiệu hóa hạng mặc định có minTotalSpent = 0 nếu là hạng cơ sở duy nhất
            if (!dto.getIsActive() && BigDecimal.ZERO.compareTo(existing.getMinTotalSpent()) == 0) {
                long zeroSpentActiveCount = membershipTierRepository.findByIsActiveTrueOrderByMinTotalSpentAsc().stream()
                        .filter(t -> t.getMinTotalSpent().compareTo(BigDecimal.ZERO) == 0 && !t.getTierId().equals(tierId))
                        .count();
                if (zeroSpentActiveCount == 0) {
                    throw new IllegalStateException("Không thể vô hiệu hóa hạng thành viên cơ sở (mức chi tiêu 0 đ) khi không có hạng thay thế.");
                }
            }
            existing.setIsActive(dto.getIsActive());
        }

        return membershipTierRepository.save(existing);
    }

    @Override
    public MembershipTier toggleTierStatus(Long tierId) {
        MembershipTier tier = getTierById(tierId);
        boolean newStatus = !Boolean.TRUE.equals(tier.getIsActive());
        if (!newStatus && BigDecimal.ZERO.compareTo(tier.getMinTotalSpent()) == 0) {
            long zeroSpentActiveCount = membershipTierRepository.findByIsActiveTrueOrderByMinTotalSpentAsc().stream()
                    .filter(t -> t.getMinTotalSpent().compareTo(BigDecimal.ZERO) == 0 && !t.getTierId().equals(tierId))
                    .count();
            if (zeroSpentActiveCount == 0) {
                throw new IllegalStateException("Không thể vô hiệu hóa hạng thành viên cơ sở duy nhất.");
            }
        }
        tier.setIsActive(newStatus);
        return membershipTierRepository.save(tier);
    }

    @Override
    @Transactional(readOnly = true)
    public MembershipTier findHighestQualifyingTier(BigDecimal totalSpent) {
        if (totalSpent == null || totalSpent.compareTo(BigDecimal.ZERO) < 0) {
            totalSpent = BigDecimal.ZERO;
        }
        List<MembershipTier> activeTiers = membershipTierRepository.findByIsActiveTrueOrderByMinTotalSpentAsc();
        MembershipTier qualifying = null;
        for (MembershipTier tier : activeTiers) {
            if (totalSpent.compareTo(tier.getMinTotalSpent()) >= 0) {
                qualifying = tier;
            }
        }
        if (qualifying == null && !activeTiers.isEmpty()) {
            qualifying = activeTiers.get(0);
        }
        return qualifying;
    }

    @Override
    public void recalculatePatientTier(Long profileId) {
        PatientProfile patient = patientProfileRepository.findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hồ sơ bệnh nhân ID: " + profileId));
        BigDecimal totalSpent = patientLoyaltyWalletRepository.findByPatientProfile_ProfileId(profileId)
                .map(PatientLoyaltyWallet::getTotalSpentAmount)
                .orElse(BigDecimal.ZERO);

        MembershipTier qualifyingTier = findHighestQualifyingTier(totalSpent);
        if (qualifyingTier != null) {
            if (patient.getMembershipTier() == null || !qualifyingTier.getTierId().equals(patient.getMembershipTier().getTierId())) {
                patient.setMembershipTier(qualifyingTier);
                patientProfileRepository.save(patient);
            }
        }
    }

    private void validateTierDto(MembershipTierDTO dto) {
        if (dto.getMinTotalSpent() == null || dto.getMinTotalSpent().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Ngưỡng chi tiêu tối thiểu phải >= 0 đ.");
        }
        if (dto.getDiscountPercent() == null || dto.getDiscountPercent().compareTo(BigDecimal.ZERO) < 0 || dto.getDiscountPercent().compareTo(new BigDecimal("100.00")) > 0) {
            throw new IllegalArgumentException("Tỷ lệ chiết khấu phải từ 0% đến 100%.");
        }
        if (dto.getSpendPerPoint() == null || dto.getSpendPerPoint().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Mức chi tiêu đổi 1 điểm phải lớn hơn 0 đ.");
        }
        if (dto.getPointValue() == null || dto.getPointValue().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Giá trị quy đổi 1 điểm phải >= 0 đ.");
        }
    }
}
