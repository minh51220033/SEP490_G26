package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowStepDTO {
    private Long stepId;
    private Integer sequenceOrder;
    private String stepCode;
    private String stepName;
    private String stepDescription;
    private String completionCriteria;
    private Integer estimatedDurationMinutes;
    private java.math.BigDecimal stepPrice;
    private String stepType;
    private Boolean isOptional;
    private Boolean requiresClinicalApproval;
    private String status;

    // Execution fields (cho bảng checklist lâm sàng tại ghế)
    private Long stepExecutionId;
    private String executionStatus; // PENDING, IN_PROGRESS, COMPLETED, SKIPPED
    private String completedAt;
    private String resultNote;
    private String staffName;

    // FT43: thông tin thực hiện bổ sung cho bảng tiến độ quy trình
    private String startedAt;
    private String targetQueueType;
    @Builder.Default
    private java.util.List<String> allowedActions = new java.util.ArrayList<>();
}
