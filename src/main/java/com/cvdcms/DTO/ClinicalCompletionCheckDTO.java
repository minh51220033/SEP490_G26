package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClinicalCompletionCheckDTO {
    private Long visitId;
    private String visitCode;
    private Long profileId;
    private String patientName;
    private String patientCode;
    private String doctorName;
    private String currentStatus;

    private boolean ready;
    private boolean hasDiagnosis;
    private int diagnosisCount;
    private boolean hasMainDiagnosis;

    private boolean hasCompletedServices;
    private int completedServiceCount;
    private BigDecimal totalBillableAmount;

    private boolean hasPrescription;
    private int prescriptionCount;

    private boolean hasReExamination;
    private int reExaminationCount;

    private List<String> missingRequirements;
    private List<String> warnings;

    private List<Map<String, Object>> diagnoses;
    private List<Map<String, Object>> services;
}
