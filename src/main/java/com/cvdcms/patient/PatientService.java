package com.cvdcms.patient;

import com.cvdcms.entity.*;
import com.cvdcms.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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

    public PatientService(PatientAccess access, ReceptionPatientRepository profiles,
                          PatientSearchRepository search, PatientVisitReadRepository visits,
                          BranchRepository branches, PatientAuditRepository auditRepository,
                          ClinicalExaminationRepository examinations,
                          ExaminationDiagnosisRepository diagnoses,
                          PerformedServiceRepository performedServices,
                          PrescriptionRepository prescriptions, AppointmentRepository appointments,
                          PatientDocumentStorageService documentStorage,
                          ObjectMapper mapper) {
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
        int safeSize = Math.min(50, Math.max(1, size));
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
                                                String phone, Long excludeId) {
        var scope = access.require(session);
        return findCandidates(scope, name, dob, phone, excludeId);
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
        if (!form.confirmDuplicate() && !findCandidates(scope, form.fullName(), form.dob(),
                form.phoneNumber(), excludeId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Có hồ sơ tương tự. Vui lòng đối chiếu và xác nhận trước khi lưu.");
        }
    }

    private List<DuplicateCandidate> findCandidates(PatientAccess.Scope scope, String name, LocalDate dob,
                                                     String phone, Long excludeId) {
        String cleanName = blankToNull(name);
        String cleanPhone = blankToNull(phone);
        if (cleanPhone == null && (cleanName == null || dob == null)) return List.of();
        List<ReceptionPatientRecord> found = search.candidates(scope.branchId(),
                cleanPhone == null ? "" : cleanPhone,
                dob, cleanName == null ? "" : cleanName.toLowerCase(Locale.ROOT),
                PageRequest.of(0, 15));
        return found.stream().filter(p -> !p.getProfileId().equals(excludeId)).map(p -> {
            List<String> matches = new ArrayList<>();
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
