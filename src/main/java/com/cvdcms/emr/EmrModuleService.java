package com.cvdcms.emr;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.DiagnosisType;
import com.cvdcms.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class EmrModuleService {
    private static final DateTimeFormatter RECORD_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final EmrAccess access;
    private final EmrRecordRepository records;
    private final EmrRecordVersionRepository versions;
    private final EmrAuditEventRepository audits;
    private final VisitRepository visits;
    private final ClinicalExaminationRepository examinations;
    private final ExaminationDiagnosisRepository diagnoses;
    private final PerformedServiceRepository performedServices;
    private final PrescriptionRepository prescriptions;
    private final ClinicalPhotoRepository photos;
    private final StaffProfileRepository staffProfiles;
    private final ObjectMapper mapper;

    public EmrModuleService(EmrAccess access, EmrRecordRepository records,
            EmrRecordVersionRepository versions, EmrAuditEventRepository audits,
            VisitRepository visits, ClinicalExaminationRepository examinations,
            ExaminationDiagnosisRepository diagnoses, PerformedServiceRepository performedServices,
            PrescriptionRepository prescriptions, ClinicalPhotoRepository photos,
            StaffProfileRepository staffProfiles, ObjectMapper mapper) {
        this.access = access;
        this.records = records;
        this.versions = versions;
        this.audits = audits;
        this.visits = visits;
        this.examinations = examinations;
        this.diagnoses = diagnoses;
        this.performedServices = performedServices;
        this.prescriptions = prescriptions;
        this.photos = photos;
        this.staffProfiles = staffProfiles;
        this.mapper = mapper;
    }

    public Dashboard dashboard(HttpSession session, String query, EmrRecordStatus status) {
        EmrAccess.Scope scope = access.require(session);
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<RecordRow> recordRows = records.findAllWithDetails().stream()
                .filter(r -> scope.permits(r.getBranch().getBranchId()))
                .filter(r -> status == null || r.getStatus() == status)
                .filter(r -> normalized.isBlank() || matches(r, normalized))
                .map(this::row)
                .toList();

        List<EligibleVisitRow> eligible = visits.findFilteredVisits(null, null, null, query).stream()
                .filter(v -> scope.permits(v.getBranch().getBranchId()))
                .filter(v -> !records.existsByVisit_VisitId(v.getVisitId()))
                .limit(20)
                .map(v -> {
                    List<String> missing = validateSource(v);
                    return new EligibleVisitRow(v.getVisitId(), v.getVisitCode(),
                            v.getPatientProfile().getPatientCode(), v.getPatientProfile().getFullName(),
                            v.getCheckInAt(), v.getBranch().getBranchName(),
                            v.getPrimaryDoctor() == null ? null : v.getPrimaryDoctor().getFullName(),
                            missing.isEmpty(), missing);
                }).toList();

        long signed = recordRows.stream().filter(r -> r.status() == EmrRecordStatus.SIGNED).count();
        long waiting = recordRows.stream().filter(r -> r.status() == EmrRecordStatus.PATIENT_SIGNATURE_PENDING
                || r.status() == EmrRecordStatus.DOCTOR_SIGNATURE_PENDING).count();
        return new Dashboard(recordRows, eligible, recordRows.size(), signed, waiting);
    }

    public RecordDetail detail(HttpSession session, Long recordId) {
        EmrAccess.Scope scope = access.require(session);
        EmrRecord record = requireRecord(recordId, scope);
        EmrRecordVersion current = currentVersion(record);
        List<String> missing = validateSource(record.getVisit());
        String currentSourceHash = EmrHashing.sha256(toJson(buildSource(record.getVisit())));
        boolean stale = current.getSnapshot() == null
                || !currentSourceHash.equals(current.getSnapshot().getSourceHash());
        if (stale && EmrStatePolicy.canRefresh(record.getStatus())) {
            missing = new ArrayList<>(missing);
            missing.add(0, "Dữ liệu khám nguồn đã thay đổi. Cần làm mới snapshot trước khi khóa.");
        }
        List<VersionRow> versionRows = versions.findAllDetailsByRecordId(recordId).stream()
                .map(v -> new VersionRow(v.getVersionNumber(), v.getStatus(), v.getCreatedAt(),
                        v.getLockedAt(), v.getSignedAt(), v.getAmendmentReason(), v.getContentHash()))
                .toList();
        List<SignatureRow> signatureRows = current.getSignatures().stream()
                .map(s -> new SignatureRow(s.getSignerType(), s.getSignerName(), s.getProvider(),
                        s.getStatus(), s.getSignedAt(), s.getExternalTransactionId())).toList();
        List<DocumentRow> documentRows = current.getDocuments().stream()
                .map(d -> new DocumentRow(d.getDocumentCode(), d.getTitle(), d.getDocumentType(),
                        d.getSortOrder(), d.getDocumentHash())).toList();
        List<AuditRow> auditRows = audits.findByRecord_RecordIdOrderByCreatedAtDesc(recordId).stream()
                .map(a -> new AuditRow(a.getAction(), a.getDetails(),
                        a.getActorUser() == null ? "Hệ thống" : a.getActorUser().getUsername(), a.getCreatedAt()))
                .toList();
        return new RecordDetail(row(record), current.getVersionNumber(), current.getStatus(),
                parseJson(current.getSnapshot().getSourceJson()), stale, missing,
                documentRows, signatureRows, versionRows, auditRows,
                EmrStatePolicy.canRefresh(record.getStatus()), EmrStatePolicy.canValidate(record.getStatus()),
                EmrStatePolicy.canLock(record.getStatus()), EmrStatePolicy.canPatientSign(record.getStatus()),
                EmrStatePolicy.canDoctorSign(record.getStatus()), EmrStatePolicy.canAmend(record.getStatus()));
    }

    @Transactional
    public Long create(HttpSession session, Long visitId) {
        EmrAccess.Scope scope = access.require(session);
        Visit visit = visits.findWithDetailsById(visitId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lượt khám."));
        access.requireBranch(scope, visit.getBranch().getBranchId());
        records.findByVisit_VisitId(visitId).ifPresent(existing -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Lượt khám đã có bệnh án " + existing.getRecordCode() + ".");
        });

        EmrRecord record = new EmrRecord();
        record.setRecordCode("EMR-" + RECORD_DATE.format(visit.getCheckInAt()) + "-" + visit.getVisitId());
        record.setVisit(visit);
        record.setPatientProfile(visit.getPatientProfile());
        record.setBranch(visit.getBranch());
        record.setStatus(EmrRecordStatus.DRAFT);
        record.setCurrentVersionNumber(1);
        record.setCreatedBy(scope.user());
        records.save(record);

        EmrRecordVersion version = new EmrRecordVersion();
        version.setRecord(record);
        version.setVersionNumber(1);
        version.setCreatedBy(scope.user());
        versions.save(version);
        capture(version, visit);
        audit(record, version, scope.user(), "CREATE_DRAFT",
                "Tạo bệnh án từ lượt khám " + visit.getVisitCode() + ".");
        return record.getRecordId();
    }

    @Transactional
    public void refresh(HttpSession session, Long recordId) {
        EmrAccess.Scope scope = access.require(session);
        EmrRecord record = requireRecord(recordId, scope);
        if (!EmrStatePolicy.canRefresh(record.getStatus())) {
            conflict("Chỉ có thể làm mới bệnh án đang ở trạng thái nháp/chờ khóa.");
        }
        EmrRecordVersion version = currentVersion(record);
        version.setContentHash(null);
        version.setStatus(EmrVersionStatus.DRAFT);
        capture(version, record.getVisit());
        if (record.getStatus() != EmrRecordStatus.AMENDMENT_DRAFT) record.setStatus(EmrRecordStatus.DRAFT);
        audit(record, version, scope.user(), "REFRESH_SNAPSHOT", "Làm mới snapshot từ dữ liệu khám nguồn.");
    }

    @Transactional
    public ValidationResult validate(HttpSession session, Long recordId) {
        EmrAccess.Scope scope = access.require(session);
        EmrRecord record = requireRecord(recordId, scope);
        if (!EmrStatePolicy.canValidate(record.getStatus())) conflict("Trạng thái hiện tại không cho phép kiểm tra.");
        EmrRecordVersion version = currentVersion(record);
        List<String> missing = validateSource(record.getVisit());
        String currentHash = EmrHashing.sha256(toJson(buildSource(record.getVisit())));
        if (!currentHash.equals(version.getSnapshot().getSourceHash())) {
            missing = new ArrayList<>(missing);
            missing.add("Snapshot chưa đồng bộ với dữ liệu khám nguồn.");
        }
        if (missing.isEmpty()) {
            record.setStatus(EmrRecordStatus.READY_TO_LOCK);
            version.setStatus(EmrVersionStatus.READY_TO_LOCK);
            audit(record, version, scope.user(), "VALIDATE_READY", "Bệnh án đủ điều kiện khóa.");
        } else {
            audit(record, version, scope.user(), "VALIDATE_FAILED", String.join("; ", missing));
        }
        return new ValidationResult(missing.isEmpty(), missing);
    }

    @Transactional
    public void lock(HttpSession session, Long recordId) {
        EmrAccess.Scope scope = access.require(session);
        EmrRecord record = requireRecord(recordId, scope);
        if (!EmrStatePolicy.canLock(record.getStatus())) conflict("Bệnh án phải được kiểm tra hợp lệ trước khi khóa.");
        EmrRecordVersion version = currentVersion(record);
        List<String> missing = validateSource(record.getVisit());
        String currentHash = EmrHashing.sha256(toJson(buildSource(record.getVisit())));
        if (!currentHash.equals(version.getSnapshot().getSourceHash())) missing.add("Snapshot đã cũ.");
        if (!missing.isEmpty()) conflict(String.join("; ", missing));
        String documentMaterial = version.getDocuments().stream()
                .sorted(Comparator.comparing(EmrDocument::getSortOrder))
                .map(EmrDocument::getDocumentHash).reduce("", (a, b) -> a + b);
        version.setContentHash(EmrHashing.sha256(version.getSnapshot().getSourceHash() + documentMaterial));
        version.setStatus(EmrVersionStatus.LOCKED);
        version.setLockedAt(LocalDateTime.now());
        record.setStatus(EmrRecordStatus.PATIENT_SIGNATURE_PENDING);
        audit(record, version, scope.user(), "LOCK", "Khóa phiên bản và chốt content hash.");
    }

    @Transactional
    public void patientSignForTest(HttpSession session, Long recordId, String signerName) {
        EmrAccess.Scope scope = access.require(session);
        EmrRecord record = requireRecord(recordId, scope);
        if (!EmrStatePolicy.canPatientSign(record.getStatus())) conflict("Bệnh án chưa ở bước người bệnh ký.");
        String name = signerName == null || signerName.isBlank()
                ? record.getPatientProfile().getFullName() : signerName.trim();
        EmrRecordVersion version = currentVersion(record);
        createTestSignature(version, EmrSignerType.PATIENT, name, null);
        record.setStatus(EmrRecordStatus.DOCTOR_SIGNATURE_PENDING);
        audit(record, version, scope.user(), "PATIENT_SIGN_TEST",
                "Người bệnh xác nhận bằng bộ ký thử nghiệm nội bộ.");
    }

    @Transactional
    public void doctorSignForTest(HttpSession session, Long recordId) {
        EmrAccess.Scope scope = access.require(session);
        EmrRecord record = requireRecord(recordId, scope);
        if (!EmrStatePolicy.canDoctorSign(record.getStatus())) conflict("Bệnh án chưa ở bước bác sĩ ký.");
        EmrRecordVersion version = currentVersion(record);
        String doctorName = staffProfiles.findByUser_UserId(scope.user().getUserId())
                .map(StaffProfile::getFullName).orElse(scope.user().getUsername());
        createTestSignature(version, EmrSignerType.DOCTOR, doctorName, scope.user());
        versions.findAllDetailsByRecordId(recordId).stream()
                .filter(previous -> previous.getVersionNumber() < version.getVersionNumber())
                .filter(previous -> previous.getStatus() == EmrVersionStatus.SIGNED)
                .forEach(previous -> previous.setStatus(EmrVersionStatus.SUPERSEDED));
        version.setStatus(EmrVersionStatus.SIGNED);
        version.setSignedAt(LocalDateTime.now());
        record.setStatus(EmrRecordStatus.SIGNED);
        audit(record, version, scope.user(), "DOCTOR_SIGN_TEST",
                "Hoàn tất phiên bản bằng bộ ký thử nghiệm nội bộ.");
    }

    @Transactional
    public void createAmendment(HttpSession session, Long recordId, String reason) {
        EmrAccess.Scope scope = access.require(session);
        EmrRecord record = requireRecord(recordId, scope);
        if (!EmrStatePolicy.canAmend(record.getStatus())) conflict("Chỉ bệnh án đã ký mới được tạo bản sửa đổi.");
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phải nhập lý do sửa đổi.");
        }
        int next = record.getCurrentVersionNumber() + 1;
        EmrRecordVersion version = new EmrRecordVersion();
        version.setRecord(record);
        version.setVersionNumber(next);
        version.setAmendmentReason(reason.trim());
        version.setCreatedBy(scope.user());
        versions.save(version);
        capture(version, record.getVisit());
        record.setCurrentVersionNumber(next);
        record.setStatus(EmrRecordStatus.AMENDMENT_DRAFT);
        audit(record, version, scope.user(), "CREATE_AMENDMENT", reason.trim());
    }

    private void capture(EmrRecordVersion version, Visit visit) {
        String json = toJson(buildSource(visit));
        EmrSourceSnapshot snapshot = version.getSnapshot();
        if (snapshot == null) {
            snapshot = new EmrSourceSnapshot();
            snapshot.setVersion(version);
            version.setSnapshot(snapshot);
        }
        snapshot.setSourceJson(json);
        snapshot.setSourceHash(EmrHashing.sha256(json));
        upsertDocument(version, "COVER", "Bìa hồ sơ bệnh án", "COVER", 10, json);
        upsertDocument(version, "OUTPATIENT_DENTAL",
                "Bệnh án ngoại trú Răng Hàm Mặt", "MEDICAL_RECORD", 20, json);
        upsertDocument(version, "TREATMENT_SUMMARY",
                "Tổng kết quá trình khám và điều trị", "SUMMARY", 30, json);
        versions.save(version);
    }

    private void upsertDocument(EmrRecordVersion version, String code, String title,
            String type, int sortOrder, String content) {
        EmrDocument document = version.getDocuments().stream()
                .filter(existing -> code.equals(existing.getDocumentCode()))
                .findFirst().orElseGet(() -> {
                    EmrDocument created = new EmrDocument();
                    created.setVersion(version);
                    version.getDocuments().add(created);
                    return created;
                });
        document.setDocumentCode(code);
        document.setTitle(title);
        document.setDocumentType(type);
        document.setSortOrder(sortOrder);
        document.setContentJson(content);
        document.setDocumentHash(EmrHashing.sha256(code + "|" + content));
    }

    private void createTestSignature(EmrRecordVersion version, EmrSignerType type,
            String signerName, User user) {
        LocalDateTime signedAt = LocalDateTime.now();
        EmrSignature signature = new EmrSignature();
        signature.setVersion(version);
        signature.setSignerType(type);
        signature.setSignerName(signerName);
        signature.setSignerUser(user);
        signature.setProvider("LOCAL_TEST_ONLY");
        signature.setExternalTransactionId(UUID.randomUUID().toString());
        signature.setStatus(EmrSignatureStatus.SIGNED);
        signature.setSignedAt(signedAt);
        signature.setSignatureHash(EmrHashing.sha256(version.getContentHash() + "|" + type + "|"
                + signerName + "|" + signedAt));
        version.getSignatures().add(signature);
    }

    private Map<String, Object> buildSource(Visit visit) {
        ClinicalExamination exam = examinations.findByVisit_VisitId(visit.getVisitId()).orElse(null);
        List<ExaminationDiagnosis> diagnosisRows = diagnoses.findByVisitIdWithDetails(visit.getVisitId());
        List<PerformedService> serviceRows = performedServices.findByVisitIdWithDetails(visit.getVisitId());
        List<Prescription> prescriptionRows = prescriptions.findByVisit_VisitIdOrderByCreatedAtDesc(visit.getVisitId());
        List<ClinicalPhoto> photoRows = photos.findByPatientProfile_ProfileIdOrderByCapturedAtDesc(
                visit.getPatientProfile().getProfileId()).stream()
                .filter(p -> p.getVisit() != null && Objects.equals(p.getVisit().getVisitId(), visit.getVisitId()))
                .toList();

        Map<String, Object> root = new LinkedHashMap<>();
        PatientProfile patient = visit.getPatientProfile();
        root.put("patient", mapOf(
                "profileId", patient.getProfileId(), "patientCode", patient.getPatientCode(),
                "fullName", patient.getFullName(), "dob", text(patient.getDob()), "gender", patient.getGender(),
                "phoneNumber", patient.getPhoneNumber(), "address", patient.getAddress(),
                "idCardNumber", patient.getIdCardNumber(), "healthInsuranceNumber", patient.getHealthInsuranceNumber(),
                "allergyDetails", patient.getAllergyDetails(),
                "underlyingDiseaseDetails", patient.getUnderlyingDiseaseDetails(),
                "guardianName", patient.getGuardianName(), "guardianPhone", patient.getGuardianPhone()));
        root.put("visit", mapOf(
                "visitId", visit.getVisitId(), "visitCode", visit.getVisitCode(),
                "checkInAt", text(visit.getCheckInAt()), "visitType", text(visit.getVisitType()),
                "initialSymptoms", visit.getInitialSymptoms(), "status", text(visit.getStatus()),
                "branchCode", visit.getBranch().getBranchCode(), "branchName", visit.getBranch().getBranchName(),
                "doctorName", visit.getPrimaryDoctor() == null ? null : visit.getPrimaryDoctor().getFullName(),
                "doctorLicense", visit.getPrimaryDoctor() == null ? null : visit.getPrimaryDoctor().getPracticeLicenseNo()));
        root.put("clinical", exam == null ? Map.of() : mapOf(
                "chiefComplaint", exam.getChiefComplaint(), "historyIllness", exam.getHistoryIllness(),
                "vitalSigns", exam.getVitalSignsJson(), "extraoralExam", exam.getExtraoralExam(),
                "intraoralExam", exam.getIntraoralExam(), "periodontalStatus", exam.getPeriodontalStatus(),
                "odontogram", exam.getOdontogramChartJson(), "summaryAssessment", exam.getSummaryAssessment(),
                "examTime", text(exam.getExamTime())));
        root.put("diagnoses", diagnosisRows.stream().map(d -> mapOf(
                "type", text(d.getDiagnosisType()), "icd10Code",
                d.getIcd10Disease() == null ? null : d.getIcd10Disease().getIcd10Code(),
                "diagnosisText", d.getDiagnosisText(), "toothNumbers", d.getToothNumbers(), "notes", d.getNotes())).toList());
        root.put("performedServices", serviceRows.stream().map(s -> mapOf(
                "serviceCode", s.getService().getServiceCode(), "serviceName", s.getService().getServiceName(),
                "toothNumbers", s.getToothNumbers(), "quantity", s.getQuantity(),
                "doctorName", s.getDoctor().getFullName(), "completedAt", text(s.getCompletedAt()),
                "status", text(s.getStatus()), "clinicalNotes", s.getClinicalNotes())).toList());
        root.put("prescriptions", prescriptionRows.stream().map(p -> mapOf(
                "prescriptionCode", p.getPrescriptionCode(), "diagnosisText", p.getDiagnosisText(),
                "adviceNotes", p.getAdviceNotes(), "followupDate", text(p.getFollowupDate()),
                "items", p.getItems().stream().map(i -> mapOf("drugName", i.getDrugName(),
                        "dosage", i.getDosage(), "durationDays", i.getDurationDays(),
                        "quantity", i.getQuantity(), "unit", i.getUnit(),
                        "usageInstruction", i.getUsageInstruction())).toList())).toList());
        root.put("photos", photoRows.stream().map(p -> mapOf("stage", text(p.getPhotoStage()),
                "category", text(p.getPhotoCategory()), "imageUrl", p.getImageUrl(),
                "caption", p.getCaption(), "capturedAt", text(p.getCapturedAt()))).toList());
        return root;
    }

    private List<String> validateSource(Visit visit) {
        List<String> missing = new ArrayList<>();
        ClinicalExamination exam = examinations.findByVisit_VisitId(visit.getVisitId()).orElse(null);
        if (exam == null) missing.add("Chưa có phiếu khám lâm sàng.");
        String complaint = exam == null ? visit.getInitialSymptoms() : exam.getChiefComplaint();
        if (complaint == null || complaint.isBlank()) missing.add("Thiếu lý do vào khám/triệu chứng chính.");
        if (visit.getPrimaryDoctor() == null) missing.add("Chưa gán bác sĩ khám bệnh.");
        List<ExaminationDiagnosis> rows = diagnoses.findByVisitIdWithDetails(visit.getVisitId());
        Optional<ExaminationDiagnosis> main = rows.stream()
                .filter(d -> d.getDiagnosisType() == DiagnosisType.MAIN).findFirst();
        if (main.isEmpty()) missing.add("Thiếu chẩn đoán chính.");
        else if (main.get().getIcd10Disease() == null) missing.add("Chẩn đoán chính chưa có mã ICD-10.");
        return missing;
    }

    private EmrRecord requireRecord(Long id, EmrAccess.Scope scope) {
        EmrRecord record = records.findDetailsById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bệnh án."));
        access.requireBranch(scope, record.getBranch().getBranchId());
        return record;
    }

    private EmrRecordVersion currentVersion(EmrRecord record) {
        return versions.findByRecord_RecordIdAndVersionNumber(record.getRecordId(), record.getCurrentVersionNumber())
                .orElseThrow(() -> new IllegalStateException("Bệnh án thiếu phiên bản hiện hành."));
    }

    private void audit(EmrRecord record, EmrRecordVersion version, User actor, String action, String details) {
        EmrAuditEvent event = new EmrAuditEvent();
        event.setRecord(record);
        event.setVersion(version);
        event.setActorUser(actor);
        event.setAction(action);
        event.setDetails(details);
        audits.save(event);
    }

    private RecordRow row(EmrRecord r) {
        return new RecordRow(r.getRecordId(), r.getRecordCode(), r.getVisit().getVisitId(),
                r.getVisit().getVisitCode(), r.getPatientProfile().getProfileId(),
                r.getPatientProfile().getPatientCode(),
                r.getPatientProfile().getFullName(), r.getBranch().getBranchName(),
                r.getVisit().getPrimaryDoctor() == null ? null : r.getVisit().getPrimaryDoctor().getFullName(),
                r.getVisit().getCheckInAt(), r.getStatus(), r.getCurrentVersionNumber(), r.getUpdatedAt());
    }

    private boolean matches(EmrRecord r, String query) {
        return contains(r.getRecordCode(), query) || contains(r.getVisit().getVisitCode(), query)
                || contains(r.getPatientProfile().getPatientCode(), query)
                || contains(r.getPatientProfile().getFullName(), query);
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private String toJson(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Không thể tạo snapshot bệnh án.", ex); }
    }

    private Map<String, Object> parseJson(String json) {
        try { return mapper.readValue(json, new TypeReference<>() {}); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Snapshot bệnh án không hợp lệ.", ex); }
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) map.put((String) values[i], values[i + 1]);
        return map;
    }

    private String text(Object value) { return value == null ? null : value.toString(); }

    private void conflict(String message) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record Dashboard(List<RecordRow> records, List<EligibleVisitRow> eligibleVisits,
                            long total, long signed, long waitingSignature) {}
    public record RecordRow(Long id, String code, Long visitId, String visitCode, Long patientId,
                            String patientCode,
                            String patientName, String branchName, String doctorName, LocalDateTime checkInAt,
                            EmrRecordStatus status, Integer versionNumber, LocalDateTime updatedAt) {}
    public record EligibleVisitRow(Long visitId, String visitCode, String patientCode, String patientName,
                                   LocalDateTime checkInAt, String branchName, String doctorName,
                                   boolean ready, List<String> missing) {}
    public record RecordDetail(RecordRow record, Integer currentVersion, EmrVersionStatus versionStatus,
                               Map<String, Object> snapshot, boolean stale, List<String> missing,
                               List<DocumentRow> documents, List<SignatureRow> signatures,
                               List<VersionRow> versions, List<AuditRow> audits,
                               boolean canRefresh, boolean canValidate, boolean canLock,
                               boolean canPatientSign, boolean canDoctorSign, boolean canAmend) {}
    public record DocumentRow(String code, String title, String type, Integer sortOrder, String hash) {}
    public record SignatureRow(EmrSignerType type, String signerName, String provider,
                               EmrSignatureStatus status, LocalDateTime signedAt, String transactionId) {}
    public record VersionRow(Integer number, EmrVersionStatus status, LocalDateTime createdAt,
                             LocalDateTime lockedAt, LocalDateTime signedAt, String reason, String hash) {}
    public record AuditRow(String action, String details, String actor, LocalDateTime createdAt) {}
    public record ValidationResult(boolean ready, List<String> missing) {}
}
