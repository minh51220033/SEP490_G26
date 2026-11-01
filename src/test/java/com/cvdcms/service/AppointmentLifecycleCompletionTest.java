package com.cvdcms.service;

import com.cvdcms.entity.Appointment;
import com.cvdcms.entity.AppointmentHistory;
import com.cvdcms.entity.Visit;
import com.cvdcms.entity.enums.AppointmentStatus;
import com.cvdcms.entity.enums.PreferredSession;
import com.cvdcms.repository.AppointmentHistoryRepository;
import com.cvdcms.repository.AppointmentPolicyRepository;
import com.cvdcms.repository.VisitRepository;
import com.cvdcms.service.impl.AppointmentLifecycleServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AppointmentLifecycleCompletionTest {
    @Mock VisitRepository visits;
    @Mock AppointmentPolicyRepository appointments;
    @Mock AppointmentHistoryRepository histories;

    AppointmentLifecycleService lifecycle;

    @BeforeEach
    void setUp() {
        lifecycle = new AppointmentLifecycleServiceImpl(visits, appointments, histories);
    }

    @Test
    void completeByVisitTransitionsCheckedInToCompleted() {
        Visit visit = new Visit();
        visit.setVisitId(50L);
        visit.setVisitCode("V20261102-1234");
        visit.setAppointmentId(10L);

        Appointment appointment = new Appointment();
        appointment.setAppointmentId(10L);
        appointment.setStatus(AppointmentStatus.CHECKED_IN);
        appointment.setAppointmentDate(LocalDate.now());
        appointment.setPreferredSession(PreferredSession.MORNING);

        when(visits.findById(50L)).thenReturn(Optional.of(visit));
        when(appointments.lockById(10L)).thenReturn(Optional.of(appointment));

        lifecycle.completeByVisit(50L, 9L);

        assertEquals(AppointmentStatus.COMPLETED, appointment.getStatus());
        verify(appointments).saveAndFlush(appointment);
        verify(histories).save(any(AppointmentHistory.class));
    }

    @Test
    void completeByVisitIsIdempotentOnRepeatedCalls() {
        Visit visit = new Visit();
        visit.setVisitId(50L);
        visit.setAppointmentId(10L);

        Appointment appointment = new Appointment();
        appointment.setAppointmentId(10L);
        appointment.setStatus(AppointmentStatus.COMPLETED); // Already completed

        when(visits.findById(50L)).thenReturn(Optional.of(visit));
        when(appointments.lockById(10L)).thenReturn(Optional.of(appointment));

        lifecycle.completeByVisit(50L, 9L);

        assertEquals(AppointmentStatus.COMPLETED, appointment.getStatus());
        verify(appointments, never()).saveAndFlush(any());
        verify(histories, never()).save(any());
    }

    @Test
    void completeByVisitIgnoresWalkInAndEmergency() {
        Visit walkInVisit = new Visit();
        walkInVisit.setVisitId(51L);
        walkInVisit.setAppointmentId(null); // Walk-in / Emergency has no appointmentId

        when(visits.findById(51L)).thenReturn(Optional.of(walkInVisit));

        lifecycle.completeByVisit(51L, 9L);

        verify(appointments, never()).findById(any());
        verify(appointments, never()).saveAndFlush(any());
        verify(histories, never()).save(any());
    }
}

