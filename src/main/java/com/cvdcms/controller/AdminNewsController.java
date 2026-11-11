package com.cvdcms.controller;

import com.cvdcms.DTO.NewsCreateDTO;
import com.cvdcms.DTO.NewsUpdateDTO;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.NewsArticle;
import com.cvdcms.entity.User;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.CommentRepository;
import com.cvdcms.repository.NewsArticleRepository;
import com.cvdcms.service.SystemAuditLogService;
import com.cvdcms.service.impl.HtmlSanitizerService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RequiredArgsConstructor
@Controller
@RequestMapping("/admin/news")
public class AdminNewsController {

    private final NewsArticleRepository newsRepository;
    private final HtmlSanitizerService htmlSanitizerService;
    private final CommentRepository commentRepository;
    private final BranchRepository branchRepository;
    private final SystemAuditLogService auditLogService;

    private boolean isAdmin(HttpSession session) {
        User user = getLoggedInUser(session);
        if (user == null) return false;
        if (user.getRoles() != null && user.getRoles().stream().anyMatch(role -> "ADMIN".equals(role.getRoleName()) || "ROLE_ADMIN".equals(role.getRoleName()))) {
            return true;
        }
        Object permsObj = session != null ? session.getAttribute("permissionCodes") : null;
        if (permsObj instanceof java.util.Collection<?> cols) {
            return cols.stream().anyMatch(p -> "CATALOG_NEWS".equalsIgnoreCase(String.valueOf(p)));
        }
        return false;
    }

    @GetMapping
    public String listNews(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Long branchId,
            HttpSession session,
            Model model) {

        if (!isAdmin(session)) {
            if (getLoggedInUser(session) == null) return "redirect:/login";
            return "error/403";
        }

        if (size != 10 && size != 25 && size != 50 && size != 100) size = 10;
        if (page < 0) page = 0;

        Set<String> allowedSorts = Set.of("createdAt", "updatedAt", "publishedAt", "title");
        if (!allowedSorts.contains(sort)) sort = "createdAt";

        Sort.Direction sortDirection = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortDirection, sort));

        Page<NewsArticle> newsPage = newsRepository.searchAdmin(
                keyword == null ? "" : keyword.trim(),
                status == null ? "" : status,
                branchId,
                pageable
        );

        long totalItems = newsPage.getTotalElements();
        int currentPage = newsPage.getNumber();
        int pageSize = newsPage.getSize();
        int totalPages = newsPage.getTotalPages();
        long fromItem = totalItems == 0 ? 0 : ((long) currentPage * pageSize) + 1;
        long toItem = Math.min(((long) (currentPage + 1) * pageSize), totalItems);

        long publishedCount = newsRepository.countByStatus("PUBLISHED");
        long draftCount = newsRepository.countByStatus("DRAFT");
        long archivedCount = newsRepository.countByStatus("ARCHIVED");
        long totalCommentsCount = commentRepository.count();

        model.addAttribute("newsPage", newsPage);
        model.addAttribute("newsList", newsPage.getContent());
        model.addAttribute("totalItems", totalItems);
        model.addAttribute("publishedCount", publishedCount);
        model.addAttribute("draftCount", draftCount);
        model.addAttribute("archivedCount", archivedCount);
        model.addAttribute("totalCommentsCount", totalCommentsCount);
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("fromItem", fromItem);
        model.addAttribute("toItem", toItem);
        model.addAttribute("keyword", keyword);
        model.addAttribute("status", status);
        model.addAttribute("sort", sort);
        model.addAttribute("direction", direction);
        model.addAttribute("branchId", branchId);

        model.addAttribute(
                "branches",
                branchRepository.findAllByOrderByBranchNameAsc()
        );

        return "admin/news/list";
    }

    @GetMapping("/{id}")
    public String viewNewsDetailAdmin(@PathVariable Long id, HttpSession session) {
        if (!isAdmin(session)) {
            if (getLoggedInUser(session) == null) return "redirect:/login";
            return "error/403";
        }

        NewsArticle news = newsRepository.findById(id).orElse(null);
        if (news == null) {
            return "redirect:/admin/news";
        }

        return "redirect:/news/" + news.getSlug();
    }

    @GetMapping("/create")
    public String showCreateForm(HttpSession session, Model model) {
        if (!isAdmin(session)) {
            if (getLoggedInUser(session) == null) return "redirect:/login";
            return "error/403";
        }
        model.addAttribute("newsCreateDTO", new NewsCreateDTO());
        model.addAttribute(
                "branches",
                branchRepository.findByIsActiveTrueOrderByBranchNameAsc()
        );

        return "admin/news/create";
    }

    @PostMapping
    public String createNews(
            @Valid @ModelAttribute("newsCreateDTO") NewsCreateDTO dto,
            BindingResult bindingResult,
            HttpServletRequest request,
            HttpSession session,
            Model model) {

        if (!isAdmin(session)) {
            if (getLoggedInUser(session) == null) {
                return "redirect:/login";
            }
            return "error/403";
        }

        if (bindingResult.hasErrors()) {
            model.addAttribute(
                    "branches",
                    branchRepository.findByIsActiveTrueOrderByBranchNameAsc()
            );
            return "admin/news/create";
        }

        Branch branch = branchRepository.findById(dto.getBranchId())
                .filter(b -> Boolean.TRUE.equals(b.getIsActive()))
                .orElse(null);

        if (branch == null) {
            bindingResult.rejectValue(
                    "branchId",
                    "branchId.invalid",
                    "Chi nhánh không tồn tại hoặc đã ngừng hoạt động."
            );

            model.addAttribute(
                    "branches",
                    branchRepository.findByIsActiveTrueOrderByBranchNameAsc()
            );
            return "admin/news/create";
        }

        User admin = getLoggedInUser(session);

        NewsArticle news = new NewsArticle();
        news.setTitle(dto.getTitle().trim());
        news.setSlug(generateUniqueSlug(dto.getTitle()));
        news.setSummary(
                dto.getSummary() == null ? null : dto.getSummary().trim()
        );
        news.setContent(htmlSanitizerService.sanitize(dto.getContent()));
        news.setThumbnail(dto.getThumbnail());
        news.setBranch(branch);
        news.setCreatedBy(admin);

        String newsStatus = "PUBLISHED".equals(dto.getStatus())
                ? "PUBLISHED"
                : "DRAFT";

        news.setStatus(newsStatus);

        if ("PUBLISHED".equals(newsStatus)) {
            news.setPublishedAt(LocalDateTime.now());
        }

        newsRepository.save(news);

        // Ghi nhận Audit Log (FT31)
        auditLogService.logAction(
                admin,
                branch,
                "CREATE_NEWS",
                "news_articles",
                news.getNewsId(),
                request.getRemoteAddr(),
                null,
                Map.of("title", news.getTitle(), "status", news.getStatus(), "branch", branch.getBranchName())
        );

        session.setAttribute("newsSuccess", "Tạo bài viết thành công.");
        return "redirect:/admin/news";
    }

    @GetMapping("/{id}/edit")
    public String showEditForm(@PathVariable Long id, HttpSession session, Model model) {
        if (!isAdmin(session)) {
            if (getLoggedInUser(session) == null) return "redirect:/login";
            return "error/403";
        }

        NewsArticle news = newsRepository.findById(id).orElse(null);
        if (news == null) return "redirect:/admin/news";

        NewsUpdateDTO dto = new NewsUpdateDTO();
        dto.setNewsId(news.getNewsId());
        dto.setTitle(news.getTitle());
        dto.setSummary(news.getSummary());
        dto.setContent(news.getContent());
        dto.setThumbnail(news.getThumbnail());
        dto.setStatus(news.getStatus());

        dto.setBranchId(
                news.getBranch() != null
                        ? news.getBranch().getBranchId()
                        : null
        );

        model.addAttribute("newsUpdateDTO", dto);

        model.addAttribute(
                "branches",
                branchRepository.findByIsActiveTrueOrderByBranchNameAsc()
        );

        return "admin/news/edit";
    }

    @PostMapping("/{id}")
    public String updateNews(
            @PathVariable Long id,
            @Valid @ModelAttribute("newsUpdateDTO") NewsUpdateDTO dto,
            BindingResult bindingResult,
            HttpServletRequest request,
            HttpSession session,
            Model model) {

        if (!isAdmin(session)) {
            if (getLoggedInUser(session) == null) {
                return "redirect:/login";
            }
            return "error/403";
        }

        NewsArticle news = newsRepository.findById(id).orElse(null);

        if (news == null) {
            return "redirect:/admin/news";
        }

        if (bindingResult.hasErrors()) {
            model.addAttribute(
                    "branches",
                    branchRepository.findByIsActiveTrueOrderByBranchNameAsc()
            );
            return "admin/news/edit";
        }

        if (dto.getBranchId() == null) {
            bindingResult.rejectValue(
                    "branchId",
                    "branchId.required",
                    "Vui lòng chọn chi nhánh."
            );
            model.addAttribute(
                    "branches",
                    branchRepository.findByIsActiveTrueOrderByBranchNameAsc()
            );
            return "admin/news/edit";
        }

        Branch branch = branchRepository.findById(dto.getBranchId())
                .filter(b -> Boolean.TRUE.equals(b.getIsActive()))
                .orElse(null);

        if (branch == null) {
            bindingResult.rejectValue(
                    "branchId",
                    "branchId.invalid",
                    "Chi nhánh không tồn tại hoặc đã ngừng hoạt động."
            );
            model.addAttribute(
                    "branches",
                    branchRepository.findByIsActiveTrueOrderByBranchNameAsc()
            );
            return "admin/news/edit";
        }

        Set<String> allowedStatuses =
                Set.of("DRAFT", "PUBLISHED", "ARCHIVED");

        String newStatus = dto.getStatus();

        if (newStatus == null || !allowedStatuses.contains(newStatus)) {
            bindingResult.rejectValue(
                    "status",
                    "status.invalid",
                    "Trạng thái bài viết không hợp lệ."
            );
            model.addAttribute(
                    "branches",
                    branchRepository.findByIsActiveTrueOrderByBranchNameAsc()
            );
            return "admin/news/edit";
        }

        String oldTitle = news.getTitle();
        String oldStatus = news.getStatus();

        news.setTitle(dto.getTitle().trim());

        if (!"PUBLISHED".equals(news.getStatus())) {
            news.setSlug(generateUniqueSlug(
                    dto.getTitle(),
                    news.getNewsId()
            ));
        }

        news.setSummary(
                dto.getSummary() == null ? null : dto.getSummary().trim()
        );
        news.setContent(
                htmlSanitizerService.sanitize(dto.getContent())
        );
        news.setThumbnail(dto.getThumbnail());
        news.setBranch(branch);

        if ("PUBLISHED".equals(newStatus)
                && news.getPublishedAt() == null) {
            news.setPublishedAt(LocalDateTime.now());
        }

        news.setStatus(newStatus);
        newsRepository.save(news);

        // Ghi nhận Audit Log (FT31)
        User admin = getLoggedInUser(session);
        auditLogService.logAction(
                admin,
                branch,
                "UPDATE_NEWS",
                "news_articles",
                news.getNewsId(),
                request.getRemoteAddr(),
                Map.of("title", oldTitle, "status", oldStatus),
                Map.of("title", news.getTitle(), "status", newStatus, "branch", branch.getBranchName())
        );

        session.setAttribute(
                "newsSuccess",
                "Cập nhật bài viết thành công."
        );

        return "redirect:/admin/news";
    }

    @PostMapping("/upload/image")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> uploadImage(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "upload", required = false) MultipartFile upload,
            HttpSession session) {

        if (!isAdmin(session)) {
            Map<String, Object> error = new HashMap<>();
            error.put("message", "Bạn không có quyền tải ảnh lên.");

            return ResponseEntity.status(
                    getLoggedInUser(session) == null ? 401 : 403
            ).body(error);
        }

        Map<String, Object> response = new HashMap<>();
        try {
            MultipartFile multipartFile = (file != null && !file.isEmpty()) ? file : upload;

            if (multipartFile == null || multipartFile.isEmpty()) {
                response.put("error", Map.of("message", "File không hợp lệ hoặc bị rỗng."));
                return ResponseEntity.badRequest().body(response);
            }

            String fileName = UUID.randomUUID().toString() + "_" + multipartFile.getOriginalFilename();
            Path path = Paths.get("uploads/news/" + fileName);

            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }

            Files.copy(multipartFile.getInputStream(), path, StandardCopyOption.REPLACE_EXISTING);

            String fileUrl = "/uploads/news/" + fileName;

            response.put("url", fileUrl);
            response.put("location", fileUrl);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, String> errorDetails = new HashMap<>();
            errorDetails.put("message", "Lỗi lưu file: " + e.getMessage());
            response.put("error", errorDetails);
            return ResponseEntity.status(500).body(response);
        }
    }

    @PostMapping("/{id}/delete")
    @Transactional
    public String deleteNews(
            @PathVariable Long id,
            HttpServletRequest request,
            HttpSession session) {
        if (!isAdmin(session)) {
            if (getLoggedInUser(session) == null) return "redirect:/login";
            return "error/403";
        }

        NewsArticle news = newsRepository.findById(id).orElse(null);
        if (news != null) {
            User admin = getLoggedInUser(session);
            commentRepository.deleteByNews_NewsId(news.getNewsId());
            newsRepository.delete(news);

            // Ghi nhận Audit Log (FT31)
            auditLogService.logAction(
                    admin,
                    news.getBranch(),
                    "DELETE_NEWS",
                    "news_articles",
                    news.getNewsId(),
                    request.getRemoteAddr(),
                    Map.of("title", news.getTitle(), "slug", news.getSlug()),
                    null
            );

            session.setAttribute("newsSuccess", "Xóa bài viết thành công.");
        }
        return "redirect:/admin/news";
    }

    private String generateUniqueSlug(String title) {
        return generateUniqueSlug(title, null);
    }

    private String generateUniqueSlug(String title, Long currentId) {
        String normalized = Normalizer
                .normalize(title, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");

        String slug = normalized
                .replace("đ", "d")
                .replace("Đ", "D")
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");

        if (slug.isBlank()) {
            slug = "news";
        }

        String originalSlug = slug;
        int counter = 1;

        while (true) {
            NewsArticle existing = newsRepository.findBySlug(slug)
                    .orElse(null);

            if (existing == null) {
                return slug;
            }

            if (currentId != null
                    && existing.getNewsId().equals(currentId)) {
                return slug;
            }

            counter++;
            slug = originalSlug + "-" + counter;
        }
    }

    private User getLoggedInUser(HttpSession session) {
        return (User) session.getAttribute("loggedInUser");
    }
}
