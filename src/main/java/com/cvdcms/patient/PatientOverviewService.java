package com.cvdcms.patient;

import com.cvdcms.DTO.ClinicalPhotoResponseDTO;
import com.cvdcms.DTO.LaboOrderResponseDTO;
import com.cvdcms.DTO.PatientDebtSummaryDTO;
import com.cvdcms.emr.EmrAuditEvent;
import com.cvdcms.emr.EmrAuditEventRepository;
import com.cvdcms.emr.EmrRecord;
import com.cvdcms.emr.EmrRecordRepository;
import com.cvdcms.entity.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.ClinicalPhotoService;
import com.cvdcms.service.CustomerCareService;
import com.cvdcms.service.DebtService;
import com.cvdcms.service.LaboOrderService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class PatientOverviewService {

    private final UserRepository users;
    private final PatientProfileRepository profileRepository;
    private final ReceptionPatientRepository receptionProfiles;
    private final PatientSearchRepository searchRepository;
    private final BranchRepository branchRepository;
    private final DebtService debtService;
    private final PatientVisitReadRepository visitReadRepository;
    private final AppointmentRepository appointmentRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final CustomerCareService customerCareService;
    private final LaboOrderService laboOrderService;
    private final ClinicalPhotoService clinicalPhotoService;
    private final InvoiceRepository invoiceRepository;
    private final EmrRecordRepository emrRecordRepository;
    private final PatientDocumentStorageService documentStorageService;
    private final PatientAuditRepository patientAuditRepository;
    private final TreatmentPlanRepository treatmentPlanRepository;
    private final PerformedServiceRepository performedServiceRepository;
    private final ClinicalExaminationRepository clinicalExaminationRepository;
    private final ExaminationDiagnosisRepository examinationDiagnosisRepository;

    @Autowired(required = false)
    private AppointmentHistoryRepository appointmentHistoryRepository;
    @Autowired(required = false)
    private CustomerCareInteractionRepository customerCareInteractionRepository;
    @Autowired(required = false)
    private WorkflowExecutionRepository workflowExecutionRepository;
    @Autowired(required = false)
    private WorkflowStepExecutionRepository workflowStepExecutionRepository;
    @Autowired(required = false)
    private ParaclinicalOrderRepository paraclinicalOrderRepository;
    @Autowired(required = false)
    private LaboOrderRepository laboOrderRepository;
    @Autowired(required = false)
    private ClinicalPhotoRepository clinicalPhotoRepository;
    @Autowired(required = false)
    private PatientDocumentRepository patientDocumentRepository;
    @Autowired(required = false)
    private EmrAuditEventRepository emrAuditEventRepository;
    @Autowired(required = false)
    private InvoiceAuditLogRepository invoiceAuditLogRepository;
    @Autowired(required = false)
    private PaymentRepository paymentRepository;
    @Autowired(required = false)
    private ClinicalQueueTicketRepository queueTicketRepository;
    @Autowired(required = false)
    private StaffProfileRepository staffProfileRepository;
    @Autowired(required = false)
    private ObjectMapper objectMapper;

    public PatientOverviewService(UserRepository users,
                                  PatientProfileRepository profileRepository,
                                  ReceptionPatientRepository receptionProfiles,
                                  PatientSearchRepository searchRepository,
                                  BranchRepository branchRepository,
                                  DebtService debtService,
                                  PatientVisitReadRepository visitReadRepository,
                                  AppointmentRepository appointmentRepository,
                                  PrescriptionRepository prescriptionRepository,
                                  CustomerCareService customerCareService,
                                  LaboOrderService laboOrderService,
                                  ClinicalPhotoService clinicalPhotoService,
                                  InvoiceRepository invoiceRepository,
                                  EmrRecordRepository emrRecordRepository,
                                  PatientDocumentStorageService documentStorageService,
                                  PatientAuditRepository patientAuditRepository,
                                  TreatmentPlanRepository treatmentPlanRepository,
                                  PerformedServiceRepository performedServiceRepository,
                                  ClinicalExaminationRepository clinicalExaminationRepository,
                                  ExaminationDiagnosisRepository examinationDiagnosisRepository) {
        this.users = users;
        this.profileRepository = profileRepository;
        this.receptionProfiles = receptionProfiles;
        this.searchRepository = searchRepository;
        this.branchRepository = branchRepository;
        this.debtService = debtService;
        this.visitReadRepository = visitReadRepository;
        this.appointmentRepository = appointmentRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.customerCareService = customerCareService;
        this.laboOrderService = laboOrderService;
        this.clinicalPhotoService = clinicalPhotoService;
        this.invoiceRepository = invoiceRepository;
        this.emrRecordRepository = emrRecordRepository;
        this.documentStorageService = documentStorageService;
        this.patientAuditRepository = patientAuditRepository;
        this.treatmentPlanRepository = treatmentPlanRepository;
        this.performedServiceRepository = performedServiceRepository;
        this.clinicalExaminationRepository = clinicalExaminationRepository;
        this.examinationDiagnosisRepository = examinationDiagnosisRepository;
    }

    public User requireAdminOrManager(HttpSession session) {
        Object principal = session == null ? null : session.getAttribute("loggedInUser");
        if (!(principal instanceof User current) || current.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }
        User user = users.findById(current.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (!Boolean.TRUE.equals(user.getIsActive()) || !"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản bị khóa hoặc không hoạt động.");
        }
        boolean isAdmin = user.getRoles().stream().anyMatch(r ->
                Boolean.TRUE.equals(r.getIsActive()) && ("ADMIN".equalsIgnoreCase(r.getRoleName()) || "ROLE_ADMIN".equalsIgnoreCase(r.getRoleName())))
                || users.hasActiveAdminRole(user.getUserId());
        boolean hasPermission = users.hasActivePermission(user.getUserId(), "PATIENT_PROFILE_MANAGE");

        if (!isAdmin && !hasPermission) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chức năng dành cho Quản trị viên.");
        }
        return user;
    }

    public Page<PatientOverviewDTO> searchPatients(Long branchId, String query, LocalDate dob, String status, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        String cleanQuery = query != null && !query.isBlank() ? query.trim() : null;
        String cleanStatus = status != null && !status.isBlank() ? status.trim() : null;

        Page<ReceptionPatientRecord> records = searchRepository.search(
                branchId, cleanQuery, dob, cleanStatus,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "profileId"))
        );

        return records.map(r -> {
            PatientDebtSummaryDTO debt = null;
            try {
                debt = debtService.getPatientDebtDetail(r.getProfileId());
            } catch (Exception ignored) {
            }
            PatientOverviewDTO dto = new PatientOverviewDTO();
            dto.setId(r.getProfileId());
            dto.setCode(r.getPatientCode());
            dto.setFullName(r.getFullName());
            dto.setDob(r.getDob());
            dto.setGender(r.getGender());
            dto.setPhoneNumber(r.getPhoneNumber());
            dto.setAddress(r.getAddress());
            if (r.getPreferredBranch() != null) {
                dto.setBranchId(r.getPreferredBranch().getBranchId());
                dto.setBranchName(r.getPreferredBranch().getBranchName());
            }
            dto.setStatus(r.getStatus());
            dto.setCustomerSource(r.getCustomerSource() != null ? r.getCustomerSource() : "Trực tiếp");
            dto.setDepositBalance(r.getDepositBalance() != null ? r.getDepositBalance() : java.math.BigDecimal.ZERO);
            if (debt != null) {
                dto.setTotalBilled(debt.getTotalBilled());
                dto.setTotalPaid(debt.getTotalPaid());
                dto.setTotalDebt(debt.getTotalDebt());
                dto.setUnpaidInvoicesCount(debt.getUnpaidInvoicesCount());
                if (debt.getDepositBalance() != null && debt.getDepositBalance().compareTo(java.math.BigDecimal.ZERO) > 0) {
                    dto.setDepositBalance(debt.getDepositBalance());
                }
            }
            return dto;
        });
    }

    public PatientOverviewDTO getPatientOverview(Long profileId) {
        PatientDebtSummaryDTO debt = null;
        try {
            debt = debtService.getPatientDebtDetail(profileId);
        } catch (Exception ignored) {
        }

        PatientProfile profile = null;
        try {
            profile = profileRepository.findById(profileId).orElse(null);
        } catch (Exception ignored) {
            profile = null;
        }
        if (profile != null) {
            return PatientOverviewDTO.from(profile, debt);
        }

        ReceptionPatientRecord record = receptionProfiles.findById(profileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hồ sơ bệnh nhân ID: " + profileId));
        return PatientOverviewDTO.from(record, debt);
    }

    public List<Branch> getAllActiveBranches() {
        return branchRepository.findByIsActiveTrueOrderByBranchNameAsc();
    }

    public List<VisitView> getPatientVisits(Long profileId) {
        return visitReadRepository.forPatient(profileId).stream().map(VisitView::from).toList();
    }

    public List<Appointment> getPatientAppointments(Long profileId) {
        return appointmentRepository.findByProfileIdWithDetails(profileId);
    }

    public Map<String, Object> getPatientCareHistory(Long profileId, Long branchId, Long userId) {
        try {
            Map<String, Object> careMap = new LinkedHashMap<>(customerCareService.getPatientCareHistory(profileId, branchId, userId));
            Object interactions = careMap.get("interactions");
            careMap.put("timeline", interactions != null ? interactions : List.of());
            return careMap;
        } catch (Exception ex) {
            return Map.of("timeline", List.of(), "interactions", List.of(), "totalInteractions", 0, "totalTasks", 0);
        }
    }

    public List<Prescription> getPatientPrescriptions(Long profileId) {
        return prescriptionRepository.findByPatientProfile_ProfileIdOrderByCreatedAtDesc(profileId);
    }

    public List<LaboOrderResponseDTO> getPatientLaboOrders(Long profileId) {
        return laboOrderService.getOrdersByPatient(profileId);
    }

    public List<ClinicalPhotoResponseDTO> getPatientPhotos(Long profileId) {
        return clinicalPhotoService.getPhotosByProfile(profileId);
    }

    public List<Invoice> getPatientInvoices(Long profileId) {
        return invoiceRepository.findByPatientProfile_ProfileIdOrderByIssuedAtDesc(profileId);
    }

    public List<EmrRecord> getPatientEmrRecords(Long profileId) {
        return emrRecordRepository.findAllByPatientProfileId(profileId);
    }

    public List<PatientDocumentView> getPatientDocuments(Long profileId) {
        return documentStorageService.list(profileId);
    }

    public List<SystemAuditLog> getPatientAudits(Long profileId) {
        return patientAuditRepository.findByEntityTableAndEntityIdOrderByCreatedAtDesc("patient_profiles", profileId);
    }

    public List<TreatmentPlan> getPatientTreatmentPlans(Long profileId) {
        List<TreatmentPlan> plans = treatmentPlanRepository.findByProfileIdWithDetails(profileId);
        if (plans != null) {
            for (TreatmentPlan plan : plans) {
                if (plan.getStages() != null) {
                    plan.getStages().sort(Comparator.comparing(
                            TreatmentPlanStage::getStageOrder,
                            Comparator.nullsLast(Comparator.naturalOrder())));
                    for (TreatmentPlanStage stage : plan.getStages()) {
                        if (stage.getItems() != null) {
                            for (TreatmentPlanItem item : stage.getItems()) {
                                if (item.getService() != null) {
                                    item.getService().getServiceName();
                                }
                            }
                        }
                    }
                }
            }
        }
        return plans != null ? plans : List.of();
    }

    public List<PerformedService> getPatientPerformedServices(Long profileId) {
        return performedServiceRepository.findByProfileIdWithDetails(profileId);
    }

    public List<ExaminationDiagnosis> getPatientDiagnoses(Long profileId) {
        return examinationDiagnosisRepository.findByProfileIdWithDetails(profileId);
    }

    public List<ClinicalExamination> getPatientExaminations(Long profileId) {
        return clinicalExaminationRepository.findByProfileIdWithDetails(profileId);
    }

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private static final Map<String, String> AUDIT_FIELD_LABELS = Map.ofEntries(
            Map.entry("fullName", "Họ và tên"),
            Map.entry("phoneNumber", "Số điện thoại"),
            Map.entry("dob", "Ngày sinh"),
            Map.entry("gender", "Giới tính"),
            Map.entry("address", "Địa chỉ"),
            Map.entry("email", "Email"),
            Map.entry("patientCode", "Mã bệnh nhân"),
            Map.entry("allergyDetails", "Dị ứng"),
            Map.entry("underlyingDiseaseDetails", "Bệnh nền"),
            Map.entry("hasAllergies", "Có dị ứng"),
            Map.entry("hasUnderlyingDisease", "Có bệnh nền"),
            Map.entry("status", "Trạng thái"),
            Map.entry("queueStatus", "Trạng thái hàng chờ"),
            Map.entry("reason", "Lý do"),
            Map.entry("roomName", "Phòng khám"),
            Map.entry("chairName", "Ghế điều trị"),
            Map.entry("stepName", "Bước quy trình"),
            Map.entry("template", "Mẫu quy trình"),
            Map.entry("note", "Ghi chú"),
            Map.entry("type", "Loại tài liệu"),
            Map.entry("name", "Tên tệp"),
            Map.entry("visitCode", "Mã ca khám")
    );

    public List<DoctorRecordService.AuditLogRow> getUnifiedAuditLogs(Long profileId) {
        if (profileId == null) return List.of();
        List<DoctorRecordService.AuditLogRow> rows = new ArrayList<>();
        Map<Long, String> userIdNameCache = new HashMap<>();
        long syntheticIdSeq = 900_000L;

        // 1. Hồ sơ bệnh nhân & Giấy tờ tùy thân (system_audit_logs + patient_documents)
        Set<Long> loggedDocIds = new HashSet<>();
        if (patientAuditRepository != null) {
            try {
                List<SystemAuditLog> profileLogs = patientAuditRepository
                        .findByEntityTableAndEntityIdOrderByCreatedAtDesc("patient_profiles", profileId);
                for (SystemAuditLog l : profileLogs) {
                    Map<String, Object> oldMap = parseJsonMap(l.getOldValuesJson());
                    Map<String, Object> newMap = parseJsonMap(l.getNewValuesJson());
                    Object docIdObj = newMap.get("documentId");
                    if (docIdObj instanceof Number n) {
                        loggedDocIds.add(n.longValue());
                    }
                    String rawAction = l.getAction() != null ? l.getAction() : "UPDATE";
                    String actionGroup = normalizeActionGroup(rawAction);
                    String actionLabel = resolveActionLabel(rawAction, actionGroup);
                    String badge = badgeClassForGroup(actionGroup);
                    String actor = resolveUserActorName(l.getUser(), userIdNameCache, "Hệ thống");
                    String details = buildDynamicAuditDetails("Hồ sơ bệnh nhân", rawAction, oldMap, newMap);
                    rows.add(new DoctorRecordService.AuditLogRow(
                            l.getLogId() != null ? l.getLogId() : ++syntheticIdSeq,
                            l.getCreatedAt(),
                            actor,
                            actionGroup,
                            badge,
                            actionLabel,
                            "Hồ sơ bệnh nhân",
                            details
                    ));
                }
            } catch (Exception ignored) {
            }
        }

        if (patientDocumentRepository != null) {
            try {
                for (PatientDocument doc : patientDocumentRepository.findByPatientProfile_ProfileIdOrderByUploadedAtDesc(profileId)) {
                    if (doc.getDocumentId() != null && loggedDocIds.contains(doc.getDocumentId())) {
                        continue;
                    }
                    String actor = resolveUserActorName(doc.getUploadedBy(), userIdNameCache, "Nhân viên tiếp đón");
                    String docType = doc.getDocumentType() != null ? doc.getDocumentType().name() : "Giấy tờ";
                    String details = "Tải lên tài liệu " + docType + ": " + safeText(doc.getDocumentName(), "Bản scan")
                            + (doc.getNotes() != null && !doc.getNotes().isBlank() ? " (" + doc.getNotes().trim() + ")" : "");
                    rows.add(new DoctorRecordService.AuditLogRow(
                            ++syntheticIdSeq,
                            doc.getUploadedAt(),
                            actor,
                            "CREATE",
                            "bg-success",
                            "TẢI GIẤY TỜ",
                            "Hồ sơ bệnh nhân",
                            details
                    ));
                }
            } catch (Exception ignored) {
            }
        }

        // 2. Lịch hẹn & Lịch sử thay đổi lịch hẹn (appointments + appointment_histories)
        if (appointmentRepository != null) {
            try {
                List<Appointment> appts = appointmentRepository.findByProfileIdWithDetails(profileId);
                for (Appointment a : appts) {
                    String apptCode = safeText(a.getAppointmentCode(), "#" + a.getAppointmentId());
                    String branchName = a.getBranch() != null ? safeText(a.getBranch().getBranchName(), "") : "";
                    String serviceName = a.getService() != null ? safeText(a.getService().getServiceName(), "") : "";
                    String doctorName = a.getDoctor() != null ? safeText(a.getDoctor().getFullName(), "Chưa phân công BS") : "Chưa phân công BS";
                    boolean hasCreateHistory = false;
                    boolean hasCancelHistory = false;

                    if (appointmentHistoryRepository != null && a.getAppointmentId() != null) {
                        List<AppointmentHistory> histories = appointmentHistoryRepository
                                .findByAppointment_AppointmentIdOrderByCreatedAtAscHistoryIdAsc(a.getAppointmentId());
                        for (AppointmentHistory h : histories) {
                            String rawAct = safeText(h.getAction(), "UPDATE").toUpperCase();
                            if (rawAct.contains("CREATE") || rawAct.contains("BOOK")) hasCreateHistory = true;
                            if (rawAct.contains("CANCEL")) hasCancelHistory = true;
                            String actionGroup = normalizeActionGroup(rawAct);
                            String label = switch (rawAct) {
                                case "CREATE", "BOOK" -> "ĐẶT LỊCH";
                                case "CONFIRM", "CONFIRMED" -> "XÁC NHẬN LỊCH";
                                case "RESCHEDULE" -> "DỜI LỊCH HẸN";
                                case "CHECK_IN", "CHECKED_IN" -> "CHECK-IN HẸN";
                                case "CANCEL", "CANCELLED" -> "HỦY LỊCH HẸN";
                                case "NO_SHOW" -> "VẮNG MẶT";
                                default -> resolveActionLabel(rawAct, actionGroup);
                            };
                            String actor = resolveUserIdActorName(h.getActorUserId(), userIdNameCache, doctorName);
                            StringBuilder sb = new StringBuilder("Lịch hẹn ").append(apptCode);
                            if (!serviceName.isEmpty()) sb.append(" (").append(serviceName).append(")");
                            if (h.getOldValue() != null && !h.getOldValue().isBlank() && h.getNewValue() != null && !h.getNewValue().isBlank()) {
                                sb.append(": ").append(h.getOldValue()).append(" → ").append(h.getNewValue());
                            } else if (h.getNewValue() != null && !h.getNewValue().isBlank()) {
                                sb.append(": ").append(h.getNewValue());
                            }
                            if (h.getReason() != null && !h.getReason().isBlank()) {
                                sb.append(" — Lý do: ").append(h.getReason().trim());
                            }
                            rows.add(new DoctorRecordService.AuditLogRow(
                                    ++syntheticIdSeq,
                                    h.getCreatedAt() != null ? h.getCreatedAt() : a.getCreatedAt(),
                                    actor,
                                    actionGroup,
                                    badgeClassForGroup(actionGroup),
                                    label,
                                    "Lịch hẹn",
                                    sb.toString()
                            ));
                        }
                    }

                    if (!hasCreateHistory && a.getCreatedAt() != null) {
                        String dateStr = a.getAppointmentDate() != null ? a.getAppointmentDate().format(DATE_FMT) : "";
                        String timeStr = a.getExpectedTime() != null ? " " + a.getExpectedTime().format(TIME_FMT) : "";
                        StringBuilder sb = new StringBuilder("Đặt lịch hẹn ").append(apptCode)
                                .append(" ngày ").append(dateStr).append(timeStr);
                        if (!serviceName.isEmpty()) sb.append(" — Dịch vụ: ").append(serviceName);
                        if (!branchName.isEmpty()) sb.append(" tại ").append(branchName);
                        if (a.getInitialSymptoms() != null && !a.getInitialSymptoms().isBlank()) {
                            sb.append(" (Triệu chứng/Yêu cầu: ").append(a.getInitialSymptoms().trim()).append(")");
                        }
                        rows.add(new DoctorRecordService.AuditLogRow(
                                ++syntheticIdSeq,
                                a.getCreatedAt(),
                                doctorName,
                                "CREATE",
                                "bg-success",
                                "ĐẶT LỊCH",
                                "Lịch hẹn",
                                sb.toString()
                        ));
                    }

                    if (!hasCancelHistory && a.getCancelledAt() != null) {
                        String actor = resolveUserIdActorName(a.getCancelledBy(), userIdNameCache, "Lễ tân / Hệ thống");
                        String details = "Hủy lịch hẹn " + apptCode
                                + (a.getCancelReason() != null && !a.getCancelReason().isBlank() ? " — Lý do: " + a.getCancelReason().trim() : "");
                        rows.add(new DoctorRecordService.AuditLogRow(
                                ++syntheticIdSeq,
                                a.getCancelledAt(),
                                actor,
                                "DELETE",
                                "bg-danger",
                                "HỦY LỊCH HẸN",
                                "Lịch hẹn",
                                details
                        ));
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 3. Lượt khám, Hàng chờ & 4. Quy trình điều trị từng bước (visits + queue_tickets + workflow_executions + workflow_step_executions)
        List<Visit> patientVisits = visitReadRepository != null ? visitReadRepository.forPatient(profileId) : List.of();
        for (Visit v : patientVisits) {
            Long vid = v.getVisitId();
            String vCode = safeText(v.getVisitCode(), "#" + vid);
            String vDoctor = v.getPrimaryDoctor() != null ? safeText(v.getPrimaryDoctor().getFullName(), "Bác sĩ phụ trách") : "Bác sĩ phụ trách";
            String vBranch = v.getBranch() != null ? safeText(v.getBranch().getBranchName(), "Phòng khám") : "Phòng khám";
            boolean hasVisitCreateAudit = false;
            boolean hasVisitCompleteAudit = false;

            if (patientAuditRepository != null && vid != null) {
                try {
                    List<SystemAuditLog> visitLogs = patientAuditRepository
                            .findByEntityTableAndEntityIdOrderByCreatedAtDesc("visits", vid);
                    for (SystemAuditLog vl : visitLogs) {
                        String rawAct = safeText(vl.getAction(), "UPDATE").toUpperCase();
                        if (rawAct.contains("CREATE") || rawAct.contains("CHECK_IN")) hasVisitCreateAudit = true;
                        if (rawAct.contains("COMPLETE")) hasVisitCompleteAudit = true;
                        String actionGroup = normalizeActionGroup(rawAct);
                        String label = switch (rawAct) {
                            case "CHECK_IN", "CREATE", "VISIT_CHECK_IN" -> "TIẾP NHẬN";
                            case "REOPEN_CLINICAL" -> "MỞ LẠI KHÁM";
                            case "COMPLETE", "VISIT_COMPLETE" -> "HOÀN TẤT KHÁM";
                            case "CANCEL", "VISIT_CANCEL" -> "HỦY CA KHÁM";
                            default -> resolveActionLabel(rawAct, actionGroup);
                        };
                        String actor = resolveUserActorName(vl.getUser(), userIdNameCache, vDoctor);
                        Map<String, Object> oldMap = parseJsonMap(vl.getOldValuesJson());
                        Map<String, Object> newMap = parseJsonMap(vl.getNewValuesJson());
                        String details = "Ca khám " + vCode + " (" + vBranch + "): "
                                + buildDynamicAuditDetails("Lượt khám", rawAct, oldMap, newMap);
                        rows.add(new DoctorRecordService.AuditLogRow(
                                vl.getLogId() != null ? vl.getLogId() : ++syntheticIdSeq,
                                vl.getCreatedAt() != null ? vl.getCreatedAt() : v.getCheckInAt(),
                                actor,
                                actionGroup,
                                badgeClassForGroup(actionGroup),
                                label,
                                "Lượt khám",
                                details
                        ));
                    }
                } catch (Exception ignored) {
                }
            }

            if (!hasVisitCreateAudit && v.getCheckInAt() != null) {
                StringBuilder sb = new StringBuilder("Tiếp nhận ca khám ").append(vCode)
                        .append(" tại ").append(vBranch);
                if (v.getVisitType() != null) {
                    sb.append(" (Hình thức: ").append(v.getVisitType().name()).append(")");
                }
                if (v.getInitialSymptoms() != null && !v.getInitialSymptoms().isBlank()) {
                    sb.append(" — Triệu chứng ban đầu: ").append(v.getInitialSymptoms().trim());
                }
                rows.add(new DoctorRecordService.AuditLogRow(
                        ++syntheticIdSeq,
                        v.getCheckInAt(),
                        vDoctor,
                        "CREATE",
                        "bg-success",
                        "TIẾP NHẬN",
                        "Lượt khám",
                        sb.toString()
                ));
            }

            if (!hasVisitCompleteAudit && v.getCompletedAt() != null) {
                String details = "Hoàn tất ca khám lâm sàng " + vCode + " tại " + vBranch
                        + (v.getNotes() != null && !v.getNotes().isBlank() ? " — Lời dặn/Ghi chú: " + v.getNotes().trim() : "");
                rows.add(new DoctorRecordService.AuditLogRow(
                        ++syntheticIdSeq,
                        v.getCompletedAt(),
                        vDoctor,
                        "UPDATE",
                        "bg-primary",
                        "HOÀN TẤT KHÁM",
                        "Lượt khám",
                        details
                ));
            }

            if (queueTicketRepository != null && vid != null) {
                try {
                    for (com.cvdcms.entity.enums.QueueType qtType : com.cvdcms.entity.enums.QueueType.values()) {
                        List<QueueTicket> tickets = queueTicketRepository.findByVisitVisitIdAndQueueType(vid, qtType);
                        for (QueueTicket qt : tickets) {
                            String dispNo = safeText(qt.getDisplayNumber(), "#" + qt.getTicketNumber());
                            boolean hasQtAudit = false;
                            if (patientAuditRepository != null && qt.getTicketId() != null) {
                                List<SystemAuditLog> qtLogs = patientAuditRepository
                                        .findByEntityTableAndEntityIdOrderByCreatedAtDesc("queue_tickets", qt.getTicketId());
                                for (SystemAuditLog ql : qtLogs) {
                                    hasQtAudit = true;
                                    String rawAct = safeText(ql.getAction(), "UPDATE").toUpperCase();
                                    String actionGroup = normalizeActionGroup(rawAct);
                                    String label = rawAct.contains("CALL") || rawAct.contains("DISPATCH") ? "GỌI SỐ / ĐIỀU PHỐI"
                                            : (rawAct.contains("ISSUE") || rawAct.contains("CREATE") ? "CẤP SỐ CHỜ"
                                            : resolveActionLabel(rawAct, actionGroup));
                                    String actor = resolveUserActorName(ql.getUser(), userIdNameCache, "Lễ tân / Điều phối");
                                    Map<String, Object> oldMap = parseJsonMap(ql.getOldValuesJson());
                                    Map<String, Object> newMap = parseJsonMap(ql.getNewValuesJson());
                                    String details = "[" + vCode + "] Số thứ tự " + dispNo + " (" + qtType.name() + "): "
                                            + buildDynamicAuditDetails("Hàng chờ", rawAct, oldMap, newMap);
                                    rows.add(new DoctorRecordService.AuditLogRow(
                                            ql.getLogId() != null ? ql.getLogId() : ++syntheticIdSeq,
                                            ql.getCreatedAt() != null ? ql.getCreatedAt() : qt.getIssuedAt(),
                                            actor,
                                            actionGroup,
                                            badgeClassForGroup(actionGroup),
                                            label,
                                            "Lượt khám",
                                            details
                                    ));
                                }
                            }
                            if (!hasQtAudit && qt.getCalledAt() != null) {
                                rows.add(new DoctorRecordService.AuditLogRow(
                                        ++syntheticIdSeq,
                                        qt.getCalledAt(),
                                        vDoctor,
                                        "UPDATE",
                                        "bg-primary",
                                        "GỌI VÀO KHÁM",
                                        "Lượt khám",
                                        "[" + vCode + "] Gọi số thứ tự " + dispNo + " (" + qtType.name() + ") vào phòng khám"
                                ));
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            // 4. Quy trình điều trị từng bước (workflow_executions & workflow_step_executions)
            if (workflowExecutionRepository != null && vid != null) {
                try {
                    List<WorkflowExecution> executions = workflowExecutionRepository.findByVisit_VisitId(vid);
                    for (WorkflowExecution ex : executions) {
                        Long execId = ex.getExecutionId();
                        String tplName = ex.getVersion() != null && ex.getVersion().getTemplate() != null
                                ? safeText(ex.getVersion().getTemplate().getTemplateName(), "Quy trình lâm sàng")
                                : "Quy trình lâm sàng";
                        String svcName = ex.getVersion() != null && ex.getVersion().getTemplate() != null
                                && ex.getVersion().getTemplate().getService() != null
                                ? safeText(ex.getVersion().getTemplate().getService().getServiceName(), tplName)
                                : tplName;

                        boolean hasExecAudit = false;
                        if (patientAuditRepository != null && execId != null) {
                            List<SystemAuditLog> execLogs = patientAuditRepository
                                    .findByEntityTableAndEntityIdOrderByCreatedAtDesc("workflow_executions", execId);
                            for (SystemAuditLog el : execLogs) {
                                hasExecAudit = true;
                                String rawAct = safeText(el.getAction(), "WORKFLOW_UPDATE").toUpperCase();
                                String actionGroup = rawAct.contains("CREATE") ? "CREATE" : (rawAct.contains("CANCEL") ? "DELETE" : "UPDATE");
                                String label = switch (rawAct) {
                                    case "WORKFLOW_CREATE" -> "KHỞI TẠO QT";
                                    case "WORKFLOW_COMPLETE" -> "HOÀN TẤT QT";
                                    default -> "CẬP NHẬT QT";
                                };
                                String actor = resolveUserActorName(el.getUser(), userIdNameCache, vDoctor);
                                String details = "[" + vCode + "] " + label + " \"" + tplName + "\" (Dịch vụ: " + svcName + ")";
                                rows.add(new DoctorRecordService.AuditLogRow(
                                        el.getLogId() != null ? el.getLogId() : ++syntheticIdSeq,
                                        el.getCreatedAt() != null ? el.getCreatedAt() : ex.getStartedAt(),
                                        actor,
                                        actionGroup,
                                        badgeClassForGroup(actionGroup),
                                        label,
                                        "Quy trình điều trị",
                                        details
                                ));
                            }
                        }

                        if (!hasExecAudit && ex.getStartedAt() != null) {
                            rows.add(new DoctorRecordService.AuditLogRow(
                                    ++syntheticIdSeq,
                                    ex.getStartedAt(),
                                    vDoctor,
                                    "CREATE",
                                    "bg-success",
                                    "KHỞI TẠO QT",
                                    "Quy trình điều trị",
                                    "[" + vCode + "] Khởi tạo quy trình \"" + tplName + "\" cho dịch vụ " + svcName
                            ));
                        }

                        if (workflowStepExecutionRepository != null && execId != null) {
                            List<WorkflowStepExecution> stepExecs = workflowStepExecutionRepository
                                    .findByExecution_ExecutionIdOrderByStep_SequenceOrderAsc(execId);
                            for (WorkflowStepExecution se : stepExecs) {
                                boolean hasStepAudit = false;
                                String stepOrder = se.getStep() != null && se.getStep().getSequenceOrder() != null
                                        ? "Bước " + se.getStep().getSequenceOrder() + ": " : "Bước: ";
                                String fallbackStepName = se.getStep() != null ? safeText(se.getStep().getStepName(), "Bước kỹ thuật") : "Bước kỹ thuật";
                                String stepStaff = se.getAssignedStaff() != null
                                        ? safeText(se.getAssignedStaff().getFullName(), vDoctor)
                                        : vDoctor;

                                if (patientAuditRepository != null && se.getStepExecutionId() != null) {
                                    List<SystemAuditLog> stepLogs = patientAuditRepository
                                            .findByEntityTableAndEntityIdOrderByCreatedAtDesc("workflow_step_executions", se.getStepExecutionId());
                                    for (SystemAuditLog sl : stepLogs) {
                                        hasStepAudit = true;
                                        String rawAct = safeText(sl.getAction(), "WORKFLOW_STEP_UPDATE").toUpperCase();
                                        Map<String, Object> newMap = parseJsonMap(sl.getNewValuesJson());
                                        String stepName = safeText(asString(newMap.get("stepName")), fallbackStepName);
                                        String note = asString(newMap.get("note"));
                                        String actionGroup = rawAct.contains("SKIP") ? "DELETE"
                                                : (rawAct.contains("START") ? "CREATE" : "UPDATE");
                                        String label = switch (rawAct) {
                                            case "WORKFLOW_STEP_START" -> "BẮT ĐẦU BƯỚC";
                                            case "WORKFLOW_STEP_COMPLETE" -> "HOÀN TẤT BƯỚC";
                                            case "WORKFLOW_STEP_PAUSE" -> "TẠM DỪNG BƯỚC";
                                            case "WORKFLOW_STEP_RESUME" -> "TIẾP TỤC BƯỚC";
                                            case "WORKFLOW_STEP_SKIP" -> "BỎ QUA BƯỚC";
                                            default -> "CẬP NHẬT BƯỚC";
                                        };
                                        String actor = resolveUserActorName(sl.getUser(), userIdNameCache, stepStaff);
                                        StringBuilder sb = new StringBuilder("[").append(vCode).append("] ")
                                                .append(svcName).append(" — ").append(stepOrder).append(stepName);
                                        if (note != null && !note.isBlank()) {
                                            sb.append(" (Ghi chú: ").append(note.trim()).append(")");
                                        }
                                        rows.add(new DoctorRecordService.AuditLogRow(
                                                sl.getLogId() != null ? sl.getLogId() : ++syntheticIdSeq,
                                                sl.getCreatedAt() != null ? sl.getCreatedAt() : (se.getCompletedAt() != null ? se.getCompletedAt() : se.getStartedAt()),
                                                actor,
                                                actionGroup,
                                                badgeClassForGroup(actionGroup),
                                                label,
                                                "Quy trình điều trị",
                                                sb.toString()
                                        ));
                                    }
                                }

                                if (!hasStepAudit && (se.getStartedAt() != null || se.getCompletedAt() != null)) {
                                    String stName = se.getStatus() != null ? se.getStatus().name() : "IN_PROGRESS";
                                    String actionGroup = "SKIPPED".equals(stName) ? "DELETE" : "UPDATE";
                                    String label = switch (stName) {
                                        case "COMPLETED" -> "HOÀN TẤT BƯỚC";
                                        case "PAUSED" -> "TẠM DỪNG BƯỚC";
                                        case "SKIPPED" -> "BỎ QUA BƯỚC";
                                        default -> "THỰC HIỆN BƯỚC";
                                    };
                                    StringBuilder sb = new StringBuilder("[").append(vCode).append("] ")
                                            .append(svcName).append(" — ").append(stepOrder).append(fallbackStepName);
                                    if (se.getResultNote() != null && !se.getResultNote().isBlank()) {
                                        sb.append(" (Kết quả/Ghi chú: ").append(se.getResultNote().trim()).append(")");
                                    }
                                    rows.add(new DoctorRecordService.AuditLogRow(
                                            ++syntheticIdSeq,
                                            se.getCompletedAt() != null ? se.getCompletedAt() : se.getStartedAt(),
                                            stepStaff,
                                            actionGroup,
                                            badgeClassForGroup(actionGroup),
                                            label,
                                            "Quy trình điều trị",
                                            sb.toString()
                                    ));
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            // 6a. Chỉ định & Kết quả Cận lâm sàng (paraclinical_orders)
            if (paraclinicalOrderRepository != null && vid != null) {
                try {
                    List<ParaclinicalOrder> orders = paraclinicalOrderRepository.findByVisit_VisitIdOrderByOrderTimeDesc(vid);
                    for (ParaclinicalOrder po : orders) {
                        String orderCode = safeText(po.getOrderCode(), "CLS-" + po.getOrderId());
                        String orderDoctor = po.getDoctor() != null ? safeText(po.getDoctor().getFullName(), vDoctor) : vDoctor;
                        List<String> itemNames = new ArrayList<>();
                        if (po.getOrderItems() != null) {
                            for (ParaclinicalOrderItem item : po.getOrderItems()) {
                                String sName = item.getService() != null ? safeText(item.getService().getServiceName(), "Chỉ định CLS") : "Chỉ định CLS";
                                if (item.getToothNumbers() != null && !item.getToothNumbers().isBlank()) {
                                    sName += " (Răng " + item.getToothNumbers().trim() + ")";
                                }
                                itemNames.add(sName);
                                if (item.getPerformedAt() != null) {
                                    String tech = item.getTechnician() != null ? safeText(item.getTechnician().getFullName(), orderDoctor) : orderDoctor;
                                    String resDetail = "[" + vCode + "] Trả kết quả cận lâm sàng " + sName
                                            + (item.getResultSummary() != null && !item.getResultSummary().isBlank()
                                                ? ": " + item.getResultSummary().trim() : "");
                                    rows.add(new DoctorRecordService.AuditLogRow(
                                            ++syntheticIdSeq,
                                            item.getPerformedAt(),
                                            tech,
                                            "UPDATE",
                                            "bg-primary",
                                            "KẾT QUẢ CLS",
                                            "Cận lâm sàng & Phim ảnh",
                                            resDetail
                                    ));
                                }
                            }
                        }
                        if (po.getOrderTime() != null) {
                            String detail = "[" + vCode + "] Lập phiếu chỉ định cận lâm sàng " + orderCode
                                    + (!itemNames.isEmpty() ? ": " + String.join(", ", itemNames) : "");
                            rows.add(new DoctorRecordService.AuditLogRow(
                                    ++syntheticIdSeq,
                                    po.getOrderTime(),
                                    orderDoctor,
                                    "CREATE",
                                    "bg-success",
                                    "CHỈ ĐỊNH CLS",
                                    "Cận lâm sàng & Phim ảnh",
                                    detail
                            ));
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }

        // 5. Khám lâm sàng, Chẩn đoán, Kế hoạch điều trị & Dịch vụ/Thủ thuật thực hiện
        if (clinicalExaminationRepository != null) {
            try {
                for (ClinicalExamination exam : clinicalExaminationRepository.findByProfileIdWithDetails(profileId)) {
                    String vCode = exam.getVisit() != null ? safeText(exam.getVisit().getVisitCode(), "") : "";
                    String dr = exam.getDoctor() != null ? safeText(exam.getDoctor().getFullName(), "Bác sĩ khám") : "Bác sĩ khám";
                    List<String> diags = new ArrayList<>();
                    if (exam.getExaminationDiagnoses() != null) {
                        for (ExaminationDiagnosis d : exam.getExaminationDiagnoses()) {
                            if (d.getDiagnosisText() != null && !d.getDiagnosisText().isBlank()) {
                                String dText = d.getDiagnosisText().trim();
                                if (d.getToothNumbers() != null && !d.getToothNumbers().isBlank()) {
                                    dText += " (Răng " + d.getToothNumbers().trim() + ")";
                                }
                                diags.add(dText);
                            }
                        }
                    }
                    StringBuilder sb = new StringBuilder();
                    if (!vCode.isEmpty()) sb.append("[").append(vCode).append("] ");
                    sb.append("Khám lâm sàng — Lý do khám: ").append(safeText(exam.getChiefComplaint(), "Khám nha khoa"));
                    if (!diags.isEmpty()) {
                        sb.append(" | Chẩn đoán: ").append(String.join("; ", diags));
                    } else if (exam.getSummaryAssessment() != null && !exam.getSummaryAssessment().isBlank()) {
                        sb.append(" | Nhận định: ").append(exam.getSummaryAssessment().trim());
                    }
                    rows.add(new DoctorRecordService.AuditLogRow(
                            ++syntheticIdSeq,
                            exam.getExamTime(),
                            dr,
                            "UPDATE",
                            "bg-primary",
                            "KHÁM & CHẨN ĐOÁN",
                            "Khám & Thủ thuật",
                            sb.toString()
                    ));
                }
            } catch (Exception ignored) {
            }
        }

        if (treatmentPlanRepository != null) {
            try {
                for (TreatmentPlan plan : treatmentPlanRepository.findByProfileIdWithDetails(profileId)) {
                    String dr = plan.getDoctor() != null ? safeText(plan.getDoctor().getFullName(), "Bác sĩ") : "Bác sĩ";
                    String costStr = formatVnd(plan.getEstimatedCost());
                    String detail = "Lập kế hoạch điều trị " + safeText(plan.getPlanCode(), "")
                            + ": " + safeText(plan.getPlanName(), "Phác đồ điều trị")
                            + " (Dự kiến: " + costStr + ", Trạng thái: " + (plan.getStatus() != null ? plan.getStatus().name() : "DRAFT") + ")";
                    rows.add(new DoctorRecordService.AuditLogRow(
                            ++syntheticIdSeq,
                            plan.getCreatedAt(),
                            dr,
                            "CREATE",
                            "bg-success",
                            "LẬP KẾ HOẠCH",
                            "Khám & Thủ thuật",
                            detail
                    ));
                }
            } catch (Exception ignored) {
            }
        }

        if (performedServiceRepository != null) {
            try {
                for (PerformedService ps : performedServiceRepository.findByProfileIdWithDetails(profileId)) {
                    String vCode = ps.getVisit() != null ? safeText(ps.getVisit().getVisitCode(), "") : "";
                    String dr = ps.getDoctor() != null ? safeText(ps.getDoctor().getFullName(), "Bác sĩ điều trị") : "Bác sĩ điều trị";
                    String svcName = ps.getService() != null ? safeText(ps.getService().getServiceName(), "Thủ thuật nha khoa") : "Thủ thuật nha khoa";
                    boolean isCancelled = ps.getStatus() != null && "CANCELLED".equalsIgnoreCase(ps.getStatus().name());
                    String actionGroup = isCancelled ? "DELETE" : "UPDATE";
                    String label = isCancelled ? "HỦY THỦ THUẬT" : "THỰC HIỆN DV";

                    StringBuilder sb = new StringBuilder();
                    if (!vCode.isEmpty()) sb.append("[").append(vCode).append("] ");
                    sb.append("Thực hiện dịch vụ/thủ thuật: ").append(svcName);
                    if (ps.getStepName() != null && !ps.getStepName().isBlank() && !ps.getStepName().equalsIgnoreCase(svcName)) {
                        sb.append(" — Bước: ").append(ps.getStepName().trim());
                    }
                    if (ps.getToothNumbers() != null && !ps.getToothNumbers().isBlank()) {
                        sb.append(" (Răng ").append(ps.getToothNumbers().trim()).append(")");
                    }
                    sb.append(" x").append(ps.getQuantity() != null ? ps.getQuantity().stripTrailingZeros().toPlainString() : "1")
                      .append(" — Thành tiền: ").append(formatVnd(ps.getFinalPrice()));
                    if (ps.getClinicalNotes() != null && !ps.getClinicalNotes().isBlank()) {
                        sb.append(" (Ghi chú: ").append(ps.getClinicalNotes().trim()).append(")");
                    }
                    rows.add(new DoctorRecordService.AuditLogRow(
                            ++syntheticIdSeq,
                            ps.getCompletedAt(),
                            dr,
                            actionGroup,
                            badgeClassForGroup(actionGroup),
                            label,
                            "Khám & Thủ thuật",
                            sb.toString()
                    ));
                }
            } catch (Exception ignored) {
            }
        }

        // 6b. Ảnh lâm sàng & X-quang (clinical_photos)
        if (clinicalPhotoRepository != null) {
            try {
                for (ClinicalPhoto photo : clinicalPhotoRepository.findByPatientProfile_ProfileIdOrderByCapturedAtDesc(profileId)) {
                    String vCode = photo.getVisit() != null ? safeText(photo.getVisit().getVisitCode(), "") : "";
                    String actor = photo.getVisit() != null && photo.getVisit().getPrimaryDoctor() != null
                            ? safeText(photo.getVisit().getPrimaryDoctor().getFullName(), "Bác sĩ / KTV")
                            : "Bác sĩ / KTV";
                    String stage = photo.getPhotoStage() != null ? photo.getPhotoStage().name() : "";
                    String cat = photo.getPhotoCategory() != null ? photo.getPhotoCategory().name() : "";
                    String detail = (!vCode.isEmpty() ? "[" + vCode + "] " : "")
                            + "Tải lên phim/ảnh lâm sàng (" + cat + " - " + stage + ")"
                            + (photo.getCaption() != null && !photo.getCaption().isBlank() ? ": " + photo.getCaption().trim() : "");
                    rows.add(new DoctorRecordService.AuditLogRow(
                            ++syntheticIdSeq,
                            photo.getCapturedAt(),
                            actor,
                            "CREATE",
                            "bg-success",
                            "TẢI PHIM ẢNH",
                            "Cận lâm sàng & Phim ảnh",
                            detail
                    ));
                }
            } catch (Exception ignored) {
            }
        }

        // 7. Đơn thuốc & Xử lý/Mua thuốc (prescriptions)
        if (prescriptionRepository != null) {
            try {
                for (Prescription p : prescriptionRepository.findByPatientProfile_ProfileIdOrderByCreatedAtDesc(profileId)) {
                    String vCode = p.getVisit() != null ? safeText(p.getVisit().getVisitCode(), "") : "";
                    String dr = p.getDoctor() != null ? safeText(p.getDoctor().getFullName(), "Bác sĩ kê đơn") : "Bác sĩ kê đơn";
                    String pCode = safeText(p.getPrescriptionCode(), "TOA-" + p.getPrescriptionId());
                    List<String> drugs = new ArrayList<>();
                    if (p.getItems() != null) {
                        for (PrescriptionItem item : p.getItems()) {
                            if (item.getDrugName() != null && !item.getDrugName().isBlank()) {
                                drugs.add(item.getDrugName().trim() + " ("
                                        + (item.getQuantity() != null ? item.getQuantity().stripTrailingZeros().toPlainString() : "1")
                                        + " " + safeText(item.getUnit(), "Viên") + ")");
                            }
                        }
                    }
                    StringBuilder sb = new StringBuilder();
                    if (!vCode.isEmpty()) sb.append("[").append(vCode).append("] ");
                    sb.append("Kê đơn thuốc ").append(pCode)
                      .append(" — Chẩn đoán: ").append(safeText(p.getDiagnosisText(), "Nha khoa"));
                    if (!drugs.isEmpty()) {
                        sb.append(" | Thuốc: ").append(String.join(", ", drugs));
                    }
                    if (p.getAdviceNotes() != null && !p.getAdviceNotes().isBlank()) {
                        sb.append(" | Lời dặn: ").append(p.getAdviceNotes().trim());
                    }
                    rows.add(new DoctorRecordService.AuditLogRow(
                            ++syntheticIdSeq,
                            p.getCreatedAt(),
                            dr,
                            "CREATE",
                            "bg-success",
                            "KÊ ĐƠN THUỐC",
                            "Đơn thuốc",
                            sb.toString()
                    ));

                    if (p.getHandledAt() != null) {
                        String handler = p.getHandledBy() != null ? safeText(p.getHandledBy().getFullName(), "Nhân viên quầy thuốc") : "Nhân viên quầy thuốc";
                        String choice = p.getPurchaseChoice() != null ? p.getPurchaseChoice().name() : (p.getStatus() != null ? p.getStatus().name() : "HANDLED");
                        String handleDetail = "Xử lý đơn thuốc " + pCode + " — Hình thức: " + choice
                                + (p.getChoiceNote() != null && !p.getChoiceNote().isBlank() ? " (" + p.getChoiceNote().trim() + ")" : "");
                        rows.add(new DoctorRecordService.AuditLogRow(
                                ++syntheticIdSeq,
                                p.getHandledAt(),
                                handler,
                                "UPDATE",
                                "bg-primary",
                                "XỬ LÝ ĐƠN THUỐC",
                                "Đơn thuốc",
                                handleDetail
                        ));
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 8. Đặt xưởng Labo (labo_orders)
        if (laboOrderRepository != null) {
            try {
                for (LaboOrder lo : laboOrderRepository.findByPatientProfile_ProfileIdOrderBySentDateDesc(profileId)) {
                    String dr = lo.getDoctor() != null ? safeText(lo.getDoctor().getFullName(), "Bác sĩ") : "Bác sĩ";
                    String supplier = lo.getSupplier() != null ? safeText(lo.getSupplier().getSupplierName(), "Xưởng Labo") : "Xưởng Labo";
                    String lCode = safeText(lo.getLaboCode(), "LAB-" + lo.getLaboId());
                    LocalDateTime sentTime = lo.getSentDate() != null ? lo.getSentDate().atStartOfDay().plusHours(9) : LocalDateTime.now();
                    StringBuilder sb = new StringBuilder("Lập phiếu đặt xưởng Labo ").append(lCode)
                            .append(": ").append(safeText(lo.getMaterialType(), "Phục hình"))
                            .append(" x").append(lo.getQuantity() != null ? lo.getQuantity() : 1);
                    if (lo.getToothPositions() != null && !lo.getToothPositions().isBlank()) {
                        sb.append(" (Răng ").append(lo.getToothPositions().trim()).append(")");
                    }
                    if (lo.getShadeColor() != null && !lo.getShadeColor().isBlank()) {
                        sb.append(" — Màu: ").append(lo.getShadeColor().trim());
                    }
                    sb.append(" — Xưởng: ").append(supplier);
                    rows.add(new DoctorRecordService.AuditLogRow(
                            ++syntheticIdSeq,
                            sentTime,
                            dr,
                            "CREATE",
                            "bg-success",
                            "ĐẶT XƯỞNG LABO",
                            "Đặt xưởng Labo",
                            sb.toString()
                    ));

                    if (lo.getReceivedDate() != null) {
                        rows.add(new DoctorRecordService.AuditLogRow(
                                ++syntheticIdSeq,
                                lo.getReceivedDate().atStartOfDay().plusHours(15),
                                dr,
                                "UPDATE",
                                "bg-primary",
                                "NHẬN MẪU LABO",
                                "Đặt xưởng Labo",
                                "Nhận mẫu phục hình Labo " + lCode + " (" + safeText(lo.getMaterialType(), "") + ") từ " + supplier
                        ));
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 9. Hồ sơ Bệnh án điện tử (emr_records + emr_audit_events)
        if (emrRecordRepository != null) {
            try {
                for (EmrRecord rec : emrRecordRepository.findAllByPatientProfileId(profileId)) {
                    String rCode = safeText(rec.getRecordCode(), "EMR-" + rec.getRecordId());
                    boolean hasEvents = false;
                    if (emrAuditEventRepository != null && rec.getRecordId() != null) {
                        List<EmrAuditEvent> events = emrAuditEventRepository.findByRecord_RecordIdOrderByCreatedAtDesc(rec.getRecordId());
                        for (EmrAuditEvent ev : events) {
                            hasEvents = true;
                            String rawAct = safeText(ev.getAction(), "UPDATE").toUpperCase();
                            String actionGroup = normalizeActionGroup(rawAct);
                            String label = rawAct.contains("CREATE") ? "TẠO BỆNH ÁN"
                                    : (rawAct.contains("SIGN") ? "KÝ BỆNH ÁN"
                                    : (rawAct.contains("LOCK") ? "KHÓA BỆNH ÁN" : "CẬP NHẬT BAĐT"));
                            String actor = resolveUserActorName(ev.getActorUser(), userIdNameCache, "Bác sĩ điều trị");
                            String detail = "Bệnh án " + rCode + ": "
                                    + (ev.getDetails() != null && !ev.getDetails().isBlank() ? ev.getDetails().trim() : rawAct);
                            rows.add(new DoctorRecordService.AuditLogRow(
                                    ++syntheticIdSeq,
                                    ev.getCreatedAt(),
                                    actor,
                                    actionGroup,
                                    badgeClassForGroup(actionGroup),
                                    label,
                                    "Bệnh án (BAĐT)",
                                    detail
                            ));
                        }
                    }
                    if (!hasEvents && rec.getUpdatedAt() != null) {
                        rows.add(new DoctorRecordService.AuditLogRow(
                                ++syntheticIdSeq,
                                rec.getUpdatedAt(),
                                "Bác sĩ điều trị",
                                "CREATE",
                                "bg-success",
                                "TẠO BỆNH ÁN",
                                "Bệnh án (BAĐT)",
                                "Khởi tạo và lưu trữ Hồ sơ bệnh án điện tử " + rCode
                                        + " (Trạng thái: " + (rec.getStatus() != null ? rec.getStatus().getDisplayName() : "Nháp") + ")"
                        ));
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 10. Hóa đơn & Thanh toán (invoices + invoice_audit_logs + payments)
        if (invoiceRepository != null) {
            try {
                for (Invoice inv : invoiceRepository.findByPatientProfile_ProfileIdOrderByIssuedAtDesc(profileId)) {
                    String iCode = safeText(inv.getInvoiceCode(), "HD-" + inv.getInvoiceId());
                    String cashierName = inv.getCashier() != null ? safeText(inv.getCashier().getFullName(), "Thu ngân") : "Thu ngân";
                    boolean hasInvAudit = false;

                    if (invoiceAuditLogRepository != null && inv.getInvoiceId() != null) {
                        List<InvoiceAuditLog> invLogs = invoiceAuditLogRepository.findByInvoiceIdWithStaff(inv.getInvoiceId());
                        for (InvoiceAuditLog il : invLogs) {
                            hasInvAudit = true;
                            String rawAct = safeText(il.getActionType(), "UPDATE").toUpperCase();
                            String actionGroup = normalizeActionGroup(rawAct);
                            String label = switch (rawAct) {
                                case "CREATE" -> "LẬP HÓA ĐƠN";
                                case "PAYMENT" -> "THU TIỀN HĐ";
                                case "ADJUST" -> "ĐIỀU CHỈNH HĐ";
                                case "CANCEL" -> "HỦY HÓA ĐƠN";
                                case "REFUND" -> "HOÀN TIỀN";
                                default -> resolveActionLabel(rawAct, actionGroup);
                            };
                            String actor = il.getPerformedBy() != null ? safeText(il.getPerformedBy().getFullName(), cashierName) : cashierName;
                            StringBuilder sb = new StringBuilder("Hóa đơn ").append(iCode).append(": ");
                            if (il.getDetails() != null && !il.getDetails().isBlank()) {
                                sb.append(il.getDetails().trim());
                            } else {
                                sb.append(label);
                            }
                            if (il.getReason() != null && !il.getReason().isBlank()) {
                                sb.append(" (Lý do: ").append(il.getReason().trim()).append(")");
                            }
                            rows.add(new DoctorRecordService.AuditLogRow(
                                    ++syntheticIdSeq,
                                    il.getPerformedAt(),
                                    actor,
                                    actionGroup,
                                    badgeClassForGroup(actionGroup),
                                    label,
                                    "Hóa đơn & Thanh toán",
                                    sb.toString()
                            ));
                        }
                    }

                    if (!hasInvAudit && inv.getIssuedAt() != null) {
                        boolean cancelled = inv.getStatus() == com.cvdcms.entity.enums.InvoiceStatus.CANCELLED;
                        rows.add(new DoctorRecordService.AuditLogRow(
                                ++syntheticIdSeq,
                                inv.getIssuedAt(),
                                cashierName,
                                cancelled ? "DELETE" : "CREATE",
                                cancelled ? "bg-danger" : "bg-success",
                                cancelled ? "HỦY HÓA ĐƠN" : "LẬP HÓA ĐƠN",
                                "Hóa đơn & Thanh toán",
                                "Phát hành hóa đơn " + iCode + " — Tổng tiền: " + formatVnd(inv.getTotalAmount())
                                        + " (Đã thu: " + formatVnd(inv.getPaidAmount()) + ")"
                        ));
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (paymentRepository != null) {
            try {
                for (Payment pmt : paymentRepository.findByPatientProfile_ProfileIdOrderByPaidAtDesc(profileId)) {
                    String cashierName = pmt.getCashier() != null ? safeText(pmt.getCashier().getFullName(), "Thu ngân") : "Thu ngân";
                    String pmtCode = safeText(pmt.getPaymentCode(), "TT-" + pmt.getPaymentId());
                    String invCode = pmt.getInvoice() != null ? safeText(pmt.getInvoice().getInvoiceCode(), "") : "";
                    String method = pmt.getPaymentMethod() != null ? pmt.getPaymentMethod().name() : "CASH";
                    String detail = "Thu tiền phiếu " + pmtCode
                            + (!invCode.isEmpty() ? " (Hóa đơn " + invCode + ")" : "")
                            + " — Số tiền: " + formatVnd(pmt.getAmount())
                            + " qua " + method
                            + (pmt.getNotes() != null && !pmt.getNotes().isBlank() ? " (" + pmt.getNotes().trim() + ")" : "");
                    rows.add(new DoctorRecordService.AuditLogRow(
                            ++syntheticIdSeq,
                            pmt.getPaidAt(),
                            cashierName,
                            "CREATE",
                            "bg-success",
                            "THU TIỀN",
                            "Hóa đơn & Thanh toán",
                            detail
                    ));
                }
            } catch (Exception ignored) {
            }
        }

        // 11. Liên hệ / Chăm sóc khách hàng (customer_care_interactions)
        if (customerCareInteractionRepository != null) {
            try {
                for (CustomerCareInteraction ci : customerCareInteractionRepository.findByProfileIdWithDetails(profileId)) {
                    String staffName = ci.getStaff() != null ? safeText(ci.getStaff().getFullName(), "Nhân viên CSKH") : "Nhân viên CSKH";
                    String channel = ci.getChannel() != null ? ci.getChannel().name() : "PHONE_CALL";
                    String result = ci.getContactResult() != null ? ci.getContactResult().name() : "";
                    StringBuilder sb = new StringBuilder("Liên hệ chăm sóc khách hàng qua ").append(channel);
                    if (!result.isEmpty()) sb.append(" (Kết quả: ").append(result).append(")");
                    if (ci.getNotes() != null && !ci.getNotes().isBlank()) {
                        sb.append(" — Nội dung: ").append(ci.getNotes().trim());
                    }
                    if (ci.getNextRetryAt() != null) {
                        sb.append(" (Hẹn gọi lại: ").append(ci.getNextRetryAt().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))).append(")");
                    }
                    rows.add(new DoctorRecordService.AuditLogRow(
                            ++syntheticIdSeq,
                            ci.getCreatedAt(),
                            staffName,
                            "UPDATE",
                            "bg-primary",
                            "LIÊN HỆ CSKH",
                            "Chăm sóc khách hàng",
                            sb.toString()
                    ));
                }
            } catch (Exception ignored) {
            }
        }

        rows.sort(Comparator.comparing(DoctorRecordService.AuditLogRow::timestamp,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return rows;
    }

    private String resolveUserActorName(User user, Map<Long, String> cache, String fallback) {
        if (user == null) return fallback;
        Long uid = user.getUserId();
        if (uid != null && cache.containsKey(uid)) {
            return cache.get(uid);
        }
        String resolved = null;
        if (uid != null && staffProfileRepository != null) {
            try {
                resolved = staffProfileRepository.findByUser_UserId(uid)
                        .map(StaffProfile::getFullName)
                        .filter(n -> n != null && !n.isBlank())
                        .orElse(null);
            } catch (Exception ignored) {
            }
        }
        if (resolved == null || resolved.isBlank()) {
            String uname = user.getUsername();
            if ("admin".equalsIgnoreCase(uname)) {
                resolved = "Quản trị viên Hệ thống";
            } else if (uname != null && !uname.isBlank()) {
                resolved = uname;
            } else {
                resolved = fallback;
            }
        }
        if (uid != null) {
            cache.put(uid, resolved);
        }
        return resolved;
    }

    private String resolveUserIdActorName(Long userId, Map<Long, String> cache, String fallback) {
        if (userId == null) return fallback;
        if (cache.containsKey(userId)) return cache.get(userId);
        if (users != null) {
            try {
                User u = users.findById(userId).orElse(null);
                if (u != null) {
                    return resolveUserActorName(u, cache, fallback);
                }
            } catch (Exception ignored) {
            }
        }
        return fallback;
    }

    private String normalizeActionGroup(String rawAction) {
        if (rawAction == null || rawAction.isBlank()) return "UPDATE";
        String up = rawAction.trim().toUpperCase();
        if (up.contains("DELETE") || up.contains("REMOVE") || up.contains("CANCEL")
                || up.contains("SKIP") || up.contains("REJECT") || up.contains("BAN")) {
            return "DELETE";
        }
        if (up.contains("CREATE") || up.contains("ADD") || up.contains("INSERT")
                || up.contains("UPLOAD") || up.contains("CHECK_IN") || up.contains("BOOK")
                || up.equals("PAYMENT")) {
            return "CREATE";
        }
        return "UPDATE";
    }

    private String resolveActionLabel(String rawAction, String actionGroup) {
        if (rawAction == null || rawAction.isBlank()) return "CẬP NHẬT";
        String up = rawAction.trim().toUpperCase();
        return switch (up) {
            case "CREATE" -> "THÊM MỚI";
            case "UPDATE" -> "CẬP NHẬT";
            case "DELETE" -> "XÓA";
            case "UPLOAD_PATIENT_DOCUMENT" -> "TẢI GIẤY TỜ";
            case "UPDATE_HEALTH_NOTES" -> "CẬP NHẬT Y TẾ";
            case "UPDATE_PATIENT_CODE" -> "ĐỔI MÃ BN";
            default -> switch (actionGroup) {
                case "CREATE" -> "THÊM MỚI";
                case "DELETE" -> "XÓA / HỦY";
                default -> "CẬP NHẬT";
            };
        };
    }

    private String badgeClassForGroup(String actionGroup) {
        if ("CREATE".equalsIgnoreCase(actionGroup)) return "bg-success";
        if ("DELETE".equalsIgnoreCase(actionGroup)) return "bg-danger";
        return "bg-primary";
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            ObjectMapper om = objectMapper != null ? objectMapper : new ObjectMapper();
            return om.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String buildDynamicAuditDetails(String moduleName, String rawAction,
                                            Map<String, Object> oldMap, Map<String, Object> newMap) {
        String up = rawAction != null ? rawAction.trim().toUpperCase() : "UPDATE";
        if (newMap.containsKey("summary") && newMap.get("summary") != null) {
            return String.valueOf(newMap.get("summary"));
        }
        if ("UPLOAD_PATIENT_DOCUMENT".equals(up)) {
            String type = asString(newMap.get("type"));
            String name = asString(newMap.get("name"));
            return "Tải lên tài liệu " + safeText(type, "giấy tờ")
                    + (name != null && !name.isBlank() ? ": " + name : "");
        }

        List<String> diffs = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(oldMap.keySet());
        keys.addAll(newMap.keySet());

        for (String key : keys) {
            if ("documentId".equals(key) || "profileId".equals(key) || "visitId".equals(key)) continue;
            String oldVal = asString(oldMap.get(key));
            String newVal = asString(newMap.get(key));
            if (Objects.equals(oldVal, newVal)) continue;
            String fieldLabel = AUDIT_FIELD_LABELS.getOrDefault(key, key);
            if (oldVal != null && !oldVal.isBlank() && newVal != null && !newVal.isBlank()) {
                diffs.add(fieldLabel + ": " + oldVal + " → " + newVal);
            } else if (newVal != null && !newVal.isBlank()) {
                diffs.add(fieldLabel + ": " + newVal);
            } else if (oldVal != null && !oldVal.isBlank()) {
                diffs.add("Xóa " + fieldLabel + " (" + oldVal + ")");
            }
        }

        String prefix = switch (up) {
            case "CREATE" -> "Tạo mới " + moduleName.toLowerCase();
            case "UPDATE" -> "Cập nhật " + moduleName.toLowerCase();
            case "UPDATE_HEALTH_NOTES" -> "Cập nhật thông tin dị ứng & bệnh nền";
            case "UPDATE_PATIENT_CODE" -> "Đổi mã định danh bệnh nhân";
            case "REOPEN_CLINICAL" -> "Mở lại ca khám lâm sàng";
            default -> "Thao tác " + up + " trên " + moduleName.toLowerCase();
        };

        if (!diffs.isEmpty()) {
            return prefix + " — " + String.join(", ", diffs);
        }
        return prefix;
    }

    private String asString(Object val) {
        return val == null ? null : String.valueOf(val);
    }

    private String safeText(String val, String fallback) {
        return (val != null && !val.isBlank()) ? val.trim() : fallback;
    }

    private String formatVnd(BigDecimal amount) {
        BigDecimal safe = amount != null ? amount : BigDecimal.ZERO;
        NumberFormat nf = NumberFormat.getInstance(new Locale("vi", "VN"));
        nf.setMaximumFractionDigits(0);
        return nf.format(safe) + " đ";
    }
}

