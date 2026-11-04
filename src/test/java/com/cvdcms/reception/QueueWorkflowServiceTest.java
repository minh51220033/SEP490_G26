package com.cvdcms.reception;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.service.ClinicalExaminationBootstrapService;
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
    @Mock ClinicalExaminationBootstrapService examinations;
    @Mock HttpSession session;
    QueueWorkflowService service;
    Branch branch;
    Visit visit;
    QueueTicket ticket;

    @BeforeEach void setup() {
        service = new QueueWorkflowService(access, branches, tickets, visits, rooms, chairs, audits, examinations);
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
        when(tickets.countOtherForDoctor(eq(1L), any(), eq(QueueType.EXAM), eq(2L), eq(QueueStatus.CALLED), eq(11L))).thenReturn(1L);
        assertThrows(ResponseStatusException.class, () -> service.transition(session, 11L, QueueWorkflowService.Action.CALL, null));
        verify(tickets, never()).save(any());
    }

    @Test void pauseAndResumeDoNotChangeVisit() {
        permitted();
        service.transition(session, 11L, QueueWorkflowService.Action.PAUSE, "Bệnh nhân tạm ra ngoài");
        assertEquals(QueueStatus.PAUSED, ticket.getStatus());
        assertEquals("Bệnh nhân tạm ra ngoài", ticket.getPauseReason());
        assertNotNull(ticket.getPausedAt());
        service.transition(session, 11L, QueueWorkflowService.Action.RESUME, null);
        assertEquals(QueueStatus.WAITING, ticket.getStatus());
        assertNotNull(ticket.getResumedAt());
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

    @Test void emergencyResumesPausedTicketWithoutChangingNumber() {
        permitted(); ticket.setStatus(QueueStatus.PAUSED); ticket.setDisplayNumber("05");
        service.transition(session, 11L, QueueWorkflowService.Action.EMERGENCY, "Chảy máu liên tục");
        assertEquals(QueueStatus.WAITING, ticket.getStatus());
        assertEquals("05", ticket.getDisplayNumber());
        assertEquals(100, ticket.getPriorityScore());
    }

    @Test void priorityChangesOrderButNotPrintedNumber() {
        permitted(); ticket.setDisplayNumber("05"); ticket.setIsPriority(false);
        service.transition(session, 11L, QueueWorkflowService.Action.PRIORITIZE, "Bệnh nhân cao tuổi cần hỗ trợ");
        assertEquals(50, ticket.getPriorityScore());
        assertFalse(ticket.getIsPriority());
        assertEquals("05", ticket.getDisplayNumber());
    }

    @Test void callNextSelectsHighestPriorityWithinDoctorQueue() {
        User user = new User(); user.setUserId(9L);
        when(access.require(session)).thenReturn(new ReceptionAccess.Scope(user, false, List.of(1L)));
        when(branches.lock(1L)).thenReturn(Optional.of(branch));
        StaffProfile doctor = new StaffProfile(); doctor.setStaffId(2L);
        StaffProfile anotherDoctor = new StaffProfile(); anotherDoctor.setStaffId(3L);
        visit.setPrimaryDoctor(doctor);
        ticket.setTicketNumber(1); ticket.setPriorityScore(0);
        QueueTicket urgent = new QueueTicket(); urgent.setTicketId(12L); urgent.setVisit(visit);
        urgent.setBranch(branch); urgent.setStatus(QueueStatus.WAITING); urgent.setPriorityScore(100);
        urgent.setTicketNumber(2); urgent.setQueueType(QueueType.EXAM);
        urgent.setQueueDate(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")));
        Visit otherVisit = new Visit(); otherVisit.setPrimaryDoctor(anotherDoctor); otherVisit.setStatus(VisitStatus.WAITING);
        QueueTicket other = new QueueTicket(); other.setTicketId(13L); other.setVisit(otherVisit);
        other.setStatus(QueueStatus.CALLED); other.setPriorityScore(100); other.setTicketNumber(1);
        when(tickets.forBoard(eq(1L), any(), eq(QueueType.EXAM))).thenReturn(List.of(ticket, other, urgent));
        when(tickets.lock(12L)).thenReturn(Optional.of(urgent));
        when(visits.lock(7L)).thenReturn(Optional.of(visit));

        assertSame(urgent, service.callNext(session, 1L, 2L));
        assertEquals(QueueStatus.CALLED, urgent.getStatus());
        assertEquals(QueueStatus.CALLED, other.getStatus());
        verify(tickets).save(urgent);
    }

    @Test void directCallCannotSkipPriorityTicket() {
        permitted();
        StaffProfile doctor = new StaffProfile(); doctor.setStaffId(2L); visit.setPrimaryDoctor(doctor);
        ticket.setTicketNumber(1); ticket.setPriorityScore(0);
        QueueTicket urgent = new QueueTicket(); urgent.setTicketId(12L); urgent.setVisit(visit);
        urgent.setStatus(QueueStatus.WAITING); urgent.setPriorityScore(100); urgent.setTicketNumber(2);
        when(tickets.forBoard(eq(1L), any(), eq(QueueType.EXAM))).thenReturn(List.of(ticket, urgent));

        assertThrows(ResponseStatusException.class,
                () -> service.transition(session, 11L, QueueWorkflowService.Action.CALL, null));
        assertEquals(QueueStatus.WAITING, ticket.getStatus());
        verify(tickets, never()).save(any());
    }

    @Test void directCallInOrderSucceedsWithoutReason() {
        permitted();
        StaffProfile doctor = new StaffProfile(); doctor.setStaffId(2L); visit.setPrimaryDoctor(doctor);
        ticket.setTicketNumber(1); ticket.setPriorityScore(0);
        when(tickets.countOtherForDoctor(eq(1L), any(), eq(QueueType.EXAM), eq(2L), eq(QueueStatus.CALLED), eq(11L))).thenReturn(0L);
        when(tickets.forBoard(eq(1L), any(), eq(QueueType.EXAM))).thenReturn(List.of(ticket));

        service.transition(session, 11L, QueueWorkflowService.Action.CALL, null);
        assertEquals(QueueStatus.CALLED, ticket.getStatus());
        assertNotNull(ticket.getCalledAt());
        verify(tickets).save(ticket);
    }

    @Test void directCallOutOfOrderRejectsWithoutPermission() {
        User user = new User(); user.setUserId(9L);
        when(access.require(session)).thenReturn(new ReceptionAccess.Scope(user, false, false, List.of(1L)));
        when(branches.lock(1L)).thenReturn(Optional.of(branch));
        when(tickets.findById(11L)).thenReturn(Optional.of(ticket));
        when(tickets.lock(11L)).thenReturn(Optional.of(ticket));
        when(visits.lock(7L)).thenReturn(Optional.of(visit));

        StaffProfile doctor = new StaffProfile(); doctor.setStaffId(2L); visit.setPrimaryDoctor(doctor);
        ticket.setTicketNumber(2); ticket.setPriorityScore(0);
        QueueTicket earlier = new QueueTicket(); earlier.setTicketId(12L); earlier.setVisit(visit);
        earlier.setStatus(QueueStatus.WAITING); earlier.setPriorityScore(0); earlier.setTicketNumber(1);
        when(tickets.countOtherForDoctor(eq(1L), any(), eq(QueueType.EXAM), eq(2L), eq(QueueStatus.CALLED), eq(11L))).thenReturn(0L);
        when(tickets.forBoard(eq(1L), any(), eq(QueueType.EXAM))).thenReturn(List.of(earlier, ticket));

        var ex = assertThrows(ResponseStatusException.class,
                () -> service.transition(session, 11L, QueueWorkflowService.Action.CALL, "Bác sĩ gọi ưu tiên ca này"));
        assertTrue(ex.getReason().contains("quyền điều phối"));
        assertEquals(QueueStatus.WAITING, ticket.getStatus());
        verify(tickets, never()).save(any());
    }

    @Test void directCallOutOfOrderRejectsWithoutValidReason() {
        User user = new User(); user.setUserId(9L);
        when(access.require(session)).thenReturn(new ReceptionAccess.Scope(user, true, true, List.of(1L)));
        when(branches.lock(1L)).thenReturn(Optional.of(branch));
        when(tickets.findById(11L)).thenReturn(Optional.of(ticket));
        when(tickets.lock(11L)).thenReturn(Optional.of(ticket));
        when(visits.lock(7L)).thenReturn(Optional.of(visit));

        StaffProfile doctor = new StaffProfile(); doctor.setStaffId(2L); visit.setPrimaryDoctor(doctor);
        ticket.setTicketNumber(2); ticket.setPriorityScore(0);
        QueueTicket earlier = new QueueTicket(); earlier.setTicketId(12L); earlier.setVisit(visit);
        earlier.setStatus(QueueStatus.WAITING); earlier.setPriorityScore(0); earlier.setTicketNumber(1);
        when(tickets.countOtherForDoctor(eq(1L), any(), eq(QueueType.EXAM), eq(2L), eq(QueueStatus.CALLED), eq(11L))).thenReturn(0L);
        when(tickets.forBoard(eq(1L), any(), eq(QueueType.EXAM))).thenReturn(List.of(earlier, ticket));

        var ex = assertThrows(ResponseStatusException.class,
                () -> service.transition(session, 11L, QueueWorkflowService.Action.CALL, "ngắn"));
        assertTrue(ex.getReason().contains("10 đến 255 ký tự"));
        assertEquals(QueueStatus.WAITING, ticket.getStatus());
    }

    @Test void directCallOutOfOrderSucceedsWithPermissionAndValidReason() {
        User user = new User(); user.setUserId(9L);
        when(access.require(session)).thenReturn(new ReceptionAccess.Scope(user, true, true, List.of(1L)));
        when(branches.lock(1L)).thenReturn(Optional.of(branch));
        when(tickets.findById(11L)).thenReturn(Optional.of(ticket));
        when(tickets.lock(11L)).thenReturn(Optional.of(ticket));
        when(visits.lock(7L)).thenReturn(Optional.of(visit));

        StaffProfile doctor = new StaffProfile(); doctor.setStaffId(2L); visit.setPrimaryDoctor(doctor);
        ticket.setTicketNumber(2); ticket.setPriorityScore(0);
        QueueTicket earlier = new QueueTicket(); earlier.setTicketId(12L); earlier.setVisit(visit);
        earlier.setStatus(QueueStatus.WAITING); earlier.setPriorityScore(0); earlier.setTicketNumber(1);
        when(tickets.countOtherForDoctor(eq(1L), any(), eq(QueueType.EXAM), eq(2L), eq(QueueStatus.CALLED), eq(11L))).thenReturn(0L);
        when(tickets.forBoard(eq(1L), any(), eq(QueueType.EXAM))).thenReturn(List.of(earlier, ticket));

        service.transition(session, 11L, QueueWorkflowService.Action.CALL, "Bệnh nhân có hẹn làm thủ thuật trước");
        assertEquals(QueueStatus.CALLED, ticket.getStatus());
        assertNotNull(ticket.getCalledAt());
        verify(tickets).save(ticket);
        verify(audits).save(argThat(log -> log.getAction().equals("QUEUE_CALL")
                && log.getNewValuesJson().contains("Bệnh nhân có hẹn làm thủ thuật trước")));
    }

    @Test void startRequiresAssignedRoomAndChair() {
        permitted(); ticket.setStatus(QueueStatus.CALLED);
        StaffProfile doctor = new StaffProfile(); doctor.setStaffId(2L); visit.setPrimaryDoctor(doctor);
        assertThrows(ResponseStatusException.class,
                () -> service.transition(session, 11L, QueueWorkflowService.Action.START, null));
        assertEquals(VisitStatus.WAITING, visit.getStatus());
    }

    @Test void startRejectsIfChairIsOccupied() {
        permitted();
        ticket.setStatus(QueueStatus.CALLED);
        StaffProfile doctor = new StaffProfile(); doctor.setStaffId(2L); visit.setPrimaryDoctor(doctor);
        visit.setRoomId(3L); visit.setChairId(4L);
        Room room = new Room(); room.setRoomId(3L); room.setBranch(branch); room.setStatus(RoomStatus.ACTIVE);
        DentalChair chair = new DentalChair(); chair.setChairId(4L); chair.setBranch(branch); chair.setRoom(room);
        chair.setStatus(ChairStatus.AVAILABLE);
        when(rooms.findById(3L)).thenReturn(Optional.of(room));
        when(chairs.findById(4L)).thenReturn(Optional.of(chair));
        when(visits.occupiedChair(eq(4L), eq(7L), anySet())).thenReturn(1L);

        var ex = assertThrows(ResponseStatusException.class,
                () -> service.transition(session, 11L, QueueWorkflowService.Action.START, null));
        assertTrue(ex.getReason().contains("Ghế đang có ca khám khác"));
        assertEquals(VisitStatus.WAITING, visit.getStatus());
        assertEquals(QueueStatus.CALLED, ticket.getStatus());
    }

    @Test void startAllowsSwitchingToAvailableChairIfOccupied() {
        permitted();
        ticket.setStatus(QueueStatus.CALLED);
        StaffProfile doctor = new StaffProfile(); doctor.setStaffId(2L); visit.setPrimaryDoctor(doctor);
        visit.setRoomId(3L); visit.setChairId(4L);
        Room room = new Room(); room.setRoomId(3L); room.setBranch(branch); room.setStatus(RoomStatus.ACTIVE);
        DentalChair chairAlt = new DentalChair(); chairAlt.setChairId(5L); chairAlt.setBranch(branch); chairAlt.setRoom(room);
        chairAlt.setStatus(ChairStatus.AVAILABLE);
        when(rooms.findById(3L)).thenReturn(Optional.of(room));
        when(chairs.findById(5L)).thenReturn(Optional.of(chairAlt));
        when(visits.occupiedChair(eq(5L), eq(7L), anySet())).thenReturn(0L);

        service.transition(session, 11L, QueueWorkflowService.Action.START, 5L, null);
        assertEquals(VisitStatus.IN_EXAM, visit.getStatus());
        assertEquals(5L, visit.getChairId());
        assertEquals(ChairStatus.IN_USE, chairAlt.getStatus());
        assertEquals(QueueStatus.IN_SERVICE, ticket.getStatus());
        verify(chairs).save(chairAlt);
        verify(visits).save(visit);
    }

    @Test void doneWaitsUntilPatientLeavesTreatmentChair() {
        permitted(); ticket.setStatus(QueueStatus.IN_SERVICE); visit.setStatus(VisitStatus.IN_TREATMENT);
        DentalChair chair = new DentalChair(); chair.setChairId(4L); chair.setStatus(ChairStatus.IN_USE);
        visit.setChairId(4L);

        assertThrows(ResponseStatusException.class,
                () -> service.transition(session, 11L, QueueWorkflowService.Action.DONE, null));
        assertEquals(ChairStatus.IN_USE, chair.getStatus());

        visit.setStatus(VisitStatus.WAITING_PAYMENT);
        when(chairs.findById(4L)).thenReturn(Optional.of(chair));
        service.transition(session, 11L, QueueWorkflowService.Action.DONE, null);
        assertEquals(QueueStatus.DONE, ticket.getStatus());
        assertEquals(ChairStatus.CLEANING, chair.getStatus());
    }

    @Test void pauseRequiresReason() {
        permitted();
        assertThrows(ResponseStatusException.class,
                () -> service.transition(session, 11L, QueueWorkflowService.Action.PAUSE, "ngắn"));
        assertEquals(QueueStatus.WAITING, ticket.getStatus());
    }

    @Test void resumedPatientPreservesInitialPositionWhenClinicReason() {
        permitted();
        service.transition(session, 11L, QueueWorkflowService.Action.PAUSE, "Bác sĩ bận hội chẩn ca khẩn cấp");
        assertEquals(QueueStatus.PAUSED, ticket.getStatus());
        service.transition(session, 11L, QueueWorkflowService.Action.RESUME, null);
        assertEquals(QueueStatus.WAITING, ticket.getStatus());
        assertNull(ticket.getResumedAt());
    }

    @Test void resumedPatientRejoinsBehindEarlierWaitingPatientInSamePriority() {
        User user = new User(); user.setUserId(9L);
        when(access.require(session)).thenReturn(new ReceptionAccess.Scope(user, false, List.of(1L)));
        when(branches.lock(1L)).thenReturn(Optional.of(branch));
        StaffProfile doctor = new StaffProfile(); doctor.setStaffId(2L); visit.setPrimaryDoctor(doctor);
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        ticket.setTicketNumber(1); ticket.setPriorityScore(0);
        ticket.setIssuedAt(now.minusMinutes(30)); ticket.setResumedAt(now);
        QueueTicket waiting = new QueueTicket(); waiting.setTicketId(12L); waiting.setBranch(branch);
        waiting.setVisit(visit); waiting.setQueueType(QueueType.EXAM);
        waiting.setQueueDate(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")));
        waiting.setStatus(QueueStatus.WAITING); waiting.setPriorityScore(0);
        waiting.setTicketNumber(2); waiting.setIssuedAt(now.minusMinutes(10));
        when(tickets.forBoard(eq(1L), any(), eq(QueueType.EXAM))).thenReturn(List.of(ticket, waiting));
        when(tickets.lock(12L)).thenReturn(Optional.of(waiting));
        when(visits.lock(7L)).thenReturn(Optional.of(visit));

        assertSame(waiting, service.callNext(session, 1L, 2L));
        assertEquals(QueueStatus.WAITING, ticket.getStatus());
    }
}
