package com.cvdcms.service.impl;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.AppointmentPolicyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;

@Service
public class AppointmentPolicyServiceImpl implements AppointmentPolicyService {
    private static final List<AppointmentStatus> OCCUPYING = List.of(
            AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED,
            AppointmentStatus.CHECKED_IN, AppointmentStatus.COMPLETED);

    private final AppointmentPolicyRepository appointments;
    private final AppointmentSessionWindowRepository windows;
    private final AppointmentSessionPolicyRepository policies;
    private final StaffBranchAssignmentRepository assignments;
    private final StaffScheduleRepository schedules;
    private final LeaveRequestRepository leaves;
    private final StaffProfileRepository staffProfiles;
    private final int defaultBranchCapacity;
    private final int defaultDoctorCapacity;

    @Autowired
    public AppointmentPolicyServiceImpl(AppointmentPolicyRepository appointments,
            AppointmentSessionWindowRepository windows, AppointmentSessionPolicyRepository policies,
            StaffBranchAssignmentRepository assignments, StaffScheduleRepository schedules,
            LeaveRequestRepository leaves,
            StaffProfileRepository staffProfiles,
            @Value("${appointments.branch-capacity:24}") int defaultBranchCapacity,
            @Value("${appointments.doctor-capacity:10}") int defaultDoctorCapacity) {
        this.appointments = appointments;
        this.windows = windows;
        this.policies = policies;
        this.assignments = assignments;
        this.schedules = schedules;
        this.leaves = leaves;
        this.staffProfiles = staffProfiles;
        this.defaultBranchCapacity = defaultBranchCapacity;
        this.defaultDoctorCapacity = defaultDoctorCapacity;
    }

    public AppointmentPolicyServiceImpl(AppointmentPolicyRepository appointments,
            AppointmentSessionWindowRepository windows, AppointmentSessionPolicyRepository policies,
            StaffBranchAssignmentRepository assignments, StaffScheduleRepository schedules,
            LeaveRequestRepository leaves,
            int defaultBranchCapacity,
            int defaultDoctorCapacity) {
        this(appointments, windows, policies, assignments, schedules, leaves, null,
                defaultBranchCapacity, defaultDoctorCapacity);
    }

    @Override
    public Availability availability(Branch branch, LocalDate date, PreferredSession session,
                                     Long excludeAppointmentId, AppointmentActorType actorType) {
        String invalid = invalidSession(branch, date, session, actorType);
        if (invalid != null) return new Availability(false, invalid, 0, List.of());
        List<Appointment> booked = booked(branch.getBranchId(), date, session, excludeAppointmentId);
        List<StaffProfile> doctors = eligibleDoctors(branch, date, session);
        AppointmentSessionPolicy policy = policy(branch.getBranchId(), date, session);
        int doctorCap = doctorCapacity(policy);
        int capacity = Math.min(branchCapacity(policy), doctors.size() * doctorCap);
        int remaining = Math.max(0, capacity - booked.size());
        List<DoctorOption> options = doctors.stream().map(doctor -> {
            long doctorBooked = booked.stream().filter(a -> a.getDoctor() != null
                    && doctor.getStaffId().equals(a.getDoctor().getStaffId())).count();
            return new DoctorOption(doctor.getStaffId(), doctor.getFullName(), doctor.getSpecialty(),
                    Math.max(0, doctorCap - (int) doctorBooked));
        }).filter(option -> option.remaining() > 0).toList();
        String reason = doctors.isEmpty() ? "Chưa có bác sĩ trực buổi này." : remaining == 0 ? "Buổi khám đã đầy." : null;
        return new Availability(remaining > 0, reason, remaining, options);
    }

    @Override
    public List<SessionLoadSummary> dayLoadOverview(Branch branch, LocalDate date,
                                                    AppointmentActorType actorType) {
        if (branch == null || date == null) return List.of();
        List<PreferredSession> sessions = List.of(PreferredSession.MORNING,
                PreferredSession.AFTERNOON, PreferredSession.EVENING);
        Map<PreferredSession, String> names = Map.of(
                PreferredSession.MORNING, "Buổi sáng",
                PreferredSession.AFTERNOON, "Buổi chiều",
                PreferredSession.EVENING, "Buổi tối"
        );

        record RawLoad(PreferredSession session, String name, int booked, int capacity, int remaining,
                       String status, boolean active) {}
        List<RawLoad> rawList = new ArrayList<>();

        for (PreferredSession session : sessions) {
            String name = names.getOrDefault(session, session.name());
            String invalid = invalidSession(branch, date, session, actorType);
            List<StaffProfile> doctors = eligibleDoctors(branch, date, session);
            if (invalid != null || doctors.isEmpty()) {
                rawList.add(new RawLoad(session, name, 0, 0, 0, "OFF", false));
                continue;
            }
            AppointmentSessionPolicy policy = policy(branch.getBranchId(), date, session);
            int capacity = Math.min(branchCapacity(policy), doctors.size() * doctorCapacity(policy));
            int booked = booked(branch.getBranchId(), date, session, null).size();
            int remaining = Math.max(0, capacity - booked);
            double ratio = capacity == 0 ? 1.0 : (double) booked / capacity;
            String status = remaining == 0 ? "FULL" : ratio >= 0.75 ? "HIGH" : ratio <= 0.35 ? "LOW" : "BALANCED";
            rawList.add(new RawLoad(session, name, booked, capacity, remaining, status, true));
        }

        Optional<RawLoad> lightSession = rawList.stream()
                .filter(r -> r.active && "LOW".equals(r.status) && r.remaining >= 2)
                .findFirst();

        List<SessionLoadSummary> result = new ArrayList<>();
        for (RawLoad r : rawList) {
            String rec;
            if (!r.active) {
                rec = "Không có ca trực";
            } else if ("FULL".equals(r.status)) {
                rec = "Ca đã đủ định mức tiếp nhận (" + r.booked + "/" + r.capacity + " ca). Vui lòng chọn ca khác.";
            } else if ("HIGH".equals(r.status) && lightSession.isPresent() && !lightSession.get().session.equals(r.session)) {
                rec = "Ca " + r.name + " sắp đầy (" + r.booked + "/" + r.capacity + " ca). Khuyến nghị điều phối sang "
                        + lightSession.get().name + " (còn " + lightSession.get().remaining + " chỗ) để tránh dồn lịch.";
            } else if ("LOW".equals(r.status)) {
                rec = "Ca " + r.name + " đang vắng (mới " + r.booked + "/" + r.capacity + " ca), rất thích hợp tiếp nhận thêm.";
            } else {
                rec = "Ca " + r.name + " đang tiếp nhận ổn định (" + r.booked + "/" + r.capacity + " ca).";
            }
            result.add(new SessionLoadSummary(r.session, r.name, r.booked, r.capacity, r.remaining, r.status, rec));
        }
        return result;
    }

    @Override
    public void ensureBookable(Branch branch, PatientProfile profile, LocalDate date,
            PreferredSession session, Long doctorId, Long excludeAppointmentId,
            AppointmentActorType actorType) {
        String invalid = invalidSession(branch, date, session, actorType);
        if (invalid != null) throw conflict(invalid);
        boolean duplicate = appointments.findOccupyingForPatientDay(profile.getProfileId(),
                        branch.getBranchId(), date, OCCUPYING).stream()
                .anyMatch(a -> !Objects.equals(a.getAppointmentId(), excludeAppointmentId));
        if (duplicate) throw conflict("Hồ sơ đã có lịch còn hiệu lực tại chi nhánh trong ngày này.");
        List<StaffProfile> doctors = eligibleDoctors(branch, date, session);
        if (doctors.isEmpty()) throw conflict("Chưa có bác sĩ trực buổi này.");
        List<Appointment> booked = booked(branch.getBranchId(), date, session, excludeAppointmentId);
        AppointmentSessionPolicy policy = policy(branch.getBranchId(), date, session);
        int maxCapacity = Math.min(branchCapacity(policy), doctors.size() * doctorCapacity(policy));
        if (booked.size() >= maxCapacity) {
            throw conflict("Buổi khám đã đủ định mức tiếp nhận (" + booked.size() + "/" + maxCapacity + " ca).");
        }
        if (doctorId != null) {
            if (doctors.stream().noneMatch(d -> doctorId.equals(d.getStaffId()))) {
                throw conflict("Bác sĩ không khả dụng trong ngày và buổi đã chọn.");
            }
            long doctorBooked = booked.stream().filter(a -> a.getDoctor() != null
                    && doctorId.equals(a.getDoctor().getStaffId())).count();
            if (doctorBooked >= doctorCapacity(policy)) {
                throw conflict("Bác sĩ đã hết khả năng nhận lịch trong buổi (" + doctorBooked + "/" + doctorCapacity(policy) + " ca).");
            }
        }
    }

    @Override
    public List<DoctorOption> doctorsForDate(Branch branch, LocalDate date,
                                             AppointmentActorType actorType) {
        Map<Long, StaffProfile> doctors = new LinkedHashMap<>();
        for (PreferredSession session : List.of(PreferredSession.MORNING,
                PreferredSession.AFTERNOON, PreferredSession.EVENING)) {
            if (actorType == AppointmentActorType.RECEPTIONIST || invalidSession(branch, date, session, actorType) == null) {
                eligibleDoctors(branch, date, session)
                        .forEach(doctor -> doctors.put(doctor.getStaffId(), doctor));
            }
        }
        if (doctors.isEmpty() && branch != null && date != null) {
            Set<Long> assigned = new HashSet<>();
            for (StaffBranchAssignment assignment : assignments.findByBranchAndStatus(
                    branch.getBranchId(), AssignmentStatus.ACTIVE)) {
                if (assignment.getStartDate().isAfter(date)
                        || (assignment.getEndDate() != null && assignment.getEndDate().isBefore(date))) continue;
                String role = assignment.getRole().getRoleName();
                if (!Boolean.TRUE.equals(assignment.getRole().getIsActive()) || role == null
                        || !("BAC_SI".equalsIgnoreCase(role) || "ROLE_BAC_SI".equalsIgnoreCase(role))) continue;
                if (assignment.getStaff().getStatus() == StaffStatus.ACTIVE) {
                    assigned.add(assignment.getStaff().getStaffId());
                }
            }
            List<LeaveRequest> approved = leaves.findApprovedOn(branch.getBranchId(), date, LeaveStatus.APPROVED);
            for (StaffSchedule schedule : schedules.findForBooking(branch.getBranchId(), date, ScheduleStatus.SCHEDULED)) {
                StaffProfile doctor = schedule.getStaff();
                WorkShift shift = schedule.getShift();
                if (assigned.contains(doctor.getStaffId()) && Boolean.TRUE.equals(shift.getIsActive())) {
                    boolean absent = approved.stream().anyMatch(leave ->
                            leave.getStaff().getStaffId().equals(doctor.getStaffId())
                                    && (leave.getShift() == null || leave.getShift().getShiftId().equals(shift.getShiftId())));
                    if (!absent) {
                        doctors.put(doctor.getStaffId(), doctor);
                    }
                }
            }
        }
        return doctors.values().stream().sorted(Comparator.comparing(StaffProfile::getFullName))
                .map(d -> new DoctorOption(d.getStaffId(), d.getFullName(), d.getSpecialty(), 0))
                .toList();
    }

    @Override
    public boolean doctorAvailable(Branch branch, LocalDate date, PreferredSession session,
                                   Long doctorId) {
        return doctorId == null || invalidSession(branch, date, session,
                AppointmentActorType.RECEPTIONIST) == null
                && eligibleDoctors(branch, date, session).stream()
                .anyMatch(d -> doctorId.equals(d.getStaffId()));
    }

    @Override
    public boolean sessionEnded(Branch branch, LocalDate date, PreferredSession session,
                                LocalDateTime now) {
        if (date == null || session == null || session == PreferredSession.ANY) return false;
        return !now.isBefore(LocalDateTime.of(date, sessionRange(branch, session).end()));
    }

    @Override
    public void ensureModificationCutoff(Appointment appointment, AppointmentActorType actorType) {
        // Cho phép bệnh nhân hủy, sửa lịch hẹn linh hoạt như bên lễ tân, không áp dụng giới hạn thời gian trước 2 tiếng
    }

    private String invalidSession(Branch branch, LocalDate date, PreferredSession session,
                                  AppointmentActorType actorType) {
        if (branch == null || !Boolean.TRUE.equals(branch.getIsActive())) return "Chi nhánh không hoạt động.";
        if (date == null || session == null || session == PreferredSession.ANY) {
            return "Vui lòng chọn ngày và buổi khám cụ thể.";
        }
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        if (date.isBefore(today) || date.isAfter(today.plusDays(90))) {
            return "Ngày khám phải nằm trong 90 ngày tới.";
        }
        String day = date.getDayOfWeek() == DayOfWeek.SUNDAY ? "CN"
                : String.valueOf(date.getDayOfWeek().getValue() + 1);
        if (branch.getWorkingDays() == null || Arrays.stream(branch.getWorkingDays().split(","))
                .map(String::trim).noneMatch(day::equalsIgnoreCase)) {
            return "Chi nhánh không làm việc trong ngày này.";
        }
        SessionRange range = sessionRange(branch, session);
        if (!range.active() || !range.start().isBefore(range.end())) {
            return "Chi nhánh không nhận lịch trong buổi này.";
        }
        if (branch.getOpeningTime().isAfter(range.start())
                || branch.getClosingTime().isBefore(range.end())) {
            return "Chi nhánh không mở cửa trong buổi được chọn.";
        }
        // Đồng bộ linh hoạt như bên lễ tân: Chỉ từ chối khi buổi khám trong ngày đã hoàn toàn kết thúc
        if (date.equals(today) && !LocalTime.now(CLINIC_ZONE).isBefore(range.end())) {
            return "Buổi khám trong ngày đã kết thúc.";
        }
        return null;
    }

    private List<Appointment> booked(Long branchId, LocalDate date, PreferredSession session,
                                     Long excludeAppointmentId) {
        return appointments.findOccupyingForSession(branchId, date, session, OCCUPYING).stream()
                .filter(a -> !Objects.equals(a.getAppointmentId(), excludeAppointmentId)).toList();
    }

    private List<StaffProfile> eligibleDoctors(Branch branch, LocalDate date,
                                               PreferredSession session) {
        SessionRange range = sessionRange(branch, session);
        Set<Long> assigned = new HashSet<>();
        for (StaffBranchAssignment assignment : assignments.findByBranchAndStatus(
                branch.getBranchId(), AssignmentStatus.ACTIVE)) {
            if (assignment.getStartDate().isAfter(date)
                    || assignment.getEndDate() != null && assignment.getEndDate().isBefore(date)) continue;
            String role = assignment.getRole().getRoleName();
            if (!Boolean.TRUE.equals(assignment.getRole().getIsActive()) || role == null
                    || !("BAC_SI".equalsIgnoreCase(role) || "ROLE_BAC_SI".equalsIgnoreCase(role))) continue;
            if (assignment.getStaff().getStatus() == StaffStatus.ACTIVE) {
                assigned.add(assignment.getStaff().getStaffId());
            }
        }
        List<LeaveRequest> approved = leaves.findApprovedOn(branch.getBranchId(), date, LeaveStatus.APPROVED);
        Map<Long, StaffProfile> result = new LinkedHashMap<>();
        for (StaffSchedule schedule : schedules.findForBooking(branch.getBranchId(), date,
                ScheduleStatus.SCHEDULED)) {
            StaffProfile doctor = schedule.getStaff();
            WorkShift shift = schedule.getShift();
            if (!assigned.contains(doctor.getStaffId()) || !Boolean.TRUE.equals(shift.getIsActive())
                    || shift.getStartTime().isAfter(range.start())
                    || shift.getEndTime().isBefore(range.end())) continue;
            boolean absent = approved.stream().anyMatch(leave ->
                    leave.getStaff().getStaffId().equals(doctor.getStaffId())
                            && (leave.getShift() == null
                            || leave.getShift().getShiftId().equals(shift.getShiftId())));
            if (!absent) result.put(doctor.getStaffId(), doctor);
        }

        return result.values().stream().sorted(Comparator.comparing(StaffProfile::getFullName)).toList();
    }

    private SessionRange sessionRange(Branch branch, PreferredSession session) {
        return windows.findByBranch_BranchIdAndPreferredSession(branch.getBranchId(), session)
                .map(window -> new SessionRange(window.getStartTime(), window.getEndTime(),
                        Boolean.TRUE.equals(window.getIsActive())))
                .orElseGet(() -> switch (session) {
                    case MORNING -> new SessionRange(LocalTime.of(8, 0), LocalTime.NOON, true);
                    case AFTERNOON -> new SessionRange(LocalTime.of(13, 30), LocalTime.of(17, 30), true);
                    case EVENING -> new SessionRange(LocalTime.of(18, 0), LocalTime.of(20, 0), true);
                    default -> new SessionRange(LocalTime.MAX, LocalTime.MIN, false);
                });
    }

    private AppointmentSessionPolicy policy(Long branchId, LocalDate date, PreferredSession session) {
        return policies.findByBranch_BranchIdAndAppointmentDateAndPreferredSession(branchId, date, session)
                .orElse(null);
    }

    private int branchCapacity(AppointmentSessionPolicy policy) {
        return policy == null ? defaultBranchCapacity : policy.getBranchCapacity();
    }

    private int doctorCapacity(AppointmentSessionPolicy policy) {
        return policy == null ? defaultDoctorCapacity : policy.getDoctorCapacity();
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record SessionRange(LocalTime start, LocalTime end, boolean active) { }
}
