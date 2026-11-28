package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private String frameKey;
    private Boolean isOptional;
    private Boolean requiresClinicalApproval;
    private Boolean isAdhoc;
    private String status;

    @Builder.Default
    private List<CustomFieldDTO> customFields = new ArrayList<>();

    // Execution fields (cho bảng checklist lâm sàng tại ghế)
    private Long stepExecutionId;
    private String executionStatus; // PENDING, IN_PROGRESS, PAUSED, COMPLETED, SKIPPED
    private String completedAt;
    private String resultNote;
    private String staffName;
    private Long assignedStaffId;
    private Boolean readOnlyByOtherDoctor;

    @Builder.Default
    private Map<String, Object> resultData = new LinkedHashMap<>();

    // FT43: thông tin thực hiện bổ sung cho bảng tiến độ quy trình
    private String startedAt;
    private String targetQueueType;
    @Builder.Default
    private List<String> allowedActions = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CustomFieldDTO {
        private String id;
        private String label;
        private String type; // text, textarea, select
        @Builder.Default
        private List<String> options = new ArrayList<>();
        private String placeholder;
    }
}
