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
import org.springframework.web.server.ResponseStatusException;

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
                        Model model,
                        HttpSession session,
                        RedirectAttributes redirectAttributes) {
                if (bindingResult.hasErrors()) {
                        prepareCategoryFormPage(model, session, null);
                        return CATEGORY_FORM_VIEW;
                }

                try {
                        serviceCatalogService.createCategory(dto);

                        redirectAttributes.addFlashAttribute(
                                        "successMessage",
                                        "Thêm nhóm dịch vụ thành công.");

                        return REDIRECT_LIST;
                } catch (IllegalArgumentException ex) {
                        bindingResult.reject(
                                        "category.create.failed",
                                        ex.getMessage());

                        prepareCategoryFormPage(model, session, null);
                        return CATEGORY_FORM_VIEW;
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
                                                "Cập nhật nhóm dịch vụ thành công.");

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
                                        "Đã thay đổi trạng thái nhóm dịch vụ.");
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
                        Model model,
                        HttpSession session,
                        RedirectAttributes redirectAttributes) {
                if (bindingResult.hasErrors()) {
                        prepareServiceFormPage(model, session, null);
                        return FORM_VIEW;
                }

                try {
                        serviceCatalogService.createService(dto);

                        redirectAttributes.addFlashAttribute(
                                        "successMessage",
                                        "Thêm dịch vụ thành công.");

                        return REDIRECT_LIST;
                } catch (IllegalArgumentException ex) {
                        bindingResult.reject(
                                        "service.create.failed",
                                        ex.getMessage());

                        prepareServiceFormPage(model, session, null);

                        return FORM_VIEW;
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
                                                "Cập nhật dịch vụ thành công.");

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
