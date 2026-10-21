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
public class WorkflowTemplateResponseDTO {
    private Long templateId;
    private String templateCode;
    private String templateName;
    private String description;
    private Long serviceId;
    private String serviceCode;
    private String serviceName;
    private Long clinicId;
    private String status; // DRAFT, ACTIVE, INACTIVE
    private Long versionId;
    private Integer versionNumber;
    private String flowMode;
    private Integer stepCount;
    private String createdAt;
    private String updatedAt;

    @Builder.Default
    private List<WorkflowStepDTO> steps = new ArrayList<>();
}
