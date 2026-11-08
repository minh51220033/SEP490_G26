package com.cvdcms.reception;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.AppointmentRepository;
import com.cvdcms.service.ClinicalExaminationBootstrapService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
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
    @Mock AppointmentRepository appointments;
    @Mock ClinicalExaminationBootstrapService examinations;
    @Mock HttpSession session;
    DispatchService service;
    Branch branch;
    Visit visit;
    StaffProfile doctor;

    @BeforeEach void setup() {
        service = new DispatchService(access, visits, doctors, schedules, rooms, chairs, tickets, branches, audits, appointments, examinations);
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
        service.assign(session, 1L, 7L, 3L, 5L, 6L, null);
        assertEquals(VisitStatus.WAITING, visit.getStatus());
        assertEquals(ChairStatus.AVAILABLE, chair.getStatus());
        assertEquals(6L, visit.getChairId());
        verify(visits).save(visit);
    }

    @Test void assignmentRejectsChairAlreadyUsedByAnotherActiveVisit() {
        permitted();
        when(doctors.eligible(eq(1L), any())).thenReturn(List.of(doctor));
        when(schedules.working(eq(3L), eq(1L), any(), any())).thenReturn(1L);
        when(doctors.findById(3L)).thenReturn(Optional.of(doctor));
        Room room = new Room(); room.setRoomId(5L); room.setBranch(branch); room.setStatus(RoomStatus.ACTIVE);
        DentalChair chair = new DentalChair(); chair.setChairId(6L); chair.setBranch(branch); chair.setRoom(room); chair.setStatus(ChairStatus.AVAILABLE);
        when(chairs.findById(6L)).thenReturn(Optional.of(chair));
        when(visits.occupiedChair(6L, 7L, Set.of(VisitStatus.IN_EXAM, VisitStatus.IN_TREATMENT))).thenReturn(1L);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.assign(session, 1L, 7L, 3L, 5L, 6L, null));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Ghế đang được sử dụng"));
        verify(visits, never()).save(any());
    }

    @Test void assignmentRejectsChairFromAnotherBranch() {
        permitted();
        when(doctors.eligible(eq(1L), any())).thenReturn(List.of(doctor));
        when(schedules.working(eq(3L), eq(1L), any(), any())).thenReturn(1L);
        when(doctors.findById(3L)).thenReturn(Optional.of(doctor));
        Branch otherBranch = new Branch(); otherBranch.setBranchId(2L); otherBranch.setIsActive(true);
        Room foreignRoom = new Room(); foreignRoom.setRoomId(8L); foreignRoom.setBranch(otherBranch); foreignRoom.setStatus(RoomStatus.ACTIVE);
        DentalChair foreignChair = new DentalChair(); foreignChair.setChairId(6L); foreignChair.setBranch(otherBranch);
        foreignChair.setRoom(foreignRoom); foreignChair.setStatus(ChairStatus.AVAILABLE);
        when(chairs.findById(6L)).thenReturn(Optional.of(foreignChair));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.assign(session, 1L, 7L, 3L, 8L, 6L, null));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("thuộc phòng tại chi nhánh"));
        verify(visits, never()).save(any());
    }

    @Test void doctorWithoutCurrentScheduleIsRejected() {
        permitted();
        when(doctors.eligible(eq(1L), any())).thenReturn(List.of(doctor));
        assertThrows(ResponseStatusException.class, () -> service.assign(session, 1L, 7L, 3L, null, null, null));
        verify(visits, never()).save(any());
    }

    @Test void autoAssignmentChoosesLeastWaitingDoctor() {
        permitted();
        StaffProfile quieter = new StaffProfile(); quieter.setStaffId(5L);
        when(doctors.eligible(eq(1L), any())).thenReturn(List.of(doctor, quieter));
        when(schedules.working(anyLong(), eq(1L), any(), any())).thenReturn(1L);
        when(visits.doctorLoad(eq(3L), eq(1L), any(), any(), eq(7L))).thenReturn(4L);
        when(visits.doctorLoad(eq(5L), eq(1L), any(), any(), eq(7L))).thenReturn(1L);
        when(doctors.findById(5L)).thenReturn(Optional.of(quieter));

        service.assign(session, 1L, 7L, null, null, null, null);

        assertSame(quieter, visit.getPrimaryDoctor());
        verify(visits).save(visit);
    }

    @Test void namedDoctorIsKeptAndOverrideRequiresReason() {
        permitted();
        PatientProfile patient = new PatientProfile(); patient.setProfileId(11L); visit.setPatientProfile(patient);
        Appointment appointment = new Appointment(); appointment.setAppointmentId(12L);
        appointment.setBranch(branch); appointment.setPatientProfile(patient); appointment.setDoctor(doctor);
        visit.setAppointmentId(12L);
        StaffProfile other = new StaffProfile(); other.setStaffId(5L);
        when(appointments.findById(12L)).thenReturn(Optional.of(appointment));
        when(doctors.eligible(eq(1L), any())).thenReturn(List.of(doctor, other));
        when(schedules.working(anyLong(), eq(1L), any(), any())).thenReturn(1L);

        assertThrows(ResponseStatusException.class, () -> service.assign(session, 1L, 7L, 5L, null, null, null));
        verify(visits, never()).save(any());

        when(doctors.findById(3L)).thenReturn(Optional.of(doctor));
        service.assign(session, 1L, 7L, null, null, null, null);
        assertSame(doctor, visit.getPrimaryDoctor());
    }

    @Test void transferKeepsExistingTicketAndRecordsReason() {
        permitted(); visit.setPrimaryDoctor(doctor);
        StaffProfile other = new StaffProfile(); other.setStaffId(5L);
        QueueTicket ticket = new QueueTicket(); ticket.setStatus(QueueStatus.WAITING); ticket.setDisplayNumber("P-03");
        when(doctors.eligible(eq(1L), any())).thenReturn(List.of(doctor, other));
        when(schedules.working(anyLong(), eq(1L), any(), any())).thenReturn(1L);
        when(doctors.findById(5L)).thenReturn(Optional.of(other));
        when(tickets.findByVisitVisitIdAndQueueType(7L, QueueType.EXAM)).thenReturn(Optional.of(ticket));

        assertThrows(ResponseStatusException.class, () -> service.assign(session, 1L, 7L, 5L, null, null, "ngắn"));
        service.assign(session, 1L, 7L, 5L, null, null, "Bệnh nhân đồng ý đổi bác sĩ");

        assertSame(other, visit.getPrimaryDoctor());
        assertEquals("P-03", ticket.getDisplayNumber());
        verify(tickets, never()).save(any());
        verify(audits).save(argThat(log -> "TRANSFER_DOCTOR_QUEUE".equals(log.getAction())
                && log.getNewValuesJson().contains("Bệnh nhân đồng ý đổi bác sĩ")));
    }

    @Test void cannotTransferAfterTicketWasCalled() {
        permitted(); visit.setPrimaryDoctor(doctor);
        StaffProfile other = new StaffProfile(); other.setStaffId(5L);
        QueueTicket ticket = new QueueTicket(); ticket.setStatus(QueueStatus.CALLED);
        when(doctors.eligible(eq(1L), any())).thenReturn(List.of(doctor, other));
        when(schedules.working(anyLong(), eq(1L), any(), any())).thenReturn(1L);
        when(tickets.findByVisitVisitIdAndQueueType(7L, QueueType.EXAM)).thenReturn(Optional.of(ticket));

        assertThrows(ResponseStatusException.class,
                () -> service.assign(session, 1L, 7L, 5L, null, null, "Bệnh nhân đồng ý đổi bác sĩ"));
        assertSame(doctor, visit.getPrimaryDoctor());
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

    @Test void assignWithNoDoctorsWorkingThrowsConflict() {
        permitted();
        when(doctors.eligible(eq(1L), any())).thenReturn(List.of(doctor));
        when(schedules.working(eq(3L), eq(1L), any(), any())).thenReturn(0L);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.assign(session, 1L, 7L, null, null, null, null));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("không có bác sĩ nào đang trong ca trực"));
        verify(visits, never()).save(any());
    }

    @Test void boardExcludesDoctorsWithoutWorkingSchedule() {
        User user = new User(); user.setUserId(4L);
        when(access.require(session)).thenReturn(new ReceptionAccess.Scope(user, false, List.of(1L)));
        when(branches.findAll()).thenReturn(List.of(branch));
        when(branches.findById(1L)).thenReturn(Optional.of(branch));
        when(doctors.eligible(eq(1L), any())).thenReturn(List.of(doctor));
        when(schedules.working(eq(3L), eq(1L), any(), any())).thenReturn(0L);

        DispatchService.Board board = service.board(session, 1L);
        assertTrue(board.doctors().isEmpty(), "Bác sĩ không có ca làm việc không được hiển thị trên bảng điều phối");
    }

    @Test
    void testCompleteDispatchAndIssueTicket_Success() {
        permitted();
        when(visits.findById(7L)).thenReturn(Optional.of(visit));
        when(doctors.eligible(eq(1L), any())).thenReturn(List.of(doctor));
        when(schedules.working(eq(3L), eq(1L), any(), any())).thenReturn(1L);
        when(doctors.findById(3L)).thenReturn(Optional.of(doctor));

        Room room = new Room();
        room.setRoomId(5L);
        room.setBranch(branch);
        room.setRoomName("Phòng 101");
        room.setStatus(RoomStatus.ACTIVE);

        DentalChair chair = new DentalChair();
        chair.setChairId(6L);
        chair.setBranch(branch);
        chair.setRoom(room);
        chair.setChairName("Ghế 01");
        chair.setStatus(ChairStatus.AVAILABLE);

        when(chairs.findById(6L)).thenReturn(Optional.of(chair));
        when(rooms.findById(5L)).thenReturn(Optional.of(room));
        when(tickets.findByVisitVisitIdAndQueueType(7L, QueueType.EXAM)).thenReturn(Optional.empty());
        when(tickets.maxNumber(eq(1L), any(), eq(QueueType.EXAM), eq(false))).thenReturn(0);

        QueueTicket issued = service.completeDispatchAndIssueTicket(session, 1L, 7L, 3L, 5L, 6L, false, null, "Bệnh nhân cần khám nhẹ nhàng");

        assertNotNull(issued);
        assertSame(doctor, visit.getPrimaryDoctor());
        assertEquals(5L, visit.getRoomId());
        assertEquals(6L, visit.getChairId());
        assertTrue(issued.getDisplayNumber().matches("^(P-)?\\d{2,}$"), "QueueTicket phải theo chuẩn 01, 02 hoặc P-01");
        assertEquals("01", issued.getDisplayNumber());
        assertEquals(VisitStatus.WAITING, visit.getStatus(), "VisitStatus phải giữ nguyên là WAITING/WAITING_RESULT, KHÔNG đổi thành COMPLETED");
        assertNotEquals(VisitStatus.COMPLETED, visit.getStatus());
        verify(visits, atLeastOnce()).save(visit);
        verify(tickets).saveAndFlush(issued);
    }

    @Test
    void testCompleteDispatchAndIssueTicket_RollbackOnTicketFailure() {
        permitted();
        when(visits.findById(7L)).thenReturn(Optional.of(visit));
        when(doctors.eligible(eq(1L), any())).thenReturn(List.of(doctor));
        when(schedules.working(eq(3L), eq(1L), any(), any())).thenReturn(1L);
        when(doctors.findById(3L)).thenReturn(Optional.of(doctor));

        Room room = new Room();
        room.setRoomId(5L);
        room.setBranch(branch);
        room.setStatus(RoomStatus.ACTIVE);

        DentalChair chair = new DentalChair();
        chair.setChairId(6L);
        chair.setBranch(branch);
        chair.setRoom(room);
        chair.setStatus(ChairStatus.AVAILABLE);

        when(chairs.findById(6L)).thenReturn(Optional.of(chair));
        when(rooms.findById(5L)).thenReturn(Optional.of(room));
        when(tickets.findByVisitVisitIdAndQueueType(7L, QueueType.EXAM)).thenReturn(Optional.empty());
        when(tickets.maxNumber(eq(1L), any(), eq(QueueType.EXAM), eq(false))).thenReturn(999);

        assertThrows(ResponseStatusException.class, () ->
                service.completeDispatchAndIssueTicket(session, 1L, 7L, 3L, 5L, 6L, false, null, "Ghi chú thử nghiệm"));

        assertNull(visit.getPrimaryDoctor(), "Khi cấp số lỗi, toàn bộ giao dịch phải rollback và bác sĩ không bị gán dở dang");
        assertNull(visit.getRoomId(), "Phòng không bị lưu dở dang sau khi rollback");
        assertNull(visit.getChairId(), "Ghế không bị lưu dở dang sau khi rollback");
        assertEquals(VisitStatus.WAITING, visit.getStatus());
    }
}
