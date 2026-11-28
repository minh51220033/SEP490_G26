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

/**
 * [Phân hệ]: Khuyến mãi & Voucher
 * [Vai trò]: Service triển khai nghiệp vụ quản trị chương trình khuyến mãi, phát hành voucher cho bệnh nhân, tính toán mức chiết khấu và kiểm tra điều kiện áp dụng khuyến mãi trên hóa đơn.
 */
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

    /**
     * [Chức năng]: Lấy danh sách chương trình khuyến mãi có lọc theo từ khóa (mã/tên), trạng thái hiệu lực và chi nhánh áp dụng.
     * [Mục đích]: Phục vụ màn hình quản trị danh sách khuyến mãi với các trạng thái ACTIVE, EXPIRED, UPCOMING, INACTIVE hoặc ALL.
     */
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

    /**
     * [Chức năng]: Tìm kiếm chương trình khuyến mãi theo ID.
     * [Mục đích]: Trả về thực thể Promotion tương ứng hoặc ném IllegalArgumentException nếu không tồn tại.
     */
    @Override
    @Transactional(readOnly = true)
    public Promotion getPromotionById(Long promotionId) {
        return promotionRepository.findById(promotionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chương trình khuyến mãi ID: " + promotionId));
    }

    /**
     * [Chức năng]: Tìm kiếm chương trình khuyến mãi theo mã khuyến mãi (promoCode).
     * [Mục đích]: Hỗ trợ tra cứu và áp dụng chương trình khuyến mãi theo mã định danh, ném IllegalArgumentException nếu không tìm thấy.
     */
    @Override
    @Transactional(readOnly = true)
    public Promotion getPromotionByCode(String promoCode) {
        return promotionRepository.findByPromoCode(promoCode)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chương trình khuyến mãi mã: " + promoCode));
    }

    /**
     * [Chức năng]: Tạo mới một chương trình khuyến mãi và lưu vào cơ sở dữ liệu.
     * [Mục đích]: Xác thực dữ liệu qua validatePromotionDto, chuẩn hóa mã viết hoa và kiểm tra trùng mã, gắn liên kết chi nhánh, hạng thành viên và dịch vụ áp dụng (nếu có) với usedCount khởi tạo bằng 0.
     */
    @Override
    public Promotion createPromotion(PromotionDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("Dữ liệu chương trình khuyến mãi không được để trống.");
        }
        validatePromotionDto(dto);

        String cleanCode = dto.getPromoCode().trim().toUpperCase();
        if (promotionRepository.existsByPromoCode(cleanCode)) {
            throw new IllegalArgumentException("Mã khuyến mãi '" + cleanCode + "' đã tồn tại.");
        }

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

    /**
     * [Chức năng]: Cập nhật thông tin cấu hình của một chương trình khuyến mãi theo ID.
     * [Mục đích]: Kiểm tra hợp lệ DTO, chống trùng mã với chương trình khác, đảm bảo giới hạn lượt dùng mới không nhỏ hơn số lượt đã dùng thực tế, đồng thời cập nhật lại liên kết chi nhánh/hạng/dịch vụ.
     */
    @Override
    public Promotion updatePromotion(Long promotionId, PromotionDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("Dữ liệu cập nhật khuyến mãi không được rỗng.");
        }
        validatePromotionDto(dto);

        Promotion existing = getPromotionById(promotionId);
        String cleanCode = dto.getPromoCode().trim().toUpperCase();

        if (promotionRepository.existsByPromoCodeAndPromotionIdNot(cleanCode, promotionId)) {
            throw new IllegalArgumentException("Mã khuyến mãi '" + cleanCode + "' đã được sử dụng bởi chương trình khác.");
        }

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

    /**
     * [Chức năng]: Đảo trạng thái kích hoạt (đang chạy/tạm dừng) của một chương trình khuyến mãi.
     * [Mục đích]: Cho phép quản trị viên bật hoặc tắt nhanh chương trình khuyến mãi mà không xóa dữ liệu lịch sử.
     */
    @Override
    public Promotion togglePromotionStatus(Long promotionId) {
        Promotion promo = getPromotionById(promotionId);
        promo.setIsActive(!Boolean.TRUE.equals(promo.getIsActive()));
        return promotionRepository.save(promo);
    }

    /**
     * [Chức năng]: Phát hành voucher cá nhân (PatientVoucher) hàng loạt cho danh sách bệnh nhân từ một chương trình khuyến mãi.
     * [Mục đích]: Kiểm tra chương trình đang hoạt động và chưa hết hạn, hạn dùng voucher ở tương lai, kiểm tra điều kiện hạng thành viên (nếu chương trình yêu cầu), sinh mã voucher duy nhất và lưu ở trạng thái AVAILABLE.
     */
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

            // Kiểm tra điều kiện hạng nếu promo có quy định hạng (so sánh theo minTotalSpent và hạng phải đang hoạt động)
            if (promo.getApplicableTier() != null) {
                if (patient.getMembershipTier() == null || !Boolean.TRUE.equals(patient.getMembershipTier().getIsActive())) {
                    throw new IllegalArgumentException("Bệnh nhân " + patient.getFullName() + " (" + patient.getPatientCode() + ") chưa có hạng thành viên hoạt động đạt yêu cầu.");
                }
                BigDecimal patientMinSpent = patient.getMembershipTier().getMinTotalSpent();
                BigDecimal promoMinSpent = promo.getApplicableTier().getMinTotalSpent() != null
                        ? promo.getApplicableTier().getMinTotalSpent()
                        : BigDecimal.ZERO;

                if (patientMinSpent == null || patientMinSpent.compareTo(promoMinSpent) < 0) {
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

    /**
     * [Chức năng]: Lấy danh sách tất cả voucher đã phát hành thuộc một chương trình khuyến mãi sắp xếp theo ngày hết hạn giảm dần.
     * [Mục đích]: Phục vụ hiển thị danh sách theo dõi trạng thái voucher trên trang chi tiết chương trình khuyến mãi.
     */
    @Override
    @Transactional(readOnly = true)
    public List<PatientVoucher> getVouchersByPromotion(Long promotionId) {
        return patientVoucherRepository.findByPromotion_PromotionIdOrderByExpiresAtDesc(promotionId);
    }

    /**
     * [Chức năng]: Lấy danh sách các voucher khả dụng (trạng thái AVAILABLE và còn hạn) của một bệnh nhân.
     * [Mục đích]: Cung cấp danh sách voucher hợp lệ để bệnh nhân hoặc thu ngân chọn áp dụng khi lập hóa đơn thanh toán.
     */
    @Override
    @Transactional(readOnly = true)
    public List<PatientVoucher> getAvailableVouchersForPatient(Long profileId) {
        return patientVoucherRepository.findAvailableVouchersForPatient(profileId, VoucherStatus.AVAILABLE, LocalDateTime.now());
    }

    /**
     * [Chức năng]: Tính số tiền giảm giá dự kiến của một chương trình khuyến mãi áp dụng trên giá trị hóa đơn.
     * [Mục đích]: Trả về 0 nếu chưa đạt giá trị hóa đơn tối thiểu (minBillAmount), ngược lại ủy quyền sang computeBoundedDiscount để tính mức giảm bị chặn trần.
     */
    @Override
    public BigDecimal calculateDiscount(Promotion promo, BigDecimal billAmount) {
        if (promo == null || billAmount == null || billAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        if (promo.getMinBillAmount() != null && billAmount.compareTo(promo.getMinBillAmount()) < 0) {
            return BigDecimal.ZERO;
        }
        return computeBoundedDiscount(promo, billAmount);
    }

    /**
     * [Chức năng]: Tính mức giảm giá khuyến mãi (theo phần trăm có trần maxDiscountAmount hoặc giảm tiền cố định) được giới hạn an toàn trong khoảng [0, eligibleAmount].
     * [Mục đích]: Dùng chung cho cả màn hình xem trước giá ưu đãi và luồng lập hóa đơn để đảm bảo số tiền giảm không âm và không vượt quá tổng tiền đủ điều kiện.
     */
    public static BigDecimal computeBoundedDiscount(Promotion promo, BigDecimal eligibleAmount) {
        if (promo == null || eligibleAmount == null || eligibleAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        if (promo.getDiscountValue() == null || promo.getDiscountValue().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal discount;
        if (promo.getPromoType() == PromotionType.PERCENTAGE) {
            discount = eligibleAmount.multiply(promo.getDiscountValue()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            if (promo.getMaxDiscountAmount() != null && discount.compareTo(promo.getMaxDiscountAmount()) > 0) {
                discount = promo.getMaxDiscountAmount();
            }
        } else {
            discount = promo.getDiscountValue();
        }
        return discount.max(BigDecimal.ZERO).min(eligibleAmount);
    }

    /**
     * [Chức năng]: Kiểm tra tính hợp lệ của cấu hình giảm giá trên thực thể Promotion trước khi áp dụng vào hóa đơn.
     * [Mục đích]: Chặn các cấu hình khuyến mãi sai lệch (thiếu loại chiết khấu, giá trị giảm <= 0, phần trăm > 100%, mức giảm tối đa hoặc hóa đơn tối thiểu bị âm kể cả dữ liệu legacy).
     */
    public static void validatePromotionDiscountConfig(Promotion promo) {
        if (promo == null) {
            throw new IllegalArgumentException("Không tìm thấy chương trình khuyến mãi.");
        }
        if (promo.getPromoType() == null) {
            throw new IllegalStateException("Chương trình khuyến mãi '" + promo.getPromoName() + "' thiếu loại chiết khấu.");
        }
        if (promo.getDiscountValue() == null || promo.getDiscountValue().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Chương trình khuyến mãi '" + promo.getPromoName() + "' có giá trị giảm không hợp lệ.");
        }
        if (promo.getPromoType() == PromotionType.PERCENTAGE && promo.getDiscountValue().compareTo(new BigDecimal("100.00")) > 0) {
            throw new IllegalStateException("Chương trình khuyến mãi '" + promo.getPromoName() + "' có tỷ lệ giảm vượt quá 100%.");
        }
        if (promo.getMaxDiscountAmount() != null && promo.getMaxDiscountAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalStateException("Chương trình khuyến mãi '" + promo.getPromoName() + "' có mức giảm tối đa âm không hợp lệ.");
        }
        if (promo.getMinBillAmount() != null && promo.getMinBillAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalStateException("Chương trình khuyến mãi '" + promo.getPromoName() + "' có giá trị hóa đơn tối thiểu âm không hợp lệ.");
        }
    }

    /**
     * [Chức năng]: Xác thực toàn bộ điều kiện áp dụng của một chương trình khuyến mãi cho hóa đơn cụ thể.
     * [Mục đích]: Kiểm tra cấu hình giảm giá, trạng thái kích hoạt, khoảng thời gian hiệu lực, phạm vi chi nhánh, giới hạn lượt dùng, giá trị hóa đơn tối thiểu, điều kiện hạng thành viên hoạt động (FT19) và dịch vụ bắt buộc phải có trong hóa đơn.
     */
    @Override
    @Transactional(readOnly = true)
    public void validatePromotionForInvoice(
            Promotion promo,
            PatientProfile patient,
            BigDecimal billAmount,
            List<Long> serviceIds,
            Branch branch
    ) {
        validatePromotionDiscountConfig(promo);
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

        // Kiểm tra điều kiện hạng thành viên FT19 (so sánh theo minTotalSpent và hạng phải đang hoạt động)
        if (promo.getApplicableTier() != null) {
            if (patient == null || patient.getMembershipTier() == null || !Boolean.TRUE.equals(patient.getMembershipTier().getIsActive())) {
                String currentTierName = (patient != null && patient.getMembershipTier() != null) ? patient.getMembershipTier().getTierName() : "Chưa có hạng";
                throw new IllegalStateException("Chương trình yêu cầu hạng thành viên hoạt động từ " + promo.getApplicableTier().getTierName() + " trở lên (Hạng hiện tại: " + currentTierName + ").");
            }
            BigDecimal patientMinSpent = patient.getMembershipTier().getMinTotalSpent() != null
                    ? patient.getMembershipTier().getMinTotalSpent()
                    : BigDecimal.ZERO;
            BigDecimal promoMinSpent = promo.getApplicableTier().getMinTotalSpent() != null
                    ? promo.getApplicableTier().getMinTotalSpent()
                    : BigDecimal.ZERO;
            if (patientMinSpent.compareTo(promoMinSpent) < 0) {
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

    /**
     * [Hàm hỗ trợ]: Kiểm tra ràng buộc dữ liệu đầu vào của PromotionDTO (mã, tên, loại giảm giá, khoảng ngày hiệu lực, mức giảm, mức trần, hóa đơn tối thiểu và giới hạn lượt dùng).
     */
    private void validatePromotionDto(PromotionDTO dto) {
        if (dto.getPromoCode() == null || dto.getPromoCode().trim().isEmpty()) {
            throw new IllegalArgumentException("Mã khuyến mãi không được để trống.");
        }
        if (dto.getPromoName() == null || dto.getPromoName().trim().isEmpty()) {
            throw new IllegalArgumentException("Tên chương trình khuyến mãi không được để trống.");
        }
        if (dto.getPromoType() == null) {
            throw new IllegalArgumentException("Loại chiết khấu không được để trống.");
        }
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
        if (dto.getMaxDiscountAmount() != null && dto.getMaxDiscountAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Mức giảm tối đa không được âm.");
        }
        if (dto.getMinBillAmount() != null && dto.getMinBillAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Giá trị hóa đơn tối thiểu không được âm.");
        }
        if (dto.getUsageLimit() != null && dto.getUsageLimit() < 1) {
            throw new IllegalArgumentException("Giới hạn lượt dùng phải từ 1 trở lên.");
        }
    }
}
