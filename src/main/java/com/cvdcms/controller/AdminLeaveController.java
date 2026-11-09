package com.cvdcms.controller;

import com.cvdcms.entity.Appointment;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.LeaveRequest;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.LeaveStatus;
import com.cvdcms.service.BranchService;
import com.cvdcms.service.LeaveRequestService;
import jakarta.servlet.http.HttpSession;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.*;

// FT42: Quản lý & Phê duyệt Yêu cầu Nghỉ phép / Khóa lịch Bác sĩ (SCR-42.3, SCR-42.4, SCR-42.5)
@Controller
public class AdminLeaveController {

    private final LeaveRequestService leaveService;
    private final BranchService branchService;

    public AdminLeaveController(LeaveRequestService leaveService, BranchService branchService) {
        this.leaveService = leaveService;
        this.branchService = branchService;
    }

    private User getSessionUser(HttpSession session) {
        if (session != null && session.getAttribute("loggedInUser") instanceof User u) {
            return u;
        }
        return null;
    }

    // =========================================================================
    // SCR-42.3: DANH SÁCH YÊU CẦU NGHỈ PHÉP CHỜ DUYỆT & TỔNG HỢP
    // =========================================================================

    @GetMapping("/admin/leave-requests")
    public String adminLeaveList(@RequestParam(required = false) Long branchId,
                                 @RequestParam(required = false) LeaveStatus status,
                                 @RequestParam(required = false) Long staffId,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                 @RequestParam(required = false) String keyword,
                                 Model model,
                                 HttpSession session) {
        User user = getSessionUser(session);
        if (user == null) {
            return "redirect:/login";
        }

        model.addAttribute("activeMenu", "admin-leaves");

        // Danh sách đơn xin nghỉ theo bộ lọc
        List<LeaveRequest> requests = leaveService.filterAdminLeaveRequests(
                branchId, status, staffId, startDate, endDate, keyword
        );
        model.addAttribute("requests", requests);

        // Số liệu tổng hợp (Badge counter)
        Map<String, Long> summary = leaveService.getAdminSummaryCounts();
        model.addAttribute("summary", summary);

        // Danh mục lọc
        List<Branch> branches = branchService.getActiveBranches();
        model.addAttribute("branches", branches);

        List<StaffProfile> doctors = leaveService.getAllDoctors();
        model.addAttribute("doctors", doctors);

        model.addAttribute("statuses", LeaveStatus.values());

        // Giữ lại tham số lọc
        model.addAttribute("selectedBranchId", branchId);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("selectedStaffId", staffId);
        model.addAttribute("selectedStartDate", startDate);
        model.addAttribute("selectedEndDate", endDate);
        model.addAttribute("selectedKeyword", keyword);

        return "leave/admin_list";
    }

    // =========================================================================
    // SCR-42.4: PHÊ DUYỆT ĐƠN NGHỈ PHÉP & TỰ ĐỘNG KHÓA LỊCH
    // =========================================================================

    @PostMapping("/admin/leave-requests/{id}/approve")
    public String approveLeaveRequest(@PathVariable("id") Long leaveId,
                                      @RequestParam(required = false) String reviewNote,
                                      RedirectAttributes ra,
                                      HttpSession session) {
        User user = getSessionUser(session);
        if (user == null) {
            return "redirect:/login";
        }

        try {
            int cancelledAppointments = leaveService.approveLeaveRequest(leaveId, user.getUserId(), reviewNote);
            String successMsg = "Đã phê duyệt đơn nghỉ phép #" + leaveId + " thành công! Lịch làm việc của Bác sĩ đã chuyển sang trạng thái Nghỉ phép.";
            if (cancelledAppointments > 0) {
                successMsg += " Hệ thống đã tự động hủy " + cancelledAppointments + " lịch hẹn bị ảnh hưởng, gửi thông báo cho bệnh nhân và báo lễ tân dời lịch.";
            }
            ra.addFlashAttribute("successMessage", successMsg);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }

        return "redirect:/admin/leave-requests";
    }

    // =========================================================================
    // SCR-42.4: TỪ CHỐI ĐƠN NGHỈ PHÉP
    // =========================================================================

    @PostMapping("/admin/leave-requests/{id}/reject")
    public String rejectLeaveRequest(@PathVariable("id") Long leaveId,
                                     @RequestParam String reviewNote,
                                     RedirectAttributes ra,
                                     HttpSession session) {
        User user = getSessionUser(session);
        if (user == null) {
            return "redirect:/login";
        }

        try {
            leaveService.rejectLeaveRequest(leaveId, user.getUserId(), reviewNote);
            ra.addFlashAttribute("successMessage", "Đã từ chối đơn nghỉ phép #" + leaveId + ".");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }

        return "redirect:/admin/leave-requests";
    }

    // =========================================================================
    // SCR-42.5: API DANH SÁCH LỊCH HẸN BỆNH NHÂN BỊ ẢNH HƯỞNG
    // =========================================================================

    @GetMapping("/admin/leave-requests/{id}/affected-appointments")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getAffectedAppointments(@PathVariable("id") Long leaveId,
                                                                             HttpSession session) {
        User user = getSessionUser(session);
        if (user == null) {
            return ResponseEntity.status(401).build();
        }

        List<Appointment> list = leaveService.getAffectedAppointments(leaveId);
        List<Map<String, Object>> response = new ArrayList<>();
        for (Appointment a : list) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("appointmentId", a.getAppointmentId());
            map.put("appointmentCode", a.getAppointmentCode());
            map.put("patientName", a.getPatientProfile() != null ? a.getPatientProfile().getFullName() : "N/A");
            map.put("patientCode", a.getPatientProfile() != null ? a.getPatientProfile().getPatientCode() : "");
            map.put("phoneNumber", a.getPatientProfile() != null ? a.getPatientProfile().getPhoneNumber() : "");
            map.put("appointmentDate", a.getAppointmentDate() != null ? a.getAppointmentDate().toString() : "");
            map.put("expectedTime", a.getExpectedTime() != null ? a.getExpectedTime().toString() : "Chưa xác định");
            map.put("serviceName", a.getService() != null ? a.getService().getServiceName() : "Khám tổng quát");
            map.put("branchName", a.getBranch() != null ? a.getBranch().getBranchName() : "");
            map.put("status", a.getStatus() != null ? a.getStatus().name() : "");
            response.add(map);
        }

        return ResponseEntity.ok(response);
    }
}

