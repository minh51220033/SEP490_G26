package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TreatmentPlanRequestDTO {
    private Long patientId;
    private Long branchId;
    private Long doctorId;
    private String planName;
    private String diagnosisText;
    private BigDecimal totalEstimatedCost;
    private BigDecimal finalEstimatedCost;
    private String status; // DRAFT, PROPOSED, ACCEPTED, IN_PROGRESS, COMPLETED, CANCELLED
    private String startDate; // yyyy-MM-dd
    private String expectedEndDate; // yyyy-MM-dd
    private String notes;

    @Builder.Default
    private List<TreatmentPlanStageRequestDTO> stages = new ArrayList<>();
}
