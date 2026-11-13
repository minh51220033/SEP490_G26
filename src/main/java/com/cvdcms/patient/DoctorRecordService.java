package com.cvdcms.patient;

import com.cvdcms.emr.EmrRecord;
import com.cvdcms.emr.EmrRecordRepository;
import com.cvdcms.emr.EmrRecordStatus;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.entity.enums.DocumentType;
import com.cvdcms.entity.enums.PhotoCategory;
import com.cvdcms.entity.enums.PhotoStage;
import com.cvdcms.repository.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class DoctorRecordService {
    private final UserRepository users;
    private final StaffBranchAssignmentRepository assignments;
    private final DoctorRecordRepository profiles;
    private final PatientVisitReadRepository visits;
    private final ClinicalPhotoRepository photos;
    private final TreatmentPlanRepository plans;
    private final ClinicalExaminationRepository examinations;
    private final ExaminationDiagnosisRepository diagnoses;
    private final PerformedServiceRepository performed;
    private final PrescriptionRepository prescriptions;
    private final BranchRepository branchRepository;
    private final PatientDocumentStorageService documentStorage;
    private final EmrRecordRepository emrRecords;
    private final PatientDocumentRepository documentRepository;
    private final PatientAuditRepository auditRepository;
    @Autowired(required = false)
    private PatientOverviewService overviewService;

    public DoctorRecordService(UserRepository users, StaffBranchAssignmentRepository assignments,
            DoctorRecordRepository profiles, PatientVisitReadRepository visits,
            ClinicalPhotoRepository photos, TreatmentPlanRepository plans,
            ClinicalExaminationRepository examinations, ExaminationDiagnosisRepository diagnoses,
            PerformedServiceRepository performed, PrescriptionRepository prescriptions,
            BranchRepository branchRepository, PatientDocumentStorageService documentStorage,
            EmrRecordRepository emrRecords) {
        this(users, assignments, profiles, visits, photos, plans, examinations, diagnoses, performed,
                prescriptions, branchRepository, documentStorage, emrRecords, null, null);
    }

    @Autowired
    public DoctorRecordService(UserRepository users, StaffBranchAssignmentRepository assignments,
            DoctorRecordRepository profiles, PatientVisitReadRepository visits,
            ClinicalPhotoRepository photos, TreatmentPlanRepository plans,
            ClinicalExaminationRepository examinations, ExaminationDiagnosisRepository diagnoses,
            PerformedServiceRepository performed, PrescriptionRepository prescriptions,
            BranchRepository branchRepository, PatientDocumentStorageService documentStorage,
            EmrRecordRepository emrRecords, PatientDocumentRepository documentRepository,
            PatientAuditRepository auditRepository) {
        this.users = users; this.assignments = assignments; this.profiles = profiles;
        this.visits = visits; this.photos = photos; this.plans = plans;
        this.examinations = examinations; this.diagnoses = diagnoses;
        this.performed = performed; this.prescriptions = prescriptions;
        this.branchRepository = branchRepository; this.documentStorage = documentStorage;
        this.emrRecords = emrRecords;
        this.documentRepository = documentRepository;
        this.auditRepository = auditRepository;
    }

    public Long extractUserId(HttpSession session) {
        if (session == null) return null;
        Object principal = session.getAttribute("loggedInUser");
        if (principal instanceof User current) {
            return current.getUserId();
        }
        if (principal != null && "com.cvdcms.entity.User".equals(principal.getClass().getName())) {
            try {
                Object idVal = principal.getClass().getMethod("getUserId").invoke(principal);
                if (idVal instanceof Long uid) {
                    users.findById(uid).ifPresent(u -> session.setAttribute("loggedInUser", u));
                    return uid;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    public boolean isAdmin(HttpSession session) {
        if (session == null) return false;
        if (Boolean.TRUE.equals(session.getAttribute("isAdmin"))) return true;
        Long userId = extractUserId(session);
        if (userId == null) return false;
        return users.findById(userId)
                .map(u -> u.getRoles() != null && u.getRoles().stream()
                        .anyMatch(role -> Boolean.TRUE.equals(role.getIsActive())
                                && ("ADMIN".equalsIgnoreCase(role.getRoleName())
                                    || "ROLE_ADMIN".equalsIgnoreCase(role.getRoleName()))))
                .orElse(false);
    }

    public boolean isDoctor(HttpSession session) {
        if (session == null) return false;
        Long userId = extractUserId(session);
        if (userId == null) return false;
        if (users.hasActivePermission(userId, "MEDICAL_RECORD_VIEW")) {
            return true;
        }
        return users.findById(userId)
                .map(u -> u.getRoles() != null && u.getRoles().stream()
                        .anyMatch(role -> Boolean.TRUE.equals(role.getIsActive())
                                && ("BAC_SI".equalsIgnoreCase(role.getRoleName())
                                    || "ROLE_BAC_SI".equalsIgnoreCase(role.getRoleName()))))
                .orElse(false);
    }

    private Set<Long> branches(HttpSession session) {
        Long userId = extractUserId(session);
        if (userId == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        User user = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (!Boolean.TRUE.equals(user.getIsActive()) || !"ACTIVE".equalsIgnoreCase(user.getStatus()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);

        boolean admin = users.hasActiveAdminRole(user.getUserId())
                || (user.getRoles() != null && user.getRoles().stream()
                    .anyMatch(role -> Boolean.TRUE.equals(role.getIsActive())
                            && ("ADMIN".equalsIgnoreCase(role.getRoleName())
                                || "ROLE_ADMIN".equalsIgnoreCase(role.getRoleName()))));
        boolean doctor = users.hasActivePermission(user.getUserId(), "MEDICAL_RECORD_VIEW")
                || (user.getRoles() != null && user.getRoles().stream()
                    .anyMatch(role -> Boolean.TRUE.equals(role.getIsActive())
                            && ("BAC_SI".equalsIgnoreCase(role.getRoleName())
                                || "ROLE_BAC_SI".equalsIgnoreCase(role.getRoleName()))));

        if (!admin && !doctor)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);

        if (admin) {
            Set<Long> allBranchIds = new HashSet<>();
            branchRepository.findAll().forEach(b -> {
                if (b.getBranchId() != null) allBranchIds.add(b.getBranchId());
            });
            return allBranchIds;
        }

        Set<Long> branchIds = new HashSet<>(assignments.findActiveBranchIdsByUserId(
                user.getUserId(), AssignmentStatus.ACTIVE));
        if (user.getBranch() != null) branchIds.add(user.getBranch().getBranchId());
        if (branchIds.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Tài khoản bác sĩ chưa được phân chi nhánh.");
        return branchIds;
    }

    public Page<PatientView> search(HttpSession session, String query, LocalDate dob,
                                    String status, Long branchId, int page) {
        return search(session, query, dob, status, branchId, page, 10);
    }

    public Page<PatientView> search(HttpSession session, String query, LocalDate dob,
                                    String status, Long branchId, int page, int size) {
        Set<Long> allowed = branches(session);
        boolean admin = isAdmin(session);
        if (!admin && branchId != null && !allowed.contains(branchId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        String term = query == null || query.isBlank() ? null : query.trim();
        String state = status == null || status.isBlank() ? null : status.trim();
        int safeSize = (size == 10 || size == 25 || size == 50 || size == 100) ? size : 10;
        return profiles.search(allowed, branchId, dob, state, term,
                PageRequest.of(Math.max(0, page), safeSize, Sort.by(Sort.Direction.DESC, "profileId")))
                .map(PatientView::from);
    }

    public PatientView get(HttpSession session, Long id) {
        PatientProfile profile = profiles.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Set<Long> allowed = branches(session);
        if (!isAdmin(session)) {
            boolean preferredOk = profile.getPreferredBranch() != null
                    && allowed.contains(profile.getPreferredBranch().getBranchId());
            boolean visitInScope = visits.forPatient(id).stream()
                    .anyMatch(v -> v.getBranch() != null && allowed.contains(v.getBranch().getBranchId()));
            if (!preferredOk && !visitInScope)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return PatientView.from(profile);
    }

    public List<Branch> allowedBranches(HttpSession session) {
        if (isAdmin(session)) {
            return branchRepository.findAll().stream()
                    .sorted(java.util.Comparator.comparing(Branch::getBranchName)).toList();
        }
        return branchRepository.findAllById(branches(session)).stream()
                .sorted(java.util.Comparator.comparing(Branch::getBranchName)).toList();
    }

    public List<VisitView> visits(HttpSession session, Long id) {
        get(session, id);
        return visits.forPatient(id).stream().map(VisitView::from).toList();
    }

    public VisitDetails visit(HttpSession session, Long id, Long visitId) {
        get(session, id);
        Visit visit = visits.oneForPatient(id, visitId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new VisitDetails(VisitView.from(visit),
                examinations.findByVisit_VisitId(visitId)
                        .map(ClinicalExamination::getSummaryAssessment).orElse(null),
                diagnoses.findByVisit_VisitId(visitId).stream()
                        .map(ExaminationDiagnosis::getDiagnosisText).toList(),
                performed.findByVisitIdWithDetails(visitId).stream()
                        .map(item -> item.getService().getServiceName()).toList(),
                prescriptions.findByVisit_VisitIdOrderByCreatedAtDesc(visitId).stream()
                        .map(Prescription::getPrescriptionCode).toList(),
                emrRecords.findByVisit_VisitId(visitId).map(this::toEmrView).orElse(null));
    }

    public List<EmrView> emrRecords(HttpSession session, Long patientId) {
        get(session, patientId);
        boolean admin = isAdmin(session);
        Set<Long> allowed = branches(session);
        return emrRecords.findAllByPatientProfileId(patientId).stream()
                .filter(record -> admin || record.getBranch() == null || allowed.contains(record.getBranch().getBranchId()))
                .map(this::toEmrView)
                .toList();
    }

    private EmrView toEmrView(EmrRecord record) {
        Visit visit = record.getVisit();
        String doctorName = (visit != null && visit.getPrimaryDoctor() != null) ? visit.getPrimaryDoctor().getFullName() : null;
        String diagnosis = null;
        if (visit != null) {
            diagnosis = diagnoses.findByVisit_VisitId(visit.getVisitId()).stream()
                    .map(ExaminationDiagnosis::getDiagnosisText)
                    .findFirst().orElse(null);
        }
        return new EmrView(record.getRecordId(), record.getRecordCode(),
                visit != null ? visit.getVisitId() : null,
                visit != null ? visit.getVisitCode() : "—",
                visit != null ? visit.getCheckInAt() : null,
                record.getStatus(), record.getCurrentVersionNumber(), record.getUpdatedAt(),
                doctorName, diagnosis);
    }

    public List<PhotoView> photos(HttpSession session, Long id) {
        get(session, id);
        return photos.findByPatientProfile_ProfileIdOrderByCapturedAtDesc(id).stream()
                .map(photo -> new PhotoView(
                        photo.getPhotoId(),
                        photo.getImageUrl(),
                        photo.getCaption() != null ? photo.getCaption() : "",
                        photo.getPhotoStage() != null ? photo.getPhotoStage().name() : "BEFORE",
                        photo.getPhotoCategory() != null ? photo.getPhotoCategory().name() : "INTRAORAL",
                        photo.getCapturedAt(),
                        photo.getVisit() != null ? photo.getVisit().getVisitId() : null,
                        photo.getVisit() != null ? photo.getVisit().getVisitCode() : null
                ))
                .toList();
    }

    public List<PlanView> plans(HttpSession session, Long id) {
        get(session, id);
        return plans.findByProfileIdWithDetails(id).stream()
                .map(plan -> new PlanView(plan.getPlanName(), plan.getStatus().name())).toList();
    }

    public List<PatientDocumentView> documents(HttpSession session, Long id) {
        get(session, id);
        return documentStorage.list(id);
    }

    public PatientDocumentStorageService.PatientDocumentFile downloadDocument(
            HttpSession session, Long patientId, Long documentId) {
        get(session, patientId);
        return documentStorage.download(patientId, documentId);
    }

    @Transactional
    public PatientDocumentView uploadDocument(HttpSession session, Long patientId, DocumentType type, MultipartFile file) {
        Long userId = extractUserId(session);
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        User user = users.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        get(session, patientId);
        PatientDocumentView saved = documentStorage.upload(patientId, user, type, file);
        if (auditRepository != null) {
            PatientProfile profile = profiles.findById(patientId).orElse(null);
            Branch branch = profile != null ? profile.getPreferredBranch() : null;
            SystemAuditLog log = new SystemAuditLog();
            log.setUser(user);
            log.setBranch(branch);
            log.setAction("UPLOAD_PATIENT_DOCUMENT");
            log.setEntityTable("patient_profiles");
            log.setEntityId(patientId);
            log.setCreatedAt(LocalDateTime.now());
            log.setNewValuesJson("{\"documentId\":" + saved.id() + ",\"type\":\"" + saved.type().name() + "\",\"name\":\"" + saved.name() + "\"}");
            auditRepository.save(log);
        }
        return saved;
    }

    public List<TreatmentTimelineItem> treatmentTimeline(HttpSession session, Long id) {
        get(session, id);
        List<Visit> patientVisits = visits.forPatient(id);
        List<TreatmentTimelineItem> timeline = new ArrayList<>();

        for (Visit v : patientVisits) {
            Long vid = v.getVisitId();
            List<String> diagList = diagnoses.findByVisit_VisitId(vid).stream()
                    .map(ExaminationDiagnosis::getDiagnosisText).toList();

            List<TreatmentServiceItem> svcList = performed.findByVisitIdWithDetails(vid).stream()
                    .map(p -> new TreatmentServiceItem(
                            p.getPerformedId(),
                            p.getService() != null ? p.getService().getServiceName() : "Dịch vụ nha khoa",
                            p.getToothNumbers(),
                            p.getUnitPrice() != null ? p.getUnitPrice() : BigDecimal.ZERO,
                            p.getQuantity() != null ? p.getQuantity() : BigDecimal.ONE,
                            p.getFinalPrice() != null ? p.getFinalPrice() : BigDecimal.ZERO,
                            p.getStatus() != null ? p.getStatus().name() : "COMPLETED"
                    )).toList();

            Optional<EmrRecord> emrOpt = emrRecords.findByVisit_VisitId(vid);
            Long emrId = emrOpt.map(EmrRecord::getRecordId).orElse(null);
            String emrCode = emrOpt.map(EmrRecord::getRecordCode).orElse(null);
            String emrStatus = emrOpt.map(e -> e.getStatus().getDisplayName()).orElse(null);

            timeline.add(new TreatmentTimelineItem(
                    v.getVisitId(),
                    v.getVisitCode(),
                    v.getCheckInAt(),
                    v.getBranch() != null ? v.getBranch().getBranchName() : "Chi nhánh",
                    v.getPrimaryDoctor() != null ? v.getPrimaryDoctor().getFullName() : "Chưa phân bác sĩ",
                    v.getStatus() != null ? v.getStatus().name() : "COMPLETED",
                    v.getInitialSymptoms(),
                    diagList,
                    svcList,
                    emrId,
                    emrCode,
                    emrStatus
            ));
        }
        return timeline;
    }

    public List<AuditLogRow> auditLogs(HttpSession session, Long id) {
        get(session, id);
        if (overviewService != null) {
            return overviewService.getUnifiedAuditLogs(id);
        }
        List<AuditLogRow> list = new ArrayList<>();

        if (auditRepository != null) {
            List<SystemAuditLog> dbLogs = auditRepository.findByEntityTableAndEntityIdOrderByCreatedAtDesc("patient_profiles", id);
            for (SystemAuditLog l : dbLogs) {
                String rawAction = l.getAction() != null ? l.getAction() : "UPDATE";
                String actionGroup = "UPDATE";
                String badge = "bg-primary";
                String label = "CẬP NHẬT";
                if (rawAction.contains("CREATE") || rawAction.contains("UPLOAD") || rawAction.contains("ADD")) {
                    actionGroup = "CREATE";
                    badge = "bg-success";
                    label = "THÊM MỚI";
                } else if (rawAction.contains("DELETE") || rawAction.contains("REMOVE") || rawAction.contains("CANCEL")) {
                    actionGroup = "DELETE";
                    badge = "bg-danger";
                    label = "XÓA";
                }
                String user = l.getUser() != null ? l.getUser().getFullName() : "Hệ thống";
                String details = l.getNewValuesJson() != null && !l.getNewValuesJson().isBlank()
                        ? rawAction + " — " + l.getNewValuesJson()
                        : rawAction;
                list.add(new AuditLogRow(l.getLogId(), l.getCreatedAt(), user, actionGroup, badge, label, "Hồ sơ bệnh nhân", details));
            }
        }

        List<Visit> patientVisits = visits.forPatient(id);
        for (Visit v : patientVisits) {
            String dr = v.getPrimaryDoctor() != null ? v.getPrimaryDoctor().getFullName() : "Bác sĩ phụ trách";
            list.add(new AuditLogRow(
                    v.getVisitId() + 100000L,
                    v.getCheckInAt(),
                    dr,
                    "CREATE",
                    "bg-success",
                    "TIẾP NHẬN",
                    "Lượt khám",
                    "Tiếp nhận ca khám " + v.getVisitCode() + " tại " + (v.getBranch() != null ? v.getBranch().getBranchName() : "phòng khám")
            ));
        }

        list.sort(Comparator.comparing(AuditLogRow::timestamp, Comparator.nullsLast(Comparator.reverseOrder())));
        return list;
    }

    @Transactional
    public void updateHealthNotes(HttpSession session, Long patientId, String allergyDetails, String underlyingDiseaseDetails) {
        get(session, patientId);
        PatientProfile profile = profiles.findById(patientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        String cleanAllergy = allergyDetails == null || allergyDetails.isBlank() ? null : allergyDetails.trim();
        String cleanUnderlying = underlyingDiseaseDetails == null || underlyingDiseaseDetails.isBlank() ? null : underlyingDiseaseDetails.trim();
        profile.setAllergyDetails(cleanAllergy);
        profile.setHasAllergies(cleanAllergy != null);
        profile.setUnderlyingDiseaseDetails(cleanUnderlying);
        profile.setHasUnderlyingDisease(cleanUnderlying != null);
        profiles.saveAndFlush(profile);
    }

    @Transactional
    public void updatePatientCode(HttpSession session, Long patientId, String newCode) {
        if (newCode == null || newCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã bệnh nhân không được để trống.");
        }
        get(session, patientId);
        PatientProfile profile = profiles.findById(patientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        profile.setPatientCode(newCode.trim().toUpperCase());
        profiles.saveAndFlush(profile);
    }

    @Transactional
    public void updateDocumentType(HttpSession session, Long patientId, Long documentId, DocumentType newType) {
        get(session, patientId);
        if (documentRepository == null) return;
        PatientDocument doc = documentRepository.findByDocumentIdAndPatientProfile_ProfileId(documentId, patientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tài liệu scan."));
        doc.setDocumentType(newType);
        documentRepository.saveAndFlush(doc);
    }

    @Transactional
    public void deleteDocument(HttpSession session, Long patientId, Long documentId) {
        get(session, patientId);
        if (documentRepository == null) return;
        PatientDocument doc = documentRepository.findByDocumentIdAndPatientProfile_ProfileId(documentId, patientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tài liệu scan."));
        documentRepository.delete(doc);
    }

    @Transactional
    public void updatePhoto(HttpSession session, Long photoId, String caption, String stageStr, String categoryStr) {
        updatePhotoForPatient(session, null, photoId, caption, stageStr, categoryStr);
    }

    @Transactional
    public void updatePhotoForPatient(HttpSession session, Long expectedPatientId, Long photoId, String caption, String stageStr, String categoryStr) {
        ClinicalPhoto photo = photos.findById(photoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy ảnh lâm sàng."));
        Long actualPatientId = photo.getPatientProfile() != null ? photo.getPatientProfile().getProfileId() : null;
        if (expectedPatientId != null && !expectedPatientId.equals(actualPatientId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy ảnh lâm sàng thuộc hồ sơ này.");
        }
        get(session, actualPatientId);
        if (caption != null) photo.setCaption(caption.trim());
        if (stageStr != null && !stageStr.isBlank()) {
            try { photo.setPhotoStage(PhotoStage.valueOf(stageStr.toUpperCase())); } catch (Exception ignored) {}
        }
        if (categoryStr != null && !categoryStr.isBlank()) {
            try { photo.setPhotoCategory(PhotoCategory.valueOf(categoryStr.toUpperCase())); } catch (Exception ignored) {}
        }
        photos.saveAndFlush(photo);
    }

    @Transactional
    public void deletePhoto(HttpSession session, Long photoId) {
        deletePhotoForPatient(session, null, photoId);
    }

    @Transactional
    public void deletePhotoForPatient(HttpSession session, Long expectedPatientId, Long photoId) {
        ClinicalPhoto photo = photos.findById(photoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy ảnh lâm sàng."));
        Long actualPatientId = photo.getPatientProfile() != null ? photo.getPatientProfile().getProfileId() : null;
        if (expectedPatientId != null && !expectedPatientId.equals(actualPatientId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy ảnh lâm sàng thuộc hồ sơ này.");
        }
        get(session, actualPatientId);
        photos.delete(photo);
    }

    public record VisitDetails(VisitView visit, String assessment, List<String> diagnoses,
                               List<String> services, List<String> prescriptions, EmrView emr) {}
    public record EmrView(Long id, String code, Long visitId, String visitCode,
                          LocalDateTime checkInAt, EmrRecordStatus status,
                          Integer versionNumber, LocalDateTime updatedAt,
                          String doctorName, String mainDiagnosis) {
        public EmrView(Long id, String code, Long visitId, String visitCode,
                       LocalDateTime checkInAt, EmrRecordStatus status,
                       Integer versionNumber, LocalDateTime updatedAt) {
            this(id, code, visitId, visitCode, checkInAt, status, versionNumber, updatedAt, null, null);
        }
    }
    public record PhotoView(Long id, String url, String caption, String stage, String category,
                            LocalDateTime capturedAt, Long visitId, String visitCode) {
        public PhotoView(String url, String caption) {
            this(null, url, caption, "BEFORE", "INTRAORAL", null, null, null);
        }
    }
    public record PlanView(String name, String status) {}
    public record TreatmentTimelineItem(
            Long visitId, String visitCode, LocalDateTime checkInAt,
            String branchName, String doctorName, String status,
            String symptoms, List<String> diagnoses,
            List<TreatmentServiceItem> services,
            Long emrRecordId, String emrRecordCode, String emrStatus
    ) {}
    public record TreatmentServiceItem(
            Long serviceId, String serviceName, String toothNumber,
            BigDecimal price, BigDecimal quantity,
            BigDecimal totalAmount, String status
    ) {}
    public record AuditLogRow(
            Long id, LocalDateTime timestamp, String userName,
            String action, String actionBadgeClass, String actionLabel,
            String entityType, String details
    ) {}
}

