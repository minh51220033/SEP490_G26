package com.cvdcms.controller;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.User;
import com.cvdcms.service.CustomerCareService;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
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
            @RequestParam(defaultValue = "ALL") String tabGroup,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long assignedStaffId,
            @RequestParam(required = false) Long escalatedToDoctorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            HttpSession session) {

        User user = requireUser(session);
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tham số phân trang không hợp lệ.");
        }
        Pageable pageable = PageRequest.of(page, size);
        return customerCareService.searchTasks(
                branchId, tabGroup, status, assignedStaffId, escalatedToDoctorId,
                fromDate, toDate, keyword, pageable, user.getUserId()
        );
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
