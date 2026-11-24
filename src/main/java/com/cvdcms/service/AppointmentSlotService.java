package com.cvdcms.service;

import com.cvdcms.entity.Appointment;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.ClinicEquipment;
import com.cvdcms.entity.DentalChair;
import com.cvdcms.entity.LeaveRequest;
import com.cvdcms.entity.ServiceResourceRequirement;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.StaffSchedule;
import com.cvdcms.entity.Visit;
import com.cvdcms.entity.WorkShift;
import com.cvdcms.entity.enums.AppointmentActorType;
import com.cvdcms.entity.enums.AppointmentStatus;
import com.cvdcms.entity.enums.ChairStatus;
import com.cvdcms.entity.enums.EquipmentStatus;
import com.cvdcms.entity.enums.LeaveStatus;
import com.cvdcms.entity.enums.PreferredSession;
import com.cvdcms.entity.enums.ResourceRequirementKind;
import com.cvdcms.entity.enums.ScheduleStatus;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.repository.AppointmentPolicyRepository;
import com.cvdcms.repository.AppointmentSessionWindowRepository;
import com.cvdcms.repository.ClinicEquipmentRepository;
import com.cvdcms.repository.DentalChairRepository;
import com.cvdcms.repository.LeaveRequestRepository;
import com.cvdcms.repository.ServiceResourceRequirementRepository;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.StaffScheduleRepository;
import com.cvdcms.repository.VisitRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Quản lý khung giờ khám động (Dynamic Available Slots), kiểm tra xung đột Half-Open [start, end),
 * giải phóng slot theo Grace Period 15 phút & actual_end_time, và khóa bi quan chống Race Condition.
 */
@Service
public class AppointmentSlotService {
    public static final int GRACE_PERIOD_MINUTES = 15;
    public static final int NO_SHOW_CUTOFF_MINUTES = 30;
    public static final int SUGGESTED_STEP_MINUTES = 5;
    public static final int DEFAULT_CONCURRENT_ARRIVALS_PER_DOCTOR = 1;

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
    private int maxConcurrentPerDoctorSlot = DEFAULT_CONCURRENT_ARRIVALS_PER_DOCTOR;
    private Clock clock = Clock.system(AppointmentPolicyService.CLINIC_ZONE);
    @PersistenceContext
    private EntityManager entityManager;

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

    public int getMaxConcurrentPerDoctorSlot() {
        return maxConcurrentPerDoctorSlot;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.DentalChairRepository dentalChairs;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.ClinicEquipmentRepository clinicEquipments;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.ServiceResourceRequirementRepository serviceRequirements;

    @org.springframework.beans.factory.annotation.Value("${appointments.doctor-concurrent-per-slot:1}")
    public void setMaxConcurrentPerDoctorSlot(int maxConcurrentPerDoctorSlot) {
        this.maxConcurrentPerDoctorSlot = Math.max(1, maxConcurrentPerDoctorSlot);
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

        List<TimeInterval> leaveBlocks = new ArrayList<>();
        for (LeaveRequest leave : approvedLeaves) {
            if (leave.getShift() != null && leave.getShift().getStartTime() != null && leave.getShift().getEndTime() != null) {
                if (leave.getShift().getStartTime().isBefore(leave.getShift().getEndTime())) {
                    leaveBlocks.add(new TimeInterval(leave.getShift().getStartTime(), leave.getShift().getEndTime()));
                }
            }
        }

        List<TimeInterval> netWorking = subtractIntervals(mergeIntervals(workingIntervals), leaveBlocks);
        if (netWorking.isEmpty()) {
            return new DoctorAvailabilityResult(doctorId, date, duration, List.of(), List.of());
        }

        List<TimeInterval> activeDoctorIntervals = buildEffectiveIntervals(
                occupyingAppointments, excludeAppointmentId, date, nowDt, visitByApptId);

        long totalWorkingMinutes = sumIntervalMinutes(netWorking);
        long occupiedMinutes = overlapMinutes(activeDoctorIntervals, netWorking);
        long remainingWorkloadMinutes = Math.max(0L, totalWorkingMinutes - occupiedMinutes);
        if (remainingWorkloadMinutes < duration) {
            return new DoctorAvailabilityResult(doctorId, date, duration, List.of(), List.of());
        }

        List<TimeInterval> freeIntervals = subtractFullConcurrencyIntervals(
                        netWorking, activeDoctorIntervals, maxConcurrentPerDoctorSlot).stream()
                .filter(iv -> Duration.between(iv.start(), iv.end()).toMinutes() >= duration)
                .toList();

        int step = (duration % 15 != 0) ? SUGGESTED_STEP_MINUTES : 15;
        List<SuggestedSlot> suggestedSlots = new ArrayList<>();
        for (TimeInterval iv : freeIntervals) {
            LocalTime cursor = alignUpToStep(iv.start(), SUGGESTED_STEP_MINUTES);
            while (!cursor.plusMinutes(duration).isAfter(iv.end())) {
                Long matchedBranch = resolveBranchForInterval(schedules, cursor, cursor.plusMinutes(duration), branchId);
                suggestedSlots.add(new SuggestedSlot(cursor, cursor.plusMinutes(duration), matchedBranch));
                cursor = cursor.plusMinutes(step);
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

        // Không retry deadlock/lock failure bên trong transaction đang mở hoặc đã rollback
        return validateDoctorSlotInternal(
                branchId, doctorId, date, requestedStart, requiredDuration, excludeAppointmentId, true);
    }

    private LocalTime validateDoctorSlotInternal(
            Long branchId,
            Long doctorId,
            LocalDate date,
            LocalTime requestedStart,
            int requiredDuration,
            Long excludeAppointmentId,
            boolean lockRows
    ) {
        if (lockRows && staffProfiles != null) {
            StaffProfile lockedDoctor = staffProfiles.lockById(doctorId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bác sĩ không tồn tại."));
            if (lockedDoctor.getStatus() != StaffStatus.ACTIVE) {
                throw new SlotAlreadyTakenException("Bác sĩ hiện không ở trạng thái hoạt động.");
            }
        }
        if (staffSchedules == null) {
            return requestedStart;
        }
        if (lockRows) {
            // Khóa toàn bộ ca trực của bác sĩ trong ngày theo thứ tự scheduleId tăng dần để tránh deadlock
            staffSchedules.lockDoctorSchedulesForDate(doctorId, date, ScheduleStatus.SCHEDULED);
        }

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
        // Dùng locking read (FOR UPDATE) khi lockRows=true để tránh stale snapshot trên MySQL REPEATABLE_READ
        List<Appointment> occupying = lockRows
                ? appointments.lockDoctorAppointmentsForDate(doctorId, date, OCCUPYING)
                : appointments.findDoctorAppointmentsForDate(doctorId, date, OCCUPYING);
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
    }

    /**
     * Kiểm tra năng lực tài nguyên ghế nha và thiết bị độc quyền tại chi nhánh (Legacy mode)
     * trong khoảng thời gian [start, end). Bỏ qua nếu repository chưa được inject (unit test thuần).
     */
    @Transactional(readOnly = true)
    public void validateBranchResourceCapacity(
            Branch branch,
            com.cvdcms.entity.Service service,
            LocalDate date,
            LocalTime start,
            LocalTime end,
            Long excludeAppointmentId
    ) {
        if (branch == null || branch.getBranchId() == null || date == null || start == null || end == null || !start.isBefore(end)) {
            return;
        }
        if (dentalChairs == null) {
            return;
        }

        Long branchId = branch.getBranchId();
        boolean needsChair = service == null || !Boolean.FALSE.equals(service.getRequiresChair());
        List<ServiceResourceRequirement> equipmentReqs = List.of();
        if (service != null && service.getServiceId() != null && serviceRequirements != null) {
            equipmentReqs = serviceRequirements.findByServiceServiceIdOrderByRequirementIdAsc(service.getServiceId()).stream()
                    .filter(r -> !Boolean.FALSE.equals(r.getIsRequired()))
                    .filter(r -> r.getResourceKind() == ResourceRequirementKind.EQUIPMENT_TYPE
                            || r.getResourceKind() == ResourceRequirementKind.SPECIFIC_EQUIPMENT)
                    .toList();
        }

        if (!needsChair && equipmentReqs.isEmpty()) {
            return;
        }

        List<Appointment> dayBooked = appointments.findOccupyingForBranchAndDate(branchId, date, OCCUPYING).stream()
                .filter(a -> excludeAppointmentId == null || !Objects.equals(a.getAppointmentId(), excludeAppointmentId))
                .toList();
        Map<Long, Visit> visitByApptId = loadVisitsForCheckedInAppointments(dayBooked);
        LocalDateTime nowDt = LocalDateTime.now(clock);
        List<Appointment> overlapping = dayBooked.stream()
                .filter(a -> overlapsWithRelease(a, start, end, date, nowDt, visitByApptId))
                .toList();

        if (needsChair) {
            List<DentalChair> branchChairs = dentalChairs.findByBranchBranchIdOrderByChairCodeAsc(branchId);
            if (!branchChairs.isEmpty()) {
                long operationalChairs = branchChairs.stream()
                        .filter(c -> c.getStatus() != ChairStatus.MAINTENANCE && c.getStatus() != ChairStatus.INACTIVE)
                        .count();
                if (operationalChairs <= 0) {
                    throw new SlotAlreadyTakenException(
                            "Chi nhánh hiện không có ghế nha khoa khả dụng cho khung giờ " + start + " - " + end + "."
                    );
                }
                long chairUsed = overlapping.stream()
                        .filter(a -> a.getService() == null || !Boolean.FALSE.equals(a.getService().getRequiresChair()))
                        .count();
                if (chairUsed >= operationalChairs) {
                    throw new SlotAlreadyTakenException(
                            "Tất cả " + operationalChairs + " ghế nha khoa tại chi nhánh đã kín lịch trong khung giờ "
                                    + start + " - " + end + "."
                    );
                }
            }
        }

        if (!equipmentReqs.isEmpty() && clinicEquipments != null) {
            List<ClinicEquipment> activeEquipments = clinicEquipments
                    .findByBranchBranchIdAndStatusOrderByEquipmentCodeAsc(branchId, EquipmentStatus.ACTIVE);
            Map<Long, List<ServiceResourceRequirement>> reqCache = new HashMap<>();

            for (ServiceResourceRequirement req : equipmentReqs) {
                if (req.getResourceKind() == ResourceRequirementKind.SPECIFIC_EQUIPMENT && req.getEquipment() != null) {
                    Long eqId = req.getEquipment().getEquipmentId();
                    boolean eqActiveHere = activeEquipments.stream()
                            .anyMatch(e -> Objects.equals(e.getEquipmentId(), eqId));
                    if (!eqActiveHere) {
                        throw new SlotAlreadyTakenException(
                                "Thiết bị \"" + req.getEquipment().getEquipmentName() + "\" hiện không hoạt động tại chi nhánh."
                        );
                    }
                    long usedEq = countOverlappingAppointmentsUsingSpecificEquipment(overlapping, eqId, reqCache);
                    if (usedEq >= 1) {
                        throw new SlotAlreadyTakenException(
                                "Thiết bị \"" + req.getEquipment().getEquipmentName() + "\" đã kín lịch trong khung giờ "
                                        + start + " - " + end + "."
                        );
                    }
                } else if (req.getResourceKind() == ResourceRequirementKind.EQUIPMENT_TYPE && req.getEquipmentType() != null) {
                    long totalActiveOfType = activeEquipments.stream()
                            .filter(e -> e.getEquipmentType() == req.getEquipmentType())
                            .count();
                    if (totalActiveOfType <= 0) {
                        throw new SlotAlreadyTakenException(
                                "Chi nhánh hiện chưa có thiết bị " + req.getEquipmentType().name()
                                        + " đang hoạt động cho dịch vụ \"" + service.getServiceName() + "\"."
                        );
                    }
                    long usedOfType = countOverlappingAppointmentsUsingEquipmentType(overlapping, req.getEquipmentType(), reqCache);
                    if (usedOfType >= totalActiveOfType) {
                        throw new SlotAlreadyTakenException(
                                "Thiết bị " + req.getEquipmentType().name() + " tại chi nhánh đã kín lịch trong khung giờ "
                                        + start + " - " + end + "."
                        );
                    }
                }
            }
        }
    }

    private long countOverlappingAppointmentsUsingSpecificEquipment(
            List<Appointment> overlapping,
            Long targetEquipmentId,
            Map<Long, List<ServiceResourceRequirement>> reqCache
    ) {
        if (serviceRequirements == null || targetEquipmentId == null) {
            return 0L;
        }
        long count = 0L;
        for (Appointment appt : overlapping) {
            if (appt.getService() == null || appt.getService().getServiceId() == null) continue;
            List<ServiceResourceRequirement> reqs = reqCache.computeIfAbsent(
                    appt.getService().getServiceId(),
                    serviceRequirements::findByServiceServiceIdOrderByRequirementIdAsc
            );
            boolean uses = reqs.stream()
                    .filter(r -> !Boolean.FALSE.equals(r.getIsRequired()))
                    .anyMatch(r -> r.getResourceKind() == ResourceRequirementKind.SPECIFIC_EQUIPMENT
                            && r.getEquipment() != null
                            && Objects.equals(r.getEquipment().getEquipmentId(), targetEquipmentId));
            if (uses) count++;
        }
        return count;
    }

    private long countOverlappingAppointmentsUsingEquipmentType(
            List<Appointment> overlapping,
            com.cvdcms.entity.enums.EquipmentType equipmentType,
            Map<Long, List<ServiceResourceRequirement>> reqCache
    ) {
        if (serviceRequirements == null || equipmentType == null) {
            return 0L;
        }
        long count = 0L;
        for (Appointment appt : overlapping) {
            if (appt.getService() == null || appt.getService().getServiceId() == null) continue;
            List<ServiceResourceRequirement> reqs = reqCache.computeIfAbsent(
                    appt.getService().getServiceId(),
                    serviceRequirements::findByServiceServiceIdOrderByRequirementIdAsc
            );
            boolean uses = reqs.stream()
                    .filter(r -> !Boolean.FALSE.equals(r.getIsRequired()))
                    .anyMatch(r -> (r.getResourceKind() == ResourceRequirementKind.EQUIPMENT_TYPE
                            && r.getEquipmentType() == equipmentType)
                            || (r.getResourceKind() == ResourceRequirementKind.SPECIFIC_EQUIPMENT
                            && r.getEquipment() != null
                            && r.getEquipment().getEquipmentType() == equipmentType));
            if (uses) count++;
        }
        return count;
    }

    private Integer resolveOperationalChairCap(Long branchId) {
        if (dentalChairs == null || branchId == null) {
            return null;
        }
        List<DentalChair> branchChairs = dentalChairs.findByBranchBranchIdOrderByChairCodeAsc(branchId);
        if (branchChairs.isEmpty()) {
            return null;
        }
        long operational = branchChairs.stream()
                .filter(c -> c.getStatus() != ChairStatus.MAINTENANCE && c.getStatus() != ChairStatus.INACTIVE)
                .count();
        return (int) operational;
    }

    private int capRemainingByChairs(
            Integer operationalChairCap,
            int currentRemaining,
            LocalTime start,
            LocalTime end,
            LocalDate date,
            LocalDateTime nowDt,
            List<Appointment> booked,
            Map<Long, Visit> visitByApptId
    ) {
        if (operationalChairCap == null || currentRemaining <= 0) {
            return currentRemaining;
        }
        if (operationalChairCap <= 0) {
            return 0;
        }
        long chairOverlap = booked.stream()
                .filter(a -> a.getService() == null || !Boolean.FALSE.equals(a.getService().getRequiresChair()))
                .filter(a -> overlapsWithRelease(a, start, end, date, nowDt, visitByApptId))
                .count();
        int chairRemaining = Math.max(0, operationalChairCap - (int) chairOverlap);
        return Math.min(currentRemaining, chairRemaining);
    }

    /**
     * Khóa bi quan bác sĩ, lịch trực và lịch hẹn chiếm chỗ cho lịch linh hoạt (expectedTime == null)
     * có chỉ định bác sĩ nhằm kiểm tra trạng thái bác sĩ, ca trực/nghỉ phép và giới hạn doctorCapacity theo buổi.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void lockAndValidateDoctorSession(
            Long branchId,
            Long doctorId,
            LocalDate date,
            PreferredSession session,
            Long excludeAppointmentId
    ) {
        if (branchId == null || doctorId == null || date == null || session == null || session == PreferredSession.ANY) {
            return;
        }
        StaffProfile lockedDoctor = staffProfiles != null
                ? staffProfiles.lockById(doctorId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bác sĩ không tồn tại."))
                : null;
        if (lockedDoctor != null) {
            refreshIfManaged(lockedDoctor);
            if (lockedDoctor.getStatus() != StaffStatus.ACTIVE) {
                throw new SlotAlreadyTakenException("Bác sĩ hiện không ở trạng thái hoạt động.");
            }
        }
        if (staffSchedules == null) {
            return;
        }
        staffSchedules.lockDoctorSchedulesForDate(doctorId, date, ScheduleStatus.SCHEDULED);
        List<StaffSchedule> schedules = staffSchedules.findDoctorSchedulesForDate(doctorId, date, ScheduleStatus.SCHEDULED).stream()
                .filter(s -> s.getBranch() != null && branchId.equals(s.getBranch().getBranchId()))
                .filter(s -> Boolean.TRUE.equals(s.getBranch().getIsActive()))
                .filter(s -> s.getShift() != null && Boolean.TRUE.equals(s.getShift().getIsActive()))
                .toList();
        if (schedules.isEmpty()) {
            throw new SlotAlreadyTakenException("Bác sĩ không có ca làm việc hợp lệ vào ngày " + date + ".");
        }

        List<LeaveRequest> approvedLeaves = leaveRequests != null
                ? leaveRequests.findApprovedForStaffOnDate(doctorId, date, LeaveStatus.APPROVED)
                : List.of();

        Branch branch = schedules.get(0).getBranch();
        Range sessionRange = range(branch, session);
        if (!sessionRange.active || !sessionRange.start.isBefore(sessionRange.end)) {
            throw new SlotAlreadyTakenException("Chi nhánh không nhận lịch trong buổi đã chọn.");
        }

        LocalDateTime nowDt = LocalDateTime.now(clock);
        boolean hasWorkingShiftInSession = false;
        for (StaffSchedule schedule : schedules) {
            WorkShift shift = schedule.getShift();
            if (shift == null || shift.getStartTime() == null || shift.getEndTime() == null
                    || !shift.getEndTime().isAfter(shift.getStartTime())) {
                continue;
            }
            long shiftMinutes = Duration.between(shift.getStartTime(), shift.getEndTime()).toMinutes();
            String shiftCode = shift.getShiftCode() != null ? shift.getShiftCode().toUpperCase(Locale.ROOT) : "";
            if (shiftMinutes > WorkShiftService.MAX_SHIFT_MINUTES
                    || shiftCode.contains("ALL") || shiftCode.contains("FULL")
                    || shiftCode.contains("DEMO") || shiftCode.contains("SEED")) {
                continue;
            }
            boolean onLeave = approvedLeaves.stream().anyMatch(l ->
                    (l.getBranch() == null || branchId.equals(l.getBranch().getBranchId()))
                            && (l.getShift() == null || l.getShift().getShiftId().equals(shift.getShiftId())));
            if (onLeave) {
                continue;
            }
            if (!shift.getStartTime().isBefore(sessionRange.end) || !shift.getEndTime().isAfter(sessionRange.start)) {
                continue;
            }
            LocalTime effectiveEnd = shift.getEndTime().isBefore(sessionRange.end) ? shift.getEndTime() : sessionRange.end;
            if (date.equals(nowDt.toLocalDate()) && !nowDt.toLocalTime().isBefore(effectiveEnd)) {
                continue;
            }
            hasWorkingShiftInSession = true;
            break;
        }
        if (!hasWorkingShiftInSession) {
            throw new SlotAlreadyTakenException("Bác sĩ không khả dụng trong ngày và buổi đã chọn.");
        }

        List<Appointment> occupying = appointments.lockDoctorAppointmentsForDate(doctorId, date, OCCUPYING);
        int doctorCap = policy != null ? policy.doctorCapacityForSession(branchId, date, session) : Integer.MAX_VALUE;
        long doctorSessionBooked = occupying.stream()
                .filter(a -> excludeAppointmentId == null || !Objects.equals(a.getAppointmentId(), excludeAppointmentId))
                .filter(a -> a.getBranch() != null && branchId.equals(a.getBranch().getBranchId()))
                .filter(a -> a.getPreferredSession() == session || a.getPreferredSession() == PreferredSession.ANY)
                .count();
        if (doctorSessionBooked >= doctorCap) {
            throw new SlotAlreadyTakenException(
                    "Bác sĩ đã hết khả năng nhận lịch trong buổi (" + doctorSessionBooked + "/" + doctorCap + " ca).");
        }
    }

    private void refreshIfManaged(Object entity) {
        if (entityManager != null && entity != null && entityManager.contains(entity)) {
            entityManager.refresh(entity);
        }
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
        LocalDateTime nowDt = LocalDateTime.now(clock);
        LocalTime requestedEnd = requestedStart.plusMinutes(requiredDuration);
        List<Appointment> sessionAppointments = appointments.findOccupyingForSession(
                branch.getBranchId(), date, session, OCCUPYING);
        Map<Long, Visit> visitByApptId = loadVisitsForCheckedInAppointments(sessionAppointments);
        Map<Long, Long> sessionLoadByDoctor = new HashMap<>();
        Map<Long, Long> overlapLoadByDoctor = new HashMap<>();
        for (Appointment a : sessionAppointments) {
            if (a.getDoctor() != null && !Objects.equals(a.getAppointmentId(), excludeAppointmentId)) {
                Long docId = a.getDoctor().getStaffId();
                sessionLoadByDoctor.merge(docId, 1L, Long::sum);
                if (overlapsWithRelease(a, requestedStart, requestedEnd, date, nowDt, visitByApptId)) {
                    overlapLoadByDoctor.merge(docId, 1L, Long::sum);
                }
            }
        }

        List<AppointmentPolicyService.DoctorOption> candidates = branchAvailability.doctors().stream()
                .sorted(Comparator
                        .comparingLong((AppointmentPolicyService.DoctorOption d) -> overlapLoadByDoctor.getOrDefault(d.id(), 0L))
                        .thenComparingLong((AppointmentPolicyService.DoctorOption d) -> sessionLoadByDoctor.getOrDefault(d.id(), 0L))
                        .thenComparingLong(AppointmentPolicyService.DoctorOption::id))
                .toList();

        for (AppointmentPolicyService.DoctorOption candidate : candidates) {
            try {
                // Pre-check không khóa để bỏ qua ứng viên không đủ giờ/trùng lịch trước khi lấy FOR UPDATE
                validateDoctorSlotInternal(branch.getBranchId(), candidate.id(), date, requestedStart, requiredDuration, excludeAppointmentId, false);
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

    @Transactional(readOnly = true)
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

        AppointmentPolicyService.Availability availability = policy.availability(branch, date, session,
                excludeId, actorType);
        if (availability == null || !availability.available() || availability.remainingBranch() <= 0) {
            return List.of();
        }
        if (doctorId != null && availability.doctors().stream().noneMatch(d -> doctorId.equals(d.id()) && d.remaining() > 0)) {
            return List.of();
        }

        List<Appointment> booked = appointments.findOccupyingForSession(branch.getBranchId(), date, session, OCCUPYING)
                .stream().filter(a -> excludeId == null || !Objects.equals(a.getAppointmentId(), excludeId)).toList();
        Map<Long, Visit> visitByApptId = loadVisitsForCheckedInAppointments(booked);

        List<AppointmentPolicyService.DoctorOption> targetDoctors = doctorId != null
                ? availability.doctors().stream().filter(d -> doctorId.equals(d.id())).toList()
                : availability.doctors();
        if (targetDoctors.isEmpty()) {
            return List.of();
        }

        Map<Long, DoctorSlotSnapshot> snapshots = buildDoctorSnapshots(
                branch.getBranchId(), date, range, durationMinutes, excludeId, nowDt, targetDoctors, booked, visitByApptId);
        Integer operationalChairCap = resolveOperationalChairCap(branch.getBranchId());

        int step = (durationMinutes % 15 != 0) ? SUGGESTED_STEP_MINUTES : 15;
        List<Slot> result = new ArrayList<>();
        for (LocalTime time = range.start; !time.plusMinutes(durationMinutes).isAfter(range.end);
                time = time.plusMinutes(step)) {
            if (date.equals(nowDt.toLocalDate()) && !time.isAfter(nowDt.toLocalTime())) {
                continue;
            }
            LocalTime end = time.plusMinutes(durationMinutes);
            int remaining = computeRemainingAtWindow(
                    time, end, durationMinutes, date, nowDt, doctorId, availability.remainingBranch(),
                    targetDoctors, snapshots, booked, visitByApptId);
            remaining = capRemainingByChairs(operationalChairCap, remaining, time, end, date, nowDt, booked, visitByApptId);
            if (remaining > 0) {
                result.add(new Slot(time, remaining));
            }
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
            AppointmentPolicyService.Availability availability = policy.availability(branch, date, session, excludeId, actorType);
            if (availability != null && !availability.available()) {
                throw new SlotAlreadyTakenException(
                        availability.reason() != null ? availability.reason() : "Buổi khám không còn khả dụng."
                );
            }
            List<Appointment> booked = appointments.findOccupyingForSession(branch.getBranchId(), date, session, OCCUPYING)
                    .stream().filter(a -> excludeId == null || !Objects.equals(a.getAppointmentId(), excludeId)).toList();
            Map<Long, Visit> visitByApptId = loadVisitsForCheckedInAppointments(booked);
            LocalTime end = requested.plusMinutes(durationMinutes);
            if (availability != null && availability.available()) {
                List<AppointmentPolicyService.DoctorOption> targetDoctors = doctorId != null
                        ? availability.doctors().stream().filter(d -> doctorId.equals(d.id())).toList()
                        : availability.doctors();
                if (targetDoctors.isEmpty()) {
                    throw new SlotAlreadyTakenException("Không còn bác sĩ khả dụng trong ca khám.");
                }
                Map<Long, DoctorSlotSnapshot> snapshots = buildDoctorSnapshots(
                        branch.getBranchId(), date, range, durationMinutes, excludeId, nowDt, targetDoctors, booked, visitByApptId);
                int remainingAtSlot = computeRemainingAtWindow(
                        requested, end, durationMinutes, date, nowDt, doctorId, availability.remainingBranch(),
                        targetDoctors, snapshots, booked, visitByApptId);
                remainingAtSlot = capRemainingByChairs(
                        resolveOperationalChairCap(branch.getBranchId()), remainingAtSlot,
                        requested, end, date, nowDt, booked, visitByApptId);
                if (remainingAtSlot <= 0) {
                    throw new SlotAlreadyTakenException(doctorId != null
                            ? "Khung giờ " + requested + " - " + end + " của bác sĩ đã đạt giới hạn tiếp nhận."
                            : "Khung giờ " + requested + " - " + end + " đã đạt tối đa công suất tiếp nhận của ca trực.");
                }
            } else if (doctorId != null) {
                long doctorOverlap = booked.stream()
                        .filter(a -> overlapsWithRelease(a, requested, end, date, nowDt, visitByApptId)
                                && a.getDoctor() != null && doctorId.equals(a.getDoctor().getStaffId()))
                        .count();
                if (doctorOverlap >= maxConcurrentPerDoctorSlot) {
                    throw new SlotAlreadyTakenException(
                            "Khung giờ " + requested + " - " + end + " của bác sĩ đã đạt giới hạn tiếp nhận.");
                }
            }
        }
        return requested;
    }

    public static int duration(com.cvdcms.entity.Service service) {
        return service == null || service.getEstimatedDurationMinutes() == null
                ? 30 : service.getEstimatedDurationMinutes();
    }

    private record DoctorSlotSnapshot(
            int docSessionRemaining,
            List<TimeInterval> netWorking,
            List<TimeInterval> activeIntervals,
            long remainingWorkloadMinutes
    ) { }

    private Map<Long, DoctorSlotSnapshot> buildDoctorSnapshots(
            Long branchId,
            LocalDate date,
            Range range,
            int durationMinutes,
            Long excludeId,
            LocalDateTime nowDt,
            List<AppointmentPolicyService.DoctorOption> doctors,
            List<Appointment> sessionBooked,
            Map<Long, Visit> visitByApptId
    ) {
        Map<Long, DoctorSlotSnapshot> map = new HashMap<>();
        List<TimeInterval> defaultRangeInterval = List.of(new TimeInterval(range.start, range.end));

        for (AppointmentPolicyService.DoctorOption doc : doctors) {
            if (staffSchedules == null) {
                List<Appointment> docBooked = sessionBooked.stream()
                        .filter(a -> a.getDoctor() != null && doc.id().equals(a.getDoctor().getStaffId()))
                        .toList();
                List<TimeInterval> active = buildEffectiveIntervals(docBooked, excludeId, date, nowDt, visitByApptId);
                map.put(doc.id(), new DoctorSlotSnapshot(doc.remaining(), defaultRangeInterval, active, 240L * 10));
                continue;
            }

            List<StaffSchedule> rawSchedules = staffSchedules.findDoctorSchedulesForDate(doc.id(), date, ScheduleStatus.SCHEDULED).stream()
                    .filter(s -> branchId == null || (s.getBranch() != null && branchId.equals(s.getBranch().getBranchId())))
                    .filter(s -> s.getBranch() != null && Boolean.TRUE.equals(s.getBranch().getIsActive()))
                    .filter(s -> s.getShift() != null && Boolean.TRUE.equals(s.getShift().getIsActive()))
                    .filter(s -> s.getStaff() == null || s.getStaff().getStatus() == null || s.getStaff().getStatus() == StaffStatus.ACTIVE)
                    .toList();
            List<LeaveRequest> approvedLeaves = leaveRequests != null
                    ? leaveRequests.findApprovedForStaffOnDate(doc.id(), date, LeaveStatus.APPROVED)
                    : List.of();
            List<TimeInterval> working = new ArrayList<>();
            for (StaffSchedule schedule : rawSchedules) {
                WorkShift shift = schedule.getShift();
                Branch br = schedule.getBranch();
                if (shift == null || shift.getStartTime() == null || shift.getEndTime() == null) continue;
                Long schBranch = br != null ? br.getBranchId() : branchId;
                boolean fullLeave = approvedLeaves.stream().anyMatch(l ->
                        l.getShift() == null && (l.getBranch() == null || schBranch == null || schBranch.equals(l.getBranch().getBranchId())));
                if (fullLeave) continue;
                LocalTime sStart = shift.getStartTime().isBefore(range.start) ? range.start : shift.getStartTime();
                LocalTime sEnd = shift.getEndTime().isAfter(range.end) ? range.end : shift.getEndTime();
                if (br != null && br.getOpeningTime() != null && br.getOpeningTime().isAfter(sStart)) sStart = br.getOpeningTime();
                if (br != null && br.getClosingTime() != null && br.getClosingTime().isBefore(sEnd)) sEnd = br.getClosingTime();
                if (date.equals(nowDt.toLocalDate()) && nowDt.toLocalTime().isAfter(sStart)) sStart = nowDt.toLocalTime();
                if (sStart.isBefore(sEnd)) working.add(new TimeInterval(sStart, sEnd));
            }
            List<TimeInterval> leaveBlocks = new ArrayList<>();
            for (LeaveRequest leave : approvedLeaves) {
                if (leave.getShift() != null && leave.getShift().getStartTime() != null && leave.getShift().getEndTime() != null
                        && leave.getShift().getStartTime().isBefore(leave.getShift().getEndTime())) {
                    leaveBlocks.add(new TimeInterval(leave.getShift().getStartTime(), leave.getShift().getEndTime()));
                }
            }
            List<TimeInterval> netWorking = subtractIntervals(mergeIntervals(working), leaveBlocks);
            if (netWorking.isEmpty()) {
                map.put(doc.id(), new DoctorSlotSnapshot(0, List.of(), List.of(), 0L));
                continue;
            }
            List<Appointment> docAppointments = appointments.findDoctorAppointmentsForDate(doc.id(), date, OCCUPYING);
            Map<Long, Visit> docVisits = loadVisitsForCheckedInAppointments(docAppointments);
            List<TimeInterval> active = buildEffectiveIntervals(docAppointments, excludeId, date, nowDt, docVisits);
            long totalWorkingMinutes = sumIntervalMinutes(netWorking);
            long occupiedMinutes = overlapMinutes(active, netWorking);
            long remWorkload = Math.max(0L, totalWorkingMinutes - occupiedMinutes);
            map.put(doc.id(), new DoctorSlotSnapshot(doc.remaining(), netWorking, active, remWorkload));
        }
        return map;
    }

    private int computeRemainingAtWindow(
            LocalTime start,
            LocalTime end,
            int durationMinutes,
            LocalDate date,
            LocalDateTime nowDt,
            Long requestedDoctorId,
            int remainingBranch,
            List<AppointmentPolicyService.DoctorOption> targetDoctors,
            Map<Long, DoctorSlotSnapshot> snapshots,
            List<Appointment> sessionBooked,
            Map<Long, Visit> visitByApptId
    ) {
        if (remainingBranch <= 0) {
            return 0;
        }
        int totalDoctorAtWindow = 0;
        for (AppointmentPolicyService.DoctorOption doc : targetDoctors) {
            DoctorSlotSnapshot snap = snapshots.get(doc.id());
            if (snap == null || snap.docSessionRemaining() <= 0 || snap.remainingWorkloadMinutes() < durationMinutes) {
                continue;
            }
            boolean fitsWorking = snap.netWorking().stream()
                    .anyMatch(iv -> !start.isBefore(iv.start()) && !end.isAfter(iv.end()));
            if (!fitsWorking) {
                continue;
            }
            long overlapCount = snap.activeIntervals().stream()
                    .filter(iv -> intervalsOverlap(iv.start(), iv.end(), start, end))
                    .count();
            int waveRem = Math.max(0, maxConcurrentPerDoctorSlot - (int) overlapCount);
            int workloadCap = Math.max(0, (int) (snap.remainingWorkloadMinutes() / durationMinutes));
            totalDoctorAtWindow += Math.min(snap.docSessionRemaining(), Math.min(waveRem, workloadCap));
        }
        if (requestedDoctorId != null) {
            return Math.min(remainingBranch, totalDoctorAtWindow);
        }
        long unassignedOverlap = sessionBooked.stream()
                .filter(a -> a.getDoctor() == null)
                .filter(a -> overlapsWithRelease(a, start, end, date, nowDt, visitByApptId))
                .count();
        return Math.min(remainingBranch, Math.max(0, totalDoctorAtWindow - (int) unassignedOverlap));
    }

    private static TimeInterval resolveEffectiveInterval(
            Appointment appointment,
            LocalDate date,
            LocalDateTime nowDt,
            Map<Long, Visit> visitByApptId
    ) {
        if (appointment == null || appointment.getExpectedTime() == null) return null;
        LocalTime apptStart = appointment.getExpectedTime();
        int length = appointment.getDurationMinutes() != null && appointment.getDurationMinutes() > 0
                ? appointment.getDurationMinutes()
                : duration(appointment.getService());
        LocalTime apptEnd = appointment.getExpectedEndTime() != null
                ? appointment.getExpectedEndTime()
                : apptStart.plusMinutes(length);

        if (date != null && date.equals(nowDt.toLocalDate())
                && (appointment.getStatus() == AppointmentStatus.PENDING || appointment.getStatus() == AppointmentStatus.CONFIRMED)
                && nowDt.toLocalTime().isAfter(apptStart.plusMinutes(GRACE_PERIOD_MINUTES))) {
            return null;
        }
        if (appointment.getStatus() == AppointmentStatus.CHECKED_IN && visitByApptId != null) {
            Visit v = visitByApptId.get(appointment.getAppointmentId());
            if (v != null && v.getActualEndTime() != null && date != null) {
                LocalDate actualEndDate = v.getActualEndTime().toLocalDate();
                if (actualEndDate.isBefore(date)) {
                    return null;
                }
                if (actualEndDate.equals(date)) {
                    LocalTime actualEnd = v.getActualEndTime().toLocalTime();
                    if (!actualEnd.isAfter(apptStart)) {
                        return null;
                    }
                    if (actualEnd.isBefore(apptEnd)) {
                        apptEnd = actualEnd;
                    }
                }
            }
        }
        return apptStart.isBefore(apptEnd) ? new TimeInterval(apptStart, apptEnd) : null;
    }

    private static List<TimeInterval> buildEffectiveIntervals(
            List<Appointment> appointments,
            Long excludeAppointmentId,
            LocalDate date,
            LocalDateTime nowDt,
            Map<Long, Visit> visitByApptId
    ) {
        if (appointments == null || appointments.isEmpty()) return List.of();
        List<TimeInterval> list = new ArrayList<>();
        for (Appointment appt : appointments) {
            if (excludeAppointmentId != null && Objects.equals(appt.getAppointmentId(), excludeAppointmentId)) {
                continue;
            }
            TimeInterval effective = resolveEffectiveInterval(appt, date, nowDt, visitByApptId);
            if (effective != null) {
                list.add(effective);
            }
        }
        return list;
    }

    private static long sumIntervalMinutes(List<TimeInterval> intervals) {
        long total = 0L;
        for (TimeInterval iv : intervals) {
            if (iv.start() != null && iv.end() != null && iv.start().isBefore(iv.end())) {
                total += Duration.between(iv.start(), iv.end()).toMinutes();
            }
        }
        return total;
    }

    private static long overlapMinutes(List<TimeInterval> busyIntervals, List<TimeInterval> workingIntervals) {
        if (busyIntervals.isEmpty() || workingIntervals.isEmpty()) return 0L;
        List<TimeInterval> mergedBusy = mergeIntervals(busyIntervals);
        long total = 0L;
        for (TimeInterval busy : mergedBusy) {
            for (TimeInterval work : workingIntervals) {
                LocalTime s = busy.start().isAfter(work.start()) ? busy.start() : work.start();
                LocalTime e = busy.end().isBefore(work.end()) ? busy.end() : work.end();
                if (s.isBefore(e)) {
                    total += Duration.between(s, e).toMinutes();
                }
            }
        }
        return total;
    }

    private static List<TimeInterval> subtractFullConcurrencyIntervals(
            List<TimeInterval> working,
            List<TimeInterval> active,
            int maxConcurrent
    ) {
        if (working.isEmpty()) return List.of();
        if (active.isEmpty() || maxConcurrent <= 0) {
            return maxConcurrent <= 0 ? List.of() : working;
        }
        List<TimeInterval> openSegments = new ArrayList<>();
        for (TimeInterval work : working) {
            TreeSet<LocalTime> points = new TreeSet<>();
            points.add(work.start());
            points.add(work.end());
            for (TimeInterval act : active) {
                if (act.start().isAfter(work.start()) && act.start().isBefore(work.end())) {
                    points.add(act.start());
                }
                if (act.end().isAfter(work.start()) && act.end().isBefore(work.end())) {
                    points.add(act.end());
                }
            }
            List<LocalTime> sorted = new ArrayList<>(points);
            for (int i = 0; i < sorted.size() - 1; i++) {
                LocalTime segStart = sorted.get(i);
                LocalTime segEnd = sorted.get(i + 1);
                if (!segStart.isBefore(segEnd)) continue;
                long concurrent = active.stream()
                        .filter(act -> intervalsOverlap(act.start(), act.end(), segStart, segEnd))
                        .count();
                if (concurrent < maxConcurrent) {
                    openSegments.add(new TimeInterval(segStart, segEnd));
                }
            }
        }
        return mergeIntervals(openSegments);
    }

    private static boolean overlapsWithRelease(
            Appointment appointment,
            LocalTime start,
            LocalTime end,
            LocalDate date,
            LocalDateTime nowDt,
            Map<Long, Visit> visitByApptId
    ) {
        TimeInterval effective = resolveEffectiveInterval(appointment, date, nowDt, visitByApptId);
        return effective != null && intervalsOverlap(effective.start(), effective.end(), start, end);
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
            if (v != null && v.getAppointmentId() != null && v.getStatus() != VisitStatus.CANCELLED) {
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
