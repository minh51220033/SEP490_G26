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
    private static final Map<String, Set<String>> EDITABLE_DOCUMENT_FIELDS = Map.of(
            "COVER", Set.of("archiveNo", "department", "roomBed", "admissionAt", "dischargeAt"),
            "OUTPATIENT_DENTAL", Set.of("referralDiagnosis", "diseaseProgress", "personalHistory",
                    "familyHistory", "lowerLevelTreatment", "treatmentPlan", "treatmentNotes"),
            "TREATMENT_SUMMARY", Set.of("clinicalProgressSummary", "paraclinicalSummary",
                    "mainClinicalSigns", "dischargeDiagnosis", "treatmentMethod",
                    "dischargeCondition", "followupInstructions"),
            "ATTACHMENT_INDEX", Set.of(),
            "VERIFICATION", Set.of());

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
    private final NanopixXraySyncLogRepository xrays;
    private final LaboOrderRepository laboOrders;
    private final EmrAttachmentRepository attachments;
    private final EmrAttachmentStorage attachmentStorage;
    private final TreatmentPlanRepository treatmentPlans;
    private final StaffProfileRepository staffProfiles;
    private final ObjectMapper mapper;

    public EmrModuleService(EmrAccess access, EmrRecordRepository records,
            EmrRecordVersionRepository versions, EmrAuditEventRepository audits,
            VisitRepository visits, ClinicalExaminationRepository examinations,
            ExaminationDiagnosisRepository diagnoses, PerformedServiceRepository performedServices,
            PrescriptionRepository prescriptions, ClinicalPhotoRepository photos,
            NanopixXraySyncLogRepository xrays, LaboOrderRepository laboOrders,
            TreatmentPlanRepository treatmentPlans,
            EmrAttachmentRepository attachments, EmrAttachmentStorage attachmentStorage,
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
        this.xrays = xrays;
        this.laboOrders = laboOrders;
        this.attachments = attachments;
        this.attachmentStorage = attachmentStorage;
        this.treatmentPlans = treatmentPlans;
        this.staffProfiles = staffProfiles;
        this.mapper = mapper;
    }

    public Dashboard dashboard(HttpSession session, String query, EmrRecordStatus status) {
        return dashboard(session, query, status, null, null, null, null);
    }

    public Dashboard dashboard(HttpSession session, String query, EmrRecordStatus status,
            java.time.LocalDate fromDate, java.time.LocalDate toDate, String doctor, String branch) {
        EmrAccess.Scope scope = access.require(session);
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<RecordRow> recordRows = records.findAllWithDetails().stream()
                .filter(r -> scope.permits(r.getBranch().getBranchId()))
                .filter(r -> status == null || r.getStatus() == status)
                .filter(r -> fromDate == null || !r.getVisit().getCheckInAt().toLocalDate().isBefore(fromDate))
                .filter(r -> toDate == null || !r.getVisit().getCheckInAt().toLocalDate().isAfter(toDate))
                .filter(r -> doctor == null || doctor.isBlank() || r.getVisit().getPrimaryDoctor() != null
                        && r.getVisit().getPrimaryDoctor().getFullName().toLowerCase(Locale.ROOT).contains(doctor.trim().toLowerCase(Locale.ROOT)))
                .filter(r -> branch == null || branch.isBlank() || r.getBranch().getBranchName()
                        .toLowerCase(Locale.ROOT).contains(branch.trim().toLowerCase(Locale.ROOT)))
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
        List<String> missing = validateRecord(record.getVisit(), current);
        List<String> warnings = warningsFor(record.getVisit());
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
                .map(s -> new SignatureRow(s.getSignatureId(), s.getSignerType(), s.getSignerName(), s.getProvider(),
                        s.getStatus(), s.getSignedAt(), s.getExternalTransactionId())).toList();
        List<AttachmentRow> attachmentRows = attachments.findByVersion_VersionIdOrderByIdAsc(current.getVersionId())
                .stream().map(a -> new AttachmentRow(a.getId(), a.getCaption(), a.getOriginalName(),
                        a.getMimeType(), a.getByteSize(), a.getSha256(), a.getCapturedAt())).toList();
        List<DocumentRow> documentRows = current.getDocuments().stream()
                .map(d -> new DocumentRow(d.getDocumentCode(), d.getTitle(), d.getDocumentType(),
                        d.getSortOrder(), d.getDocumentHash(), documentManualFields(d))).toList();
        Map<String, Map<String, String>> documentFields = new LinkedHashMap<>();
        documentRows.forEach(document -> documentFields.put(document.code(), document.fields()));
        List<AuditRow> auditRows = audits.findByRecord_RecordIdOrderByCreatedAtDesc(recordId).stream()
                .map(a -> new AuditRow(a.getAction(), a.getDetails(),
                        a.getActorUser() == null ? "Hệ thống" : a.getActorUser().getUsername(), a.getCreatedAt()))
                .toList();
        return new RecordDetail(row(record), current.getVersionNumber(), current.getStatus(), current.getContentHash(),
                parseJson(current.getSnapshot().getSourceJson()), stale, missing, warnings,
                documentRows, documentFields, attachmentRows, signatureRows, versionRows, auditRows,
                EmrStatePolicy.canRefresh(record.getStatus()), EmrStatePolicy.canValidate(record.getStatus()),
                EmrStatePolicy.canLock(record.getStatus()),
                EmrStatePolicy.canDoctorSign(record.getStatus()),
                EmrStatePolicy.canAmend(record.getStatus()));
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
        List<String> missing = validateRecord(record.getVisit(), version);
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
            if (record.getStatus() == EmrRecordStatus.READY_TO_LOCK) {
                record.setStatus(version.getVersionNumber() > 1
                        ? EmrRecordStatus.AMENDMENT_DRAFT : EmrRecordStatus.DRAFT);
                version.setStatus(EmrVersionStatus.DRAFT);
            }
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
        List<String> missing = validateRecord(record.getVisit(), version);
        String currentHash = EmrHashing.sha256(toJson(buildSource(record.getVisit())));
        if (!currentHash.equals(version.getSnapshot().getSourceHash())) missing.add("Snapshot đã cũ.");
        if (!missing.isEmpty()) conflict(String.join("; ", missing));
        List<ClinicalPhoto> sourcePhotos = photos.findByPatientProfile_ProfileIdOrderByCapturedAtDesc(
                record.getPatientProfile().getProfileId()).stream()
                .filter(photo -> photo.getVisit() != null
                        && Objects.equals(photo.getVisit().getVisitId(), record.getVisit().getVisitId()))
                .toList();
        List<EmrAttachment> archived = new ArrayList<>();
        for (ClinicalPhoto photo : sourcePhotos) archived.add(attachmentStorage.archive(version, photo));
        for (NanopixXraySyncLog xray : xrays.findByMatchedVisit_VisitIdOrderByCapturedAtDesc(record.getVisit().getVisitId()))
            archived.add(attachmentStorage.archive(version, xray.getImageUrl(), "X-quang · " + xray.getExternalStudyUid()));
        for (LaboOrder labo : laboOrders.findByVisit_VisitIdOrderBySentDateDesc(record.getVisit().getVisitId()))
            if (labo.getImageUrl() != null && !labo.getImageUrl().isBlank())
                archived.add(attachmentStorage.archive(version, labo.getImageUrl(), "Labo · " + labo.getLaboCode()));
        attachments.saveAll(archived);
        String documentMaterial = version.getDocuments().stream()
                .sorted(Comparator.comparing(EmrDocument::getSortOrder))
                .map(EmrDocument::getDocumentHash).reduce("", (a, b) -> a + b);
        String attachmentMaterial = archived.stream().map(a -> a.getOriginalName() + ":" + a.getSha256())
                .sorted().reduce("", String::concat);
        version.setContentHash(EmrHashing.sha256(version.getSnapshot().getSourceHash()
                + documentMaterial + attachmentMaterial));
        version.setStatus(EmrVersionStatus.LOCKED);
        version.setLockedAt(LocalDateTime.now());
        record.setStatus(EmrRecordStatus.DOCTOR_SIGNATURE_PENDING);
        audit(record, version, scope.user(), "LOCK", "Khóa phiên bản và chốt content hash.");
    }

    @Transactional
    public void doctorSignForTest(HttpSession session, Long recordId, boolean confirmed) {
        if (!confirmed) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Bác sĩ cần tích xác nhận đã kiểm tra hồ sơ trước khi ký thử.");
        EmrAccess.Scope scope = access.require(session);
        EmrRecord record = requireRecord(recordId, scope);
        if (!EmrStatePolicy.canDoctorSign(record.getStatus()))
            conflict("Bệnh án chưa ở bước bác sĩ ký.");
        if (record.getVisit().getPrimaryDoctor() == null || !Objects.equals(
                record.getVisit().getPrimaryDoctor().getUser().getUserId(), scope.user().getUserId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ bác sĩ khám được ký hồ sơ này.");
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

    @Transactional
    public void updateDocument(HttpSession session, Long recordId, String documentCode,
            Map<String, String> submittedFields) {
        EmrAccess.Scope scope = access.require(session);
        EmrRecord record = requireRecord(recordId, scope);
        if (!EmrStatePolicy.canRefresh(record.getStatus())) {
            conflict("Chỉ có thể chỉnh sửa biểu mẫu khi bệnh án còn ở trạng thái nháp/chờ khóa.");
        }
        String normalizedCode = documentCode == null ? "" : documentCode.trim().toUpperCase(Locale.ROOT);
        Set<String> allowedFields = EDITABLE_DOCUMENT_FIELDS.get(normalizedCode);
        if (allowedFields == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy thành phần hồ sơ.");
        }
        if (allowedFields.isEmpty()) {
            conflict("Thành phần này được hệ thống sinh tự động và không có trường chỉnh sửa.");
        }

        EmrRecordVersion version = currentVersion(record);
        EmrDocument document = version.getDocuments().stream()
                .filter(item -> normalizedCode.equals(item.getDocumentCode()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Không tìm thấy biểu mẫu trong phiên bản hiện tại."));

        Map<String, Object> payload = documentPayload(document);
        Map<String, String> manual = new LinkedHashMap<>();
        for (String field : allowedFields) {
            String value = submittedFields == null ? null : submittedFields.get(field);
            if (value == null) continue;
            String normalized = value.trim();
            if (normalized.length() > 4000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Nội dung trường " + field + " vượt quá 4.000 ký tự.");
            }
            manual.put(field, normalized);
        }
        payload.put("manual", manual);
        document.setContentJson(toJson(payload));
        document.setDocumentHash(EmrHashing.sha256(normalizedCode + "|" + document.getContentJson()));
        version.setContentHash(null);
        version.setStatus(EmrVersionStatus.DRAFT);
        if (record.getStatus() != EmrRecordStatus.AMENDMENT_DRAFT) {
            record.setStatus(EmrRecordStatus.DRAFT);
        }
        audit(record, version, scope.user(), "UPDATE_DOCUMENT",
                "Cập nhật biểu mẫu " + normalizedCode + ".");
    }

    public String exportJson(HttpSession session, Long recordId) {
        EmrAccess.Scope scope = access.require(session);
        EmrRecord record = requireRecord(recordId, scope);
        EmrRecordVersion version = currentVersion(record);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordCode", record.getRecordCode());
        result.put("status", record.getStatus());
        result.put("version", version.getVersionNumber());
        result.put("versionStatus", version.getStatus());
        result.put("contentHash", version.getContentHash());
        result.put("source", parseJson(version.getSnapshot().getSourceJson()));
        result.put("documents", version.getDocuments().stream().map(document -> mapOf(
                "code", document.getDocumentCode(), "title", document.getTitle(),
                "type", document.getDocumentType(), "hash", document.getDocumentHash(),
                "manualFields", documentManualFields(document))).toList());
        result.put("attachments", attachments.findByVersion_VersionIdOrderByIdAsc(version.getVersionId())
                .stream().map(a -> mapOf("id", a.getId(), "name", a.getOriginalName(),
                        "mimeType", a.getMimeType(), "byteSize", a.getByteSize(),
                        "sha256", a.getSha256())).toList());
        result.put("signatures", version.getSignatures().stream().map(signature -> mapOf(
                "signerType", signature.getSignerType(), "signerName", signature.getSignerName(),
                "provider", signature.getProvider(), "status", signature.getStatus(),
                "signedAt", text(signature.getSignedAt()), "signatureHash", signature.getSignatureHash())).toList());
        return prettyJson(result);
    }

    public AttachmentDownload downloadAttachment(HttpSession session, Long recordId, Long attachmentId) {
        EmrAccess.Scope scope = access.require(session);
        requireRecord(recordId, scope);
        EmrAttachment attachment = attachments.findById(attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tệp EMR."));
        if (!Objects.equals(attachment.getVersion().getRecord().getRecordId(), recordId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tệp EMR.");
        return new AttachmentDownload(attachmentStorage.read(attachment), attachment.getMimeType(),
                attachment.getOriginalName());
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
                "Tổng kết bệnh án ngoại trú", "SUMMARY", 30, json);
        upsertDocument(version, "ATTACHMENT_INDEX",
                "Danh mục tài liệu đính kèm", "ATTACHMENT_INDEX", 40, json);
        upsertDocument(version, "VERIFICATION",
                "Thông tin xác thực hồ sơ", "VERIFICATION", 50, json);
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
        Map<String, Object> payload = documentPayload(document);
        payload.put("source", parseJson(content));
        payload.putIfAbsent("manual", new LinkedHashMap<String, String>());
        document.setContentJson(toJson(payload));
        document.setDocumentHash(EmrHashing.sha256(code + "|" + document.getContentJson()));
    }

    private Map<String, Object> documentPayload(EmrDocument document) {
        if (document.getContentJson() == null || document.getContentJson().isBlank()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> parsed = parseJson(document.getContentJson());
        if (parsed.containsKey("source") || parsed.containsKey("manual")) {
            return new LinkedHashMap<>(parsed);
        }
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("source", parsed);
        legacy.put("manual", new LinkedHashMap<String, String>());
        return legacy;
    }

    private Map<String, String> documentManualFields(EmrDocument document) {
        Object value = documentPayload(document).get("manual");
        if (!(value instanceof Map<?, ?> values)) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, item) -> {
            if (key != null && item != null) result.put(key.toString(), item.toString());
        });
        return Collections.unmodifiableMap(result);
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
                "caption", p.getCaption(), "capturedAt", text(p.getCapturedAt()),
                "sourceId", p.getPhotoId())).toList());
        root.put("xrays", xrays.findByMatchedVisit_VisitIdOrderByCapturedAtDesc(visit.getVisitId()).stream()
                .map(x -> mapOf("studyUid", x.getExternalStudyUid(), "imageUrl", x.getImageUrl(),
                        "toothNumbers", x.getToothNumbers(), "capturedAt", text(x.getCapturedAt()))).toList());
        root.put("laboOrders", laboOrders.findByVisit_VisitIdOrderBySentDateDesc(visit.getVisitId()).stream()
                .map(l -> mapOf("code", l.getLaboCode(), "material", l.getMaterialType(),
                        "toothPositions", l.getToothPositions(), "status", text(l.getStatus()),
                        "imageUrl", l.getImageUrl(), "notes", l.getNotes())).toList());
        root.put("visitHistory", visits.findByPatientProfile_ProfileIdOrderByCheckInAtDesc(patient.getProfileId())
                .stream().filter(previous -> !Objects.equals(previous.getVisitId(), visit.getVisitId())
                        && !previous.getCheckInAt().isAfter(visit.getCheckInAt()))
                .limit(30).map(previous -> mapOf("visitCode", previous.getVisitCode(),
                        "checkInAt", text(previous.getCheckInAt()),
                        "symptoms", previous.getInitialSymptoms(),
                        "doctorName", previous.getPrimaryDoctor() == null ? null : previous.getPrimaryDoctor().getFullName(),
                        "diagnoses", diagnoses.findByVisitIdWithDetails(previous.getVisitId()).stream()
                                .map(ExaminationDiagnosis::getDiagnosisText).toList(),
                        "services", performedServices.findByVisitIdWithDetails(previous.getVisitId()).stream()
                                .map(service -> service.getService().getServiceName()).toList())).toList());
        root.put("treatmentPlans", treatmentPlans.findByProfileIdWithDetails(patient.getProfileId()).stream()
                .map(plan -> mapOf("code", plan.getPlanCode(), "name", plan.getPlanName(),
                        "status", text(plan.getStatus()), "notes", plan.getNotes(),
                        "stages", plan.getStages().stream().map(stage -> mapOf(
                                "name", stage.getStageName(), "status", text(stage.getStatus()),
                                "expectedEndDate", text(stage.getExpectedEndDate()))).toList())).toList());
        return root;
    }

    private List<String> validateRecord(Visit visit, EmrRecordVersion version) {
        List<String> missing = validateSource(visit);
        if (version == null) return missing;
        Map<String, String> summary = version.getDocuments().stream()
                .filter(document -> "TREATMENT_SUMMARY".equals(document.getDocumentCode()))
                .findFirst().map(this::documentManualFields).orElse(Map.of());
        if (blank(summary.get("dischargeCondition")))
            missing.add("Thiếu tình trạng người bệnh khi kết thúc lượt khám (Tổng kết bệnh án ngoại trú).");
        if (blank(summary.get("followupInstructions")))
            missing.add("Thiếu hướng theo dõi hoặc kế hoạch điều trị tiếp theo (Tổng kết bệnh án ngoại trú).");
        return missing;
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    private List<String> warningsFor(Visit visit) {
        ClinicalExamination exam = examinations.findByVisit_VisitId(visit.getVisitId()).orElse(null);
        List<String> warnings = new ArrayList<>();
        if (exam == null) return warnings;
        if (blank(exam.getHistoryIllness())) warnings.add("Chưa ghi diễn biến bệnh sử; khai thác thêm ở lần khám tiếp theo.");
        if (blank(exam.getVitalSignsJson())) warnings.add("Chưa ghi dấu hiệu sinh tồn.");
        if (blank(exam.getExtraoralExam())) warnings.add("Chưa ghi khám ngoài miệng / khớp thái dương hàm.");
        if (blank(exam.getIntraoralExam())) warnings.add("Chưa ghi khám trong miệng.");
        if (blank(exam.getOdontogramChartJson())) warnings.add("Chưa có sơ đồ răng trong phiếu khám.");
        return warnings;
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
        if (exam != null && blank(exam.getSummaryAssessment()))
            missing.add("Thiếu kết luận khám lâm sàng.");
        if (visit.getPrimaryDoctor() != null && blank(visit.getPrimaryDoctor().getPracticeLicenseNo()))
            missing.add("Bác sĩ khám chưa có số giấy phép hành nghề.");
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

    private String prettyJson(Object value) {
        try { return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Không thể xuất bệnh án.", ex); }
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
                               String contentHash,
                               Map<String, Object> snapshot, boolean stale, List<String> missing, List<String> warnings,
                               List<DocumentRow> documents, Map<String, Map<String, String>> documentFields,
                               List<AttachmentRow> attachments,
                               List<SignatureRow> signatures,
                               List<VersionRow> versions, List<AuditRow> audits,
                               boolean canRefresh, boolean canValidate, boolean canLock,
                               boolean canDoctorSign, boolean canAmend) {}
    public record DocumentRow(String code, String title, String type, Integer sortOrder, String hash,
                              Map<String, String> fields) {}
    public record AttachmentRow(Long id, String caption, String name, String mimeType,
                                Long byteSize, String sha256, LocalDateTime capturedAt) {}
    public record AttachmentDownload(byte[] bytes, String mimeType, String name) {}
    public record SignatureRow(Long id, EmrSignerType type, String signerName, String provider,
                               EmrSignatureStatus status, LocalDateTime signedAt, String transactionId) {}
    public record VersionRow(Integer number, EmrVersionStatus status, LocalDateTime createdAt,
                             LocalDateTime lockedAt, LocalDateTime signedAt, String reason, String hash) {}
    public record AuditRow(String action, String details, String actor, LocalDateTime createdAt) {}
    public record ValidationResult(boolean ready, List<String> missing) {}
}
