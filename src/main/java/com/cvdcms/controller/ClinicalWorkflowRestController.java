package com.cvdcms.controller;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.User;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.ClinicalWorkflowService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/api/doctor/workflows")
@RequiredArgsConstructor
public class ClinicalWorkflowRestController {

    private final ClinicalWorkflowService workflowService;
    private final com.cvdcms.security.DoctorVisitAccess doctorVisitAccess;

    @Autowired(required = false)
    private UserRepository userRepository;

    private static final Set<String> TEMPLATE_MANAGER_ROLES = Set.of(
            "ADMIN", "ROLE_ADMIN",
            "MANAGER", "ROLE_MANAGER",
            "CLINIC_MANAGER", "ROLE_CLINIC_MANAGER",
            "QUAN_LY", "ROLE_QUAN_LY",
            "TRUONG_KHOA", "ROLE_TRUONG_KHOA"
    );

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
        Long userId = requireTemplateManager(session);
        return ResponseEntity.ok(workflowService.saveTemplate(requestDTO, userId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteWorkflow(@PathVariable Long id, HttpSession session) {
        requireTemplateManager(session);
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
    // Tầng 1 · Combo dịch vụ mẫu (VTTech Dental)
    // =====================================================================

    @GetMapping("/combos")
    public ResponseEntity<List<ServiceComboDTO>> getAllCombos(
            @RequestParam(value = "activeOnly", required = false, defaultValue = "false") boolean activeOnly) {
        return ResponseEntity.ok(workflowService.getAllCombos(activeOnly));
    }

    @PostMapping("/combos")
    public ResponseEntity<ServiceComboDTO> saveCombo(
            @RequestBody Map<String, Object> body,
            HttpSession session) {
        Long userId = requireTemplateManager(session);
        return ResponseEntity.ok(workflowService.saveCombo(body, userId));
    }

    @DeleteMapping("/combos/{comboId}")
    public ResponseEntity<Void> deleteCombo(@PathVariable Long comboId, HttpSession session) {
        requireTemplateManager(session);
        workflowService.deleteCombo(comboId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/frames")
    public ResponseEntity<Map<String, List<WorkflowStepDTO.CustomFieldDTO>>> getFrameCustomFields() {
        return ResponseEntity.ok(workflowService.getAllFrameCustomFields());
    }

    @PutMapping("/frames/{frameKey}/fields")
    public ResponseEntity<List<WorkflowStepDTO.CustomFieldDTO>> saveFrameCustomFields(
            @PathVariable String frameKey,
            @RequestBody(required = false) Map<String, List<WorkflowStepDTO.CustomFieldDTO>> body,
            HttpSession session) {
        requireTemplateManager(session);
        List<WorkflowStepDTO.CustomFieldDTO> fields = body != null && body.get("fields") != null
                ? body.get("fields")
                : List.of();
        return ResponseEntity.ok(workflowService.saveFrameCustomFields(frameKey, fields));
    }

    // =====================================================================
    // FT43 - Thực hiện quy trình khám & điều trị theo lượt khám (UC-13)
    // =====================================================================

    /** SCR-43.1: tổng quan quy trình của lượt khám + dịch vụ có thể khởi tạo quy trình. */
    @GetMapping("/execution/visit/{visitId}")
    public ResponseEntity<WorkflowVisitOverviewDTO> getVisitOverview(@PathVariable Long visitId, HttpSession session) {
        doctorVisitAccess.requireVisitRead(session, visitId);
        return ResponseEntity.ok(workflowService.getVisitOverview(visitId));
    }

    @GetMapping("/execution/{visitId}/{serviceId}")
    public ResponseEntity<WorkflowExecutionDTO> getExecution(
            @PathVariable Long visitId,
            @PathVariable Long serviceId,
            HttpSession session) {
        doctorVisitAccess.requireActiveClinicalExam(session, visitId);
        WorkflowExecutionDTO dto = workflowService.getOrCreateExecution(visitId, serviceId, requireUserId(session));
        if (dto == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(dto);
    }

    /** Khởi tạo quy trình cho lượt khám theo mẫu đang áp dụng của dịch vụ (hoặc gắn giai đoạn/mục KHDT). */
    @PostMapping("/execution/start")
    public ResponseEntity<WorkflowExecutionDTO> startExecution(
            @RequestBody Map<String, Object> body,
            HttpSession session) {
        Long visitId = longOf(body, "visitId");
        Long planItemId = optionalLongOf(body, "planItemId");
        Long planStageId = optionalLongOf(body, "planStageId");
        Long serviceId = optionalLongOf(body, "serviceId");
        if (serviceId == null && planItemId == null && planStageId == null) {
            throw new IllegalArgumentException("Thiếu tham số: serviceId, planItemId hoặc planStageId");
        }
        doctorVisitAccess.requireActiveClinicalExam(session, visitId);
        return ResponseEntity.ok(workflowService.startExecution(visitId, serviceId, planItemId, planStageId, requireUserId(session)));
    }

    /** Tầng 1 Combo VTTech: Áp dụng toàn bộ Combo dịch vụ mẫu vào Bàn quy trình bước của lượt khám. */
    @PostMapping("/execution/start-combo")
    public ResponseEntity<Map<String, Object>> startComboExecution(
            @RequestBody Map<String, Object> body,
            HttpSession session) {
        Long visitId = longOf(body, "visitId");
        Long comboId = longOf(body, "comboId");
        doctorVisitAccess.requireActiveClinicalExam(session, visitId);
        return ResponseEntity.ok(workflowService.startComboExecution(visitId, comboId, requireUserId(session)));
    }

    /** SCR-43.2, 43.3: START / COMPLETE / PAUSE / RESUME / SKIP / SAVE_DRAFT trên bước quy trình. */
    @PostMapping("/execution/step")
    public ResponseEntity<WorkflowExecutionDTO> updateStepExecution(
            @RequestBody Map<String, Object> body,
            HttpSession session) {
        Long executionId = longOf(body, "executionId");
        Long activeVisitId = optionalLongOf(body, "activeVisitId");
        doctorVisitAccess.requireWorkflowExecution(session, executionId, activeVisitId);
        Long stepId = longOf(body, "stepId");
        String action = body.get("action") != null ? body.get("action").toString() : null;
        String note = body.get("note") != null ? body.get("note").toString() : null;
        boolean criteriaConfirmed = body.get("criteriaConfirmed") != null
                && Boolean.parseBoolean(body.get("criteriaConfirmed").toString());
        Map<String, Object> resultData = mapOf(body.get("resultData"));
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
                executionId, stepId, action, note, criteriaConfirmed, resultData, activeVisitId, requireUserId(session)));
    }

    /** Thêm bước phát sinh riêng cho 1 ca đang điều trị. */
    @PostMapping("/execution/{executionId}/adhoc-step")
    public ResponseEntity<WorkflowExecutionDTO> addAdhocStep(
            @PathVariable Long executionId,
            @RequestBody Map<String, Object> body,
            HttpSession session) {
        Long activeVisitId = optionalLongOf(body, "activeVisitId");
        doctorVisitAccess.requireWorkflowExecution(session, executionId, activeVisitId);
        String stepName = body.get("stepName") != null ? body.get("stepName").toString() : null;
        String frameKey = body.get("frameKey") != null ? body.get("frameKey").toString() : null;
        String stepType = body.get("stepType") != null ? body.get("stepType").toString() : null;
        String insertPosition = body.get("insertPosition") != null ? body.get("insertPosition").toString() : null;
        Long afterStepId = optionalLongOf(body, "afterStepId");
        String completionCriteria = body.get("completionCriteria") != null ? body.get("completionCriteria").toString() : null;
        String stepDescription = body.get("stepDescription") != null ? body.get("stepDescription").toString() : null;

        return ResponseEntity.ok(workflowService.addAdhocStep(
                executionId, stepName, frameKey, stepType, insertPosition, afterStepId,
                completionCriteria, stepDescription, activeVisitId, requireUserId(session)));
    }

    /** Xóa bước phát sinh khi chưa thực hiện / chưa lưu dữ liệu. */
    @DeleteMapping("/execution/{executionId}/adhoc-step/{stepId}")
    public ResponseEntity<WorkflowExecutionDTO> removeAdhocStep(
            @PathVariable Long executionId,
            @PathVariable Long stepId,
            @RequestParam(value = "activeVisitId", required = false) Long activeVisitId,
            HttpSession session) {
        doctorVisitAccess.requireWorkflowExecution(session, executionId, activeVisitId);
        return ResponseEntity.ok(workflowService.removeAdhocStep(
                executionId, stepId, activeVisitId, requireUserId(session)));
    }

    /** Hủy dịch vụ quy trình mới khởi tạo (chỉ cho phép khi chưa thực hiện bước nào). */
    @DeleteMapping("/execution/{executionId}")
    public ResponseEntity<Void> deleteExecution(
            @PathVariable Long executionId,
            @RequestParam(value = "activeVisitId", required = false) Long activeVisitId,
            HttpSession session) {
        doctorVisitAccess.requireWorkflowExecution(session, executionId, activeVisitId);
        workflowService.deleteExecution(executionId, activeVisitId, requireUserId(session));
        return ResponseEntity.noContent().build();
    }

    /** SCR-43.4: lịch sử thực hiện quy trình. */
    @GetMapping("/execution/{executionId}/history")
    public ResponseEntity<List<WorkflowHistoryEntryDTO>> getExecutionHistory(
            @PathVariable Long executionId,
            HttpSession session) {
        doctorVisitAccess.requireWorkflowExecutionRead(session, executionId);
        return ResponseEntity.ok(workflowService.getExecutionHistory(executionId));
    }

    private Long requireTemplateManager(HttpSession session) {
        User user = doctorVisitAccess.requireAuthenticatedUser(session);
        Long userId = user.getUserId();
        if (doctorVisitAccess.isAdmin(user)) {
            return userId;
        }
        boolean hasManagerRole = false;
        if (userRepository != null) {
            List<String> dbRoles = userRepository.findActiveRoleNamesByUserId(userId);
            if (dbRoles != null) {
                hasManagerRole = dbRoles.stream()
                        .filter(r -> r != null && !r.isBlank())
                        .map(r -> r.trim().toUpperCase(Locale.ROOT))
                        .anyMatch(TEMPLATE_MANAGER_ROLES::contains);
            }
        }
        if (!hasManagerRole && user.getRoles() != null) {
            hasManagerRole = user.getRoles().stream()
                    .filter(r -> r != null && r.getRoleName() != null && (r.getIsActive() == null || Boolean.TRUE.equals(r.getIsActive())))
                    .map(r -> r.getRoleName().trim().toUpperCase(Locale.ROOT))
                    .anyMatch(TEMPLATE_MANAGER_ROLES::contains);
        }
        if (!hasManagerRole) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Chỉ Quản trị viên hoặc Quản lý chuyên môn mới có quyền thêm, sửa hoặc xóa mẫu quy trình kỹ thuật.");
        }
        return userId;
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

    private Long optionalLongOf(Map<String, Object> body, String key) {
        if (body == null) return null;
        Object value = body.get(key);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Tham số không hợp lệ: " + key);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapOf(Object val) {
        if (val instanceof Map<?, ?> m) {
            Map<String, Object> copy = new java.util.LinkedHashMap<>();
            m.forEach((k, v) -> {
                if (k != null) copy.put(k.toString(), v);
            });
            return copy;
        }
        return null;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleInvalid(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", messageOf(ex)));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", messageOf(ex)));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation in ClinicalWorkflowRestController: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "Dữ liệu quy trình bị trùng mã hoặc đang được tham chiếu, không thể thực hiện thao tác."));
    }

    private String messageOf(RuntimeException ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? "Yêu cầu không hợp lệ." : ex.getMessage();
    }
}
