package com.cvdcms.service.impl;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.AppointmentConflictDetectionService;
import com.cvdcms.service.AppointmentPolicyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class AppointmentConflictDetectionServiceImpl implements AppointmentConflictDetectionService {
    private static final List<AppointmentStatus> OCCUPYING = List.of(
            AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED,
            AppointmentStatus.CHECKED_IN, AppointmentStatus.COMPLETED);

    private final AppointmentPolicyRepository appointments;
    private final AppointmentSessionWindowRepository windows;
    private final AppointmentSessionPolicyRepository policies;
    private final StaffBranchAssignmentRepository assignments;
    private final StaffScheduleRepository schedules;
    private final LeaveRequestRepository leaves;
    private final int defaultBranchCapacity;
    private final int defaultDoctorCapacity;
    private final AppointmentPolicyService policyService;

    @Autowired
    public AppointmentConflictDetectionServiceImpl(AppointmentPolicyRepository appointments,
                                                   AppointmentSessionWindowRepository windows,
                                                   AppointmentSessionPolicyRepository policies,
                                                   StaffBranchAssignmentRepository assignments,
                                                   StaffScheduleRepository schedules,
                                                   LeaveRequestRepository leaves,
                                                   @Value("${appointments.branch-capacity:24}") int defaultBranchCapacity,
                                                   @Value("${appointments.doctor-capacity:10}") int defaultDoctorCapacity,
                                                   AppointmentPolicyService policyService) {
        this.appointments = appointments;
        this.windows = windows;
        this.policies = policies;
        this.assignments = assignments;
        this.schedules = schedules;
        this.leaves = leaves;
        this.defaultBranchCapacity = defaultBranchCapacity;
        this.defaultDoctorCapacity = defaultDoctorCapacity;
        this.policyService = policyService;
    }

    public AppointmentConflictDetectionServiceImpl(AppointmentPolicyRepository appointments,
                                                   AppointmentSessionWindowRepository windows,
                                                   AppointmentSessionPolicyRepository policies,
                                                   StaffBranchAssignmentRepository assignments,
                                                   StaffScheduleRepository schedules,
                                                   LeaveRequestRepository leaves,
                                                   int defaultBranchCapacity,
                                                   int defaultDoctorCapacity) {
        this(appointments, windows, policies, assignments, schedules, leaves, defaultBranchCapacity, defaultDoctorCapacity, null);
    }

    @Override
    public List<Conflict> detectConflicts(Appointment appointment) {
        if (appointment == null) {
            return List.of(new Conflict("NULL_APPOINTMENT", "Lịch hẹn không tồn tại.", true));
        }

        List<Conflict> conflicts = new ArrayList<>();
        Branch branch = appointment.getBranch();
        LocalDate date = appointment.getAppointmentDate();
        PreferredSession session = appointment.getPreferredSession();
        StaffProfile doctor = appointment.getDoctor();
        PatientProfile profile = appointment.getPatientProfile();
        LocalDate today = LocalDate.now(AppointmentPolicyService.CLINIC_ZONE);

        // 1. Branch active check
        if (branch == null) {
            conflicts.add(new Conflict("BRANCH_MISSING", "Chưa chỉ định chi nhánh phòng khám.", true));
            return conflicts;
        }

        if (!Boolean.TRUE.equals(branch.getIsActive())) {
            conflicts.add(new Conflict("BRANCH_INACTIVE", "Chi nhánh phòng khám hiện đang tạm ngừng hoạt động.", true));
        }

        // 2. Date checks
        if (date == null) {
            conflicts.add(new Conflict("DATE_MISSING", "Chưa chọn ngày khám.", true));
            return conflicts;
        }

        if (date.isBefore(today) && (appointment.getStatus() == AppointmentStatus.PENDING || appointment.getStatus() == AppointmentStatus.CONFIRMED)) {
            conflicts.add(new Conflict("PAST_DATE", "Ngày khám nằm trong quá khứ so với thời điểm hiện tại.", true));
        }

        String dayStr = date.getDayOfWeek() == DayOfWeek.SUNDAY ? "CN" : String.valueOf(date.getDayOfWeek().getValue() + 1);
        if (branch.getWorkingDays() == null || Arrays.stream(branch.getWorkingDays().split(","))
                .map(String::trim).noneMatch(dayStr::equalsIgnoreCase)) {
            conflicts.add(new Conflict("BRANCH_CLOSED_DAY", "Chi nhánh không làm việc vào ngày " + dayStr + " (" + date + ").", true));
        }

        // 3. Session window checks
        if (session == null || session == PreferredSession.ANY) {
            conflicts.add(new Conflict("UNSPECIFIED_SESSION", "Lịch hẹn chưa chọn buổi khám cụ thể.", false));
        } else {
            SessionRange range = sessionRange(branch, session);
            if (!range.active() || !range.start().isBefore(range.end())) {
                conflicts.add(new Conflict("SESSION_INACTIVE", "Chi nhánh không nhận lịch trong buổi " + session + ".", true));
            } else if (branch.getOpeningTime().isAfter(range.start()) || branch.getClosingTime().isBefore(range.end())) {
                conflicts.add(new Conflict("SESSION_OUTSIDE_HOURS", "Buổi khám nằm ngoài khung giờ mở cửa của chi nhánh.", true));
            }
        }

        // 4. Doctor schedule & leave checks
        if (doctor != null) {
            Long doctorId = doctor.getStaffId();
            boolean assigned = isDoctorAssigned(branch.getBranchId(), doctorId, date);
            if (!assigned) {
                conflicts.add(new Conflict("DOCTOR_NOT_ASSIGNED", "Bác sĩ " + doctor.getFullName() + " không có phân công hoạt động tại chi nhánh vào ngày khám.", true));
            }

            if (session != null && session != PreferredSession.ANY) {
                SessionRange range = sessionRange(branch, session);
                List<StaffSchedule> scheduledShifts = schedules.findForBooking(branch.getBranchId(), date, ScheduleStatus.SCHEDULED).stream()
                        .filter(s -> s.getStaff().getStaffId().equals(doctorId))
                        .filter(s -> Boolean.TRUE.equals(s.getShift().getIsActive()))
                        .filter(s -> !s.getShift().getStartTime().isAfter(range.start()) && !s.getShift().getEndTime().isBefore(range.end()))
                        .toList();

                if (scheduledShifts.isEmpty()) {
                    conflicts.add(new Conflict("DOCTOR_NO_SCHEDULE", "Bác sĩ " + doctor.getFullName() + " không có ca trực trong buổi " + session + ".", true));
                }

                List<LeaveRequest> approvedLeaves = leaves.findApprovedOn(branch.getBranchId(), date, LeaveStatus.APPROVED).stream()
                        .filter(l -> l.getStaff().getStaffId().equals(doctorId))
                        .filter(l -> l.getShift() == null || scheduledShifts.stream().anyMatch(s -> s.getShift().getShiftId().equals(l.getShift().getShiftId())))
                        .toList();

                if (!approvedLeaves.isEmpty()) {
                    conflicts.add(new Conflict("DOCTOR_ON_LEAVE", "Bác sĩ " + doctor.getFullName() + " đã có đơn nghỉ phép được duyệt trong ca trực.", true));
                }
            }
        }

        // 5. Patient duplicate booking check
        if (profile != null) {
            boolean duplicate = appointments.findOccupyingForPatientDay(profile.getProfileId(), branch.getBranchId(), date, OCCUPYING).stream()
                    .anyMatch(a -> !Objects.equals(a.getAppointmentId(), appointment.getAppointmentId()));
            if (duplicate) {
                conflicts.add(new Conflict("DUPLICATE_PATIENT_APPOINTMENT", "Bệnh nhân đã có lịch hẹn khác còn hiệu lực tại chi nhánh trong ngày này.", false));
            }
        }

        // 6. Capacity checks
        if (session != null && session != PreferredSession.ANY) {
            if (policyService != null) {
                var availability = policyService.availability(branch, date, session, appointment.getAppointmentId(), AppointmentActorType.RECEPTIONIST);
                if (!availability.available() && availability.remainingBranch() <= 0) {
                    conflicts.add(new Conflict("CAPACITY_FULL", "Buổi khám đã vượt định mức tiếp nhận chi nhánh (" + availability.reason() + ").", false));
                }
                if (doctor != null && availability.doctors().stream().noneMatch(d -> d.id().equals(doctor.getStaffId()))) {
                    conflicts.add(new Conflict("DOCTOR_CAPACITY_FULL", "Bác sĩ " + doctor.getFullName() + " đã hết khả năng nhận thêm lịch trong buổi.", false));
                }
            } else {
                AppointmentSessionPolicy policy = policies.findByBranch_BranchIdAndAppointmentDateAndPreferredSession(branch.getBranchId(), date, session).orElse(null);
                int branchCap = policy == null ? defaultBranchCapacity : policy.getBranchCapacity();
                int docCap = policy == null ? defaultDoctorCapacity : policy.getDoctorCapacity();

                List<Appointment> booked = appointments.findOccupyingForSession(branch.getBranchId(), date, session, OCCUPYING).stream()
                        .filter(a -> !Objects.equals(a.getAppointmentId(), appointment.getAppointmentId()))
                        .toList();

                if (booked.size() >= branchCap) {
                    conflicts.add(new Conflict("CAPACITY_FULL", "Buổi khám đã vượt định mức tiếp nhận chi nhánh (" + booked.size() + "/" + branchCap + ").", false));
                }

                if (doctor != null) {
                    long doctorBooked = booked.stream()
                            .filter(a -> a.getDoctor() != null && doctor.getStaffId().equals(a.getDoctor().getStaffId()))
                            .count();
                    if (doctorBooked >= docCap) {
                        conflicts.add(new Conflict("DOCTOR_CAPACITY_FULL", "Bác sĩ " + doctor.getFullName() + " đã hết khả năng nhận thêm lịch trong buổi (" + doctorBooked + "/" + docCap + ").", false));
                    }
                }
            }
        }

        return conflicts;
    }

    @Override
    public List<Conflict> detectConflicts(Long appointmentId) {
        if (appointmentId == null) return List.of();
        return appointments.findById(appointmentId)
                .map(this::detectConflicts)
                .orElseGet(() -> List.of(new Conflict("NOT_FOUND", "Không tìm thấy lịch hẹn ID: " + appointmentId, true)));
    }

    private boolean isDoctorAssigned(Long branchId, Long doctorId, LocalDate date) {
        return assignments.findByBranchAndStatus(branchId, AssignmentStatus.ACTIVE).stream()
                .filter(a -> a.getStaff().getStaffId().equals(doctorId))
                .filter(a -> !a.getStartDate().isAfter(date) && (a.getEndDate() == null || !a.getEndDate().isBefore(date)))
                .anyMatch(a -> {
                    String role = a.getRole().getRoleName();
                    return Boolean.TRUE.equals(a.getRole().getIsActive()) && role != null
                            && ("BAC_SI".equalsIgnoreCase(role) || "ROLE_BAC_SI".equalsIgnoreCase(role))
                            && a.getStaff().getStatus() == StaffStatus.ACTIVE;
                });
    }

    private SessionRange sessionRange(Branch branch, PreferredSession session) {
        return windows.findByBranch_BranchIdAndPreferredSession(branch.getBranchId(), session)
                .map(window -> new SessionRange(window.getStartTime(), window.getEndTime(), Boolean.TRUE.equals(window.getIsActive())))
                .orElseGet(() -> switch (session) {
                    case MORNING -> new SessionRange(LocalTime.of(8, 0), LocalTime.NOON, true);
                    case AFTERNOON -> new SessionRange(LocalTime.of(13, 30), LocalTime.of(17, 30), true);
                    case EVENING -> new SessionRange(LocalTime.of(18, 0), LocalTime.of(20, 0), true);
                    default -> new SessionRange(LocalTime.MAX, LocalTime.MIN, false);
                });
    }

    private record SessionRange(LocalTime start, LocalTime end, boolean active) { }
}
