package com.cvdcms.controller;

import com.cvdcms.DTO.ServiceCategoryDTO;
import com.cvdcms.DTO.ServiceCreateDTO;
import com.cvdcms.DTO.ServiceTechnicalConfigDTO;
import com.cvdcms.DTO.ServiceUpdateDTO;

import com.cvdcms.entity.Service;
import com.cvdcms.entity.ServiceCategory;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.ServiceStatus;
import com.cvdcms.entity.enums.ServiceType;

import com.cvdcms.service.ServiceCatalogService;
import com.cvdcms.repository.UserRepository;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Controller
@RequestMapping("/admin/services")
public class AdminServiceCatalogController {

        private static final String LIST_VIEW = "admin/services/list";

        private static final String CATEGORY_FORM_VIEW = "admin/services/category-form";

        private static final String FORM_VIEW = "admin/services/form";

        private static final String CONFIG_VIEW = "admin/services/technical-config";

        private static final String REDIRECT_LIST = "redirect:/admin/services";

        private static final String DELETE_TOKEN_SESSION_KEY = "serviceCatalogDeleteToken";

        private final ServiceCatalogService serviceCatalogService;
        private final UserRepository userRepository;

        public AdminServiceCatalogController(
                        ServiceCatalogService serviceCatalogService,
                        UserRepository userRepository) {
                this.serviceCatalogService = serviceCatalogService;
                this.userRepository = userRepository;
        }

        // =====================================================
        // 1. DANH SÁCH DỊCH VỤ VÀ NHÓM DANH MỤC
        // =====================================================

        @GetMapping
        public String listServices(
                        @RequestParam(required = false) Long categoryId,
                        Model model,
                        HttpSession session,
                        RedirectAttributes redirectAttributes) {
                try {
                        prepareListPage(model, session, categoryId);
                        return LIST_VIEW;
                } catch (IllegalArgumentException ex) {
                        redirectAttributes.addFlashAttribute(
                                        "errorMessage",
                                        ex.getMessage());

                        return REDIRECT_LIST;
                }
        }

        // =====================================================
        // 2. THÊM NHÓM DANH MỤC
        // =====================================================

        @GetMapping("/categories/create")
        public String showCreateCategoryForm(
                        Model model,
                        HttpSession session) {
                model.addAttribute("categoryForm", new ServiceCategoryDTO());
                prepareCategoryFormPage(model, session, null);
                return CATEGORY_FORM_VIEW;
        }

        @PostMapping("/categories")
        public String createCategory(
                        @Valid @ModelAttribute("categoryForm") ServiceCategoryDTO dto,
                        BindingResult bindingResult,
                        @RequestParam(value = "source", required = false) String source,
                        Model model,
                        HttpSession session,
                        RedirectAttributes redirectAttributes) {
                if (bindingResult.hasErrors()) {
                        if ("standalone".equals(source)) {
                                prepareCategoryFormPage(model, session, null);
                                return CATEGORY_FORM_VIEW;
                        }
                        redirectAttributes.addFlashAttribute(
                                        "org.springframework.validation.BindingResult.categoryForm",
                                        bindingResult);
                        redirectAttributes.addFlashAttribute("categoryForm", dto);
                        redirectAttributes.addFlashAttribute(
                                        "errorMessage",
                                        bindingResult.getAllErrors().get(0).getDefaultMessage());
                        redirectAttributes.addFlashAttribute("openCategoryModal", true);
                        return REDIRECT_LIST;
                }

                try {
                        serviceCatalogService.createCategory(dto);

                        redirectAttributes.addFlashAttribute(
                                        "successMessage",
                                        "Th\u00EAm nh\u00F3m d\u1ECBch v\u1EE5 th\u00E0nh c\u00F4ng.");

                        return REDIRECT_LIST;
                } catch (IllegalArgumentException ex) {
                        if ("standalone".equals(source)) {
                                bindingResult.reject(
                                                "category.create.failed",
                                                ex.getMessage());
                                prepareCategoryFormPage(model, session, null);
                                return CATEGORY_FORM_VIEW;
                        }
                        redirectAttributes.addFlashAttribute("categoryForm", dto);
                        redirectAttributes.addFlashAttribute(
                                        "errorMessage",
                                        ex.getMessage());
                        redirectAttributes.addFlashAttribute("openCategoryModal", true);
                        return REDIRECT_LIST;
                }
        }

        // =====================================================
        // 3. MỞ FORM SỬA NHÓM DANH MỤC
        // =====================================================

        @GetMapping("/categories/{categoryId}/edit")
        public String editCategory(
                        @PathVariable Long categoryId,
                        Model model,
                        HttpSession session,
                        RedirectAttributes redirectAttributes) {
                try {
                        ServiceCategory category = serviceCatalogService.getCategoryById(
                                        categoryId);

                        ServiceCategoryDTO dto = new ServiceCategoryDTO();

                        dto.setCategoryCode(category.getCategoryCode());
                        dto.setCategoryName(category.getCategoryName());
                        dto.setCategoryType(category.getCategoryType());
                        dto.setDisplayOrder(category.getDisplayOrder());
                        dto.setIsActive(category.getIsActive());

                        model.addAttribute("categoryForm", dto);

                        prepareCategoryFormPage(model, session, categoryId);

                        return CATEGORY_FORM_VIEW;
                } catch (IllegalArgumentException ex) {
                        redirectAttributes.addFlashAttribute(
                                        "errorMessage",
                                        ex.getMessage());

                        return REDIRECT_LIST;
                }
        }

        // =====================================================
        // 4. CẬP NHẬT NHÓM DANH MỤC
        // =====================================================

        @PostMapping("/categories/{categoryId}/update")
        public String updateCategory(
                        @PathVariable Long categoryId,

                        @Valid @ModelAttribute("categoryForm") ServiceCategoryDTO dto,

                        BindingResult bindingResult,
                        Model model,
                        HttpSession session,
                        RedirectAttributes redirectAttributes) {
                // Kiểm tra nhóm tồn tại trước khi xử lý form.
                try {
                        serviceCatalogService.getCategoryById(categoryId);
                } catch (IllegalArgumentException ex) {
                        redirectAttributes.addFlashAttribute(
                                        "errorMessage",
                                        ex.getMessage());

                        return REDIRECT_LIST;
                }

                if (!bindingResult.hasErrors()) {
                        try {
                                serviceCatalogService.updateCategory(
                                                categoryId,
                                                dto);

                                redirectAttributes.addFlashAttribute(
                                                "successMessage",
                                                "C\u1EADp nh\u1EADt nh\u00F3m d\u1ECBch v\u1EE5 th\u00E0nh c\u00F4ng.");

                                return REDIRECT_LIST;
                        } catch (IllegalArgumentException ex) {
                                bindingResult.reject(
                                                "category.update.failed",
                                                ex.getMessage());
                        }
                }

                prepareCategoryFormPage(model, session, categoryId);

                return CATEGORY_FORM_VIEW;
        }

        // =====================================================
        // 5. BẬT / TẮT NHÓM DANH MỤC
        // =====================================================

        @PostMapping("/categories/{categoryId}/toggle")
        public String toggleCategoryStatus(
                        @PathVariable Long categoryId,
                        RedirectAttributes redirectAttributes) {
                try {
                        serviceCatalogService.toggleCategoryStatus(
                                        categoryId);

                        redirectAttributes.addFlashAttribute(
                                        "successMessage",
                                        "\u0110\u00E3 thay \u0111\u1ED5i tr\u1EA1ng th\u00E1i nh\u00F3m d\u1ECBch v\u1EE5.");
                } catch (IllegalArgumentException ex) {
                        redirectAttributes.addFlashAttribute(
                                        "errorMessage",
                                        ex.getMessage());
                }

                return REDIRECT_LIST;
        }

        @PostMapping("/categories/{categoryId}/delete")
        public String deleteCategory(
                        @PathVariable Long categoryId,
                        @RequestParam(required = false) String deleteToken,
                        HttpSession session,
                        RedirectAttributes redirectAttributes) {
                requireDeletePermission(session, deleteToken);

                try {
                        serviceCatalogService.deleteCategory(categoryId);
                        redirectAttributes.addFlashAttribute(
                                        "successMessage", "Xóa nhóm dịch vụ thành công.");
                } catch (IllegalArgumentException ex) {
                        redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
                }

                return REDIRECT_LIST;
        }

        // =====================================================
        // 6. MỞ FORM THÊM DỊCH VỤ
        // =====================================================

        @GetMapping("/create")
        public String showCreateServiceForm(
                        Model model,
                        HttpSession session) {
                model.addAttribute(
                                "serviceForm",
                                new ServiceCreateDTO());

                prepareServiceFormPage(model, session, null);

                return FORM_VIEW;
        }

        // =====================================================
        // 7. LƯU DỊCH VỤ MỚI
        // =====================================================

        @PostMapping
        public String createService(
                        @Valid @ModelAttribute("serviceForm") ServiceCreateDTO dto,
                        BindingResult bindingResult,
                        @RequestParam(value = "source", required = false) String source,
                        Model model,
                        HttpSession session,
                        RedirectAttributes redirectAttributes) {
                if (bindingResult.hasErrors()) {
                        if ("standalone".equals(source)) {
                                prepareServiceFormPage(model, session, null);
                                return FORM_VIEW;
                        }
                        redirectAttributes.addFlashAttribute(
                                        "org.springframework.validation.BindingResult.serviceForm",
                                        bindingResult);
                        redirectAttributes.addFlashAttribute("serviceForm", dto);
                        redirectAttributes.addFlashAttribute(
                                        "errorMessage",
                                        bindingResult.getAllErrors().get(0).getDefaultMessage());
                        redirectAttributes.addFlashAttribute("openServiceModal", true);
                        return REDIRECT_LIST;
                }

                try {
                        serviceCatalogService.createService(dto);

                        redirectAttributes.addFlashAttribute(
                                        "successMessage",
                                        "Th\u00EAm d\u1ECBch v\u1EE5 th\u00E0nh c\u00F4ng.");

                        return REDIRECT_LIST;
                } catch (IllegalArgumentException ex) {
                        if ("standalone".equals(source)) {
                                bindingResult.reject(
                                                "service.create.failed",
                                                ex.getMessage());
                                prepareServiceFormPage(model, session, null);
                                return FORM_VIEW;
                        }
                        redirectAttributes.addFlashAttribute("serviceForm", dto);
                        redirectAttributes.addFlashAttribute(
                                        "errorMessage",
                                        ex.getMessage());
                        redirectAttributes.addFlashAttribute("openServiceModal", true);
                        return REDIRECT_LIST;
                }
        }

        // =====================================================
        // 8. MỞ FORM SỬA DỊCH VỤ
        // =====================================================

        @GetMapping("/{serviceId}/edit")
        public String showUpdateServiceForm(
                        @PathVariable Long serviceId,
                        Model model,
                        HttpSession session,
                        RedirectAttributes redirectAttributes) {
                try {
                        Service service = serviceCatalogService.getServiceById(
                                        serviceId);

                        ServiceUpdateDTO dto = serviceCatalogService.getServiceUpdateForm(
                                        serviceId);

                        model.addAttribute("serviceForm", dto);

                        prepareServiceFormPage(
                                        model,
                                        session,
                                        service);

                        return FORM_VIEW;
                } catch (IllegalArgumentException ex) {
                        redirectAttributes.addFlashAttribute(
                                        "errorMessage",
                                        ex.getMessage());

                        return REDIRECT_LIST;
                }
        }

        // =====================================================
        // 9. CẬP NHẬT DỊCH VỤ VÀ GIÁ
        // =====================================================

        @PostMapping("/{serviceId}/update")
        public String updateService(
                        @PathVariable Long serviceId,

                        @Valid @ModelAttribute("serviceForm") ServiceUpdateDTO dto,

                        BindingResult bindingResult,
                        Model model,
                        HttpSession session,
                        RedirectAttributes redirectAttributes) {
                Service service;

                try {
                        service = serviceCatalogService.getServiceById(
                                        serviceId);
                } catch (IllegalArgumentException ex) {
                        redirectAttributes.addFlashAttribute(
                                        "errorMessage",
                                        ex.getMessage());

                        return REDIRECT_LIST;
                }

                if (!bindingResult.hasErrors()) {
                        try {
                                serviceCatalogService.updateService(
                                                serviceId,
                                                dto);

                                redirectAttributes.addFlashAttribute(
                                                "successMessage",
                                                "C\u1EADp nh\u1EADt d\u1ECBch v\u1EE5 th\u00E0nh c\u00F4ng.");

                                return REDIRECT_LIST;
                        } catch (IllegalArgumentException ex) {
                                bindingResult.reject(
                                                "service.update.failed",
                                                ex.getMessage());
                        }
                }

                prepareServiceFormPage(
                                model,
                                session,
                                service);

                return FORM_VIEW;
        }

        // =====================================================
        // 10. BẬT / TẮT DỊCH VỤ
        // =====================================================

        @PostMapping("/{serviceId}/toggle")
        public String toggleServiceStatus(
                        @PathVariable Long serviceId,
                        RedirectAttributes redirectAttributes) {
                try {
                        serviceCatalogService.toggleServiceStatus(
                                        serviceId);

                        redirectAttributes.addFlashAttribute(
                                        "successMessage",
                                        "Đã thay đổi trạng thái dịch vụ.");
                } catch (IllegalArgumentException ex) {
                        redirectAttributes.addFlashAttribute(
                                        "errorMessage",
                                        ex.getMessage());
                }

                return REDIRECT_LIST;
        }

        @PostMapping("/{serviceId}/delete")
        public String deleteService(
                        @PathVariable Long serviceId,
                        @RequestParam(required = false) String deleteToken,
                        HttpSession session,
                        RedirectAttributes redirectAttributes) {
                requireDeletePermission(session, deleteToken);

                try {
                        serviceCatalogService.deleteService(serviceId);
                        redirectAttributes.addFlashAttribute(
                                        "successMessage", "Xóa dịch vụ thành công.");
                } catch (IllegalArgumentException ex) {
                        redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
                }

                return REDIRECT_LIST;
        }

        // =====================================================
        // 11. MỞ FORM CẤU HÌNH KỸ THUẬT
        // =====================================================

        @GetMapping("/{serviceId}/config")
        public String showTechnicalConfigForm(
                        @PathVariable Long serviceId,
                        Model model,
                        HttpSession session,
                        RedirectAttributes redirectAttributes) {
                try {
                        Service service = serviceCatalogService.getServiceById(
                                        serviceId);

                        ServiceTechnicalConfigDTO dto = serviceCatalogService.getServiceTechnicalConfig(
                                        serviceId);

                        model.addAttribute(
                                        "technicalConfigForm",
                                        dto);

                        prepareTechnicalConfigPage(
                                        model,
                                        session,
                                        service);

                        return CONFIG_VIEW;
                } catch (IllegalArgumentException ex) {
                        redirectAttributes.addFlashAttribute(
                                        "errorMessage",
                                        ex.getMessage());

                        return REDIRECT_LIST;
                }
        }

        // =====================================================
        // 12. LƯU CẤU HÌNH KỸ THUẬT
        // =====================================================

        @PostMapping("/{serviceId}/config")
        public String updateTechnicalConfig(
                        @PathVariable Long serviceId,

                        @Valid @ModelAttribute("technicalConfigForm") ServiceTechnicalConfigDTO dto,

                        BindingResult bindingResult,
                        Model model,
                        HttpSession session,
                        RedirectAttributes redirectAttributes) {
                Service service;

                try {
                        service = serviceCatalogService.getServiceById(
                                        serviceId);
                } catch (IllegalArgumentException ex) {
                        redirectAttributes.addFlashAttribute(
                                        "errorMessage",
                                        ex.getMessage());

                        return REDIRECT_LIST;
                }

                if (!bindingResult.hasErrors()) {
                        try {
                                serviceCatalogService.updateServiceTechnicalConfig(
                                                serviceId,
                                                dto);

                                redirectAttributes.addFlashAttribute(
                                                "successMessage",
                                                "Lưu cấu hình kỹ thuật thành công.");

                                return "redirect:/admin/services/"
                                                + serviceId
                                                + "/config";
                        } catch (IllegalArgumentException ex) {
                                bindingResult.reject(
                                                "service.config.failed",
                                                ex.getMessage());
                        }
                }

                prepareTechnicalConfigPage(
                                model,
                                session,
                                service);

                return CONFIG_VIEW;
        }

        // =====================================================
        // 12B. UPLOAD HÌNH ẢNH DỊCH VỤ & ẢNH TRONG MÔ TẢ CKEDITOR
        // =====================================================

        private static final long MAX_SERVICE_IMAGE_BYTES = 5L * 1024 * 1024; // 5MB
        private static final Set<String> ALLOWED_IMAGE_MIMES = Set.of(
                        "image/jpeg", "image/jpg", "image/png", "image/webp");
        private static final Set<String> ALLOWED_IMAGE_EXTS = Set.of(
                        "jpg", "jpeg", "png", "webp");

        @PostMapping("/upload/image")
        @ResponseBody
        public ResponseEntity<Map<String, Object>> uploadServiceImage(
                        @RequestParam(value = "file", required = false) MultipartFile file,
                        @RequestParam(value = "upload", required = false) MultipartFile upload,
                        HttpSession session) {
                Map<String, Object> response = new HashMap<>();

                Object sessionUser = session != null ? session.getAttribute("loggedInUser") : null;
                if (!(sessionUser instanceof User user) || user.getUserId() == null) {
                        response.put("error", Map.of("message", "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại."));
                        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
                }

                boolean isAllowed = userRepository.hasActiveAdminRole(user.getUserId())
                                || userRepository.hasActivePermission(user.getUserId(), "CATALOG_MANAGE");
                if (!isAllowed) {
                        response.put("error", Map.of("message", "Bạn không có quyền tải ảnh dịch vụ lên."));
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
                }

                MultipartFile multipartFile = (file != null && !file.isEmpty()) ? file : upload;
                if (multipartFile == null || multipartFile.isEmpty()) {
                        response.put("error", Map.of("message", "Vui lòng chọn tệp hình ảnh hợp lệ."));
                        return ResponseEntity.badRequest().body(response);
                }

                if (multipartFile.getSize() > MAX_SERVICE_IMAGE_BYTES) {
                        response.put("error", Map.of("message", "Dung lượng ảnh vượt quá giới hạn cho phép (tối đa 5MB)."));
                        return ResponseEntity.badRequest().body(response);
                }

                String contentType = multipartFile.getContentType();
                String normalizedMime = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
                if (!ALLOWED_IMAGE_MIMES.contains(normalizedMime)) {
                        response.put("error", Map.of("message", "Định dạng tệp không hợp lệ. Chỉ chấp nhận ảnh JPG, JPEG, PNG hoặc WEBP."));
                        return ResponseEntity.badRequest().body(response);
                }

                String originalName = multipartFile.getOriginalFilename();
                String ext = extractExtension(originalName);
                if (!ALLOWED_IMAGE_EXTS.contains(ext) || !isMimeMatchingExt(normalizedMime, ext)) {
                        response.put("error", Map.of("message", "Phần mở rộng tệp không hợp lệ hoặc không khớp định dạng ảnh (JPG, PNG, WEBP)."));
                        return ResponseEntity.badRequest().body(response);
                }

                try {
                        byte[] bytes = multipartFile.getBytes();
                        if (!isValidImageSignature(bytes, ext)) {
                                response.put("error", Map.of("message", "Nội dung tệp không phải là hình ảnh hợp lệ hoặc tệp bị hỏng."));
                                return ResponseEntity.badRequest().body(response);
                        }

                        String safeFileName = UUID.randomUUID().toString() + "." + ext;
                        Path uploadDir = Paths.get("uploads", "services").toAbsolutePath().normalize();
                        Files.createDirectories(uploadDir);

                        Path targetPath = uploadDir.resolve(safeFileName).normalize();
                        if (!targetPath.startsWith(uploadDir)) {
                                response.put("error", Map.of("message", "Đường dẫn lưu tệp không hợp lệ."));
                                return ResponseEntity.badRequest().body(response);
                        }

                        Files.write(targetPath, bytes);

                        String fileUrl = "/uploads/services/" + safeFileName;
                        response.put("url", fileUrl);
                        response.put("location", fileUrl);
                        return ResponseEntity.ok(response);
                } catch (Exception ex) {
                        response.put("error", Map.of("message", "Không thể lưu hình ảnh dịch vụ. Vui lòng thử lại."));
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
                }
        }

        private String extractExtension(String filename) {
                if (filename == null) {
                        return "";
                }
                int dotIdx = filename.lastIndexOf('.');
                if (dotIdx < 0 || dotIdx == filename.length() - 1) {
                        return "";
                }
                return filename.substring(dotIdx + 1).trim().toLowerCase(Locale.ROOT);
        }

        private boolean isMimeMatchingExt(String mime, String ext) {
                if ("image/jpeg".equals(mime) || "image/jpg".equals(mime)) {
                        return "jpg".equals(ext) || "jpeg".equals(ext);
                }
                if ("image/png".equals(mime)) {
                        return "png".equals(ext);
                }
                if ("image/webp".equals(mime)) {
                        return "webp".equals(ext);
                }
                return false;
        }

        private boolean isValidImageSignature(byte[] bytes, String ext) {
                if (bytes == null || bytes.length < 12) {
                        return false;
                }
                if ("jpg".equals(ext) || "jpeg".equals(ext)) {
                        return (bytes[0] & 0xFF) == 0xFF
                                        && (bytes[1] & 0xFF) == 0xD8
                                        && (bytes[2] & 0xFF) == 0xFF;
                }
                if ("png".equals(ext)) {
                        return bytes.length >= 24
                                        && (bytes[0] & 0xFF) == 0x89
                                        && bytes[1] == 0x50
                                        && bytes[2] == 0x4E
                                        && bytes[3] == 0x47
                                        && bytes[4] == 0x0D
                                        && bytes[5] == 0x0A
                                        && bytes[6] == 0x1A
                                        && bytes[7] == 0x0A
                                        && bytes[12] == 'I'
                                        && bytes[13] == 'H'
                                        && bytes[14] == 'D'
                                        && bytes[15] == 'R';
                }
                if ("webp".equals(ext)) {
                        return bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                                        && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
                }
                return false;
        }

        // =====================================================
        // 13. CHUẨN BỊ DỮ LIỆU CHO GIAO DIỆN
        // =====================================================

        private void prepareListPage(
                        Model model,
                        HttpSession session,
                        Long categoryId) {
                addLayoutAttributes(session, model);

                // Token riêng cho thao tác xóa, kể cả khi dự án đang tắt Spring CSRF.
                synchronized (session) {
                        if (!(session.getAttribute(DELETE_TOKEN_SESSION_KEY) instanceof String)) {
                                session.setAttribute(DELETE_TOKEN_SESSION_KEY, UUID.randomUUID().toString());
                        }
                        model.addAttribute("deleteToken", session.getAttribute(DELETE_TOKEN_SESSION_KEY));
                }

                model.addAttribute(
                                "categories",
                                serviceCatalogService.getAllCategories());

                List<Service> services;

                if (categoryId == null) {
                        services = serviceCatalogService.getAllServices();
                } else {
                        services = serviceCatalogService.getServicesByCategory(
                                        categoryId);
                }

                model.addAttribute("services", services);
                model.addAttribute("selectedCategoryId", categoryId);
                model.addAttribute("serviceTypes", ServiceType.values());
                model.addAttribute("serviceStatuses", ServiceStatus.values());

                if (!model.containsAttribute("serviceForm")) {
                        model.addAttribute("serviceForm", new ServiceCreateDTO());
                }
                if (!model.containsAttribute("categoryForm")) {
                        model.addAttribute("categoryForm", new ServiceCategoryDTO());
                }

        }

        private void prepareCategoryFormPage(
                        Model model,
                        HttpSession session,
                        Long categoryEditingId) {
                addLayoutAttributes(session, model);
                model.addAttribute("categoryEditingId", categoryEditingId);
                model.addAttribute("serviceTypes", ServiceType.values());
        }

        private void prepareServiceFormPage(
                        Model model,
                        HttpSession session,
                        Service currentService) {
                addLayoutAttributes(session, model);

                List<ServiceCategory> categories = new ArrayList<>(
                                serviceCatalogService.getActiveCategories());

                // Khi sửa, vẫn hiển thị được nhóm hiện tại
                // kể cả khi nhóm đó đã ngừng sử dụng.
                if (currentService != null) {
                        ServiceCategory currentCategory = currentService.getCategory();

                        boolean alreadyIncluded = categories.stream()
                                        .anyMatch(category -> category.getCategoryId().equals(
                                                        currentCategory.getCategoryId()));

                        if (!alreadyIncluded) {
                                categories.add(currentCategory);
                        }
                }

                model.addAttribute("categories", categories);
                model.addAttribute("serviceTypes", ServiceType.values());
                model.addAttribute("serviceStatuses", ServiceStatus.values());

                boolean editing = currentService != null;

                model.addAttribute("isEdit", editing);

                model.addAttribute(
                                "serviceId",
                                editing ? currentService.getServiceId() : null);

                model.addAttribute(
                                "serviceCode",
                                editing ? currentService.getServiceCode() : null);
        }

        private void prepareTechnicalConfigPage(
                        Model model,
                        HttpSession session,
                        Service service) {
                addLayoutAttributes(session, model);

                model.addAttribute("service", service);
        }

        private void requireDeletePermission(HttpSession session, String deleteToken) {
                Object sessionUser = session.getAttribute("loggedInUser");

                if (!(sessionUser instanceof User user)
                                || user.getUserId() == null
                                || !userRepository.hasActiveAdminRole(user.getUserId())) {
                        throw new ResponseStatusException(
                                        HttpStatus.FORBIDDEN, "Chỉ Admin đang hoạt động mới được xóa dữ liệu dịch vụ.");
                }

                if (deleteToken == null
                                || !deleteToken.equals(session.getAttribute(DELETE_TOKEN_SESSION_KEY))) {
                        throw new ResponseStatusException(
                                        HttpStatus.FORBIDDEN,
                                        "Phiên xác nhận không hợp lệ. Vui lòng tải lại danh sách.");
                }
        }

        private void addLayoutAttributes(
                        HttpSession session,
                        Model model) {
                model.addAttribute(
                                "roleNames",
                                session.getAttribute("roleNames"));

                Object sessionUser = session.getAttribute(
                                "loggedInUser");

                if (sessionUser instanceof User user) {
                        model.addAttribute(
                                        "displayName",
                                        user.getUsername());
                }
        }
}
