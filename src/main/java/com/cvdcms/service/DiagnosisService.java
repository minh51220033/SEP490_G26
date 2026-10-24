package com.cvdcms.service;

import com.cvdcms.DTO.ClinicalCompletionCheckDTO;
import com.cvdcms.DTO.DiagnosisRequestDTO;
import com.cvdcms.DTO.DiagnosisResponseDTO;
import com.cvdcms.entity.Visit;

import java.util.List;

public interface DiagnosisService {

    List<DiagnosisResponseDTO> getDiagnosesByVisitId(Long visitId);

    DiagnosisResponseDTO addDiagnosis(DiagnosisRequestDTO request);

    void deleteDiagnosis(Long diagnosisId);

    Visit getVisitOrThrow(Long visitId);

    List<Visit> getAvailableVisitsForDiagnosis();

    ClinicalCompletionCheckDTO checkClinicalCompletionReadiness(Long visitId);

    Visit completeClinicalExamination(Long visitId, String doctorNotes);

    Visit reopenClinicalExamination(Long visitId, String reason);
}
