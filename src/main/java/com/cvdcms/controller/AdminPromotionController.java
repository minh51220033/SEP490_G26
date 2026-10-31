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

    private void addLayoutAttributes(HttpSession session, Model model) {
        model.addAttribute("activeMenu", "admin-promotions");
        if (session != null) {
            model.addAttribute("roleNames", session.getAttribute("roleNames"));
            model.addAttribute("displayName", session.getAttribute("displayName"));
            model.addAttribute("loggedInUser", session.getAttribute("loggedInUser"));
        }
    }

    private String checkAdmin(HttpSession session) {
        if (session == null || !(session.getAttribute("loggedInUser") instanceof User user)) {
            return "redirect:/login";
        }
        boolean isAdmin = userRepository.hasActiveAdminRole(user.getUserId());
        if (!isAdmin && !userRepository.hasActivePermission(user.getUserId(), "CATALOG_MANAGE")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền quản lý chương trình khuyến mãi và voucher.");
        }
        return null;
    }

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
