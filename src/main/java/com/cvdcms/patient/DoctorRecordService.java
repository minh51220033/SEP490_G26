package com.cvdcms.patient;

import com.cvdcms.emr.EmrRecord;
import com.cvdcms.emr.EmrRecordRepository;
import com.cvdcms.emr.EmrRecordStatus;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.repository.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.time.LocalDate;
import java.time.LocalDateTime;

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

    public DoctorRecordService(UserRepository users, StaffBranchAssignmentRepository assignments,
            DoctorRecordRepository profiles, PatientVisitReadRepository visits,
            ClinicalPhotoRepository photos, TreatmentPlanRepository plans,
            ClinicalExaminationRepository examinations, ExaminationDiagnosisRepository diagnoses,
            PerformedServiceRepository performed, PrescriptionRepository prescriptions,
            BranchRepository branchRepository, PatientDocumentStorageService documentStorage,
            EmrRecordRepository emrRecords) {
        this.users = users; this.assignments = assignments; this.profiles = profiles;
        this.visits = visits; this.photos = photos; this.plans = plans;
        this.examinations = examinations; this.diagnoses = diagnoses;
        this.performed = performed; this.prescriptions = prescriptions;
        this.branchRepository = branchRepository; this.documentStorage = documentStorage;
        this.emrRecords = emrRecords;
    }

    private Set<Long> branches(HttpSession session) {
        Object principal = session == null ? null : session.getAttribute("loggedInUser");
        if (!(principal instanceof User current) || current.getUserId() == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        User doctor = users.findById(current.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (!Boolean.TRUE.equals(doctor.getIsActive()) || !"ACTIVE".equalsIgnoreCase(doctor.getStatus())
                || doctor.getRoles().stream().noneMatch(role -> Boolean.TRUE.equals(role.getIsActive())
                    && "BAC_SI".equalsIgnoreCase(role.getRoleName())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        Set<Long> branchIds = new HashSet<>(assignments.findActiveBranchIdsByUserId(
                doctor.getUserId(), AssignmentStatus.ACTIVE));
        if (doctor.getBranch() != null) branchIds.add(doctor.getBranch().getBranchId());
        if (branchIds.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Tài khoản bác sĩ chưa được phân chi nhánh.");
        return branchIds;
    }

    public Page<PatientView> search(HttpSession session, String query, LocalDate dob,
                                    String status, Long branchId, int page) {
        Set<Long> allowed = branches(session);
        if (branchId != null && !allowed.contains(branchId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        String term = query == null || query.isBlank() ? null : query.trim();
        String state = status == null || status.isBlank() ? null : status.trim();
        return profiles.search(allowed, branchId, dob, state, term,
                PageRequest.of(Math.max(0, page), 15, Sort.by(Sort.Direction.DESC, "profileId")))
                .map(PatientView::from);
    }

    public PatientView get(HttpSession session, Long id) {
        PatientProfile profile = profiles.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!branches(session).contains(profile.getPreferredBranch().getBranchId()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return PatientView.from(profile);
    }

    public List<Branch> allowedBranches(HttpSession session) {
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
        Set<Long> allowed = branches(session);
        return emrRecords.findAllByPatientProfileId(patientId).stream()
                .filter(record -> allowed.contains(record.getBranch().getBranchId()))
                .map(this::toEmrView)
                .toList();
    }

    private EmrView toEmrView(EmrRecord record) {
        Visit visit = record.getVisit();
        return new EmrView(record.getRecordId(), record.getRecordCode(),
                visit.getVisitId(), visit.getVisitCode(), visit.getCheckInAt(),
                record.getStatus(), record.getCurrentVersionNumber(), record.getUpdatedAt());
    }

    public List<PhotoView> photos(HttpSession session, Long id) {
        get(session, id);
        return photos.findByPatientProfile_ProfileIdOrderByCapturedAtDesc(id).stream()
                .map(photo -> new PhotoView(photo.getImageUrl(), photo.getCaption()))
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

    public record VisitDetails(VisitView visit, String assessment, List<String> diagnoses,
                               List<String> services, List<String> prescriptions, EmrView emr) {}
    public record EmrView(Long id, String code, Long visitId, String visitCode,
                          LocalDateTime checkInAt, EmrRecordStatus status,
                          Integer versionNumber, LocalDateTime updatedAt) {}
    public record PhotoView(String url, String caption) {}
    public record PlanView(String name, String status) {}
}
