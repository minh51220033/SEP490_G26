package com.cvdcms.controller;

import com.cvdcms.DTO.MembershipTierDTO;
import com.cvdcms.entity.MembershipTier;
import com.cvdcms.entity.User;
import com.cvdcms.service.MembershipTierService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Collection;
import java.util.List;

/**
 * [Phân hệ]: Hạng thành viên & Quy tắc tích điểm (Quản trị)
 * [Vai trò]: Controller quản trị cấu hình các hạng thẻ thành viên, ngưỡng chi tiêu, chiết khấu và tỷ lệ quy đổi điểm tại đường dẫn /admin/membership-tiers.
 */
@Controller
@RequestMapping("/admin/membership-tiers")
public class AdminMembershipTierController {

    private final MembershipTierService membershipTierService;
    private final com.cvdcms.repository.UserRepository userRepository;

    public AdminMembershipTierController(MembershipTierService membershipTierService) {
        this(membershipTierService, null);
    }

    @Autowired
    public AdminMembershipTierController(MembershipTierService membershipTierService, com.cvdcms.repository.UserRepository userRepository) {
        this.membershipTierService = membershipTierService;
        this.userRepository = userRepository;
    }

    /**
     * [Hàm hỗ trợ]: Nạp các thuộc tính giao diện chung (menu đang chọn, tên hiển thị, vai trò, người dùng hiện tại) vào Model.
     */
    private void addLayoutAttributes(HttpSession session, Model model) {
        model.addAttribute("activeMenu", "membership-tiers");
        if (session != null) {
            model.addAttribute("roleNames", session.getAttribute("roleNames"));
            model.addAttribute("displayName", session.getAttribute("displayName"));
            model.addAttribute("loggedInUser", session.getAttribute("loggedInUser"));
        }
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra trạng thái đăng nhập và quyền quản trị hạng thành viên (Admin, CATALOG_MEMBERSHIP_TIERS hoặc CATALOG_MANAGE).
     */
    private String checkAdmin(HttpSession session) {
        if (userRepository == null || session == null) {
            return null;
        }
        User loggedInUser = (User) session.getAttribute("loggedInUser");
        if (loggedInUser == null) {
            return "redirect:/login";
        }
        boolean isAdmin = userRepository.hasActiveAdminRole(loggedInUser.getUserId());
        if (!isAdmin
                && !userRepository.hasActivePermission(loggedInUser.getUserId(), "CATALOG_MEMBERSHIP_TIERS")
                && !userRepository.hasActivePermission(loggedInUser.getUserId(), "CATALOG_MANAGE")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền quản lý hạng thành viên và quy tắc tích điểm.");
        }
        return null;
    }

    /**
     * [Chức năng]: Xử lý trang GET /admin/membership-tiers hiển thị danh sách toàn bộ hạng thành viên và biểu mẫu tạo mới.
     * [Mục đích]: Xác thực quyền quản trị, lấy danh sách hạng từ MembershipTierService và trả về view admin/memberships/list.
     */
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

    /**
     * [Chức năng]: Xử lý POST /admin/membership-tiers/save để tạo mới hoặc cập nhật thông tin một hạng thành viên.
     * [Mục đích]: Xác thực quyền quản trị và kiểm tra hợp lệ dữ liệu DTO; gọi MembershipTierService để cập nhật (nếu có tierId) hoặc tạo mới, sau đó chuyển hướng kèm thông báo kết quả.
     */
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
            redirectAttributes.addFlashAttribute("formTier", dto);
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
            redirectAttributes.addFlashAttribute("formTier", dto);
        }

        return "redirect:/admin/membership-tiers";
    }

    /**
     * [Chức năng]: Xử lý POST /admin/membership-tiers/{id}/toggle-status để kích hoạt hoặc vô hiệu hóa hạng thành viên.
     * [Mục đích]: Kiểm tra quyền quản trị, gọi MembershipTierService.toggleTierStatus và chuyển hướng về trang danh sách kèm flash message kết quả hoặc lỗi nghiệp vụ.
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
            MembershipTier tier = membershipTierService.toggleTierStatus(id);
            String statusText = Boolean.TRUE.equals(tier.getIsActive()) ? "Kích hoạt" : "Vô hiệu hóa";
            redirectAttributes.addFlashAttribute("successMessage", statusText + " hạng '" + tier.getTierName() + "' thành công.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi: " + e.getMessage());
        }

        return "redirect:/admin/membership-tiers";
    }
}
