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
import org.mockito.ArgumentCaptor;
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
class ReceptionServiceTest {
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
        branch = new Branch(); branch.setBranchId(1L); branch.setBranchName("CN01"); branch.setIsActive(true);
        patient = new PatientProfile(); patient.setProfileId(7L); patient.setFullName("Bệnh nhân A"); patient.setStatus("ACTIVE");
        user = new User(); user.setUserId(9L);
    }

    private void permitted() {
        when(access.require(session)).thenReturn(new ReceptionAccess.Scope(user, false, List.of(1L)));
        when(branches.findById(1L)).thenReturn(Optional.of(branch));
        lenient().when(lockedBranches.findByIdForBooking(1L)).thenReturn(Optional.of(branch));
    }

    private Appointment appointment(AppointmentStatus state, LocalDate date) {
        Appointment a = new Appointment(); a.setAppointmentId(20L); a.setPatientProfile(patient); a.setBranch(branch);
        a.setAppointmentDate(date); a.setStatus(state); a.setInitialSymptoms("Đau răng"); return a;
    }

    @Test
    void confirmedAppointmentCreatesOneWaitingVisitAndAudit() {
        permitted(); Appointment a = appointment(AppointmentStatus.CONFIRMED, today);
        when(appointments.findById(20L)).thenReturn(Optional.of(a)); when(appointments.lockById(20L)).thenReturn(Optional.of(a));
        when(profiles.lockById(7L)).thenReturn(Optional.of(patient)); when(visits.findByAppointmentId(20L)).thenReturn(Optional.empty());
        when(keys.findByRequestKey(anyString())).thenReturn(Optional.empty()); when(visits.activeForProfile(eq(7L), anySet())).thenReturn(List.of());
        when(visits.saveAndFlush(any())).thenAnswer(invocation -> { Visit v = invocation.getArgument(0); v.setVisitId(100L); return v; });
        var result = service.checkInAppointment(session, 20L, "Đau tăng khi nhai", UUID.randomUUID().toString());
        assertEquals(100L, result.id()); assertEquals(VisitStatus.WAITING, result.status()); assertEquals(AppointmentStatus.CHECKED_IN, a.getStatus());
        ArgumentCaptor<Visit> captured = ArgumentCaptor.forClass(Visit.class); verify(visits).saveAndFlush(captured.capture());
        assertEquals(VisitType.APPOINTMENT, captured.getValue().getVisitType()); assertEquals(20L, captured.getValue().getAppointmentId());
        assertEquals("Đau tăng khi nhai", captured.getValue().getInitialSymptoms()); verify(keys).save(any()); verify(audits).save(any());
        verify(histories).save(any());
    }

    @Test
    void pendingAppointmentCannotBeDirectlyCheckedIn() {
        permitted(); Appointment a = appointment(AppointmentStatus.PENDING, today);
        when(appointments.findById(20L)).thenReturn(Optional.of(a));
        when(appointments.lockById(20L)).thenReturn(Optional.of(a));
        when(profiles.lockById(7L)).thenReturn(Optional.of(patient));
        when(visits.findByAppointmentId(20L)).thenReturn(Optional.empty());
        when(keys.findByRequestKey(anyString())).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () ->
                service.checkInAppointment(session, 20L, "Đau răng", UUID.randomUUID().toString()));
        verify(visits, never()).saveAndFlush(any());
        assertEquals(AppointmentStatus.PENDING, a.getStatus());
    }

    @Test
    void confirmAndCheckInAtomicallyTransitionsPendingToConfirmedThenCheckedIn() {
        permitted(); Appointment a = appointment(AppointmentStatus.PENDING, today);
        when(appointments.findById(20L)).thenReturn(Optional.of(a));
        when(appointments.lockById(20L)).thenReturn(Optional.of(a));
        when(profiles.lockById(7L)).thenReturn(Optional.of(patient));
        when(visits.findByAppointmentId(20L)).thenReturn(Optional.empty());
        when(keys.findByRequestKey(anyString())).thenReturn(Optional.empty());
        when(visits.activeForProfile(eq(7L), anySet())).thenReturn(List.of());
        when(visits.saveAndFlush(any())).thenAnswer(invocation -> { Visit v = invocation.getArgument(0); v.setVisitId(105L); return v; });

        var result = service.confirmAndCheckIn(session, 20L, "Khám tổng quát", UUID.randomUUID().toString());

        assertEquals(105L, result.id());
        assertEquals(VisitStatus.WAITING, result.status());
        assertEquals(AppointmentStatus.CHECKED_IN, a.getStatus());
        // Verify 2 history records: one for CONFIRM, one for CHECK_IN
        verify(histories, times(2)).save(any());
        verify(visits).saveAndFlush(any());
        verify(audits).save(any());
    }

    @Test
    void repeatedAppointmentReturnsExistingVisit() {
        permitted(); Appointment a = appointment(AppointmentStatus.CHECKED_IN, today);
        Visit existing = new Visit(); existing.setVisitId(101L); existing.setVisitCode("VEXIST"); existing.setPatientProfile(patient); existing.setBranch(branch); existing.setVisitType(VisitType.APPOINTMENT); existing.setStatus(VisitStatus.WAITING);
        when(appointments.findById(20L)).thenReturn(Optional.of(a)); when(appointments.lockById(20L)).thenReturn(Optional.of(a)); when(profiles.lockById(7L)).thenReturn(Optional.of(patient)); when(visits.findByAppointmentId(20L)).thenReturn(Optional.of(existing));
        assertEquals(101L, service.checkInAppointment(session, 20L, null, UUID.randomUUID().toString()).id());
        verify(visits, never()).saveAndFlush(any()); verify(audits, never()).save(any());
    }

    @Test
    void futureAppointmentCannotBeCheckedIn() {
        permitted(); Appointment a = appointment(AppointmentStatus.CONFIRMED, today.plusDays(1));
        when(appointments.findById(20L)).thenReturn(Optional.of(a)); when(appointments.lockById(20L)).thenReturn(Optional.of(a)); when(profiles.lockById(7L)).thenReturn(Optional.of(patient)); when(visits.findByAppointmentId(20L)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> service.checkInAppointment(session, 20L, null, UUID.randomUUID().toString()));
        verify(visits, never()).saveAndFlush(any());
    }

    @Test
    void activeVisitBlocksAnotherWalkIn() {
        permitted(); when(profiles.lockById(7L)).thenReturn(Optional.of(patient)); when(keys.findByRequestKey(anyString())).thenReturn(Optional.empty());
        Visit active = new Visit(); active.setVisitCode("VACTIVE"); when(visits.activeForProfile(eq(7L), anySet())).thenReturn(List.of(active));
        var ex = assertThrows(ResponseStatusException.class, () -> service.checkInWalkIn(session, 1L, 7L, "Đau răng", false, UUID.randomUUID().toString()));
        assertTrue(ex.getReason().contains("VACTIVE")); verify(visits, never()).saveAndFlush(any());
    }

    @Test
    void inactiveProfileIsRejected() {
        permitted(); patient.setStatus("INACTIVE"); when(profiles.lockById(7L)).thenReturn(Optional.of(patient));
        assertThrows(ResponseStatusException.class, () -> service.checkInWalkIn(session, 1L, 7L, "Đau răng", false, UUID.randomUUID().toString()));
        verify(visits, never()).saveAndFlush(any());
    }

    @Test
    void cancelledAppointmentCannotBeCheckedIn() {
        permitted(); Appointment a = appointment(AppointmentStatus.CANCELLED, today);
        when(appointments.findById(20L)).thenReturn(Optional.of(a)); when(appointments.lockById(20L)).thenReturn(Optional.of(a)); when(profiles.lockById(7L)).thenReturn(Optional.of(patient)); when(visits.findByAppointmentId(20L)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> service.checkInAppointment(session, 20L, null, UUID.randomUUID().toString()));
        verify(visits, never()).saveAndFlush(any());
    }

    @Test
    void repeatedWalkInKeyReturnsExistingVisit() {
        permitted(); when(profiles.lockById(7L)).thenReturn(Optional.of(patient));
        Visit existing = new Visit(); existing.setVisitId(44L); existing.setVisitCode("VWALK"); existing.setPatientProfile(patient); existing.setBranch(branch); existing.setVisitType(VisitType.WALK_IN); existing.setStatus(VisitStatus.WAITING);
        ReceptionCheckInKey key = new ReceptionCheckInKey(); key.setVisit(existing); when(keys.findByRequestKey(anyString())).thenReturn(Optional.of(key));
        assertEquals(44L, service.checkInWalkIn(session, 1L, 7L, "Đau răng", false, UUID.randomUUID().toString()).id());
        verify(visits, never()).saveAndFlush(any());
    }

    @Test
    void emergencyRequiresMeaningfulReason() {
        permitted(); when(profiles.lockById(7L)).thenReturn(Optional.of(patient)); when(keys.findByRequestKey(anyString())).thenReturn(Optional.empty()); when(visits.activeForProfile(eq(7L), anySet())).thenReturn(List.of());
        assertThrows(ResponseStatusException.class, () -> service.checkInWalkIn(session, 1L, 7L, "Đau", true, UUID.randomUUID().toString()));
        verify(visits, never()).saveAndFlush(any());
    }
}
