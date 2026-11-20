package com.cvdcms.controller;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.User;
import com.cvdcms.service.CustomerCareNotificationService;
import com.cvdcms.service.CustomerCareService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerCareRestControllerTest {

    @Mock
    private CustomerCareService customerCareService;

    @Mock
    private CustomerCareNotificationService notificationService;

    private CustomerCareRestController controller;
    private MockHttpSession session;
    private User user;

    @BeforeEach
    void setUp() {
        controller = new CustomerCareRestController(customerCareService);
        ReflectionTestUtils.setField(controller, "notificationService", notificationService);

        user = new User();
        user.setUserId(10L);
        user.setUsername("letan01");
        session = new MockHttpSession();
        session.setAttribute("loggedInUser", user);
    }

    @Test
    void requireUser_ThrowsUnauthorized_WhenSessionNullOrMissingUser() {
        ResponseStatusException r1 = assertThrows(ResponseStatusException.class,
                () -> controller.getBranches(null));
        assertEquals(HttpStatus.UNAUTHORIZED, r1.getStatusCode());

        ResponseStatusException r2 = assertThrows(ResponseStatusException.class,
                () -> controller.getSummary(1L, new MockHttpSession()));
        assertEquals(HttpStatus.UNAUTHORIZED, r2.getStatusCode());
    }

    @Test
    void getBranchesLookupsSummaryAndCalendar_DelegateToCustomerCareService() {
        when(customerCareService.getAccessibleBranches(10L)).thenReturn(Map.of("branches", List.of()));
        when(customerCareService.getLookups(1L, 10L)).thenReturn(Map.of("staffs", List.of()));
        when(customerCareService.getSummary(1L, 10L)).thenReturn(Map.of("totalTasks", 5L));
        when(customerCareService.getCalendarTasks(eq(1L), any(), any(), eq(2L), isNull(), isNull(), isNull(), eq(false), eq(10L)))
                .thenReturn(List.of());

        assertTrue(controller.getBranches(session).containsKey("branches"));
        assertTrue(controller.getLookups(1L, session).containsKey("staffs"));
        assertEquals(5L, controller.getSummary(1L, session).get("totalTasks"));
        assertTrue(controller.calendarTasks(1L, LocalDate.now(), LocalDate.now(), 2L, null, null, null, false, session).isEmpty());
    }

    @Test
    void listTasks_NormalizesUnsupportedPageSizeToTenAndClampsNegativePage() {
        Page<CustomerCareTaskDTO> pageResult = new PageImpl<>(List.of());
        when(customerCareService.searchTasks(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(Pageable.class), eq(10L)))
                .thenReturn(pageResult);

        controller.listTasks(1L, "ACTIONABLE", null, null, null, null, null, null,
                null, null, null, false, -3, 33, session);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(customerCareService).searchTasks(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), captor.capture(), eq(10L));
        assertEquals(0, captor.getValue().getPageNumber());
        assertEquals(10, captor.getValue().getPageSize());

        controller.listTasks(1L, "ACTIONABLE", null, null, null, null, null, null,
                null, null, null, false, 1, 25, session);
        verify(customerCareService, times(2)).searchTasks(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), captor.capture(), eq(10L));
        assertEquals(25, captor.getValue().getPageSize());
    }

    @Test
    void notificationEndpoints_HandleAvailableAndNullNotificationService() {
        when(notificationService.getUserNotifications(10L, 30)).thenReturn(Map.of("unreadCount", 2));
        CustomerCareNotificationDTO readDto = CustomerCareNotificationDTO.builder().notificationId(5L).isRead(true).build();
        when(notificationService.markAsRead(5L, 10L)).thenReturn(readDto);
        when(notificationService.markAllAsRead(10L)).thenReturn(Map.of("updatedCount", 2, "unreadCount", 0));

        assertEquals(2, controller.getNotifications(30, session).get("unreadCount"));
        assertTrue(controller.markNotificationRead(5L, session).getIsRead());
        assertEquals(2, controller.markAllNotificationsRead(session).get("updatedCount"));

        ReflectionTestUtils.setField(controller, "notificationService", null);
        assertEquals(0, controller.getNotifications(30, session).get("unreadCount"));
        assertEquals(0, controller.markAllNotificationsRead(session).get("updatedCount"));
        ResponseStatusException unavailable = assertThrows(ResponseStatusException.class,
                () -> controller.markNotificationRead(5L, session));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getStatusCode());
    }

    @Test
    void taskCrudEscalationReopenAndVisitInteraction_DelegateCorrectly() {
        CustomerCareTaskDTO dto = new CustomerCareTaskDTO();
        dto.setTaskId(99L);

        when(customerCareService.getTaskDetail(99L, 10L)).thenReturn(dto);
        when(customerCareService.getVisitExaminationSummary(55L, 10L)).thenReturn(Map.of("visitId", 55L));

        CustomerCareCreateDTO createDto = new CustomerCareCreateDTO();
        when(customerCareService.createTask(createDto, 10L)).thenReturn(dto);
        when(customerCareService.updateTask(99L, createDto, 10L)).thenReturn(dto);
        when(customerCareService.autoGenerateTasks(1L, 10L)).thenReturn(Map.of("createdCount", 3));

        CustomerCareInteractionCreateDTO interactionDto = new CustomerCareInteractionCreateDTO();
        when(customerCareService.recordInteraction(99L, interactionDto, 10L)).thenReturn(dto);
        when(customerCareService.recordVisitInteraction(55L, interactionDto, 10L)).thenReturn(dto);

        CustomerCareEscalateDTO escalateDto = new CustomerCareEscalateDTO();
        when(customerCareService.escalateToDoctor(99L, escalateDto, 10L)).thenReturn(dto);
        when(customerCareService.submitDoctorResponse(99L, escalateDto, 10L)).thenReturn(dto);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        when(customerCareService.reopenTask(99L, "Lý do mở lại", 10L, "10.0.0.5")).thenReturn(dto);

        assertSame(dto, controller.getTaskDetail(99L, session));
        assertEquals(55L, controller.getVisitExaminationSummary(55L, session).get("visitId"));
        assertSame(dto, controller.createTask(createDto, session));
        assertSame(dto, controller.updateTask(99L, createDto, session));
        assertEquals(3, controller.autoGenerateTasks(1L, session).get("createdCount"));
        assertSame(dto, controller.recordInteraction(99L, interactionDto, session));

        ResponseEntity<Map<String, Object>> visitInteractRes = controller.recordVisitInteraction(55L, interactionDto, session);
        assertEquals(HttpStatus.OK, visitInteractRes.getStatusCode());
        assertEquals(true, visitInteractRes.getBody().get("success"));
        assertSame(dto, visitInteractRes.getBody().get("task"));

        assertSame(dto, controller.escalateToDoctor(99L, escalateDto, session));
        assertSame(dto, controller.submitDoctorResponse(99L, escalateDto, session));
        assertSame(dto, controller.reopenTask(99L, Map.of("reason", "Lý do mở lại"), request, session));
    }

    @Test
    void listInteractionsAndPatientCareHistory_ClampSizeAndDelegate() {
        Page<CustomerCareInteractionDTO> page = new PageImpl<>(List.of());
        when(customerCareService.searchInteractions(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(Pageable.class), eq(10L)))
                .thenReturn(page);
        when(customerCareService.getPatientCareHistory(12L, 1L, 10L)).thenReturn(Map.of("totalTasks", 2));

        controller.listInteractions(1L, 12L, "BN", "TODAY", null, null, null, null,
                null, null, null, -1, 500, session);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(customerCareService).searchInteractions(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                captor.capture(), eq(10L));
        assertEquals(0, captor.getValue().getPageNumber());
        assertEquals(100, captor.getValue().getPageSize());

        assertEquals(2, controller.getPatientCareHistory(12L, 1L, session).get("totalTasks"));
    }

    @Test
    void exceptionHandlers_ReturnJsonMessageWithStatus() {
        ResponseEntity<Map<String, String>> bad = controller.handleBadRequest(new IllegalArgumentException("Invalid input"));
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());
        assertEquals("Invalid input", bad.getBody().get("message"));

        ResponseEntity<Map<String, String>> rse = controller.handleResponseStatus(
                new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN, rse.getStatusCode());
        assertEquals("Không thể xử lý yêu cầu CSKH.", rse.getBody().get("message"));
    }

    @Test
    void writeEndpoints_ThrowUnauthorized_WhenSessionMissingUser() {
        MockHttpSession empty = new MockHttpSession();
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> controller.createTask(new CustomerCareCreateDTO(), empty)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> controller.updateTask(1L, new CustomerCareCreateDTO(), empty)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> controller.recordInteraction(1L, new CustomerCareInteractionCreateDTO(), empty)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> controller.escalateToDoctor(1L, new CustomerCareEscalateDTO(), empty)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> controller.submitDoctorResponse(1L, new CustomerCareEscalateDTO(), empty)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> controller.reopenTask(1L, Map.of("reason", "test"), null, empty)).getStatusCode());
    }

    @Test
    void listTasks_PreservesAllowedPageSizesAndForwardsFilters() {
        Page<CustomerCareTaskDTO> emptyPage = new PageImpl<>(List.of());
        when(customerCareService.searchTasks(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(Pageable.class), eq(10L)))
                .thenReturn(emptyPage);

        for (int allowedSize : List.of(10, 25, 50, 100)) {
            controller.listTasks(1L, "TODAY", "PENDING", 2L, 3L, null, null, "kw",
                    "TODAY", "POST_TREATMENT", "WAITING", true, 0, allowedSize, session);
        }

        ArgumentCaptor<Pageable> cap = ArgumentCaptor.forClass(Pageable.class);
        verify(customerCareService, times(4)).searchTasks(
                eq(1L), eq("TODAY"), eq("PENDING"), eq(2L), eq(3L), isNull(), isNull(), eq("kw"),
                eq("TODAY"), eq("POST_TREATMENT"), eq("WAITING"), eq(true), cap.capture(), eq(10L));
        List<Integer> capturedSizes = cap.getAllValues().stream().map(Pageable::getPageSize).toList();
        assertEquals(List.of(10, 25, 50, 100), capturedSizes);
    }

    @Test
    void reopenTask_HandlesNullBodyAndNullServletRequest() {
        CustomerCareTaskDTO reopened = new CustomerCareTaskDTO();
        reopened.setTaskId(77L);
        when(customerCareService.reopenTask(77L, null, 10L, null)).thenReturn(reopened);

        assertSame(reopened, controller.reopenTask(77L, null, null, session));
    }

    @Test
    void handleResponseStatus_PreservesExplicitReason() {
        ResponseEntity<Map<String, String>> rse = controller.handleResponseStatus(
                new ResponseStatusException(HttpStatus.CONFLICT, "Đã hoàn thành"));
        assertEquals(HttpStatus.CONFLICT, rse.getStatusCode());
        assertEquals("Đã hoàn thành", rse.getBody().get("message"));
    }
}
