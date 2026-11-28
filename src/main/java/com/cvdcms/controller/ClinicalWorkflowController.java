package com.cvdcms.controller;

import com.cvdcms.DTO.ServiceDTO;
import com.cvdcms.DTO.WorkflowTemplateResponseDTO;
import com.cvdcms.service.ClinicalWorkflowService;
import com.cvdcms.service.PerformedServiceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * [Phân hệ]: Quản lý mẫu quy trình kỹ thuật & Combo dịch vụ (Chương 7)
 * [Vai trò]: Controller giao diện web (Thymeleaf) hiển thị danh sách mẫu quy trình, biểu mẫu tạo mới/chỉnh sửa quy trình từng bước và cấu hình 5 khung thao tác lâm sàng.
 */
@Slf4j
@Controller
@RequestMapping("/doctor/workflows")
@RequiredArgsConstructor
public class ClinicalWorkflowController {

    private final ClinicalWorkflowService workflowService;
    private final PerformedServiceService performedServiceService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.UserRepository userRepository;

    /**
     * [Chức năng]: Hiển thị trang danh sách mẫu quy trình kỹ thuật và combo dịch vụ (`GET /doctor/workflows`).
     * [Mục đích]: Nạp và lọc danh sách mẫu quy trình theo từ khóa, dịch vụ, trạng thái cùng phân trang; đồng thời truyền danh sách combo, trường tùy biến 5 khung thao tác và cờ quyền quản trị mẫu sang view `doctor/workflows`.
     */
    @GetMapping
    public String getWorkflowsPage(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long serviceId,
            @RequestParam(required = false) String status,
            jakarta.servlet.http.HttpSession session,
            Model model) {
        if (size != 10 && size != 25 && size != 50 && size != 100) {
            size = 10;
        }
        List<WorkflowTemplateResponseDTO> allTemplates = workflowService.getAllTemplates();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim().toLowerCase();
            allTemplates = allTemplates.stream()
                    .filter(w -> (w.getTemplateName() != null && w.getTemplateName().toLowerCase().contains(kw)) ||
                                 (w.getTemplateCode() != null && w.getTemplateCode().toLowerCase().contains(kw)) ||
                                 (w.getDescription() != null && w.getDescription().toLowerCase().contains(kw)))
                    .toList();
        }
        if (serviceId != null) {
            allTemplates = allTemplates.stream()
                    .filter(w -> serviceId.equals(w.getServiceId()))
                    .toList();
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            allTemplates = allTemplates.stream()
                    .filter(w -> w.getStatus() != null && status.equalsIgnoreCase(w.getStatus()))
                    .toList();
        }

        long totalItems = allTemplates.size();
        int totalPages = totalItems == 0 ? 0 : (int) Math.ceil((double) totalItems / size);
        int currentPage = Math.max(0, Math.min(page, Math.max(0, totalPages - 1)));
        int fromIndex = totalItems == 0 ? 0 : currentPage * size;
        int toIndex = (int) Math.min(fromIndex + size, totalItems);
        List<WorkflowTemplateResponseDTO> pagedWorkflows = totalItems == 0 ? List.of() : allTemplates.subList(fromIndex, toIndex);

        long toItem = toIndex;
        long fromItem = totalItems == 0 ? 0 : Math.min(((long) currentPage * size) + 1, toItem);

        List<ServiceDTO> activeServices = performedServiceService.getAllActiveServices();
        List<com.cvdcms.DTO.ServiceComboDTO> combos = workflowService.getAllCombos(false);

        model.addAttribute("workflows", pagedWorkflows);
        model.addAttribute("allWorkflows", workflowService.getAllTemplates());
        model.addAttribute("combos", combos);
        model.addAttribute("items", pagedWorkflows);
        model.addAttribute("totalItems", totalItems);
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("pageSize", size);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("fromItem", fromItem);
        model.addAttribute("toItem", toItem);
        model.addAttribute("keyword", keyword);
        model.addAttribute("selectedServiceId", serviceId);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("activeServices", activeServices);
        model.addAttribute("frameCustomFields", workflowService.getAllFrameCustomFields());
        model.addAttribute("canManageWorkflowTemplates", resolveCanManageTemplates(session));
        model.addAttribute("activeMenu", "workflows");
        return "doctor/workflows";
    }

    /**
     * [Chức năng]: Hiển thị trang tạo mới mẫu quy trình kỹ thuật (`GET /doctor/workflows/create`).
     * [Mục đích]: Chuẩn bị model ở chế độ tạo mới (`isEditMode = false`) cùng danh sách dịch vụ hoạt động, cấu hình trường tùy biến của 5 khung thao tác và quyền quản trị mẫu để render `doctor/workflow-form`.
     */
    @GetMapping("/create")
    public String createWorkflowPage(jakarta.servlet.http.HttpSession session, Model model) {
        model.addAttribute("isEditMode", false);
        model.addAttribute("workflow", null);
        model.addAttribute("activeServices", performedServiceService.getAllActiveServices());
        model.addAttribute("frameCustomFields", workflowService.getAllFrameCustomFields());
        model.addAttribute("canManageWorkflowTemplates", resolveCanManageTemplates(session));
        model.addAttribute("activeMenu", "workflows");
        return "doctor/workflow-form";
    }

    /**
     * [Chức năng]: Hiển thị trang chỉnh sửa mẫu quy trình kỹ thuật theo ID (`GET /doctor/workflows/{id}/edit`).
     * [Mục đích]: Tải chi tiết mẫu quy trình từ `ClinicalWorkflowService`; nếu không tìm thấy thì chuyển hướng về `/doctor/workflows`, ngược lại nạp dữ liệu và trường tùy biến 5 khung vào `doctor/workflow-form`.
     */
    @GetMapping("/{id}/edit")
    public String editWorkflowPage(
            @PathVariable("id") Long id,
            jakarta.servlet.http.HttpSession session,
            Model model) {
        WorkflowTemplateResponseDTO workflow;
        try {
            workflow = workflowService.getTemplateById(id);
        } catch (Exception ex) {
            log.warn("Không tìm thấy mẫu quy trình {}: {}", id, ex.getMessage());
            return "redirect:/doctor/workflows";
        }
        if (workflow == null) {
            return "redirect:/doctor/workflows";
        }
        model.addAttribute("isEditMode", true);
        model.addAttribute("workflow", workflow);
        model.addAttribute("activeServices", performedServiceService.getAllActiveServices());
        model.addAttribute("frameCustomFields", workflowService.getAllFrameCustomFields());
        model.addAttribute("canManageWorkflowTemplates", resolveCanManageTemplates(session));
        model.addAttribute("activeMenu", "workflows");
        return "doctor/workflow-form";
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra người dùng trong phiên đăng nhập có vai trò Quản trị viên hoặc Quản lý chuyên môn để được phép thêm/sửa/xóa mẫu quy trình hay không.
     */
    private boolean resolveCanManageTemplates(jakarta.servlet.http.HttpSession session) {
        Object principal = session != null ? session.getAttribute("loggedInUser") : null;
        if (!(principal instanceof com.cvdcms.entity.User user) || user.getUserId() == null) {
            return false;
        }
        if (userRepository != null && userRepository.hasActiveAdminRole(user.getUserId())) {
            return true;
        }
        java.util.Set<String> allowed = java.util.Set.of(
                "ADMIN", "ROLE_ADMIN", "MANAGER", "ROLE_MANAGER",
                "CLINIC_MANAGER", "ROLE_CLINIC_MANAGER", "QUAN_LY", "ROLE_QUAN_LY",
                "TRUONG_KHOA", "ROLE_TRUONG_KHOA");
        if (userRepository != null) {
            List<String> roles = userRepository.findActiveRoleNamesByUserId(user.getUserId());
            if (roles != null && roles.stream().filter(r -> r != null).map(r -> r.trim().toUpperCase(java.util.Locale.ROOT)).anyMatch(allowed::contains)) {
                return true;
            }
        }
        return false;
    }
}
