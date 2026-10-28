package com.cvdcms.reception;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.BranchRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ReceptionService {
    private static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final ReceptionAccess access;
    private final ReceptionProfileRepository profiles;
    private final ReceptionCheckInAppointmentRepository appointments;
    private final ReceptionVisitRepository visits;
    private final ReceptionCheckInKeyRepository keys;
    private final ReceptionAuditRepository audits;
    private final BranchRepository branches;
    private final ObjectMapper mapper;

    public ReceptionService(ReceptionAccess access, ReceptionProfileRepository profiles, ReceptionCheckInAppointmentRepository appointments,
                            ReceptionVisitRepository visits, ReceptionCheckInKeyRepository keys, ReceptionAuditRepository audits,
                            BranchRepository branches, ObjectMapper mapper) {
        this.access = access; this.profiles = profiles; this.appointments = appointments; this.visits = visits;
        this.keys = keys; this.audits = audits; this.branches = branches; this.mapper = mapper;
    }

    public VisitResult checkInAppointment(HttpSession session, Long appointmentId, String symptoms, String requestKey) {
        requireKey(requestKey);
        Appointment reference = appointments.findById(appointmentId).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Không tìm thấy lịch hẹn."));
        Branch branch = allowedBranch(session, reference.getBranch().getBranchId());
        PatientProfile patient = lockedActiveProfile(reference.getPatientProfile().getProfileId());
        Appointment appointment = appointments.lockById(appointmentId).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Không tìm thấy lịch hẹn."));
        if (!appointment.getPatientProfile().getProfileId().equals(patient.getProfileId()) || !appointment.getBranch().getBranchId().equals(branch.getBranchId()))
            throw error(HttpStatus.CONFLICT, "Lịch hẹn đã thay đổi. Vui lòng tải lại trang.");
        Visit existing = visits.findByAppointmentId(appointmentId).orElse(null);
        if (existing != null) return result(existing);
        if (keys.findByRequestKey(requestKey).isPresent()) throw error(HttpStatus.CONFLICT, "Mã yêu cầu check-in đã dùng cho lượt khám khác.");
        if (!appointment.getAppointmentDate().equals(today())) throw error(HttpStatus.CONFLICT, "Chỉ check-in lịch hẹn trong ngày tại chi nhánh đã đặt. Hãy đổi lịch trước.");
        ReceptionTransitionPolicy.appointment(appointment.getStatus(), AppointmentStatus.CHECKED_IN);
        rejectActiveVisit(patient);
        String before = appointment.getStatus().name();
        Visit visit = newVisit(patient, branch, VisitType.APPOINTMENT, appointmentId, symptoms(symptoms, appointment.getInitialSymptoms()));
        visits.saveAndFlush(visit); storeKey(requestKey, visit);
        appointment.setStatus(AppointmentStatus.CHECKED_IN); appointments.save(appointment);
        audit(session, branch, "CHECK_IN_APPOINTMENT", visit, Map.of("appointmentId", appointmentId, "profileId", patient.getProfileId(), "appointmentStatusBefore", before, "visitStatus", "WAITING"));
        return result(visit);
    }

    public VisitResult checkInWalkIn(HttpSession session, Long branchId, Long profileId, String symptoms, boolean emergency, String requestKey) {
        requireKey(requestKey);
        Branch branch = allowedBranch(session, branchId);
        PatientProfile patient = lockedActiveProfile(profileId);
        var prior = keys.findByRequestKey(requestKey);
        if (prior.isPresent()) {
            Visit existing = prior.get().getVisit();
            if (!existing.getPatientProfile().getProfileId().equals(profileId) || !existing.getBranch().getBranchId().equals(branchId))
                throw error(HttpStatus.CONFLICT, "Mã yêu cầu check-in đã dùng cho bệnh nhân khác.");
            return result(existing);
        }
        rejectActiveVisit(patient);
        String initialSymptoms = symptoms(symptoms, null);
        if (emergency && initialSymptoms.length() < 10) throw error(HttpStatus.BAD_REQUEST, "Ca cấp cứu cần ghi rõ triệu chứng hoặc lý do ưu tiên (ít nhất 10 ký tự).");
        Visit visit = newVisit(patient, branch, emergency ? VisitType.EMERGENCY : VisitType.WALK_IN, null, initialSymptoms);
        visits.saveAndFlush(visit); storeKey(requestKey, visit);
        audit(session, branch, emergency ? "CHECK_IN_EMERGENCY" : "CHECK_IN_WALK_IN", visit,
                Map.of("profileId", profileId, "visitStatus", "WAITING", "visitType", visit.getVisitType().name(), "initialSymptoms", initialSymptoms));
        return result(visit);
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(HttpSession session, Long requestedBranchId, String query) {
        var scope = access.require(session);
        List<Branch> options = branches.findByIsActiveTrueOrderByBranchNameAsc().stream().filter(b -> scope.permits(b.getBranchId())).toList();
        if (options.isEmpty()) throw error(HttpStatus.FORBIDDEN, "Không có chi nhánh tiếp đón đang hoạt động.");
        Long branchId = requestedBranchId == null ? options.get(0).getBranchId() : requestedBranchId;
        Branch branch = allowedBranch(session, branchId); LocalDate day = today();
        List<AppointmentRow> appointmentRows = appointments.forDay(branchId, day, List.of(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED, AppointmentStatus.CHECKED_IN)).stream().map(this::appointmentRow).toList();
        List<VisitRow> visitRows = visits.forDay(branchId, day.atStartOfDay(), day.plusDays(1).atStartOfDay()).stream().map(this::visitRow).toList();
        String term = query == null ? "" : query.trim();
        List<PatientRow> matches = term.length() < 2 ? List.of() : profiles.search(term, PageRequest.of(0, 20)).stream().map(this::patientRow).toList();
        return new Dashboard(branchId, branch.getBranchName(), day, options, appointmentRows, visitRows, matches, term, UUID.randomUUID().toString());
    }

    private PatientProfile lockedActiveProfile(Long id) {
        PatientProfile patient = profiles.lockById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Không tìm thấy hồ sơ bệnh nhân. Tạo hồ sơ ở FT03 trước."));
        if (!"ACTIVE".equalsIgnoreCase(patient.getStatus())) throw error(HttpStatus.CONFLICT, "Hồ sơ bệnh nhân không hoạt động.");
        return patient;
    }
    private Branch allowedBranch(HttpSession session, Long branchId) {
        var scope = access.require(session);
        Branch branch = branches.findById(branchId).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Không tìm thấy chi nhánh."));
        if (!Boolean.TRUE.equals(branch.getIsActive()) || !scope.permits(branchId)) throw error(HttpStatus.FORBIDDEN, "Không được check-in tại chi nhánh này.");
        return branch;
    }
    private void rejectActiveVisit(PatientProfile patient) {
        var active = visits.activeForProfile(patient.getProfileId(), ReceptionTransitionPolicy.activeVisits());
        if (!active.isEmpty()) throw error(HttpStatus.CONFLICT, "Bệnh nhân đã có lượt khám đang hoạt động: " + active.get(0).getVisitCode());
    }
    private Visit newVisit(PatientProfile patient, Branch branch, VisitType type, Long appointmentId, String initialSymptoms) {
        Visit visit = new Visit();
        visit.setVisitCode("V" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        visit.setPatientProfile(patient); visit.setBranch(branch); visit.setAppointmentId(appointmentId); visit.setVisitType(type);
        visit.setCheckInAt(LocalDateTime.now(CLINIC_ZONE)); visit.setInitialSymptoms(initialSymptoms); visit.setStatus(VisitStatus.WAITING);
        return visit;
    }
    private void storeKey(String requestKey, Visit visit) { ReceptionCheckInKey key = new ReceptionCheckInKey(); key.setRequestKey(requestKey); key.setVisit(visit); keys.save(key); }
    private void audit(HttpSession session, Branch branch, String action, Visit visit, Map<String, ?> values) {
        SystemAuditLog log = new SystemAuditLog(); log.setUser(access.require(session).user()); log.setBranch(branch); log.setAction(action); log.setEntityTable("visits"); log.setEntityId(visit.getVisitId());
        try { log.setNewValuesJson(mapper.writeValueAsString(values)); } catch (JsonProcessingException e) { throw new IllegalStateException("Không thể ghi nhật ký check-in.", e); }
        audits.save(log);
    }
    private void requireKey(String value) { try { UUID.fromString(value); } catch (RuntimeException e) { throw error(HttpStatus.BAD_REQUEST, "Mã yêu cầu check-in không hợp lệ."); } }
    private String symptoms(String supplied, String fallback) {
        String value = supplied == null || supplied.isBlank() ? fallback : supplied.trim();
        if (value == null || value.isBlank()) throw error(HttpStatus.BAD_REQUEST, "Vui lòng ghi nhận triệu chứng hoặc lý do đến khám.");
        if (value.length() > 500) throw error(HttpStatus.BAD_REQUEST, "Triệu chứng tối đa 500 ký tự."); return value.trim();
    }
    private LocalDate today() { return LocalDate.now(CLINIC_ZONE); }
    private ResponseStatusException error(HttpStatus status, String reason) { return new ResponseStatusException(status, reason); }
    private VisitResult result(Visit v) { return new VisitResult(v.getVisitId(), v.getVisitCode(), v.getPatientProfile().getFullName(), v.getBranch().getBranchName(), v.getVisitType(), v.getStatus(), v.getCheckInAt()); }
    private PatientRow patientRow(PatientProfile p) { return new PatientRow(p.getProfileId(), p.getPatientCode(), p.getFullName(), p.getDob(), p.getPhoneNumber(), Boolean.TRUE.equals(p.getHasAllergies()), p.getAllergyDetails(), Boolean.TRUE.equals(p.getHasUnderlyingDisease()), p.getUnderlyingDiseaseDetails()); }
    private AppointmentRow appointmentRow(Appointment a) { return new AppointmentRow(a.getAppointmentId(), a.getAppointmentCode(), patientRow(a.getPatientProfile()), a.getExpectedTime(), a.getInitialSymptoms(), a.getDoctor() == null ? "Chưa chỉ định" : a.getDoctor().getFullName(), a.getStatus(), UUID.randomUUID().toString()); }
    private VisitRow visitRow(Visit v) { return new VisitRow(v.getVisitId(), v.getVisitCode(), patientRow(v.getPatientProfile()), v.getVisitType(), v.getStatus(), v.getCheckInAt(), v.getInitialSymptoms()); }
    public record VisitResult(Long id, String code, String patientName, String branchName, VisitType type, VisitStatus status, LocalDateTime checkedInAt) { }
    public record PatientRow(Long id, String code, String name, LocalDate dob, String phone, boolean allergies, String allergyDetails, boolean underlyingDisease, String underlyingDiseaseDetails) { }
    public record AppointmentRow(Long id, String code, PatientRow patient, LocalTime expectedTime, String symptoms, String doctorName, AppointmentStatus status, String requestKey) { }
    public record VisitRow(Long id, String code, PatientRow patient, VisitType type, VisitStatus status, LocalDateTime checkedInAt, String symptoms) { }
    public record Dashboard(Long branchId, String branchName, LocalDate day, List<Branch> branches, List<AppointmentRow> appointments, List<VisitRow> visits, List<PatientRow> matches, String query, String walkInKey) { }
}
