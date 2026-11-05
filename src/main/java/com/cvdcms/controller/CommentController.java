package com.cvdcms.controller;

import com.cvdcms.DTO.CommentCreateDTO;
import com.cvdcms.entity.Comment;
import com.cvdcms.entity.CommentLike;
import com.cvdcms.entity.NewsArticle;
import com.cvdcms.entity.User;
import com.cvdcms.repository.CommentBanRepository;
import com.cvdcms.repository.CommentLikeRepository;
import com.cvdcms.repository.CommentRepository;
import com.cvdcms.repository.NewsArticleRepository;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@Controller
public class CommentController {

    private final CommentRepository commentRepository;
    private final CommentLikeRepository commentLikeRepository;
    private final CommentBanRepository commentBanRepository;
    private final NewsArticleRepository newsRepository;

    public CommentController(
            CommentRepository commentRepository,
            CommentLikeRepository commentLikeRepository,
            CommentBanRepository commentBanRepository,
            NewsArticleRepository newsRepository) {
        this.commentRepository = commentRepository;
        this.commentLikeRepository = commentLikeRepository;
        this.commentBanRepository = commentBanRepository;
        this.newsRepository = newsRepository;
    }

    // =====================================================
    // 1. ĐĂNG BÌNH LUẬN GỐC (POST /news/{id}/comments)
    // =====================================================
    @PostMapping("/news/{id}/comments")
    public String createComment(
            @PathVariable Long id,
            @Valid @ModelAttribute CommentCreateDTO dto,
            BindingResult bindingResult,
            HttpSession session) {

        NewsArticle news = newsRepository.findById(id).orElse(null);
        if (news == null || !"PUBLISHED".equals(news.getStatus())) {
            session.setAttribute("commentError", "Bài viết không tồn tại hoặc chưa được xuất bản.");
            return "redirect:/news";
        }

        String detailUrl = "redirect:/news/" + news.getSlug();

        User user = getLoggedInUser(session);
        if (user == null) {
            return "redirect:/login";
        }

        if (bindingResult.hasErrors()
                || dto.getContent() == null
                || dto.getContent().trim().isEmpty()) {
            session.setAttribute("commentError", "Nội dung bình luận không được để trống (tối thiểu 2 ký tự).");
            return detailUrl;
        }

        if (isCommentBanned(user)) {
            session.setAttribute("commentError", getBanMessage(user));
            return detailUrl;
        }

        Comment comment = new Comment();
        comment.setNews(news);
        comment.setUser(user);
        comment.setParentComment(null);
        comment.setContent(dto.getContent().trim());
        comment.setStatus("ACTIVE");

        commentRepository.save(comment);

        session.setAttribute("commentSuccess", "Đăng bình luận thành công.");
        return detailUrl;
    }

    // =====================================================
    // 2. PHẢN HỒI BÌNH LUẬN (POST /comments/{id}/reply)
    // =====================================================
    @PostMapping("/comments/{id}/reply")
    public String reply(
            @PathVariable Long id,
            @Valid @ModelAttribute CommentCreateDTO dto,
            BindingResult bindingResult,
            HttpSession session) {

        User user = getLoggedInUser(session);
        if (user == null) return "redirect:/login";

        Comment parent = commentRepository.findById(id).orElse(null);
        if (parent == null) return "redirect:/news";

        NewsArticle news = parent.getNews();
        if (news == null || !"PUBLISHED".equals(news.getStatus())) {
            session.setAttribute("commentError", "Bài viết không còn khả dụng.");
            return "redirect:/news";
        }

        String detailUrl = "redirect:/news/" + news.getSlug();

        // Không cho phép phản hồi bình luận đã bị xóa
        if (!"ACTIVE".equals(parent.getStatus())) {
            session.setAttribute("commentError", "Không thể phản hồi bình luận đã bị xóa.");
            return detailUrl;
        }

        if (bindingResult.hasErrors() || isCommentBanned(user)) {
            if (isCommentBanned(user)) session.setAttribute("commentError", getBanMessage(user));
            return detailUrl;
        }

        // Giới hạn 1 cấp phản hồi: nếu parent vốn là 1 reply thì không cho lồng thêm
        if (parent.getParentComment() != null) {
            return detailUrl;
        }

        Comment reply = new Comment();
        reply.setNews(news);
        reply.setUser(user);
        reply.setParentComment(parent);
        reply.setContent(dto.getContent().trim());
        reply.setStatus("ACTIVE");

        commentRepository.save(reply);
        session.setAttribute("commentSuccess", "Gửi phản hồi thành công.");
        return detailUrl;
    }

    // =====================================================
    // 3. CHỈNH SỬA BÌNH LUẬN (POST /comments/{id}/edit)
    // =====================================================
    @PostMapping("/comments/{id}/edit")
    public String editComment(
            @PathVariable Long id,
            @RequestParam("content") String content,
            HttpSession session) {

        User user = getLoggedInUser(session);
        if (user == null) return "redirect:/login";

        Comment comment = commentRepository.findById(id).orElse(null);
        if (comment == null || !"ACTIVE".equals(comment.getStatus())) return "redirect:/news";

        String detailUrl = "redirect:/news/" + comment.getNews().getSlug();

        if (isCommentBanned(user)) {
            session.setAttribute("commentError", "Tài khoản của bạn đã bị cấm, không được phép chỉnh sửa bình luận!");
            return detailUrl;
        }

        boolean isOwner = comment.getUser() != null
                && comment.getUser().getUserId().equals(user.getUserId());
        boolean admin = isAdmin(user);

        if (!isOwner && !admin) {
            return detailUrl;
        }

        if (content != null && !content.trim().isEmpty()) {
            comment.setContent(content.trim());
            commentRepository.save(comment);
            session.setAttribute("commentSuccess", "Cập nhật bình luận thành công.");
        }

        return detailUrl;
    }

    // =====================================================
    // 4. XÓA BÌNH LUẬN CỦA CHÍNH MÌNH (POST /comments/{id}/delete)
    // =====================================================
    @PostMapping("/comments/{id}/delete")
    public String deleteOwnComment(
            @PathVariable Long id,
            HttpSession session) {

        User user = getLoggedInUser(session);
        if (user == null) return "redirect:/login";

        Comment comment = commentRepository.findById(id).orElse(null);
        if (comment == null) return "redirect:/news";

        String detailUrl = "redirect:/news/" + comment.getNews().getSlug();

        boolean admin = isAdmin(user);
        if (!admin && isCommentBanned(user)) {
            session.setAttribute("commentError", "Tài khoản của bạn đã bị cấm, không thể xóa bình luận.");
            return detailUrl;
        }

        boolean isOwner = comment.getUser() != null
                && comment.getUser().getUserId().equals(user.getUserId());

        if (!isOwner && !admin) {
            return detailUrl;
        }

        if (!"ACTIVE".equals(comment.getStatus())) {
            return detailUrl;
        }

        comment.setStatus("DELETED");
        comment.setDeletedAt(LocalDateTime.now());
        comment.setDeletedBy(user);
        commentRepository.save(comment);

        session.setAttribute("commentSuccess", "Đã xóa bình luận.");
        return detailUrl;
    }

    // =====================================================
    // 5. THÍCH / BỎ THÍCH BÌNH LUẬN (POST /comments/{id}/like)
    // Bổ sung @Transactional, kiểm tra Ban và chống Race Condition
    // =====================================================
    @PostMapping("/comments/{id}/like")
    @Transactional
    public String toggleLike(@PathVariable Long id, HttpSession session) {
        User user = getLoggedInUser(session);
        if (user == null) return "redirect:/login";

        Comment comment = commentRepository.findById(id).orElse(null);
        if (comment == null || !"ACTIVE".equals(comment.getStatus())) return "redirect:/news";

        String detailUrl = "redirect:/news/" + comment.getNews().getSlug();

        // Kiểm tra xem người dùng có bị cấm tương tác không
        if (isCommentBanned(user)) {
            session.setAttribute("commentError", "Tài khoản của bạn đang bị cấm tương tác.");
            return detailUrl;
        }

        try {
            boolean alreadyLiked = commentLikeRepository.existsByComment_CommentIdAndUser_UserId(id, user.getUserId());
            if (alreadyLiked) {
                commentLikeRepository.findByComment_CommentIdAndUser_UserId(id, user.getUserId())
                        .ifPresent(commentLikeRepository::delete);
            } else {
                CommentLike like = new CommentLike();
                like.setComment(comment);
                like.setUser(user);
                commentLikeRepository.save(like);
            }
        } catch (DataIntegrityViolationException ignored) {
            // Chống lỗi đụng độ 500 khi click đúp nhanh
        }

        return detailUrl;
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

    private boolean isAdmin(User user) {
        return hasRole(user, "ADMIN");
    }

    private boolean isCommentBanned(User user) {
        return commentBanRepository
                .findFirstByUser_UserIdAndActiveTrueOrderByBannedAtDesc(user.getUserId())
                .map(ban -> {
                    if (ban.getExpiresAt() != null
                            && !ban.getExpiresAt().isAfter(LocalDateTime.now())) {
                        ban.setActive(false);
                        commentBanRepository.save(ban);
                        return false;
                    }
                    return true;
                })
                .orElse(false);
    }

    private String getBanMessage(User user) {
        return commentBanRepository.findFirstByUser_UserIdAndActiveTrueOrderByBannedAtDesc(user.getUserId())
                .map(ban -> ban.getExpiresAt() == null ? "Tài khoản của bạn đã bị cấm bình luận vĩnh viễn." : "Tài khoản của bạn đã bị cấm bình luận đến " + ban.getExpiresAt().toLocalDate() + ".")
                .orElse("Tài khoản của bạn không thể bình luận.");
    }
}
