package com.cvdcms.service;

import com.cvdcms.entity.Appointment;
import com.cvdcms.entity.enums.AppointmentLifecycleAction;
import com.cvdcms.entity.enums.AppointmentStatus;
import com.cvdcms.service.impl.AppointmentLifecycleServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class AppointmentLifecycleServiceTest {
    private final AppointmentLifecycleService lifecycle = new AppointmentLifecycleServiceImpl();

    @Test
    void supportsTheApprovedAppointmentLifecycle() {
        assertTrue(lifecycle.canTransition(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED,
                AppointmentLifecycleAction.CONFIRM));
        assertTrue(lifecycle.canTransition(AppointmentStatus.CONFIRMED, AppointmentStatus.CHECKED_IN,
                AppointmentLifecycleAction.CHECK_IN));
        assertTrue(lifecycle.canTransition(AppointmentStatus.CHECKED_IN, AppointmentStatus.COMPLETED,
                AppointmentLifecycleAction.COMPLETE));
        assertTrue(lifecycle.canTransition(AppointmentStatus.PENDING, AppointmentStatus.CANCELLED,
                AppointmentLifecycleAction.CANCEL));
        assertTrue(lifecycle.canTransition(AppointmentStatus.CONFIRMED, AppointmentStatus.NO_SHOW,
                AppointmentLifecycleAction.MARK_NO_SHOW));
    }

    @Test
    void confirmedRescheduleReturnsToPendingButDirectReverseTransitionIsRejected() {
        Appointment appointment = appointment(AppointmentStatus.CONFIRMED);
        lifecycle.transition(appointment, AppointmentStatus.PENDING,
                AppointmentLifecycleAction.RESCHEDULE);
        assertEquals(AppointmentStatus.PENDING, appointment.getStatus());

        Appointment direct = appointment(AppointmentStatus.CONFIRMED);
        assertThrows(ResponseStatusException.class, () -> lifecycle.transition(direct,
                AppointmentStatus.PENDING, AppointmentLifecycleAction.CONFIRM));
    }

    @Test
    void receptionistConfirmedRescheduleCanStayConfirmed() {
        Appointment appointment = appointment(AppointmentStatus.CONFIRMED);
        lifecycle.transition(appointment, AppointmentStatus.CONFIRMED,
                AppointmentLifecycleAction.RESCHEDULE);
        assertEquals(AppointmentStatus.CONFIRMED, appointment.getStatus());
    }

    @Test
    void pendingCannotSkipConfirmationOrBecomeNoShow() {
        Appointment pending = appointment(AppointmentStatus.PENDING);
        assertThrows(ResponseStatusException.class, () -> lifecycle.transition(pending,
                AppointmentStatus.CHECKED_IN, AppointmentLifecycleAction.CHECK_IN));
        assertThrows(ResponseStatusException.class, () -> lifecycle.transition(pending,
                AppointmentStatus.NO_SHOW, AppointmentLifecycleAction.MARK_NO_SHOW));
        assertEquals(AppointmentStatus.PENDING, pending.getStatus());
    }

    @Test
    void terminalStatesCannotTransition() {
        for (AppointmentStatus terminal : new AppointmentStatus[]{AppointmentStatus.COMPLETED,
                AppointmentStatus.CANCELLED, AppointmentStatus.NO_SHOW}) {
            Appointment appointment = appointment(terminal);
            assertThrows(ResponseStatusException.class, () -> lifecycle.transition(appointment,
                    AppointmentStatus.CONFIRMED, AppointmentLifecycleAction.CONFIRM));
        }
    }

    private Appointment appointment(AppointmentStatus status) {
        Appointment appointment = new Appointment();
        appointment.setStatus(status);
        return appointment;
    }
}
