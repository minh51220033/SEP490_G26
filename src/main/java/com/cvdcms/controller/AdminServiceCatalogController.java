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

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

/**
 * [Phân hệ]: Danh mục dịch vụ & Nhóm dịch vụ (Quản trị)
 * [Vai trò]: Controller quản trị toàn bộ danh mục nhóm dịch vụ, dịch vụ/sản phẩm, cấu hình kỹ thuật và tải ảnh dịch vụ tại đường dẫn /admin/services.
 */
@Controller
@RequestMapping("/admin/services")
public class AdminServiceCatalogController {

    private static final String LIST_VIEW = "admin/services/list";

    private static final String CATEGORY_FORM_VIEW = "admin/services/category-form";

    private static final String FORM_VIEW = "admin/services/form";

    private static final String CONFIG_VIEW = "admin/services/technical-config";

    private static final String CATEGORY_LIST_VIEW = "admin/services/category-list";

    private static final String REDIRECT_LIST = "redirect:/admin/services";

    private static final String REDIRECT_CATEGORIES = "redirect:/admin/services/categories";

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

    /**
     * [Chức năng]: Xử lý trang GET /admin/services hiển thị danh sách dịch vụ và sản phẩm có lọc, tìm kiếm và phân trang.
     * [Mục đích]: Nạp dữ liệu bảng dịch vụ và thống kê tổng quan qua prepareListPage để hiển thị view admin/services/list; tự chuyển hướng nếu tham số tab=categories hoặc có lỗi tham số.
     */
    @GetMapping
    public String listServices(
            @RequestParam(value = "q", required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) ServiceStatus status,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "type", required = false) ServiceType serviceType,
            @RequestParam(value = "tab", required = false) String tab,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            Model model,
            HttpSession session,
            RedirectAttributes redirectAttributes) {
        if ("categories".equalsIgnoreCase(tab)) {
            return REDIRECT_LIST;
        }
        try {
            prepareListPage(model, session, keyword, categoryId, status, kind, serviceType, page, size);
            return LIST_VIEW;
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    ex.getMessage());

            return REDIRECT_LIST;
        }
    }

    // =====================================================
    // 1.1 DANH SÁCH NHÓM DỊCH VỤ (TAB 2)
    // =====================================================

    /**
     * [Chức năng]: Xử lý trang GET /admin/services/categories hiển thị danh sách nhóm dịch vụ theo bộ lọc từ khóa, loại nhóm và trạng thái hoạt động.
     * [Mục đích]: Chuẩn bị thống kê và danh sách nhóm dịch vụ thông qua prepareCategoryListPage rồi trả về view admin/services/category-list.
     */
    @GetMapping("/categories")
    public String listCategories(
            @RequestParam(value = "q", required = false) String keyword,
            @RequestParam(value = "type", required = false) ServiceType categoryType,
            @RequestParam(value = "active", required = false) Boolean active,
            Model model,
            HttpSession session) {
        prepareCategoryListPage(model, session, keyword, categoryType, active);
        return CATEGORY_LIST_VIEW;
    }

    // =====================================================
    // 2. THÊM NHÓM DANH MỤC
    // =====================================================

    /**
     * [Chức năng]: Xử lý GET /admin/services/categories/create mở trang biểu mẫu độc lập để thêm mới nhóm dịch vụ.
     * [Mục đích]: Khởi tạo ServiceCategoryDTO rỗng, nạp dữ liệu dùng chung cho form và trả về view admin/services/category-form.
     */
    @GetMapping("/categories/create")
    public String showCreateCategoryForm(
            Model model,
            HttpSession session) {
        model.addAttribute("categoryForm", new ServiceCategoryDTO());
        prepareCategoryFormPage(model, session, null);
        return CATEGORY_FORM_VIEW;
    }

    /**
     * [Chức năng]: Xử lý POST /admin/services/categories để tạo mới một nhóm dịch vụ từ modal hoặc trang biểu mẫu độc lập.
     * [Mục đích]: Kiểm tra hợp lệ ServiceCategoryDTO, gọi ServiceCatalogService.createCategory và chuyển hướng kèm thông báo thành công hoặc giữ lại dữ liệu/mở lại modal khi có lỗi.
     */
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
            return REDIRECT_CATEGORIES;
        }

        try {
            serviceCatalogService.createCategory(dto);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Th\u00EAm nh\u00F3m d\u1ECBch v\u1EE5 th\u00E0nh c\u00F4ng.");

            return REDIRECT_CATEGORIES;
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
            return REDIRECT_CATEGORIES;
        }
    }

    // =====================================================
    // 3. MỞ FORM SỬA NHÓM DANH MỤC
    // =====================================================

    /**
     * [Chức năng]: Xử lý GET /admin/services/categories/{categoryId}/edit mở biểu mẫu chỉnh sửa một nhóm dịch vụ.
     * [Mục đích]: Tải thực thể ServiceCategory từ ServiceCatalogService, ánh xạ sang ServiceCategoryDTO để render view admin/services/category-form; chuyển hướng về danh sách nhóm nếu ID không hợp lệ.
     */
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

            return REDIRECT_CATEGORIES;
        }
    }

    // =====================================================
    // 4. CẬP NHẬT NHÓM DANH MỤC
    // =====================================================

    /**
     * [Chức năng]: Xử lý POST /admin/services/categories/{categoryId}/update để cập nhật thông tin một nhóm dịch vụ.
     * [Mục đích]: Xác minh nhóm tồn tại, kiểm tra validation của ServiceCategoryDTO, gọi ServiceCatalogService.updateCategory và chuyển hướng về danh sách nhóm hoặc trả lại form kèm lỗi.
     */
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

            return REDIRECT_CATEGORIES;
        }

        if (!bindingResult.hasErrors()) {
            try {
                serviceCatalogService.updateCategory(
                        categoryId,
                        dto);

                redirectAttributes.addFlashAttribute(
                        "successMessage",
                        "C\u1EADp nh\u1EADt nh\u00F3m d\u1ECBch v\u1EE5 th\u00E0nh c\u00F4ng.");

                return REDIRECT_CATEGORIES;
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

    /**
     * [Chức năng]: Xử lý POST /admin/services/categories/{categoryId}/toggle để bật hoặc tắt trạng thái hoạt động của nhóm dịch vụ.
     * [Mục đích]: Gọi ServiceCatalogService.toggleCategoryStatus và chuyển hướng về /admin/services/categories kèm thông báo kết quả.
     */
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

        return REDIRECT_CATEGORIES;
    }

    /**
     * [Chức năng]: Xử lý POST /admin/services/categories/{categoryId}/delete để xóa một nhóm dịch vụ chưa chứa dịch vụ con.
     * [Mục đích]: Xác thực quyền xóa và mã deleteToken trong phiên qua requireDeletePermission, sau đó gọi ServiceCatalogService.deleteCategory và chuyển hướng về danh sách nhóm.
     */
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

        return REDIRECT_CATEGORIES;
    }

    // =====================================================
    // 6. MỞ FORM THÊM DỊCH VỤ
    // =====================================================

    /**
     * [Chức năng]: Xử lý GET /admin/services/create mở biểu mẫu thêm mới dịch vụ hoặc sản phẩm.
     * [Mục đích]: Khởi tạo ServiceCreateDTO, chuẩn bị danh sách nhóm hoạt động và enum hiển thị qua prepareServiceFormPage rồi trả về view admin/services/form.
     */
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

    /**
     * [Chức năng]: Xử lý POST /admin/services để tạo mới một dịch vụ hoặc sản phẩm từ modal hoặc biểu mẫu độc lập.
     * [Mục đích]: Kiểm tra hợp lệ ServiceCreateDTO, gọi ServiceCatalogService.createService và chuyển hướng về danh sách kèm thông báo phù hợp (dịch vụ/sản phẩm) hoặc giữ lại form/mở lại modal khi lỗi.
     */
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
                    dto.getServiceType() == ServiceType.PRODUCT
                            ? "Thêm sản phẩm thành công."
                            : "Thêm dịch vụ thành công.");

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

    /**
     * [Chức năng]: Xử lý GET /admin/services/{serviceId}/edit mở biểu mẫu chỉnh sửa thông tin và đơn giá dịch vụ.
     * [Mục đích]: Tải thực thể Service và ServiceUpdateDTO từ ServiceCatalogService, chuẩn bị dữ liệu form (giữ cả nhóm hiện tại dù đã tắt) và trả về view admin/services/form.
     */
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

    /**
     * [Chức năng]: Xử lý POST /admin/services/{serviceId}/update để lưu thay đổi thông tin chung, thời lượng và đơn giá dịch vụ.
     * [Mục đích]: Kiểm tra dịch vụ tồn tại và validation của ServiceUpdateDTO, gọi ServiceCatalogService.updateService và chuyển hướng về danh sách dịch vụ hoặc hiển thị lại form khi có lỗi.
     */
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
                        dto.getServiceType() == ServiceType.PRODUCT
                                ? "Cập nhật sản phẩm thành công."
                                : "Cập nhật dịch vụ thành công.");

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

    /**
     * [Chức năng]: Xử lý POST /admin/services/{serviceId}/toggle để chuyển đổi trạng thái hoạt động (ACTIVE/INACTIVE) của dịch vụ.
     * [Mục đích]: Gọi ServiceCatalogService.toggleServiceStatus và chuyển hướng về /admin/services kèm thông báo thành công hoặc lỗi nghiệp vụ (ví dụ nhóm cha đã tắt).
     */
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

    /**
     * [Chức năng]: Xử lý POST /admin/services/{serviceId}/delete để xóa một dịch vụ chưa phát sinh dữ liệu tham chiếu.
     * [Mục đích]: Kiểm tra quyền xóa và deleteToken qua requireDeletePermission, gọi ServiceCatalogService.deleteService và chuyển hướng về danh sách dịch vụ.
     */
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

    /**
     * [Chức năng]: Xử lý GET /admin/services/{serviceId}/config mở màn hình cấu hình mẫu kết quả cận lâm sàng và yêu cầu kỹ thuật của dịch vụ.
     * [Mục đích]: Lấy thông tin dịch vụ và ServiceTechnicalConfigDTO từ ServiceCatalogService để hiển thị view admin/services/technical-config.
     */
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

            return REDIRECT_CATEGORIES;
        }
    }

    // =====================================================
    // 12. LƯU CẤU HÌNH KỸ THUẬT
    // =====================================================

    /**
     * [Chức năng]: Xử lý POST /admin/services/{serviceId}/config để lưu cấu hình mẫu kết quả JSON và yêu cầu kỹ thuật của dịch vụ.
     * [Mục đích]: Kiểm tra dịch vụ tồn tại và dữ liệu DTO, gọi ServiceCatalogService.updateServiceTechnicalConfig và chuyển hướng lại trang cấu hình kèm thông báo thành công hoặc hiển thị lỗi.
     */
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

    /**
     * [Chức năng]: API POST /admin/services/upload/image tiếp nhận tải lên hình ảnh đại diện dịch vụ hoặc ảnh chèn trong trình soạn thảo CKEditor.
     * [Mục đích]: Kiểm tra phiên đăng nhập, phân quyền danh mục, giới hạn dung lượng (5MB), loại MIME, phần mở rộng và chữ ký nhị phân (magic bytes) của ảnh trước khi lưu vào thư mục uploads/services và trả về đường dẫn URL dạng JSON.
     */
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
                || userRepository.hasActivePermission(user.getUserId(), "CATALOG_SERVICES")
                || userRepository.hasActivePermission(user.getUserId(), "CATALOG_SERVICE_CATEGORIES")
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

    /**
     * [Hàm hỗ trợ]: Tách và chuẩn hóa phần mở rộng (viết thường) từ tên tệp gốc khi kiểm tra ảnh tải lên.
     */
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

    /**
     * [Hàm hỗ trợ]: Kiểm tra đối chiếu kiểu MIME của tệp tải lên có khớp với phần mở rộng (jpg, jpeg, png, webp) hay không.
     */
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

    /**
     * [Hàm hỗ trợ]: Xác thực chữ ký byte đầu tệp (magic bytes) nhằm đảm bảo dữ liệu tải lên thực sự là ảnh JPEG, PNG hoặc WEBP hợp lệ.
     */
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

    /**
     * [Hàm hỗ trợ]: Nạp toàn bộ dữ liệu thống kê, token xác nhận xóa, kết quả lọc/phân trang dịch vụ và các DTO mặc định cho trang danh sách dịch vụ.
     */
    private void prepareListPage(
            Model model,
            HttpSession session,
            String keyword,
            Long categoryId,
            ServiceStatus status,
            String kind,
            ServiceType serviceType,
            int page,
            int size) {
        addLayoutAttributes(session, model);

        // Token riêng cho thao tác xóa, kể cả khi dự án đang tắt Spring CSRF.
        synchronized (session) {
            if (!(session.getAttribute(DELETE_TOKEN_SESSION_KEY) instanceof String)) {
                session.setAttribute(DELETE_TOKEN_SESSION_KEY, UUID.randomUUID().toString());
            }
            model.addAttribute("deleteToken", session.getAttribute(DELETE_TOKEN_SESSION_KEY));
        }

        List<ServiceCategory> allCategories = serviceCatalogService.getAllCategories();
        model.addAttribute("categories", allCategories);

        List<Service> allServices = serviceCatalogService.getAllServices();
        long totalServicesCount = allServices.size();
        long serviceOnlyCount = allServices.stream().filter(s -> s.getServiceType() != ServiceType.PRODUCT).count();
        long productOnlyCount = allServices.stream().filter(s -> s.getServiceType() == ServiceType.PRODUCT).count();
        long activeServicesCount = allServices.stream().filter(s -> s.getStatus() == ServiceStatus.ACTIVE).count();
        long inactiveServicesCount = allServices.stream().filter(s -> s.getStatus() == ServiceStatus.INACTIVE).count();
        model.addAttribute("totalServicesCount", totalServicesCount);
        model.addAttribute("serviceOnlyCount", serviceOnlyCount);
        model.addAttribute("productOnlyCount", productOnlyCount);
        model.addAttribute("activeServicesCount", activeServicesCount);
        model.addAttribute("inactiveServicesCount", inactiveServicesCount);
        model.addAttribute("totalCategoriesCount", (long) allCategories.size());

        String normalizedKind = ("PRODUCT".equalsIgnoreCase(kind) || "SERVICE".equalsIgnoreCase(kind))
                ? kind.toUpperCase(Locale.ROOT)
                : "";

        // Dùng API tìm kiếm trả về List đang có; chia trang sau khi lọc.
        List<Service> filteredServices = serviceCatalogService.searchServices(
                keyword, categoryId, status, serviceType, normalizedKind.isEmpty() ? null : normalizedKind);
        int pageSize = size > 0 ? Math.min(size, 100) : 10;
        int lastPage = filteredServices.isEmpty() ? 0 : (filteredServices.size() - 1) / pageSize;
        int currentPage = Math.min(Math.max(page, 0), lastPage);
        Pageable pageable = PageRequest.of(currentPage, pageSize);
        int fromIndex = (int) pageable.getOffset();
        int toIndex = (int) Math.min((long) fromIndex + pageSize, filteredServices.size());
        Page<Service> servicePage = new PageImpl<>(
                new ArrayList<>(filteredServices.subList(fromIndex, toIndex)),
                pageable,
                filteredServices.size());

        model.addAttribute("services", servicePage.getContent());
        model.addAttribute("servicePage", servicePage);
        model.addAttribute("currentPage", servicePage.getNumber());
        model.addAttribute("pageSize", servicePage.getSize());
        model.addAttribute("totalPages", servicePage.getTotalPages());
        model.addAttribute("totalItems", servicePage.getTotalElements());
        model.addAttribute("fromItem", filteredServices.isEmpty() ? 0 : fromIndex + 1);
        model.addAttribute("toItem", toIndex);
        model.addAttribute("keyword", keyword != null ? keyword.trim() : "");
        model.addAttribute("selectedCategoryId", categoryId);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("selectedKind", normalizedKind);
        model.addAttribute("selectedServiceType", serviceType);
        model.addAttribute("serviceTypes", ServiceType.values());
        model.addAttribute("serviceStatuses", ServiceStatus.values());

        if (!model.containsAttribute("serviceForm")) {
            model.addAttribute("serviceForm", new ServiceCreateDTO());
        }
        if (!model.containsAttribute("categoryForm")) {
            model.addAttribute("categoryForm", new ServiceCategoryDTO());
        }
        model.addAttribute("activeTab", "services");
        model.addAttribute("activeMenu", "catalogs");
        model.addAttribute("pageTitle", "Quản lý danh mục - Danh mục dịch vụ & sản phẩm");
    }

    /**
     * [Hàm hỗ trợ]: Nạp dữ liệu thống kê, token xác nhận xóa, kết quả lọc nhóm dịch vụ và thuộc tính giao diện cho trang danh sách nhóm dịch vụ.
     */
    private void prepareCategoryListPage(
            Model model,
            HttpSession session,
            String keyword,
            ServiceType categoryType,
            Boolean active) {
        addLayoutAttributes(session, model);

        synchronized (session) {
            if (!(session.getAttribute(DELETE_TOKEN_SESSION_KEY) instanceof String)) {
                session.setAttribute(DELETE_TOKEN_SESSION_KEY, UUID.randomUUID().toString());
            }
            model.addAttribute("deleteToken", session.getAttribute(DELETE_TOKEN_SESSION_KEY));
        }

        List<ServiceCategory> allCategories = serviceCatalogService.getAllCategories();
        long totalCategoriesCount = allCategories.size();
        long activeCategoriesCount = allCategories.stream().filter(c -> Boolean.TRUE.equals(c.getIsActive())).count();
        long inactiveCategoriesCount = totalCategoriesCount - activeCategoriesCount;
        model.addAttribute("totalCategoriesCount", totalCategoriesCount);
        model.addAttribute("activeCategoriesCount", activeCategoriesCount);
        model.addAttribute("inactiveCategoriesCount", inactiveCategoriesCount);
        model.addAttribute("totalServicesCount", (long) serviceCatalogService.getAllServices().size());

        model.addAttribute("categories", serviceCatalogService.searchCategories(keyword, categoryType, active));
        model.addAttribute("keyword", keyword != null ? keyword.trim() : "");
        model.addAttribute("selectedType", categoryType);
        model.addAttribute("selectedActive", active);
        model.addAttribute("serviceTypes", ServiceType.values());
        if (!model.containsAttribute("categoryForm")) {
            model.addAttribute("categoryForm", new ServiceCategoryDTO());
        }
        model.addAttribute("activeTab", "categories");
        model.addAttribute("activeMenu", "catalogs");
        model.addAttribute("pageTitle", "Quản lý danh mục - Nhóm dịch vụ");
    }

    /**
     * [Hàm hỗ trợ]: Chuẩn bị các thuộc tính Model cần thiết cho màn hình biểu mẫu thêm/sửa nhóm dịch vụ.
     */
    private void prepareCategoryFormPage(
            Model model,
            HttpSession session,
            Long categoryEditingId) {
        addLayoutAttributes(session, model);
        model.addAttribute("categoryEditingId", categoryEditingId);
        model.addAttribute("serviceTypes", ServiceType.values());
        model.addAttribute("activeTab", "categories");
        model.addAttribute("activeMenu", "catalogs");
        model.addAttribute("pageTitle", categoryEditingId == null ? "Thêm nhóm dịch vụ - Quản lý danh mục" : "Chỉnh sửa nhóm dịch vụ - Quản lý danh mục");
    }

    /**
     * [Hàm hỗ trợ]: Chuẩn bị danh sách nhóm dịch vụ (bao gồm cả nhóm cũ nếu đang sửa dịch vụ thuộc nhóm đã tắt), enum loại/trạng thái và cờ chế độ chỉnh sửa cho form dịch vụ.
     */
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
        model.addAttribute("activeTab", "services");
        model.addAttribute("activeMenu", "catalogs");
        model.addAttribute("pageTitle", editing ? "Chỉnh sửa dịch vụ - Quản lý danh mục" : "Thêm dịch vụ - Quản lý danh mục");
    }

    /**
     * [Hàm hỗ trợ]: Nạp thông tin dịch vụ và thuộc tính điều hướng giao diện cho trang cấu hình kỹ thuật dịch vụ.
     */
    private void prepareTechnicalConfigPage(
            Model model,
            HttpSession session,
            Service service) {
        addLayoutAttributes(session, model);

        model.addAttribute("service", service);
        model.addAttribute("activeTab", "services");
        model.addAttribute("activeMenu", "catalogs");
        model.addAttribute("pageTitle", "Cấu hình kỹ thuật dịch vụ - Quản lý danh mục");
    }

    /**
     * [Hàm hỗ trợ]: Xác thực người dùng có quyền quản lý danh mục và mã deleteToken gửi lên khớp với token lưu trong phiên trước khi cho phép xóa nhóm hoặc dịch vụ.
     */
    private void requireDeletePermission(HttpSession session, String deleteToken) {
        Object sessionUser = session.getAttribute("loggedInUser");

        if (!(sessionUser instanceof User user)
                || user.getUserId() == null
                || (!userRepository.hasActiveAdminRole(user.getUserId())
                && !userRepository.hasActivePermission(user.getUserId(), "CATALOG_SERVICES")
                && !userRepository.hasActivePermission(user.getUserId(), "CATALOG_SERVICE_CATEGORIES")
                && !userRepository.hasActivePermission(user.getUserId(), "CATALOG_MANAGE"))) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Bạn không có quyền xóa dữ liệu dịch vụ.");
        }

        if (deleteToken == null
                || !deleteToken.equals(session.getAttribute(DELETE_TOKEN_SESSION_KEY))) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Phiên xác nhận không hợp lệ. Vui lòng tải lại danh sách.");
        }
    }

    /**
     * [Hàm hỗ trợ]: Nạp danh sách vai trò và tên hiển thị người dùng hiện tại từ HttpSession vào Model để hiển thị trên thanh điều hướng.
     */
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
