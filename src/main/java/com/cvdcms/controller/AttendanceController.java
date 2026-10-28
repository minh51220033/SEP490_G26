package com.cvdcms.controller;

import com.cvdcms.entity.AttendanceRecord;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.User;
import com.cvdcms.service.AttendanceService;
import com.cvdcms.service.BranchService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;

// FT33 - Chấm công: SCR-33.2 (Check-in/Check-out), SCR-33.4 (Lịch sử cá nhân & toàn chi nhánh).
@Controller
public class AttendanceController {

    private final AttendanceService attendanceService;
    private final BranchService branchService;

    public AttendanceController(AttendanceService attendanceService, BranchService branchService) {
        this.attendanceService = attendanceService;
        this.branchService = branchService;
    }

    private void addLayoutAttributes(HttpSession session, Model model) {
        model.addAttribute("roleNames", session.getAttribute("roleNames"));
        if (session.getAttribute("loggedInUser") instanceof User u) {
            model.addAttribute("displayName", u.getUsername());
        }
    }

    private void requireAdmin(HttpSession session) {
        Object roleNames = session.getAttribute("roleNames");
        boolean isAdmin = roleNames instanceof Collection<?> c
                && c.stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(String.valueOf(r))
                        || "ROLE_ADMIN".equalsIgnoreCase(String.valueOf(r)));
        if (!isAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền xem chấm công toàn chi nhánh.");
        }
    }

    // Lấy IP mạng của thiết bị; tự động ánh xạ IP card Wi-Fi thật khi demo trên localhost.
    private String clientIp(HttpServletRequest request) {
        return AttendanceService.resolveClientIp(request);
    }

    private void addMonthAttributes(Model model, YearMonth month) {
        model.addAttribute("month", month);
        model.addAttribute("prevMonth", month.minusMonths(1));
        model.addAttribute("nextMonth", month.plusMonths(1));
        model.addAttribute("currentMonth", YearMonth.now());
        model.addAttribute("today", LocalDate.now());
    }

    private boolean isPureAdmin(HttpSession session) {
        Object roleNamesObj = session.getAttribute("roleNames");
        boolean isAdmin = roleNamesObj instanceof Collection<?> c
                && c.stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(String.valueOf(r)) || "ROLE_ADMIN".equalsIgnoreCase(String.valueOf(r)));
        boolean isStaffRole = roleNamesObj instanceof Collection<?> c
                && c.stream().anyMatch(r -> "BAC_SI".equalsIgnoreCase(String.valueOf(r))
                        || "LE_TAN".equalsIgnoreCase(String.valueOf(r))
                        || "KE_TOAN".equalsIgnoreCase(String.valueOf(r))
                        || "QUAN_LY_KHO".equalsIgnoreCase(String.valueOf(r))
                        || "QUAN_LY_kHO".equalsIgnoreCase(String.valueOf(r)));
        return isAdmin && !isStaffRole;
    }

    // ===================== SCR-33.2 + SCR-33.4 (CÁ NHÂN) =====================

    @GetMapping("/attendance/my")
    public String myAttendance(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            Model model, HttpSession session, HttpServletRequest request) {
        if (!(session.getAttribute("loggedInUser") instanceof User user)) {
            return "redirect:/login";
        }
        if (isPureAdmin(session)) {
            return "redirect:/admin/attendance";
        }
        addLayoutAttributes(session, model);

        YearMonth selected = month != null ? month : YearMonth.now();
        addMonthAttributes(model, selected);
        model.addAttribute("clientIp", clientIp(request));

        StaffProfile staff = attendanceService.findStaffByUserId(user.getUserId());
        model.addAttribute("staff", staff);
        if (staff != null) {
            LocalDate today = LocalDate.now();
            AttendanceRecord todayRecord = attendanceService.findRecord(staff.getStaffId(), today);
            model.addAttribute("todayShifts", attendanceService.getTodayShifts(staff.getStaffId(), today));
            model.addAttribute("todayRecord", todayRecord);

            List<AttendanceRecord> history = attendanceService.getMyHistory(staff.getStaffId(), selected);
            model.addAttribute("history", history);
            model.addAttribute("summary", attendanceService.summarize(history));
        }
        return "attendance/my";
    }

    @PostMapping("/attendance/check-in")
    public String checkIn(RedirectAttributes ra, HttpSession session, HttpServletRequest request) {
        if (!(session.getAttribute("loggedInUser") instanceof User user)) {
            return "redirect:/login";
        }
        if (isPureAdmin(session)) {
            ra.addFlashAttribute("errorMessage", "Tài khoản Quản trị hệ thống không thực hiện chấm công cá nhân.");
            return "redirect:/admin/attendance";
        }
        try {
            AttendanceRecord r = attendanceService.checkIn(user.getUserId(), clientIp(request));
            ra.addFlashAttribute("successMessage", r.getStatus().name().equals("LATE")
                    ? "Check-in thành công (đi muộn so với giờ bắt đầu ca)."
                    : "Check-in thành công. Chúc bạn một ngày làm việc tốt!");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/attendance/my";
    }

    @PostMapping("/attendance/check-out")
    public String checkOut(RedirectAttributes ra, HttpSession session, HttpServletRequest request) {
        if (!(session.getAttribute("loggedInUser") instanceof User user)) {
            return "redirect:/login";
        }
        if (isPureAdmin(session)) {
            ra.addFlashAttribute("errorMessage", "Tài khoản Quản trị hệ thống không thực hiện chấm công cá nhân.");
            return "redirect:/admin/attendance";
        }
        try {
            attendanceService.checkOut(user.getUserId(), clientIp(request));
            ra.addFlashAttribute("successMessage", "Check-out thành công. Hẹn gặp lại bạn!");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/attendance/my";
    }

    // ===================== SCR-33.4 (TOÀN CHI NHÁNH - ADMIN) =====================

    @GetMapping("/admin/attendance")
    public String branchAttendance(@RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            Model model, HttpSession session) {
        if (!(session.getAttribute("loggedInUser") instanceof User)) {
            return "redirect:/login";
        }
        requireAdmin(session);
        addLayoutAttributes(session, model);

        YearMonth selected = month != null ? month : YearMonth.now();
        addMonthAttributes(model, selected);

        List<Branch> branches = branchService.getActiveBranches();
        model.addAttribute("branches", branches);
        if (branchId == null && !branches.isEmpty()) {
            branchId = branches.get(0).getBranchId();
        }
        model.addAttribute("selectedBranchId", branchId);

        if (branchId != null) {
            List<AttendanceRecord> records = attendanceService.getBranchHistory(branchId, selected);
            model.addAttribute("records", records);
            model.addAttribute("summary", attendanceService.summarize(records));
            model.addAttribute("staffSummary", attendanceService.summarizeByStaff(records));
        }
        return "attendance/branch";
    }
}
