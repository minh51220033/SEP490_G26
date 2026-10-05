package com.cvdcms.controller;

import com.cvdcms.entity.Comment;
import com.cvdcms.entity.NewsArticle;
import com.cvdcms.entity.User;
import com.cvdcms.repository.CommentBanRepository;
import com.cvdcms.repository.CommentLikeRepository;
import com.cvdcms.repository.CommentRepository;
import com.cvdcms.repository.NewsArticleRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@Controller
@RequestMapping("/news")
public class NewsController {

    private final NewsArticleRepository newsRepository;
    private final CommentRepository commentRepository;
    private final CommentLikeRepository commentLikeRepository;
    private final CommentBanRepository commentBanRepository;

    public NewsController(
            NewsArticleRepository newsRepository,
            CommentRepository commentRepository,
            CommentLikeRepository commentLikeRepository,
            CommentBanRepository commentBanRepository) {
        this.newsRepository = newsRepository;
        this.commentRepository = commentRepository;
        this.commentLikeRepository = commentLikeRepository;
        this.commentBanRepository = commentBanRepository;
    }

    // =====================================================
    // 1. TRANG DANH SÁCH TIN TỨC CÔNG KHAI (GET /news)
    // Tích hợp đúng với searchPublished() & list_2.html
    // =====================================================
    @GetMapping({"", "/"})
    public String publicNewsList(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "6") int size,
            Model model) {

        if (page < 0) page = 0;
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "publishedAt"));

        Page<NewsArticle> newsPage = newsRepository.searchPublished(keyword == null ? "" : keyword.trim(), pageable);

        model.addAttribute("newsPage", newsPage);
        model.addAttribute("keyword", keyword);

        // Đảm bảo tên template tương ứng với vị trí file list_2.html (VD: templates/news/list.html)
        return "news/list";
    }

    // =====================================================
    // 2. TRANG CHI TIẾT BÀI VIẾT (GET /news/{slug})
    // =====================================================
    @GetMapping("/{slug}")
    public String newsDetail(
            @PathVariable String slug,
            @RequestParam(defaultValue = "0") int commentPage,
            HttpSession session,
            Model model) {

        NewsArticle news = newsRepository.findBySlug(slug).orElse(null);
        if (news == null || !"PUBLISHED".equals(news.getStatus())) {
            return "redirect:/news";
        }

        if (session.getAttribute("commentError") != null) {
            model.addAttribute("commentError", session.getAttribute("commentError"));
            session.removeAttribute("commentError");
        }
        if (session.getAttribute("commentSuccess") != null) {
            model.addAttribute("commentSuccess", session.getAttribute("commentSuccess"));
            session.removeAttribute("commentSuccess");
        }

        User loggedInUser = (User) session.getAttribute("loggedInUser");
        boolean isBanned = false;
        String banMessage = null;

        if (loggedInUser != null) {
            var banOpt = commentBanRepository.findFirstByUser_UserIdAndActiveTrueOrderByBannedAtDesc(loggedInUser.getUserId());
            if (banOpt.isPresent()) {
                var ban = banOpt.get();
                if (ban.getExpiresAt() != null && ban.getExpiresAt().isBefore(LocalDateTime.now())) {
                    ban.setActive(false);
                    commentBanRepository.save(ban);
                } else {
                    isBanned = true;
                    if (ban.getExpiresAt() == null) {
                        banMessage = "Tài khoản của bạn đã bị cấm bình luận vĩnh viễn. Lý do: " + (ban.getReason() != null ? ban.getReason() : "Vi phạm quy định.");
                    } else {
                        banMessage = "Tài khoản của bạn đang bị cấm bình luận đến " + ban.getExpiresAt().toLocalDate() + ". Lý do: " + (ban.getReason() != null ? ban.getReason() : "Vi phạm quy định.");
                    }
                }
            }
        }

        model.addAttribute("isBanned", isBanned);
        model.addAttribute("banMessage", banMessage);

        Pageable commentPageable = PageRequest.of(Math.max(commentPage, 0), 10, Sort.by(Sort.Direction.ASC, "createdAt"));
        Page<Comment> commentPageResult = commentRepository.findByNews_NewsIdAndParentCommentIsNullAndStatus(news.getNewsId(), "ACTIVE", commentPageable);
        List<Comment> comments = commentPageResult.getContent();

        Map<Long, List<Comment>> repliesByComment = new HashMap<>();
        Map<Long, Long> likeCounts = new HashMap<>();

        for (Comment comment : comments) {
            List<Comment> replies = commentRepository.findByParentComment_CommentIdAndStatusOrderByCreatedAtAsc(comment.getCommentId(), "ACTIVE");
            repliesByComment.put(comment.getCommentId(), replies);
            likeCounts.put(comment.getCommentId(), commentLikeRepository.countByComment_CommentId(comment.getCommentId()));

            for (Comment reply : replies) {
                likeCounts.put(reply.getCommentId(), commentLikeRepository.countByComment_CommentId(reply.getCommentId()));
            }
        }

        model.addAttribute("news", news);
        model.addAttribute("comments", comments);
        model.addAttribute("commentPage", commentPageResult);
        model.addAttribute("repliesByComment", repliesByComment);
        model.addAttribute("likeCounts", likeCounts);

        return "news/detail";
    }
}