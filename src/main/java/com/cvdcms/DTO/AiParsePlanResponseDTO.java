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
public class AiParsePlanResponseDTO {
    private String planTitle;
    private String procedureCode;
    private Integer totalEstimatedDays;
    private String postOpAdvice;
    private String contraindications;

    @Builder.Default
    private List<AiStageDTO> stages = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AiStageDTO {
        private Integer stageOrder;
        private String stageName;
        private Integer estimatedDaysFromStart;
        private String description;

        @Builder.Default
        private List<AiStepDTO> steps = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AiStepDTO {
        private Integer stepOrder;
        private String stepName;
        private String actionType; // SURGERY, EXAMINATION, MEDICATION, PROSTHETIC
        private String notes;
        private String suggestedServiceCode;
        private String suggestedTooth;
    }
}
