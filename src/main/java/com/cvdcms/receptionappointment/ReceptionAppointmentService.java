package com.cvdcms.receptionappointment;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.patient.PatientAccess;
import com.cvdcms.patientportal.PortalBookingBranchRepository;
import com.cvdcms.repository.*;
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
public class ReceptionAppointmentService {
    private static final Set<String> CANCEL_REASONS = Set.of("Bệnh nhân đổi kế hoạch",
            "Bệnh nhân không còn nhu cầu", "Trùng lịch", "Bác sĩ không khả dụng", "Khác");
    private final PatientAccess access;
    private final ReceptionAppointmentRepository appointments;
    private final ReceptionAppointmentPatientRepository patients;
    private final BranchRepository branches;
    private final PortalBookingBranchRepository lockedBranches;
    private final ServiceRepository services;
    private final StaffProfileRepository staff;
    private final ReceptionAppointmentRules rules;
    private final AppointmentHistoryRepository histories;
    private final CustomerCareTaskRepository careTasks;

    public ReceptionAppointmentService(PatientAccess access, ReceptionAppointmentRepository appointments,
            ReceptionAppointmentPatientRepository patients, BranchRepository branches,
            PortalBookingBranchRepository lockedBranches, ServiceRepository services, StaffProfileRepository staff,
            ReceptionAppointmentRules rules, AppointmentHistoryRepository histories,
            CustomerCareTaskRepository careTasks) {
        this.access = access; this.appointments = appointments; this.patients = patients;
        this.branches = branches; this.lockedBranches = lockedBranches; this.services = services;
        this.staff = staff; this.rules = rules; this.histories = histories; this.careTasks = careTasks;
    }

    public record CreateRequest(@NotNull Long branchId, @NotNull Long profileId,
            @NotNull LocalDate date, @NotNull PreferredSession session, Long doctorId, Long serviceId,
            @Size(max = 500) String symptoms) {}
    public record UpdateRequest(@NotNull LocalDate date, @NotNull PreferredSession session,
            Long doctorId, Long serviceId, @Size(max = 500) String symptoms,
            @NotBlank @Size(max = 255) String reason) {}
    public record ConfirmRequest(Long doctorId) {}
    public record CancelRequest(@NotBlank String reasonCode, @Size(max = 180) String note) {}
    public record BranchOption(Long id, String name) {}
    public record ServiceOption(Long id, String name) {}
    public record PatientOption(Long id, String code, String name, String phone) {}
    public record Options(List<BranchOption> branches, List<ServiceOption> services) {}
    public record AppointmentView(Long id, String code, Long profileId, String patientCode,
            String patientName, String patientPhone, Long branchId, String branchName,
            Long doctorId, String doctorName, Long serviceId, String serviceName,
            LocalDate date, PreferredSession session, String symptoms,
            AppointmentSource source, AppointmentStatus status, int rescheduleCount,
            String cancelReason, boolean doctorUnavailable, LocalDateTime createdAt) {}
    public record HistoryView(String action, String oldValue, String newValue, String reason, LocalDateTime createdAt) {}
    public record Detail(AppointmentView appointment, List<HistoryView> history) {}
    public record Dashboard(LocalDate date, long total, long pending, long confirmed,
            long checkedIn, long noShow, List<AppointmentView> appointments) {}

    @Transactional(readOnly = true)
    public Options options(HttpSession session) {
        PatientAccess.Scope scope = access.require(session);
        List<Branch> allowed = scope.admin() ? branches.findByIsActiveTrueOrderByBranchNameAsc()
                : branches.findById(scope.branchId()).filter(b -> Boolean.TRUE.equals(b.getIsActive())).stream().toList();
        List<BranchOption> branchOptions = allowed.stream().map(b -> new BranchOption(b.getBranchId(), b.getBranchName())).toList();
        List<ServiceOption> serviceOptions = services.findByStatusOrderByCategory_DisplayOrderAscServiceNameAsc(ServiceStatus.ACTIVE)
                .stream().filter(s -> Boolean.TRUE.equals(s.getCategory().getIsActive()))
                .map(s -> new ServiceOption(s.getServiceId(), s.getServiceName())).toList();
        return new Options(branchOptions, serviceOptions);
    }

    @Transactional(readOnly = true)
    public List<PatientOption> searchPatients(HttpSession session, Long branchId, String query) {
        PatientAccess.Scope scope = access.require(session);
        requireBranch(scope, branchId);
        if (query == null || query.trim().length() < 2) return List.of();
        return patients.search(branchId, query.trim()).stream().limit(20)
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
    public List<ReceptionAppointmentRules.DoctorOption> doctors(HttpSession session, Long branchId, LocalDate date) {
        PatientAccess.Scope scope = access.require(session);
        return rules.doctorsForDate(requireBranch(scope, branchId), date);
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(HttpSession session, LocalDate date, Long branchId, Long doctorId) {
        PatientAccess.Scope scope = access.require(session);
        if (date == null) date = LocalDate.now(ReceptionAppointmentRules.ZONE);
        Long effectiveBranch = scopedBranch(scope, branchId);
        List<AppointmentView> list = appointments.findInRange(date, date, effectiveBranch, doctorId, null)
                .stream().map(this::view).toList();
        return new Dashboard(date, list.size(), count(list, AppointmentStatus.PENDING),
                count(list, AppointmentStatus.CONFIRMED), count(list, AppointmentStatus.CHECKED_IN),
                count(list, AppointmentStatus.NO_SHOW), list);
    }

    @Transactional(readOnly = true)
    public List<AppointmentView> calendar(HttpSession session, LocalDate start, LocalDate end,
            Long branchId, Long doctorId, AppointmentStatus status) {
        PatientAccess.Scope scope = access.require(session);
        if (start == null || end == null || end.isBefore(start) || end.isAfter(start.plusDays(42)))
            throw bad("Khoảng xem lịch không hợp lệ hoặc vượt quá 43 ngày.");
        return appointments.findInRange(start, end, scopedBranch(scope, branchId), doctorId, status)
                .stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<AppointmentView> noShows(HttpSession session, Long branchId) {
        PatientAccess.Scope scope = access.require(session);
        LocalDate today = LocalDate.now(ReceptionAppointmentRules.ZONE);
        return appointments.findInRange(today.minusDays(7), today, scopedBranch(scope, branchId), null,
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
        PatientProfile profile = patients.findBookable(request.profileId(), branch.getBranchId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hồ sơ không thuộc phạm vi chi nhánh."));
        rules.ensureBookable(branch, profile, request.date(), request.session(), request.doctorId(), null);
        Appointment a = new Appointment();
        a.setAppointmentCode(code()); a.setBranch(branch); a.setPatientProfile(profile);
        a.setAppointmentDate(request.date()); a.setPreferredSession(request.session());
        a.setExpectedTime(null); a.setDoctor(doctor(request.doctorId()));
        a.setService(service(request.serviceId())); a.setInitialSymptoms(clean(request.symptoms()));
        a.setSource(AppointmentSource.RECEPTIONIST); a.setStatus(AppointmentStatus.CONFIRMED);
        appointments.saveAndFlush(a);
        history(a, scope.user().getUserId(), "CREATE", null, snapshot(a), null);
        return view(a);
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
                || a.getPreferredSession() != request.session();
        a.setAppointmentDate(request.date()); a.setPreferredSession(request.session());
        a.setDoctor(doctor(request.doctorId())); a.setService(service(request.serviceId()));
        a.setInitialSymptoms(clean(request.symptoms())); a.setExpectedTime(null);
        if (moved) a.setRescheduleCount(a.getRescheduleCount() + 1);
        appointments.saveAndFlush(a);
        history(a, scope.user().getUserId(), moved ? "RESCHEDULE" : "UPDATE", old,
                snapshot(a), clean(request.reason()));
        updateReminders(a, moved);
        return view(a);
    }

    public AppointmentView confirm(HttpSession session, Long id, ConfirmRequest request) {
        PatientAccess.Scope scope = access.require(session);
        Appointment a = lockAppointment(scope, id);
        if (a.getStatus() != AppointmentStatus.PENDING)
            throw conflict("Chỉ có thể xác nhận lịch đang chờ lễ tân.");
        activeProfile(a.getPatientProfile());
        rules.ensureBookable(a.getBranch(), a.getPatientProfile(), a.getAppointmentDate(),
                a.getPreferredSession(), request.doctorId(), id);
        String old = snapshot(a);
        a.setDoctor(doctor(request.doctorId())); a.setStatus(AppointmentStatus.CONFIRMED);
        appointments.saveAndFlush(a);
        history(a, scope.user().getUserId(), "CONFIRM", old, snapshot(a), null);
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
        a.setStatus(AppointmentStatus.CANCELLED); a.setCancelReason(reason);
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
        a.setStatus(AppointmentStatus.NO_SHOW);
        appointments.saveAndFlush(a);
        for (CustomerCareTask reminder : careTasks.findByAppointment_AppointmentId(id))
            if (reminder.getTaskType() == CustomerCareTaskType.APPOINTMENT_REMINDER
                    && reminder.getStatus() != CustomerCareTaskStatus.COMPLETED)
                reminder.setStatus(CustomerCareTaskStatus.CANCELLED);
        if (!careTasks.existsByAppointment_AppointmentIdAndTaskType(id, CustomerCareTaskType.NO_SHOW_RECALL)) {
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
        h.setAppointment(a); h.setActorUserId(actorId); h.setAction(action);
        h.setOldValue(old); h.setNewValue(value); h.setReason(reason);
        histories.save(h);
    }
    private AppointmentView view(Appointment a) {
        boolean doctorUnavailable = a.getDoctor() != null
                && (a.getStatus() == AppointmentStatus.PENDING || a.getStatus() == AppointmentStatus.CONFIRMED)
                && !a.getAppointmentDate().isBefore(LocalDate.now(ReceptionAppointmentRules.ZONE))
                && !rules.doctorAvailable(a.getBranch(), a.getAppointmentDate(), a.getPreferredSession(), a.getDoctor().getStaffId());
        return new AppointmentView(a.getAppointmentId(), a.getAppointmentCode(), a.getPatientProfile().getProfileId(),
                a.getPatientProfile().getPatientCode(), a.getPatientProfile().getFullName(),
                a.getPatientProfile().getPhoneNumber(), a.getBranch().getBranchId(), a.getBranch().getBranchName(),
                a.getDoctor() == null ? null : a.getDoctor().getStaffId(),
                a.getDoctor() == null ? null : a.getDoctor().getFullName(),
                a.getService() == null ? null : a.getService().getServiceId(),
                a.getService() == null ? null : a.getService().getServiceName(),
                a.getAppointmentDate(), a.getPreferredSession(), a.getInitialSymptoms(), a.getSource(), a.getStatus(),
                a.getRescheduleCount(), a.getCancelReason(), doctorUnavailable, a.getCreatedAt());
    }
    private static long count(List<AppointmentView> list, AppointmentStatus status) {
        return list.stream().filter(a -> a.status() == status).count();
    }
    private static String snapshot(Appointment a) {
        return a.getAppointmentDate() + "|" + a.getPreferredSession() + "|doctor="
                + (a.getDoctor() == null ? null : a.getDoctor().getStaffId()) + "|status=" + a.getStatus();
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
