package com.cvdcms.controller;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.User;
import com.cvdcms.service.ClinicalWorkflowService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/doctor/workflows")
@RequiredArgsConstructor
public class ClinicalWorkflowRestController {

    private final ClinicalWorkflowService workflowService;
    private final com.cvdcms.security.DoctorVisitAccess doctorVisitAccess;

    @GetMapping
    public ResponseEntity<List<WorkflowTemplateResponseDTO>> getAllWorkflows() {
        return ResponseEntity.ok(workflowService.getAllTemplates());
    }

    @GetMapping("/{id}")
    public ResponseEntity<WorkflowTemplateResponseDTO> getWorkflowById(@PathVariable Long id) {
        return ResponseEntity.ok(workflowService.getTemplateById(id));
    }

    @GetMapping("/by-service/{serviceId}")
    public ResponseEntity<WorkflowTemplateResponseDTO> getWorkflowByService(@PathVariable Long serviceId) {
        WorkflowTemplateResponseDTO dto = workflowService.getTemplateByServiceId(serviceId);
        if (dto == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(dto);
    }

    @PostMapping
    public ResponseEntity<WorkflowTemplateResponseDTO> saveWorkflow(
            @RequestBody WorkflowTemplateRequestDTO requestDTO,
            HttpSession session) {
        User user = (User) session.getAttribute("loggedInUser");
        Long userId = user != null ? user.getUserId() : 1L;
        return ResponseEntity.ok(workflowService.saveTemplate(requestDTO, userId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteWorkflow(@PathVariable Long id) {
        workflowService.deleteTemplate(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/ai-extract")
    public ResponseEntity<WorkflowTemplateResponseDTO> extractFromAi(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        String imageBase64 = body.get("imageBase64");
        String mimeType = body.get("mimeType");

        WorkflowTemplateResponseDTO result = workflowService.parseWorkflowAi(text, imageBase64, mimeType);
        return ResponseEntity.ok(result);
    }

    // =====================================================================
    // FT43 - Thực hiện quy trình khám & điều trị theo lượt khám (UC-13)
    // =====================================================================

    /** SCR-43.1: tổng quan quy trình của lượt khám + dịch vụ có thể khởi tạo quy trình. */
    @GetMapping("/execution/visit/{visitId}")
    public ResponseEntity<WorkflowVisitOverviewDTO> getVisitOverview(@PathVariable Long visitId, HttpSession session) {
        doctorVisitAccess.require(session, visitId);
        return ResponseEntity.ok(workflowService.getVisitOverview(visitId));
    }

    @GetMapping("/execution/{visitId}/{serviceId}")
    public ResponseEntity<WorkflowExecutionDTO> getExecution(
            @PathVariable Long visitId,
            @PathVariable Long serviceId,
            HttpSession session) {
        doctorVisitAccess.require(session, visitId);
        WorkflowExecutionDTO dto = workflowService.getOrCreateExecution(visitId, serviceId, requireUserId(session));
        if (dto == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(dto);
    }

    /** Khởi tạo quy trình cho lượt khám theo mẫu đang áp dụng của dịch vụ. */
    @PostMapping("/execution/start")
    public ResponseEntity<WorkflowExecutionDTO> startExecution(
            @RequestBody Map<String, Object> body,
            HttpSession session) {
        Long visitId = longOf(body, "visitId");
        Long serviceId = longOf(body, "serviceId");
        doctorVisitAccess.require(session, visitId);
        return ResponseEntity.ok(workflowService.startExecution(visitId, serviceId, requireUserId(session)));
    }

    /** SCR-43.2, 43.3: START / COMPLETE / PAUSE / RESUME / SKIP trên bước hiện tại. */
    @PostMapping("/execution/step")
    public ResponseEntity<WorkflowExecutionDTO> updateStepExecution(
            @RequestBody Map<String, Object> body,
            HttpSession session) {
        Long executionId = longOf(body, "executionId");
        doctorVisitAccess.requireWorkflowExecution(session, executionId);
        Long stepId = longOf(body, "stepId");
        String action = body.get("action") != null ? body.get("action").toString() : null;
        String note = body.get("note") != null ? body.get("note").toString() : null;
        boolean criteriaConfirmed = body.get("criteriaConfirmed") != null
                && Boolean.parseBoolean(body.get("criteriaConfirmed").toString());
        if ((action == null || action.isBlank()) && body.get("status") != null) {
            String st = body.get("status").toString().trim().toUpperCase();
            action = switch (st) {
                case "IN_PROGRESS" -> "START";
                case "COMPLETED" -> "COMPLETE";
                case "PAUSED" -> "PAUSE";
                case "SKIPPED" -> "SKIP";
                default -> st;
            };
            if (body.get("criteriaConfirmed") == null) {
                criteriaConfirmed = true;
            }
        }

        return ResponseEntity.ok(workflowService.performStepAction(
                executionId, stepId, action, note, criteriaConfirmed, requireUserId(session)));
    }

    /** SCR-43.4: lịch sử thực hiện quy trình. */
    @GetMapping("/execution/{executionId}/history")
    public ResponseEntity<List<WorkflowHistoryEntryDTO>> getExecutionHistory(
            @PathVariable Long executionId,
            HttpSession session) {
        doctorVisitAccess.requireWorkflowExecution(session, executionId);
        return ResponseEntity.ok(workflowService.getExecutionHistory(executionId));
    }

    private Long requireUserId(HttpSession session) {
        Object principal = session == null ? null : session.getAttribute("loggedInUser");
        if (principal instanceof User user && user.getUserId() != null) {
            return user.getUserId();
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
    }

    private Long longOf(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Thiếu tham số: " + key);
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Tham số không hợp lệ: " + key);
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleInvalid(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", messageOf(ex)));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", messageOf(ex)));
    }

    private String messageOf(RuntimeException ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? "Yêu cầu không hợp lệ." : ex.getMessage();
    }
}
