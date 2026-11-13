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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.security.DoctorVisitAccess doctorVisitAccess;

    private jakarta.servlet.http.HttpSession currentHttpSession() {
        var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        return attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra
                ? sra.getRequest().getSession(false) : null;
    }

    private boolean isHttpRequestActive() {
        return org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes;
    }

    @PostMapping("/re-examinations")
    public ResponseEntity<ReExaminationResponseDTO> createReExamination(@RequestBody ReExaminationRequestDTO dto) {
        if (doctorVisitAccess != null && isHttpRequestActive()) {
            jakarta.servlet.http.HttpSession session = currentHttpSession();
            if (dto != null && dto.getVisitId() != null) {
                doctorVisitAccess.require(session, dto.getVisitId());
            } else if (dto != null && dto.getPatientProfileId() != null) {
                doctorVisitAccess.requirePatientWrite(session, dto.getPatientProfileId());
            } else {
                doctorVisitAccess.requireAuthenticatedUser(session);
            }
            if (dto != null && dto.getBranchId() != null) {
                doctorVisitAccess.requireBranch(session, dto.getBranchId());
            }
        }
        log.info("REST: Creating re-examination appointment");
        ReExaminationResponseDTO response = reExaminationService.createReExamination(dto);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/re-examinations/patient/{patientId}")
    public ResponseEntity<List<ReExaminationResponseDTO>> getPatientReExaminations(@PathVariable Long patientId) {
        if (doctorVisitAccess != null && isHttpRequestActive()) {
            doctorVisitAccess.requirePatientRead(currentHttpSession(), patientId);
        }
        return ResponseEntity.ok(reExaminationService.getReExaminationsByPatient(patientId));
    }

    @GetMapping("/recalls")
    public ResponseEntity<List<ReExaminationResponseDTO>> getRecalls(
            @RequestParam(value = "dateFilter", defaultValue = "ALL") String dateFilter,
            @RequestParam(value = "doctorId", required = false) Long doctorId,
            @RequestParam(value = "status", defaultValue = "ALL") String status,
            @RequestParam(value = "keyword", required = false) String keyword
    ) {
        List<ReExaminationResponseDTO> list = reExaminationService.getRecallsList(dateFilter, doctorId, status, keyword);
        if (doctorVisitAccess != null && isHttpRequestActive()) {
            com.cvdcms.entity.User user = doctorVisitAccess.requireAuthenticatedUser(currentHttpSession());
            list = list.stream()
                    .filter(item -> doctorVisitAccess.isBranchAllowed(user, item.getBranchId()))
                    .toList();
        }
        return ResponseEntity.ok(list);
    }

    @GetMapping("/recalls/statistics")
    public ResponseEntity<Map<String, Object>> getRecallsStatistics() {
        if (doctorVisitAccess != null && isHttpRequestActive()) {
            doctorVisitAccess.requireAuthenticatedUser(currentHttpSession());
        }
        return ResponseEntity.ok(reExaminationService.getRecallsStatistics());
    }

    @PutMapping("/re-examinations/{id}/status")
    public ResponseEntity<ReExaminationResponseDTO> updateStatus(
            @PathVariable Long id,
            @RequestParam String status,
            @RequestParam(required = false) String cancelReason
    ) {
        if (doctorVisitAccess != null && isHttpRequestActive()) {
            doctorVisitAccess.requireReExamination(currentHttpSession(), id);
        }
        return ResponseEntity.ok(reExaminationService.updateAppointmentStatus(id, status, cancelReason));
    }

    @PutMapping("/re-examinations/{id}/reschedule")
    public ResponseEntity<ReExaminationResponseDTO> reschedule(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate newDate,
            @RequestParam(required = false) String newTime,
            @RequestParam(defaultValue = "MORNING") PreferredSession session
    ) {
        if (doctorVisitAccess != null && isHttpRequestActive()) {
            doctorVisitAccess.requireReExamination(currentHttpSession(), id);
        }
        return ResponseEntity.ok(reExaminationService.updateAppointmentDate(id, newDate, newTime, session));
    }

    @PutMapping("/care-tasks")
    public ResponseEntity<Map<String, Object>> updateCareTask(@RequestBody CareTaskUpdateDTO dto) {
        if (doctorVisitAccess != null && isHttpRequestActive()) {
            if (dto != null && dto.getTaskId() != null) {
                doctorVisitAccess.requireCareTask(currentHttpSession(), dto.getTaskId());
            } else {
                doctorVisitAccess.requireAuthenticatedUser(currentHttpSession());
            }
        }
        reExaminationService.updateCareTaskStatus(dto);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã cập nhật kết quả liên hệ CSKH"));
    }

    @DeleteMapping("/re-examinations/{id}")
    public ResponseEntity<Map<String, Object>> cancelAppointment(
            @PathVariable Long id,
            @RequestParam(defaultValue = "Bác sĩ / Bệnh nhân hủy hẹn") String reason
    ) {
        if (doctorVisitAccess != null && isHttpRequestActive()) {
            doctorVisitAccess.requireReExamination(currentHttpSession(), id);
        }
        reExaminationService.cancelAppointment(id, reason);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã hủy lịch hẹn tái khám"));
    }
}
