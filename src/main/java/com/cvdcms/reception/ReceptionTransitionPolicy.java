package com.cvdcms.reception;

import com.cvdcms.entity.enums.AppointmentStatus;
import com.cvdcms.entity.enums.QueueStatus;
import com.cvdcms.entity.enums.VisitStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;

/** Central state rules shared by reception and the later dispatch/queue features. */
public final class ReceptionTransitionPolicy {
    private static final Map<AppointmentStatus, Set<AppointmentStatus>> APPOINTMENTS = Map.of(
            AppointmentStatus.PENDING, Set.of(AppointmentStatus.CONFIRMED, AppointmentStatus.CHECKED_IN, AppointmentStatus.CANCELLED, AppointmentStatus.NO_SHOW),
            AppointmentStatus.CONFIRMED, Set.of(AppointmentStatus.CHECKED_IN, AppointmentStatus.CANCELLED, AppointmentStatus.NO_SHOW),
            AppointmentStatus.CHECKED_IN, Set.of(AppointmentStatus.COMPLETED),
            AppointmentStatus.COMPLETED, Set.of(), AppointmentStatus.CANCELLED, Set.of(), AppointmentStatus.NO_SHOW, Set.of());
    private static final Map<QueueStatus, Set<QueueStatus>> QUEUES = Map.of(
            QueueStatus.WAITING, Set.of(QueueStatus.CALLED, QueueStatus.PAUSED, QueueStatus.CANCELLED),
            QueueStatus.CALLED, Set.of(QueueStatus.IN_SERVICE, QueueStatus.PAUSED, QueueStatus.CANCELLED),
            QueueStatus.IN_SERVICE, Set.of(QueueStatus.DONE), QueueStatus.PAUSED, Set.of(QueueStatus.WAITING, QueueStatus.CANCELLED),
            QueueStatus.DONE, Set.of(), QueueStatus.CANCELLED, Set.of());
    private static final Set<VisitStatus> ACTIVE_VISITS = Set.of(VisitStatus.WAITING, VisitStatus.IN_EXAM,
            VisitStatus.WAITING_PARACLINICAL, VisitStatus.WAITING_RESULT, VisitStatus.IN_TREATMENT, VisitStatus.WAITING_PAYMENT);
    private ReceptionTransitionPolicy() { }
    public static Set<VisitStatus> activeVisits() { return ACTIVE_VISITS; }
    public static void appointment(AppointmentStatus before, AppointmentStatus after) {
        if (!APPOINTMENTS.getOrDefault(before, Set.of()).contains(after))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Trạng thái lịch hẹn không cho phép thao tác này.");
    }
    public static void queue(QueueStatus before, QueueStatus after) {
        if (!QUEUES.getOrDefault(before, Set.of()).contains(after))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Trạng thái hàng chờ không cho phép thao tác này.");
    }
}
