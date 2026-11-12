package com.cvdcms.service.impl;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.ClinicalWorkflowService;
import com.cvdcms.service.GeminiAiService;
import com.cvdcms.service.SystemAuditLogService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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
    private final UserRepository userRepo;
    private final PerformedServiceRepository performedServiceRepo;
    private final ParaclinicalOrderItemRepository paraclinicalItemRepo;
    private final SystemAuditLogRepository auditRepo;
    private final SystemAuditLogService auditService;

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
                step.setStepPrice(sDto.getStepPrice() != null ? sDto.getStepPrice() : java.math.BigDecimal.ZERO);
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

    // =====================================================================
    // FT43 - QUẢN LÝ THỰC HIỆN QUY TRÌNH KHÁM & ĐIỀU TRỊ (UC-13)
    // =====================================================================

    private static final Set<VisitStatus> LOCKED_VISIT_STATUSES =
            EnumSet.of(VisitStatus.WAITING_PAYMENT, VisitStatus.COMPLETED, VisitStatus.CANCELLED);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Các thao tác được phép trên bước hiện tại của quy trình. */
    private enum StepAction { START, COMPLETE, PAUSE, RESUME, SKIP }

    @Override
    @Transactional
    public WorkflowExecutionDTO getOrCreateExecution(Long visitId, Long serviceId, Long userId) {
        return createOrGetExecution(visitId, serviceId, userId, false);
    }

    @Override
    @Transactional
    public WorkflowExecutionDTO startExecution(Long visitId, Long serviceId, Long userId) {
        return createOrGetExecution(visitId, serviceId, userId, true);
    }

    private WorkflowExecutionDTO createOrGetExecution(Long visitId, Long serviceId, Long userId, boolean strict) {
        if (visitId == null || serviceId == null) {
            throw new IllegalArgumentException("visitId và serviceId không được để trống");
        }

        Visit visit = visitRepo.findById(visitId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca khám với ID: " + visitId));

        // Quy trình đã khởi tạo thì luôn trả về đúng phiên bản đã áp dụng cho lượt khám (không đổi theo mẫu mới)
        Optional<WorkflowExecution> existing =
                executionRepo.findFirstByVisit_VisitIdAndVersion_Template_Service_ServiceId(visitId, serviceId);
        if (existing.isPresent()) {
            return mapExecutionToDTO(existing.get());
        }

        String lockReason = lockReason(visit);
        if (lockReason != null) {
            throw new IllegalStateException(lockReason);
        }

        ClinicalWorkflowTemplate template = templateRepo
                .findFirstByService_ServiceIdAndStatus(serviceId, WorkflowTemplateStatus.ACTIVE).orElse(null);
        ClinicalWorkflowVersion version = template != null ? resolveVersion(template) : null;
        List<ClinicalWorkflowStep> steps = version != null ? activeSteps(version) : List.of();
        if (template == null || version == null || steps.isEmpty()) {
            if (strict) {
                throw new IllegalArgumentException(
                        "Dịch vụ này chưa có mẫu quy trình đang hoạt động hoặc mẫu chưa có bước nào (FT34).");
            }
            return null;
        }

        WorkflowExecution execution = new WorkflowExecution();
        execution.setVersion(version);
        execution.setVisit(visit);
        execution.setPatientProfile(visit.getPatientProfile());
        execution.setBranch(visit.getBranch());
        execution.setCurrentStep(steps.get(0));
        execution.setStatus(WorkflowExecutionStatus.IN_PROGRESS);
        execution = executionRepo.save(execution);

        for (ClinicalWorkflowStep st : steps) {
            WorkflowStepExecution se = new WorkflowStepExecution();
            se.setExecution(execution);
            se.setStep(st);
            se.setStatus(WorkflowStepExecutionStatus.PENDING);
            stepExecutionRepo.save(se);
        }

        Map<String, Object> created = new LinkedHashMap<>();
        created.put("visitId", visitId);
        created.put("template", template.getTemplateName());
        created.put("version", version.getVersionNumber());
        created.put("steps", steps.size());
        writeAudit(userId, visit.getBranch(), "WORKFLOW_CREATE", "workflow_executions",
                execution.getExecutionId(), null, created);

        return mapExecutionToDTO(execution);
    }

    @Override
    @Transactional(readOnly = true)
    public WorkflowVisitOverviewDTO getVisitOverview(Long visitId) {
        Visit visit = visitRepo.findById(visitId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca khám với ID: " + visitId));
        String lockReason = lockReason(visit);

        List<WorkflowExecution> executions = new ArrayList<>(executionRepo.findByVisit_VisitId(visitId));
        executions.sort(Comparator.comparing(WorkflowExecution::getStartedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));

        Set<Long> startedServiceIds = new HashSet<>();
        List<WorkflowExecutionDTO> executionDTOs = new ArrayList<>();
        for (WorkflowExecution e : executions) {
            executionDTOs.add(mapExecutionToDTO(e));
            ClinicalWorkflowTemplate t = e.getVersion().getTemplate();
            if (t.getService() != null) {
                startedServiceIds.add(t.getService().getServiceId());
            }
        }

        Set<Long> performedServiceIds = performedServiceRepo.findByVisit_VisitIdOrderByCompletedAtDesc(visitId).stream()
                .filter(ps -> ps.getService() != null)
                .map(ps -> ps.getService().getServiceId())
                .collect(Collectors.toSet());

        List<WorkflowVisitOverviewDTO.ServiceOption> available = new ArrayList<>();
        if (lockReason == null) {
            for (ClinicalWorkflowTemplate t : templateRepo.findAllByOrderByCreatedAtDesc()) {
                if (t.getStatus() != WorkflowTemplateStatus.ACTIVE || t.getService() == null
                        || startedServiceIds.contains(t.getService().getServiceId())) {
                    continue;
                }
                ClinicalWorkflowVersion v = resolveVersion(t);
                int stepCount = v != null ? activeSteps(v).size() : 0;
                if (stepCount == 0) {
                    continue;
                }
                available.add(WorkflowVisitOverviewDTO.ServiceOption.builder()
                        .serviceId(t.getService().getServiceId())
                        .serviceCode(t.getService().getServiceCode())
                        .serviceName(t.getService().getServiceName())
                        .templateName(t.getTemplateName())
                        .stepCount(stepCount)
                        .suggested(performedServiceIds.contains(t.getService().getServiceId()))
                        .build());
            }
            available.sort(Comparator.comparing((WorkflowVisitOverviewDTO.ServiceOption o) -> !o.getSuggested())
                    .thenComparing(o -> o.getServiceName() == null ? "" : o.getServiceName()));
        }

        return WorkflowVisitOverviewDTO.builder()
                .visitId(visitId)
                .locked(lockReason != null)
                .lockedReason(lockReason)
                .executions(executionDTOs)
                .availableServices(available)
                .build();
    }

    @Override
    @Transactional
    public WorkflowExecutionDTO performStepAction(Long executionId, Long stepId, String action, String note,
                                                  boolean criteriaConfirmed, Long userId) {
        if (executionId == null || stepId == null || action == null || action.isBlank()) {
            throw new IllegalArgumentException("Thiếu thông tin thao tác bước quy trình.");
        }
        StepAction act;
        try {
            act = StepAction.valueOf(action.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Thao tác không hợp lệ: " + action);
        }

        WorkflowExecution execution = executionRepo.findForUpdate(executionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy execution với ID: " + executionId));
        Visit visit = execution.getVisit();

        String lockReason = lockReason(visit);
        if (lockReason != null) {
            throw new IllegalStateException(lockReason);
        }
        if (execution.getStatus() == WorkflowExecutionStatus.COMPLETED) {
            throw new IllegalStateException("Quy trình đã hoàn tất, không thể thao tác thêm.");
        }
        if (execution.getStatus() == WorkflowExecutionStatus.CANCELLED) {
            throw new IllegalStateException("Quy trình đã bị hủy, không thể thao tác.");
        }

        List<WorkflowStepExecution> all =
                stepExecutionRepo.findByExecution_ExecutionIdOrderByStep_SequenceOrderAsc(executionId);
        WorkflowStepExecution target = all.stream()
                .filter(s -> stepId.equals(s.getStep().getStepId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bước thực thi cho stepId: " + stepId));
        WorkflowStepExecution current = firstOpenStep(all);
        if (current == null) {
            throw new IllegalStateException("Tất cả các bước của quy trình đã hoàn tất.");
        }
        if (current != target) {
            throw new IllegalStateException("Phải thực hiện theo đúng thứ tự các bước. Bước hiện tại là: "
                    + current.getStep().getStepName());
        }

        ClinicalWorkflowStep step = target.getStep();
        WorkflowStepExecutionStatus from = target.getStatus();
        FlowMode mode = execution.getVersion().getFlowMode();
        LocalDateTime now = LocalDateTime.now();
        StaffProfile staff = staffOf(userId);
        String cleanNote = note == null || note.isBlank() ? null : note.trim();

        switch (act) {
            case START -> {
                if (from != WorkflowStepExecutionStatus.PENDING) {
                    throw new IllegalStateException("Chỉ có thể bắt đầu bước đang ở trạng thái chờ thực hiện.");
                }
                target.setStatus(WorkflowStepExecutionStatus.IN_PROGRESS);
                target.setStartedAt(now);
                if (staff != null) target.setAssignedStaff(staff);
            }
            case RESUME -> {
                if (from != WorkflowStepExecutionStatus.PAUSED) {
                    throw new IllegalStateException("Chỉ có thể tiếp tục bước đang tạm dừng.");
                }
                target.setStatus(WorkflowStepExecutionStatus.IN_PROGRESS);
                if (staff != null) target.setAssignedStaff(staff);
            }
            case PAUSE -> {
                if (from != WorkflowStepExecutionStatus.IN_PROGRESS) {
                    throw new IllegalStateException("Chỉ có thể tạm dừng bước đang thực hiện.");
                }
                if (mode != FlowMode.INTERRUPTIBLE) {
                    throw new IllegalStateException(
                            "Quy trình này cấu hình luồng liên tục (CONTINUOUS), không cho phép tạm dừng.");
                }
                if (cleanNote == null) {
                    throw new IllegalArgumentException("Vui lòng nhập lý do tạm dừng (ví dụ: chuyển đi chụp phim).");
                }
                target.setStatus(WorkflowStepExecutionStatus.PAUSED);
            }
            case SKIP -> {
                if (from != WorkflowStepExecutionStatus.PENDING) {
                    throw new IllegalStateException("Chỉ có thể bỏ qua bước chưa bắt đầu.");
                }
                if (!Boolean.TRUE.equals(step.getIsOptional())) {
                    throw new IllegalStateException("Bước bắt buộc không thể bỏ qua. Chỉ bước tùy chọn mới được bỏ qua.");
                }
                target.setStatus(WorkflowStepExecutionStatus.SKIPPED);
                target.setCompletedAt(now);
                if (staff != null) target.setAssignedStaff(staff);
            }
            case COMPLETE -> {
                if (from != WorkflowStepExecutionStatus.IN_PROGRESS) {
                    throw new IllegalStateException("Phải bắt đầu bước trước khi hoàn tất.");
                }
                String criteria = step.getCompletionCriteria();
                if (criteria != null && !criteria.isBlank() && !criteriaConfirmed) {
                    throw new IllegalStateException("Vui lòng xác nhận đã đạt điều kiện hoàn tất của bước: " + criteria);
                }
                if (Boolean.TRUE.equals(step.getRequiresClinicalApproval())) {
                    if (!canApproveClinically(userId)) {
                        throw new IllegalStateException("Bước này cần bác sĩ phê duyệt chuyên môn trước khi hoàn tất.");
                    }
                    target.setApprovedBy(userId);
                    target.setApprovedAt(now);
                }
                if (step.getStepType() == StepType.PARACLINICAL) {
                    checkParaclinicalReady(visit.getVisitId(), step);
                }
                target.setStatus(WorkflowStepExecutionStatus.COMPLETED);
                target.setCompletedAt(now);
                if (target.getStartedAt() == null) target.setStartedAt(now);
                if (staff != null) target.setAssignedStaff(staff);
            }
        }
        if (cleanNote != null) {
            target.setResultNote(cleanNote);
        }
        stepExecutionRepo.save(target);

        // Cập nhật bước hiện tại & trạng thái chung của quy trình
        WorkflowStepExecution next = firstOpenStep(all);
        boolean finished = next == null;
        if (finished) {
            execution.setStatus(WorkflowExecutionStatus.COMPLETED);
            execution.setCompletedAt(now);
            execution.setCurrentStep(null);
        } else {
            execution.setCurrentStep(next.getStep());
            execution.setStatus(next.getStatus() == WorkflowStepExecutionStatus.PAUSED
                    ? WorkflowExecutionStatus.PAUSED : WorkflowExecutionStatus.IN_PROGRESS);
            execution.setCompletedAt(null);
        }
        executionRepo.save(execution);

        Map<String, Object> oldVal = new LinkedHashMap<>();
        oldVal.put("status", from.name());
        Map<String, Object> newVal = new LinkedHashMap<>();
        newVal.put("status", target.getStatus().name());
        newVal.put("stepName", step.getStepName());
        newVal.put("note", cleanNote);
        writeAudit(userId, visit.getBranch(), "WORKFLOW_STEP_" + act.name(), "workflow_step_executions",
                target.getStepExecutionId(), oldVal, newVal);
        if (finished) {
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("visitId", visit.getVisitId());
            done.put("status", WorkflowExecutionStatus.COMPLETED.name());
            writeAudit(userId, visit.getBranch(), "WORKFLOW_COMPLETE", "workflow_executions",
                    execution.getExecutionId(), null, done);
        }

        return mapExecutionToDTO(execution);
    }

    /** Giữ lại cho tương thích: ánh xạ trạng thái cũ sang thao tác mới và dùng chung luật nghiệp vụ FT43. */
    @Override
    @Transactional
    public WorkflowStepDTO updateStepExecution(Long executionId, Long stepId, String status, String note, Long staffId) {
        String action = switch (status == null ? "" : status.trim().toUpperCase()) {
            case "IN_PROGRESS" -> "START";
            case "COMPLETED" -> "COMPLETE";
            case "PAUSED" -> "PAUSE";
            case "SKIPPED" -> "SKIP";
            default -> throw new IllegalArgumentException("Trạng thái bước không hợp lệ: " + status);
        };
        WorkflowExecutionDTO dto = performStepAction(executionId, stepId, action, note, true, staffId);
        return dto.getSteps().stream().filter(s -> stepId.equals(s.getStepId())).findFirst().orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public WorkflowExecutionDTO getExecutionDetail(Long executionId) {
        WorkflowExecution execution = executionRepo.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy execution với ID: " + executionId));
        return mapExecutionToDTO(execution);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowExecutionDTO> getProgressBoard(Collection<Long> branchIds, LocalDate day) {
        LocalDate d = day != null ? day : LocalDate.now();
        boolean allBranches = branchIds == null;
        Collection<Long> ids = allBranches || branchIds.isEmpty() ? List.of(-1L) : branchIds;
        return executionRepo.findBoard(d.atStartOfDay(), d.plusDays(1).atStartOfDay(), allBranches, ids).stream()
                .map(this::mapExecutionToDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowHistoryEntryDTO> getExecutionHistory(Long executionId) {
        WorkflowExecution execution = executionRepo.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy execution với ID: " + executionId));

        List<SystemAuditLog> logs = new ArrayList<>(auditRepo
                .findByEntityTableAndEntityIdOrderByCreatedAtDesc("workflow_executions", executionId));
        for (WorkflowStepExecution se : stepExecutionRepo.findByExecution_ExecutionId(execution.getExecutionId())) {
            logs.addAll(auditRepo.findByEntityTableAndEntityIdOrderByCreatedAtDesc(
                    "workflow_step_executions", se.getStepExecutionId()));
        }
        logs.sort(Comparator.comparing(SystemAuditLog::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));

        List<WorkflowHistoryEntryDTO> result = new ArrayList<>();
        for (SystemAuditLog l : logs) {
            Map<String, Object> oldVal = parseJson(l.getOldValuesJson());
            Map<String, Object> newVal = parseJson(l.getNewValuesJson());
            String actorName = l.getUser() != null
                    ? (l.getUser().getFullName() != null && !l.getUser().getFullName().isBlank()
                        ? l.getUser().getFullName() : l.getUser().getUsername())
                    : "Hệ thống";
            result.add(WorkflowHistoryEntryDTO.builder()
                    .time(l.getCreatedAt() != null ? l.getCreatedAt().format(DT_FORMAT) : "")
                    .stepName(asText(newVal.get("stepName")))
                    .action(l.getAction())
                    .actionLabel(actionLabel(l.getAction()))
                    .actorName(actorName)
                    .fromStatus(asText(oldVal.get("status")))
                    .toStatus(asText(newVal.get("status")))
                    .note(asText(newVal.get("note")))
                    .build());
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // Helper FT43
    // ---------------------------------------------------------------------

    private ClinicalWorkflowVersion resolveVersion(ClinicalWorkflowTemplate template) {
        return versionRepo.findFirstByTemplate_TemplateIdAndStatus(template.getTemplateId(), WorkflowVersionStatus.PUBLISHED)
                .orElseGet(() -> versionRepo.findFirstByTemplate_TemplateIdOrderByVersionNumberDesc(template.getTemplateId())
                        .orElse(null));
    }

    private List<ClinicalWorkflowStep> activeSteps(ClinicalWorkflowVersion version) {
        return stepRepo.findByVersion_VersionIdOrderBySequenceOrderAsc(version.getVersionId()).stream()
                .filter(s -> s.getStatus() == null || s.getStatus() == StepStatus.ACTIVE)
                .collect(Collectors.toList());
    }

    private WorkflowStepExecution firstOpenStep(List<WorkflowStepExecution> ordered) {
        for (WorkflowStepExecution se : ordered) {
            if (se.getStatus() != WorkflowStepExecutionStatus.COMPLETED
                    && se.getStatus() != WorkflowStepExecutionStatus.SKIPPED) {
                return se;
            }
        }
        return null;
    }

    /** Ca khám đã chuyển thu ngân / hoàn tất / hủy thì khóa toàn bộ thao tác quy trình (BR-EMR-02). */
    private String lockReason(Visit visit) {
        if (visit == null || visit.getStatus() == null || !LOCKED_VISIT_STATUSES.contains(visit.getStatus())) {
            return null;
        }
        return switch (visit.getStatus()) {
            case CANCELLED -> "Lượt khám đã bị hủy, không thể thao tác quy trình.";
            case COMPLETED -> "Lượt khám đã hoàn tất, quy trình được khóa.";
            default -> "Lượt khám đang chờ thanh toán, quy trình được khóa. Hãy mở lại ca khám nếu cần chỉnh sửa.";
        };
    }

    private StaffProfile staffOf(Long userId) {
        return userId == null ? null : staffProfileRepo.findByUserUserId(userId).orElse(null);
    }

    private boolean canApproveClinically(Long userId) {
        return userId != null && (userRepo.hasActiveAdminRole(userId)
                || userRepo.hasActivePermission(userId, "CLINICAL_DAILY_VISITS"));
    }

    /** Bước cận lâm sàng chỉ hoàn tất khi các chỉ định của lượt khám (FT12-FT13) đã có kết quả. */
    private void checkParaclinicalReady(Long visitId, ClinicalWorkflowStep step) {
        List<ParaclinicalOrderItem> items = paraclinicalItemRepo
                .findByOrder_Visit_VisitIdOrderByOrder_OrderIdDescOrderItemIdDesc(visitId).stream()
                .filter(i -> i.getStatus() != ParaclinicalOrderItemStatus.CANCELLED)
                .collect(Collectors.toList());
        long pending = items.stream().filter(i -> i.getStatus() != ParaclinicalOrderItemStatus.COMPLETED).count();
        if (pending > 0) {
            throw new IllegalStateException("Còn " + pending + " chỉ định cận lâm sàng chưa có kết quả. "
                    + "Hoàn tất các chỉ định trước khi hoàn tất bước này.");
        }
        if (items.isEmpty() && !Boolean.TRUE.equals(step.getIsOptional())) {
            throw new IllegalStateException("Lượt khám chưa có chỉ định cận lâm sàng. "
                    + "Hãy tạo chỉ định ở tab Khám bệnh trước khi hoàn tất bước này.");
        }
    }

    /** Ghi nhật ký hoạt động (UC-25); lỗi ghi log không làm hỏng thao tác nghiệp vụ. */
    private void writeAudit(Long userId, Branch branch, String action, String table, Long entityId,
                            Object oldVal, Object newVal) {
        try {
            User user = userId != null ? userRepo.findById(userId).orElse(null) : null;
            auditService.logAction(user, branch, action, table, entityId, null, oldVal, newVal);
        } catch (Exception ex) {
            log.warn("Không thể ghi nhật ký quy trình {}: {}", action, ex.getMessage());
        }
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String actionLabel(String action) {
        if (action == null) return "";
        return switch (action) {
            case "WORKFLOW_CREATE" -> "Khởi tạo quy trình";
            case "WORKFLOW_COMPLETE" -> "Hoàn tất quy trình";
            case "WORKFLOW_STEP_START" -> "Bắt đầu bước";
            case "WORKFLOW_STEP_COMPLETE" -> "Hoàn tất bước";
            case "WORKFLOW_STEP_PAUSE" -> "Tạm dừng";
            case "WORKFLOW_STEP_RESUME" -> "Tiếp tục";
            case "WORKFLOW_STEP_SKIP" -> "Bỏ qua bước";
            default -> action;
        };
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
                    .stepPrice(s.getStepPrice())
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

    private WorkflowExecutionDTO mapExecutionToDTO(WorkflowExecution execution) {
        ClinicalWorkflowVersion version = execution.getVersion();
        ClinicalWorkflowTemplate template = version.getTemplate();
        Visit visit = execution.getVisit();

        // Nguồn dữ liệu là các bước đã gắn với lượt khám, nên luôn đúng phiên bản mẫu đã áp dụng
        List<WorkflowStepExecution> stepExecutions =
                stepExecutionRepo.findByExecution_ExecutionIdOrderByStep_SequenceOrderAsc(execution.getExecutionId());
        WorkflowStepExecution current = firstOpenStep(stepExecutions);

        String lockReason = lockReason(visit);
        boolean actionable = lockReason == null
                && (execution.getStatus() == WorkflowExecutionStatus.IN_PROGRESS
                    || execution.getStatus() == WorkflowExecutionStatus.PAUSED);
        boolean interruptible = version.getFlowMode() == FlowMode.INTERRUPTIBLE;

        List<WorkflowStepDTO> stepDTOs = new ArrayList<>();
        int completedCount = 0;
        int skippedCount = 0;

        for (WorkflowStepExecution se : stepExecutions) {
            ClinicalWorkflowStep step = se.getStep();
            WorkflowStepExecutionStatus st = se.getStatus();
            if (st == WorkflowStepExecutionStatus.COMPLETED) completedCount++;
            if (st == WorkflowStepExecutionStatus.SKIPPED) skippedCount++;

            List<String> actions = new ArrayList<>();
            if (actionable && se == current) {
                switch (st) {
                    case PENDING -> {
                        actions.add("START");
                        if (Boolean.TRUE.equals(step.getIsOptional())) actions.add("SKIP");
                    }
                    case IN_PROGRESS -> {
                        actions.add("COMPLETE");
                        if (interruptible) actions.add("PAUSE");
                    }
                    case PAUSED -> actions.add("RESUME");
                    default -> { }
                }
            }

            stepDTOs.add(WorkflowStepDTO.builder()
                    .stepId(step.getStepId())
                    .sequenceOrder(step.getSequenceOrder())
                    .stepCode(step.getStepCode())
                    .stepName(step.getStepName())
                    .stepDescription(step.getStepDescription())
                    .completionCriteria(step.getCompletionCriteria())
                    .estimatedDurationMinutes(step.getEstimatedDurationMinutes())
                    .stepPrice(step.getStepPrice())
                    .stepType(step.getStepType() != null ? step.getStepType().name() : "CLINICAL")
                    .isOptional(step.getIsOptional())
                    .requiresClinicalApproval(step.getRequiresClinicalApproval())
                    .targetQueueType(step.getTargetQueueType() != null ? step.getTargetQueueType().name() : null)
                    .stepExecutionId(se.getStepExecutionId())
                    .executionStatus(st.name())
                    .startedAt(se.getStartedAt() != null ? se.getStartedAt().format(DT_FORMAT) : null)
                    .completedAt(se.getCompletedAt() != null ? se.getCompletedAt().format(DT_FORMAT) : null)
                    .resultNote(se.getResultNote())
                    .staffName(se.getAssignedStaff() != null ? se.getAssignedStaff().getFullName() : null)
                    .allowedActions(actions)
                    .build());
        }

        int total = stepExecutions.size();
        int progress = total > 0 ? ((completedCount + skippedCount) * 100 / total) : 0;
        ClinicalWorkflowStep currentStep = current != null ? current.getStep() : null;

        return WorkflowExecutionDTO.builder()
                .executionId(execution.getExecutionId())
                .templateId(template.getTemplateId())
                .templateCode(template.getTemplateCode())
                .templateName(template.getTemplateName())
                .visitId(visit.getVisitId())
                .serviceId(template.getService() != null ? template.getService().getServiceId() : null)
                .serviceName(template.getService() != null ? template.getService().getServiceName() : null)
                .branchId(execution.getBranch() != null ? execution.getBranch().getBranchId() : null)
                .branchName(execution.getBranch() != null ? execution.getBranch().getBranchName() : null)
                .patientName(execution.getPatientProfile() != null ? execution.getPatientProfile().getFullName() : null)
                .patientCode(execution.getPatientProfile() != null ? execution.getPatientProfile().getPatientCode() : null)
                .doctorName(visit.getPrimaryDoctor() != null ? visit.getPrimaryDoctor().getFullName() : null)
                .visitStatus(visit.getStatus() != null ? visit.getStatus().name() : null)
                .flowMode(version.getFlowMode() != null ? version.getFlowMode().name() : "CONTINUOUS")
                .status(execution.getStatus().name())
                .totalSteps(total)
                .completedSteps(completedCount)
                .skippedSteps(skippedCount)
                .progressPercent(progress)
                .currentStepId(currentStep != null ? currentStep.getStepId() : null)
                .currentStepName(currentStep != null ? currentStep.getStepName() : null)
                .nextQueueType(currentStep != null && currentStep.getTargetQueueType() != null
                        ? currentStep.getTargetQueueType().name() : null)
                .locked(lockReason != null)
                .lockedReason(lockReason)
                .startedAt(execution.getStartedAt() != null ? execution.getStartedAt().format(DT_FORMAT) : null)
                .completedAt(execution.getCompletedAt() != null ? execution.getCompletedAt().format(DT_FORMAT) : null)
                .steps(stepDTOs)
                .build();
    }
}
