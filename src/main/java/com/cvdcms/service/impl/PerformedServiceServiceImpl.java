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

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @Override
    @Transactional(readOnly = true)
    public List<ServiceDTO> getAllActiveServices() {
        return serviceRepository.findByStatusOrderByCategory_DisplayOrderAscServiceNameAsc(ServiceStatus.ACTIVE)
                .stream()
                .map(this::mapToServiceDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffProfile> getAvailableDoctors() {
        return staffProfileRepository.findByStatus(StaffStatus.ACTIVE).stream()
                .filter(s -> s.getStaffCode() != null && s.getStaffCode().startsWith("BS")
                        || (s.getPositionTitle() != null && s.getPositionTitle().toLowerCase().contains("bác sĩ"))
                        || (s.getDepartment() != null && s.getDepartment().toLowerCase().contains("bác sĩ")))
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

        // Quy tắc BR-EMR-02: Khóa bệnh án lâm sàng bất biến khi chuyển thanh toán hoặc hoàn tất
        if (visit.getStatus() == VisitStatus.WAITING_PAYMENT || visit.getStatus() == VisitStatus.COMPLETED) {
            throw new IllegalStateException("Bệnh án lâm sàng đã hoàn tất và khóa bất biến (BR-EMR-02). Không thể thêm dịch vụ thực hiện.");
        }

        com.cvdcms.entity.Service service = serviceRepository.findById(requestDTO.getServiceId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dịch vụ với ID: " + requestDTO.getServiceId()));

        StaffProfile doctor = staffProfileRepository.findById(requestDTO.getDoctorId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bác sĩ với ID: " + requestDTO.getDoctorId()));

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

        BigDecimal finalPrice = requestDTO.getFinalPrice() != null && requestDTO.getFinalPrice().compareTo(BigDecimal.ZERO) >= 0
                ? requestDTO.getFinalPrice() : calculatedFinal;

        PerformedService ps = new PerformedService();
        ps.setVisit(visit);
        ps.setPatientProfile(visit.getPatientProfile());
        ps.setBranch(visit.getBranch());
        ps.setService(service);
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
        log.info("Performed service created successfully with id={}", saved.getPerformedId());

        return mapToResponseDTO(saved);
    }

    @Override
    @Transactional
    public void cancelPerformedService(Long performedId) {
        log.info("Cancelling/Deleting performed service with id={}", performedId);
        PerformedService ps = performedServiceRepository.findById(performedId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dịch vụ thực hiện với ID: " + performedId));

        // Quy tắc BR-EMR-02: Khóa bất biến
        if (ps.getVisit() != null &&
                (ps.getVisit().getStatus() == VisitStatus.WAITING_PAYMENT || ps.getVisit().getStatus() == VisitStatus.COMPLETED)) {
            throw new IllegalStateException("Bệnh án lâm sàng đã khóa bất biến (BR-EMR-02). Không thể xóa hoặc hủy dịch vụ đã hoàn tất.");
        }

        // Nếu dịch vụ này gắn với bước trong Kế hoạch điều trị (FT15), hoàn tác lại trạng thái của bước đó
        if (ps.getPlanItem() != null) {
            TreatmentPlanItem planItem = ps.getPlanItem();
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

        // Xóa hoàn toàn hoặc cập nhật status sang CANCELLED
        performedServiceRepository.delete(ps);
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
}
