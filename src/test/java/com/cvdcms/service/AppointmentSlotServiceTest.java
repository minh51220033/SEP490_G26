package com.cvdcms.service;

import com.cvdcms.entity.Appointment;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.enums.AppointmentActorType;
import com.cvdcms.entity.enums.PreferredSession;
import com.cvdcms.repository.AppointmentPolicyRepository;
import com.cvdcms.repository.AppointmentSessionWindowRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AppointmentSlotServiceTest {
    private final AppointmentPolicyRepository appointments = mock(AppointmentPolicyRepository.class);
    private final AppointmentSessionWindowRepository windows = mock(AppointmentSessionWindowRepository.class);
    private final AppointmentPolicyService policy = mock(AppointmentPolicyService.class);
    private final AppointmentSlotService slots = new AppointmentSlotService(appointments, windows, policy);
    private final LocalDate date = LocalDate.now(AppointmentPolicyService.CLINIC_ZONE).plusDays(2);

    private Branch branch() {
        Branch branch = new Branch(); branch.setBranchId(4L);
        when(windows.findByBranch_BranchIdAndPreferredSession(eq(4L), any()))
                .thenReturn(Optional.empty());
        when(policy.availability(eq(branch), eq(date), eq(PreferredSession.MORNING), nullable(Long.class),
                eq(AppointmentActorType.RECEPTIONIST))).thenReturn(new AppointmentPolicyService.Availability(
                true, null, 10, List.of(new AppointmentPolicyService.DoctorOption(7L, "A", null, 5))));
        return branch;
    }

    @Test
    void overlappingLongAppointmentBlocksSlotAndLunchBoundary() {
        Branch branch = branch();
        Appointment booked = new Appointment(); booked.setExpectedTime(LocalTime.of(8, 30));
        booked.setDurationMinutes(60);
        when(appointments.findOccupyingForSession(eq(4L), eq(date), eq(PreferredSession.MORNING), anyList()))
                .thenReturn(List.of(booked));
        assertThrows(ResponseStatusException.class, () -> slots.resolve(branch, date,
                PreferredSession.MORNING, null, 30, null, LocalTime.of(9, 15), AppointmentActorType.RECEPTIONIST));
        assertThrows(ResponseStatusException.class, () -> slots.resolve(branch, date,
                PreferredSession.MORNING, null, 60, null, LocalTime.of(11, 30), AppointmentActorType.RECEPTIONIST));
        assertEquals(LocalTime.of(9, 30), slots.resolve(branch, date,
                PreferredSession.MORNING, null, 30, null, LocalTime.of(9, 30), AppointmentActorType.RECEPTIONIST));
    }

    @Test
    void requestedDoctorCannotHaveTwoOverlappingAppointments() {
        Branch branch = branch();
        StaffProfile doctor = new StaffProfile(); doctor.setStaffId(7L);
        Appointment booked = new Appointment(); booked.setExpectedTime(LocalTime.of(8, 0));
        booked.setDurationMinutes(45); booked.setDoctor(doctor);
        when(appointments.findOccupyingForSession(eq(4L), eq(date), eq(PreferredSession.MORNING), anyList()))
                .thenReturn(List.of(booked));
        assertThrows(ResponseStatusException.class, () -> slots.resolve(branch, date,
                PreferredSession.MORNING, 7L, 30, null, LocalTime.of(8, 15), AppointmentActorType.RECEPTIONIST));
    }

    @Test
    void resolveFlexiblyRejectsDoctorConflictAndAllowsNullExpectedTime() {
        Branch branch = branch();
        StaffProfile doctor = new StaffProfile(); doctor.setStaffId(7L);
        Appointment booked = new Appointment(); booked.setExpectedTime(LocalTime.of(8, 0));
        booked.setDurationMinutes(45); booked.setDoctor(doctor);
        when(appointments.findOccupyingForSession(eq(4L), eq(date), eq(PreferredSession.MORNING), anyList()))
                .thenReturn(List.of(booked));

        // 1. null expectedTime is allowed for walk-in/flexible shift arrivals
        assertNull(slots.resolveFlexibly(branch, date, PreferredSession.MORNING, 7L, 30, null, null, AppointmentActorType.RECEPTIONIST));

        // 2. Overlapping time with doctor is blocked
        assertThrows(ResponseStatusException.class, () -> slots.resolveFlexibly(branch, date,
                PreferredSession.MORNING, 7L, 30, null, LocalTime.of(8, 15), AppointmentActorType.RECEPTIONIST));

        // 3. Non-overlapping time with doctor is accepted
        assertEquals(LocalTime.of(9, 0), slots.resolveFlexibly(branch, date,
                PreferredSession.MORNING, 7L, 30, null, LocalTime.of(9, 0), AppointmentActorType.RECEPTIONIST));
    }
}
