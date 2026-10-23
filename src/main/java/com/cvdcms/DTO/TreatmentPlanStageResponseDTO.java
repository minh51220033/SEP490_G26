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
public class TreatmentPlanStageResponseDTO {
    private Long stageId;
    private Integer stageOrder;
    private String stageName;
    private String status; // PENDING, IN_PROGRESS, COMPLETED, SKIPPED
    private String statusBadgeClass;
    private String statusDisplayName;
    private String completedAt;
    private String expectedEndDate;
    private String description;
    private BigDecimal stageTotalCost;

    @Builder.Default
    private List<TreatmentPlanItemResponseDTO> items = new ArrayList<>();
}
