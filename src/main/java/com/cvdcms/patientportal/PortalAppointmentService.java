package com.cvdcms.patientportal;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.AppointmentLifecycleService;
import com.cvdcms.service.AppointmentPolicyService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;

@Service
@Transactional
public class PortalAppointmentService {
    private static final ZoneId CLINIC_ZONE = AppointmentPolicyService.CLINIC_ZONE;

    private final PortalAccess access;
    private final PortalAppointmentRepository appointments;
    private final AppointmentHistoryRepository histories;
    private final BranchRepository branches;
    private final AppointmentBookingLockRepository lockedBranches;
    private final PortalBookingRequestRepository bookingRequests;
    private final ServiceRepository services;
    private final StaffProfileRepository staff;
    private final CustomerCareTaskRepository careTasks;
    private final AppointmentPolicyService policy;
    private final AppointmentLifecycleService lifecycle;

    public PortalAppointmentService(PortalAccess access, PortalAppointmentRepository appointments,
            AppointmentHistoryRepository histories, BranchRepository branches,
            AppointmentBookingLockRepository lockedBranches,
            PortalBookingRequestRepository bookingRequests, ServiceRepository services, StaffProfileRepository staff,
            CustomerCareTaskRepository careTasks, AppointmentPolicyService policy,
            AppointmentLifecycleService lifecycle) {
        this.access = access; this.appointments = appointments; this.histories = histories;
        this.branches = branches; this.lockedBranches = lockedBranches;
        this.bookingRequests = bookingRequests; this.services = services; this.staff = staff;
        this.careTasks = careTasks; this.policy = policy; this.lifecycle = lifecycle;
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
        AppointmentPolicyService.Availability result = policy.availability(branch, date, session,
                excludeAppointmentId, AppointmentActorType.PATIENT);
        return new Availability(result.available(), result.reason(), result.remainingBranch(),
                result.doctors().stream().map(d ->
                        new DoctorOption(d.id(), d.name(), d.specialty(), d.remaining())).toList());
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
        policy.ensureBookable(branch, profile, request.date(), request.session(), request.doctorId(),
                null, AppointmentActorType.PATIENT);
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
        policy.ensureModificationCutoff(a, AppointmentActorType.PATIENT);
        if (a.getRescheduleCount() >= 3) throw conflict("Đã đạt giới hạn ba lần đổi lịch trực tuyến.");
        if (a.getAppointmentDate().equals(request.date()) && a.getPreferredSession() == request.session()
                && Objects.equals(id(a.getDoctor()), request.doctorId()))
            throw bad("Vui lòng chọn lịch hoặc bác sĩ khác.");
        policy.ensureBookable(branch, a.getPatientProfile(), request.date(), request.session(),
                request.doctorId(), appointmentId, AppointmentActorType.PATIENT);
        String oldValue = snapshot(a);
        a.setAppointmentDate(request.date());
        a.setPreferredSession(request.session());
        a.setDoctor(doctor(request.doctorId()));
        a.setExpectedTime(null);
        lifecycle.transition(a, AppointmentStatus.PENDING, AppointmentLifecycleAction.RESCHEDULE);
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
        policy.ensureModificationCutoff(a, AppointmentActorType.PATIENT);
        String reason = clean(request.reason());
        if (reason == null) throw bad("Vui lòng nhập lý do hủy.");
        String oldValue = snapshot(a);
        lifecycle.transition(a, AppointmentStatus.CANCELLED, AppointmentLifecycleAction.CANCEL);
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
