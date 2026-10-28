package com.cvdcms.controller;

import com.cvdcms.DTO.ClinicalCompletionCheckDTO;
import com.cvdcms.DTO.ClinicalCompletionRequestDTO;
import com.cvdcms.entity.Visit;
import com.cvdcms.service.DiagnosisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/doctor/visits")
@RequiredArgsConstructor
public class DoctorExaminationRestController {

    private final DiagnosisService diagnosisService;

    /**
     * Kiểm tra điều kiện hoàn tất ca khám lâm sàng (ICD-10, dịch vụ COMPLETED, etc.)
     */
    @GetMapping("/{visitId}/completion-check")
    public ResponseEntity<ClinicalCompletionCheckDTO> checkCompletionReadiness(@PathVariable Long visitId) {
        ClinicalCompletionCheckDTO checkDTO = diagnosisService.checkClinicalCompletionReadiness(visitId);
        return ResponseEntity.ok(checkDTO);
    }

    /**
     * Bác sĩ bấm "Hoàn tất ca khám & Chuyển thanh toán"
     * Cập nhật visit.status = 'WAITING_PAYMENT', khóa bệnh án lâm sàng (BR-EMR-02)
     */
    @PostMapping("/{visitId}/complete-clinical")
    public ResponseEntity<Map<String, Object>> completeClinicalExamination(
            @PathVariable Long visitId,
            @RequestBody(required = false) ClinicalCompletionRequestDTO request
    ) {
        String doctorNotes = (request != null) ? request.getDoctorNotes() : null;
        try {
            Visit updatedVisit = diagnosisService.completeClinicalExamination(visitId, doctorNotes);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Đã hoàn tất ca khám và chuyển sang bộ phận Kế toán (Chờ thanh toán) thành công!");
            response.put("visitId", updatedVisit.getVisitId());
            response.put("visitCode", updatedVisit.getVisitCode());
            response.put("status", updatedVisit.getStatus().name());

            return ResponseEntity.ok(response);
        } catch (IllegalStateException | IllegalArgumentException ex) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", ex.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception ex) {
            log.error("Lỗi khi hoàn tất ca khám lâm sàng: ", ex);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Lỗi máy chủ khi hoàn tất ca khám: " + ex.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Mở lại ca khám lâm sàng (Chỉ cho phép khi đang ở trạng thái WAITING_PAYMENT và Kế toán chưa xuất hóa đơn)
     */
    @PostMapping("/{visitId}/reopen-clinical")
    public ResponseEntity<Map<String, Object>> reopenClinicalExamination(
            @PathVariable Long visitId,
            @RequestBody(required = false) Map<String, String> body
    ) {
        String reason = (body != null) ? body.get("reason") : null;
        try {
            Visit updatedVisit = diagnosisService.reopenClinicalExamination(visitId, reason);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Đã mở lại ca khám lâm sàng thành công. Bác sĩ có thể tiếp tục bổ sung thông tin.");
            response.put("visitId", updatedVisit.getVisitId());
            response.put("status", updatedVisit.getStatus().name());

            return ResponseEntity.ok(response);
        } catch (IllegalStateException | IllegalArgumentException ex) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", ex.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception ex) {
            log.error("Lỗi khi mở lại ca khám lâm sàng: ", ex);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Lỗi máy chủ khi mở lại ca khám: " + ex.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Lấy thông tin khám tổng quát (Triệu chứng, Tiền sử dị ứng/bệnh nền, Mô mềm/TMJ)
     */
    @GetMapping("/{visitId}/general-examination")
    public ResponseEntity<com.cvdcms.DTO.GeneralExaminationRequestDTO> getGeneralExamination(@PathVariable Long visitId) {
        return ResponseEntity.ok(diagnosisService.getGeneralExamination(visitId));
    }

    /**
     * Bác sĩ lưu kết quả khám tổng quát
     */
    @PostMapping("/{visitId}/general-examination")
    public ResponseEntity<Map<String, Object>> saveGeneralExamination(
            @PathVariable Long visitId,
            @RequestBody com.cvdcms.DTO.GeneralExaminationRequestDTO request
    ) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (request == null) {
                request = new com.cvdcms.DTO.GeneralExaminationRequestDTO();
            }
            request.setVisitId(visitId);
            diagnosisService.saveGeneralExamination(request);

            response.put("success", true);
            response.put("message", "Đã lưu thông tin khám tổng quát thành công!");
            return ResponseEntity.ok(response);
        } catch (IllegalStateException | IllegalArgumentException ex) {
            response.put("success", false);
            response.put("message", ex.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception ex) {
            log.error("Lỗi khi lưu khám tổng quát ca khám {}: ", visitId, ex);
            response.put("success", false);
            response.put("message", "Lỗi máy chủ khi lưu thông tin khám tổng quát: " + ex.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
