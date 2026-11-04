package com.cvdcms.controller;

import com.cvdcms.entity.*;
import com.cvdcms.repository.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

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
    private final NewsArticleLikeRepository newsArticleLikeRepository;

    public NewsController(
            NewsArticleRepository newsRepository,
            CommentRepository commentRepository,
            CommentLikeRepository commentLikeRepository,
            CommentBanRepository commentBanRepository,
            BranchRepository branchRepository,
            UserRepository userRepository,
            PatientProfileRepository patientProfileRepository,
            NewsArticleLikeRepository newsArticleLikeRepository) {

        this.newsRepository = newsRepository;
        this.commentRepository = commentRepository;
        this.commentLikeRepository = commentLikeRepository;
        this.commentBanRepository = commentBanRepository;
        this.branchRepository = branchRepository;
        this.userRepository = userRepository;
        this.patientProfileRepository = patientProfileRepository;
        this.newsArticleLikeRepository = newsArticleLikeRepository;
    }

    // =====================================================
    // 1. TRANG DANH SÁCH TIN TỨC CÔNG KHAI (GET /news)
    // Cho phép khách vãng lai và tất cả người dùng (Admin/Bác sĩ/Nhân viên/Bệnh nhân)
    // =====================================================
    @GetMapping({"", "/"})
    public String publicNewsList(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "6") int size,
            HttpSession session,
            Model model) {

        page = Math.max(page, 0);
        if (size != 6 && size != 12 && size != 18) {
            size = 6;
        }

        // Tải danh sách tất cả cơ sở đang hoạt động để người dùng lọc
        List<Branch> activeBranches = branchRepository.findByIsActiveTrueOrderByBranchNameAsc();
        model.addAttribute("branches", activeBranches);

        User sessionUser = (User) session.getAttribute("loggedInUser");
        Long targetBranchId = branchId;

        // Nếu người dùng không chỉ định branchId cụ thể và đang đăng nhập:
        // Cố gắng lấy chi nhánh mặc định của bệnh nhân hoặc cơ sở của nhân viên
        if (targetBranchId == null && sessionUser != null) {
            User currentUser = userRepository.findById(sessionUser.getUserId()).orElse(null);
            if (currentUser != null) {
                // Thử tìm theo hồ sơ bệnh nhân mặc định
                PatientProfile profile = patientProfileRepository
                        .findFirstByUser_UserIdAndIsDefaultTrue(currentUser.getUserId())
                        .orElse(null);

                if (profile != null && profile.getPreferredBranch() != null) {
                    targetBranchId = profile.getPreferredBranch().getBranchId();
                } else if (currentUser.getBranch() != null) {
                    targetBranchId = currentUser.getBranch().getBranchId();
                }
            }
        }

        // Lấy thông tin cơ sở hiển thị
        String currentBranchName = "Tất cả cơ sở";
        String currentBranchAddress = null;
        if (targetBranchId != null) {
            for (Branch b : activeBranches) {
                if (b.getBranchId().equals(targetBranchId)) {
                    currentBranchName = b.getBranchName();
                    currentBranchAddress = b.getAddress();
                    break;
                }
            }
        }

        String searchKeyword = keyword == null ? "" : keyword.trim();
        Pageable pageable = PageRequest.of(page, size);

        // Truy vấn tin tức đã xuất bản (hỗ trợ targetBranchId == null để xem tất cả)
        Page<NewsArticle> newsPage = newsRepository.searchPublishedByBranch(
                searchKeyword,
                targetBranchId,
                pageable
        );

        model.addAttribute("newsPage", newsPage);
        model.addAttribute("keyword", searchKeyword);
        model.addAttribute("size", size);
        model.addAttribute("currentBranchName", currentBranchName);
        model.addAttribute("currentBranchAddress", currentBranchAddress);
        model.addAttribute("currentBranchId", targetBranchId);

        return "news/list";
    }

    // =====================================================
    // 2. TRANG CHI TIẾT BÀI VIẾT (GET /news/{slug})
    // Tối ưu N+1 Query và bảo toàn cây bình luận con
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

        // Lấy trạng thái Like của bài viết (FT38 SCR-38.3)
        long articleLikesCount = newsArticleLikeRepository.countByNews_NewsId(news.getNewsId());
        boolean isArticleLiked = loggedInUser != null && newsArticleLikeRepository.existsByNews_NewsIdAndUser_UserId(news.getNewsId(), loggedInUser.getUserId());
        model.addAttribute("articleLikesCount", articleLikesCount);
        model.addAttribute("isArticleLiked", isArticleLiked);

        // Tải bình luận gốc (bao gồm cả ACTIVE và DELETED có replies)
        Pageable commentPageable = PageRequest.of(Math.max(commentPage, 0), 10, Sort.by(Sort.Direction.ASC, "createdAt"));
        Page<Comment> commentPageResult = commentRepository.findByNews_NewsIdAndParentCommentIsNull(news.getNewsId(), commentPageable);
        List<Comment> rawComments = commentPageResult.getContent();

        List<Long> rootIds = rawComments.stream().map(Comment::getCommentId).toList();
        Map<Long, List<Comment>> repliesByComment = new HashMap<>();
        List<Long> allCommentIds = new ArrayList<>(rootIds);

        if (!rootIds.isEmpty()) {
            List<Comment> allReplies = commentRepository.findByParentComment_CommentIdInOrderByCreatedAtAsc(rootIds);
            for (Comment reply : allReplies) {
                if ("ACTIVE".equals(reply.getStatus())) {
                    repliesByComment.computeIfAbsent(reply.getParentComment().getCommentId(), k -> new ArrayList<>()).add(reply);
                    allCommentIds.add(reply.getCommentId());
                }
            }
        }

        // Lọc danh sách comment gốc hiển thị: đang ACTIVE, hoặc đã DELETED nhưng có câu trả lời bên dưới
        List<Comment> displayComments = rawComments.stream()
                .filter(c -> "ACTIVE".equals(c.getStatus()) || repliesByComment.containsKey(c.getCommentId()))
                .toList();

        // Tối ưu N+1 Query: Đếm số Like và kiểm tra user đã like chưa theo lô (Batch query)
        Map<Long, Long> likeCounts = new HashMap<>();
        Set<Long> likedCommentIds = new HashSet<>();

        if (!allCommentIds.isEmpty()) {
            List<Object[]> countResults = commentLikeRepository.countLikesGroupedByCommentIds(allCommentIds);
            for (Object[] row : countResults) {
                Long commentId = (Long) row[0];
                Long count = (Long) row[1];
                likeCounts.put(commentId, count);
            }

            if (loggedInUser != null) {
                List<Long> userLikedList = commentLikeRepository.findLikedCommentIdsByUser(allCommentIds, loggedInUser.getUserId());
                likedCommentIds.addAll(userLikedList);
            }
        }

        model.addAttribute("news", news);
        model.addAttribute("comments", displayComments);
        model.addAttribute("commentPage", commentPageResult);
        model.addAttribute("repliesByComment", repliesByComment);
        model.addAttribute("likeCounts", likeCounts);
        model.addAttribute("likedCommentIds", likedCommentIds);

        return "news/detail";
    }

    // =====================================================
    // 3. THÍCH / BỎ THÍCH BÀI VIẾT (POST /news/{id}/like)
    // FT38 - SCR-38.3
    // =====================================================
    @PostMapping("/{id}/like")
    @Transactional
    public String toggleArticleLike(@PathVariable Long id, HttpSession session) {
        User user = (User) session.getAttribute("loggedInUser");
        if (user == null) {
            return "redirect:/login";
        }

        NewsArticle news = newsRepository.findById(id).orElse(null);
        if (news == null || !"PUBLISHED".equals(news.getStatus())) {
            return "redirect:/news";
        }

        // Kiểm tra xem tài khoản có đang bị ban tương tác hay không
        boolean isBanned = commentBanRepository
                .findFirstByUser_UserIdAndActiveTrueOrderByBannedAtDesc(user.getUserId())
                .map(ban -> {
                    if (ban.getExpiresAt() != null && !ban.getExpiresAt().isAfter(LocalDateTime.now())) {
                        ban.setActive(false);
                        commentBanRepository.save(ban);
                        return false;
                    }
                    return true;
                }).orElse(false);

        if (isBanned) {
            session.setAttribute("commentError", "Tài khoản của bạn đang bị hạn chế tương tác.");
            return "redirect:/news/" + news.getSlug();
        }

        try {
            var existingLike = newsArticleLikeRepository.findByNews_NewsIdAndUser_UserId(id, user.getUserId());
            if (existingLike.isPresent()) {
                newsArticleLikeRepository.delete(existingLike.get());
            } else {
                NewsArticleLike newLike = new NewsArticleLike();
                newLike.setNews(news);
                newLike.setUser(user);
                newsArticleLikeRepository.save(newLike);
            }
        } catch (DataIntegrityViolationException ignored) {
            // Tránh đụng độ race condition khi click nhanh
        }

        return "redirect:/news/" + news.getSlug();
    }
}
