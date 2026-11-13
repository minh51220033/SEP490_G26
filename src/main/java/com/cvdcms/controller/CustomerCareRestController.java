package com.cvdcms.controller;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.User;
import com.cvdcms.service.CustomerCareNotificationService;
import com.cvdcms.service.CustomerCareService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * REST Controller cung cấp các API cho chức năng Quản lý Chăm sóc khách hàng (FT44 - UC-26):
 * - SCR-44.1: KPI Summary & Danh sách công việc CSKH.
 * - SCR-44.2: Lọc theo chi nhánh, nhân viên, trạng thái và các nhóm bệnh nhân.
 * - SCR-44.3: Lập lịch / Phân công CSKH thủ công & Tự động tổng hợp công việc.
 * - SCR-44.4: Chi tiết công việc & Ghi kết quả liên hệ (kèm hẹn gọi lại next_retry_at) + Xem hồ sơ khám lâm sàng / lời dặn (#tab-instructions).
 * - SCR-44.5: Chuyển Bác sĩ hỗ trợ chuyên môn & Nhập phản hồi chuyên môn của Bác sĩ.
 * - SCR-44.6: Xem Timeline lịch sử chăm sóc bệnh nhân.
 */
@RestController
@RequestMapping("/api/customer-care")
public class CustomerCareRestController {

    private final CustomerCareService customerCareService;

    @Autowired(required = false)
    private CustomerCareNotificationService notificationService;

    public CustomerCareRestController(CustomerCareService customerCareService) {
        this.customerCareService = customerCareService;
    }

    @GetMapping("/branches")
    public Map<String, Object> getBranches(HttpSession session) {
        User user = requireUser(session);
        return customerCareService.getAccessibleBranches(user.getUserId());
    }

    @GetMapping("/lookups")
    public Map<String, Object> getLookups(
            @RequestParam(required = false) Long branchId,
            HttpSession session) {
        User user = requireUser(session);
        return customerCareService.getLookups(branchId, user.getUserId());
    }

    @GetMapping("/summary")
    public Map<String, Object> getSummary(
            @RequestParam(required = false) Long branchId,
            HttpSession session) {
        User user = requireUser(session);
        return customerCareService.getSummary(branchId, user.getUserId());
    }

    @GetMapping("/tasks")
    public Page<CustomerCareTaskDTO> listTasks(
            @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "ACTIONABLE") String tabGroup,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long assignedStaffId,
            @RequestParam(required = false) Long escalatedToDoctorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String dueFilter,
            @RequestParam(required = false) String careGroupFilter,
            @RequestParam(required = false) String doctorResponseFilter,
            @RequestParam(defaultValue = "false") Boolean myTasksOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            HttpSession session) {

        User user = requireUser(session);
        // Chuẩn hóa pageSize (chấp nhận 10, 25, 50, 100) theo quy chuẩn MISA AMIS
        if (size != 10 && size != 25 && size != 50 && size != 100) {
            size = 10;
        }
        Pageable pageable = PageRequest.of(Math.max(0, page), size);
        return customerCareService.searchTasks(
                branchId, tabGroup, status, assignedStaffId, escalatedToDoctorId,
                fromDate, toDate, keyword, dueFilter, careGroupFilter,
                doctorResponseFilter, myTasksOnly, pageable, user.getUserId()
        );
    }

    @GetMapping("/calendar")
    public List<CustomerCareTaskDTO> calendarTasks(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long assignedStaffId,
            @RequestParam(required = false) Long escalatedToDoctorId,
            @RequestParam(required = false) String careGroupFilter,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") Boolean myTasksOnly,
            HttpSession session) {
        User user = requireUser(session);
        return customerCareService.getCalendarTasks(
                branchId, startDate, endDate, assignedStaffId, escalatedToDoctorId,
                careGroupFilter, status, myTasksOnly, user.getUserId()
        );
    }

    @GetMapping("/notifications")
    public Map<String, Object> getNotifications(
            @RequestParam(defaultValue = "30") int limit,
            HttpSession session) {
        User user = requireUser(session);
        customerCareService.requireCareRole(user.getUserId());
        if (notificationService == null) {
            return Map.of("unreadCount", 0, "notifications", List.of());
        }
        return notificationService.getUserNotifications(user.getUserId(), limit);
    }

    @PostMapping("/notifications/{notificationId}/read")
    public CustomerCareNotificationDTO markNotificationRead(
            @PathVariable Long notificationId,
            HttpSession session) {
        User user = requireUser(session);
        customerCareService.requireCareRole(user.getUserId());
        if (notificationService == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Dịch vụ thông báo chưa sẵn sàng.");
        }
        return notificationService.markAsRead(notificationId, user.getUserId());
    }

    @PostMapping("/notifications/read-all")
    public Map<String, Object> markAllNotificationsRead(HttpSession session) {
        User user = requireUser(session);
        customerCareService.requireCareRole(user.getUserId());
        if (notificationService == null) {
            return Map.of("updatedCount", 0, "unreadCount", 0);
        }
        return notificationService.markAllAsRead(user.getUserId());
    }

    @GetMapping("/tasks/{taskId}")
    public CustomerCareTaskDTO getTaskDetail(
            @PathVariable Long taskId,
            HttpSession session) {
        User user = requireUser(session);
        return customerCareService.getTaskDetail(taskId, user.getUserId());
    }

    @GetMapping("/visits/{visitId}/examination-summary")
    public Map<String, Object> getVisitExaminationSummary(
            @PathVariable Long visitId,
            HttpSession session) {
        User user = requireUser(session);
        return customerCareService.getVisitExaminationSummary(visitId, user.getUserId());
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerCareTaskDTO createTask(
            @RequestBody CustomerCareCreateDTO dto,
            HttpSession session) {
        User user = requireUser(session);
        return customerCareService.createTask(dto, user.getUserId());
    }

    @PutMapping("/tasks/{taskId}")
    public CustomerCareTaskDTO updateTask(
            @PathVariable Long taskId,
            @RequestBody CustomerCareCreateDTO dto,
            HttpSession session) {
        User user = requireUser(session);
        return customerCareService.updateTask(taskId, dto, user.getUserId());
    }

    @PostMapping("/tasks/auto-generate")
    public Map<String, Object> autoGenerateTasks(
            @RequestParam Long branchId,
            HttpSession session) {
        User user = requireUser(session);
        return customerCareService.autoGenerateTasks(branchId, user.getUserId());
    }

    @PostMapping("/tasks/{taskId}/interactions")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerCareTaskDTO recordInteraction(
            @PathVariable Long taskId,
            @RequestBody CustomerCareInteractionCreateDTO dto,
            HttpSession session) {
        User user = requireUser(session);
        return customerCareService.recordInteraction(taskId, dto, user.getUserId());
    }

    @PostMapping("/visits/{visitId}/interactions")
    public ResponseEntity<Map<String, Object>> recordVisitInteraction(
            @PathVariable Long visitId,
            @RequestBody CustomerCareInteractionCreateDTO dto,
            HttpSession session) {
        User user = requireUser(session);
        CustomerCareTaskDTO taskDTO = customerCareService.recordVisitInteraction(visitId, dto, user.getUserId());
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Đã lưu lịch sử cuộc gọi / tương tác CSKH thành công!",
                "task", taskDTO
        ));
    }

    @PostMapping("/tasks/{taskId}/escalate")
    public CustomerCareTaskDTO escalateToDoctor(
            @PathVariable Long taskId,
            @RequestBody CustomerCareEscalateDTO dto,
            HttpSession session) {
        User user = requireUser(session);
        return customerCareService.escalateToDoctor(taskId, dto, user.getUserId());
    }

    @PostMapping("/tasks/{taskId}/doctor-response")
    public CustomerCareTaskDTO submitDoctorResponse(
            @PathVariable Long taskId,
            @RequestBody CustomerCareEscalateDTO dto,
            HttpSession session) {
        User user = requireUser(session);
        return customerCareService.submitDoctorResponse(taskId, dto, user.getUserId());
    }

    @PostMapping("/tasks/{taskId}/reopen")
    public CustomerCareTaskDTO reopenTask(
            @PathVariable Long taskId,
            @RequestBody(required = false) Map<String, String> body,
            jakarta.servlet.http.HttpServletRequest request,
            HttpSession session) {
        User user = requireUser(session);
        String reason = body != null ? body.get("reason") : null;
        String ip = request != null ? request.getRemoteAddr() : null;
        return customerCareService.reopenTask(taskId, reason, user.getUserId(), ip);
    }

    @GetMapping("/interactions")
    public Page<CustomerCareInteractionDTO> listInteractions(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long profileId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String timeRange,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Long staffId,
            @RequestParam(required = false) Long doctorId,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String contactResult,
            @RequestParam(required = false) String careGroupFilter,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size,
            HttpSession session) {
        User user = requireUser(session);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)));
        return customerCareService.searchInteractions(
                branchId, profileId, keyword, timeRange, fromDate, toDate,
                staffId, doctorId, channel, contactResult, careGroupFilter,
                pageable, user.getUserId()
        );
    }

    @GetMapping("/patients/{profileId}/history")
    public Map<String, Object> getPatientCareHistory(
            @PathVariable Long profileId,
            @RequestParam(required = false) Long branchId,
            HttpSession session) {
        User user = requireUser(session);
        return customerCareService.getPatientCareHistory(profileId, branchId, user.getUserId());
    }

    private User requireUser(HttpSession session) {
        Object loggedIn = session != null ? session.getAttribute("loggedInUser") : null;
        if (!(loggedIn instanceof User user) || user.getUserId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Phiên đăng nhập không hợp lệ. Vui lòng đăng nhập lại."
            );
        }
        return user;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        String msg = ex.getReason() != null ? ex.getReason() : "Không thể xử lý yêu cầu CSKH.";
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", msg));
    }
}
