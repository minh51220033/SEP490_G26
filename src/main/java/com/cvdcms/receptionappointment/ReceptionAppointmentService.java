package com.cvdcms.receptionappointment;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.patient.PatientAccess;
import com.cvdcms.patient.PatientProfileForm;
import com.cvdcms.patient.PatientService;
import com.cvdcms.patient.PatientView;
import com.cvdcms.patient.ReceptionPatientRecord;
import com.cvdcms.patient.ReceptionPatientRepository;
import com.cvdcms.repository.*;
import com.cvdcms.service.AppointmentConflictDetectionService;
import com.cvdcms.service.AppointmentLifecycleService;
import com.cvdcms.service.AppointmentPolicyService;
import com.cvdcms.service.AppointmentSlotService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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
    public record PatientOption(Long id, String code, String name, String phone) {}
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
                .stream().filter(s -> Boolean.TRUE.equals(s.getCategory().getIsActive()))
                .map(s -> new ServiceOption(s.getServiceId(), s.getServiceName(),
                        AppointmentSlotService.duration(s))).toList();
        return new Options(branchOptions, serviceOptions);
    }

    @Transactional(readOnly = true)
    public List<PatientOption> searchPatients(HttpSession session, Long branchId, String query) {
        PatientAccess.Scope scope = access.require(session);
        if (branchId != null) {
            try {
                requireBranch(scope, branchId);
            } catch (Exception ignored) {}
        }
        if (query == null || query.trim().length() < 2) return List.of();
        return patients.search(query.trim()).stream().limit(20)
                .map(p -> new PatientOption(p.getProfileId(), p.getPatientCode(), p.getFullName(), p.getPhoneNumber()))
                .toList();
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
        return slots.available(branch, date, period, doctorId,
                AppointmentSlotService.duration(service(serviceId)), excludeId,
                AppointmentActorType.RECEPTIONIST).stream()
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

    public AppointmentView create(HttpSession session, CreateRequest request) {
        PatientAccess.Scope scope = access.require(session);
        Branch branch = lockBranch(scope, request.branchId());
        PatientProfile profile;
        if (request.profileId() != null) {
            profile = patients.findBookable(request.profileId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hồ sơ không thuộc phạm vi chi nhánh."));
        } else if (request.patientName() != null && !request.patientName().isBlank()) {
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
                    branch.getBranchId(),
                    null,
                    null,
                    request.guardianName() != null ? request.guardianName().trim() : null,
                    request.guardianPhone() != null ? request.guardianPhone().trim() : null,
                    request.guardianRelationship() != null ? request.guardianRelationship().trim() : null,
                    request.idCardNumber() != null ? request.idCardNumber().trim() : null,
                    null,
                    Boolean.TRUE.equals(request.confirmDuplicate())
            );

            // Tái sử dụng PatientService chuẩn: kiểm tra quyền chi nhánh, phát hiện trùng, bắt buộc người giám hộ nếu < 18 tuổi (BR-PAT-09), ghi audit log
            var violations = validator.validate(form);
            if (!violations.isEmpty()) throw bad(violations.iterator().next().getMessage());
            PatientView createdProfile = patientService.create(session, form);
            profile = patients.findById(createdProfile.id())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Không thể tải hồ sơ sau khi tạo."));
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

        Appointment a = new Appointment();
        a.setAppointmentCode(code()); a.setBranch(branch); a.setPatientProfile(profile);
        a.setAppointmentDate(request.date()); a.setPreferredSession(targetSession);
        a.setDoctor(doctor(request.doctorId()));
        a.setService(service(request.serviceId()));

        String notes = clean(request.symptoms());
        if (request.internalNote() != null && !request.internalNote().isBlank()) {
            notes = (notes == null ? "" : notes + " - ") + "[Ghi chú: " + request.internalNote().trim() + "]";
        }
        a.setInitialSymptoms(notes);
        if (request.durationMinutes() != null && (request.durationMinutes() < 15 || request.durationMinutes() > 240))
            throw bad("Thời lượng dự kiến phải từ 15 đến 240 phút.");
        a.setDurationMinutes(request.durationMinutes() != null ? request.durationMinutes() : AppointmentSlotService.duration(a.getService()));

        LocalTime expectedTime = null;
        if (request.expectedTime() != null) {
            // Bắt buộc xác minh khung giờ hợp lệ, không fallback sang raw time nếu kiểm tra thất bại
            expectedTime = slots.resolveFlexibly(branch, request.date(), targetSession, request.doctorId(),
                    a.getDurationMinutes(), null, request.expectedTime(), AppointmentActorType.RECEPTIONIST);
        }
        a.setExpectedTime(expectedTime);
        a.setSource(AppointmentSource.RECEPTIONIST);
        a.setStatus(Boolean.TRUE.equals(request.isTentative()) ? AppointmentStatus.PENDING : AppointmentStatus.CONFIRMED);
        appointments.saveAndFlush(a);
        history(a, scope.user().getUserId(), "CREATE", null, snapshot(a), null);
        if (careEventPublisher != null && a.getAppointmentId() != null) {
            careEventPublisher.publishEvent(new com.cvdcms.event.CustomerCareDomainEvents.AppointmentUpsertedCareEvent(a.getAppointmentId()));
        }
        return view(a);
    }

    public AppointmentView quickStatus(HttpSession session, Long id, QuickStatusRequest request) {
        PatientAccess.Scope scope = access.require(session);
        Appointment a = lockAppointment(scope, id);
        String old = snapshot(a);

        String rawStatus = request.status() != null ? request.status().trim() : "";
        AppointmentStatus targetStatus;
        try {
            targetStatus = AppointmentStatus.valueOf(rawStatus.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trạng thái lịch hẹn không hợp lệ: " + rawStatus);
        }

        String note = request.note() != null ? request.note().trim() : "";

        if (targetStatus == AppointmentStatus.CANCELLED) {
            if (!lifecycle.canTransition(a.getStatus(), AppointmentStatus.CANCELLED, AppointmentLifecycleAction.CANCEL)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Lịch hẹn ở trạng thái " + a.getStatus() + " không thể hủy.");
            }
            String reason = !note.isEmpty() ? note : "Bệnh nhân đổi kế hoạch";
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
            if (!rules.sessionEnded(a.getBranch(), a.getAppointmentDate(), a.getPreferredSession(), now))
                throw conflict("Chỉ đánh dấu không đến sau khi buổi khám kết thúc.");
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

    public AppointmentView update(HttpSession session, Long id, UpdateRequest request) {
        PatientAccess.Scope scope = access.require(session);
        Appointment a = lockAppointment(scope, id);
        mutable(a);
        activeProfile(a.getPatientProfile());
        rules.ensureBookable(a.getBranch(), a.getPatientProfile(), request.date(), request.session(),
                request.doctorId(), id);
        String old = snapshot(a);
        boolean moved = !Objects.equals(a.getAppointmentDate(), request.date())
                || a.getPreferredSession() != request.session()
                || request.expectedTime() != null && !Objects.equals(a.getExpectedTime(), request.expectedTime());
        LocalTime requestedTime = request.expectedTime() != null ? request.expectedTime()
                : Objects.equals(a.getAppointmentDate(), request.date()) && a.getPreferredSession() == request.session()
                    ? a.getExpectedTime() : null;
        a.setAppointmentDate(request.date()); a.setPreferredSession(request.session());
        a.setDoctor(doctor(request.doctorId())); a.setService(service(request.serviceId()));
        a.setInitialSymptoms(clean(request.symptoms()));
        a.setDurationMinutes(AppointmentSlotService.duration(a.getService()));
        LocalTime expectedTime = null;
        if (requestedTime != null) {
            expectedTime = slots.resolveFlexibly(a.getBranch(), request.date(), request.session(), request.doctorId(),
                    a.getDurationMinutes(), id, requestedTime, AppointmentActorType.RECEPTIONIST);
        }
        a.setExpectedTime(expectedTime);
        if (moved) a.setRescheduleCount(a.getRescheduleCount() + 1);
        lifecycle.transition(a, a.getStatus(), AppointmentLifecycleAction.RESCHEDULE);
        appointments.saveAndFlush(a);
        history(a, scope.user().getUserId(), moved ? "RESCHEDULE" : "UPDATE", old,
                snapshot(a), clean(request.reason()));
        updateReminders(a, moved);
        return view(a);
    }

    public AppointmentView confirm(HttpSession session, Long id, ConfirmRequest request) {
        PatientAccess.Scope scope = access.require(session);
        Appointment a = lockAppointment(scope, id);
        return confirmLocked(scope, a, request.doctorId());
    }

    private AppointmentView confirmLocked(PatientAccess.Scope scope, Appointment a, Long doctorId) {
        Long id = a.getAppointmentId();
        if (a.getStatus() != AppointmentStatus.PENDING)
            throw conflict("Chỉ có thể xác nhận lịch đang chờ lễ tân.");
        activeProfile(a.getPatientProfile());
        rules.ensureBookable(a.getBranch(), a.getPatientProfile(), a.getAppointmentDate(),
                a.getPreferredSession(), doctorId, id);
        String old = snapshot(a);
        a.setDoctor(doctor(doctorId));
        if (a.getExpectedTime() != null) {
            slots.resolveFlexibly(a.getBranch(), a.getAppointmentDate(), a.getPreferredSession(), doctorId,
                    a.getDurationMinutes(), id, a.getExpectedTime(), AppointmentActorType.RECEPTIONIST);
        }
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
        return view(a);
    }

    public void markNoShow(Long id, LocalDateTime now) {
        Appointment before = appointments.findById(id).orElse(null);
        if (before == null) return;
        if (lockedBranches.findByIdForBooking(before.getBranch().getBranchId()).isEmpty()) return;
        Appointment a = appointments.lockById(id).orElse(null);
        if (a == null || a.getStatus() != AppointmentStatus.CONFIRMED
                || !rules.sessionEnded(a.getBranch(), a.getAppointmentDate(), a.getPreferredSession(), now)) return;
        applyNoShow(a, now, null, "Tự động đánh dấu không đến sau khi buổi khám kết thúc");
    }

    private void applyNoShow(Appointment a, LocalDateTime now, Long actorId, String reason) {
        Long id = a.getAppointmentId();
        lifecycle.transition(a, AppointmentStatus.NO_SHOW, AppointmentLifecycleAction.MARK_NO_SHOW);
        appointments.saveAndFlush(a);
        history(a, actorId, "NO_SHOW", snapshotWithStatus(a, AppointmentStatus.CONFIRMED), snapshot(a), reason);
        for (CustomerCareTask reminder : careTasks.findByAppointment_AppointmentId(id))
            if (reminder.getTaskType() == CustomerCareTaskType.APPOINTMENT_REMINDER
                    && reminder.getStatus() != CustomerCareTaskStatus.COMPLETED)
                reminder.setStatus(CustomerCareTaskStatus.CANCELLED);
        if (careEventPublisher != null && id != null) {
            careEventPublisher.publishEvent(new com.cvdcms.event.CustomerCareDomainEvents.AppointmentNoShowCareEvent(id));
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
        return appointments.findNoShowCandidates(today.minusDays(7), today, AppointmentStatus.CONFIRMED);
    }

    private Appointment lockAppointment(PatientAccess.Scope scope, Long id) {
        Appointment before = owned(scope, id);
        lockBranch(scope, before.getBranch().getBranchId());
        Appointment a = appointments.lockById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!scope.permits(a.getBranch().getBranchId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return a;
    }
    private Appointment owned(PatientAccess.Scope scope, Long id) {
        Appointment a = appointments.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!scope.permits(a.getBranch().getBranchId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return a;
    }
    private Branch lockBranch(PatientAccess.Scope scope, Long id) {
        requireBranch(scope, id);
        return lockedBranches.findByIdForBooking(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
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
