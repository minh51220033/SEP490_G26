package com.cvdcms.controller;

import com.cvdcms.DTO.MedicationDTO;
import com.cvdcms.DTO.PrescriptionRequestDTO;
import com.cvdcms.DTO.PrescriptionResponseDTO;
import com.cvdcms.service.PrescriptionService;
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
@RequestMapping("/api/doctor")
@RequiredArgsConstructor
public class PrescriptionRestController {

    private final PrescriptionService prescriptionService;
    private final com.cvdcms.security.DoctorVisitAccess doctorVisitAccess;

    /**
     * Lấy danh mục thuốc nha khoa khả dụng kèm tồn kho còn hạn theo chi nhánh
     */
    @GetMapping("/medications/catalog")
    public ResponseEntity<List<MedicationDTO>> getMedicationCatalog(
            @RequestParam(required = false) Long visitId,
            @RequestParam(required = false) Long branchId,
            jakarta.servlet.http.HttpSession session) {
        com.cvdcms.entity.User currentUser = doctorVisitAccess.requireAuthenticatedUser(session);
        Long resolvedBranchId = branchId;
        if (visitId != null) {
            doctorVisitAccess.require(session, visitId);
            resolvedBranchId = prescriptionService.resolveBranchIdForVisit(visitId);
        } else if (branchId != null) {
            doctorVisitAccess.requireBranch(session, branchId);
        } else if (currentUser != null && currentUser.getBranch() != null) {
            resolvedBranchId = currentUser.getBranch().getBranchId();
        }
        List<MedicationDTO> list = prescriptionService.getActiveMedicationsForBranch(resolvedBranchId);
        return ResponseEntity.ok(list);
    }

    /**
     * Lấy danh sách đơn thuốc theo ca khám
     */
    @GetMapping("/prescriptions")
    public ResponseEntity<List<PrescriptionResponseDTO>> getPrescriptionsByVisit(@RequestParam Long visitId, jakarta.servlet.http.HttpSession session) {
        doctorVisitAccess.require(session, visitId);
        List<PrescriptionResponseDTO> list = prescriptionService.getPrescriptionsByVisit(visitId);
        return ResponseEntity.ok(list);
    }

    /**
     * Lấy chi tiết một đơn thuốc (dùng cho in hoặc xem chi tiết)
     */
    @GetMapping("/prescriptions/{id}")
    public ResponseEntity<PrescriptionResponseDTO> getPrescriptionById(@PathVariable Long id, jakarta.servlet.http.HttpSession session) {
        doctorVisitAccess.requirePrescription(session, id);
        PrescriptionResponseDTO dto = prescriptionService.getPrescriptionById(id);
        return ResponseEntity.ok(dto);
    }

    /**
     * Kê đơn thuốc mới cho ca khám (FT18 - SCR-18.3, SCR-18.4)
     */
    @PostMapping("/prescriptions")
    public ResponseEntity<?> createPrescription(@RequestBody PrescriptionRequestDTO request, jakarta.servlet.http.HttpSession session) {
        if (request != null && request.getVisitId() != null) {
            doctorVisitAccess.requireActiveClinicalExam(session, request.getVisitId());
        } else if (request != null && request.getPatientId() != null) {
            doctorVisitAccess.requirePatient(session, request.getPatientId());
        } else {
            doctorVisitAccess.requireAuthenticatedUser(session);
        }
        try {
            PrescriptionResponseDTO created = prescriptionService.createPrescription(request);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Đã kê đơn thuốc thành công!");
            response.put("data", created);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Validation error createPrescription: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            log.error("Internal error createPrescription: ", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Lỗi máy chủ khi tạo đơn thuốc: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Cập nhật đơn thuốc và điều chỉnh chênh lệch tồn kho
     */
    @PutMapping("/prescriptions/{id}")
    public ResponseEntity<?> updatePrescription(
            @PathVariable Long id,
            @RequestBody PrescriptionRequestDTO request,
            jakarta.servlet.http.HttpSession session) {
        doctorVisitAccess.requirePrescription(session, id);
        try {
            PrescriptionResponseDTO updated = prescriptionService.updatePrescription(id, request);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Đã cập nhật đơn thuốc và đồng bộ tồn kho thành công!");
            response.put("data", updated);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Validation error updatePrescription {}: {}", id, e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            log.error("Internal error updatePrescription {}: ", id, e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Lỗi máy chủ khi cập nhật đơn thuốc: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Hủy đơn thuốc và hoàn trả tồn kho (nếu đủ điều kiện)
     */
    @PostMapping("/prescriptions/{id}/cancel")
    public ResponseEntity<?> cancelPrescription(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            jakarta.servlet.http.HttpSession session) {
        doctorVisitAccess.requirePrescription(session, id);
        try {
            String reason = body != null ? body.get("reason") : null;
            PrescriptionResponseDTO cancelled = prescriptionService.cancelPrescription(id, reason);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Đã hủy đơn thuốc và hoàn kho thành công!");
            response.put("data", cancelled);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Validation error cancelPrescription {}: {}", id, e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            log.error("Internal error cancelPrescription {}: ", id, e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Lỗi máy chủ khi hủy đơn thuốc: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Xóa đơn thuốc
     */
    @DeleteMapping("/prescriptions/{id}")
    public ResponseEntity<?> deletePrescription(@PathVariable Long id, jakarta.servlet.http.HttpSession session) {
        doctorVisitAccess.requirePrescription(session, id);
        try {
            prescriptionService.deletePrescription(id);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Đã xóa đơn thuốc thành công!");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Lỗi máy chủ khi xóa đơn thuốc: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}
