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
import org.springframework.stereotype.Controller;
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

    @PostMapping("/news/{id}/comments")
    public String createComment(
            @PathVariable Long id,
            @Valid @ModelAttribute CommentCreateDTO dto,
            BindingResult bindingResult,
            HttpSession session) {

        User user = getLoggedInUser(session);
        if (user == null) return "redirect:/login";
        if (bindingResult.hasErrors()) return "redirect:/news/" + getNewsSlug(id);

        if (isCommentBanned(user)) {
            session.setAttribute("commentError", getBanMessage(user));
            return "redirect:/news/" + getNewsSlug(id);
        }

        NewsArticle news = newsRepository.findById(id).orElse(null);
        if (news == null || !"PUBLISHED".equals(news.getStatus())) return "redirect:/news";

        Comment comment = new Comment();
        comment.setNews(news);
        comment.setUser(user);
        comment.setParentComment(null);
        comment.setContent(dto.getContent().trim());
        comment.setStatus("ACTIVE");

        commentRepository.save(comment);
        session.setAttribute("commentSuccess", "Đăng bình luận thành công.");
        return "redirect:/news/" + news.getSlug();
    }

    @PostMapping("/comments/{id}/reply")
    public String reply(
            @PathVariable Long id,
            @Valid @ModelAttribute CommentCreateDTO dto,
            BindingResult bindingResult,
            HttpSession session) {

        User user = getLoggedInUser(session);
        if (user == null) return "redirect:/login";

        Comment parent = commentRepository.findById(id).orElse(null);
        if (parent == null || !"ACTIVE".equals(parent.getStatus())) return "redirect:/news";

        if (bindingResult.hasErrors() || isCommentBanned(user)) {
            if (isCommentBanned(user)) session.setAttribute("commentError", getBanMessage(user));
            return "redirect:/news/" + parent.getNews().getSlug();
        }

        if (parent.getParentComment() != null) {
            return "redirect:/news/" + parent.getNews().getSlug();
        }

        Comment reply = new Comment();
        reply.setNews(parent.getNews());
        reply.setUser(user);
        reply.setParentComment(parent);
        reply.setContent(dto.getContent().trim());
        reply.setStatus("ACTIVE");

        commentRepository.save(reply);
        session.setAttribute("commentSuccess", "Gửi phản hồi thành công.");
        return "redirect:/news/" + parent.getNews().getSlug();
    }

    // CHẶN CHỈNH SỬA BÌNH LUẬN NẾU TÀI KHOẢN ĐANG BỊ BAN
    @PostMapping("/comments/{id}/edit")
    public String editComment(
            @PathVariable Long id,
            @RequestParam("content") String content,
            HttpSession session) {

        User user = getLoggedInUser(session);
        if (user == null) return "redirect:/login";

        Comment comment = commentRepository.findById(id).orElse(null);
        if (comment == null || !"ACTIVE".equals(comment.getStatus())) return "redirect:/news";

        if (isCommentBanned(user)) {
            session.setAttribute("commentError", "Tài khoản của bạn đã bị cấm, không được phép chỉnh sửa bình luận!");
            return "redirect:/news/" + comment.getNews().getSlug();
        }

        boolean isOwner = comment.getUser().getUserId().equals(user.getUserId());
        boolean isAdmin = user.getRole() != null && "ADMIN".equals(user.getRole().getRoleName());

        if (!isOwner && !isAdmin) {
            return "redirect:/news/" + comment.getNews().getSlug();
        }

        if (content != null && !content.trim().isEmpty()) {
            comment.setContent(content.trim());
            commentRepository.save(comment);
            session.setAttribute("commentSuccess", "Cập nhật bình luận thành công.");
        }

        return "redirect:/news/" + comment.getNews().getSlug();
    }

    // CHẶN XÓA BÌNH LUẬN CỦA CHÍNH MÌNH NẾU ĐANG BỊ BAN
    @PostMapping("/comments/{id}/delete")
    public String deleteOwnComment(
            @PathVariable Long id,
            HttpSession session) {

        User user = getLoggedInUser(session);
        if (user == null) return "redirect:/login";

        Comment comment = commentRepository.findById(id).orElse(null);
        if (comment == null) return "redirect:/news";

        boolean isAdmin = user.getRole() != null && "ADMIN".equals(user.getRole().getRoleName());

        if (!isAdmin && isCommentBanned(user)) {
            session.setAttribute("commentError", "Tài khoản của bạn đã bị cấm, không thể xóa bình luận!");
            return "redirect:/news/" + comment.getNews().getSlug();
        }

        boolean isOwner = comment.getUser().getUserId().equals(user.getUserId());

        if (!isOwner && !isAdmin) {
            return "redirect:/news/" + comment.getNews().getSlug();
        }

        comment.setStatus("DELETED");
        comment.setDeletedAt(LocalDateTime.now());
        comment.setDeletedBy(user);
        commentRepository.save(comment);

        return "redirect:/news/" + comment.getNews().getSlug();
    }

    @PostMapping("/comments/{id}/like")
    public String toggleLike(@PathVariable Long id, HttpSession session) {
        User user = getLoggedInUser(session);
        if (user == null) return "redirect:/login";

        Comment comment = commentRepository.findById(id).orElse(null);
        if (comment == null || !"ACTIVE".equals(comment.getStatus())) return "redirect:/news";

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

        return "redirect:/news/" + comment.getNews().getSlug();
    }

    private User getLoggedInUser(HttpSession session) {
        return (User) session.getAttribute("loggedInUser");
    }

    private boolean isCommentBanned(User user) {
        return commentBanRepository.findFirstByUser_UserIdAndActiveTrueOrderByBannedAtDesc(user.getUserId())
                .map(ban -> {
                    if (ban.getExpiresAt() != null && ban.getExpiresAt().isBefore(LocalDateTime.now())) {
                        ban.setActive(false);
                        commentBanRepository.save(ban);
                        return false;
                    }
                    return true;
                }).orElse(false);
    }

    private String getBanMessage(User user) {
        return commentBanRepository.findFirstByUser_UserIdAndActiveTrueOrderByBannedAtDesc(user.getUserId())
                .map(ban -> ban.getExpiresAt() == null ? "Tài khoản của bạn đã bị cấm bình luận vĩnh viễn." : "Tài khoản của bạn đã bị cấm bình luận đến " + ban.getExpiresAt().toLocalDate() + ".")
                .orElse("Tài khoản của bạn không thể bình luận.");
    }

    private String getNewsSlug(Long newsId) {
        return newsRepository.findById(newsId).map(NewsArticle::getSlug).orElse("");
    }
}