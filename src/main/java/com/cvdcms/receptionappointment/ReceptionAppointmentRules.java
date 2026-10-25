package com.cvdcms.receptionappointment;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.patientportal.PortalAppointmentRepository;
import com.cvdcms.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;

@Component
public class ReceptionAppointmentRules {
    static final ZoneId ZONE = ZoneId.of("Asia/Bangkok");
    private static final List<AppointmentStatus> OCCUPYING = List.of(AppointmentStatus.PENDING,
            AppointmentStatus.CONFIRMED, AppointmentStatus.CHECKED_IN, AppointmentStatus.COMPLETED);
    private final PortalAppointmentRepository appointments;
    private final AppointmentSessionWindowRepository windows;
    private final AppointmentSessionPolicyRepository policies;
    private final StaffBranchAssignmentRepository assignments;
    private final StaffScheduleRepository schedules;
    private final LeaveRequestRepository leaves;
    @Value("${appointments.branch-capacity:24}") private int defaultBranchCapacity;
    @Value("${appointments.doctor-capacity:10}") private int defaultDoctorCapacity;

    public ReceptionAppointmentRules(PortalAppointmentRepository appointments,
            AppointmentSessionWindowRepository windows, AppointmentSessionPolicyRepository policies,
            StaffBranchAssignmentRepository assignments, StaffScheduleRepository schedules,
            LeaveRequestRepository leaves) {
        this.appointments = appointments; this.windows = windows; this.policies = policies;
        this.assignments = assignments; this.schedules = schedules; this.leaves = leaves;
    }

    public record DoctorOption(Long id, String name, String specialty, int remaining) {}
    public record Availability(boolean available, String reason, int remainingBranch, List<DoctorOption> doctors) {}
    private record SessionRange(LocalTime start, LocalTime end, boolean active) {}

    public Availability availability(Branch branch, LocalDate date, PreferredSession session, Long excludeId) {
        String invalid = invalidSession(branch, date, session);
        if (invalid != null) return new Availability(false, invalid, 0, List.of());
        List<Appointment> booked = booked(branch.getBranchId(), date, session, excludeId);
        List<StaffProfile> doctors = eligibleDoctors(branch, date, session);
        AppointmentSessionPolicy policy = policy(branch.getBranchId(), date, session);
        int capacity = Math.min(branchCapacity(policy), doctors.size() * doctorCapacity(policy));
        int remaining = Math.max(0, capacity - booked.size());
        List<DoctorOption> options = doctors.stream().map(d -> new DoctorOption(d.getStaffId(), d.getFullName(),
                d.getSpecialty(), Math.max(0, doctorCapacity(policy) - (int) booked.stream()
                        .filter(a -> a.getDoctor() != null && d.getStaffId().equals(a.getDoctor().getStaffId()))
                        .count()))).filter(d -> d.remaining() > 0).toList();
        String reason = doctors.isEmpty() ? "Chưa có bác sĩ trực buổi này."
                : remaining == 0 ? "Buổi khám đã đầy." : null;
        return new Availability(remaining > 0, reason, remaining, options);
    }

    public void ensureBookable(Branch branch, PatientProfile profile, LocalDate date,
            PreferredSession session, Long doctorId, Long excludeId) {
        String invalid = invalidSession(branch, date, session);
        if (invalid != null) throw conflict(invalid);
        boolean duplicate = appointments.findActiveForPatientDay(profile.getProfileId(), branch.getBranchId(), date, OCCUPYING)
                .stream().anyMatch(a -> !a.getAppointmentId().equals(excludeId));
        if (duplicate) throw conflict("Hồ sơ đã có lịch còn hiệu lực tại chi nhánh trong ngày này.");
        List<StaffProfile> doctors = eligibleDoctors(branch, date, session);
        if (doctors.isEmpty()) throw conflict("Chưa có bác sĩ trực buổi này.");
        List<Appointment> booked = booked(branch.getBranchId(), date, session, excludeId);
        AppointmentSessionPolicy policy = policy(branch.getBranchId(), date, session);
        if (booked.size() >= Math.min(branchCapacity(policy), doctors.size() * doctorCapacity(policy)))
            throw conflict("Buổi khám đã đầy.");
        if (doctorId != null) {
            if (doctors.stream().noneMatch(d -> doctorId.equals(d.getStaffId())))
                throw conflict("Bác sĩ không khả dụng trong ngày và buổi đã chọn.");
            long taken = booked.stream().filter(a -> a.getDoctor() != null
                    && doctorId.equals(a.getDoctor().getStaffId())).count();
            if (taken >= doctorCapacity(policy)) throw conflict("Bác sĩ đã hết chỗ trong buổi.");
        }
    }

    public boolean doctorAvailable(Branch branch, LocalDate date, PreferredSession session, Long doctorId) {
        return doctorId == null || invalidSession(branch, date, session) == null
                && eligibleDoctors(branch, date, session).stream().anyMatch(d -> doctorId.equals(d.getStaffId()));
    }

    public List<DoctorOption> doctorsForDate(Branch branch, LocalDate date) {
        Map<Long, StaffProfile> doctors = new LinkedHashMap<>();
        for (PreferredSession session : List.of(PreferredSession.MORNING, PreferredSession.AFTERNOON, PreferredSession.EVENING)) {
            if (invalidSession(branch, date, session) == null)
                eligibleDoctors(branch, date, session).forEach(d -> doctors.put(d.getStaffId(), d));
        }
        return doctors.values().stream().sorted(Comparator.comparing(StaffProfile::getFullName))
                .map(d -> new DoctorOption(d.getStaffId(), d.getFullName(), d.getSpecialty(), 0)).toList();
    }

    public boolean sessionEnded(Branch branch, LocalDate date, PreferredSession session, LocalDateTime now) {
        if (date == null || session == null || session == PreferredSession.ANY) return false;
        SessionRange range = range(branch, session);
        return !now.isBefore(LocalDateTime.of(date, range.end()));
    }

    private String invalidSession(Branch branch, LocalDate date, PreferredSession session) {
        if (branch == null || !Boolean.TRUE.equals(branch.getIsActive())) return "Chi nhánh không hoạt động.";
        if (date == null || session == null || session == PreferredSession.ANY)
            return "Vui lòng chọn ngày và buổi khám cụ thể.";
        LocalDate today = LocalDate.now(ZONE);
        if (date.isBefore(today) || date.isAfter(today.plusDays(90)))
            return "Ngày khám phải nằm trong 90 ngày tới.";
        String day = date.getDayOfWeek() == DayOfWeek.SUNDAY ? "CN" : String.valueOf(date.getDayOfWeek().getValue() + 1);
        if (branch.getWorkingDays() == null || Arrays.stream(branch.getWorkingDays().split(","))
                .map(String::trim).noneMatch(day::equalsIgnoreCase))
            return "Chi nhánh không làm việc trong ngày này.";
        SessionRange range = range(branch, session);
        if (!range.active() || !range.start().isBefore(range.end())) return "Chi nhánh không nhận lịch trong buổi này.";
        if (branch.getOpeningTime().isAfter(range.start()) || branch.getClosingTime().isBefore(range.end()))
            return "Chi nhánh không mở cửa trong buổi được chọn.";
        if (date.equals(today) && !LocalTime.now(ZONE).isBefore(range.end()))
            return "Buổi khám đã kết thúc.";
        return null;
    }

    private SessionRange range(Branch branch, PreferredSession session) {
        return windows.findByBranch_BranchIdAndPreferredSession(branch.getBranchId(), session)
                .map(w -> new SessionRange(w.getStartTime(), w.getEndTime(), Boolean.TRUE.equals(w.getIsActive())))
                .orElseGet(() -> switch (session) {
                    case MORNING -> new SessionRange(LocalTime.of(8, 0), LocalTime.NOON, true);
                    case AFTERNOON -> new SessionRange(LocalTime.of(13, 30), LocalTime.of(17, 30), true);
                    case EVENING -> new SessionRange(LocalTime.of(18, 0), LocalTime.of(20, 0), true);
                    default -> new SessionRange(LocalTime.MAX, LocalTime.MIN, false);
                });
    }

    private List<Appointment> booked(Long branchId, LocalDate date, PreferredSession session, Long excludeId) {
        return appointments.findActiveForSession(branchId, date, session, OCCUPYING).stream()
                .filter(a -> !a.getAppointmentId().equals(excludeId)).toList();
    }

    private List<StaffProfile> eligibleDoctors(Branch branch, LocalDate date, PreferredSession session) {
        SessionRange range = range(branch, session);
        Set<Long> assigned = new HashSet<>();
        for (StaffBranchAssignment a : assignments.findByBranchAndStatus(branch.getBranchId(), AssignmentStatus.ACTIVE)) {
            if (a.getStartDate().isAfter(date) || a.getEndDate() != null && a.getEndDate().isBefore(date)) continue;
            String role = a.getRole().getRoleName();
            if (!Boolean.TRUE.equals(a.getRole().getIsActive()) || role == null
                    || !("BAC_SI".equalsIgnoreCase(role) || "ROLE_BAC_SI".equalsIgnoreCase(role))) continue;
            if (a.getStaff().getStatus() == StaffStatus.ACTIVE) assigned.add(a.getStaff().getStaffId());
        }
        List<LeaveRequest> approved = leaves.findApprovedOn(branch.getBranchId(), date, LeaveStatus.APPROVED);
        Map<Long, StaffProfile> result = new LinkedHashMap<>();
        for (StaffSchedule schedule : schedules.findForBooking(branch.getBranchId(), date, ScheduleStatus.SCHEDULED)) {
            StaffProfile doctor = schedule.getStaff();
            WorkShift shift = schedule.getShift();
            if (!assigned.contains(doctor.getStaffId()) || !Boolean.TRUE.equals(shift.getIsActive())
                    || shift.getStartTime().isAfter(range.start()) || shift.getEndTime().isBefore(range.end())) continue;
            boolean absent = approved.stream().anyMatch(l -> l.getStaff().getStaffId().equals(doctor.getStaffId())
                    && (l.getShift() == null || l.getShift().getShiftId().equals(shift.getShiftId())));
            if (!absent) result.put(doctor.getStaffId(), doctor);
        }
        return result.values().stream().sorted(Comparator.comparing(StaffProfile::getFullName)).toList();
    }

    private AppointmentSessionPolicy policy(Long branchId, LocalDate date, PreferredSession session) {
        return policies.findByBranch_BranchIdAndAppointmentDateAndPreferredSession(branchId, date, session).orElse(null);
    }
    private int branchCapacity(AppointmentSessionPolicy p) {
        return p == null ? defaultBranchCapacity : p.getBranchCapacity();
    }
    private int doctorCapacity(AppointmentSessionPolicy p) {
        return p == null ? defaultDoctorCapacity : p.getDoctorCapacity();
    }
    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
