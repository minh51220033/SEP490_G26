package com.cvdcms.controller;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.Comment;
import com.cvdcms.entity.NewsArticle;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.User;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.CommentBanRepository;
import com.cvdcms.repository.CommentLikeRepository;
import com.cvdcms.repository.CommentRepository;
import com.cvdcms.repository.NewsArticleRepository;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.repository.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/news")
public class NewsController {

    private final NewsArticleRepository newsRepository;
    private final CommentRepository commentRepository;
    private final CommentLikeRepository commentLikeRepository;
    private final CommentBanRepository commentBanRepository;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;
    private final PatientProfileRepository patientProfileRepository;

    public NewsController(
            NewsArticleRepository newsRepository,
            CommentRepository commentRepository,
            CommentLikeRepository commentLikeRepository,
            CommentBanRepository commentBanRepository,
            BranchRepository branchRepository,
            UserRepository userRepository,
            PatientProfileRepository patientProfileRepository
    ) {
        this.newsRepository = newsRepository;
        this.commentRepository = commentRepository;
        this.commentLikeRepository = commentLikeRepository;
        this.commentBanRepository = commentBanRepository;
        this.branchRepository = branchRepository;
        this.userRepository = userRepository;
        this.patientProfileRepository = patientProfileRepository;
    }

    // =====================================================
    // 1. TRANG DANH SÁCH TIN TỨC CÔNG KHAI (GET /news)
    // Cho phép Guest, Bệnh nhân và Nhân viên xem công khai
    // =====================================================
    @GetMapping({"", "/"})
    public String publicNewsList(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "6") int size,
            HttpSession session,
            Model model
    ) {
        page = Math.max(page, 0);
        if (size != 6 && size != 9 && size != 12 && size != 18) {
            size = 6;
        }

        String searchKeyword = keyword == null ? "" : keyword.trim();
        List<Branch> activeBranches = branchRepository.findByIsActiveTrueOrderByBranchNameAsc();

        String currentBranchName = "Tất cả cơ sở";
        String currentBranchAddress = "";
        Long currentBranchId = branchId;

        if (branchId != null) {
            for (Branch b : activeBranches) {
                if (branchId.equals(b.getBranchId())) {
                    currentBranchName = b.getBranchName();
                    currentBranchAddress = b.getAddress() != null ? b.getAddress() : "";
                    break;
                }
            }
        } else if (session != null && session.getAttribute("loggedInUser") instanceof User sessionUser) {
            User user = userRepository.findById(sessionUser.getUserId()).orElse(null);
            if (user != null) {
                PatientProfile profile = patientProfileRepository
                        .findFirstByUser_UserIdAndIsDefaultTrue(user.getUserId())
                        .orElse(null);
                if (profile != null && profile.getPreferredBranch() != null) {
                    Branch pref = profile.getPreferredBranch();
                    currentBranchName = pref.getBranchName();
                    currentBranchAddress = pref.getAddress() != null ? pref.getAddress() : "";
                }
            }
        }

        Pageable pageable = PageRequest.of(page, size);
        Page<NewsArticle> newsPage = newsRepository.searchPublishedPublic(
                searchKeyword,
                branchId,
                pageable
        );

        model.addAttribute("activeNav", "news");
        model.addAttribute("newsPage", newsPage);
        model.addAttribute("keyword", searchKeyword);
        model.addAttribute("size", size);
        model.addAttribute("branches", activeBranches);
        model.addAttribute("selectedBranchId", branchId);
        model.addAttribute("currentBranchName", currentBranchName);
        model.addAttribute("currentBranchAddress", currentBranchAddress);
        model.addAttribute("currentBranchId", currentBranchId);

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
            HttpServletResponse response,
            Model model
    ) {
        model.addAttribute("activeNav", "news");

        NewsArticle news = newsRepository.findBySlug(slug).orElse(null);
        if (news == null
                || !"PUBLISHED".equalsIgnoreCase(news.getStatus())
                || (news.getBranch() != null && !Boolean.TRUE.equals(news.getBranch().getIsActive()))) {
            if (response != null) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            }
            model.addAttribute("notFoundTitle", "Không tìm thấy bài viết");
            model.addAttribute("notFoundMessage", "Bài viết không tồn tại hoặc hiện chưa được xuất bản công khai.");
            model.addAttribute("backUrl", "/news");
            model.addAttribute("backLabel", "Quay lại danh sách tin tức");
            return "portal/not-found";
        }

        if (session != null && session.getAttribute("commentError") != null) {
            model.addAttribute("commentError", session.getAttribute("commentError"));
            session.removeAttribute("commentError");
        }
        if (session != null && session.getAttribute("commentSuccess") != null) {
            model.addAttribute("commentSuccess", session.getAttribute("commentSuccess"));
            session.removeAttribute("commentSuccess");
        }

        User loggedInUser = session != null ? (User) session.getAttribute("loggedInUser") : null;
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
                        banMessage = "Tài khoản của bạn đã bị cấm bình luận vĩnh viễn. Lý do: "
                                + (ban.getReason() != null ? ban.getReason() : "Vi phạm quy định.");
                    } else {
                        banMessage = "Tài khoản của bạn đang bị cấm bình luận đến "
                                + ban.getExpiresAt().toLocalDate() + ". Lý do: "
                                + (ban.getReason() != null ? ban.getReason() : "Vi phạm quy định.");
                    }
                }
            }
        }

        model.addAttribute("isBanned", isBanned);
        model.addAttribute("banMessage", banMessage);

        Pageable commentPageable = PageRequest.of(
                Math.max(commentPage, 0),
                10,
                Sort.by(Sort.Direction.ASC, "createdAt")
        );
        Page<Comment> commentPageResult = commentRepository.findByNews_NewsIdAndParentCommentIsNullAndStatus(
                news.getNewsId(),
                "ACTIVE",
                commentPageable
        );
        List<Comment> comments = commentPageResult.getContent();

        Map<Long, List<Comment>> repliesByComment = new HashMap<>();
        Map<Long, Long> likeCounts = new HashMap<>();

        for (Comment comment : comments) {
            List<Comment> replies = commentRepository.findByParentComment_CommentIdAndStatusOrderByCreatedAtAsc(
                    comment.getCommentId(),
                    "ACTIVE"
            );
            repliesByComment.put(comment.getCommentId(), replies);
            likeCounts.put(comment.getCommentId(), commentLikeRepository.countByComment_CommentId(comment.getCommentId()));

            for (Comment reply : replies) {
                likeCounts.put(reply.getCommentId(), commentLikeRepository.countByComment_CommentId(reply.getCommentId()));
            }
        }

        List<NewsArticle> relatedNews = newsRepository.findRelatedPublished(
                news.getNewsId(),
                PageRequest.of(0, 3)
        );

        model.addAttribute("news", news);
        model.addAttribute("relatedNews", relatedNews);
        model.addAttribute("comments", comments);
        model.addAttribute("commentPage", commentPageResult);
        model.addAttribute("repliesByComment", repliesByComment);
        model.addAttribute("likeCounts", likeCounts);

        return "news/detail";
    }
}
