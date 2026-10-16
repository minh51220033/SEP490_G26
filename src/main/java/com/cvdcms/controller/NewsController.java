package com.cvdcms.controller;

import com.cvdcms.entity.*;
import com.cvdcms.repository.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.cvdcms.entity.NewsArticle;


import java.time.LocalDateTime;
import java.util.*;

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
            UserRepository userRepository,                     // Thêm dòng này
            PatientProfileRepository patientProfileRepository) { // Thêm dòng này

        this.newsRepository = newsRepository;
        this.commentRepository = commentRepository;
        this.commentLikeRepository = commentLikeRepository;
        this.commentBanRepository = commentBanRepository;
        this.branchRepository = branchRepository;
        this.userRepository = userRepository;                   // Thêm dòng này
        this.patientProfileRepository = patientProfileRepository; // Thêm dòng này
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
            HttpSession session,
            Model model) {

        page = Math.max(page, 0);

        if (size != 6 && size != 12 && size != 18) {
            size = 6;
        }
        

        // 1. Lấy tài khoản đang đăng nhập từ session.
        User sessionUser = (User) session.getAttribute("loggedInUser");

        if (sessionUser == null) {
            return "redirect:/login";
        }

        // 2. Tải lại tài khoản từ database.
        User user = userRepository.findById(sessionUser.getUserId())
                .orElse(null);

        if (user == null) {
            session.invalidate();
            return "redirect:/login";
        }

        // 3. Lấy hồ sơ bệnh nhân mặc định của tài khoản.
        // Giả định repository trả về Optional<PatientProfile>.
        PatientProfile profile = patientProfileRepository
                .findFirstByUser_UserIdAndIsDefaultTrue(user.getUserId())
                .orElse(null);

        if (profile == null || profile.getPreferredBranch() == null) {
            model.addAttribute(
                    "errorMessage",
                    "Tài khoản chưa có hồ sơ mặc định hoặc chưa được gán cơ sở."
            );
            model.addAttribute("newsPage", Page.empty());
            model.addAttribute("keyword", keyword.trim());
            model.addAttribute("size", size);
            model.addAttribute("currentBranchName", "Chưa xác định cơ sở");

            return "news/list";
        }

        // 4. Lấy đúng cơ sở của tài khoản.
        Branch branch = profile.getPreferredBranch();
        Long currentBranchId = branch.getBranchId();

        // 5. Chỉ truy vấn tin tức thuộc cơ sở hiện tại.
        String searchKeyword = keyword == null ? "" : keyword.trim();

        Pageable pageable = PageRequest.of(page, size);

        Page<NewsArticle> newsPage =
                newsRepository.searchPublishedByBranch(
                        searchKeyword,
                        currentBranchId,
                        pageable
                );

        // 6. Truyền dữ liệu cho giao diện.
        model.addAttribute("newsPage", newsPage);
        model.addAttribute("keyword", searchKeyword);
        model.addAttribute("size", size);
        model.addAttribute("currentBranchName", branch.getBranchName());
        model.addAttribute("currentBranchAddress", branch.getAddress());
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