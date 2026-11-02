package com.cvdcms.service;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.impl.AppointmentPolicyServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AppointmentPolicyServiceTest {
    @Mock AppointmentPolicyRepository appointments;
    @Mock AppointmentSessionWindowRepository windows;
    @Mock AppointmentSessionPolicyRepository policies;
    @Mock StaffBranchAssignmentRepository assignments;
    @Mock StaffScheduleRepository schedules;
    @Mock LeaveRequestRepository leaves;

    AppointmentPolicyService policy;
    Branch branch;
    PatientProfile patient;
    StaffProfile doctor;
    LocalDate date;

    @BeforeEach
    void setUp() {
        policy = new AppointmentPolicyServiceImpl(appointments, windows, policies, assignments,
                schedules, leaves, 2, 1);
        date = LocalDate.now(AppointmentPolicyService.CLINIC_ZONE).plusDays(7);
        branch = new Branch();
        branch.setBranchId(1L); branch.setIsActive(true); branch.setWorkingDays("2,3,4,5,6,7,CN");
        branch.setOpeningTime(LocalTime.of(8, 0)); branch.setClosingTime(LocalTime.of(20, 0));
        patient = new PatientProfile(); patient.setProfileId(10L); patient.setStatus("ACTIVE");
        doctor = new StaffProfile(); doctor.setStaffId(20L); doctor.setFullName("Bác sĩ A");
        doctor.setSpecialty("Răng hàm mặt"); doctor.setStatus(StaffStatus.ACTIVE);

        Role role = new Role(); role.setRoleName("BAC_SI"); role.setIsActive(true);
        StaffBranchAssignment assignment = new StaffBranchAssignment();
        assignment.setStaff(doctor); assignment.setBranch(branch); assignment.setRole(role);
        assignment.setStartDate(date.minusYears(1)); assignment.setStatus(AssignmentStatus.ACTIVE);
        WorkShift shift = new WorkShift(); shift.setShiftId(30L); shift.setIsActive(true);
        shift.setStartTime(LocalTime.of(8, 0)); shift.setEndTime(LocalTime.NOON);
        StaffSchedule schedule = new StaffSchedule(); schedule.setStaff(doctor); schedule.setBranch(branch);
        schedule.setShift(shift); schedule.setWorkDate(date); schedule.setStatus(ScheduleStatus.SCHEDULED);

        when(windows.findByBranch_BranchIdAndPreferredSession(anyLong(), any()))
                .thenReturn(Optional.empty());
        when(policies.findByBranch_BranchIdAndAppointmentDateAndPreferredSession(anyLong(), any(), any()))
                .thenReturn(Optional.empty());
        when(assignments.findByBranchAndStatus(1L, AssignmentStatus.ACTIVE)).thenReturn(List.of(assignment));
        when(schedules.findForBooking(1L, date, ScheduleStatus.SCHEDULED)).thenReturn(List.of(schedule));
        when(leaves.findApprovedOn(1L, date, LeaveStatus.APPROVED)).thenReturn(List.of());
        when(appointments.findOccupyingForPatientDay(anyLong(), anyLong(), any(), anyList()))
                .thenReturn(List.of());
    }

    @Test
    void oneDoctorWithOneBookingMakesTheSessionFull() {
        Appointment existing = appointment(100L, doctor);
        when(appointments.findOccupyingForSession(eq(1L), eq(date),
                eq(PreferredSession.MORNING), anyList()))
                .thenReturn(List.of(existing));

        AppointmentPolicyService.Availability result = policy.availability(branch, date,
                PreferredSession.MORNING, null, AppointmentActorType.PATIENT);

        assertFalse(result.available());
        assertEquals(0, result.remainingBranch());
        assertEquals("Buổi khám đã đầy.", result.reason());
        assertTrue(result.doctors().isEmpty());
    }

    @Test
    void duplicatePatientDayIsRejectedEvenWhenTheSessionHasCapacity() {
        Appointment duplicate = appointment(101L, null);
        when(appointments.findOccupyingForPatientDay(eq(10L), eq(1L), eq(date), anyList()))
                .thenReturn(List.of(duplicate));
        when(appointments.findOccupyingForSession(eq(1L), eq(date),
                eq(PreferredSession.MORNING), anyList()))
                .thenReturn(List.of());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> policy.ensureBookable(branch, patient, date, PreferredSession.MORNING,
                        null, null, AppointmentActorType.PATIENT));
        assertTrue(exception.getReason().contains("đã có lịch"));
    }

    @Test
    void excludedAppointmentDoesNotConsumeCapacityOrCountAsDuplicate() {
        Appointment current = appointment(102L, doctor);
        current.setPatientProfile(patient);
        when(appointments.findOccupyingForPatientDay(eq(10L), eq(1L), eq(date), anyList()))
                .thenReturn(List.of(current));
        when(appointments.findOccupyingForSession(eq(1L), eq(date),
                eq(PreferredSession.MORNING), anyList()))
                .thenReturn(List.of(current));

        assertDoesNotThrow(() -> policy.ensureBookable(branch, patient, date,
                PreferredSession.MORNING, 20L, 102L, AppointmentActorType.PATIENT));
    }

    @Test
    void unavailableDoctorIsRejected() {
        when(appointments.findOccupyingForSession(eq(1L), eq(date),
                eq(PreferredSession.MORNING), anyList()))
                .thenReturn(List.of());
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> policy.ensureBookable(branch, patient, date, PreferredSession.MORNING,
                        999L, null, AppointmentActorType.RECEPTIONIST));
        assertTrue(exception.getReason().contains("không khả dụng"));
    }

    @Test
    void inactiveBranchIsRejectedBeforeCapacityQueries() {
        branch.setIsActive(false);
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> policy.ensureBookable(branch, patient, date, PreferredSession.MORNING,
                        null, null, AppointmentActorType.PATIENT));
        assertEquals("Chi nhánh không hoạt động.", exception.getReason());
    }

    private Appointment appointment(Long id, StaffProfile assignedDoctor) {
        Appointment appointment = new Appointment();
        appointment.setAppointmentId(id); appointment.setPatientProfile(patient); appointment.setBranch(branch);
        appointment.setAppointmentDate(date); appointment.setPreferredSession(PreferredSession.MORNING);
        appointment.setDoctor(assignedDoctor); appointment.setStatus(AppointmentStatus.CONFIRMED);
        return appointment;
    }
}
