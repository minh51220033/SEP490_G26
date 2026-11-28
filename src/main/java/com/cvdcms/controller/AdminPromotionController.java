package com.cvdcms.controller;

import com.cvdcms.DTO.IssueVoucherDTO;
import com.cvdcms.DTO.PromotionDTO;
import com.cvdcms.entity.*;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.MembershipTierRepository;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.repository.ServiceRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.PromotionService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * [Phân hệ]: Khuyến mãi & Voucher (Quản trị)
 * [Vai trò]: Controller quản trị chương trình khuyến mãi và phát hành voucher cho bệnh nhân tại đường dẫn /admin/promotions.
 */
@Controller
@RequestMapping("/admin/promotions")
public class AdminPromotionController {

    private final PromotionService promotionService;
    private final BranchRepository branchRepository;
    private final MembershipTierRepository membershipTierRepository;
    private final ServiceRepository serviceRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final UserRepository userRepository;

    public AdminPromotionController(
            PromotionService promotionService,
            BranchRepository branchRepository,
            MembershipTierRepository membershipTierRepository,
            ServiceRepository serviceRepository,
            PatientProfileRepository patientProfileRepository,
            UserRepository userRepository
    ) {
        this.promotionService = promotionService;
        this.branchRepository = branchRepository;
        this.membershipTierRepository = membershipTierRepository;
        this.serviceRepository = serviceRepository;
        this.patientProfileRepository = patientProfileRepository;
        this.userRepository = userRepository;
    }

    /**
     * [Hàm hỗ trợ]: Nạp các thuộc tính giao diện chung (menu đang chọn, vai trò, tên hiển thị, tài khoản đăng nhập) vào Model.
     */
    private void addLayoutAttributes(HttpSession session, Model model) {
        model.addAttribute("activeMenu", "admin-promotions");
        if (session != null) {
            model.addAttribute("roleNames", session.getAttribute("roleNames"));
            model.addAttribute("displayName", session.getAttribute("displayName"));
            model.addAttribute("loggedInUser", session.getAttribute("loggedInUser"));
        }
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra phiên đăng nhập và quyền quản trị chương trình khuyến mãi (Admin, CATALOG_PROMOTIONS hoặc CATALOG_MANAGE).
     */
    private String checkAdmin(HttpSession session) {
        if (session == null || !(session.getAttribute("loggedInUser") instanceof User user)) {
            return "redirect:/login";
        }
        boolean isAdmin = userRepository.hasActiveAdminRole(user.getUserId());
        if (!isAdmin
                && !userRepository.hasActivePermission(user.getUserId(), "CATALOG_PROMOTIONS")
                && !userRepository.hasActivePermission(user.getUserId(), "CATALOG_MANAGE")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền quản lý chương trình khuyến mãi và voucher.");
        }
        return null;
    }

    /**
     * [Chức năng]: Xử lý trang GET /admin/promotions hiển thị danh sách chương trình khuyến mãi có lọc theo từ khóa, trạng thái và chi nhánh.
     * [Mục đích]: Kiểm tra quyền quản trị, tải danh sách khuyến mãi qua PromotionService và danh sách chi nhánh để render view admin/promotions/list.
     */
    @GetMapping
    public String listPromotions(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long branchId,
            Model model,
            HttpSession session
    ) {
        String authRedirect = checkAdmin(session);
        if (authRedirect != null) return authRedirect;

        addLayoutAttributes(session, model);

        List<Promotion> promotions = promotionService.getAllPromotions(keyword, status, branchId);
        List<Branch> branches = branchRepository.findAll();

        model.addAttribute("promotions", promotions);
        model.addAttribute("branches", branches);
        model.addAttribute("keyword", keyword);
        model.addAttribute("status", status != null ? status : "ALL");
        model.addAttribute("selectedBranchId", branchId);

        return "admin/promotions/list";
    }

    /**
     * [Chức năng]: Xử lý GET /admin/promotions/create mở biểu mẫu tạo mới chương trình khuyến mãi.
     * [Mục đích]: Khởi tạo PromotionDTO với khoảng thời gian hiệu lực mặc định 1 tháng, nạp danh sách chi nhánh, hạng thành viên đang hoạt động, dịch vụ và trả về view admin/promotions/form.
     */
    @GetMapping("/create")
    public String createForm(Model model, HttpSession session) {
        String authRedirect = checkAdmin(session);
        if (authRedirect != null) return authRedirect;

        addLayoutAttributes(session, model);

        PromotionDTO dto = new PromotionDTO();
        dto.setStartDate(LocalDateTime.now());
        dto.setEndDate(LocalDateTime.now().plusMonths(1));

        model.addAttribute("promotionForm", dto);
        model.addAttribute("branches", branchRepository.findAll());
        model.addAttribute("tiers", membershipTierRepository.findByIsActiveTrueOrderByDisplayOrderAsc());
        model.addAttribute("services", serviceRepository.findAll());
        model.addAttribute("isEdit", false);

        return "admin/promotions/form";
    }

    /**
     * [Chức năng]: Xử lý GET /admin/promotions/{id}/edit mở biểu mẫu chỉnh sửa chương trình khuyến mãi hiện có.
     * [Mục đích]: Tải thực thể Promotion theo ID từ PromotionService, chuyển đổi sang PromotionDTO kèm danh mục tham chiếu (chi nhánh, hạng, dịch vụ) và trả về view admin/promotions/form.
     */
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, HttpSession session) {
        String authRedirect = checkAdmin(session);
        if (authRedirect != null) return authRedirect;

        addLayoutAttributes(session, model);

        Promotion promo = promotionService.getPromotionById(id);

        PromotionDTO dto = new PromotionDTO();
        dto.setPromotionId(promo.getPromotionId());
        dto.setPromoCode(promo.getPromoCode());
        dto.setPromoName(promo.getPromoName());
        dto.setDescription(promo.getDescription());
        dto.setBranchId(promo.getBranch() != null ? promo.getBranch().getBranchId() : null);
        dto.setPromoType(promo.getPromoType());
        dto.setDiscountValue(promo.getDiscountValue());
        dto.setMaxDiscountAmount(promo.getMaxDiscountAmount());
        dto.setMinBillAmount(promo.getMinBillAmount());
        dto.setApplicableTierId(promo.getApplicableTier() != null ? promo.getApplicableTier().getTierId() : null);
        dto.setApplicableServiceId(promo.getApplicableService() != null ? promo.getApplicableService().getServiceId() : null);
        dto.setStartDate(promo.getStartDate());
        dto.setEndDate(promo.getEndDate());
        dto.setUsageLimit(promo.getUsageLimit());
        dto.setUsedCount(promo.getUsedCount());
        dto.setIsActive(promo.getIsActive());

        model.addAttribute("promotionForm", dto);
        model.addAttribute("branches", branchRepository.findAll());
        model.addAttribute("tiers", membershipTierRepository.findByIsActiveTrueOrderByDisplayOrderAsc());
        model.addAttribute("services", serviceRepository.findAll());
        model.addAttribute("isEdit", true);

        return "admin/promotions/form";
    }

    /**
     * [Chức năng]: Xử lý POST /admin/promotions/save để tạo mới hoặc cập nhật chương trình khuyến mãi.
     * [Mục đích]: Kiểm tra quyền quản trị và validation của PromotionDTO; gọi PromotionService để lưu hoặc cập nhật, sau đó chuyển hướng về trang danh sách hoặc quay lại form khi có lỗi.
     */
    @PostMapping("/save")
    public String savePromotion(
            @Valid @ModelAttribute("promotionForm") PromotionDTO dto,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes,
            Model model,
            HttpSession session
    ) {
        String authRedirect = checkAdmin(session);
        if (authRedirect != null) return authRedirect;

        if (bindingResult.hasErrors()) {
            addLayoutAttributes(session, model);
            model.addAttribute("branches", branchRepository.findAll());
            model.addAttribute("tiers", membershipTierRepository.findByIsActiveTrueOrderByDisplayOrderAsc());
            model.addAttribute("services", serviceRepository.findAll());
            model.addAttribute("isEdit", dto.getPromotionId() != null);
            return "admin/promotions/form";
        }

        try {
            if (dto.getPromotionId() != null) {
                promotionService.updatePromotion(dto.getPromotionId(), dto);
                redirectAttributes.addFlashAttribute("successMessage", "Cập nhật chương trình khuyến mãi '" + dto.getPromoName() + "' thành công.");
            } else {
                Promotion created = promotionService.createPromotion(dto);
                redirectAttributes.addFlashAttribute("successMessage", "Tạo mới chương trình khuyến mãi '" + created.getPromoName() + "' thành công.");
            }
            return "redirect:/admin/promotions";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi: " + e.getMessage());
            return "redirect:/admin/promotions" + (dto.getPromotionId() != null ? "/" + dto.getPromotionId() + "/edit" : "/create");
        }
    }

    /**
     * [Chức năng]: Xử lý POST /admin/promotions/{id}/toggle-status để kích hoạt hoặc tạm dừng chương trình khuyến mãi.
     * [Mục đích]: Xác thực quyền quản trị, gọi PromotionService.togglePromotionStatus và chuyển hướng về /admin/promotions kèm flash message kết quả.
     */
    @PostMapping("/{id}/toggle-status")
    public String toggleStatus(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        String authRedirect = checkAdmin(session);
        if (authRedirect != null) return authRedirect;

        try {
            Promotion promo = promotionService.togglePromotionStatus(id);
            String statusText = Boolean.TRUE.equals(promo.getIsActive()) ? "Kích hoạt" : "Tạm dừng";
            redirectAttributes.addFlashAttribute("successMessage", statusText + " chương trình '" + promo.getPromoName() + "' thành công.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi: " + e.getMessage());
        }

        return "redirect:/admin/promotions";
    }

    /**
     * [Chức năng]: Xử lý GET /admin/promotions/{id} hiển thị chi tiết chương trình khuyến mãi, danh sách voucher đã phát hành và biểu mẫu cấp phát voucher.
     * [Mục đích]: Lấy thông tin khuyến mãi, các PatientVoucher thuộc chương trình và danh sách hồ sơ bệnh nhân để render view admin/promotions/detail.
     */
    @GetMapping("/{id}")
    public String viewDetail(
            @PathVariable Long id,
            Model model,
            HttpSession session
    ) {
        String authRedirect = checkAdmin(session);
        if (authRedirect != null) return authRedirect;

        addLayoutAttributes(session, model);

        Promotion promo = promotionService.getPromotionById(id);
        List<PatientVoucher> vouchers = promotionService.getVouchersByPromotion(id);
        List<PatientProfile> patients = patientProfileRepository.findAll();

        IssueVoucherDTO issueDTO = new IssueVoucherDTO();
        issueDTO.setPromotionId(id);
        issueDTO.setExpiresAt(promo.getEndDate());

        model.addAttribute("promo", promo);
        model.addAttribute("vouchers", vouchers);
        model.addAttribute("patients", patients);
        model.addAttribute("issueForm", issueDTO);

        return "admin/promotions/detail";
    }

    /**
     * [Chức năng]: Xử lý POST /admin/promotions/{id}/issue-vouchers để phát hành voucher hàng loạt cho danh sách bệnh nhân đã chọn.
     * [Mục đích]: Kiểm tra quyền quản trị và tính hợp lệ của IssueVoucherDTO, gọi PromotionService.issueVouchers và chuyển hướng về trang chi tiết chương trình kèm số lượng voucher đã phát hành hoặc thông báo lỗi.
     */
    @PostMapping("/{id}/issue-vouchers")
    public String issueVouchers(
            @PathVariable Long id,
            @Valid @ModelAttribute("issueForm") IssueVoucherDTO dto,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        String authRedirect = checkAdmin(session);
        if (authRedirect != null) return authRedirect;

        dto.setPromotionId(id);

        if (bindingResult.hasErrors()) {
            String errorMsg = bindingResult.getFieldErrors().stream()
                    .map(e -> e.getDefaultMessage())
                    .findFirst().orElse("Dữ liệu cấp phát voucher không hợp lệ.");
            redirectAttributes.addFlashAttribute("errorMessage", errorMsg);
            return "redirect:/admin/promotions/" + id;
        }

        try {
            List<PatientVoucher> issued = promotionService.issueVouchers(dto);
            redirectAttributes.addFlashAttribute("successMessage", "Đã phát hành thành công " + issued.size() + " voucher cho bệnh nhân.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi cấp phát: " + e.getMessage());
        }

        return "redirect:/admin/promotions/" + id;
    }
}
