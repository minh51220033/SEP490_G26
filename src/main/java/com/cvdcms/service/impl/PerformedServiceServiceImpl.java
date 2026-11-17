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
    private com.cvdcms.emr.EmrRecordRepository emrRecordRepository;

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

        Visit visit = visitRepository.findById(requestDTO.getVisitId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca khám với ID: " + requestDTO.getVisitId()));

        // Quy tắc BR-EMR-02: Khóa bệnh án lâm sàng bất biến khi chuyển thanh toán, hoàn tất, hủy hoặc EMR đã khóa
        com.cvdcms.emr.EmrStatePolicy.assertMutableVisitAndEmr(
                visit,
                emrRecordRepository,
                "Bệnh án lâm sàng đã hoàn tất và khóa bất biến. Không thể thêm dịch vụ thực hiện");

        com.cvdcms.entity.Service service = serviceRepository.findById(requestDTO.getServiceId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dịch vụ với ID: " + requestDTO.getServiceId()));
        if (service.getStatus() != ServiceStatus.ACTIVE
                || (service.getCategory() != null && Boolean.FALSE.equals(service.getCategory().getIsActive()))) {
            throw new IllegalStateException("Dịch vụ hoặc nhóm dịch vụ đang ngừng hoạt động, không thể chỉ định thực hiện.");
        }

        StaffProfile doctor = staffProfileRepository.findById(requestDTO.getDoctorId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bác sĩ với ID: " + requestDTO.getDoctorId()));
        if (doctor.getStatus() != null && doctor.getStatus() != StaffStatus.ACTIVE) {
            throw new IllegalArgumentException("Bác sĩ thực hiện hiện không ở trạng thái hoạt động.");
        }
        validateDoctorForVisit(doctor, visit);

        StaffProfile assistant = null;
        if (requestDTO.getAssistantId() != null) {
            assistant = staffProfileRepository.findById(requestDTO.getAssistantId()).orElse(null);
        }

        BigDecimal qty = requestDTO.getQuantity() != null && requestDTO.getQuantity().compareTo(BigDecimal.ZERO) > 0
                ? requestDTO.getQuantity() : BigDecimal.ONE;

        BigDecimal unitPrice = requestDTO.getUnitPrice() != null && requestDTO.getUnitPrice().compareTo(BigDecimal.ZERO) >= 0
                ? requestDTO.getUnitPrice() : service.getBasePrice();

        BigDecimal discount = requestDTO.getDiscountAmount() != null && requestDTO.getDiscountAmount().compareTo(BigDecimal.ZERO) >= 0
                ? requestDTO.getDiscountAmount() : BigDecimal.ZERO;

        BigDecimal calculatedFinal = unitPrice.multiply(qty).subtract(discount);
        if (calculatedFinal.compareTo(BigDecimal.ZERO) < 0) {
            calculatedFinal = BigDecimal.ZERO;
        }

        // Luôn đồng bộ thành tiền theo công thức backend (unitPrice * qty - discount) khớp InvoiceServiceImpl
        BigDecimal finalPrice = calculatedFinal;

        PerformedService ps = new PerformedService();
        ps.setVisit(visit);
        ps.setPatientProfile(visit.getPatientProfile());
        ps.setBranch(visit.getBranch());
        ps.setService(service);

        String stepName = requestDTO.getStepName();
        if (stepName == null && requestDTO.getPlanItemId() != null) {
            stepName = treatmentPlanItemRepository.findById(requestDTO.getPlanItemId())
                    .map(it -> it.getStepName() != null ? it.getStepName() : (it.getService() != null ? it.getService().getServiceName() : null))
                    .orElse(null);
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

        if (requestDTO.getPlanItemId() != null) {
            treatmentPlanItemRepository.findById(requestDTO.getPlanItemId()).ifPresent(planItem -> {
                ps.setPlanItem(planItem);
                planItem.setStatus(TreatmentPlanItemStatus.COMPLETED);
                planItem.setCompletedAt(LocalDateTime.now());
                treatmentPlanItemRepository.save(planItem);

                // Tự động cập nhật tiến độ giai đoạn và kế hoạch
                TreatmentPlanStage stage = planItem.getStage();
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
            });
        }

        PerformedService saved = performedServiceRepository.save(ps);
        recordMaterialConsumptions(saved, doctor, requestDTO.getConsumedMaterials());
        log.info("Performed service created successfully with id={}", saved.getPerformedId());

        if (serviceCommissionService != null) {
            serviceCommissionService.calculateCommissionsForPerformedService(saved);
        }

        return mapToResponseDTO(saved);
    }

    @Override
    @Transactional
    public void cancelPerformedService(Long performedId) {
        log.info("Cancelling/Deleting performed service with id={}", performedId);
        PerformedService ps = performedServiceRepository.findById(performedId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dịch vụ thực hiện với ID: " + performedId));

        // Quy tắc BR-EMR-02: Khóa bất biến theo VisitStatus & trạng thái EMR
        com.cvdcms.emr.EmrStatePolicy.assertMutableVisitAndEmr(
                ps.getVisit(),
                emrRecordRepository,
                "Bệnh án lâm sàng đã khóa bất biến. Không thể xóa hoặc hủy dịch vụ đã thực hiện");

        // Chặn xóa dịch vụ đã được Kế toán xuất hóa đơn còn hiệu lực
        if (invoiceItemRepository != null && invoiceItemRepository.existsByPerformedIdAndInvoiceNotCancelled(performedId)) {
            throw new IllegalStateException("Dịch vụ thực hiện đã được Kế toán xuất hóa đơn còn hiệu lực, không thể hủy hoặc xóa.");
        }

        // Hoàn lại vật tư tiêu hao lâm sàng (nếu có) trước khi xóa
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
            }
        }

        if (serviceCommissionService != null) {
            serviceCommissionService.cancelPendingCommissionsForPerformedService(ps.getPerformedId());
        }
        performedServiceRepository.delete(ps);
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
        Long branchId = saved.getBranch().getBranchId();
        java.time.LocalDate today = java.time.LocalDate.now();
        for (PerformedServiceRequestDTO.MaterialUsageDTO usage : usages) {
            if (usage == null || usage.getMaterialId() == null
                    || usage.getQuantity() == null || usage.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            Material material = materialRepository.findById(usage.getMaterialId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy vật tư ID: " + usage.getMaterialId()));
            BranchInventory inventory = branchInventoryRepository.findForUpdate(branchId, material.getMaterialId())
                    .orElseThrow(() -> new IllegalStateException("Kho chi nhánh chưa có tồn kho vật tư: " + material.getMaterialName()));
            BigDecimal currentStock = inventory.getCurrentStock() != null ? inventory.getCurrentStock() : BigDecimal.ZERO;
            if (currentStock.compareTo(usage.getQuantity()) < 0) {
                throw new IllegalStateException("Kho chi nhánh không đủ vật tư tiêu hao '" + material.getMaterialName() + "' (Tồn: " + currentStock + ", Cần: " + usage.getQuantity() + ").");
            }
            List<MaterialBatch> batches = materialBatchRepository.findAvailableBatchesFefoForUpdate(branchId, material.getMaterialId(), today);
            BigDecimal totalBatchQty = batches.stream()
                    .map(b -> b.getQuantityRemaining() != null ? b.getQuantityRemaining() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (totalBatchQty.compareTo(usage.getQuantity()) < 0) {
                throw new IllegalStateException("Các lô còn hạn của vật tư '" + material.getMaterialName() + "' không đủ để trừ tiêu hao.");
            }
            BigDecimal remainingToConsume = usage.getQuantity();
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
            inventory.setCurrentStock(currentStock.subtract(usage.getQuantity()));
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
        for (MaterialConsumption mc : consumptions) {
            BigDecimal qty = mc.getQuantity() != null ? mc.getQuantity() : BigDecimal.ZERO;
            if (qty.compareTo(BigDecimal.ZERO) > 0 && mc.getBatch() != null && materialBatchRepository != null) {
                MaterialBatch batch = materialBatchRepository.findByIdForUpdate(mc.getBatch().getBatchId()).orElse(mc.getBatch());
                BigDecimal curBatch = batch.getQuantityRemaining() != null ? batch.getQuantityRemaining() : BigDecimal.ZERO;
                batch.setQuantityRemaining(curBatch.add(qty));
                if (batch.getExpiryDate() == null || !batch.getExpiryDate().isBefore(today)) {
                    batch.setStatus(com.cvdcms.entity.enums.BatchStatus.ACTIVE);
                }
                materialBatchRepository.save(batch);
            }
            if (qty.compareTo(BigDecimal.ZERO) > 0 && mc.getBranch() != null && mc.getMaterial() != null && branchInventoryRepository != null) {
                branchInventoryRepository.findForUpdate(mc.getBranch().getBranchId(), mc.getMaterial().getMaterialId())
                        .ifPresent(inv -> {
                            BigDecimal cur = inv.getCurrentStock() != null ? inv.getCurrentStock() : BigDecimal.ZERO;
                            inv.setCurrentStock(cur.add(qty));
                            branchInventoryRepository.save(inv);
                        });
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
