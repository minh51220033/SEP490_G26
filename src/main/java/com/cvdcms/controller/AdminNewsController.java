package com.cvdcms.controller;

import com.cvdcms.DTO.NewsCreateDTO;
import com.cvdcms.DTO.NewsUpdateDTO;
import com.cvdcms.entity.NewsArticle;
import com.cvdcms.entity.User;
import com.cvdcms.repository.CommentRepository;
import com.cvdcms.repository.NewsArticleRepository;
import com.cvdcms.service.impl.HtmlSanitizerService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
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

// IMPORT ĐÚNG CÁC LỚP XỬ LÝ FILE CỦA JAVA.NIO
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

//Quản lý /admin/news sửa lý CRUD new

@Controller
@RequestMapping("/admin/news")
public class AdminNewsController {

    private final NewsArticleRepository newsRepository;
    private final HtmlSanitizerService htmlSanitizerService;
    private final CommentRepository commentRepository;

    public AdminNewsController(
            NewsArticleRepository newsRepository,
            HtmlSanitizerService htmlSanitizerService,
            CommentRepository commentRepository) {
        this.newsRepository = newsRepository;
        this.htmlSanitizerService = htmlSanitizerService;
        this.commentRepository = commentRepository;
    }

    private boolean isAdmin(HttpSession session) {
        User user = (User) session.getAttribute("loggedInUser");
        return user != null && user.getRole() != null && "ADMIN".equals(user.getRole().getRoleName());
    }

    private User getLoggedInUser(HttpSession session) {
        return (User) session.getAttribute("loggedInUser");
    }

    @GetMapping
    public String listNews(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "0") int page,
            HttpSession session,
            Model model) {

        if (!isAdmin(session)) {
            if (getLoggedInUser(session) == null) return "redirect:/login";
            return "error/403";
        }

        if (size != 10 && size != 20 && size != 50) size = 10;
        if (page < 0) page = 0;

        Set<String> allowedSorts = Set.of("createdAt", "updatedAt", "publishedAt", "title");
        if (!allowedSorts.contains(sort)) sort = "createdAt";

        Sort.Direction sortDirection = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortDirection, sort));

        Page<NewsArticle> newsPage = newsRepository.searchAdmin(
                keyword == null ? "" : keyword.trim(),
                status == null ? "" : status,
                pageable
        );

        model.addAttribute("newsPage", newsPage);
        model.addAttribute("keyword", keyword);
        model.addAttribute("status", status);
        model.addAttribute("sort", sort);
        model.addAttribute("direction", direction);
        model.addAttribute("size", size);

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
        return "admin/news/create";
    }

    @PostMapping
    public String createNews(
            @Valid @ModelAttribute("newsCreateDTO") NewsCreateDTO dto,
            BindingResult bindingResult,
            HttpSession session) {

        if (!isAdmin(session)) {
            if (getLoggedInUser(session) == null) return "redirect:/login";
            return "error/403";
        }

        if (bindingResult.hasErrors()) return "admin/news/create";

        User admin = getLoggedInUser(session);
        NewsArticle news = new NewsArticle();
        news.setTitle(dto.getTitle().trim());
        news.setSlug(generateUniqueSlug(dto.getTitle()));
        news.setSummary(dto.getSummary() == null ? null : dto.getSummary().trim());
        news.setContent(htmlSanitizerService.sanitize(dto.getContent()));
        news.setThumbnail(dto.getThumbnail());

        String newsStatus = dto.getStatus();
        if (!"PUBLISHED".equals(newsStatus)) newsStatus = "DRAFT";
        news.setStatus(newsStatus);
        news.setCreatedBy(admin);

        if ("PUBLISHED".equals(newsStatus)) news.setPublishedAt(LocalDateTime.now());

        newsRepository.save(news);
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

        model.addAttribute("newsUpdateDTO", dto);
        return "admin/news/edit";
    }

    @PostMapping("/{id}")
    public String updateNews(
            @PathVariable Long id,
            @Valid @ModelAttribute("newsUpdateDTO") NewsUpdateDTO dto,
            BindingResult bindingResult,
            HttpSession session) {

        if (!isAdmin(session)) {
            if (getLoggedInUser(session) == null) return "redirect:/login";
            return "error/403";
        }

        NewsArticle news = newsRepository.findById(id).orElse(null);
        if (news == null) return "redirect:/admin/news";

        if (bindingResult.hasErrors()) return "admin/news/edit";

        news.setTitle(dto.getTitle().trim());
        if (!"PUBLISHED".equals(news.getStatus())) {
            news.setSlug(generateUniqueSlug(dto.getTitle(), news.getNewsId()));
        }
        news.setSummary(dto.getSummary() == null ? null : dto.getSummary().trim());
        news.setContent(htmlSanitizerService.sanitize(dto.getContent()));
        news.setThumbnail(dto.getThumbnail());

        String newStatus = dto.getStatus();
        if (!Set.of("DRAFT", "PUBLISHED", "ARCHIVED").contains(newStatus)) newStatus = "DRAFT";

        if ("PUBLISHED".equals(newStatus) && !"PUBLISHED".equals(news.getStatus())) {
            news.setPublishedAt(LocalDateTime.now());
        }
        news.setStatus(newStatus);

        newsRepository.save(news);
        session.setAttribute("newsSuccess", "Cập nhật bài viết thành công.");
        return "redirect:/admin/news";
    }

    @PostMapping("/upload/image")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> uploadImage(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "upload", required = false) MultipartFile upload) {

        Map<String, Object> response = new HashMap<>();
        try {
            // Lấy file từ 'file' hoặc 'upload' (đáp ứng cả CKEditor và Custom JS)
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

            // Trả về cả "url" (CKEditor) và "location" (TinyMCE)
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
    public String deleteNews(@PathVariable Long id, HttpSession session) {
        if (!isAdmin(session)) {
            if (getLoggedInUser(session) == null) return "redirect:/login";
            return "error/403";
        }

        NewsArticle news = newsRepository.findById(id).orElse(null);
        if (news != null) {
            commentRepository.deleteByNews_NewsId(news.getNewsId());
            newsRepository.delete(news);
            session.setAttribute("newsSuccess", "Xóa bài viết thành công.");
        }
        return "redirect:/admin/news";
    }

    private String generateUniqueSlug(String title) {
        return generateUniqueSlug(title, null);
    }

    private String generateUniqueSlug(String title, Long currentId) {
        String normalized = Normalizer.normalize(title, Normalizer.Form.NFD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        String slug = normalized.toLowerCase().replaceAll("đ", "d").replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (slug.isBlank()) slug = "news";

        String originalSlug = slug;
        int counter = 1;
        while (true) {
            boolean exists = newsRepository.existsBySlug(slug);
            if (!exists) return slug;

            NewsArticle existing = newsRepository.findBySlug(slug).orElse(null);
            if (currentId != null && existing != null && existing.getNewsId().equals(currentId)) return slug;

            counter++;
            slug = originalSlug + "-" + counter;
        }
    }
}