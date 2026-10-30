package com.cvdcms.controller;

import com.cvdcms.DTO.StaffAssignmentDTO;
import com.cvdcms.DTO.StaffFormDTO;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.Role;
import com.cvdcms.entity.StaffBranchAssignment;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.entity.enums.StaffGender;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.BranchService;
import com.cvdcms.service.StaffProfileService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// FT41 - Quản lý Hồ sơ nhân viên & Phân công chi nhánh (SCR-41.1, 41.2, 41.3, 41.4).
// Chỉ ADMIN hoặc tài khoản có quyền STAFF_MANAGE được truy cập (UC-03).
@Controller
@RequestMapping("/admin/staffs")
public class StaffController {

    private static final int PAGE_SIZE = 10;

    private final StaffProfileService staffService;
    private final BranchService branchService;
    private final UserRepository userRepository;

    public StaffController(StaffProfileService staffService, BranchService branchService, UserRepository userRepository) {
        this.staffService = staffService;
        this.branchService = branchService;
        this.userRepository = userRepository;
    }

    // Đưa thông tin người dùng từ session vào model để layout chung (sidebar, topbar) hiển thị đúng.
    private void addLayoutAttributes(HttpSession session, Model model) {
        model.addAttribute("roleNames", session.getAttribute("roleNames"));

        Object user = session.getAttribute("loggedInUser");
        if (user instanceof User u) {
            model.addAttribute("displayName", u.getUsername());
        }
    }

    // Chưa đăng nhập -> về trang đăng nhập; không phải ADMIN hoặc không có quyền STAFF_MANAGE -> 403. Trả null nếu hợp lệ.
    private String checkAdmin(HttpSession session) {
        if (session == null || !(session.getAttribute("loggedInUser") instanceof User sessionUser)) {
            return "redirect:/login";
        }
        boolean isAdmin = userRepository.hasActiveAdminRole(sessionUser.getUserId());
        if (!isAdmin && !userRepository.hasActivePermission(sessionUser.getUserId(), "STAFF_MANAGE")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền quản lý nhân sự.");
        }
        return null;
    }

    private List<Branch> activeBranches() {
        return branchService.getActiveBranches();
    }

    // ===================== SCR-41.1: DANH SÁCH =====================

    @GetMapping
    public String listStaff(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String specialty,
            @RequestParam(required = false) StaffStatus status,
            @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model,
            HttpSession session) {

        String redirect = checkAdmin(session);
        if (redirect != null) {
            return redirect;
        }
        addLayoutAttributes(session, model);

        List<StaffProfile> all = staffService.searchStaff(keyword, specialty, status, branchId);

        int pageSize = (size == 25 || size == 50 || size == 100) ? size : 10;
        int totalPages = Math.max(1, (int) Math.ceil(all.size() / (double) pageSize));
        int currentPage = Math.min(Math.max(page, 0), totalPages - 1);
        int from = currentPage * pageSize;
        int to = Math.min(from + pageSize, all.size());
        List<StaffProfile> pageItems = all.subList(from, to);

        model.addAttribute("staffs", pageItems);
        model.addAttribute("totalItems", all.size());
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("fromItem", all.isEmpty() ? 0 : (from + 1));
        model.addAttribute("toItem", to);

        model.addAttribute("assignmentsByStaff", staffService.getActiveAssignmentsByStaff());
        model.addAttribute("keyword", keyword);
        model.addAttribute("selectedSpecialty", specialty);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("selectedBranchId", branchId);

        model.addAttribute("specialties", staffService.getSpecialties());
        model.addAttribute("statuses", StaffStatus.values());
        model.addAttribute("branches", branchService.getAllBranches());

        return "staff/list";
    }

    // ===================== SCR-41.2: THÊM MỚI =====================

    @GetMapping("/create")
    public String showCreateForm(Model model, HttpSession session) {
        String redirect = checkAdmin(session);
        if (redirect != null) {
            return redirect;
        }
        addLayoutAttributes(session, model);
        fillFormModel(model, new StaffFormDTO(), null);
        return "staff/form";
    }

    @PostMapping("/create")
    public String createStaff(
            @ModelAttribute("staffForm") StaffFormDTO form,
            Model model,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        String redirect = checkAdmin(session);
        if (redirect != null) {
            return redirect;
        }

        try {
            StaffProfile saved = staffService.createStaff(form);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Đã tạo hồ sơ nhân viên " + saved.getStaffCode() + ".");
            return "redirect:/admin/staffs/" + saved.getStaffId();
        } catch (IllegalArgumentException ex) {
            addLayoutAttributes(session, model);
            fillFormModel(model, form, null);
            model.addAttribute("errorMessage", ex.getMessage());
            return "staff/form";
        }
    }

    // ===================== SCR-41.2: CẬP NHẬT =====================

    @GetMapping("/{id}/edit")
    public String showEditForm(
            @PathVariable Long id,
            Model model,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        String redirect = checkAdmin(session);
        if (redirect != null) {
            return redirect;
        }

        StaffProfile staff;
        try {
            staff = staffService.getStaffById(id);
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/staffs";
        }

        addLayoutAttributes(session, model);
        fillFormModel(model, toForm(staff), staff);
        return "staff/form";
    }

    @PostMapping("/{id}/edit")
    public String updateStaff(
            @PathVariable Long id,
            @ModelAttribute("staffForm") StaffFormDTO form,
            Model model,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        String redirect = checkAdmin(session);
        if (redirect != null) {
            return redirect;
        }

        try {
            staffService.updateStaff(id, form);
            redirectAttributes.addFlashAttribute("successMessage", "Đã cập nhật hồ sơ nhân viên.");
            return "redirect:/admin/staffs/" + id;
        } catch (IllegalArgumentException ex) {
            StaffProfile staff;
            try {
                staff = staffService.getStaffById(id);
            } catch (IllegalArgumentException notFound) {
                redirectAttributes.addFlashAttribute("errorMessage", notFound.getMessage());
                return "redirect:/admin/staffs";
            }
            form.setStaffCode(staff.getStaffCode());
            addLayoutAttributes(session, model);
            fillFormModel(model, form, staff);
            model.addAttribute("errorMessage", ex.getMessage());
            return "staff/form";
        }
    }

    // ===================== SCR-41.4: CHI TIẾT (kèm SCR-41.3) =====================

    @GetMapping("/{id}")
    public String staffDetail(
            @PathVariable Long id,
            Model model,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        String redirect = checkAdmin(session);
        if (redirect != null) {
            return redirect;
        }

        StaffProfile staff;
        try {
            staff = staffService.getStaffById(id);
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/staffs";
        }

        addLayoutAttributes(session, model);

        List<StaffBranchAssignment> assignments = staffService.getAssignments(id);
        List<StaffBranchAssignment> activeAssignments = assignments.stream()
                .filter(a -> a.getStatus() == AssignmentStatus.ACTIVE)
                .toList();
        long activeCount = activeAssignments.size();
        StaffBranchAssignment primary = activeAssignments.stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsPrimary()))
                .findFirst().orElse(null);

        Role primaryRole = staffService.resolveCurrentStaffRole(staff, activeAssignments);
        StaffAssignmentDTO formDto = new StaffAssignmentDTO();
        if (primaryRole != null) {
            formDto.setRoleId(primaryRole.getRoleId());
        }
        boolean isDoctorStaff = primaryRole != null && "BAC_SI".equalsIgnoreCase(primaryRole.getRoleName());

        model.addAttribute("staff", staff);
        model.addAttribute("assignments", assignments);
        model.addAttribute("activeAssignmentCount", activeCount);
        model.addAttribute("primaryAssignment", primary);
        model.addAttribute("primaryRole", primaryRole);
        model.addAttribute("isDoctorStaff", isDoctorStaff);
        model.addAttribute("assignmentForm", formDto);
        model.addAttribute("branches", activeBranches());
        model.addAttribute("roles", staffService.getAssignableRoles());
        model.addAttribute("statuses", StaffStatus.values());

        return "staff/detail";
    }

    // ===================== TRẠNG THÁI LÀM VIỆC =====================

    @PostMapping("/{id}/status")
    public String changeStatus(
            @PathVariable Long id,
            @RequestParam StaffStatus status,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        String redirect = checkAdmin(session);
        if (redirect != null) {
            return redirect;
        }
        return handle(id, redirectAttributes, "Đã đổi trạng thái làm việc.",
                () -> staffService.changeStatus(id, status));
    }

    // ===================== SCR-41.3: PHÂN CÔNG CHI NHÁNH =====================

    @PostMapping("/{id}/assignments")
    public String addAssignment(
            @PathVariable Long id,
            @ModelAttribute StaffAssignmentDTO assignment,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        String redirect = checkAdmin(session);
        if (redirect != null) {
            return redirect;
        }
        return handle(id, redirectAttributes, "Đã thêm phân công chi nhánh.",
                () -> staffService.addAssignment(id, assignment), "tab-assign");
    }

    @PostMapping("/{id}/assignments/{assignmentId}/edit")
    public String updateAssignment(
            @PathVariable Long id,
            @PathVariable Long assignmentId,
            @ModelAttribute StaffAssignmentDTO assignment,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        String redirect = checkAdmin(session);
        if (redirect != null) {
            return redirect;
        }
        return handle(id, redirectAttributes, "Đã cập nhật phân công chi nhánh.",
                () -> staffService.updateAssignment(id, assignmentId, assignment), "tab-assign");
    }

    @PostMapping("/{id}/assignments/{assignmentId}/end")
    public String endAssignment(
            @PathVariable Long id,
            @PathVariable Long assignmentId,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        String redirect = checkAdmin(session);
        if (redirect != null) {
            return redirect;
        }
        return handle(id, redirectAttributes, "Đã kết thúc phân công.",
                () -> staffService.endAssignment(id, assignmentId), "tab-assign");
    }

    @PostMapping("/{id}/assignments/{assignmentId}/primary")
    public String setPrimary(
            @PathVariable Long id,
            @PathVariable Long assignmentId,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        String redirect = checkAdmin(session);
        if (redirect != null) {
            return redirect;
        }
        return handle(id, redirectAttributes, "Đã đặt làm chi nhánh chính.",
                () -> staffService.setPrimaryAssignment(id, assignmentId), "tab-assign");
    }

    // ===================== HÀM HỖ TRỢ =====================

    // Dữ liệu dùng chung cho form thêm/sửa. staff = null khi thêm mới.
    private void fillFormModel(Model model, StaffFormDTO form, StaffProfile staff) {
        Long currentUserId = staff != null && staff.getUser() != null ? staff.getUser().getUserId() : null;
        Role staffRole = null;
        if (staff != null) {
            List<StaffBranchAssignment> activeAssignments = staffService.getAssignments(staff.getStaffId()).stream()
                    .filter(a -> a.getStatus() == AssignmentStatus.ACTIVE)
                    .toList();
            staffRole = staffService.resolveCurrentStaffRole(staff, activeAssignments);
        }
        Long staffRoleId = staffRole != null ? staffRole.getRoleId() : null;
        boolean isDoctorStaff = staffRole != null && "BAC_SI".equalsIgnoreCase(staffRole.getRoleName());

        model.addAttribute("staffForm", form);
        model.addAttribute("staff", staff);
        model.addAttribute("editMode", staff != null);
        model.addAttribute("staffRoleId", staffRoleId);
        model.addAttribute("isDoctorStaff", isDoctorStaff);
        model.addAttribute("branches", activeBranches());
        model.addAttribute("roles", staffService.getAssignableRoles());
        model.addAttribute("users", staffService.getLinkableUsers(currentUserId));
        model.addAttribute("genders", StaffGender.values());
        model.addAttribute("statuses", StaffStatus.values());
        model.addAttribute("specialtySuggestions", List.of(
                "Răng hàm mặt", "Chỉnh nha", "Implant", "Nội nha", "Nha chu",
                "Phục hình răng", "Nha khoa trẻ em", "Phẫu thuật hàm mặt"));
    }

    private StaffFormDTO toForm(StaffProfile s) {
        StaffFormDTO f = new StaffFormDTO();
        f.setStaffCode(s.getStaffCode());
        f.setFullName(s.getFullName());
        f.setGender(s.getGender());
        f.setDob(s.getDob());
        f.setPhoneNumber(s.getPhoneNumber());
        f.setEmail(s.getEmail());
        f.setIdCardNumber(s.getIdCardNumber());
        f.setDepartment(s.getDepartment());
        f.setPositionTitle(s.getPositionTitle());
        f.setSpecialty(s.getSpecialty());
        f.setPracticeLicenseNo(s.getPracticeLicenseNo());
        f.setMohDoctorCode(s.getMohDoctorCode());
        f.setBaseSalary(s.getBaseSalary());
        f.setAllowanceAmount(s.getAllowanceAmount());
        f.setInsuranceSalary(s.getInsuranceSalary());
        f.setBankAccountNumber(s.getBankAccountNumber());
        f.setBankName(s.getBankName());
        f.setHireDate(s.getHireDate());
        f.setStatus(s.getStatus());
        f.setUserId(s.getUser() != null ? s.getUser().getUserId() : null);
        return f;
    }

    // Chạy thao tác, gắn thông báo thành công/lỗi rồi quay lại trang chi tiết nhân viên.
    private String handle(Long staffId, RedirectAttributes redirectAttributes,
                          String successMessage, Runnable action) {
        return handle(staffId, redirectAttributes, successMessage, action, null);
    }

    private String handle(Long staffId, RedirectAttributes redirectAttributes,
                          String successMessage, Runnable action, String tab) {
        try {
            action.run();
            redirectAttributes.addFlashAttribute("successMessage", successMessage);
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        if (tab != null) {
            redirectAttributes.addFlashAttribute("activeTab", tab);
        }
        return "redirect:/admin/staffs/" + staffId;
    }
}
