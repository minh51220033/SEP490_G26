package com.cvdcms.DTO;

import com.cvdcms.entity.enums.DiagnosisType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosisResponseDTO {
    private Long diagnosisId;
    private Long examId;
    private Long visitId;
    private String visitCode;
    private String patientName;
    private String icd10Code;
    private String diseaseNameVi;
    private String diseaseNameEn;
    private String diagnosisText;
    private DiagnosisType diagnosisType;
    private String diagnosisTypeDisplayName;
    private String toothNumbers;
    private String notes;
}
