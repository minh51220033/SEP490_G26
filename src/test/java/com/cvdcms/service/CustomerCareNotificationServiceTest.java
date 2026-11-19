package com.cvdcms.service;

import com.cvdcms.DTO.CustomerCareNotificationDTO;
import com.cvdcms.DTO.CustomerCareRealtimeEventDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.CustomerCareTaskStatus;
import com.cvdcms.entity.enums.NotificationType;
import com.cvdcms.event.CustomerCareDomainEvents.CustomerCareRealtimeBroadcastEvent;
import com.cvdcms.repository.CustomerCareTaskRepository;
import com.cvdcms.repository.SystemNotificationRepository;
import com.cvdcms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerCareNotificationServiceTest {

    @Mock
    private SystemNotificationRepository notificationRepository;

    @Mock
    private CustomerCareTaskRepository taskRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private CustomerCareNotificationService notificationService;

    private Branch branch;
    private PatientProfile patient;
    private CustomerCareTask task;
    private User staffUser;
    private StaffProfile assignedStaff;
    private User otherBranchUser;

    @BeforeEach
    void setUp() {
        branch = new Branch();
        branch.setBranchId(1L);
        branch.setBranchName("Cơ sở 1");

        Branch otherBranch = new Branch();
        otherBranch.setBranchId(2L);

        patient = new PatientProfile();
        patient.setProfileId(100L);
        patient.setPatientCode("BN-0100");
        patient.setFullName("Nguyễn Văn A");

        staffUser = new User();
        staffUser.setUserId(10L);
        staffUser.setUsername("letan01");
        staffUser.setIsActive(true);
        staffUser.setBranch(branch);

        otherBranchUser = new User();
        otherBranchUser.setUserId(20L);
        otherBranchUser.setUsername("letan02");
        otherBranchUser.setIsActive(true);
        otherBranchUser.setBranch(otherBranch);

        assignedStaff = new StaffProfile();
        assignedStaff.setStaffId(50L);
        assignedStaff.setUser(staffUser);

        task = new CustomerCareTask();
        task.setTaskId(500L);
        task.setTaskCode("CSKH-500");
        task.setBranch(branch);
        task.setPatientProfile(patient);
        task.setAssignedStaff(assignedStaff);
        task.setDueDate(LocalDate.of(2026, 5, 10));
        task.setStatus(CustomerCareTaskStatus.PENDING);
    }

    @Test
    void notifyTaskCreated_DoesNothing_WhenTaskOrBranchNull() {
        notificationService.notifyTaskCreated(null, 1L, "Admin", false);
        CustomerCareTask noBranch = new CustomerCareTask();
        notificationService.notifyTaskCreated(noBranch, 1L, "Admin", false);

        verifyNoInteractions(notificationRepository, eventPublisher);
    }

    @Test
    void notifyTaskCreated_PersistsNotificationsOnlyForMatchingActiveBranchUsersAndExcludesActorWhenManual() {
        when(userRepository.findActiveReceptionistsAndAdminsByBranch(1L))
                .thenReturn(List.of(staffUser, otherBranchUser));
        when(userRepository.findById(10L)).thenReturn(Optional.of(staffUser));

        notificationService.notifyTaskCreated(task, 99L, "Quản trị viên", false);

        ArgumentCaptor<SystemNotification> notifCaptor = ArgumentCaptor.forClass(SystemNotification.class);
        verify(notificationRepository, times(1)).save(notifCaptor.capture());
        SystemNotification saved = notifCaptor.getValue();
        assertEquals(NotificationType.CUSTOMER_CARE, saved.getNotificationType());
        assertEquals(500L, saved.getTaskId());
        assertEquals("NORMAL", saved.getPriorityLevel());
        assertEquals("/customer-care?taskId=500", saved.getActionUrl());

        ArgumentCaptor<CustomerCareRealtimeBroadcastEvent> eventCaptor =
                ArgumentCaptor.forClass(CustomerCareRealtimeBroadcastEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertEquals("CUSTOMER_CARE_TASK_CREATED", eventCaptor.getValue().payload().getEventType());
        assertEquals(List.of(10L), eventCaptor.getValue().targetUserIds());
    }

    @Test
    void notifyTaskCreated_IncludesActorWhenAutoGenerated() {
        when(userRepository.findActiveReceptionistsAndAdminsByBranch(1L)).thenReturn(List.of(staffUser));
        when(userRepository.findById(10L)).thenReturn(Optional.of(staffUser));

        notificationService.notifyTaskCreated(task, 10L, "Hệ thống tự động", true);

        verify(notificationRepository, times(1)).save(any(SystemNotification.class));
        ArgumentCaptor<CustomerCareRealtimeBroadcastEvent> captor =
                ArgumentCaptor.forClass(CustomerCareRealtimeBroadcastEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(List.of(10L), captor.getValue().targetUserIds());
    }

    @Test
    void notifyTaskUpdated_PublishesCompletedOrUpdatedEventDependingOnStatus() {
        notificationService.notifyTaskUpdated(task, 10L, "Lễ tân");
        task.setStatus(CustomerCareTaskStatus.COMPLETED);
        notificationService.notifyTaskUpdated(task, 10L, "Lễ tân");

        ArgumentCaptor<CustomerCareRealtimeBroadcastEvent> captor =
                ArgumentCaptor.forClass(CustomerCareRealtimeBroadcastEvent.class);
        verify(eventPublisher, times(2)).publishEvent(captor.capture());
        List<CustomerCareRealtimeBroadcastEvent> events = captor.getAllValues();
        assertEquals("CUSTOMER_CARE_TASK_UPDATED", events.get(0).payload().getEventType());
        assertEquals("CUSTOMER_CARE_TASK_COMPLETED", events.get(1).payload().getEventType());
    }

    @Test
    void notifyEscalatedToDoctor_SendsHighPriorityToActiveDoctorAndSkipsNullArgs() {
        notificationService.notifyEscalatedToDoctor(task, null, 10L, "Lễ tân");
        verifyNoInteractions(notificationRepository);

        User docUser = new User();
        docUser.setUserId(30L);
        docUser.setIsActive(true);
        StaffProfile doctor = new StaffProfile();
        doctor.setStaffId(300L);
        doctor.setUser(docUser);

        task.setStatus(CustomerCareTaskStatus.ESCALATED_TO_DOCTOR);
        when(userRepository.findById(30L)).thenReturn(Optional.of(docUser));

        notificationService.notifyEscalatedToDoctor(task, doctor, 10L, "Lễ tân");

        ArgumentCaptor<SystemNotification> captor = ArgumentCaptor.forClass(SystemNotification.class);
        verify(notificationRepository).save(captor.capture());
        assertEquals("HIGH", captor.getValue().getPriorityLevel());
        assertEquals(30L, captor.getValue().getUser().getUserId());

        ArgumentCaptor<CustomerCareRealtimeBroadcastEvent> evCaptor =
                ArgumentCaptor.forClass(CustomerCareRealtimeBroadcastEvent.class);
        verify(eventPublisher).publishEvent(evCaptor.capture());
        assertEquals("CUSTOMER_CARE_TASK_ESCALATED", evCaptor.getValue().payload().getEventType());
        assertEquals(300L, evCaptor.getValue().payload().getTargetDoctorStaffId());
    }

    @Test
    void notifyDoctorResponded_VariesTitleAndPriorityByStatusAndFallsBackToBranchStaff() {
        task.setStatus(CustomerCareTaskStatus.COMPLETED);
        when(userRepository.findById(10L)).thenReturn(Optional.of(staffUser));

        notificationService.notifyDoctorResponded(task, 30L, "BS Hùng");

        ArgumentCaptor<SystemNotification> captor = ArgumentCaptor.forClass(SystemNotification.class);
        verify(notificationRepository).save(captor.capture());
        assertEquals("NORMAL", captor.getValue().getPriorityLevel());
        assertTrue(captor.getValue().getTitle().contains("hoàn tất"));

        // When doctorUserId == assignedStaff userId, fall back to branch care staff
        clearInvocations(notificationRepository);
        task.setStatus(CustomerCareTaskStatus.ESCALATED_TO_DOCTOR);
        User branchRec = new User();
        branchRec.setUserId(40L);
        branchRec.setIsActive(true);
        branchRec.setBranch(branch);
        when(userRepository.findActiveReceptionistsAndAdminsByBranch(1L)).thenReturn(List.of(branchRec));
        when(userRepository.findById(40L)).thenReturn(Optional.of(branchRec));

        notificationService.notifyDoctorResponded(task, 10L, "BS Lan");

        ArgumentCaptor<SystemNotification> secondCaptor = ArgumentCaptor.forClass(SystemNotification.class);
        verify(notificationRepository, times(1)).save(secondCaptor.capture());
        assertEquals("HIGH", secondCaptor.getValue().getPriorityLevel());
        assertEquals(40L, secondCaptor.getValue().getUser().getUserId());
    }

    @Test
    void notifyTaskReopened_SendsHighPriorityNotificationWithReason() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(staffUser));

        notificationService.notifyTaskReopened(task, "Cần kiểm tra lại triệu chứng", 1L, "Admin");

        ArgumentCaptor<SystemNotification> captor = ArgumentCaptor.forClass(SystemNotification.class);
        verify(notificationRepository).save(captor.capture());
        assertEquals("HIGH", captor.getValue().getPriorityLevel());
        assertTrue(captor.getValue().getMessage().contains("Cần kiểm tra lại triệu chứng"));

        ArgumentCaptor<CustomerCareRealtimeBroadcastEvent> evCaptor =
                ArgumentCaptor.forClass(CustomerCareRealtimeBroadcastEvent.class);
        verify(eventPublisher).publishEvent(evCaptor.capture());
        assertEquals("CUSTOMER_CARE_TASK_REOPENED", evCaptor.getValue().payload().getEventType());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getUserNotifications_ClampsLimitAndMapsTaskAndEventTypes() {
        SystemNotification n1 = new SystemNotification();
        n1.setNotificationId(1L);
        n1.setTitle("Công việc CSKH mới: CSKH-500");
        n1.setTaskId(500L);
        n1.setNotificationType(NotificationType.CUSTOMER_CARE);
        n1.setIsRead(false);

        SystemNotification n2 = new SystemNotification();
        n2.setNotificationId(2L);
        n2.setTitle("Bác sĩ cần hỗ trợ ca");
        n2.setNotificationType(NotificationType.CUSTOMER_CARE);
        n2.setIsRead(true);

        when(notificationRepository.findByUser_UserIdAndNotificationTypeOrderByCreatedAtDesc(
                eq(10L), eq(NotificationType.CUSTOMER_CARE), any(Pageable.class)))
                .thenReturn(List.of(n1, n2));
        when(notificationRepository.countByUser_UserIdAndNotificationTypeAndIsReadFalse(10L, NotificationType.CUSTOMER_CARE))
                .thenReturn(1L);
        when(taskRepository.findAllById(any())).thenReturn(List.of(task));

        Map<String, Object> res = notificationService.getUserNotifications(10L, 999);

        ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(notificationRepository).findByUser_UserIdAndNotificationTypeOrderByCreatedAtDesc(
                eq(10L), eq(NotificationType.CUSTOMER_CARE), pageCaptor.capture());
        assertEquals(50, pageCaptor.getValue().getPageSize());

        assertEquals(1L, res.get("unreadCount"));
        List<CustomerCareNotificationDTO> items = (List<CustomerCareNotificationDTO>) res.get("notifications");
        assertEquals(2, items.size());
        assertEquals("TASK_CREATED", items.get(0).getEventType());
        assertEquals("CSKH-500", items.get(0).getTaskCode());
        assertEquals("ESCALATED", items.get(1).getEventType());
    }

    @Test
    void markAsRead_ThrowsNotFound_WhenMissing_AndMarksUnreadNotificationOnce() {
        when(notificationRepository.findByNotificationIdAndUser_UserId(99L, 10L)).thenReturn(Optional.empty());
        ResponseStatusException notFound = assertThrows(ResponseStatusException.class,
                () -> notificationService.markAsRead(99L, 10L));
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());

        SystemNotification unread = new SystemNotification();
        unread.setNotificationId(1L);
        unread.setTitle("Công việc CSKH mới: CSKH-500");
        unread.setTaskId(500L);
        unread.setIsRead(false);
        when(notificationRepository.findByNotificationIdAndUser_UserId(1L, 10L)).thenReturn(Optional.of(unread));
        when(taskRepository.findById(500L)).thenReturn(Optional.of(task));

        CustomerCareNotificationDTO dto = notificationService.markAsRead(1L, 10L);
        assertTrue(dto.getIsRead());
        assertNotNull(unread.getReadAt());
        verify(notificationRepository, times(1)).save(unread);

        // Already read should not save again
        unread.setReadAt(LocalDateTime.now());
        notificationService.markAsRead(1L, 10L);
        verify(notificationRepository, times(1)).save(unread);
    }

    @Test
    void markAllAsRead_UpdatesCountAndReturnsZeroUnread() {
        when(notificationRepository.markAllReadByUserAndType(
                eq(10L), eq(NotificationType.CUSTOMER_CARE), any(LocalDateTime.class)))
                .thenReturn(4);

        Map<String, Object> result = notificationService.markAllAsRead(10L);

        assertEquals(4, result.get("updatedCount"));
        assertEquals(0, result.get("unreadCount"));
    }

    @Test
    void onRealtimeBroadcast_SendsBranchTopicsAndUserQueuesAndSwallowsMessagingErrors() {
        CustomerCareRealtimeEventDTO payload = CustomerCareRealtimeEventDTO.builder()
                .eventType("CUSTOMER_CARE_TASK_CREATED")
                .branchId(1L)
                .taskId(500L)
                .title("New")
                .summary("Summary")
                .build();

        when(userRepository.findById(10L)).thenReturn(Optional.of(staffUser));

        notificationService.onRealtimeBroadcast(
                new CustomerCareRealtimeBroadcastEvent(payload, List.of(10L), true));

        verify(messagingTemplate).convertAndSend("/topic/customer-care/branch/1", payload);
        verify(messagingTemplate).convertAndSend("/topic/customer-care/admin", payload);
        verify(messagingTemplate).convertAndSendToUser(
                eq("letan01"), eq("/queue/customer-care/notifications"), any(CustomerCareRealtimeEventDTO.class));

        doThrow(new RuntimeException("Broker down")).when(messagingTemplate)
                .convertAndSend(eq("/topic/customer-care/branch/1"), eq(payload));
        assertDoesNotThrow(() -> notificationService.onRealtimeBroadcast(
                new CustomerCareRealtimeBroadcastEvent(payload, List.of(), true)));
    }

    @Test
    void notifyTaskCreated_TruncatesOverlongTitleWithinColumnLimit() {
        task.setTaskCode("C".repeat(220));
        when(userRepository.findActiveReceptionistsAndAdminsByBranch(1L)).thenReturn(List.of(staffUser));
        when(userRepository.findById(10L)).thenReturn(Optional.of(staffUser));

        notificationService.notifyTaskCreated(task, 99L, "Admin", false);

        ArgumentCaptor<SystemNotification> captor = ArgumentCaptor.forClass(SystemNotification.class);
        verify(notificationRepository).save(captor.capture());
        assertEquals(195, captor.getValue().getTitle().length());
    }

    @Test
    void notifyEscalatedToDoctor_SkipsNotificationPersistence_WhenDoctorUserInactive() {
        User inactiveDocUser = new User();
        inactiveDocUser.setUserId(31L);
        inactiveDocUser.setIsActive(false);
        StaffProfile doctor = new StaffProfile();
        doctor.setStaffId(301L);
        doctor.setUser(inactiveDocUser);

        notificationService.notifyEscalatedToDoctor(task, doctor, 10L, "Lễ tân");

        verifyNoInteractions(notificationRepository);
        verify(eventPublisher).publishEvent(any(CustomerCareRealtimeBroadcastEvent.class));
    }

    @Test
    void notifyDoctorResponded_InProgressStatusProducesHighPriorityFollowupNotification() {
        task.setStatus(CustomerCareTaskStatus.IN_PROGRESS);
        when(userRepository.findById(10L)).thenReturn(Optional.of(staffUser));

        notificationService.notifyDoctorResponded(task, 30L, "BS Minh");

        ArgumentCaptor<SystemNotification> captor = ArgumentCaptor.forClass(SystemNotification.class);
        verify(notificationRepository).save(captor.capture());
        assertEquals("HIGH", captor.getValue().getPriorityLevel());
        assertTrue(captor.getValue().getTitle().contains("đã phản hồi"));
    }

    @Test
    void notifyTaskReopened_FallsBackToBranchReceptionistAndExcludesActor() {
        assignedStaff.setUser(null);
        User adminActor = new User();
        adminActor.setUserId(1L);
        adminActor.setIsActive(true);
        adminActor.setBranch(branch);

        when(userRepository.findActiveReceptionistsAndAdminsByBranch(1L)).thenReturn(List.of(adminActor, staffUser));
        when(userRepository.findById(10L)).thenReturn(Optional.of(staffUser));

        notificationService.notifyTaskReopened(task, "Cần kiểm tra", 1L, "Admin");

        ArgumentCaptor<SystemNotification> captor = ArgumentCaptor.forClass(SystemNotification.class);
        verify(notificationRepository, times(1)).save(captor.capture());
        assertEquals(10L, captor.getValue().getUser().getUserId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getUserNotifications_ClampsMinimumLimitToOneAndMapsReopenedCompletedCallbackEvents() {
        SystemNotification reopenN = new SystemNotification();
        reopenN.setNotificationId(1L);
        reopenN.setTitle("Công việc CSKH-1 đã được mở lại");
        reopenN.setIsRead(false);

        SystemNotification completeN = new SystemNotification();
        completeN.setNotificationId(2L);
        completeN.setTitle("Bác sĩ đã hoàn thành ca");
        completeN.setIsRead(true);

        SystemNotification callbackN = new SystemNotification();
        callbackN.setNotificationId(3L);
        callbackN.setTitle("Đến hẹn gọi lại bệnh nhân");
        callbackN.setIsRead(false);

        when(notificationRepository.findByUser_UserIdAndNotificationTypeOrderByCreatedAtDesc(
                eq(10L), eq(NotificationType.CUSTOMER_CARE), any(Pageable.class)))
                .thenReturn(List.of(reopenN, completeN, callbackN));
        when(notificationRepository.countByUser_UserIdAndNotificationTypeAndIsReadFalse(10L, NotificationType.CUSTOMER_CARE))
                .thenReturn(2L);

        Map<String, Object> res = notificationService.getUserNotifications(10L, 0);

        ArgumentCaptor<Pageable> cap = ArgumentCaptor.forClass(Pageable.class);
        verify(notificationRepository).findByUser_UserIdAndNotificationTypeOrderByCreatedAtDesc(
                eq(10L), eq(NotificationType.CUSTOMER_CARE), cap.capture());
        assertEquals(1, cap.getValue().getPageSize());

        List<CustomerCareNotificationDTO> items = (List<CustomerCareNotificationDTO>) res.get("notifications");
        assertEquals("TASK_REOPENED", items.get(0).getEventType());
        assertEquals("TASK_COMPLETED", items.get(1).getEventType());
        assertEquals("CALLBACK_DUE", items.get(2).getEventType());
    }

    @Test
    void onRealtimeBroadcast_SkipsBranchTopicsWhenDisabled_AndIgnoresNullOrBlankUsernames() {
        CustomerCareRealtimeEventDTO payload = CustomerCareRealtimeEventDTO.builder()
                .eventType("CUSTOMER_CARE_TASK_UPDATED")
                .branchId(1L)
                .build();
        User blankUsername = new User();
        blankUsername.setUserId(15L);
        blankUsername.setUsername("   ");
        when(userRepository.findById(15L)).thenReturn(Optional.of(blankUsername));

        notificationService.onRealtimeBroadcast(null);
        notificationService.onRealtimeBroadcast(new CustomerCareRealtimeBroadcastEvent(payload, List.of(15L), false));

        verifyNoInteractions(messagingTemplate);
    }
}
