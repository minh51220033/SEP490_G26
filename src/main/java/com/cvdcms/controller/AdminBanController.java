package com.cvdcms.controller;

import com.cvdcms.entity.CommentBan;
import com.cvdcms.entity.User;
import com.cvdcms.repository.CommentBanRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;


// Quản lý admin bans
@Controller
@RequestMapping("/admin/bans")
public class AdminBanController {

    private final CommentBanRepository commentBanRepository;

    public AdminBanController(CommentBanRepository commentBanRepository) {
        this.commentBanRepository = commentBanRepository;
    }

    private boolean isAdmin(HttpSession session) {
        User user = (User) session.getAttribute("loggedInUser");
        return user != null && user.getRole() != null && "ADMIN".equalsIgnoreCase(user.getRole().getRoleName());
    }

    // 1. HIỂN THỊ DANH SÁCH TÀI KHỎAN BỊ BAN
    @GetMapping
    public String listBannedUsers(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) Boolean activeOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            HttpSession session,
            Model model) {

        if (!isAdmin(session)) {
            User user = (User) session.getAttribute("loggedInUser");
            if (user == null) return "redirect:/login";
            return "error/403";
        }

        if (page < 0) page = 0;

        // Sắp xếp theo ngày cấm giảm dần (nếu Entity của bạn đặt tên khác 'bannedAt', hãy đổi lại)
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "bannedAt"));

        // Truyền đủ 3 tham số: keyword, activeOnly, pageable
        Page<CommentBan> banPage = commentBanRepository.searchBans(
                keyword == null ? "" : keyword.trim(),
                activeOnly,
                pageable
        );

        model.addAttribute("banPage", banPage);
        model.addAttribute("keyword", keyword);
        model.addAttribute("activeOnly", activeOnly);

        return "admin/bans/list";
    }

    // 2. CHỨC NĂNG GỠ BAN (UNBAN)
    @PostMapping("/{id}/unban")
    public String unbanUser(
            @PathVariable Long id,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        if (!isAdmin(session)) {
            User user = (User) session.getAttribute("loggedInUser");
            if (user == null) return "redirect:/login";
            return "error/403";
        }

        CommentBan ban = commentBanRepository.findById(id).orElse(null);
        if (ban != null) {
            ban.setActive(false);
            commentBanRepository.save(ban);

            // Dùng RedirectAttributes giúp thông báo tự mất sau 1 lần load
            redirectAttributes.addFlashAttribute("banSuccess", "Đã gỡ cấm bình luận thành công cho tài khoản: " + ban.getUser().getUsername());
        }

        return "redirect:/admin/bans";
    }
}