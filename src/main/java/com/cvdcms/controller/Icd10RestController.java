package com.cvdcms.controller;

import com.cvdcms.DTO.DiagnosisRequestDTO;
import com.cvdcms.DTO.DiagnosisResponseDTO;
import com.cvdcms.DTO.Icd10DiseaseDTO;
import com.cvdcms.service.DiagnosisService;
import com.cvdcms.service.Icd10DiseaseService;
import jakarta.validation.Valid;
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
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class Icd10RestController {

    private final Icd10DiseaseService icd10DiseaseService;
    private final DiagnosisService diagnosisService;

    @GetMapping("/icd10/search")
    public ResponseEntity<List<Icd10DiseaseDTO>> searchIcd10(
            @RequestParam(value = "q", required = false, defaultValue = "") String query,
            @RequestParam(value = "limit", required = false, defaultValue = "15") int limit
    ) {
        List<Icd10DiseaseDTO> results = icd10DiseaseService.searchDiseases(query, limit);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/icd10/common")
    public ResponseEntity<List<Icd10DiseaseDTO>> getCommonDiseases() {
        return ResponseEntity.ok(icd10DiseaseService.getCommonDentalDiseases());
    }

    @GetMapping("/diagnoses/visit/{visitId}")
    public ResponseEntity<List<DiagnosisResponseDTO>> getDiagnosesByVisit(@PathVariable Long visitId) {
        return ResponseEntity.ok(diagnosisService.getDiagnosesByVisitId(visitId));
    }

    @PostMapping("/diagnoses")
    public ResponseEntity<?> addDiagnosis(@Valid @RequestBody DiagnosisRequestDTO request) {
        try {
            DiagnosisResponseDTO response = diagnosisService.addDiagnosis(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            Map<String, String> err = new HashMap<>();
            err.put("error", ex.getMessage());
            return ResponseEntity.badRequest().body(err);
        } catch (Exception ex) {
            log.error("Lỗi khi thêm chẩn đoán: ", ex);
            Map<String, String> err = new HashMap<>();
            err.put("error", "Lỗi hệ thống: " + ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }

    @DeleteMapping("/diagnoses/{diagnosisId}")
    public ResponseEntity<?> deleteDiagnosis(@PathVariable Long diagnosisId) {
        try {
            diagnosisService.deleteDiagnosis(diagnosisId);
            Map<String, Object> res = new HashMap<>();
            res.put("success", true);
            res.put("message", "Đã xóa chẩn đoán thành công");
            return ResponseEntity.ok(res);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            Map<String, String> err = new HashMap<>();
            err.put("error", ex.getMessage());
            return ResponseEntity.badRequest().body(err);
        }
    }
}
