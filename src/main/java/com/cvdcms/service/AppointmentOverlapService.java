package com.cvdcms.service;

import com.cvdcms.entity.Appointment;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.DentalChair;
import com.cvdcms.entity.LeaveRequest;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.StaffSchedule;
import com.cvdcms.entity.enums.AppointmentActorType;
import com.cvdcms.entity.enums.AppointmentStatus;
import com.cvdcms.entity.enums.ChairStatus;
import com.cvdcms.entity.enums.LeaveStatus;
import com.cvdcms.entity.enums.PreferredSession;
import com.cvdcms.entity.enums.ScheduleStatus;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.repository.AppointmentPolicyRepository;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.DentalChairRepository;
import com.cvdcms.repository.LeaveRequestRepository;
import com.cvdcms.repository.ServiceRepository;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.StaffScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Evaluates Clinic Overlap vs Doctor Overlap as soft warnings and builds
 * alternative time-slot suggestions matching full service duration and operating constraints.
 */
@Service
@RequiredArgsConstructor
public class AppointmentOverlapService {

    private static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Bangkok");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final int STEP_MINUTES = 15;
    private static final int MAX_SUGGESTIONS = 12;
    private static final int GRACE_PERIOD_MINUTES = 15;

    private static final List<AppointmentStatus> OCCUPYING = List.of(
            AppointmentStatus.PENDING,
            AppointmentStatus.CONFIRMED,
            AppointmentStatus.CHECKED_IN
    );

    private final BranchRepository branches;
    private final ServiceRepository services;
    private final StaffProfileRepository staffProfiles;
    private final StaffScheduleRepository staffSchedules;
    private final LeaveRequestRepository leaveRequests;
    private final AppointmentPolicyRepository appointments;
    private final DentalChairRepository dentalChairs;
    private final AppointmentPolicyService policy;

    private Clock clock = Clock.system(CLINIC_ZONE);

    public void setClock(Clock clock) {
        if (clock != null) {
            this.clock = clock;
        }
    }

    public record SuggestedTimeSlot(
            LocalTime startTime,
            LocalTime endTime,
            String label,
            String clinicStatus,
            String clinicLabel,
            String doctorStatus,
            String doctorLabel,
            int clinicOverlapCount,
            int branchCapacity
    ) {}

    public record OverlapCheckResult(
            boolean validOperatingWindow,
            String operatingError,
            int durationMinutes,
            LocalTime requestedStart,
            LocalTime requestedEnd,
            boolean clinicOverlap,
            String clinicStatus,
            String clinicWarningMessage,
            int clinicOverlapCount,
            int branchCapacity,
            int clinicWarningThreshold,
            boolean doctorOverlap,
            String doctorStatus,
            String doctorWarningMessage,
            int doctorOverlapCount,
            String doctorName,
            List<SuggestedTimeSlot> clinicSuggestions,
            List<SuggestedTimeSlot> doctorSuggestions,
            List<SuggestedTimeSlot> combinedSuggestions,
            String noSuggestionMessage
    ) {
        public boolean hasAnyWarning() {
            return clinicOverlap || doctorOverlap;
        }
    }

    private record TimeWindow(LocalTime start, LocalTime end) {}

    @Transactional(readOnly = true)
    public OverlapCheckResult checkOverlap(
            Long branchId,
            LocalDate date,
            PreferredSession session,
            LocalTime expectedTime,
            Long doctorId,
            Long serviceId,
            Long excludeAppointmentId
    ) {
        if (branchId == null || date == null || session == null) {
            return invalidResult("Thiếu thông tin chi nhánh, ngày khám hoặc buổi khám.", 30, expectedTime);
        }
        Branch branch = branches.findById(branchId).orElse(null);
        if (branch == null || !Boolean.TRUE.equals(branch.getIsActive())) {
            return invalidResult("Chi nhánh không hoạt động hoặc không tồn tại.", 30, expectedTime);
        }
        com.cvdcms.entity.Service service = serviceId == null ? null : services.findById(serviceId).orElse(null);
        int durationMinutes = AppointmentSlotService.duration(service);
        LocalTime requestedEnd = expectedTime != null ? expectedTime.plusMinutes(durationMinutes) : null;

        AppointmentPolicyService.Availability availability = policy.availability(
                branch, date, session, excludeAppointmentId, AppointmentActorType.PATIENT);
        if (availability == null || !availability.available()) {
            String reason = availability != null && availability.reason() != null
                    ? availability.reason()
                    : "Khung buổi khám không khả dụng theo lịch hoạt động của chi nhánh.";
            return invalidResult(reason, durationMinutes, expectedTime);
        }

        AppointmentPolicyService.SessionRange sessionRange = policy.sessionRange(branch, session);
        if (sessionRange == null || !sessionRange.active()) {
            return invalidResult("Buổi khám không hoạt động tại chi nhánh.", durationMinutes, expectedTime);
        }

        LocalDateTime nowDt = LocalDateTime.now(clock);
        LocalDate today = nowDt.toLocalDate();
        LocalTime nowTime = nowDt.toLocalTime();

        if (expectedTime == null) {
            return invalidResult("Vui lòng chọn khung giờ dự kiến.", durationMinutes, null);
        }
        if (expectedTime.isBefore(sessionRange.start()) || requestedEnd.isAfter(sessionRange.end())) {
            return invalidResult(
                    "Khung giờ " + expectedTime.format(TIME_FMT) + " - " + requestedEnd.format(TIME_FMT)
                            + " nằm ngoài giờ làm việc của ca (" + sessionRange.start().format(TIME_FMT)
                            + " - " + sessionRange.end().format(TIME_FMT) + ").",
                    durationMinutes, expectedTime);
        }
        if (date.equals(today) && !expectedTime.isAfter(nowTime)) {
            return invalidResult(
                    "Giờ hẹn " + expectedTime.format(TIME_FMT) + " đã qua so với thời gian hiện tại.",
                    durationMinutes, expectedTime);
        }

        StaffProfile doctor = doctorId == null ? null : staffProfiles.findById(doctorId).orElse(null);
        List<TimeWindow> doctorNetWindows = List.of();
        if (doctorId != null) {
            if (doctor == null || doctor.getStatus() != StaffStatus.ACTIVE) {
                return invalidResult("Bác sĩ đã chọn hiện không khả dụng.", durationMinutes, expectedTime);
            }
            boolean eligibleInSession = availability.doctors().stream().anyMatch(d -> doctorId.equals(d.id()));
            if (!eligibleInSession) {
                return invalidResult("Bác sĩ đã chọn không có ca trực hoặc đang nghỉ phép trong buổi này.",
                        durationMinutes, expectedTime);
            }
            doctorNetWindows = loadDoctorNetWindows(branchId, doctorId, date, sessionRange);
            if (!fitsAnyWindow(expectedTime, requestedEnd, doctorNetWindows)) {
                return invalidResult(
                        "Khung giờ " + expectedTime.format(TIME_FMT) + " - " + requestedEnd.format(TIME_FMT)
                                + " nằm ngoài ca làm việc hoặc trùng lịch nghỉ của bác sĩ " + doctor.getFullName() + ".",
                        durationMinutes, expectedTime);
            }
        }

        List<Appointment> sessionBooked = appointments.findOccupyingForSession(branchId, date, session, OCCUPYING)
                .stream()
                .filter(a -> excludeAppointmentId == null || !Objects.equals(a.getAppointmentId(), excludeAppointmentId))
                .toList();
        List<Appointment> doctorBooked = doctorId == null ? List.of()
                : appointments.findDoctorAppointmentsForDate(doctorId, date, OCCUPYING).stream()
                .filter(a -> excludeAppointmentId == null || !Objects.equals(a.getAppointmentId(), excludeAppointmentId))
                .toList();

        int workingDoctorCount = Math.max(1, availability.doctors().size());
        int operationalChairs = (int) dentalChairs.findByBranchBranchIdOrderByChairCodeAsc(branchId).stream()
                .filter(c -> c.getStatus() != ChairStatus.MAINTENANCE && c.getStatus() != ChairStatus.INACTIVE)
                .count();
        int branchCapacity = operationalChairs > 0
                ? Math.min(workingDoctorCount, operationalChairs)
                : workingDoctorCount;
        int clinicThreshold = branchCapacity <= 1 ? 1 : (int) Math.ceil(branchCapacity * 0.75d);

        int clinicOverlapCount = countOverlaps(sessionBooked, expectedTime, requestedEnd, date, nowDt);
        String clinicStatus = classifyClinicStatus(clinicOverlapCount, clinicThreshold);
        boolean clinicOverlap = clinicOverlapCount >= clinicThreshold;
        String clinicWarningMessage = clinicOverlap
                ? "Cảnh báo: Khung giờ này đang có nhiều lịch hẹn tại chi nhánh ("
                + clinicOverlapCount + "/" + branchCapacity
                + " vị trí đang bận). Bạn vẫn có thể đặt lịch theo thời gian mong muốn, tuy nhiên có thể phải chờ lâu hơn dự kiến."
                : null;

        int doctorOverlapCount = doctorId == null ? 0 : countOverlaps(doctorBooked, expectedTime, requestedEnd, date, nowDt);
        boolean doctorOverlap = doctorId != null && doctorOverlapCount > 0;
        String doctorStatus = doctorId == null ? "NOT_APPLICABLE" : (doctorOverlap ? "HAS_OVERLAP" : "NO_OVERLAP");
        String doctorWarningMessage = doctorOverlap
                ? "Cảnh báo: Bác sĩ " + (doctor != null ? doctor.getFullName() : "bạn chọn")
                + " đang có lịch hẹn giao với khoảng thời gian này. Bạn vẫn có thể giữ lịch, tuy nhiên có thể phải chờ bác sĩ hoàn thành ca khám trước đó."
                : null;

        // Build candidate suggestions across the valid session window
        List<SuggestedTimeSlot> allCandidates = buildCandidates(
                sessionRange, date, today, nowTime, nowDt, durationMinutes,
                expectedTime, doctorId, doctorNetWindows, sessionBooked, doctorBooked,
                branchCapacity, clinicThreshold);

        List<SuggestedTimeSlot> clinicSuggestions = new ArrayList<>();
        List<SuggestedTimeSlot> doctorSuggestions = new ArrayList<>();
        List<SuggestedTimeSlot> combinedSuggestions = new ArrayList<>();
        java.util.Set<LocalTime> usedStartTimes = new java.util.HashSet<>();

        if (clinicOverlap && doctorOverlap) {
            combinedSuggestions = allCandidates.stream()
                    .filter(s -> "NO_OVERLAP".equals(s.clinicStatus()) && "NO_OVERLAP".equals(s.doctorStatus()))
                    .limit(MAX_SUGGESTIONS)
                    .toList();
            combinedSuggestions.forEach(s -> usedStartTimes.add(s.startTime()));
        }
        if (clinicOverlap) {
            clinicSuggestions = allCandidates.stream()
                    .filter(s -> !usedStartTimes.contains(s.startTime()))
                    .filter(s -> "NO_OVERLAP".equals(s.clinicStatus()))
                    .limit(MAX_SUGGESTIONS)
                    .toList();
            if (clinicSuggestions.isEmpty()) {
                clinicSuggestions = allCandidates.stream()
                        .filter(s -> !usedStartTimes.contains(s.startTime()))
                        .filter(s -> "WITHIN_CAPACITY".equals(s.clinicStatus()))
                        .limit(MAX_SUGGESTIONS)
                        .toList();
            }
            clinicSuggestions.forEach(s -> usedStartTimes.add(s.startTime()));
        }
        if (doctorOverlap) {
            doctorSuggestions = allCandidates.stream()
                    .filter(s -> !usedStartTimes.contains(s.startTime()))
                    .filter(s -> "NO_OVERLAP".equals(s.doctorStatus()))
                    .limit(MAX_SUGGESTIONS)
                    .toList();
            doctorSuggestions.forEach(s -> usedStartTimes.add(s.startTime()));
        }

        String noSuggestionMessage = null;
        if ((clinicOverlap || doctorOverlap)
                && clinicSuggestions.isEmpty()
                && doctorSuggestions.isEmpty()
                && combinedSuggestions.isEmpty()) {
            noSuggestionMessage = "Không tìm được khung giờ trống phù hợp đủ " + durationMinutes
                    + " phút trong ca này. Bạn vẫn có thể giữ giờ mong muốn hoặc chọn ngày/ca khác.";
        }

        return new OverlapCheckResult(
                true,
                null,
                durationMinutes,
                expectedTime,
                requestedEnd,
                clinicOverlap,
                clinicStatus,
                clinicWarningMessage,
                clinicOverlapCount,
                branchCapacity,
                clinicThreshold,
                doctorOverlap,
                doctorStatus,
                doctorWarningMessage,
                doctorOverlapCount,
                doctor != null ? doctor.getFullName() : null,
                clinicSuggestions,
                doctorSuggestions,
                combinedSuggestions,
                noSuggestionMessage
        );
    }

    private List<SuggestedTimeSlot> buildCandidates(
            AppointmentPolicyService.SessionRange sessionRange,
            LocalDate date,
            LocalDate today,
            LocalTime nowTime,
            LocalDateTime nowDt,
            int durationMinutes,
            LocalTime requestedStart,
            Long doctorId,
            List<TimeWindow> doctorNetWindows,
            List<Appointment> sessionBooked,
            List<Appointment> doctorBooked,
            int branchCapacity,
            int clinicThreshold
    ) {
        List<LocalTime> starts = new ArrayList<>();
        LocalTime cursor = sessionRange.start();
        while (!cursor.plusMinutes(durationMinutes).isAfter(sessionRange.end())) {
            if (!cursor.equals(requestedStart) && (!date.equals(today) || cursor.isAfter(nowTime))) {
                LocalTime candEnd = cursor.plusMinutes(durationMinutes);
                if (doctorId == null || fitsAnyWindow(cursor, candEnd, doctorNetWindows)) {
                    starts.add(cursor);
                }
            }
            LocalTime next = cursor.plusMinutes(STEP_MINUTES);
            if (!next.isAfter(cursor)) {
                break;
            }
            cursor = next;
        }

        starts.sort(Comparator
                .comparingLong((LocalTime t) -> Math.abs(Duration.between(requestedStart, t).toMinutes()))
                .thenComparing(t -> t));

        List<SuggestedTimeSlot> result = new ArrayList<>();
        for (LocalTime start : starts) {
            LocalTime end = start.plusMinutes(durationMinutes);
            int cCount = countOverlaps(sessionBooked, start, end, date, nowDt);
            String cStatus = classifyClinicStatus(cCount, clinicThreshold);
            String cLabel = switch (cStatus) {
                case "NO_OVERLAP" -> "Không overlap";
                case "WITHIN_CAPACITY" -> "Có thể đông (" + cCount + "/" + branchCapacity + ")";
                default -> "Đông (" + cCount + "/" + branchCapacity + ")";
            };
            String dStatus;
            String dLabel;
            if (doctorId == null) {
                dStatus = "NOT_APPLICABLE";
                dLabel = "Chưa chọn bác sĩ";
            } else {
                int dCount = countOverlaps(doctorBooked, start, end, date, nowDt);
                dStatus = dCount > 0 ? "HAS_OVERLAP" : "NO_OVERLAP";
                dLabel = dCount > 0 ? "Có overlap" : "Không overlap";
            }
            result.add(new SuggestedTimeSlot(
                    start,
                    end,
                    start.format(TIME_FMT) + " - " + end.format(TIME_FMT),
                    cStatus,
                    cLabel,
                    dStatus,
                    dLabel,
                    cCount,
                    branchCapacity
            ));
        }
        return result;
    }

    private String classifyClinicStatus(int overlapCount, int clinicThreshold) {
        if (overlapCount <= 0) {
            return "NO_OVERLAP";
        }
        if (overlapCount < clinicThreshold) {
            return "WITHIN_CAPACITY";
        }
        return "OVERLAP_WARNING";
    }

    private int countOverlaps(List<Appointment> booked, LocalTime start, LocalTime end,
                              LocalDate date, LocalDateTime nowDt) {
        int count = 0;
        for (Appointment appt : booked) {
            TimeWindow iv = effectiveWindow(appt, date, nowDt);
            if (iv != null && iv.start().isBefore(end) && start.isBefore(iv.end())) {
                count++;
            }
        }
        return count;
    }

    private TimeWindow effectiveWindow(Appointment appt, LocalDate date, LocalDateTime nowDt) {
        if (appt == null || appt.getExpectedTime() == null) {
            return null;
        }
        LocalTime start = appt.getExpectedTime();
        LocalTime end = appt.getExpectedEndTime() != null
                ? appt.getExpectedEndTime()
                : start.plusMinutes(appt.getDurationMinutes() != null ? appt.getDurationMinutes() : 30);
        if (date.equals(nowDt.toLocalDate())
                && (appt.getStatus() == AppointmentStatus.PENDING || appt.getStatus() == AppointmentStatus.CONFIRMED)
                && nowDt.toLocalTime().isAfter(start.plusMinutes(GRACE_PERIOD_MINUTES))) {
            return null;
        }
        return new TimeWindow(start, end);
    }

    private List<TimeWindow> loadDoctorNetWindows(Long branchId, Long doctorId, LocalDate date,
                                                  AppointmentPolicyService.SessionRange sessionRange) {
        List<StaffSchedule> schedules = staffSchedules.findDoctorSchedulesForDate(doctorId, date, ScheduleStatus.SCHEDULED)
                .stream()
                .filter(s -> s.getBranch() != null && branchId.equals(s.getBranch().getBranchId()))
                .filter(s -> s.getShift() != null && Boolean.TRUE.equals(s.getShift().getIsActive()))
                .toList();
        List<LeaveRequest> leaves = leaveRequests.findApprovedForStaffOnDate(doctorId, date, LeaveStatus.APPROVED);
        List<TimeWindow> windows = new ArrayList<>();
        for (StaffSchedule sched : schedules) {
            boolean onLeave = leaves.stream().anyMatch(l ->
                    l.getShift() == null || l.getShift().getShiftId().equals(sched.getShift().getShiftId()));
            if (onLeave) {
                continue;
            }
            LocalTime sStart = sched.getShift().getStartTime();
            LocalTime sEnd = sched.getShift().getEndTime();
            if (sStart == null || sEnd == null || !sStart.isBefore(sEnd)) {
                continue;
            }
            LocalTime clippedStart = sStart.isBefore(sessionRange.start()) ? sessionRange.start() : sStart;
            LocalTime clippedEnd = sEnd.isAfter(sessionRange.end()) ? sessionRange.end() : sEnd;
            if (clippedStart.isBefore(clippedEnd)) {
                windows.add(new TimeWindow(clippedStart, clippedEnd));
            }
        }
        return windows;
    }

    private boolean fitsAnyWindow(LocalTime start, LocalTime end, List<TimeWindow> windows) {
        return windows.stream().anyMatch(w -> !start.isBefore(w.start()) && !end.isAfter(w.end()));
    }

    private OverlapCheckResult invalidResult(String error, int durationMinutes, LocalTime requestedStart) {
        return new OverlapCheckResult(
                false,
                error,
                durationMinutes,
                requestedStart,
                requestedStart != null ? requestedStart.plusMinutes(durationMinutes) : null,
                false,
                "INVALID",
                null,
                0,
                0,
                0,
                false,
                "NOT_APPLICABLE",
                null,
                0,
                null,
                List.of(),
                List.of(),
                List.of(),
                null
        );
    }
}
