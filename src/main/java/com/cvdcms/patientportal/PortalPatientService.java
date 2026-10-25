package com.cvdcms.patientportal;

import com.cvdcms.entity.*;
import com.cvdcms.patient.PatientSearchRepository;
import com.cvdcms.patient.PatientVisitReadRepository;
import com.cvdcms.patient.PatientAuditRepository;
import com.cvdcms.patient.VisitView;
import com.cvdcms.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
@Transactional
public class PortalPatientService {
    private final PortalAccess access;
    private final PatientProfileRepository profiles;
    private final AccountPatientProfileRepository links;
    private final BranchRepository branches;
    private final PatientSearchRepository duplicates;
    private final PatientVisitReadRepository visits;
    private final ClinicalExaminationRepository examinations;
    private final ExaminationDiagnosisRepository diagnoses;
    private final PerformedServiceRepository performed;
    private final PrescriptionRepository prescriptions;
    private final ClinicalPhotoRepository photos;
    private final TreatmentPlanRepository plans;
    private final PatientAuditRepository audit;
    private final ObjectMapper mapper;

    @Value("${app.upload-dir:uploads}")
    private String uploadDir;

    public PortalPatientService(PortalAccess access, PatientProfileRepository profiles,
            AccountPatientProfileRepository links, BranchRepository branches,
            PatientSearchRepository duplicates, PatientVisitReadRepository visits,
            ClinicalExaminationRepository examinations, ExaminationDiagnosisRepository diagnoses,
            PerformedServiceRepository performed, PrescriptionRepository prescriptions,
            PatientAuditRepository audit, ClinicalPhotoRepository photos,
            TreatmentPlanRepository plans, ObjectMapper mapper) {
        this.access = access; this.profiles = profiles; this.links = links; this.branches = branches;
        this.duplicates = duplicates; this.visits = visits; this.examinations = examinations;
        this.diagnoses = diagnoses; this.performed = performed; this.prescriptions = prescriptions;
        this.audit = audit; this.photos = photos; this.plans = plans; this.mapper = mapper;
    }

    public List<PortalProfileView> list(HttpSession session) {
        User user = access.requireUser(session);
        Map<Long, PortalProfileView> owned = new LinkedHashMap<>();
        links.findByAccount_UserIdOrderByProfile_ProfileIdDesc(user.getUserId())
                .forEach(link -> owned.put(link.getProfile().getProfileId(),
                        PortalProfileView.from(link.getProfile(),
                                link.getAccessType() == AccountPatientProfile.AccessType.SELF)));
        // Hồ sơ tạo qua luồng /profile cũ được gắn vào cùng bảng quyền khi mở portal.
        profiles.findByUser_UserIdOrderByIsDefaultDescProfileIdDesc(user.getUserId())
                .forEach(profile -> {
                    if (links.findByProfile_ProfileId(profile.getProfileId()).isEmpty()) {
                        AccountPatientProfile link = new AccountPatientProfile();
                        link.setAccount(user);
                        link.setProfile(profile);
                        link.setAccessType(Boolean.TRUE.equals(profile.getIsDefault())
                                ? AccountPatientProfile.AccessType.SELF
                                : AccountPatientProfile.AccessType.DEPENDENT);
                        links.save(link);
                        owned.putIfAbsent(profile.getProfileId(),
                                PortalProfileView.from(profile, Boolean.TRUE.equals(profile.getIsDefault())));
                    }
                });
        return List.copyOf(owned.values());
    }

    @Transactional(readOnly = true)
    public PortalProfileView get(HttpSession session, Long id) {
        PatientProfile profile = access.requireProfile(access.requireUser(session), id);
        return view(profile);
    }

    @Transactional(readOnly = true)
    public List<Branch> branches(HttpSession session) {
        access.requireUser(session);
        return branches.findByIsActiveTrueOrderByBranchNameAsc();
    }

    @Transactional(readOnly = true)
    public boolean hasDuplicateCandidates(
            HttpSession session, String name, LocalDate dob, String phone, Long excludeId) {
        access.requireUser(session);
        String normalizedName = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        String normalizedPhone = phone == null ? "" : phone.trim();
        if (normalizedPhone.isEmpty() && (normalizedName.isEmpty() || dob == null)) return false;
        return duplicates.candidates(null, normalizedPhone, dob, normalizedName, PageRequest.of(0, 15))
                .stream().anyMatch(p -> !p.getProfileId().equals(excludeId));
    }

    public PortalProfileView create(HttpSession session, PortalProfileForm form) {
        User user = access.requireUser(session);
        Branch branch = activeBranch(form.branchId());
        requireDuplicateConfirmation(session, form, null);
        PatientProfile profile = new PatientProfile();
        profile.setUser(user);
        profile.setPatientCode("BN" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        profile.setIsDefault(false);
        profile.setStatus("ACTIVE");
        profile.setMedicalHistoryCompleted(false);
        apply(profile, form, branch);
        profiles.saveAndFlush(profile);
        AccountPatientProfile link = new AccountPatientProfile();
        link.setAccount(user);
        link.setProfile(profile);
        link.setAccessType(AccountPatientProfile.AccessType.DEPENDENT);
        links.save(link);
        audit(user, profile, "CREATE_DEPENDENT", null, snapshot(profile));
        return view(profile);
    }

    public PortalProfileView update(HttpSession session, Long id, PortalProfileForm form) {
        User user = access.requireUser(session);
        PatientProfile profile = access.requireProfile(user, id);
        Branch branch = activeBranch(form.branchId());
        requireDuplicateConfirmation(session, form, id);
        Map<String, Object> before = snapshot(profile);
        apply(profile, form, branch);
        profiles.save(profile);
        audit(user, profile, "UPDATE_DEPENDENT", before, snapshot(profile));
        return view(profile);
    }

    @Transactional(readOnly = true)
    public List<VisitView> visits(HttpSession session, Long id) {
        access.requireProfile(access.requireUser(session), id);
        return visits.forPatient(id).stream().map(VisitView::from).toList();
    }

    @Transactional(readOnly = true)
    public List<PhotoView> photos(HttpSession session, Long id) {
        access.requireProfile(access.requireUser(session), id);
        return photos.findByPatientProfile_ProfileIdOrderByCapturedAtDesc(id).stream()
                .map(photo -> new PhotoView(photo.getImageUrl(), photo.getCaption(),
                        photo.getVisit() == null ? null : photo.getVisit().getVisitCode()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PlanView> plans(HttpSession session, Long id) {
        access.requireProfile(access.requireUser(session), id);
        return plans.findByProfileIdWithDetails(id).stream()
                .filter(plan -> plan.getStatus() != com.cvdcms.entity.enums.TreatmentPlanStatus.DRAFT)
                .map(plan -> new PlanView(plan.getPlanCode(), plan.getPlanName(), plan.getStatus().name()))
                .toList();
    }

    @Transactional(readOnly = true)
    public VisitDetails visit(HttpSession session, Long id, Long visitId) {
        access.requireProfile(access.requireUser(session), id);
        Visit visit = visits.oneForPatient(id, visitId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new VisitDetails(VisitView.from(visit),
                examinations.findByVisit_VisitId(visitId).map(ClinicalExamination::getSummaryAssessment).orElse(null),
                diagnoses.findByVisit_VisitId(visitId).stream().map(ExaminationDiagnosis::getDiagnosisText).toList(),
                performed.findByVisitIdWithDetails(visitId).stream().map(item -> item.getService().getServiceName()).toList(),
                prescriptions.findByVisit_VisitIdOrderByCreatedAtDesc(visitId).stream().map(Prescription::getPrescriptionCode).toList());
    }

    private Branch activeBranch(Long id) {
        return branches.findById(id).filter(b -> Boolean.TRUE.equals(b.getIsActive()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chi nhánh không hợp lệ."));
    }

    private void requireDuplicateConfirmation(HttpSession session, PortalProfileForm form, Long excludeId) {
        if (!form.confirmDuplicate() && hasDuplicateCandidates(session, form.fullName(), form.dob(),
                form.phoneNumber(), excludeId))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Có hồ sơ tương tự. Vui lòng đối chiếu và xác nhận trước khi lưu.");
    }

    private void apply(PatientProfile p, PortalProfileForm f, Branch branch) {
        p.setFullName(f.fullName().trim()); p.setDob(f.dob()); p.setGender(f.gender());
        p.setPhoneNumber(f.phoneNumber().trim()); p.setAddress(f.address().trim());
        p.setPreferredBranch(branch);
        p.setGuardianName(blank(f.guardianName())); p.setGuardianPhone(blank(f.guardianPhone()));
        p.setGuardianRelationship(blank(f.guardianRelationship()));
        p.setIdCardNumber(blank(f.idCardNumber()));
        p.setHealthInsuranceNumber(blank(f.healthInsuranceNumber()));
    }

    private PortalProfileView view(PatientProfile profile) {
        return PortalProfileView.from(profile, isSelf(profile));
    }

    private boolean isSelf(PatientProfile profile) {
        return links.findByProfile_ProfileId(profile.getProfileId())
                .map(link -> link.getAccessType() == AccountPatientProfile.AccessType.SELF)
                .orElse(Boolean.TRUE.equals(profile.getIsDefault()));
    }

    public PortalProfileView setDefault(HttpSession session, Long id) {
        User user = access.requireUser(session);
        PatientProfile selected = access.requireProfile(user, id);
        profiles.findByUser_UserIdOrderByIsDefaultDescProfileIdDesc(user.getUserId())
                .forEach(profile -> {
                    if (!profile.getProfileId().equals(id) && Boolean.TRUE.equals(profile.getIsDefault())) {
                        profile.setIsDefault(false);
                        profiles.save(profile);
                    }
                });
        selected.setIsDefault(true);
        profiles.save(selected);
        audit(user, selected, "SET_DEFAULT_PROFILE", null, snapshot(selected));
        return view(selected);
    }

    public PortalProfileView uploadAvatar(HttpSession session, Long id, MultipartFile file) {
        User user = access.requireUser(session);
        PatientProfile profile = access.requireProfile(user, id);
        if (file == null || file.isEmpty() || file.getSize() > 5 * 1024 * 1024)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ảnh đại diện phải có dung lượng tối đa 5 MB.");
        String extension = switch (String.valueOf(file.getContentType())) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Chỉ chấp nhận ảnh JPG, PNG hoặc WebP.");
        };
        try {
            Path directory = Paths.get(uploadDir, "avatars").toAbsolutePath().normalize();
            Files.createDirectories(directory);
            String name = UUID.randomUUID() + extension;
            file.transferTo(directory.resolve(name));
            String previous = profile.getAvatarUrl();
            profile.setAvatarUrl("/uploads/avatars/" + name);
            profiles.save(profile);
            audit(user, profile, "UPDATE_PROFILE_AVATAR",
                    Map.of("avatarUrl", previous == null ? "" : previous),
                    Map.of("avatarUrl", profile.getAvatarUrl()));
            return view(profile);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Không thể lưu ảnh đại diện.", ex);
        }
    }

    private String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private Map<String, Object> snapshot(PatientProfile p) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("profileId", p.getProfileId()); values.put("fullName", p.getFullName());
        values.put("dob", p.getDob() == null ? null : p.getDob().toString());
        values.put("phoneNumber", p.getPhoneNumber()); values.put("branchId", p.getPreferredBranch().getBranchId());
        values.put("address", p.getAddress()); values.put("guardianName", p.getGuardianName());
        return values;
    }

    private void audit(User user, PatientProfile p, String action,
                       Map<String, Object> before, Map<String, Object> after) {
        SystemAuditLog log = new SystemAuditLog();
        log.setUser(user); log.setBranch(p.getPreferredBranch()); log.setAction(action);
        log.setEntityTable("patient_profiles"); log.setEntityId(p.getProfileId());
        try {
            if (before != null) log.setOldValuesJson(mapper.writeValueAsString(before));
            log.setNewValuesJson(mapper.writeValueAsString(after));
        } catch (JsonProcessingException ex) { throw new IllegalStateException("Không thể ghi audit.", ex); }
        audit.save(log);
    }

    public record VisitDetails(VisitView visit, String assessment, List<String> diagnoses,
                               List<String> services, List<String> prescriptions) {}
    public record PhotoView(String imageUrl, String caption, String visitCode) {}
    public record PlanView(String code, String name, String status) {}
}
