package com.cvdcms.controller;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.User;
import com.cvdcms.entity.WorkShift;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.BranchService;
import com.cvdcms.service.WorkShiftService;
import jakarta.servlet.http.HttpSession;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalTime;
import java.util.List;

// FT33 – Cấu hình ca làm việc (SCR-33.0): CRUD WorkShift theo chi nhánh.
@Controller
public class WorkShiftController {

    private final WorkShiftService workShiftService;
    private final BranchService branchService;
    private final UserRepository userRepository;

    public WorkShiftController(WorkShiftService workShiftService,
                               BranchService branchService,
                               UserRepository userRepository) {
        this.workShiftService = workShiftService;
        this.branchService = branchService;
        this.userRepository = userRepository;
    }

    // ===== HELPERS =====

    private boolean isLoggedIn(HttpSession session) {
        return session.getAttribute("loggedInUser") instanceof User;
    }

    private void requireAdmin(HttpSession session) {
        if (!isLoggedIn(session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }
        User user = (User) session.getAttribute("loggedInUser");
        boolean hasAdmin = userRepository.hasActiveAdminRole(user.getUserId());
        boolean hasPermission = userRepository.hasActivePermission(user.getUserId(), "SCHEDULE_MANAGE")
                || userRepository.hasActivePermission(user.getUserId(), "STAFF_MANAGE");
        if (!hasAdmin && !hasPermission) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền cấu hình ca làm việc.");
        }
    }

    private Long resolveSelectedBranch(Long branchId, HttpSession session) {
        if (branchId != null) return branchId;
        Object sb = session.getAttribute("currentBranchId");
        if (sb instanceof Long l) return l;
        if (sb instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        List<Branch> branches = branchService.getActiveBranches();
        return branches.isEmpty() ? null : branches.get(0).getBranchId();
    }

    // ===================== GET: Danh sách ca =====================

    @GetMapping("/admin/work-shifts")
    public String listShifts(@RequestParam(required = false) Long branchId,
                             Model model, HttpSession session) {
        if (!isLoggedIn(session)) return "redirect:/login";
        requireAdmin(session);

        Long selectedBranchId = resolveSelectedBranch(branchId, session);
        List<Branch> branches = branchService.getActiveBranches();

        model.addAttribute("branches", branches);
        model.addAttribute("selectedBranchId", selectedBranchId);
        model.addAttribute("roleNames", session.getAttribute("roleNames"));
        model.addAttribute("permissionCodes", session.getAttribute("permissionCodes"));
        model.addAttribute("isAdmin", isAdmin(session));

        if (selectedBranchId != null) {
            List<WorkShift> shifts = workShiftService.getShiftsByBranch(selectedBranchId);
            model.addAttribute("shifts", shifts);
        }

        return "shift/list";
    }

    // ===================== POST: Tạo ca mới =====================

    @PostMapping("/admin/work-shifts")
    public String createShift(@RequestParam Long branchId,
                              @RequestParam String shiftCode,
                              @RequestParam String shiftName,
                              @RequestParam @DateTimeFormat(pattern = "HH:mm") LocalTime startTime,
                              @RequestParam @DateTimeFormat(pattern = "HH:mm") LocalTime endTime,
                              RedirectAttributes ra, HttpSession session) {
        if (!isLoggedIn(session)) return "redirect:/login";
        requireAdmin(session);
        try {
            workShiftService.create(branchId, shiftCode, shiftName, startTime, endTime);
            ra.addFlashAttribute("successMessage", "Tạo ca '" + shiftName.trim() + "' thành công.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/work-shifts?branchId=" + branchId;
    }

    // ===================== POST: Cập nhật ca =====================

    @PostMapping("/admin/work-shifts/{id}/update")
    public String updateShift(@PathVariable Long id,
                              @RequestParam Long branchId,
                              @RequestParam String shiftCode,
                              @RequestParam String shiftName,
                              @RequestParam @DateTimeFormat(pattern = "HH:mm") LocalTime startTime,
                              @RequestParam @DateTimeFormat(pattern = "HH:mm") LocalTime endTime,
                              RedirectAttributes ra, HttpSession session) {
        if (!isLoggedIn(session)) return "redirect:/login";
        requireAdmin(session);
        try {
            workShiftService.update(id, shiftCode, shiftName, startTime, endTime);
            ra.addFlashAttribute("successMessage", "Cập nhật ca thành công.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/work-shifts?branchId=" + branchId;
    }

    // ===================== POST: Bật / Tắt trạng thái ca =====================

    @PostMapping("/admin/work-shifts/{id}/toggle")
    public String toggleShift(@PathVariable Long id,
                              @RequestParam Long branchId,
                              RedirectAttributes ra, HttpSession session) {
        if (!isLoggedIn(session)) return "redirect:/login";
        requireAdmin(session);
        try {
            WorkShift s = workShiftService.toggleActive(id);
            ra.addFlashAttribute("successMessage",
                    "Ca '" + s.getShiftName() + "' đã được " +
                    (Boolean.TRUE.equals(s.getIsActive()) ? "bật" : "tắt") + ".");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/work-shifts?branchId=" + branchId;
    }

    // ===================== POST: Xóa ca =====================

    @PostMapping("/admin/work-shifts/{id}/delete")
    public String deleteShift(@PathVariable Long id,
                              @RequestParam Long branchId,
                              RedirectAttributes ra, HttpSession session) {
        if (!isLoggedIn(session)) return "redirect:/login";
        requireAdmin(session);
        try {
            workShiftService.delete(id);
            ra.addFlashAttribute("successMessage", "Đã xóa ca làm việc thành công.");
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", "Không thể xóa ca làm việc này vì đã có dữ liệu phân ca hoặc chấm công liên kết. Vui lòng sử dụng tính năng 'Tắt ca'.");
        }
        return "redirect:/admin/work-shifts?branchId=" + branchId;
    }

    private boolean isAdmin(HttpSession session) {
        if (!(session.getAttribute("loggedInUser") instanceof User u)) return false;
        return userRepository.hasActiveAdminRole(u.getUserId());
    }
}

