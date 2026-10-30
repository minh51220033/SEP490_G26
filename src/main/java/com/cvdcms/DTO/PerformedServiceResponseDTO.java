package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PerformedServiceResponseDTO {
    private Long performedId;
    private Long visitId;
    private Long profileId;
    private String patientName;
    private Long serviceId;
    private String serviceCode;
    private String serviceName;
    private String categoryName;
    private String unit;
    private String toothNumbers;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    private BigDecimal discountAmount;
    private BigDecimal finalPrice;
    private Long doctorId;
    private String doctorName;
    private String doctorCode;
    private Long assistantId;
    private String assistantName;
    private String completedAt;
    private String status;
    private String statusDisplay;
    private String clinicalNotes;
    private Boolean hasWorkflow;
    private Long workflowTemplateId;
    private Integer workflowStepCount;
    private String workflowTemplateName;
    private Long planItemId;
    private String planName;
    private String stageName;
}
