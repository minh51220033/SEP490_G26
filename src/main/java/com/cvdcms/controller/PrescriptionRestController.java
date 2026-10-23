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

    /**
     * Lấy danh mục thuốc nha khoa khả dụng (dùng cho autocomplete / dropdown)
     */
    @GetMapping("/medications/catalog")
    public ResponseEntity<List<MedicationDTO>> getMedicationCatalog() {
        List<MedicationDTO> list = prescriptionService.getActiveMedications();
        return ResponseEntity.ok(list);
    }

    /**
     * Lấy danh sách đơn thuốc theo ca khám
     */
    @GetMapping("/prescriptions")
    public ResponseEntity<List<PrescriptionResponseDTO>> getPrescriptionsByVisit(@RequestParam Long visitId) {
        List<PrescriptionResponseDTO> list = prescriptionService.getPrescriptionsByVisit(visitId);
        return ResponseEntity.ok(list);
    }

    /**
     * Lấy chi tiết một đơn thuốc (dùng cho in hoặc xem chi tiết)
     */
    @GetMapping("/prescriptions/{id}")
    public ResponseEntity<PrescriptionResponseDTO> getPrescriptionById(@PathVariable Long id) {
        PrescriptionResponseDTO dto = prescriptionService.getPrescriptionById(id);
        return ResponseEntity.ok(dto);
    }

    /**
     * Kê đơn thuốc mới cho ca khám (FT18 - SCR-18.3, SCR-18.4)
     */
    @PostMapping("/prescriptions")
    public ResponseEntity<?> createPrescription(@RequestBody PrescriptionRequestDTO request) {
        try {
            PrescriptionResponseDTO created = prescriptionService.createPrescription(request);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Đã kê đơn thuốc thành công!");
            response.put("data", created);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
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
     * Xóa đơn thuốc
     */
    @DeleteMapping("/prescriptions/{id}")
    public ResponseEntity<?> deletePrescription(@PathVariable Long id) {
        try {
            prescriptionService.deletePrescription(id);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Đã xóa đơn thuốc thành công!");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
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
