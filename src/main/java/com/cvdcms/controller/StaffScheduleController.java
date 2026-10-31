package com.cvdcms.controller;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.User;
import com.cvdcms.entity.WorkShift;
import com.cvdcms.service.BranchService;
import com.cvdcms.service.StaffScheduleService;
import jakarta.servlet.http.HttpSession;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

// FT33 - Phân ca làm việc: SCR-33.1 (bảng phân ca tuần - ADMIN), SCR-33.3 (lịch cá nhân - mọi nhân viên).
@Controller
public class StaffScheduleController {

    private final StaffScheduleService scheduleService;
    private final BranchService branchService;
    private final com.cvdcms.repository.UserRepository userRepository;
    private final com.cvdcms.repository.AttendanceRecordRepository attendanceRepository;

    public StaffScheduleController(StaffScheduleService scheduleService, BranchService branchService, com.cvdcms.repository.UserRepository userRepository, com.cvdcms.repository.AttendanceRecordRepository attendanceRepository) {
        this.scheduleService = scheduleService;
        this.branchService = branchService;
        this.userRepository = userRepository;
        this.attendanceRepository = attendanceRepository;
    }

    private void addLayoutAttributes(HttpSession session, Model model) {
        model.addAttribute("roleNames", session.getAttribute("roleNames"));
        if (session.getAttribute("loggedInUser") instanceof User u) {
            model.addAttribute("displayName", u.getUsername());
        }
    }

    private boolean isLoggedIn(HttpSession session) {
        return session.getAttribute("loggedInUser") instanceof User;
    }

    private void requireAdmin(HttpSession session) {
        if (!isLoggedIn(session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }
        User user = (User) session.getAttribute("loggedInUser");
        boolean isAdmin = userRepository.hasActiveAdminRole(user.getUserId());
        if (!isAdmin && !userRepository.hasActivePermission(user.getUserId(), "SCHEDULE_MANAGE")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền xếp lịch làm việc.");
        }
    }

    // ===================== SCR-33.1: BẢNG PHÂN CA TUẦN =====================

    @GetMapping("/admin/schedules")
    public String weeklySchedule(@RequestParam(required = false) Long branchId,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week,
                                 Model model, HttpSession session) {
        if (!isLoggedIn(session)) {
            return "redirect:/login";
        }
        requireAdmin(session);
        addLayoutAttributes(session, model);

        List<Branch> branches = branchService.getActiveBranches();
        model.addAttribute("branches", branches);

        LocalDate monday = StaffScheduleService.mondayOf(week != null ? week : LocalDate.now());
        model.addAttribute("monday", monday);
        model.addAttribute("prevWeek", monday.minusWeeks(1));
        model.addAttribute("nextWeek", monday.plusWeeks(1));
        model.addAttribute("today", LocalDate.now());

        // Đảm bảo branchId hợp lệ thuộc danh sách chi nhánh đang hoạt động
        if (branchId != null) {
            Long targetBranchId = branchId;
            if (branches.stream().noneMatch(b -> b.getBranchId().equals(targetBranchId))) {
                branchId = null;
            }
        }

        if (branchId == null && !branches.isEmpty()) {
            branchId = branches.get(0).getBranchId();
        }
        model.addAttribute("selectedBranchId", branchId);

        if (branchId != null) {
            List<WorkShift> shifts = scheduleService.getShifts(branchId);
            model.addAttribute("shifts", shifts);
            model.addAttribute("matrix", scheduleService.buildWeekMatrix(branchId, monday, shifts));
            model.addAttribute("assignableStaff", scheduleService.getAssignableStaff(branchId));

            // Danh sách nhân viên đã chấm công hôm nay tại chi nhánh (staffId -> giờ check-in HH:mm)
            java.util.Map<Long, String> checkedInStaffTimesToday = new java.util.HashMap<>();
            LocalDate today = LocalDate.now();
            for (com.cvdcms.entity.AttendanceRecord a : attendanceRepository.findByBranchAndRange(branchId, today, today)) {
                if (a.getCheckInAt() != null) {
                    checkedInStaffTimesToday.put(a.getStaff().getStaffId(),
                            a.getCheckInAt().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
                }
            }
            model.addAttribute("checkedInStaffTimesToday", checkedInStaffTimesToday);
        }
        return "schedule/weekly";
    }

    @PostMapping("/admin/schedules/assign")
    public String assign(@RequestParam Long branchId,
                         @RequestParam Long staffId,
                         @RequestParam Long shiftId,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate workDate,
                         @RequestParam(required = false) String note,
                         RedirectAttributes ra, HttpSession session) {
        if (!isLoggedIn(session)) {
            return "redirect:/login";
        }
        requireAdmin(session);
        try {
            scheduleService.assign(branchId, staffId, shiftId, workDate, note);
            ra.addFlashAttribute("successMessage", "Đã xếp ca làm việc.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return redirectWeek(branchId, workDate);
    }

    @PostMapping("/admin/schedules/{id}/delete")
    public String delete(@PathVariable Long id,
                         @RequestParam Long branchId,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week,
                         RedirectAttributes ra, HttpSession session) {
        if (!isLoggedIn(session)) {
            return "redirect:/login";
        }
        requireAdmin(session);
        try {
            boolean hadAttendance = scheduleService.remove(id);
            if (hadAttendance) {
                ra.addFlashAttribute("successMessage", "Đã gỡ nhân viên khỏi ca trực và tự động hủy lượt chấm công nhầm hôm nay.");
            } else {
                ra.addFlashAttribute("successMessage", "Đã gỡ nhân viên khỏi ca trực.");
            }
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return redirectWeek(branchId, week);
    }

    @PostMapping("/admin/schedules/copy-week")
    public String copyWeek(@RequestParam Long branchId,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week,
                           RedirectAttributes ra, HttpSession session) {
        if (!isLoggedIn(session)) {
            return "redirect:/login";
        }
        requireAdmin(session);
        LocalDate monday = StaffScheduleService.mondayOf(week);
        int n = scheduleService.copyWeek(branchId, monday.minusWeeks(1), monday);
        if (n > 0) {
            ra.addFlashAttribute("successMessage", "Đã sao chép " + n + " ca từ tuần trước.");
        } else {
            ra.addFlashAttribute("errorMessage", "Tuần trước không có ca nào để sao chép (hoặc đã được xếp đủ).");
        }
        return redirectWeek(branchId, monday);
    }

    private String redirectWeek(Long branchId, LocalDate anyDayInWeek) {
        return "redirect:/admin/schedules?branchId=" + branchId
                + "&week=" + StaffScheduleService.mondayOf(anyDayInWeek);
    }

    // ===================== SCR-33.3: LỊCH LÀM VIỆC CÁ NHÂN =====================

    @GetMapping("/schedules/my")
    public String mySchedule(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week,
                             Model model, HttpSession session) {
        if (!(session.getAttribute("loggedInUser") instanceof User user)) {
            return "redirect:/login";
        }
        Object roleNamesObj = session.getAttribute("roleNames");
        boolean isAdmin = roleNamesObj instanceof Collection<?> c
                && c.stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(String.valueOf(r)) || "ROLE_ADMIN".equalsIgnoreCase(String.valueOf(r)));
        boolean isStaffRole = roleNamesObj instanceof Collection<?> c
                && c.stream().anyMatch(r -> "BAC_SI".equalsIgnoreCase(String.valueOf(r))
                        || "LE_TAN".equalsIgnoreCase(String.valueOf(r))
                        || "KE_TOAN".equalsIgnoreCase(String.valueOf(r))
                        || "QUAN_LY_KHO".equalsIgnoreCase(String.valueOf(r))
                        || "QUAN_LY_kHO".equalsIgnoreCase(String.valueOf(r)));
        if (isAdmin && !isStaffRole) {
            return "redirect:/admin/schedules";
        }
        if (!isAdmin && !userRepository.hasActivePermission(user.getUserId(), "SCHEDULE_MY")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền xem lịch trực cá nhân.");
        }
        addLayoutAttributes(session, model);

        LocalDate monday = StaffScheduleService.mondayOf(week != null ? week : LocalDate.now());
        model.addAttribute("monday", monday);
        model.addAttribute("prevWeek", monday.minusWeeks(1));
        model.addAttribute("nextWeek", monday.plusWeeks(1));
        model.addAttribute("today", LocalDate.now());

        StaffProfile staff = scheduleService.findStaffByUserId(user.getUserId());
        model.addAttribute("staff", staff);
        if (staff != null) {
            model.addAttribute("myWeek", scheduleService.getMyWeek(staff.getStaffId(), monday));
        }
        return "schedule/my";
    }

    // ===================== API: BÁC SĨ KHẢ DỤNG THEO NGÀY =====================

    @GetMapping("/api/schedules/available-doctors")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> availableDoctors(
            @RequestParam Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            HttpSession session) {
        if (!isLoggedIn(session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(scheduleService.getAvailableDoctors(branchId, date));
    }
}
