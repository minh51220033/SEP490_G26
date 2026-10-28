package com.cvdcms.controller;

import com.cvdcms.DTO.MembershipTierDTO;
import com.cvdcms.entity.MembershipTier;
import com.cvdcms.entity.User;
import com.cvdcms.service.MembershipTierService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Collection;
import java.util.List;

@Controller
@RequestMapping("/admin/membership-tiers")
public class AdminMembershipTierController {

    private final MembershipTierService membershipTierService;

    public AdminMembershipTierController(MembershipTierService membershipTierService) {
        this.membershipTierService = membershipTierService;
    }

    private void addLayoutAttributes(HttpSession session, Model model) {
        model.addAttribute("activeMenu", "membership-tiers");
        if (session != null) {
            model.addAttribute("roleNames", session.getAttribute("roleNames"));
            model.addAttribute("displayName", session.getAttribute("displayName"));
            model.addAttribute("loggedInUser", session.getAttribute("loggedInUser"));
        }
    }

    private String checkAdmin(HttpSession session) {
        if (session == null || !(session.getAttribute("loggedInUser") instanceof User)) {
            return "redirect:/login";
        }
        Object roleNames = session.getAttribute("roleNames");
        boolean isAdmin = roleNames instanceof Collection<?> c
                && c.stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(String.valueOf(r))
                || "ROLE_ADMIN".equalsIgnoreCase(String.valueOf(r)));
        if (!isAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền quản lý hạng thành viên và quy tắc tích điểm.");
        }
        return null;
    }

    @GetMapping
    public String listTiers(Model model, HttpSession session) {
        String authRedirect = checkAdmin(session);
        if (authRedirect != null) return authRedirect;

        addLayoutAttributes(session, model);

        List<MembershipTier> tiers = membershipTierService.getAllTiers();
        model.addAttribute("tiers", tiers);
        model.addAttribute("newTier", new MembershipTierDTO());

        return "admin/memberships/list";
    }

    @PostMapping("/save")
    public String saveTier(
            @Valid @ModelAttribute("tierForm") MembershipTierDTO dto,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        String authRedirect = checkAdmin(session);
        if (authRedirect != null) return authRedirect;

        if (bindingResult.hasErrors()) {
            String errorMsg = bindingResult.getFieldErrors().stream()
                    .map(e -> e.getDefaultMessage())
                    .findFirst().orElse("Dữ liệu cấu hình hạng thành viên không hợp lệ.");
            redirectAttributes.addFlashAttribute("errorMessage", errorMsg);
            return "redirect:/admin/membership-tiers";
        }

        try {
            if (dto.getTierId() != null) {
                membershipTierService.updateTier(dto.getTierId(), dto);
                redirectAttributes.addFlashAttribute("successMessage", "Cập nhật hạng thành viên '" + dto.getTierName() + "' thành công.");
            } else {
                membershipTierService.createTier(dto);
                redirectAttributes.addFlashAttribute("successMessage", "Tạo mới hạng thành viên '" + dto.getTierName() + "' thành công.");
            }
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi: " + e.getMessage());
        }

        return "redirect:/admin/membership-tiers";
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
            MembershipTier tier = membershipTierService.toggleTierStatus(id);
            String statusText = Boolean.TRUE.equals(tier.getIsActive()) ? "Kích hoạt" : "Vô hiệu hóa";
            redirectAttributes.addFlashAttribute("successMessage", statusText + " hạng '" + tier.getTierName() + "' thành công.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi: " + e.getMessage());
        }

        return "redirect:/admin/membership-tiers";
    }
}
