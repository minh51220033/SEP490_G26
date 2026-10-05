package com.cvdcms.controller;

import com.cvdcms.DTO.CommentBanDTO;
import com.cvdcms.entity.Comment;
import com.cvdcms.entity.CommentBan;
import com.cvdcms.entity.User;
import com.cvdcms.repository.CommentBanRepository;
import com.cvdcms.repository.CommentRepository;
import com.cvdcms.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Set;

//Quản lý /admin/comments Chỉ xử lý comment và kích hoạt cấm

@Controller
@RequestMapping("/admin")
public class AdminCommentController {

    private final CommentRepository commentRepository;
    private final CommentBanRepository commentBanRepository;
    private final UserRepository userRepository;

    public AdminCommentController(
            CommentRepository commentRepository,
            CommentBanRepository commentBanRepository,
            UserRepository userRepository) {

        this.commentRepository = commentRepository;
        this.commentBanRepository = commentBanRepository;
        this.userRepository = userRepository;
    }

    private boolean isAdmin(HttpSession session) {
        User user = (User) session.getAttribute("loggedInUser");
        return user != null && user.getRole() != null && "ADMIN".equals(user.getRole().getRoleName());
    }


    // xóa
    @PostMapping("/comments/{id}/delete")
    public String deleteComment(@PathVariable Long id, HttpSession session) {
        if (!isAdmin(session)) {
            if (session.getAttribute("loggedInUser") == null) return "redirect:/login";
            return "error/403";
        }

        Comment comment = commentRepository.findById(id).orElse(null);
        if (comment == null) return "redirect:/admin";

        User admin = (User) session.getAttribute("loggedInUser");

        comment.setStatus("DELETED");
        comment.setDeletedAt(LocalDateTime.now());
        comment.setDeletedBy(admin);
        commentRepository.save(comment);

        return "redirect:/news/" + comment.getNews().getSlug();
    }

    // =====================================================
    // BAN USER FROM COMMENTING (Kiem tra ngat can Admin ban chinh minh)
    // =====================================================
    @PostMapping("/comments/{id}/ban")
    public String banUser(
            @PathVariable Long id,
            @RequestParam(value = "reason", defaultValue = "Vi phạm quy định bình luận") String reason,
            HttpSession session) {

        if (!isAdmin(session)) {
            if (session.getAttribute("loggedInUser") == null) return "redirect:/login";
            return "error/403";
        }

        Comment comment = commentRepository.findById(id).orElse(null);
        if (comment == null) return "redirect:/admin";

        User targetUser = comment.getUser();
        User admin = (User) session.getAttribute("loggedInUser");

        // KHÔNG CHO PHÉP ADMIN TỰ BAN CHÍNH MÌNH HOẶC BAN TÀI KHOẢN ADMIN KHÁC
        boolean isTargetAdmin = targetUser.getRole() != null && "ADMIN".equals(targetUser.getRole().getRoleName());
        if (targetUser.getUserId().equals(admin.getUserId()) || isTargetAdmin) {
            session.setAttribute("commentError", "Không thể thực hiện cấm tài khoản Admin!");
            return "redirect:/news/" + comment.getNews().getSlug();
        }

        boolean alreadyBanned = commentBanRepository
                .findFirstByUser_UserIdAndActiveTrueOrderByBannedAtDesc(targetUser.getUserId())
                .isPresent();

        if (alreadyBanned) {
            return "redirect:/news/" + comment.getNews().getSlug();
        }

        CommentBan ban = new CommentBan();
        ban.setUser(targetUser);
        ban.setBannedBy(admin);
        ban.setReason(reason.trim());
        ban.setBannedAt(LocalDateTime.now());
        ban.setExpiresAt(null); // Vô thời hạn
        ban.setActive(true);

        commentBanRepository.save(ban);

        return "redirect:/news/" + comment.getNews().getSlug();
    }


}