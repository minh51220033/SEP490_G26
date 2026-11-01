package com.cvdcms.service.impl;

import com.cvdcms.entity.Appointment;
import com.cvdcms.entity.AppointmentHistory;
import com.cvdcms.entity.Visit;
import com.cvdcms.entity.enums.AppointmentLifecycleAction;
import com.cvdcms.entity.enums.AppointmentStatus;
import com.cvdcms.repository.AppointmentHistoryRepository;
import com.cvdcms.repository.AppointmentPolicyRepository;
import com.cvdcms.repository.VisitRepository;
import com.cvdcms.service.AppointmentLifecycleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

@Service
public class AppointmentLifecycleServiceImpl implements AppointmentLifecycleService {
    private static final Map<AppointmentLifecycleAction, Set<StatusChange>> ALLOWED = allowedTransitions();

    private final VisitRepository visits;
    private final AppointmentPolicyRepository appointments;
    private final AppointmentHistoryRepository histories;

    @Autowired
    public AppointmentLifecycleServiceImpl(VisitRepository visits,
                                           AppointmentPolicyRepository appointments,
                                           AppointmentHistoryRepository histories) {
        this.visits = visits;
        this.appointments = appointments;
        this.histories = histories;
    }

    public AppointmentLifecycleServiceImpl() {
        this(null, null, null);
    }

    @Override
    public boolean canTransition(AppointmentStatus current, AppointmentStatus target,
                                 AppointmentLifecycleAction action) {
        return current != null && target != null && action != null
                && ALLOWED.getOrDefault(action, Set.of()).contains(new StatusChange(current, target));
    }

    @Override
    public void transition(Appointment appointment, AppointmentStatus target,
                           AppointmentLifecycleAction action) {
        if (appointment == null) {
            throw new IllegalArgumentException("Appointment is required.");
        }
        if (!canTransition(appointment.getStatus(), target, action)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Trạng thái lịch hẹn không cho phép thao tác này.");
        }
        appointment.setStatus(target);
    }

    @Override
    @Transactional
    public void completeByVisit(Long visitId, Long actorUserId) {
        if (visitId == null || visits == null || appointments == null) return;
        Visit visit = visits.findById(visitId).orElse(null);
        if (visit == null || visit.getAppointmentId() == null) {
            // Walk-in / Emergency or unlinked visit: does not modify any appointment
            return;
        }

        Appointment appointment = appointments.lockById(visit.getAppointmentId()).orElse(null);
        if (appointment == null) {
            return;
        }

        // Idempotency: if already completed, do nothing
        if (appointment.getStatus() == AppointmentStatus.COMPLETED) {
            return;
        }

        if (appointment.getStatus() == AppointmentStatus.CHECKED_IN) {
            if (actorUserId == null) {
                throw new IllegalArgumentException("Actor user is required to complete an appointment.");
            }
            String oldSnapshot = snapshot(appointment);
            transition(appointment, AppointmentStatus.COMPLETED, AppointmentLifecycleAction.COMPLETE);
            appointments.saveAndFlush(appointment);

            if (histories != null) {
                AppointmentHistory history = new AppointmentHistory();
                history.setAppointment(appointment);
                history.setActorUserId(actorUserId);
                history.setAction("COMPLETE");
                history.setOldValue(oldSnapshot);
                history.setNewValue(snapshot(appointment));
                history.setReason("Hoàn tất lịch hẹn theo lượt khám " + visit.getVisitCode());
                histories.save(history);
            }
        }
    }

    private String snapshot(Appointment a) {
        return a.getAppointmentDate() + "|" + a.getPreferredSession()
                + "|doctor=" + (a.getDoctor() != null ? a.getDoctor().getStaffId() : "none")
                + "|status=" + a.getStatus();
    }

    private static Map<AppointmentLifecycleAction, Set<StatusChange>> allowedTransitions() {
        Map<AppointmentLifecycleAction, Set<StatusChange>> result =
                new EnumMap<>(AppointmentLifecycleAction.class);
        result.put(AppointmentLifecycleAction.CONFIRM,
                Set.of(change(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED)));
        result.put(AppointmentLifecycleAction.RESCHEDULE, Set.of(
                change(AppointmentStatus.PENDING, AppointmentStatus.PENDING),
                change(AppointmentStatus.CONFIRMED, AppointmentStatus.PENDING),
                change(AppointmentStatus.CONFIRMED, AppointmentStatus.CONFIRMED)));
        result.put(AppointmentLifecycleAction.CHECK_IN,
                Set.of(change(AppointmentStatus.CONFIRMED, AppointmentStatus.CHECKED_IN)));
        result.put(AppointmentLifecycleAction.COMPLETE,
                Set.of(change(AppointmentStatus.CHECKED_IN, AppointmentStatus.COMPLETED)));
        result.put(AppointmentLifecycleAction.CANCEL, Set.of(
                change(AppointmentStatus.PENDING, AppointmentStatus.CANCELLED),
                change(AppointmentStatus.CONFIRMED, AppointmentStatus.CANCELLED)));
        result.put(AppointmentLifecycleAction.MARK_NO_SHOW,
                Set.of(change(AppointmentStatus.CONFIRMED, AppointmentStatus.NO_SHOW)));
        return Map.copyOf(result);
    }

    private static StatusChange change(AppointmentStatus current, AppointmentStatus target) {
        return new StatusChange(current, target);
    }

    private record StatusChange(AppointmentStatus current, AppointmentStatus target) { }
}
