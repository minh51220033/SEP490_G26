package com.cvdcms.service.impl;

import com.cvdcms.DTO.IssueVoucherDTO;
import com.cvdcms.DTO.PromotionDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.PromotionType;
import com.cvdcms.entity.enums.VoucherStatus;
import com.cvdcms.repository.*;
import com.cvdcms.service.PromotionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class PromotionServiceImpl implements PromotionService {

    private final PromotionRepository promotionRepository;
    private final PatientVoucherRepository patientVoucherRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final MembershipTierRepository membershipTierRepository;
    private final ServiceRepository serviceRepository;
    private final BranchRepository branchRepository;

    public PromotionServiceImpl(
            PromotionRepository promotionRepository,
            PatientVoucherRepository patientVoucherRepository,
            PatientProfileRepository patientProfileRepository,
            MembershipTierRepository membershipTierRepository,
            ServiceRepository serviceRepository,
            BranchRepository branchRepository
    ) {
        this.promotionRepository = promotionRepository;
        this.patientVoucherRepository = patientVoucherRepository;
        this.patientProfileRepository = patientProfileRepository;
        this.membershipTierRepository = membershipTierRepository;
        this.serviceRepository = serviceRepository;
        this.branchRepository = branchRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Promotion> getAllPromotions(String keyword, String status, Long branchId) {
        List<Promotion> all = promotionRepository.findAllByOrderByStartDateDesc();
        List<Promotion> filtered = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        String kw = keyword != null ? keyword.trim().toLowerCase() : "";

        for (Promotion p : all) {
            if (!kw.isEmpty()) {
                boolean matchCode = p.getPromoCode() != null && p.getPromoCode().toLowerCase().contains(kw);
                boolean matchName = p.getPromoName() != null && p.getPromoName().toLowerCase().contains(kw);
                if (!matchCode && !matchName) {
                    continue;
                }
            }

            if (branchId != null && p.getBranch() != null && !branchId.equals(p.getBranch().getBranchId())) {
                continue;
            }

            if (status != null && !status.isEmpty() && !"ALL".equalsIgnoreCase(status)) {
                if ("ACTIVE".equalsIgnoreCase(status)) {
                    if (!Boolean.TRUE.equals(p.getIsActive()) || p.getStartDate().isAfter(now) || p.getEndDate().isBefore(now)) {
                        continue;
                    }
                } else if ("EXPIRED".equalsIgnoreCase(status)) {
                    if (!p.getEndDate().isBefore(now)) {
                        continue;
                    }
                } else if ("UPCOMING".equalsIgnoreCase(status)) {
                    if (!p.getStartDate().isAfter(now)) {
                        continue;
                    }
                } else if ("INACTIVE".equalsIgnoreCase(status)) {
                    if (Boolean.TRUE.equals(p.getIsActive())) {
                        continue;
                    }
                }
            }
            filtered.add(p);
        }
        return filtered;
    }

    @Override
    @Transactional(readOnly = true)
    public Promotion getPromotionById(Long promotionId) {
        return promotionRepository.findById(promotionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chương trình khuyến mãi ID: " + promotionId));
    }

    @Override
    @Transactional(readOnly = true)
    public Promotion getPromotionByCode(String promoCode) {
        return promotionRepository.findByPromoCode(promoCode)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chương trình khuyến mãi mã: " + promoCode));
    }

    @Override
    public Promotion createPromotion(PromotionDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("Dữ liệu chương trình khuyến mãi không được để trống.");
        }
        String cleanCode = dto.getPromoCode().trim().toUpperCase();
        if (promotionRepository.existsByPromoCode(cleanCode)) {
            throw new IllegalArgumentException("Mã khuyến mãi '" + cleanCode + "' đã tồn tại.");
        }

        validatePromotionDto(dto);

        Promotion promo = new Promotion();
        promo.setPromoCode(cleanCode);
        promo.setPromoName(dto.getPromoName().trim());
        promo.setDescription(dto.getDescription());
        promo.setPromoType(dto.getPromoType());
        promo.setDiscountValue(dto.getDiscountValue());
        promo.setMaxDiscountAmount(dto.getMaxDiscountAmount());
        promo.setMinBillAmount(dto.getMinBillAmount() != null ? dto.getMinBillAmount() : BigDecimal.ZERO);
        promo.setStartDate(dto.getStartDate());
        promo.setEndDate(dto.getEndDate());
        promo.setUsageLimit(dto.getUsageLimit());
        promo.setUsedCount(0);
        promo.setIsActive(dto.getIsActive() != null ? dto.getIsActive() : true);

        if (dto.getBranchId() != null) {
            Branch branch = branchRepository.findById(dto.getBranchId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh ID: " + dto.getBranchId()));
            promo.setBranch(branch);
        }

        if (dto.getApplicableTierId() != null) {
            MembershipTier tier = membershipTierRepository.findById(dto.getApplicableTierId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hạng thành viên ID: " + dto.getApplicableTierId()));
            promo.setApplicableTier(tier);
        }

        if (dto.getApplicableServiceId() != null) {
            com.cvdcms.entity.Service service = serviceRepository.findById(dto.getApplicableServiceId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dịch vụ ID: " + dto.getApplicableServiceId()));
            promo.setApplicableService(service);
        }

        return promotionRepository.save(promo);
    }

    @Override
    public Promotion updatePromotion(Long promotionId, PromotionDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("Dữ liệu cập nhật khuyến mãi không được rỗng.");
        }
        Promotion existing = getPromotionById(promotionId);
        String cleanCode = dto.getPromoCode().trim().toUpperCase();

        if (promotionRepository.existsByPromoCodeAndPromotionIdNot(cleanCode, promotionId)) {
            throw new IllegalArgumentException("Mã khuyến mãi '" + cleanCode + "' đã được sử dụng bởi chương trình khác.");
        }

        validatePromotionDto(dto);

        if (dto.getUsageLimit() != null && dto.getUsageLimit() < existing.getUsedCount()) {
            throw new IllegalArgumentException("Giới hạn lượt dùng (" + dto.getUsageLimit() + ") không được nhỏ hơn số lượt đã dùng thực tế (" + existing.getUsedCount() + ").");
        }

        existing.setPromoCode(cleanCode);
        existing.setPromoName(dto.getPromoName().trim());
        existing.setDescription(dto.getDescription());
        existing.setPromoType(dto.getPromoType());
        existing.setDiscountValue(dto.getDiscountValue());
        existing.setMaxDiscountAmount(dto.getMaxDiscountAmount());
        existing.setMinBillAmount(dto.getMinBillAmount() != null ? dto.getMinBillAmount() : BigDecimal.ZERO);
        existing.setStartDate(dto.getStartDate());
        existing.setEndDate(dto.getEndDate());
        existing.setUsageLimit(dto.getUsageLimit());
        if (dto.getIsActive() != null) {
            existing.setIsActive(dto.getIsActive());
        }

        if (dto.getBranchId() != null) {
            Branch branch = branchRepository.findById(dto.getBranchId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh ID: " + dto.getBranchId()));
            existing.setBranch(branch);
        } else {
            existing.setBranch(null);
        }

        if (dto.getApplicableTierId() != null) {
            MembershipTier tier = membershipTierRepository.findById(dto.getApplicableTierId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hạng thành viên ID: " + dto.getApplicableTierId()));
            existing.setApplicableTier(tier);
        } else {
            existing.setApplicableTier(null);
        }

        if (dto.getApplicableServiceId() != null) {
            com.cvdcms.entity.Service service = serviceRepository.findById(dto.getApplicableServiceId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dịch vụ ID: " + dto.getApplicableServiceId()));
            existing.setApplicableService(service);
        } else {
            existing.setApplicableService(null);
        }

        return promotionRepository.save(existing);
    }

    @Override
    public Promotion togglePromotionStatus(Long promotionId) {
        Promotion promo = getPromotionById(promotionId);
        promo.setIsActive(!Boolean.TRUE.equals(promo.getIsActive()));
        return promotionRepository.save(promo);
    }

    @Override
    public List<PatientVoucher> issueVouchers(IssueVoucherDTO dto) {
        if (dto == null || dto.getPromotionId() == null || dto.getPatientProfileIds() == null || dto.getPatientProfileIds().isEmpty()) {
            throw new IllegalArgumentException("Dữ liệu cấp phát voucher không hợp lệ.");
        }
        Promotion promo = getPromotionById(dto.getPromotionId());
        if (!Boolean.TRUE.equals(promo.getIsActive())) {
            throw new IllegalStateException("Chương trình khuyến mãi đang bị vô hiệu hóa, không thể cấp phát voucher.");
        }
        if (promo.getEndDate().isBefore(LocalDateTime.now())) {
            throw new IllegalStateException("Chương trình khuyến mãi đã hết hạn.");
        }
        if (dto.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Hạn dùng của voucher phải trong tương lai.");
        }

        List<PatientVoucher> issuedList = new ArrayList<>();
        String prefix = dto.getCustomPrefix() != null && !dto.getCustomPrefix().trim().isEmpty()
                ? dto.getCustomPrefix().trim().toUpperCase()
                : "VC-" + promo.getPromoCode();

        for (Long profileId : dto.getPatientProfileIds()) {
            PatientProfile patient = patientProfileRepository.findById(profileId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bệnh nhân ID: " + profileId));

            // Kiểm tra điều kiện hạng nếu promo có quy định hạng
            if (promo.getApplicableTier() != null) {
                if (patient.getMembershipTier() == null || patient.getMembershipTier().getDisplayOrder() < promo.getApplicableTier().getDisplayOrder()) {
                    throw new IllegalArgumentException("Bệnh nhân " + patient.getFullName() + " (" + patient.getPatientCode() + ") chưa đạt hạng " + promo.getApplicableTier().getTierName() + ".");
                }
            }

            // Tạo mã voucher ngẫu nhiên duy nhất
            String voucherCode = prefix + "-" + patient.getPatientCode() + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
            while (patientVoucherRepository.existsByVoucherCode(voucherCode)) {
                voucherCode = prefix + "-" + patient.getPatientCode() + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
            }

            PatientVoucher voucher = new PatientVoucher();
            voucher.setPromotion(promo);
            voucher.setPatientProfile(patient);
            voucher.setVoucherCode(voucherCode);
            voucher.setStatus(VoucherStatus.AVAILABLE);
            voucher.setExpiresAt(dto.getExpiresAt());

            issuedList.add(patientVoucherRepository.save(voucher));
        }

        return issuedList;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientVoucher> getVouchersByPromotion(Long promotionId) {
        return patientVoucherRepository.findByPromotion_PromotionIdOrderByExpiresAtDesc(promotionId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientVoucher> getAvailableVouchersForPatient(Long profileId) {
        return patientVoucherRepository.findAvailableVouchersForPatient(profileId, VoucherStatus.AVAILABLE, LocalDateTime.now());
    }

    @Override
    public BigDecimal calculateDiscount(Promotion promo, BigDecimal billAmount) {
        if (promo == null || billAmount == null || billAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        if (promo.getMinBillAmount() != null && billAmount.compareTo(promo.getMinBillAmount()) < 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal discount = BigDecimal.ZERO;
        if (promo.getPromoType() == PromotionType.PERCENTAGE) {
            discount = billAmount.multiply(promo.getDiscountValue()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            if (promo.getMaxDiscountAmount() != null && discount.compareTo(promo.getMaxDiscountAmount()) > 0) {
                discount = promo.getMaxDiscountAmount();
            }
        } else {
            discount = promo.getDiscountValue().min(billAmount);
        }
        return discount.max(BigDecimal.ZERO);
    }

    @Override
    @Transactional(readOnly = true)
    public void validatePromotionForInvoice(
            Promotion promo,
            PatientProfile patient,
            BigDecimal billAmount,
            List<Long> serviceIds,
            Branch branch
    ) {
        if (promo == null) {
            throw new IllegalArgumentException("Không tìm thấy chương trình khuyến mãi.");
        }
        if (!Boolean.TRUE.equals(promo.getIsActive())) {
            throw new IllegalStateException("Chương trình khuyến mãi '" + promo.getPromoName() + "' đang tạm ngừng hoạt động.");
        }
        LocalDateTime now = LocalDateTime.now();
        if (promo.getStartDate() != null && now.isBefore(promo.getStartDate())) {
            throw new IllegalStateException("Chương trình khuyến mãi '" + promo.getPromoName() + "' chưa tới ngày áp dụng.");
        }
        if (promo.getEndDate() != null && now.isAfter(promo.getEndDate())) {
            throw new IllegalStateException("Chương trình khuyến mãi '" + promo.getPromoName() + "' đã hết hạn vào ngày " + promo.getEndDate() + ".");
        }
        if (promo.getBranch() != null && branch != null && !promo.getBranch().getBranchId().equals(branch.getBranchId())) {
            throw new IllegalStateException("Chương trình chỉ áp dụng tại chi nhánh: " + promo.getBranch().getBranchName());
        }
        if (promo.getUsageLimit() != null && promo.getUsedCount() != null && promo.getUsedCount() >= promo.getUsageLimit()) {
            throw new IllegalStateException("Chương trình đã đạt giới hạn số lượt sử dụng tối đa (" + promo.getUsageLimit() + " lượt).");
        }
        if (promo.getMinBillAmount() != null && billAmount != null && billAmount.compareTo(promo.getMinBillAmount()) < 0) {
            throw new IllegalStateException("Giá trị hóa đơn chưa đạt mức tối thiểu " + promo.getMinBillAmount() + " đ để áp dụng ưu đãi này.");
        }

        // Kiểm tra điều kiện hạng thành viên FT19
        if (promo.getApplicableTier() != null) {
            if (patient == null || patient.getMembershipTier() == null) {
                throw new IllegalStateException("Chương trình yêu cầu hạng thành viên từ " + promo.getApplicableTier().getTierName() + " trở lên.");
            }
            if (patient.getMembershipTier().getDisplayOrder() < promo.getApplicableTier().getDisplayOrder()) {
                throw new IllegalStateException("Chương trình chỉ áp dụng cho bệnh nhân từ hạng " + promo.getApplicableTier().getTierName() + " trở lên (Hạng hiện tại: " + patient.getMembershipTier().getTierName() + ").");
            }
        }

        // Kiểm tra điều kiện dịch vụ áp dụng
        if (promo.getApplicableService() != null) {
            if (serviceIds == null || !serviceIds.contains(promo.getApplicableService().getServiceId())) {
                throw new IllegalStateException("Chương trình chỉ áp dụng khi hóa đơn có sử dụng dịch vụ: " + promo.getApplicableService().getServiceName());
            }
        }
    }

    private void validatePromotionDto(PromotionDTO dto) {
        if (dto.getStartDate() == null || dto.getEndDate() == null) {
            throw new IllegalArgumentException("Thời gian bắt đầu và kết thúc không được để trống.");
        }
        if (dto.getEndDate().isBefore(dto.getStartDate())) {
            throw new IllegalArgumentException("Thời gian kết thúc phải diễn ra sau thời gian bắt đầu.");
        }
        if (dto.getPromoType() == PromotionType.PERCENTAGE) {
            if (dto.getDiscountValue() == null || dto.getDiscountValue().compareTo(BigDecimal.ZERO) <= 0 || dto.getDiscountValue().compareTo(new BigDecimal("100.00")) > 0) {
                throw new IllegalArgumentException("Tỷ lệ giảm phần trăm phải nằm trong khoảng từ 0.01% đến 100%.");
            }
        } else {
            if (dto.getDiscountValue() == null || dto.getDiscountValue().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Mức giảm tiền cố định phải lớn hơn 0 đ.");
            }
        }
        if (dto.getMinBillAmount() != null && dto.getMinBillAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Giá trị hóa đơn tối thiểu không được âm.");
        }
        if (dto.getUsageLimit() != null && dto.getUsageLimit() < 1) {
            throw new IllegalArgumentException("Giới hạn lượt dùng phải từ 1 trở lên.");
        }
    }
}
