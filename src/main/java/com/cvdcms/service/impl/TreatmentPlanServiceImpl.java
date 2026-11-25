package com.cvdcms.service.impl;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.PerformedServiceStatus;
import com.cvdcms.entity.enums.TreatmentPlanItemStatus;
import com.cvdcms.entity.enums.TreatmentPlanStageStatus;
import com.cvdcms.entity.enums.TreatmentPlanStatus;
import com.cvdcms.repository.*;
import com.cvdcms.service.TreatmentPlanService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TreatmentPlanServiceImpl implements TreatmentPlanService {

    private final TreatmentPlanRepository treatmentPlanRepository;
    private final TreatmentPlanStageRepository treatmentPlanStageRepository;
    private final TreatmentPlanItemRepository treatmentPlanItemRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final BranchRepository branchRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final ServiceRepository serviceRepository;
    private final PerformedServiceRepository performedServiceRepository;
    private final InvoiceItemRepository invoiceItemRepository;
    private final TreatmentPlanInstallmentRepository treatmentPlanInstallmentRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public TreatmentPlanServiceImpl(
            TreatmentPlanRepository treatmentPlanRepository,
            TreatmentPlanStageRepository treatmentPlanStageRepository,
            TreatmentPlanItemRepository treatmentPlanItemRepository,
            PatientProfileRepository patientProfileRepository,
            BranchRepository branchRepository,
            StaffProfileRepository staffProfileRepository,
            ServiceRepository serviceRepository,
            PerformedServiceRepository performedServiceRepository,
            InvoiceItemRepository invoiceItemRepository,
            TreatmentPlanInstallmentRepository treatmentPlanInstallmentRepository) {
        this.treatmentPlanRepository = treatmentPlanRepository;
        this.treatmentPlanStageRepository = treatmentPlanStageRepository;
        this.treatmentPlanItemRepository = treatmentPlanItemRepository;
        this.patientProfileRepository = patientProfileRepository;
        this.branchRepository = branchRepository;
        this.staffProfileRepository = staffProfileRepository;
        this.serviceRepository = serviceRepository;
        this.performedServiceRepository = performedServiceRepository;
        this.invoiceItemRepository = invoiceItemRepository;
        this.treatmentPlanInstallmentRepository = treatmentPlanInstallmentRepository;
    }

    // Compatibility constructor preserved for unit tests passing GeminiAiService
    public TreatmentPlanServiceImpl(
            TreatmentPlanRepository treatmentPlanRepository,
            TreatmentPlanStageRepository treatmentPlanStageRepository,
            TreatmentPlanItemRepository treatmentPlanItemRepository,
            PatientProfileRepository patientProfileRepository,
            BranchRepository branchRepository,
            StaffProfileRepository staffProfileRepository,
            ServiceRepository serviceRepository,
            com.cvdcms.service.GeminiAiService geminiAiService,
            PerformedServiceRepository performedServiceRepository,
            InvoiceItemRepository invoiceItemRepository,
            TreatmentPlanInstallmentRepository treatmentPlanInstallmentRepository) {
        this(
                treatmentPlanRepository,
                treatmentPlanStageRepository,
                treatmentPlanItemRepository,
                patientProfileRepository,
                branchRepository,
                staffProfileRepository,
                serviceRepository,
                performedServiceRepository,
                invoiceItemRepository,
                treatmentPlanInstallmentRepository);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PaymentRepository paymentRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private UserRepository userRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.service.LoyaltyService loyaltyService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BranchBankAccountRepository branchBankAccountRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.EmrRecordRepository emrRecordRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.cvdcms.service.PerformedServiceService performedServiceService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private VisitRepository visitRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorkflowExecutionRepository workflowExecutionRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorkflowStepExecutionRepository workflowStepExecutionRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ClinicalWorkflowStepRepository clinicalWorkflowStepRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    @Override
    @Transactional(readOnly = true)
    public List<TreatmentPlanResponseDTO> getPlansByPatientId(Long patientId) {
        List<TreatmentPlan> plans = treatmentPlanRepository.findByProfileIdWithDetails(patientId);
        return plans.stream().map(this::mapToResponseDTO).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public TreatmentPlanResponseDTO getPlanById(Long planId) {
        TreatmentPlan plan = treatmentPlanRepository.findByIdWithDetails(planId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kế hoạch điều trị."));
        return mapToResponseDTO(plan);
    }

    @Override
    @Transactional
    public TreatmentPlanResponseDTO createPlan(TreatmentPlanRequestDTO request) {
        if (request.getPatientId() == null) {
            throw new IllegalArgumentException("Vui lòng chỉ định bệnh nhân cho kế hoạch điều trị!");
        }

        PatientProfile patient = patientProfileRepository.findById(request.getPatientId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hồ sơ bệnh nhân."));

        if (request.getVisitId() != null && visitRepository != null) {
            Visit visit = visitRepository.findById(request.getVisitId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca khám phù hợp."));
            com.cvdcms.util.EmrStatePolicy.assertMutableVisitAndEmr(
                    visit,
                    emrRecordRepository,
                    "Ca khám đã khóa/hoàn tất lâm sàng, không thể lập kế hoạch điều trị");
            if (visit.getPatientProfile() != null && !visit.getPatientProfile().getProfileId().equals(patient.getProfileId())) {
                throw new IllegalArgumentException("Ca khám không khớp với hồ sơ bệnh nhân.");
            }
        }

        Branch branch = null;
        if (request.getBranchId() != null) {
            branch = branchRepository.findById(request.getBranchId()).orElse(null);
        }
        if (branch == null && patient.getPreferredBranch() != null) {
            branch = patient.getPreferredBranch();
        }
        if (branch == null) {
            branch = branchRepository.findAll().stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("Hệ thống chưa có chi nhánh nào!"));
        }

        StaffProfile doctor = null;
        if (request.getDoctorId() != null) {
            doctor = staffProfileRepository.findById(request.getDoctorId()).orElse(null);
        }
        if (doctor == null) {
            doctor = staffProfileRepository.findAll().stream().findFirst().orElse(null);
        }

        TreatmentPlan plan = new TreatmentPlan();
        plan.setPlanCode("KHDT" + (System.currentTimeMillis() % 100000000));
        plan.setPatientProfile(patient);
        plan.setBranch(branch);
        plan.setDoctor(doctor);
        String rawPlanName = request.getPlanName() != null && !request.getPlanName().trim().isEmpty() 
                ? request.getPlanName().trim() : "Kế hoạch điều trị nha khoa";
        // Cho phep luu ten phac do dai day du vao cot TEXT
        plan.setPlanName(rawPlanName);

        TreatmentPlanStatus planStatus = TreatmentPlanStatus.PROPOSED;
        if (request.getStatus() != null) {
            try {
                planStatus = TreatmentPlanStatus.valueOf(request.getStatus().toUpperCase());
            } catch (Exception ignored) {}
        }
        plan.setStatus(planStatus);

        LocalDate startDate = parseLocalDateSafe(request.getStartDate(), LocalDate.now());
        plan.setStartDate(startDate);

        LocalDate expectedEndDate = parseLocalDateSafe(request.getExpectedEndDate(), startDate.plusDays(90));
        plan.setExpectedEndDate(expectedEndDate);

        plan.setNotes(buildPlanNotesWithDiagnosis(request.getNotes(), request.getDiagnosisText()));

        BigDecimal totalEstimated = BigDecimal.ZERO;
        List<com.cvdcms.entity.Service> allServices = serviceRepository.findAll();

        // Xử lý các giai đoạn (Stages) và chi tiết (Items)
        if (request.getStages() != null && !request.getStages().isEmpty()) {
            int maxOrder = 0;
            Set<Integer> usedOrders = new HashSet<>();
            for (TreatmentPlanStageRequestDTO stageReq : request.getStages()) {
                Integer reqOrder = stageReq.getStageOrder();
                int resolvedOrder;
                if (reqOrder != null && reqOrder > 0 && usedOrders.add(reqOrder)) {
                    resolvedOrder = reqOrder;
                    maxOrder = Math.max(maxOrder, resolvedOrder);
                } else {
                    resolvedOrder = ++maxOrder;
                    usedOrders.add(resolvedOrder);
                }
                TreatmentPlanStage stage = new TreatmentPlanStage();
                stage.setPlan(plan);
                stage.setStageOrder(resolvedOrder);
                stage.setStageName(stageReq.getStageName() != null && !stageReq.getStageName().trim().isEmpty()
                        ? stageReq.getStageName().trim() : "Giai đoạn " + stage.getStageOrder());
                stage.setDescription(stageReq.getDescription());
                if (stageReq.getEstimatedDaysFromStart() != null && stageReq.getEstimatedDaysFromStart() >= 0) {
                    stage.setExpectedEndDate(startDate.plusDays(stageReq.getEstimatedDaysFromStart()));
                }

                TreatmentPlanStageStatus stageStatus = TreatmentPlanStageStatus.PENDING;
                if (stageReq.getStatus() != null) {
                    try {
                        stageStatus = TreatmentPlanStageStatus.valueOf(stageReq.getStatus().toUpperCase());
                    } catch (Exception ignored) {}
                }
                stage.setStatus(stageStatus);

                if (stageReq.getItems() != null && !stageReq.getItems().isEmpty()) {
                    for (TreatmentPlanItemRequestDTO itemReq : stageReq.getItems()) {
                        TreatmentPlanItem item = buildPlanItemEntity(stage, plan, itemReq, allServices);
                        totalEstimated = totalEstimated.add(item.getEstimatedPrice().multiply(item.getQuantity()));
                        stage.getItems().add(item);
                    }
                }
                plan.getStages().add(stage);
            }
        }

        BigDecimal validatedDiscount = BigDecimal.ZERO;
        BigDecimal finalCost = totalEstimated.subtract(validatedDiscount).max(BigDecimal.ZERO);
        plan.setTotalEstimatedCost(totalEstimated);
        plan.setFinalEstimatedCost(finalCost);

        plan.setIsInstallment(Boolean.TRUE.equals(request.getIsInstallment()));
        plan.setTotalPaidAmount(BigDecimal.ZERO);

        if (Boolean.TRUE.equals(request.getIsInstallment()) && request.getInstallments() != null && !request.getInstallments().isEmpty()) {
            applyInstallmentSchedule(plan, request.getInstallments(), false);
        }

        // Nút Lưu Kế hoạch điều trị tự động kích hoạt logic chuyển kế toán
        plan.setSentToAccounting(true);
        plan.setSentToAccountingAt(LocalDateTime.now());

        TreatmentPlan saved = treatmentPlanRepository.save(plan);
        log.info("Created TreatmentPlan ID: {}, Code: {}", saved.getPlanId(), saved.getPlanCode());
        return mapToResponseDTO(saved);
    }

    @Override
    @Transactional
    public TreatmentPlanResponseDTO updatePlan(Long planId, TreatmentPlanRequestDTO request) {
        TreatmentPlan plan = treatmentPlanRepository.findByIdWithDetails(planId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kế hoạch điều trị."));

        if (plan.getStatus() == TreatmentPlanStatus.CANCELLED) {
            throw new IllegalStateException("Không thể chỉnh sửa kế hoạch điều trị đã hủy!");
        }

        if (request.getVisitId() != null && visitRepository != null) {
            Visit visit = visitRepository.findById(request.getVisitId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca khám phù hợp."));
            com.cvdcms.util.EmrStatePolicy.assertMutableVisitAndEmr(
                    visit,
                    emrRecordRepository,
                    "Ca khám đã khóa/hoàn tất lâm sàng, không thể chỉnh sửa kế hoạch điều trị");
            if (visit.getPatientProfile() != null && plan.getPatientProfile() != null
                    && !visit.getPatientProfile().getProfileId().equals(plan.getPatientProfile().getProfileId())) {
                throw new IllegalArgumentException("Ca khám không khớp với hồ sơ bệnh nhân của kế hoạch điều trị.");
            }
        }

        BigDecimal paidAmount = plan.getTotalPaidAmount() != null ? plan.getTotalPaidAmount() : BigDecimal.ZERO;
        boolean scheduleLocked = paidAmount.compareTo(BigDecimal.ZERO) > 0 || plan.isFullyPaid();
        boolean wasInstallment = Boolean.TRUE.equals(plan.getIsInstallment());
        boolean wantsInstallment = request.getIsInstallment() != null
                ? Boolean.TRUE.equals(request.getIsInstallment())
                : wasInstallment;

        if (scheduleLocked) {
            if (wantsInstallment != wasInstallment) {
                throw new IllegalStateException("Phác đồ đã ghi nhận thanh toán hoặc đã tất toán, không thể thay đổi hình thức thanh toán!");
            }
            if (request.getInstallments() != null && !isSameInstallmentSchedule(plan.getInstallments(), request.getInstallments())) {
                throw new IllegalStateException("Kế hoạch điều trị đã phát sinh thanh toán hoặc đã tất toán, lộ trình chia đợt đã được khóa cứng!");
            }
        }

        if (wantsInstallment != wasInstallment && hasLinkedPerformedServices(plan)) {
            throw new IllegalStateException("Phác đồ đã có dịch vụ thực hiện trong ca khám, không thể chuyển đổi giữa thanh toán đơn lẻ và trả theo đợt!");
        }

        if (request.getBranchId() != null) {
            branchRepository.findById(request.getBranchId()).ifPresent(plan::setBranch);
        }
        if (request.getDoctorId() != null) {
            staffProfileRepository.findById(request.getDoctorId()).ifPresent(plan::setDoctor);
        }

        if (request.getPlanName() != null && !request.getPlanName().trim().isEmpty()) {
            plan.setPlanName(request.getPlanName().trim());
        }

        LocalDate startDate = parseLocalDateSafe(request.getStartDate(),
                plan.getStartDate() != null ? plan.getStartDate() : LocalDate.now());
        plan.setStartDate(startDate);
        if (request.getExpectedEndDate() != null && !request.getExpectedEndDate().isBlank()) {
            plan.setExpectedEndDate(parseLocalDateSafe(request.getExpectedEndDate(), plan.getExpectedEndDate()));
        }
        plan.setNotes(buildPlanNotesWithDiagnosis(request.getNotes(), request.getDiagnosisText()));

        BigDecimal totalEstimated = request.getStages() != null
                ? reconcilePlanStagesForUpdate(plan, request.getStages(), startDate)
                : computePlanStagesTotal(plan);

        BigDecimal validatedDiscount = BigDecimal.ZERO;
        BigDecimal finalCost = totalEstimated.subtract(validatedDiscount).max(BigDecimal.ZERO);

        if (finalCost.compareTo(paidAmount) < 0) {
            throw new IllegalArgumentException("Tổng chi phí phác đồ sau chỉnh sửa không được nhỏ hơn số tiền bệnh nhân đã thanh toán!");
        }

        plan.setTotalEstimatedCost(totalEstimated);
        plan.setFinalEstimatedCost(finalCost);

        // Bảo toàn trạng thái IN_PROGRESS / COMPLETED dựa trên tiến độ thực tế của các giai đoạn & bước thủ thuật
        syncPlanStatusFromStages(plan, request.getStatus());

        plan.setIsInstallment(wantsInstallment);
        if (wantsInstallment) {
            if (request.getInstallments() != null && !scheduleLocked) {
                applyInstallmentSchedule(plan, request.getInstallments(), false);
            } else {
                recalculateInstallmentsInternal(plan);
            }
        } else {
            plan.getInstallments().clear();
        }

        TreatmentPlan saved = treatmentPlanRepository.save(plan);
        log.info("Updated TreatmentPlan Code: {}", saved.getPlanCode());
        return mapToResponseDTO(saved);
    }

    @Override
    @Transactional
    public TreatmentPlanResponseDTO updatePlanStatus(Long planId, String status) {
        TreatmentPlan plan = treatmentPlanRepository.findByIdWithDetails(planId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kế hoạch điều trị."));

        try {
            TreatmentPlanStatus newStatus = TreatmentPlanStatus.valueOf(status.toUpperCase());
            plan.setStatus(newStatus);
            TreatmentPlan updated = treatmentPlanRepository.save(plan);
            log.info("Updated TreatmentPlan {} to status: {}", plan.getPlanCode(), newStatus);
            return mapToResponseDTO(updated);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Trạng thái kế hoạch không hợp lệ: " + status);
        }
    }

    @Override
    @Transactional
    public void deletePlan(Long planId) {
        TreatmentPlan plan = treatmentPlanRepository.findByIdWithDetails(planId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kế hoạch điều trị."));

        if (plan.getTotalPaidAmount() != null && plan.getTotalPaidAmount().compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalStateException("Không thể xóa kế hoạch điều trị đã có thanh toán theo đợt!");
        }
        boolean allowDeleteCompletedUnbilled = plan.getStatus() == TreatmentPlanStatus.CANCELLED;
        detachLinkedRecordsForPlanDelete(plan, allowDeleteCompletedUnbilled);

        treatmentPlanRepository.delete(plan);
        log.info("Deleted TreatmentPlan {}", plan.getPlanCode());
    }

    private void detachLinkedRecordsForPlanDelete(TreatmentPlan plan, boolean allowDeleteCompletedUnbilled) {
        if (plan.getStages() == null) {
            return;
        }
        List<PerformedService> servicesToSave = new ArrayList<>();
        List<WorkflowExecution> executionsToSave = new ArrayList<>();
        Set<Long> seenExecIds = new HashSet<>();

        for (TreatmentPlanStage stage : plan.getStages()) {
            if (stage.getStageId() != null && workflowExecutionRepository != null) {
                for (WorkflowExecution ex : workflowExecutionRepository.findByPlanStage_StageId(stage.getStageId())) {
                    if (ex.getExecutionId() != null && seenExecIds.add(ex.getExecutionId())) {
                        ex.setPlanStage(null);
                        ex.setPlanItem(null);
                        if (ex.getStatus() != com.cvdcms.entity.enums.WorkflowExecutionStatus.COMPLETED
                                && ex.getStatus() != com.cvdcms.entity.enums.WorkflowExecutionStatus.CANCELLED) {
                            ex.setStatus(com.cvdcms.entity.enums.WorkflowExecutionStatus.CANCELLED);
                            ex.setCurrentStep(null);
                            ex.setCompletedAt(LocalDateTime.now());
                        }
                        executionsToSave.add(ex);
                    }
                }
            }
            if (stage.getItems() == null) {
                continue;
            }
            for (TreatmentPlanItem item : stage.getItems()) {
                if (!allowDeleteCompletedUnbilled && isPlanItemLockedForEdit(item)) {
                    throw new IllegalStateException("Không thể xóa kế hoạch điều trị đã có thủ thuật đang thực hiện hoặc đã hoàn thành trong ca khám! Nếu muốn xóa, hãy chuyển trạng thái sang Đã hủy trước.");
                }
                if (item.getPlanItemId() == null) {
                    continue;
                }
                if (performedServiceRepository != null) {
                    for (PerformedService ps : performedServiceRepository.findByPlanItem_PlanItemId(item.getPlanItemId())) {
                        boolean isBilled = invoiceItemRepository != null && ps.getPerformedId() != null
                                && invoiceItemRepository.existsByPerformedIdAndInvoiceNotCancelled(ps.getPerformedId());
                        if (isBilled) {
                            throw new IllegalStateException("Không thể xóa kế hoạch điều trị đã có dịch vụ được lập hóa đơn!");
                        }
                        if (ps.getStatus() != PerformedServiceStatus.CANCELLED) {
                            ps.setStatus(PerformedServiceStatus.CANCELLED);
                        }
                        ps.setPlanItem(null);
                        servicesToSave.add(ps);
                    }
                }
                if (workflowExecutionRepository != null) {
                    for (WorkflowExecution ex : workflowExecutionRepository.findByPlanItem_PlanItemId(item.getPlanItemId())) {
                        if (ex.getExecutionId() != null && seenExecIds.add(ex.getExecutionId())) {
                            ex.setPlanItem(null);
                            ex.setPlanStage(null);
                            if (ex.getStatus() != com.cvdcms.entity.enums.WorkflowExecutionStatus.COMPLETED
                                    && ex.getStatus() != com.cvdcms.entity.enums.WorkflowExecutionStatus.CANCELLED) {
                                ex.setStatus(com.cvdcms.entity.enums.WorkflowExecutionStatus.CANCELLED);
                                ex.setCurrentStep(null);
                                ex.setCompletedAt(LocalDateTime.now());
                            }
                            executionsToSave.add(ex);
                        }
                    }
                }
            }
        }
        if (!servicesToSave.isEmpty() && performedServiceRepository != null) {
            performedServiceRepository.saveAll(servicesToSave);
            performedServiceRepository.flush();
        }
        if (!executionsToSave.isEmpty() && workflowExecutionRepository != null) {
            workflowExecutionRepository.saveAll(executionsToSave);
            workflowExecutionRepository.flush();
        }
    }

    @Override
    @Transactional
    public TreatmentPlanResponseDTO handoverToAccounting(Long planId) {
        TreatmentPlan plan = treatmentPlanRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kế hoạch điều trị."));
        plan.setSentToAccounting(true);
        plan.setSentToAccountingAt(LocalDateTime.now());
        TreatmentPlan saved = treatmentPlanRepository.save(plan);
        log.info("Kế hoạch điều trị {} đã được chuyển sang Kế toán thành công.", saved.getPlanCode());
        return mapToResponseDTO(saved);
    }

    @Override
    @Transactional
    public TreatmentPlanResponseDTO updateStageStatus(Long stageId, String statusStr) {
        TreatmentPlanStage stage = treatmentPlanStageRepository.findById(stageId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy giai đoạn với ID: " + stageId));

        if (stage.getPlan() != null && stage.getPlan().getStatus() == TreatmentPlanStatus.CANCELLED) {
            throw new IllegalStateException("Kế hoạch điều trị đã hủy, không thể cập nhật trạng thái giai đoạn.");
        }

        TreatmentPlanStageStatus newStatus = TreatmentPlanStageStatus.valueOf(statusStr.toUpperCase());
        stage.setStatus(newStatus);

        LocalDateTime now = LocalDateTime.now();
        if (newStatus == TreatmentPlanStageStatus.COMPLETED) {
            stage.setCompletedAt(now);
            if (stage.getItems() != null) {
                for (TreatmentPlanItem it : stage.getItems()) {
                    it.setStatus(TreatmentPlanItemStatus.COMPLETED);
                    it.setCompletedAt(now);
                }
            }
        } else if (newStatus == TreatmentPlanStageStatus.IN_PROGRESS) {
            if (stage.getCompletedAt() == null) {
                stage.setCompletedAt(now);
            }
            if (stage.getItems() != null) {
                for (TreatmentPlanItem it : stage.getItems()) {
                    if (it.getStatus() == TreatmentPlanItemStatus.PLANNED) {
                        it.setStatus(TreatmentPlanItemStatus.IN_PROGRESS);
                    }
                }
            }
        } else if (newStatus == TreatmentPlanStageStatus.PENDING) {
            if (stage.getItems() != null) {
                for (TreatmentPlanItem it : stage.getItems()) {
                    if (it.getPlanItemId() != null) {
                        List<PerformedService> activeServices = performedServiceRepository
                                .findByPlanItem_PlanItemIdAndStatusNot(it.getPlanItemId(), PerformedServiceStatus.CANCELLED);
                        for (PerformedService ps : activeServices) {
                            com.cvdcms.util.EmrStatePolicy.assertMutableVisitAndEmr(
                                    ps.getVisit(),
                                    emrRecordRepository,
                                    "Bệnh án lâm sàng đã khóa/hủy, không thể đặt lại trạng thái giai đoạn phác đồ");
                            if (performedServiceService != null && ps.getPerformedId() != null) {
                                performedServiceService.cancelPerformedService(ps.getPerformedId());
                            } else {
                                if (invoiceItemRepository != null && ps.getPerformedId() != null
                                        && invoiceItemRepository.existsByPerformedIdAndInvoiceNotCancelled(ps.getPerformedId())) {
                                    throw new IllegalStateException(
                                            "Giai đoạn này có bước thủ thuật đã được lập hóa đơn viện phí, không thể hoàn tác về Chưa thực hiện!");
                                }
                                ps.setStatus(PerformedServiceStatus.CANCELLED);
                                performedServiceRepository.save(ps);
                            }
                        }
                    }
                }
                for (TreatmentPlanItem it : stage.getItems()) {
                    it.setStatus(TreatmentPlanItemStatus.PLANNED);
                    it.setCompletedAt(null);
                }
            }
            stage.setCompletedAt(null);
            treatmentPlanStageRepository.save(stage);
            syncWorkflowExecutionsAfterRevert(stage, null);
        }

        treatmentPlanStageRepository.save(stage);

        if (stage.getPlan() != null) {
            return syncPlanProgressAndStatus(stage.getPlan().getPlanId());
        }
        syncWorkflowExecutionsForStage(stage, null);
        return null;
    }

    @Override
    @Transactional
    public TreatmentPlanResponseDTO updateItemStatus(Long planItemId, String statusStr) {
        TreatmentPlanItem item = treatmentPlanItemRepository.findById(planItemId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thủ thuật với ID: " + planItemId));

        TreatmentPlan parentPlan = item.getPlan() != null
                ? item.getPlan()
                : (item.getStage() != null ? item.getStage().getPlan() : null);
        if (parentPlan != null && parentPlan.getStatus() == TreatmentPlanStatus.CANCELLED) {
            throw new IllegalStateException("Kế hoạch điều trị đã hủy, không thể cập nhật trạng thái bước thủ thuật.");
        }

        TreatmentPlanItemStatus newStatus = TreatmentPlanItemStatus.valueOf(statusStr.toUpperCase());
        if (newStatus == TreatmentPlanItemStatus.PLANNED || newStatus == TreatmentPlanItemStatus.CANCELLED) {
            List<PerformedService> activeServices = performedServiceRepository.findByPlanItem_PlanItemIdAndStatusNot(
                    planItemId, PerformedServiceStatus.CANCELLED);
            for (PerformedService ps : activeServices) {
                com.cvdcms.util.EmrStatePolicy.assertMutableVisitAndEmr(
                        ps.getVisit(),
                        emrRecordRepository,
                        "Bệnh án lâm sàng đã khóa/hủy, không thể hoàn tác bước thủ thuật phác đồ");
                if (performedServiceService != null && ps.getPerformedId() != null) {
                    performedServiceService.cancelPerformedService(ps.getPerformedId());
                } else {
                    boolean isBilled = invoiceItemRepository != null && ps.getPerformedId() != null
                            && invoiceItemRepository.existsByPerformedIdAndInvoiceNotCancelled(ps.getPerformedId());
                    if (isBilled) {
                        throw new IllegalStateException("Bước thủ thuật này đã được lập hóa đơn viện phí, không thể hoàn tác!");
                    }
                    ps.setStatus(PerformedServiceStatus.CANCELLED);
                    performedServiceRepository.save(ps);
                }
            }
        }
        item.setStatus(newStatus);
        LocalDateTime now = LocalDateTime.now();
        if (newStatus == TreatmentPlanItemStatus.COMPLETED) {
            item.setCompletedAt(now);
        } else {
            item.setCompletedAt(null);
        }
        treatmentPlanItemRepository.save(item);

        TreatmentPlanStage stage = item.getStage();
        if (stage != null) {
            List<TreatmentPlanItem> items = stage.getItems();
            boolean allCompleted = items != null && !items.isEmpty() && items.stream().allMatch(i -> i.getStatus() == TreatmentPlanItemStatus.COMPLETED);
            boolean anyActive = items != null && items.stream().anyMatch(i -> i.getStatus() == TreatmentPlanItemStatus.COMPLETED || i.getStatus() == TreatmentPlanItemStatus.IN_PROGRESS);

            if (allCompleted) {
                stage.setStatus(TreatmentPlanStageStatus.COMPLETED);
                if (stage.getCompletedAt() == null) stage.setCompletedAt(now);
            } else if (anyActive) {
                stage.setStatus(TreatmentPlanStageStatus.IN_PROGRESS);
                stage.setCompletedAt(null);
            } else {
                stage.setStatus(TreatmentPlanStageStatus.PENDING);
                stage.setCompletedAt(null);
            }
            treatmentPlanStageRepository.save(stage);
        }

        if (newStatus == TreatmentPlanItemStatus.PLANNED || newStatus == TreatmentPlanItemStatus.CANCELLED) {
            syncWorkflowExecutionsAfterRevert(stage, item);
        }

        if (stage != null && stage.getPlan() != null) {
            return syncPlanProgressAndStatus(stage.getPlan().getPlanId());
        }
        if (parentPlan != null && parentPlan.getPlanId() != null) {
            return syncPlanProgressAndStatus(parentPlan.getPlanId());
        }
        if (newStatus == TreatmentPlanItemStatus.COMPLETED || newStatus == TreatmentPlanItemStatus.IN_PROGRESS) {
            syncWorkflowExecutionsForStage(stage, item);
        }
        return null;
    }

    /**
     * Khi hoàn tác bước hoặc giai đoạn KHDT về Chưa làm, đồng bộ/đặt lại quy trình lâm sàng liên kết (FT43)
     * để tiến độ giữa tab Kế hoạch điều trị và tab Quy trình điều trị luôn khớp nhau 100%.
     */
    private void syncWorkflowExecutionsAfterRevert(TreatmentPlanStage stage, TreatmentPlanItem targetItem) {
        if (workflowExecutionRepository == null) {
            return;
        }
        Long targetServiceId = (targetItem != null && targetItem.getService() != null)
                ? targetItem.getService().getServiceId()
                : null;
        Long targetItemId = targetItem != null ? targetItem.getPlanItemId() : null;

        Map<Long, WorkflowExecution> matchedExecs = new LinkedHashMap<>();
        if (targetItemId != null) {
            for (WorkflowExecution ex : workflowExecutionRepository.findByPlanItem_PlanItemId(targetItemId)) {
                if (ex.getExecutionId() != null) {
                    matchedExecs.put(ex.getExecutionId(), ex);
                }
            }
        }
        if (stage != null && stage.getStageId() != null) {
            for (WorkflowExecution ex : workflowExecutionRepository.findByPlanStage_StageId(stage.getStageId())) {
                if (ex.getExecutionId() == null) {
                    continue;
                }
                if (targetServiceId != null) {
                    Long exServiceId = (ex.getVersion() != null
                            && ex.getVersion().getTemplate() != null
                            && ex.getVersion().getTemplate().getService() != null)
                            ? ex.getVersion().getTemplate().getService().getServiceId()
                            : null;
                    boolean sameItem = ex.getPlanItem() != null && Objects.equals(ex.getPlanItem().getPlanItemId(), targetItemId);
                    if (!sameItem && exServiceId != null && !Objects.equals(exServiceId, targetServiceId)) {
                        continue;
                    }
                }
                matchedExecs.put(ex.getExecutionId(), ex);
            }
        }

        if (matchedExecs.isEmpty()) {
            return;
        }

        for (WorkflowExecution ex : matchedExecs.values()) {
            TreatmentPlanStage exStage = ex.getPlanStage() != null
                    ? ex.getPlanStage()
                    : (stage != null ? stage : (ex.getPlanItem() != null ? ex.getPlanItem().getStage() : null));
            Long exServiceId = (ex.getVersion() != null
                    && ex.getVersion().getTemplate() != null
                    && ex.getVersion().getTemplate().getService() != null)
                    ? ex.getVersion().getTemplate().getService().getServiceId()
                    : targetServiceId;

            List<TreatmentPlanItem> stageServiceItems = new ArrayList<>();
            if (exStage != null && exStage.getStageId() != null) {
                for (TreatmentPlanItem it : treatmentPlanItemRepository.findByStage_StageId(exStage.getStageId())) {
                    if (it.getStatus() == TreatmentPlanItemStatus.CANCELLED) {
                        continue;
                    }
                    if (exServiceId == null || (it.getService() != null && Objects.equals(it.getService().getServiceId(), exServiceId))) {
                        stageServiceItems.add(it);
                    }
                }
                stageServiceItems.sort(Comparator.comparing(TreatmentPlanItem::getPlanItemId));
            } else if (ex.getPlanItem() != null && ex.getPlanItem().getStatus() != TreatmentPlanItemStatus.CANCELLED) {
                stageServiceItems.add(ex.getPlanItem());
            } else if (targetItem != null && targetItem.getStatus() != TreatmentPlanItemStatus.CANCELLED) {
                stageServiceItems.add(targetItem);
            }

            long completedItemCount = stageServiceItems.stream()
                    .filter(it -> it.getStatus() == TreatmentPlanItemStatus.COMPLETED)
                    .count();
            long inProgressItemCount = stageServiceItems.stream()
                    .filter(it -> it.getStatus() == TreatmentPlanItemStatus.IN_PROGRESS)
                    .count();

            List<WorkflowStepExecution> stepExecs = workflowStepExecutionRepository != null
                    ? new ArrayList<>(workflowStepExecutionRepository.findByExecution_ExecutionIdOrderByStep_SequenceOrderAsc(ex.getExecutionId()))
                    : new ArrayList<>();

            if (completedItemCount == 0 && inProgressItemCount == 0) {
                List<ClinicalWorkflowStep> adhocSteps = stepExecs.stream()
                        .map(WorkflowStepExecution::getStep)
                        .filter(s -> s != null && Objects.equals(ex.getExecutionId(), s.getAdhocExecutionId()))
                        .toList();
                if (!stepExecs.isEmpty() && workflowStepExecutionRepository != null) {
                    workflowStepExecutionRepository.deleteAll(stepExecs);
                    workflowStepExecutionRepository.flush();
                }
                if (!adhocSteps.isEmpty() && clinicalWorkflowStepRepository != null) {
                    clinicalWorkflowStepRepository.deleteAll(adhocSteps);
                    clinicalWorkflowStepRepository.flush();
                }
                workflowExecutionRepository.delete(ex);
                workflowExecutionRepository.flush();
                continue;
            }

            stepExecs.sort(Comparator.comparingInt(se -> se.getStepOrder() != null
                    ? se.getStepOrder()
                    : (se.getStep() != null && se.getStep().getSequenceOrder() != null ? se.getStep().getSequenceOrder() : 999)));

            List<WorkflowStepExecution> templateSteps = stepExecs.stream()
                    .filter(se -> se.getStep() != null && se.getStep().getAdhocExecutionId() == null)
                    .toList();

            Map<Long, TreatmentPlanItem> matchedByStepExecId = mapPlanItemsToTemplateStepsForSync(stageServiceItems, templateSteps);
            LocalDateTime now = LocalDateTime.now();
            for (WorkflowStepExecution se : templateSteps) {
                TreatmentPlanItem matchedItem = se.getStepExecutionId() != null
                        ? matchedByStepExecId.get(se.getStepExecutionId())
                        : null;
                if (matchedItem != null && matchedItem.getStatus() == TreatmentPlanItemStatus.COMPLETED) {
                    if (se.getStatus() != com.cvdcms.entity.enums.WorkflowStepExecutionStatus.COMPLETED
                            && se.getStatus() != com.cvdcms.entity.enums.WorkflowStepExecutionStatus.SKIPPED) {
                        LocalDateTime doneAt = matchedItem.getCompletedAt() != null ? matchedItem.getCompletedAt() : now;
                        se.setStatus(com.cvdcms.entity.enums.WorkflowStepExecutionStatus.COMPLETED);
                        if (se.getStartedAt() == null) se.setStartedAt(doneAt);
                        se.setCompletedAt(doneAt);
                        if (se.getResultNote() == null || se.getResultNote().isBlank()) {
                            se.setResultNote("Đã hoàn thành nhanh từ Kế hoạch điều trị");
                        }
                    }
                } else {
                    se.setStatus(com.cvdcms.entity.enums.WorkflowStepExecutionStatus.PENDING);
                    se.setStartedAt(null);
                    se.setCompletedAt(null);
                    se.setApprovedBy(null);
                    se.setApprovedAt(null);
                }
            }
            if (!stepExecs.isEmpty() && workflowStepExecutionRepository != null) {
                workflowStepExecutionRepository.saveAll(stepExecs);
            }

            WorkflowStepExecution firstOpen = stepExecs.stream()
                    .filter(se -> se.getStatus() != com.cvdcms.entity.enums.WorkflowStepExecutionStatus.COMPLETED
                            && se.getStatus() != com.cvdcms.entity.enums.WorkflowStepExecutionStatus.SKIPPED)
                    .findFirst()
                    .orElse(null);

            TreatmentPlanItem nextOpenItem = stageServiceItems.stream()
                    .filter(it -> it.getStatus() != TreatmentPlanItemStatus.COMPLETED)
                    .findFirst()
                    .orElse(null);
            if (nextOpenItem != null) {
                ex.setPlanItem(nextOpenItem);
            }
            ex.setStatus(com.cvdcms.entity.enums.WorkflowExecutionStatus.IN_PROGRESS);
            ex.setCompletedAt(null);
            ex.setCurrentStep(firstOpen != null ? firstOpen.getStep() : null);
            workflowExecutionRepository.save(ex);
            workflowExecutionRepository.flush();
        }
    }

    @Override
    @Transactional
    public TreatmentPlanResponseDTO syncPlanProgressAndStatus(Long planId) {
        TreatmentPlan plan = treatmentPlanRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kế hoạch ID: " + planId));

        List<TreatmentPlanStage> stages = treatmentPlanStageRepository.findByPlan_PlanIdOrderByStageOrderAsc(planId);
        int totalStages = stages.size();
        int completedStages = 0;
        boolean anyStageActive = false;

        for (TreatmentPlanStage st : stages) {
            List<TreatmentPlanItem> items = treatmentPlanItemRepository.findByStage_StageId(st.getStageId());
            boolean allItemsDone = items != null && !items.isEmpty() &&
                    items.stream().allMatch(i -> i.getStatus() == TreatmentPlanItemStatus.COMPLETED);
            boolean anyItemActive = items != null &&
                    items.stream().anyMatch(i -> i.getStatus() == TreatmentPlanItemStatus.COMPLETED || i.getStatus() == TreatmentPlanItemStatus.IN_PROGRESS);

            if (allItemsDone) {
                st.setStatus(TreatmentPlanStageStatus.COMPLETED);
                if (st.getCompletedAt() == null) st.setCompletedAt(LocalDateTime.now());
                completedStages++;
                anyStageActive = true;
            } else if (anyItemActive) {
                st.setStatus(TreatmentPlanStageStatus.IN_PROGRESS);
                st.setCompletedAt(null);
                anyStageActive = true;
            } else {
                st.setStatus(TreatmentPlanStageStatus.PENDING);
                st.setCompletedAt(null);
            }
            treatmentPlanStageRepository.save(st);
        }

        if (totalStages > 0 && completedStages == totalStages) {
            plan.setStatus(TreatmentPlanStatus.COMPLETED);
        } else if (anyStageActive || completedStages > 0) {
            plan.setStatus(TreatmentPlanStatus.IN_PROGRESS);
        } else {
            if (plan.getStatus() == TreatmentPlanStatus.COMPLETED || plan.getStatus() == TreatmentPlanStatus.IN_PROGRESS) {
                plan.setStatus(TreatmentPlanStatus.ACCEPTED);
            }
        }
        treatmentPlanRepository.save(plan);
        for (TreatmentPlanStage st : stages) {
            syncWorkflowExecutionsForStage(st, null);
        }
        return mapToResponseDTO(treatmentPlanRepository.findByIdWithDetails(planId).orElse(plan));
    }

    private void syncWorkflowExecutionsForStage(TreatmentPlanStage stage, TreatmentPlanItem targetItem) {
        if (workflowExecutionRepository == null || workflowStepExecutionRepository == null) {
            return;
        }
        Map<Long, WorkflowExecution> matchedExecs = new LinkedHashMap<>();
        if (stage != null && stage.getStageId() != null) {
            for (WorkflowExecution ex : workflowExecutionRepository.findByPlanStage_StageId(stage.getStageId())) {
                if (ex.getExecutionId() != null) {
                    matchedExecs.put(ex.getExecutionId(), ex);
                }
            }
            for (TreatmentPlanItem it : treatmentPlanItemRepository.findByStage_StageId(stage.getStageId())) {
                if (it.getPlanItemId() != null) {
                    for (WorkflowExecution ex : workflowExecutionRepository.findByPlanItem_PlanItemId(it.getPlanItemId())) {
                        if (ex.getExecutionId() != null) {
                            matchedExecs.put(ex.getExecutionId(), ex);
                        }
                    }
                }
            }
        } else if (targetItem != null && targetItem.getPlanItemId() != null) {
            for (WorkflowExecution ex : workflowExecutionRepository.findByPlanItem_PlanItemId(targetItem.getPlanItemId())) {
                if (ex.getExecutionId() != null) {
                    matchedExecs.put(ex.getExecutionId(), ex);
                }
            }
        }
        if (matchedExecs.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        for (WorkflowExecution ex : matchedExecs.values()) {
            if (ex.getStatus() == com.cvdcms.entity.enums.WorkflowExecutionStatus.CANCELLED) {
                continue;
            }
            TreatmentPlanStage exStage = ex.getPlanStage() != null
                    ? ex.getPlanStage()
                    : (stage != null ? stage : (ex.getPlanItem() != null ? ex.getPlanItem().getStage() : null));
            Long exServiceId = (ex.getVersion() != null
                    && ex.getVersion().getTemplate() != null
                    && ex.getVersion().getTemplate().getService() != null)
                    ? ex.getVersion().getTemplate().getService().getServiceId()
                    : null;

            List<TreatmentPlanItem> stageServiceItems = new ArrayList<>();
            if (exStage != null && exStage.getStageId() != null) {
                for (TreatmentPlanItem it : treatmentPlanItemRepository.findByStage_StageId(exStage.getStageId())) {
                    if (it.getStatus() == TreatmentPlanItemStatus.CANCELLED) {
                        continue;
                    }
                    if (exServiceId == null || (it.getService() != null && Objects.equals(it.getService().getServiceId(), exServiceId))) {
                        stageServiceItems.add(it);
                    }
                }
                stageServiceItems.sort(Comparator.comparing(TreatmentPlanItem::getPlanItemId));
            } else if (ex.getPlanItem() != null && ex.getPlanItem().getStatus() != TreatmentPlanItemStatus.CANCELLED) {
                stageServiceItems.add(ex.getPlanItem());
            } else if (targetItem != null && targetItem.getStatus() != TreatmentPlanItemStatus.CANCELLED) {
                stageServiceItems.add(targetItem);
            }
            if (stageServiceItems.isEmpty()) {
                continue;
            }

            List<WorkflowStepExecution> stepExecs = new ArrayList<>(
                    workflowStepExecutionRepository.findByExecution_ExecutionIdOrderByStep_SequenceOrderAsc(ex.getExecutionId()));
            if (stepExecs.isEmpty()) {
                continue;
            }
            stepExecs.sort(Comparator.comparingInt(se -> se.getStepOrder() != null
                    ? se.getStepOrder()
                    : (se.getStep() != null && se.getStep().getSequenceOrder() != null ? se.getStep().getSequenceOrder() : 999)));
            List<WorkflowStepExecution> templateSteps = stepExecs.stream()
                    .filter(se -> se.getStep() != null && se.getStep().getAdhocExecutionId() == null)
                    .toList();

            Map<Long, TreatmentPlanItem> matchedByStepExecId = mapPlanItemsToTemplateStepsForSync(stageServiceItems, templateSteps);
            boolean stepsChanged = false;
            for (WorkflowStepExecution se : templateSteps) {
                TreatmentPlanItem matchedItem = se.getStepExecutionId() != null
                        ? matchedByStepExecId.get(se.getStepExecutionId())
                        : null;
                if (matchedItem != null && matchedItem.getStatus() == TreatmentPlanItemStatus.COMPLETED
                        && se.getStatus() != com.cvdcms.entity.enums.WorkflowStepExecutionStatus.COMPLETED
                        && se.getStatus() != com.cvdcms.entity.enums.WorkflowStepExecutionStatus.SKIPPED) {
                    LocalDateTime doneAt = matchedItem.getCompletedAt() != null ? matchedItem.getCompletedAt() : now;
                    se.setStatus(com.cvdcms.entity.enums.WorkflowStepExecutionStatus.COMPLETED);
                    if (se.getStartedAt() == null) se.setStartedAt(doneAt);
                    se.setCompletedAt(doneAt);
                    if (se.getResultNote() == null || se.getResultNote().isBlank()) {
                        se.setResultNote("Đã hoàn thành nhanh từ Kế hoạch điều trị");
                    }
                    stepsChanged = true;
                }
            }
            if (stepsChanged) {
                workflowStepExecutionRepository.saveAll(stepExecs);
            }

            WorkflowStepExecution firstOpen = stepExecs.stream()
                    .filter(se -> se.getStatus() != com.cvdcms.entity.enums.WorkflowStepExecutionStatus.COMPLETED
                            && se.getStatus() != com.cvdcms.entity.enums.WorkflowStepExecutionStatus.SKIPPED)
                    .findFirst()
                    .orElse(null);

            if (firstOpen == null) {
                if (ex.getStatus() != com.cvdcms.entity.enums.WorkflowExecutionStatus.COMPLETED || ex.getCurrentStep() != null) {
                    ex.setStatus(com.cvdcms.entity.enums.WorkflowExecutionStatus.COMPLETED);
                    ex.setCurrentStep(null);
                    if (ex.getCompletedAt() == null) ex.setCompletedAt(now);
                    workflowExecutionRepository.save(ex);
                }
            } else {
                TreatmentPlanItem nextOpenItem = stageServiceItems.stream()
                        .filter(it -> it.getStatus() != TreatmentPlanItemStatus.COMPLETED)
                        .findFirst()
                        .orElse(ex.getPlanItem());
                com.cvdcms.entity.enums.WorkflowExecutionStatus expectedStatus =
                        firstOpen.getStatus() == com.cvdcms.entity.enums.WorkflowStepExecutionStatus.PAUSED
                                ? com.cvdcms.entity.enums.WorkflowExecutionStatus.PAUSED
                                : com.cvdcms.entity.enums.WorkflowExecutionStatus.IN_PROGRESS;
                Long curStepId = ex.getCurrentStep() != null ? ex.getCurrentStep().getStepId() : null;
                Long nextStepId = firstOpen.getStep() != null ? firstOpen.getStep().getStepId() : null;
                Long curItemId = ex.getPlanItem() != null ? ex.getPlanItem().getPlanItemId() : null;
                Long nextItemId = nextOpenItem != null ? nextOpenItem.getPlanItemId() : null;

                if (stepsChanged || ex.getStatus() != expectedStatus
                        || !Objects.equals(curStepId, nextStepId)
                        || (nextItemId != null && !Objects.equals(curItemId, nextItemId))) {
                    ex.setStatus(expectedStatus);
                    ex.setCompletedAt(null);
                    ex.setCurrentStep(firstOpen.getStep());
                    if (nextOpenItem != null) {
                        ex.setPlanItem(nextOpenItem);
                    }
                    workflowExecutionRepository.save(ex);
                }
            }
        }
    }

    private Map<Long, TreatmentPlanItem> mapPlanItemsToTemplateStepsForSync(
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
            String itemNorm = normalizeStepNameForSync(linkedItems.get(i).getStepName());
            String stepNorm = templateSteps.get(i).getStep() != null
                    ? normalizeStepNameForSync(templateSteps.get(i).getStep().getStepName())
                    : "";
            if (!itemNorm.isEmpty() && itemNorm.equals(stepNorm)) {
                if (templateSteps.get(i).getStepExecutionId() != null) {
                    mapping.put(templateSteps.get(i).getStepExecutionId(), linkedItems.get(i));
                }
                usedStep[i] = true;
                usedItemIdx.add(i);
            }
        }

        for (int i = 0; i < linkedItems.size(); i++) {
            if (usedItemIdx.contains(i)) continue;
            String itemNorm = normalizeStepNameForSync(linkedItems.get(i).getStepName());
            if (itemNorm.isEmpty()) continue;
            for (int j = 0; j < templateSteps.size(); j++) {
                if (usedStep[j]) continue;
                String stepNorm = templateSteps.get(j).getStep() != null
                        ? normalizeStepNameForSync(templateSteps.get(j).getStep().getStepName())
                        : "";
                if (itemNorm.equals(stepNorm)) {
                    if (templateSteps.get(j).getStepExecutionId() != null) {
                        mapping.put(templateSteps.get(j).getStepExecutionId(), linkedItems.get(i));
                    }
                    usedStep[j] = true;
                    usedItemIdx.add(i);
                    break;
                }
            }
        }

        for (int i = 0; i < linkedItems.size() && i < templateSteps.size(); i++) {
            if (!usedItemIdx.contains(i) && !usedStep[i]) {
                if (templateSteps.get(i).getStepExecutionId() != null) {
                    mapping.put(templateSteps.get(i).getStepExecutionId(), linkedItems.get(i));
                }
                usedStep[i] = true;
                usedItemIdx.add(i);
            }
        }
        return mapping;
    }

    private String normalizeStepNameForSync(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('đ', 'd').replace('Đ', 'd')
                .trim().toLowerCase(Locale.ROOT);
    }

    private TreatmentPlanResponseDTO mapToResponseDTO(TreatmentPlan p) {
        PatientProfile patient = p.getPatientProfile();
        Branch branch = p.getBranch();
        StaffProfile doctor = p.getDoctor();

        int totalStages = (p.getStages() != null) ? p.getStages().size() : 0;
        int completedStages = 0;
        int totalItems = 0;
        int completedItems = 0;

        List<TreatmentPlanStageResponseDTO> stageDTOs = new ArrayList<>();
        if (p.getStages() != null) {
            for (TreatmentPlanStage s : p.getStages()) {
                if (s.getStatus() == TreatmentPlanStageStatus.COMPLETED) {
                    completedStages++;
                }

                BigDecimal stageTotalCost = BigDecimal.ZERO;
                List<TreatmentPlanItemResponseDTO> itemDTOs = new ArrayList<>();
                if (s.getItems() != null) {
                    for (TreatmentPlanItem it : s.getItems()) {
                        totalItems++;
                        if (it.getStatus() == TreatmentPlanItemStatus.COMPLETED) {
                            completedItems++;
                        }
                        com.cvdcms.entity.Service svc = it.getService();
                        BigDecimal estPrice = it.getEstimatedPrice() != null
                                ? it.getEstimatedPrice()
                                : (svc != null && svc.getBasePrice() != null ? svc.getBasePrice() : BigDecimal.ZERO);
                        BigDecimal qtyVal = it.getQuantity() != null ? it.getQuantity() : BigDecimal.ONE;
                        BigDecimal itemTotal = estPrice.multiply(qtyVal);
                        stageTotalCost = stageTotalCost.add(itemTotal);

                        itemDTOs.add(TreatmentPlanItemResponseDTO.builder()
                                .planItemId(it.getPlanItemId())
                                .serviceId(svc != null ? svc.getServiceId() : null)
                                .serviceCode(svc != null ? svc.getServiceCode() : null)
                                .serviceName(svc != null ? svc.getServiceName() : "Dịch vụ nha khoa")
                                .stepName(it.getStepName() != null && !it.getStepName().isBlank() ? it.getStepName() : (svc != null ? svc.getServiceName() : "Bước thủ thuật"))
                                .categoryName(svc != null && svc.getCategory() != null ? svc.getCategory().getCategoryName() : null)
                                .unit(svc != null ? svc.getUnit() : "Lần")
                                .toothNumbers(it.getToothNumbers())
                                .quantity(qtyVal)
                                .estimatedPrice(estPrice)
                                .totalPrice(itemTotal)
                                .status(it.getStatus() != null ? it.getStatus().name() : "PLANNED")
                                .statusBadgeClass(getItemStatusBadgeClass(it.getStatus()))
                                .statusDisplayName(getItemStatusDisplayName(it.getStatus()))
                                .completedAt(it.getCompletedAt() != null ? it.getCompletedAt().format(DATE_TIME_FORMATTER) : null)
                                .build());
                    }
                }

                LocalDate stageExpectedEnd = s.getExpectedEndDate();
                if (stageExpectedEnd == null && p.getExpectedEndDate() != null) {
                    stageExpectedEnd = p.getExpectedEndDate();
                }
                String stageExpectedEndStr = stageExpectedEnd != null ? stageExpectedEnd.format(DATE_FORMATTER) : null;

                stageDTOs.add(TreatmentPlanStageResponseDTO.builder()
                        .stageId(s.getStageId())
                        .stageOrder(s.getStageOrder())
                        .stageName(s.getStageName())
                        .description(s.getDescription())
                        .status(s.getStatus() != null ? s.getStatus().name() : "PENDING")
                        .statusBadgeClass(getStageStatusBadgeClass(s.getStatus()))
                        .statusDisplayName(getStageStatusDisplayName(s.getStatus()))
                        .completedAt(s.getCompletedAt() != null ? s.getCompletedAt().format(DATE_TIME_FORMATTER) : null)
                        .expectedEndDate(stageExpectedEndStr)
                        .stageTotalCost(stageTotalCost)
                        .items(itemDTOs)
                        .build());
            }
        }

        int progressPercent = 0;
        if (totalItems > 0) {
            progressPercent = (completedItems * 100) / totalItems;
        } else if (totalStages > 0) {
            progressPercent = (completedStages * 100) / totalStages;
        }
        TreatmentPlanStatus planStatus = p.getStatus() != null ? p.getStatus() : TreatmentPlanStatus.DRAFT;
        if (totalStages > 0 && completedStages < totalStages && planStatus == TreatmentPlanStatus.COMPLETED) {
            planStatus = (completedStages > 0 || completedItems > 0) ? TreatmentPlanStatus.IN_PROGRESS : TreatmentPlanStatus.ACCEPTED;
        }

        List<TreatmentPlanInstallmentDTO> installmentDTOs = new ArrayList<>();
        TreatmentPlanInstallmentDTO nextInstDTO = null;
        if (p.getInstallments() != null) {
            for (TreatmentPlanInstallment inst : p.getInstallments()) {
                TreatmentPlanInstallmentDTO instDTO = mapInstallmentToDTO(inst, p);
                installmentDTOs.add(instDTO);
                if (nextInstDTO == null && (inst.getStatus() == com.cvdcms.entity.enums.TreatmentPlanInstallmentStatus.PENDING
                        || inst.getStatus() == com.cvdcms.entity.enums.TreatmentPlanInstallmentStatus.PARTIAL
                        || inst.getStatus() == com.cvdcms.entity.enums.TreatmentPlanInstallmentStatus.OVERDUE)) {
                    nextInstDTO = instDTO;
                }
            }
        }

        BigDecimal totalPaid = p.getTotalPaidAmount() != null ? p.getTotalPaidAmount() : BigDecimal.ZERO;
        BigDecimal estCost = p.getEstimatedCost();
        BigDecimal remainingCost = estCost.subtract(totalPaid);
        if (remainingCost.compareTo(BigDecimal.ZERO) < 0) remainingCost = BigDecimal.ZERO;

        return TreatmentPlanResponseDTO.builder()
                .planId(p.getPlanId())
                .planCode(p.getPlanCode())
                .patientId(patient != null ? patient.getProfileId() : null)
                .patientCode(patient != null ? patient.getPatientCode() : null)
                .patientName(patient != null ? patient.getFullName() : null)
                .phoneNumber(patient != null ? patient.getPhoneNumber() : null)
                .branchId(branch != null ? branch.getBranchId() : null)
                .branchName(branch != null ? branch.getBranchName() : null)
                .doctorId(doctor != null ? doctor.getStaffId() : null)
                .doctorName(doctor != null ? doctor.getFullName() : null)
                .planName(p.getPlanName())
                .diagnosisText(extractDiagnosisFromNotes(p.getNotes()))
                .totalEstimatedCost(p.getTotalEstimatedCost())
                .finalEstimatedCost(p.getFinalEstimatedCost())
                .status(planStatus.name())
                .statusBadgeClass(getPlanStatusBadgeClass(planStatus))
                .statusDisplayName(getPlanStatusDisplayName(planStatus))
                .startDate(p.getStartDate() != null ? p.getStartDate().format(DATE_FORMATTER) : null)
                .expectedEndDate(p.getExpectedEndDate() != null ? p.getExpectedEndDate().format(DATE_FORMATTER) : null)
                .notes(p.getNotes())
                .sentToAccounting(Boolean.TRUE.equals(p.getSentToAccounting()))
                .sentToAccountingAt(p.getSentToAccountingAt() != null ? p.getSentToAccountingAt().format(DATE_TIME_FORMATTER) : null)
                .createdAt(p.getCreatedAt() != null ? p.getCreatedAt().format(DATE_TIME_FORMATTER) : null)
                .totalStages(totalStages)
                .completedStages(completedStages)
                .totalItems(totalItems)
                .completedItems(completedItems)
                .progressPercent(progressPercent)
                .isInstallment(Boolean.TRUE.equals(p.getIsInstallment()))
                .totalPaidAmount(totalPaid)
                .remainingCost(remainingCost)
                .nextInstallment(nextInstDTO)
                .installments(installmentDTOs)
                .stages(stageDTOs)
                .build();
    }

    private TreatmentPlanInstallmentDTO mapInstallmentToDTO(TreatmentPlanInstallment inst, TreatmentPlan plan) {
        if (inst == null) return null;
        PatientProfile pat = plan != null ? plan.getPatientProfile() : null;
        Branch branch = plan != null ? plan.getBranch() : null;
        BigDecimal expected = inst.getExpectedAmount() != null ? inst.getExpectedAmount() : BigDecimal.ZERO;
        BigDecimal paid = inst.getPaidAmount() != null ? inst.getPaidAmount() : BigDecimal.ZERO;
        BigDecimal remaining = expected.subtract(paid);
        if (remaining.compareTo(BigDecimal.ZERO) < 0) remaining = BigDecimal.ZERO;

        return TreatmentPlanInstallmentDTO.builder()
                .installmentId(inst.getInstallmentId())
                .planId(plan != null ? plan.getPlanId() : null)
                .planCode(plan != null ? plan.getPlanCode() : null)
                .planName(plan != null ? plan.getPlanName() : null)
                .patientId(pat != null ? pat.getProfileId() : null)
                .patientCode(pat != null ? pat.getPatientCode() : null)
                .patientName(pat != null ? pat.getFullName() : null)
                .patientPhone(pat != null ? pat.getPhoneNumber() : null)
                .branchId(branch != null ? branch.getBranchId() : null)
                .branchName(branch != null ? branch.getBranchName() : null)
                .installmentOrder(inst.getInstallmentOrder())
                .phaseName(inst.getPhaseName())
                .expectedDate(inst.getExpectedDate() != null ? inst.getExpectedDate().format(DATE_FORMATTER) : null)
                .expectedDateRaw(inst.getExpectedDate() != null ? inst.getExpectedDate().toString() : null)
                .expectedAmount(expected)
                .paidAmount(paid)
                .remainingAmount(remaining)
                .status(inst.getStatus() != null ? inst.getStatus().name() : "PENDING")
                .statusDisplayName(inst.getStatus() != null ? inst.getStatus().getDisplayName() : "Chưa thanh toán")
                .statusBadgeClass(inst.getStatus() != null ? inst.getStatus().getBadgeClass() : "badge bg-secondary")
                .notes(inst.getNotes())
                .reminded(Boolean.TRUE.equals(inst.getReminded()))
                .remindedAt(inst.getRemindedAt() != null ? inst.getRemindedAt().format(DATE_TIME_FORMATTER) : null)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TreatmentPlanInstallmentDTO> getPlanInstallments(Long planId) {
        TreatmentPlan plan = treatmentPlanRepository.findByIdWithDetails(planId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kế hoạch điều trị."));
        return plan.getInstallments().stream()
                .map(inst -> mapInstallmentToDTO(inst, plan))
                .collect(Collectors.toList());
    }

     @Override
    @Transactional
    public TreatmentPlanResponseDTO saveInstallments(Long planId, List<TreatmentPlanInstallmentDTO> installmentDTOs) {
        TreatmentPlan plan = treatmentPlanRepository.findByIdWithDetails(planId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kế hoạch điều trị."));
        BigDecimal currentPaid = plan.getTotalPaidAmount() != null ? plan.getTotalPaidAmount() : BigDecimal.ZERO;
        if (currentPaid.compareTo(BigDecimal.ZERO) > 0 || plan.isFullyPaid()) {
            throw new IllegalStateException("Kế hoạch điều trị đã phát sinh thanh toán hoặc đã tất toán, lộ trình chia đợt đã được khóa cứng!");
        }
        if (installmentDTOs == null || installmentDTOs.isEmpty()) {
            throw new IllegalArgumentException("Lộ trình chia đợt phải có ít nhất 1 đợt thanh toán!");
        }
        applyInstallmentSchedule(plan, installmentDTOs, true);
        TreatmentPlan saved = treatmentPlanRepository.save(plan);
        return mapToResponseDTO(saved);
    }

    @Override
    @Transactional
    public TreatmentPlanResponseDTO recordInstallmentPayment(Long planId, BigDecimal amount, Long invoiceId) {
        InstallmentPaymentRequestDTO req = new InstallmentPaymentRequestDTO();
        req.setPlanId(planId);
        req.setAmount(amount);
        req.setPaymentMethod(com.cvdcms.entity.enums.PaymentMethod.CASH);
        return recordInstallmentPaymentInternal(planId, req, null, false);
    }

    @Override
    @Transactional
    public TreatmentPlanResponseDTO recordInstallmentPayment(Long planId, InstallmentPaymentRequestDTO request, Long cashierUserId) {
        return recordInstallmentPaymentInternal(planId, request, cashierUserId, paymentRepository != null);
    }

    private TreatmentPlanResponseDTO recordInstallmentPaymentInternal(
            Long planId,
            InstallmentPaymentRequestDTO request,
            Long cashierUserId,
            boolean recordAccountingLedger) {
        if (request == null || request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Số tiền thanh toán phải lớn hơn 0!");
        }
        BigDecimal amount = request.getAmount();
        com.cvdcms.entity.enums.PaymentMethod paymentMethod = request.getPaymentMethod() != null
                ? request.getPaymentMethod() : com.cvdcms.entity.enums.PaymentMethod.CASH;


        TreatmentPlan plan = treatmentPlanRepository.findByIdForUpdate(planId)
                .or(() -> treatmentPlanRepository.findByIdWithDetails(planId))
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kế hoạch điều trị."));
        if (plan.getStatus() == TreatmentPlanStatus.CANCELLED) {
            throw new IllegalStateException("Không thể thu tiền cho kế hoạch điều trị đã bị hủy.");
        }

        Branch branch = plan.getBranch();
        PatientProfile patient = plan.getPatientProfile();

        StaffProfile cashier = null;
        if (recordAccountingLedger) {
            cashier = validateInstallmentCashier(cashierUserId, branch != null ? branch.getBranchId() : null);
        }

        String idempotencyKey = (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank())
                ? request.getIdempotencyKey().trim() : null;
        String transactionRef = (request.getTransactionRef() != null && !request.getTransactionRef().isBlank())
                ? request.getTransactionRef().trim() : null;

        if (recordAccountingLedger && idempotencyKey != null) {
            Optional<Payment> existingOpt = paymentRepository.findByIdempotencyKey(idempotencyKey);
            if (existingOpt.isPresent()) {
                Payment existing = existingOpt.get();
                boolean matches = existing.getInvoice() == null
                        && existing.getPaymentType() == com.cvdcms.entity.enums.PaymentType.PAYMENT
                        && existing.getPaymentMethod() == paymentMethod
                        && existing.getAmount() != null && existing.getAmount().compareTo(amount) == 0
                        && (patient == null || (existing.getPatientProfile() != null && existing.getPatientProfile().getProfileId().equals(patient.getProfileId())))
                        && (branch == null || (existing.getBranch() != null && existing.getBranch().getBranchId().equals(branch.getBranchId())))
                        && (plan.getPlanCode() == null || (existing.getNotes() != null && existing.getNotes().contains(plan.getPlanCode())));
                if (matches) {
                    return mapToResponseDTO(plan);
                }
                throw new IllegalStateException("Khóa giao dịch (" + idempotencyKey + ") đã được sử dụng cho giao dịch khác.");
            }
        }

        if (recordAccountingLedger && transactionRef != null) {
            Optional<Payment> existingRefOpt = paymentRepository.findByTransactionRef(transactionRef);
            if (existingRefOpt.isPresent()) {
                Payment existing = existingRefOpt.get();
                boolean matches = existing.getInvoice() == null
                        && existing.getPaymentType() == com.cvdcms.entity.enums.PaymentType.PAYMENT
                        && existing.getAmount() != null && existing.getAmount().compareTo(amount) == 0
                        && (patient == null || (existing.getPatientProfile() != null && existing.getPatientProfile().getProfileId().equals(patient.getProfileId())))
                        && (branch == null || (existing.getBranch() != null && existing.getBranch().getBranchId().equals(branch.getBranchId())))
                        && (plan.getPlanCode() == null || (existing.getNotes() != null && existing.getNotes().contains(plan.getPlanCode())));
                if (matches) {
                    return mapToResponseDTO(plan);
                }
                throw new IllegalStateException("Mã tham chiếu giao dịch (" + transactionRef + ") đã được sử dụng cho giao dịch khác.");
            }
        }
        BigDecimal estimatedCost = plan.getEstimatedCost() != null ? plan.getEstimatedCost() : BigDecimal.ZERO;
        BigDecimal currentPaid = plan.getTotalPaidAmount() != null ? plan.getTotalPaidAmount() : BigDecimal.ZERO;
        BigDecimal remainingCost = estimatedCost.subtract(currentPaid);
        if (remainingCost.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Kế hoạch điều trị này đã thu đủ, không thể thu thêm!");
        }
        if (amount.compareTo(remainingCost) > 0) {
            throw new IllegalArgumentException(String.format(
                    "Số tiền thu (%,.0f đ) vượt quá số tiền còn lại cần thu (%,.0f đ)!",
                    amount, remainingCost));
        }

        if (recordAccountingLedger) {
            if (patient == null || branch == null) {
                throw new IllegalStateException("Kế hoạch điều trị thiếu thông tin bệnh nhân hoặc chi nhánh.");
            }
            PatientProfile targetPatient = patient;
            if (paymentMethod == com.cvdcms.entity.enums.PaymentMethod.DEPOSIT_WALLET) {
                PatientProfile lockedPatient = patientProfileRepository.findByIdForUpdate(patient.getProfileId())
                        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hồ sơ bệnh nhân ID: " + patient.getProfileId()));
                BigDecimal depositBalance = lockedPatient.getDepositBalance() != null ? lockedPatient.getDepositBalance() : BigDecimal.ZERO;
                if (depositBalance.compareTo(amount) < 0) {
                    throw new IllegalStateException("Số dư ví tạm ứng (" + depositBalance + " đ) không đủ để thanh toán số tiền (" + amount + " đ).");
                }
                lockedPatient.setDepositBalance(depositBalance.subtract(amount));
                patientProfileRepository.save(lockedPatient);
                targetPatient = lockedPatient;
            }

            Payment payment = new Payment();
            payment.setPaymentCode("TT" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            payment.setInvoice(null);
            payment.setPatientProfile(targetPatient);
            payment.setBranch(branch);
            payment.setCashier(cashier);
            payment.setPaymentType(com.cvdcms.entity.enums.PaymentType.PAYMENT);
            payment.setPaymentMethod(paymentMethod);
            payment.setAmount(amount);
            payment.setTransactionRef(transactionRef);
            payment.setIdempotencyKey(idempotencyKey);

            String basePrefix = "[KHDT: " + plan.getPlanCode() + "]";
            String userNotes = request.getNotes() != null ? request.getNotes().trim() : "";
            String combinedNotes = userNotes.isEmpty() ? basePrefix : (basePrefix + " " + userNotes);
            if (combinedNotes.length() > 255) {
                combinedNotes = combinedNotes.substring(0, 255);
            }
            payment.setNotes(combinedNotes);

            if (paymentMethod == com.cvdcms.entity.enums.PaymentMethod.BANK_TRANSFER && branchBankAccountRepository != null) {
                attachInstallmentBankTransferSnapshot(payment, request, branch,
                        (plan.getPlanCode() != null ? plan.getPlanCode() : "KHDT") + " TT ");
            }

            Payment savedPayment = paymentRepository.save(payment);
            if (loyaltyService != null && paymentMethod != com.cvdcms.entity.enums.PaymentMethod.DEPOSIT_WALLET) {
                loyaltyService.recordPaymentPoints(savedPayment);
            }
        }

        plan.setTotalPaidAmount(currentPaid.add(amount));

        recalculateInstallmentsInternal(plan);

        TreatmentPlan saved = treatmentPlanRepository.save(plan);
        log.info("Ghi nhận thanh toán {} đ ({}) cho Kế hoạch điều trị {}. Tổng đã thu: {} đ",
                amount, paymentMethod.name(), saved.getPlanCode(), saved.getTotalPaidAmount());
        return mapToResponseDTO(saved);
    }

    private StaffProfile validateInstallmentCashier(Long cashierUserId, Long branchId) {
        if (cashierUserId == null) {
            throw new org.springframework.security.access.AccessDeniedException("Chưa đăng nhập hoặc không xác định được thu ngân.");
        }
        if (userRepository == null) {
            return null;
        }
        User user = userRepository.findById(cashierUserId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản người dùng ID: " + cashierUserId));
        if (!Boolean.TRUE.equals(user.getIsActive()) || !"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new IllegalStateException("Tài khoản người dùng đang bị khóa hoặc ngưng hoạt động.");
        }
        boolean isAdmin = userRepository.hasActiveAdminRole(cashierUserId);
        if (!isAdmin && !userRepository.hasActivePermission(cashierUserId, "PAYMENT_COLLECT")) {
            throw new org.springframework.security.access.AccessDeniedException("Bạn không có quyền thu tiền trả góp (PAYMENT_COLLECT).");
        }
        StaffProfile cashier = staffProfileRepository.findByUser_UserId(cashierUserId)
                .orElseThrow(() -> new IllegalStateException("Tài khoản chưa được liên kết với hồ sơ nhân sự."));
        if (cashier.getStatus() != com.cvdcms.entity.enums.StaffStatus.ACTIVE) {
            throw new IllegalStateException("Hồ sơ nhân viên không ở trạng thái hoạt động.");
        }
        if (!isAdmin && branchId != null && staffBranchAssignmentRepository != null) {
            boolean hasAssignment = staffBranchAssignmentRepository.hasActiveAssignment(
                    cashier.getStaffId(), branchId, com.cvdcms.entity.enums.AssignmentStatus.ACTIVE);
            if (!hasAssignment) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "Nhân sự (" + cashier.getFullName() + ") không được phân công hoạt động tại chi nhánh ID: " + branchId);
            }
        }
        return cashier;
    }

    private void attachInstallmentBankTransferSnapshot(Payment payment, InstallmentPaymentRequestDTO request,
                                                       Branch branch, String expectedContentPrefix) {
        BranchBankAccount chosenAccount = null;
        Long requestedAccountId = request.getBankAccountId();
        if (requestedAccountId != null) {
            BranchBankAccount account = branchBankAccountRepository.findById(requestedAccountId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản ngân hàng ID: " + requestedAccountId));
            if (branch != null && account.getBranch() != null
                    && !account.getBranch().getBranchId().equals(branch.getBranchId())) {
                throw new IllegalArgumentException("Tài khoản nhận tiền không thuộc chi nhánh giao dịch.");
            }
            if (!Boolean.TRUE.equals(account.getIsActive())) {
                throw new IllegalStateException("Tài khoản ngân hàng nhận tiền đã ngừng sử dụng.");
            }
            chosenAccount = account;
        } else if (branch != null) {
            chosenAccount = branchBankAccountRepository
                    .findByBranch_BranchIdAndIsDefaultTrueAndIsActiveTrue(branch.getBranchId())
                    .orElseGet(() -> branchBankAccountRepository
                            .findByBranch_BranchIdAndIsActiveTrueOrderByIsDefaultDescCreatedAtDesc(branch.getBranchId())
                            .stream().findFirst().orElse(null));
        }
        if (chosenAccount == null) {
            throw new IllegalStateException("Chi nhánh chưa có tài khoản ngân hàng hoạt động để nhận chuyển khoản.");
        }
        payment.setBankAccount(chosenAccount);
        payment.setBankBin(chosenAccount.getBankBin());
        payment.setBankName(chosenAccount.getBankName());
        payment.setBankAccountNumber(chosenAccount.getAccountNumber());
        payment.setBankAccountHolder(chosenAccount.getAccountHolderName());
        String expectedContent = request.getExpectedTransferContent();
        if (expectedContent == null || expectedContent.isBlank()) {
            String ik = request.getIdempotencyKey();
            String refPart = ik != null && ik.length() >= 4
                    ? ik.substring(Math.max(0, ik.length() - 6))
                    : UUID.randomUUID().toString().substring(0, 6).toUpperCase();
            expectedContent = expectedContentPrefix + refPart;
        }
        payment.setExpectedTransferContent(expectedContent.trim().toUpperCase());
    }

    private void recalculateInstallmentsInternal(TreatmentPlan plan) {
        if (plan.getInstallments() == null || plan.getInstallments().isEmpty()) {
            return;
        }

        BigDecimal unallocated = plan.getTotalPaidAmount() != null ? plan.getTotalPaidAmount() : BigDecimal.ZERO;
        LocalDate today = LocalDate.now();

        for (TreatmentPlanInstallment inst : plan.getInstallments()) {
            BigDecimal expected = inst.getExpectedAmount() != null ? inst.getExpectedAmount() : BigDecimal.ZERO;
            if (unallocated.compareTo(BigDecimal.ZERO) <= 0) {
                inst.setPaidAmount(BigDecimal.ZERO);
                if (inst.getExpectedDate() != null && inst.getExpectedDate().isBefore(today)) {
                    inst.setStatus(com.cvdcms.entity.enums.TreatmentPlanInstallmentStatus.OVERDUE);
                } else {
                    inst.setStatus(com.cvdcms.entity.enums.TreatmentPlanInstallmentStatus.PENDING);
                }
            } else if (unallocated.compareTo(expected) >= 0) {
                inst.setPaidAmount(expected);
                inst.setStatus(com.cvdcms.entity.enums.TreatmentPlanInstallmentStatus.PAID);
                unallocated = unallocated.subtract(expected);
            } else {
                inst.setPaidAmount(unallocated);
                inst.setStatus(com.cvdcms.entity.enums.TreatmentPlanInstallmentStatus.PARTIAL);
                unallocated = BigDecimal.ZERO;
            }
        }

        if (unallocated.compareTo(BigDecimal.ZERO) > 0 && !plan.getInstallments().isEmpty()) {
            TreatmentPlanInstallment last = plan.getInstallments().get(plan.getInstallments().size() - 1);
            last.setPaidAmount(last.getPaidAmount().add(unallocated));
        }
    }

        @Override
    @Transactional
    public TreatmentPlanInstallmentDTO markReminderSent(Long installmentId, String notes) {
        TreatmentPlanInstallment inst = treatmentPlanInstallmentRepository.findById(installmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đợt thanh toán cần nhắc hẹn."));
        if (inst.getStatus() == com.cvdcms.entity.enums.TreatmentPlanInstallmentStatus.PAID
                || (inst.getPlan() != null && inst.getPlan().isFullyPaid())) {
            throw new IllegalStateException("Đợt thanh toán này đã hoàn tất, không cần nhắc hẹn!");
        }
        inst.setReminded(true);
        inst.setRemindedAt(LocalDateTime.now());
        if (notes != null && !notes.isBlank()) {
            inst.setNotes((inst.getNotes() != null ? inst.getNotes() + " | " : "") + notes);
        }
        TreatmentPlanInstallment saved = treatmentPlanInstallmentRepository.save(inst);
        return mapInstallmentToDTO(saved, saved.getPlan());
    }

    // ==================== Shared Private Helpers ====================

    private LocalDate parseLocalDateSafe(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String trimmed = raw.trim();
        try {
            return LocalDate.parse(trimmed);
        } catch (Exception ignored) {
            try {
                return LocalDate.parse(trimmed, DATE_FORMATTER);
            } catch (Exception ignored2) {
                return fallback;
            }
        }
    }

    private String buildPlanNotesWithDiagnosis(String notes, String diagnosisText) {
        String cleanedNotes = notes != null ? notes.trim() : "";
        if (!cleanedNotes.isEmpty()) {
            cleanedNotes = cleanedNotes.replaceAll("\\[Chẩn đoán:\\s*[^\\]]*\\]\\s*(\\|\\s*)?", "").trim();
            if (cleanedNotes.startsWith("|")) {
                cleanedNotes = cleanedNotes.substring(1).trim();
            }
            if (cleanedNotes.endsWith("|")) {
                cleanedNotes = cleanedNotes.substring(0, cleanedNotes.length() - 1).trim();
            }
        }
        if (diagnosisText != null && !diagnosisText.trim().isEmpty()) {
            String diagTag = "[Chẩn đoán: " + diagnosisText.trim() + "]";
            return cleanedNotes.isEmpty() ? diagTag : (diagTag + " | " + cleanedNotes);
        }
        return cleanedNotes.isEmpty() ? null : cleanedNotes;
    }

    private boolean hasLinkedPerformedServices(TreatmentPlan plan) {
        if (plan == null || plan.getStages() == null || performedServiceRepository == null) {
            return false;
        }
        for (TreatmentPlanStage st : plan.getStages()) {
            if (st.getItems() == null) continue;
            for (TreatmentPlanItem it : st.getItems()) {
                if (it.getPlanItemId() != null
                        && performedServiceRepository.existsByPlanItem_PlanItemIdAndStatusNot(
                                it.getPlanItemId(), PerformedServiceStatus.CANCELLED)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isSameInstallmentSchedule(
            List<TreatmentPlanInstallment> existing,
            List<TreatmentPlanInstallmentDTO> incoming) {
        if (existing == null) existing = Collections.emptyList();
        if (incoming == null) return true;
        if (existing.size() != incoming.size()) {
            return false;
        }
        for (int i = 0; i < existing.size(); i++) {
            TreatmentPlanInstallment oldInst = existing.get(i);
            TreatmentPlanInstallmentDTO newDto = incoming.get(i);
            BigDecimal oldAmt = oldInst.getExpectedAmount() != null ? oldInst.getExpectedAmount() : BigDecimal.ZERO;
            BigDecimal newAmt = newDto.getExpectedAmount() != null ? newDto.getExpectedAmount() : BigDecimal.ZERO;
            if (oldAmt.compareTo(newAmt) != 0) {
                return false;
            }
            LocalDate newDate = parseLocalDateSafe(
                    newDto.getExpectedDateRaw() != null && !newDto.getExpectedDateRaw().isBlank()
                            ? newDto.getExpectedDateRaw()
                            : newDto.getExpectedDate(),
                    null);
            if (!Objects.equals(oldInst.getExpectedDate(), newDate)) {
                return false;
            }
            String oldPhase = oldInst.getPhaseName() != null ? oldInst.getPhaseName().trim() : "";
            String newPhase = newDto.getPhaseName() != null ? newDto.getPhaseName().trim() : oldPhase;
            if (!oldPhase.equals(newPhase)) {
                return false;
            }
        }
        return true;
    }

    private BigDecimal computePlanStagesTotal(TreatmentPlan plan) {
        BigDecimal total = BigDecimal.ZERO;
        if (plan == null || plan.getStages() == null) {
            return total;
        }
        for (TreatmentPlanStage st : plan.getStages()) {
            if (st.getItems() == null) continue;
            for (TreatmentPlanItem it : st.getItems()) {
                if (it.getStatus() == TreatmentPlanItemStatus.CANCELLED) continue;
                BigDecimal qty = it.getQuantity() != null ? it.getQuantity() : BigDecimal.ONE;
                BigDecimal price = it.getEstimatedPrice() != null ? it.getEstimatedPrice() : BigDecimal.ZERO;
                total = total.add(price.multiply(qty));
            }
        }
        return total;
    }

    private void syncPlanStatusFromStages(TreatmentPlan plan, String requestedStatus) {
        List<TreatmentPlanStage> stages = plan.getStages() != null ? plan.getStages() : Collections.emptyList();
        int totalStages = stages.size();
        int completedStages = 0;
        boolean anyStageActive = false;

        for (TreatmentPlanStage st : stages) {
            List<TreatmentPlanItem> items = st.getItems() != null ? st.getItems() : Collections.emptyList();
            boolean allItemsDone = !items.isEmpty() && items.stream().allMatch(i -> i.getStatus() == TreatmentPlanItemStatus.COMPLETED);
            boolean anyItemActive = items.stream().anyMatch(i ->
                    i.getStatus() == TreatmentPlanItemStatus.COMPLETED || i.getStatus() == TreatmentPlanItemStatus.IN_PROGRESS);
            if (allItemsDone || (items.isEmpty() && st.getStatus() == TreatmentPlanStageStatus.COMPLETED)) {
                completedStages++;
                anyStageActive = true;
            } else if (anyItemActive || st.getStatus() == TreatmentPlanStageStatus.IN_PROGRESS || st.getStatus() == TreatmentPlanStageStatus.COMPLETED) {
                anyStageActive = true;
            }
        }

        if (totalStages > 0 && completedStages == totalStages) {
            plan.setStatus(TreatmentPlanStatus.COMPLETED);
        } else if (anyStageActive || completedStages > 0) {
            plan.setStatus(TreatmentPlanStatus.IN_PROGRESS);
        } else if (requestedStatus != null && !requestedStatus.isBlank()) {
            try {
                TreatmentPlanStatus newStatus = TreatmentPlanStatus.valueOf(requestedStatus.toUpperCase());
                if (newStatus != TreatmentPlanStatus.COMPLETED && newStatus != TreatmentPlanStatus.IN_PROGRESS) {
                    plan.setStatus(newStatus);
                } else if (plan.getStatus() == TreatmentPlanStatus.COMPLETED || plan.getStatus() == TreatmentPlanStatus.IN_PROGRESS) {
                    plan.setStatus(TreatmentPlanStatus.ACCEPTED);
                }
            } catch (Exception ignored) {}
        }
    }

    private boolean isPlanItemLockedForEdit(TreatmentPlanItem item) {
        if (item == null) return false;
        if (item.getStatus() == TreatmentPlanItemStatus.COMPLETED || item.getStatus() == TreatmentPlanItemStatus.IN_PROGRESS) {
            return true;
        }
        return item.getPlanItemId() != null
                && performedServiceRepository != null
                && performedServiceRepository.existsByPlanItem_PlanItemIdAndStatusNot(item.getPlanItemId(), PerformedServiceStatus.CANCELLED);
    }

    private TreatmentPlanItem buildPlanItemEntity(
            TreatmentPlanStage stage,
            TreatmentPlan plan,
            TreatmentPlanItemRequestDTO itemReq,
            List<com.cvdcms.entity.Service> allServices) {
        TreatmentPlanItem item = new TreatmentPlanItem();
        item.setStage(stage);
        item.setPlan(plan);

        com.cvdcms.entity.Service service = resolveServiceForPlanItem(itemReq, allServices);
        item.setService(service);
        String stepName = (itemReq.getStepName() != null && !itemReq.getStepName().trim().isEmpty())
                ? itemReq.getStepName().trim()
                : ((itemReq.getCustomServiceName() != null && !itemReq.getCustomServiceName().trim().isEmpty())
                    ? itemReq.getCustomServiceName().trim()
                    : (service.getServiceName() != null ? service.getServiceName() : "Bước thủ thuật"));
        item.setStepName(stepName);
        item.setToothNumbers(itemReq.getToothNumbers());

        BigDecimal qty = (itemReq.getQuantity() != null && itemReq.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                ? itemReq.getQuantity() : BigDecimal.ONE;
        item.setQuantity(qty);

        BigDecimal price = itemReq.getEstimatedPrice();
        if (price == null) {
            price = service.getBasePrice() != null ? service.getBasePrice() : BigDecimal.ZERO;
        } else if (price.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Đơn giá bước thủ thuật không được nhỏ hơn 0.");
        }
        item.setEstimatedPrice(price);

        TreatmentPlanItemStatus itemStatus = TreatmentPlanItemStatus.PLANNED;
        if (itemReq.getStatus() != null) {
            try {
                itemStatus = TreatmentPlanItemStatus.valueOf(itemReq.getStatus().toUpperCase());
            } catch (Exception ignored) {}
        }
        item.setStatus(itemStatus);
        return item;
    }

    private com.cvdcms.entity.Service resolveServiceForPlanItem(
            TreatmentPlanItemRequestDTO itemReq,
            List<com.cvdcms.entity.Service> allServices) {
        com.cvdcms.entity.Service service = null;
        if (itemReq.getServiceId() != null) {
            service = serviceRepository.findById(itemReq.getServiceId()).orElse(null);
        }
        if (service == null && itemReq.getCustomServiceName() != null && !itemReq.getCustomServiceName().trim().isEmpty()) {
            String custom = itemReq.getCustomServiceName().trim().toLowerCase();
            service = allServices.stream()
                    .filter(s -> s.getServiceName() != null
                            && (s.getServiceName().toLowerCase().contains(custom) || custom.contains(s.getServiceName().toLowerCase())))
                    .findFirst()
                    .orElse(null);
        }
        if (service == null && !allServices.isEmpty()) {
            service = allServices.get(0);
        }
        if (service == null) {
            throw new IllegalArgumentException("Hệ thống chưa có danh mục dịch vụ điều trị nào để gắn vào kế hoạch.");
        }
        return service;
    }

    private BigDecimal reconcilePlanStagesForUpdate(
            TreatmentPlan plan,
            List<TreatmentPlanStageRequestDTO> stageReqs,
            LocalDate startDate) {
        List<TreatmentPlanStage> existingStages = plan.getStages() != null ? plan.getStages() : new ArrayList<>();
        Map<Long, TreatmentPlanItem> existingItemsById = new HashMap<>();
        Set<Long> lockedItemIds = new LinkedHashSet<>();
        for (TreatmentPlanStage es : existingStages) {
            if (es.getItems() == null) continue;
            for (TreatmentPlanItem it : es.getItems()) {
                if (it.getPlanItemId() != null) {
                    existingItemsById.put(it.getPlanItemId(), it);
                    if (isPlanItemLockedForEdit(it)) {
                        lockedItemIds.add(it.getPlanItemId());
                    }
                }
            }
        }

        List<com.cvdcms.entity.Service> allServices = serviceRepository.findAll();

        if (lockedItemIds.isEmpty()) {
            plan.getStages().clear();
            treatmentPlanRepository.flush();
            BigDecimal totalEstimated = BigDecimal.ZERO;
            if (stageReqs != null) {
                int stageOrder = 1;
                for (TreatmentPlanStageRequestDTO stageReq : stageReqs) {
                    int resolvedOrder = stageReq.getStageOrder() != null ? stageReq.getStageOrder() : stageOrder++;
                    TreatmentPlanStage stage = new TreatmentPlanStage();
                    stage.setPlan(plan);
                    stage.setStageOrder(resolvedOrder);
                    stage.setStageName(stageReq.getStageName() != null && !stageReq.getStageName().trim().isEmpty()
                            ? stageReq.getStageName().trim() : "Giai đoạn " + resolvedOrder);
                    stage.setDescription(stageReq.getDescription());
                    if (stageReq.getEstimatedDaysFromStart() != null && stageReq.getEstimatedDaysFromStart() >= 0) {
                        stage.setExpectedEndDate(startDate.plusDays(stageReq.getEstimatedDaysFromStart()));
                    }
                    TreatmentPlanStageStatus stageStatus = TreatmentPlanStageStatus.PENDING;
                    if (stageReq.getStatus() != null) {
                        try {
                            stageStatus = TreatmentPlanStageStatus.valueOf(stageReq.getStatus().toUpperCase());
                        } catch (Exception ignored) {}
                    }
                    stage.setStatus(stageStatus);
                    if (stageReq.getItems() != null) {
                        for (TreatmentPlanItemRequestDTO itemReq : stageReq.getItems()) {
                            TreatmentPlanItem item = buildPlanItemEntity(stage, plan, itemReq, allServices);
                            totalEstimated = totalEstimated.add(item.getEstimatedPrice().multiply(item.getQuantity()));
                            stage.getItems().add(item);
                        }
                    }
                    plan.getStages().add(stage);
                }
            }
            return totalEstimated;
        }

        Set<Long> retainedLockedIds = new HashSet<>();
        if (stageReqs != null) {
            for (TreatmentPlanStageRequestDTO sr : stageReqs) {
                if (sr.getItems() == null) continue;
                for (TreatmentPlanItemRequestDTO ir : sr.getItems()) {
                    if (ir.getPlanItemId() != null && lockedItemIds.contains(ir.getPlanItemId())) {
                        retainedLockedIds.add(ir.getPlanItemId());
                    }
                }
            }
        }
        if (!retainedLockedIds.containsAll(lockedItemIds)) {
            throw new IllegalStateException("Không thể xóa bước thủ thuật đang thực hiện hoặc đã hoàn thành trong ca khám!");
        }

        Map<Long, TreatmentPlanStage> existingStagesById = new HashMap<>();
        for (TreatmentPlanStage es : existingStages) {
            if (es.getStageId() != null) {
                existingStagesById.put(es.getStageId(), es);
            }
        }

        // Shift stageOrder temporarily so uk_tps (plan_id, stage_order) never collides during updates
        int tempOffset = 10000;
        for (TreatmentPlanStage es : existingStages) {
            es.setStageOrder(tempOffset++);
        }
        treatmentPlanRepository.flush();

        Set<Long> keptStageIds = new HashSet<>();
        List<TreatmentPlanStage> rebuiltStages = new ArrayList<>();
        BigDecimal totalEstimated = BigDecimal.ZERO;

        if (stageReqs != null) {
            int stageOrder = 1;
            for (TreatmentPlanStageRequestDTO stageReq : stageReqs) {
                int resolvedOrder = stageReq.getStageOrder() != null ? stageReq.getStageOrder() : stageOrder++;
                TreatmentPlanStage stage = (stageReq.getStageId() != null && existingStagesById.containsKey(stageReq.getStageId()))
                        ? existingStagesById.get(stageReq.getStageId())
                        : new TreatmentPlanStage();
                if (stage.getStageId() != null) {
                    keptStageIds.add(stage.getStageId());
                }
                stage.setPlan(plan);
                stage.setStageOrder(resolvedOrder);
                stage.setStageName(stageReq.getStageName() != null && !stageReq.getStageName().trim().isEmpty()
                        ? stageReq.getStageName().trim() : "Giai đoạn " + resolvedOrder);
                stage.setDescription(stageReq.getDescription());
                if (stageReq.getEstimatedDaysFromStart() != null && stageReq.getEstimatedDaysFromStart() >= 0) {
                    stage.setExpectedEndDate(startDate.plusDays(stageReq.getEstimatedDaysFromStart()));
                }

                List<TreatmentPlanItem> updatedItems = new ArrayList<>();
                boolean hasPendingItem = false;
                boolean hasInProgressItem = false;
                boolean hasCompletedItem = false;
                if (stageReq.getItems() != null) {
                    for (TreatmentPlanItemRequestDTO itemReq : stageReq.getItems()) {
                        TreatmentPlanItem existingItem = itemReq.getPlanItemId() != null ? existingItemsById.get(itemReq.getPlanItemId()) : null;
                        TreatmentPlanItem item;
                        if (existingItem != null && lockedItemIds.contains(existingItem.getPlanItemId())) {
                            // Keep executed/completed item immutable while allowing stage assignment to persist cleanly
                            item = existingItem;
                            item.setStage(stage);
                        } else {
                            item = buildPlanItemEntity(stage, plan, itemReq, allServices);
                        }
                        BigDecimal qty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ONE;
                        BigDecimal price = item.getEstimatedPrice() != null ? item.getEstimatedPrice() : BigDecimal.ZERO;
                        if (item.getStatus() != TreatmentPlanItemStatus.CANCELLED) {
                            totalEstimated = totalEstimated.add(price.multiply(qty));
                        }
                        if (item.getStatus() == TreatmentPlanItemStatus.COMPLETED) {
                            hasCompletedItem = true;
                        } else if (item.getStatus() == TreatmentPlanItemStatus.IN_PROGRESS) {
                            hasInProgressItem = true;
                        } else if (item.getStatus() != TreatmentPlanItemStatus.CANCELLED) {
                            hasPendingItem = true;
                        }
                        updatedItems.add(item);
                    }
                }
                if (stage.getItems() == null) {
                    stage.setItems(new ArrayList<>());
                }
                stage.getItems().clear();
                stage.getItems().addAll(updatedItems);
                if (hasCompletedItem && !hasInProgressItem && !hasPendingItem) {
                    stage.setStatus(TreatmentPlanStageStatus.COMPLETED);
                    if (stage.getCompletedAt() == null) {
                        stage.setCompletedAt(LocalDateTime.now());
                    }
                } else if (hasCompletedItem || hasInProgressItem) {
                    stage.setStatus(TreatmentPlanStageStatus.IN_PROGRESS);
                    stage.setCompletedAt(null);
                } else {
                    stage.setStatus(TreatmentPlanStageStatus.PENDING);
                    stage.setCompletedAt(null);
                }
                rebuiltStages.add(stage);
            }
        }

        existingStages.removeIf(es -> es.getStageId() != null && !keptStageIds.contains(es.getStageId()));
        treatmentPlanRepository.flush();
        plan.getStages().clear();
        plan.getStages().addAll(rebuiltStages);
        return totalEstimated;
    }

    private void applyInstallmentSchedule(
            TreatmentPlan plan,
            List<TreatmentPlanInstallmentDTO> installmentDTOs,
            boolean forceInstallmentFlag) {
        if (forceInstallmentFlag) {
            plan.setIsInstallment(true);
        }

        Map<Integer, Boolean> prevReminded = new HashMap<>();
        Map<Integer, LocalDateTime> prevRemindedAt = new HashMap<>();
        if (plan.getInstallments() != null) {
            for (TreatmentPlanInstallment oldInst : plan.getInstallments()) {
                if (oldInst.getInstallmentOrder() != null && Boolean.TRUE.equals(oldInst.getReminded())) {
                    prevReminded.put(oldInst.getInstallmentOrder(), true);
                    prevRemindedAt.put(oldInst.getInstallmentOrder(), oldInst.getRemindedAt());
                }
            }
        }

        BigDecimal totalScheduled = BigDecimal.ZERO;
        List<TreatmentPlanInstallment> newList = new ArrayList<>();
        if (installmentDTOs != null) {
            int order = 1;
            for (TreatmentPlanInstallmentDTO dto : installmentDTOs) {
                BigDecimal expAmount = dto.getExpectedAmount() != null ? dto.getExpectedAmount() : BigDecimal.ZERO;
                if (expAmount.compareTo(BigDecimal.ZERO) < 0) {
                    throw new IllegalArgumentException("Số tiền dự kiến từng đợt không được nhỏ hơn 0!");
                }
                totalScheduled = totalScheduled.add(expAmount);

                TreatmentPlanInstallment inst = new TreatmentPlanInstallment();
                inst.setPlan(plan);
                int instOrder = dto.getInstallmentOrder() != null ? dto.getInstallmentOrder() : order++;
                inst.setInstallmentOrder(instOrder);
                inst.setPhaseName(dto.getPhaseName() != null && !dto.getPhaseName().isBlank()
                        ? dto.getPhaseName() : "Đợt " + instOrder);

                LocalDate expDate = parseLocalDateSafe(
                        dto.getExpectedDateRaw() != null && !dto.getExpectedDateRaw().isBlank()
                                ? dto.getExpectedDateRaw()
                                : dto.getExpectedDate(),
                        null);
                inst.setExpectedDate(expDate);
                inst.setExpectedAmount(expAmount);
                inst.setPaidAmount(BigDecimal.ZERO);
                inst.setStatus(com.cvdcms.entity.enums.TreatmentPlanInstallmentStatus.PENDING);
                inst.setNotes(dto.getNotes());
                if (Boolean.TRUE.equals(prevReminded.get(instOrder))) {
                    inst.setReminded(true);
                    inst.setRemindedAt(prevRemindedAt.get(instOrder));
                }
                newList.add(inst);
            }
        }

        BigDecimal effectiveCost = plan.getEstimatedCost();
        if (effectiveCost.compareTo(BigDecimal.ZERO) > 0 && totalScheduled.compareTo(effectiveCost) > 0) {
            throw new IllegalArgumentException("Tổng tiền các đợt thanh toán không được vượt quá tổng chi phí phác đồ!");
        }

        plan.getInstallments().clear();
        plan.getInstallments().addAll(newList);
        recalculateInstallmentsInternal(plan);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TreatmentPlanInstallmentDTO> searchUpcomingInstallments(
            Long branchId, LocalDate startDate, LocalDate endDate, String statusStr, String keyword, Pageable pageable) {
        com.cvdcms.entity.enums.TreatmentPlanInstallmentStatus status = null;
        if (statusStr != null && !statusStr.isBlank() && !"ALL".equalsIgnoreCase(statusStr)) {
            try {
                status = com.cvdcms.entity.enums.TreatmentPlanInstallmentStatus.valueOf(statusStr.toUpperCase());
            } catch (Exception ignored) {}
        }
        Page<TreatmentPlanInstallment> page = treatmentPlanInstallmentRepository.searchUpcomingInstallments(
                branchId, startDate, endDate, status, keyword, pageable);
        return page.map(inst -> mapInstallmentToDTO(inst, inst.getPlan()));
    }

    private String extractDiagnosisFromNotes(String notes) {
        if (notes == null || notes.isEmpty()) return null;
        int start = notes.indexOf("[Chẩn đoán: ");
        if (start >= 0) {
            int end = notes.indexOf("]", start);
            if (end > start) {
                return notes.substring(start + 12, end).trim();
            }
        }
        return null;
    }

    private String getPlanStatusBadgeClass(TreatmentPlanStatus status) {
        if (status == null) return "bg-secondary";
        return switch (status) {
            case DRAFT -> "bg-secondary text-white";
            case PROPOSED -> "bg-warning text-dark";
            case ACCEPTED -> "bg-info text-white";
            case IN_PROGRESS -> "bg-primary text-white";
            case COMPLETED -> "bg-success text-white";
            case CANCELLED -> "bg-danger text-white";
        };
    }

    private String getPlanStatusDisplayName(TreatmentPlanStatus status) {
        if (status == null) return "Bản nháp";
        return switch (status) {
            case DRAFT -> "Bản nháp";
            case PROPOSED -> "Chờ BN duyệt";
            case ACCEPTED -> "BN đã đồng ý";
            case IN_PROGRESS -> "Đang điều trị";
            case COMPLETED -> "Đã hoàn thành";
            case CANCELLED -> "Đã hủy";
        };
    }

    private String getStageStatusBadgeClass(TreatmentPlanStageStatus status) {
        if (status == null) return "bg-secondary";
        return switch (status) {
            case PENDING -> "bg-light text-secondary border";
            case IN_PROGRESS -> "bg-primary text-white";
            case COMPLETED -> "bg-success text-white";
            case SKIPPED -> "bg-dark text-white";
        };
    }

    private String getStageStatusDisplayName(TreatmentPlanStageStatus status) {
        if (status == null) return "Chưa thực hiện";
        return switch (status) {
            case PENDING -> "Chưa thực hiện";
            case IN_PROGRESS -> "Đang thực hiện";
            case COMPLETED -> "Hoàn thành";
            case SKIPPED -> "Đã bỏ qua";
        };
    }

    private String getItemStatusBadgeClass(TreatmentPlanItemStatus status) {
        if (status == null) return "bg-secondary";
        return switch (status) {
            case PLANNED -> "bg-light text-secondary border";
            case IN_PROGRESS -> "bg-primary text-white";
            case COMPLETED -> "bg-success text-white";
            case CANCELLED -> "bg-danger text-white";
        };
    }

    private String getItemStatusDisplayName(TreatmentPlanItemStatus status) {
        if (status == null) return "Chưa thực hiện";
        return switch (status) {
            case PLANNED -> "Chưa thực hiện";
            case IN_PROGRESS -> "Đang thực hiện";
            case COMPLETED -> "Hoàn thành";
            case CANCELLED -> "Đã hủy";
        };
    }
}

