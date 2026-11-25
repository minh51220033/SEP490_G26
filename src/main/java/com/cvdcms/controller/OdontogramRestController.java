package com.cvdcms.controller;

import com.cvdcms.DTO.OdontogramRequestDTO;
import com.cvdcms.DTO.OdontogramResponseDTO;
import com.cvdcms.entity.PatientOdontogram;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.User;
import com.cvdcms.security.PatientAccess;
import com.cvdcms.repository.PatientOdontogramRepository;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.repository.VisitRepository;
import com.cvdcms.service.PatientOdontogramService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class OdontogramRestController {

    private final PatientOdontogramService odontogramService;
    private final PatientOdontogramRepository odontogramRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final VisitRepository visitRepository;
    private final PatientAccess patientAccess;

    @GetMapping("/patients/{patientId}/odontograms")
    public ResponseEntity<Map<String, Object>> listByPatient(
            @PathVariable Long patientId,
            HttpSession session) {
        requirePatientAccess(session, patientId);
        List<OdontogramResponseDTO> list = odontogramService.listByPatient(patientId);
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", list);
        body.put("total", list.size());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/odontograms/{id}")
    public ResponseEntity<Map<String, Object>> getDetail(
            @PathVariable Long id,
            HttpSession session) {
        PatientOdontogram record = odontogramRepository.findByOdontogramIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bản ghi tình trạng răng."));
        requirePatientAccess(session, record.getPatientProfile().getProfileId());
        OdontogramResponseDTO dto = odontogramService.getDetail(id);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", dto
        ));
    }

    @PostMapping("/patients/{patientId}/odontograms")
    public ResponseEntity<Map<String, Object>> create(
            @PathVariable Long patientId,
            @RequestBody OdontogramRequestDTO request,
            HttpSession session) {
        User actor = requirePatientAccess(session, patientId);
        OdontogramResponseDTO created = odontogramService.create(patientId, request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "success", true,
                "data", created,
                "message", "Đã lưu sơ đồ tình trạng răng thành công."
        ));
    }

    @PutMapping("/odontograms/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @RequestBody OdontogramRequestDTO request,
            HttpSession session) {
        PatientOdontogram record = odontogramRepository.findByOdontogramIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bản ghi tình trạng răng."));
        User actor = requirePatientAccess(session, record.getPatientProfile().getProfileId());
        OdontogramResponseDTO updated = odontogramService.update(id, request, actor);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", updated,
                "message", "Đã cập nhật sơ đồ tình trạng răng thành công."
        ));
    }

    @DeleteMapping("/odontograms/{id}")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable Long id,
            HttpSession session) {
        PatientOdontogram record = odontogramRepository.findByOdontogramIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bản ghi tình trạng răng."));
        User actor = requirePatientAccess(session, record.getPatientProfile().getProfileId());
        odontogramService.softDelete(id, actor);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Đã xóa bản ghi sơ đồ răng."
        ));
    }

    private User requirePatientAccess(HttpSession session, Long patientId) {
        PatientAccess.Scope scope = patientAccess.require(session);
        PatientProfile patient = patientProfileRepository.findById(patientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hồ sơ bệnh nhân."));
        if (scope.admin()) {
            return scope.user();
        }
        Long preferredBranchId = patient.getPreferredBranch() != null ? patient.getPreferredBranch().getBranchId() : null;
        if (preferredBranchId != null && scope.permits(preferredBranchId)) {
            return scope.user();
        }
        boolean hasVisitInAllowedBranch = scope.branchIds() != null && !scope.branchIds().isEmpty()
                && visitRepository.findByPatientProfile_ProfileIdOrderByCheckInAtDesc(patientId).stream()
                .anyMatch(v -> v.getBranch() != null && scope.branchIds().contains(v.getBranch().getBranchId()));
        if (hasVisitInAllowedBranch) {
            return scope.user();
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền truy cập hồ sơ bệnh nhân của chi nhánh khác.");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                "success", false,
                "message", ex.getReason() != null ? ex.getReason() : "Không thể thực hiện thao tác."
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ex.getMessage() != null ? ex.getMessage() : "Dữ liệu không hợp lệ."
        ));
    }
}
