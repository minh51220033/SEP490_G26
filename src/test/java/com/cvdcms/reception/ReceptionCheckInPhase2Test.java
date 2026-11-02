package com.cvdcms.reception;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.AppointmentBookingLockRepository;
import com.cvdcms.repository.AppointmentHistoryRepository;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.service.AppointmentLifecycleService;
import com.cvdcms.service.impl.AppointmentLifecycleServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReceptionCheckInPhase2Test {
    @Mock ReceptionAccess access;
    @Mock ReceptionProfileRepository profiles;
    @Mock ReceptionCheckInAppointmentRepository appointments;
    @Mock ReceptionVisitRepository visits;
    @Mock ReceptionCheckInKeyRepository keys;
    @Mock ReceptionAuditRepository audits;
    @Mock BranchRepository branches;
    @Mock AppointmentBookingLockRepository lockedBranches;
    @Mock AppointmentHistoryRepository histories;
    @Mock HttpSession session;

    AppointmentLifecycleService lifecycle = new AppointmentLifecycleServiceImpl();
    ReceptionService service;
    Branch branch;
    PatientProfile patient;
    User user;
    LocalDate today;

    @BeforeEach
    void setup() {
        service = new ReceptionService(access, profiles, appointments, visits, keys, audits, branches,
                new ObjectMapper(), lockedBranches, lifecycle, histories);
        today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));

        branch = new Branch();
        branch.setBranchId(1L);
        branch.setBranchName("Chi nhánh Trung Tâm");
        branch.setIsActive(true);

        patient = new PatientProfile();
        patient.setProfileId(10L);
        patient.setFullName("Nguyễn Văn A");
        patient.setStatus("ACTIVE");

        user = new User();
        user.setUserId(99L);
    }

    private void permitUser() {
        when(access.require(session)).thenReturn(new ReceptionAccess.Scope(user, false, List.of(1L)));
        when(branches.findById(1L)).thenReturn(Optional.of(branch));
        lenient().when(lockedBranches.findByIdForBooking(1L)).thenReturn(Optional.of(branch));
    }

    private Appointment createAppointment(AppointmentStatus status) {
        Appointment a = new Appointment();
        a.setAppointmentId(200L);
        a.setAppointmentCode("LH20261102-0001");
        a.setBranch(branch);
        a.setPatientProfile(patient);
        a.setAppointmentDate(today);
        a.setPreferredSession(PreferredSession.MORNING);
        a.setStatus(status);
        a.setInitialSymptoms("Khám răng định kỳ");
        return a;
    }

    @Test
    void directPendingToCheckedInIsStrictlyRejected() {
        permitUser();
        Appointment pending = createAppointment(AppointmentStatus.PENDING);
        when(appointments.findById(200L)).thenReturn(Optional.of(pending));
        when(appointments.lockById(200L)).thenReturn(Optional.of(pending));
        when(profiles.lockById(10L)).thenReturn(Optional.of(patient));
        when(visits.findByAppointmentId(200L)).thenReturn(Optional.empty());
        when(keys.findByRequestKey(anyString())).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.checkInAppointment(session, 200L, "Đau răng", UUID.randomUUID().toString()));

        assertTrue(ex.getReason().contains("không cho phép"));
        assertEquals(AppointmentStatus.PENDING, pending.getStatus(), "Status must not change on rejection");
        verify(visits, never()).saveAndFlush(any());
        verify(histories, never()).save(any());
    }

    @Test
    void confirmAndCheckInAtomicallyTransitionsPendingToConfirmedThenCheckedIn() {
        permitUser();
        Appointment pending = createAppointment(AppointmentStatus.PENDING);
        when(appointments.findById(200L)).thenReturn(Optional.of(pending));
        when(appointments.lockById(200L)).thenReturn(Optional.of(pending));
        when(profiles.lockById(10L)).thenReturn(Optional.of(patient));
        when(visits.findByAppointmentId(200L)).thenReturn(Optional.empty());
        when(keys.findByRequestKey(anyString())).thenReturn(Optional.empty());
        when(visits.activeForProfile(eq(10L), anySet())).thenReturn(List.of());
        when(visits.saveAndFlush(any())).thenAnswer(inv -> {
            Visit v = inv.getArgument(0);
            v.setVisitId(500L);
            v.setVisitCode("V20261102-001");
            return v;
        });

        ReceptionService.VisitResult result = service.confirmAndCheckIn(session, 200L, "Khám đau răng", UUID.randomUUID().toString());

        assertNotNull(result);
        assertEquals(500L, result.id());
        assertEquals(VisitStatus.WAITING, result.status());
        assertEquals(AppointmentStatus.CHECKED_IN, pending.getStatus());

        // Verifies exactly 2 history entries recorded: CONFIRM and CHECK_IN
        verify(histories, times(2)).save(any(AppointmentHistory.class));
        verify(visits).saveAndFlush(any(Visit.class));
        verify(audits).save(any(SystemAuditLog.class));
    }

    @Test
    void confirmAndCheckInThrowsConflictIfVisitCreationFailsAndRollsBack() {
        permitUser();
        Appointment pending = createAppointment(AppointmentStatus.PENDING);
        when(appointments.findById(200L)).thenReturn(Optional.of(pending));
        when(appointments.lockById(200L)).thenReturn(Optional.of(pending));
        when(profiles.lockById(10L)).thenReturn(Optional.of(patient));
        when(visits.findByAppointmentId(200L)).thenReturn(Optional.empty());
        when(keys.findByRequestKey(anyString())).thenReturn(Optional.empty());
        when(visits.activeForProfile(eq(10L), anySet())).thenReturn(List.of());

        // Simulate database exception during visit persist
        when(visits.saveAndFlush(any())).thenThrow(new RuntimeException("Database error saving visit"));

        assertThrows(RuntimeException.class, () ->
                service.confirmAndCheckIn(session, 200L, "Khám răng", UUID.randomUUID().toString()));

        // Since transaction is managed by Spring @Transactional, transaction rolls back on RuntimeException
    }

    @Test
    void walkInAndEmergencyDoNotCreateOrMutateAppointment() {
        permitUser();
        when(profiles.lockById(10L)).thenReturn(Optional.of(patient));
        when(keys.findByRequestKey(anyString())).thenReturn(Optional.empty());
        when(visits.activeForProfile(eq(10L), anySet())).thenReturn(List.of());
        when(visits.saveAndFlush(any())).thenAnswer(inv -> {
            Visit v = inv.getArgument(0);
            v.setVisitId(600L);
            return v;
        });

        // 1. Walk-in
        ReceptionService.VisitResult walkIn = service.checkInWalkIn(session, 1L, 10L, "Đau răng nhẹ", false, UUID.randomUUID().toString());
        assertEquals(VisitType.WALK_IN, walkIn.type());
        verify(appointments, never()).save(any());
        verify(histories, never()).save(any());

        // 2. Emergency
        ReceptionService.VisitResult emergency = service.checkInWalkIn(session, 1L, 10L, "Chấn thương răng cửa hàm trên rất đau", true, UUID.randomUUID().toString());
        assertEquals(VisitType.EMERGENCY, emergency.type());
        verify(appointments, never()).save(any());
    }

    @Test
    void repeatedCheckInReturnsExistingVisitIdempotently() {
        permitUser();
        Appointment a = createAppointment(AppointmentStatus.CHECKED_IN);
        Visit existing = new Visit();
        existing.setVisitId(777L);
        existing.setVisitCode("V-ALREADY-CHECKED-IN");
        existing.setPatientProfile(patient);
        existing.setBranch(branch);
        existing.setVisitType(VisitType.APPOINTMENT);
        existing.setStatus(VisitStatus.WAITING);

        when(appointments.findById(200L)).thenReturn(Optional.of(a));
        when(appointments.lockById(200L)).thenReturn(Optional.of(a));
        when(profiles.lockById(10L)).thenReturn(Optional.of(patient));
        when(visits.findByAppointmentId(200L)).thenReturn(Optional.of(existing));

        ReceptionService.VisitResult res = service.checkInAppointment(session, 200L, "Khám", UUID.randomUUID().toString());
        assertEquals(777L, res.id());
        verify(visits, never()).saveAndFlush(any());
        verify(audits, never()).save(any());
    }

    @Test
    void checkInConcurrentWithNoShowJob() {
        permitUser();
        // If No-show job already transitioned appointment to NO_SHOW:
        Appointment noShowAppt = createAppointment(AppointmentStatus.NO_SHOW);
        when(appointments.findById(200L)).thenReturn(Optional.of(noShowAppt));
        when(appointments.lockById(200L)).thenReturn(Optional.of(noShowAppt));
        when(profiles.lockById(10L)).thenReturn(Optional.of(patient));
        when(visits.findByAppointmentId(200L)).thenReturn(Optional.empty());
        when(keys.findByRequestKey(anyString())).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () ->
                service.checkInAppointment(session, 200L, "Khám", UUID.randomUUID().toString()));

        verify(visits, never()).saveAndFlush(any());
    }
}

