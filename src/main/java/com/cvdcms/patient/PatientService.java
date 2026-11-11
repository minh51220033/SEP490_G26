package com.cvdcms.patient;

import com.cvdcms.emr.EmrRecordRepository;
import com.cvdcms.entity.*;
import com.cvdcms.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.time.LocalDate;
import java.util.*;

@Service
@Transactional
public class PatientService {
    private final PatientAccess access;
    private final ReceptionPatientRepository profiles;
    private final PatientSearchRepository search;
    private final PatientVisitReadRepository visits;
    private final BranchRepository branches;
    private final PatientAuditRepository auditRepository;
    private final ClinicalExaminationRepository examinations;
    private final ExaminationDiagnosisRepository diagnoses;
    private final PerformedServiceRepository performedServices;
    private final PrescriptionRepository prescriptions;
    private final AppointmentRepository appointments;
    private final PatientDocumentStorageService documentStorage;
    private final ObjectMapper mapper;
    private final ClinicalPhotoRepository photos;
    private final EmrRecordRepository emrRecords;
    private final PatientDocumentRepository documentRepository;
    @Autowired(required = false)
    private PatientOverviewService overviewService;

    public PatientService(PatientAccess access, ReceptionPatientRepository profiles,
                          PatientSearchRepository search, PatientVisitReadRepository visits,
                          BranchRepository branches, PatientAuditRepository auditRepository,
                          ClinicalExaminationRepository examinations,
                          ExaminationDiagnosisRepository diagnoses,
                          PerformedServiceRepository performedServices,
                          PrescriptionRepository prescriptions, AppointmentRepository appointments,
                          PatientDocumentStorageService documentStorage,
                          ObjectMapper mapper) {
        this(access, profiles, search, visits, branches, auditRepository, examinations, diagnoses,
                performedServices, prescriptions, appointments, documentStorage, mapper, null, null, null);
    }

    @Autowired
    public PatientService(PatientAccess access, ReceptionPatientRepository profiles,
                          PatientSearchRepository search, PatientVisitReadRepository visits,
                          BranchRepository branches, PatientAuditRepository auditRepository,
                          ClinicalExaminationRepository examinations,
                          ExaminationDiagnosisRepository diagnoses,
                          PerformedServiceRepository performedServices,
                          PrescriptionRepository prescriptions, AppointmentRepository appointments,
                          PatientDocumentStorageService documentStorage,
                          ObjectMapper mapper,
                          ClinicalPhotoRepository photos,
                          EmrRecordRepository emrRecords,
                          PatientDocumentRepository documentRepository) {
        this.access = access;
        this.profiles = profiles;
        this.search = search;
        this.visits = visits;
        this.branches = branches;
        this.auditRepository = auditRepository;
        this.examinations = examinations;
        this.diagnoses = diagnoses;
        this.performedServices = performedServices;
        this.prescriptions = prescriptions;
        this.appointments = appointments;
        this.documentStorage = documentStorage;
        this.mapper = mapper;
        this.photos = photos;
        this.emrRecords = emrRecords;
        this.documentRepository = documentRepository;
    }

    @Transactional(readOnly = true)
    public PatientAccess.Scope scope(HttpSession session) { return access.require(session); }

    @Transactional(readOnly = true)
    public Page<PatientView> list(HttpSession session, String query, LocalDate dob,
                                  String status, Long branchId, int page, int size) {
        var scope = access.require(session);
        if (branchId != null && !scope.permits(branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        Long selectedBranchId = scope.admin() ? branchId : scope.branchId();
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        String term = blankToNull(query);
        String state = blankToNull(status);
        return search.search(selectedBranchId, term, dob, state,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "profileId")))
                .map(PatientView::from);
    }

    @Transactional(readOnly = true)
    public PatientView get(HttpSession session, Long id) {
        var scope = access.require(session);
        return PatientView.from(visibleProfile(scope, id));
    }

    @Transactional(readOnly = true)
    public List<DuplicateCandidate> candidates(HttpSession session, String name, LocalDate dob,
                                                String phone, String idCardNumber, Long excludeId) {
        var scope = access.require(session);
        return findCandidates(scope, name, dob, phone, idCardNumber, excludeId);
    }

    @Transactional(readOnly = true)
    public List<DuplicateCandidate> candidates(HttpSession session, String name, LocalDate dob,
                                                String phone, Long excludeId) {
        return candidates(session, name, dob, phone, null, excludeId);
    }

    public PatientView create(HttpSession session, PatientProfileForm form) {
        var scope = access.require(session);
        Branch branch = allowedBranch(scope, form.branchId());
        requireDuplicateConfirmation(scope, form, null);
        ReceptionPatientRecord profile = new ReceptionPatientRecord();
        profile.setPatientCode("BN" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        profile.setIsDefault(false);
        profile.setStatus("ACTIVE");
        apply(profile, form, branch);
        profiles.saveAndFlush(profile);
        audit(scope, branch, "CREATE", profile.getProfileId(), null, snapshot(profile));
        return PatientView.from(profile);
    }

    public PatientView update(HttpSession session, Long id, PatientProfileForm form) {
        var scope = access.require(session);
        ReceptionPatientRecord profile = visibleProfile(scope, id);
        Branch branch = allowedBranch(scope, form.branchId());
        requireDuplicateConfirmation(scope, form, id);
        Map<String, Object> before = snapshot(profile);
        apply(profile, form, branch);
        profiles.saveAndFlush(profile);
        audit(scope, branch, "UPDATE", id, before, snapshot(profile));
        return PatientView.from(profile);
    }

    @Transactional(readOnly = true)
    public List<ReceptionVisitView> visits(HttpSession session, Long patientId) {
        var scope = access.require(session);
        visibleProfile(scope, patientId);
        return visits.forPatient(patientId).stream().map(ReceptionVisitView::from).toList();
    }

    @Transactional(readOnly = true)
    public List<PatientDocumentView> documents(HttpSession session, Long patientId) {
        var scope = access.require(session);
        visibleProfile(scope, patientId);
        return documentStorage.list(patientId);
    }

    public PatientDocumentView uploadDocument(HttpSession session, Long patientId,
            com.cvdcms.entity.enums.DocumentType type,
            org.springframework.web.multipart.MultipartFile file) {
        var scope = access.require(session);
        ReceptionPatientRecord profile = visibleProfile(scope, patientId);
        PatientDocumentView saved = documentStorage.upload(patientId, scope.user(), type, file);
        audit(scope, profile.getPreferredBranch(),
                "UPLOAD_PATIENT_DOCUMENT", patientId, null,
                Map.of("documentId", saved.id(), "documentType", saved.type().name(),
                        "documentName", saved.name()));
        return saved;
    }

    @Transactional(readOnly = true)
    public PatientDocumentStorageService.PatientDocumentFile downloadDocument(
            HttpSession session, Long patientId, Long documentId) {
        var scope = access.require(session);
        visibleProfile(scope, patientId);
        return documentStorage.download(patientId, documentId);
    }

    @Transactional(readOnly = true)
    public List<DoctorRecordService.PhotoView> photos(HttpSession session, Long patientId) {
        var scope = access.require(session);
        visibleProfile(scope, patientId);
        if (photos == null) return List.of();
        return photos.findByPatientProfile_ProfileIdOrderByCapturedAtDesc(patientId).stream()
                .map(photo -> new DoctorRecordService.PhotoView(
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

    @Transactional(readOnly = true)
    public List<DoctorRecordService.EmrView> emrRecords(HttpSession session, Long patientId) {
        var scope = access.require(session);
        visibleProfile(scope, patientId);
        if (emrRecords == null) return List.of();
        return emrRecords.findAllByPatientProfileId(patientId).stream()
                .filter(record -> scope.permits(record.getBranch().getBranchId()))
                .map(record -> {
                    Visit visit = record.getVisit();
                    String doctorName = (visit != null && visit.getPrimaryDoctor() != null) ? visit.getPrimaryDoctor().getFullName() : null;
                    String diagnosis = null;
                    if (visit != null) {
                        diagnosis = diagnoses.findByVisit_VisitId(visit.getVisitId()).stream()
                                .map(ExaminationDiagnosis::getDiagnosisText)
                                .findFirst().orElse(null);
                    }
                    return new DoctorRecordService.EmrView(
                            record.getRecordId(), record.getRecordCode(),
                            visit != null ? visit.getVisitId() : null,
                            visit != null ? visit.getVisitCode() : "—",
                            visit != null ? visit.getCheckInAt() : null,
                            record.getStatus(), record.getCurrentVersionNumber(), record.getUpdatedAt(),
                            doctorName, diagnosis
                    );
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DoctorRecordService.TreatmentTimelineItem> treatmentTimeline(HttpSession session, Long patientId) {
        var scope = access.require(session);
        visibleProfile(scope, patientId);
        List<Visit> patientVisits = visits.forPatient(patientId);
        List<DoctorRecordService.TreatmentTimelineItem> timeline = new ArrayList<>();

        for (Visit v : patientVisits) {
            Long vid = v.getVisitId();
            List<String> diagList = diagnoses.findByVisit_VisitId(vid).stream()
                    .map(ExaminationDiagnosis::getDiagnosisText).toList();

            List<DoctorRecordService.TreatmentServiceItem> svcList = performedServices.findByVisitIdWithDetails(vid).stream()
                    .map(p -> new DoctorRecordService.TreatmentServiceItem(
                            p.getPerformedId(),
                            p.getService() != null ? p.getService().getServiceName() : "Dịch vụ nha khoa",
                            p.getToothNumbers(),
                            p.getUnitPrice() != null ? p.getUnitPrice() : BigDecimal.ZERO,
                            p.getQuantity() != null ? p.getQuantity() : BigDecimal.ONE,
                            p.getFinalPrice() != null ? p.getFinalPrice() : BigDecimal.ZERO,
                            p.getStatus() != null ? p.getStatus().name() : "COMPLETED"
                    )).toList();

            Long emrId = null;
            String emrCode = null;
            String emrStatus = null;
            if (emrRecords != null) {
                Optional<com.cvdcms.emr.EmrRecord> emrOpt = emrRecords.findByVisit_VisitId(vid);
                emrId = emrOpt.map(com.cvdcms.emr.EmrRecord::getRecordId).orElse(null);
                emrCode = emrOpt.map(com.cvdcms.emr.EmrRecord::getRecordCode).orElse(null);
                emrStatus = emrOpt.map(e -> e.getStatus().getDisplayName()).orElse(null);
            }

            timeline.add(new DoctorRecordService.TreatmentTimelineItem(
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

    @Transactional(readOnly = true)
    public List<DoctorRecordService.AuditLogRow> auditLogs(HttpSession session, Long patientId) {
        var scope = access.require(session);
        visibleProfile(scope, patientId);
        if (overviewService != null) {
            return overviewService.getUnifiedAuditLogs(patientId);
        }
        List<DoctorRecordService.AuditLogRow> list = new ArrayList<>();

        if (auditRepository != null) {
            List<SystemAuditLog> dbLogs = auditRepository.findByEntityTableAndEntityIdOrderByCreatedAtDesc("patient_profiles", patientId);
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
                list.add(new DoctorRecordService.AuditLogRow(l.getLogId(), l.getCreatedAt(), user, actionGroup, badge, label, "Hồ sơ bệnh nhân", details));
            }
        }

        List<Visit> patientVisits = visits.forPatient(patientId);
        for (Visit v : patientVisits) {
            String dr = v.getPrimaryDoctor() != null ? v.getPrimaryDoctor().getFullName() : "Bác sĩ phụ trách";
            list.add(new DoctorRecordService.AuditLogRow(
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

        list.sort(Comparator.comparing(DoctorRecordService.AuditLogRow::timestamp, Comparator.nullsLast(Comparator.reverseOrder())));
        return list;
    }

    public void updateHealthNotes(HttpSession session, Long patientId, String allergyDetails, String underlyingDiseaseDetails) {
        var scope = access.require(session);
        ReceptionPatientRecord profile = visibleProfile(scope, patientId);
        String cleanAllergy = allergyDetails == null || allergyDetails.isBlank() ? null : allergyDetails.trim();
        String cleanUnderlying = underlyingDiseaseDetails == null || underlyingDiseaseDetails.isBlank() ? null : underlyingDiseaseDetails.trim();
        profile.setAllergyDetails(cleanAllergy);
        profile.setHasAllergies(cleanAllergy != null);
        profile.setUnderlyingDiseaseDetails(cleanUnderlying);
        profile.setHasUnderlyingDisease(cleanUnderlying != null);
        profiles.saveAndFlush(profile);
        audit(scope, profile.getPreferredBranch(), "UPDATE_HEALTH_NOTES", patientId, null, snapshot(profile));
    }

    public void updatePatientCode(HttpSession session, Long patientId, String newCode) {
        if (newCode == null || newCode.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã bệnh nhân không được để trống.");
        var scope = access.require(session);
        ReceptionPatientRecord profile = visibleProfile(scope, patientId);
        profile.setPatientCode(newCode.trim().toUpperCase());
        profiles.saveAndFlush(profile);
        audit(scope, profile.getPreferredBranch(), "UPDATE_PATIENT_CODE", patientId, null, snapshot(profile));
    }

    public void updateDocumentType(HttpSession session, Long patientId, Long documentId, com.cvdcms.entity.enums.DocumentType newType) {
        var scope = access.require(session);
        visibleProfile(scope, patientId);
        if (documentRepository == null) return;
        PatientDocument doc = documentRepository.findByDocumentIdAndPatientProfile_ProfileId(documentId, patientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tài liệu scan."));
        doc.setDocumentType(newType);
        documentRepository.saveAndFlush(doc);
    }

    public void deleteDocument(HttpSession session, Long patientId, Long documentId) {
        var scope = access.require(session);
        visibleProfile(scope, patientId);
        if (documentRepository == null) return;
        PatientDocument doc = documentRepository.findByDocumentIdAndPatientProfile_ProfileId(documentId, patientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tài liệu scan."));
        documentRepository.delete(doc);
    }

    @Transactional(readOnly = true)
    public VisitDetails visit(HttpSession session, Long patientId, Long visitId) {
        var scope = access.require(session);
        visibleProfile(scope, patientId);
        Visit visit = visits.oneForPatient(patientId, visitId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new VisitDetails(ReceptionVisitView.from(visit),
                performedServices.findByVisitIdWithDetails(visitId).stream()
                        .map(item -> item.getService().getServiceName()).toList(),
                appointments.findByProfileIdWithDetails(patientId).stream()
                        .filter(a -> a.getAppointmentDate().isAfter(visit.getCheckInAt().toLocalDate()))
                        .filter(a -> a.getStatus() == com.cvdcms.entity.enums.AppointmentStatus.PENDING
                                || a.getStatus() == com.cvdcms.entity.enums.AppointmentStatus.CONFIRMED)
                        .map(a -> new FollowUp(a.getAppointmentCode(), a.getAppointmentDate(),
                                a.getExpectedTime(), a.getStatus().name(),
                                a.getDoctor() == null ? null : a.getDoctor().getFullName(),
                                a.getService() == null ? null : a.getService().getServiceName()))
                        .toList());
    }

    @Transactional(readOnly = true)
    public List<Branch> branches(HttpSession session) {
        var scope = access.require(session);
        return branches.findByIsActiveTrueOrderByBranchNameAsc().stream()
                .filter(branch -> scope.permits(branch.getBranchId())).toList();
    }

    private ReceptionPatientRecord visibleProfile(PatientAccess.Scope scope, Long id) {
        ReceptionPatientRecord profile = profiles.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!scope.permits(profile.getPreferredBranch().getBranchId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return profile;
    }

    private Branch allowedBranch(PatientAccess.Scope scope, Long id) {
        if (id == null || !scope.permits(id)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        Branch branch = branches.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chi nhánh không tồn tại."));
        if (!Boolean.TRUE.equals(branch.getIsActive()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chi nhánh đã ngừng hoạt động.");
        return branch;
    }

    private void requireDuplicateConfirmation(PatientAccess.Scope scope, PatientProfileForm form, Long excludeId) {
        List<DuplicateCandidate> candidates = findCandidates(scope, form.fullName(), form.dob(),
                form.phoneNumber(), form.idCardNumber(), excludeId);
        if (candidates.isEmpty()) {
            return;
        }

        String cleanIdCard = blankToNull(form.idCardNumber());
        String cleanPhone = blankToNull(form.phoneNumber());
        String cleanName = blankToNull(form.fullName());

        for (DuplicateCandidate c : candidates) {
            boolean exactIdCardMatch = cleanIdCard != null && c.matchedFields().contains("Số CCCD");
            boolean exactPhoneMatch = cleanPhone != null && c.matchedFields().contains("Số điện thoại");
            if (exactIdCardMatch) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Số CCCD " + cleanIdCard + " đã được sử dụng cho bệnh nhân (Mã: " + c.code() + " - " + c.fullName() + " tại " + c.branchName() + "). Không được tạo hồ sơ trùng số CCCD.");
            }
            if (exactPhoneMatch) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Số điện thoại " + cleanPhone + " đã được sử dụng cho bệnh nhân (Mã: " + c.code() + " - " + c.fullName() + " tại " + c.branchName() + "). Không được tạo hồ sơ trùng số điện thoại.");
            }
        }

        if (!form.confirmDuplicate()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Có hồ sơ tương tự. Vui lòng đối chiếu và xác nhận trước khi lưu.");
        }
    }

    private List<DuplicateCandidate> findCandidates(PatientAccess.Scope scope, String name, LocalDate dob,
                                                     String phone, String idCardNumber, Long excludeId) {
        String cleanName = blankToNull(name);
        String cleanPhone = blankToNull(phone);
        String cleanIdCard = blankToNull(idCardNumber);
        if (cleanPhone == null && (cleanName == null || dob == null) && cleanIdCard == null) return List.of();
        List<ReceptionPatientRecord> found = search.candidates(null,
                cleanPhone == null ? "" : cleanPhone,
                dob, cleanName == null ? "" : cleanName.toLowerCase(Locale.ROOT),
                cleanIdCard == null ? "" : cleanIdCard,
                PageRequest.of(0, 15));
        return found.stream().filter(p -> !p.getProfileId().equals(excludeId)).map(p -> {
            List<String> matches = new ArrayList<>();
            if (cleanIdCard != null && cleanIdCard.equals(p.getIdCardNumber())) matches.add("Số CCCD");
            if (cleanPhone != null && cleanPhone.equals(p.getPhoneNumber())) matches.add("Số điện thoại");
            if (cleanName != null && cleanName.equalsIgnoreCase(p.getFullName().trim())) matches.add("Họ tên");
            if (dob != null && dob.equals(p.getDob())) matches.add("Ngày sinh");
            return new DuplicateCandidate(p.getProfileId(), p.getPatientCode(), p.getFullName(),
                    p.getDob(), p.getPhoneNumber(), p.getPreferredBranch().getBranchName(), matches);
        }).toList();
    }

    private void apply(ReceptionPatientRecord profile, PatientProfileForm form, Branch branch) {
        validateGuardianForMinor(form);
        profile.setFullName(form.fullName().trim());
        profile.setDob(form.dob());
        profile.setGender(form.gender().trim());
        profile.setPhoneNumber(form.phoneNumber().trim());
        profile.setAddress(form.address() == null ? "" : form.address().trim());
        profile.setPreferredBranch(branch);
        profile.setAllergyDetails(blankToNull(form.allergyDetails()));
        profile.setHasAllergies(profile.getAllergyDetails() == null ? null : true);
        profile.setUnderlyingDiseaseDetails(blankToNull(form.underlyingDiseaseDetails()));
        profile.setHasUnderlyingDisease(profile.getUnderlyingDiseaseDetails() == null ? null : true);
        profile.setGuardianName(blankToNull(form.guardianName()));
        profile.setGuardianPhone(blankToNull(form.guardianPhone()));
        profile.setGuardianRelationship(blankToNull(form.guardianRelationship()));
        profile.setIdCardNumber(blankToNull(form.idCardNumber()));
        profile.setHealthInsuranceNumber(blankToNull(form.healthInsuranceNumber()));
    }

    private void validateGuardianForMinor(PatientProfileForm form) {
        if (!form.dob().plusYears(18).isAfter(LocalDate.now())) return;
        if (blankToNull(form.guardianName()) == null || blankToNull(form.guardianPhone()) == null
                || blankToNull(form.guardianRelationship()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Bệnh nhân dưới 18 tuổi phải có họ tên, số điện thoại và quan hệ của người giám hộ.");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Map<String, Object> snapshot(ReceptionPatientRecord p) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("patientCode", p.getPatientCode());
        values.put("fullName", p.getFullName());
        values.put("dob", p.getDob() == null ? null : p.getDob().toString());
        values.put("gender", p.getGender());
        values.put("phoneNumber", p.getPhoneNumber());
        values.put("address", p.getAddress());
        values.put("branchId", p.getPreferredBranch().getBranchId());
        values.put("allergyDetails", p.getAllergyDetails());
        values.put("underlyingDiseaseDetails", p.getUnderlyingDiseaseDetails());
        values.put("guardianName", p.getGuardianName());
        values.put("guardianPhone", p.getGuardianPhone());
        values.put("guardianRelationship", p.getGuardianRelationship());
        values.put("idCardNumber", p.getIdCardNumber());
        values.put("healthInsuranceNumber", p.getHealthInsuranceNumber());
        return values;
    }

    private void audit(PatientAccess.Scope scope, Branch branch, String action, Long id,
                       Map<String, Object> before, Map<String, Object> after) {
        SystemAuditLog log = new SystemAuditLog();
        log.setUser(scope.user());
        log.setBranch(branch);
        log.setAction(action);
        log.setEntityTable("patient_profiles");
        log.setEntityId(id);
        try {
            if (before != null) log.setOldValuesJson(mapper.writeValueAsString(before));
            log.setNewValuesJson(mapper.writeValueAsString(after));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Không thể ghi nhật ký hồ sơ.", ex);
        }
        auditRepository.save(log);
    }

    public record VisitDetails(ReceptionVisitView visit, List<String> services,
                               List<FollowUp> followUps) {}
    public record FollowUp(String code, LocalDate date, java.time.LocalTime expectedTime,
                           String status, String doctorName, String serviceName) {}
}
