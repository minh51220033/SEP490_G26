package com.cvdcms.service.impl;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.PerformedServiceStatus;
import com.cvdcms.entity.enums.TreatmentPlanItemStatus;
import com.cvdcms.entity.enums.TreatmentPlanStageStatus;
import com.cvdcms.entity.enums.TreatmentPlanStatus;
import com.cvdcms.repository.*;
import com.cvdcms.service.GeminiAiService;
import com.cvdcms.service.TreatmentPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
public class TreatmentPlanServiceImpl implements TreatmentPlanService {

    private final TreatmentPlanRepository treatmentPlanRepository;
    private final TreatmentPlanStageRepository treatmentPlanStageRepository;
    private final TreatmentPlanItemRepository treatmentPlanItemRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final BranchRepository branchRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final ServiceRepository serviceRepository;
    private final GeminiAiService geminiAiService;
    private final PerformedServiceRepository performedServiceRepository;
    private final InvoiceItemRepository invoiceItemRepository;

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
            int stageOrder = 1;
            for (TreatmentPlanStageRequestDTO stageReq : request.getStages()) {
                int resolvedOrder = stageReq.getStageOrder() != null ? stageReq.getStageOrder() : stageOrder++;
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

        BigDecimal overallCost = (request.getTotalEstimatedCost() != null && request.getTotalEstimatedCost().compareTo(BigDecimal.ZERO) > 0)
                ? request.getTotalEstimatedCost()
                : (request.getFinalEstimatedCost() != null && request.getFinalEstimatedCost().compareTo(BigDecimal.ZERO) > 0
                    ? request.getFinalEstimatedCost()
                    : totalEstimated);
        plan.setTotalEstimatedCost(overallCost);
        plan.setFinalEstimatedCost(request.getFinalEstimatedCost() != null && request.getFinalEstimatedCost().compareTo(BigDecimal.ZERO) > 0
                ? request.getFinalEstimatedCost() : overallCost);

<<<<<<< Updated upstream
=======
        plan.setIsInstallment(Boolean.TRUE.equals(request.getIsInstallment()));
        plan.setTotalPaidAmount(BigDecimal.ZERO);

        if (Boolean.TRUE.equals(request.getIsInstallment()) && request.getInstallments() != null && !request.getInstallments().isEmpty()) {
            applyInstallmentSchedule(plan, request.getInstallments(), false);
        }

>>>>>>> Stashed changes
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

        if (request.getBranchId() != null) {
            branchRepository.findById(request.getBranchId()).ifPresent(plan::setBranch);
        }
        if (request.getDoctorId() != null) {
            staffProfileRepository.findById(request.getDoctorId()).ifPresent(plan::setDoctor);
        }

        if (request.getPlanName() != null && !request.getPlanName().trim().isEmpty()) {
            plan.setPlanName(request.getPlanName().trim());
        }
        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            try {
                TreatmentPlanStatus newStatus = TreatmentPlanStatus.valueOf(request.getStatus().toUpperCase());
                if (plan.getStatus() != TreatmentPlanStatus.COMPLETED || newStatus == TreatmentPlanStatus.COMPLETED) {
                    plan.setStatus(newStatus);
                }
            } catch (Exception ignored) {}
        }

        LocalDate startDate = parseLocalDateSafe(request.getStartDate(),
                plan.getStartDate() != null ? plan.getStartDate() : LocalDate.now());
        plan.setStartDate(startDate);
        if (request.getExpectedEndDate() != null && !request.getExpectedEndDate().isBlank()) {
            plan.setExpectedEndDate(parseLocalDateSafe(request.getExpectedEndDate(), plan.getExpectedEndDate()));
        }
        plan.setNotes(buildPlanNotesWithDiagnosis(request.getNotes(), request.getDiagnosisText()));

        BigDecimal totalEstimated = reconcilePlanStagesForUpdate(plan, request.getStages(), startDate);

        BigDecimal overallCost = (request.getTotalEstimatedCost() != null && request.getTotalEstimatedCost().compareTo(BigDecimal.ZERO) > 0)
                ? request.getTotalEstimatedCost()
                : (request.getFinalEstimatedCost() != null && request.getFinalEstimatedCost().compareTo(BigDecimal.ZERO) > 0
                    ? request.getFinalEstimatedCost()
                    : totalEstimated);
        BigDecimal finalCost = (request.getFinalEstimatedCost() != null && request.getFinalEstimatedCost().compareTo(BigDecimal.ZERO) > 0)
                ? request.getFinalEstimatedCost()
                : overallCost;

        BigDecimal paidAmount = plan.getTotalPaidAmount() != null ? plan.getTotalPaidAmount() : BigDecimal.ZERO;
        if (finalCost.compareTo(paidAmount) < 0) {
            throw new IllegalArgumentException("Tổng chi phí phác đồ sau chỉnh sửa không được nhỏ hơn số tiền bệnh nhân đã thanh toán!");
        }

        plan.setTotalEstimatedCost(overallCost);
        plan.setFinalEstimatedCost(finalCost);

        boolean wantsInstallment = Boolean.TRUE.equals(request.getIsInstallment());
        if (!wantsInstallment && paidAmount.compareTo(BigDecimal.ZERO) > 0 && Boolean.TRUE.equals(plan.getIsInstallment())) {
            throw new IllegalStateException("Phác đồ đã ghi nhận thanh toán theo đợt, không thể chuyển sang thanh toán đơn lẻ!");
        }
        plan.setIsInstallment(wantsInstallment);
        if (wantsInstallment) {
            if (request.getInstallments() != null) {
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
        if (plan.getStages() != null) {
            for (TreatmentPlanStage stage : plan.getStages()) {
                if (stage.getItems() == null) continue;
                for (TreatmentPlanItem item : stage.getItems()) {
                    if (isPlanItemLockedForEdit(item)) {
                        throw new IllegalStateException("Không thể xóa kế hoạch điều trị đã có thủ thuật đang thực hiện hoặc đã hoàn thành trong ca khám!");
                    }
                }
            }
        }

        treatmentPlanRepository.delete(plan);
        log.info("Deleted TreatmentPlan {}", plan.getPlanCode());
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
            stage.setCompletedAt(null);
            if (stage.getItems() != null) {
                for (TreatmentPlanItem it : stage.getItems()) {
                    it.setStatus(TreatmentPlanItemStatus.PLANNED);
                    it.setCompletedAt(null);
                }
            }
        }

        treatmentPlanStageRepository.save(stage);

        if (stage.getPlan() != null) {
            return syncPlanProgressAndStatus(stage.getPlan().getPlanId());
        }

        if (false) { /*
            // cleaned
            // cleaned
            // cleaned

            // cleaned


                }
            }
            // plan save */
            // return null;
        }

        return null;
    }

    @Override
    @Transactional
    public TreatmentPlanResponseDTO updateItemStatus(Long planItemId, String statusStr) {
        TreatmentPlanItem item = treatmentPlanItemRepository.findById(planItemId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thủ thuật với ID: " + planItemId));

        TreatmentPlanItemStatus newStatus = TreatmentPlanItemStatus.valueOf(statusStr.toUpperCase());
        item.setStatus(newStatus);
        if (newStatus == TreatmentPlanItemStatus.PLANNED) {
            List<PerformedService> activeServices = performedServiceRepository.findByPlanItem_PlanItemIdAndStatusNot(
                    planItemId, PerformedServiceStatus.CANCELLED);
            for (PerformedService ps : activeServices) {
                boolean isBilled = invoiceItemRepository.existsByPerformedIdAndInvoiceNotCancelled(ps.getPerformedId());
                if (isBilled) {
                    throw new IllegalStateException("Bước thủ thuật này đã được lập hóa đơn viện phí, không thể hoàn tác!");
                }
                ps.setStatus(PerformedServiceStatus.CANCELLED);
                performedServiceRepository.save(ps);
            }
        }
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

            if (stage.getPlan() != null) {
            return syncPlanProgressAndStatus(stage.getPlan().getPlanId());
        }

            if (false) { /*
                if (false) { return null; }

                            .allMatch(s -> s.getStatus() == TreatmentPlanStageStatus.COMPLETED);

                            .anyMatch(s -> s.getStatus() == TreatmentPlanStageStatus.COMPLETED || s.getStatus() == TreatmentPlanStageStatus.IN_PROGRESS);


        
                        // noop

    
                        // noop
                    }
                }
                */ return null;
            }
        }
        return null;
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
        return mapToResponseDTO(treatmentPlanRepository.findByIdWithDetails(planId).orElse(plan));
    }

    public AiParsePlanResponseDTO parsePlanWithAi(AiParsePlanRequestDTO request) {
        return geminiAiService.parseTreatmentPlan(request);
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
                        BigDecimal estPrice = (it.getEstimatedPrice() != null && it.getEstimatedPrice().compareTo(BigDecimal.ZERO) > 0)
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
                .stages(stageDTOs)
                .build();
    }

<<<<<<< Updated upstream
=======
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
        applyInstallmentSchedule(plan, installmentDTOs, true);
        TreatmentPlan saved = treatmentPlanRepository.save(plan);
        return mapToResponseDTO(saved);
    }

    @Override
    @Transactional
    public TreatmentPlanResponseDTO recordInstallmentPayment(Long planId, BigDecimal amount, Long invoiceId) {
        TreatmentPlan plan = treatmentPlanRepository.findByIdWithDetails(planId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kế hoạch điều trị."));
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Số tiền thanh toán phải lớn hơn 0!");
        }

        BigDecimal currentPaid = plan.getTotalPaidAmount() != null ? plan.getTotalPaidAmount() : BigDecimal.ZERO;
        BigDecimal effectiveTotal = plan.getEstimatedCost();
        if (effectiveTotal.compareTo(BigDecimal.ZERO) > 0
                && currentPaid.add(amount).compareTo(effectiveTotal) > 0) {
            throw new IllegalArgumentException("Số tiền thanh toán vượt quá tổng chi phí còn lại của kế hoạch điều trị!");
        }
        plan.setTotalPaidAmount(currentPaid.add(amount));

        recalculateInstallmentsInternal(plan);

        TreatmentPlan saved = treatmentPlanRepository.save(plan);
        log.info("Ghi nhận thanh toán {} đ cho Kế hoạch điều trị {}. Tổng đã thu: {} đ",
                amount, saved.getPlanCode(), saved.getTotalPaidAmount());
        return mapToResponseDTO(saved);
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
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            price = service.getBasePrice() != null ? service.getBasePrice() : BigDecimal.ZERO;
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
                        totalEstimated = totalEstimated.add(price.multiply(qty));
                        if (item.getStatus() == TreatmentPlanItemStatus.COMPLETED) {
                            hasCompletedItem = true;
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
                if (hasCompletedItem && !hasPendingItem) {
                    stage.setStatus(TreatmentPlanStageStatus.COMPLETED);
                } else if (hasCompletedItem) {
                    stage.setStatus(TreatmentPlanStageStatus.IN_PROGRESS);
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

>>>>>>> Stashed changes
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

