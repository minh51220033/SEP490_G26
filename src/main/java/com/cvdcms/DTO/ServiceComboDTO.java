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
public class ServiceComboDTO {
    private Long comboId;
    private String comboCode;
    private String comboName;
    private String description;
    private String status;
    private BigDecimal totalBasePrice;
    private Integer totalDurationMinutes;
    private Integer serviceCount;

    @Builder.Default
    private List<ComboItemDTO> items = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComboItemDTO {
        private Long comboItemId;
        private Long serviceId;
        private String serviceCode;
        private String serviceName;
        private String unit;
        private BigDecimal basePrice;
        private Integer sequenceOrder;
        private Integer defaultQuantity;
        private String notes;
        private Long workflowTemplateId;
        private String workflowTemplateName;
        private Integer workflowStepCount;
        private Integer estimatedDurationMinutes;
        private Boolean allowSameVisit;
    }
}
