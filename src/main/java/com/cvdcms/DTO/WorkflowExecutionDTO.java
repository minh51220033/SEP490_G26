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

    // FT43: thông tin liên kết với lượt khám / hàng chờ
    private Long branchId;
    private String branchName;
    private String patientName;
    private String patientCode;
    private String doctorName;
    private String visitStatus;
    private String flowMode;
    private Integer skippedSteps;
    private Long currentStepId;
    private String currentStepName;
    private String nextQueueType;
    private Boolean locked;
    private String lockedReason;

    @Builder.Default
    private List<WorkflowStepDTO> steps = new ArrayList<>();
}
