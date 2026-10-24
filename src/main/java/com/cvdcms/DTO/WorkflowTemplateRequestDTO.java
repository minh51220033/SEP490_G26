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
public class WorkflowTemplateRequestDTO {
    private Long templateId;
    private String templateCode;
    private String templateName;
    private String description;
    private Long serviceId;
    private Long clinicId;
    private String status; // DRAFT, ACTIVE, INACTIVE

    @Builder.Default
    private List<WorkflowStepDTO> steps = new ArrayList<>();
}
