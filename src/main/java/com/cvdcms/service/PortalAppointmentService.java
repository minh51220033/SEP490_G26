package com.cvdcms.service;

import com.cvdcms.security.PortalAccess;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.AppointmentLifecycleService;
import com.cvdcms.service.AppointmentPolicyService;
import com.cvdcms.service.AppointmentSlotService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
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
    private final AppointmentSlotService slots;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PatientProfileRepository patientProfiles;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private StaffScheduleRepository staffSchedules;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher careEventPublisher;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.service.ResourceBookingTransactionService resourceBookingTransactionService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.service.ResourceAvailabilityService resourceAvailabilityService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.service.AppointmentOverlapService overlapService;
    @PersistenceContext
    private EntityManager entityManager;

    public PortalAppointmentService(PortalAccess access, PortalAppointmentRepository appointments,
            AppointmentHistoryRepository histories, BranchRepository branches,
            AppointmentBookingLockRepository lockedBranches,
            PortalBookingRequestRepository bookingRequests, ServiceRepository services, StaffProfileRepository staff,
            CustomerCareTaskRepository careTasks, AppointmentPolicyService policy,
            AppointmentLifecycleService lifecycle, AppointmentSlotService slots) {
        this.access = access; this.appointments = appointments; this.histories = histories;
        this.branches = branches; this.lockedBranches = lockedBranches;
        this.bookingRequests = bookingRequests; this.services = services; this.staff = staff;
        this.careTasks = careTasks; this.policy = policy; this.lifecycle = lifecycle; this.slots = slots;
    }

    public record BookingRequest(@NotNull Long profileId, @NotNull Long branchId,
                                 @NotNull LocalDate date, @NotNull PreferredSession session,
                                 Long doctorId, Long serviceId, @Size(max = 500) String symptoms,
                                 @NotBlank String requestKey, LocalTime expectedTime,
                                 Boolean confirmOverlap) {
        public BookingRequest(Long profileId, Long branchId, LocalDate date, PreferredSession session,
                Long doctorId, Long serviceId, String symptoms, String requestKey) {
            this(profileId, branchId, date, session, doctorId, serviceId, symptoms, requestKey, null, false);
        }
        public BookingRequest(Long profileId, Long branchId, LocalDate date, PreferredSession session,
                Long doctorId, Long serviceId, String symptoms, String requestKey, LocalTime expectedTime) {
            this(profileId, branchId, date, session, doctorId, serviceId, symptoms, requestKey, expectedTime, false);
        }
    }
    public record SlotOption(LocalTime time, int remaining) {}
    public record RescheduleRequest(@NotNull LocalDate date, @NotNull PreferredSession session,
                                    Long doctorId, @Size(max = 255) String reason, LocalTime expectedTime,
                                    Boolean confirmOverlap) {
        public RescheduleRequest(LocalDate date, PreferredSession session, Long doctorId, String reason) {
            this(date, session, doctorId, reason, null, false);
        }
        public RescheduleRequest(LocalDate date, PreferredSession session, Long doctorId, String reason, LocalTime expectedTime) {
            this(date, session, doctorId, reason, expectedTime, false);
        }
    }
    public record CancelRequest(@NotBlank @Size(max = 255) String reason) {}
    public record DoctorOption(Long id, String name, String specialty, int remaining) {}
    public record Availability(boolean available, String reason, int remainingBranch,
                               List<DoctorOption> doctors,
                               boolean patientHasSameSessionConflict, String conflictMessage,
                               Long conflictAppointmentId, String conflictAppointmentCode,
                               String conflictBranchName) {
        public Availability(boolean available, String reason, int remainingBranch, List<DoctorOption> doctors) {
            this(available, reason, remainingBranch, doctors, false, null, null, null, null);
        }
    }
    public record AppointmentView(Long id, String code, Long profileId, String patientName,
            Long branchId, String branchName, Long requestedDoctorId, String requestedDoctorName,
            String serviceName, LocalDate date, PreferredSession session, String symptoms,
            AppointmentStatus status, int rescheduleCount, String cancelReason, LocalDateTime createdAt,
            LocalTime expectedTime, Integer durationMinutes) {}
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
                        && s.getServiceType() != com.cvdcms.entity.enums.ServiceType.PRODUCT
                        && Boolean.TRUE.equals(s.getCategory().getIsActive())).toList();
    }

    @Transactional(readOnly = true)
    public Availability availability(HttpSession httpSession, Long branchId, LocalDate date,
                                     PreferredSession session, Long excludeAppointmentId) {
        return availability(httpSession, branchId, date, session, excludeAppointmentId, null);
    }

    @Transactional(readOnly = true)
    public Availability availability(HttpSession httpSession, Long branchId, LocalDate date,
                                     PreferredSession session, Long excludeAppointmentId, Long profileId) {
        User user = access.requireUser(httpSession);
        if (excludeAppointmentId != null) {
            Appointment existing = owned(user, excludeAppointmentId);
            if (!existing.getBranch().getBranchId().equals(branchId))
                throw bad("Không thể đổi chi nhánh từ biểu mẫu này.");
        }
        Branch branch = branches.findById(branchId).orElse(null);
        if (branch == null || !Boolean.TRUE.equals(branch.getIsActive()))
            return new Availability(false, "Chi nhánh không hoạt động.", 0, List.of());
        AppointmentPolicyService.PatientSessionConflict patientConflict = null;
        if (profileId != null) {
            access.requireProfile(user, profileId);
            patientConflict = policy.findSameSessionConflict(profileId, date, session, excludeAppointmentId);
        }
        AppointmentPolicyService.Availability result = policy.availability(branch, date, session,
                excludeAppointmentId, AppointmentActorType.PATIENT);
        List<DoctorOption> docOptions = result.doctors().stream().map(d ->
                new DoctorOption(d.id(), d.name(), d.specialty(), d.remaining())).toList();
        boolean hasConflict = patientConflict != null && patientConflict.hasConflict();
        return new Availability(result.available(), result.reason(), result.remainingBranch(),
                docOptions,
                hasConflict,
                hasConflict ? patientConflict.message() : null,
                hasConflict ? patientConflict.appointmentId() : null,
                hasConflict ? patientConflict.appointmentCode() : null,
                hasConflict ? patientConflict.branchName() : null);
    }

    @Transactional(readOnly = true)
    public List<SlotOption> slots(HttpSession httpSession, Long branchId, LocalDate date,
            PreferredSession period, Long doctorId, Long serviceId, Long excludeId) {
        return slots(httpSession, branchId, date, period, doctorId, serviceId, excludeId, false);
    }

    @Transactional(readOnly = true)
    public List<SlotOption> slots(HttpSession httpSession, Long branchId, LocalDate date,
            PreferredSession period, Long doctorId, Long serviceId, Long excludeId, boolean includeOverlap) {
        User user = access.requireUser(httpSession);
        if (excludeId != null && !owned(user, excludeId).getBranch().getBranchId().equals(branchId))
            throw bad("Không thể đổi chi nhánh từ biểu mẫu này.");
        Branch branch = branches.findById(branchId).filter(b -> Boolean.TRUE.equals(b.getIsActive()))
                .orElseThrow(() -> bad("Chi nhánh không hoạt động."));
        com.cvdcms.entity.Service service = serviceId == null ? null : requestedService(serviceId);
        if (excludeId != null) service = owned(user, excludeId).getService();
        int duration = AppointmentSlotService.duration(service);
        if (period == null && doctorId != null) {
            return slots.getDoctorAvailability(branchId, doctorId, date, duration, excludeId)
                    .suggestedSlots().stream()
                    .map(s -> new SlotOption(s.startTime(), 1))
                    .toList();
        }
        List<AppointmentSlotService.Slot> candidateSlots = slots.available(branch, date, period, doctorId, duration, excludeId,
                        AppointmentActorType.PATIENT);
        if (includeOverlap && period != null && overlapService != null) {
            AppointmentPolicyService.SessionRange range = policy.sessionRange(branch, period);
            if (range != null && range.active()) {
                java.util.Map<LocalTime, Integer> freeRemaining = new java.util.HashMap<>();
                candidateSlots.forEach(s -> freeRemaining.put(s.time(), s.remaining()));
                List<SlotOption> allValid = new java.util.ArrayList<>();
                LocalDateTime nowDt = LocalDateTime.now(CLINIC_ZONE);
                LocalTime cursor = range.start();
                while (!cursor.plusMinutes(duration).isAfter(range.end())) {
                    if (!date.equals(nowDt.toLocalDate()) || cursor.isAfter(nowDt.toLocalTime())) {
                        var chk = overlapService.checkOverlap(branchId, date, period, cursor, doctorId,
                                service != null ? service.getServiceId() : null, excludeId);
                        if (chk.validOperatingWindow()) {
                            int rem = freeRemaining.getOrDefault(cursor, 0);
                            allValid.add(new SlotOption(cursor, rem));
                        }
                    }
                    LocalTime next = cursor.plusMinutes(15);
                    if (!next.isAfter(cursor)) break;
                    cursor = next;
                }
                if (!allValid.isEmpty()) {
                    return allValid;
                }
            }
        }
        final Long effectiveServiceId = service != null ? service.getServiceId() : null;
        boolean useEngine = Boolean.TRUE.equals(branch.getResourceEngineEnabled())
                && effectiveServiceId != null && resourceAvailabilityService != null;
        return candidateSlots.stream()
                .filter(s -> !useEngine || resourceAvailabilityService.checkAvailability(
                        new com.cvdcms.DTO.availability.AvailabilityCheckRequest(
                                branchId, effectiveServiceId, date, s.time(), doctorId, excludeId, false))
                        .available())
                .map(s -> new SlotOption(s.time(), s.remaining()))
                .toList();
    }

    @Transactional(readOnly = true)
    public AppointmentOverlapService.OverlapCheckResult overlapCheck(
            HttpSession httpSession, Long branchId, LocalDate date, PreferredSession sessionPeriod,
            LocalTime expectedTime, Long doctorId, Long serviceId, Long excludeId) {
        User user = access.requireUser(httpSession);
        if (excludeId != null && !owned(user, excludeId).getBranch().getBranchId().equals(branchId)) {
            throw bad("Không thể đổi chi nhánh từ biểu mẫu này.");
        }
        if (overlapService == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Overlap service unavailable");
        }
        Long effectiveServiceId = serviceId;
        if (excludeId != null) {
            Appointment existing = owned(user, excludeId);
            if (existing.getService() != null) {
                effectiveServiceId = existing.getService().getServiceId();
            }
        }
        return overlapService.checkOverlap(branchId, date, sessionPeriod, expectedTime, doctorId, effectiveServiceId, excludeId);
    }

    @Transactional(readOnly = true)
    public AppointmentSlotService.DoctorAvailabilityResult doctorAvailability(
            HttpSession httpSession, Long branchId, LocalDate date, Long doctorId, Long serviceId, Long excludeId) {
        User user = access.requireUser(httpSession);
        if (excludeId != null && !owned(user, excludeId).getBranch().getBranchId().equals(branchId))
            throw bad("Không thể đổi chi nhánh từ biểu mẫu này.");
        branches.findById(branchId).filter(b -> Boolean.TRUE.equals(b.getIsActive()))
                .orElseThrow(() -> bad("Chi nhánh không hoạt động."));
        com.cvdcms.entity.Service service = serviceId == null ? null : requestedService(serviceId);
        if (excludeId != null) service = owned(user, excludeId).getService();
        return slots.getDoctorAvailability(branchId, doctorId, date, AppointmentSlotService.duration(service), excludeId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AppointmentView book(HttpSession httpSession, BookingRequest request) {
        User user = access.requireUser(httpSession);
        PatientProfile verifiedProfile = access.requireProfile(user, request.profileId());
        requireActiveProfile(verifiedProfile);
        if (request.requestKey() == null || !validKey(request.requestKey()))
            throw bad("Mã yêu cầu không hợp lệ.");
        // Global lock hierarchy: 1. PatientProfile -> 2. Branch -> 3. StaffProfile -> 4. StaffSchedule -> 5. Appointment
        PatientProfile profile = patientProfiles != null
                ? patientProfiles.findByIdForUpdate(verifiedProfile.getProfileId()).orElse(verifiedProfile)
                : verifiedProfile;
        refreshIfManaged(profile);
        requireActiveProfile(profile);
        Branch branch = lockedBranches.findByIdForBooking(request.branchId()).orElseThrow(() -> bad("Chi nhánh không tồn tại."));
        refreshIfManaged(branch);
        if (!Boolean.TRUE.equals(branch.getIsActive())) {
            throw bad("Chi nhánh không hoạt động.");
        }
        Optional<Appointment> existing = bookingRequests.findById(request.requestKey()).map(PortalBookingRequest::getAppointment);
        if (existing.isPresent()) {
            Appointment a = existing.get();
            if (!Objects.equals(a.getPatientProfile().getProfileId(), request.profileId())
                    || !Objects.equals(a.getBranch().getBranchId(), request.branchId())
                    || !Objects.equals(a.getAppointmentDate(), request.date())
                    || a.getPreferredSession() != request.session()
                    || !Objects.equals(id(a.getDoctor()), request.doctorId())
                    || !Objects.equals(serviceId(a.getService()), request.serviceId())
                    || !Objects.equals(a.getInitialSymptoms(), clean(request.symptoms()))
                    || request.expectedTime() != null && !Objects.equals(a.getExpectedTime(), request.expectedTime()))
                throw conflict("Mã yêu cầu đã được dùng cho lịch khác.");
            return view(a);
        }
        boolean allowOverlap = Boolean.TRUE.equals(request.confirmOverlap());
        policy.ensureBookable(branch, profile, request.date(), request.session(), request.doctorId(),
                null, AppointmentActorType.PATIENT, allowOverlap);
        com.cvdcms.entity.Service service = requestedService(request.serviceId());
        if (Boolean.TRUE.equals(branch.getResourceEngineEnabled())) {
            if (service == null) {
                throw bad("Vui lòng chọn dịch vụ khám trước khi đặt lịch.");
            }
            if (resourceBookingTransactionService == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Resource Booking Engine chưa sẵn sàng.");
            }
            int durationMinutes = AppointmentSlotService.duration(service);
            LocalTime resolvedTime = request.expectedTime() != null
                    ? request.expectedTime()
                    : slots.resolveFlexibly(branch, request.date(), request.session(), request.doctorId(),
                            durationMinutes, null, null, AppointmentActorType.PATIENT);
            if (resolvedTime == null) {
                throw bad("Vui lòng chọn khung giờ khám cụ thể.");
            }
            var result = resourceBookingTransactionService.bookInExistingTransaction(
                    new com.cvdcms.DTO.availability.ResourceBookingRequest(
                            branch.getBranchId(), profile.getProfileId(), service.getServiceId(),
                            request.date(), resolvedTime, request.doctorId(), request.doctorId() == null,
                            null, request.session(), AppointmentSource.ONLINE, clean(request.symptoms()),
                            "PORTAL:" + request.requestKey(), false),
                    AppointmentStatus.PENDING);
            Appointment appointment = appointments.findById(result.appointmentId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR));
            if (!result.idempotentReplay()) {
                PortalBookingRequest bookingRequest = new PortalBookingRequest();
                bookingRequest.setRequestKey(request.requestKey());
                bookingRequest.setAppointment(appointment);
                bookingRequests.save(bookingRequest);
                history(appointment, user.getUserId(), "CREATE", null, snapshot(appointment), null);
                if (careEventPublisher != null) {
                    careEventPublisher.publishEvent(new com.cvdcms.event.CustomerCareDomainEvents.AppointmentUpsertedCareEvent(appointment.getAppointmentId()));
                    careEventPublisher.publishEvent(new com.cvdcms.event.ClinicQueueDomainEvent(
                            "APPOINTMENT_BOOKED", branch.getBranchId(), result.doctorId(),
                            null, appointment.getAppointmentId(), appointment.getStatus().name(),
                            "Đặt lịch hẹn mới #" + appointment.getAppointmentCode(), LocalDateTime.now(CLINIC_ZONE)));
                }
            }
            return view(appointment);
        }
        int durationMinutes = AppointmentSlotService.duration(service);
        StaffProfile assignedDoctor = doctor(request.doctorId());
        LocalTime resolvedTime = slots.resolveFlexibly(branch, request.date(), request.session(), request.doctorId(),
                durationMinutes, null, request.expectedTime(), AppointmentActorType.PATIENT, allowOverlap);
        if (assignedDoctor != null && resolvedTime == null && !allowOverlap) {
            slots.lockAndValidateDoctorSession(branch.getBranchId(), assignedDoctor.getStaffId(),
                    request.date(), request.session(), null);
        }
        if (assignedDoctor == null && resolvedTime != null) {
            if (allowOverlap) {
                assignedDoctor = slots.tryAutoAssignDoctorForSlot(
                        branch, request.date(), request.session(), resolvedTime, durationMinutes, null)
                        .orElse(null);
            } else {
                StaffProfile autoAssigned = slots.autoAssignDoctorForSlot(
                        branch, request.date(), request.session(), resolvedTime, durationMinutes, null);
                if (autoAssigned != null) {
                    assignedDoctor = autoAssigned;
                }
            }
        }
        if (resolvedTime != null && !allowOverlap) {
            slots.validateBranchResourceCapacity(branch, service, request.date(),
                    resolvedTime, resolvedTime.plusMinutes(durationMinutes), null);
        }
        Appointment appointment = new Appointment();
        appointment.setAppointmentCode("LH" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        appointment.setPatientProfile(profile);
        appointment.setBranch(branch);
        appointment.setDoctor(assignedDoctor);
        appointment.setIsDoctorRequested(request.doctorId() != null);
        if (allowOverlap && resolvedTime != null && overlapService != null) {
            var chk = overlapService.checkOverlap(branch.getBranchId(), request.date(), request.session(),
                    resolvedTime, request.doctorId(), service != null ? service.getServiceId() : null, null);
            if (chk.hasAnyWarning()) {
                appointment.setOverlapWarningAcknowledged(true);
                String note = (chk.clinicOverlap() ? "[Clinic] " + chk.clinicOverlapCount() + "/" + chk.branchCapacity() + " " : "")
                        + (chk.doctorOverlap() ? "[Doctor] " + chk.doctorOverlapCount() + " overlap" : "");
                appointment.setOverlapWarningNote(note.trim());
            }
        }
        appointment.setService(service);
        appointment.setAppointmentDate(request.date());
        appointment.setPreferredSession(request.session());
        appointment.setDurationMinutes(durationMinutes);
        appointment.setExpectedTime(resolvedTime);
        appointment.syncExpectedEndTime();
        appointment.setInitialSymptoms(clean(request.symptoms()));
        appointment.setSource(AppointmentSource.ONLINE);
        appointment.setStatus(AppointmentStatus.PENDING);
        appointments.saveAndFlush(appointment);
        PortalBookingRequest bookingRequest = new PortalBookingRequest();
        bookingRequest.setRequestKey(request.requestKey());
        bookingRequest.setAppointment(appointment);
        bookingRequests.save(bookingRequest);
        history(appointment, user.getUserId(), "CREATE", null, snapshot(appointment), null);
        if (careEventPublisher != null && appointment.getAppointmentId() != null) {
            careEventPublisher.publishEvent(new com.cvdcms.event.CustomerCareDomainEvents.AppointmentUpsertedCareEvent(appointment.getAppointmentId()));
            careEventPublisher.publishEvent(new com.cvdcms.event.ClinicQueueDomainEvent(
                    "APPOINTMENT_BOOKED", branch.getBranchId(), id(assignedDoctor),
                    null, appointment.getAppointmentId(), appointment.getStatus().name(),
                    "Đặt lịch hẹn mới #" + appointment.getAppointmentCode(), LocalDateTime.now(CLINIC_ZONE)));
        }
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

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AppointmentView reschedule(HttpSession httpSession, Long appointmentId, RescheduleRequest request) {
        User user = access.requireUser(httpSession);
        AppointmentLockSnapshot snap = readLockSnapshot(appointmentId);
        if (snap == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (snap.patientProfileId() != null) {
            access.requireProfile(user, snap.patientProfileId());
        }
        if (snap.status() != AppointmentStatus.PENDING && snap.status() != AppointmentStatus.CONFIRMED) {
            throw conflict("Lịch hẹn không còn ở trạng thái có thể đổi hoặc hủy.");
        }
        if (snap.preferredSession() == PreferredSession.ANY) {
            throw conflict("Lịch hẹn chưa có buổi khám cụ thể. Vui lòng liên hệ lễ tân.");
        }

        // Global lock hierarchy: 1. PatientProfile -> 2. Branch -> 3. StaffProfile (staffId ASC) -> 4. StaffSchedule -> 5. Appointment
        if (patientProfiles != null && snap.patientProfileId() != null) {
            PatientProfile lockedProfile = patientProfiles.findByIdForUpdate(snap.patientProfileId()).orElse(null);
            refreshIfManaged(lockedProfile);
        }
        Branch branch = lockedBranches.findByIdForBooking(snap.branchId())
                .orElseThrow(() -> bad("Chi nhánh không tồn tại."));
        refreshIfManaged(branch);
        if (!Boolean.TRUE.equals(branch.getIsActive())) {
            throw bad("Chi nhánh không hoạt động.");
        }
        lockDoctorsAndSchedules(Arrays.asList(snap.doctorId(), request.doctorId()),
                Arrays.asList(snap.appointmentDate(), request.date()));

        Appointment a = appointments.findByIdForUpdate(appointmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        refreshIfManaged(a);
        if (!snap.matchesLockedState(a)) {
            throw conflict("Lịch hẹn vừa được cập nhật bởi thao tác khác. Vui lòng tải lại trang và thử lại.");
        }

        access.requireProfile(user, a.getPatientProfile().getProfileId());
        requireMutable(a);
        policy.ensureModificationCutoff(a, AppointmentActorType.PATIENT);
        if (a.getRescheduleCount() >= 3) throw conflict("Đã đạt giới hạn ba lần đổi lịch trực tuyến.");
        if (a.getAppointmentDate().equals(request.date()) && a.getPreferredSession() == request.session()
                && Objects.equals(id(a.getDoctor()), request.doctorId())
                && (request.expectedTime() == null || Objects.equals(a.getExpectedTime(), request.expectedTime())))
            throw bad("Vui lòng chọn lịch hoặc bác sĩ khác.");
        boolean allowOverlap = Boolean.TRUE.equals(request.confirmOverlap());
        policy.ensureBookable(branch, a.getPatientProfile(), request.date(), request.session(),
                request.doctorId(), appointmentId, AppointmentActorType.PATIENT, allowOverlap);
        String oldValue = snapshot(a);
        LocalTime requestedTime = request.expectedTime() != null ? request.expectedTime()
                : Objects.equals(a.getAppointmentDate(), request.date()) && a.getPreferredSession() == request.session()
                    ? a.getExpectedTime() : null;
        if (Boolean.TRUE.equals(branch.getResourceEngineEnabled())) {
            if (resourceBookingTransactionService == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Resource Booking Engine chưa sẵn sàng.");
            }
            int durationMinutes = AppointmentSlotService.duration(a.getService());
            LocalTime resolvedTime = requestedTime != null ? requestedTime
                    : slots.resolveFlexibly(branch, request.date(), request.session(), request.doctorId(),
                            durationMinutes, appointmentId, null, AppointmentActorType.PATIENT);
            if (resolvedTime == null) {
                throw bad("Vui lòng chọn khung giờ khám cụ thể.");
            }
            resourceBookingTransactionService.rescheduleInExistingTransaction(
                    com.cvdcms.DTO.availability.ResourceRescheduleRequest.reschedule(
                            appointmentId, branch.getBranchId(), null,
                            request.date(), resolvedTime, request.session(),
                            request.doctorId(), request.doctorId() == null,
                            "RESCHEDULED", AppointmentStatus.PENDING, true));
            Appointment updated = appointments.findById(appointmentId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            history(updated, user.getUserId(), "RESCHEDULE", oldValue, snapshot(updated), clean(request.reason()));
            for (CustomerCareTask task : careTasks.findByAppointment_AppointmentId(appointmentId)) {
                if (task.getTaskType() == CustomerCareTaskType.APPOINTMENT_REMINDER
                        && task.getStatus() != CustomerCareTaskStatus.CANCELLED) {
                    task.setDueDate(request.date().minusDays(1).isBefore(LocalDate.now(CLINIC_ZONE))
                            ? LocalDate.now(CLINIC_ZONE) : request.date().minusDays(1));
                    task.setStatus(CustomerCareTaskStatus.PENDING);
                }
            }
            return view(updated);
        }
        int durationMinutes = AppointmentSlotService.duration(a.getService());
        StaffProfile assignedDoctor = doctor(request.doctorId());
        LocalTime resolvedTime = slots.resolveFlexibly(branch, request.date(), request.session(), request.doctorId(),
                durationMinutes, appointmentId, requestedTime, AppointmentActorType.PATIENT, allowOverlap);
        if (assignedDoctor != null && resolvedTime == null && !allowOverlap) {
            slots.lockAndValidateDoctorSession(branch.getBranchId(), assignedDoctor.getStaffId(),
                    request.date(), request.session(), appointmentId);
        }
        if (assignedDoctor == null && resolvedTime != null) {
            if (allowOverlap) {
                assignedDoctor = slots.tryAutoAssignDoctorForSlot(
                        branch, request.date(), request.session(), resolvedTime, durationMinutes, appointmentId)
                        .orElse(null);
            } else {
                StaffProfile autoAssigned = slots.autoAssignDoctorForSlot(
                        branch, request.date(), request.session(), resolvedTime, durationMinutes, appointmentId);
                if (autoAssigned != null) {
                    assignedDoctor = autoAssigned;
                }
            }
        }
        if (resolvedTime != null && !allowOverlap) {
            slots.validateBranchResourceCapacity(branch, a.getService(), request.date(),
                    resolvedTime, resolvedTime.plusMinutes(durationMinutes), appointmentId);
        }
        a.setAppointmentDate(request.date());
        a.setPreferredSession(request.session());
        a.setDoctor(assignedDoctor);
        a.setIsDoctorRequested(request.doctorId() != null);
        a.setDurationMinutes(durationMinutes);
        a.setExpectedTime(resolvedTime);
        a.syncExpectedEndTime();
        if (allowOverlap) {
            a.setOverlapWarningAcknowledged(true);
            a.setOverlapWarningNote("Bệnh nhân xác nhận tiếp tục đổi lịch vào khung giờ có cảnh báo đông/trùng.");
        } else {
            a.setOverlapWarningAcknowledged(false);
            a.setOverlapWarningNote(null);
        }
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
        if (patientProfiles != null && beforeLock.getPatientProfile() != null) {
            patientProfiles.findByIdForUpdate(beforeLock.getPatientProfile().getProfileId());
        }
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
        if (resourceBookingTransactionService != null) {
            resourceBookingTransactionService.releaseReservationsOnCancelOrNoShow(
                    appointmentId, ReservationStatus.CANCELLED, "APPOINTMENT_CANCELLED", LocalDateTime.now(CLINIC_ZONE));
        }
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

    private AppointmentLockSnapshot readLockSnapshot(Long appointmentId) {
        return appointments.findLockSnapshotById(appointmentId)
                .orElseGet(() -> appointments.findById(appointmentId).map(AppointmentLockSnapshot::from).orElse(null));
    }

    private void lockDoctorsAndSchedules(Collection<Long> doctorIds, Collection<LocalDate> dates) {
        TreeSet<Long> sortedDoctorIds = new TreeSet<>();
        for (Long docId : doctorIds) {
            if (docId != null) {
                sortedDoctorIds.add(docId);
            }
        }
        if (sortedDoctorIds.isEmpty()) {
            return;
        }
        for (Long docId : sortedDoctorIds) {
            if (staff != null) {
                staff.lockById(docId).ifPresent(this::refreshIfManaged);
            }
        }
        if (staffSchedules == null) {
            return;
        }
        TreeSet<LocalDate> sortedDates = new TreeSet<>();
        for (LocalDate d : dates) {
            if (d != null) {
                sortedDates.add(d);
            }
        }
        for (Long docId : sortedDoctorIds) {
            for (LocalDate d : sortedDates) {
                staffSchedules.lockDoctorSchedulesForDate(docId, d, ScheduleStatus.SCHEDULED);
            }
        }
    }

    public record MultiServiceBookingRequest(
            @jakarta.validation.constraints.NotNull Long profileId,
            @jakarta.validation.constraints.NotNull Long branchId,
            @jakarta.validation.constraints.NotNull LocalDate date,
            @jakarta.validation.constraints.NotNull PreferredSession session,
            Long doctorId,
            @jakarta.validation.constraints.NotEmpty List<Long> serviceIds,
            LocalTime expectedTime,
            String symptoms,
            String requestKey
    ) {}

    public record MultiServiceBookingResult(
            String bookingGroupCode,
            int totalDurationMinutes,
            List<AppointmentView> appointments
    ) {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MultiServiceBookingResult bookMultiService(HttpSession httpSession, MultiServiceBookingRequest request) {
        User user = access.requireUser(httpSession);
        PatientProfile verifiedProfile = access.requireProfile(user, request.profileId());
        requireActiveProfile(verifiedProfile);
        if (request.requestKey() == null || !validKey(request.requestKey())) {
            throw bad("Mã yêu cầu không hợp lệ.");
        }
        PortalBookingRequest existingReq = bookingRequests.findById(request.requestKey()).orElse(null);
        if (existingReq != null && existingReq.getAppointment() != null) {
            Appointment first = existingReq.getAppointment();
            if (!Objects.equals(first.getPatientProfile().getProfileId(), verifiedProfile.getProfileId())) {
                throw conflict("Mã yêu cầu đã được sử dụng cho hồ sơ khác.");
            }
            String existingGroupCode = first.getBookingGroupCode();
            List<AppointmentView> replayViews = existingGroupCode != null
                    ? appointments.findByPatientProfile_ProfileIdOrderByAppointmentDateDescAppointmentIdDesc(
                            verifiedProfile.getProfileId()).stream()
                            .filter(a -> existingGroupCode.equals(a.getBookingGroupCode()))
                            .sorted(java.util.Comparator.comparing(Appointment::getExpectedTime,
                                    java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                            .map(this::view).toList()
                    : List.of(view(first));
            int replayDuration = replayViews.stream()
                    .mapToInt(v -> v.durationMinutes() != null ? v.durationMinutes() : 30)
                    .sum();
            return new MultiServiceBookingResult(existingGroupCode, replayDuration, replayViews);
        }
        if (request.serviceIds() == null || request.serviceIds().isEmpty()) {
            throw bad("Vui lòng chọn 1 dịch vụ khám.");
        }
        if (request.serviceIds().size() > 1) {
            throw bad("Mỗi lần đặt lịch chỉ được chọn tối đa 1 dịch vụ. Nếu muốn đặt thêm dịch vụ khác, vui lòng tạo lượt đặt lịch riêng.");
        }

        PatientProfile profile = patientProfiles != null
                ? patientProfiles.findByIdForUpdate(verifiedProfile.getProfileId()).orElse(verifiedProfile)
                : verifiedProfile;
        refreshIfManaged(profile);
        requireActiveProfile(profile);
        Branch branch = lockedBranches.findByIdForBooking(request.branchId()).orElseThrow(() -> bad("Chi nhánh không tồn tại."));
        refreshIfManaged(branch);
        if (!Boolean.TRUE.equals(branch.getIsActive())) {
            throw bad("Chi nhánh không hoạt động.");
        }

        List<com.cvdcms.entity.Service> resolvedServices = new ArrayList<>();
        int totalDuration = 0;
        for (Long svcId : request.serviceIds()) {
            com.cvdcms.entity.Service svc = requestedService(svcId);
            if (svc == null) {
                throw bad("Dịch vụ không tồn tại hoặc không hỗ trợ đặt trực tuyến.");
            }
            resolvedServices.add(svc);
            totalDuration += AppointmentSlotService.duration(svc);
        }

        policy.ensureBookable(branch, profile, request.date(), request.session(), request.doctorId(),
                null, AppointmentActorType.PATIENT);

        StaffProfile assignedDoctor = doctor(request.doctorId());
        LocalTime resolvedTime = slots.resolveFlexibly(branch, request.date(), request.session(), request.doctorId(),
                totalDuration, null, request.expectedTime(), AppointmentActorType.PATIENT);
        if (resolvedTime == null) {
            throw conflict("Không còn đủ năng lực tiếp nhận cho tổng thời lượng " + totalDuration + " phút trong khung giờ đã chọn.");
        }
        if (assignedDoctor == null) {
            assignedDoctor = slots.autoAssignDoctorForSlot(
                    branch, request.date(), request.session(), resolvedTime, totalDuration, null);
            if (assignedDoctor == null) {
                throw conflict("Không còn bác sĩ khả dụng cho tổng thời lượng " + totalDuration + " phút trong khung giờ đã chọn.");
            }
        } else {
            slots.lockAndValidateDoctorSlot(branch.getBranchId(), assignedDoctor.getStaffId(),
                    request.date(), resolvedTime, totalDuration, null);
        }

        String groupCode = "GRP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        LocalTime validateCursor = resolvedTime;
        for (com.cvdcms.entity.Service svc : resolvedServices) {
            int svcDuration = AppointmentSlotService.duration(svc);
            slots.validateBranchResourceCapacity(branch, svc, request.date(),
                    validateCursor, validateCursor.plusMinutes(svcDuration), null);
            validateCursor = validateCursor.plusMinutes(svcDuration);
        }
        LocalTime cursorTime = resolvedTime;
        List<AppointmentView> createdViews = new ArrayList<>();
        for (int i = 0; i < resolvedServices.size(); i++) {
            com.cvdcms.entity.Service svc = resolvedServices.get(i);
            int svcDuration = AppointmentSlotService.duration(svc);
            Appointment appointment = new Appointment();
            appointment.setAppointmentCode("LH" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
            appointment.setBookingGroupCode(groupCode);
            appointment.setPatientProfile(profile);
            appointment.setBranch(branch);
            appointment.setDoctor(assignedDoctor);
            appointment.setIsDoctorRequested(request.doctorId() != null);
            appointment.setService(svc);
            appointment.setAppointmentDate(request.date());
            appointment.setPreferredSession(request.session());
            appointment.setDurationMinutes(svcDuration);
            appointment.setExpectedTime(cursorTime);
            appointment.syncExpectedEndTime();
            appointment.setInitialSymptoms(clean(request.symptoms()));
            appointment.setSource(AppointmentSource.ONLINE);
            appointment.setStatus(AppointmentStatus.PENDING);
            appointments.saveAndFlush(appointment);
            if (i == 0) {
                PortalBookingRequest bookingRequest = new PortalBookingRequest();
                bookingRequest.setRequestKey(request.requestKey());
                bookingRequest.setAppointment(appointment);
                bookingRequests.save(bookingRequest);
            }
            history(appointment, user.getUserId(), "CREATE", null, snapshot(appointment), "GROUP:" + groupCode);
            if (careEventPublisher != null && appointment.getAppointmentId() != null) {
                careEventPublisher.publishEvent(new com.cvdcms.event.CustomerCareDomainEvents.AppointmentUpsertedCareEvent(appointment.getAppointmentId()));
                careEventPublisher.publishEvent(new com.cvdcms.event.ClinicQueueDomainEvent(
                        "APPOINTMENT_BOOKED", branch.getBranchId(), assignedDoctor.getStaffId(),
                        null, appointment.getAppointmentId(), appointment.getStatus().name(),
                        "Đặt lịch hẹn nhiều dịch vụ #" + appointment.getAppointmentCode(), LocalDateTime.now(CLINIC_ZONE)));
            }
            createdViews.add(view(appointment));
            cursorTime = cursorTime.plusMinutes(svcDuration);
        }
        return new MultiServiceBookingResult(groupCode, totalDuration, createdViews);
    }

    private void refreshIfManaged(Object entity) {
        if (entityManager != null && entity != null && entityManager.contains(entity)) {
            entityManager.refresh(entity);
        }
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
                + "|time=" + a.getExpectedTime() + "|status=" + a.getStatus();
    }
    private AppointmentView view(Appointment a) {
        return new AppointmentView(a.getAppointmentId(), a.getAppointmentCode(), a.getPatientProfile().getProfileId(),
                a.getPatientProfile().getFullName(), a.getBranch().getBranchId(), a.getBranch().getBranchName(),
                id(a.getDoctor()), a.getDoctor() == null ? null : a.getDoctor().getFullName(),
                a.getService() == null ? null : a.getService().getServiceName(), a.getAppointmentDate(),
                a.getPreferredSession(), a.getInitialSymptoms(), a.getStatus(), a.getRescheduleCount(),
                a.getCancelReason(), a.getCreatedAt(), a.getExpectedTime(), a.getDurationMinutes());
    }
    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
