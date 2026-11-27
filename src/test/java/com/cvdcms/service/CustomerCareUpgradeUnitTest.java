package com.cvdcms.service;

import com.cvdcms.DTO.CustomerCareInteractionCreateDTO;
import com.cvdcms.DTO.CustomerCareTaskDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.event.ClinicQueueDomainEvent;
import com.cvdcms.event.CustomerCareDomainEvents.InstallmentPaymentRecordedCareEvent;
import com.cvdcms.repository.*;
import com.cvdcms.service.impl.CustomerCareServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerCareUpgradeUnitTest {

    @Mock private CustomerCareTaskRepository taskRepository;
    @Mock private CustomerCareInteractionRepository interactionRepository;
    @Mock private PatientProfileRepository patientRepository;
    @Mock private BranchRepository branchRepository;
    @Mock private StaffProfileRepository staffRepository;
    @Mock private VisitRepository visitRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private UserRepository userRepository;
    @Mock private LeaveRequestRepository leaveRequestRepository;
    @Mock private TreatmentPlanInstallmentRepository installmentRepository;
    @Mock private SystemNotificationRepository notificationRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private SimpMessagingTemplate messagingTemplate;

    private CustomerCareNotificationService notificationService;
    private CustomerCareAutomationService automationService;
    private CustomerCareServiceImpl careService;

    private Branch branch;
    private PatientProfile patient;
    private StaffProfile doctor;
    private StaffProfile receptionist;

    @BeforeEach
    void setUp() {
        branch = new Branch();
        branch.setBranchId(1L);
        branch.setBranchName("Cơ sở 1");
        branch.setIsActive(true);

        patient = new PatientProfile();
        patient.setProfileId(10L);
        patient.setPatientCode("BN0010");
        patient.setFullName("Nguyễn Văn A");
        patient.setPhoneNumber("0901234567");

        doctor = new StaffProfile();
        doctor.setStaffId(20L);
        doctor.setFullName("BS. Trần Minh");
        doctor.setPositionTitle("Bác sĩ");

        User recUser = new User();
        recUser.setUserId(300L);
        recUser.setUsername("letan1");
        recUser.setIsActive(true);
        recUser.setStatus("ACTIVE");
        recUser.setBranch(branch);

        receptionist = new StaffProfile();
        receptionist.setStaffId(30L);
        receptionist.setFullName("Lê Thị Lễ Tân");
        receptionist.setPositionTitle("Lễ tân");
        receptionist.setUser(recUser);

        notificationService = new CustomerCareNotificationService(
                notificationRepository, taskRepository, userRepository, eventPublisher, messagingTemplate);
        automationService = new CustomerCareAutomationService(
                taskRepository, visitRepository, prescriptionRepository, appointmentRepository,
                branchRepository, staffRepository, notificationService, leaveRequestRepository, installmentRepository);
        careService = new CustomerCareServiceImpl(
                taskRepository, interactionRepository, patientRepository, branchRepository,
                staffRepository, visitRepository, appointmentRepository, prescriptionRepository, userRepository);
    }

    @Test
    void ft01_pendingOrRejectedLeave_createsZeroTasks() {
        LeaveRequest pending = new LeaveRequest();
        pending.setLeaveId(1L);
        pending.setStatus(LeaveStatus.PENDING);
        pending.setStaff(doctor);
        pending.setBranch(branch);
        pending.setStartDate(LocalDate.now());
        pending.setEndDate(LocalDate.now().plusDays(1));

        assertEquals(0, automationService.createDoctorLeaveTasksIfMissing(pending));

        pending.setStatus(LeaveStatus.REJECTED);
        assertEquals(0, automationService.createDoctorLeaveTasksIfMissing(pending));
        verify(taskRepository, never()).save(any());
    }

    @Test
    void ft01_approvedShiftLeave_createsTaskOnlyForAffectedAppointment_andDeduplicates() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        WorkShift morning = new WorkShift();
        morning.setShiftId(1L);
        morning.setShiftName("Ca sáng");
        morning.setStartTime(LocalTime.of(8, 0));
        morning.setEndTime(LocalTime.of(12, 0));

        LeaveRequest approved = new LeaveRequest();
        approved.setLeaveId(55L);
        approved.setStatus(LeaveStatus.APPROVED);
        approved.setStaff(doctor);
        approved.setBranch(branch);
        approved.setShift(morning);
        approved.setStartDate(tomorrow);
        approved.setEndDate(tomorrow);

        Appointment inShift = new Appointment();
        inShift.setAppointmentId(101L);
        inShift.setAppointmentCode("LH101");
        inShift.setBranch(branch);
        inShift.setDoctor(doctor);
        inShift.setPatientProfile(patient);
        inShift.setAppointmentDate(tomorrow);
        inShift.setExpectedTime(LocalTime.of(9, 0));
        inShift.setExpectedEndTime(LocalTime.of(9, 30));
        inShift.setStatus(AppointmentStatus.CONFIRMED);

        Appointment outOfShift = new Appointment();
        outOfShift.setAppointmentId(102L);
        outOfShift.setAppointmentCode("LH102");
        outOfShift.setBranch(branch);
        outOfShift.setDoctor(doctor);
        outOfShift.setPatientProfile(patient);
        outOfShift.setAppointmentDate(tomorrow);
        outOfShift.setExpectedTime(LocalTime.of(14, 0));
        outOfShift.setExpectedEndTime(LocalTime.of(14, 30));
        outOfShift.setStatus(AppointmentStatus.CONFIRMED);

        when(leaveRequestRepository.findByStaffStaffIdOrderBySubmittedAtDesc(20L)).thenReturn(List.of(approved));
        when(appointmentRepository.findByBranchWithDetails(1L)).thenReturn(List.of(inShift, outOfShift));
        when(taskRepository.existsByLeaveRequestIdAndAppointment_AppointmentIdAndTaskType(
                55L, 101L, CustomerCareTaskType.DOCTOR_LEAVE_RESCHEDULE)).thenReturn(false, true);
        when(taskRepository.save(any(CustomerCareTask.class))).thenAnswer(inv -> {
            CustomerCareTask t = inv.getArgument(0);
            t.setTaskId(900L);
            return t;
        });

        automationService.onClinicQueueDomainEvent(new ClinicQueueDomainEvent(
                "DOCTOR_SCHEDULE_CHANGED", 1L, 20L, null, null, "LEAVE_APPROVED", "Nghỉ", LocalDateTime.now()));
        // Run second time -> must not create duplicate
        int secondRun = automationService.createDoctorLeaveTasksIfMissing(approved);

        assertEquals(0, secondRun);
        ArgumentCaptor<CustomerCareTask> captor = ArgumentCaptor.forClass(CustomerCareTask.class);
        verify(taskRepository, times(1)).save(captor.capture());
        assertEquals(CustomerCareTaskType.DOCTOR_LEAVE_RESCHEDULE, captor.getValue().getTaskType());
        assertEquals(55L, captor.getValue().getLeaveRequestId());
        assertEquals(101L, captor.getValue().getAppointment().getAppointmentId());
    }

    @Test
    void ft02_installmentStagesAndAutoCloseOnFullPayment() {
        TreatmentPlan plan = new TreatmentPlan();
        plan.setPlanId(77L);
        plan.setPlanCode("KHDT077");
        plan.setBranch(branch);
        plan.setPatientProfile(patient);
        plan.setStatus(TreatmentPlanStatus.IN_PROGRESS);
        plan.setTotalEstimatedCost(new BigDecimal("30000000"));
        plan.setTotalPaidAmount(new BigDecimal("14000000"));

        TreatmentPlanInstallment inst = new TreatmentPlanInstallment();
        inst.setInstallmentId(88L);
        inst.setPlan(plan);
        inst.setInstallmentOrder(2);
        inst.setPhaseName("Đợt 2");
        inst.setExpectedDate(LocalDate.now().plusDays(3)); // T-3
        inst.setExpectedAmount(new BigDecimal("10000000"));
        inst.setPaidAmount(new BigDecimal("4000000"));
        inst.setStatus(TreatmentPlanInstallmentStatus.PARTIAL);

        when(installmentRepository.findByPlan_PlanIdOrderByInstallmentOrderAsc(77L)).thenReturn(List.of(inst));
        when(taskRepository.findFirstByInstallmentIdAndTaskType(88L, CustomerCareTaskType.PAYMENT_REMINDER))
                .thenReturn(Optional.empty());
        when(taskRepository.save(any(CustomerCareTask.class))).thenAnswer(inv -> {
            CustomerCareTask t = inv.getArgument(0);
            t.setTaskId(901L);
            return t;
        });

        automationService.onInstallmentPaymentRecorded(new InstallmentPaymentRecordedCareEvent(77L));

        ArgumentCaptor<CustomerCareTask> savedCaptor = ArgumentCaptor.forClass(CustomerCareTask.class);
        verify(taskRepository).save(savedCaptor.capture());
        CustomerCareTask created = savedCaptor.getValue();
        assertEquals(CustomerCareTaskType.PAYMENT_REMINDER, created.getTaskType());
        assertEquals("T_MINUS_3", created.getReminderStage());
        assertEquals(88L, created.getInstallmentId());

        // Same stage again -> idempotent, no extra notification
        when(taskRepository.findFirstByInstallmentIdAndTaskType(88L, CustomerCareTaskType.PAYMENT_REMINDER))
                .thenReturn(Optional.of(created));
        automationService.reconcileInstallmentsForPlan(77L);
        verify(taskRepository, times(1)).save(any());

        // Full payment -> auto-closes task to COMPLETED
        inst.setPaidAmount(new BigDecimal("10000000"));
        inst.setStatus(TreatmentPlanInstallmentStatus.PAID);
        automationService.onInstallmentPaymentRecorded(new InstallmentPaymentRecordedCareEvent(77L));
        assertEquals(CustomerCareTaskStatus.COMPLETED, created.getStatus());
    }

    @Test
    void failedContactResult_keepsTaskInProgress_neverAutoCompletes() {
        CustomerCareTask task = new CustomerCareTask();
        task.setTaskId(500L);
        task.setTaskCode("TASK500");
        task.setBranch(branch);
        task.setPatientProfile(patient);
        task.setTaskType(CustomerCareTaskType.APPOINTMENT_REMINDER);
        task.setStatus(CustomerCareTaskStatus.PENDING);
        task.setDueDate(LocalDate.now());

        when(userRepository.findById(300L)).thenReturn(Optional.of(receptionist.getUser()));
        when(taskRepository.findActiveRoleNamesByUserId(300L)).thenReturn(List.of("LE_TAN"));
        when(taskRepository.findExplicitBranchIdsByUserId(300L)).thenReturn(List.of(1L));
        when(branchRepository.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(branch));
        when(taskRepository.findByIdWithDetails(500L)).thenReturn(Optional.of(task));
        when(staffRepository.findByUser_UserId(300L)).thenReturn(Optional.of(receptionist));
        when(interactionRepository.findByTaskIdWithDetails(500L)).thenReturn(List.of());

        CustomerCareInteractionCreateDTO dto = new CustomerCareInteractionCreateDTO();
        dto.setChannel("PHONE_CALL");
        dto.setContactResult("FAILED");
        dto.setNotes("Số điện thoại không liên lạc được");
        LocalDateTime retry = LocalDateTime.now().plusDays(1);
        dto.setNextRetryAt(retry);

        CustomerCareTaskDTO res = careService.recordInteraction(500L, dto, 300L);
        assertEquals("IN_PROGRESS", res.getStatus());
        assertEquals(retry.toLocalDate(), task.getDueDate());
    }

    @Test
    void proxyFactoryPostProcessor_wrapsTreatmentPlanServiceAndPublishesEventAfterSuccess() {
        TreatmentPlanService rawTarget = mock(TreatmentPlanService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ApplicationEventPublisher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(eventPublisher);

        InstallmentPaymentSyncPostProcessor processor = new InstallmentPaymentSyncPostProcessor(provider);
        Object wrapped = processor.postProcessAfterInitialization(rawTarget, "treatmentPlanServiceImpl");

        assertInstanceOf(TreatmentPlanService.class, wrapped);
        assertInstanceOf(InstallmentPaymentSyncPostProcessor.CskhWrappedTreatmentPlanService.class, wrapped);

        // Wrapping twice returns same instance
        assertSame(wrapped, processor.postProcessAfterInitialization(wrapped, "treatmentPlanServiceImpl"));

        TreatmentPlanService proxy = (TreatmentPlanService) wrapped;
        proxy.recordInstallmentPayment(77L, new BigDecimal("500000"), null);

        verify(rawTarget).recordInstallmentPayment(77L, new BigDecimal("500000"), null);
        verify(eventPublisher).publishEvent(new InstallmentPaymentRecordedCareEvent(77L));
    }

    @Test
    void notificationService_computesPerUserTabUnreadCounts() {
        CustomerCareTask t1 = new CustomerCareTask();
        t1.setTaskId(1L);
        t1.setTaskType(CustomerCareTaskType.DOCTOR_LEAVE_RESCHEDULE);
        t1.setStatus(CustomerCareTaskStatus.PENDING);
        t1.setDueDate(LocalDate.now());

        SystemNotification n1 = new SystemNotification();
        n1.setNotificationId(10L);
        n1.setTaskId(1L);
        n1.setIsRead(false);
        n1.setTitle("Mới");
        n1.setMessage("MSG");
        n1.setNotificationType(NotificationType.CUSTOMER_CARE);
        n1.setCreatedAt(LocalDateTime.now());

        when(notificationRepository.findByUser_UserIdAndNotificationTypeOrderByCreatedAtDesc(
                eq(300L), eq(NotificationType.CUSTOMER_CARE), any(Pageable.class)))
                .thenReturn(List.of(n1));
        when(notificationRepository.countByUser_UserIdAndNotificationTypeAndIsReadFalse(
                300L, NotificationType.CUSTOMER_CARE)).thenReturn(1L);
        when(taskRepository.findAllById(any())).thenReturn(List.of(t1));

        Map<String, Object> result = notificationService.getUserNotifications(300L, 20);
        assertEquals(1L, result.get("unreadCount"));
        @SuppressWarnings("unchecked")
        Map<String, Long> tabCounts = (Map<String, Long>) result.get("tabUnreadCounts");
        assertEquals(1L, tabCounts.get("TODAY"));
        assertEquals(1L, tabCounts.get("DOCTOR_LEAVE"));
        assertEquals(0L, tabCounts.get("PAYMENT_REMINDER"));
    }
}
