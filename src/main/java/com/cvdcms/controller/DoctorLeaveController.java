package com.cvdcms.controller;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.LeaveRequest;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.User;
import com.cvdcms.entity.WorkShift;
import com.cvdcms.entity.enums.LeaveType;
import com.cvdcms.service.LeaveRequestService;
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
import java.util.*;

// FT42: Quản lý Yêu cầu Nghỉ phép & Khóa lịch Bác sĩ (SCR-42.1, SCR-42.2)
// CHỈ DÀNH RIÊNG CHO: Bác sĩ điều trị (BAC_SI). Admin có màn hình duyệt riêng tại /admin/leave-requests.
@Controller
public class DoctorLeaveController {

    private final LeaveRequestService leaveService;

    public DoctorLeaveController(LeaveRequestService leaveService) {
        this.leaveService = leaveService;
    }

    private User getSessionUser(HttpSession session) {
        if (session != null && session.getAttribute("loggedInUser") instanceof User u) {
            return u;
        }
        return null;
    }

    /**
     * Kiểm tra người dùng có phải là Bác sĩ điều trị hay không.
     * Quản trị viên (ADMIN) và các vai trò khác (Lễ tân, Kế toán, Thủ kho, Bệnh nhân) KHÔNG được phép truy cập màn hình này.
     * Theo tài liệu FT42, chỉ Bác sĩ mới nộp đơn xin nghỉ & khóa lịch khám của chính mình.
     */
    private boolean isDoctor(HttpSession session, StaffProfile staff) {
        if (session == null) {
            return false;
        }
        Object roleNamesObj = session.getAttribute("roleNames");
        if (roleNamesObj instanceof Collection<?> roles) {
            boolean isDoctor = roles.stream().anyMatch(r ->
                    "BAC_SI".equalsIgnoreCase(String.valueOf(r)) || "ROLE_BAC_SI".equalsIgnoreCase(String.valueOf(r))
            );
            if (isDoctor) {
                return true;
            }
        }
        // Cho phép nhân sự có quyền HR_LEAVE_REQUESTS
        Object permsObj = session.getAttribute("permissionCodes");
        if (staff != null && permsObj instanceof Collection<?> perms) {
            boolean hasLeavePermission = perms.stream().anyMatch(p -> "HR_LEAVE_REQUESTS".equalsIgnoreCase(String.valueOf(p)));
            if (hasLeavePermission) {
                return true;
            }
        }
        // Kiểm tra mã nhân viên nếu bắt đầu bằng BS (Bác sĩ)
        return staff != null && staff.getStaffCode() != null && staff.getStaffCode().toUpperCase().startsWith("BS");
    }

    private boolean isAdmin(HttpSession session) {
        if (session == null) {
            return false;
        }
        Object roleNamesObj = session.getAttribute("roleNames");
        if (roleNamesObj instanceof Collection<?> roles) {
            return roles.stream().anyMatch(r ->
                    "ADMIN".equalsIgnoreCase(String.valueOf(r)) || "ROLE_ADMIN".equalsIgnoreCase(String.valueOf(r))
            );
        }
        return false;
    }

    // =========================================================================
    // SCR-42.2: DANH SÁCH & THEO DÕI ĐƠN NGHỈ CỦA BÁC SĨ (KÈM MODAL SCR-42.1)
    // =========================================================================

    @GetMapping("/doctor/leave-requests")
    public String doctorLeaveRequests(Model model, HttpSession session) {
        User user = getSessionUser(session);
        if (user == null) {
            return "redirect:/login";
        }

        StaffProfile doctor = leaveService.getStaffByUserId(user.getUserId());

        // CHẶN BẢO VỆ PHÂN QUYỀN CHUẨN TÀI LIỆU: Chỉ Bác sĩ mới có quyền
        if (!isDoctor(session, doctor)) {
            // Nếu là Admin đi nhầm vào màn hình Bác sĩ -> Tự động chuyển hướng sang màn hình Duyệt đơn của Admin
            if (isAdmin(session)) {
                return "redirect:/admin/leave-requests";
            }
            session.setAttribute("flashPermissionNotice",
                    "Chức năng Gửi yêu cầu nghỉ phép & Khóa lịch khám (FT42) dành riêng cho Bác sĩ điều trị. Bạn không có quyền truy cập màn hình này.");
            return "redirect:/home";
        }

        model.addAttribute("doctor", doctor);
        model.addAttribute("activeMenu", "leave-requests");

        List<LeaveRequest> requests = leaveService.getDoctorLeaveRequests(user.getUserId());
        model.addAttribute("requests", requests);

        Map<String, Long> summary = leaveService.getDoctorSummaryCounts(user.getUserId());
        model.addAttribute("summary", summary);

        // Danh sách cơ sở mà bác sĩ được phân công
        List<Branch> branches = leaveService.getDoctorBranches(user.getUserId());
        model.addAttribute("branches", branches);

        // Các loại hình nghỉ phép
        model.addAttribute("leaveTypes", LeaveType.values());

        // Ngày hiện tại & mốc 3 ngày (R8) để hiển thị gợi ý
        LocalDate today = LocalDate.now();
        model.addAttribute("today", today);
        model.addAttribute("rule8NoticeDate", today.plusDays(3));

        return "leave/doctor_list";
    }

    // =========================================================================
    // SCR-42.1: XỬ LÝ GỬI FORM YÊU CẦU NGHỈ PHÉP / KHÓA LỊCH
    // =========================================================================

    @PostMapping("/doctor/leave-requests")
    public String submitLeaveRequest(@RequestParam Long branchId,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                     @RequestParam(required = false) Long shiftId,
                                     @RequestParam(defaultValue = "ANNUAL") LeaveType leaveType,
                                     @RequestParam(defaultValue = "false") boolean isEmergency,
                                     @RequestParam String reason,
                                     RedirectAttributes ra,
                                     HttpSession session) {
        User user = getSessionUser(session);
        if (user == null) {
            return "redirect:/login";
        }

        StaffProfile doctor = leaveService.getStaffByUserId(user.getUserId());
        if (!isDoctor(session, doctor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ Bác sĩ điều trị mới có quyền gửi đơn xin nghỉ / khóa lịch khám.");
        }

        try {
            LeaveRequest saved = leaveService.submitDoctorLeave(
                    user.getUserId(), branchId, startDate, endDate, shiftId, leaveType, isEmergency, reason
            );
            ra.addFlashAttribute("successMessage",
                    "Đã gửi đơn xin nghỉ phép #" + saved.getLeaveId() + " thành công! Đơn đang ở trạng thái Chờ duyệt.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
            // Giữ lại các trường vừa nhập để user không phải gõ lại
            ra.addFlashAttribute("prevBranchId", branchId);
            ra.addFlashAttribute("prevStartDate", startDate);
            ra.addFlashAttribute("prevEndDate", endDate);
            ra.addFlashAttribute("prevShiftId", shiftId);
            ra.addFlashAttribute("prevLeaveType", leaveType);
            ra.addFlashAttribute("prevIsEmergency", isEmergency);
            ra.addFlashAttribute("prevReason", reason);
        }

        return "redirect:/doctor/leave-requests";
    }

    // =========================================================================
    // HỦY ĐƠN NGHỈ PHÉP (KHI ĐƠN ĐANG CHỜ DUYỆT PENDING)
    // =========================================================================

    @PostMapping("/doctor/leave-requests/{id}/cancel")
    public String cancelLeaveRequest(@PathVariable("id") Long leaveId,
                                     RedirectAttributes ra,
                                     HttpSession session) {
        User user = getSessionUser(session);
        if (user == null) {
            return "redirect:/login";
        }

        StaffProfile doctor = leaveService.getStaffByUserId(user.getUserId());
        if (!isDoctor(session, doctor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền thao tác trên đơn này.");
        }

        try {
            leaveService.cancelLeaveRequest(leaveId, user.getUserId());
            ra.addFlashAttribute("successMessage", "Đã hủy đơn xin nghỉ phép #" + leaveId + ".");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }

        return "redirect:/doctor/leave-requests";
    }

    // =========================================================================
    // API: LẤY DANH SÁCH CA LÀM VIỆC THEO CHI NHÁNH ĐỂ BÁC SĨ CHỌN NGHỈ THEO CA
    // =========================================================================

    @GetMapping("/api/doctor/shifts")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getShiftsForBranch(@RequestParam Long branchId, HttpSession session) {
        User user = getSessionUser(session);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        StaffProfile doctor = leaveService.getStaffByUserId(user.getUserId());
        if (!isDoctor(session, doctor)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        List<WorkShift> shifts = leaveService.getShiftsByBranch(branchId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (WorkShift s : shifts) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("shiftId", s.getShiftId());
            map.put("shiftCode", s.getShiftCode());
            map.put("shiftName", s.getShiftName());
            map.put("startTime", s.getStartTime() != null ? s.getStartTime().toString() : "");
            map.put("endTime", s.getEndTime() != null ? s.getEndTime().toString() : "");
            result.add(map);
        }
        return ResponseEntity.ok(result);
    }
}
