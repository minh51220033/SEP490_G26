package com.cvdcms.service.impl;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.*;
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
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kế hoạch điều trị với ID: " + planId));
        return mapToResponseDTO(plan);
    }

    @Override
    @Transactional
    public TreatmentPlanResponseDTO createPlan(TreatmentPlanRequestDTO request) {
        if (request.getPatientId() == null) {
            throw new IllegalArgumentException("Vui lòng chỉ định bệnh nhân cho kế hoạch điều trị!");
        }

        PatientProfile patient = patientProfileRepository.findById(request.getPatientId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hồ sơ bệnh nhân ID: " + request.getPatientId()));

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
        plan.setPlanName(request.getPlanName() != null && !request.getPlanName().trim().isEmpty() 
                ? request.getPlanName().trim() : "Kế hoạch điều trị nha khoa");

        TreatmentPlanStatus planStatus = TreatmentPlanStatus.PROPOSED;
        if (request.getStatus() != null) {
            try {
                planStatus = TreatmentPlanStatus.valueOf(request.getStatus().toUpperCase());
            } catch (Exception ignored) {}
        }
        plan.setStatus(planStatus);

        LocalDate startDate = LocalDate.now();
        if (request.getStartDate() != null && !request.getStartDate().isEmpty()) {
            try {
                startDate = LocalDate.parse(request.getStartDate());
            } catch (Exception ignored) {}
        }
        plan.setStartDate(startDate);

        LocalDate expectedEndDate = startDate.plusDays(90);
        if (request.getExpectedEndDate() != null && !request.getExpectedEndDate().isEmpty()) {
            try {
                expectedEndDate = LocalDate.parse(request.getExpectedEndDate());
            } catch (Exception ignored) {}
        }
        plan.setExpectedEndDate(expectedEndDate);
        plan.setNotes(request.getNotes());

        BigDecimal totalEstimated = BigDecimal.ZERO;

        // Xử lý các giai đoạn (Stages) và chi tiết (Items)
        if (request.getStages() != null && !request.getStages().isEmpty()) {
            int stageOrder = 1;
            for (TreatmentPlanStageRequestDTO stageReq : request.getStages()) {
                TreatmentPlanStage stage = new TreatmentPlanStage();
                stage.setPlan(plan);
                stage.setStageOrder(stageReq.getStageOrder() != null ? stageReq.getStageOrder() : stageOrder++);
                stage.setStageName(stageReq.getStageName() != null && !stageReq.getStageName().trim().isEmpty() 
                        ? stageReq.getStageName().trim() : "Giai đoạn " + stage.getStageOrder());

                TreatmentPlanStageStatus stageStatus = TreatmentPlanStageStatus.PENDING;
                if (stageReq.getStatus() != null) {
                    try {
                        stageStatus = TreatmentPlanStageStatus.valueOf(stageReq.getStatus().toUpperCase());
                    } catch (Exception ignored) {}
                }
                stage.setStatus(stageStatus);

                if (stageReq.getItems() != null && !stageReq.getItems().isEmpty()) {
                    for (TreatmentPlanItemRequestDTO itemReq : stageReq.getItems()) {
                        TreatmentPlanItem item = new TreatmentPlanItem();
                        item.setStage(stage);
                        item.setPlan(plan);

                        com.cvdcms.entity.Service service = null;
                        if (itemReq.getServiceId() != null) {
                            service = serviceRepository.findById(itemReq.getServiceId()).orElse(null);
                        }
                        if (service == null) {
                            service = serviceRepository.findAll().stream().findFirst().orElse(null);
                        }
                        item.setService(service);

                        item.setToothNumbers(itemReq.getToothNumbers());
                        BigDecimal qty = (itemReq.getQuantity() != null && itemReq.getQuantity().compareTo(BigDecimal.ZERO) > 0) 
                                ? itemReq.getQuantity() : BigDecimal.ONE;
                        item.setQuantity(qty);

                        BigDecimal price = itemReq.getEstimatedPrice();
                        if (price == null) {
                            price = (service != null && service.getBasePrice() != null) 
                                    ? service.getBasePrice() : BigDecimal.ZERO;
                        }
                        item.setEstimatedPrice(price);

                        TreatmentPlanItemStatus itemStatus = TreatmentPlanItemStatus.PLANNED;
                        if (itemReq.getStatus() != null) {
                            try {
                                itemStatus = TreatmentPlanItemStatus.valueOf(itemReq.getStatus().toUpperCase());
                            } catch (Exception ignored) {}
                        }
                        item.setStatus(itemStatus);

                        BigDecimal itemTotal = price.multiply(qty);
                        totalEstimated = totalEstimated.add(itemTotal);

                        stage.getItems().add(item);
                    }
                }
                plan.getStages().add(stage);
            }
        }

        plan.setTotalEstimatedCost(totalEstimated);
        plan.setFinalEstimatedCost(request.getFinalEstimatedCost() != null 
                ? request.getFinalEstimatedCost() : totalEstimated);

        TreatmentPlan saved = treatmentPlanRepository.save(plan);
        log.info("Created TreatmentPlan ID: {}, Code: {}", saved.getPlanId(), saved.getPlanCode());
        return mapToResponseDTO(saved);
    }

    @Override
    @Transactional
    public TreatmentPlanResponseDTO updatePlanStatus(Long planId, String status) {
        TreatmentPlan plan = treatmentPlanRepository.findByIdWithDetails(planId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kế hoạch điều trị ID: " + planId));

        try {
            TreatmentPlanStatus newStatus = TreatmentPlanStatus.valueOf(status.toUpperCase());
            plan.setStatus(newStatus);
            TreatmentPlan updated = treatmentPlanRepository.save(plan);
            log.info("Updated TreatmentPlan ID: {} to status: {}", planId, newStatus);
            return mapToResponseDTO(updated);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Trạng thái kế hoạch không hợp lệ: " + status);
        }
    }

    @Override
    @Transactional
    public void deletePlan(Long planId) {
        TreatmentPlan plan = treatmentPlanRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kế hoạch điều trị ID: " + planId));
        treatmentPlanRepository.delete(plan);
        log.info("Deleted TreatmentPlan ID: {}", planId);
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

        TreatmentPlan plan = stage.getPlan();
        if (plan != null) {
            List<TreatmentPlanStage> allStages = plan.getStages();
            long completedCount = allStages.stream().filter(s -> s.getStatus() == TreatmentPlanStageStatus.COMPLETED).count();
            if (completedCount == allStages.size() && allStages.size() > 0) {
                plan.setStatus(TreatmentPlanStatus.COMPLETED);
            } else if (completedCount > 0 || newStatus == TreatmentPlanStageStatus.IN_PROGRESS) {
                if (plan.getStatus() == TreatmentPlanStatus.DRAFT || plan.getStatus() == TreatmentPlanStatus.PROPOSED || plan.getStatus() == TreatmentPlanStatus.ACCEPTED) {
                    plan.setStatus(TreatmentPlanStatus.IN_PROGRESS);
                }
            }
            treatmentPlanRepository.save(plan);
            return mapToResponseDTO(treatmentPlanRepository.findByIdWithDetails(plan.getPlanId()).orElse(plan));
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
            if (allCompleted) {
                stage.setStatus(TreatmentPlanStageStatus.COMPLETED);
                stage.setCompletedAt(now);
                treatmentPlanStageRepository.save(stage);
            } else if (items != null && items.stream().anyMatch(i -> i.getStatus() == TreatmentPlanItemStatus.COMPLETED || i.getStatus() == TreatmentPlanItemStatus.IN_PROGRESS)) {
                if (stage.getStatus() == TreatmentPlanStageStatus.PENDING) {
                    stage.setStatus(TreatmentPlanStageStatus.IN_PROGRESS);
                    treatmentPlanStageRepository.save(stage);
                }
            }
            TreatmentPlan plan = stage.getPlan();
            if (plan != null) {
                return mapToResponseDTO(treatmentPlanRepository.findByIdWithDetails(plan.getPlanId()).orElse(plan));
            }
        }
        return null;
    }

    @Override
    public AiParsePlanResponseDTO parsePlanWithAi(AiParsePlanRequestDTO request) {
        return geminiAiService.parseTreatmentPlan(request);
    }

    private TreatmentPlanResponseDTO mapToResponseDTO(TreatmentPlan p) {
        PatientProfile patient = p.getPatientProfile();
        Branch branch = p.getBranch();
        StaffProfile doctor = p.getDoctor();

        int totalStages = (p.getStages() != null) ? p.getStages().size() : 0;
        int completedStages = 0;

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
                        com.cvdcms.entity.Service svc = it.getService();
                        BigDecimal itemTotal = it.getEstimatedPrice().multiply(it.getQuantity());
                        stageTotalCost = stageTotalCost.add(itemTotal);

                        itemDTOs.add(TreatmentPlanItemResponseDTO.builder()
                                .planItemId(it.getPlanItemId())
                                .serviceId(svc != null ? svc.getServiceId() : null)
                                .serviceCode(svc != null ? svc.getServiceCode() : null)
                                .serviceName(svc != null ? svc.getServiceName() : "Dịch vụ nha khoa")
                                .categoryName(svc != null && svc.getCategory() != null ? svc.getCategory().getCategoryName() : null)
                                .unit(svc != null ? svc.getUnit() : "Lần")
                                .toothNumbers(it.getToothNumbers())
                                .quantity(it.getQuantity())
                                .estimatedPrice(it.getEstimatedPrice())
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

        int progressPercent = (totalStages > 0) ? (completedStages * 100 / totalStages) : 0;

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
                .totalEstimatedCost(p.getTotalEstimatedCost())
                .finalEstimatedCost(p.getFinalEstimatedCost())
                .status(p.getStatus() != null ? p.getStatus().name() : "DRAFT")
                .statusBadgeClass(getPlanStatusBadgeClass(p.getStatus()))
                .statusDisplayName(getPlanStatusDisplayName(p.getStatus()))
                .startDate(p.getStartDate() != null ? p.getStartDate().format(DATE_FORMATTER) : null)
                .expectedEndDate(p.getExpectedEndDate() != null ? p.getExpectedEndDate().format(DATE_FORMATTER) : null)
                .notes(p.getNotes())
                .createdAt(p.getCreatedAt() != null ? p.getCreatedAt().format(DATE_TIME_FORMATTER) : null)
                .totalStages(totalStages)
                .completedStages(completedStages)
                .progressPercent(progressPercent)
                .stages(stageDTOs)
                .build();
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
