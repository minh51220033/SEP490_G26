package com.cvdcms.service;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.event.CustomerCareDomainEvents.*;
import com.cvdcms.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerCareAutomationServiceTest {

    @Mock
    private CustomerCareTaskRepository taskRepository;

    @Mock
    private VisitRepository visitRepository;

    @Mock
    private PrescriptionRepository prescriptionRepository;

    @Mock
    private AppointmentRepository appointmentRepository;

    @Mock
    private BranchRepository branchRepository;

    @Mock
    private StaffProfileRepository staffRepository;

    @Mock
    private CustomerCareNotificationService notificationService;

    @InjectMocks
    private CustomerCareAutomationService automationService;

    private Branch branch;
    private PatientProfile patient;
    private StaffProfile receptionist;
    private StaffProfile doctor;

    @BeforeEach
    void setUp() {
        branch = new Branch();
        branch.setBranchId(1L);
        branch.setBranchName("Cơ sở 1");

        patient = new PatientProfile();
        patient.setProfileId(10L);
        patient.setFullName("Trần Thị B");

        receptionist = new StaffProfile();
        receptionist.setStaffId(20L);
        receptionist.setStaffCode("LT01");
        receptionist.setPositionTitle("Lễ tân CSKH");
        receptionist.setDepartment("Chăm sóc khách hàng");
        receptionist.setStatus(StaffStatus.ACTIVE);

        doctor = new StaffProfile();
        doctor.setStaffId(21L);
        doctor.setStaffCode("BS01");
        doctor.setPositionTitle("Bác sĩ RHM");
        doctor.setDepartment("Lâm sàng");
        doctor.setStatus(StaffStatus.ACTIVE);

        lenient().when(taskRepository.existsByTaskCode(anyString())).thenReturn(false);
        lenient().when(taskRepository.save(any(CustomerCareTask.class)))
                .thenAnswer(inv -> {
                    CustomerCareTask t = inv.getArgument(0);
                    t.setTaskId(99L);
                    return t;
                });
    }

    @Test
    void eventListeners_IgnoreNullEventOrMissingIdsAndSwallowExceptions() {
        automationService.onVisitCompleted(null);
        automationService.onVisitCompleted(new VisitCompletedCareEvent(null));
        automationService.onPrescriptionCreated(null);
        automationService.onPrescriptionCreated(new PrescriptionCreatedCareEvent(null));
        automationService.onAppointmentUpserted(null);
        automationService.onAppointmentUpserted(new AppointmentUpsertedCareEvent(null));
        automationService.onAppointmentNoShow(null);
        automationService.onAppointmentNoShow(new AppointmentNoShowCareEvent(null));
        verifyNoInteractions(visitRepository, prescriptionRepository, appointmentRepository);

        when(visitRepository.findWithDetailsById(1L)).thenThrow(new RuntimeException("DB fail"));
        assertDoesNotThrow(() -> automationService.onVisitCompleted(new VisitCompletedCareEvent(1L)));
    }

    @Test
    void createPostTreatmentTaskIfMissing_ReturnsFalse_WhenVisitNotFoundNotCompletedOrAlreadyHasTask() {
        when(visitRepository.findWithDetailsById(1L)).thenReturn(Optional.empty());
        assertFalse(automationService.createPostTreatmentTaskIfMissing(1L));

        Visit inProgress = new Visit();
        inProgress.setVisitId(2L);
        inProgress.setStatus(VisitStatus.IN_EXAM);
        inProgress.setPatientProfile(patient);
        inProgress.setBranch(branch);
        when(visitRepository.findWithDetailsById(2L)).thenReturn(Optional.of(inProgress));
        assertFalse(automationService.createPostTreatmentTaskIfMissing(2L));

        Visit completed = new Visit();
        completed.setVisitId(3L);
        completed.setStatus(VisitStatus.COMPLETED);
        completed.setPatientProfile(patient);
        completed.setBranch(branch);
        when(visitRepository.findWithDetailsById(3L)).thenReturn(Optional.of(completed));
        when(taskRepository.existsByVisit_VisitIdAndTaskType(3L, CustomerCareTaskType.POST_TREATMENT_FOLLOWUP))
                .thenReturn(true);
        assertFalse(automationService.createPostTreatmentTaskIfMissing(3L));
    }

    @Test
    void createPostTreatmentTaskIfMissing_CreatesTaskAssignsNonDoctorStaffAndClampsPastDueDateToToday() {
        Visit visit = new Visit();
        visit.setVisitId(10L);
        visit.setVisitCode("LK-10");
        visit.setStatus(VisitStatus.COMPLETED);
        visit.setPatientProfile(patient);
        visit.setBranch(branch);
        visit.setCheckInAt(LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh")).minusDays(5));
        visit.setInitialSymptoms("Đau răng số 8");
        visit.setNotes("Nhổ răng khôn");

        when(visitRepository.findWithDetailsById(10L)).thenReturn(Optional.of(visit));
        when(taskRepository.existsByVisit_VisitIdAndTaskType(10L, CustomerCareTaskType.POST_TREATMENT_FOLLOWUP))
                .thenReturn(false);
        when(taskRepository.findActiveStaffIdsByBranch(1L)).thenReturn(List.of(21L, 20L));
        when(staffRepository.findById(21L)).thenReturn(Optional.of(doctor));
        when(staffRepository.findById(20L)).thenReturn(Optional.of(receptionist));

        boolean created = automationService.createPostTreatmentTaskIfMissing(10L);

        assertTrue(created);
        ArgumentCaptor<CustomerCareTask> captor = ArgumentCaptor.forClass(CustomerCareTask.class);
        verify(taskRepository).save(captor.capture());
        CustomerCareTask saved = captor.getValue();
        assertEquals(CustomerCareTaskType.POST_TREATMENT_FOLLOWUP, saved.getTaskType());
        assertEquals(receptionist, saved.getAssignedStaff());
        assertEquals(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")), saved.getDueDate());
        assertTrue(saved.getNotes().contains("Đau răng số 8"));
        verify(notificationService).notifyTaskCreated(saved, null, "Hệ thống tự động", true);
    }

    @Test
    void createMedicationReminderIfMissing_ReturnsFalse_WhenCancelledOrDuplicate_AndCreatesWhenValid() {
        Visit cancelledVisit = new Visit();
        cancelledVisit.setStatus(VisitStatus.CANCELLED);

        Prescription prCancelled = new Prescription();
        prCancelled.setPrescriptionId(1L);
        prCancelled.setPatientProfile(patient);
        prCancelled.setBranch(branch);
        prCancelled.setVisit(cancelledVisit);
        when(prescriptionRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(prCancelled));
        assertFalse(automationService.createMedicationReminderIfMissing(1L));

        Visit validVisit = new Visit();
        validVisit.setStatus(VisitStatus.COMPLETED);
        Prescription pr = new Prescription();
        pr.setPrescriptionId(2L);
        pr.setPrescriptionCode("DT-002");
        pr.setPatientProfile(patient);
        pr.setBranch(branch);
        pr.setVisit(validVisit);
        pr.setFollowupDate(LocalDate.of(2026, 6, 1));
        pr.setDiagnosisText("Viêm nướu");
        pr.setAdviceNotes("Súc miệng nước muối");

        when(prescriptionRepository.findByIdWithDetails(2L)).thenReturn(Optional.of(pr));
        when(taskRepository.existsByPrescription_PrescriptionIdAndTaskType(2L, CustomerCareTaskType.MEDICATION_REMINDER))
                .thenReturn(false);
        when(taskRepository.findActiveStaffIdsByBranch(1L)).thenReturn(List.of());
        when(staffRepository.findByStatus(StaffStatus.ACTIVE)).thenReturn(List.of(receptionist));

        assertTrue(automationService.createMedicationReminderIfMissing(2L));
        ArgumentCaptor<CustomerCareTask> captor = ArgumentCaptor.forClass(CustomerCareTask.class);
        verify(taskRepository).save(captor.capture());
        assertEquals(CustomerCareTaskType.MEDICATION_REMINDER, captor.getValue().getTaskType());
        assertEquals(LocalDate.of(2026, 6, 1), captor.getValue().getDueDate());
        assertTrue(captor.getValue().getNotes().contains("Viêm nướu"));
        verify(notificationService).notifyTaskCreated(captor.getValue(), null, "Hệ thống tự động", true);
    }

    @Test
    void createAppointmentReminderIfMissing_HandlesStatusCheckAndCreatesReminderOneDayBefore() {
        Appointment completedAppt = new Appointment();
        completedAppt.setAppointmentId(1L);
        completedAppt.setPatientProfile(patient);
        completedAppt.setBranch(branch);
        completedAppt.setStatus(AppointmentStatus.COMPLETED);
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(completedAppt));
        assertFalse(automationService.createAppointmentReminderIfMissing(1L));

        Service srv = new Service();
        srv.setServiceName("Cạo vôi răng");
        Appointment pendingAppt = new Appointment();
        pendingAppt.setAppointmentId(2L);
        pendingAppt.setAppointmentCode("LH-002");
        pendingAppt.setPatientProfile(patient);
        pendingAppt.setBranch(branch);
        pendingAppt.setStatus(AppointmentStatus.CONFIRMED);
        pendingAppt.setAppointmentDate(LocalDate.of(2026, 7, 15));
        pendingAppt.setService(srv);

        when(appointmentRepository.findById(2L)).thenReturn(Optional.of(pendingAppt));
        when(taskRepository.existsByAppointment_AppointmentIdAndTaskType(2L, CustomerCareTaskType.APPOINTMENT_REMINDER))
                .thenReturn(false);
        when(taskRepository.findActiveStaffIdsByBranch(1L)).thenReturn(List.of(20L));
        when(staffRepository.findById(20L)).thenReturn(Optional.of(receptionist));

        assertTrue(automationService.createAppointmentReminderIfMissing(2L));
        ArgumentCaptor<CustomerCareTask> captor = ArgumentCaptor.forClass(CustomerCareTask.class);
        verify(taskRepository).save(captor.capture());
        assertEquals(CustomerCareTaskType.APPOINTMENT_REMINDER, captor.getValue().getTaskType());
        assertEquals(LocalDate.of(2026, 7, 14), captor.getValue().getDueDate());
        assertTrue(captor.getValue().getNotes().contains("Cạo vôi răng"));
    }

    @Test
    void createNoShowRecallIfMissing_CreatesOnlyForNoShowStatusWhenMissing() {
        Appointment confirmed = new Appointment();
        confirmed.setAppointmentId(1L);
        confirmed.setPatientProfile(patient);
        confirmed.setBranch(branch);
        confirmed.setStatus(AppointmentStatus.CONFIRMED);
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(confirmed));
        assertFalse(automationService.createNoShowRecallIfMissing(1L));

        Appointment noShow = new Appointment();
        noShow.setAppointmentId(2L);
        noShow.setAppointmentCode("LH-NS-2");
        noShow.setPatientProfile(patient);
        noShow.setBranch(branch);
        noShow.setStatus(AppointmentStatus.NO_SHOW);
        when(appointmentRepository.findById(2L)).thenReturn(Optional.of(noShow));
        when(taskRepository.existsByAppointment_AppointmentIdAndTaskType(2L, CustomerCareTaskType.NO_SHOW_RECALL))
                .thenReturn(false);
        when(taskRepository.findActiveStaffIdsByBranch(1L)).thenReturn(List.of(20L));
        when(staffRepository.findById(20L)).thenReturn(Optional.of(receptionist));

        assertTrue(automationService.createNoShowRecallIfMissing(2L));
        ArgumentCaptor<CustomerCareTask> captor = ArgumentCaptor.forClass(CustomerCareTask.class);
        verify(taskRepository).save(captor.capture());
        assertEquals(CustomerCareTaskType.NO_SHOW_RECALL, captor.getValue().getTaskType());
        assertEquals(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")), captor.getValue().getDueDate());
    }

    @Test
    void catchUpMissedTasks_ScansAllActiveBranchesAndContinuesOnBranchFailure() {
        Branch badBranch = new Branch();
        badBranch.setBranchId(2L);

        when(branchRepository.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(badBranch, branch));
        when(taskRepository.findCompletedVisitsByBranchForCare(2L)).thenThrow(new RuntimeException("Branch 2 error"));

        Visit visit = new Visit();
        visit.setVisitId(100L);
        visit.setVisitCode("LK-100");
        visit.setStatus(VisitStatus.COMPLETED);
        visit.setPatientProfile(patient);
        visit.setBranch(branch);

        Appointment noShow = new Appointment();
        noShow.setAppointmentId(200L);
        noShow.setAppointmentCode("LH-200");
        noShow.setPatientProfile(patient);
        noShow.setBranch(branch);
        noShow.setStatus(AppointmentStatus.NO_SHOW);

        when(taskRepository.findCompletedVisitsByBranchForCare(1L)).thenReturn(List.of(visit));
        when(taskRepository.findValidPrescriptionsByBranchForCare(1L)).thenReturn(List.of());
        when(appointmentRepository.findByBranchWithDetails(1L)).thenReturn(List.of(noShow));
        when(visitRepository.findWithDetailsById(100L)).thenReturn(Optional.of(visit));
        when(appointmentRepository.findById(200L)).thenReturn(Optional.of(noShow));
        when(taskRepository.findActiveStaffIdsByBranch(1L)).thenReturn(List.of(20L));
        when(staffRepository.findById(20L)).thenReturn(Optional.of(receptionist));

        automationService.catchUpMissedTasks();

        verify(taskRepository, times(2)).save(any(CustomerCareTask.class));
        verify(notificationService, times(2)).notifyTaskCreated(any(), isNull(), eq("Hệ thống tự động"), eq(true));
    }

    @Test
    void onPrescriptionCreated_SwallowsException_WhenRepositoryFails() {
        when(prescriptionRepository.findByIdWithDetails(77L)).thenThrow(new RuntimeException("DB fail"));
        assertDoesNotThrow(() -> automationService.onPrescriptionCreated(new PrescriptionCreatedCareEvent(77L)));
    }

    @Test
    void onAppointmentUpserted_SwallowsException_WhenRepositoryFails() {
        when(appointmentRepository.findById(88L)).thenThrow(new RuntimeException("DB fail"));
        assertDoesNotThrow(() -> automationService.onAppointmentUpserted(new AppointmentUpsertedCareEvent(88L)));
    }

    @Test
    void onAppointmentNoShow_SwallowsException_WhenRepositoryFails() {
        when(appointmentRepository.findById(99L)).thenThrow(new RuntimeException("DB fail"));
        assertDoesNotThrow(() -> automationService.onAppointmentNoShow(new AppointmentNoShowCareEvent(99L)));
    }

    @Test
    void createPostTreatmentTaskIfMissing_FallsBackToFirstBranchStaff_WhenBranchHasOnlyDoctors() {
        Visit visit = new Visit();
        visit.setVisitId(15L);
        visit.setVisitCode("LK-15");
        visit.setStatus(VisitStatus.COMPLETED);
        visit.setPatientProfile(patient);
        visit.setBranch(branch);

        when(visitRepository.findWithDetailsById(15L)).thenReturn(Optional.of(visit));
        when(taskRepository.existsByVisit_VisitIdAndTaskType(15L, CustomerCareTaskType.POST_TREATMENT_FOLLOWUP)).thenReturn(false);
        when(taskRepository.findActiveStaffIdsByBranch(1L)).thenReturn(List.of(21L));
        when(staffRepository.findById(21L)).thenReturn(Optional.of(doctor));

        assertTrue(automationService.createPostTreatmentTaskIfMissing(15L));
        ArgumentCaptor<CustomerCareTask> captor = ArgumentCaptor.forClass(CustomerCareTask.class);
        verify(taskRepository).save(captor.capture());
        assertEquals(doctor, captor.getValue().getAssignedStaff());
    }

    @Test
    void createMedicationReminderIfMissing_ReturnsFalse_WhenMissingOrAlreadyExists() {
        when(prescriptionRepository.findByIdWithDetails(404L)).thenReturn(Optional.empty());
        assertFalse(automationService.createMedicationReminderIfMissing(404L));

        Prescription pr = new Prescription();
        pr.setPrescriptionId(5L);
        pr.setPatientProfile(patient);
        pr.setBranch(branch);
        when(prescriptionRepository.findByIdWithDetails(5L)).thenReturn(Optional.of(pr));
        when(taskRepository.existsByPrescription_PrescriptionIdAndTaskType(5L, CustomerCareTaskType.MEDICATION_REMINDER))
                .thenReturn(true);
        assertFalse(automationService.createMedicationReminderIfMissing(5L));
    }

    @Test
    void catchUpMissedTasks_ReturnsImmediately_WhenNoActiveBranches() {
        when(branchRepository.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of());
        automationService.catchUpMissedTasks();
        verifyNoInteractions(taskRepository, appointmentRepository, visitRepository, prescriptionRepository);
    }
}
