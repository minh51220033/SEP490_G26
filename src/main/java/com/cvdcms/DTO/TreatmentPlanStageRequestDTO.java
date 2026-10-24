package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TreatmentPlanStageRequestDTO {
    private Integer stageOrder;
    private String stageName;
    private Integer estimatedDaysFromStart;
    private String description;
    private String status; // PENDING, IN_PROGRESS, COMPLETED, SKIPPED

    @Builder.Default
    private List<TreatmentPlanItemRequestDTO> items = new ArrayList<>();
}
