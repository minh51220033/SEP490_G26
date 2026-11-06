package com.cvdcms.service.impl;

import com.cvdcms.DTO.PublicDoctorCardDTO;
import com.cvdcms.DTO.ServicePromotionViewDTO;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.NewsArticle;
import com.cvdcms.entity.Promotion;
import com.cvdcms.entity.Role;
import com.cvdcms.entity.Service;
import com.cvdcms.entity.StaffBranchAssignment;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.StaffSchedule;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.entity.enums.PromotionType;
import com.cvdcms.entity.enums.ScheduleStatus;
import com.cvdcms.entity.enums.StaffGender;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.NewsArticleRepository;
import com.cvdcms.repository.PromotionRepository;
import com.cvdcms.repository.StaffBranchAssignmentRepository;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.StaffScheduleRepository;
import com.cvdcms.service.CustomerPortalService;
import com.cvdcms.service.ServiceCatalogService;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@org.springframework.stereotype.Service
@Transactional(readOnly = true)
public class CustomerPortalServiceImpl implements CustomerPortalService {

    private final BranchRepository branchRepository;
    private final ServiceCatalogService serviceCatalogService;
    private final PromotionRepository promotionRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final StaffBranchAssignmentRepository assignmentRepository;
    private final StaffScheduleRepository scheduleRepository;
    private final NewsArticleRepository newsArticleRepository;

    public CustomerPortalServiceImpl(
            BranchRepository branchRepository,
            ServiceCatalogService serviceCatalogService,
            PromotionRepository promotionRepository,
            StaffProfileRepository staffProfileRepository,
            StaffBranchAssignmentRepository assignmentRepository,
            StaffScheduleRepository scheduleRepository,
            NewsArticleRepository newsArticleRepository
    ) {
        this.branchRepository = branchRepository;
        this.serviceCatalogService = serviceCatalogService;
        this.promotionRepository = promotionRepository;
        this.staffProfileRepository = staffProfileRepository;
        this.assignmentRepository = assignmentRepository;
        this.scheduleRepository = scheduleRepository;
        this.newsArticleRepository = newsArticleRepository;
    }

    @Override
    public List<Branch> getActiveBranches() {
        return branchRepository.findByIsActiveTrueOrderByBranchNameAsc();
    }

    @Override
    public Branch getPrimaryBranch() {
        List<Branch> branches = getActiveBranches();
        return branches.isEmpty() ? null : branches.get(0);
    }

    @Override
    public List<Service> getFeaturedServices(int limit) {
        List<Service> all = new ArrayList<>(serviceCatalogService.getPublicServices());
        // Ưu tiên hiển thị dịch vụ điều trị lâm sàng & khám tư vấn trước dịch vụ cận lâm sàng (X-quang/Labo)
        all.sort(Comparator.comparingInt(s -> {
            if (Boolean.TRUE.equals(s.getIsParaclinical())) {
                return 2;
            }
            if (s.getServiceType() != null
                    && ("XRAY".equals(s.getServiceType().name()) || "LAB".equals(s.getServiceType().name()))) {
                return 1;
            }
            return 0;
        }));
        if (all.size() <= limit) {
            return all;
        }
        return all.subList(0, Math.max(0, limit));
    }

    @Override
    public Map<Long, ServicePromotionViewDTO> getActiveServicePromotionsMap() {
        return getActiveServicePromotionsMap(null);
    }

    @Override
    public Map<Long, ServicePromotionViewDTO> getActiveServicePromotionsMap(Long branchId) {
        List<Promotion> activePromos = promotionRepository.findActiveServicePromotions(LocalDateTime.now());
        Map<Long, ServicePromotionViewDTO> bestByServiceId = new HashMap<>();

        for (Promotion promo : activePromos) {
            if (branchId != null && promo.getBranch() != null
                    && !Objects.equals(promo.getBranch().getBranchId(), branchId)) {
                continue;
            }
            Service svc = promo.getApplicableService();
            if (svc == null || svc.getServiceId() == null) {
                continue;
            }
            // Chỉ áp dụng hiển thị công khai cho khuyến mãi không yêu cầu hạng thẻ riêng biệt
            if (promo.getApplicableTier() != null) {
                continue;
            }
            BigDecimal base = svc.getBasePrice() != null ? svc.getBasePrice() : BigDecimal.ZERO;
            BigDecimal surcharge = svc.getSurchargeAmount() != null ? svc.getSurchargeAmount() : BigDecimal.ZERO;
            BigDecimal originalPrice = base.add(surcharge);
            if (originalPrice.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if (promo.getMinBillAmount() != null && originalPrice.compareTo(promo.getMinBillAmount()) < 0) {
                continue;
            }

            BigDecimal discount = calculateDiscount(originalPrice, promo);
            if (discount.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            BigDecimal discountedPrice = originalPrice.subtract(discount).max(BigDecimal.ZERO);
            BigDecimal savedAmount = originalPrice.subtract(discountedPrice);
            if (savedAmount.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            ServicePromotionViewDTO existing = bestByServiceId.get(svc.getServiceId());
            if (existing == null || savedAmount.compareTo(existing.getSavedAmount()) > 0) {
                ServicePromotionViewDTO dto = new ServicePromotionViewDTO();
                dto.setServiceId(svc.getServiceId());
                dto.setPromotionId(promo.getPromotionId());
                dto.setPromoCode(promo.getPromoCode());
                dto.setPromoName(promo.getPromoName());
                dto.setDescription(promo.getDescription());
                dto.setBranchName(promo.getBranch() != null ? promo.getBranch().getBranchName() : "Tất cả cơ sở");
                dto.setOriginalPrice(originalPrice);
                dto.setDiscountedPrice(discountedPrice);
                dto.setSavedAmount(savedAmount);
                dto.setDiscountBadge("-" + promo.getFormattedDiscount());
                dto.setEndDate(promo.getEndDate());
                bestByServiceId.put(svc.getServiceId(), dto);
            }
        }
        return bestByServiceId;
    }

    @Override
    public ServicePromotionViewDTO getServicePromotion(Service service) {
        if (service == null || service.getServiceId() == null) {
            return null;
        }
        return getActiveServicePromotionsMap().get(service.getServiceId());
    }

    @Override
    public List<Service> getRelatedPublicServices(Long excludeServiceId, Long categoryId, int limit) {
        List<Service> sameCategory = serviceCatalogService.getPublicServices(categoryId, null);
        List<Service> related = new ArrayList<>();
        for (Service item : sameCategory) {
            if (!Objects.equals(item.getServiceId(), excludeServiceId)) {
                related.add(item);
                if (related.size() >= limit) {
                    return related;
                }
            }
        }
        if (related.size() < limit) {
            for (Service item : serviceCatalogService.getPublicServices()) {
                if (!Objects.equals(item.getServiceId(), excludeServiceId)
                        && related.stream().noneMatch(r -> Objects.equals(r.getServiceId(), item.getServiceId()))) {
                    related.add(item);
                    if (related.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return related;
    }

    @Override
    public List<PublicDoctorCardDTO> getPublicDoctors(String keyword, String specialty, Long branchId) {
        List<StaffProfile> activeStaff = staffProfileRepository.findByStatus(StaffStatus.ACTIVE);
        List<StaffBranchAssignment> activeAssignments = assignmentRepository.findAllByStatus(AssignmentStatus.ACTIVE);

        LocalDate today = LocalDate.now();
        Map<Long, List<StaffBranchAssignment>> assignmentsByStaff = new HashMap<>();
        for (StaffBranchAssignment a : activeAssignments) {
            if (a.getStaff() == null || a.getStaff().getStaffId() == null) {
                continue;
            }
            if (a.getStartDate() != null && a.getStartDate().isAfter(today)) {
                continue;
            }
            if (a.getEndDate() != null && a.getEndDate().isBefore(today)) {
                continue;
            }
            if (a.getBranch() != null && !Boolean.TRUE.equals(a.getBranch().getIsActive())) {
                continue;
            }
            assignmentsByStaff
                    .computeIfAbsent(a.getStaff().getStaffId(), k -> new ArrayList<>())
                    .add(a);
        }

        String normKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        String normSpecialty = specialty == null ? "" : specialty.trim();

        List<PublicDoctorCardDTO> doctors = new ArrayList<>();
        for (StaffProfile sp : activeStaff) {
            List<StaffBranchAssignment> staffAssigns = assignmentsByStaff.getOrDefault(sp.getStaffId(), List.of());
            if (!isPublicActiveDoctor(sp, staffAssigns)) {
                continue;
            }

            PublicDoctorCardDTO dto = toDoctorCardDTO(sp, staffAssigns);

            if (branchId != null) {
                boolean worksAtBranch = dto.getActiveBranches().stream()
                        .anyMatch(b -> Objects.equals(b.getBranchId(), branchId));
                if (!worksAtBranch) {
                    continue;
                }
            }

            if (!normSpecialty.isEmpty()) {
                if (dto.getSpecialty() == null || !dto.getSpecialty().trim().equalsIgnoreCase(normSpecialty)) {
                    continue;
                }
            }

            if (!normKeyword.isEmpty()) {
                String fullName = dto.getFullName() != null ? dto.getFullName().toLowerCase(Locale.ROOT) : "";
                String spec = dto.getSpecialty() != null ? dto.getSpecialty().toLowerCase(Locale.ROOT) : "";
                String pos = dto.getPositionTitle() != null ? dto.getPositionTitle().toLowerCase(Locale.ROOT) : "";
                String code = dto.getStaffCode() != null ? dto.getStaffCode().toLowerCase(Locale.ROOT) : "";
                if (!fullName.contains(normKeyword)
                        && !spec.contains(normKeyword)
                        && !pos.contains(normKeyword)
                        && !code.contains(normKeyword)) {
                    continue;
                }
            }

            doctors.add(dto);
        }

        doctors.sort(Comparator.comparing(
                d -> d.getFullName() != null ? d.getFullName() : "",
                String.CASE_INSENSITIVE_ORDER
        ));
        return doctors;
    }

    @Override
    public List<PublicDoctorCardDTO> getFeaturedDoctors(int limit) {
        return getFeaturedDoctors(limit, null);
    }

    @Override
    public List<PublicDoctorCardDTO> getFeaturedDoctors(int limit, Long branchId) {
        List<PublicDoctorCardDTO> all = getPublicDoctors(null, null, branchId);
        if (all.size() <= limit) {
            return all;
        }
        return all.subList(0, Math.max(0, limit));
    }

    @Override
    public List<String> getPublicDoctorSpecialties() {
        List<PublicDoctorCardDTO> allDoctors = getPublicDoctors(null, null, null);
        Set<String> specialties = new LinkedHashSet<>();
        for (PublicDoctorCardDTO doc : allDoctors) {
            if (doc.getSpecialty() != null && !doc.getSpecialty().isBlank()) {
                specialties.add(doc.getSpecialty().trim());
            }
        }
        return new ArrayList<>(specialties);
    }

    @Override
    public PublicDoctorCardDTO getPublicDoctorById(Long staffId) {
        if (staffId == null || staffId <= 0) {
            throw new IllegalArgumentException("Mã bác sĩ không hợp lệ.");
        }
        StaffProfile sp = staffProfileRepository.findById(staffId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin bác sĩ."));

        LocalDate today = LocalDate.now();
        List<StaffBranchAssignment> allAssigns = assignmentRepository.findByStaffId(staffId);
        List<StaffBranchAssignment> activeAssigns = allAssigns.stream()
                .filter(a -> a.getStatus() == AssignmentStatus.ACTIVE)
                .filter(a -> a.getStartDate() == null || !a.getStartDate().isAfter(today))
                .filter(a -> a.getEndDate() == null || !a.getEndDate().isBefore(today))
                .filter(a -> a.getBranch() != null && Boolean.TRUE.equals(a.getBranch().getIsActive()))
                .collect(Collectors.toList());

        if (!isPublicActiveDoctor(sp, activeAssigns)) {
            throw new IllegalArgumentException("Hồ sơ bác sĩ không tồn tại hoặc hiện không hoạt động công khai.");
        }

        PublicDoctorCardDTO dto = toDoctorCardDTO(sp, activeAssigns);

        List<StaffSchedule> schedules = scheduleRepository
                .findByStaffAndRange(staffId, today, today.plusDays(6))
                .stream()
                .filter(s -> s.getStatus() == ScheduleStatus.SCHEDULED)
                .filter(s -> s.getBranch() != null && Boolean.TRUE.equals(s.getBranch().getIsActive()))
                .collect(Collectors.toList());

        dto.setUpcomingSchedules(schedules);
        return dto;
    }

    @Override
    public List<NewsArticle> getLatestPublishedNews(int limit) {
        return getLatestPublishedNews(limit, null);
    }

    @Override
    public List<NewsArticle> getLatestPublishedNews(int limit, Long branchId) {
        if (limit <= 0) {
            return List.of();
        }
        return newsArticleRepository
                .searchPublishedPublic("", branchId, PageRequest.of(0, limit))
                .getContent();
    }

    @Override
    public String plainTextSummary(String html, int maxLength) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = org.jsoup.Jsoup.parse(html).text().trim();
        if (maxLength > 0 && text.length() > maxLength) {
            return text.substring(0, maxLength).trim() + "...";
        }
        return text;
    }

    private boolean isPublicActiveDoctor(StaffProfile sp, List<StaffBranchAssignment> activeAssigns) {
        if (sp == null || sp.getStatus() != StaffStatus.ACTIVE) {
            return false;
        }
        User user = sp.getUser();
        if (user != null) {
            if (!Boolean.TRUE.equals(user.getIsActive())) {
                return false;
            }
            if (user.getStatus() != null && !"ACTIVE".equalsIgnoreCase(user.getStatus())) {
                return false;
            }
        }

        boolean hasDoctorAssignment = activeAssigns != null && activeAssigns.stream()
                .anyMatch(a -> a.getRole() != null && isDoctorRoleCode(a.getRole().getRoleName()));

        boolean hasDoctorUserRole = user != null && user.getRoles() != null && user.getRoles().stream()
                .filter(r -> Boolean.TRUE.equals(r.getIsActive()))
                .anyMatch(r -> isDoctorRoleCode(r.getRoleName()));

        return hasDoctorAssignment || hasDoctorUserRole;
    }

    private boolean isDoctorRoleCode(String roleName) {
        if (roleName == null) {
            return false;
        }
        String norm = roleName.trim().toUpperCase(Locale.ROOT);
        return "BAC_SI".equals(norm) || "ROLE_BAC_SI".equals(norm);
    }

    private PublicDoctorCardDTO toDoctorCardDTO(StaffProfile sp, List<StaffBranchAssignment> activeAssigns) {
        PublicDoctorCardDTO dto = new PublicDoctorCardDTO();
        dto.setStaffId(sp.getStaffId());
        dto.setStaffCode(sp.getStaffCode());
        dto.setFullName(sp.getFullName());
        dto.setInitials(extractInitials(sp.getFullName()));
        dto.setGenderLabel(formatGender(sp.getGender()));
        dto.setPositionTitle(
                sp.getPositionTitle() != null && !sp.getPositionTitle().isBlank()
                        ? sp.getPositionTitle().trim()
                        : "Bác sĩ Răng Hàm Mặt"
        );
        dto.setSpecialty(
                sp.getSpecialty() != null && !sp.getSpecialty().isBlank()
                        ? sp.getSpecialty().trim()
                        : "Nha khoa tổng quát"
        );
        dto.setDepartment(sp.getDepartment());
        dto.setPracticeLicenseNo(sp.getPracticeLicenseNo());
        dto.setHireDate(sp.getHireDate());

        Map<Long, Branch> branchMap = new LinkedHashMap<>();
        Branch primaryBranch = null;
        if (activeAssigns != null) {
            List<StaffBranchAssignment> sorted = new ArrayList<>(activeAssigns);
            sorted.sort(Comparator.comparing((StaffBranchAssignment a) -> !Boolean.TRUE.equals(a.getIsPrimary())));
            for (StaffBranchAssignment a : sorted) {
                Branch b = a.getBranch();
                if (b != null && b.getBranchId() != null) {
                    branchMap.putIfAbsent(b.getBranchId(), b);
                    if (primaryBranch == null) {
                        primaryBranch = b;
                    }
                }
            }
        }

        List<Branch> activeBranches = new ArrayList<>(branchMap.values());
        dto.setActiveBranches(activeBranches);
        dto.setBranchNames(activeBranches.stream().map(Branch::getBranchName).collect(Collectors.toList()));
        if (primaryBranch != null) {
            dto.setPrimaryBranchId(primaryBranch.getBranchId());
            dto.setPrimaryBranchName(primaryBranch.getBranchName());
        }
        return dto;
    }

    private String extractInitials(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "BS";
        }
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, 1).toUpperCase(Locale.ROOT);
        }
        String first = parts[0].substring(0, 1);
        String last = parts[parts.length - 1].substring(0, 1);
        return (first + last).toUpperCase(Locale.ROOT);
    }

    private String formatGender(StaffGender gender) {
        if (gender == null) {
            return null;
        }
        return switch (gender) {
            case MALE -> "Nam";
            case FEMALE -> "Nữ";
            case OTHER -> "Khác";
        };
    }

    private BigDecimal calculateDiscount(BigDecimal originalPrice, Promotion promo) {
        if (promo.getDiscountValue() == null || promo.getDiscountValue().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal discount;
        if (promo.getPromoType() == PromotionType.PERCENTAGE) {
            discount = originalPrice
                    .multiply(promo.getDiscountValue())
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
        } else {
            discount = promo.getDiscountValue();
        }
        if (promo.getMaxDiscountAmount() != null
                && promo.getMaxDiscountAmount().compareTo(BigDecimal.ZERO) > 0
                && discount.compareTo(promo.getMaxDiscountAmount()) > 0) {
            discount = promo.getMaxDiscountAmount();
        }
        if (discount.compareTo(originalPrice) > 0) {
            discount = originalPrice;
        }
        return discount;
    }
}
