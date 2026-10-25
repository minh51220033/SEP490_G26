package com.cvdcms.patientportal;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;

@Service
@Transactional
public class PortalAppointmentService {
    private static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Bangkok");
    private static final List<AppointmentStatus> ACTIVE = List.of(
            AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED,
            AppointmentStatus.CHECKED_IN, AppointmentStatus.COMPLETED);

    private final PortalAccess access;
    private final PortalAppointmentRepository appointments;
    private final AppointmentHistoryRepository histories;
    private final AppointmentSessionPolicyRepository policies;
    private final AppointmentSessionWindowRepository windows;
    private final BranchRepository branches;
    private final PortalBookingBranchRepository lockedBranches;
    private final PortalBookingRequestRepository bookingRequests;
    private final ServiceRepository services;
    private final StaffProfileRepository staff;
    private final StaffBranchAssignmentRepository assignments;
    private final StaffScheduleRepository schedules;
    private final LeaveRequestRepository leaves;
    private final CustomerCareTaskRepository careTasks;

    @Value("${appointments.branch-capacity:24}") private int defaultBranchCapacity;
    @Value("${appointments.doctor-capacity:10}") private int defaultDoctorCapacity;

    public PortalAppointmentService(PortalAccess access, PortalAppointmentRepository appointments,
            AppointmentHistoryRepository histories, AppointmentSessionPolicyRepository policies,
            AppointmentSessionWindowRepository windows,
            BranchRepository branches, PortalBookingBranchRepository lockedBranches,
            PortalBookingRequestRepository bookingRequests, ServiceRepository services, StaffProfileRepository staff,
            StaffBranchAssignmentRepository assignments, StaffScheduleRepository schedules,
            LeaveRequestRepository leaves, CustomerCareTaskRepository careTasks) {
        this.access = access; this.appointments = appointments; this.histories = histories;
        this.policies = policies; this.windows = windows; this.branches = branches; this.lockedBranches = lockedBranches;
        this.bookingRequests = bookingRequests; this.services = services; this.staff = staff;
        this.assignments = assignments; this.schedules = schedules; this.leaves = leaves;
        this.careTasks = careTasks;
    }

    public record BookingRequest(@NotNull Long profileId, @NotNull Long branchId,
                                 @NotNull LocalDate date, @NotNull PreferredSession session,
                                 Long doctorId, Long serviceId, @Size(max = 500) String symptoms,
                                 @NotBlank String requestKey) {}
    public record RescheduleRequest(@NotNull LocalDate date, @NotNull PreferredSession session,
                                    Long doctorId, @Size(max = 255) String reason) {}
    public record CancelRequest(@NotBlank @Size(max = 255) String reason) {}
    public record DoctorOption(Long id, String name, String specialty, int remaining) {}
    public record Availability(boolean available, String reason, int remainingBranch,
                               List<DoctorOption> doctors) {}
    public record AppointmentView(Long id, String code, Long profileId, String patientName,
            Long branchId, String branchName, Long requestedDoctorId, String requestedDoctorName,
            String serviceName, LocalDate date, PreferredSession session, String symptoms,
            AppointmentStatus status, int rescheduleCount, String cancelReason, LocalDateTime createdAt) {}
    public record HistoryView(String action, String oldValue, String newValue, String reason,
                              LocalDateTime createdAt) {}
    public record Detail(AppointmentView appointment, List<HistoryView> history) {}

    @Transactional(readOnly = true)
    public List<Branch> branches(HttpSession httpSession) {
        access.requireUser(httpSession);
        return branches.findByIsActiveTrueOrderByBranchNameAsc();
    }

    @Transactional(readOnly = true)
    public List<com.cvdcms.entity.Service> bookableServices(HttpSession httpSession) {
        access.requireUser(httpSession);
        return services.findByStatusOrderByCategory_DisplayOrderAscServiceNameAsc(ServiceStatus.ACTIVE)
                .stream().filter(s -> Boolean.TRUE.equals(s.getIsBookableOnline())
                        && Boolean.TRUE.equals(s.getCategory().getIsActive())).toList();
    }

    @Transactional(readOnly = true)
    public Availability availability(HttpSession httpSession, Long branchId, LocalDate date,
                                     PreferredSession session, Long excludeAppointmentId) {
        User user = access.requireUser(httpSession);
        if (excludeAppointmentId != null) {
            Appointment existing = owned(user, excludeAppointmentId);
            if (!existing.getBranch().getBranchId().equals(branchId))
                throw bad("Không thể đổi chi nhánh từ biểu mẫu này.");
        }
        Branch branch = branches.findById(branchId).orElse(null);
        if (branch == null || !Boolean.TRUE.equals(branch.getIsActive()))
            return new Availability(false, "Chi nhánh không hoạt động.", 0, List.of());
        String invalid = invalidSession(branch, date, session);
        if (invalid != null) return new Availability(false, invalid, 0, List.of());
        AppointmentSessionPolicy policy = policy(branchId, date, session);
        List<Appointment> booked = appointments.findActiveForSession(branchId, date, session, ACTIVE).stream()
                .filter(a -> !a.getAppointmentId().equals(excludeAppointmentId)).toList();
        List<StaffProfile> eligible = eligibleDoctors(branch, date, session);
        int sessionCapacity = Math.min(branchCapacity(policy), eligible.size() * doctorCapacity(policy));
        int remaining = Math.max(0, sessionCapacity - booked.size());
        List<DoctorOption> options = eligible.stream().map(doctor -> {
            long doctorCount = booked.stream().filter(a -> a.getDoctor() != null
                    && doctor.getStaffId().equals(a.getDoctor().getStaffId())).count();
            return new DoctorOption(doctor.getStaffId(), doctor.getFullName(), doctor.getSpecialty(),
                    Math.max(0, doctorCapacity(policy) - (int) doctorCount));
        }).filter(d -> d.remaining() > 0).toList();
        String reason = eligible.isEmpty() ? "Chưa có bác sĩ trực buổi này."
                : remaining > 0 ? null : "Buổi khám đã đầy.";
        return new Availability(remaining > 0, reason, remaining, options);
    }

    public AppointmentView book(HttpSession httpSession, BookingRequest request) {
        User user = access.requireUser(httpSession);
        PatientProfile profile = access.requireProfile(user, request.profileId());
        requireActiveProfile(profile);
        if (request.requestKey() == null || !validKey(request.requestKey()))
            throw bad("Mã yêu cầu không hợp lệ.");
        Branch branch = lockedBranches.findByIdForBooking(request.branchId()).orElseThrow(() -> bad("Chi nhánh không tồn tại."));
        Optional<Appointment> existing = bookingRequests.findById(request.requestKey()).map(PortalBookingRequest::getAppointment);
        if (existing.isPresent()) {
            Appointment a = existing.get();
            if (!Objects.equals(a.getPatientProfile().getProfileId(), request.profileId())
                    || !Objects.equals(a.getBranch().getBranchId(), request.branchId())
                    || !Objects.equals(a.getAppointmentDate(), request.date())
                    || a.getPreferredSession() != request.session()
                    || !Objects.equals(id(a.getDoctor()), request.doctorId())
                    || !Objects.equals(serviceId(a.getService()), request.serviceId())
                    || !Objects.equals(a.getInitialSymptoms(), clean(request.symptoms())))
                throw conflict("Mã yêu cầu đã được dùng cho lịch khác.");
            return view(a);
        }
        ensureCapacity(branch, request.date(), request.session(), request.doctorId(), profile.getProfileId(), null);
        com.cvdcms.entity.Service service = requestedService(request.serviceId());
        Appointment appointment = new Appointment();
        appointment.setAppointmentCode("LH" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        appointment.setPatientProfile(profile);
        appointment.setBranch(branch);
        appointment.setDoctor(doctor(request.doctorId()));
        appointment.setService(service);
        appointment.setAppointmentDate(request.date());
        appointment.setPreferredSession(request.session());
        appointment.setExpectedTime(null);
        appointment.setInitialSymptoms(clean(request.symptoms()));
        appointment.setSource(AppointmentSource.ONLINE);
        appointment.setStatus(AppointmentStatus.PENDING);
        appointments.saveAndFlush(appointment);
        PortalBookingRequest bookingRequest = new PortalBookingRequest();
        bookingRequest.setRequestKey(request.requestKey());
        bookingRequest.setAppointment(appointment);
        bookingRequests.save(bookingRequest);
        history(appointment, user.getUserId(), "CREATE", null, snapshot(appointment), null);
        return view(appointment);
    }

    @Transactional(readOnly = true)
    public List<AppointmentView> list(HttpSession httpSession, Long profileId) {
        User user = access.requireUser(httpSession);
        access.requireProfile(user, profileId);
        return appointments.findByPatientProfile_ProfileIdOrderByAppointmentDateDescAppointmentIdDesc(profileId)
                .stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public Detail detail(HttpSession httpSession, Long appointmentId) {
        User user = access.requireUser(httpSession);
        Appointment a = owned(user, appointmentId);
        List<HistoryView> events = histories.findByAppointment_AppointmentIdOrderByCreatedAtAscHistoryIdAsc(appointmentId)
                .stream().map(h -> new HistoryView(h.getAction(), h.getOldValue(), h.getNewValue(),
                        h.getReason(), h.getCreatedAt())).toList();
        return new Detail(view(a), events);
    }

    public AppointmentView reschedule(HttpSession httpSession, Long appointmentId, RescheduleRequest request) {
        User user = access.requireUser(httpSession);
        Appointment beforeLock = owned(user, appointmentId);
        Branch branch = lockedBranches.findByIdForBooking(beforeLock.getBranch().getBranchId())
                .orElseThrow(() -> bad("Chi nhánh không tồn tại."));
        Appointment a = appointments.findByIdForUpdate(appointmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        access.requireProfile(user, a.getPatientProfile().getProfileId());
        requireMutable(a);
        ensurePatientCutoff(a);
        if (a.getRescheduleCount() >= 3) throw conflict("Đã đạt giới hạn ba lần đổi lịch trực tuyến.");
        if (a.getAppointmentDate().equals(request.date()) && a.getPreferredSession() == request.session()
                && Objects.equals(id(a.getDoctor()), request.doctorId()))
            throw bad("Vui lòng chọn lịch hoặc bác sĩ khác.");
        ensureCapacity(branch, request.date(), request.session(), request.doctorId(),
                a.getPatientProfile().getProfileId(), appointmentId);
        String oldValue = snapshot(a);
        a.setAppointmentDate(request.date());
        a.setPreferredSession(request.session());
        a.setDoctor(doctor(request.doctorId()));
        a.setExpectedTime(null);
        a.setStatus(AppointmentStatus.PENDING);
        a.setRescheduleCount(a.getRescheduleCount() + 1);
        appointments.saveAndFlush(a);
        history(a, user.getUserId(), "RESCHEDULE", oldValue, snapshot(a), clean(request.reason()));
        for (CustomerCareTask task : careTasks.findByAppointment_AppointmentId(appointmentId)) {
            if (task.getTaskType() == CustomerCareTaskType.APPOINTMENT_REMINDER
                    && task.getStatus() != CustomerCareTaskStatus.CANCELLED) {
                task.setDueDate(request.date().minusDays(1).isBefore(LocalDate.now(CLINIC_ZONE))
                        ? LocalDate.now(CLINIC_ZONE) : request.date().minusDays(1));
                task.setStatus(CustomerCareTaskStatus.PENDING);
            }
        }
        return view(a);
    }

    public AppointmentView cancel(HttpSession httpSession, Long appointmentId, CancelRequest request) {
        User user = access.requireUser(httpSession);
        Appointment beforeLock = owned(user, appointmentId);
        lockedBranches.findByIdForBooking(beforeLock.getBranch().getBranchId())
                .orElseThrow(() -> bad("Chi nhánh không tồn tại."));
        Appointment a = appointments.findByIdForUpdate(appointmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        access.requireProfile(user, a.getPatientProfile().getProfileId());
        requireMutable(a);
        ensurePatientCutoff(a);
        String reason = clean(request.reason());
        if (reason == null) throw bad("Vui lòng nhập lý do hủy.");
        String oldValue = snapshot(a);
        a.setStatus(AppointmentStatus.CANCELLED);
        a.setCancelReason(reason);
        a.setCancelledBy(user.getUserId());
        a.setCancelledAt(LocalDateTime.now(CLINIC_ZONE));
        appointments.saveAndFlush(a);
        history(a, user.getUserId(), "CANCEL", oldValue, snapshot(a), reason);
        for (CustomerCareTask task : careTasks.findByAppointment_AppointmentId(appointmentId)) {
            if (task.getTaskType() == CustomerCareTaskType.APPOINTMENT_REMINDER
                    && task.getStatus() != CustomerCareTaskStatus.COMPLETED)
                task.setStatus(CustomerCareTaskStatus.CANCELLED);
        }
        return view(a);
    }

    private void ensureCapacity(Branch branch, LocalDate date, PreferredSession session,
                                Long doctorId, Long profileId, Long excludeId) {
        String invalid = invalidSession(branch, date, session);
        if (invalid != null) throw conflict(invalid);
        List<Appointment> duplicate = appointments.findActiveForPatientDay(profileId, branch.getBranchId(), date, ACTIVE);
        if (duplicate.stream().anyMatch(a -> !a.getAppointmentId().equals(excludeId)))
            throw conflict("Hồ sơ này đã có lịch hẹn tại chi nhánh trong ngày được chọn.");
        List<StaffProfile> eligible = eligibleDoctors(branch, date, session);
        if (eligible.isEmpty()) throw conflict("Chưa có bác sĩ trực buổi này.");
        List<Appointment> booked = appointments.findActiveForSession(branch.getBranchId(), date, session, ACTIVE);
        AppointmentSessionPolicy policy = policy(branch.getBranchId(), date, session);
        long branchBooked = booked.stream().filter(a -> !a.getAppointmentId().equals(excludeId)).count();
        if (branchBooked >= Math.min(branchCapacity(policy), eligible.size() * doctorCapacity(policy)))
            throw conflict("Buổi khám đã đầy.");
        if (doctorId != null) {
            if (eligible.stream().noneMatch(d -> doctorId.equals(d.getStaffId())))
                throw conflict("Bác sĩ không khả dụng tại chi nhánh trong ngày và buổi đã chọn.");
            long doctorBooked = booked.stream().filter(a -> !a.getAppointmentId().equals(excludeId)
                    && a.getDoctor() != null && doctorId.equals(a.getDoctor().getStaffId())).count();
            if (doctorBooked >= doctorCapacity(policy)) throw conflict("Bác sĩ đã hết khả năng nhận lịch trong buổi.");
        }
    }

    private List<StaffProfile> eligibleDoctors(Branch branch, LocalDate date, PreferredSession session) {
        Long branchId = branch.getBranchId();
        SessionRange range = sessionRange(branch, session);
        Set<Long> assigned = new HashSet<>();
        for (StaffBranchAssignment a : assignments.findByBranchAndStatus(branchId, AssignmentStatus.ACTIVE)) {
            if (a.getStartDate().isAfter(date) || a.getEndDate() != null && a.getEndDate().isBefore(date)) continue;
            String roleName = a.getRole().getRoleName();
            if (!Boolean.TRUE.equals(a.getRole().getIsActive()) || roleName == null
                    || !("BAC_SI".equalsIgnoreCase(roleName) || "ROLE_BAC_SI".equalsIgnoreCase(roleName))) continue;
            if (a.getStaff().getStatus() == StaffStatus.ACTIVE) assigned.add(a.getStaff().getStaffId());
        }
        List<LeaveRequest> approved = leaves.findApprovedOn(branchId, date, LeaveStatus.APPROVED);
        Map<Long, StaffProfile> result = new LinkedHashMap<>();
        for (StaffSchedule schedule : schedules.findForBooking(branchId, date, ScheduleStatus.SCHEDULED)) {
            StaffProfile doctor = schedule.getStaff();
            if (!assigned.contains(doctor.getStaffId())
                    || !Boolean.TRUE.equals(schedule.getShift().getIsActive())
                    || !covers(schedule.getShift(), range)) continue;
            boolean absent = approved.stream().anyMatch(l -> l.getStaff().getStaffId().equals(doctor.getStaffId())
                    && (l.getShift() == null || l.getShift().getShiftId().equals(schedule.getShift().getShiftId())));
            if (!absent) result.put(doctor.getStaffId(), doctor);
        }
        return result.values().stream().sorted(Comparator.comparing(StaffProfile::getFullName)).toList();
    }

    private String invalidSession(Branch branch, LocalDate date, PreferredSession session) {
        if (!Boolean.TRUE.equals(branch.getIsActive())) return "Chi nhánh không hoạt động.";
        if (date == null || session == null || session == PreferredSession.ANY)
            return "Vui lòng chọn ngày và buổi khám cụ thể.";
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        if (date.isBefore(today) || date.isAfter(today.plusDays(90)))
            return "Ngày khám phải nằm trong 90 ngày tới.";
        String day = date.getDayOfWeek() == DayOfWeek.SUNDAY ? "CN" : String.valueOf(date.getDayOfWeek().getValue() + 1);
        if (branch.getWorkingDays() == null || Arrays.stream(branch.getWorkingDays().split(","))
                .map(String::trim).noneMatch(day::equalsIgnoreCase)) return "Chi nhánh không làm việc trong ngày này.";
        SessionRange range = sessionRange(branch, session);
        if (!range.active()) return "Chi nhánh không nhận lịch trong buổi này.";
        if (!range.start().isBefore(range.end())) return "Cấu hình buổi khám không hợp lệ.";
        if (branch.getOpeningTime().isAfter(range.start()) || branch.getClosingTime().isBefore(range.end()))
            return "Chi nhánh không mở cửa trong buổi được chọn.";
        if (date.equals(today) && !LocalTime.now(CLINIC_ZONE).isBefore(range.end().minusHours(2)))
            return "Đã qua hạn đặt lịch cho buổi này.";
        return null;
    }

    private record SessionRange(LocalTime start, LocalTime end, boolean active) {}
    private SessionRange sessionRange(Branch branch, PreferredSession session) {
        return windows.findByBranch_BranchIdAndPreferredSession(branch.getBranchId(), session)
                .map(w -> new SessionRange(w.getStartTime(), w.getEndTime(), Boolean.TRUE.equals(w.getIsActive())))
                .orElseGet(() -> switch (session) {
                    case MORNING -> new SessionRange(LocalTime.of(8, 0), LocalTime.NOON, true);
                    case AFTERNOON -> new SessionRange(LocalTime.of(13, 30), LocalTime.of(17, 30), true);
                    case EVENING -> new SessionRange(LocalTime.of(18, 0), LocalTime.of(20, 0), true);
                    default -> new SessionRange(LocalTime.MAX, LocalTime.MIN, false);
                });
    }
    private static boolean covers(WorkShift shift, SessionRange range) {
        return !shift.getStartTime().isAfter(range.start()) && !shift.getEndTime().isBefore(range.end());
    }
    private AppointmentSessionPolicy policy(Long branchId, LocalDate date, PreferredSession session) {
        return policies.findByBranch_BranchIdAndAppointmentDateAndPreferredSession(branchId, date, session).orElse(null);
    }
    private int branchCapacity(AppointmentSessionPolicy policy) {
        return policy == null ? defaultBranchCapacity : policy.getBranchCapacity();
    }
    private int doctorCapacity(AppointmentSessionPolicy policy) {
        return policy == null ? defaultDoctorCapacity : policy.getDoctorCapacity();
    }
    private static boolean validKey(String key) {
        try { return UUID.fromString(key).toString().equalsIgnoreCase(key); }
        catch (IllegalArgumentException ex) { return false; }
    }
    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static Long id(StaffProfile doctor) { return doctor == null ? null : doctor.getStaffId(); }
    private static Long serviceId(com.cvdcms.entity.Service service) { return service == null ? null : service.getServiceId(); }
    private StaffProfile doctor(Long id) { return id == null ? null : staff.findById(id)
            .orElseThrow(() -> bad("Bác sĩ không tồn tại.")); }
    private com.cvdcms.entity.Service requestedService(Long id) {
        if (id == null) return null;
        return services.findById(id).filter(s -> s.getStatus() == ServiceStatus.ACTIVE
                && Boolean.TRUE.equals(s.getIsBookableOnline())
                && Boolean.TRUE.equals(s.getCategory().getIsActive()))
                .orElseThrow(() -> bad("Dịch vụ không nhận đặt trực tuyến."));
    }
    private void requireActiveProfile(PatientProfile profile) {
        if (!"ACTIVE".equalsIgnoreCase(profile.getStatus())) throw conflict("Hồ sơ bệnh nhân đã ngừng hoạt động.");
    }
    private Appointment owned(User user, Long id) {
        Appointment a = appointments.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        access.requireProfile(user, a.getPatientProfile().getProfileId());
        return a;
    }
    private void requireMutable(Appointment a) {
        if (a.getStatus() != AppointmentStatus.PENDING && a.getStatus() != AppointmentStatus.CONFIRMED)
            throw conflict("Lịch hẹn không còn ở trạng thái có thể đổi hoặc hủy.");
        if (a.getPreferredSession() == PreferredSession.ANY)
            throw conflict("Lịch hẹn chưa có buổi khám cụ thể. Vui lòng liên hệ lễ tân.");
    }
    private void ensurePatientCutoff(Appointment a) {
        LocalDateTime deadline = LocalDateTime.of(a.getAppointmentDate(),
                sessionRange(a.getBranch(), a.getPreferredSession()).end().minusHours(2));
        if (!LocalDateTime.now(CLINIC_ZONE).isBefore(deadline))
            throw conflict("Đã qua hạn tự đổi hoặc hủy. Vui lòng liên hệ lễ tân.");
    }
    private void history(Appointment a, Long actor, String action, String oldValue, String newValue, String reason) {
        AppointmentHistory h = new AppointmentHistory();
        h.setAppointment(a); h.setActorUserId(actor); h.setAction(action);
        h.setOldValue(oldValue); h.setNewValue(newValue); h.setReason(reason);
        histories.save(h);
    }
    private String snapshot(Appointment a) {
        return a.getAppointmentDate() + "|" + a.getPreferredSession() + "|doctor=" + id(a.getDoctor())
                + "|status=" + a.getStatus();
    }
    private AppointmentView view(Appointment a) {
        return new AppointmentView(a.getAppointmentId(), a.getAppointmentCode(), a.getPatientProfile().getProfileId(),
                a.getPatientProfile().getFullName(), a.getBranch().getBranchId(), a.getBranch().getBranchName(),
                id(a.getDoctor()), a.getDoctor() == null ? null : a.getDoctor().getFullName(),
                a.getService() == null ? null : a.getService().getServiceName(), a.getAppointmentDate(),
                a.getPreferredSession(), a.getInitialSymptoms(), a.getStatus(), a.getRescheduleCount(),
                a.getCancelReason(), a.getCreatedAt());
    }
    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
