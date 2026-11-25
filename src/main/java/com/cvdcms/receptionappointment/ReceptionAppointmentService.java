package com.cvdcms.receptionappointment;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.patient.PatientAccess;
import com.cvdcms.patient.PatientProfileForm;
import com.cvdcms.patient.PatientSearchPolicy;
import com.cvdcms.patient.PatientService;
import com.cvdcms.patient.PatientView;
import com.cvdcms.patient.ReceptionPatientRecord;
import com.cvdcms.patient.ReceptionPatientRepository;
import com.cvdcms.reception.ReceptionVisitRepository;
import com.cvdcms.repository.*;
import com.cvdcms.service.AppointmentConflictDetectionService;
import com.cvdcms.service.AppointmentLifecycleService;
import com.cvdcms.service.AppointmentPolicyService;
import com.cvdcms.service.AppointmentSlotService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class ReceptionAppointmentService {
    private static final Set<String> CANCEL_REASONS = Set.of("Bệnh nhân đổi kế hoạch",
            "Bệnh nhân không còn nhu cầu", "Trùng lịch", "Bác sĩ không khả dụng", "Khác");
    private final PatientAccess access;
    private final ReceptionAppointmentRepository appointments;
    private final ReceptionAppointmentPatientRepository patients;
    private final ReceptionPatientRepository receptionPatients;
    private final BranchRepository branches;
    private final AppointmentBookingLockRepository lockedBranches;
    private final ServiceRepository services;
    private final StaffProfileRepository staff;
    private final ReceptionAppointmentRules rules;
    private final AppointmentHistoryRepository histories;
    private final CustomerCareTaskRepository careTasks;
    private final AppointmentLifecycleService lifecycle;
    private final AppointmentConflictDetectionService conflictDetection;
    private final AppointmentSlotService slots;
    private final PatientService patientService;
    private final Validator validator;
    @Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher careEventPublisher;

    @Autowired(required = false)
    private ReceptionVisitRepository receptionVisits;

    @Autowired(required = false)
    private ClinicalQueueTicketRepository clinicalQueueTickets;

    @Autowired(required = false)
    private PatientProfileRepository patientProfiles;

    @Autowired(required = false)
    private StaffScheduleRepository staffSchedules;

    @Autowired(required = false)
    private SystemNotificationRepository notifications;

    @Autowired(required = false)
    private com.cvdcms.service.ResourceBookingTransactionService resourceBookingTransactionService;

    @Autowired(required = false)
    private com.cvdcms.service.ResourceAvailabilityService resourceAvailabilityService;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    public ReceptionAppointmentService(PatientAccess access, ReceptionAppointmentRepository appointments,
            ReceptionAppointmentPatientRepository patients, ReceptionPatientRepository receptionPatients,
            BranchRepository branches, AppointmentBookingLockRepository lockedBranches,
            ServiceRepository services, StaffProfileRepository staff,
            ReceptionAppointmentRules rules, AppointmentHistoryRepository histories,
            CustomerCareTaskRepository careTasks, AppointmentLifecycleService lifecycle,
            AppointmentConflictDetectionService conflictDetection, AppointmentSlotService slots,
            PatientService patientService, Validator validator) {
        this.access = access; this.appointments = appointments; this.patients = patients;
        this.receptionPatients = receptionPatients;
        this.branches = branches; this.lockedBranches = lockedBranches; this.services = services;
        this.staff = staff; this.rules = rules; this.histories = histories; this.careTasks = careTasks;
        this.lifecycle = lifecycle; this.conflictDetection = conflictDetection;
        this.slots = slots;
        this.patientService = patientService;
        this.validator = validator;
    }

    public record CreateRequest(@NotNull Long branchId, Long profileId,
            @NotNull LocalDate date, PreferredSession session, Long doctorId, Long serviceId,
            @Size(max = 500) String symptoms, LocalTime expectedTime,
            String patientName, String patientPhone, String patientAddress, String customerSource,
            Boolean isTentative, String internalNote,
            LocalDate patientDob, String patientGender,
            String guardianName, String guardianPhone, String guardianRelationship,
            String idCardNumber, Boolean confirmDuplicate, @Min(15) @Max(240) Integer durationMinutes) {
        public CreateRequest(Long branchId, Long profileId, LocalDate date, PreferredSession session,
                Long doctorId, Long serviceId, String symptoms, LocalTime expectedTime,
                String patientName, String patientPhone, String patientAddress, String customerSource,
                Boolean isTentative, String internalNote, LocalDate patientDob, String patientGender,
                String guardianName, String guardianPhone, String guardianRelationship,
                String idCardNumber, Boolean confirmDuplicate) {
            this(branchId, profileId, date, session, doctorId, serviceId, symptoms, expectedTime,
                    patientName, patientPhone, patientAddress, customerSource, isTentative, internalNote,
                    patientDob, patientGender, guardianName, guardianPhone, guardianRelationship, idCardNumber, confirmDuplicate, null);
        }
        public CreateRequest(Long branchId, Long profileId, LocalDate date, PreferredSession session,
                Long doctorId, Long serviceId, String symptoms, LocalTime expectedTime) {
            this(branchId, profileId, date, session, doctorId, serviceId, symptoms, expectedTime,
                    null, null, null, null, false, null,
                    null, null, null, null, null, null, false);
        }
        public CreateRequest(Long branchId, Long profileId, LocalDate date, PreferredSession session,
                Long doctorId, Long serviceId, String symptoms) {
            this(branchId, profileId, date, session, doctorId, serviceId, symptoms, null);
        }
        public CreateRequest(Long branchId, Long profileId, LocalDate date, PreferredSession session,
                Long doctorId, Long serviceId, String symptoms, LocalTime expectedTime,
                String patientName, String patientPhone, String patientAddress, String customerSource,
                Boolean isTentative, String internalNote) {
            this(branchId, profileId, date, session, doctorId, serviceId, symptoms, expectedTime,
                    patientName, patientPhone, patientAddress, customerSource, isTentative, internalNote,
                    null, null, null, null, null, null, false);
        }
    }
    public record QuickStatusRequest(String status, String note) {}
    public record SlotOption(LocalTime time, int remaining) {}
    public record UpdateRequest(@NotNull LocalDate date, @NotNull PreferredSession session,
            Long doctorId, Long serviceId, @Size(max = 500) String symptoms,
            @NotBlank @Size(max = 255) String reason, LocalTime expectedTime) {
        public UpdateRequest(LocalDate date, PreferredSession session, Long doctorId, Long serviceId,
                String symptoms, String reason) {
            this(date, session, doctorId, serviceId, symptoms, reason, null);
        }
    }
    public record ConfirmRequest(Long doctorId) {}
    public record CancelRequest(@NotBlank String reasonCode, @Size(max = 180) String note) {}
    public record BranchOption(Long id, String name) {}
    public record ServiceOption(Long id, String name, int durationMinutes) {}
    public record PatientOption(Long id, String code, String name, String phone,
            LocalDate dob, String gender, boolean crossBranch) {
        public PatientOption(Long id, String code, String name, String phone, LocalDate dob, String gender) {
            this(id, code, name, phone, dob, gender, false);
        }
        public PatientOption(Long id, String code, String name, String phone) {
            this(id, code, name, phone, null, null, false);
        }
    }
    public record Options(List<BranchOption> branches, List<ServiceOption> services) {}
    public record AppointmentView(Long id, String code, Long profileId, String patientCode,
            String patientName, String patientPhone, Long branchId, String branchName,
            Long doctorId, String doctorName, Long serviceId, String serviceName,
            LocalDate date, PreferredSession session, String symptoms,
            AppointmentSource source, AppointmentStatus status, int rescheduleCount,
            String cancelReason, boolean doctorUnavailable, LocalDateTime createdAt,
            String conflictNotes, LocalTime expectedTime, Integer durationMinutes,
            LocalDate patientDob, String patientGender) {
        public AppointmentView(Long id, String code, Long profileId, String patientCode,
                               String patientName, String patientPhone, Long branchId, String branchName,
                               Long doctorId, String doctorName, Long serviceId, String serviceName,
                               LocalDate date, PreferredSession session, String symptoms,
                               AppointmentSource source, AppointmentStatus status, int rescheduleCount,
                               String cancelReason, boolean doctorUnavailable, LocalDateTime createdAt) {
            this(id, code, profileId, patientCode, patientName, patientPhone, branchId, branchName,
                    doctorId, doctorName, serviceId, serviceName, date, session, symptoms,
                    source, status, rescheduleCount, cancelReason, doctorUnavailable, createdAt, null, null, 30, null, null);
        }

        public String getPatientToken() {
            return com.cvdcms.security.UrlIdCodec.shared().encodePatient(profileId);
        }

        public boolean isNeedsReassignment() {
            return doctorUnavailable && (status == AppointmentStatus.PENDING || status == AppointmentStatus.CONFIRMED);
        }

        public String getWorkflowFlag() {
            return isNeedsReassignment() ? "NEEDS_REASSIGNMENT" : null;
        }
    }
    public record HistoryView(String action, String oldValue, String newValue, String reason, LocalDateTime createdAt) {}
    public record Detail(AppointmentView appointment, List<HistoryView> history) {}
    public record Dashboard(LocalDate date, long total, long pending, long confirmed,
            long checkedIn, long noShow, long cancelled, List<AppointmentView> appointments) {
        public Dashboard(LocalDate date, long total, long pending, long confirmed,
                long checkedIn, long noShow, List<AppointmentView> appointments) {
            this(date, total, pending, confirmed, checkedIn, noShow,
                    appointments.stream().filter(a -> a.status() == AppointmentStatus.CANCELLED).count(),
                    appointments);
        }
    }

    @Transactional(readOnly = true)
    public Options options(HttpSession session) {
        PatientAccess.Scope scope = access.require(session);
        List<Branch> allowed = scope.admin() ? branches.findByIsActiveTrueOrderByBranchNameAsc()
                : (scope.branchIds() != null && !scope.branchIds().isEmpty()
                        ? branches.findAllById(scope.branchIds()).stream().filter(b -> Boolean.TRUE.equals(b.getIsActive())).toList()
                        : branches.findById(scope.branchId()).filter(b -> Boolean.TRUE.equals(b.getIsActive())).stream().toList());
        List<BranchOption> branchOptions = allowed.stream().map(b -> new BranchOption(b.getBranchId(), b.getBranchName())).toList();
        List<ServiceOption> serviceOptions = services.findByStatusOrderByCategory_DisplayOrderAscServiceNameAsc(ServiceStatus.ACTIVE)
                .stream().filter(s -> Boolean.TRUE.equals(s.getCategory().getIsActive())
                        && s.getServiceType() != com.cvdcms.entity.enums.ServiceType.PRODUCT)
                .map(s -> new ServiceOption(s.getServiceId(), s.getServiceName(),
                        AppointmentSlotService.duration(s))).toList();
        return new Options(branchOptions, serviceOptions);
    }

    @Transactional(readOnly = true)
    public List<PatientOption> searchPatients(HttpSession session, Long branchId, String query) {
        PatientAccess.Scope scope = access.require(session);
        List<Long> scopedBranchIds = resolvePatientSearchBranches(scope, branchId);
        String q = PatientSearchPolicy.normalize(query);

        if (q.isEmpty()) {
            List<PatientProfile> list = scopedBranchIds == null
                    ? patients.findRecentBookable(PageRequest.of(0, 30))
                    : patients.findRecentBookableByBranches(scopedBranchIds, PageRequest.of(0, 30));
            return list.stream().map(p -> toPatientOption(p, false)).toList();
        }

        PatientSearchPolicy.ExactIdentifierType exactType = PatientSearchPolicy.classifyExactIdentifier(q);
        if (exactType != PatientSearchPolicy.ExactIdentifierType.NONE) {
            List<PatientProfile> exactCandidates = switch (exactType) {
                case PHONE -> patients.findExactActiveByPhone(q);
                case ID_CARD -> patients.findExactActiveByIdCard(q);
                case PATIENT_CODE -> patients.findExactActiveByPatientCode(q);
                case NONE -> List.of();
            };
            Optional<PatientProfile> matched = PatientSearchPolicy.resolveSingleConsistentExactMatch(exactType, q, exactCandidates);
            if (matched.isEmpty()) {
                return List.of();
            }
            PatientProfile profile = matched.get();
            boolean inScope = scopedBranchIds == null || isProfileAccessibleInBranches(profile, scopedBranchIds);
            return List.of(toPatientOption(profile, !inScope));
        }

        List<PatientProfile> list = scopedBranchIds == null
                ? patients.search(q).stream().limit(30).toList()
                : patients.searchByBranches(scopedBranchIds, q, PageRequest.of(0, 30));
        return list.stream().map(p -> toPatientOption(p, false)).toList();
    }

    private List<Long> resolvePatientSearchBranches(PatientAccess.Scope scope, Long requestedBranchId) {
        if (requestedBranchId != null) {
            Branch requested = requireBranch(scope, requestedBranchId);
            return scope.admin() ? null : List.of(requested.getBranchId());
        }
        if (scope.admin()) {
            return null;
        }
        Long currentBranchId = scope.branchId();
        if (currentBranchId == null || !scope.permits(currentBranchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Tài khoản chưa được phân công chi nhánh hợp lệ để tìm kiếm bệnh nhân.");
        }
        Branch currentBranch = branches.findById(currentBranchId)
                .filter(b -> Boolean.TRUE.equals(b.getIsActive()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Chi nhánh hiện tại không hoạt động hoặc không hợp lệ."));
        return List.of(currentBranch.getBranchId());
    }

    private boolean isProfileAccessibleInBranches(PatientProfile p, List<Long> branchIds) {
        if (p.getPreferredBranch() != null && p.getPreferredBranch().getBranchId() != null
                && branchIds.contains(p.getPreferredBranch().getBranchId())) {
            return true;
        }
        return p.getProfileId() != null && patients.isProfileAccessibleInBranches(p.getProfileId(), branchIds);
    }

    private PatientOption toPatientOption(PatientProfile p, boolean crossBranch) {
        return new PatientOption(
                p.getProfileId(),
                p.getPatientCode(),
                p.getFullName(),
                p.getPhoneNumber(),
                p.getDob(),
                p.getGender(),
                crossBranch
        );
    }

    @Transactional(readOnly = true)
    public ReceptionAppointmentRules.Availability availability(HttpSession session, Long branchId,
            LocalDate date, PreferredSession period, Long excludeId) {
        PatientAccess.Scope scope = access.require(session);
        Branch branch = requireBranch(scope, branchId);
        if (excludeId != null) {
            Appointment existing = owned(scope, excludeId);
            if (!existing.getBranch().getBranchId().equals(branchId)) throw bad("Không thể đổi chi nhánh của lịch này.");
        }
        return rules.availability(branch, date, period, excludeId);
    }

    @Transactional(readOnly = true)
    public List<AppointmentPolicyService.SessionLoadSummary> sessionLoad(HttpSession session, Long branchId, LocalDate date) {
        PatientAccess.Scope scope = access.require(session);
        Branch branch = requireBranch(scope, branchId);
        return rules.dayLoadOverview(branch, date);
    }

    @Transactional(readOnly = true)
    public List<AppointmentPolicyService.SessionWindowInfo> sessions(HttpSession session, Long branchId) {
        PatientAccess.Scope scope = access.require(session);
        Branch branch = requireBranch(scope, branchId);
        return rules.sessionWindows(branch);
    }

    @Transactional(readOnly = true)
    public List<ReceptionAppointmentRules.DoctorOption> doctors(HttpSession session, Long branchId, LocalDate date) {
        PatientAccess.Scope scope = access.require(session);
        return rules.doctorsForDate(requireBranch(scope, branchId), date);
    }

    @Transactional(readOnly = true)
    public List<SlotOption> slots(HttpSession session, Long branchId, LocalDate date,
            PreferredSession period, Long doctorId, Long serviceId, Long excludeId) {
        PatientAccess.Scope scope = access.require(session);
        Branch branch = requireBranch(scope, branchId);
        if (excludeId != null && !owned(scope, excludeId).getBranch().getBranchId().equals(branchId))
            throw bad("Không thể đổi chi nhánh của lịch này.");
        int duration = AppointmentSlotService.duration(service(serviceId));
        if (period == null && doctorId != null) {
            return slots.getDoctorAvailability(branchId, doctorId, date, duration, excludeId)
                    .suggestedSlots().stream()
                    .map(s -> new SlotOption(s.startTime(), 1))
                    .toList();
        }
        return slots.available(branch, date, period, doctorId,
                duration, excludeId, AppointmentActorType.RECEPTIONIST).stream()
                .map(s -> new SlotOption(s.time(), s.remaining())).toList();
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(HttpSession session, LocalDate date, Long branchId, Long doctorId) {
        PatientAccess.Scope scope = access.require(session);
        if (date == null) date = LocalDate.now(ReceptionAppointmentRules.ZONE);
        Long targetBranch = branchId;
        if (targetBranch == null && session != null) {
            Object sId = session.getAttribute("currentBranchId");
            if (sId instanceof Long sid && scope.permits(sid)) {
                targetBranch = sid;
            }
        }
        Long effectiveBranch = scopedBranch(scope, targetBranch);
        List<AppointmentView> list = appointments.findInRange(date, date, effectiveBranch, doctorId, null)
                .stream().map(this::view).toList();
        return new Dashboard(date, list.size(), count(list, AppointmentStatus.PENDING),
                count(list, AppointmentStatus.CONFIRMED), count(list, AppointmentStatus.CHECKED_IN),
                count(list, AppointmentStatus.NO_SHOW), count(list, AppointmentStatus.CANCELLED), list);
    }

    @Transactional(readOnly = true)
    public List<AppointmentView> calendar(HttpSession session, LocalDate start, LocalDate end,
            Long branchId, Long doctorId, AppointmentStatus status) {
        PatientAccess.Scope scope = access.require(session);
        if (start == null || end == null || end.isBefore(start) || end.isAfter(start.plusDays(42)))
            throw bad("Khoảng xem lịch không hợp lệ hoặc vượt quá 43 ngày.");
        Long targetBranch = branchId;
        if (targetBranch == null && session != null) {
            Object sId = session.getAttribute("currentBranchId");
            if (sId instanceof Long sid && scope.permits(sid)) {
                targetBranch = sid;
            }
        }
        return appointments.findInRange(start, end, scopedBranch(scope, targetBranch), doctorId, status)
                .stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<AppointmentView> noShows(HttpSession session, Long branchId) {
        PatientAccess.Scope scope = access.require(session);
        Long targetBranch = branchId;
        if (targetBranch == null && session != null) {
            Object sId = session.getAttribute("currentBranchId");
            if (sId instanceof Long sid && scope.permits(sid)) {
                targetBranch = sid;
            }
        }
        LocalDate today = LocalDate.now(ReceptionAppointmentRules.ZONE);
        return appointments.findInRange(today.minusDays(7), today, scopedBranch(scope, targetBranch), null,
                        AppointmentStatus.NO_SHOW).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public Detail detail(HttpSession session, Long id) {
        Appointment a = owned(access.require(session), id);
        List<HistoryView> events = histories.findByAppointment_AppointmentIdOrderByCreatedAtAscHistoryIdAsc(id)
                .stream().map(h -> new HistoryView(h.getAction(), h.getOldValue(), h.getNewValue(),
                        h.getReason(), h.getCreatedAt())).toList();
        return new Detail(view(a), events);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AppointmentView create(HttpSession session, CreateRequest request) {
        PatientAccess.Scope scope = access.require(session);
        if (request.doctorId() != null && !access.canAssignDoctor(scope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Lễ tân không được tự ý gán bác sĩ thủ công. Hệ thống sẽ tự động phân bổ bác sĩ.");
        }
        Long branchId = request.branchId();
        if (branchId == null || !scope.permits(branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        Branch branch;
        PatientProfile profile;
        if (request.profileId() != null) {
            branch = requireBranch(scope, branchId);
            profile = patients.findBookable(request.profileId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hồ sơ không thuộc phạm vi chi nhánh."));
            // Giữ nguyên thứ tự khóa chuẩn cho hồ sơ đã tồn tại: 1. PatientProfile -> 2. Branch -> 3. StaffProfile
            if (patientProfiles != null && profile.getProfileId() != null) {
                profile = patientProfiles.findByIdForUpdate(profile.getProfileId()).orElse(profile);
                refreshIfManaged(profile);
            }
            branch = lockBranch(scope, branchId);
        } else if (request.patientName() != null && !request.patientName().isBlank()) {
            PatientProfileForm form = buildAndValidateNewPatientForm(request, branchId);
            // Nhánh tạo hồ sơ mới: khóa Branch (FOR UPDATE) trước INSERT patient_profiles để tránh FK S-lock -> X-lock deadlock.
            branch = lockBranch(scope, branchId);
            // Tái sử dụng PatientService chuẩn: kiểm tra quyền chi nhánh, phát hiện trùng, bắt buộc người giám hộ nếu < 18 tuổi (BR-PAT-09), ghi audit log
            PatientView createdProfile = patientService.create(session, form);
            profile = patients.findById(createdProfile.id())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Không thể tải hồ sơ sau khi tạo."));
            if (patientProfiles != null && profile.getProfileId() != null) {
                profile = patientProfiles.findByIdForUpdate(profile.getProfileId()).orElse(profile);
                refreshIfManaged(profile);
            }
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vui lòng chọn hồ sơ bệnh nhân hoặc nhập đầy đủ thông tin bệnh nhân mới hợp lệ.");
        }

        PreferredSession targetSession = request.session();
        if (targetSession == null || targetSession == PreferredSession.ANY) {
            if (request.expectedTime() != null && request.expectedTime().isAfter(LocalTime.of(12, 0))) {
                targetSession = PreferredSession.AFTERNOON;
            } else {
                targetSession = PreferredSession.MORNING;
            }
        }

        // Bắt buộc xác minh điều kiện đặt lịch (ngày/giờ, quyền, trạng thái, khả dụng ca trực) - không bypass cho bất kỳ cờ nào
        rules.ensureBookable(branch, profile, request.date(), targetSession, request.doctorId(), null);

        String notes = clean(request.symptoms());
        if (request.internalNote() != null && !request.internalNote().isBlank()) {
            notes = (notes == null ? "" : notes + " - ") + "[Ghi chú: " + request.internalNote().trim() + "]";
        }

        if (Boolean.TRUE.equals(branch.getResourceEngineEnabled())) {
            if (request.serviceId() == null) {
                throw bad("Chi nhánh đã bật Resource Engine: lịch hẹn mới bắt buộc phải chọn dịch vụ.");
            }
            if (resourceBookingTransactionService == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Resource Booking Engine chưa sẵn sàng.");
            }
            com.cvdcms.entity.Service svc = service(request.serviceId());
            int svcDuration = AppointmentSlotService.duration(svc);
            LocalTime resolvedTime = request.expectedTime() != null ? request.expectedTime()
                    : slots.resolveFlexibly(branch, request.date(), targetSession, request.doctorId(),
                            svcDuration, null, null, AppointmentActorType.RECEPTIONIST);
            if (resolvedTime == null) {
                throw bad("Vui lòng chọn khung giờ khám cụ thể.");
            }
            AppointmentStatus initialStatus = Boolean.TRUE.equals(request.isTentative())
                    ? AppointmentStatus.PENDING : AppointmentStatus.CONFIRMED;
            var resResult = resourceBookingTransactionService.bookInExistingTransaction(
                    new com.cvdcms.DTO.availability.ResourceBookingRequest(
                            branch.getBranchId(), profile.getProfileId(), svc.getServiceId(),
                            request.date(), resolvedTime, request.doctorId(), request.doctorId() == null,
                            null, targetSession, AppointmentSource.RECEPTIONIST, notes, null, false),
                    initialStatus);
            Appointment created = appointments.findById(resResult.appointmentId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR));
            history(created, scope.user().getUserId(), "CREATE", null, snapshot(created), null);
            if (careEventPublisher != null) {
                careEventPublisher.publishEvent(new com.cvdcms.event.CustomerCareDomainEvents.AppointmentUpsertedCareEvent(created.getAppointmentId()));
                careEventPublisher.publishEvent(new com.cvdcms.event.ClinicQueueDomainEvent(
                        "APPOINTMENT_BOOKED", branch.getBranchId(), resResult.doctorId(),
                        null, created.getAppointmentId(), created.getStatus().name(),
                        "Lịch hẹn mới #" + created.getAppointmentCode(), LocalDateTime.now(ReceptionAppointmentRules.ZONE)));
            }
            return view(created);
        }

        Appointment a = new Appointment();
        a.setAppointmentCode(code()); a.setBranch(branch); a.setPatientProfile(profile);
        a.setAppointmentDate(request.date()); a.setPreferredSession(targetSession);
        a.setDoctor(doctor(request.doctorId()));
        a.setService(service(request.serviceId()));

        a.setInitialSymptoms(notes);
        if (request.durationMinutes() != null && (request.durationMinutes() < 15 || request.durationMinutes() > 240))
            throw bad("Thời lượng dự kiến phải từ 15 đến 240 phút.");
        a.setDurationMinutes(request.durationMinutes() != null ? request.durationMinutes() : AppointmentSlotService.duration(a.getService()));

        LocalTime expectedTime = null;
        if (request.expectedTime() != null) {
            // Bắt buộc xác minh khung giờ hợp lệ, không fallback sang raw time nếu kiểm tra thất bại
            expectedTime = slots.resolveFlexibly(branch, request.date(), targetSession, request.doctorId(),
                    a.getDurationMinutes(), null, request.expectedTime(), AppointmentActorType.RECEPTIONIST);
        } else if (request.doctorId() != null) {
            // Lịch linh hoạt (expectedTime == null) có bác sĩ chỉ định vẫn phải khóa bác sĩ/ca trực & kiểm tra doctorCapacity
            slots.lockAndValidateDoctorSession(branch.getBranchId(), request.doctorId(),
                    request.date(), targetSession, null);
        }
        if (a.getDoctor() == null && expectedTime != null) {
            StaffProfile autoAssigned = slots.autoAssignDoctorForSlot(
                    branch, request.date(), targetSession, expectedTime, a.getDurationMinutes(), null);
            if (autoAssigned != null) {
                a.setDoctor(autoAssigned);
            }
        }
        if (expectedTime != null) {
            slots.validateBranchResourceCapacity(branch, a.getService(), request.date(),
                    expectedTime, expectedTime.plusMinutes(a.getDurationMinutes()), null);
        }
        a.setExpectedTime(expectedTime);
        a.syncExpectedEndTime();
        a.setSource(AppointmentSource.RECEPTIONIST);
        a.setStatus(Boolean.TRUE.equals(request.isTentative()) ? AppointmentStatus.PENDING : AppointmentStatus.CONFIRMED);
        appointments.saveAndFlush(a);
        history(a, scope.user().getUserId(), "CREATE", null, snapshot(a), null);
        if (careEventPublisher != null && a.getAppointmentId() != null) {
            careEventPublisher.publishEvent(new com.cvdcms.event.CustomerCareDomainEvents.AppointmentUpsertedCareEvent(a.getAppointmentId()));
            careEventPublisher.publishEvent(new com.cvdcms.event.ClinicQueueDomainEvent(
                    "APPOINTMENT_BOOKED", branch.getBranchId(),
                    a.getDoctor() != null ? a.getDoctor().getStaffId() : null,
                    null, a.getAppointmentId(), a.getStatus().name(),
                    "Lịch hẹn mới #" + a.getAppointmentCode(), LocalDateTime.now(ReceptionAppointmentRules.ZONE)));
        }
        return view(a);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AppointmentView quickStatus(HttpSession session, Long id, QuickStatusRequest request) {
        PatientAccess.Scope scope = access.require(session);
        String rawStatus = request.status() != null ? request.status().trim() : "";
        AppointmentStatus targetStatus;
        try {
            targetStatus = AppointmentStatus.valueOf(rawStatus.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trạng thái lịch hẹn không hợp lệ: " + rawStatus);
        }

        AppointmentLockSnapshot snap = readAndAuthorizeSnapshot(scope, id);
        Appointment a = targetStatus == AppointmentStatus.CONFIRMED
                ? lockAppointmentWithDoctorScope(scope, snap, snap.doctorId(), snap.appointmentDate())
                : lockAppointment(scope, snap);
        String old = snapshot(a);

        String note = request.note() != null ? request.note().trim() : "";

        if (targetStatus == AppointmentStatus.CANCELLED) {
            if (!lifecycle.canTransition(a.getStatus(), AppointmentStatus.CANCELLED, AppointmentLifecycleAction.CANCEL)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Lịch hẹn ở trạng thái " + a.getStatus() + " không thể hủy.");
            }
            String reason = !note.isEmpty() ? note : "Bệnh nhân đổi kế hoạch";
            if (resourceBookingTransactionService != null) {
                resourceBookingTransactionService.releaseReservationsOnCancelOrNoShow(
                        id, ReservationStatus.CANCELLED, "APPOINTMENT_CANCELLED", LocalDateTime.now(ReceptionAppointmentRules.ZONE));
            }
            lifecycle.transition(a, AppointmentStatus.CANCELLED, AppointmentLifecycleAction.CANCEL);
            a.setCancelReason(reason);
            a.setCancelledBy(scope.user().getUserId());
            a.setCancelledAt(LocalDateTime.now(ReceptionAppointmentRules.ZONE));
            for (CustomerCareTask task : careTasks.findByAppointment_AppointmentId(id)) {
                if (task.getTaskType() == CustomerCareTaskType.APPOINTMENT_REMINDER
                        && task.getStatus() != CustomerCareTaskStatus.COMPLETED) {
                    task.setStatus(CustomerCareTaskStatus.CANCELLED);
                }
            }
            appointments.saveAndFlush(a);
            history(a, scope.user().getUserId(), "CANCEL", old, snapshot(a), reason);
            return view(a);
        }

        if (targetStatus == AppointmentStatus.CONFIRMED) {
            return confirmLocked(scope, a, a.getDoctor() == null ? null : a.getDoctor().getStaffId());
        }

        if (targetStatus == AppointmentStatus.NO_SHOW) {
            if (!lifecycle.canTransition(a.getStatus(), AppointmentStatus.NO_SHOW, AppointmentLifecycleAction.MARK_NO_SHOW)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ đánh dấu không đến với lịch hẹn đã xác nhận.");
            }
            LocalDateTime now = LocalDateTime.now(ReceptionAppointmentRules.ZONE);
            if (!isNoShowCutoffPassed(a, now))
                throw conflict("Chỉ đánh dấu không đến sau khi quá 30 phút kể từ giờ hẹn hoặc buổi khám đã kết thúc.");
            applyNoShow(a, now, scope.user().getUserId(), !note.isEmpty() ? note : "Đánh dấu khách không đến");
            return view(a);
        }

        if (targetStatus == AppointmentStatus.CHECKED_IN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Không thể đổi trực tiếp sang Đã đến bằng đổi trạng thái nhanh. Vui lòng thực hiện tiếp đón tại Bàn tiếp đón (FT07) để tạo lượt khám và cấp số thứ tự hợp lệ.");
        }

        if (targetStatus == AppointmentStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Lịch hẹn chỉ được hoàn tất tự động sau khi hoàn thành lượt khám tại ghế.");
        }

        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Không hỗ trợ thao tác chuyển trạng thái này qua đổi trạng thái nhanh.");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AppointmentView update(HttpSession session, Long id, UpdateRequest request) {
        PatientAccess.Scope scope = access.require(session);
        AppointmentLockSnapshot snap = readAndAuthorizeSnapshot(scope, id);
        if (snap.status() != AppointmentStatus.PENDING && snap.status() != AppointmentStatus.CONFIRMED) {
            throw conflict("Lịch đã check-in, hoàn thành, hủy hoặc không đến không thể sửa.");
        }
        Long oldDoctorId = snap.doctorId();
        if (request.doctorId() != null && oldDoctorId == null && !access.canAssignDoctor(scope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Lễ tân không được tự ý gán hoặc thay đổi bác sĩ thủ công.");
        }
        Long targetDoctorId = request.doctorId() != null ? request.doctorId() : oldDoctorId;

        // Global lock hierarchy: PatientProfile -> Branch -> StaffProfile (staffId ASC) -> StaffSchedule -> Appointment
        Appointment a = lockAppointmentWithDoctorScope(scope, snap, targetDoctorId, request.date());
        mutable(a);
        activeProfile(a.getPatientProfile());
        Long lockedCurrentDoctorId = a.getDoctor() != null ? a.getDoctor().getStaffId() : null;
        boolean wasNeedingReassignment = isDoctorReassignmentNeeded(a);
        if (request.doctorId() != null && !Objects.equals(lockedCurrentDoctorId, request.doctorId())
                && !access.canAssignDoctor(scope) && !wasNeedingReassignment) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Lễ tân không được tự ý gán hoặc thay đổi bác sĩ thủ công.");
        }
        Long effectiveTargetDoctorId = request.doctorId() != null ? request.doctorId() : lockedCurrentDoctorId;
        rules.ensureBookable(a.getBranch(), a.getPatientProfile(), request.date(), request.session(),
                effectiveTargetDoctorId, id);
        String old = snapshot(a);
        boolean doctorChanged = !Objects.equals(lockedCurrentDoctorId, effectiveTargetDoctorId);
        boolean moved = !Objects.equals(a.getAppointmentDate(), request.date())
                || a.getPreferredSession() != request.session()
                || request.expectedTime() != null && !Objects.equals(a.getExpectedTime(), request.expectedTime());
        LocalTime requestedTime = request.expectedTime() != null ? request.expectedTime()
                : Objects.equals(a.getAppointmentDate(), request.date()) && a.getPreferredSession() == request.session()
                    ? a.getExpectedTime() : null;
        if (Boolean.TRUE.equals(a.getBranch().getResourceEngineEnabled())) {
            if (resourceBookingTransactionService == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Resource Booking Engine chưa sẵn sàng.");
            }
            Long effectiveServiceId = request.serviceId() != null ? request.serviceId()
                    : (a.getService() != null ? a.getService().getServiceId() : null);
            if (effectiveServiceId == null) {
                throw conflict("Chi nhánh đã bật Resource Engine: lịch hẹn cần dịch vụ hợp lệ để đặt giữ tài nguyên.");
            }
            LocalTime resolvedEngineTime = requestedTime;
            if (resolvedEngineTime == null) {
                com.cvdcms.entity.Service svcForResolve = service(effectiveServiceId);
                resolvedEngineTime = slots.resolveFlexibly(
                        a.getBranch(), request.date(), request.session(), effectiveTargetDoctorId,
                        AppointmentSlotService.duration(svcForResolve), id, null, AppointmentActorType.RECEPTIONIST);
            }
            if (resolvedEngineTime == null) {
                throw conflict("Chi nhánh đã bật Resource Engine: vui lòng chọn khung giờ cụ thể khả dụng.");
            }
            boolean allowAuto = effectiveTargetDoctorId == null;
            String releaseReason = doctorChanged ? "DOCTOR_REASSIGNED"
                    : (request.serviceId() != null && (a.getService() == null || !Objects.equals(a.getService().getServiceId(), request.serviceId()))
                            ? "SERVICE_CHANGED" : "RESCHEDULED");
            var resResult = resourceBookingTransactionService.rescheduleInExistingTransaction(
                    com.cvdcms.DTO.availability.ResourceRescheduleRequest.reschedule(
                            id, a.getBranch().getBranchId(), request.serviceId(),
                            request.date(), resolvedEngineTime, request.session(),
                            effectiveTargetDoctorId, allowAuto,
                            releaseReason, a.getStatus(), moved));
            Appointment updated = appointments.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            updated.setInitialSymptoms(clean(request.symptoms()));
            appointments.saveAndFlush(updated);
            boolean resolvedDoctorChanged = !Objects.equals(lockedCurrentDoctorId, resResult.doctorId());
            String historyReason = clean(request.reason());
            if (historyReason == null && wasNeedingReassignment && (resolvedDoctorChanged || moved)) {
                historyReason = "Điều phối đổi bác sĩ hoặc dời lịch do bác sĩ trước đó không khả dụng";
            }
            history(updated, scope.user().getUserId(), moved ? "RESCHEDULE" : "UPDATE", old,
                    snapshot(updated), historyReason);
            updateReminders(updated, moved);
            if (wasNeedingReassignment && (resolvedDoctorChanged || moved)) {
                notifyPatientReassignment(updated, updated.getDoctor(), moved);
            }
            return view(updated);
        }
        StaffProfile targetDoctor = doctor(effectiveTargetDoctorId);
        com.cvdcms.entity.Service targetService = service(request.serviceId());
        int targetDuration = AppointmentSlotService.duration(targetService);
        LocalTime expectedTime = null;
        if (requestedTime != null) {
            expectedTime = slots.resolveFlexibly(a.getBranch(), request.date(), request.session(), effectiveTargetDoctorId,
                    targetDuration, id, requestedTime, AppointmentActorType.RECEPTIONIST);
        } else if (effectiveTargetDoctorId != null) {
            slots.lockAndValidateDoctorSession(a.getBranch().getBranchId(), effectiveTargetDoctorId,
                    request.date(), request.session(), id);
        }
        if (targetDoctor == null && expectedTime != null) {
            StaffProfile autoAssigned = slots.autoAssignDoctorForSlot(
                    a.getBranch(), request.date(), request.session(), expectedTime, targetDuration, id);
            if (autoAssigned != null) {
                targetDoctor = autoAssigned;
                doctorChanged = !Objects.equals(lockedCurrentDoctorId, autoAssigned.getStaffId());
            }
        }
        if (expectedTime != null) {
            slots.validateBranchResourceCapacity(a.getBranch(), targetService, request.date(),
                    expectedTime, expectedTime.plusMinutes(targetDuration), id);
        }
        a.setAppointmentDate(request.date()); a.setPreferredSession(request.session());
        a.setDoctor(targetDoctor); a.setService(targetService);
        a.setInitialSymptoms(clean(request.symptoms()));
        a.setDurationMinutes(targetDuration);
        a.setExpectedTime(expectedTime);
        a.syncExpectedEndTime();
        if (moved) a.setRescheduleCount(a.getRescheduleCount() + 1);
        lifecycle.transition(a, a.getStatus(), AppointmentLifecycleAction.RESCHEDULE);
        appointments.saveAndFlush(a);
        String historyReason = clean(request.reason());
        if (historyReason == null && wasNeedingReassignment && (doctorChanged || moved)) {
            historyReason = "Điều phối đổi bác sĩ hoặc dời lịch do bác sĩ trước đó không khả dụng";
        }
        history(a, scope.user().getUserId(), moved ? "RESCHEDULE" : "UPDATE", old,
                snapshot(a), historyReason);
        updateReminders(a, moved);
        if (wasNeedingReassignment && (doctorChanged || moved)) {
            notifyPatientReassignment(a, targetDoctor, moved);
        }
        return view(a);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AppointmentView confirm(HttpSession session, Long id, ConfirmRequest request) {
        PatientAccess.Scope scope = access.require(session);
        AppointmentLockSnapshot snap = readAndAuthorizeSnapshot(scope, id);
        if (snap.status() != AppointmentStatus.PENDING) {
            throw conflict("Chỉ có thể xác nhận lịch đang chờ lễ tân.");
        }
        Long currentDoctorId = snap.doctorId();
        if (request.doctorId() != null && currentDoctorId == null && !access.canAssignDoctor(scope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Lễ tân không được tự ý gán bác sĩ thủ công khi xác nhận lịch.");
        }
        Long targetDoctorId = request.doctorId() != null ? request.doctorId() : currentDoctorId;
        Appointment a = lockAppointmentWithDoctorScope(scope, snap, targetDoctorId, snap.appointmentDate());
        return confirmLocked(scope, a, request.doctorId());
    }

    private AppointmentView confirmLocked(PatientAccess.Scope scope, Appointment a, Long doctorId) {
        Long id = a.getAppointmentId();
        if (a.getStatus() != AppointmentStatus.PENDING)
            throw conflict("Chỉ có thể xác nhận lịch đang chờ lễ tân.");
        Long currentDoctorId = a.getDoctor() != null ? a.getDoctor().getStaffId() : null;
        if (doctorId != null && !Objects.equals(currentDoctorId, doctorId)
                && !access.canAssignDoctor(scope) && !isDoctorReassignmentNeeded(a)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Lễ tân không được tự ý gán bác sĩ thủ công khi xác nhận lịch.");
        }
        Long effectiveDoctorId = doctorId != null ? doctorId : currentDoctorId;
        activeProfile(a.getPatientProfile());
        rules.ensureBookable(a.getBranch(), a.getPatientProfile(), a.getAppointmentDate(),
                a.getPreferredSession(), effectiveDoctorId, id);
        String old = snapshot(a);
        if (Boolean.TRUE.equals(a.getBranch().getResourceEngineEnabled())
                && a.getExpectedTime() != null && a.getService() != null && resourceBookingTransactionService != null) {
            resourceBookingTransactionService.rescheduleInExistingTransaction(
                    com.cvdcms.DTO.availability.ResourceRescheduleRequest.reschedule(
                            id, a.getBranch().getBranchId(), null,
                            a.getAppointmentDate(), a.getExpectedTime(), a.getPreferredSession(),
                            effectiveDoctorId, effectiveDoctorId == null,
                            "CONFIRMED", AppointmentStatus.CONFIRMED, false));
            Appointment confirmed = appointments.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            history(confirmed, scope.user().getUserId(), "CONFIRM", old, snapshot(confirmed), null);
            if (careEventPublisher != null) {
                careEventPublisher.publishEvent(new com.cvdcms.event.CustomerCareDomainEvents.AppointmentUpsertedCareEvent(confirmed.getAppointmentId()));
            }
            return view(confirmed);
        }
        StaffProfile resolvedDoctor = doctor(effectiveDoctorId);
        if (a.getExpectedTime() != null) {
            slots.resolveFlexibly(a.getBranch(), a.getAppointmentDate(), a.getPreferredSession(), effectiveDoctorId,
                    a.getDurationMinutes(), id, a.getExpectedTime(), AppointmentActorType.RECEPTIONIST);
            if (resolvedDoctor == null) {
                StaffProfile autoAssigned = slots.autoAssignDoctorForSlot(
                        a.getBranch(), a.getAppointmentDate(), a.getPreferredSession(),
                        a.getExpectedTime(), a.getDurationMinutes(), id);
                if (autoAssigned != null) {
                    resolvedDoctor = autoAssigned;
                }
            }
        } else if (effectiveDoctorId != null) {
            slots.lockAndValidateDoctorSession(a.getBranch().getBranchId(), effectiveDoctorId,
                    a.getAppointmentDate(), a.getPreferredSession(), id);
        }
        a.setDoctor(resolvedDoctor);
        a.syncExpectedEndTime();
        lifecycle.transition(a, AppointmentStatus.CONFIRMED, AppointmentLifecycleAction.CONFIRM);
        appointments.saveAndFlush(a);
        history(a, scope.user().getUserId(), "CONFIRM", old, snapshot(a), null);
        if (careEventPublisher != null && a.getAppointmentId() != null) {
            careEventPublisher.publishEvent(new com.cvdcms.event.CustomerCareDomainEvents.AppointmentUpsertedCareEvent(a.getAppointmentId()));
        }
        return view(a);
    }

    public AppointmentView cancel(HttpSession session, Long id, CancelRequest request) {
        PatientAccess.Scope scope = access.require(session);
        Appointment a = lockAppointment(scope, id);
        mutable(a);
        String code = clean(request.reasonCode());
        String note = clean(request.note());
        if (code == null || !CANCEL_REASONS.contains(code) || "Khác".equals(code) && note == null)
            throw bad("Vui lòng chọn lý do hủy trong danh mục và ghi chú nếu chọn Khác.");
        String reason = note == null ? code : code + ": " + note;
        String old = snapshot(a);
        if (resourceBookingTransactionService != null) {
            resourceBookingTransactionService.releaseReservationsOnCancelOrNoShow(
                    id, ReservationStatus.CANCELLED, "APPOINTMENT_CANCELLED", LocalDateTime.now(ReceptionAppointmentRules.ZONE));
        }
        lifecycle.transition(a, AppointmentStatus.CANCELLED, AppointmentLifecycleAction.CANCEL);
        a.setCancelReason(reason);
        a.setCancelledBy(scope.user().getUserId());
        a.setCancelledAt(LocalDateTime.now(ReceptionAppointmentRules.ZONE));
        appointments.saveAndFlush(a);
        history(a, scope.user().getUserId(), "CANCEL", old, snapshot(a), reason);
        for (CustomerCareTask task : careTasks.findByAppointment_AppointmentId(id))
            if (task.getTaskType() == CustomerCareTaskType.APPOINTMENT_REMINDER
                    && task.getStatus() != CustomerCareTaskStatus.COMPLETED)
                task.setStatus(CustomerCareTaskStatus.CANCELLED);
        if (receptionVisits != null) {
            receptionVisits.findByAppointmentId(id).ifPresent(v -> {
                if (v.getStatus() != VisitStatus.COMPLETED) {
                    v.setStatus(VisitStatus.CANCELLED);
                    receptionVisits.saveAndFlush(v);
                    if (clinicalQueueTickets != null && v.getVisitId() != null) {
                        List<QueueTicket> tickets = clinicalQueueTickets.findByVisitVisitId(v.getVisitId());
                        for (QueueTicket t : tickets) {
                            t.setStatus(QueueStatus.CANCELLED);
                        }
                        if (!tickets.isEmpty()) {
                            clinicalQueueTickets.saveAll(tickets);
                        }
                    }
                }
            });
        }
        return view(a);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void markNoShow(Long id, LocalDateTime now) {
        Appointment before = appointments.findById(id).orElse(null);
        if (before == null || !isNoShowCutoffPassed(before, now)) return;
        if (before.getStatus() != AppointmentStatus.CONFIRMED && before.getStatus() != AppointmentStatus.PENDING) return;
        if (lockedBranches.findByIdForBooking(before.getBranch().getBranchId()).isEmpty()) return;
        Appointment a = appointments.lockById(id).orElse(null);
        if (a == null || (a.getStatus() != AppointmentStatus.CONFIRMED && a.getStatus() != AppointmentStatus.PENDING)
                || !isNoShowCutoffPassed(a, now)) return;
        if (a.getStatus() == AppointmentStatus.PENDING) {
            String pendingSnap = snapshot(a);
            lifecycle.transition(a, AppointmentStatus.CONFIRMED, AppointmentLifecycleAction.CONFIRM);
            history(a, null, "CONFIRM", pendingSnap, snapshot(a), "Hệ thống tự động xác nhận trước khi chuyển NO_SHOW do quá 30 phút");
        }
        applyNoShow(a, now, null, "Tự động đánh dấu không đến sau quá 30 phút kể từ giờ hẹn");
    }

    private boolean isNoShowCutoffPassed(Appointment a, LocalDateTime now) {
        if (a == null || a.getAppointmentDate() == null || now == null) return false;
        if (a.getAppointmentDate().isBefore(now.toLocalDate())) return true;
        if (a.getExpectedTime() != null) {
            LocalDateTime cutoff = LocalDateTime.of(a.getAppointmentDate(), a.getExpectedTime())
                    .plusMinutes(AppointmentSlotService.NO_SHOW_CUTOFF_MINUTES);
            return now.isAfter(cutoff);
        }
        return rules.sessionEnded(a.getBranch(), a.getAppointmentDate(), a.getPreferredSession(), now);
    }

    private void applyNoShow(Appointment a, LocalDateTime now, Long actorId, String reason) {
        Long id = a.getAppointmentId();
        if (resourceBookingTransactionService != null) {
            resourceBookingTransactionService.releaseReservationsOnCancelOrNoShow(
                    id, ReservationStatus.RELEASED, "NO_SHOW", now);
        }
        lifecycle.transition(a, AppointmentStatus.NO_SHOW, AppointmentLifecycleAction.MARK_NO_SHOW);
        appointments.saveAndFlush(a);
        history(a, actorId, "NO_SHOW", snapshotWithStatus(a, AppointmentStatus.CONFIRMED), snapshot(a), reason);
        for (CustomerCareTask reminder : careTasks.findByAppointment_AppointmentId(id))
            if (reminder.getTaskType() == CustomerCareTaskType.APPOINTMENT_REMINDER
                    && reminder.getStatus() != CustomerCareTaskStatus.COMPLETED)
                reminder.setStatus(CustomerCareTaskStatus.CANCELLED);
        if (careEventPublisher != null && id != null) {
            careEventPublisher.publishEvent(new com.cvdcms.event.CustomerCareDomainEvents.AppointmentNoShowCareEvent(id));
            careEventPublisher.publishEvent(new com.cvdcms.event.ClinicQueueDomainEvent(
                    "APPOINTMENT_NO_SHOW",
                    a.getBranch() != null ? a.getBranch().getBranchId() : null,
                    a.getDoctor() != null ? a.getDoctor().getStaffId() : null,
                    null, id, AppointmentStatus.NO_SHOW.name(),
                    "Lịch hẹn #" + a.getAppointmentCode() + " chuyển NO_SHOW (quá 30 phút)", now));
        } else if (!careTasks.existsByAppointment_AppointmentIdAndTaskType(id, CustomerCareTaskType.NO_SHOW_RECALL)) {
            CustomerCareTask task = new CustomerCareTask();
            task.setTaskCode("NS" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
            task.setAppointment(a); task.setPatientProfile(a.getPatientProfile()); task.setBranch(a.getBranch());
            task.setTaskType(CustomerCareTaskType.NO_SHOW_RECALL);
            task.setDueDate(now.toLocalDate()); task.setStatus(CustomerCareTaskStatus.PENDING);
            task.setNotes("Liên hệ bệnh nhân không đến theo lịch " + a.getAppointmentCode());
            careTasks.save(task);
        }
    }

    @Transactional(readOnly = true)
    public List<Long> noShowCandidateIds(LocalDate today) {
        return appointments.findNoShowCandidatesByStatuses(today.minusDays(7), today,
                List.of(AppointmentStatus.CONFIRMED, AppointmentStatus.PENDING));
    }

    private Appointment lockAppointment(PatientAccess.Scope scope, Long id) {
        return lockAppointment(scope, readAndAuthorizeSnapshot(scope, id));
    }

    private Appointment lockAppointment(PatientAccess.Scope scope, AppointmentLockSnapshot snap) {
        return lockAppointmentInternal(scope, snap, List.of(), List.of());
    }

    private Appointment lockAppointmentWithDoctorScope(
            PatientAccess.Scope scope,
            AppointmentLockSnapshot snap,
            Long targetDoctorId,
            LocalDate targetDate
    ) {
        TreeSet<Long> doctorIds = new TreeSet<>();
        if (snap.doctorId() != null) doctorIds.add(snap.doctorId());
        if (targetDoctorId != null) doctorIds.add(targetDoctorId);

        TreeSet<LocalDate> dates = new TreeSet<>();
        if (snap.appointmentDate() != null) dates.add(snap.appointmentDate());
        if (targetDate != null) dates.add(targetDate);

        return lockAppointmentInternal(scope, snap, new ArrayList<>(doctorIds), new ArrayList<>(dates));
    }

    private Appointment lockAppointmentInternal(
            PatientAccess.Scope scope,
            AppointmentLockSnapshot snap,
            List<Long> sortedDoctorIds,
            List<LocalDate> sortedDates
    ) {
        if (patientProfiles != null && snap.patientProfileId() != null) {
            PatientProfile p = patientProfiles.findByIdForUpdate(snap.patientProfileId()).orElse(null);
            refreshIfManaged(p);
        }
        if (snap.branchId() != null) {
            lockBranch(scope, snap.branchId());
        }
        if (!sortedDoctorIds.isEmpty()) {
            for (Long docId : sortedDoctorIds) {
                if (staff != null && docId != null) {
                    staff.lockById(docId).ifPresent(this::refreshIfManaged);
                }
            }
            if (staffSchedules != null && !sortedDates.isEmpty()) {
                for (Long docId : sortedDoctorIds) {
                    for (LocalDate d : sortedDates) {
                        staffSchedules.lockDoctorSchedulesForDate(docId, d, ScheduleStatus.SCHEDULED);
                    }
                }
            }
        }
        Appointment a = appointments.lockById(snap.appointmentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        refreshIfManaged(a);
        if (a.getBranch() == null || !scope.permits(a.getBranch().getBranchId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!snap.matchesLockedState(a)) {
            throw conflict("Lịch hẹn vừa được cập nhật bởi thao tác khác. Vui lòng tải lại trang và thử lại.");
        }
        return a;
    }

    private AppointmentLockSnapshot readAndAuthorizeSnapshot(PatientAccess.Scope scope, Long id) {
        AppointmentLockSnapshot snap = appointments.findLockSnapshotById(id)
                .orElseGet(() -> appointments.findById(id).map(AppointmentLockSnapshot::from).orElse(null));
        if (snap == null || snap.branchId() == null || !scope.permits(snap.branchId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return snap;
    }

    private void refreshIfManaged(Object entity) {
        if (entityManager != null && entity != null && entityManager.contains(entity)) {
            entityManager.refresh(entity);
        }
    }
    private Appointment owned(PatientAccess.Scope scope, Long id) {
        Appointment a = appointments.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!scope.permits(a.getBranch().getBranchId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return a;
    }
    private PatientProfileForm buildAndValidateNewPatientForm(CreateRequest request, Long branchId) {
        String name = request.patientName().trim();
        String phone = request.patientPhone() != null ? request.patientPhone().trim() : "";
        LocalDate dob = request.patientDob();
        String gender = request.patientGender() != null ? request.patientGender().trim() : "";

        if (phone.isBlank() || !phone.matches("^0\\d{9,10}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Số điện thoại bệnh nhân mới phải có 10 hoặc 11 chữ số và bắt đầu bằng 0.");
        }
        if (dob == null || dob.isAfter(LocalDate.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ngày sinh bệnh nhân mới không hợp lệ hoặc không được ở tương lai.");
        }
        if (!Set.of("Nam", "Nữ", "Khác").contains(gender)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Giới tính bệnh nhân mới phải là Nam, Nữ hoặc Khác.");
        }

        PatientProfileForm form = new PatientProfileForm(
                name,
                dob,
                gender,
                phone,
                request.patientAddress() != null ? request.patientAddress().trim() : "",
                branchId,
                null,
                null,
                request.guardianName() != null ? request.guardianName().trim() : null,
                request.guardianPhone() != null ? request.guardianPhone().trim() : null,
                request.guardianRelationship() != null ? request.guardianRelationship().trim() : null,
                request.idCardNumber() != null ? request.idCardNumber().trim() : null,
                null,
                Boolean.TRUE.equals(request.confirmDuplicate())
        );
        var violations = validator.validate(form);
        if (!violations.isEmpty()) throw bad(violations.iterator().next().getMessage());
        return form;
    }

    private Branch lockBranch(PatientAccess.Scope scope, Long id) {
        if (id == null || !scope.permits(id)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        Branch locked = lockedBranches.findByIdForBooking(id)
                .orElseThrow(() -> bad("Chi nhánh không hoạt động hoặc không tồn tại."));
        if (entityManager != null && entityManager.contains(locked)) {
            entityManager.refresh(locked);
        }
        if (!Boolean.TRUE.equals(locked.getIsActive())) {
            throw bad("Chi nhánh không hoạt động hoặc không tồn tại.");
        }
        return locked;
    }
    private Branch requireBranch(PatientAccess.Scope scope, Long id) {
        if (id == null || !scope.permits(id)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return branches.findById(id).filter(b -> Boolean.TRUE.equals(b.getIsActive()))
                .orElseThrow(() -> bad("Chi nhánh không hoạt động hoặc không tồn tại."));
    }
    private Long scopedBranch(PatientAccess.Scope scope, Long requested) {
        if (requested != null) { requireBranch(scope, requested); return requested; }
        return scope.admin() ? null : scope.branchId();
    }
    private StaffProfile doctor(Long id) {
        return id == null ? null : staff.findById(id).orElseThrow(() -> bad("Bác sĩ không tồn tại."));
    }
    private com.cvdcms.entity.Service service(Long id) {
        return id == null ? null : services.findById(id)
                .filter(s -> s.getStatus() == ServiceStatus.ACTIVE && Boolean.TRUE.equals(s.getCategory().getIsActive()))
                .orElseThrow(() -> bad("Dịch vụ không còn hoạt động."));
    }
    private void mutable(Appointment a) {
        if (a.getStatus() != AppointmentStatus.PENDING && a.getStatus() != AppointmentStatus.CONFIRMED)
            throw conflict("Lịch đã check-in, hoàn thành, hủy hoặc không đến không thể sửa.");
    }
    private void updateReminders(Appointment a, boolean moved) {
        LocalDate due = a.getAppointmentDate().minusDays(1);
        if (due.isBefore(LocalDate.now(ReceptionAppointmentRules.ZONE))) due = LocalDate.now(ReceptionAppointmentRules.ZONE);
        for (CustomerCareTask task : careTasks.findByAppointment_AppointmentId(a.getAppointmentId()))
            if (task.getTaskType() == CustomerCareTaskType.APPOINTMENT_REMINDER
                    && task.getStatus() != CustomerCareTaskStatus.CANCELLED) {
                task.setDueDate(due);
                if (moved) task.setStatus(CustomerCareTaskStatus.PENDING);
            }
    }
    private void activeProfile(PatientProfile profile) {
        if (!"ACTIVE".equalsIgnoreCase(profile.getStatus()))
            throw conflict("Hồ sơ bệnh nhân đã ngừng hoạt động.");
    }
    private void history(Appointment a, Long actorId, String action, String old, String value, String reason) {
        AppointmentHistory h = new AppointmentHistory();
        h.setAppointment(a);
        // Fallback actorUserId = 1L (system/admin) cho job tự động không có phiên đăng nhập (như ReceptionNoShowJob)
        h.setActorUserId(actorId != null ? actorId : 1L);
        h.setAction(action);
        h.setOldValue(old);
        h.setNewValue(value);
        h.setReason(reason);
        histories.save(h);
    }
    private boolean isDoctorReassignmentNeeded(Appointment a) {
        if (a == null || a.getDoctor() == null) {
            return false;
        }
        if (a.getStatus() != AppointmentStatus.PENDING && a.getStatus() != AppointmentStatus.CONFIRMED) {
            return false;
        }
        if (a.getDoctor().getStatus() != StaffStatus.ACTIVE) {
            return true;
        }
        List<AppointmentConflictDetectionService.Conflict> foundConflicts = conflictDetection != null
                ? conflictDetection.detectConflicts(a) : List.of();
        boolean doctorConflict = foundConflicts.stream().anyMatch(c ->
                "DOCTOR_ON_LEAVE".equals(c.code())
                        || "DOCTOR_NO_SCHEDULE".equals(c.code())
                        || "DOCTOR_NOT_ASSIGNED".equals(c.code())
                        || "DOCTOR_INACTIVE".equals(c.code()));
        if (doctorConflict) {
            return true;
        }
        return a.getAppointmentDate() != null
                && !a.getAppointmentDate().isBefore(LocalDate.now(ReceptionAppointmentRules.ZONE))
                && !rules.doctorAvailable(a.getBranch(), a.getAppointmentDate(), a.getPreferredSession(), a.getDoctor().getStaffId());
    }

    private void notifyPatientReassignment(Appointment a, StaffProfile newDoctor, boolean moved) {
        if (notifications == null || a == null || a.getPatientProfile() == null
                || a.getPatientProfile().getUser() == null) {
            return;
        }
        SystemNotification notif = new SystemNotification();
        notif.setUser(a.getPatientProfile().getUser());
        notif.setTitle("Cập nhật điều phối lịch hẹn #" + a.getAppointmentCode());
        String docLabel = newDoctor != null ? "Bác sĩ " + newDoctor.getFullName() : "bác sĩ phụ trách phù hợp";
        String timeStr = a.getExpectedTime() != null ? " lúc " + a.getExpectedTime() : "";
        notif.setMessage("Lịch hẹn #" + a.getAppointmentCode()
                + (moved ? " đã được đề xuất/dời sang ngày " + a.getAppointmentDate() + timeStr + " với " + docLabel + "."
                        : " ngày " + a.getAppointmentDate() + timeStr + " đã được điều phối sang " + docLabel + "."));
        notif.setNotificationType(NotificationType.APPOINTMENT);
        notifications.save(notif);
    }

    private AppointmentView view(Appointment a) {
        List<AppointmentConflictDetectionService.Conflict> foundConflicts = conflictDetection != null
                ? conflictDetection.detectConflicts(a) : List.of();
        boolean hasConflict = !foundConflicts.isEmpty();
        boolean doctorUnavailable = hasConflict
                || (a.getDoctor() != null
                && (a.getStatus() == AppointmentStatus.PENDING || a.getStatus() == AppointmentStatus.CONFIRMED)
                && !a.getAppointmentDate().isBefore(LocalDate.now(ReceptionAppointmentRules.ZONE))
                && !rules.doctorAvailable(a.getBranch(), a.getAppointmentDate(), a.getPreferredSession(), a.getDoctor().getStaffId()));
        String conflictNotes = foundConflicts.isEmpty() ? null
                : foundConflicts.stream().map(AppointmentConflictDetectionService.Conflict::message).collect(Collectors.joining("; "));

        return new AppointmentView(a.getAppointmentId(), a.getAppointmentCode(), a.getPatientProfile().getProfileId(),
                a.getPatientProfile().getPatientCode(), a.getPatientProfile().getFullName(),
                a.getPatientProfile().getPhoneNumber(), a.getBranch().getBranchId(), a.getBranch().getBranchName(),
                a.getDoctor() == null ? null : a.getDoctor().getStaffId(),
                a.getDoctor() == null ? null : a.getDoctor().getFullName(),
                a.getService() == null ? null : a.getService().getServiceId(),
                a.getService() == null ? null : a.getService().getServiceName(),
                a.getAppointmentDate(), a.getPreferredSession(), a.getInitialSymptoms(), a.getSource(), a.getStatus(),
                a.getRescheduleCount(), a.getCancelReason(), doctorUnavailable, a.getCreatedAt(), conflictNotes,
                a.getExpectedTime(), a.getDurationMinutes(), a.getPatientProfile().getDob(), a.getPatientProfile().getGender());
    }
    private static long count(List<AppointmentView> list, AppointmentStatus status) {
        return list.stream().filter(a -> a.status() == status).count();
    }
    private static String snapshot(Appointment a) {
        return a.getAppointmentDate() + "|" + a.getPreferredSession() + "|doctor="
                + (a.getDoctor() == null ? null : a.getDoctor().getStaffId()) + "|time="
                + a.getExpectedTime() + "|status=" + a.getStatus();
    }
    private static String snapshotWithStatus(Appointment a, AppointmentStatus status) {
        return snapshot(a).replace("|status=" + a.getStatus(), "|status=" + status);
    }
    private static String code() {
        return "LH" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }
    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
