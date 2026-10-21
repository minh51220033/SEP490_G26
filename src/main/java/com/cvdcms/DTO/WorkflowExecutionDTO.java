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
public class WorkflowExecutionDTO {
    private Long executionId;
    private Long templateId;
    private String templateCode;
    private String templateName;
    private Long visitId;
    private Long serviceId;
    private String serviceName;
    private String status; // IN_PROGRESS, COMPLETED, PAUSED, CANCELLED
    private Integer totalSteps;
    private Integer completedSteps;
    private Integer progressPercent;
    private String startedAt;
    private String completedAt;

    @Builder.Default
    private List<WorkflowStepDTO> steps = new ArrayList<>();
}
