package com.cvdcms.controller;

import com.cvdcms.entity.CommentBan;
import com.cvdcms.entity.User;
import com.cvdcms.repository.CommentBanRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.SystemAuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.Map;

// Quản lý danh sách tài khoản bị cấm & Gỡ cấm (FT39 - SCR-39.3 & SCR-39.4)
@Controller
@RequestMapping("/admin/bans")
public class AdminBanController {

    private final CommentBanRepository commentBanRepository;
    private final UserRepository userRepository;
    private final SystemAuditLogService auditLogService;

    public AdminBanController(
            CommentBanRepository commentBanRepository,
            UserRepository userRepository,
            SystemAuditLogService auditLogService) {
        this.commentBanRepository = commentBanRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    private User getLoggedInUser(HttpSession session) {
        return (User) session.getAttribute("loggedInUser");
    }

    private boolean hasRole(User user, String roleName) {
        return user != null
                && user.getRoles() != null
                && user.getRoles().stream()
                .filter(role -> role != null)
                .anyMatch(role -> roleName.equals(role.getRoleName()));
    }

    private boolean isAdmin(HttpSession session) {
        return hasRole(getLoggedInUser(session), "ADMIN");
    }

    // =====================================================
    // 1. HIỂN THỊ DANH SÁCH TÀI KHOẢN BỊ CẤM (SCR-39.3)
    // =====================================================
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

        page = Math.max(page, 0);
        if (size != 10 && size != 25 && size != 50 && size != 100) size = 10;

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "bannedAt"));

        Page<CommentBan> banPage = commentBanRepository.searchBans(
                keyword == null ? "" : keyword.trim(),
                activeOnly,
                pageable
        );

        long totalBans = commentBanRepository.count();
        long activeBans = commentBanRepository.countByActiveTrue();
        long revokedBans = commentBanRepository.countByActiveFalse();

        long totalItems = banPage.getTotalElements();
        int currentPage = banPage.getNumber();
        int pageSize = banPage.getSize();
        int totalPages = banPage.getTotalPages();
        long fromItem = totalItems == 0 ? 0 : ((long) currentPage * pageSize) + 1;
        long toItem = Math.min(((long) (currentPage + 1) * pageSize), totalItems);

        model.addAttribute("banPage", banPage);
        model.addAttribute("bans", banPage.getContent());
        model.addAttribute("totalItems", totalItems);
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("fromItem", fromItem);
        model.addAttribute("toItem", toItem);
        model.addAttribute("keyword", keyword);
        model.addAttribute("activeOnly", activeOnly);
        model.addAttribute("totalBans", totalBans);
        model.addAttribute("activeBans", activeBans);
        model.addAttribute("revokedBans", revokedBans);

        return "admin/bans/list";
    }

    // =====================================================
    // 2. TẠO LỆNH CẤM TRỰC TIẾP (SCR-39.4)
    // =====================================================
    @PostMapping("/create")
    public String createBan(
            @RequestParam String usernameOrEmail,
            @RequestParam String reason,
            @RequestParam(defaultValue = "0") int durationDays,
            HttpServletRequest request,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        if (!isAdmin(session)) {
            User user = (User) session.getAttribute("loggedInUser");
            if (user == null) return "redirect:/login";
            return "error/403";
        }

        User admin = getLoggedInUser(session);
        String query = usernameOrEmail != null ? usernameOrEmail.trim() : "";
        if (query.isBlank()) {
            redirectAttributes.addFlashAttribute("banError", "Vui lòng nhập tên đăng nhập hoặc email tài khoản.");
            return "redirect:/admin/bans";
        }

        User targetUser = userRepository.findByUsername(query)
                .or(() -> userRepository.findByEmail(query))
                .orElse(null);

        if (targetUser == null) {
            redirectAttributes.addFlashAttribute("banError", "Không tìm thấy người dùng phù hợp với: " + query);
            return "redirect:/admin/bans";
        }

        if (targetUser.getUserId().equals(admin.getUserId()) || hasRole(targetUser, "ADMIN")) {
            redirectAttributes.addFlashAttribute("banError", "Không thể áp dụng lệnh cấm đối với tài khoản Quản trị viên.");
            return "redirect:/admin/bans";
        }

        LocalDateTime expiresAt = durationDays > 0 ? LocalDateTime.now().plusDays(durationDays) : null;

        // Gom mỗi tài khoản 1 dòng duy nhất: nếu đã có bản ghi cấm trước đó thì cập nhật lại chính bản ghi đó
        CommentBan ban = commentBanRepository
                .findFirstByUser_UserIdOrderByBannedAtDesc(targetUser.getUserId())
                .orElseGet(CommentBan::new);
        boolean isUpdate = ban.getBanId() != null;

        ban.setUser(targetUser);
        ban.setBannedBy(admin);
        ban.setReason(reason != null && !reason.isBlank() ? reason.trim() : "Vi phạm quy chế bình luận");
        ban.setBannedAt(LocalDateTime.now());
        ban.setExpiresAt(expiresAt);
        ban.setActive(true);
        commentBanRepository.save(ban);

        // Ghi nhận Audit Log (FT31)
        auditLogService.logAction(
                admin,
                admin.getBranch(),
                "BAN_USER_COMMENT",
                "comment_bans",
                ban.getBanId(),
                request.getRemoteAddr(),
                null,
                Map.of(
                        "targetUser", targetUser.getUsername(),
                        "reason", ban.getReason(),
                        "expiresAt", expiresAt != null ? expiresAt.toString() : "Vĩnh viễn"
                )
        );

        redirectAttributes.addFlashAttribute("banSuccess",
                (isUpdate ? "Đã cập nhật lệnh cấm bình luận thành công cho tài khoản: " : "Đã thiết lập lệnh cấm bình luận thành công cho tài khoản: ")
                        + targetUser.getUsername());
        return "redirect:/admin/bans";
    }

    // =====================================================
    // 3. CHỨC NĂNG GỠ CẤM (UNBAN - SCR-39.4)
    // =====================================================
    @PostMapping("/{id}/unban")
    public String unbanUser(
            @PathVariable Long id,
            HttpServletRequest request,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        if (!isAdmin(session)) {
            User user = (User) session.getAttribute("loggedInUser");
            if (user == null) return "redirect:/login";
            return "error/403";
        }

        CommentBan ban = commentBanRepository.findById(id).orElse(null);
        if (ban != null) {
            User admin = getLoggedInUser(session);
            ban.setActive(false);
            commentBanRepository.save(ban);

            // Ghi nhận Audit Log (FT31)
            auditLogService.logAction(
                    admin,
                    admin.getBranch(),
                    "UNBAN_USER_COMMENT",
                    "comment_bans",
                    ban.getBanId(),
                    request.getRemoteAddr(),
                    Map.of("active", true, "targetUser", ban.getUser().getUsername()),
                    Map.of("active", false, "unbannedBy", admin.getUsername())
            );

            redirectAttributes.addFlashAttribute("banSuccess", "Đã gỡ cấm bình luận thành công cho tài khoản: " + ban.getUser().getUsername());
        }

        return "redirect:/admin/bans";
    }
}
