package com.cvdcms.service.impl;

import com.cvdcms.DTO.PerformedServiceRequestDTO;
import com.cvdcms.DTO.PerformedServiceResponseDTO;
import com.cvdcms.DTO.ServiceDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.PerformedServiceStatus;
import com.cvdcms.entity.enums.ServiceStatus;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.entity.enums.TreatmentPlanItemStatus;
import com.cvdcms.entity.enums.TreatmentPlanStageStatus;
import com.cvdcms.entity.enums.TreatmentPlanStatus;
import com.cvdcms.repository.TreatmentPlanItemRepository;
import com.cvdcms.repository.TreatmentPlanStageRepository;
import com.cvdcms.repository.TreatmentPlanRepository;
import com.cvdcms.repository.PerformedServiceRepository;
import com.cvdcms.repository.ServiceRepository;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.VisitRepository;
import com.cvdcms.service.PerformedServiceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PerformedServiceServiceImpl implements PerformedServiceService {

    private final PerformedServiceRepository performedServiceRepository;
    private final ServiceRepository serviceRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final VisitRepository visitRepository;
    private final TreatmentPlanItemRepository treatmentPlanItemRepository;
    private final TreatmentPlanStageRepository treatmentPlanStageRepository;
    private final TreatmentPlanRepository treatmentPlanRepository;
    private final com.cvdcms.repository.ClinicalWorkflowTemplateRepository workflowTemplateRepo;
    private final com.cvdcms.repository.ClinicalWorkflowVersionRepository workflowVersionRepo;
    private final com.cvdcms.repository.ClinicalWorkflowStepRepository workflowStepRepo;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.StaffBranchAssignmentRepository staffBranchAssignmentRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.DoctorCoverGrantRepository doctorCoverGrantRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.InvoiceItemRepository invoiceItemRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.EmrRecordRepository emrRecordRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.MaterialConsumptionRepository materialConsumptionRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.MaterialRepository materialRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.MaterialBatchRepository materialBatchRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.BranchInventoryRepository branchInventoryRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.service.ServiceCommissionService serviceCommissionService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.ServiceCommissionRepository serviceCommissionRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.ParaclinicalOrderItemRepository paraclinicalOrderItemRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.security.DoctorVisitAccess doctorVisitAccess;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.service.DoctorClinicalCapabilityService capabilityService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.WorkflowExecutionRepository workflowExecutionRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.WorkflowStepExecutionRepository workflowStepExecutionRepository;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    private jakarta.servlet.http.HttpSession currentHttpSession() {
        var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        return attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra
                ? sra.getRequest().getSession(false) : null;
    }

    private boolean isHttpRequestActive() {
        return org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes;
    }

    public void setServiceCommissionService(com.cvdcms.service.ServiceCommissionService serviceCommissionService) {
        this.serviceCommissionService = serviceCommissionService;
    }

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @Override
    @Transactional(readOnly = true)
    public List<ServiceDTO> getAllActiveServices() {
        return serviceRepository.findByStatusOrderByCategory_DisplayOrderAscServiceNameAsc(ServiceStatus.ACTIVE)
                .stream()
                .filter(s -> s.getCategory() == null || !Boolean.FALSE.equals(s.getCategory().getIsActive()))
                .map(this::mapToServiceDTO)
                .collect(Collectors.toList());
    }

    private boolean isDoctorProfile(StaffProfile s) {
        return s != null && ((s.getStaffCode() != null && s.getStaffCode().startsWith("BS"))
                || (s.getPositionTitle() != null && s.getPositionTitle().toLowerCase().contains("bác sĩ"))
                || (s.getDepartment() != null && s.getDepartment().toLowerCase().contains("bác sĩ")));
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffProfile> getAvailableDoctors() {
        return staffProfileRepository.findByStatus(StaffStatus.ACTIVE).stream()
                .filter(this::isDoctorProfile)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffProfile> getAvailableDoctorsByBranch(Long branchId) {
        if (branchId == null) {
            return getAvailableDoctors();
        }
        java.util.Set<Long> assignedStaffIds = new java.util.HashSet<>();
        if (staffBranchAssignmentRepository != null) {
            java.time.LocalDate today = java.time.LocalDate.now();
            for (StaffBranchAssignment a : staffBranchAssignmentRepository.findByBranchAndStatus(
                    branchId, com.cvdcms.entity.enums.AssignmentStatus.ACTIVE)) {
                boolean started = a.getStartDate() == null || !a.getStartDate().isAfter(today);
                boolean notEnded = a.getEndDate() == null || !a.getEndDate().isBefore(today);
                if (started && notEnded && a.getStaff() != null && a.getStaff().getStaffId() != null) {
                    assignedStaffIds.add(a.getStaff().getStaffId());
                }
            }
        }
        return getAvailableDoctors().stream()
                .filter(s -> assignedStaffIds.contains(s.getStaffId())
                        || (s.getUser() != null && s.getUser().getBranch() != null
                            && branchId.equals(s.getUser().getBranch().getBranchId())))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffProfile> getAvailableAssistants() {
        return staffProfileRepository.findByStatus(StaffStatus.ACTIVE).stream()
                .filter(s -> (s.getStaffCode() != null && s.getStaffCode().startsWith("PT"))
                        || (s.getPositionTitle() != null && (s.getPositionTitle().toLowerCase().contains("trợ thủ") || s.getPositionTitle().toLowerCase().contains("phụ tá") || s.getPositionTitle().toLowerCase().contains("điều dưỡng")))
                        || (s.getDepartment() != null && s.getDepartment().toLowerCase().contains("điều dưỡng")))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PerformedServiceResponseDTO> getPerformedServicesByVisitId(Long visitId) {
        return performedServiceRepository.findByVisitIdWithDetails(visitId).stream()
                .map(this::mapToResponseDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public PerformedServiceResponseDTO createPerformedService(PerformedServiceRequestDTO requestDTO) {
        log.info("Creating performed service for visitId={}, serviceId={}", requestDTO.getVisitId(), requestDTO.getServiceId());

        Visit visit = visitRepository.lockById(requestDTO.getVisitId())
                .or(() -> visitRepository.findById(requestDTO.getVisitId()))
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca khám với ID: " + requestDTO.getVisitId()));

        // Quy tắc BR-EMR-02: Khóa bệnh án lâm sàng bất biến khi chuyển thanh toán, hoàn tất, hủy hoặc EMR đã khóa
        com.cvdcms.util.EmrStatePolicy.assertMutableVisitAndEmr(
                visit,
                emrRecordRepository,
                "Bệnh án lâm sàng đã hoàn tất và khóa bất biến. Không thể thêm dịch vụ thực hiện");

        com.cvdcms.entity.Service service = serviceRepository.findById(requestDTO.getServiceId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dịch vụ với ID: " + requestDTO.getServiceId()));
        if (service.getStatus() != ServiceStatus.ACTIVE
                || (service.getCategory() != null && Boolean.FALSE.equals(service.getCategory().getIsActive()))) {
            throw new IllegalStateException("Dịch vụ hoặc nhóm dịch vụ đang ngừng hoạt động, không thể chỉ định thực hiện.");
        }

        if (doctorVisitAccess != null && isHttpRequestActive()) {
            jakarta.servlet.http.HttpSession session = currentHttpSession();
            com.cvdcms.entity.User currentUser = doctorVisitAccess.requireAuthenticatedUser(session);
            if (!doctorVisitAccess.isAdmin(currentUser)) {
                StaffProfile callerDoctor = staffProfileRepository.findByUser_UserId(currentUser.getUserId()).orElse(null);
                if (callerDoctor != null && requestDTO.getDoctorId() != null
                        && !requestDTO.getDoctorId().equals(callerDoctor.getStaffId())) {
                    throw new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.FORBIDDEN,
                            "Chỉ quản trị viên mới được chỉ định bác sĩ thực hiện khác.");
                }
            }
        }

        StaffProfile doctor = staffProfileRepository.findById(requestDTO.getDoctorId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bác sĩ với ID: " + requestDTO.getDoctorId()));
        if (doctor.getStatus() != null && doctor.getStatus() != StaffStatus.ACTIVE) {
            throw new IllegalArgumentException("Bác sĩ thực hiện hiện không ở trạng thái hoạt động.");
        }
        validateDoctorForVisit(doctor, visit);
        if (capabilityService != null && !capabilityService.isParaclinicalOrLabService(service.getServiceId())) {
            Long branchId = visit.getBranch() != null ? visit.getBranch().getBranchId() : null;
            var capCheck = capabilityService.checkDoctorAndRoomCapability(
                    branchId, doctor.getStaffId(), visit.getRoomId(), service.getServiceId());
            if (!capCheck.allowed()) {
                throw new IllegalStateException(capCheck.message());
            }
        }

        StaffProfile assistant = null;
        if (requestDTO.getAssistantId() != null) {
            assistant = staffProfileRepository.findById(requestDTO.getAssistantId()).orElse(null);
        }

        TreatmentPlanItem linkedPlanItem = null;
        TreatmentPlan linkedPlan = null;
        if (requestDTO.getPlanItemId() != null) {
            linkedPlanItem = treatmentPlanItemRepository.findById(requestDTO.getPlanItemId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bước thủ thuật trong kế hoạch điều trị với ID: " + requestDTO.getPlanItemId()));
            linkedPlan = linkedPlanItem.getPlan() != null
                    ? linkedPlanItem.getPlan()
                    : (linkedPlanItem.getStage() != null ? linkedPlanItem.getStage().getPlan() : null);
            if (linkedPlan != null && linkedPlan.getPlanId() != null) {
                treatmentPlanRepository.findByIdForUpdate(linkedPlan.getPlanId());
            }
            if (entityManager != null && entityManager.contains(linkedPlanItem)) {
                entityManager.lock(linkedPlanItem, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            }
            if (linkedPlan != null && linkedPlan.getStatus() == TreatmentPlanStatus.CANCELLED) {
                throw new IllegalStateException("Kế hoạch điều trị đã bị hủy, không thể ghi nhận thực hiện.");
            }
            if (linkedPlanItem.getStatus() == TreatmentPlanItemStatus.CANCELLED) {
                throw new IllegalStateException("Bước thủ thuật trong kế hoạch đã bị hủy, không thể thực hiện.");
            }
            if (linkedPlanItem.getStatus() == TreatmentPlanItemStatus.COMPLETED) {
                throw new IllegalStateException("Bước thủ thuật này đã ở trạng thái Đã thực hiện, không thể thực hiện lại.");
            }
            if (linkedPlan != null && linkedPlan.getPatientProfile() != null && visit.getPatientProfile() != null
                    && !linkedPlan.getPatientProfile().getProfileId().equals(visit.getPatientProfile().getProfileId())) {
                throw new IllegalArgumentException("Bước kế hoạch điều trị không khớp với hồ sơ bệnh nhân của ca khám.");
            }
            if (performedServiceRepository.existsByPlanItem_PlanItemIdAndStatusNot(
                    linkedPlanItem.getPlanItemId(), PerformedServiceStatus.CANCELLED)) {
                throw new IllegalStateException("Bước phác đồ này đã được ghi nhận dịch vụ thực hiện, không thể tạo trùng lặp.");
            }
        }

        BigDecimal qty = requestDTO.getQuantity() != null && requestDTO.getQuantity().compareTo(BigDecimal.ZERO) > 0
                ? requestDTO.getQuantity() : BigDecimal.ONE;

        BigDecimal unitPrice;
        if (linkedPlanItem != null) {
            boolean isInstallment = linkedPlan != null && Boolean.TRUE.equals(linkedPlan.getIsInstallment());
            if (isInstallment) {
                unitPrice = BigDecimal.ZERO;
            } else if (linkedPlanItem.getService() != null && linkedPlanItem.getEstimatedPrice() != null) {
                unitPrice = linkedPlanItem.getEstimatedPrice();
            } else if (linkedPlanItem.getEstimatedPrice() != null && linkedPlanItem.getEstimatedPrice().compareTo(BigDecimal.ZERO) > 0) {
                unitPrice = linkedPlanItem.getEstimatedPrice();
            } else {
                unitPrice = service.getBasePrice() != null ? service.getBasePrice() : BigDecimal.ZERO;
            }
        } else {
            BigDecimal basePrice = service.getBasePrice() != null ? service.getBasePrice() : BigDecimal.ZERO;
            if (requestDTO.getUnitPrice() != null) {
                if (requestDTO.getUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
                    throw new IllegalArgumentException("Đơn giá dịch vụ không được nhỏ hơn 0.");
                }
                if (requestDTO.getUnitPrice().compareTo(BigDecimal.ZERO) == 0
                        && basePrice.compareTo(BigDecimal.ZERO) > 0) {
                    throw new IllegalArgumentException("Đơn giá dịch vụ có giá niêm yết phải lớn hơn 0.");
                }
                unitPrice = requestDTO.getUnitPrice();
            } else {
                unitPrice = basePrice;
            }
        }

        BigDecimal lineTotal = unitPrice.multiply(qty);
        BigDecimal discount = requestDTO.getDiscountAmount() != null ? requestDTO.getDiscountAmount() : BigDecimal.ZERO;
        if (discount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Giảm giá / chiết khấu không được nhỏ hơn 0.");
        }
        if (discount.compareTo(lineTotal) > 0) {
            throw new IllegalArgumentException("Giảm giá / chiết khấu không được vượt quá thành tiền.");
        }

        // Luôn đồng bộ thành tiền theo công thức backend (unitPrice * qty - discount) khớp InvoiceServiceImpl
        BigDecimal finalPrice = lineTotal.subtract(discount);

        PerformedService ps = new PerformedService();
        ps.setVisit(visit);
        ps.setPatientProfile(visit.getPatientProfile());
        ps.setBranch(visit.getBranch());
        ps.setService(service);

        String stepName = requestDTO.getStepName();
        if (stepName == null && linkedPlanItem != null) {
            stepName = linkedPlanItem.getStepName() != null
                    ? linkedPlanItem.getStepName()
                    : (linkedPlanItem.getService() != null ? linkedPlanItem.getService().getServiceName() : null);
        }
        if (stepName == null && service != null) {
            stepName = service.getServiceName();
        }
        ps.setStepName(stepName);

        ps.setToothNumbers(requestDTO.getToothNumbers());
        ps.setQuantity(qty);
        ps.setUnitPrice(unitPrice);
        ps.setDiscountAmount(discount);
        ps.setFinalPrice(finalPrice);
        ps.setDoctor(doctor);
        ps.setAssistant(assistant);
        ps.setCompletedAt(LocalDateTime.now());
        ps.setStatus(PerformedServiceStatus.COMPLETED); // Ghi nhận hoàn thành để bàn giao Người 4 lập hóa đơn
        ps.setClinicalNotes(requestDTO.getClinicalNotes());

        if (linkedPlanItem != null) {
            ps.setPlanItem(linkedPlanItem);
            linkedPlanItem.setStatus(TreatmentPlanItemStatus.COMPLETED);
            linkedPlanItem.setCompletedAt(LocalDateTime.now());
            treatmentPlanItemRepository.save(linkedPlanItem);

            // Tự động cập nhật tiến độ giai đoạn và kế hoạch
            TreatmentPlanStage stage = linkedPlanItem.getStage();
            if (stage != null) {
                List<TreatmentPlanItem> stageItems = stage.getItems();
                boolean allDone = stageItems != null && !stageItems.isEmpty() &&
                        stageItems.stream().allMatch(i -> i.getStatus() == TreatmentPlanItemStatus.COMPLETED);
                if (allDone) {
                    stage.setStatus(TreatmentPlanStageStatus.COMPLETED);
                    stage.setCompletedAt(LocalDateTime.now());
                } else {
                    stage.setStatus(TreatmentPlanStageStatus.IN_PROGRESS);
                }
                treatmentPlanStageRepository.save(stage);

                TreatmentPlan plan = stage.getPlan();
                if (plan != null) {
                    List<TreatmentPlanStage> allStages = plan.getStages();
                    boolean allStagesDone = allStages != null && !allStages.isEmpty() &&
                            allStages.stream().allMatch(s -> s.getStatus() == TreatmentPlanStageStatus.COMPLETED);
                    if (allStagesDone) {
                        plan.setStatus(TreatmentPlanStatus.COMPLETED);
                    } else {
                        plan.setStatus(TreatmentPlanStatus.IN_PROGRESS);
                    }
                    treatmentPlanRepository.save(plan);
                }
            }
            syncWorkflowExecutionsForPlanItem(linkedPlanItem, true, doctor, ps.getCompletedAt());
        }

        PerformedService saved = performedServiceRepository.save(ps);
        recordMaterialConsumptions(saved, doctor, requestDTO.getConsumedMaterials());
        log.info("Performed service created successfully with id={}", saved.getPerformedId());

        if (serviceCommissionService != null) {
            serviceCommissionService.calculateCommissionsForPerformedService(saved);
        }

        return mapToResponseDTO(saved);
    }

    private void syncWorkflowExecutionsForPlanItem(
            TreatmentPlanItem targetItem,
            boolean markCompleted,
            StaffProfile actor,
            LocalDateTime doneAt) {
        if (targetItem == null || workflowExecutionRepository == null || workflowStepExecutionRepository == null) {
            return;
        }
        java.util.Map<Long, WorkflowExecution> matchedExecs = new java.util.LinkedHashMap<>();
        if (targetItem.getPlanItemId() != null) {
            for (WorkflowExecution ex : workflowExecutionRepository.findByPlanItem_PlanItemId(targetItem.getPlanItemId())) {
                if (ex.getExecutionId() != null) {
                    matchedExecs.put(ex.getExecutionId(), ex);
                }
            }
        }
        TreatmentPlanStage stage = targetItem.getStage();
        Long targetSvcId = targetItem.getService() != null ? targetItem.getService().getServiceId() : null;
        if (stage != null && stage.getStageId() != null) {
            for (WorkflowExecution ex : workflowExecutionRepository.findByPlanStage_StageId(stage.getStageId())) {
                if (ex.getExecutionId() == null) continue;
                Long exSvcId = (ex.getVersion() != null && ex.getVersion().getTemplate() != null && ex.getVersion().getTemplate().getService() != null)
                        ? ex.getVersion().getTemplate().getService().getServiceId()
                        : null;
                if (targetSvcId == null || exSvcId == null || java.util.Objects.equals(targetSvcId, exSvcId)) {
                    matchedExecs.put(ex.getExecutionId(), ex);
                }
            }
        }
        if (matchedExecs.isEmpty()) {
            return;
        }

        LocalDateTime effectiveTime = doneAt != null ? doneAt : LocalDateTime.now();
        for (WorkflowExecution ex : matchedExecs.values()) {
            if (ex.getStatus() == com.cvdcms.entity.enums.WorkflowExecutionStatus.CANCELLED) {
                continue;
            }
            TreatmentPlanStage exStage = ex.getPlanStage() != null ? ex.getPlanStage() : stage;
            Long exSvcId = (ex.getVersion() != null && ex.getVersion().getTemplate() != null && ex.getVersion().getTemplate().getService() != null)
                    ? ex.getVersion().getTemplate().getService().getServiceId()
                    : targetSvcId;
            List<TreatmentPlanItem> stageItems = new ArrayList<>();
            if (exStage != null && exStage.getStageId() != null) {
                for (TreatmentPlanItem it : treatmentPlanItemRepository.findByStage_StageId(exStage.getStageId())) {
                    if (it.getStatus() == TreatmentPlanItemStatus.CANCELLED) continue;
                    if (exSvcId == null || (it.getService() != null && java.util.Objects.equals(exSvcId, it.getService().getServiceId()))) {
                        stageItems.add(it);
                    }
                }
                stageItems.sort(java.util.Comparator.comparing(TreatmentPlanItem::getPlanItemId));
            } else {
                stageItems.add(targetItem);
            }

            List<WorkflowStepExecution> stepExecs = new ArrayList<>(
                    workflowStepExecutionRepository.findByExecution_ExecutionIdOrderByStep_SequenceOrderAsc(ex.getExecutionId()));
            if (stepExecs.isEmpty()) continue;
            stepExecs.sort(java.util.Comparator.comparingInt(se -> se.getStepOrder() != null
                    ? se.getStepOrder()
                    : (se.getStep() != null && se.getStep().getSequenceOrder() != null ? se.getStep().getSequenceOrder() : 999)));
            List<WorkflowStepExecution> templateSteps = stepExecs.stream()
                    .filter(se -> se.getStep() != null && se.getStep().getAdhocExecutionId() == null)
                    .toList();

            java.util.Map<Long, TreatmentPlanItem> matchedByStepExecId = new java.util.LinkedHashMap<>();
            if (stageItems.size() == 1) {
                TreatmentPlanItem single = stageItems.get(0);
                if (single.getStatus() == TreatmentPlanItemStatus.COMPLETED || templateSteps.size() == 1 || !markCompleted) {
                    for (WorkflowStepExecution se : templateSteps) {
                        if (se.getStepExecutionId() != null) matchedByStepExecId.put(se.getStepExecutionId(), single);
                    }
                }
            } else {
                boolean[] usedStep = new boolean[templateSteps.size()];
                java.util.Set<Integer> usedItemIdx = new java.util.HashSet<>();
                for (int i = 0; i < stageItems.size() && i < templateSteps.size(); i++) {
                    String in = normalizeStepNamePs(stageItems.get(i).getStepName());
                    String sn = templateSteps.get(i).getStep() != null ? normalizeStepNamePs(templateSteps.get(i).getStep().getStepName()) : "";
                    if (!in.isEmpty() && in.equals(sn)) {
                        if (templateSteps.get(i).getStepExecutionId() != null) matchedByStepExecId.put(templateSteps.get(i).getStepExecutionId(), stageItems.get(i));
                        usedStep[i] = true;
                        usedItemIdx.add(i);
                    }
                }
                for (int i = 0; i < stageItems.size(); i++) {
                    if (usedItemIdx.contains(i)) continue;
                    String in = normalizeStepNamePs(stageItems.get(i).getStepName());
                    if (in.isEmpty()) continue;
                    for (int j = 0; j < templateSteps.size(); j++) {
                        if (usedStep[j]) continue;
                        String sn = templateSteps.get(j).getStep() != null ? normalizeStepNamePs(templateSteps.get(j).getStep().getStepName()) : "";
                        if (in.equals(sn)) {
                            if (templateSteps.get(j).getStepExecutionId() != null) matchedByStepExecId.put(templateSteps.get(j).getStepExecutionId(), stageItems.get(i));
                            usedStep[j] = true;
                            usedItemIdx.add(i);
                            break;
                        }
                    }
                }
                for (int i = 0; i < stageItems.size() && i < templateSteps.size(); i++) {
                    if (!usedItemIdx.contains(i) && !usedStep[i]) {
                        if (templateSteps.get(i).getStepExecutionId() != null) matchedByStepExecId.put(templateSteps.get(i).getStepExecutionId(), stageItems.get(i));
                        usedStep[i] = true;
                        usedItemIdx.add(i);
                    }
                }
            }

            boolean changed = false;
            for (WorkflowStepExecution se : templateSteps) {
                TreatmentPlanItem mi = se.getStepExecutionId() != null ? matchedByStepExecId.get(se.getStepExecutionId()) : null;
                if (mi != null && mi.getStatus() == TreatmentPlanItemStatus.COMPLETED) {
                    if (se.getStatus() != com.cvdcms.entity.enums.WorkflowStepExecutionStatus.COMPLETED
                            && se.getStatus() != com.cvdcms.entity.enums.WorkflowStepExecutionStatus.SKIPPED) {
                        se.setStatus(com.cvdcms.entity.enums.WorkflowStepExecutionStatus.COMPLETED);
                        if (se.getStartedAt() == null) se.setStartedAt(effectiveTime);
                        se.setCompletedAt(effectiveTime);
                        if (se.getAssignedStaff() == null && actor != null) se.setAssignedStaff(actor);
                        if (se.getResultNote() == null || se.getResultNote().isBlank()) {
                            se.setResultNote("Đã hoàn thành nhanh từ Kế hoạch điều trị");
                        }
                        changed = true;
                    }
                } else if (!markCompleted && mi != null && targetItem.getPlanItemId() != null
                        && java.util.Objects.equals(mi.getPlanItemId(), targetItem.getPlanItemId())
                        && se.getStatus() == com.cvdcms.entity.enums.WorkflowStepExecutionStatus.COMPLETED) {
                    se.setStatus(com.cvdcms.entity.enums.WorkflowStepExecutionStatus.PENDING);
                    se.setStartedAt(null);
                    se.setCompletedAt(null);
                    se.setApprovedBy(null);
                    se.setApprovedAt(null);
                    changed = true;
                }
            }
            if (changed) {
                workflowStepExecutionRepository.saveAll(stepExecs);
            }

            WorkflowStepExecution firstOpen = stepExecs.stream()
                    .filter(se -> se.getStatus() != com.cvdcms.entity.enums.WorkflowStepExecutionStatus.COMPLETED
                            && se.getStatus() != com.cvdcms.entity.enums.WorkflowStepExecutionStatus.SKIPPED)
                    .findFirst().orElse(null);
            if (firstOpen == null) {
                ex.setStatus(com.cvdcms.entity.enums.WorkflowExecutionStatus.COMPLETED);
                ex.setCurrentStep(null);
                if (ex.getCompletedAt() == null) ex.setCompletedAt(effectiveTime);
            } else {
                ex.setStatus(firstOpen.getStatus() == com.cvdcms.entity.enums.WorkflowStepExecutionStatus.PAUSED
                        ? com.cvdcms.entity.enums.WorkflowExecutionStatus.PAUSED
                        : com.cvdcms.entity.enums.WorkflowExecutionStatus.IN_PROGRESS);
                ex.setCompletedAt(null);
                ex.setCurrentStep(firstOpen.getStep());
                TreatmentPlanItem nextOpenItem = stageItems.stream()
                        .filter(it -> it.getStatus() != TreatmentPlanItemStatus.COMPLETED)
                        .findFirst().orElse(null);
                if (nextOpenItem != null) ex.setPlanItem(nextOpenItem);
            }
            workflowExecutionRepository.save(ex);
        }
    }

    private String normalizeStepNamePs(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('đ', 'd').replace('Đ', 'd')
                .trim().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    @Transactional
    public void cancelPerformedService(Long performedId) {
        log.info("Cancelling/Deleting performed service with id={}", performedId);
        PerformedService ps = performedServiceRepository.findByIdForUpdate(performedId)
                .or(() -> performedServiceRepository.findById(performedId))
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dịch vụ thực hiện với ID: " + performedId));

        if (ps.getStatus() == PerformedServiceStatus.CANCELLED) {
            return;
        }

        // Quy tắc BR-EMR-02: Khóa bất biến theo VisitStatus & trạng thái EMR
        com.cvdcms.util.EmrStatePolicy.assertMutableVisitAndEmr(
                ps.getVisit(),
                emrRecordRepository,
                "Bệnh án lâm sàng đã khóa bất biến. Không thể xóa hoặc hủy dịch vụ đã thực hiện");

        // Chặn xóa dịch vụ đã được Kế toán xuất hóa đơn còn hiệu lực
        if (invoiceItemRepository != null && invoiceItemRepository.existsByPerformedIdAndInvoiceNotCancelled(performedId)) {
            throw new IllegalStateException("Dịch vụ thực hiện đã được Kế toán xuất hóa đơn còn hiệu lực, không thể hủy hoặc xóa.");
        }

        // Hoàn lại vật tư tiêu hao lâm sàng (nếu có) trước khi hủy
        revertMaterialConsumptions(ps);

        // Nếu dịch vụ này gắn với bước trong Kế hoạch điều trị (FT15), hoàn tác lại trạng thái nếu không còn dịch vụ khác thực hiện
        if (ps.getPlanItem() != null) {
            TreatmentPlanItem planItem = ps.getPlanItem();
            boolean hasOtherActive = performedServiceRepository.findByPlanItem_PlanItemIdAndStatusNot(
                    planItem.getPlanItemId(), PerformedServiceStatus.CANCELLED)
                    .stream()
                    .anyMatch(other -> other.getPerformedId() != null && !other.getPerformedId().equals(performedId));
            if (!hasOtherActive) {
                planItem.setStatus(TreatmentPlanItemStatus.PLANNED);
                planItem.setCompletedAt(null);
                treatmentPlanItemRepository.save(planItem);

                TreatmentPlanStage stage = planItem.getStage();
                if (stage != null) {
                    List<TreatmentPlanItem> stageItems = stage.getItems();
                    boolean anyDone = stageItems != null && stageItems.stream().anyMatch(i ->
                            i.getStatus() == TreatmentPlanItemStatus.COMPLETED || i.getStatus() == TreatmentPlanItemStatus.IN_PROGRESS);
                    if (anyDone) {
                        stage.setStatus(TreatmentPlanStageStatus.IN_PROGRESS);
                    } else {
                        stage.setStatus(TreatmentPlanStageStatus.PENDING);
                        stage.setCompletedAt(null);
                    }
                    treatmentPlanStageRepository.save(stage);

                    TreatmentPlan plan = stage.getPlan();
                    if (plan != null) {
                        List<TreatmentPlanStage> allStages = plan.getStages();
                        boolean anyStageDone = allStages != null && allStages.stream().anyMatch(s ->
                                s.getStatus() == TreatmentPlanStageStatus.COMPLETED || s.getStatus() == TreatmentPlanStageStatus.IN_PROGRESS);
                        if (anyStageDone) {
                            plan.setStatus(TreatmentPlanStatus.IN_PROGRESS);
                        } else {
                            plan.setStatus(TreatmentPlanStatus.ACCEPTED);
                        }
                        treatmentPlanRepository.save(plan);
                    }
                }
                syncWorkflowExecutionsForPlanItem(planItem, false, null, null);
            }
        }

        boolean hasAnyInvoiceHistory = invoiceItemRepository != null
                && invoiceItemRepository.existsByPerformedService_PerformedId(performedId);
        boolean hasParaclinicalLink = paraclinicalOrderItemRepository != null
                && paraclinicalOrderItemRepository.existsByPerformedService_PerformedId(performedId);
        boolean hasAnyCommissionHistory = serviceCommissionRepository != null
                && !serviceCommissionRepository.findByPerformedService_PerformedId(performedId).isEmpty();

        if (serviceCommissionService != null) {
            serviceCommissionService.cancelPendingCommissionsForPerformedService(ps.getPerformedId());
        }

        // Gỡ liên kết và hoàn tác trạng thái chỉ định cận lâm sàng nếu có để tránh kẹt COMPLETED không lập được hóa đơn
        if (paraclinicalOrderItemRepository != null && ps.getVisit() != null && ps.getVisit().getVisitId() != null) {
            List<ParaclinicalOrderItem> visitOrderItems = paraclinicalOrderItemRepository
                    .findByOrder_Visit_VisitIdOrderByOrder_OrderIdDescOrderItemIdDesc(ps.getVisit().getVisitId());
            if (visitOrderItems != null) {
                for (ParaclinicalOrderItem orderItem : visitOrderItems) {
                    if (orderItem.getPerformedService() != null
                            && performedId.equals(orderItem.getPerformedService().getPerformedId())) {
                        ParaclinicalOrderItem lockedItem = paraclinicalOrderItemRepository
                                .findByIdForUpdate(orderItem.getOrderItemId()).orElse(orderItem);
                        lockedItem.setPerformedService(null);
                        if (lockedItem.getStatus() == com.cvdcms.entity.enums.ParaclinicalOrderItemStatus.COMPLETED) {
                            lockedItem.setStatus(com.cvdcms.entity.enums.ParaclinicalOrderItemStatus.WAITING);
                            lockedItem.setPerformedAt(null);
                        }
                        paraclinicalOrderItemRepository.save(lockedItem);
                    }
                }
            }
        }

        boolean hasRemainingCommission = serviceCommissionRepository != null
                && !serviceCommissionRepository.findByPerformedService_PerformedId(performedId).isEmpty();

        if (hasAnyInvoiceHistory || hasParaclinicalLink || hasAnyCommissionHistory || hasRemainingCommission) {
            ps.setStatus(PerformedServiceStatus.CANCELLED);
            performedServiceRepository.save(ps);
        } else {
            performedServiceRepository.delete(ps);
        }
    }

    private void recordMaterialConsumptions(
            PerformedService saved,
            StaffProfile doctor,
            List<PerformedServiceRequestDTO.MaterialUsageDTO> usages) {
        if (usages == null || usages.isEmpty()
                || materialConsumptionRepository == null
                || materialRepository == null
                || materialBatchRepository == null
                || branchInventoryRepository == null
                || saved.getBranch() == null) {
            return;
        }
        // Gộp & khử trùng theo materialId theo thứ tự xác định để tránh vi phạm uk_mc_dedup và deadlock
        java.util.Map<Long, BigDecimal> aggregatedUsages = new java.util.TreeMap<>();
        for (PerformedServiceRequestDTO.MaterialUsageDTO usage : usages) {
            if (usage == null || usage.getMaterialId() == null
                    || usage.getQuantity() == null || usage.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            aggregatedUsages.merge(usage.getMaterialId(), usage.getQuantity(), BigDecimal::add);
        }
        if (aggregatedUsages.isEmpty()) {
            return;
        }
        Long branchId = saved.getBranch().getBranchId();
        java.time.LocalDate today = java.time.LocalDate.now();
        for (java.util.Map.Entry<Long, BigDecimal> entry : aggregatedUsages.entrySet()) {
            Long materialId = entry.getKey();
            BigDecimal requiredQty = entry.getValue();
            Material material = materialRepository.findById(materialId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy vật tư ID: " + materialId));
            BranchInventory inventory = branchInventoryRepository.findForUpdate(branchId, material.getMaterialId())
                    .orElseThrow(() -> new IllegalStateException("Kho chi nhánh chưa có tồn kho vật tư: " + material.getMaterialName()));
            BigDecimal currentStock = inventory.getCurrentStock() != null ? inventory.getCurrentStock() : BigDecimal.ZERO;
            if (currentStock.compareTo(requiredQty) < 0) {
                throw new IllegalStateException("Kho chi nhánh không đủ vật tư tiêu hao '" + material.getMaterialName() + "' (Tồn: " + currentStock + ", Cần: " + requiredQty + ").");
            }
            List<MaterialBatch> batches = materialBatchRepository.findAvailableBatchesFefoForUpdate(branchId, material.getMaterialId(), today);
            BigDecimal totalBatchQty = batches.stream()
                    .map(b -> b.getQuantityRemaining() != null ? b.getQuantityRemaining() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (totalBatchQty.compareTo(requiredQty) < 0) {
                throw new IllegalStateException("Các lô còn hạn của vật tư '" + material.getMaterialName() + "' không đủ để trừ tiêu hao.");
            }
            BigDecimal remainingToConsume = requiredQty;
            for (MaterialBatch batch : batches) {
                if (remainingToConsume.compareTo(BigDecimal.ZERO) <= 0) break;
                BigDecimal batchQty = batch.getQuantityRemaining() != null ? batch.getQuantityRemaining() : BigDecimal.ZERO;
                if (batchQty.compareTo(BigDecimal.ZERO) <= 0) continue;
                BigDecimal take = batchQty.min(remainingToConsume);
                BigDecimal nextQty = batchQty.subtract(take);
                batch.setQuantityRemaining(nextQty);
                if (nextQty.compareTo(BigDecimal.ZERO) <= 0) {
                    batch.setStatus(com.cvdcms.entity.enums.BatchStatus.OUT_OF_STOCK);
                }
                materialBatchRepository.save(batch);

                MaterialConsumption mc = new MaterialConsumption();
                mc.setVisit(saved.getVisit());
                mc.setPerformedService(saved);
                mc.setBranch(saved.getBranch());
                mc.setMaterial(material);
                mc.setBatch(batch);
                mc.setQuantity(take);
                mc.setConsumedBy(doctor);
                materialConsumptionRepository.save(mc);

                remainingToConsume = remainingToConsume.subtract(take);
            }
            inventory.setCurrentStock(currentStock.subtract(requiredQty));
            branchInventoryRepository.save(inventory);
        }
    }

    private void revertMaterialConsumptions(PerformedService ps) {
        if (materialConsumptionRepository == null || ps.getPerformedId() == null) {
            return;
        }
        List<MaterialConsumption> consumptions = materialConsumptionRepository.findByPerformedService_PerformedId(ps.getPerformedId());
        if (consumptions == null || consumptions.isEmpty()) {
            return;
        }
        java.time.LocalDate today = java.time.LocalDate.now();
        // Khóa theo đúng thứ tự BranchInventory TRƯỚC rồi mới đến MaterialBatch (khớp recordMaterialConsumptions) để tránh deadlock
        java.util.List<MaterialConsumption> sorted = new ArrayList<>(consumptions);
        sorted.sort(java.util.Comparator
                .comparing((MaterialConsumption mc) -> mc.getBranch() != null && mc.getBranch().getBranchId() != null ? mc.getBranch().getBranchId() : Long.MAX_VALUE)
                .thenComparing(mc -> mc.getMaterial() != null && mc.getMaterial().getMaterialId() != null ? mc.getMaterial().getMaterialId() : Long.MAX_VALUE)
                .thenComparing(mc -> mc.getBatch() != null && mc.getBatch().getBatchId() != null ? mc.getBatch().getBatchId() : Long.MAX_VALUE));

        if (branchInventoryRepository != null) {
            java.util.Map<String, BigDecimal> invRestores = new java.util.LinkedHashMap<>();
            java.util.Map<String, Long[]> invKeys = new java.util.LinkedHashMap<>();
            for (MaterialConsumption mc : sorted) {
                BigDecimal qty = mc.getQuantity() != null ? mc.getQuantity() : BigDecimal.ZERO;
                if (qty.compareTo(BigDecimal.ZERO) > 0 && mc.getBranch() != null && mc.getMaterial() != null
                        && mc.getBranch().getBranchId() != null && mc.getMaterial().getMaterialId() != null) {
                    String key = mc.getBranch().getBranchId() + ":" + mc.getMaterial().getMaterialId();
                    invRestores.merge(key, qty, BigDecimal::add);
                    invKeys.putIfAbsent(key, new Long[]{mc.getBranch().getBranchId(), mc.getMaterial().getMaterialId()});
                }
            }
            for (java.util.Map.Entry<String, BigDecimal> entry : invRestores.entrySet()) {
                Long[] ids = invKeys.get(entry.getKey());
                BigDecimal restoreQty = entry.getValue();
                branchInventoryRepository.findForUpdate(ids[0], ids[1])
                        .ifPresent(inv -> {
                            BigDecimal cur = inv.getCurrentStock() != null ? inv.getCurrentStock() : BigDecimal.ZERO;
                            inv.setCurrentStock(cur.add(restoreQty));
                            branchInventoryRepository.save(inv);
                        });
            }
        }

        if (materialBatchRepository != null) {
            for (MaterialConsumption mc : sorted) {
                BigDecimal qty = mc.getQuantity() != null ? mc.getQuantity() : BigDecimal.ZERO;
                if (qty.compareTo(BigDecimal.ZERO) > 0 && mc.getBatch() != null) {
                    MaterialBatch batch = materialBatchRepository.findByIdForUpdate(mc.getBatch().getBatchId()).orElse(mc.getBatch());
                    BigDecimal curBatch = batch.getQuantityRemaining() != null ? batch.getQuantityRemaining() : BigDecimal.ZERO;
                    batch.setQuantityRemaining(curBatch.add(qty));
                    if (batch.getExpiryDate() != null && batch.getExpiryDate().isBefore(today)) {
                        batch.setStatus(com.cvdcms.entity.enums.BatchStatus.EXPIRED);
                    } else {
                        batch.setStatus(com.cvdcms.entity.enums.BatchStatus.ACTIVE);
                    }
                    materialBatchRepository.save(batch);
                }
            }
        }

        materialConsumptionRepository.deleteAll(consumptions);
    }

    private ServiceDTO mapToServiceDTO(com.cvdcms.entity.Service s) {
        return ServiceDTO.builder()
                .serviceId(s.getServiceId())
                .categoryId(s.getCategory() != null ? s.getCategory().getCategoryId() : null)
                .categoryName(s.getCategory() != null ? s.getCategory().getCategoryName() : "Khác")
                .serviceCode(s.getServiceCode())
                .serviceName(s.getServiceName())
                .serviceType(s.getServiceType() != null ? s.getServiceType().name() : "PROCEDURE")
                .unit(s.getUnit())
                .basePrice(s.getBasePrice())
                .requiresTooth(s.getRequiresTooth())
                .isParaclinical(s.getIsParaclinical())
                .description(s.getDescription())
                .build();
    }

    private PerformedServiceResponseDTO mapToResponseDTO(PerformedService ps) {
        String statusDisplay = ps.getStatus() == PerformedServiceStatus.COMPLETED ? "Đã hoàn thành" : "Đã hủy";
        String timeStr = ps.getCompletedAt() != null ? ps.getCompletedAt().format(FORMATTER) : "";

        Boolean hasWorkflow = false;
        Long wfTemplateId = null;
        Integer wfStepCount = 0;
        String wfTemplateName = null;

        if (ps.getService() != null && ps.getService().getServiceId() != null) {
            Optional<com.cvdcms.entity.ClinicalWorkflowTemplate> optTpl = workflowTemplateRepo.findFirstByService_ServiceIdAndStatus(
                    ps.getService().getServiceId(), com.cvdcms.entity.enums.WorkflowTemplateStatus.ACTIVE);
            if (optTpl.isEmpty()) {
                List<com.cvdcms.entity.ClinicalWorkflowTemplate> all = workflowTemplateRepo.findByService_ServiceId(ps.getService().getServiceId());
                if (!all.isEmpty()) optTpl = Optional.of(all.get(0));
            }

            if (optTpl.isPresent()) {
                hasWorkflow = true;
                com.cvdcms.entity.ClinicalWorkflowTemplate tpl = optTpl.get();
                wfTemplateId = tpl.getTemplateId();
                wfTemplateName = tpl.getTemplateName();
                com.cvdcms.entity.ClinicalWorkflowVersion v = workflowVersionRepo.findFirstByTemplate_TemplateIdOrderByVersionNumberDesc(tpl.getTemplateId()).orElse(null);
                if (v != null) {
                    wfStepCount = workflowStepRepo.findByVersion_VersionIdOrderBySequenceOrderAsc(v.getVersionId()).size();
                }
            }
        }

        Long planItemId = null;
        String planName = null;
        String stageName = null;
        if (ps.getPlanItem() != null) {
            planItemId = ps.getPlanItem().getPlanItemId();
            if (ps.getPlanItem().getPlan() != null) {
                planName = ps.getPlanItem().getPlan().getPlanName();
            }
            if (ps.getPlanItem().getStage() != null) {
                stageName = ps.getPlanItem().getStage().getStageName();
            }
        }

        return PerformedServiceResponseDTO.builder()
                .performedId(ps.getPerformedId())
                .visitId(ps.getVisit() != null ? ps.getVisit().getVisitId() : null)
                .profileId(ps.getPatientProfile() != null ? ps.getPatientProfile().getProfileId() : null)
                .patientName(ps.getPatientProfile() != null ? ps.getPatientProfile().getFullName() : "")
                .serviceId(ps.getService() != null ? ps.getService().getServiceId() : null)
                .serviceCode(ps.getService() != null ? ps.getService().getServiceCode() : "")
                .serviceName(ps.getService() != null ? ps.getService().getServiceName() : "")
                .stepName(ps.getStepName() != null && !ps.getStepName().isBlank() ? ps.getStepName() : (ps.getService() != null ? ps.getService().getServiceName() : ""))
                .categoryName(ps.getService() != null && ps.getService().getCategory() != null ? ps.getService().getCategory().getCategoryName() : "")
                .unit(ps.getService() != null ? ps.getService().getUnit() : "Lần")
                .toothNumbers(ps.getToothNumbers())
                .quantity(ps.getQuantity())
                .unitPrice(ps.getUnitPrice())
                .discountAmount(ps.getDiscountAmount())
                .finalPrice(ps.getFinalPrice())
                .doctorId(ps.getDoctor() != null ? ps.getDoctor().getStaffId() : null)
                .doctorName(ps.getDoctor() != null ? ps.getDoctor().getFullName() : "")
                .doctorCode(ps.getDoctor() != null ? ps.getDoctor().getStaffCode() : "")
                .assistantId(ps.getAssistant() != null ? ps.getAssistant().getStaffId() : null)
                .assistantName(ps.getAssistant() != null ? ps.getAssistant().getFullName() : "")
                .completedAt(timeStr)
                .status(ps.getStatus() != null ? ps.getStatus().name() : "COMPLETED")
                .statusDisplay(statusDisplay)
                .clinicalNotes(ps.getClinicalNotes())
                .hasWorkflow(hasWorkflow)
                .workflowTemplateId(wfTemplateId)
                .workflowStepCount(wfStepCount)
                .workflowTemplateName(wfTemplateName)
                .planItemId(planItemId)
                .planName(planName)
                .stageName(stageName)
                .build();
    }

    private void validateDoctorForVisit(StaffProfile doctor, Visit visit) {
        if (doctor == null || visit == null || visit.getBranch() == null) {
            return;
        }
        if (visit.getPrimaryDoctor() != null && doctor.getStaffId().equals(visit.getPrimaryDoctor().getStaffId())) {
            return;
        }
        if (doctorCoverGrantRepository != null && doctorCoverGrantRepository
                .existsByVisitVisitIdAndCoveringDoctorStaffIdAndRevokedAtIsNullAndExpiresAtAfter(
                        visit.getVisitId(), doctor.getStaffId(), LocalDateTime.now())) {
            return;
        }
        Long visitBranchId = visit.getBranch().getBranchId();
        if (visitBranchId == null) {
            return;
        }
        boolean hasBranchInfo = false;
        if (doctor.getUser() != null && doctor.getUser().getBranch() != null) {
            hasBranchInfo = true;
            if (visitBranchId.equals(doctor.getUser().getBranch().getBranchId())) {
                return;
            }
        }
        if (staffBranchAssignmentRepository != null) {
            List<Long> branchIds = staffBranchAssignmentRepository.findActiveBranchIdsByStaffId(
                    doctor.getStaffId(), com.cvdcms.entity.enums.AssignmentStatus.ACTIVE);
            if (branchIds != null && !branchIds.isEmpty()) {
                hasBranchInfo = true;
                if (branchIds.contains(visitBranchId)) {
                    return;
                }
            }
        }
        if (hasBranchInfo) {
            throw new IllegalArgumentException("Bác sĩ thực hiện không thuộc chi nhánh của ca khám.");
        }
    }
}
