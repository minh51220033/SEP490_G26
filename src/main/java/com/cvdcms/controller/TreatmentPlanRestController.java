package com.cvdcms.controller;

import com.cvdcms.DTO.AiParsePlanRequestDTO;
import com.cvdcms.DTO.AiParsePlanResponseDTO;
import com.cvdcms.DTO.TreatmentPlanRequestDTO;
import com.cvdcms.DTO.TreatmentPlanResponseDTO;
import com.cvdcms.service.TreatmentPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/doctor/treatment-plans")
@RequiredArgsConstructor
public class TreatmentPlanRestController {

    private final TreatmentPlanService treatmentPlanService;

    /**
     * Lấy danh sách kế hoạch điều trị theo hồ sơ bệnh nhân
     */
    @GetMapping
    public ResponseEntity<List<TreatmentPlanResponseDTO>> getPlansByPatient(@RequestParam Long patientId) {
        List<TreatmentPlanResponseDTO> list = treatmentPlanService.getPlansByPatientId(patientId);
        return ResponseEntity.ok(list);
    }

    /**
     * Lấy chi tiết một kế hoạch điều trị
     */
    @GetMapping("/{id}")
    public ResponseEntity<TreatmentPlanResponseDTO> getPlanById(@PathVariable Long id) {
        TreatmentPlanResponseDTO dto = treatmentPlanService.getPlanById(id);
        return ResponseEntity.ok(dto);
    }

    /**
     * Tạo mới kế hoạch điều trị (SCR-15.2)
     */
    @PostMapping
    public ResponseEntity<?> createPlan(@RequestBody TreatmentPlanRequestDTO request) {
        try {
            TreatmentPlanResponseDTO created = treatmentPlanService.createPlan(request);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Đã tạo kế hoạch điều trị thành công!");
            response.put("data", created);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            log.warn("Validation error createPlan: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            log.error("Internal error createPlan: ", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Lỗi máy chủ khi tạo kế hoạch điều trị: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Cập nhật trạng thái kế hoạch điều trị
     */
    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @RequestParam String status) {
        try {
            TreatmentPlanResponseDTO updated = treatmentPlanService.updatePlanStatus(id, status);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Đã cập nhật trạng thái kế hoạch thành công!");
            response.put("data", updated);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Xóa kế hoạch điều trị
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deletePlan(@PathVariable Long id) {
        try {
            treatmentPlanService.deletePlan(id);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Đã xóa kế hoạch điều trị thành công!");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Bóc tách quy trình / phác đồ điều trị bằng Gemini AI (SCR-15.2 AI Assistant)
     */
    @PostMapping("/ai-parse")
    public ResponseEntity<?> parseWithAi(@RequestBody AiParsePlanRequestDTO request) {
        try {
            AiParsePlanResponseDTO parsed = treatmentPlanService.parsePlanWithAi(request);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Đã phân tích và bóc tách phác đồ bằng AI thành công!");
            response.put("data", parsed);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            log.error("AI Parse error: ", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Lỗi phân tích AI: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}
