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
public class TreatmentPlanResponseDTO {
    private Long planId;
    private String planCode;
    private Long patientId;
    private String patientCode;
    private String patientName;
    private String phoneNumber;
    private Long branchId;
    private String branchName;
    private Long doctorId;
    private String doctorName;
    private String planName;
    private BigDecimal totalEstimatedCost;
    private BigDecimal finalEstimatedCost;
    private String status; // DRAFT, PROPOSED, ACCEPTED, IN_PROGRESS, COMPLETED, CANCELLED
    private String statusBadgeClass;
    private String statusDisplayName;
    private String startDate;
    private String expectedEndDate;
    private String notes;
    private String createdAt;
    private Integer totalStages;
    private Integer completedStages;
    private Integer progressPercent;

    @Builder.Default
    private List<TreatmentPlanStageResponseDTO> stages = new ArrayList<>();
}
