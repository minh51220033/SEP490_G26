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

/**
 * [Phân hệ]: Quy trình điều trị từng bước, 5 khung thao tác & Combo dịch vụ (Chương 7)
 * [Vai trò]: REST Controller cung cấp API quản trị mẫu quy trình, combo dịch vụ mẫu, trường tùy biến 5 khung thao tác và thực thi quy trình điều trị từng bước (FT43 / UC-13) gắn với phân quyền `DoctorVisitAccess`.
 */
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

    /**
     * [Chức năng]: Lấy toàn bộ danh sách mẫu quy trình kỹ thuật (`GET /api/doctor/workflows`).
     * [Mục đích]: Phục vụ giao diện quản trị mẫu quy trình và bàn khám tải danh mục quy trình kỹ thuật hiện có qua `ClinicalWorkflowService.getAllTemplates`.
     */
    @GetMapping
    public ResponseEntity<List<WorkflowTemplateResponseDTO>> getAllWorkflows() {
        return ResponseEntity.ok(workflowService.getAllTemplates());
    }

    /**
     * [Chức năng]: Lấy chi tiết một mẫu quy trình kỹ thuật theo ID (`GET /api/doctor/workflows/{id}`).
     * [Mục đích]: Trả về thông tin mẫu quy trình cùng phiên bản mới nhất và danh sách các bước kỹ thuật để xem hoặc chỉnh sửa.
     */
    @GetMapping("/{id}")
    public ResponseEntity<WorkflowTemplateResponseDTO> getWorkflowById(@PathVariable Long id) {
        return ResponseEntity.ok(workflowService.getTemplateById(id));
    }

    /**
     * [Chức năng]: Tra cứu mẫu quy trình đang hoạt động gắn với một dịch vụ (`GET /api/doctor/workflows/by-service/{serviceId}`).
     * [Mục đích]: Hỗ trợ bàn khám và kế hoạch điều trị kiểm tra dịch vụ có quy trình chuẩn hay không; trả về HTTP 404 nếu chưa cấu hình mẫu ACTIVE.
     */
    @GetMapping("/by-service/{serviceId}")
    public ResponseEntity<WorkflowTemplateResponseDTO> getWorkflowByService(@PathVariable Long serviceId) {
        WorkflowTemplateResponseDTO dto = workflowService.getTemplateByServiceId(serviceId);
        if (dto == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(dto);
    }

    /**
     * [Chức năng]: Tạo mới hoặc cập nhật mẫu quy trình kỹ thuật (`POST /api/doctor/workflows`).
     * [Mục đích]: Kiểm tra quyền quản lý mẫu qua `requireTemplateManager` trước khi gọi `workflowService.saveTemplate` để lưu mẫu, phiên bản và danh sách bước.
     */
    @PostMapping
    public ResponseEntity<WorkflowTemplateResponseDTO> saveWorkflow(
            @RequestBody WorkflowTemplateRequestDTO requestDTO,
            HttpSession session) {
        Long userId = requireTemplateManager(session);
        return ResponseEntity.ok(workflowService.saveTemplate(requestDTO, userId));
    }

    /**
     * [Chức năng]: Xóa một mẫu quy trình kỹ thuật theo ID (`DELETE /api/doctor/workflows/{id}`).
     * [Mục đích]: Kiểm tra quyền quản lý mẫu và ủy quyền cho `workflowService.deleteTemplate` (chỉ xóa khi mẫu chưa có lượt khám thực thi).
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteWorkflow(@PathVariable Long id, HttpSession session) {
        requireTemplateManager(session);
        workflowService.deleteTemplate(id);
        return ResponseEntity.noContent().build();
    }

    
    // =====================================================================
    // Tầng 1 · Combo dịch vụ mẫu (VTTech Dental)
    // =====================================================================

    /**
     * [Chức năng]: Lấy danh sách Combo dịch vụ mẫu (`GET /api/doctor/workflows/combos`).
     * [Mục đích]: Trả về toàn bộ combo hoặc chỉ các combo đang hoạt động (`activeOnly=true`) kèm thông tin dịch vụ thành phần, thời lượng và mẫu quy trình liên kết.
     */
    @GetMapping("/combos")
    public ResponseEntity<List<ServiceComboDTO>> getAllCombos(
            @RequestParam(value = "activeOnly", required = false, defaultValue = "false") boolean activeOnly) {
        return ResponseEntity.ok(workflowService.getAllCombos(activeOnly));
    }

    /**
     * [Chức năng]: Tạo mới hoặc cập nhật Combo dịch vụ mẫu (`POST /api/doctor/workflows/combos`).
     * [Mục đích]: Xác thực quyền quản lý mẫu qua `requireTemplateManager` và lưu cấu hình combo cùng thứ tự dịch vụ thành phần qua `workflowService.saveCombo`.
     */
    @PostMapping("/combos")
    public ResponseEntity<ServiceComboDTO> saveCombo(
            @RequestBody Map<String, Object> body,
            HttpSession session) {
        Long userId = requireTemplateManager(session);
        return ResponseEntity.ok(workflowService.saveCombo(body, userId));
    }

    /**
     * [Chức năng]: Xóa Combo dịch vụ mẫu theo ID (`DELETE /api/doctor/workflows/combos/{comboId}`).
     * [Mục đích]: Kiểm tra quyền quản trị mẫu và xóa bản ghi combo khỏi hệ thống qua `workflowService.deleteCombo`.
     */
    @DeleteMapping("/combos/{comboId}")
    public ResponseEntity<Void> deleteCombo(@PathVariable Long comboId, HttpSession session) {
        requireTemplateManager(session);
        workflowService.deleteCombo(comboId);
        return ResponseEntity.noContent().build();
    }

    /**
     * [Chức năng]: Lấy cấu hình các trường dữ liệu tùy biến của 5 khung thao tác lâm sàng (`GET /api/doctor/workflows/frames`).
     * [Mục đích]: Cung cấp danh sách trường tùy biến mặc định theo từng `frameKey` (`CLINICAL_EXAM`, `XRAY`, `ENDODONTICS`, `PROCEDURE`, `PROSTHETICS_LABO`) cho giao diện cấu hình và bàn thực thi quy trình.
     */
    @GetMapping("/frames")
    public ResponseEntity<Map<String, List<WorkflowStepDTO.CustomFieldDTO>>> getFrameCustomFields() {
        return ResponseEntity.ok(workflowService.getAllFrameCustomFields());
    }

    /**
     * [Chức năng]: Lưu danh sách trường dữ liệu tùy biến cho một khung thao tác (`PUT /api/doctor/workflows/frames/{frameKey}/fields`).
     * [Mục đích]: Kiểm tra quyền quản lý mẫu qua `requireTemplateManager`, trích mảng `fields` từ payload và lưu cấu hình trường của khung vào bảng `clinical_workflow_frame_fields`.
     */
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

    /**
     * [Chức năng]: Lấy tổng quan quy trình điều trị của một lượt khám (`GET /api/doctor/workflows/execution/visit/{visitId}`, SCR-43.1).
     * [Mục đích]: Kiểm tra quyền đọc ca khám qua `doctorVisitAccess.requireVisitRead`, trả về danh sách tiến trình đang chạy/kế thừa, các hạng mục KHDT có thể khởi tạo và combo khả dụng.
     */
    @GetMapping("/execution/visit/{visitId}")
    public ResponseEntity<WorkflowVisitOverviewDTO> getVisitOverview(@PathVariable Long visitId, HttpSession session) {
        doctorVisitAccess.requireVisitRead(session, visitId);
        return ResponseEntity.ok(workflowService.getVisitOverview(visitId));
    }

    /**
     * [Chức năng]: Lấy hoặc tự động khởi tạo tiến trình quy trình cho cặp lượt khám và dịch vụ (`GET /api/doctor/workflows/execution/{visitId}/{serviceId}`).
     * [Mục đích]: Xác thực ca khám đang mở khám hợp lệ qua `doctorVisitAccess.requireActiveClinicalExam`, gọi `workflowService.getOrCreateExecution`; trả về HTTP 404 nếu dịch vụ chưa có mẫu quy trình.
     */
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

    /**
     * [Chức năng]: Khởi tạo tiến trình quy trình điều trị mới cho lượt khám (`POST /api/doctor/workflows/execution/start`).
     * [Mục đích]: Xác thực tham số (`visitId` cùng ít nhất một trong `serviceId`/`planItemId`/`planStageId`), kiểm tra quyền mở khám và chính sách làm thêm dịch vụ trong cùng ca (`assertVisitServiceExecutionAllowed`) rồi gọi `workflowService.startExecution`.
     */
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
        boolean patientConfirmed = Boolean.TRUE.equals(body.get("patientConfirmed"))
                || "true".equalsIgnoreCase(String.valueOf(body.get("patientConfirmed")));
        doctorVisitAccess.assertVisitServiceExecutionAllowed(visitId, serviceId, planItemId, patientConfirmed);
        return ResponseEntity.ok(workflowService.startExecution(visitId, serviceId, planItemId, planStageId, requireUserId(session)));
    }

    /**
     * [Chức năng]: Áp dụng Combo dịch vụ mẫu vào bàn thực thi quy trình của lượt khám (`POST /api/doctor/workflows/execution/start-combo`).
     * [Mục đích]: Kiểm tra quyền thao tác ca khám đang mở, phân loại các dịch vụ vượt ngưỡng thời gian/cần hẹn buổi sau qua `doctorVisitAccess.resolveComboDeferredServiceIds` rồi khởi chạy các quy trình đủ điều kiện qua `workflowService.startComboExecution`.
     */
    @PostMapping("/execution/start-combo")
    public ResponseEntity<Map<String, Object>> startComboExecution(
            @RequestBody Map<String, Object> body,
            HttpSession session) {
        Long visitId = longOf(body, "visitId");
        Long comboId = longOf(body, "comboId");
        doctorVisitAccess.requireActiveClinicalExam(session, visitId);
        boolean patientConfirmed = Boolean.TRUE.equals(body.get("patientConfirmed"))
                || "true".equalsIgnoreCase(String.valueOf(body.get("patientConfirmed")));
        java.util.Set<Long> deferred = doctorVisitAccess.resolveComboDeferredServiceIds(visitId, comboId, patientConfirmed);
        return ResponseEntity.ok(workflowService.startComboExecution(visitId, comboId, requireUserId(session), deferred));
    }

    /**
     * [Chức năng]: Thực hiện thao tác chuyển trạng thái hoặc lưu dữ liệu trên một bước quy trình (`POST /api/doctor/workflows/execution/step`, SCR-43.2 & SCR-43.3).
     * [Mục đích]: Xác thực quyền thao tác tiến trình qua `doctorVisitAccess.requireWorkflowExecution`, chuẩn hóa `action` (`START`/`COMPLETE`/`PAUSE`/`RESUME`/`SKIP`/`SAVE_DRAFT`, tương thích ngược cả tham số `status` cũ) và gọi `workflowService.performStepAction`.
     */
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

    /**
     * [Chức năng]: Chèn thêm bước phát sinh (ad-hoc) vào một tiến trình quy trình đang điều trị (`POST /api/doctor/workflows/execution/{executionId}/adhoc-step`).
     * [Mục đích]: Kiểm tra quyền thao tác tiến trình qua `doctorVisitAccess.requireWorkflowExecution` và gọi `workflowService.addAdhocStep` để tạo bước phát sinh gắn khung thao tác và vị trí chèn phù hợp.
     */
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

    /**
     * [Chức năng]: Xóa một bước phát sinh khỏi tiến trình quy trình (`DELETE /api/doctor/workflows/execution/{executionId}/adhoc-step/{stepId}`).
     * [Mục đích]: Kiểm tra quyền thao tác tiến trình qua `doctorVisitAccess.requireWorkflowExecution` và xóa bước ad-hoc khi bước chưa bắt đầu thực hiện hoặc chưa lưu dữ liệu.
     */
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

    /**
     * [Chức năng]: Hủy xóa một tiến trình quy trình mới khởi tạo (`DELETE /api/doctor/workflows/execution/{executionId}`).
     * [Mục đích]: Xác thực quyền thao tác tiến trình qua `doctorVisitAccess.requireWorkflowExecution` và gọi `workflowService.deleteExecution` (chỉ cho phép khi chưa thực hiện bước nào).
     */
    @DeleteMapping("/execution/{executionId}")
    public ResponseEntity<Void> deleteExecution(
            @PathVariable Long executionId,
            @RequestParam(value = "activeVisitId", required = false) Long activeVisitId,
            HttpSession session) {
        doctorVisitAccess.requireWorkflowExecution(session, executionId, activeVisitId);
        workflowService.deleteExecution(executionId, activeVisitId, requireUserId(session));
        return ResponseEntity.noContent().build();
    }

    /**
     * [Chức năng]: Lấy nhật ký lịch sử thực hiện của một tiến trình quy trình (`GET /api/doctor/workflows/execution/{executionId}/history`, SCR-43.4).
     * [Mục đích]: Kiểm tra quyền xem tiến trình qua `doctorVisitAccess.requireWorkflowExecutionRead` và trả về các mốc audit của tiến trình cùng các bước con.
     */
    @GetMapping("/execution/{executionId}/history")
    public ResponseEntity<List<WorkflowHistoryEntryDTO>> getExecutionHistory(
            @PathVariable Long executionId,
            HttpSession session) {
        doctorVisitAccess.requireWorkflowExecutionRead(session, executionId);
        return ResponseEntity.ok(workflowService.getExecutionHistory(executionId));
    }

    /**
     * [Hàm hỗ trợ]: Xác thực người dùng hiện tại có vai trò Quản trị viên hoặc Quản lý chuyên môn để quản trị mẫu quy trình/combo/khung, trả về `userId` hoặc ném HTTP 403.
     */
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

    /**
     * [Hàm hỗ trợ]: Lấy `userId` của tài khoản đang đăng nhập từ `HttpSession` hoặc ném HTTP 401 nếu chưa đăng nhập.
     */
    private Long requireUserId(HttpSession session) {
        Object principal = session == null ? null : session.getAttribute("loggedInUser");
        if (principal instanceof User user && user.getUserId() != null) {
            return user.getUserId();
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
    }

    /**
     * [Hàm hỗ trợ]: Đọc và ép kiểu tham số bắt buộc dạng `Long` từ body request, ném `IllegalArgumentException` nếu thiếu hoặc sai định dạng.
     */
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

    /**
     * [Hàm hỗ trợ]: Đọc tham số tùy chọn dạng `Long` từ body request; trả về `null` nếu không truyền hoặc rỗng.
     */
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

    /**
     * [Hàm hỗ trợ]: Chuyển đối tượng JSON `resultData` sang `Map<String, Object>` mới có thứ tự khóa ổn định.
     */
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

    /**
     * [Chức năng]: Xử lý ngoại lệ tham số không hợp lệ (`IllegalArgumentException`) trong controller quy trình.
     * [Mục đích]: Trả về HTTP 400 kèm thông điệp JSON `{ "message": ... }` thân thiện cho giao diện.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleInvalid(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", messageOf(ex)));
    }

    /**
     * [Chức năng]: Xử lý ngoại lệ xung đột trạng thái nghiệp vụ (`IllegalStateException`) trong controller quy trình.
     * [Mục đích]: Trả về HTTP 409 kèm thông điệp chi tiết (ví dụ khóa ca khám, sai thứ tự bước, cần bệnh nhân xác nhận làm thêm).
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", messageOf(ex)));
    }

    /**
     * [Chức năng]: Xử lý ngoại lệ vi phạm toàn vẹn dữ liệu (`DataIntegrityViolationException`) khi thao tác mẫu/tiến trình quy trình.
     * [Mục đích]: Ghi log cảnh báo và trả về HTTP 409 với thông điệp tránh lộ lỗi SQL gốc khi trùng mã hoặc bản ghi đang được tham chiếu.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation in ClinicalWorkflowRestController: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "Dữ liệu quy trình bị trùng mã hoặc đang được tham chiếu, không thể thực hiện thao tác."));
    }

    /**
     * [Hàm hỗ trợ]: Lấy thông điệp lỗi từ `RuntimeException`, trả về câu mặc định nếu thông điệp bị trống.
     */
    private String messageOf(RuntimeException ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? "Yêu cầu không hợp lệ." : ex.getMessage();
    }
}
