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

/**
 * [Phân hệ]: Hạng thành viên & Quy tắc tích điểm
 * [Vai trò]: Service triển khai nghiệp vụ quản lý danh mục hạng thẻ thành viên, xác định hạng đủ điều kiện theo chi tiêu và tính lại hạng tự động cho bệnh nhân.
 */
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

    /**
     * [Chức năng]: Lấy toàn bộ danh sách hạng thành viên trong hệ thống sắp xếp theo thứ tự hiển thị tăng dần.
     * [Mục đích]: Phục vụ màn hình quản trị cấu hình hạng thẻ thành viên hiển thị cả hạng đang bật và đang tắt.
     */
    @Override
    @Transactional(readOnly = true)
    public List<MembershipTier> getAllTiers() {
        return membershipTierRepository.findAllByOrderByDisplayOrderAsc();
    }

    /**
     * [Chức năng]: Lấy danh sách các hạng thành viên đang hoạt động theo thứ tự hiển thị tăng dần.
     * [Mục đích]: Cung cấp dữ liệu lựa chọn hạng hợp lệ cho cấu hình khuyến mãi và các màn hình tra cứu.
     */
    @Override
    @Transactional(readOnly = true)
    public List<MembershipTier> getActiveTiers() {
        return membershipTierRepository.findByIsActiveTrueOrderByDisplayOrderAsc();
    }

    /**
     * [Chức năng]: Tìm kiếm hạng thành viên theo ID.
     * [Mục đích]: Trả về thực thể MembershipTier tương ứng hoặc ném IllegalArgumentException nếu không tồn tại.
     */
    @Override
    @Transactional(readOnly = true)
    public MembershipTier getTierById(Long tierId) {
        return membershipTierRepository.findById(tierId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hạng thành viên với ID: " + tierId));
    }

    /**
     * [Chức năng]: Tìm kiếm hạng thành viên theo mã hạng (tierCode).
     * [Mục đích]: Tra cứu thực thể MembershipTier theo mã định danh nghiệp vụ hoặc ném IllegalArgumentException khi không tìm thấy.
     */
    @Override
    @Transactional(readOnly = true)
    public MembershipTier getTierByCode(String tierCode) {
        return membershipTierRepository.findByTierCode(tierCode)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hạng thành viên với mã: " + tierCode));
    }

    /**
     * [Chức năng]: Tạo mới một hạng thành viên và lưu vào bảng hạng thành viên.
     * [Mục đích]: Chuẩn hóa mã hạng viết hoa, kiểm tra trùng mã, xác thực ngưỡng chi tiêu/chiết khấu/quy tắc điểm qua validateTierDto trước khi lưu vào cơ sở dữ liệu.
     */
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

    /**
     * [Chức năng]: Cập nhật thông tin cấu hình của một hạng thành viên theo ID.
     * [Mục đích]: Kiểm tra trùng mã hạng với bản ghi khác, xác thực tham số quy tắc tích điểm và bảo vệ quy tắc hệ thống phải luôn còn ít nhất một hạng cơ sở đang hoạt động có ngưỡng chi tiêu 0 đ.
     */
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

        // Không cho sửa hoặc tắt hạng cơ sở duy nhất làm mất hạng có ngưỡng chi tiêu 0 đ
        boolean isCurrentBaseTier = existing.getMinTotalSpent() != null && BigDecimal.ZERO.compareTo(existing.getMinTotalSpent()) == 0;
        boolean willDeactivate = dto.getIsActive() != null && !dto.getIsActive();
        boolean willChangeSpentFromZero = dto.getMinTotalSpent() != null && dto.getMinTotalSpent().compareTo(BigDecimal.ZERO) > 0;

        if (isCurrentBaseTier && (willDeactivate || willChangeSpentFromZero)) {
            long otherZeroSpentActiveCount = membershipTierRepository.findByIsActiveTrueOrderByMinTotalSpentAsc().stream()
                    .filter(t -> t.getMinTotalSpent() != null && t.getMinTotalSpent().compareTo(BigDecimal.ZERO) == 0 && !t.getTierId().equals(tierId))
                    .count();
            if (otherZeroSpentActiveCount == 0) {
                throw new IllegalStateException("Không thể sửa hoặc vô hiệu hóa hạng thành viên cơ sở (mức chi tiêu 0 đ) làm mất hạng có ngưỡng chi tiêu 0 đ.");
            }
        }

        existing.setTierCode(cleanCode);
        existing.setTierName(dto.getTierName().trim());
        existing.setMinTotalSpent(dto.getMinTotalSpent());
        existing.setDiscountPercent(dto.getDiscountPercent());
        existing.setSpendPerPoint(dto.getSpendPerPoint());
        existing.setPointValue(dto.getPointValue());
        existing.setDisplayOrder(dto.getDisplayOrder());
        if (dto.getIsActive() != null) {
            existing.setIsActive(dto.getIsActive());
        }

        return membershipTierRepository.save(existing);
    }

    /**
     * [Chức năng]: Đảo trạng thái kích hoạt (bật/tắt) của một hạng thành viên theo ID.
     * [Mục đích]: Chặn thao tác vô hiệu hóa nếu đây là hạng cơ sở duy nhất có ngưỡng chi tiêu 0 đ đang hoạt động, sau đó lưu trạng thái mới.
     */
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

    /**
     * [Chức năng]: Xác định hạng thành viên đang hoạt động cao nhất mà tổng mức chi tiêu đạt điều kiện.
     * [Mục đích]: Duyệt các hạng đang hoạt động tăng dần theo ngưỡng chi tiêu tối thiểu để chọn hạng cao nhất có minTotalSpent nhỏ hơn hoặc bằng totalSpent (hoặc hạng thấp nhất nếu chưa đạt ngưỡng nào).
     */
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

    /**
     * [Chức năng]: Tính toán và cập nhật lại hạng thành viên cho một hồ sơ bệnh nhân dựa trên tổng chi tiêu tích lũy trong ví điểm.
     * [Mục đích]: Được gọi sau khi ghi nhận thanh toán hoặc điều chỉnh hoàn tiền; so sánh hạng đủ điều kiện cao nhất với hạng hiện tại và lưu PatientProfile nếu có thay đổi.
     */
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

    /**
     * [Hàm hỗ trợ]: Kiểm tra ràng buộc hợp lệ của các thông số số học trong MembershipTierDTO (ngưỡng chi tiêu, chiết khấu 0-100%, mức chi tiêu đổi 1 điểm và giá trị quy đổi điểm).
     */
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
