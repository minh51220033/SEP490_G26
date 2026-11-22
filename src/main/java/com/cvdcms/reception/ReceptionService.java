package com.cvdcms.reception;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.patient.PatientSearchPolicy;
import com.cvdcms.repository.AppointmentBookingLockRepository;
import com.cvdcms.repository.AppointmentHistoryRepository;
import com.cvdcms.repository.AppointmentLockSnapshot;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.service.AppointmentLifecycleService;
import com.cvdcms.service.AppointmentPolicyService;
import com.cvdcms.service.ClinicalExaminationBootstrapService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ReceptionService {
    private static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final ReceptionAccess access;
    private final ReceptionProfileRepository profiles;
    private final ReceptionCheckInAppointmentRepository appointments;
    private final ReceptionVisitRepository visits;
    private final ReceptionCheckInKeyRepository keys;
    private final ReceptionAuditRepository audits;
    private final BranchRepository branches;
    private final ObjectMapper mapper;
    private final AppointmentBookingLockRepository lockedBranches;
    private final AppointmentLifecycleService lifecycle;
    private final AppointmentHistoryRepository histories;
    private final AppointmentPolicyService policyService;
    private final ClinicalExaminationBootstrapService examinations;
    @Autowired(required = false)
    private DispatchTicketRepository tickets;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.ClinicalExaminationRepository clinicalExamRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.StaffScheduleRepository staffSchedules;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.LeaveRequestRepository leaveRequests;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.StaffBranchAssignmentRepository branchAssignments;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.service.ClinicQueueCoordinatorService queueCoordinator;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private StaffProfileRepository staffProfiles;

    @PersistenceContext
    private EntityManager entityManager;

    private Clock clock = Clock.system(CLINIC_ZONE);

    public void setClock(Clock clock) {
        if (clock != null) {
            this.clock = clock;
        }
    }

    @Autowired
    public ReceptionService(ReceptionAccess access, ReceptionProfileRepository profiles,
                            ReceptionCheckInAppointmentRepository appointments,
                            ReceptionVisitRepository visits, ReceptionCheckInKeyRepository keys,
                            ReceptionAuditRepository audits, BranchRepository branches,
                            ObjectMapper mapper, AppointmentBookingLockRepository lockedBranches,
                            AppointmentLifecycleService lifecycle, AppointmentHistoryRepository histories,
                            AppointmentPolicyService policyService,
                            ClinicalExaminationBootstrapService examinations) {
        this.access = access;
        this.profiles = profiles;
        this.appointments = appointments;
        this.visits = visits;
        this.keys = keys;
        this.audits = audits;
        this.branches = branches;
        this.mapper = mapper;
        this.lockedBranches = lockedBranches;
        this.lifecycle = lifecycle;
        this.histories = histories;
        this.policyService = policyService;
        this.examinations = examinations;
    }

    public ReceptionService(ReceptionAccess access, ReceptionProfileRepository profiles,
                            ReceptionCheckInAppointmentRepository appointments,
                            ReceptionVisitRepository visits, ReceptionCheckInKeyRepository keys,
                            ReceptionAuditRepository audits, BranchRepository branches,
                            ObjectMapper mapper, AppointmentBookingLockRepository lockedBranches,
                            AppointmentLifecycleService lifecycle, AppointmentHistoryRepository histories) {
        this(access, profiles, appointments, visits, keys, audits, branches, mapper,
                lockedBranches, lifecycle, histories, null, null);
    }

    public void setTickets(DispatchTicketRepository tickets) {
        this.tickets = tickets;
    }

    public VisitResult checkInAppointment(HttpSession session, Long appointmentId, String symptoms, String requestKey) {
        return checkInAppointment(session, appointmentId, symptoms, requestKey, false, false);
    }

    public VisitResult checkInAppointment(HttpSession session, Long appointmentId, String symptoms, String requestKey,
                                         boolean priority, boolean emergency) {
        requireKey(requestKey);
        LockedCheckInContext ctx = lockAppointmentForCheckIn(session, appointmentId);
        Branch branch = ctx.branch();
        PatientProfile patient = ctx.patient();
        Appointment appointment = ctx.appointment();

        Visit existing = visits.findByAppointmentId(appointmentId).orElse(null);
        if (existing != null && existing.getStatus() != VisitStatus.CANCELLED) return result(existing);

        var prior = keys.findByRequestKey(requestKey);
        if (prior.isPresent()) {
            Visit priorVisit = prior.get().getVisit();
            if (priorVisit != null && appointmentId.equals(priorVisit.getAppointmentId())
                    && priorVisit.getStatus() != VisitStatus.CANCELLED) {
                return result(priorVisit);
            }
            throw error(HttpStatus.CONFLICT, "Mã yêu cầu check-in đã dùng cho lượt khám khác.");
        }

        verifyPostLockCheckInState(ctx);

        if (!appointment.getAppointmentDate().equals(today())) {
            throw error(HttpStatus.CONFLICT, "Chỉ check-in lịch hẹn trong ngày tại chi nhánh đã đặt. Hãy đổi lịch trước.");
        }

        LocalDateTime nowDateTime = LocalDateTime.now(clock);
        LocalTime nowTime = nowDateTime.toLocalTime();
        if (branch.getClosingTime() != null && !nowTime.isBefore(branch.getClosingTime())) {
            throw error(HttpStatus.CONFLICT, "Phòng khám đã đóng cửa trong ngày hôm nay, không thể tiếp nhận khám.");
        }
        if (policyService != null && policyService.sessionEnded(branch, appointment.getAppointmentDate(),
                appointment.getPreferredSession(), nowDateTime)) {
            throw error(HttpStatus.CONFLICT, "Ca khám đã kết thúc trong ngày hôm nay, không thể tiếp nhận khám.");
        }

        // Enforce state transition rules (strictly rejects direct PENDING -> CHECKED_IN)
        ReceptionTransitionPolicy.appointment(appointment.getStatus(), AppointmentStatus.CHECKED_IN);
        boolean bookedAndOnTime = evaluateGracePeriodAndRejectExpired(appointment, nowDateTime);
        String resolvedSymptoms = symptoms(symptoms, appointment.getInitialSymptoms());
        if (emergency && resolvedSymptoms.length() < 10) {
            throw error(HttpStatus.BAD_REQUEST, "Ca cấp cứu cần ghi rõ triệu chứng hoặc lý do ưu tiên (ít nhất 10 ký tự).");
        }
        String before = appointment.getStatus().name();
        String oldSnapshot = snapshot(appointment);
        lifecycle.transition(appointment, AppointmentStatus.CHECKED_IN, AppointmentLifecycleAction.CHECK_IN);

        rejectActiveVisit(patient);

        Visit visit = newVisit(patient, branch, emergency ? VisitType.EMERGENCY : VisitType.APPOINTMENT, appointmentId,
                resolvedSymptoms, nowDateTime, emergency, bookedAndOnTime);
        if (priority || emergency) {
            visit.setNotes(visit.getNotes() == null ? "[ƯU TIÊN]" : "[ƯU TIÊN] · " + visit.getNotes());
        }
        visits.saveAndFlush(visit);
        if (appointment.getDoctor() != null) {
            visit.setPrimaryDoctor(appointment.getDoctor());
            visits.saveAndFlush(visit);
            if (examinations != null) {
                examinations.ensureForAssignedVisit(visit.getVisitId());
            }
        } else {
            autoAssignDoctorOnCheckIn(branch, visit, nowDateTime);
        }
        storeKey(requestKey, visit);

        appointments.save(appointment);

        Long actorId = access.require(session).user().getUserId();
        if (queueCoordinator != null && visit.getPrimaryDoctor() != null) {
            queueCoordinator.issueTicketForVisitIfEligible(visit.getVisitId(), actorId);
        }
        boolean arrivedEarly = appointment.getExpectedTime() != null
                && nowDateTime.isBefore(LocalDateTime.of(appointment.getAppointmentDate(), appointment.getExpectedTime())
                        .minusMinutes(com.cvdcms.service.AppointmentSlotService.GRACE_PERIOD_MINUTES));
        String checkInReason = bookedAndOnTime
                ? "Check-in đúng giờ tại quầy tiếp đón"
                : arrivedEarly
                    ? "Check-in sớm hơn 15 phút trước giờ hẹn (chưa thuộc nhóm ưu tiên đúng giờ)"
                    : "Check-in tại quầy tiếp đón (trễ grace period / không giờ hẹn cố định)";
        recordHistory(appointment, actorId, "CHECK_IN", oldSnapshot, snapshot(appointment), checkInReason);

        audit(session, branch, emergency ? "CHECK_IN_EMERGENCY_APPOINTMENT" : "CHECK_IN_APPOINTMENT", visit,
                Map.of("appointmentId", appointmentId, "profileId", patient.getProfileId(),
                        "appointmentStatusBefore", before, "visitStatus", "WAITING",
                        "isBookedAndOnTime", bookedAndOnTime, "emergency", emergency));
        publishQueueEvent("APPOINTMENT_CHECKED_IN", branch.getBranchId(),
                visit.getPrimaryDoctor() != null ? visit.getPrimaryDoctor().getStaffId() : null,
                visit.getVisitId(), appointmentId, visit.getStatus().name(),
                "Check-in lịch hẹn #" + appointment.getAppointmentCode(), nowDateTime);
        return result(visit);
    }

    public VisitResult confirmAndCheckIn(HttpSession session, Long appointmentId, String symptoms, String requestKey) {
        return confirmAndCheckIn(session, appointmentId, symptoms, requestKey, false, false);
    }

    public VisitResult confirmAndCheckIn(HttpSession session, Long appointmentId, String symptoms, String requestKey,
                                        boolean priority, boolean emergency) {
        requireKey(requestKey);
        LockedCheckInContext ctx = lockAppointmentForCheckIn(session, appointmentId);
        Branch branch = ctx.branch();
        PatientProfile patient = ctx.patient();
        Appointment appointment = ctx.appointment();

        Visit existing = visits.findByAppointmentId(appointmentId).orElse(null);
        if (existing != null && existing.getStatus() != VisitStatus.CANCELLED) return result(existing);

        Visit priorVisit = keys.findByRequestKey(requestKey).map(ReceptionCheckInKey::getVisit).orElse(null);
        if (priorVisit != null) {
            if (appointmentId.equals(priorVisit.getAppointmentId()) && priorVisit.getStatus() != VisitStatus.CANCELLED) {
                return result(priorVisit);
            }
            throw error(HttpStatus.CONFLICT, "Mã yêu cầu check-in đã dùng cho lượt khám khác.");
        }

        verifyPostLockCheckInState(ctx);

        if (!appointment.getAppointmentDate().equals(today())) {
            throw error(HttpStatus.CONFLICT, "Chỉ check-in lịch hẹn trong ngày tại chi nhánh đã đặt. Hãy đổi lịch trước.");
        }

        LocalDateTime nowDateTime = LocalDateTime.now(clock);
        LocalTime nowTime = nowDateTime.toLocalTime();
        if (branch.getClosingTime() != null && !nowTime.isBefore(branch.getClosingTime())) {
            throw error(HttpStatus.CONFLICT, "Phòng khám đã đóng cửa trong ngày hôm nay, không thể tiếp nhận khám.");
        }
        if (policyService != null && policyService.sessionEnded(branch, appointment.getAppointmentDate(),
                appointment.getPreferredSession(), nowDateTime)) {
            throw error(HttpStatus.CONFLICT, "Ca khám đã kết thúc trong ngày hôm nay, không thể tiếp nhận khám.");
        }

        rejectActiveVisit(patient);

        Long actorId = access.require(session).user().getUserId();
        String initialStatus = appointment.getStatus().name();

        if (appointment.getStatus() == AppointmentStatus.PENDING) {
            // Revalidate via policy service
            if (policyService != null) {
                Long doctorId = appointment.getDoctor() != null ? appointment.getDoctor().getStaffId() : null;
                policyService.ensureBookable(branch, patient, appointment.getAppointmentDate(),
                        appointment.getPreferredSession(), doctorId, appointment.getAppointmentId(),
                        AppointmentActorType.RECEPTIONIST);
            }

            String pendingSnapshot = snapshot(appointment);
            // Step 1: PENDING -> CONFIRMED
            lifecycle.transition(appointment, AppointmentStatus.CONFIRMED, AppointmentLifecycleAction.CONFIRM);
            recordHistory(appointment, actorId, "CONFIRM", pendingSnapshot, snapshot(appointment),
                    "Lễ tân xác nhận lịch khi bệnh nhân có mặt tại phòng khám");
            appointments.saveAndFlush(appointment);
        } else if (appointment.getStatus() != AppointmentStatus.CONFIRMED) {
            throw error(HttpStatus.CONFLICT, "Trạng thái lịch hẹn (" + appointment.getStatus() + ") không cho phép xác nhận và tiếp đón.");
        }

        boolean bookedAndOnTime = evaluateGracePeriodAndRejectExpired(appointment, nowDateTime);
        String resolvedSymptoms = symptoms(symptoms, appointment.getInitialSymptoms());
        if (emergency && resolvedSymptoms.length() < 10) {
            throw error(HttpStatus.BAD_REQUEST, "Ca cấp cứu cần ghi rõ triệu chứng hoặc lý do ưu tiên (ít nhất 10 ký tự).");
        }

        // Step 2: CONFIRMED -> CHECKED_IN
        String confirmedSnapshot = snapshot(appointment);
        lifecycle.transition(appointment, AppointmentStatus.CHECKED_IN, AppointmentLifecycleAction.CHECK_IN);
        recordHistory(appointment, actorId, "CHECK_IN", confirmedSnapshot, snapshot(appointment),
                "Tiếp đón và tạo lượt khám sau khi xác nhận");

        Visit visit = newVisit(patient, branch, emergency ? VisitType.EMERGENCY : VisitType.APPOINTMENT, appointmentId,
                resolvedSymptoms, nowDateTime, emergency, bookedAndOnTime);
        if (priority || emergency) {
            visit.setNotes(visit.getNotes() == null ? "[ƯU TIÊN]" : "[ƯU TIÊN] · " + visit.getNotes());
        }
        visits.saveAndFlush(visit);
        if (appointment.getDoctor() != null) {
            visit.setPrimaryDoctor(appointment.getDoctor());
            visits.saveAndFlush(visit);
            if (examinations != null) {
                examinations.ensureForAssignedVisit(visit.getVisitId());
            }
        } else {
            autoAssignDoctorOnCheckIn(branch, visit, nowDateTime);
        }
        storeKey(requestKey, visit);

        appointments.save(appointment);

        Long confirmActorId = access.require(session).user().getUserId();
        if (queueCoordinator != null && visit.getPrimaryDoctor() != null) {
            queueCoordinator.issueTicketForVisitIfEligible(visit.getVisitId(), confirmActorId);
        }

        audit(session, branch, emergency ? "CONFIRM_AND_CHECK_IN_EMERGENCY" : "CONFIRM_AND_CHECK_IN", visit,
                Map.of("appointmentId", appointmentId, "profileId", patient.getProfileId(),
                        "appointmentStatusBefore", initialStatus, "visitStatus", "WAITING",
                        "isBookedAndOnTime", bookedAndOnTime, "emergency", emergency));
        publishQueueEvent("APPOINTMENT_CHECKED_IN", branch.getBranchId(),
                visit.getPrimaryDoctor() != null ? visit.getPrimaryDoctor().getStaffId() : null,
                visit.getVisitId(), appointmentId, visit.getStatus().name(),
                "Xác nhận & Check-in lịch hẹn #" + appointment.getAppointmentCode(), nowDateTime);
        return result(visit);
    }

    @Transactional
    public VisitResult revertCheckIn(HttpSession session, Long appointmentId) {
        Appointment reference = appointments.findById(appointmentId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Không tìm thấy lịch hẹn."));
        Branch branch = allowedBranch(session, reference.getBranch().getBranchId());

        PatientProfile patient = lockedActiveProfile(reference.getPatientProfile().getProfileId());
        lockBranchForBooking(branch.getBranchId());
        Appointment appointment = appointments.lockById(appointmentId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Không tìm thấy lịch hẹn."));

        if (appointment.getStatus() != AppointmentStatus.CHECKED_IN) {
            throw error(HttpStatus.CONFLICT, "Chỉ có thể hoàn tác lịch hẹn đang ở trạng thái Đã check-in.");
        }

        Visit visit = visits.findByAppointmentId(appointmentId).orElse(null);
        if (visit != null) {
            if (!canRevertVisit(visit)) {
                throw error(HttpStatus.CONFLICT, "Lượt khám đã được chuyển sang điều phối ghế / cấp số hàng chờ hoặc đang khám, không thể hoàn tác tại bước tiếp đón.");
            }
            visit.setStatus(VisitStatus.CANCELLED);
            visits.saveAndFlush(visit);
            if (examinations != null) {
                examinations.cleanupOnRevertCheckIn(visit.getVisitId());
            }

            // Hủy toàn bộ QueueTicket liên quan để tránh ghost ticket trên hàng chờ và màn hình TV
            boolean hadCalledTicket = false;
            if (tickets != null) {
                List<QueueTicket> visitTickets = tickets.findAllByVisitAndType(visit.getVisitId(), QueueType.EXAM);
                for (QueueTicket t : visitTickets) {
                    if (t.getStatus() == QueueStatus.CALLED) {
                        hadCalledTicket = true;
                    }
                    t.setStatus(QueueStatus.CANCELLED);
                }
                if (!visitTickets.isEmpty()) {
                    tickets.saveAll(visitTickets);
                }
            }
            keys.findByVisit_VisitId(visit.getVisitId()).ifPresent(keys::delete);
            if (hadCalledTicket && queueCoordinator != null && visit.getPrimaryDoctor() != null) {
                Long actorUserId = access.require(session).user().getUserId();
                queueCoordinator.autoCallNextForDoctorIfIdle(branch.getBranchId(), visit.getPrimaryDoctor().getStaffId(), actorUserId);
            }
        }

        String oldSnapshot = snapshot(appointment);
        ReceptionTransitionPolicy.appointment(appointment.getStatus(), AppointmentStatus.CONFIRMED);
        lifecycle.transition(appointment, AppointmentStatus.CONFIRMED, AppointmentLifecycleAction.REVERT_CHECK_IN);
        appointments.save(appointment);

        Long actorId = access.require(session).user().getUserId();
        recordHistory(appointment, actorId, "REVERT_CHECK_IN", oldSnapshot, snapshot(appointment),
                "Hoàn tác tiếp đón: Bệnh nhân chưa đến hoặc bấm nhầm");

        audit(session, branch, "REVERT_CHECK_IN_APPOINTMENT", visit,
                Map.of("appointmentId", appointmentId, "profileId", patient.getProfileId(),
                        "appointmentStatusAfter", "CONFIRMED"));
        return visit != null ? result(visit) : new VisitResult(appointment.getAppointmentId(), appointment.getAppointmentCode(), patient.getFullName(), branch.getBranchName(), VisitType.APPOINTMENT, VisitStatus.CANCELLED, appointment.getCreatedAt());
    }

    @Transactional
    public void updateAppointmentNote(HttpSession session, Long appointmentId, String note) {
        Appointment reference = appointments.findById(appointmentId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Không tìm thấy lịch hẹn."));
        allowedBranch(session, reference.getBranch().getBranchId());

        Appointment appointment = appointments.lockById(appointmentId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Không tìm thấy lịch hẹn."));

        if (appointment.getStatus() == AppointmentStatus.CHECKED_IN) {
            throw error(HttpStatus.CONFLICT, "Lịch hẹn đã tiếp đón không thể sửa chú thích trực tiếp ở đây.");
        }

        String trimmed = note != null ? note.trim() : "";
        if (trimmed.length() > 500) {
            throw error(HttpStatus.BAD_REQUEST, "Chú thích tối đa 500 ký tự.");
        }
        appointment.setInitialSymptoms(trimmed);
        appointments.save(appointment);
    }

    public VisitResult checkInWalkIn(HttpSession session, Long branchId, Long profileId,
                                    String symptoms, boolean emergency, String requestKey) {
        return checkInWalkIn(session, branchId, profileId, symptoms, emergency, false, requestKey);
    }

    public VisitResult checkInWalkIn(HttpSession session, Long branchId, Long profileId,
                                    String symptoms, boolean emergency, boolean priority, String requestKey) {
        requireKey(requestKey);
        Branch branch = allowedBranch(session, branchId);
        PatientProfile patient = lockedActiveProfile(profileId);
        lockBranchForBooking(branchId);

        var prior = keys.findByRequestKey(requestKey);
        if (prior.isPresent()) {
            Visit existing = prior.get().getVisit();
            if (!existing.getPatientProfile().getProfileId().equals(profileId)
                    || !existing.getBranch().getBranchId().equals(branchId)) {
                throw error(HttpStatus.CONFLICT, "Mã yêu cầu check-in đã dùng cho bệnh nhân khác.");
            }
            return result(existing);
        }

        rejectActiveVisit(patient);
        String initialSymptoms = symptoms(symptoms, null);
        if (emergency && initialSymptoms.length() < 10) {
            throw error(HttpStatus.BAD_REQUEST, "Ca cấp cứu cần ghi rõ triệu chứng hoặc lý do ưu tiên (ít nhất 10 ký tự).");
        }

        // Walk-in / Emergency does NOT create or mutate any Appointment
        LocalDateTime nowDateTime = LocalDateTime.now(clock);
        Visit visit = newVisit(patient, branch, emergency ? VisitType.EMERGENCY : VisitType.WALK_IN,
                null, initialSymptoms, nowDateTime, emergency, false);
        if (priority || emergency) {
            visit.setNotes(visit.getNotes() == null ? "[ƯU TIÊN]" : "[ƯU TIÊN] " + visit.getNotes());
        }
        visits.saveAndFlush(visit);
        autoAssignDoctorOnCheckIn(branch, visit, nowDateTime);
        storeKey(requestKey, visit);

        Long walkInActorId = access.require(session).user().getUserId();
        if (queueCoordinator != null && visit.getPrimaryDoctor() != null) {
            queueCoordinator.issueTicketForVisitIfEligible(visit.getVisitId(), walkInActorId);
        }

        audit(session, branch, emergency ? "CHECK_IN_EMERGENCY" : "CHECK_IN_WALK_IN", visit,
                Map.of("profileId", profileId, "visitStatus", "WAITING", "visitType", visit.getVisitType().name(),
                        "initialSymptoms", initialSymptoms, "isEmergency", emergency, "isBookedAndOnTime", false));
        publishQueueEvent(emergency ? "EMERGENCY_ADDED" : "WALK_IN_ADDED", branchId,
                visit.getPrimaryDoctor() != null ? visit.getPrimaryDoctor().getStaffId() : null,
                visit.getVisitId(), null, visit.getStatus().name(),
                "Tiếp nhận " + (emergency ? "cấp cứu" : "vãng lai") + " " + patient.getFullName(), nowDateTime);
        return result(visit);
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(HttpSession session, Long requestedBranchId, String query) {
        return dashboard(session, requestedBranchId, query, null, null);
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(HttpSession session, Long requestedBranchId, String query, LocalDate requestedDay) {
        return dashboard(session, requestedBranchId, query, requestedDay, null);
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(HttpSession session, Long requestedBranchId, String query, LocalDate requestedDay, String datePreset) {
        var scope = access.require(session);
        List<Branch> options = branches.findByIsActiveTrueOrderByBranchNameAsc().stream()
                .filter(b -> scope.permits(b.getBranchId())).toList();
        if (options.isEmpty()) {
            throw error(HttpStatus.FORBIDDEN, "Không có chi nhánh tiếp đón đang hoạt động.");
        }
        Long branchId = requestedBranchId;
        if (branchId == null && session != null) {
            Object sId = session.getAttribute("currentBranchId");
            if (sId instanceof Long sid && options.stream().anyMatch(b -> b.getBranchId().equals(sid))) {
                branchId = sid;
            }
        }
        if (branchId == null) {
            branchId = options.get(0).getBranchId();
        }
        if (session != null) {
            session.setAttribute("currentBranchId", branchId);
        }
        Branch branch = allowedBranch(session, branchId);

        String effectivePreset = (datePreset != null && !datePreset.isBlank()) ? datePreset.trim().toUpperCase() : null;
        LocalDate effectiveDay = requestedDay;
        if (effectivePreset == null) {
            effectivePreset = (requestedDay != null) ? "DAY" : "TODAY";
        }

        LocalDate appointmentDay = (effectiveDay != null) ? effectiveDay : today();
        List<Visit> historyVisits;

        if ("ALL".equalsIgnoreCase(effectivePreset)) {
            historyVisits = visits.forAllDays(branchId);
            effectiveDay = null;
        } else if ("THIS_WEEK".equalsIgnoreCase(effectivePreset)) {
            LocalDate mon = today().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
            historyVisits = visits.forDay(branchId, mon.atStartOfDay(), today().plusDays(1).atStartOfDay());
            effectiveDay = today();
        } else if ("THIS_MONTH".equalsIgnoreCase(effectivePreset)) {
            LocalDate firstOfMonth = today().withDayOfMonth(1);
            historyVisits = visits.forDay(branchId, firstOfMonth.atStartOfDay(), today().plusDays(1).atStartOfDay());
            effectiveDay = today();
        } else {
            LocalDate targetDay = (effectiveDay != null) ? effectiveDay : today();
            historyVisits = visits.forDay(branchId, targetDay.atStartOfDay(), targetDay.plusDays(1).atStartOfDay());
            effectiveDay = targetDay;
            if (targetDay.isEqual(today())) {
                effectivePreset = "TODAY";
            }
        }

        Map<Long, QueueTicket> ticketMap = new HashMap<>();
        if (tickets != null) {
            try {
                LocalDate ticketDay = (effectiveDay != null) ? effectiveDay : today();
                tickets.board(branchId, ticketDay, QueueType.EXAM).forEach(t -> {
                    if (t.getVisit() != null) {
                        ticketMap.put(t.getVisit().getVisitId(), t);
                    }
                });
            } catch (Exception ignored) {}
        }

        List<Appointment> dayAppointments = appointments.forDay(branchId, appointmentDay,
                List.of(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED, AppointmentStatus.CHECKED_IN));
        Map<Long, Appointment> appointmentById = new HashMap<>();
        for (Appointment a : dayAppointments) {
            appointmentById.put(a.getAppointmentId(), a);
        }
        Map<Long, Visit> visitByAppointmentId = new HashMap<>();
        for (Visit v : historyVisits) {
            if (v.getAppointmentId() != null && v.getStatus() != VisitStatus.CANCELLED) {
                visitByAppointmentId.putIfAbsent(v.getAppointmentId(), v);
            }
        }
        List<AppointmentRow> appointmentRows = dayAppointments
                .stream().map(a -> appointmentRow(a, visitByAppointmentId.get(a.getAppointmentId()), ticketMap)).toList();

        Comparator<VisitRow> triageComparator = Comparator
                .comparingInt((VisitRow v) -> getVisitRowStatusRank(v.status()))
                .thenComparingInt(VisitRow::queueTierRank)
                .thenComparing(VisitRow::checkedInAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(VisitRow::id, Comparator.nullsLast(Comparator.naturalOrder()));

        List<VisitRow> visitRows = historyVisits
                .stream()
                .filter(v -> v != null && v.getStatus() != VisitStatus.CANCELLED)
                .map(v -> visitRow(v, ticketMap, appointmentById))
                .sorted(triageComparator)
                .toList();
        String term = PatientSearchPolicy.normalize(query);
        List<PatientRow> matches = List.of();
        String walkInWarning = null;
        if (!term.isEmpty()) {
            try {
                matches = executeWalkInPatientSearch(scope.admin() ? null : List.of(branchId), term);
            } catch (ResponseStatusException ex) {
                if (ex.getStatusCode() == HttpStatus.CONFLICT) {
                    walkInWarning = ex.getReason();
                } else {
                    throw ex;
                }
            }
        }
        return new Dashboard(branchId, branch.getBranchName(), effectiveDay, options, appointmentRows, visitRows,
                matches, term, UUID.randomUUID().toString(), effectivePreset, walkInWarning);
    }

    private int getVisitRowStatusRank(VisitStatus status) {
        if (status == null) return 2;
        return switch (status) {
            case IN_EXAM, IN_TREATMENT -> 0; // Đang khám / điều trị
            case WAITING -> 1; // Đang chờ khám
            case WAITING_PAYMENT, COMPLETED -> 2; // Đã xong / chờ thanh toán
            case CANCELLED -> 3; // Đã hủy
            default -> 2;
        };
    }

    @Transactional(readOnly = true)
    public List<PatientRow> searchPatients(HttpSession session, String query) {
        return searchPatients(session, null, query);
    }

    @Transactional(readOnly = true)
    public List<PatientRow> searchPatients(HttpSession session, Long requestedBranchId, String query) {
        ReceptionAccess.Scope scope = access.require(session);
        List<Long> scopedBranchIds = resolveWalkInSearchBranches(session, scope, requestedBranchId);
        return executeWalkInPatientSearch(scopedBranchIds, PatientSearchPolicy.normalize(query));
    }

    private List<Long> resolveWalkInSearchBranches(HttpSession session,
                                                   ReceptionAccess.Scope scope,
                                                   Long requestedBranchId) {
        if (requestedBranchId != null) {
            if (!scope.permits(requestedBranchId)) {
                throw error(HttpStatus.FORBIDDEN, "Không được tìm kiếm bệnh nhân ở chi nhánh này.");
            }
            Branch branch = branches.findById(requestedBranchId)
                    .filter(b -> Boolean.TRUE.equals(b.getIsActive()))
                    .orElseThrow(() -> error(HttpStatus.FORBIDDEN, "Chi nhánh không tồn tại hoặc đã ngừng hoạt động."));
            return scope.admin() ? null : List.of(branch.getBranchId());
        }
        if (scope.admin()) {
            return null;
        }
        List<Long> allowedIds = scope.branchIds();
        if (allowedIds == null || allowedIds.isEmpty()) {
            throw error(HttpStatus.FORBIDDEN, "Tài khoản chưa được phân công chi nhánh tiếp đón hợp lệ.");
        }
        if (session != null && session.getAttribute("currentBranchId") instanceof Long sid && scope.permits(sid)) {
            Optional<Branch> sessionBranch = branches.findById(sid).filter(b -> Boolean.TRUE.equals(b.getIsActive()));
            if (sessionBranch.isPresent()) {
                return List.of(sessionBranch.get().getBranchId());
            }
        }
        for (Long candidateId : allowedIds) {
            if (candidateId == null) {
                continue;
            }
            Optional<Branch> candidate = branches.findById(candidateId).filter(b -> Boolean.TRUE.equals(b.getIsActive()));
            if (candidate.isPresent()) {
                return List.of(candidate.get().getBranchId());
            }
        }
        throw error(HttpStatus.FORBIDDEN, "Không có chi nhánh tiếp đón đang hoạt động.");
    }

    private List<PatientRow> executeWalkInPatientSearch(List<Long> scopedBranchIds, String term) {
        if (term.isEmpty()) {
            List<PatientProfile> list = scopedBranchIds == null
                    ? profiles.findRecentActive(PageRequest.of(0, 20))
                    : profiles.findRecentActiveByBranches(scopedBranchIds, PageRequest.of(0, 20));
            return list.stream().map(this::patientRow).toList();
        }
        PatientSearchPolicy.ExactIdentifierType exactType = PatientSearchPolicy.classifyExactIdentifier(term);
        if (exactType != PatientSearchPolicy.ExactIdentifierType.NONE) {
            List<PatientProfile> exactCandidates = switch (exactType) {
                case PHONE -> profiles.findExactActiveByPhone(term);
                case ID_CARD -> profiles.findExactActiveByIdCard(term);
                case PATIENT_CODE -> profiles.findExactActiveByPatientCode(term);
                case NONE -> List.of();
            };
            Optional<PatientProfile> matched = PatientSearchPolicy.resolveSingleConsistentExactMatch(
                    exactType, term, exactCandidates);
            if (matched.isEmpty()) {
                return List.of();
            }
            PatientProfile profile = matched.get();
            boolean inScope = scopedBranchIds == null || isProfileAccessibleInWalkInBranches(profile, scopedBranchIds);
            return List.of(inScope ? patientRow(profile) : crossBranchPatientRow(profile));
        }
        List<PatientProfile> list = scopedBranchIds == null
                ? profiles.search(term, PageRequest.of(0, 20))
                : profiles.searchByBranches(scopedBranchIds, term, PageRequest.of(0, 20));
        return list.stream().map(this::patientRow).toList();
    }

    private boolean isProfileAccessibleInWalkInBranches(PatientProfile p, List<Long> branchIds) {
        if (p.getPreferredBranch() != null && p.getPreferredBranch().getBranchId() != null
                && branchIds.contains(p.getPreferredBranch().getBranchId())) {
            return true;
        }
        return p.getProfileId() != null && profiles.isProfileAccessibleInBranches(p.getProfileId(), branchIds);
    }

    private void lockBranchForBooking(Long branchId) {
        if (lockedBranches != null && branchId != null) {
            Branch lockedBranch = lockedBranches.findByIdForBooking(branchId)
                    .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Chi nhánh không tồn tại."));
            refreshIfManaged(lockedBranch);
            if (!Boolean.TRUE.equals(lockedBranch.getIsActive())) {
                throw error(HttpStatus.FORBIDDEN, "Không được check-in tại chi nhánh này.");
            }
        }
    }

    private PatientProfile lockedActiveProfile(Long id) {
        PatientProfile patient = profiles.lockById(id)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Không tìm thấy hồ sơ bệnh nhân. Tạo hồ sơ ở FT03 trước."));
        refreshIfManaged(patient);
        if (!"ACTIVE".equalsIgnoreCase(patient.getStatus())) {
            throw error(HttpStatus.CONFLICT, "Hồ sơ bệnh nhân không hoạt động.");
        }
        return patient;
    }

    private record LockedCheckInContext(
            AppointmentLockSnapshot snapshot,
            Branch branch,
            PatientProfile patient,
            StaffProfile lockedDoctor,
            Appointment appointment
    ) { }

    private LockedCheckInContext lockAppointmentForCheckIn(HttpSession session, Long appointmentId) {
        AppointmentLockSnapshot snap = appointments.findLockSnapshotById(appointmentId)
                .orElseGet(() -> appointments.findById(appointmentId).map(AppointmentLockSnapshot::from).orElse(null));
        if (snap == null || snap.branchId() == null || snap.patientProfileId() == null) {
            throw error(HttpStatus.NOT_FOUND, "Không tìm thấy lịch hẹn.");
        }
        Branch branch = allowedBranch(session, snap.branchId());

        // Global lock hierarchy: 1. PatientProfile -> 2. Branch -> 3. StaffProfile (nếu có doctor) -> 4. Appointment
        PatientProfile patient = lockedActiveProfile(snap.patientProfileId());
        lockBranchForBooking(branch.getBranchId());
        StaffProfile lockedDoctor = null;
        if (snap.doctorId() != null && staffProfiles != null) {
            Optional<StaffProfile> lockedDocOpt = staffProfiles.lockById(snap.doctorId());
            if (lockedDocOpt.isPresent()) {
                lockedDoctor = lockedDocOpt.get();
                refreshIfManaged(lockedDoctor);
            } else if (entityManager != null) {
                throw error(HttpStatus.CONFLICT, "Bác sĩ phụ trách lịch hẹn không còn tồn tại hoặc đã thay đổi.");
            }
        }
        if (snap.doctorId() != null && staffSchedules != null && snap.appointmentDate() != null) {
            // Global lock hierarchy: StaffProfile -> StaffSchedule -> Appointment
            staffSchedules.lockDoctorSchedulesForDate(snap.doctorId(), snap.appointmentDate(), ScheduleStatus.SCHEDULED);
        }
        Appointment appointment = appointments.lockById(appointmentId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Không tìm thấy lịch hẹn."));
        refreshIfManaged(appointment);

        if (appointment.getPatientProfile() == null
                || !appointment.getPatientProfile().getProfileId().equals(patient.getProfileId())
                || appointment.getBranch() == null
                || !appointment.getBranch().getBranchId().equals(branch.getBranchId())) {
            throw error(HttpStatus.CONFLICT, "Lịch hẹn đã thay đổi. Vui lòng tải lại trang.");
        }
        return new LockedCheckInContext(snap, branch, patient, lockedDoctor, appointment);
    }

    private void verifyPostLockCheckInState(LockedCheckInContext ctx) {
        if (!ctx.snapshot().matchesLockedState(ctx.appointment())) {
            throw error(HttpStatus.CONFLICT, "Lịch hẹn đã thay đổi trong lúc xử lý. Vui lòng tải lại trang.");
        }
        Appointment appt = ctx.appointment();
        if (appt.getDoctor() != null && ctx.lockedDoctor() != null
                && ctx.lockedDoctor().getStatus() != StaffStatus.ACTIVE) {
            throw error(HttpStatus.CONFLICT,
                    "Bác sĩ phụ trách lịch hẹn hiện không còn hoạt động (NEEDS_REASSIGNMENT). Vui lòng đổi bác sĩ hoặc cập nhật lịch hẹn trước khi tiếp đón.");
        }
        if (appt.getDoctor() != null && appt.getAppointmentDate() != null) {
            Long doctorId = appt.getDoctor().getStaffId();
            Long branchId = ctx.branch() != null ? ctx.branch().getBranchId() : null;
            LocalDate date = appt.getAppointmentDate();

            if (leaveRequests != null) {
                List<LeaveRequest> approvedLeaves = leaveRequests.findApprovedForStaffOnDate(
                        doctorId, date, LeaveStatus.APPROVED);
                if (approvedLeaves != null) {
                    boolean leaveBlocked = approvedLeaves.stream().anyMatch(l ->
                            (l.getBranch() == null || branchId == null || branchId.equals(l.getBranch().getBranchId()))
                                    && shiftMatchesAppointment(l.getShift(), appt, ctx.branch()));
                    if (leaveBlocked) {
                        throw error(HttpStatus.CONFLICT,
                                "Bác sĩ phụ trách đang nghỉ phép trong ca khám này (NEEDS_REASSIGNMENT). Vui lòng đổi bác sĩ hoặc đề xuất dời lịch trước khi check-in.");
                    }
                }
            }

            if (staffSchedules != null && branchId != null) {
                List<StaffSchedule> allDoctorDaySchedules = staffSchedules.findByStaffStaffIdAndWorkDateBetween(doctorId, date, date);
                boolean hasOnLeaveShift = allDoctorDaySchedules != null && allDoctorDaySchedules.stream().anyMatch(s ->
                        s.getStatus() == ScheduleStatus.ON_LEAVE
                                && s.getBranch() != null && branchId.equals(s.getBranch().getBranchId())
                                && shiftMatchesAppointment(s.getShift(), appt, ctx.branch()));
                if (hasOnLeaveShift) {
                    throw error(HttpStatus.CONFLICT,
                            "Ca làm việc của bác sĩ phụ trách đã chuyển sang Nghỉ phép (NEEDS_REASSIGNMENT). Vui lòng đổi bác sĩ hoặc đề xuất dời lịch trước khi check-in.");
                }

                List<StaffSchedule> scheduledShifts = staffSchedules.findDoctorSchedulesForDate(doctorId, date, ScheduleStatus.SCHEDULED);
                boolean hasMatchingActiveShift = scheduledShifts != null && scheduledShifts.stream().anyMatch(s ->
                        s.getBranch() != null && branchId.equals(s.getBranch().getBranchId())
                                && Boolean.TRUE.equals(s.getBranch().getIsActive())
                                && s.getShift() != null && Boolean.TRUE.equals(s.getShift().getIsActive())
                                && shiftMatchesAppointment(s.getShift(), appt, ctx.branch()));
                if (!hasMatchingActiveShift) {
                    throw error(HttpStatus.CONFLICT,
                            "Bác sĩ phụ trách không có ca trực hợp lệ cho lịch hẹn này (NEEDS_REASSIGNMENT). Vui lòng đổi bác sĩ hoặc đề xuất dời lịch trước khi check-in.");
                }
            }
        }
    }

    private boolean shiftMatchesAppointment(WorkShift shift, Appointment appt, Branch branch) {
        if (shift == null) {
            return true;
        }
        LocalTime shiftStart = shift.getStartTime();
        LocalTime shiftEnd = shift.getEndTime();
        if (shiftStart == null || shiftEnd == null) {
            return true;
        }
        if (appt.getExpectedTime() != null) {
            return !appt.getExpectedTime().isBefore(shiftStart) && appt.getExpectedTime().isBefore(shiftEnd);
        }
        PreferredSession session = appt.getPreferredSession();
        if (session != null && session != PreferredSession.ANY && policyService != null && branch != null) {
            AppointmentPolicyService.SessionRange range = policyService.sessionRange(branch, session);
            if (range != null && range.start() != null && range.end() != null) {
                return shiftStart.isBefore(range.end()) && shiftEnd.isAfter(range.start());
            }
        }
        return true;
    }

    private void refreshIfManaged(Object entity) {
        if (entityManager != null && entity != null && entityManager.contains(entity)) {
            entityManager.refresh(entity);
        }
    }

    private Branch allowedBranch(HttpSession session, Long branchId) {
        var scope = access.require(session);
        Branch branch = branches.findById(branchId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Không tìm thấy chi nhánh."));
        if (!Boolean.TRUE.equals(branch.getIsActive()) || !scope.permits(branchId)) {
            throw error(HttpStatus.FORBIDDEN, "Không được check-in tại chi nhánh này.");
        }
        return branch;
    }

    private void rejectActiveVisit(PatientProfile patient) {
        LocalDate today = today();
        LocalDateTime startOfToday = today.atStartOfDay();
        LocalDateTime endOfToday = today.plusDays(1).atStartOfDay();
        var allActive = visits.activeForProfile(patient.getProfileId(), ReceptionTransitionPolicy.activeVisits());
        var activeToday = allActive.stream()
                .filter(v -> v.getCheckInAt() == null || (!v.getCheckInAt().isBefore(startOfToday) && v.getCheckInAt().isBefore(endOfToday)))
                .toList();
        if (!activeToday.isEmpty()) {
            throw error(HttpStatus.CONFLICT, "Bệnh nhân đã có lượt khám đang hoạt động: " + activeToday.get(0).getVisitCode());
        }
        for (Visit past : allActive) {
            if (past.getCheckInAt() != null && past.getCheckInAt().isBefore(startOfToday) && past.getStatus() == VisitStatus.WAITING) {
                past.setStatus(VisitStatus.CANCELLED);
                past.setNotes((past.getNotes() != null ? past.getNotes() + " | " : "") + "Tự động đóng lượt khám chưa hoàn tất từ ngày trước (" + past.getCheckInAt().toLocalDate() + ")");
                visits.save(past);
            }
        }
    }

    private Visit newVisit(PatientProfile patient, Branch branch, VisitType type, Long appointmentId,
                           String initialSymptoms, LocalDateTime checkInTime,
                           boolean isEmergency, boolean isBookedAndOnTime) {
        Visit visit = new Visit();
        visit.setVisitCode("V" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        visit.setPatientProfile(patient);
        visit.setBranch(branch);
        visit.setAppointmentId(appointmentId);
        visit.setVisitType(type);
        visit.setIsEmergency(isEmergency || type == VisitType.EMERGENCY);
        visit.setIsBookedAndOnTime(isBookedAndOnTime);
        visit.setCheckInAt(checkInTime != null ? checkInTime : LocalDateTime.now(clock));
        visit.setInitialSymptoms(initialSymptoms);
        visit.setStatus(VisitStatus.WAITING);
        return visit;
    }

    /**
     * Đối chiếu thời gian check-in của server với expected_time trong cửa sổ đúng giờ [-15p, +15p]:
     * - [expected_time - 15 phút, expected_time + 15 phút] (tính cả 2 biên) -> đúng giờ (Priority 2).
     * - Đến sớm hơn 15 phút (< expected_time - 15 phút) -> không thuộc nhóm đúng giờ (Priority 4).
     * - 16..30 phút sau expected_time -> trễ giờ (Priority 4, is_booked_and_on_time = false).
     * - > 30 phút sau expected_time -> quá hạn NO_SHOW, không cho check-in bằng lịch hẹn cũ.
     */
    private boolean evaluateGracePeriodAndRejectExpired(Appointment appointment, LocalDateTime checkInTime) {
        if (appointment.getExpectedTime() == null || appointment.getAppointmentDate() == null) {
            return false;
        }
        LocalDateTime expectedDateTime = LocalDateTime.of(appointment.getAppointmentDate(), appointment.getExpectedTime());
        if (checkInTime.isAfter(expectedDateTime.plusMinutes(com.cvdcms.service.AppointmentSlotService.NO_SHOW_CUTOFF_MINUTES))) {
            throw error(HttpStatus.CONFLICT,
                    "Lịch hẹn đã quá 30 phút so với giờ hẹn dự kiến (đủ điều kiện NO_SHOW). Vui lòng tiếp nhận theo luồng Walk-in mới nếu bệnh nhân vẫn muốn khám.");
        }
        LocalDateTime earliestOnTime = expectedDateTime.minusMinutes(com.cvdcms.service.AppointmentSlotService.GRACE_PERIOD_MINUTES);
        LocalDateTime latestOnTime = expectedDateTime.plusMinutes(com.cvdcms.service.AppointmentSlotService.GRACE_PERIOD_MINUTES);
        return !checkInTime.isBefore(earliestOnTime) && !checkInTime.isAfter(latestOnTime);
    }

    private void autoAssignDoctorOnCheckIn(Branch branch, Visit visit, LocalDateTime nowDateTime) {
        if (staffSchedules == null || branch == null || visit == null || visit.getPrimaryDoctor() != null) {
            return;
        }
        try {
            LocalDate day = nowDateTime.toLocalDate();
            LocalTime now = nowDateTime.toLocalTime();
            List<StaffSchedule> todaySchedules = staffSchedules.findForBooking(branch.getBranchId(), day, ScheduleStatus.SCHEDULED);
            if (todaySchedules == null || todaySchedules.isEmpty()) {
                return;
            }
            List<LeaveRequest> approvedLeaves = leaveRequests != null
                    ? leaveRequests.findApprovedOn(branch.getBranchId(), day, LeaveStatus.APPROVED)
                    : List.of();
            Set<Long> activeDoctorIds = new HashSet<>();
            if (branchAssignments != null) {
                for (StaffBranchAssignment sba : branchAssignments.findByBranchAndStatus(branch.getBranchId(), AssignmentStatus.ACTIVE)) {
                    if (sba.getStaff() != null && sba.getStaff().getStatus() == StaffStatus.ACTIVE && sba.getRole() != null) {
                        String rName = sba.getRole().getRoleName() != null ? sba.getRole().getRoleName().toUpperCase(Locale.ROOT) : "";
                        if (rName.contains("BAC_SI") || rName.contains("DOCTOR")) {
                            activeDoctorIds.add(sba.getStaff().getStaffId());
                        }
                    }
                }
            }
            Map<Long, StaffProfile> onDutyDoctors = new LinkedHashMap<>();
            for (StaffSchedule s : todaySchedules) {
                StaffProfile doc = s.getStaff();
                WorkShift sh = s.getShift();
                if (doc == null || sh == null || !Boolean.TRUE.equals(sh.getIsActive())) continue;
                if (doc.getStatus() != null && doc.getStatus() != StaffStatus.ACTIVE) continue;
                if (!activeDoctorIds.isEmpty() && !activeDoctorIds.contains(doc.getStaffId())) continue;
                if (now.isBefore(sh.getStartTime()) || now.isAfter(sh.getEndTime())) continue;
                boolean onLeave = approvedLeaves.stream().anyMatch(l ->
                        l.getStaff() != null && doc.getStaffId().equals(l.getStaff().getStaffId())
                                && (l.getShift() == null || l.getShift().getShiftId().equals(sh.getShiftId())));
                if (!onLeave) {
                    onDutyDoctors.putIfAbsent(doc.getStaffId(), doc);
                }
            }
            if (onDutyDoctors.isEmpty()) {
                return;
            }
            // Cân bằng tải dựa trên số ca đang chờ / đang khám trong ngày của từng bác sĩ (1 query)
            Map<Long, Long> waitingCounts = new HashMap<>();
            for (Visit dayVisit : visits.forDay(branch.getBranchId(), day.atStartOfDay(), day.plusDays(1).atStartOfDay())) {
                if (dayVisit.getPrimaryDoctor() != null
                        && (dayVisit.getStatus() == VisitStatus.WAITING
                        || dayVisit.getStatus() == VisitStatus.IN_EXAM
                        || dayVisit.getStatus() == VisitStatus.IN_TREATMENT)) {
                    waitingCounts.merge(dayVisit.getPrimaryDoctor().getStaffId(), 1L, Long::sum);
                }
            }
            StaffProfile chosen = onDutyDoctors.values().stream()
                    .min(Comparator.comparingLong((StaffProfile d) -> waitingCounts.getOrDefault(d.getStaffId(), 0L))
                            .thenComparingLong(StaffProfile::getStaffId))
                    .orElse(null);
            if (chosen != null) {
                visit.setPrimaryDoctor(chosen);
                visits.saveAndFlush(visit);
                if (examinations != null) {
                    examinations.ensureForAssignedVisit(visit.getVisitId());
                }
            }
        } catch (Exception ignored) {
            // Không làm gián đoạn luồng check-in nếu thiếu dữ liệu lịch trực chi tiết
        }
    }

    private void publishQueueEvent(String eventType, Long branchId, Long doctorId, Long visitId,
                                   Long appointmentId, String status, String summary, LocalDateTime occurredAt) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new com.cvdcms.event.ClinicQueueDomainEvent(
                    eventType, branchId, doctorId, visitId, appointmentId, status, summary, occurredAt));
        }
    }

    private void storeKey(String requestKey, Visit visit) {
        ReceptionCheckInKey key = new ReceptionCheckInKey();
        key.setRequestKey(requestKey);
        key.setVisit(visit);
        keys.save(key);
    }

    private void audit(HttpSession session, Branch branch, String action, Visit visit, Map<String, ?> values) {
        SystemAuditLog log = new SystemAuditLog();
        log.setUser(access.require(session).user());
        log.setBranch(branch);
        log.setAction(action);
        log.setEntityTable("visits");
        log.setEntityId(visit.getVisitId());
        try {
            log.setNewValuesJson(mapper.writeValueAsString(values));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không thể ghi nhật ký check-in.", e);
        }
        audits.save(log);
    }

    private void recordHistory(Appointment a, Long actorId, String action, String oldVal, String newVal, String reason) {
        if (histories == null) return;
        AppointmentHistory h = new AppointmentHistory();
        h.setAppointment(a);
        h.setActorUserId(actorId != null ? actorId : 1L);
        h.setAction(action);
        h.setOldValue(oldVal);
        h.setNewValue(newVal);
        h.setReason(reason);
        histories.save(h);
    }

    private String snapshot(Appointment a) {
        return a.getAppointmentDate() + "|" + a.getPreferredSession()
                + "|doctor=" + (a.getDoctor() != null ? a.getDoctor().getStaffId() : "none")
                + "|status=" + a.getStatus();
    }

    private void requireKey(String value) {
        try {
            UUID.fromString(value);
        } catch (RuntimeException e) {
            throw error(HttpStatus.BAD_REQUEST, "Mã yêu cầu check-in không hợp lệ.");
        }
    }

    private String symptoms(String supplied, String fallback) {
        String value = supplied == null || supplied.isBlank() ? fallback : supplied.trim();
        if (value == null || value.isBlank()) {
            throw error(HttpStatus.BAD_REQUEST, "Vui lòng ghi nhận triệu chứng hoặc lý do đến khám.");
        }
        if (value.length() > 500) {
            throw error(HttpStatus.BAD_REQUEST, "Triệu chứng tối đa 500 ký tự.");
        }
        return value.trim();
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private ResponseStatusException error(HttpStatus status, String reason) {
        return new ResponseStatusException(status, reason);
    }

    private VisitResult result(Visit v) {
        return new VisitResult(v.getVisitId(), v.getVisitCode(), v.getPatientProfile().getFullName(),
                v.getBranch().getBranchName(), v.getVisitType(), v.getStatus(), v.getCheckInAt());
    }

    private PatientRow patientRow(PatientProfile p) {
        return new PatientRow(p.getProfileId(), p.getPatientCode(), p.getFullName(), p.getDob(), p.getPhoneNumber(),
                Boolean.TRUE.equals(p.getHasAllergies()), p.getAllergyDetails(),
                Boolean.TRUE.equals(p.getHasUnderlyingDisease()), p.getUnderlyingDiseaseDetails(), false);
    }

    private PatientRow crossBranchPatientRow(PatientProfile p) {
        return new PatientRow(p.getProfileId(), p.getPatientCode(), p.getFullName(), p.getDob(), p.getPhoneNumber(),
                false, null, false, null, true);
    }

    private boolean canRevertVisit(Visit visit) {
        if (visit == null) return true;
        if (visit.getStatus() != VisitStatus.WAITING) return false;
        if (visit.getRoomId() != null || visit.getChairId() != null) return false;
        if (tickets != null) {
            try {
                List<QueueTicket> visitTickets = tickets.findAllByVisitAndType(visit.getVisitId(), QueueType.EXAM);
                boolean isAlreadyBeingExamined = visitTickets != null && visitTickets.stream()
                        .anyMatch(t -> t.getStatus() == QueueStatus.IN_SERVICE || t.getStatus() == QueueStatus.DONE);
                if (isAlreadyBeingExamined) return false;
            } catch (Exception ignored) {}
        }
        return true;
    }

    private AppointmentRow appointmentRow(Appointment a) {
        return appointmentRow(a, null, Map.of());
    }

    private AppointmentRow appointmentRow(Appointment a, Visit linkedVisit, Map<Long, QueueTicket> ticketMap) {
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        long lateMinutes = 0;
        if (a.getExpectedTime() != null && a.getStatus() != AppointmentStatus.CHECKED_IN && a.getAppointmentDate() != null) {
            if (a.getAppointmentDate().isBefore(today)) {
                lateMinutes = Duration.between(a.getAppointmentDate().atTime(a.getExpectedTime()), LocalDateTime.now(CLINIC_ZONE)).toMinutes();
            } else if (a.getAppointmentDate().isEqual(today)) {
                lateMinutes = Math.max(0, Duration.between(a.getExpectedTime(), LocalTime.now(CLINIC_ZONE)).toMinutes());
            }
        }
        boolean canRevert = false;
        if (a.getStatus() == AppointmentStatus.CHECKED_IN) {
            Visit v = linkedVisit != null ? linkedVisit : visits.findByAppointmentId(a.getAppointmentId()).orElse(null);
            if (v == null) {
                canRevert = true;
            } else {
                canRevert = canRevertVisit(v);
            }
        }
        return new AppointmentRow(a.getAppointmentId(), a.getAppointmentCode(), patientRow(a.getPatientProfile()),
                a.getExpectedTime(), a.getInitialSymptoms(),
                a.getDoctor() == null ? "Chưa chỉ định" : a.getDoctor().getFullName(),
                a.getStatus(), UUID.randomUUID().toString(), lateMinutes, canRevert);
    }

    private VisitRow visitRow(Visit v, Map<Long, QueueTicket> ticketMap) {
        return visitRow(v, ticketMap, Map.of());
    }

    private VisitRow visitRow(Visit v, Map<Long, QueueTicket> ticketMap, Map<Long, Appointment> appointmentById) {
        QueueTicket t = ticketMap != null ? ticketMap.get(v.getVisitId()) : null;
        String doctorName = v.getPrimaryDoctor() != null ? v.getPrimaryDoctor().getFullName()
                : (t != null && t.getVisit() != null && t.getVisit().getPrimaryDoctor() != null ? t.getVisit().getPrimaryDoctor().getFullName() : null);
        boolean isEmergency = Boolean.TRUE.equals(v.getIsEmergency())
                || v.getVisitType() == VisitType.EMERGENCY
                || (t != null && Boolean.TRUE.equals(t.getIsEmergency()));
        boolean isBookedOnTime = Boolean.TRUE.equals(v.getIsBookedAndOnTime());
        boolean isPriority = isEmergency || isBookedOnTime
                || (t != null && Boolean.TRUE.equals(t.getIsPriority()))
                || (v.getNotes() != null && v.getNotes().contains("ƯU TIÊN"));
        boolean isEarlyWaiting = false;
        if (!isEmergency && !isBookedOnTime && v.getVisitType() == VisitType.APPOINTMENT) {
            if (t != null && t.getPriorityReason() != null
                    && t.getPriorityReason().toUpperCase(Locale.ROOT).contains("EARLY_WAITING")) {
                isEarlyWaiting = true;
            } else if (v.getAppointmentId() != null && v.getCheckInAt() != null) {
                Appointment linkedAppt = appointmentById != null ? appointmentById.get(v.getAppointmentId()) : null;
                if (linkedAppt == null && appointments != null) {
                    linkedAppt = appointments.findById(v.getAppointmentId()).orElse(null);
                }
                if (linkedAppt != null && linkedAppt.getExpectedTime() != null && linkedAppt.getAppointmentDate() != null) {
                    LocalDateTime earlyCutoff = LocalDateTime.of(linkedAppt.getAppointmentDate(), linkedAppt.getExpectedTime())
                            .minusMinutes(com.cvdcms.service.AppointmentSlotService.GRACE_PERIOD_MINUTES);
                    isEarlyWaiting = v.getCheckInAt().isBefore(earlyCutoff);
                }
            }
        }
        return new VisitRow(v.getVisitId(), v.getVisitCode(), patientRow(v.getPatientProfile()),
                v.getVisitType(), v.getStatus(), v.getCheckInAt(), v.getInitialSymptoms(),
                t != null ? t.getTicketId() : null,
                t != null ? t.getDisplayNumber() : null,
                doctorName,
                isPriority,
                isEmergency,
                isBookedOnTime,
                isEarlyWaiting);
    }

    private VisitRow visitRow(Visit v) {
        return new VisitRow(v.getVisitId(), v.getVisitCode(), patientRow(v.getPatientProfile()),
                v.getVisitType(), v.getStatus(), v.getCheckInAt(), v.getInitialSymptoms());
    }

    public record VisitResult(Long id, String code, String patientName, String branchName,
                              VisitType type, VisitStatus status, LocalDateTime checkedInAt) { }
    public record PatientRow(Long id, String code, String name, LocalDate dob, String phone,
                             boolean allergies, String allergyDetails, boolean underlyingDisease,
                             String underlyingDiseaseDetails, boolean crossBranch) {
        public PatientRow(Long id, String code, String name, LocalDate dob, String phone,
                          boolean allergies, String allergyDetails, boolean underlyingDisease,
                          String underlyingDiseaseDetails) {
            this(id, code, name, dob, phone, allergies, allergyDetails, underlyingDisease, underlyingDiseaseDetails, false);
        }
    }
    public record AppointmentRow(Long id, String code, PatientRow patient, LocalTime expectedTime,
                                 String symptoms, String doctorName, AppointmentStatus status, String requestKey,
                                 long lateMinutes, boolean canRevert) {
        public AppointmentRow(Long id, String code, PatientRow patient, LocalTime expectedTime,
                              String symptoms, String doctorName, AppointmentStatus status, String requestKey,
                              long lateMinutes) {
            this(id, code, patient, expectedTime, symptoms, doctorName, status, requestKey, lateMinutes, status == AppointmentStatus.CHECKED_IN);
        }
    }
    public record VisitRow(Long id, String code, PatientRow patient, VisitType type,
                           VisitStatus status, LocalDateTime checkedInAt, String symptoms,
                           Long ticketId, String ticketDisplayNumber, String doctorName,
                           boolean priority, boolean emergency, boolean bookedAndOnTime,
                           boolean earlyWaiting) {
        public VisitRow(Long id, String code, PatientRow patient, VisitType type,
                        VisitStatus status, LocalDateTime checkedInAt, String symptoms,
                        Long ticketId, String ticketDisplayNumber, String doctorName,
                        boolean priority, boolean emergency, boolean bookedAndOnTime) {
            this(id, code, patient, type, status, checkedInAt, symptoms, ticketId, ticketDisplayNumber,
                    doctorName, priority, emergency, bookedAndOnTime, false);
        }
        public VisitRow(Long id, String code, PatientRow patient, VisitType type,
                        VisitStatus status, LocalDateTime checkedInAt, String symptoms,
                        Long ticketId, String ticketDisplayNumber, String doctorName,
                        boolean priority, boolean emergency) {
            this(id, code, patient, type, status, checkedInAt, symptoms, ticketId, ticketDisplayNumber,
                    doctorName, priority, emergency, false, false);
        }
        public VisitRow(Long id, String code, PatientRow patient, VisitType type,
                        VisitStatus status, LocalDateTime checkedInAt, String symptoms) {
            this(id, code, patient, type, status, checkedInAt, symptoms, null, null, null, false, false, false, false);
        }
        public int queueTierRank() {
            if (emergency) return 1;
            if (bookedAndOnTime) return 2;
            if (type == VisitType.WALK_IN) return 3;
            return 4;
        }
    }


    public record Dashboard(Long branchId, String branchName, LocalDate day, List<Branch> branches,
                            List<AppointmentRow> appointments, List<VisitRow> visits, List<PatientRow> matches,
                            String query, String walkInKey, String datePreset, String walkInWarning) {
        public Dashboard(Long branchId, String branchName, LocalDate day, List<Branch> branches,
                         List<AppointmentRow> appointments, List<VisitRow> visits, List<PatientRow> matches,
                         String query, String walkInKey, String datePreset) {
            this(branchId, branchName, day, branches, appointments, visits, matches, query, walkInKey, datePreset, null);
        }
        public Dashboard(Long branchId, String branchName, LocalDate day, List<Branch> branches,
                         List<AppointmentRow> appointments, List<VisitRow> visits, List<PatientRow> matches,
                         String query, String walkInKey) {
            this(branchId, branchName, day, branches, appointments, visits, matches, query, walkInKey, "TODAY", null);
        }
    }
}
