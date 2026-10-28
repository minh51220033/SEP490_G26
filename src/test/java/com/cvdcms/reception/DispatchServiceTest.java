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
class DispatchServiceTest {
    @Mock ReceptionAccess access;
    @Mock DispatchVisitRepository visits;
    @Mock DispatchDoctorRepository doctors;
    @Mock DispatchScheduleRepository schedules;
    @Mock DispatchRoomRepository rooms;
    @Mock DispatchChairRepository chairs;
    @Mock DispatchTicketRepository tickets;
    @Mock DispatchBranchRepository branches;
    @Mock ReceptionAuditRepository audits;
    @Mock HttpSession session;
    DispatchService service;
    Branch branch;
    Visit visit;
    StaffProfile doctor;

    @BeforeEach void setup() {
        service = new DispatchService(access, visits, doctors, schedules, rooms, chairs, tickets, branches, audits);
        branch = new Branch(); branch.setBranchId(1L); branch.setIsActive(true);
        doctor = new StaffProfile(); doctor.setStaffId(3L);
        visit = new Visit(); visit.setVisitId(7L); visit.setBranch(branch); visit.setStatus(VisitStatus.WAITING);
        visit.setCheckInAt(LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"))); visit.setVisitType(VisitType.WALK_IN);
    }
    private void permitted() {
        User user = new User(); user.setUserId(4L);
        when(access.require(session)).thenReturn(new ReceptionAccess.Scope(user, false, List.of(1L)));
        when(branches.lock(1L)).thenReturn(Optional.of(branch));
        when(visits.lock(7L)).thenReturn(Optional.of(visit));
    }

    @Test void assignmentDoesNotReserveChair() {
        permitted();
        when(doctors.eligible(eq(1L), any())).thenReturn(List.of(doctor));
        when(schedules.working(eq(3L), eq(1L), any(), any())).thenReturn(1L);
        when(doctors.findById(3L)).thenReturn(Optional.of(doctor));
        Room room = new Room(); room.setRoomId(5L); room.setBranch(branch); room.setStatus(RoomStatus.ACTIVE);
        DentalChair chair = new DentalChair(); chair.setChairId(6L); chair.setBranch(branch); chair.setRoom(room); chair.setStatus(ChairStatus.AVAILABLE);
        when(chairs.findById(6L)).thenReturn(Optional.of(chair));
        when(rooms.findById(5L)).thenReturn(Optional.of(room));
        service.assign(session, 1L, 7L, 3L, 5L, 6L);
        assertEquals(VisitStatus.WAITING, visit.getStatus());
        assertEquals(ChairStatus.AVAILABLE, chair.getStatus());
        assertEquals(6L, visit.getChairId());
        verify(visits).save(visit);
    }

    @Test void doctorWithoutCurrentScheduleIsRejected() {
        permitted();
        when(doctors.eligible(eq(1L), any())).thenReturn(List.of(doctor));
        assertThrows(ResponseStatusException.class, () -> service.assign(session, 1L, 7L, 3L, null, null));
        verify(visits, never()).save(any());
    }

    @Test void priorityTicketRequiresReasonAndNextNumber() {
        permitted(); visit.setPrimaryDoctor(doctor);
        when(tickets.findByVisitVisitIdAndQueueType(7L, QueueType.EXAM)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> service.issue(session, 1L, 7L, true, "Đau"));
        when(tickets.maxNumber(eq(1L), any(), eq(QueueType.EXAM), eq(true))).thenReturn(1);
        QueueTicket ticket = service.issue(session, 1L, 7L, true, "Hạn chế vận động");
        assertEquals("P-02", ticket.getDisplayNumber());
        assertEquals("Hạn chế vận động", ticket.getPriorityReason());
        assertEquals(QueueStatus.WAITING, ticket.getStatus());
        verify(tickets).saveAndFlush(ticket);
    }

    @Test void existingTicketIsReturnedWithoutSecondNumber() {
        permitted(); visit.setPrimaryDoctor(doctor);
        QueueTicket old = new QueueTicket(); old.setTicketId(9L);
        when(tickets.findByVisitVisitIdAndQueueType(7L, QueueType.EXAM)).thenReturn(Optional.of(old));
        assertSame(old, service.issue(session, 1L, 7L, false, null));
        verify(tickets, never()).saveAndFlush(any());
    }
}
