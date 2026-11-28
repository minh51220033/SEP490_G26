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

/**
 * [Phân hệ]: Mẫu quy trình điều trị từng bước, 5 khung thao tác & Combo dịch vụ (Chương 7 - FT34/FT43/UC-13)
 * [Vai trò]: Lớp triển khai dịch vụ lõi quản lý vòng đời mẫu quy trình/phiên bản, cấu hình trường tùy biến của 5 khung thao tác, Combo dịch vụ mẫu và toàn bộ luồng thực thi quy trình điều trị từng bước (khởi tạo, chuyển bước, thêm/xóa bước ad-hoc, đồng bộ KHDT & dịch vụ thực hiện).
 */
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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.EmrRecordRepository emrRecordRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TreatmentPlanItemRepository treatmentPlanItemRepo;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TreatmentPlanStageRepository treatmentPlanStageRepo;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TreatmentPlanRepository treatmentPlanRepo;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.service.PerformedServiceService performedServiceService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.security.DoctorVisitAccess doctorVisitAccess;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ServiceComboRepository serviceComboRepo;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LaboOrderRepository laboOrderRepo;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private InvoiceItemRepository invoiceItemRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.service.DoctorClinicalCapabilityService capabilityService;

    private final Map<String, List<WorkflowStepDTO.CustomFieldDTO>> inMemoryFrameFields = new java.util.concurrent.ConcurrentHashMap<>();

    private static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final Set<String> VALID_FRAME_KEYS = Set.of(
            "CLINICAL_EXAM", "XRAY", "ENDODONTICS", "PROCEDURE", "PROSTHETICS_LABO");

    /**
     * [Chức năng]: Lấy toàn bộ danh sách mẫu quy trình kỹ thuật sắp xếp mới nhất trước.
     * [Mục đích]: Truy vấn `ClinicalWorkflowTemplateRepository` và ánh xạ sang `WorkflowTemplateResponseDTO` kèm phiên bản mới nhất và danh sách bước chuẩn.
     */
    @Override
    @Transactional(readOnly = true)
    public List<WorkflowTemplateResponseDTO> getAllTemplates() {
        List<ClinicalWorkflowTemplate> templates = templateRepo.findAllByOrderByCreatedAtDesc();
        return templates.stream().map(this::mapTemplateToDTO).collect(Collectors.toList());
    }

    /**
     * [Chức năng]: Lấy chi tiết một mẫu quy trình kỹ thuật theo `templateId`.
     * [Mục đích]: Nạp bản ghi mẫu quy trình từ cơ sở dữ liệu (ném lỗi nếu không tồn tại) và ánh xạ sang DTO phục vụ màn xem/sửa.
     */
    @Override
    @Transactional(readOnly = true)
    public WorkflowTemplateResponseDTO getTemplateById(Long templateId) {
        ClinicalWorkflowTemplate template = templateRepo.findById(templateId)
                .orElseThrow(
                        () -> new IllegalArgumentException("Không tìm thấy quy trình kỹ thuật với ID: " + templateId));
        return mapTemplateToDTO(template);
    }

    /**
     * [Chức năng]: Tra cứu mẫu quy trình đang hoạt động (`ACTIVE`) gắn với một `serviceId`.
     * [Mục đích]: Phục vụ bàn khám và API kiểm tra dịch vụ đã được thiết lập quy trình kỹ thuật chuẩn hay chưa.
     */
    @Override
    @Transactional(readOnly = true)
    public WorkflowTemplateResponseDTO getTemplateByServiceId(Long serviceId) {
        if (serviceId == null)
            return null;
        return templateRepo.findFirstByService_ServiceIdAndStatus(serviceId, WorkflowTemplateStatus.ACTIVE)
                .map(this::mapTemplateToDTO)
                .orElse(null);
    }

    /**
     * [Chức năng]: Tạo mới hoặc cập nhật mẫu quy trình kỹ thuật kèm phiên bản và danh sách bước chuẩn.
     * [Mục đích]: Validate tên, mã không trùng, `flowMode`, không đổi dịch vụ khi đã có execution và không trùng `stepCode`; khi phiên bản mới nhất đã `PUBLISHED` sẽ chuyển sang `RETIRED` và phát hành phiên bản mới để bảo toàn lịch sử các ca cũ.
     */
    @Override
    @Transactional
    public WorkflowTemplateResponseDTO saveTemplate(WorkflowTemplateRequestDTO dto, Long userId) {
        if (dto == null) {
            throw new IllegalArgumentException("Dữ liệu quy trình không được để trống.");
        }
        String templateName = dto.getTemplateName() != null ? dto.getTemplateName().trim() : "";
        if (templateName.isEmpty()) {
            throw new IllegalArgumentException("Tên quy trình kỹ thuật không được để trống.");
        }

        FlowMode requestedFlowMode = null;
        if (dto.getFlowMode() != null && !dto.getFlowMode().isBlank()) {
            try {
                requestedFlowMode = FlowMode.valueOf(dto.getFlowMode().trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(
                        "Chế độ luồng quy trình (flowMode) không hợp lệ: " + dto.getFlowMode());
            }
        }

        ClinicalWorkflowTemplate template;
        if (dto.getTemplateId() != null) {
            template = templateRepo.findById(dto.getTemplateId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Không tìm thấy quy trình với ID: " + dto.getTemplateId()));
        } else {
            template = new ClinicalWorkflowTemplate();
            template.setCreatedBy(userId);
        }

        // Code
        String code = dto.getTemplateCode() != null ? dto.getTemplateCode().trim().toUpperCase() : "";
        if (code.isEmpty()) {
            code = "QTKT-" + System.currentTimeMillis() % 100000;
        }
        Optional<ClinicalWorkflowTemplate> existingByCode = templateRepo.findByTemplateCode(code);
        if (existingByCode.isPresent()
                && (template.getTemplateId() == null
                        || !existingByCode.get().getTemplateId().equals(template.getTemplateId()))) {
            throw new IllegalStateException(
                    "Mã quy trình '" + code + "' đã tồn tại trên hệ thống. Vui lòng chọn mã khác.");
        }

        // Chặn thay đổi dịch vụ liên kết nếu quy trình đã phát sinh lượt thực hiện
        // (executions)
        if (template.getTemplateId() != null) {
            final Long targetTemplateId = template.getTemplateId();
            Long currentServiceId = template.getService() != null ? template.getService().getServiceId() : null;
            Long requestedServiceId = dto.getServiceId();
            if (!Objects.equals(currentServiceId, requestedServiceId)) {
                boolean hasTemplateExecutions = executionRepo.findAll().stream()
                        .anyMatch(ex -> ex.getVersion() != null
                                && ex.getVersion().getTemplate() != null
                                && targetTemplateId.equals(ex.getVersion().getTemplate().getTemplateId()));
                if (hasTemplateExecutions) {
                    throw new IllegalStateException(
                            "Không thể thay đổi dịch vụ liên kết vì quy trình đã có lượt khám thực hiện.");
                }
            }
        }

        // Validate danh sách bước trước khi cập nhật DB (tránh lỗi trùng stepCode hoặc
        // tên trống gây 500)
        if (dto.getSteps() != null) {
            Set<String> seenStepCodes = new HashSet<>();
            int seqCheck = 1;
            for (WorkflowStepDTO sDto : dto.getSteps()) {
                if (sDto == null || sDto.getStepName() == null || sDto.getStepName().trim().isEmpty()) {
                    throw new IllegalArgumentException("Tên bước quy trình không được để trống.");
                }
                int seqOrder = sDto.getSequenceOrder() != null ? sDto.getSequenceOrder() : seqCheck;
                seqCheck++;
                String stepCode = (sDto.getStepCode() != null && !sDto.getStepCode().trim().isEmpty())
                        ? sDto.getStepCode().trim().toUpperCase()
                        : "STEP-" + seqOrder;
                if (!seenStepCodes.add(stepCode)) {
                    throw new IllegalStateException("Mã bước '" + stepCode + "' bị trùng lặp trong cùng quy trình.");
                }
                if (sDto.getTargetQueueType() != null && !sDto.getTargetQueueType().isBlank()) {
                    try {
                        QueueType.valueOf(sDto.getTargetQueueType().trim().toUpperCase());
                    } catch (IllegalArgumentException ex) {
                        throw new IllegalArgumentException(
                                "Loại hàng đợi đích (targetQueueType) không hợp lệ: " + sDto.getTargetQueueType());
                    }
                }
            }
        }

        template.setTemplateCode(code);
        template.setTemplateName(templateName);
        template.setDescription(dto.getDescription());

        // Status
        if (dto.getStatus() != null && !dto.getStatus().isEmpty()) {
            try {
                template.setStatus(WorkflowTemplateStatus.valueOf(dto.getStatus().trim().toUpperCase()));
            } catch (Exception ignored) {
                template.setStatus(WorkflowTemplateStatus.ACTIVE);
            }
        } else {
            template.setStatus(WorkflowTemplateStatus.ACTIVE);
        }

        // Link Service
        if (dto.getServiceId() != null) {
            com.cvdcms.entity.Service s = serviceRepo.findById(dto.getServiceId())
                    .orElseThrow(
                            () -> new IllegalArgumentException("Không tìm thấy dịch vụ với ID: " + dto.getServiceId()));
            template.setService(s);
        } else {
            template.setService(null);
        }

        template = templateRepo.save(template);

        // Version: không bao giờ ghi đè/xóa các bước của version đang PUBLISHED hoặc đã
        // có execution
        ClinicalWorkflowVersion existingLatest = versionRepo
                .findFirstByTemplate_TemplateIdOrderByVersionNumberDesc(template.getTemplateId())
                .orElse(null);

        ClinicalWorkflowVersion targetVersion;
        if (existingLatest == null) {
            targetVersion = new ClinicalWorkflowVersion();
            targetVersion.setTemplate(template);
            targetVersion.setVersionNumber(1);
            targetVersion.setStatus(WorkflowVersionStatus.PUBLISHED);
            targetVersion.setFlowMode(requestedFlowMode != null ? requestedFlowMode : FlowMode.CONTINUOUS);
            targetVersion.setChangeSummary("Khởi tạo quy trình chuẩn Bộ Y Tế");
            targetVersion.setCreatedBy(userId);
            targetVersion.setPublishedAt(LocalDateTime.now());
            targetVersion = versionRepo.save(targetVersion);
        } else if (dto.getSteps() != null && existingLatest.getStatus() == WorkflowVersionStatus.PUBLISHED) {
            existingLatest.setStatus(WorkflowVersionStatus.RETIRED);
            versionRepo.save(existingLatest);

            int nextVersion = (existingLatest.getVersionNumber() != null ? existingLatest.getVersionNumber() : 1) + 1;
            targetVersion = new ClinicalWorkflowVersion();
            targetVersion.setTemplate(template);
            targetVersion.setVersionNumber(nextVersion);
            targetVersion.setStatus(WorkflowVersionStatus.PUBLISHED);
            targetVersion.setFlowMode(requestedFlowMode != null
                    ? requestedFlowMode
                    : (existingLatest.getFlowMode() != null ? existingLatest.getFlowMode() : FlowMode.CONTINUOUS));
            targetVersion.setChangeSummary("Cập nhật quy trình phiên bản v" + nextVersion);
            targetVersion.setCreatedBy(userId);
            targetVersion.setPublishedAt(LocalDateTime.now());
            targetVersion = versionRepo.save(targetVersion);
        } else {
            targetVersion = existingLatest;
            if (requestedFlowMode != null && targetVersion.getFlowMode() != requestedFlowMode) {
                targetVersion.setFlowMode(requestedFlowMode);
                targetVersion = versionRepo.save(targetVersion);
            }
        }

        // Cập nhật các bước (Steps) trên DRAFT mới tạo hoặc version mới phát hành
        if (dto.getSteps() != null) {
            if (targetVersion == existingLatest) {
                Long targetVersionId = targetVersion.getVersionId();
                boolean versionHasExecutions = executionRepo.findAll().stream()
                        .anyMatch(ex -> ex.getVersion() != null
                                && targetVersionId.equals(ex.getVersion().getVersionId()));
                if (!versionHasExecutions) {
                    List<ClinicalWorkflowStep> oldSteps = stepRepo
                            .findByVersion_VersionIdOrderBySequenceOrderAsc(targetVersionId)
                            .stream().filter(s -> s.getAdhocExecutionId() == null).toList();
                    if (!oldSteps.isEmpty()) {
                        Set<Long> oldStepIds = oldSteps.stream()
                                .map(ClinicalWorkflowStep::getStepId)
                                .filter(Objects::nonNull)
                                .collect(Collectors.toSet());
                        if (!oldStepIds.isEmpty()) {
                            versionHasExecutions = stepExecutionRepo.findAll().stream()
                                    .anyMatch(se -> se.getStep() != null
                                            && oldStepIds.contains(se.getStep().getStepId()));
                        }
                    }
                }
                if (versionHasExecutions) {
                    throw new IllegalStateException(
                            "Không thể xóa hoặc ghi đè các bước của phiên bản đã có lượt khám thực hiện.");
                }
                stepRepo.deleteByVersion_VersionId(targetVersionId);
                stepRepo.flush();
            }

            int seq = 1;
            for (WorkflowStepDTO sDto : dto.getSteps()) {
                ClinicalWorkflowStep step = new ClinicalWorkflowStep();
                step.setVersion(targetVersion);
                int seqOrder = sDto.getSequenceOrder() != null ? sDto.getSequenceOrder() : seq;
                seq++;
                step.setSequenceOrder(seqOrder);
                step.setStepCode(sDto.getStepCode() != null && !sDto.getStepCode().trim().isEmpty()
                        ? sDto.getStepCode().trim().toUpperCase()
                        : "STEP-" + seqOrder);
                step.setStepName(sDto.getStepName().trim());
                step.setStepDescription(sDto.getStepDescription());
                step.setCompletionCriteria(sDto.getCompletionCriteria());
                step.setCustomFieldsJson(serializeCustomFields(sDto.getCustomFields()));
                step.setEstimatedDurationMinutes(
                        sDto.getEstimatedDurationMinutes() != null ? sDto.getEstimatedDurationMinutes() : 10);
                step.setStepPrice(sDto.getStepPrice() != null ? sDto.getStepPrice() : java.math.BigDecimal.ZERO);
                step.setIsOptional(sDto.getIsOptional() != null ? sDto.getIsOptional() : false);
                step.setRequiresClinicalApproval(
                        sDto.getRequiresClinicalApproval() != null ? sDto.getRequiresClinicalApproval() : false);

                if (sDto.getStepType() != null && !sDto.getStepType().isEmpty()) {
                    try {
                        step.setStepType(StepType.valueOf(sDto.getStepType().trim().toUpperCase()));
                    } catch (Exception ignored) {
                        step.setStepType(StepType.CLINICAL);
                    }
                } else {
                    step.setStepType(StepType.CLINICAL);
                }
                step.setFrameKey(inferFrameKey(sDto.getFrameKey(), step.getStepType(), step.getStepName(), code));

                if (sDto.getTargetQueueType() != null && !sDto.getTargetQueueType().isBlank()) {
                    step.setTargetQueueType(QueueType.valueOf(sDto.getTargetQueueType().trim().toUpperCase()));
                } else {
                    step.setTargetQueueType(null);
                }

                step.setStatus(StepStatus.ACTIVE);
                stepRepo.save(step);
            }
        }

        return mapTemplateToDTO(template);
    }

    /**
     * [Chức năng]: Xóa mẫu quy trình kỹ thuật cùng toàn bộ phiên bản và bước chuẩn trực thuộc.
     * [Mục đích]: Chặn xóa nếu bất kỳ phiên bản nào đã phát sinh `WorkflowExecution` trong thực tế (yêu cầu chuyển trạng thái Ngừng áp dụng thay vì xóa).
     */
    @Override
    @Transactional
    public void deleteTemplate(Long templateId) {
        ClinicalWorkflowTemplate template = templateRepo.findById(templateId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy quy trình với ID: " + templateId));

        List<ClinicalWorkflowVersion> versions = versionRepo
                .findByTemplate_TemplateIdOrderByVersionNumberDesc(templateId);
        for (ClinicalWorkflowVersion v : versions) {
            boolean hasExecutions = executionRepo.findAll().stream()
                    .anyMatch(ex -> ex.getVersion() != null && v.getVersionId().equals(ex.getVersion().getVersionId()));
            if (hasExecutions) {
                throw new IllegalStateException(
                        "Không thể xóa quy trình kỹ thuật đã được sử dụng trong lượt khám thực tế. Hãy chuyển trạng thái sang Ngừng áp dụng.");
            }
        }
        for (ClinicalWorkflowVersion v : versions) {
            stepRepo.deleteByVersion_VersionId(v.getVersionId());
            versionRepo.delete(v);
        }
        templateRepo.delete(template);
    }

    // =====================================================================
    // FT43 - QUẢN LÝ THỰC HIỆN QUY TRÌNH KHÁM & ĐIỀU TRỊ (UC-13)
    // =====================================================================

    private static final Set<VisitStatus> LOCKED_VISIT_STATUSES = EnumSet.of(VisitStatus.WAITING_PAYMENT,
            VisitStatus.COMPLETED, VisitStatus.CANCELLED);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Các thao tác được phép trên bước hiện tại của quy trình. */
    private enum StepAction {
        START, COMPLETE, PAUSE, RESUME, SKIP, SAVE_DRAFT
    }

    /**
     * [Chức năng]: Lấy tiến trình quy trình hiện có hoặc tự khởi tạo mới cho cặp lượt khám và dịch vụ ở chế độ không ném lỗi (`strict=false`).
     * [Mục đích]: Trả về `null` nếu dịch vụ chưa có mẫu quy trình `ACTIVE`, dùng khi mở bàn khám kiểm tra tiến trình tự động.
     */
    @Override
    @Transactional
    public WorkflowExecutionDTO getOrCreateExecution(Long visitId, Long serviceId, Long userId) {
        return createOrGetExecution(visitId, serviceId, null, null, userId, false);
    }

    /**
     * [Chức năng]: Khởi tạo (hoặc lấy lại) tiến trình quy trình cho dịch vụ độc lập trên lượt khám (`strict=true`).
     * [Mục đích]: Ủy quyền cho `createOrGetExecution` và báo lỗi rõ ràng nếu dịch vụ chưa có mẫu quy trình hoạt động.
     */
    @Override
    @Transactional
    public WorkflowExecutionDTO startExecution(Long visitId, Long serviceId, Long userId) {
        return createOrGetExecution(visitId, serviceId, null, null, userId, true);
    }

    /**
     * [Chức năng]: Khởi tạo (hoặc lấy lại) tiến trình quy trình gắn với một hạng mục Kế hoạch điều trị (`planItemId`).
     * [Mục đích]: Liên kết tiến trình với `TreatmentPlanItem` để đồng bộ trạng thái và tự động tạo dịch vụ thực hiện khi hoàn tất.
     */
    @Override
    @Transactional
    public WorkflowExecutionDTO startExecution(Long visitId, Long serviceId, Long planItemId, Long userId) {
        return createOrGetExecution(visitId, serviceId, planItemId, null, userId, true);
    }

    /**
     * [Chức năng]: Khởi tạo (hoặc lấy lại) tiến trình quy trình gắn đồng thời hạng mục và/hoặc giai đoạn Kế hoạch điều trị (`planStageId`).
     * [Mục đích]: Hỗ trợ gom nhiều bước cùng dịch vụ trong một giai đoạn KHDT vào một tiến trình quy trình thống nhất, tránh nhân bản.
     */
    @Override
    @Transactional
    public WorkflowExecutionDTO startExecution(Long visitId, Long serviceId, Long planItemId, Long planStageId,
            Long userId) {
        return createOrGetExecution(visitId, serviceId, planItemId, planStageId, userId, true);
    }

    /**
     * [Hàm hỗ trợ]: Nạp chồng `createOrGetExecution` với số lượng khởi tạo mặc định (`initialQuantity = null`).
     */
    private WorkflowExecutionDTO createOrGetExecution(Long visitId, Long serviceId, Long planItemId,
            Long planStageId, Long userId, boolean strict) {
        return createOrGetExecution(visitId, serviceId, planItemId, planStageId, userId, strict, null);
    }

    /**
     * [Chức năng]: Thực hiện lõi lấy lại hoặc khởi tạo mới một `WorkflowExecution` và toàn bộ các `WorkflowStepExecution` tương ứng.
     * [Mục đích]: Khóa bi quan `visitRepo.lockById` tránh tạo trùng khi double-click, kiểm tra hợp lệ KHDT/khóa ca/năng lực bác sĩ, tái dùng tiến trình đã mở theo giai đoạn/hạng mục/bệnh nhân, tạo sẵn dữ liệu răng & số lượng vào bước đầu và ghi audit `WORKFLOW_CREATE`.
     */
    private WorkflowExecutionDTO createOrGetExecution(Long visitId, Long serviceId, Long planItemId,
            Long planStageId, Long userId, boolean strict, Integer initialQuantity) {
        if (visitId == null || (serviceId == null && planItemId == null && planStageId == null)) {
            throw new IllegalArgumentException("visitId và serviceId/planItemId/planStageId không được để trống");
        }

        // Khóa ca khám khi khởi tạo để tránh double-click hoặc 2 request đồng thời sinh
        // 2 execution trùng
        Visit visit = visitRepo.lockById(visitId)
                .orElseGet(() -> visitRepo.findById(visitId)
                        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca khám với ID: " + visitId)));

        TreatmentPlanItem planItem = null;
        TreatmentPlanStage planStage = null;
        Long resolvedServiceId = serviceId;

        if (planItemId != null && treatmentPlanItemRepo != null) {
            planItem = treatmentPlanItemRepo.findById(planItemId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Không tìm thấy mục kế hoạch điều trị với ID: " + planItemId));
            TreatmentPlan parentPlan = parentPlanOf(planItem);
            if (parentPlan != null && parentPlan.getStatus() == TreatmentPlanStatus.CANCELLED) {
                throw new IllegalStateException("Kế hoạch điều trị đã hủy, không thể khởi tạo quy trình.");
            }
            if (planItem.getStatus() == TreatmentPlanItemStatus.CANCELLED) {
                throw new IllegalStateException("Hạng mục kế hoạch điều trị đã hủy.");
            }
            if (parentPlan != null && parentPlan.getPatientProfile() != null && visit.getPatientProfile() != null
                    && !parentPlan.getPatientProfile().getProfileId()
                            .equals(visit.getPatientProfile().getProfileId())) {
                throw new IllegalArgumentException("Hạng mục kế hoạch không khớp bệnh nhân của lượt khám.");
            }
            if (resolvedServiceId == null && planItem.getService() != null) {
                resolvedServiceId = planItem.getService().getServiceId();
            }
            if (planStage == null) {
                planStage = planItem.getStage();
            }
        }

        if (planStage == null && planStageId != null && treatmentPlanStageRepo != null) {
            planStage = treatmentPlanStageRepo.findById(planStageId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Không tìm thấy giai đoạn kế hoạch điều trị với ID: " + planStageId));
            TreatmentPlan parentPlan = planStage.getPlan();
            if (parentPlan != null && parentPlan.getStatus() == TreatmentPlanStatus.CANCELLED) {
                throw new IllegalStateException("Kế hoạch điều trị đã hủy, không thể khởi tạo quy trình.");
            }
            if (parentPlan != null && parentPlan.getPatientProfile() != null && visit.getPatientProfile() != null
                    && !parentPlan.getPatientProfile().getProfileId()
                            .equals(visit.getPatientProfile().getProfileId())) {
                throw new IllegalArgumentException("Giai đoạn kế hoạch không khớp bệnh nhân của lượt khám.");
            }
        }

        List<TreatmentPlanItem> stageServiceItems = List.of();
        if (planStage != null && treatmentPlanItemRepo != null) {
            final Long filterServiceId = resolvedServiceId;
            stageServiceItems = treatmentPlanItemRepo.findByStage_StageId(planStage.getStageId()).stream()
                    .filter(i -> i.getStatus() != TreatmentPlanItemStatus.CANCELLED)
                    .filter(i -> filterServiceId == null
                            || (i.getService() != null && filterServiceId.equals(i.getService().getServiceId())))
                    .sorted(Comparator.comparing(TreatmentPlanItem::getPlanItemId))
                    .toList();
            if (resolvedServiceId == null && !stageServiceItems.isEmpty()
                    && stageServiceItems.get(0).getService() != null) {
                resolvedServiceId = stageServiceItems.get(0).getService().getServiceId();
            }
            if (planItem == null && !stageServiceItems.isEmpty()) {
                planItem = stageServiceItems.stream()
                        .filter(i -> i.getStatus() != TreatmentPlanItemStatus.COMPLETED)
                        .findFirst()
                        .orElse(stageServiceItems.get(0));
            }
        }

        String lockReason = lockReason(visit);

        // 1. Ưu tiên tìm quy trình đang gắn cùng giai đoạn + dịch vụ (tránh nhân bản
        // khi 1 dịch vụ có nhiều bước trong giai đoạn)
        if (planStage != null && resolvedServiceId != null) {
            Optional<WorkflowExecution> existingByStage = executionRepo
                    .findFirstByPlanStage_StageIdAndVersion_Template_Service_ServiceId(planStage.getStageId(),
                            resolvedServiceId);
            if (existingByStage.isPresent()) {
                WorkflowExecution ex = existingByStage.get();
                if (lockReason == null) {
                    ex = reconcileOrphanedCompletedExecution(ex, visit);
                    ex = reconcileExecutionWithPlanItems(ex, visit);
                }
                return mapExecutionToDTO(ex, visit);
            }
            for (TreatmentPlanItem sib : stageServiceItems) {
                Optional<WorkflowExecution> sibExec = executionRepo.findFirstByPlanItem_PlanItemId(sib.getPlanItemId());
                if (sibExec.isPresent()) {
                    WorkflowExecution ex = sibExec.get();
                    if (lockReason == null) {
                        if (ex.getPlanStage() == null) {
                            ex.setPlanStage(planStage);
                            ex = executionRepo.save(ex);
                        }
                        ex = reconcileOrphanedCompletedExecution(ex, visit);
                        ex = reconcileExecutionWithPlanItems(ex, visit);
                    }
                    return mapExecutionToDTO(ex, visit);
                }
            }
        }

        if (planItemId != null) {
            Optional<WorkflowExecution> existingByPlan = executionRepo.findFirstByPlanItem_PlanItemId(planItemId);
            if (existingByPlan.isPresent()) {
                WorkflowExecution ex = existingByPlan.get();
                if (lockReason == null) {
                    ex = reconcileOrphanedCompletedExecution(ex, visit);
                    ex = reconcileExecutionWithPlanItems(ex, visit);
                }
                return mapExecutionToDTO(ex, visit);
            }
        }

        if (planItem == null && planStage == null && resolvedServiceId != null) {
            // Quy trình đã khởi tạo trên lượt khám thì luôn trả về đúng phiên bản đã áp
            // dụng (không đổi theo mẫu mới)
            Optional<WorkflowExecution> existing = executionRepo
                    .findFirstByVisit_VisitIdAndVersion_Template_Service_ServiceId(visitId, resolvedServiceId);
            if (existing.isPresent()) {
                return mapExecutionToDTO(existing.get(), visit);
            }
            if (visit.getPatientProfile() != null && visit.getPatientProfile().getProfileId() != null) {
                final Long targetSvcId = resolvedServiceId;
                Optional<WorkflowExecution> openPatientExec = executionRepo
                        .findByPatientProfile_ProfileIdOrderByStartedAtDesc(visit.getPatientProfile().getProfileId())
                        .stream()
                        .filter(pe -> pe.getPlanItem() == null && pe.getPlanStage() == null)
                        .filter(pe -> pe.getStatus() == WorkflowExecutionStatus.IN_PROGRESS
                                || pe.getStatus() == WorkflowExecutionStatus.PAUSED)
                        .filter(pe -> pe.getVersion() != null && pe.getVersion().getTemplate() != null
                                && pe.getVersion().getTemplate().getService() != null
                                && Objects.equals(pe.getVersion().getTemplate().getService().getServiceId(),
                                        targetSvcId))
                        .findFirst();
                if (openPatientExec.isPresent()) {
                    return mapExecutionToDTO(openPatientExec.get(), visit);
                }
            }
        }

        if (lockReason != null) {
            throw new IllegalStateException(lockReason);
        }

        if (resolvedServiceId != null) {
            var capCheck = checkServiceCapability(visit, resolvedServiceId, userId);
            if (!capCheck.allowed()) {
                throw new IllegalStateException(capCheck.message());
            }
        }

        ClinicalWorkflowTemplate template = resolvedServiceId != null
                ? templateRepo.findFirstByService_ServiceIdAndStatus(resolvedServiceId, WorkflowTemplateStatus.ACTIVE)
                        .orElse(null)
                : null;
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
        execution.setPlanItem(planItem);
        execution.setPlanStage(planStage);
        execution.setCurrentStep(steps.get(0));
        execution.setStatus(WorkflowExecutionStatus.IN_PROGRESS);
        execution = executionRepo.save(execution);

        String prefilledTeeth = planItem != null ? planItem.getToothNumbers() : null;
        if ((prefilledTeeth == null || prefilledTeeth.isBlank()) && !stageServiceItems.isEmpty()) {
            prefilledTeeth = stageServiceItems.stream()
                    .map(TreatmentPlanItem::getToothNumbers)
                    .filter(t -> t != null && !t.isBlank())
                    .findFirst().orElse(null);
        }
        for (int i = 0; i < steps.size(); i++) {
            ClinicalWorkflowStep st = steps.get(i);
            WorkflowStepExecution se = new WorkflowStepExecution();
            se.setExecution(execution);
            se.setStep(st);
            se.setStepOrder(st.getSequenceOrder() != null ? st.getSequenceOrder() : (i + 1));
            se.setStatus(WorkflowStepExecutionStatus.PENDING);
            Map<String, Object> initMap = new LinkedHashMap<>();
            if (prefilledTeeth != null && !prefilledTeeth.isBlank()) {
                List<String> teethList = parseTeethTokens(prefilledTeeth);
                if (!teethList.isEmpty()) {
                    initMap.put("teeth", teethList);
                }
            }
            if (i == 0 && initialQuantity != null && initialQuantity > 1) {
                initMap.put("serviceQuantity", initialQuantity);
            }
            if (!initMap.isEmpty()) {
                initMap.put("prefilled", true);
                se.setResultData(writeJsonQuietly(initMap));
            }
            stepExecutionRepo.save(se);
        }

        execution = reconcileExecutionWithPlanItems(execution, visit);
        if (execution.getPlanItem() != null && execution.getStatus() != WorkflowExecutionStatus.COMPLETED) {
            syncLinkedPlanOnProgress(execution.getPlanItem());
        }

        Map<String, Object> created = new LinkedHashMap<>();
        created.put("visitId", visitId);
        created.put("template", template.getTemplateName());
        created.put("version", version.getVersionNumber());
        created.put("steps", steps.size());
        if (planItem != null) {
            created.put("planItemId", planItem.getPlanItemId());
        }
        if (planStage != null) {
            created.put("planStageId", planStage.getStageId());
        }
        writeAudit(userId, visit.getBranch(), "WORKFLOW_CREATE", "workflow_executions",
                execution.getExecutionId(), null, created);

        return mapExecutionToDTO(execution, visit);
    }

    /**
     * [Chức năng]: Tổng hợp toàn cảnh tab Quy trình điều trị cho một lượt khám (`WorkflowVisitOverviewDTO`).
     * [Mục đích]: Nạp các tiến trình gắn KHDT của ca khám hiện tại và các tiến trình làm dở từ buổi trước của cùng bệnh nhân, liệt kê các hạng mục KHDT chưa khởi tạo có mẫu quy trình hoạt động kèm kiểm tra năng lực bác sĩ và danh sách Combo khả dụng.
     */
    @Override
    @Transactional(readOnly = true)
    public WorkflowVisitOverviewDTO getVisitOverview(Long visitId) {
        Visit visit = visitRepo.findById(visitId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca khám với ID: " + visitId));
        String lockReason = lockReason(visit);

        List<WorkflowExecution> executions = executionRepo.findByVisit_VisitId(visitId).stream()
                .filter(e -> e.getPlanItem() != null || e.getPlanStage() != null)
                .collect(Collectors.toCollection(ArrayList::new));
        Set<Long> seenExecIds = executions.stream()
                .map(WorkflowExecution::getExecutionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Long bookedServiceId = null;
        if (doctorVisitAccess != null) {
            Map<String, Object> booked = doctorVisitAccess.resolveBookedServiceSummary(visit);
            if (booked != null && booked.get("serviceId") instanceof Number num) {
                bookedServiceId = num.longValue();
            }
        }

        // Nạp thêm các quy trình thuộc Kế hoạch điều trị đang làm dở từ buổi khám trước của cùng bệnh nhân
        Long profileId = visit.getPatientProfile() != null ? visit.getPatientProfile().getProfileId() : null;
        if (profileId != null) {
            List<WorkflowExecution> patientExecs = executionRepo
                    .findByPatientProfile_ProfileIdOrderByStartedAtDesc(profileId);
            if (patientExecs != null) {
                for (WorkflowExecution pe : patientExecs) {
                    if (pe.getExecutionId() != null && seenExecIds.contains(pe.getExecutionId())) {
                        continue;
                    }
                    boolean hasPlanLink = pe.getPlanItem() != null || pe.getPlanStage() != null;
                    boolean activeInCurrentVisit = hasStepActivityInVisit(pe, visitId);
                    if (!hasPlanLink) {
                        continue;
                    }
                    if (pe.getStatus() == WorkflowExecutionStatus.IN_PROGRESS
                            || pe.getStatus() == WorkflowExecutionStatus.PAUSED
                            || (pe.getStatus() == WorkflowExecutionStatus.COMPLETED && activeInCurrentVisit)) {
                        executions.add(pe);
                        if (pe.getExecutionId() != null)
                            seenExecIds.add(pe.getExecutionId());
                    }
                }
            }
        }

        executions.sort(Comparator.comparing(WorkflowExecution::getStartedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));

        Set<Long> startedStandaloneServiceIds = new HashSet<>();
        Set<Long> startedPlanItemIds = new HashSet<>();
        Set<String> startedStageServiceKeys = new HashSet<>();
        List<WorkflowExecutionDTO> executionDTOs = new ArrayList<>();
        for (WorkflowExecution e : executions) {
            if (isOrphanedCompletedPlanExecution(e)) {
                continue;
            }
            executionDTOs.add(mapExecutionToDTO(e, visit));
            ClinicalWorkflowTemplate t = e.getVersion().getTemplate();
            TreatmentPlanStage st = e.getPlanStage() != null ? e.getPlanStage()
                    : (e.getPlanItem() != null ? e.getPlanItem().getStage() : null);
            if (st != null && st.getStageId() != null && t.getService() != null
                    && t.getService().getServiceId() != null) {
                startedStageServiceKeys.add(st.getStageId() + ":" + t.getService().getServiceId());
            }
            if (e.getPlanItem() != null && e.getPlanItem().getPlanItemId() != null) {
                startedPlanItemIds.add(e.getPlanItem().getPlanItemId());
            } else if (st == null && t.getService() != null) {
                startedStandaloneServiceIds.add(t.getService().getServiceId());
            }
        }

        Set<Long> performedServiceIds = performedServiceRepo.findByVisit_VisitIdOrderByCompletedAtDesc(visitId).stream()
                .filter(ps -> ps.getService() != null)
                .map(ps -> ps.getService().getServiceId())
                .collect(Collectors.toSet());

        List<WorkflowVisitOverviewDTO.ServiceOption> available = new ArrayList<>();
        if (lockReason == null) {
            List<ClinicalWorkflowTemplate> allTemplates = templateRepo.findAllByOrderByCreatedAtDesc();
            Map<Long, ClinicalWorkflowTemplate> activeTplByServiceId = new LinkedHashMap<>();
            Map<Long, Integer> stepCountByTplId = new HashMap<>();

            for (ClinicalWorkflowTemplate t : allTemplates) {
                if (t.getStatus() != WorkflowTemplateStatus.ACTIVE || t.getService() == null) {
                    continue;
                }
                ClinicalWorkflowVersion v = resolveVersion(t);
                int stepCount = v != null ? activeSteps(v).size() : 0;
                if (stepCount <= 0) {
                    continue;
                }
                activeTplByServiceId.putIfAbsent(t.getService().getServiceId(), t);
                stepCountByTplId.put(t.getTemplateId(), stepCount);
            }

            // 1. Đưa các hạng mục/giai đoạn từ Kế hoạch điều trị đang mở lên đầu (gom theo
            // Stage + Service để không trùng 4 dòng)
            if (profileId != null && treatmentPlanRepo != null) {
                List<TreatmentPlan> plans = treatmentPlanRepo.findByProfileIdWithDetails(profileId);
                if (plans != null) {
                    for (TreatmentPlan plan : plans) {
                        if (plan.getStatus() == TreatmentPlanStatus.CANCELLED
                                || plan.getStatus() == TreatmentPlanStatus.COMPLETED) {
                            continue;
                        }
                        if (plan.getStages() == null)
                            continue;
                        for (TreatmentPlanStage stage : plan.getStages()) {
                            if (stage.getItems() == null)
                                continue;
                            Map<Long, List<TreatmentPlanItem>> openByService = new LinkedHashMap<>();
                            for (TreatmentPlanItem item : stage.getItems()) {
                                if (item.getPlanItemId() == null || item.getService() == null)
                                    continue;
                                if (item.getStatus() == TreatmentPlanItemStatus.COMPLETED
                                        || item.getStatus() == TreatmentPlanItemStatus.CANCELLED) {
                                    continue;
                                }
                                if (startedPlanItemIds.contains(item.getPlanItemId())
                                        || performedServiceRepo.existsByPlanItem_PlanItemIdAndStatusNot(
                                                item.getPlanItemId(), PerformedServiceStatus.CANCELLED)) {
                                    continue;
                                }
                                openByService.computeIfAbsent(item.getService().getServiceId(), k -> new ArrayList<>())
                                        .add(item);
                            }

                            for (Map.Entry<Long, List<TreatmentPlanItem>> entry : openByService.entrySet()) {
                                Long sId = entry.getKey();
                                if (stage.getStageId() != null
                                        && startedStageServiceKeys.contains(stage.getStageId() + ":" + sId)) {
                                    continue;
                                }
                                ClinicalWorkflowTemplate tpl = activeTplByServiceId.get(sId);
                                if (tpl == null)
                                    continue;
                                List<TreatmentPlanItem> openItems = entry.getValue();
                                TreatmentPlanItem rep = openItems.get(0);
                                String teeth = openItems.stream()
                                        .map(TreatmentPlanItem::getToothNumbers)
                                        .filter(tn -> tn != null && !tn.isBlank())
                                        .findFirst().orElse(null);
                                String displayStep = (openItems.size() > 1 && stage.getStageName() != null
                                        && !stage.getStageName().isBlank())
                                                ? stage.getStageName() + " (" + openItems.size() + " bước)"
                                                : rep.getStepName();
                                var capOpt = checkServiceCapability(visit, sId, null);
                                available.add(WorkflowVisitOverviewDTO.ServiceOption.builder()
                                        .serviceId(sId)
                                        .serviceCode(rep.getService().getServiceCode())
                                        .serviceName(rep.getService().getServiceName())
                                        .templateName(tpl.getTemplateName())
                                        .stepCount(stepCountByTplId.getOrDefault(tpl.getTemplateId(), 0))
                                        .estimatedDurationMinutes(resolveServiceDuration(rep.getService()))
                                        .allowSameVisit(!Boolean.FALSE.equals(rep.getService().getAllowSameVisit()))
                                        .isParaclinical(Boolean.TRUE.equals(rep.getService().getIsParaclinical()))
                                        .suggested(true)
                                        .planItemId(rep.getPlanItemId())
                                        .planStageId(stage.getStageId())
                                        .planStageOrder(stage.getStageOrder())
                                        .planStageName(stage.getStageName())
                                        .planCode(plan.getPlanCode())
                                        .planName(plan.getPlanName())
                                        .stepName(displayStep)
                                        .toothNumbers(teeth)
                                        .canPerform(capOpt.allowed())
                                        .capabilityReason(capOpt.message())
                                        .requiresHandover(!capOpt.allowed())
                                        .build());
                            }
                        }
                    }
                }
            }

            // Thống nhất 1 luồng: tab Quy trình điều trị chỉ nạp các dịch vụ/hạng mục từ Kế hoạch điều trị
            available.sort(Comparator
                    .comparing((WorkflowVisitOverviewDTO.ServiceOption o) -> o.getPlanItemId() == null
                            && o.getPlanStageId() == null)
                    .thenComparing(o -> !Boolean.TRUE.equals(o.getSuggested()))
                    .thenComparing(o -> o.getServiceName() == null ? "" : o.getServiceName()));
        }

        List<ServiceComboDTO> activeCombos = lockReason == null ? getAllCombos(true) : List.of();
        if (!activeCombos.isEmpty()) {
            for (ServiceComboDTO combo : activeCombos) {
                if (combo.getItems() == null) continue;
                for (ServiceComboDTO.ComboItemDTO ci : combo.getItems()) {
                    if (ci.getServiceId() == null) continue;
                    var capItem = checkServiceCapability(visit, ci.getServiceId(), null);
                    ci.setCanPerform(capItem.allowed());
                    ci.setCapabilityReason(capItem.message());
                }
            }
        }
        if (bookedServiceId != null && !activeCombos.isEmpty()) {
            final Long reqSrvId = bookedServiceId;
            activeCombos = activeCombos.stream()
                    .filter(c -> c.getItems() != null && c.getItems().stream()
                            .anyMatch(it -> it.getServiceId() != null && reqSrvId.equals(it.getServiceId())))
                    .toList();
        }

        return WorkflowVisitOverviewDTO.builder()
                .visitId(visitId)
                .locked(lockReason != null)
                .lockedReason(lockReason)
                .executions(executionDTOs)
                .availableServices(available)
                .availableCombos(activeCombos)
                .build();
    }

    /**
     * [Chức năng]: Phiên bản nạp chồng rút gọn thực hiện thao tác trên bước quy trình không truyền `resultData` và `activeVisitId`.
     * [Mục đích]: Ủy quyền cho hàm `performStepAction` 8 tham số để phục vụ các lời gọi ngắn gọn và tương thích ngược.
     */
    @Override
    @Transactional
    public WorkflowExecutionDTO performStepAction(Long executionId, Long stepId, String action, String note,
            boolean criteriaConfirmed, Long userId) {
        return performStepAction(executionId, stepId, action, note, criteriaConfirmed, null, null, userId);
    }

    /**
     * [Chức năng]: Xử lý máy trạng thái thao tác trên bước hiện tại của quy trình (`START`, `SAVE_DRAFT`, `PAUSE`, `RESUME`, `SKIP`, `COMPLETE`).
     * [Mục đích]: Khóa bi quan `executionRepo.findForUpdate`, kiểm tra khóa ca/EMR và năng lực bác sĩ, ép tuần tự đúng bước đầu tiên chưa xong, kiểm tra điều kiện hoàn tất/phê duyệt lâm sàng/Labo/Cận lâm sàng, gộp JSON `resultData` kèm snapshot buổi khám, đồng bộ KHDT & dịch vụ thực hiện và ghi audit log.
     */
    @Override
    @Transactional
    public WorkflowExecutionDTO performStepAction(Long executionId, Long stepId, String action, String note,
            boolean criteriaConfirmed, Map<String, Object> resultData,
            Long activeVisitId, Long userId) {
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
        Visit effectiveVisit = resolveEffectiveVisit(execution, activeVisitId);

        String lockReason = lockReason(effectiveVisit);
        if (lockReason != null) {
            throw new IllegalStateException(lockReason);
        }
        Long execServiceId = execution.getVersion() != null && execution.getVersion().getTemplate() != null
                && execution.getVersion().getTemplate().getService() != null
                        ? execution.getVersion().getTemplate().getService().getServiceId()
                        : null;
        if (execServiceId != null) {
            var capCheck = checkServiceCapability(effectiveVisit, execServiceId, userId);
            if (!capCheck.allowed()) {
                throw new IllegalStateException(capCheck.message());
            }
        }
        execution = reconcileExecutionWithPlanItems(execution, effectiveVisit);
        if (execution.getStatus() == WorkflowExecutionStatus.COMPLETED) {
            throw new IllegalStateException("Quy trình đã hoàn tất, không thể thao tác thêm.");
        }
        if (execution.getStatus() == WorkflowExecutionStatus.CANCELLED) {
            throw new IllegalStateException("Quy trình đã bị hủy, không thể thao tác.");
        }

        List<WorkflowStepExecution> all = new ArrayList<>(
                stepExecutionRepo.findByExecution_ExecutionIdOrderByStep_SequenceOrderAsc(executionId));
        all.sort(Comparator.comparingInt(this::effectiveStepOrder));

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
        String actorDisplay = staff != null && staff.getFullName() != null
                ? staff.getFullName()
                : (effectiveVisit.getPrimaryDoctor() != null ? effectiveVisit.getPrimaryDoctor().getFullName()
                        : "Bác sĩ");

        switch (act) {
            case START -> {
                if (from != WorkflowStepExecutionStatus.PENDING) {
                    throw new IllegalStateException("Chỉ có thể bắt đầu bước đang ở trạng thái chờ thực hiện.");
                }
                target.setStatus(WorkflowStepExecutionStatus.IN_PROGRESS);
                target.setStartedAt(now);
                if (staff != null)
                    target.setAssignedStaff(staff);
                if (resultData != null && !resultData.isEmpty()) {
                    target.setResultData(mergeStepResultData(target.getResultData(), resultData, null));
                }
            }
            case SAVE_DRAFT -> {
                if (from == WorkflowStepExecutionStatus.COMPLETED || from == WorkflowStepExecutionStatus.SKIPPED) {
                    throw new IllegalStateException("Bước này đã hoàn tất hoặc bỏ qua, không thể lưu nháp thêm.");
                }
                if (from == WorkflowStepExecutionStatus.PENDING) {
                    target.setStatus(WorkflowStepExecutionStatus.IN_PROGRESS);
                    target.setStartedAt(now);
                }
                if (staff != null)
                    target.setAssignedStaff(staff);
                Map<String, Object> sessionSnap = buildSessionSnapshot("SAVE_DRAFT", effectiveVisit, actorDisplay,
                        cleanNote, resultData, now);
                target.setResultData(mergeStepResultData(target.getResultData(), resultData, sessionSnap));
            }
            case RESUME -> {
                if (from != WorkflowStepExecutionStatus.PAUSED) {
                    throw new IllegalStateException("Chỉ có thể tiếp tục bước đang tạm dừng.");
                }
                target.setStatus(WorkflowStepExecutionStatus.IN_PROGRESS);
                if (staff != null)
                    target.setAssignedStaff(staff);
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
                if (resultData != null && !resultData.isEmpty()) {
                    Map<String, Object> sessionSnap = buildSessionSnapshot("PAUSE", effectiveVisit, actorDisplay,
                            cleanNote, resultData, now);
                    target.setResultData(mergeStepResultData(target.getResultData(), resultData, sessionSnap));
                }
            }
            case SKIP -> {
                if (from != WorkflowStepExecutionStatus.PENDING) {
                    throw new IllegalStateException("Chỉ có thể bỏ qua bước chưa bắt đầu.");
                }
                if (!Boolean.TRUE.equals(step.getIsOptional())) {
                    throw new IllegalStateException(
                            "Bước bắt buộc không thể bỏ qua. Chỉ bước tùy chọn mới được bỏ qua.");
                }
                target.setStatus(WorkflowStepExecutionStatus.SKIPPED);
                target.setCompletedAt(now);
                if (staff != null)
                    target.setAssignedStaff(staff);
            }
            case COMPLETE -> {
                if (from != WorkflowStepExecutionStatus.IN_PROGRESS) {
                    throw new IllegalStateException("Phải bắt đầu bước trước khi hoàn tất.");
                }
                String criteria = step.getCompletionCriteria();
                if (criteria != null && !criteria.isBlank() && !criteriaConfirmed) {
                    throw new IllegalStateException(
                            "Vui lòng xác nhận đã đạt điều kiện hoàn tất của bước: " + criteria);
                }
                if (Boolean.TRUE.equals(step.getRequiresClinicalApproval())) {
                    if (!canApproveClinically(userId)) {
                        throw new IllegalStateException("Bước này cần bác sĩ phê duyệt chuyên môn trước khi hoàn tất.");
                    }
                    target.setApprovedBy(userId);
                    target.setApprovedAt(now);
                }
                checkLaboReworkReady(step, target, resultData);
                if (step.getStepType() == StepType.PARACLINICAL) {
                    checkParaclinicalReady(effectiveVisit, execution.getVisit(), step, target, resultData);
                }
                target.setStatus(WorkflowStepExecutionStatus.COMPLETED);
                target.setCompletedAt(now);
                if (target.getStartedAt() == null)
                    target.setStartedAt(now);
                if (staff != null)
                    target.setAssignedStaff(staff);
                if (resultData != null && !resultData.isEmpty()) {
                    Map<String, Object> sessionSnap = buildSessionSnapshot("COMPLETE", effectiveVisit, actorDisplay,
                            cleanNote, resultData, now);
                    target.setResultData(mergeStepResultData(target.getResultData(), resultData, sessionSnap));
                } else if (cleanNote != null) {
                    Map<String, Object> sessionSnap = buildSessionSnapshot("COMPLETE", effectiveVisit, actorDisplay,
                            cleanNote, null, now);
                    target.setResultData(mergeStepResultData(target.getResultData(), null, sessionSnap));
                }
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
                    ? WorkflowExecutionStatus.PAUSED
                    : WorkflowExecutionStatus.IN_PROGRESS);
            execution.setCompletedAt(null);
        }
        executionRepo.save(execution);

        syncWorkflowStepProgress(execution, target, act, all, effectiveVisit, userId, finished, now);

        Map<String, Object> oldVal = new LinkedHashMap<>();
        oldVal.put("status", from.name());
        Map<String, Object> newVal = new LinkedHashMap<>();
        newVal.put("status", target.getStatus().name());
        newVal.put("stepName", step.getStepName());
        newVal.put("note", cleanNote);
        writeAudit(userId, effectiveVisit.getBranch(), "WORKFLOW_STEP_" + act.name(), "workflow_step_executions",
                target.getStepExecutionId(), oldVal, newVal);
        if (finished) {
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("visitId", effectiveVisit.getVisitId());
            done.put("status", WorkflowExecutionStatus.COMPLETED.name());
            writeAudit(userId, effectiveVisit.getBranch(), "WORKFLOW_COMPLETE", "workflow_executions",
                    execution.getExecutionId(), null, done);
        }

        return mapExecutionToDTO(execution, effectiveVisit);
    }

    /**
     * [Chức năng]: Chèn thêm một bước phát sinh (ad-hoc) vào tiến trình quy trình đang điều trị mà không ảnh hưởng mẫu chuẩn.
     * [Mục đích]: Khóa bản ghi `WorkflowExecution`, kiểm tra khóa ca và năng lực bác sĩ, tạo `ClinicalWorkflowStep` gắn `adhocExecutionId`, chèn sau bước chỉ định (không chèn trước bước đang chạy/tạm dừng), đánh lại `stepOrder` và ghi audit `WORKFLOW_ADHOC_ADD`.
     */
    @Override
    @Transactional
    public WorkflowExecutionDTO addAdhocStep(Long executionId, String stepName, String frameKey, String stepType,
            String insertPosition, Long afterStepId, String completionCriteria,
            String stepDescription, Long activeVisitId, Long userId) {
        if (executionId == null || stepName == null || stepName.isBlank()) {
            throw new IllegalArgumentException("Tên bước phát sinh không được để trống.");
        }
        WorkflowExecution execution = executionRepo.findForUpdate(executionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy execution với ID: " + executionId));
        Visit effectiveVisit = resolveEffectiveVisit(execution, activeVisitId);
        String lockReason = lockReason(effectiveVisit);
        if (lockReason != null) {
            throw new IllegalStateException(lockReason);
        }
        Long execServiceId = execution.getVersion() != null && execution.getVersion().getTemplate() != null
                && execution.getVersion().getTemplate().getService() != null
                        ? execution.getVersion().getTemplate().getService().getServiceId()
                        : null;
        if (execServiceId != null) {
            var capCheck = checkServiceCapability(effectiveVisit, execServiceId, userId);
            if (!capCheck.allowed()) {
                throw new IllegalStateException(capCheck.message());
            }
        }
        if (execution.getStatus() == WorkflowExecutionStatus.COMPLETED
                || execution.getStatus() == WorkflowExecutionStatus.CANCELLED) {
            throw new IllegalStateException("Quy trình đã kết thúc, không thể chèn thêm bước.");
        }

        List<WorkflowStepExecution> all = new ArrayList<>(
                stepExecutionRepo.findByExecution_ExecutionIdOrderByStep_SequenceOrderAsc(executionId));
        all.sort(Comparator.comparingInt(this::effectiveStepOrder));

        StepType resolvedType = StepType.TREATMENT;
        if (stepType != null && !stepType.isBlank()) {
            try {
                resolvedType = StepType.valueOf(stepType.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
            }
        } else if ("XRAY".equalsIgnoreCase(frameKey)) {
            resolvedType = StepType.PARACLINICAL;
        } else if ("CLINICAL_EXAM".equalsIgnoreCase(frameKey)) {
            resolvedType = StepType.CLINICAL;
        }
        String resolvedFrame = inferFrameKey(frameKey, resolvedType, stepName,
                execution.getVersion().getTemplate().getTemplateCode());

        ClinicalWorkflowStep adhocStep = new ClinicalWorkflowStep();
        adhocStep.setVersion(execution.getVersion());
        adhocStep.setAdhocExecutionId(executionId);
        adhocStep.setStepCode("ADHOC-" + executionId + "-" + (System.currentTimeMillis() % 100000));
        adhocStep.setStepName(stepName.trim());
        adhocStep.setStepDescription(
                stepDescription != null && !stepDescription.isBlank() ? stepDescription.trim() : null);
        adhocStep.setCompletionCriteria(
                completionCriteria != null && !completionCriteria.isBlank() ? completionCriteria.trim() : null);
        adhocStep.setStepType(resolvedType);
        adhocStep.setFrameKey(resolvedFrame);
        adhocStep.setSequenceOrder(all.size() + 1);
        adhocStep.setIsOptional(false);
        adhocStep.setRequiresClinicalApproval(false);
        adhocStep.setEstimatedDurationMinutes(15);
        adhocStep.setStepPrice(java.math.BigDecimal.ZERO);
        adhocStep.setStatus(StepStatus.ACTIVE);
        adhocStep = stepRepo.save(adhocStep);

        WorkflowStepExecution newSe = new WorkflowStepExecution();
        newSe.setExecution(execution);
        newSe.setStep(adhocStep);
        newSe.setStatus(WorkflowStepExecutionStatus.PENDING);

        int insertIdx = all.size();
        if (!"END".equalsIgnoreCase(insertPosition) && afterStepId != null) {
            for (int i = 0; i < all.size(); i++) {
                if (afterStepId.equals(all.get(i).getStep().getStepId())) {
                    insertIdx = i + 1;
                    break;
                }
            }
        } else if (!"END".equalsIgnoreCase(insertPosition)) {
            WorkflowStepExecution cur = firstOpenStep(all);
            if (cur != null) {
                int idx = all.indexOf(cur);
                if (idx >= 0)
                    insertIdx = idx + 1;
            }
        }
        // Không chèn phía trước bước đang thực hiện/tạm dừng để tránh kẹt thứ tự tuần
        // tự
        for (int i = 0; i < all.size(); i++) {
            WorkflowStepExecutionStatus st = all.get(i).getStatus();
            if ((st == WorkflowStepExecutionStatus.IN_PROGRESS || st == WorkflowStepExecutionStatus.PAUSED)
                    && insertIdx <= i) {
                insertIdx = i + 1;
            }
        }
        insertIdx = Math.max(0, Math.min(insertIdx, all.size()));
        all.add(insertIdx, newSe);

        for (int i = 0; i < all.size(); i++) {
            WorkflowStepExecution item = all.get(i);
            item.setStepOrder(i + 1);
            stepExecutionRepo.save(item);
        }

        WorkflowStepExecution next = firstOpenStep(all);
        execution.setCurrentStep(next != null ? next.getStep() : null);
        executionRepo.save(execution);

        Map<String, Object> auditVal = new LinkedHashMap<>();
        auditVal.put("stepName", adhocStep.getStepName());
        auditVal.put("frameKey", resolvedFrame);
        auditVal.put("position", insertIdx + 1);
        writeAudit(userId, effectiveVisit.getBranch(), "WORKFLOW_ADHOC_ADD", "workflow_executions",
                executionId, null, auditVal);

        return mapExecutionToDTO(execution, effectiveVisit);
    }

    /**
     * [Chức năng]: Xóa một bước phát sinh (ad-hoc) khỏi tiến trình quy trình.
     * [Mục đích]: Khóa tiến trình, chặn xóa bước chuẩn của mẫu hoặc bước ad-hoc đã có tiến độ/dữ liệu lưu, xóa bản ghi thực thi cùng bước ad-hoc và đánh lại thứ tự các bước còn lại.
     */
    @Override
    @Transactional
    public WorkflowExecutionDTO removeAdhocStep(Long executionId, Long stepId, Long activeVisitId, Long userId) {
        WorkflowExecution execution = executionRepo.findForUpdate(executionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy execution với ID: " + executionId));
        Visit effectiveVisit = resolveEffectiveVisit(execution, activeVisitId);
        String lockReason = lockReason(effectiveVisit);
        if (lockReason != null) {
            throw new IllegalStateException(lockReason);
        }
        Long execServiceId = execution.getVersion() != null && execution.getVersion().getTemplate() != null
                && execution.getVersion().getTemplate().getService() != null
                        ? execution.getVersion().getTemplate().getService().getServiceId()
                        : null;
        if (execServiceId != null) {
            var capCheck = checkServiceCapability(effectiveVisit, execServiceId, userId);
            if (!capCheck.allowed()) {
                throw new IllegalStateException(capCheck.message());
            }
        }

        List<WorkflowStepExecution> all = new ArrayList<>(
                stepExecutionRepo.findByExecution_ExecutionIdOrderByStep_SequenceOrderAsc(executionId));
        all.sort(Comparator.comparingInt(this::effectiveStepOrder));

        WorkflowStepExecution target = all.stream()
                .filter(se -> stepId.equals(se.getStep().getStepId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bước cần xóa."));
        ClinicalWorkflowStep step = target.getStep();
        if (step.getAdhocExecutionId() == null || !executionId.equals(step.getAdhocExecutionId())) {
            throw new IllegalStateException("Không thể xóa bước chuẩn thuộc quy trình mẫu.");
        }
        if (target.getStatus() != WorkflowStepExecutionStatus.PENDING
                || hasStepSavedContent(target)) {
            throw new IllegalStateException("Bước phát sinh đã bắt đầu thực hiện hoặc có dữ liệu lưu, không thể xóa.");
        }

        all.remove(target);
        stepExecutionRepo.delete(target);
        stepExecutionRepo.flush();
        stepRepo.delete(step);

        for (int i = 0; i < all.size(); i++) {
            all.get(i).setStepOrder(i + 1);
            stepExecutionRepo.save(all.get(i));
        }

        WorkflowStepExecution next = firstOpenStep(all);
        execution.setCurrentStep(next != null ? next.getStep() : null);
        executionRepo.save(execution);

        return mapExecutionToDTO(execution, effectiveVisit);
    }

    /**
     * [Chức năng]: Hủy xóa một tiến trình quy trình chưa bắt đầu thực hiện bất kỳ bước nào.
     * [Mục đích]: Khóa tiến trình, kiểm tra `canDeleteExecution`, xóa toàn bộ `WorkflowStepExecution`/bước ad-hoc/execution, trả các `TreatmentPlanItem` liên kết từ `IN_PROGRESS` về `PLANNED` (nếu chưa có dịch vụ thực hiện) và ghi audit `WORKFLOW_DELETE`.
     */
    @Override
    @Transactional
    public void deleteExecution(Long executionId, Long activeVisitId, Long userId) {
        WorkflowExecution execution = executionRepo.findForUpdate(executionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy quy trình với ID: " + executionId));
        Visit effectiveVisit = resolveEffectiveVisit(execution, activeVisitId);
        String lockReason = lockReason(effectiveVisit);
        if (lockReason != null) {
            throw new IllegalStateException(lockReason);
        }
        Long execServiceId = execution.getVersion() != null && execution.getVersion().getTemplate() != null
                && execution.getVersion().getTemplate().getService() != null
                        ? execution.getVersion().getTemplate().getService().getServiceId()
                        : null;
        if (execServiceId != null) {
            var capCheck = checkServiceCapability(effectiveVisit, execServiceId, userId);
            if (!capCheck.allowed()) {
                throw new IllegalStateException(capCheck.message());
            }
        }

        List<WorkflowStepExecution> all = stepExecutionRepo
                .findByExecution_ExecutionIdOrderByStep_SequenceOrderAsc(executionId);
        if (!canDeleteExecution(execution, all)) {
            throw new IllegalStateException("Quy trình đã có bước thực hiện hoặc dữ liệu lưu, không thể hủy bỏ.");
        }

        TreatmentPlanItem planItem = execution.getPlanItem();
        TreatmentPlanStage planStage = execution.getPlanStage();
        com.cvdcms.entity.Service tplService = execution.getVersion() != null
                && execution.getVersion().getTemplate() != null
                        ? execution.getVersion().getTemplate().getService()
                        : null;
        List<ClinicalWorkflowStep> adhocSteps = all.stream()
                .map(WorkflowStepExecution::getStep)
                .filter(s -> s != null && executionId.equals(s.getAdhocExecutionId()))
                .toList();

        stepExecutionRepo.deleteAll(all);
        stepExecutionRepo.flush();
        if (!adhocSteps.isEmpty()) {
            stepRepo.deleteAll(adhocSteps);
        }
        executionRepo.delete(execution);

        if (planStage != null && treatmentPlanItemRepo != null) {
            Long sId = tplService != null ? tplService.getServiceId() : null;
            TreatmentPlanItem lastReset = null;
            for (TreatmentPlanItem it : treatmentPlanItemRepo.findByStage_StageId(planStage.getStageId())) {
                if (sId != null && (it.getService() == null || !sId.equals(it.getService().getServiceId())))
                    continue;
                if (it.getStatus() == TreatmentPlanItemStatus.IN_PROGRESS
                        && !performedServiceRepo.existsByPlanItem_PlanItemIdAndStatusNot(
                                it.getPlanItemId(), PerformedServiceStatus.CANCELLED)) {
                    it.setStatus(TreatmentPlanItemStatus.PLANNED);
                    treatmentPlanItemRepo.save(it);
                    lastReset = it;
                }
            }
            if (lastReset != null)
                recalculateStageAndPlanStatus(lastReset);
        } else if (planItem != null && treatmentPlanItemRepo != null
                && planItem.getStatus() == TreatmentPlanItemStatus.IN_PROGRESS
                && !performedServiceRepo.existsByPlanItem_PlanItemIdAndStatusNot(
                        planItem.getPlanItemId(), PerformedServiceStatus.CANCELLED)) {
            planItem.setStatus(TreatmentPlanItemStatus.PLANNED);
            treatmentPlanItemRepo.save(planItem);
            recalculateStageAndPlanStatus(planItem);
        }

        Map<String, Object> delVal = new LinkedHashMap<>();
        delVal.put("executionId", executionId);
        delVal.put("visitId", effectiveVisit.getVisitId());
        writeAudit(userId, effectiveVisit.getBranch(), "WORKFLOW_DELETE", "workflow_executions",
                executionId, null, delVal);
    }

    /**
     * [Chức năng]: Cập nhật trạng thái bước quy trình theo chuẩn API cũ (tương thích ngược).
     * [Mục đích]: Ánh xạ chuỗi trạng thái (`IN_PROGRESS`/`COMPLETED`/`PAUSED`/`SKIPPED`) sang hành động FT43 tương ứng và ủy quyền cho `performStepAction`.
     */
    @Override
    @Transactional
    public WorkflowStepDTO updateStepExecution(Long executionId, Long stepId, String status, String note,
            Long staffId) {
        String action = switch (status == null ? "" : status.trim().toUpperCase()) {
            case "IN_PROGRESS" -> "START";
            case "COMPLETED" -> "COMPLETE";
            case "PAUSED" -> "PAUSE";
            case "SKIPPED" -> "SKIP";
            default -> throw new IllegalArgumentException("Trạng thái bước không hợp lệ: " + status);
        };
        WorkflowExecutionDTO dto = performStepAction(executionId, stepId, action, note, true, null, null, staffId);
        return dto.getSteps().stream().filter(s -> stepId.equals(s.getStepId())).findFirst().orElse(null);
    }

    /**
     * [Chức năng]: Lấy chi tiết một tiến trình quy trình điều trị theo `executionId`.
     * [Mục đích]: Tải bản ghi `WorkflowExecution` và ánh xạ đầy đủ các bước, tiến độ, quyền thao tác sang `WorkflowExecutionDTO`.
     */
    @Override
    @Transactional(readOnly = true)
    public WorkflowExecutionDTO getExecutionDetail(Long executionId) {
        WorkflowExecution execution = executionRepo.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy execution với ID: " + executionId));
        return mapExecutionToDTO(execution);
    }

    /**
     * [Chức năng]: Lấy bảng theo dõi tiến độ các quy trình điều trị trong ngày theo tập chi nhánh.
     * [Mục đích]: Truy vấn `executionRepo.findBoard` theo khoảng thời gian trong ngày `day` (mặc định hôm nay) và ánh xạ danh sách tiến trình sang DTO.
     */
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

    /**
     * [Chức năng]: Lấy lịch sử thao tác chi tiết của một tiến trình quy trình (SCR-43.4).
     * [Mục đích]: Gộp nhật ký `SystemAuditLog` của bảng `workflow_executions` và toàn bộ `workflow_step_executions` thuộc tiến trình, sắp xếp mới nhất trước và chuyển đổi sang `WorkflowHistoryEntryDTO`.
     */
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
                            ? l.getUser().getFullName()
                            : l.getUser().getUsername())
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

    /**
     * [Hàm hỗ trợ]: Lấy phiên bản `PUBLISHED` hiện hành của mẫu quy trình (dự phòng lấy phiên bản có số lớn nhất).
     */
    private ClinicalWorkflowVersion resolveVersion(ClinicalWorkflowTemplate template) {
        return versionRepo
                .findFirstByTemplate_TemplateIdAndStatus(template.getTemplateId(), WorkflowVersionStatus.PUBLISHED)
                .orElseGet(() -> versionRepo
                        .findFirstByTemplate_TemplateIdOrderByVersionNumberDesc(template.getTemplateId())
                        .orElse(null));
    }

    /**
     * [Hàm hỗ trợ]: Lấy danh sách các bước chuẩn (không gồm bước ad-hoc) đang ở trạng thái `ACTIVE` của một phiên bản quy trình theo đúng thứ tự.
     */
    private List<ClinicalWorkflowStep> activeSteps(ClinicalWorkflowVersion version) {
        return stepRepo.findByVersion_VersionIdOrderBySequenceOrderAsc(version.getVersionId()).stream()
                .filter(s -> s.getAdhocExecutionId() == null)
                .filter(s -> s.getStatus() == null || s.getStatus() == StepStatus.ACTIVE)
                .collect(Collectors.toList());
    }

    /**
     * [Hàm hỗ trợ]: Tìm bước đầu tiên chưa ở trạng thái `COMPLETED` hoặc `SKIPPED` trong danh sách bước thực thi đã sắp thứ tự.
     */
    private WorkflowStepExecution firstOpenStep(List<WorkflowStepExecution> ordered) {
        for (WorkflowStepExecution se : ordered) {
            if (se.getStatus() != WorkflowStepExecutionStatus.COMPLETED
                    && se.getStatus() != WorkflowStepExecutionStatus.SKIPPED) {
                return se;
            }
        }
        return null;
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra ca khám có bị khóa thao tác quy trình hay không (do trạng thái `WAITING_PAYMENT`/`COMPLETED`/`CANCELLED` hoặc EMR đã khóa/ký số theo BR-EMR-02), trả về lý do khóa hoặc `null`.
     */
    private String lockReason(Visit visit) {
        if (visit != null && visit.getStatus() != null && LOCKED_VISIT_STATUSES.contains(visit.getStatus())) {
            return switch (visit.getStatus()) {
                case CANCELLED -> "Lượt khám đã bị hủy, không thể thao tác quy trình.";
                case COMPLETED -> "Lượt khám đã hoàn tất, quy trình được khóa.";
                default -> "Lượt khám đang chờ thanh toán, quy trình được khóa. Hãy mở lại ca khám nếu cần chỉnh sửa.";
            };
        }
        if (visit != null && emrRecordRepository != null && visit.getVisitId() != null) {
            Optional<com.cvdcms.entity.EmrRecord> emrOpt = emrRecordRepository.findByVisit_VisitId(visit.getVisitId());
            if (emrOpt.isPresent() && com.cvdcms.util.EmrStatePolicy.isEmrLockedStatus(emrOpt.get().getStatus())) {
                return "Hồ sơ bệnh án điện tử (EMR) đã khóa hoặc ký số xác thực, quy trình được khóa.";
            }
        }
        return null;
    }

    /**
     * [Hàm hỗ trợ]: Tra cứu hồ sơ nhân sự (`StaffProfile`) tương ứng với `userId`.
     */
    private StaffProfile staffOf(Long userId) {
        return userId == null ? null : staffProfileRepo.findByUserUserId(userId).orElse(null);
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra người dùng có quyền phê duyệt chuyên môn bước quy trình (Admin hoặc có quyền `CLINICAL_DAILY_VISITS`).
     */
    private boolean canApproveClinically(Long userId) {
        return userId != null && (userRepo.hasActiveAdminRole(userId)
                || userRepo.hasActivePermission(userId, "CLINICAL_DAILY_VISITS"));
    }

    /**
     * [Hàm hỗ trợ]: Chặn hoàn tất bước Labo nếu đang đánh dấu yêu cầu chỉnh sửa/làm lại (`requestLaboRework`) hoặc phiếu `LaboOrder` đang ở trạng thái `RETURNED`.
     */
    private void checkLaboReworkReady(ClinicalWorkflowStep step, WorkflowStepExecution target,
            Map<String, Object> incoming) {
        Map<String, Object> merged = parseJson(target != null ? target.getResultData() : null);
        if (incoming != null) {
            merged.putAll(incoming);
        }
        if (Boolean.TRUE.equals(merged.get("requestLaboRework"))) {
            throw new IllegalStateException("Bước Labo đang đánh dấu yêu cầu chỉnh sửa / làm lại. "
                    + "Vui lòng chọn 'Lưu nháp' hoặc 'Tạm dừng' chờ Labo hoàn thiện trước khi Hoàn tất.");
        }
        Object rawLaboId = merged.get("laboId");
        Long laboId = parseLongObj(rawLaboId);
        if (laboId != null && laboOrderRepo != null) {
            LaboOrder order = laboOrderRepo.findById(laboId).orElse(null);
            if (order != null && order.getStatus() == LaboOrderStatus.RETURNED) {
                throw new IllegalStateException("Phiếu Labo " + order.getLaboCode()
                        + " đang ở trạng thái Trả về để sửa lại (RETURNED). Vui lòng tạm dừng chờ Labo giao lại trước khi Hoàn tất bước.");
            }
        }
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra bước cận lâm sàng (`PARACLINICAL`) đã có đủ kết quả chỉ định gắn trực tiếp trên bước, trên lượt khám hoặc trên hồ sơ bệnh nhân trước khi cho phép `COMPLETE`.
     */
    private void checkParaclinicalReady(Visit effectiveVisit, Visit originVisit, ClinicalWorkflowStep step,
            WorkflowStepExecution target, Map<String, Object> incoming) {
        Long visitId = effectiveVisit != null ? effectiveVisit.getVisitId() : null;
        Long originVisitId = originVisit != null ? originVisit.getVisitId() : null;
        Long profileId = effectiveVisit != null && effectiveVisit.getPatientProfile() != null
                ? effectiveVisit.getPatientProfile().getProfileId()
                : (originVisit != null && originVisit.getPatientProfile() != null
                        ? originVisit.getPatientProfile().getProfileId()
                        : null);

        Map<String, Object> merged = parseJson(target != null ? target.getResultData() : null);
        if (incoming != null) {
            merged.putAll(incoming);
        }
        Long boundOrderId = parseLongObj(merged.get("xrayOrderId"));
        Long boundOrderItemId = parseLongObj(merged.get("paraclinicalOrderItemId"));

        if (boundOrderId != null || boundOrderItemId != null) {
            List<ParaclinicalOrderItem> candidateItems = boundOrderId != null
                    ? paraclinicalItemRepo.findByOrder_OrderId(boundOrderId)
                    : paraclinicalItemRepo.findById(boundOrderItemId).map(List::of).orElse(List.of());
            List<ParaclinicalOrderItem> activeBound = candidateItems.stream()
                    .filter(i -> i.getStatus() != ParaclinicalOrderItemStatus.CANCELLED)
                    .toList();
            if (!activeBound.isEmpty()) {
                long pending = activeBound.stream()
                        .filter(i -> i.getStatus() != ParaclinicalOrderItemStatus.COMPLETED)
                        .count();
                if (pending > 0) {
                    throw new IllegalStateException(
                            "Chỉ định cận lâm sàng gắn với bước này chưa có kết quả (" + pending + " mục chờ).");
                }
                return;
            }
        }

        List<ParaclinicalOrderItem> items = visitId != null
                ? paraclinicalItemRepo.findByOrder_Visit_VisitIdOrderByOrder_OrderIdDescOrderItemIdDesc(visitId)
                        .stream()
                        .filter(i -> i.getStatus() != ParaclinicalOrderItemStatus.CANCELLED)
                        .collect(Collectors.toList())
                : List.of();
        if (items.isEmpty() && originVisitId != null && !Objects.equals(visitId, originVisitId)) {
            items = paraclinicalItemRepo
                    .findByOrder_Visit_VisitIdOrderByOrder_OrderIdDescOrderItemIdDesc(originVisitId).stream()
                    .filter(i -> i.getStatus() != ParaclinicalOrderItemStatus.CANCELLED)
                    .collect(Collectors.toList());
        }
        if (items.isEmpty() && profileId != null) {
            items = paraclinicalItemRepo
                    .findByOrder_PatientProfile_ProfileIdOrderByOrder_OrderIdDescOrderItemIdDesc(profileId).stream()
                    .filter(i -> i.getStatus() != ParaclinicalOrderItemStatus.CANCELLED)
                    .collect(Collectors.toList());
        }
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

    /**
     * [Hàm hỗ trợ]: Chuyển đổi an toàn một giá trị động (`Number` hoặc `String`) sang `Long`, trả về `null` nếu không hợp lệ.
     */
    private Long parseLongObj(Object val) {
        if (val == null)
            return null;
        if (val instanceof Number num)
            return num.longValue();
        try {
            String s = String.valueOf(val).trim();
            return s.isEmpty() ? null : Long.parseLong(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra tiến trình quy trình đã có ít nhất một bước chuyển khỏi `PENDING` hoặc có dữ liệu đã lưu hay chưa.
     */
    private boolean hasStepProgress(WorkflowExecution ex) {
        if (ex == null || ex.getExecutionId() == null) {
            return false;
        }
        List<WorkflowStepExecution> stepExecs = stepExecutionRepo
                .findByExecution_ExecutionIdOrderByStep_SequenceOrderAsc(ex.getExecutionId());
        for (WorkflowStepExecution se : stepExecs) {
            if (se.getStatus() != WorkflowStepExecutionStatus.PENDING || hasStepSavedContent(se)) {
                return true;
            }
        }
        return false;
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra tiến trình quy trình có ghi nhận snapshot thao tác (mảng `sessions` trong `resultData`) diễn ra trong lượt khám `visitId` hay không.
     */
    private boolean hasStepActivityInVisit(WorkflowExecution ex, Long visitId) {
        if (ex == null || visitId == null)
            return false;
        String targetVisit = String.valueOf(visitId);
        List<WorkflowStepExecution> stepExecs = stepExecutionRepo
                .findByExecution_ExecutionIdOrderByStep_SequenceOrderAsc(ex.getExecutionId());
        for (WorkflowStepExecution se : stepExecs) {
            Map<String, Object> data = parseJson(se.getResultData());
            Object sessionsObj = data.get("sessions");
            if (sessionsObj instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> mp) {
                        Object v = mp.get("visitId");
                        if (v != null && targetVisit.equals(String.valueOf(v))) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * [Hàm hỗ trợ]: Ghi nhật ký audit quy trình qua `SystemAuditLogService` (UC-25) và nuốt ngoại lệ ghi log để không làm gián đoạn luồng nghiệp vụ.
     */
    private void writeAudit(Long userId, Branch branch, String action, String table, Long entityId,
            Object oldVal, Object newVal) {
        try {
            User user = userId != null ? userRepo.findById(userId).orElse(null) : null;
            auditService.logAction(user, branch, action, table, entityId, null, oldVal, newVal);
        } catch (Exception ex) {
            log.warn("Không thể ghi nhật ký quy trình {}: {}", action, ex.getMessage());
        }
    }

    /**
     * [Hàm hỗ trợ]: Giải mã chuỗi JSON `resultData` thành `LinkedHashMap<String, Object>`, trả về map rỗng nếu chuỗi trống hoặc lỗi cú pháp.
     */
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(JSON.readValue(json, new TypeReference<Map<String, Object>>() {
            }));
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * [Hàm hỗ trợ]: Tuần tự hóa đối tượng sang chuỗi JSON an toàn, ghi log cảnh báo và trả về `null` nếu thất bại.
     */
    private String writeJsonQuietly(Object obj) {
        if (obj == null)
            return null;
        try {
            return JSON.writeValueAsString(obj);
        } catch (Exception ex) {
            log.warn("Lỗi serialize JSON quy trình: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * [Hàm hỗ trợ]: Suy luận mã khung thao tác hợp lệ trong 5 khung chuẩn (`CLINICAL_EXAM`, `XRAY`, `ENDODONTICS`, `PROCEDURE`, `PROSTHETICS_LABO`) từ khóa yêu cầu, loại bước, tên bước và mã quy trình.
     */
    private String inferFrameKey(String requestedFrameKey, StepType stepType, String stepName, String templateCode) {
        if (requestedFrameKey != null && !requestedFrameKey.isBlank()) {
            String upper = requestedFrameKey.trim().toUpperCase(Locale.ROOT);
            if (VALID_FRAME_KEYS.contains(upper)) {
                return upper;
            }
        }
        String s = stepName != null ? stepName.toLowerCase(Locale.ROOT) : "";
        String t = templateCode != null ? templateCode.toLowerCase(Locale.ROOT) : "";
        if (stepType == StepType.PARACLINICAL || s.contains("x-quang") || s.contains("xquang") || s.contains("phim")) {
            return "XRAY";
        }
        if (t.contains("tuy") || s.contains("nội nha") || s.contains("ống tủy") || s.contains("mở tủy")) {
            return "ENDODONTICS";
        }
        if (s.contains("labo") || s.contains("mão") || s.contains("thử sườn") || s.contains("phục hình")
                || s.contains("lấy dấu")) {
            return "PROSTHETICS_LABO";
        }
        if (stepType == StepType.CLINICAL && (s.contains("thăm khám") || s.contains("khám"))) {
            return "CLINICAL_EXAM";
        }
        return "PROCEDURE";
    }

    /**
     * [Hàm hỗ trợ]: Chuẩn hóa (sinh ID mặc định, đặt kiểu `text`, cắt khoảng trắng) và tuần tự hóa danh sách trường tùy biến của bước sang JSON.
     */
    private String serializeCustomFields(List<WorkflowStepDTO.CustomFieldDTO> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        List<WorkflowStepDTO.CustomFieldDTO> clean = list.stream()
                .filter(f -> f != null && f.getLabel() != null && !f.getLabel().isBlank())
                .peek(f -> {
                    if (f.getId() == null || f.getId().isBlank()) {
                        f.setId("cf-" + UUID.randomUUID().toString().substring(0, 8));
                    }
                    if (f.getType() == null || f.getType().isBlank()) {
                        f.setType("text");
                    }
                    f.setLabel(f.getLabel().trim());
                })
                .collect(Collectors.toList());
        if (clean.isEmpty())
            return null;
        return writeJsonQuietly(clean);
    }

    /**
     * [Hàm hỗ trợ]: Giải mã chuỗi JSON cấu hình trường tùy biến thành danh sách `WorkflowStepDTO.CustomFieldDTO`.
     */
    private List<WorkflowStepDTO.CustomFieldDTO> parseCustomFields(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<WorkflowStepDTO.CustomFieldDTO>>() {
            });
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }

    /**
     * [Hàm hỗ trợ]: Trả về số thứ tự hiển thị/thực thi hiệu lực của một bước (ưu tiên `stepOrder` riêng trên execution, dự phòng `sequenceOrder` của bước mẫu).
     */
    private int effectiveStepOrder(WorkflowStepExecution se) {
        if (se == null)
            return 0;
        if (se.getStepOrder() != null)
            return se.getStepOrder();
        return se.getStep() != null && se.getStep().getSequenceOrder() != null ? se.getStep().getSequenceOrder() : 0;
    }

    /**
     * [Hàm hỗ trợ]: Xác định ca khám hiệu lực cho thao tác (ca gốc hoặc ca tái khám `activeVisitId` nếu hợp lệ và khớp hồ sơ bệnh nhân).
     */
    private Visit resolveEffectiveVisit(WorkflowExecution execution, Long activeVisitId) {
        Visit originVisit = execution.getVisit();
        if (activeVisitId == null || (originVisit != null && activeVisitId.equals(originVisit.getVisitId()))) {
            return originVisit;
        }
        Visit active = visitRepo.findById(activeVisitId)
                .orElseThrow(
                        () -> new IllegalArgumentException("Không tìm thấy ca khám hiện tại với ID: " + activeVisitId));
        if (execution.getPatientProfile() != null && active.getPatientProfile() != null
                && !execution.getPatientProfile().getProfileId().equals(active.getPatientProfile().getProfileId())) {
            throw new IllegalArgumentException("Ca khám hiện tại không khớp bệnh nhân của quy trình.");
        }
        return active;
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra một bước quy trình đã có thời gian bắt đầu, ghi chú hoặc dữ liệu nhập thực sự (bỏ qua dữ liệu `prefilled` tự điền răng/số lượng) hay chưa.
     */
    private boolean hasStepSavedContent(WorkflowStepExecution se) {
        if (se == null)
            return false;
        if (se.getStartedAt() != null)
            return true;
        if (se.getResultNote() != null && !se.getResultNote().isBlank())
            return true;
        Map<String, Object> data = parseJson(se.getResultData());
        boolean onlyPrefilled = Boolean.TRUE.equals(data.get("prefilled"));
        return data.entrySet().stream().anyMatch(entry -> {
            String key = entry.getKey();
            if ("prefilled".equals(key))
                return false;
            if (onlyPrefilled && ("teeth".equals(key) || "serviceQuantity".equals(key)))
                return false;
            Object v = entry.getValue();
            if (v == null)
                return false;
            if (v instanceof Collection<?> col)
                return !col.isEmpty();
            if (v instanceof Map<?, ?> mp)
                return !mp.isEmpty();
            return !String.valueOf(v).isBlank();
        });
    }

    /**
     * [Hàm hỗ trợ]: Phát hiện tiến trình quy trình đã `COMPLETED` nhưng toàn bộ hạng mục KHDT liên kết lại đang ở `PLANNED` và chưa có dịch vụ thực hiện (trạng thái mồ côi do hủy dịch vụ).
     */
    private boolean isOrphanedCompletedPlanExecution(WorkflowExecution execution) {
        if (execution == null || execution.getStatus() != WorkflowExecutionStatus.COMPLETED) {
            return false;
        }
        if (treatmentPlanItemRepo == null) {
            return false;
        }
        TreatmentPlanStage stage = execution.getPlanStage() != null
                ? execution.getPlanStage()
                : (execution.getPlanItem() != null ? execution.getPlanItem().getStage() : null);
        Long serviceId = execution.getVersion() != null
                && execution.getVersion().getTemplate() != null
                && execution.getVersion().getTemplate().getService() != null
                        ? execution.getVersion().getTemplate().getService().getServiceId()
                        : null;

        List<TreatmentPlanItem> linkedItems = new ArrayList<>();
        if (stage != null && stage.getStageId() != null) {
            for (TreatmentPlanItem it : treatmentPlanItemRepo.findByStage_StageId(stage.getStageId())) {
                if (it.getStatus() == TreatmentPlanItemStatus.CANCELLED) {
                    continue;
                }
                if (serviceId == null || (it.getService() != null && serviceId.equals(it.getService().getServiceId()))) {
                    linkedItems.add(it);
                }
            }
        } else if (execution.getPlanItem() != null
                && execution.getPlanItem().getStatus() != TreatmentPlanItemStatus.CANCELLED) {
            linkedItems.add(execution.getPlanItem());
        }

        if (linkedItems.isEmpty()) {
            return false;
        }
        boolean allPlanned = linkedItems.stream()
                .allMatch(it -> it.getStatus() == TreatmentPlanItemStatus.PLANNED);
        if (!allPlanned) {
            return false;
        }
        return linkedItems.stream().noneMatch(it -> it.getPlanItemId() != null
                && performedServiceRepo.existsByPlanItem_PlanItemIdAndStatusNot(
                        it.getPlanItemId(), PerformedServiceStatus.CANCELLED));
    }

    /**
     * [Hàm hỗ trợ]: Phục hồi một tiến trình `COMPLETED` mồ côi về lại `IN_PROGRESS` và đặt lại các bước về `PENDING` để bác sĩ có thể thực hiện lại từ đầu trên ca khám hiện tại.
     */
    private WorkflowExecution reconcileOrphanedCompletedExecution(WorkflowExecution execution, Visit visit) {
        if (!isOrphanedCompletedPlanExecution(execution)) {
            return execution;
        }
        List<WorkflowStepExecution> steps = new ArrayList<>(
                stepExecutionRepo.findByExecution_ExecutionIdOrderByStep_SequenceOrderAsc(execution.getExecutionId()));
        steps.sort(Comparator.comparingInt(this::effectiveStepOrder));
        for (WorkflowStepExecution se : steps) {
            se.setStatus(WorkflowStepExecutionStatus.PENDING);
            se.setStartedAt(null);
            se.setCompletedAt(null);
            se.setApprovedBy(null);
            se.setApprovedAt(null);
        }
        if (!steps.isEmpty()) {
            stepExecutionRepo.saveAll(steps);
        }
        execution.setStatus(WorkflowExecutionStatus.IN_PROGRESS);
        execution.setCompletedAt(null);
        execution.setCurrentStep(steps.isEmpty() ? null : steps.get(0).getStep());
        if (visit != null) {
            execution.setVisit(visit);
            if (visit.getBranch() != null) {
                execution.setBranch(visit.getBranch());
            }
        }
        return executionRepo.save(execution);
    }

    /**
     * [Hàm hỗ trợ]: Lấy danh sách các `TreatmentPlanItem` (chưa hủy) liên kết với tiến trình quy trình theo giai đoạn + dịch vụ hoặc theo hạng mục đơn lẻ.
     */
    private List<TreatmentPlanItem> resolveExecutionLinkedPlanItems(WorkflowExecution execution) {
        if (execution == null || treatmentPlanItemRepo == null) {
            return List.of();
        }
        TreatmentPlanStage stage = execution.getPlanStage() != null
                ? execution.getPlanStage()
                : (execution.getPlanItem() != null ? execution.getPlanItem().getStage() : null);
        Long serviceId = execution.getVersion() != null
                && execution.getVersion().getTemplate() != null
                && execution.getVersion().getTemplate().getService() != null
                        ? execution.getVersion().getTemplate().getService().getServiceId()
                        : null;

        List<TreatmentPlanItem> linkedItems = new ArrayList<>();
        if (stage != null && stage.getStageId() != null) {
            for (TreatmentPlanItem it : treatmentPlanItemRepo.findByStage_StageId(stage.getStageId())) {
                if (it.getStatus() == TreatmentPlanItemStatus.CANCELLED) {
                    continue;
                }
                if (serviceId == null || (it.getService() != null && serviceId.equals(it.getService().getServiceId()))) {
                    linkedItems.add(it);
                }
            }
            linkedItems.sort(Comparator.comparing(TreatmentPlanItem::getPlanItemId));
        } else if (execution.getPlanItem() != null && execution.getPlanItem().getPlanItemId() != null) {
            treatmentPlanItemRepo.findById(execution.getPlanItem().getPlanItemId())
                    .filter(it -> it.getStatus() != TreatmentPlanItemStatus.CANCELLED)
                    .ifPresent(linkedItems::add);
        }
        return linkedItems;
    }

    /**
     * [Hàm hỗ trợ]: Ánh xạ `stepExecutionId` của các bước chuẩn sang `TreatmentPlanItem` tương ứng (ưu tiên khớp tên bước chuẩn hóa, sau đó khớp theo chỉ số vị trí).
     */
    private Map<Long, TreatmentPlanItem> mapPlanItemsToTemplateSteps(
            List<TreatmentPlanItem> linkedItems,
            List<WorkflowStepExecution> templateSteps) {
        Map<Long, TreatmentPlanItem> mapping = new LinkedHashMap<>();
        if (linkedItems == null || linkedItems.isEmpty() || templateSteps == null || templateSteps.isEmpty()) {
            return mapping;
        }
        if (linkedItems.size() == 1) {
            TreatmentPlanItem single = linkedItems.get(0);
            if (single.getStatus() == TreatmentPlanItemStatus.COMPLETED || templateSteps.size() == 1) {
                for (WorkflowStepExecution se : templateSteps) {
                    if (se.getStepExecutionId() != null) {
                        mapping.put(se.getStepExecutionId(), single);
                    }
                }
            }
            return mapping;
        }

        boolean[] usedStep = new boolean[templateSteps.size()];
        Set<Integer> usedItemIdx = new HashSet<>();

        for (int i = 0; i < linkedItems.size() && i < templateSteps.size(); i++) {
            String itemNorm = normalizePlanStepName(linkedItems.get(i).getStepName());
            String stepNorm = templateSteps.get(i).getStep() != null
                    ? normalizePlanStepName(templateSteps.get(i).getStep().getStepName())
                    : "";
            if (!itemNorm.isEmpty() && itemNorm.equals(stepNorm)) {
                Long seId = templateSteps.get(i).getStepExecutionId();
                if (seId != null) {
                    mapping.put(seId, linkedItems.get(i));
                }
                usedStep[i] = true;
                usedItemIdx.add(i);
            }
        }

        for (int i = 0; i < linkedItems.size(); i++) {
            if (usedItemIdx.contains(i)) {
                continue;
            }
            String itemNorm = normalizePlanStepName(linkedItems.get(i).getStepName());
            if (itemNorm.isEmpty()) {
                continue;
            }
            for (int j = 0; j < templateSteps.size(); j++) {
                if (usedStep[j]) {
                    continue;
                }
                String stepNorm = templateSteps.get(j).getStep() != null
                        ? normalizePlanStepName(templateSteps.get(j).getStep().getStepName())
                        : "";
                if (itemNorm.equals(stepNorm)) {
                    Long seId = templateSteps.get(j).getStepExecutionId();
                    if (seId != null) {
                        mapping.put(seId, linkedItems.get(i));
                    }
                    usedStep[j] = true;
                    usedItemIdx.add(i);
                    break;
                }
            }
        }

        for (int i = 0; i < linkedItems.size() && i < templateSteps.size(); i++) {
            if (!usedItemIdx.contains(i) && !usedStep[i]) {
                Long seId = templateSteps.get(i).getStepExecutionId();
                if (seId != null) {
                    mapping.put(seId, linkedItems.get(i));
                }
                usedStep[i] = true;
                usedItemIdx.add(i);
            }
        }
        return mapping;
    }

    /**
     * [Hàm hỗ trợ]: Đối soát ngược tiến trình quy trình với các hạng mục KHDT đã được hoàn thành nhanh bên ngoài, tự đánh dấu `COMPLETED` các bước khớp và cập nhật `currentStep`/trạng thái `WorkflowExecution`.
     */
    private WorkflowExecution reconcileExecutionWithPlanItems(WorkflowExecution execution, Visit visit) {
        if (execution == null || execution.getStatus() == WorkflowExecutionStatus.CANCELLED) {
            return execution;
        }
        List<TreatmentPlanItem> linkedItems = resolveExecutionLinkedPlanItems(execution);
        if (linkedItems.isEmpty()) {
            return execution;
        }

        List<WorkflowStepExecution> allSteps = new ArrayList<>(
                stepExecutionRepo.findByExecution_ExecutionIdOrderByStep_SequenceOrderAsc(execution.getExecutionId()));
        if (allSteps.isEmpty()) {
            return execution;
        }
        allSteps.sort(Comparator.comparingInt(this::effectiveStepOrder));
        List<WorkflowStepExecution> templateSteps = allSteps.stream()
                .filter(se -> se.getStep() != null && se.getStep().getAdhocExecutionId() == null)
                .toList();

        Map<Long, TreatmentPlanItem> matchedByStepExecId = mapPlanItemsToTemplateSteps(linkedItems, templateSteps);
        boolean stepsChanged = false;
        LocalDateTime now = LocalDateTime.now();

        for (WorkflowStepExecution se : templateSteps) {
            TreatmentPlanItem matchedItem = se.getStepExecutionId() != null
                    ? matchedByStepExecId.get(se.getStepExecutionId())
                    : null;
            if (matchedItem == null) {
                continue;
            }
            if (matchedItem.getStatus() == TreatmentPlanItemStatus.COMPLETED
                    && se.getStatus() != WorkflowStepExecutionStatus.COMPLETED
                    && se.getStatus() != WorkflowStepExecutionStatus.SKIPPED) {
                LocalDateTime doneAt = matchedItem.getCompletedAt() != null ? matchedItem.getCompletedAt() : now;
                se.setStatus(WorkflowStepExecutionStatus.COMPLETED);
                if (se.getStartedAt() == null) {
                    se.setStartedAt(doneAt);
                }
                se.setCompletedAt(doneAt);
                if (se.getAssignedStaff() == null) {
                    StaffProfile actor = null;
                    if (matchedItem.getPlanItemId() != null) {
                        List<PerformedService> activePs = performedServiceRepo.findByPlanItem_PlanItemIdAndStatusNot(
                                matchedItem.getPlanItemId(), PerformedServiceStatus.CANCELLED);
                        if (!activePs.isEmpty() && activePs.get(0).getDoctor() != null) {
                            actor = activePs.get(0).getDoctor();
                        }
                    }
                    if (actor == null && visit != null) {
                        actor = visit.getPrimaryDoctor();
                    }
                    if (actor != null) {
                        se.setAssignedStaff(actor);
                    }
                }
                if (se.getResultNote() == null || se.getResultNote().isBlank()) {
                    se.setResultNote("Đã hoàn thành nhanh từ Kế hoạch điều trị");
                }
                stepsChanged = true;
            }
        }

        if (stepsChanged) {
            stepExecutionRepo.saveAll(allSteps);
        }

        WorkflowStepExecution next = firstOpenStep(allSteps);
        if (next == null) {
            if (execution.getStatus() != WorkflowExecutionStatus.COMPLETED || execution.getCurrentStep() != null) {
                execution.setStatus(WorkflowExecutionStatus.COMPLETED);
                execution.setCurrentStep(null);
                if (execution.getCompletedAt() == null) {
                    execution.setCompletedAt(now);
                }
                execution = executionRepo.save(execution);
            }
        } else {
            WorkflowExecutionStatus expectedStatus = next.getStatus() == WorkflowStepExecutionStatus.PAUSED
                    ? WorkflowExecutionStatus.PAUSED
                    : WorkflowExecutionStatus.IN_PROGRESS;
            TreatmentPlanItem nextOpenItem = linkedItems.stream()
                    .filter(i -> i.getStatus() != TreatmentPlanItemStatus.COMPLETED)
                    .findFirst()
                    .orElse(execution.getPlanItem());
            Long currentStepId = execution.getCurrentStep() != null ? execution.getCurrentStep().getStepId() : null;
            Long nextStepId = next.getStep() != null ? next.getStep().getStepId() : null;
            Long currentPlanItemId = execution.getPlanItem() != null ? execution.getPlanItem().getPlanItemId() : null;
            Long nextPlanItemId = nextOpenItem != null ? nextOpenItem.getPlanItemId() : null;

            if (stepsChanged || execution.getStatus() != expectedStatus
                    || !Objects.equals(currentStepId, nextStepId)
                    || (nextPlanItemId != null && !Objects.equals(currentPlanItemId, nextPlanItemId))) {
                execution.setCurrentStep(next.getStep());
                execution.setStatus(expectedStatus);
                execution.setCompletedAt(null);
                if (nextOpenItem != null) {
                    execution.setPlanItem(nextOpenItem);
                }
                execution = executionRepo.save(execution);
            }
        }
        return execution;
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra tiến trình quy trình có được phép hủy xóa hay không (chưa hoàn tất/hủy và mọi bước đều còn `PENDING` không có dữ liệu lưu).
     */
    private boolean canDeleteExecution(WorkflowExecution execution, List<WorkflowStepExecution> steps) {
        if (execution == null || execution.getStatus() == WorkflowExecutionStatus.COMPLETED
                || execution.getStatus() == WorkflowExecutionStatus.CANCELLED) {
            return false;
        }
        if (steps == null)
            return true;
        return steps.stream().noneMatch(se -> se.getStatus() != WorkflowStepExecutionStatus.PENDING
                || hasStepSavedContent(se));
    }

    /**
     * [Hàm hỗ trợ]: Tách chuỗi ký hiệu răng (ví dụ `"R16, R17"` hoặc `"Toàn hàm"`) thành danh sách mã răng sạch không trùng lặp.
     */
    private List<String> parseTeethTokens(String toothNumbers) {
        if (toothNumbers == null || toothNumbers.isBlank())
            return Collections.emptyList();
        List<String> tokens = new ArrayList<>();
        for (String chunk : toothNumbers.split("[,;]+")) {
            String trimmed = chunk == null ? "" : chunk.trim();
            if (trimmed.isEmpty())
                continue;
            if (trimmed.matches("(?i)^toàn\\s*hàm$") || trimmed.matches("(?i)^toan\\s*ham$")) {
                tokens.add("Toàn hàm");
                continue;
            }
            for (String sub : trimmed.split("\\s+")) {
                String clean = sub.replaceFirst("(?i)^R-?", "").trim();
                if (!clean.isBlank()) {
                    tokens.add(clean);
                }
            }
        }
        return tokens.stream().distinct().collect(Collectors.toList());
    }

    /**
     * [Hàm hỗ trợ]: Gộp dữ liệu biểu mẫu `incoming` mới gửi vào JSON `resultData` cũ của bước và nối thêm bản ghi `sessionSnapshot` vào mảng `sessions`.
     */
    @SuppressWarnings("unchecked")
    private String mergeStepResultData(String existingJson, Map<String, Object> incoming,
            Map<String, Object> sessionSnapshot) {
        Map<String, Object> merged = parseJson(existingJson);
        if (incoming != null && !incoming.isEmpty()) {
            merged.remove("prefilled");
            for (Map.Entry<String, Object> entry : incoming.entrySet()) {
                if ("sessions".equals(entry.getKey()))
                    continue;
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        if (sessionSnapshot != null) {
            List<Map<String, Object>> sessions = new ArrayList<>();
            Object oldSessions = merged.get("sessions");
            if (oldSessions instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> mp) {
                        Map<String, Object> copy = new LinkedHashMap<>();
                        mp.forEach((k, v) -> {
                            if (k != null)
                                copy.put(k.toString(), v);
                        });
                        sessions.add(copy);
                    }
                }
            }
            sessions.add(sessionSnapshot);
            merged.put("sessions", sessions);
        }
        return writeJsonQuietly(merged);
    }

    /**
     * [Hàm hỗ trợ]: Tạo đối tượng snapshot lịch sử buổi khám (`time`, `action`, `visitId`, `visitCode`, `staffName`, `note`, `values`) để lưu trong `resultData.sessions`.
     */
    private Map<String, Object> buildSessionSnapshot(String action, Visit visit, String actorName,
            String note, Map<String, Object> incomingValues, LocalDateTime now) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("time", now != null ? now.format(DT_FORMAT) : LocalDateTime.now().format(DT_FORMAT));
        snap.put("action", action);
        snap.put("visitId", visit != null ? visit.getVisitId() : null);
        snap.put("visitCode", visit != null ? visit.getVisitCode() : null);
        snap.put("staffName", actorName);
        snap.put("note", note);
        if (incomingValues != null && !incomingValues.isEmpty()) {
            Map<String, Object> valCopy = new LinkedHashMap<>(incomingValues);
            valCopy.remove("sessions");
            snap.put("values", valCopy);
        }
        return snap;
    }

    /**
     * [Hàm hỗ trợ]: Lấy `TreatmentPlan` cha của một `TreatmentPlanItem` (trực tiếp hoặc thông qua giai đoạn `stage`).
     */
    private TreatmentPlan parentPlanOf(TreatmentPlanItem item) {
        if (item == null)
            return null;
        if (item.getPlan() != null)
            return item.getPlan();
        return item.getStage() != null ? item.getStage().getPlan() : null;
    }

    /**
     * [Hàm hỗ trợ]: Chuyển trạng thái hạng mục KHDT liên kết từ `PLANNED` sang `IN_PROGRESS` và tính lại trạng thái giai đoạn/phác đồ khi quy trình bắt đầu chạy.
     */
    private void syncLinkedPlanOnProgress(TreatmentPlanItem planItem) {
        if (planItem == null || treatmentPlanItemRepo == null)
            return;
        try {
            TreatmentPlanItem item = treatmentPlanItemRepo.findById(planItem.getPlanItemId()).orElse(null);
            if (item == null)
                return;
            if (item.getStatus() == TreatmentPlanItemStatus.PLANNED) {
                item.setStatus(TreatmentPlanItemStatus.IN_PROGRESS);
                treatmentPlanItemRepo.save(item);
                recalculateStageAndPlanStatus(item);
            }
        } catch (Exception ex) {
            log.warn("Không thể đồng bộ trạng thái đang thực hiện sang Kế hoạch điều trị: {}", ex.getMessage());
        }
    }

    /**
     * [Chức năng]: Đồng bộ tiến độ hoàn tất/bỏ qua của từng bước quy trình sang Kế hoạch điều trị và Dịch vụ thực hiện (`PerformedService`).
     * [Mục đích]: Xử lý cả trường hợp giai đoạn KHDT gồm nhiều hạng mục cùng dịch vụ (khớp từng bước để ghi nhận hoặc hủy khi `SKIP`) lẫn trường hợp hạng mục đơn/dịch vụ độc lập khi hoàn tất toàn bộ quy trình.
     */
    private void syncWorkflowStepProgress(WorkflowExecution execution,
            WorkflowStepExecution target,
            StepAction act,
            List<WorkflowStepExecution> allSteps,
            Visit effectiveVisit,
            Long userId,
            boolean finished,
            LocalDateTime now) {
        TreatmentPlanStage stage = execution.getPlanStage() != null
                ? execution.getPlanStage()
                : (execution.getPlanItem() != null ? execution.getPlanItem().getStage() : null);
        com.cvdcms.entity.Service service = execution.getVersion().getTemplate().getService();
        boolean targetIsAdhoc = target != null && target.getStep() != null
                && target.getStep().getAdhocExecutionId() != null;
        List<WorkflowStepExecution> templateStepExecs = allSteps == null ? List.of()
                : allSteps.stream()
                        .filter(s -> s.getStep() != null && s.getStep().getAdhocExecutionId() == null)
                        .toList();
        boolean anyTemplateStepCompleted = templateStepExecs.stream()
                .anyMatch(s -> s.getStatus() == WorkflowStepExecutionStatus.COMPLETED);

            if (stage != null && service != null && treatmentPlanItemRepo != null) {
                Long sId = service.getServiceId();
                List<TreatmentPlanItem> stageItems = treatmentPlanItemRepo.findByStage_StageId(stage.getStageId())
                        .stream()
                        .filter(i -> i.getStatus() != TreatmentPlanItemStatus.CANCELLED
                                && i.getService() != null && sId.equals(i.getService().getServiceId()))
                        .sorted(Comparator.comparing(TreatmentPlanItem::getPlanItemId))
                        .toList();

                if (stageItems.size() > 1) {
                    List<TreatmentPlanItem> openItems = stageItems.stream()
                            .filter(i -> i.getStatus() != TreatmentPlanItemStatus.COMPLETED)
                            .toList();
                    Long actingDoctorId = resolveActingDoctorId(effectiveVisit, userId);

                    if (act == StepAction.SKIP) {
                        if (!targetIsAdhoc && !openItems.isEmpty()) {
                            TreatmentPlanItem skippedItem = matchStageItemForStepStrict(target, templateStepExecs,
                                    stageItems, openItems);
                            if (skippedItem != null) {
                                skippedItem.setStatus(TreatmentPlanItemStatus.CANCELLED);
                                treatmentPlanItemRepo.save(skippedItem);
                                recalculateStageAndPlanStatus(skippedItem);
                            }
                        }
                        return;
                    }

                    if (!finished) {
                        if (act == StepAction.COMPLETE && !targetIsAdhoc && !openItems.isEmpty()) {
                            TreatmentPlanItem candidate = matchStageItemForStep(target, templateStepExecs, stageItems,
                                    openItems);
                            final TreatmentPlanItem matched = (candidate != null) ? candidate : openItems.get(0);
                            final Long matchedId = matched.getPlanItemId();

                            recordPlanItemAsPerformedOrCompleted(matched, service, execution, allSteps, effectiveVisit,
                                    actingDoctorId, now);

                            TreatmentPlanItem nextOpen = openItems.stream()
                                    .filter(i -> !i.getPlanItemId().equals(matchedId)
                                            && i.getStatus() == TreatmentPlanItemStatus.PLANNED)
                                    .findFirst().orElse(null);
                            if (nextOpen != null) {
                                nextOpen.setStatus(TreatmentPlanItemStatus.IN_PROGRESS);
                                treatmentPlanItemRepo.save(nextOpen);
                            }
                            recalculateStageAndPlanStatus(matched);
                        } else if (!openItems.isEmpty()) {
                            syncLinkedPlanOnProgress(openItems.get(0));
                        }
                        return;
                    }

                    // Kết thúc bước cuối: chỉ hoàn tất/tính tiền các mục mở nếu quy trình có ít
                    // nhất 1 bước mẫu được COMPLETE
                    if (anyTemplateStepCompleted) {
                        TreatmentPlanItem lastHandled = null;
                        for (TreatmentPlanItem it : openItems) {
                            recordPlanItemAsPerformedOrCompleted(it, service, execution, allSteps, effectiveVisit,
                                    actingDoctorId, now);
                            lastHandled = it;
                        }
                        if (lastHandled != null) {
                            recalculateStageAndPlanStatus(lastHandled);
                        }
                    }
                    return;
                }
            }

            if (finished) {
                syncCompletedWorkflowToPlanAndService(execution, allSteps, anyTemplateStepCompleted, effectiveVisit,
                        userId);
            } else if (execution.getPlanItem() != null) {
                syncLinkedPlanOnProgress(execution.getPlanItem());
            }
    }

    /**
     * [Hàm hỗ trợ]: Ghi nhận hoàn tất một `TreatmentPlanItem`, tự động tái sử dụng bản ghi `PerformedService` CLS chưa gắn KHDT nếu có hoặc tạo mới qua `PerformedServiceService` (tính đơn giá 0đ cho phác đồ trả góp và tự nhân số lượng theo số răng).
     */
    private void recordPlanItemAsPerformedOrCompleted(TreatmentPlanItem item,
            com.cvdcms.entity.Service service,
            WorkflowExecution execution,
            List<WorkflowStepExecution> allSteps,
            Visit effectiveVisit,
            Long actingDoctorId,
            LocalDateTime now) {
        if (item == null || item.getStatus() == TreatmentPlanItemStatus.COMPLETED
                || item.getStatus() == TreatmentPlanItemStatus.CANCELLED)
            return;
        boolean alreadyPerformed = performedServiceRepo.existsByPlanItem_PlanItemIdAndStatusNot(
                item.getPlanItemId(), PerformedServiceStatus.CANCELLED);
        if (!alreadyPerformed && effectiveVisit != null && actingDoctorId != null) {
            TreatmentPlan plan = parentPlanOf(item);
            boolean isInstallment = plan != null && Boolean.TRUE.equals(plan.getIsInstallment());
            java.math.BigDecimal qty = item.getQuantity() != null ? item.getQuantity() : java.math.BigDecimal.ONE;
            java.math.BigDecimal unitPrice = isInstallment ? java.math.BigDecimal.ZERO
                    : (item.getEstimatedPrice() != null ? item.getEstimatedPrice()
                            : (service != null && service.getBasePrice() != null ? service.getBasePrice()
                                    : java.math.BigDecimal.ZERO));

            Long itemServiceId = item.getService() != null ? item.getService().getServiceId()
                    : (service != null ? service.getServiceId() : null);
            PerformedService reusableClsPs = findExistingUnlinkedParaclinicalPerformedService(
                    effectiveVisit, execution, allSteps, itemServiceId);
            if (reusableClsPs != null) {
                reusableClsPs.setPlanItem(item);
                reusableClsPs.setUnitPrice(unitPrice);
                reusableClsPs.setQuantity(qty);
                reusableClsPs.setFinalPrice(unitPrice.multiply(qty));
                performedServiceRepo.save(reusableClsPs);
                item.setStatus(TreatmentPlanItemStatus.COMPLETED);
                item.setCompletedAt(now);
                treatmentPlanItemRepo.save(item);
                recalculateStageAndPlanStatus(item);
                return;
            }

            if (performedServiceService != null) {
                com.cvdcms.entity.Service effService = item.getService() != null ? item.getService() : service;
                String resolvedTeeth = (item.getToothNumbers() != null && !item.getToothNumbers().isBlank())
                        ? item.getToothNumbers()
                        : extractTeethFromSteps(allSteps);
                if ((item.getToothNumbers() == null || item.getToothNumbers().isBlank()) && resolvedTeeth != null) {
                    item.setToothNumbers(resolvedTeeth);
                }
                if (qty.compareTo(java.math.BigDecimal.ONE) <= 0 && isToothBasedService(effService)) {
                    int teethCount = countSpecificTeeth(resolvedTeeth);
                    if (teethCount > 1) {
                        qty = java.math.BigDecimal.valueOf(teethCount);
                        item.setQuantity(qty);
                    }
                }
                String displayStep = (item.getStepName() != null && !item.getStepName().isBlank())
                        ? item.getStepName()
                        : (service != null ? service.getServiceName() : "Dịch vụ điều trị");
                PerformedServiceRequestDTO req = PerformedServiceRequestDTO.builder()
                        .visitId(effectiveVisit.getVisitId())
                        .serviceId(item.getService().getServiceId())
                        .stepName(displayStep)
                        .toothNumbers(resolvedTeeth)
                        .quantity(qty)
                        .unitPrice(unitPrice)
                        .discountAmount(java.math.BigDecimal.ZERO)
                        .finalPrice(unitPrice.multiply(qty))
                        .doctorId(actingDoctorId)
                        .planItemId(item.getPlanItemId())
                        .clinicalNotes("Hoàn tất theo quy trình từng bước: "
                                + execution.getVersion().getTemplate().getTemplateName())
                        .build();
                performedServiceService.createPerformedService(req);
                item.setStatus(TreatmentPlanItemStatus.COMPLETED);
                item.setCompletedAt(now);
                treatmentPlanItemRepo.save(item);
                return;
            }
        }
        item.setStatus(TreatmentPlanItemStatus.COMPLETED);
        item.setCompletedAt(now);
        treatmentPlanItemRepo.save(item);
    }

    /**
     * [Hàm hỗ trợ]: Khớp chặt bước quy trình `target` với một `TreatmentPlanItem` đang mở trong giai đoạn (chỉ khớp theo tên bước chuẩn hóa hoặc chỉ số 1-1 khi số lượng bước mẫu bằng số hạng mục).
     */
    private TreatmentPlanItem matchStageItemForStepStrict(WorkflowStepExecution target,
            List<WorkflowStepExecution> templateSteps,
            List<TreatmentPlanItem> stageItems,
            List<TreatmentPlanItem> openItems) {
        if (target == null || target.getStep() == null || openItems.isEmpty())
            return null;
        String stepNorm = normalizePlanStepName(target.getStep().getStepName());
        if (!stepNorm.isEmpty()) {
            for (TreatmentPlanItem it : openItems) {
                if (stepNorm.equals(normalizePlanStepName(it.getStepName()))) {
                    return it;
                }
            }
        }
        if (templateSteps != null && templateSteps.size() == stageItems.size()) {
            int idx = templateSteps.indexOf(target);
            if (idx >= 0 && idx < stageItems.size()
                    && stageItems.get(idx).getStatus() != TreatmentPlanItemStatus.COMPLETED) {
                return stageItems.get(idx);
            }
        }
        return null;
    }

    /**
     * [Hàm hỗ trợ]: Khớp bước quy trình `target` với một `TreatmentPlanItem` đang mở (ưu tiên `matchStageItemForStepStrict`, dự phòng theo vị trí hoặc phần tử mở đầu tiên).
     */
    private TreatmentPlanItem matchStageItemForStep(WorkflowStepExecution target,
            List<WorkflowStepExecution> allSteps,
            List<TreatmentPlanItem> stageItems,
            List<TreatmentPlanItem> openItems) {
        TreatmentPlanItem strictMatch = matchStageItemForStepStrict(target, allSteps, stageItems, openItems);
        if (strictMatch != null)
            return strictMatch;
        if (target == null || target.getStep() == null || openItems.isEmpty())
            return null;
        int idx = allSteps != null ? allSteps.indexOf(target) : -1;
        if (idx >= 0 && idx < stageItems.size()
                && stageItems.get(idx).getStatus() != TreatmentPlanItemStatus.COMPLETED) {
            return stageItems.get(idx);
        }
        return openItems.get(0);
    }

    /**
     * [Hàm hỗ trợ]: Chuẩn hóa tên bước (bỏ dấu tiếng Việt, chuyển chữ thường, đổi `đ/Đ` thành `d`) để so khớp giữa bước quy trình mẫu và hạng mục KHDT.
     */
    private String normalizePlanStepName(String s) {
        if (s == null)
            return "";
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('đ', 'd').replace('Đ', 'd')
                .trim().toLowerCase(Locale.ROOT);
    }

    /**
     * [Chức năng]: Đồng bộ kết quả khi toàn bộ quy trình kết thúc sang hạng mục KHDT đơn lẻ hoặc sinh `PerformedService` độc lập.
     * [Mục đích]: Nếu không có bước mẫu nào được `COMPLETE` thì hủy hạng mục KHDT (tránh tính tiền khi bỏ qua hết); ngược lại tái sử dụng `PerformedService` CLS hoặc tạo mới dịch vụ thực hiện, cập nhật trạng thái KHDT và tính lại trạng thái giai đoạn/phác đồ.
     */
    private void syncCompletedWorkflowToPlanAndService(WorkflowExecution execution,
            List<WorkflowStepExecution> allSteps,
            boolean anyTemplateStepCompleted,
            Visit effectiveVisit,
            Long userId) {
        try {
            TreatmentPlanItem planItem = execution.getPlanItem();
            com.cvdcms.entity.Service service = execution.getVersion().getTemplate().getService();
            Long actingDoctorId = resolveActingDoctorId(effectiveVisit, userId);

            if (planItem != null && treatmentPlanItemRepo != null) {
                TreatmentPlanItem item = treatmentPlanItemRepo.findById(planItem.getPlanItemId()).orElse(null);
                if (item != null) {
                    if (!anyTemplateStepCompleted) {
                        if (item.getStatus() != TreatmentPlanItemStatus.COMPLETED
                                && item.getStatus() != TreatmentPlanItemStatus.CANCELLED) {
                            item.setStatus(TreatmentPlanItemStatus.CANCELLED);
                            treatmentPlanItemRepo.save(item);
                            recalculateStageAndPlanStatus(item);
                        }
                        return;
                    }
                    boolean alreadyPerformed = performedServiceRepo.existsByPlanItem_PlanItemIdAndStatusNot(
                            item.getPlanItemId(), PerformedServiceStatus.CANCELLED);
                    if (!alreadyPerformed && effectiveVisit != null
                            && actingDoctorId != null && item.getStatus() != TreatmentPlanItemStatus.COMPLETED
                            && item.getStatus() != TreatmentPlanItemStatus.CANCELLED) {
                        TreatmentPlan plan = parentPlanOf(item);
                        boolean isInstallment = plan != null && Boolean.TRUE.equals(plan.getIsInstallment());
                        java.math.BigDecimal qty = item.getQuantity() != null ? item.getQuantity()
                                : java.math.BigDecimal.ONE;
                        java.math.BigDecimal unitPrice = isInstallment ? java.math.BigDecimal.ZERO
                                : (item.getEstimatedPrice() != null ? item.getEstimatedPrice()
                                        : (item.getService() != null && item.getService().getBasePrice() != null
                                                ? item.getService().getBasePrice()
                                                : java.math.BigDecimal.ZERO));

                        Long itemServiceId = item.getService() != null ? item.getService().getServiceId()
                                : (service != null ? service.getServiceId() : null);
                        PerformedService reusableClsPs = findExistingUnlinkedParaclinicalPerformedService(
                                effectiveVisit, execution, allSteps, itemServiceId);
                        if (reusableClsPs != null) {
                            reusableClsPs.setPlanItem(item);
                            reusableClsPs.setUnitPrice(unitPrice);
                            reusableClsPs.setQuantity(qty);
                            reusableClsPs.setFinalPrice(unitPrice.multiply(qty));
                            performedServiceRepo.save(reusableClsPs);
                            item.setStatus(TreatmentPlanItemStatus.COMPLETED);
                            item.setCompletedAt(LocalDateTime.now());
                            treatmentPlanItemRepo.save(item);
                            recalculateStageAndPlanStatus(item);
                            return;
                        }

                        if (performedServiceService != null) {
                            com.cvdcms.entity.Service effService = item.getService() != null ? item.getService() : service;
                            String resolvedTeeth = (item.getToothNumbers() != null && !item.getToothNumbers().isBlank())
                                    ? item.getToothNumbers()
                                    : extractTeethFromSteps(allSteps);
                            if ((item.getToothNumbers() == null || item.getToothNumbers().isBlank()) && resolvedTeeth != null) {
                                item.setToothNumbers(resolvedTeeth);
                            }
                            if (qty.compareTo(java.math.BigDecimal.ONE) <= 0 && isToothBasedService(effService)) {
                                int teethCount = countSpecificTeeth(resolvedTeeth);
                                if (teethCount > 1) {
                                    qty = java.math.BigDecimal.valueOf(teethCount);
                                    item.setQuantity(qty);
                                }
                            }
                            String displayStep = item.getStepName() != null && !item.getStepName().isBlank()
                                    ? item.getStepName()
                                    : (item.getService() != null ? item.getService().getServiceName()
                                            : "Dịch vụ điều trị");
                            PerformedServiceRequestDTO req = PerformedServiceRequestDTO.builder()
                                    .visitId(effectiveVisit.getVisitId())
                                    .serviceId(item.getService().getServiceId())
                                    .stepName(displayStep)
                                    .toothNumbers(resolvedTeeth)
                                    .quantity(qty)
                                    .unitPrice(unitPrice)
                                    .discountAmount(java.math.BigDecimal.ZERO)
                                    .finalPrice(unitPrice.multiply(qty))
                                    .doctorId(actingDoctorId)
                                    .planItemId(item.getPlanItemId())
                                    .clinicalNotes("Hoàn tất theo quy trình từng bước: "
                                            + execution.getVersion().getTemplate().getTemplateName())
                                    .build();
                            performedServiceService.createPerformedService(req);
                            item.setStatus(TreatmentPlanItemStatus.COMPLETED);
                            item.setCompletedAt(LocalDateTime.now());
                            treatmentPlanItemRepo.save(item);
                            recalculateStageAndPlanStatus(item);
                            return;
                        }
                    }
                    if (item.getStatus() != TreatmentPlanItemStatus.COMPLETED
                            && item.getStatus() != TreatmentPlanItemStatus.CANCELLED) {
                        item.setStatus(TreatmentPlanItemStatus.COMPLETED);
                        item.setCompletedAt(LocalDateTime.now());
                        treatmentPlanItemRepo.save(item);
                        recalculateStageAndPlanStatus(item);
                    }
                }
                return;
            }

            if (anyTemplateStepCompleted && service != null && effectiveVisit != null && performedServiceService != null
                    && actingDoctorId != null) {
                if (hasAnyActivePerformedServiceForWorkflow(effectiveVisit, execution, allSteps,
                        service.getServiceId())) {
                    return;
                }
                String resolvedTeeth = extractTeethFromSteps(allSteps);
                java.math.BigDecimal qty = resolveStandaloneQuantity(allSteps, service, resolvedTeeth);
                java.math.BigDecimal basePrice = service.getBasePrice() != null ? service.getBasePrice()
                        : java.math.BigDecimal.ZERO;
                PerformedServiceRequestDTO req = PerformedServiceRequestDTO.builder()
                        .visitId(effectiveVisit.getVisitId())
                        .serviceId(service.getServiceId())
                        .stepName(service.getServiceName())
                        .toothNumbers(resolvedTeeth)
                        .quantity(qty)
                        .unitPrice(basePrice)
                        .discountAmount(java.math.BigDecimal.ZERO)
                        .finalPrice(basePrice.multiply(qty))
                        .doctorId(actingDoctorId)
                        .clinicalNotes(
                                "Hoàn tất theo quy trình: " + execution.getVersion().getTemplate().getTemplateName())
                        .build();
                performedServiceService.createPerformedService(req);
            }
        } catch (Exception ex) {
            log.warn("Không thể đồng bộ hoàn tất quy trình sang Kế hoạch/Dịch vụ cho execution {}: {}",
                    execution.getExecutionId(), ex.getMessage());
        }
    }

    /**
     * [Hàm hỗ trợ]: Tìm bản ghi `PerformedService` cận lâm sàng cùng dịch vụ đã phát sinh trong ca nhưng chưa gắn `planItem` và chưa xuất hóa đơn để tái sử dụng, tránh tính tiền hai lần.
     */
    private PerformedService findExistingUnlinkedParaclinicalPerformedService(
            Visit effectiveVisit, WorkflowExecution execution, List<WorkflowStepExecution> allSteps, Long serviceId) {
        if (serviceId == null)
            return null;
        Set<Long> visitIds = new LinkedHashSet<>();
        if (effectiveVisit != null && effectiveVisit.getVisitId() != null)
            visitIds.add(effectiveVisit.getVisitId());
        if (execution != null && execution.getVisit() != null && execution.getVisit().getVisitId() != null) {
            visitIds.add(execution.getVisit().getVisitId());
        }
        // Kiểm tra trước các chỉ định CLS gắn trực tiếp trong các bước của execution
        if (allSteps != null) {
            for (WorkflowStepExecution se : allSteps) {
                Map<String, Object> data = parseJson(se.getResultData());
                Long orderId = parseLongObj(data.get("xrayOrderId"));
                if (orderId != null) {
                    for (ParaclinicalOrderItem poi : paraclinicalItemRepo.findByOrder_OrderId(orderId)) {
                        PerformedService candidate = checkReusableUnlinkedPs(poi.getPerformedService(), serviceId);
                        if (candidate != null)
                            return candidate;
                    }
                }
            }
        }
        for (Long vid : visitIds) {
            List<PerformedService> existing = performedServiceRepo
                    .findByVisit_VisitIdAndService_ServiceIdAndStatusNot(vid, serviceId,
                            PerformedServiceStatus.CANCELLED);
            for (PerformedService ps : existing) {
                if (ps.getPlanItem() == null
                        && paraclinicalItemRepo.existsByPerformedService_PerformedId(ps.getPerformedId())) {
                    PerformedService candidate = checkReusableUnlinkedPs(ps, serviceId);
                    if (candidate != null)
                        return candidate;
                }
            }
        }
        return null;
    }

    /**
     * [Hàm hỗ trợ]: Xác minh một `PerformedService` thỏa điều kiện tái sử dụng (không hủy, chưa gắn `planItem`, khớp `serviceId` và chưa nằm trên hóa đơn hợp lệ).
     */
    private PerformedService checkReusableUnlinkedPs(PerformedService ps, Long serviceId) {
        if (ps == null || ps.getStatus() == PerformedServiceStatus.CANCELLED)
            return null;
        if (ps.getPlanItem() != null)
            return null;
        if (ps.getService() == null || !Objects.equals(ps.getService().getServiceId(), serviceId))
            return null;
        if (invoiceItemRepository != null
                && invoiceItemRepository.existsByPerformedIdAndInvoiceNotCancelled(ps.getPerformedId())) {
            return null;
        }
        return ps;
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra lượt khám hiện tại hoặc chỉ định CLS trong các bước đã có `PerformedService` đang hoạt động cho `serviceId` nhằm tránh tạo trùng dịch vụ độc lập.
     */
    private boolean hasAnyActivePerformedServiceForWorkflow(
            Visit effectiveVisit, WorkflowExecution execution, List<WorkflowStepExecution> allSteps, Long serviceId) {
        if (effectiveVisit != null && effectiveVisit.getVisitId() != null) {
            boolean inCurrentVisit = performedServiceRepo
                    .findByVisit_VisitIdAndService_ServiceIdAndStatusNot(
                            effectiveVisit.getVisitId(), serviceId, PerformedServiceStatus.CANCELLED)
                    .stream().findAny().isPresent();
            if (inCurrentVisit)
                return true;
        }
        if (allSteps != null) {
            for (WorkflowStepExecution se : allSteps) {
                Map<String, Object> data = parseJson(se.getResultData());
                Long orderId = parseLongObj(data.get("xrayOrderId"));
                if (orderId != null) {
                    for (ParaclinicalOrderItem poi : paraclinicalItemRepo.findByOrder_OrderId(orderId)) {
                        PerformedService ps = poi.getPerformedService();
                        if (ps != null && ps.getStatus() != PerformedServiceStatus.CANCELLED
                                && ps.getService() != null
                                && Objects.equals(ps.getService().getServiceId(), serviceId)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra dịch vụ có tính đơn vị theo từng răng hay không (yêu cầu răng và đơn vị không phải lần/gói/hàm/liệu trình/ca).
     */
    private boolean isToothBasedService(com.cvdcms.entity.Service service) {
        if (service == null || Boolean.FALSE.equals(service.getRequiresTooth())) {
            return false;
        }
        String u = service.getUnit() != null ? service.getUnit().trim().toLowerCase(Locale.ROOT) : "";
        return !(u.contains("lần") || u.contains("gói") || u.contains("hàm")
                || u.contains("liệu trình") || u.contains("ca"));
    }

    /**
     * [Hàm hỗ trợ]: Trích xuất và ghép chuỗi danh sách răng đã chọn từ `resultData.teeth` trên toàn bộ các bước của tiến trình.
     */
    private String extractTeethFromSteps(List<WorkflowStepExecution> allSteps) {
        if (allSteps == null || allSteps.isEmpty()) {
            return null;
        }
        Set<String> specificTeeth = new LinkedHashSet<>();
        boolean sawFullJaw = false;
        for (WorkflowStepExecution se : allSteps) {
            Map<String, Object> data = parseJson(se.getResultData());
            Object rawTeeth = data.get("teeth");
            if (rawTeeth instanceof Collection<?> col) {
                for (Object item : col) {
                    if (item == null) continue;
                    String token = normalizeSingleToothToken(item.toString());
                    if (token == null) continue;
                    if ("Toàn hàm".equalsIgnoreCase(token)) {
                        sawFullJaw = true;
                    } else {
                        specificTeeth.add(token);
                    }
                }
            } else if (rawTeeth != null && !rawTeeth.toString().isBlank()) {
                for (String part : rawTeeth.toString().split("[,;]+")) {
                    String token = normalizeSingleToothToken(part);
                    if (token == null) continue;
                    if ("Toàn hàm".equalsIgnoreCase(token)) {
                        sawFullJaw = true;
                    } else {
                        specificTeeth.add(token);
                    }
                }
            }
        }
        if (!specificTeeth.isEmpty()) {
            return String.join(", ", specificTeeth);
        }
        return sawFullJaw ? "Toàn hàm" : null;
    }

    /**
     * [Hàm hỗ trợ]: Chuẩn hóa một mã răng đơn lẻ (bỏ tiền tố `R`/`R-`, chuẩn hóa chuỗi `"Toàn hàm"`).
     */
    private String normalizeSingleToothToken(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        if (t.equalsIgnoreCase("toàn hàm") || t.equalsIgnoreCase("toan ham")) {
            return "Toàn hàm";
        }
        if ((t.startsWith("R-") || t.startsWith("r-")) && t.length() > 2) {
            t = t.substring(2).trim();
        } else if ((t.startsWith("R") || t.startsWith("r")) && t.length() == 3 && Character.isDigit(t.charAt(1))) {
            t = t.substring(1);
        }
        return t.isEmpty() ? null : t;
    }

    /**
     * [Hàm hỗ trợ]: Đếm số lượng mã răng cụ thể (không trùng lặp và loại trừ `"Toàn hàm"`) trong chuỗi răng.
     */
    private int countSpecificTeeth(String teethStr) {
        if (teethStr == null || teethStr.isBlank()) return 0;
        Set<String> set = new LinkedHashSet<>();
        for (String part : teethStr.split("[,;]+")) {
            String tok = normalizeSingleToothToken(part);
            if (tok != null && !"Toàn hàm".equalsIgnoreCase(tok)) {
                set.add(tok);
            }
        }
        return set.size();
    }

    /**
     * [Hàm hỗ trợ]: Xác định số lượng dịch vụ cho quy trình độc lập (ưu tiên `serviceQuantity` đã lưu trong bước, sau đó đếm số răng nếu tính theo răng, mặc định 1).
     */
    private java.math.BigDecimal resolveStandaloneQuantity(
            List<WorkflowStepExecution> allSteps,
            com.cvdcms.entity.Service service,
            String resolvedTeeth) {
        if (allSteps != null) {
            for (WorkflowStepExecution se : allSteps) {
                Map<String, Object> data = parseJson(se.getResultData());
                Long q = parseLongObj(data.get("serviceQuantity"));
                if (q != null && q > 1L) {
                    return java.math.BigDecimal.valueOf(q);
                }
            }
        }
        if (isToothBasedService(service)) {
            int toothCount = countSpecificTeeth(resolvedTeeth);
            if (toothCount > 1) {
                return java.math.BigDecimal.valueOf(toothCount);
            }
        }
        return java.math.BigDecimal.ONE;
    }

    /**
     * [Hàm hỗ trợ]: Tính toán lại và lưu trạng thái của `TreatmentPlanStage` và `TreatmentPlan` cha dựa trên trạng thái tổng hợp của các hạng mục/giai đoạn con.
     */
    private void recalculateStageAndPlanStatus(TreatmentPlanItem item) {
        if (item == null || treatmentPlanStageRepo == null || treatmentPlanRepo == null)
            return;
        TreatmentPlanStage stage = item.getStage();
        if (stage != null) {
            List<TreatmentPlanItem> items = treatmentPlanItemRepo.findByStage_StageId(stage.getStageId());
            List<TreatmentPlanItem> activeItems = items.stream()
                    .filter(i -> i.getStatus() != TreatmentPlanItemStatus.CANCELLED)
                    .toList();
            boolean allDone = !activeItems.isEmpty() && activeItems.stream()
                    .allMatch(i -> i.getStatus() == TreatmentPlanItemStatus.COMPLETED);
            boolean allCancelled = !items.isEmpty() && activeItems.isEmpty();
            boolean anyStarted = activeItems.stream().anyMatch(i -> i.getStatus() == TreatmentPlanItemStatus.COMPLETED
                    || i.getStatus() == TreatmentPlanItemStatus.IN_PROGRESS);
            if (allDone) {
                stage.setStatus(TreatmentPlanStageStatus.COMPLETED);
                if (stage.getCompletedAt() == null)
                    stage.setCompletedAt(LocalDateTime.now());
            } else if (allCancelled) {
                stage.setStatus(TreatmentPlanStageStatus.SKIPPED);
                if (stage.getCompletedAt() == null)
                    stage.setCompletedAt(LocalDateTime.now());
            } else if (anyStarted) {
                stage.setStatus(TreatmentPlanStageStatus.IN_PROGRESS);
                stage.setCompletedAt(null);
            } else {
                stage.setStatus(TreatmentPlanStageStatus.PENDING);
                stage.setCompletedAt(null);
            }
            treatmentPlanStageRepo.save(stage);

            TreatmentPlan plan = stage.getPlan();
            if (plan != null) {
                List<TreatmentPlanStage> allStages = treatmentPlanStageRepo
                        .findByPlan_PlanIdOrderByStageOrderAsc(plan.getPlanId());
                boolean anyStageCompleted = allStages.stream()
                        .anyMatch(s -> s.getStatus() == TreatmentPlanStageStatus.COMPLETED);
                boolean allStagesDone = !allStages.isEmpty() && anyStageCompleted
                        && allStages.stream().allMatch(s -> s.getStatus() == TreatmentPlanStageStatus.COMPLETED
                                || s.getStatus() == TreatmentPlanStageStatus.SKIPPED);
                boolean anyStageStarted = allStages.stream()
                        .anyMatch(s -> s.getStatus() == TreatmentPlanStageStatus.COMPLETED
                                || s.getStatus() == TreatmentPlanStageStatus.IN_PROGRESS);
                if (allStagesDone) {
                    plan.setStatus(TreatmentPlanStatus.COMPLETED);
                } else if (anyStageStarted) {
                    plan.setStatus(TreatmentPlanStatus.IN_PROGRESS);
                } else if (plan.getStatus() == TreatmentPlanStatus.IN_PROGRESS
                        || plan.getStatus() == TreatmentPlanStatus.COMPLETED) {
                    plan.setStatus(TreatmentPlanStatus.ACCEPTED);
                }
                treatmentPlanRepo.save(plan);
            }
        }
    }

    /**
     * [Hàm hỗ trợ]: Xác định `staffId` bác sĩ thực hiện thủ thuật khi đồng bộ quy trình (ưu tiên `DoctorVisitAccess.resolveActingDoctor`, dự phòng `userId` rồi bác sĩ chính của ca).
     */
    private Long resolveActingDoctorId(Visit visit, Long userId) {
        if (visit == null)
            return null;
        try {
            var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (doctorVisitAccess != null
                    && attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra) {
                StaffProfile resolved = doctorVisitAccess.resolveActingDoctor(sra.getRequest().getSession(false), visit,
                        null);
                if (resolved != null)
                    return resolved.getStaffId();
            }
        } catch (Exception ignored) {
        }
        StaffProfile actor = staffOf(userId);
        if (actor != null)
            return actor.getStaffId();
        return visit.getPrimaryDoctor() != null ? visit.getPrimaryDoctor().getStaffId() : null;
    }

    /**
     * [Hàm hỗ trợ]: Chuyển đối tượng bất kỳ sang chuỗi an toàn (`null` giữ nguyên `null`).
     */
    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * [Hàm hỗ trợ]: Đổi mã hành động audit quy trình (`WORKFLOW_*`) sang nhãn tiếng Việt hiển thị trên lịch sử SCR-43.4.
     */
    private String actionLabel(String action) {
        if (action == null)
            return "";
        return switch (action) {
            case "WORKFLOW_CREATE" -> "Khởi tạo quy trình";
            case "WORKFLOW_COMPLETE" -> "Hoàn tất quy trình";
            case "WORKFLOW_DELETE" -> "Hủy quy trình chưa thực hiện";
            case "WORKFLOW_ADHOC_ADD" -> "Thêm bước phát sinh";
            case "WORKFLOW_STEP_START" -> "Bắt đầu bước";
            case "WORKFLOW_STEP_SAVE_DRAFT" -> "Lưu kết quả buổi khám";
            case "WORKFLOW_STEP_COMPLETE" -> "Hoàn tất bước";
            case "WORKFLOW_STEP_PAUSE" -> "Tạm dừng";
            case "WORKFLOW_STEP_RESUME" -> "Tiếp tục";
            case "WORKFLOW_STEP_SKIP" -> "Bỏ qua bước";
            default -> action;
        };
    }

    /**
     * [Hàm hỗ trợ]: Lấy thời lượng ước tính (phút) của dịch vụ từ `estimatedDurationMinutes`/`procedureDurationMinutes`, mặc định 30 phút.
     */
    private int resolveServiceDuration(com.cvdcms.entity.Service s) {
        if (s == null) {
            return 30;
        }
        if (s.getEstimatedDurationMinutes() != null && s.getEstimatedDurationMinutes() > 0) {
            return s.getEstimatedDurationMinutes();
        }
        if (s.getProcedureDurationMinutes() != null && s.getProcedureDurationMinutes() > 0) {
            return s.getProcedureDurationMinutes();
        }
        return 30;
    }

    /**
     * [Hàm hỗ trợ]: Ánh xạ thực thể `ClinicalWorkflowTemplate` kèm phiên bản mới nhất và các bước chuẩn sang `WorkflowTemplateResponseDTO`.
     */
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
                    .serviceName(t.getService().getServiceName())
                    .serviceDurationMinutes(resolveServiceDuration(t.getService()))
                    .allowSameVisit(!Boolean.FALSE.equals(t.getService().getAllowSameVisit()));
        }

        ClinicalWorkflowVersion version = versionRepo
                .findFirstByTemplate_TemplateIdOrderByVersionNumberDesc(t.getTemplateId())
                .orElse(null);

        if (version != null) {
            builder.versionId(version.getVersionId())
                    .versionNumber(version.getVersionNumber())
                    .flowMode(version.getFlowMode() != null ? version.getFlowMode().name() : "CONTINUOUS");

            List<ClinicalWorkflowStep> steps = stepRepo
                    .findByVersion_VersionIdOrderBySequenceOrderAsc(version.getVersionId())
                    .stream().filter(s -> s.getAdhocExecutionId() == null).toList();
            builder.stepCount(steps.size());
            int totalStepDur = steps.stream()
                    .mapToInt(s -> (s.getEstimatedDurationMinutes() != null && s.getEstimatedDurationMinutes() > 0)
                            ? s.getEstimatedDurationMinutes()
                            : 10)
                    .sum();
            builder.totalStepDurationMinutes(totalStepDur);

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
                    .frameKey(inferFrameKey(s.getFrameKey(), s.getStepType(), s.getStepName(), t.getTemplateCode()))
                    .customFields(parseCustomFields(s.getCustomFieldsJson()))
                    .isOptional(s.getIsOptional())
                    .requiresClinicalApproval(s.getRequiresClinicalApproval())
                    .isAdhoc(false)
                    .targetQueueType(s.getTargetQueueType() != null ? s.getTargetQueueType().name() : null)
                    .status(s.getStatus() != null ? s.getStatus().name() : "ACTIVE")
                    .build()).collect(Collectors.toList());

            builder.steps(stepDTOs);
        } else {
            builder.stepCount(0);
            builder.steps(new ArrayList<>());
        }

        return builder.build();
    }

    /**
     * [Hàm hỗ trợ]: Nạp chồng `mapExecutionToDTO` dùng ca khám gốc của tiến trình.
     */
    private WorkflowExecutionDTO mapExecutionToDTO(WorkflowExecution execution) {
        return mapExecutionToDTO(execution, execution.getVisit());
    }

    /**
     * [Hàm hỗ trợ]: Ánh xạ `WorkflowExecution` và các bước thực thi sang `WorkflowExecutionDTO` theo ngữ cảnh `viewVisit` (tính tiến độ %, gộp trường tùy biến 5 khung, xác định `allowedActions`, năng lực bác sĩ và cờ chỉ đọc do bác sĩ khác thực hiện).
     */
    private WorkflowExecutionDTO mapExecutionToDTO(WorkflowExecution execution, Visit viewVisit) {
        ClinicalWorkflowVersion version = execution.getVersion();
        ClinicalWorkflowTemplate template = version.getTemplate();
        Visit effectiveVisit = viewVisit != null ? viewVisit : execution.getVisit();
        boolean continued = execution.getVisit() != null && effectiveVisit != null
                && !Objects.equals(execution.getVisit().getVisitId(), effectiveVisit.getVisitId());

        // Nguồn dữ liệu là các bước đã gắn với lượt khám, sắp theo stepOrder riêng nếu
        // có bước phát sinh
        List<WorkflowStepExecution> stepExecutions = new ArrayList<>(
                stepExecutionRepo.findByExecution_ExecutionIdOrderByStep_SequenceOrderAsc(execution.getExecutionId()));
        stepExecutions.sort(Comparator.comparingInt(this::effectiveStepOrder));

        List<TreatmentPlanItem> linkedItems = resolveExecutionLinkedPlanItems(execution);
        List<WorkflowStepExecution> templateSteps = stepExecutions.stream()
                .filter(se -> se.getStep() != null && se.getStep().getAdhocExecutionId() == null)
                .toList();
        Map<Long, TreatmentPlanItem> matchedByStepExecId = mapPlanItemsToTemplateSteps(linkedItems, templateSteps);
        Map<Long, WorkflowStepExecutionStatus> effectiveStatusByStepExecId = new LinkedHashMap<>();
        for (WorkflowStepExecution se : stepExecutions) {
            WorkflowStepExecutionStatus eff = se.getStatus();
            TreatmentPlanItem matchedItem = se.getStepExecutionId() != null
                    ? matchedByStepExecId.get(se.getStepExecutionId())
                    : null;
            if (matchedItem != null && matchedItem.getStatus() == TreatmentPlanItemStatus.COMPLETED
                    && eff != WorkflowStepExecutionStatus.COMPLETED
                    && eff != WorkflowStepExecutionStatus.SKIPPED) {
                eff = WorkflowStepExecutionStatus.COMPLETED;
            }
            if (se.getStepExecutionId() != null) {
                effectiveStatusByStepExecId.put(se.getStepExecutionId(), eff);
            }
        }

        WorkflowStepExecution current = stepExecutions.stream()
                .filter(se -> {
                    WorkflowStepExecutionStatus eff = se.getStepExecutionId() != null
                            ? effectiveStatusByStepExecId.getOrDefault(se.getStepExecutionId(), se.getStatus())
                            : se.getStatus();
                    return eff != WorkflowStepExecutionStatus.COMPLETED && eff != WorkflowStepExecutionStatus.SKIPPED;
                })
                .findFirst()
                .orElse(null);

        WorkflowExecutionStatus effectiveExecStatus = execution.getStatus();
        if (effectiveExecStatus != WorkflowExecutionStatus.CANCELLED) {
            if (current == null && !stepExecutions.isEmpty()) {
                effectiveExecStatus = WorkflowExecutionStatus.COMPLETED;
            } else if (current != null && effectiveExecStatus == WorkflowExecutionStatus.COMPLETED) {
                effectiveExecStatus = current.getStatus() == WorkflowStepExecutionStatus.PAUSED
                        ? WorkflowExecutionStatus.PAUSED
                        : WorkflowExecutionStatus.IN_PROGRESS;
            }
        }

        Long execServiceId = template != null && template.getService() != null
                ? template.getService().getServiceId()
                : null;
        var capExec = checkServiceCapability(effectiveVisit, execServiceId, null);
        Long currentDoctorId = effectiveVisit != null && effectiveVisit.getPrimaryDoctor() != null
                ? effectiveVisit.getPrimaryDoctor().getStaffId()
                : null;

        String lockReason = lockReason(effectiveVisit);
        boolean actionable = lockReason == null
                && capExec.allowed()
                && (effectiveExecStatus == WorkflowExecutionStatus.IN_PROGRESS
                        || effectiveExecStatus == WorkflowExecutionStatus.PAUSED);
        boolean interruptible = version.getFlowMode() == FlowMode.INTERRUPTIBLE;

        List<WorkflowStepDTO> stepDTOs = new ArrayList<>();
        Map<String, List<WorkflowStepDTO.CustomFieldDTO>> allFrameCustomFields = getAllFrameCustomFields();
        int completedCount = 0;
        int skippedCount = 0;

        for (int idx = 0; idx < stepExecutions.size(); idx++) {
            WorkflowStepExecution se = stepExecutions.get(idx);
            ClinicalWorkflowStep step = se.getStep();
            WorkflowStepExecutionStatus st = se.getStepExecutionId() != null
                    ? effectiveStatusByStepExecId.getOrDefault(se.getStepExecutionId(), se.getStatus())
                    : se.getStatus();
            TreatmentPlanItem matchedItem = se.getStepExecutionId() != null
                    ? matchedByStepExecId.get(se.getStepExecutionId())
                    : null;
            if (st == WorkflowStepExecutionStatus.COMPLETED)
                completedCount++;
            if (st == WorkflowStepExecutionStatus.SKIPPED)
                skippedCount++;

            List<String> actions = new ArrayList<>();
            if (actionable && se == current) {
                switch (st) {
                    case PENDING -> {
                        actions.add("START");
                        if (Boolean.TRUE.equals(step.getIsOptional()))
                            actions.add("SKIP");
                    }
                    case IN_PROGRESS -> {
                        actions.add("COMPLETE");
                        if (interruptible)
                            actions.add("PAUSE");
                    }
                    case PAUSED -> actions.add("RESUME");
                    default -> {
                    }
                }
            }

            Map<String, Object> parsedData = parseJson(se.getResultData());
            int orderNum = se.getStepOrder() != null ? se.getStepOrder()
                    : (step.getSequenceOrder() != null ? step.getSequenceOrder() : (idx + 1));
            String resolvedFrameKey = inferFrameKey(step.getFrameKey(), step.getStepType(), step.getStepName(),
                    template.getTemplateCode());
            List<WorkflowStepDTO.CustomFieldDTO> mergedCustomFields = mergeFrameCustomFields(
                    allFrameCustomFields.get(resolvedFrameKey),
                    parseCustomFields(step.getCustomFieldsJson()));

            LocalDateTime effStartedAt = se.getStartedAt() != null ? se.getStartedAt()
                    : (st == WorkflowStepExecutionStatus.COMPLETED && matchedItem != null ? matchedItem.getCompletedAt() : null);
            LocalDateTime effCompletedAt = se.getCompletedAt() != null ? se.getCompletedAt()
                    : (st == WorkflowStepExecutionStatus.COMPLETED && matchedItem != null ? matchedItem.getCompletedAt() : null);
            String effResultNote = (se.getResultNote() != null && !se.getResultNote().isBlank())
                    ? se.getResultNote()
                    : (st == WorkflowStepExecutionStatus.COMPLETED && se.getStatus() != WorkflowStepExecutionStatus.COMPLETED
                            ? "Đã hoàn thành nhanh từ Kế hoạch điều trị"
                            : null);

            Long stepStaffId = se.getAssignedStaff() != null ? se.getAssignedStaff().getStaffId() : null;
            boolean readOnlyOther = (st == WorkflowStepExecutionStatus.COMPLETED || st == WorkflowStepExecutionStatus.SKIPPED)
                    && stepStaffId != null
                    && currentDoctorId != null
                    && !Objects.equals(stepStaffId, currentDoctorId);

            stepDTOs.add(WorkflowStepDTO.builder()
                    .stepId(step.getStepId())
                    .sequenceOrder(orderNum)
                    .stepCode(step.getStepCode())
                    .stepName(step.getStepName())
                    .stepDescription(step.getStepDescription())
                    .completionCriteria(step.getCompletionCriteria())
                    .estimatedDurationMinutes(step.getEstimatedDurationMinutes())
                    .stepPrice(step.getStepPrice())
                    .stepType(step.getStepType() != null ? step.getStepType().name() : "CLINICAL")
                    .frameKey(resolvedFrameKey)
                    .customFields(mergedCustomFields)
                    .isOptional(step.getIsOptional())
                    .requiresClinicalApproval(step.getRequiresClinicalApproval())
                    .isAdhoc(step.getAdhocExecutionId() != null)
                    .targetQueueType(step.getTargetQueueType() != null ? step.getTargetQueueType().name() : null)
                    .stepExecutionId(se.getStepExecutionId())
                    .executionStatus(st.name())
                    .startedAt(effStartedAt != null ? effStartedAt.format(DT_FORMAT) : null)
                    .completedAt(effCompletedAt != null ? effCompletedAt.format(DT_FORMAT) : null)
                    .resultNote(effResultNote)
                    .resultData(parsedData)
                    .staffName(se.getAssignedStaff() != null ? se.getAssignedStaff().getFullName() : null)
                    .assignedStaffId(stepStaffId)
                    .readOnlyByOtherDoctor(readOnlyOther)
                    .allowedActions(actions)
                    .build());
        }

        int total = stepExecutions.size();
        int progress = total > 0 ? ((completedCount + skippedCount) * 100 / total) : 0;
        ClinicalWorkflowStep currentStep = current != null ? current.getStep() : null;

        TreatmentPlanItem planItem = execution.getPlanItem();
        TreatmentPlanStage planStage = execution.getPlanStage() != null
                ? execution.getPlanStage()
                : (planItem != null ? planItem.getStage() : null);
        TreatmentPlan parentPlan = parentPlanOf(planItem);
        if (parentPlan == null && planStage != null) {
            parentPlan = planStage.getPlan();
        }
        String toothNumbers = planItem != null ? planItem.getToothNumbers() : null;
        if ((toothNumbers == null || toothNumbers.isBlank()) && planStage != null && treatmentPlanItemRepo != null) {
            Long svcId = template.getService() != null ? template.getService().getServiceId() : null;
            toothNumbers = treatmentPlanItemRepo.findByStage_StageId(planStage.getStageId()).stream()
                    .filter(i -> svcId == null
                            || (i.getService() != null && svcId.equals(i.getService().getServiceId())))
                    .map(TreatmentPlanItem::getToothNumbers)
                    .filter(tn -> tn != null && !tn.isBlank())
                    .findFirst().orElse(null);
        }

        return WorkflowExecutionDTO.builder()
                .executionId(execution.getExecutionId())
                .templateId(template.getTemplateId())
                .templateCode(template.getTemplateCode())
                .templateName(template.getTemplateName())
                .visitId(execution.getVisit() != null ? execution.getVisit().getVisitId() : null)
                .serviceId(template.getService() != null ? template.getService().getServiceId() : null)
                .serviceName(template.getService() != null ? template.getService().getServiceName() : null)
                .branchId(execution.getBranch() != null ? execution.getBranch().getBranchId() : null)
                .branchName(execution.getBranch() != null ? execution.getBranch().getBranchName() : null)
                .patientName(execution.getPatientProfile() != null ? execution.getPatientProfile().getFullName() : null)
                .patientCode(
                        execution.getPatientProfile() != null ? execution.getPatientProfile().getPatientCode() : null)
                .doctorName(effectiveVisit != null && effectiveVisit.getPrimaryDoctor() != null
                        ? effectiveVisit.getPrimaryDoctor().getFullName()
                        : null)
                .visitStatus(
                        effectiveVisit != null && effectiveVisit.getStatus() != null ? effectiveVisit.getStatus().name()
                                : null)
                .flowMode(version.getFlowMode() != null ? version.getFlowMode().name() : "CONTINUOUS")
                .status(effectiveExecStatus.name())
                .totalSteps(total)
                .completedSteps(completedCount)
                .skippedSteps(skippedCount)
                .progressPercent(progress)
                .currentStepId(currentStep != null ? currentStep.getStepId() : null)
                .currentStepName(currentStep != null ? currentStep.getStepName() : null)
                .nextQueueType(currentStep != null && currentStep.getTargetQueueType() != null
                        ? currentStep.getTargetQueueType().name()
                        : null)
                .locked(lockReason != null)
                .lockedReason(lockReason)
                .planItemId(planItem != null ? planItem.getPlanItemId() : null)
                .planStageId(planStage != null ? planStage.getStageId() : null)
                .planStageOrder(planStage != null ? planStage.getStageOrder() : null)
                .planStageName(planStage != null ? planStage.getStageName() : null)
                .planCode(parentPlan != null ? parentPlan.getPlanCode() : null)
                .planName(parentPlan != null ? parentPlan.getPlanName() : null)
                .toothNumbers(toothNumbers)
                .continuedFromOtherVisit(continued)
                .canDelete(lockReason == null && canDeleteExecution(execution, stepExecutions))
                .canCurrentDoctorPerform(capExec.allowed())
                .capabilityReason(capExec.message())
                .requiresHandover(!capExec.allowed())
                .startedAt(execution.getStartedAt() != null ? execution.getStartedAt().format(DT_FORMAT) : null)
                .completedAt(execution.getCompletedAt() != null ? execution.getCompletedAt().format(DT_FORMAT) : null)
                .steps(stepDTOs)
                .build();
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra năng lực bác sĩ và phòng khám cho `serviceId` qua `DoctorClinicalCapabilityService` (bỏ qua dịch vụ cận lâm sàng/Labo).
     */
    private com.cvdcms.service.DoctorClinicalCapabilityService.CapabilityCheckResult checkServiceCapability(
            Visit visit, Long serviceId, Long actorUserId) {
        if (capabilityService == null || visit == null || serviceId == null) {
            return com.cvdcms.service.DoctorClinicalCapabilityService.CapabilityCheckResult.ok();
        }
        if (capabilityService.isParaclinicalOrLabService(serviceId)) {
            return com.cvdcms.service.DoctorClinicalCapabilityService.CapabilityCheckResult.ok();
        }
        boolean isAdmin = actorUserId != null && userRepo != null && userRepo.hasActiveAdminRole(actorUserId);
        Long branchId = visit.getBranch() != null ? visit.getBranch().getBranchId() : null;
        StaffProfile actorDoc = (!isAdmin && actorUserId != null) ? staffOf(actorUserId) : null;
        Long doctorId = actorDoc != null
                ? actorDoc.getStaffId()
                : (visit.getPrimaryDoctor() != null ? visit.getPrimaryDoctor().getStaffId() : null);
        return capabilityService.checkDoctorAndRoomCapability(branchId, doctorId, visit.getRoomId(), serviceId);
    }

    // =========================================================================
    // Tầng 1 · Combo dịch vụ mẫu (Tham khảo VTTech Dental)
    // =========================================================================

    /**
     * [Chức năng]: Lấy danh sách Combo dịch vụ mẫu (toàn bộ hoặc chỉ `ACTIVE`).
     * [Mục đích]: Truy vấn `ServiceComboRepository`, đối chiếu mẫu quy trình `ACTIVE` của từng dịch vụ thành phần để gắn số bước/tên mẫu và ánh xạ sang `ServiceComboDTO`.
     */
    @Override
    @Transactional(readOnly = true)
    public List<ServiceComboDTO> getAllCombos(boolean activeOnly) {
        if (serviceComboRepo == null) {
            return List.of();
        }
        List<ServiceCombo> combos = activeOnly
                ? serviceComboRepo.findByStatusOrderByComboIdAsc("ACTIVE")
                : serviceComboRepo.findAllByOrderByComboIdAsc();

        Map<Long, ClinicalWorkflowTemplate> activeTplByService = new LinkedHashMap<>();
        Map<Long, Integer> stepCountByTpl = new HashMap<>();
        for (ClinicalWorkflowTemplate t : templateRepo.findAllByOrderByCreatedAtDesc()) {
            if (t.getStatus() != WorkflowTemplateStatus.ACTIVE || t.getService() == null)
                continue;
            ClinicalWorkflowVersion v = resolveVersion(t);
            int cnt = v != null ? activeSteps(v).size() : 0;
            activeTplByService.putIfAbsent(t.getService().getServiceId(), t);
            stepCountByTpl.put(t.getTemplateId(), cnt);
        }

        return combos.stream()
                .map(c -> mapComboToDTO(c, activeTplByService, stepCountByTpl))
                .toList();
    }

    /**
     * [Chức năng]: Tạo mới hoặc cập nhật một Combo dịch vụ mẫu cùng danh sách dịch vụ thành phần.
     * [Mục đích]: Kiểm tra mã/tên Combo không rỗng, không trùng mã, có ít nhất 1 dịch vụ hợp lệ, lưu `ServiceCombo`/`ServiceComboItem` và ghi audit `COMBO_SAVE`.
     */
    @Override
    @Transactional
    public ServiceComboDTO saveCombo(Map<String, Object> payload, Long userId) {
        if (serviceComboRepo == null) {
            throw new IllegalStateException("Chức năng Combo dịch vụ chưa sẵn sàng.");
        }
        Long comboId = payload.get("comboId") != null && !String.valueOf(payload.get("comboId")).isBlank()
                ? Long.valueOf(String.valueOf(payload.get("comboId")))
                : null;
        String comboCode = asText(payload.get("comboCode"));
        String comboName = asText(payload.get("comboName"));
        String description = asText(payload.get("description"));
        String status = asText(payload.get("status"));

        if (comboCode == null || comboCode.isBlank()) {
            throw new IllegalArgumentException("Mã Combo không được để trống.");
        }
        if (comboName == null || comboName.isBlank()) {
            throw new IllegalArgumentException("Tên Combo không được để trống.");
        }
        comboCode = comboCode.trim().toUpperCase(Locale.ROOT);
        comboName = comboName.trim();
        String normStatus = (status != null && "INACTIVE".equalsIgnoreCase(status.trim())) ? "INACTIVE" : "ACTIVE";

        Optional<ServiceCombo> dupCode = serviceComboRepo.findByComboCode(comboCode);
        if (dupCode.isPresent() && (comboId == null || !dupCode.get().getComboId().equals(comboId))) {
            throw new IllegalStateException("Mã Combo '" + comboCode + "' đã tồn tại.");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawItems = payload.get("items") instanceof List<?> list
                ? (List<Map<String, Object>>) list
                : List.of();
        if (rawItems.isEmpty()) {
            throw new IllegalArgumentException("Vui lòng chọn ít nhất 1 dịch vụ thành phần trong Combo.");
        }

        ServiceCombo combo = comboId != null
                ? serviceComboRepo.findById(comboId)
                        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy Combo ID: " + comboId))
                : new ServiceCombo();
        combo.setComboCode(comboCode);
        combo.setComboName(comboName);
        combo.setDescription(description != null ? description.trim() : null);
        combo.setStatus(normStatus);
        if (combo.getCreatedBy() == null)
            combo.setCreatedBy(userId);
        combo.getItems().clear();

        int order = 1;
        for (Map<String, Object> ri : rawItems) {
            if (ri == null || ri.get("serviceId") == null || String.valueOf(ri.get("serviceId")).isBlank())
                continue;
            Long sId = Long.valueOf(String.valueOf(ri.get("serviceId")));
            com.cvdcms.entity.Service srv = serviceRepo.findById(sId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dịch vụ ID: " + sId));
            int qty = 1;
            if (ri.get("defaultQuantity") != null && !String.valueOf(ri.get("defaultQuantity")).isBlank()) {
                qty = Math.max(1, Integer.parseInt(String.valueOf(ri.get("defaultQuantity"))));
            }
            ServiceComboItem ci = new ServiceComboItem();
            ci.setCombo(combo);
            ci.setService(srv);
            ci.setSequenceOrder(order++);
            ci.setDefaultQuantity(qty);
            ci.setNotes(asText(ri.get("notes")));
            combo.getItems().add(ci);
        }

        if (combo.getItems().isEmpty()) {
            throw new IllegalArgumentException("Danh sách dịch vụ thành phần của Combo không hợp lệ.");
        }

        ServiceCombo saved = serviceComboRepo.save(combo);
        writeAudit(userId, null, "COMBO_SAVE", "service_combos", saved.getComboId(), null,
                Map.of("comboCode", saved.getComboCode(), "comboName", saved.getComboName(), "items",
                        saved.getItems().size()));

        return getAllCombos(false).stream()
                .filter(dto -> saved.getComboId().equals(dto.getComboId()))
                .findFirst()
                .orElseGet(() -> mapComboToDTO(saved, Map.of(), Map.of()));
    }

    /**
     * [Chức năng]: Xóa một Combo dịch vụ mẫu theo `comboId`.
     * [Mục đích]: Kiểm tra tồn tại và xóa bản ghi `ServiceCombo` (cùng các item con) khỏi `ServiceComboRepository`.
     */
    @Override
    @Transactional
    public void deleteCombo(Long comboId) {
        if (serviceComboRepo == null || comboId == null)
            return;
        ServiceCombo combo = serviceComboRepo.findById(comboId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy Combo ID: " + comboId));
        serviceComboRepo.delete(combo);
    }

    /**
     * [Chức năng]: Phiên bản nạp chồng áp dụng Combo dịch vụ vào ca khám không có danh sách dịch vụ dời buổi sau.
     * [Mục đích]: Ủy quyền cho hàm `startComboExecution` 4 tham số với `deferredServiceIds = Set.of()`.
     */
    @Override
    @Transactional
    public Map<String, Object> startComboExecution(Long visitId, Long comboId, Long userId) {
        return startComboExecution(visitId, comboId, userId, Set.of());
    }

    /**
     * [Chức năng]: Áp dụng Combo dịch vụ mẫu vào bàn quy trình của lượt khám, tách phần làm ngay và phần dời sang Kế hoạch điều trị.
     * [Mục đích]: Kiểm tra khóa ca và Combo đang `ACTIVE`; với từng dịch vụ theo thứ tự, khởi tạo `WorkflowExecution` nếu đủ điều kiện và năng lực bác sĩ, gom dịch vụ vượt ngưỡng hoặc cần bàn giao chuyên môn để lưu tự động vào KHDT qua `persistDeferredComboItemsToPlan`.
     */
    @Override
    @Transactional
    public Map<String, Object> startComboExecution(Long visitId, Long comboId, Long userId, Set<Long> deferredServiceIds) {
        if (serviceComboRepo == null) {
            throw new IllegalStateException("Chức năng Combo dịch vụ chưa sẵn sàng.");
        }
        Visit visit = visitRepo.findById(visitId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca khám với ID: " + visitId));
        String lockReason = lockReason(visit);
        if (lockReason != null) {
            throw new IllegalStateException(lockReason);
        }
        ServiceCombo combo = serviceComboRepo.findById(comboId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy Combo ID: " + comboId));
        if (!"ACTIVE".equalsIgnoreCase(combo.getStatus())) {
            throw new IllegalStateException("Combo dịch vụ đang ngừng áp dụng, không thể khởi tạo.");
        }

        List<WorkflowExecutionDTO> started = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> deferred = new ArrayList<>();
        List<Map<String, Object>> handoverRequiredServices = new ArrayList<>();
        List<ServiceComboItem> deferredItems = new ArrayList<>();
        Set<Long> deferredIds = deferredServiceIds != null ? deferredServiceIds : Set.of();
        List<ServiceComboItem> orderedItems = combo.getItems().stream()
                .sorted(Comparator.comparingInt(ci -> ci.getSequenceOrder() != null ? ci.getSequenceOrder() : 999))
                .toList();

        for (ServiceComboItem item : orderedItems) {
            if (item.getService() == null)
                continue;
            Long svcId = item.getService().getServiceId();
            if (deferredIds.contains(svcId)) {
                deferred.add(item.getService().getServiceName());
                deferredItems.add(item);
                continue;
            }
            var capCheck = checkServiceCapability(visit, svcId, userId);
            if (!capCheck.allowed()) {
                deferred.add(item.getService().getServiceName() + " (Cần BS chuyên môn)");
                deferredItems.add(item);
                Map<String, Object> ho = new LinkedHashMap<>();
                ho.put("serviceId", svcId);
                ho.put("serviceName", item.getService().getServiceName());
                ho.put("reason", capCheck.message());
                handoverRequiredServices.add(ho);
                continue;
            }
            WorkflowExecutionDTO dto = createOrGetExecution(
                    visitId, svcId, null, null, userId, false, item.getDefaultQuantity());
            if (dto != null) {
                started.add(dto);
            } else {
                skipped.add(item.getService().getServiceName());
            }
        }
        if (started.isEmpty() && !skipped.isEmpty() && deferred.isEmpty()) {
            throw new IllegalStateException(
                    "Các dịch vụ trong Combo chưa có mẫu quy trình hoạt động: " + String.join(", ", skipped));
        }

        TreatmentPlan savedDeferredPlan = deferredItems.isEmpty()
                ? null
                : persistDeferredComboItemsToPlan(visit, combo, deferredItems, userId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("comboId", combo.getComboId());
        result.put("comboCode", combo.getComboCode());
        result.put("comboName", combo.getComboName());
        result.put("startedCount", started.size());
        result.put("executions", started);
        result.put("skippedServices", skipped);
        result.put("deferredServices", deferred);
        result.put("handoverRequiredServices", handoverRequiredServices);
        if (savedDeferredPlan != null) {
            result.put("deferredPlanId", savedDeferredPlan.getPlanId());
            result.put("deferredPlanCode", savedDeferredPlan.getPlanCode());
        }
        return result;
    }

    /**
     * [Hàm hỗ trợ]: Tự động lưu các dịch vụ trong Combo bị dời buổi sau (hoặc cần bác sĩ chuyên môn) vào một giai đoạn hẹn tiếp theo trong Kế hoạch điều trị đang mở hoặc KHDT mới tạo.
     */
    private TreatmentPlan persistDeferredComboItemsToPlan(Visit visit,
            ServiceCombo combo,
            List<ServiceComboItem> deferredItems,
            Long userId) {
        if (treatmentPlanRepo == null || visit == null || visit.getPatientProfile() == null
                || deferredItems == null || deferredItems.isEmpty()) {
            return null;
        }
        PatientProfile patient = visit.getPatientProfile();
        Branch branch = visit.getBranch() != null ? visit.getBranch() : patient.getPreferredBranch();
        if (branch == null && branchRepo != null) {
            branch = branchRepo.findAll().stream().findFirst().orElse(null);
        }
        StaffProfile doctor = staffOf(userId);
        if (doctor == null) {
            doctor = visit.getPrimaryDoctor();
        }
        if (doctor == null && staffProfileRepo != null) {
            doctor = staffProfileRepo.findAll().stream().findFirst().orElse(null);
        }
        if (branch == null || doctor == null) {
            return null;
        }

        List<TreatmentPlan> existingPlans = treatmentPlanRepo.findByProfileIdWithDetails(patient.getProfileId());
        TreatmentPlan plan = existingPlans == null ? null : existingPlans.stream()
                .filter(p -> (p.getStatus() == TreatmentPlanStatus.IN_PROGRESS
                        || p.getStatus() == TreatmentPlanStatus.ACCEPTED)
                        && !Boolean.TRUE.equals(p.getIsInstallment())
                        && (p.getTotalPaidAmount() == null || p.getTotalPaidAmount().compareTo(java.math.BigDecimal.ZERO) <= 0)
                        && ((p.getVisit() != null && Objects.equals(visit.getVisitId(), p.getVisit().getVisitId()))
                                || !Boolean.TRUE.equals(p.getSentToAccounting())))
                .min(Comparator
                        .comparing((TreatmentPlan p) -> !(p.getVisit() != null && Objects.equals(visit.getVisitId(), p.getVisit().getVisitId())))
                        .thenComparingInt(p -> p.getStatus() == TreatmentPlanStatus.IN_PROGRESS ? 0 : 1))
                .orElse(null);

        if (plan == null) {
            plan = new TreatmentPlan();
            plan.setPlanCode("KHDT" + (System.currentTimeMillis() % 100000000));
            plan.setPatientProfile(patient);
            plan.setBranch(branch);
            plan.setVisit(visit);
            plan.setDoctor(doctor);
            String rawPlanName = "Kế hoạch điều trị: " + combo.getComboName();
            plan.setPlanName(rawPlanName.length() > 150 ? rawPlanName.substring(0, 150) : rawPlanName);
            plan.setStatus(TreatmentPlanStatus.IN_PROGRESS);
            plan.setStartDate(LocalDate.now());
            plan.setExpectedEndDate(LocalDate.now().plusDays(14));
            plan.setNotes("Tự động lưu dịch vụ hẹn buổi sau từ Combo " + combo.getComboCode());
            plan.setTotalEstimatedCost(java.math.BigDecimal.ZERO);
            plan.setFinalEstimatedCost(java.math.BigDecimal.ZERO);
            plan.setTotalPaidAmount(java.math.BigDecimal.ZERO);
        }

        Set<Long> existingOpenServiceIds = new HashSet<>();
        if (plan.getStages() != null) {
            for (TreatmentPlanStage st : plan.getStages()) {
                if (st.getItems() == null) continue;
                for (TreatmentPlanItem it : st.getItems()) {
                    if (it.getService() != null && it.getService().getServiceId() != null
                            && (it.getStatus() == TreatmentPlanItemStatus.PLANNED
                                    || it.getStatus() == TreatmentPlanItemStatus.IN_PROGRESS)) {
                        existingOpenServiceIds.add(it.getService().getServiceId());
                    }
                }
            }
        }

        List<ServiceComboItem> toInsert = deferredItems.stream()
                .filter(ci -> ci.getService() != null
                        && ci.getService().getServiceId() != null
                        && !existingOpenServiceIds.contains(ci.getService().getServiceId()))
                .toList();

        if (toInsert.isEmpty()) {
            return plan.getPlanId() != null ? plan : null;
        }

        if (plan.getStages() == null) {
            plan.setStages(new ArrayList<>());
        }
        int nextStageOrder = plan.getStages().stream()
                .mapToInt(s -> s.getStageOrder() != null ? s.getStageOrder() : 0)
                .max()
                .orElse(0) + 1;

        TreatmentPlanStage stage = new TreatmentPlanStage();
        stage.setPlan(plan);
        stage.setStageOrder(nextStageOrder);
        String rawStageName = "Giai đoạn " + nextStageOrder + ": Hẹn buổi tiếp theo (" + combo.getComboName() + ")";
        stage.setStageName(rawStageName.length() > 150 ? rawStageName.substring(0, 150) : rawStageName);
        stage.setDescription("Tự động lưu dịch vụ vượt ngưỡng thời gian trong ca từ Combo " + combo.getComboCode());
        stage.setStatus(TreatmentPlanStageStatus.PENDING);
        stage.setExpectedEndDate(LocalDate.now().plusDays(7));
        stage.setItems(new ArrayList<>());

        java.math.BigDecimal addedCost = java.math.BigDecimal.ZERO;
        for (ServiceComboItem ci : toInsert) {
            com.cvdcms.entity.Service srv = ci.getService();
            int qtyInt = Math.max(1, ci.getDefaultQuantity() != null ? ci.getDefaultQuantity() : 1);
            java.math.BigDecimal qty = java.math.BigDecimal.valueOf(qtyInt);
            java.math.BigDecimal price = srv.getBasePrice() != null ? srv.getBasePrice() : java.math.BigDecimal.ZERO;

            TreatmentPlanItem item = new TreatmentPlanItem();
            item.setStage(stage);
            item.setPlan(plan);
            item.setService(srv);
            item.setStepName(srv.getServiceName());
            item.setQuantity(qty);
            item.setEstimatedPrice(price);
            item.setStatus(TreatmentPlanItemStatus.PLANNED);
            stage.getItems().add(item);
            addedCost = addedCost.add(price.multiply(qty));
        }

        plan.getStages().add(stage);
        java.math.BigDecimal curTotal = plan.getTotalEstimatedCost() != null ? plan.getTotalEstimatedCost() : java.math.BigDecimal.ZERO;
        java.math.BigDecimal curFinal = plan.getFinalEstimatedCost() != null ? plan.getFinalEstimatedCost() : java.math.BigDecimal.ZERO;
        plan.setTotalEstimatedCost(curTotal.add(addedCost));
        plan.setFinalEstimatedCost(curFinal.add(addedCost));
        return treatmentPlanRepo.save(plan);
    }

    /**
     * [Hàm hỗ trợ]: Ánh xạ thực thể `ServiceCombo` cùng danh sách item thành phần, tổng đơn giá, tổng thời lượng và thông tin quy trình liên kết sang `ServiceComboDTO`.
     */
    private ServiceComboDTO mapComboToDTO(ServiceCombo c,
            Map<Long, ClinicalWorkflowTemplate> activeTplByService,
            Map<Long, Integer> stepCountByTpl) {
        java.math.BigDecimal totalBasePrice = java.math.BigDecimal.ZERO;
        int totalDurationMinutes = 0;
        List<ServiceComboDTO.ComboItemDTO> itemDTOs = new ArrayList<>();
        List<ServiceComboItem> ordered = c.getItems() == null ? List.of()
                : c.getItems().stream()
                        .sorted(Comparator.comparingInt(i -> i.getSequenceOrder() != null ? i.getSequenceOrder() : 999))
                        .toList();
        for (ServiceComboItem ci : ordered) {
            com.cvdcms.entity.Service s = ci.getService();
            if (s == null)
                continue;
            int qty = ci.getDefaultQuantity() != null ? ci.getDefaultQuantity() : 1;
            int srvDur = resolveServiceDuration(s);
            totalDurationMinutes += srvDur * Math.max(1, qty);
            java.math.BigDecimal price = s.getBasePrice() != null ? s.getBasePrice() : java.math.BigDecimal.ZERO;
            totalBasePrice = totalBasePrice.add(price.multiply(java.math.BigDecimal.valueOf(qty)));
            ClinicalWorkflowTemplate tpl = activeTplByService.get(s.getServiceId());
            itemDTOs.add(ServiceComboDTO.ComboItemDTO.builder()
                    .comboItemId(ci.getComboItemId())
                    .serviceId(s.getServiceId())
                    .serviceCode(s.getServiceCode())
                    .serviceName(s.getServiceName())
                    .unit(s.getUnit())
                    .basePrice(price)
                    .sequenceOrder(ci.getSequenceOrder())
                    .defaultQuantity(qty)
                    .notes(ci.getNotes())
                    .workflowTemplateId(tpl != null ? tpl.getTemplateId() : null)
                    .workflowTemplateName(tpl != null ? tpl.getTemplateName() : null)
                    .workflowStepCount(tpl != null ? stepCountByTpl.getOrDefault(tpl.getTemplateId(), 0) : 0)
                    .estimatedDurationMinutes(srvDur)
                    .allowSameVisit(!Boolean.FALSE.equals(s.getAllowSameVisit()))
                    .build());
        }
        return ServiceComboDTO.builder()
                .comboId(c.getComboId())
                .comboCode(c.getComboCode())
                .comboName(c.getComboName())
                .description(c.getDescription())
                .status(c.getStatus())
                .totalBasePrice(totalBasePrice)
                .totalDurationMinutes(totalDurationMinutes)
                .serviceCount(itemDTOs.size())
                .items(itemDTOs)
                .build();
    }

    /**
     * [Chức năng]: Đọc cấu hình trường dữ liệu tùy biến của toàn bộ 5 khung thao tác lâm sàng.
     * [Mục đích]: Truy vấn bảng `clinical_workflow_frame_fields` qua `JdbcTemplate` (hoặc bộ nhớ tạm khi test) và trả về map theo 5 `VALID_FRAME_KEYS`.
     */
    @Override
    public Map<String, List<WorkflowStepDTO.CustomFieldDTO>> getAllFrameCustomFields() {
        Map<String, List<WorkflowStepDTO.CustomFieldDTO>> result = new LinkedHashMap<>();
        for (String fk : VALID_FRAME_KEYS) {
            result.put(fk, new ArrayList<>());
        }
        if (jdbcTemplate == null) {
            inMemoryFrameFields.forEach((k, v) -> {
                if (result.containsKey(k) && v != null) {
                    result.put(k, new ArrayList<>(v));
                }
            });
            return result;
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT frame_key, custom_fields_json FROM clinical_workflow_frame_fields");
            for (Map<String, Object> row : rows) {
                Object keyObj = row.get("frame_key");
                Object jsonObj = row.get("custom_fields_json");
                if (keyObj != null && jsonObj != null) {
                    String fk = keyObj.toString().trim().toUpperCase(Locale.ROOT);
                    if (result.containsKey(fk)) {
                        result.put(fk, parseCustomFields(jsonObj.toString()));
                    }
                }
            }
        } catch (Exception ex) {
            log.debug("Bảng clinical_workflow_frame_fields chưa khởi tạo hoặc trống: {}", ex.getMessage());
        }
        return result;
    }

    /**
     * [Chức năng]: Lưu danh sách trường dữ liệu tùy biến mặc định cho một khung thao tác trong 5 khung chuẩn.
     * [Mục đích]: Chuẩn hóa `frameKey`, làm sạch danh sách trường qua `sanitizeFrameFields`, đảm bảo bảng `clinical_workflow_frame_fields` tồn tại và thực hiện upsert qua `JdbcTemplate` cùng cập nhật cache bộ nhớ.
     */
    @Override
    @Transactional
    public List<WorkflowStepDTO.CustomFieldDTO> saveFrameCustomFields(String frameKey,
            List<WorkflowStepDTO.CustomFieldDTO> fields) {
        String normalizedKey = normalizeFrameKey(frameKey);
        List<WorkflowStepDTO.CustomFieldDTO> sanitized = sanitizeFrameFields(normalizedKey, fields);
        if (jdbcTemplate == null) {
            inMemoryFrameFields.put(normalizedKey, sanitized);
            return sanitized;
        }
        ensureFrameConfigTable();
        String json = serializeCustomFields(sanitized);
        int updated = jdbcTemplate.update(
                "UPDATE clinical_workflow_frame_fields SET custom_fields_json = ? WHERE frame_key = ?",
                json, normalizedKey);
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO clinical_workflow_frame_fields (frame_key, custom_fields_json) VALUES (?, ?)",
                    normalizedKey, json);
        }
        inMemoryFrameFields.put(normalizedKey, sanitized);
        return sanitized;
    }

    /**
     * [Hàm hỗ trợ]: Tạo bảng `clinical_workflow_frame_fields` bằng câu lệnh DDL `CREATE TABLE IF NOT EXISTS` nếu chưa có trong cơ sở dữ liệu.
     */
    private void ensureFrameConfigTable() {
        if (jdbcTemplate == null)
            return;
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS clinical_workflow_frame_fields (
                    frame_key VARCHAR(40) NOT NULL PRIMARY KEY,
                    custom_fields_json TEXT NULL,
                    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                )
                """);
    }

    /**
     * [Hàm hỗ trợ]: Chuẩn hóa mã khung thao tác sang chữ hoa và xác thực phải thuộc tập 5 khung chuẩn `VALID_FRAME_KEYS`.
     */
    private String normalizeFrameKey(String frameKey) {
        String normalized = frameKey == null ? "" : frameKey.trim().toUpperCase(Locale.ROOT);
        if (!VALID_FRAME_KEYS.contains(normalized)) {
            throw new IllegalArgumentException("Khung thao tác không hợp lệ: " + frameKey);
        }
        return normalized;
    }

    /**
     * [Hàm hỗ trợ]: Làm sạch và chuẩn hóa danh sách trường tùy biến của khung (loại trường rỗng nhãn, giới hạn kiểu `text`/`textarea`/`select`, sinh ID duy nhất và lọc tùy chọn).
     */
    private List<WorkflowStepDTO.CustomFieldDTO> sanitizeFrameFields(String frameKey,
            List<WorkflowStepDTO.CustomFieldDTO> rawFields) {
        List<WorkflowStepDTO.CustomFieldDTO> cleaned = new ArrayList<>();
        if (rawFields == null)
            return cleaned;
        Set<String> seenIds = new HashSet<>();
        int seq = 1;
        for (WorkflowStepDTO.CustomFieldDTO f : rawFields) {
            if (f == null)
                continue;
            String label = f.getLabel() != null ? f.getLabel().trim() : "";
            if (label.isEmpty())
                continue;
            String rawType = f.getType() != null ? f.getType().trim().toLowerCase(Locale.ROOT) : "text";
            String type = ("textarea".equals(rawType) || "select".equals(rawType)) ? rawType : "text";
            String id = f.getId() != null ? f.getId().trim() : "";
            if (id.isEmpty() || !seenIds.add(id)) {
                do {
                    id = "fc-" + frameKey.toLowerCase(Locale.ROOT) + "-" + (seq++);
                } while (!seenIds.add(id));
            }
            List<String> opts = new ArrayList<>();
            if ("select".equals(type) && f.getOptions() != null) {
                for (String o : f.getOptions()) {
                    if (o != null && !o.trim().isEmpty()) {
                        opts.add(o.trim());
                    }
                }
            }
            String ph = "select".equals(type) ? "" : (f.getPlaceholder() != null ? f.getPlaceholder().trim() : "");
            cleaned.add(WorkflowStepDTO.CustomFieldDTO.builder()
                    .id(id)
                    .label(label)
                    .type(type)
                    .options(opts)
                    .placeholder(ph)
                    .build());
        }
        return cleaned;
    }

    /**
     * [Hàm hỗ trợ]: Gộp danh sách trường tùy biến riêng của bước với trường mặc định của khung thao tác (ưu tiên trường đã định nghĩa trên bước để bảo toàn ID dữ liệu lịch sử và khử trùng theo ID/nhãn).
     */
    private List<WorkflowStepDTO.CustomFieldDTO> mergeFrameCustomFields(
            List<WorkflowStepDTO.CustomFieldDTO> frameDefaults,
            List<WorkflowStepDTO.CustomFieldDTO> stepFields) {
        List<WorkflowStepDTO.CustomFieldDTO> merged = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        // Ưu tiên trường định nghĩa sẵn trên bước (bảo toàn ID cũ của lịch sử dữ liệu)
        // trước khi gộp khung mặc định
        if (stepFields != null) {
            for (WorkflowStepDTO.CustomFieldDTO f : stepFields) {
                if (f == null)
                    continue;
                merged.add(f);
                if (f.getId() != null)
                    seenKeys.add("id:" + f.getId().trim());
                if (f.getLabel() != null)
                    seenKeys.add("lbl:" + f.getLabel().trim().toLowerCase(Locale.ROOT));
            }
        }
        if (frameDefaults != null) {
            for (WorkflowStepDTO.CustomFieldDTO f : frameDefaults) {
                if (f == null)
                    continue;
                String idKey = f.getId() != null ? "id:" + f.getId().trim() : "";
                String lblKey = f.getLabel() != null ? "lbl:" + f.getLabel().trim().toLowerCase(Locale.ROOT) : "";
                if ((!idKey.isEmpty() && seenKeys.contains(idKey))
                        || (!lblKey.isEmpty() && seenKeys.contains(lblKey))) {
                    continue;
                }
                merged.add(f);
                if (!idKey.isEmpty())
                    seenKeys.add(idKey);
                if (!lblKey.isEmpty())
                    seenKeys.add(lblKey);
            }
        }
        return merged;
    }
}
