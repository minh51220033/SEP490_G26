package com.cvdcms.service;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.impl.AppointmentConflictDetectionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AppointmentConflictDetectionServiceTest {
    @Mock AppointmentPolicyRepository appointments;
    @Mock AppointmentSessionWindowRepository windows;
    @Mock AppointmentSessionPolicyRepository policies;
    @Mock StaffBranchAssignmentRepository assignments;
    @Mock StaffScheduleRepository schedules;
    @Mock LeaveRequestRepository leaves;

    AppointmentConflictDetectionService service;
    Branch branch;
    PatientProfile patient;
    StaffProfile doctor;
    LocalDate date;

    @BeforeEach
    void setUp() {
        service = new AppointmentConflictDetectionServiceImpl(appointments, windows, policies,
                assignments, schedules, leaves, 24, 10);
        date = LocalDate.now(AppointmentPolicyService.CLINIC_ZONE).plusDays(7);

        branch = new Branch();
        branch.setBranchId(1L);
        branch.setIsActive(true);
        branch.setWorkingDays("2,3,4,5,6,7,CN");
        branch.setOpeningTime(LocalTime.of(8, 0));
        branch.setClosingTime(LocalTime.of(20, 0));

        patient = new PatientProfile();
        patient.setProfileId(10L);
        patient.setStatus("ACTIVE");

        doctor = new StaffProfile();
        doctor.setStaffId(20L);
        doctor.setFullName("Bác sĩ A");
        doctor.setStatus(StaffStatus.ACTIVE);

        Role role = new Role();
        role.setRoleName("BAC_SI");
        role.setIsActive(true);

        StaffBranchAssignment assignment = new StaffBranchAssignment();
        assignment.setStaff(doctor);
        assignment.setBranch(branch);
        assignment.setRole(role);
        assignment.setStartDate(date.minusYears(1));
        assignment.setStatus(AssignmentStatus.ACTIVE);

        WorkShift shift = new WorkShift();
        shift.setShiftId(30L);
        shift.setIsActive(true);
        shift.setStartTime(LocalTime.of(8, 0));
        shift.setEndTime(LocalTime.NOON);

        StaffSchedule schedule = new StaffSchedule();
        schedule.setStaff(doctor);
        schedule.setBranch(branch);
        schedule.setShift(shift);
        schedule.setWorkDate(date);
        schedule.setStatus(ScheduleStatus.SCHEDULED);

        when(windows.findByBranch_BranchIdAndPreferredSession(anyLong(), any()))
                .thenReturn(Optional.empty());
        when(policies.findByBranch_BranchIdAndAppointmentDateAndPreferredSession(anyLong(), any(), any()))
                .thenReturn(Optional.empty());
        when(assignments.findByBranchAndStatus(1L, AssignmentStatus.ACTIVE)).thenReturn(List.of(assignment));
        when(schedules.findForBooking(1L, date, ScheduleStatus.SCHEDULED)).thenReturn(List.of(schedule));
        when(leaves.findApprovedOn(1L, date, LeaveStatus.APPROVED)).thenReturn(List.of());
        when(appointments.findOccupyingForPatientDay(anyLong(), anyLong(), any(), anyList())).thenReturn(List.of());
        when(appointments.findOccupyingForSession(anyLong(), any(), any(), anyList())).thenReturn(List.of());
    }

    private Appointment createAppointment(String code) {
        Appointment a = new Appointment();
        a.setAppointmentId(100L);
        a.setAppointmentCode(code);
        a.setBranch(branch);
        a.setPatientProfile(patient);
        a.setDoctor(doctor);
        a.setAppointmentDate(date);
        a.setPreferredSession(PreferredSession.MORNING);
        a.setStatus(AppointmentStatus.CONFIRMED);
        return a;
    }

    @Test
    void cleanAppointmentHasNoConflicts() {
        Appointment a = createAppointment("TK261102-1234");
        List<AppointmentConflictDetectionService.Conflict> conflicts = service.detectConflicts(a);
        assertTrue(conflicts.isEmpty(), "Should have no conflicts for valid appointment");
    }

    @Test
    void detectsDoctorNoScheduleConflictForFT18Appointment() {
        Appointment a = createAppointment("TK261102-1234");
        // No schedule found on that day for doctor
        when(schedules.findForBooking(1L, date, ScheduleStatus.SCHEDULED)).thenReturn(List.of());

        List<AppointmentConflictDetectionService.Conflict> conflicts = service.detectConflicts(a);
        assertFalse(conflicts.isEmpty());
        assertTrue(conflicts.stream().anyMatch(c -> c.code().equals("DOCTOR_NO_SCHEDULE")));
    }

    @Test
    void detectsDoctorOnLeaveConflict() {
        Appointment a = createAppointment("TK261102-1234");
        LeaveRequest leave = new LeaveRequest();
        leave.setStaff(doctor);
        leave.setStatus(LeaveStatus.APPROVED);
        when(leaves.findApprovedOn(1L, date, LeaveStatus.APPROVED)).thenReturn(List.of(leave));

        List<AppointmentConflictDetectionService.Conflict> conflicts = service.detectConflicts(a);
        assertFalse(conflicts.isEmpty());
        assertTrue(conflicts.stream().anyMatch(c -> c.code().equals("DOCTOR_ON_LEAVE")));
    }

    @Test
    void detectsBranchClosedDayConflict() {
        Appointment a = createAppointment("TK261102-1234");
        branch.setWorkingDays("2,3,4,5,6"); // Closed on weekend
        // Set date to Sunday
        LocalDate sunday = date.plusDays(7 - date.getDayOfWeek().getValue());
        a.setAppointmentDate(sunday);

        List<AppointmentConflictDetectionService.Conflict> conflicts = service.detectConflicts(a);
        assertFalse(conflicts.isEmpty());
        assertTrue(conflicts.stream().anyMatch(c -> c.code().equals("BRANCH_CLOSED_DAY")));
    }

    @Test
    void detectsDuplicatePatientAppointmentConflict() {
        Appointment a = createAppointment("TK261102-1234");
        Appointment duplicate = new Appointment();
        duplicate.setAppointmentId(999L);
        duplicate.setAppointmentCode("LH20261102-9999");
        when(appointments.findOccupyingForPatientDay(eq(10L), eq(1L), eq(date), anyList()))
                .thenReturn(List.of(duplicate));

        List<AppointmentConflictDetectionService.Conflict> conflicts = service.detectConflicts(a);
        assertFalse(conflicts.isEmpty());
        assertTrue(conflicts.stream().anyMatch(c -> c.code().equals("DUPLICATE_PATIENT_APPOINTMENT")));
    }

    @Test
    void detectsBranchCapacityFullConflict() {
        Appointment a = createAppointment("TK261102-1234");
        // Create 24 appointments in this session
        List<Appointment> bookedList = new java.util.ArrayList<>();
        for (long i = 1; i <= 24; i++) {
            Appointment b = new Appointment();
            b.setAppointmentId(i);
            bookedList.add(b);
        }
        when(appointments.findOccupyingForSession(eq(1L), eq(date), eq(PreferredSession.MORNING), anyList()))
                .thenReturn(bookedList);

        List<AppointmentConflictDetectionService.Conflict> conflicts = service.detectConflicts(a);
        assertFalse(conflicts.isEmpty());
        assertTrue(conflicts.stream().anyMatch(c -> c.code().equals("CAPACITY_FULL")));
    }
}

