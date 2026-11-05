package com.cvdcms.controller;

import com.cvdcms.entity.Comment;
import com.cvdcms.entity.CommentBan;
import com.cvdcms.entity.User;
import com.cvdcms.repository.CommentBanRepository;
import com.cvdcms.repository.CommentRepository;
import com.cvdcms.repository.NewsArticleRepository;
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

// Quản lý kiểm duyệt bình luận tập trung (FT39 - SCR-39.1 & SCR-39.2)
@Controller
@RequestMapping("/admin")
public class AdminCommentController {

    private final CommentRepository commentRepository;
    private final CommentBanRepository commentBanRepository;
    private final UserRepository userRepository;
    private final NewsArticleRepository newsRepository;
    private final SystemAuditLogService auditLogService;

    public AdminCommentController(
            CommentRepository commentRepository,
            CommentBanRepository commentBanRepository,
            UserRepository userRepository,
            NewsArticleRepository newsRepository,
            SystemAuditLogService auditLogService) {

        this.commentRepository = commentRepository;
        this.commentBanRepository = commentBanRepository;
        this.userRepository = userRepository;
        this.newsRepository = newsRepository;
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

    private boolean isModerator(HttpSession session) {
        User user = getLoggedInUser(session);
        return hasRole(user, "ADMIN");
    }

    // =====================================================
    // 1. DANH SÁCH BÌNH LUẬN CẦN KIỂM DUYỆT (SCR-39.1)
    // =====================================================
    @GetMapping("/comments")
    public String listComments(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(required = false) Long newsId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size,
            HttpSession session,
            Model model) {

        if (!isModerator(session)) {
            if (getLoggedInUser(session) == null) return "redirect:/login";
            return "error/403";
        }

        page = Math.max(page, 0);
        if (size != 10 && size != 25 && size != 50 && size != 100) {
            size = 10;
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Comment> commentPage = commentRepository.searchCommentsAdmin(
                keyword != null ? keyword.trim() : "",
                status != null ? status.trim() : "",
                newsId,
                pageable
        );

        long totalComments = commentRepository.count();
        long activeComments = commentRepository.countByStatus("ACTIVE");
        long deletedComments = commentRepository.countByStatus("DELETED");

        long totalItems = commentPage.getTotalElements();
        int currentPage = commentPage.getNumber();
        int pageSize = commentPage.getSize();
        int totalPages = commentPage.getTotalPages();
        long fromItem = totalItems == 0 ? 0 : ((long) currentPage * pageSize) + 1;
        long toItem = Math.min(((long) (currentPage + 1) * pageSize), totalItems);

        model.addAttribute("commentPage", commentPage);
        model.addAttribute("comments", commentPage.getContent());
        model.addAttribute("totalItems", totalItems);
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("fromItem", fromItem);
        model.addAttribute("toItem", toItem);
        model.addAttribute("keyword", keyword);
        model.addAttribute("status", status);
        model.addAttribute("newsId", newsId);
        model.addAttribute("totalComments", totalComments);
        model.addAttribute("activeComments", activeComments);
        model.addAttribute("deletedComments", deletedComments);

        return "admin/comments/list";
    }

    // =====================================================
    // 2. XÓA BÌNH LUẬN DO VI PHẠM (SCR-39.2)
    // =====================================================
    @PostMapping("/comments/{id}/delete")
    public String deleteComment(
            @PathVariable Long id,
            @RequestParam(required = false) String returnUrl,
            HttpServletRequest request,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        if (!isModerator(session)) {
            if (session.getAttribute("loggedInUser") == null) return "redirect:/login";
            return "error/403";
        }

        Comment comment = commentRepository.findById(id).orElse(null);
        if (comment == null) return "redirect:/admin/comments";

        User admin = (User) session.getAttribute("loggedInUser");

        String oldStatus = comment.getStatus();
        comment.setStatus("DELETED");
        comment.setDeletedAt(LocalDateTime.now());
        comment.setDeletedBy(admin);
        commentRepository.save(comment);

        // Ghi nhận Audit Log (FT31)
        auditLogService.logAction(
                admin,
                comment.getNews() != null ? comment.getNews().getBranch() : null,
                "DELETE_COMMENT",
                "comments",
                comment.getCommentId(),
                request.getRemoteAddr(),
                Map.of("status", oldStatus, "content", comment.getContent()),
                Map.of("status", "DELETED", "deletedBy", admin.getUsername())
        );

        redirectAttributes.addFlashAttribute("commentSuccess", "Đã xóa bình luận thành công.");

        if (returnUrl != null && !returnUrl.isBlank()) {
            return "redirect:" + returnUrl;
        }
        return "redirect:/news/" + comment.getNews().getSlug();
    }

    // =====================================================
    // 3. KHÔI PHỤC BÌNH LUẬN ĐÃ XÓA (SCR-39.2)
    // =====================================================
    @PostMapping("/comments/{id}/restore")
    public String restoreComment(
            @PathVariable Long id,
            @RequestParam(required = false) String returnUrl,
            HttpServletRequest request,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        if (!isModerator(session)) {
            if (session.getAttribute("loggedInUser") == null) return "redirect:/login";
            return "error/403";
        }

        Comment comment = commentRepository.findById(id).orElse(null);
        if (comment == null) return "redirect:/admin/comments";

        User admin = (User) session.getAttribute("loggedInUser");

        String oldStatus = comment.getStatus();
        comment.setStatus("ACTIVE");
        comment.setDeletedAt(null);
        comment.setDeletedBy(null);
        commentRepository.save(comment);

        // Ghi nhận Audit Log (FT31)
        auditLogService.logAction(
                admin,
                comment.getNews() != null ? comment.getNews().getBranch() : null,
                "RESTORE_COMMENT",
                "comments",
                comment.getCommentId(),
                request.getRemoteAddr(),
                Map.of("status", oldStatus),
                Map.of("status", "ACTIVE", "restoredBy", admin.getUsername())
        );

        redirectAttributes.addFlashAttribute("commentSuccess", "Đã khôi phục bình luận thành công.");

        if (returnUrl != null && !returnUrl.isBlank()) {
            return "redirect:" + returnUrl;
        }
        return "redirect:/admin/comments";
    }

    // =====================================================
    // 4. CẤM NGƯỜI DÙNG BÌNH LUẬN TỪ COMMENT (SCR-39.4)
    // =====================================================
    @PostMapping("/comments/{id}/ban")
    public String banUser(
            @PathVariable Long id,
            @RequestParam(value = "reason", defaultValue = "Vi phạm quy định bình luận") String reason,
            @RequestParam(value = "durationDays", defaultValue = "0") int durationDays,
            @RequestParam(required = false) String returnUrl,
            HttpServletRequest request,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        if (!isModerator(session)) {
            if (session.getAttribute("loggedInUser") == null) return "redirect:/login";
            return "error/403";
        }

        Comment comment = commentRepository.findById(id).orElse(null);
        if (comment == null) return "redirect:/admin/comments";

        User targetUser = comment.getUser();
        User admin = getLoggedInUser(session);

        if (targetUser == null || admin == null) {
            return "redirect:/admin/comments";
        }

        // Không cho phép cấm chính mình hoặc cấm tài khoản Admin khác
        if (targetUser.getUserId().equals(admin.getUserId()) || hasRole(targetUser, "ADMIN")) {
            redirectAttributes.addFlashAttribute("commentError", "Không thể cấm tài khoản Quản trị viên.");
            if (returnUrl != null && !returnUrl.isBlank()) return "redirect:" + returnUrl;
            return "redirect:/news/" + comment.getNews().getSlug();
        }

        boolean alreadyBanned = commentBanRepository
                .findFirstByUser_UserIdAndActiveTrueOrderByBannedAtDesc(targetUser.getUserId())
                .isPresent();

        if (alreadyBanned) {
            redirectAttributes.addFlashAttribute("commentError", "Tài khoản này hiện đang trong thời gian bị cấm.");
            if (returnUrl != null && !returnUrl.isBlank()) return "redirect:" + returnUrl;
            return "redirect:/news/" + comment.getNews().getSlug();
        }

        LocalDateTime expiresAt = durationDays > 0 ? LocalDateTime.now().plusDays(durationDays) : null;

        CommentBan ban = new CommentBan();
        ban.setUser(targetUser);
        ban.setBannedBy(admin);
        ban.setReason(reason.trim());
        ban.setBannedAt(LocalDateTime.now());
        ban.setExpiresAt(expiresAt);
        ban.setActive(true);
        commentBanRepository.save(ban);

        // Đồng thời ẩn bình luận vi phạm
        if ("ACTIVE".equals(comment.getStatus())) {
            comment.setStatus("DELETED");
            comment.setDeletedAt(LocalDateTime.now());
            comment.setDeletedBy(admin);
            commentRepository.save(comment);
        }

        // Ghi nhận Audit Log (FT31)
        auditLogService.logAction(
                admin,
                comment.getNews() != null ? comment.getNews().getBranch() : null,
                "BAN_USER_COMMENT",
                "comment_bans",
                ban.getBanId(),
                request.getRemoteAddr(),
                null,
                Map.of(
                        "targetUser", targetUser.getUsername(),
                        "reason", reason.trim(),
                        "expiresAt", expiresAt != null ? expiresAt.toString() : "Vĩnh viễn"
                )
        );

        redirectAttributes.addFlashAttribute("commentSuccess", "Đã áp dụng lệnh cấm bình luận đối với tài khoản: " + targetUser.getUsername());

        if (returnUrl != null && !returnUrl.isBlank()) {
            return "redirect:" + returnUrl;
        }
        return "redirect:/news/" + comment.getNews().getSlug();
    }
}
