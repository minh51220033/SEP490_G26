package com.cvdcms.service;

import com.cvdcms.DTO.*;

import java.util.List;

public interface ClinicalWorkflowService {

    List<WorkflowTemplateResponseDTO> getAllTemplates();

    WorkflowTemplateResponseDTO getTemplateById(Long templateId);

    WorkflowTemplateResponseDTO getTemplateByServiceId(Long serviceId);

    WorkflowTemplateResponseDTO saveTemplate(WorkflowTemplateRequestDTO dto, Long userId);

    void deleteTemplate(Long templateId);

    WorkflowTemplateResponseDTO parseWorkflowAi(String text, String imageBase64, String mimeType);

    WorkflowExecutionDTO getOrCreateExecution(Long visitId, Long serviceId, Long staffId);

    WorkflowStepDTO updateStepExecution(Long executionId, Long stepId, String status, String note, Long staffId);
}
