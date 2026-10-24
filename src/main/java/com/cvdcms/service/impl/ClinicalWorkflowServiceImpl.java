package com.cvdcms.service.impl;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.ClinicalWorkflowService;
import com.cvdcms.service.GeminiAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClinicalWorkflowServiceImpl implements ClinicalWorkflowService {

    private final ClinicalWorkflowTemplateRepository templateRepo;
    private final ClinicalWorkflowVersionRepository versionRepo;
    private final ClinicalWorkflowStepRepository stepRepo;
    private final WorkflowExecutionRepository executionRepo;
    private final WorkflowStepExecutionRepository stepExecutionRepo;
    private final ServiceRepository serviceRepo;
    private final BranchRepository branchRepo;
    private final VisitRepository visitRepo;
    private final StaffProfileRepository staffProfileRepo;
    private final GeminiAiService geminiAiService;

    private static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowTemplateResponseDTO> getAllTemplates() {
        List<ClinicalWorkflowTemplate> templates = templateRepo.findAllByOrderByCreatedAtDesc();
        return templates.stream().map(this::mapTemplateToDTO).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public WorkflowTemplateResponseDTO getTemplateById(Long templateId) {
        ClinicalWorkflowTemplate template = templateRepo.findById(templateId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy quy trình kỹ thuật với ID: " + templateId));
        return mapTemplateToDTO(template);
    }

    @Override
    @Transactional(readOnly = true)
    public WorkflowTemplateResponseDTO getTemplateByServiceId(Long serviceId) {
        if (serviceId == null) return null;
        Optional<ClinicalWorkflowTemplate> opt = templateRepo.findFirstByService_ServiceIdAndStatus(serviceId, WorkflowTemplateStatus.ACTIVE);
        if (opt.isEmpty()) {
            List<ClinicalWorkflowTemplate> allForService = templateRepo.findByService_ServiceId(serviceId);
            if (!allForService.isEmpty()) {
                opt = Optional.of(allForService.get(0));
            }
        }
        return opt.map(this::mapTemplateToDTO).orElse(null);
    }

    @Override
    @Transactional
    public WorkflowTemplateResponseDTO saveTemplate(WorkflowTemplateRequestDTO dto, Long userId) {
        ClinicalWorkflowTemplate template;

        if (dto.getTemplateId() != null) {
            template = templateRepo.findById(dto.getTemplateId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy quy trình với ID: " + dto.getTemplateId()));
        } else {
            template = new ClinicalWorkflowTemplate();
            template.setCreatedBy(userId);
        }

        // Code
        String code = dto.getTemplateCode() != null ? dto.getTemplateCode().trim().toUpperCase() : "";
        if (code.isEmpty()) {
            code = "QTKT-" + System.currentTimeMillis() % 100000;
        }
        template.setTemplateCode(code);
        template.setTemplateName(dto.getTemplateName());
        template.setDescription(dto.getDescription());

        // Status
        if (dto.getStatus() != null && !dto.getStatus().isEmpty()) {
            try {
                template.setStatus(WorkflowTemplateStatus.valueOf(dto.getStatus()));
            } catch (Exception ignored) {
                template.setStatus(WorkflowTemplateStatus.ACTIVE);
            }
        } else {
            template.setStatus(WorkflowTemplateStatus.ACTIVE);
        }

        // Link Service
        if (dto.getServiceId() != null) {
            com.cvdcms.entity.Service s = serviceRepo.findById(dto.getServiceId()).orElse(null);
            template.setService(s);
        } else {
            template.setService(null);
        }

        template = templateRepo.save(template);

        // Version
        ClinicalWorkflowVersion version = versionRepo.findFirstByTemplate_TemplateIdOrderByVersionNumberDesc(template.getTemplateId())
                .orElse(null);

        if (version == null) {
            version = new ClinicalWorkflowVersion();
            version.setTemplate(template);
            version.setVersionNumber(1);
            version.setStatus(WorkflowVersionStatus.PUBLISHED);
            version.setFlowMode(FlowMode.CONTINUOUS);
            version.setChangeSummary("Khởi tạo quy trình chuẩn Bộ Y Tế");
            version.setCreatedBy(userId);
            version.setPublishedAt(LocalDateTime.now());
            version = versionRepo.save(version);
        }

        // Cập nhật các bước (Steps)
        if (dto.getSteps() != null) {
            // Xóa các bước cũ của version này
            stepRepo.deleteByVersion_VersionId(version.getVersionId());
            stepRepo.flush();

            int seq = 1;
            for (WorkflowStepDTO sDto : dto.getSteps()) {
                ClinicalWorkflowStep step = new ClinicalWorkflowStep();
                step.setVersion(version);
                step.setSequenceOrder(sDto.getSequenceOrder() != null ? sDto.getSequenceOrder() : seq++);
                step.setStepCode(sDto.getStepCode() != null && !sDto.getStepCode().isEmpty() 
                        ? sDto.getStepCode() : "STEP-" + step.getSequenceOrder());
                step.setStepName(sDto.getStepName());
                step.setStepDescription(sDto.getStepDescription());
                step.setCompletionCriteria(sDto.getCompletionCriteria());
                step.setEstimatedDurationMinutes(sDto.getEstimatedDurationMinutes() != null ? sDto.getEstimatedDurationMinutes() : 10);
                step.setIsOptional(sDto.getIsOptional() != null ? sDto.getIsOptional() : false);
                step.setRequiresClinicalApproval(sDto.getRequiresClinicalApproval() != null ? sDto.getRequiresClinicalApproval() : false);

                if (sDto.getStepType() != null && !sDto.getStepType().isEmpty()) {
                    try {
                        step.setStepType(StepType.valueOf(sDto.getStepType().toUpperCase()));
                    } catch (Exception ignored) {
                        step.setStepType(StepType.CLINICAL);
                    }
                } else {
                    step.setStepType(StepType.CLINICAL);
                }

                step.setStatus(StepStatus.ACTIVE);
                stepRepo.save(step);
            }
        }

        return mapTemplateToDTO(template);
    }

    @Override
    @Transactional
    public void deleteTemplate(Long templateId) {
        ClinicalWorkflowTemplate template = templateRepo.findById(templateId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy quy trình với ID: " + templateId));

        List<ClinicalWorkflowVersion> versions = versionRepo.findByTemplate_TemplateIdOrderByVersionNumberDesc(templateId);
        for (ClinicalWorkflowVersion v : versions) {
            stepRepo.deleteByVersion_VersionId(v.getVersionId());
            versionRepo.delete(v);
        }
        templateRepo.delete(template);
    }

    @Override
    public WorkflowTemplateResponseDTO parseWorkflowAi(String text, String imageBase64, String mimeType) {
        return geminiAiService.parseWorkflowProcedure(text, imageBase64, mimeType);
    }

    @Override
    @Transactional
    public WorkflowExecutionDTO getOrCreateExecution(Long visitId, Long serviceId, Long staffId) {
        if (visitId == null || serviceId == null) {
            throw new IllegalArgumentException("visitId và serviceId không được để trống");
        }

        Visit visit = visitRepo.findById(visitId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca khám với ID: " + visitId));

        Optional<ClinicalWorkflowTemplate> optTemplate = templateRepo.findFirstByService_ServiceIdAndStatus(serviceId, WorkflowTemplateStatus.ACTIVE);
        if (optTemplate.isEmpty()) {
            List<ClinicalWorkflowTemplate> all = templateRepo.findByService_ServiceId(serviceId);
            if (!all.isEmpty()) {
                optTemplate = Optional.of(all.get(0));
            } else {
                return null; // Không có quy trình cho dịch vụ này
            }
        }

        ClinicalWorkflowTemplate template = optTemplate.get();
        ClinicalWorkflowVersion version = versionRepo.findFirstByTemplate_TemplateIdAndStatus(template.getTemplateId(), WorkflowVersionStatus.PUBLISHED)
                .orElseGet(() -> versionRepo.findFirstByTemplate_TemplateIdOrderByVersionNumberDesc(template.getTemplateId()).orElse(null));

        if (version == null) return null;

        List<ClinicalWorkflowStep> steps = stepRepo.findByVersion_VersionIdOrderBySequenceOrderAsc(version.getVersionId());
        if (steps.isEmpty()) return null;

        // Tìm execution đã có hoặc tạo mới
        WorkflowExecution execution = executionRepo.findFirstByVisit_VisitIdAndVersion_Template_Service_ServiceId(visitId, serviceId)
                .orElse(null);

        if (execution == null) {
            execution = new WorkflowExecution();
            execution.setVersion(version);
            execution.setVisit(visit);
            execution.setPatientProfile(visit.getPatientProfile());
            execution.setBranch(visit.getBranch());
            execution.setCurrentStep(steps.get(0));
            execution.setStatus(WorkflowExecutionStatus.IN_PROGRESS);
            execution = executionRepo.save(execution);

            StaffProfile staff = staffId != null ? staffProfileRepo.findById(staffId).orElse(null) : null;

            for (ClinicalWorkflowStep st : steps) {
                WorkflowStepExecution se = new WorkflowStepExecution();
                se.setExecution(execution);
                se.setStep(st);
                se.setStatus(WorkflowStepExecutionStatus.PENDING);
                se.setAssignedStaff(staff);
                stepExecutionRepo.save(se);
            }
        }

        // Map DTO
        return mapExecutionToDTO(execution, steps);
    }

    @Override
    @Transactional
    public WorkflowStepDTO updateStepExecution(Long executionId, Long stepId, String status, String note, Long staffId) {
        WorkflowExecution execution = executionRepo.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy execution với ID: " + executionId));

        WorkflowStepExecution se = stepExecutionRepo.findByExecution_ExecutionIdAndStep_StepId(executionId, stepId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bước thực thi cho stepId: " + stepId));

        WorkflowStepExecutionStatus stEnum = WorkflowStepExecutionStatus.valueOf(status.toUpperCase());
        se.setStatus(stEnum);

        if (stEnum == WorkflowStepExecutionStatus.COMPLETED) {
            se.setCompletedAt(LocalDateTime.now());
            if (se.getStartedAt() == null) se.setStartedAt(LocalDateTime.now());
        } else if (stEnum == WorkflowStepExecutionStatus.IN_PROGRESS) {
            if (se.getStartedAt() == null) se.setStartedAt(LocalDateTime.now());
        }

        if (note != null) {
            se.setResultNote(note);
        }

        if (staffId != null) {
            StaffProfile staff = staffProfileRepo.findById(staffId).orElse(null);
            if (staff != null) se.setAssignedStaff(staff);
        }

        se = stepExecutionRepo.save(se);

        // Kiểm tra xem đã hoàn thành tất cả các bước chưa
        List<WorkflowStepExecution> allSteps = stepExecutionRepo.findByExecution_ExecutionId(executionId);
        boolean allCompleted = allSteps.stream().allMatch(s -> 
                s.getStatus() == WorkflowStepExecutionStatus.COMPLETED || s.getStatus() == WorkflowStepExecutionStatus.SKIPPED);
        
        if (allCompleted) {
            execution.setStatus(WorkflowExecutionStatus.COMPLETED);
            execution.setCompletedAt(LocalDateTime.now());
            executionRepo.save(execution);
        }

        return mapStepExecutionToDTO(se);
    }

    // Helper mapping
    private WorkflowTemplateResponseDTO mapTemplateToDTO(ClinicalWorkflowTemplate t) {
        WorkflowTemplateResponseDTO.WorkflowTemplateResponseDTOBuilder builder = WorkflowTemplateResponseDTO.builder()
                .templateId(t.getTemplateId())
                .templateCode(t.getTemplateCode())
                .templateName(t.getTemplateName())
                .description(t.getDescription())
                .status(t.getStatus() != null ? t.getStatus().name() : "ACTIVE")
                .createdAt(t.getCreatedAt() != null ? t.getCreatedAt().format(DT_FORMAT) : "")
                .updatedAt(t.getUpdatedAt() != null ? t.getUpdatedAt().format(DT_FORMAT) : "");

        if (t.getService() != null) {
            builder.serviceId(t.getService().getServiceId())
                    .serviceCode(t.getService().getServiceCode())
                    .serviceName(t.getService().getServiceName());
        }

        ClinicalWorkflowVersion version = versionRepo.findFirstByTemplate_TemplateIdOrderByVersionNumberDesc(t.getTemplateId())
                .orElse(null);

        if (version != null) {
            builder.versionId(version.getVersionId())
                    .versionNumber(version.getVersionNumber())
                    .flowMode(version.getFlowMode() != null ? version.getFlowMode().name() : "CONTINUOUS");

            List<ClinicalWorkflowStep> steps = stepRepo.findByVersion_VersionIdOrderBySequenceOrderAsc(version.getVersionId());
            builder.stepCount(steps.size());

            List<WorkflowStepDTO> stepDTOs = steps.stream().map(s -> WorkflowStepDTO.builder()
                    .stepId(s.getStepId())
                    .sequenceOrder(s.getSequenceOrder())
                    .stepCode(s.getStepCode())
                    .stepName(s.getStepName())
                    .stepDescription(s.getStepDescription())
                    .completionCriteria(s.getCompletionCriteria())
                    .estimatedDurationMinutes(s.getEstimatedDurationMinutes())
                    .stepType(s.getStepType() != null ? s.getStepType().name() : "CLINICAL")
                    .isOptional(s.getIsOptional())
                    .requiresClinicalApproval(s.getRequiresClinicalApproval())
                    .status(s.getStatus() != null ? s.getStatus().name() : "ACTIVE")
                    .build()).collect(Collectors.toList());

            builder.steps(stepDTOs);
        } else {
            builder.stepCount(0);
            builder.steps(new ArrayList<>());
        }

        return builder.build();
    }

    private WorkflowExecutionDTO mapExecutionToDTO(WorkflowExecution execution, List<ClinicalWorkflowStep> steps) {
        List<WorkflowStepExecution> stepExecutions = stepExecutionRepo.findByExecution_ExecutionId(execution.getExecutionId());
        Map<Long, WorkflowStepExecution> seMap = stepExecutions.stream()
                .collect(Collectors.toMap(se -> se.getStep().getStepId(), se -> se, (k1, k2) -> k1));

        List<WorkflowStepDTO> stepDTOs = new ArrayList<>();
        int completedCount = 0;

        for (ClinicalWorkflowStep step : steps) {
            WorkflowStepExecution se = seMap.get(step.getStepId());
            String execStatus = se != null ? se.getStatus().name() : "PENDING";
            if (execStatus.equals("COMPLETED")) {
                completedCount++;
            }

            WorkflowStepDTO dto = WorkflowStepDTO.builder()
                    .stepId(step.getStepId())
                    .sequenceOrder(step.getSequenceOrder())
                    .stepCode(step.getStepCode())
                    .stepName(step.getStepName())
                    .stepDescription(step.getStepDescription())
                    .completionCriteria(step.getCompletionCriteria())
                    .estimatedDurationMinutes(step.getEstimatedDurationMinutes())
                    .stepType(step.getStepType() != null ? step.getStepType().name() : "CLINICAL")
                    .stepExecutionId(se != null ? se.getStepExecutionId() : null)
                    .executionStatus(execStatus)
                    .completedAt(se != null && se.getCompletedAt() != null ? se.getCompletedAt().format(DT_FORMAT) : null)
                    .resultNote(se != null ? se.getResultNote() : null)
                    .staffName(se != null && se.getAssignedStaff() != null ? se.getAssignedStaff().getFullName() : null)
                    .build();
            stepDTOs.add(dto);
        }

        int total = steps.size();
        int progress = total > 0 ? (completedCount * 100 / total) : 0;

        return WorkflowExecutionDTO.builder()
                .executionId(execution.getExecutionId())
                .templateId(execution.getVersion().getTemplate().getTemplateId())
                .templateCode(execution.getVersion().getTemplate().getTemplateCode())
                .templateName(execution.getVersion().getTemplate().getTemplateName())
                .visitId(execution.getVisit().getVisitId())
                .serviceId(execution.getVersion().getTemplate().getService() != null ? execution.getVersion().getTemplate().getService().getServiceId() : null)
                .serviceName(execution.getVersion().getTemplate().getService() != null ? execution.getVersion().getTemplate().getService().getServiceName() : null)
                .status(execution.getStatus().name())
                .totalSteps(total)
                .completedSteps(completedCount)
                .progressPercent(progress)
                .startedAt(execution.getStartedAt() != null ? execution.getStartedAt().format(DT_FORMAT) : null)
                .completedAt(execution.getCompletedAt() != null ? execution.getCompletedAt().format(DT_FORMAT) : null)
                .steps(stepDTOs)
                .build();
    }

    private WorkflowStepDTO mapStepExecutionToDTO(WorkflowStepExecution se) {
        ClinicalWorkflowStep step = se.getStep();
        return WorkflowStepDTO.builder()
                .stepId(step.getStepId())
                .sequenceOrder(step.getSequenceOrder())
                .stepCode(step.getStepCode())
                .stepName(step.getStepName())
                .stepDescription(step.getStepDescription())
                .completionCriteria(step.getCompletionCriteria())
                .stepExecutionId(se.getStepExecutionId())
                .executionStatus(se.getStatus().name())
                .completedAt(se.getCompletedAt() != null ? se.getCompletedAt().format(DT_FORMAT) : null)
                .resultNote(se.getResultNote())
                .staffName(se.getAssignedStaff() != null ? se.getAssignedStaff().getFullName() : null)
                .build();
    }
}
