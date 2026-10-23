package com.cvdcms.controller;

import com.cvdcms.DTO.CareTaskUpdateDTO;
import com.cvdcms.DTO.ReExaminationRequestDTO;
import com.cvdcms.DTO.ReExaminationResponseDTO;
import com.cvdcms.entity.enums.PreferredSession;
import com.cvdcms.service.ReExaminationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/doctor")
@RequiredArgsConstructor
public class ReExaminationRestController {

    private final ReExaminationService reExaminationService;

    @PostMapping("/re-examinations")
    public ResponseEntity<ReExaminationResponseDTO> createReExamination(@RequestBody ReExaminationRequestDTO dto) {
        log.info("REST: Creating re-examination for patient: {}", dto.getPatientProfileId());
        ReExaminationResponseDTO response = reExaminationService.createReExamination(dto);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/re-examinations/patient/{patientId}")
    public ResponseEntity<List<ReExaminationResponseDTO>> getPatientReExaminations(@PathVariable Long patientId) {
        return ResponseEntity.ok(reExaminationService.getReExaminationsByPatient(patientId));
    }

    @GetMapping("/recalls")
    public ResponseEntity<List<ReExaminationResponseDTO>> getRecalls(
            @RequestParam(value = "dateFilter", defaultValue = "ALL") String dateFilter,
            @RequestParam(value = "doctorId", required = false) Long doctorId,
            @RequestParam(value = "status", defaultValue = "ALL") String status,
            @RequestParam(value = "keyword", required = false) String keyword
    ) {
        return ResponseEntity.ok(reExaminationService.getRecallsList(dateFilter, doctorId, status, keyword));
    }

    @GetMapping("/recalls/statistics")
    public ResponseEntity<Map<String, Object>> getRecallsStatistics() {
        return ResponseEntity.ok(reExaminationService.getRecallsStatistics());
    }

    @PutMapping("/re-examinations/{id}/status")
    public ResponseEntity<ReExaminationResponseDTO> updateStatus(
            @PathVariable Long id,
            @RequestParam String status,
            @RequestParam(required = false) String cancelReason
    ) {
        return ResponseEntity.ok(reExaminationService.updateAppointmentStatus(id, status, cancelReason));
    }

    @PutMapping("/re-examinations/{id}/reschedule")
    public ResponseEntity<ReExaminationResponseDTO> reschedule(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate newDate,
            @RequestParam(required = false) String newTime,
            @RequestParam(defaultValue = "MORNING") PreferredSession session
    ) {
        return ResponseEntity.ok(reExaminationService.updateAppointmentDate(id, newDate, newTime, session));
    }

    @PutMapping("/care-tasks")
    public ResponseEntity<Map<String, Object>> updateCareTask(@RequestBody CareTaskUpdateDTO dto) {
        reExaminationService.updateCareTaskStatus(dto);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã cập nhật kết quả liên hệ CSKH"));
    }

    @DeleteMapping("/re-examinations/{id}")
    public ResponseEntity<Map<String, Object>> cancelAppointment(
            @PathVariable Long id,
            @RequestParam(defaultValue = "Bác sĩ / Bệnh nhân hủy hẹn") String reason
    ) {
        reExaminationService.cancelAppointment(id, reason);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã hủy lịch hẹn tái khám"));
    }
}
