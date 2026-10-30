package com.cvdcms.reception;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueueWorkflowServiceTest {
    @Mock ReceptionAccess access;
    @Mock DispatchBranchRepository branches;
    @Mock QueueWorkflowRepository tickets;
    @Mock DispatchVisitRepository visits;
    @Mock DispatchRoomRepository rooms;
    @Mock DispatchChairRepository chairs;
    @Mock ReceptionAuditRepository audits;
    @Mock HttpSession session;
    QueueWorkflowService service;
    Branch branch;
    Visit visit;
    QueueTicket ticket;

    @BeforeEach void setup() {
        service = new QueueWorkflowService(access, branches, tickets, visits, rooms, chairs, audits);
        branch = new Branch(); branch.setBranchId(1L); branch.setIsActive(true);
        visit = new Visit(); visit.setVisitId(7L); visit.setBranch(branch); visit.setStatus(VisitStatus.WAITING);
        ticket = new QueueTicket(); ticket.setTicketId(11L); ticket.setBranch(branch); ticket.setVisit(visit);
        ticket.setQueueDate(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))); ticket.setQueueType(QueueType.EXAM);
        ticket.setStatus(QueueStatus.WAITING);
    }
    private void permitted() {
        User user = new User(); user.setUserId(9L);
        when(access.require(session)).thenReturn(new ReceptionAccess.Scope(user, false, List.of(1L)));
        when(branches.lock(1L)).thenReturn(Optional.of(branch));
        when(tickets.findById(11L)).thenReturn(Optional.of(ticket));
        when(tickets.lock(11L)).thenReturn(Optional.of(ticket));
        when(visits.lock(7L)).thenReturn(Optional.of(visit));
    }

    @Test void callRequiresDoctorAndOnlyOneActiveCall() {
        permitted();
        assertThrows(ResponseStatusException.class, () -> service.transition(session, 11L, QueueWorkflowService.Action.CALL, null));
        StaffProfile doctor = new StaffProfile(); doctor.setStaffId(2L); visit.setPrimaryDoctor(doctor);
        when(tickets.countOther(eq(1L), any(), eq(QueueType.EXAM), eq(QueueStatus.CALLED), eq(11L))).thenReturn(1L);
        assertThrows(ResponseStatusException.class, () -> service.transition(session, 11L, QueueWorkflowService.Action.CALL, null));
        verify(tickets, never()).save(any());
    }

    @Test void pauseAndResumeDoNotChangeVisit() {
        permitted();
        service.transition(session, 11L, QueueWorkflowService.Action.PAUSE, null);
        assertEquals(QueueStatus.PAUSED, ticket.getStatus());
        service.transition(session, 11L, QueueWorkflowService.Action.RESUME, null);
        assertEquals(QueueStatus.WAITING, ticket.getStatus());
        assertEquals(VisitStatus.WAITING, visit.getStatus());
    }

    @Test void startMovesVisitIntoExamAndChairIntoUse() {
        permitted();
        ticket.setStatus(QueueStatus.CALLED);
        StaffProfile doctor = new StaffProfile(); doctor.setStaffId(2L); visit.setPrimaryDoctor(doctor);
        visit.setRoomId(3L); visit.setChairId(4L);
        Room room = new Room(); room.setRoomId(3L); room.setBranch(branch); room.setStatus(RoomStatus.ACTIVE);
        DentalChair chair = new DentalChair(); chair.setChairId(4L); chair.setBranch(branch); chair.setRoom(room);
        chair.setStatus(ChairStatus.AVAILABLE);
        when(rooms.findById(3L)).thenReturn(Optional.of(room));
        when(chairs.findById(4L)).thenReturn(Optional.of(chair));
        service.transition(session, 11L, QueueWorkflowService.Action.START, null);
        assertEquals(VisitStatus.IN_EXAM, visit.getStatus());
        assertEquals(QueueStatus.IN_SERVICE, ticket.getStatus());
        assertEquals(ChairStatus.IN_USE, chair.getStatus());
    }

    @Test void emergencyNeedsReasonAndKeepsIssuedNumber() {
        permitted(); ticket.setDisplayNumber("05"); ticket.setPriorityScore(0);
        assertThrows(ResponseStatusException.class, () -> service.transition(session, 11L, QueueWorkflowService.Action.EMERGENCY, "Đau"));
        service.transition(session, 11L, QueueWorkflowService.Action.EMERGENCY, "Chảy máu liên tục");
        assertEquals("05", ticket.getDisplayNumber());
        assertEquals(100, ticket.getPriorityScore());
        assertTrue(ticket.getIsEmergency());
    }
}
