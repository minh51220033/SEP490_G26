package com.cvdcms.service;

import com.cvdcms.entity.Appointment;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.LeaveRequest;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.StaffSchedule;
import com.cvdcms.entity.Visit;
import com.cvdcms.entity.WorkShift;
import com.cvdcms.entity.enums.AppointmentActorType;
import com.cvdcms.entity.enums.AppointmentStatus;
import com.cvdcms.entity.enums.LeaveStatus;
import com.cvdcms.entity.enums.PreferredSession;
import com.cvdcms.entity.enums.ScheduleStatus;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.repository.AppointmentPolicyRepository;
import com.cvdcms.repository.AppointmentSessionWindowRepository;
import com.cvdcms.repository.LeaveRequestRepository;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.StaffScheduleRepository;
import com.cvdcms.repository.VisitRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Quản lý khung giờ khám động (Dynamic Available Slots), kiểm tra xung đột Half-Open [start, end),
 * giải phóng slot theo Grace Period 15 phút & actual_end_time, và khóa bi quan chống Race Condition.
 */
@Service
public class AppointmentSlotService {
    public static final int GRACE_PERIOD_MINUTES = 15;
    public static final int NO_SHOW_CUTOFF_MINUTES = 30;
    public static final int SUGGESTED_STEP_MINUTES = 5;
    private static final int MAX_LOCK_RETRIES = 2;

    /**
     * Chỉ các trạng thái đang chiếm giữ khung giờ của bác sĩ.
     * CANCELLED, NO_SHOW và COMPLETED được loại trừ để giải phóng slot.
     */
    public static final List<AppointmentStatus> OCCUPYING = List.of(
            AppointmentStatus.PENDING,
            AppointmentStatus.CONFIRMED,
            AppointmentStatus.CHECKED_IN
    );

    private final AppointmentPolicyRepository appointments;
    private final AppointmentSessionWindowRepository windows;
    private final AppointmentPolicyService policy;
    private final StaffScheduleRepository staffSchedules;
    private final LeaveRequestRepository leaveRequests;
    private final VisitRepository visits;
    private final StaffProfileRepository staffProfiles;
    private Clock clock = Clock.system(AppointmentPolicyService.CLINIC_ZONE);

    public AppointmentSlotService(
            AppointmentPolicyRepository appointments,
            AppointmentSessionWindowRepository windows,
            AppointmentPolicyService policy
    ) {
        this(appointments, windows, policy, null, null, null, null);
    }

    @Autowired
    public AppointmentSlotService(
            AppointmentPolicyRepository appointments,
            AppointmentSessionWindowRepository windows,
            AppointmentPolicyService policy,
            @Autowired(required = false) StaffScheduleRepository staffSchedules,
            @Autowired(required = false) LeaveRequestRepository leaveRequests,
            @Autowired(required = false) VisitRepository visits,
            @Autowired(required = false) StaffProfileRepository staffProfiles
    ) {
        this.appointments = appointments;
        this.windows = windows;
        this.policy = policy;
        this.staffSchedules = staffSchedules;
        this.leaveRequests = leaveRequests;
        this.visits = visits;
        this.staffProfiles = staffProfiles;
    }

    public void setClock(Clock clock) {
        if (clock != null) {
            this.clock = clock;
        }
    }

    public record Slot(LocalTime time, int remaining) { }

    /** Khoảng thời gian trống thực tế liên tục [start, end). */
    public record TimeInterval(LocalTime start, LocalTime end) { }

    /** Khung giờ đề xuất cho khách hàng bắt đầu từ startTime trong một khoảng trống hợp lệ. */
    public record SuggestedSlot(LocalTime startTime, LocalTime endTime, Long branchId) { }

    public record DoctorAvailabilityResult(
            Long doctorId,
            LocalDate date,
            int requiredDurationMinutes,
            List<TimeInterval> availableIntervals,
            List<SuggestedSlot> suggestedSlots
    ) { }

    /**
     * Kiểm tra hai khoảng nửa mở [aStart, aEnd) và [bStart, bEnd) có giao nhau hay không.
     * Ví dụ: [08:00, 08:30) và [08:30, 09:00) -> 08:00 < 09:00 && 08:30 < 08:30 (false) -> KHÔNG overlap.
     */
    public static boolean intervalsOverlap(LocalTime aStart, LocalTime aEnd, LocalTime bStart, LocalTime bEnd) {
        if (aStart == null || aEnd == null || bStart == null || bEnd == null) {
            return false;
        }
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }

    /**
     * API chuẩn tìm khung giờ trống động theo bác sĩ, ngày và thời lượng dịch vụ (hỗ trợ 20, 45, 60 phút...).
     */
    public List<LocalTime> getAvailableSlots(Long doctorId, LocalDate date, Integer requiredDuration) {
        DoctorAvailabilityResult result = getDoctorAvailability(null, doctorId, date, requiredDuration, null, LocalDateTime.now(clock));
        return result.suggestedSlots().stream().map(SuggestedSlot::startTime).toList();
    }

    public DoctorAvailabilityResult getDoctorAvailability(
            Long branchId,
            Long doctorId,
            LocalDate date,
            Integer requiredDuration,
            Long excludeAppointmentId
    ) {
        return getDoctorAvailability(branchId, doctorId, date, requiredDuration, excludeAppointmentId, LocalDateTime.now(clock));
    }

    public DoctorAvailabilityResult getDoctorAvailability(
            Long branchId,
            Long doctorId,
            LocalDate date,
            Integer requiredDuration,
            Long excludeAppointmentId,
            LocalDateTime referenceNow
    ) {
        int duration = requiredDuration == null ? 30 : requiredDuration;
        validateDynamicDuration(duration);
        if (doctorId == null || date == null || staffSchedules == null) {
            return new DoctorAvailabilityResult(doctorId, date, duration, List.of(), List.of());
        }
        LocalDateTime nowDt = referenceNow != null ? referenceNow : LocalDateTime.now(clock);
        LocalDate today = nowDt.toLocalDate();
        if (date.isBefore(today)) {
            return new DoctorAvailabilityResult(doctorId, date, duration, List.of(), List.of());
        }

        // Query 1: Lấy toàn bộ ca trực SCHEDULED của bác sĩ trong ngày
        List<StaffSchedule> rawSchedules = staffSchedules.findDoctorSchedulesForDate(doctorId, date, ScheduleStatus.SCHEDULED);
        List<StaffSchedule> schedules = rawSchedules.stream()
                .filter(s -> branchId == null || (s.getBranch() != null && branchId.equals(s.getBranch().getBranchId())))
                .filter(s -> s.getBranch() != null && Boolean.TRUE.equals(s.getBranch().getIsActive()))
                .filter(s -> s.getShift() != null && Boolean.TRUE.equals(s.getShift().getIsActive()))
                .filter(s -> s.getStaff() == null || s.getStaff().getStatus() == null || s.getStaff().getStatus() == StaffStatus.ACTIVE)
                .toList();
        if (schedules.isEmpty()) {
            // Tuyệt đối không fallback sang bác sĩ chưa được phân ca
            return new DoctorAvailabilityResult(doctorId, date, duration, List.of(), List.of());
        }

        // Query 2: Lấy toàn bộ đơn nghỉ phép đã duyệt của bác sĩ trong ngày
        List<LeaveRequest> approvedLeaves = leaveRequests != null
                ? leaveRequests.findApprovedForStaffOnDate(doctorId, date, LeaveStatus.APPROVED)
                : List.of();

        // Query 3: Lấy toàn bộ lịch hẹn có khả năng chiếm chỗ của bác sĩ trong ngày
        List<Appointment> occupyingAppointments = appointments.findDoctorAppointmentsForDate(doctorId, date, OCCUPYING);

        // Query 4 (batch): Lấy Visit của các lịch CHECKED_IN để kiểm tra actual_end_time (giải phóng sớm)
        Map<Long, Visit> visitByApptId = loadVisitsForCheckedInAppointments(occupyingAppointments);

        return computeAvailabilityFromLoadedData(
                doctorId, branchId, date, duration, excludeAppointmentId, nowDt,
                schedules, approvedLeaves, occupyingAppointments, visitByApptId
        );
    }

    private DoctorAvailabilityResult computeAvailabilityFromLoadedData(
            Long doctorId,
            Long branchId,
            LocalDate date,
            int duration,
            Long excludeAppointmentId,
            LocalDateTime nowDt,
            List<StaffSchedule> schedules,
            List<LeaveRequest> approvedLeaves,
            List<Appointment> occupyingAppointments,
            Map<Long, Visit> visitByApptId
    ) {
        LocalDate today = nowDt.toLocalDate();
        LocalTime nowTime = nowDt.toLocalTime();

        List<TimeInterval> workingIntervals = new ArrayList<>();
        Map<Long, Long> intervalBranchMap = new HashMap<>();

        for (StaffSchedule schedule : schedules) {
            WorkShift shift = schedule.getShift();
            Branch br = schedule.getBranch();
            if (shift == null || shift.getStartTime() == null || shift.getEndTime() == null) {
                continue;
            }
            Long schBranchId = br != null ? br.getBranchId() : branchId;
            boolean fullDayLeaveForBranch = approvedLeaves.stream().anyMatch(l ->
                    l.getShift() == null && (l.getBranch() == null || schBranchId == null || schBranchId.equals(l.getBranch().getBranchId())));
            if (fullDayLeaveForBranch) {
                continue;
            }

            LocalTime sStart = shift.getStartTime();
            LocalTime sEnd = shift.getEndTime();
            if (br != null && br.getOpeningTime() != null && br.getOpeningTime().isAfter(sStart)) {
                sStart = br.getOpeningTime();
            }
            if (br != null && br.getClosingTime() != null && br.getClosingTime().isBefore(sEnd)) {
                sEnd = br.getClosingTime();
            }
            if (date.equals(today)) {
                if (!nowTime.isBefore(sEnd)) {
                    continue;
                }
                if (nowTime.isAfter(sStart)) {
                    sStart = nowTime;
                }
            }
            if (sStart.isBefore(sEnd)) {
                workingIntervals.add(new TimeInterval(sStart, sEnd));
                if (schBranchId != null) {
                    intervalBranchMap.put(sStart.toSecondOfDay() * 100000L + sEnd.toSecondOfDay(), schBranchId);
                }
            }
        }

        if (workingIntervals.isEmpty()) {
            return new DoctorAvailabilityResult(doctorId, date, duration, List.of(), List.of());
        }

        List<TimeInterval> blockedIntervals = new ArrayList<>();

        // Trừ các khoảng nghỉ phép theo ca
        for (LeaveRequest leave : approvedLeaves) {
            if (leave.getShift() != null && leave.getShift().getStartTime() != null && leave.getShift().getEndTime() != null) {
                if (leave.getShift().getStartTime().isBefore(leave.getShift().getEndTime())) {
                    blockedIntervals.add(new TimeInterval(leave.getShift().getStartTime(), leave.getShift().getEndTime()));
                }
            }
        }

        // Trừ các khoảng chiếm dụng của Appointment theo quy tắc [start, end)
        for (Appointment appt : occupyingAppointments) {
            if (excludeAppointmentId != null && Objects.equals(appt.getAppointmentId(), excludeAppointmentId)) {
                continue;
            }
            if (appt.getExpectedTime() == null) {
                continue;
            }
            LocalTime apptStart = appt.getExpectedTime();
            int apptDuration = appt.getDurationMinutes() != null && appt.getDurationMinutes() > 0
                    ? appt.getDurationMinutes()
                    : duration(appt.getService());
            LocalTime apptEnd = appt.getExpectedEndTime() != null
                    ? appt.getExpectedEndTime()
                    : apptStart.plusMinutes(apptDuration);

            // Quy tắc Grace Period 15 phút: quá 15 phút mà chưa Check-in -> giải phóng quyền giữ slot kể từ hiện tại
            if (date.equals(today)
                    && (appt.getStatus() == AppointmentStatus.PENDING || appt.getStatus() == AppointmentStatus.CONFIRMED)
                    && nowTime.isAfter(apptStart.plusMinutes(GRACE_PERIOD_MINUTES))) {
                continue;
            }

            // Quy tắc giải phóng sớm khi bác sĩ hoàn tất khám lâm sàng (actual_end_time)
            if (appt.getStatus() == AppointmentStatus.CHECKED_IN) {
                Visit linkedVisit = visitByApptId.get(appt.getAppointmentId());
                if (linkedVisit != null && linkedVisit.getActualEndTime() != null) {
                    LocalDate actualEndDate = linkedVisit.getActualEndTime().toLocalDate();
                    if (actualEndDate.isBefore(date)) {
                        continue;
                    }
                    if (actualEndDate.equals(date)) {
                        LocalTime actualEnd = linkedVisit.getActualEndTime().toLocalTime();
                        if (!actualEnd.isAfter(apptStart)) {
                            continue;
                        }
                        if (actualEnd.isBefore(apptEnd)) {
                            apptEnd = actualEnd;
                        }
                    }
                }
            }

            if (apptStart.isBefore(apptEnd)) {
                blockedIntervals.add(new TimeInterval(apptStart, apptEnd));
            }
        }

        List<TimeInterval> mergedWorking = mergeIntervals(workingIntervals);
        List<TimeInterval> freeIntervals = subtractIntervals(mergedWorking, blockedIntervals).stream()
                .filter(iv -> Duration.between(iv.start(), iv.end()).toMinutes() >= duration)
                .toList();

        List<SuggestedSlot> suggestedSlots = new ArrayList<>();
        for (TimeInterval iv : freeIntervals) {
            LocalTime cursor = alignUpToStep(iv.start(), SUGGESTED_STEP_MINUTES);
            while (!cursor.plusMinutes(duration).isAfter(iv.end())) {
                Long matchedBranch = resolveBranchForInterval(schedules, cursor, cursor.plusMinutes(duration), branchId);
                suggestedSlots.add(new SuggestedSlot(cursor, cursor.plusMinutes(duration), matchedBranch));
                cursor = cursor.plusMinutes(SUGGESTED_STEP_MINUTES);
            }
        }

        return new DoctorAvailabilityResult(doctorId, date, duration, freeIntervals, suggestedSlots);
    }

    /**
     * Khóa tài nguyên chung của bác sĩ (StaffProfile row) trong transaction READ_COMMITTED,
     * đọc lại lịch trực/nghỉ phép/lịch hẹn với locking read và kiểm tra lại overlap [start, end)
     * trước khi lưu để chặn triệt để Race Condition trên MySQL 8.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LocalTime lockAndValidateDoctorSlot(
            Long branchId,
            Long doctorId,
            LocalDate date,
            LocalTime requestedStart,
            int requiredDuration,
            Long excludeAppointmentId
    ) {
        if (doctorId == null || date == null || requestedStart == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thiếu thông tin bác sĩ, ngày hoặc giờ hẹn.");
        }
        validateDynamicDuration(requiredDuration);

        return executeWithDeadlockRetry(() -> {
            if (staffProfiles != null) {
                StaffProfile lockedDoctor = staffProfiles.lockById(doctorId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bác sĩ không tồn tại."));
                if (lockedDoctor.getStatus() != StaffStatus.ACTIVE) {
                    throw new SlotAlreadyTakenException("Bác sĩ hiện không ở trạng thái hoạt động.");
                }
            }
            if (staffSchedules == null) {
                return requestedStart;
            }
            // Khóa toàn bộ ca trực của bác sĩ trong ngày theo thứ tự scheduleId tăng dần để tránh deadlock
            staffSchedules.lockDoctorSchedulesForDate(doctorId, date, ScheduleStatus.SCHEDULED);

            LocalDateTime nowDt = LocalDateTime.now(clock);
            List<StaffSchedule> schedules = staffSchedules.findDoctorSchedulesForDate(doctorId, date, ScheduleStatus.SCHEDULED).stream()
                    .filter(s -> branchId == null || (s.getBranch() != null && branchId.equals(s.getBranch().getBranchId())))
                    .filter(s -> s.getBranch() != null && Boolean.TRUE.equals(s.getBranch().getIsActive()))
                    .filter(s -> s.getShift() != null && Boolean.TRUE.equals(s.getShift().getIsActive()))
                    .toList();
            if (schedules.isEmpty()) {
                throw new SlotAlreadyTakenException("Bác sĩ không có ca làm việc hợp lệ vào ngày " + date + ".");
            }

            List<LeaveRequest> approvedLeaves = leaveRequests != null
                    ? leaveRequests.findApprovedForStaffOnDate(doctorId, date, LeaveStatus.APPROVED)
                    : List.of();
            // Dùng locking read (FOR UPDATE) để tránh stale snapshot trên MySQL REPEATABLE_READ
            List<Appointment> occupying = appointments.lockDoctorAppointmentsForDate(doctorId, date, OCCUPYING);
            Map<Long, Visit> visitByApptId = loadVisitsForCheckedInAppointments(occupying);

            DoctorAvailabilityResult availability = computeAvailabilityFromLoadedData(
                    doctorId, branchId, date, requiredDuration, excludeAppointmentId, nowDt,
                    schedules, approvedLeaves, occupying, visitByApptId
            );

            LocalTime requestedEnd = requestedStart.plusMinutes(requiredDuration);
            boolean fits = availability.availableIntervals().stream()
                    .anyMatch(iv -> !requestedStart.isBefore(iv.start()) && !requestedEnd.isAfter(iv.end()));
            if (!fits) {
                throw new SlotAlreadyTakenException(
                        "Khung giờ " + requestedStart + " - " + requestedEnd + " của bác sĩ đã được đặt hoặc không còn khả dụng."
                );
            }
            return requestedStart;
        });
    }

    /**
     * Tự động phân bổ bác sĩ khả dụng ít tải nhất cho khung giờ [requestedStart, requestedStart + duration)
     * khi người dùng chọn "Không yêu cầu bác sĩ đích danh" (phương án B).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public StaffProfile autoAssignDoctorForSlot(
            Branch branch,
            LocalDate date,
            PreferredSession session,
            LocalTime requestedStart,
            int requiredDuration,
            Long excludeAppointmentId
    ) {
        if (branch == null || date == null || requestedStart == null || staffSchedules == null || staffProfiles == null) {
            return null;
        }
        validateDynamicDuration(requiredDuration);
        AppointmentPolicyService.Availability branchAvailability = policy.availability(
                branch, date, session, excludeAppointmentId, AppointmentActorType.PATIENT);
        if (branchAvailability == null || !branchAvailability.available() || branchAvailability.doctors().isEmpty()) {
            throw new SlotAlreadyTakenException("Không còn bác sĩ khả dụng trong ca khám đã chọn.");
        }

        // Batch load lịch hẹn của buổi để đếm tải cân bằng, tránh N+1 query
        List<Appointment> sessionAppointments = appointments.findOccupyingForSession(
                branch.getBranchId(), date, session, OCCUPYING);
        Map<Long, Long> loadByDoctor = new HashMap<>();
        for (Appointment a : sessionAppointments) {
            if (a.getDoctor() != null && !Objects.equals(a.getAppointmentId(), excludeAppointmentId)) {
                loadByDoctor.merge(a.getDoctor().getStaffId(), 1L, Long::sum);
            }
        }

        List<AppointmentPolicyService.DoctorOption> candidates = branchAvailability.doctors().stream()
                .sorted(Comparator.comparingLong((AppointmentPolicyService.DoctorOption d) -> loadByDoctor.getOrDefault(d.id(), 0L))
                        .thenComparingLong(AppointmentPolicyService.DoctorOption::id))
                .toList();

        for (AppointmentPolicyService.DoctorOption candidate : candidates) {
            try {
                lockAndValidateDoctorSlot(branch.getBranchId(), candidate.id(), date, requestedStart, requiredDuration, excludeAppointmentId);
                return staffProfiles.findById(candidate.id()).orElse(null);
            } catch (SlotAlreadyTakenException ignored) {
                // Ứng viên vừa bận hoặc không cover đủ khung giờ -> thử bác sĩ tiếp theo
            }
        }

        throw new SlotAlreadyTakenException(
                "Không còn bác sĩ nào trống lịch trong khung giờ " + requestedStart + " - " + requestedStart.plusMinutes(requiredDuration) + "."
        );
    }

    public List<Slot> available(Branch branch, LocalDate date, PreferredSession session,
            Long doctorId, int durationMinutes, Long excludeId, AppointmentActorType actorType) {
        validateDuration(durationMinutes);
        if (branch == null || date == null || session == null || session == PreferredSession.ANY) return List.of();
        Range range = range(branch, session);
        if (!range.active || !range.start.isBefore(range.end)) return List.of();
        LocalDateTime nowDt = LocalDateTime.now(clock);
        if (date.equals(nowDt.toLocalDate())) {
            LocalTime now = nowDt.toLocalTime();
            if (branch.getClosingTime() != null && !now.isBefore(branch.getClosingTime())) {
                return List.of();
            }
            if (!now.isBefore(range.end)) {
                return List.of();
            }
        }

        // Nếu có doctorId cụ thể và đã kết nối đầy đủ repository lịch làm việc -> dùng thuật toán khoảng động chính xác
        if (doctorId != null && staffSchedules != null) {
            DoctorAvailabilityResult doctorAvail = getDoctorAvailability(
                    branch.getBranchId(), doctorId, date, durationMinutes, excludeId, nowDt);
            return doctorAvail.suggestedSlots().stream()
                    .filter(s -> !s.startTime().isBefore(range.start) && !s.endTime().isAfter(range.end))
                    .map(s -> new Slot(s.startTime(), 1))
                    .toList();
        }

        AppointmentPolicyService.Availability availability = policy.availability(branch, date, session,
                excludeId, actorType);
        if (availability == null || !availability.available()
                || doctorId != null && availability.doctors().stream().noneMatch(d -> doctorId.equals(d.id())))
            return List.of();

        List<Appointment> booked = appointments.findOccupyingForSession(branch.getBranchId(), date, session, OCCUPYING)
                .stream().filter(a -> excludeId == null || !Objects.equals(a.getAppointmentId(), excludeId)).toList();
        Map<Long, Visit> visitByApptId = loadVisitsForCheckedInAppointments(booked);
        int capacity = Math.min(Math.max(1, availability.doctors().size()), availability.remainingBranch() + booked.size());
        if (capacity == 0) return List.of();

        int step = (durationMinutes % 15 != 0) ? SUGGESTED_STEP_MINUTES : 15;
        List<Slot> result = new ArrayList<>();
        for (LocalTime time = range.start; !time.plusMinutes(durationMinutes).isAfter(range.end);
                time = time.plusMinutes(step)) {
            if (date.equals(nowDt.toLocalDate())
                    && !time.isAfter(nowDt.toLocalTime())) continue;
            LocalTime end = time.plusMinutes(durationMinutes);
            final LocalTime start = time;
            long overlapping = booked.stream()
                    .filter(a -> overlapsWithRelease(a, start, end, date, nowDt, visitByApptId))
                    .count();
            boolean doctorBusy = doctorId != null && booked.stream()
                    .anyMatch(a -> overlapsWithRelease(a, start, end, date, nowDt, visitByApptId)
                            && a.getDoctor() != null && doctorId.equals(a.getDoctor().getStaffId()));
            int remaining = Math.max(0, capacity - (int) overlapping);
            if (!doctorBusy && remaining > 0) result.add(new Slot(time, remaining));
        }
        return result;
    }

    public LocalTime resolve(Branch branch, LocalDate date, PreferredSession session,
            Long doctorId, int durationMinutes, Long excludeId, LocalTime requested,
            AppointmentActorType actorType) {
        List<Slot> options = available(branch, date, session, doctorId, durationMinutes, excludeId, actorType);
        if (requested == null) {
            if (options.isEmpty()) throw new SlotAlreadyTakenException(
                    "Không còn khung giờ phù hợp. Vui lòng chọn ngày, buổi hoặc bác sĩ khác.");
            return options.get(0).time();
        }
        if (options.stream().noneMatch(slot -> slot.time().equals(requested)))
            throw new SlotAlreadyTakenException(
                    "Khung giờ đã hết chỗ hoặc không phù hợp với thời lượng khám. Vui lòng tải lại giờ trống.");
        return requested;
    }

    /**
     * Kiểm tra giờ hẹn linh động trong ca trực, hỗ trợ thời lượng động (20, 45, 60 phút...)
     * và khóa bi quan khi có bác sĩ chỉ định.
     */
    public LocalTime resolveFlexibly(Branch branch, LocalDate date, PreferredSession session,
            Long doctorId, int durationMinutes, Long excludeId, LocalTime requested,
            AppointmentActorType actorType) {
        validateDuration(durationMinutes);
        Range range = range(branch, session);
        if (!range.active) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Buổi khám không hoạt động tại chi nhánh.");
        }

        LocalDateTime nowDt = LocalDateTime.now(clock);
        if (requested == null) {
            if (date != null && date.equals(nowDt.toLocalDate())) {
                LocalTime now = nowDt.toLocalTime();
                if (branch != null && branch.getClosingTime() != null && !now.isBefore(branch.getClosingTime())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Phòng khám đã đóng cửa trong ngày hôm nay.");
                }
                if (!now.isBefore(range.end)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Ca khám trong ngày đã kết thúc.");
                }
            }
            return null;
        }
        if (requested.isBefore(range.start) || requested.isAfter(range.end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Giờ hẹn " + requested + " nằm ngoài khung giờ làm việc của ca " + session + " (" + range.start + " - " + range.end + ").");
        }
        if (requested.plusMinutes(durationMinutes).isAfter(range.end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Thời lượng khám (" + durationMinutes + " phút) bắt đầu từ " + requested
                            + " sẽ vượt quá giờ kết thúc ca " + session + " (" + range.end + "). Vui lòng chọn giờ sớm hơn.");
        }
        if (requested.getMinute() % SUGGESTED_STEP_MINUTES != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Giờ hẹn dự kiến phải theo bước 5 phút (ví dụ: :00, :05, :10, :15, :20...).");
        }

        if (date != null && date.equals(nowDt.toLocalDate())
                && !requested.isAfter(nowDt.toLocalTime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Giờ hẹn " + requested + " đã qua so với thời gian hiện tại.");
        }

        if (branch != null && date != null && session != null) {
            if (doctorId != null && staffSchedules != null) {
                lockAndValidateDoctorSlot(branch.getBranchId(), doctorId, date, requested, durationMinutes, excludeId);
                return requested;
            }
            List<Appointment> booked = appointments.findOccupyingForSession(branch.getBranchId(), date, session, OCCUPYING)
                    .stream().filter(a -> excludeId == null || !Objects.equals(a.getAppointmentId(), excludeId)).toList();
            Map<Long, Visit> visitByApptId = loadVisitsForCheckedInAppointments(booked);
            LocalTime end = requested.plusMinutes(durationMinutes);
            if (doctorId != null) {
                boolean doctorBusy = booked.stream()
                        .anyMatch(a -> overlapsWithRelease(a, requested, end, date, nowDt, visitByApptId)
                                && a.getDoctor() != null && doctorId.equals(a.getDoctor().getStaffId()));
                if (doctorBusy) {
                    throw new SlotAlreadyTakenException(
                            "Bác sĩ đã có lịch hẹn khác trong khung giờ " + requested + " - " + end + ". Vui lòng chọn giờ hoặc bác sĩ khác.");
                }
            }
            AppointmentPolicyService.Availability availability = policy.availability(branch, date, session, excludeId, actorType);
            if (availability != null && availability.available()) {
                int capacity = Math.min(Math.max(1, availability.doctors().size()), availability.remainingBranch() + booked.size());
                long overlapping = booked.stream()
                        .filter(a -> overlapsWithRelease(a, requested, end, date, nowDt, visitByApptId))
                        .count();
                if (capacity > 0 && overlapping >= capacity) {
                    throw new SlotAlreadyTakenException(
                            "Khung giờ " + requested + " - " + end + " đã đạt tối đa công suất tiếp nhận của ca trực.");
                }
            }
        }
        return requested;
    }

    public static int duration(com.cvdcms.entity.Service service) {
        return service == null || service.getEstimatedDurationMinutes() == null
                ? 30 : service.getEstimatedDurationMinutes();
    }

    private static boolean overlapsWithRelease(
            Appointment appointment,
            LocalTime start,
            LocalTime end,
            LocalDate date,
            LocalDateTime nowDt,
            Map<Long, Visit> visitByApptId
    ) {
        if (appointment.getExpectedTime() == null) return false;
        LocalTime apptStart = appointment.getExpectedTime();
        int length = appointment.getDurationMinutes() == null ? duration(appointment.getService())
                : appointment.getDurationMinutes();
        LocalTime apptEnd = appointment.getExpectedEndTime() != null
                ? appointment.getExpectedEndTime()
                : apptStart.plusMinutes(length);

        if (date != null && date.equals(nowDt.toLocalDate())
                && (appointment.getStatus() == AppointmentStatus.PENDING || appointment.getStatus() == AppointmentStatus.CONFIRMED)
                && nowDt.toLocalTime().isAfter(apptStart.plusMinutes(GRACE_PERIOD_MINUTES))) {
            return false;
        }
        if (appointment.getStatus() == AppointmentStatus.CHECKED_IN && visitByApptId != null) {
            Visit v = visitByApptId.get(appointment.getAppointmentId());
            if (v != null && v.getActualEndTime() != null && date != null && v.getActualEndTime().toLocalDate().equals(date)) {
                LocalTime actualEnd = v.getActualEndTime().toLocalTime();
                if (!actualEnd.isAfter(apptStart)) {
                    return false;
                }
                if (actualEnd.isBefore(apptEnd)) {
                    apptEnd = actualEnd;
                }
            }
        }
        return intervalsOverlap(apptStart, apptEnd, start, end);
    }

    private Map<Long, Visit> loadVisitsForCheckedInAppointments(List<Appointment> appts) {
        if (visits == null || appts == null || appts.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> checkedInIds = appts.stream()
                .filter(a -> a.getStatus() == AppointmentStatus.CHECKED_IN && a.getAppointmentId() != null)
                .map(Appointment::getAppointmentId)
                .distinct()
                .toList();
        if (checkedInIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Visit> linkedVisits = visits.findByAppointmentIdIn(checkedInIds);
        if (linkedVisits == null || linkedVisits.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Visit> map = new HashMap<>();
        for (Visit v : linkedVisits) {
            if (v.getAppointmentId() != null) {
                map.putIfAbsent(v.getAppointmentId(), v);
            }
        }
        return map;
    }

    private static List<TimeInterval> mergeIntervals(List<TimeInterval> intervals) {
        if (intervals.isEmpty()) return List.of();
        List<TimeInterval> sorted = intervals.stream()
                .filter(iv -> iv.start() != null && iv.end() != null && iv.start().isBefore(iv.end()))
                .sorted(Comparator.comparing(TimeInterval::start).thenComparing(TimeInterval::end))
                .toList();
        if (sorted.isEmpty()) return List.of();
        List<TimeInterval> merged = new ArrayList<>();
        TimeInterval current = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            TimeInterval next = sorted.get(i);
            if (!next.start().isAfter(current.end())) {
                LocalTime maxEnd = next.end().isAfter(current.end()) ? next.end() : current.end();
                current = new TimeInterval(current.start(), maxEnd);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private static List<TimeInterval> subtractIntervals(List<TimeInterval> available, List<TimeInterval> blocked) {
        if (available.isEmpty() || blocked.isEmpty()) return available;
        List<TimeInterval> current = new ArrayList<>(available);
        for (TimeInterval block : mergeIntervals(blocked)) {
            List<TimeInterval> next = new ArrayList<>();
            for (TimeInterval free : current) {
                if (!intervalsOverlap(free.start(), free.end(), block.start(), block.end())) {
                    next.add(free);
                    continue;
                }
                if (free.start().isBefore(block.start())) {
                    next.add(new TimeInterval(free.start(), block.start()));
                }
                if (block.end().isBefore(free.end())) {
                    next.add(new TimeInterval(block.end(), free.end()));
                }
            }
            current = next;
        }
        return current;
    }

    private static LocalTime alignUpToStep(LocalTime time, int stepMinutes) {
        int totalMinutes = time.getHour() * 60 + time.getMinute();
        int remainder = totalMinutes % stepMinutes;
        if (remainder == 0 && time.getSecond() == 0 && time.getNano() == 0) {
            return time.withSecond(0).withNano(0);
        }
        int alignedMinutes = totalMinutes - remainder + stepMinutes;
        if (alignedMinutes >= 24 * 60) {
            return LocalTime.of(23, 55);
        }
        return LocalTime.of(alignedMinutes / 60, alignedMinutes % 60);
    }

    private static Long resolveBranchForInterval(List<StaffSchedule> schedules, LocalTime start, LocalTime end, Long fallback) {
        for (StaffSchedule s : schedules) {
            if (s.getShift() != null && s.getBranch() != null
                    && !start.isBefore(s.getShift().getStartTime())
                    && !end.isAfter(s.getShift().getEndTime())) {
                return s.getBranch().getBranchId();
            }
        }
        return fallback;
    }

    private <T> T executeWithDeadlockRetry(java.util.function.Supplier<T> action) {
        int attempt = 0;
        while (true) {
            try {
                return action.get();
            } catch (CannotAcquireLockException | DeadlockLoserDataAccessException ex) {
                if (++attempt > MAX_LOCK_RETRIES) {
                    throw new SlotAlreadyTakenException("Hệ thống đang xử lý đồng thời, vui lòng thử lại.");
                }
                try {
                    Thread.sleep(30L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SlotAlreadyTakenException("Yêu cầu đặt lịch bị gián đoạn, vui lòng thử lại.");
                }
            } catch (PessimisticLockingFailureException ex) {
                if (++attempt > MAX_LOCK_RETRIES) {
                    throw new SlotAlreadyTakenException("Khung giờ đang có người khác thao tác, vui lòng thử lại.");
                }
            }
        }
    }

    private Range range(Branch branch, PreferredSession session) {
        AppointmentPolicyService.SessionRange sr = policy != null ? policy.sessionRange(branch, session) : null;
        if (sr != null) {
            return new Range(sr.start(), sr.end(), sr.active());
        }
        return defaultRange(branch, session);
    }

    private Range defaultRange(Branch branch, PreferredSession session) {
        if (session == null || session == PreferredSession.ANY) {
            return new Range(LocalTime.of(8, 0), LocalTime.of(8, 0), false);
        }
        return switch (session) {
            case MORNING -> new Range(LocalTime.of(8, 0), LocalTime.of(12, 0), true);
            case AFTERNOON -> new Range(LocalTime.of(13, 30), LocalTime.of(17, 30), true);
            case EVENING -> {
                LocalTime end = (branch != null && branch.getClosingTime() != null)
                        ? branch.getClosingTime() : LocalTime.of(20, 0);
                yield new Range(LocalTime.of(18, 0), end, end.isAfter(LocalTime.of(18, 0)));
            }
            case ANY -> new Range(LocalTime.of(8, 0), LocalTime.of(8, 0), false);
        };
    }

    private static void validateDuration(int minutes) {
        if (minutes < 5 || minutes > 240 || minutes % 5 != 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Thời lượng dịch vụ phải từ 5 đến 240 phút và là bội số của 5 phút.");
    }

    private static void validateDynamicDuration(int minutes) {
        if (minutes < 5 || minutes > 240)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Thời lượng dịch vụ phải từ 5 đến 240 phút.");
    }

    private record Range(LocalTime start, LocalTime end, boolean active) { }
}
