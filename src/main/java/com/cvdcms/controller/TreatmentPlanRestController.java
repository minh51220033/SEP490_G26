package com.cvdcms.controller;

import com.cvdcms.DTO.AiParsePlanRequestDTO;
import com.cvdcms.DTO.AiParsePlanResponseDTO;
import com.cvdcms.DTO.TreatmentPlanRequestDTO;
import com.cvdcms.DTO.TreatmentPlanResponseDTO;
import com.cvdcms.DTO.PerformedServiceRequestDTO;
import com.cvdcms.DTO.PerformedServiceResponseDTO;
import com.cvdcms.service.PerformedServiceService;
import com.cvdcms.service.TreatmentPlanService;
import com.cvdcms.repository.TreatmentPlanItemRepository;
import com.cvdcms.repository.VisitRepository;
import java.math.BigDecimal;
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
    private final PerformedServiceService performedServiceService;
    private final TreatmentPlanItemRepository treatmentPlanItemRepository;
    private final VisitRepository visitRepository;

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
     * Cập nhật trạng thái giai đoạn điều trị (SCR-15.3 - Ghi nhận now() và tiến độ)
     */
    @PutMapping("/stages/{stageId}/status")
    public ResponseEntity<?> updateStageStatus(@PathVariable Long stageId, @RequestParam String status) {
        try {
            TreatmentPlanResponseDTO updatedPlan = treatmentPlanService.updateStageStatus(stageId, status);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Đã cập nhật trạng thái giai đoạn thành công!");
            response.put("data", updatedPlan);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error updating stage status: ", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Cập nhật trạng thái từng thủ thuật / dịch vụ trong giai đoạn (Ghi nhận now())
     */
    @PutMapping("/items/{itemId}/status")
    public ResponseEntity<?> updateItemStatus(@PathVariable Long itemId, @RequestParam String status) {
        try {
            TreatmentPlanResponseDTO updatedPlan = treatmentPlanService.updateItemStatus(itemId, status);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Đã cập nhật trạng thái thủ thuật thành công!");
            response.put("data", updatedPlan);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error updating item status: ", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Bác sĩ bấm "Thực hiện trong ca này" từ Kế hoạch điều trị (FT15 -> FT16)
     */
    @PostMapping("/items/{itemId}/execute-in-visit")
    public ResponseEntity<?> executeItemInVisit(
            @PathVariable Long itemId,
            @RequestParam Long visitId,
            @RequestParam(required = false) Long doctorId,
            @RequestParam(required = false) Long assistantId) {
        try {
            com.cvdcms.entity.TreatmentPlanItem item = treatmentPlanItemRepository.findById(itemId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bước thủ thuật với ID: " + itemId));

            com.cvdcms.entity.Visit visit = visitRepository.findById(visitId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca khám với ID: " + visitId));

            Long docId = doctorId;
            if (docId == null) {
                if (visit.getPrimaryDoctor() != null) {
                    docId = visit.getPrimaryDoctor().getStaffId();
                } else {
                    List<com.cvdcms.entity.StaffProfile> docs = performedServiceService.getAvailableDoctors();
                    if (!docs.isEmpty()) {
                        docId = docs.get(0).getStaffId();
                    } else {
                        throw new IllegalStateException("Không tìm thấy bác sĩ thực hiện trong hệ thống!");
                    }
                }
            }

            BigDecimal unitPrice = (item.getEstimatedPrice() != null && item.getEstimatedPrice().compareTo(BigDecimal.ZERO) > 0)
                    ? item.getEstimatedPrice()
                    : (item.getService() != null && item.getService().getBasePrice() != null
                        ? item.getService().getBasePrice()
                        : BigDecimal.ZERO);
            BigDecimal qty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ONE;
            BigDecimal finalPrice = unitPrice.multiply(qty);

            PerformedServiceRequestDTO req = PerformedServiceRequestDTO.builder()
                    .visitId(visitId)
                    .serviceId(item.getService().getServiceId())
                    .toothNumbers(item.getToothNumbers())
                    .quantity(qty)
                    .unitPrice(unitPrice)
                    .discountAmount(BigDecimal.ZERO)
                    .finalPrice(finalPrice)
                    .doctorId(docId)
                    .assistantId(assistantId)
                    .planItemId(itemId)
                    .clinicalNotes("Thực hiện từ KHDT: " + (item.getPlan() != null ? item.getPlan().getPlanName() : "") + " (" + (item.getStage() != null ? item.getStage().getStageName() : "") + ")")
                    .build();

            PerformedServiceResponseDTO ps = performedServiceService.createPerformedService(req);
            TreatmentPlanResponseDTO planDto = treatmentPlanService.getPlanById(item.getPlan().getPlanId());

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Đã thực hiện dịch vụ trong ca khám hôm nay & hoàn tất bước trong lộ trình!");
            response.put("performedService", ps);
            response.put("plan", planDto);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error executing plan item in visit: ", e);
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
