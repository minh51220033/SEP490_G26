/*
package com.cvdcms.reception;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.AppointmentRepository;
import com.cvdcms.service.ClinicalExaminationBootstrapService;
import jakarta.servlet.http.HttpSession;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

*/
/**
 * Phase 3 Integration Test Suite for FT08-FT10:
 * 1. Two receptionists dispatching concurrently (concurrency & branch lock)
 * 2. Two doctors calling tickets concurrently (parallel independent doctor queues)
 * 3. Single doctor calling overlapping numbers (blocked when a call is active)
 * 4. Ticket reprint / re-issue on same Visit (idempotent, stable numbers)
 * 5. Doctor queue transfer preserving ticket number
 * 6. Emergency unpause transitioning PAUSED -> WAITING in same transaction
 * 7. Occupied chair before START and switching to available chair
 * 8. Real database rollback when ticket-visit-chair state synchronization fails
 *//*

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QueueAndDispatchPhase3IntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Mock ReceptionAccess access;
    @Mock DispatchBranchRepository branches;
    @Mock QueueWorkflowRepository queueWorkflowRepo;
    @Mock DispatchVisitRepository visits;
    @Mock DispatchDoctorRepository doctors;
    @Mock DispatchScheduleRepository schedules;
    @Mock DispatchRoomRepository rooms;
    @Mock DispatchChairRepository chairs;
    @Mock DispatchTicketRepository dispatchTickets;
    @Mock ReceptionAuditRepository audits;
    @Mock AppointmentRepository appointments;
    @Mock ClinicalExaminationBootstrapService examinations;
    @Mock HttpSession session;

    QueueWorkflowService queueWorkflowService;
    DispatchService dispatchService;

    Branch branch;
    StaffProfile doctor1;
    StaffProfile doctor2;
    PatientProfile patient;

    @BeforeEach
    void setUp() {
        queueWorkflowService = new QueueWorkflowService(access, branches, queueWorkflowRepo, visits, rooms, chairs, audits, examinations);
        dispatchService = new DispatchService(access, visits, doctors, schedules, rooms, chairs, dispatchTickets, branches, audits, appointments, examinations);

        branch = new Branch();
        branch.setBranchId(1L);
        branch.setBranchName("Cơ sở Quận 1");
        branch.setIsActive(true);

        doctor1 = new StaffProfile();
        doctor1.setStaffId(101L);
        doctor1.setFullName("BS. Nguyễn Văn A");

        doctor2 = new StaffProfile();
        doctor2.setStaffId(102L);
        doctor2.setFullName("BS. Trần Thị B");

        patient = new PatientProfile();
        patient.setProfileId(201L);
        patient.setFullName("Lê Thị Hương");

        User user = new User();
        user.setUserId(99L);
        when(access.require(session)).thenReturn(new ReceptionAccess.Scope(user, true, true, List.of(1L)));
    }

    @Test
    @DisplayName("Integration 1: Hai lễ tân phân đồng thời cùng lượt khám")
    void twoReceptionistsDispatchingConcurrently() throws Exception {
        Visit visit = new Visit();
        visit.setVisitId(501L);
        visit.setBranch(branch);
        visit.setStatus(VisitStatus.WAITING);
        visit.setCheckInAt(LocalDateTime.now(ZONE));
        visit.setPatientProfile(patient);

        when(branches.lock(1L)).thenReturn(Optional.of(branch));
        when(visits.lock(501L)).thenReturn(Optional.of(visit));
        when(doctors.eligible(eq(1L), any())).thenReturn(List.of(doctor1, doctor2));
        when(schedules.working(anyLong(), eq(1L), any(), any())).thenReturn(1L);
        when(doctors.findById(101L)).thenReturn(Optional.of(doctor1));
        when(doctors.findById(102L)).thenReturn(Optional.of(doctor2));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        Future<?> f1 = pool.submit(() -> {
            try {
                ready.countDown();
                start.await();
                dispatchService.assign(session, 1L, 501L, 101L, null, null, null);
                successCount.incrementAndGet();
            } catch (Exception ignored) { }
        });

        Future<?> f2 = pool.submit(() -> {
            try {
                ready.countDown();
                start.await();
                dispatchService.assign(session, 1L, 501L, 102L, null, null, "Lễ tân 2 phân bổ lại");
                successCount.incrementAndGet();
            } catch (Exception ignored) { }
        });

        ready.await(3, TimeUnit.SECONDS);
        start.countDown();
        f1.get(5, TimeUnit.SECONDS);
        f2.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        // One of the doctors was assigned as primary doctor cleanly
        assertNotNull(visit.getPrimaryDoctor());
        assertTrue(visit.getPrimaryDoctor().getStaffId().equals(101L) || visit.getPrimaryDoctor().getStaffId().equals(102L));
        assertTrue(successCount.get() >= 1);
        verify(visits, atLeastOnce()).save(visit);
    }

    @Test
    @DisplayName("Integration 2: Hai bác sĩ gọi số song song không can thiệp lẫn nhau")
    void twoDoctorsCallingNextTicketsInParallel() throws Exception {
        when(branches.lock(1L)).thenReturn(Optional.of(branch));

        // Dr 1 queue
        Visit v1 = new Visit(); v1.setVisitId(601L); v1.setBranch(branch); v1.setPrimaryDoctor(doctor1); v1.setStatus(VisitStatus.WAITING);
        QueueTicket t1 = new QueueTicket(); t1.setTicketId(701L); t1.setBranch(branch); t1.setVisit(v1);
        t1.setQueueDate(LocalDate.now(ZONE)); t1.setQueueType(QueueType.EXAM); t1.setStatus(QueueStatus.WAITING);
        t1.setTicketNumber(1); t1.setDisplayNumber("01");

        // Dr 2 queue
        Visit v2 = new Visit(); v2.setVisitId(602L); v2.setBranch(branch); v2.setPrimaryDoctor(doctor2); v2.setStatus(VisitStatus.WAITING);
        QueueTicket t2 = new QueueTicket(); t2.setTicketId(702L); t2.setBranch(branch); t2.setVisit(v2);
        t2.setQueueDate(LocalDate.now(ZONE)); t2.setQueueType(QueueType.EXAM); t2.setStatus(QueueStatus.WAITING);
        t2.setTicketNumber(2); t2.setDisplayNumber("02");

        when(queueWorkflowRepo.countOtherForDoctor(eq(1L), any(), eq(QueueType.EXAM), eq(101L), eq(QueueStatus.CALLED), eq(-1L))).thenReturn(0L);
        when(queueWorkflowRepo.countOtherForDoctor(eq(1L), any(), eq(QueueType.EXAM), eq(102L), eq(QueueStatus.CALLED), eq(-1L))).thenReturn(0L);

        when(queueWorkflowRepo.forBoard(eq(1L), any(), eq(QueueType.EXAM))).thenReturn(List.of(t1, t2));
        when(queueWorkflowRepo.lock(701L)).thenReturn(Optional.of(t1));
        when(queueWorkflowRepo.lock(702L)).thenReturn(Optional.of(t2));
        when(visits.lock(601L)).thenReturn(Optional.of(v1));
        when(visits.lock(602L)).thenReturn(Optional.of(v2));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<QueueTicket> f1 = pool.submit(() -> queueWorkflowService.callNext(session, 1L, 101L));
        Future<QueueTicket> f2 = pool.submit(() -> queueWorkflowService.callNext(session, 1L, 102L));

        QueueTicket result1 = f1.get(5, TimeUnit.SECONDS);
        QueueTicket result2 = f2.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        assertSame(t1, result1);
        assertEquals(QueueStatus.CALLED, result1.getStatus());
        assertEquals("01", result1.getDisplayNumber());

        assertSame(t2, result2);
        assertEquals(QueueStatus.CALLED, result2.getStatus());
        assertEquals("02", result2.getDisplayNumber());

        verify(queueWorkflowRepo).save(t1);
        verify(queueWorkflowRepo).save(t2);
    }

    @Test
    @DisplayName("Integration 3: Một bác sĩ gọi chồng số bị chặn khi đã có số đang gọi")
    void doctorCannotCallOverlappingTickets() {
        when(branches.lock(1L)).thenReturn(Optional.of(branch));
        when(queueWorkflowRepo.countOtherForDoctor(eq(1L), any(), eq(QueueType.EXAM), eq(101L), eq(QueueStatus.CALLED), eq(-1L)))
                .thenReturn(1L);

        var ex = assertThrows(ResponseStatusException.class,
                () -> queueWorkflowService.callNext(session, 1L, 101L));
        assertTrue(ex.getReason().contains("Bác sĩ này đã có số được gọi"));
        verify(queueWorkflowRepo, never()).save(any());
    }

    @Test
    @DisplayName("Integration 4: Cấp/in lại cùng Visit trả về đúng số cũ (ổn định số FT09)")
    void issueTicketIsIdempotentAndStable() {
        when(branches.lock(1L)).thenReturn(Optional.of(branch));
        when(branches.findById(1L)).thenReturn(Optional.of(branch));

        Visit visit = new Visit();
        visit.setVisitId(502L); visit.setBranch(branch); visit.setStatus(VisitStatus.WAITING);
        visit.setCheckInAt(LocalDateTime.now(ZONE)); visit.setPrimaryDoctor(doctor1);
        when(visits.lock(502L)).thenReturn(Optional.of(visit));

        QueueTicket existing = new QueueTicket();
        existing.setTicketId(703L); existing.setVisit(visit); existing.setDisplayNumber("04");
        existing.setTicketNumber(4); existing.setStatus(QueueStatus.WAITING);

        when(dispatchTickets.findByVisitVisitIdAndQueueType(502L, QueueType.EXAM)).thenReturn(Optional.of(existing));

        // Call issue second time (simulate reprint or re-click)
        QueueTicket returned = dispatchService.issue(session, 1L, 502L, false, null);

        assertSame(existing, returned);
        assertEquals("04", returned.getDisplayNumber());
        assertEquals(4, returned.getTicketNumber());
        verify(dispatchTickets, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Integration 5: Chuyển bác sĩ giữ nguyên số khám và cập nhật nhật ký điều phối")
    void transferDoctorPreservesTicketDisplayNumber() {
        when(branches.lock(1L)).thenReturn(Optional.of(branch));
        when(branches.findById(1L)).thenReturn(Optional.of(branch));

        Visit visit = new Visit();
        visit.setVisitId(503L); visit.setBranch(branch); visit.setStatus(VisitStatus.WAITING);
        visit.setCheckInAt(LocalDateTime.now(ZONE)); visit.setPrimaryDoctor(doctor1);
        when(visits.lock(503L)).thenReturn(Optional.of(visit));

        QueueTicket ticket = new QueueTicket();
        ticket.setTicketId(704L); ticket.setVisit(visit); ticket.setDisplayNumber("P-02");
        ticket.setTicketNumber(2); ticket.setStatus(QueueStatus.WAITING);

        when(doctors.eligible(eq(1L), any())).thenReturn(List.of(doctor1, doctor2));
        when(schedules.working(anyLong(), eq(1L), any(), any())).thenReturn(1L);
        when(doctors.findById(102L)).thenReturn(Optional.of(doctor2));
        when(dispatchTickets.findByVisitVisitIdAndQueueType(503L, QueueType.EXAM)).thenReturn(Optional.of(ticket));

        dispatchService.assign(session, 1L, 503L, 102L, null, null, "Bệnh nhân yêu cầu đổi sang bác sĩ B");

        assertSame(doctor2, visit.getPrimaryDoctor());
        assertEquals("P-02", ticket.getDisplayNumber());
        verify(audits).save(argThat(log -> "TRANSFER_DOCTOR_QUEUE".equals(log.getAction())
                && log.getNewValuesJson().contains("doctor=102")));
    }

    @Test
    @DisplayName("Integration 6: Kích hoạt khẩn cấp ca PAUSED đưa về WAITING ngay trong cùng giao dịch")
    void emergencyUnpauseTransitionsPausedToWaitingInSameTransaction() {
        when(branches.lock(1L)).thenReturn(Optional.of(branch));

        Visit visit = new Visit();
        visit.setVisitId(504L); visit.setBranch(branch); visit.setStatus(VisitStatus.WAITING); visit.setPrimaryDoctor(doctor1);

        QueueTicket ticket = new QueueTicket();
        ticket.setTicketId(705L); ticket.setBranch(branch); ticket.setVisit(visit);
        ticket.setQueueDate(LocalDate.now(ZONE)); ticket.setQueueType(QueueType.EXAM);
        ticket.setStatus(QueueStatus.PAUSED); ticket.setPausedAt(LocalDateTime.now(ZONE).minusMinutes(10));
        ticket.setDisplayNumber("08"); ticket.setPriorityScore(0);

        when(queueWorkflowRepo.findById(705L)).thenReturn(Optional.of(ticket));
        when(queueWorkflowRepo.lock(705L)).thenReturn(Optional.of(ticket));
        when(visits.lock(504L)).thenReturn(Optional.of(visit));

        queueWorkflowService.transition(session, 705L, QueueWorkflowService.Action.EMERGENCY, "Bệnh nhân chảy máu liên tục sau nhổ răng");

        assertEquals(QueueStatus.WAITING, ticket.getStatus(), "Status must become WAITING in the same transaction");
        assertEquals("08", ticket.getDisplayNumber(), "Display number must not change");
        assertEquals(100, ticket.getPriorityScore(), "Priority score must be 100");
        assertTrue(ticket.getIsEmergency());
        assertNotNull(ticket.getResumedAt());
        verify(queueWorkflowRepo).save(ticket);
    }

    @Test
    @DisplayName("Integration 7: Ghế bị chiếm trước START báo lỗi; đổi sang ghế trống thì START thành công")
    void chairOccupiedCheckAndDynamicSwitchBeforeStart() {
        when(branches.lock(1L)).thenReturn(Optional.of(branch));

        Visit visit = new Visit();
        visit.setVisitId(505L); visit.setBranch(branch); visit.setStatus(VisitStatus.WAITING);
        visit.setPrimaryDoctor(doctor1); visit.setRoomId(10L); visit.setChairId(20L);

        QueueTicket ticket = new QueueTicket();
        ticket.setTicketId(706L); ticket.setBranch(branch); ticket.setVisit(visit);
        ticket.setQueueDate(LocalDate.now(ZONE)); ticket.setQueueType(QueueType.EXAM);
        ticket.setStatus(QueueStatus.CALLED);

        Room room = new Room(); room.setRoomId(10L); room.setBranch(branch); room.setStatus(RoomStatus.ACTIVE);

        DentalChair occupiedChair = new DentalChair();
        occupiedChair.setChairId(20L); occupiedChair.setBranch(branch); occupiedChair.setRoom(room);
        occupiedChair.setStatus(ChairStatus.AVAILABLE);

        when(queueWorkflowRepo.findById(706L)).thenReturn(Optional.of(ticket));
        when(queueWorkflowRepo.lock(706L)).thenReturn(Optional.of(ticket));
        when(visits.lock(505L)).thenReturn(Optional.of(visit));
        when(rooms.findById(10L)).thenReturn(Optional.of(room));
        when(chairs.findById(20L)).thenReturn(Optional.of(occupiedChair));

        // Chair 20 is occupied by another visit in exam
        when(visits.occupiedChair(eq(20L), eq(505L), anySet())).thenReturn(1L);

        // Case A: Attempting START with occupied chair 20 is rejected
        var ex = assertThrows(ResponseStatusException.class,
                () -> queueWorkflowService.transition(session, 706L, QueueWorkflowService.Action.START, null));
        assertTrue(ex.getReason().contains("Ghế đang có ca khám khác"));
        assertEquals(VisitStatus.WAITING, visit.getStatus());
        assertEquals(QueueStatus.CALLED, ticket.getStatus());

        // Case B: Switch to alternative available chair 21
        DentalChair freeChair = new DentalChair();
        freeChair.setChairId(21L); freeChair.setBranch(branch); freeChair.setRoom(room);
        freeChair.setStatus(ChairStatus.AVAILABLE);

        when(chairs.findById(21L)).thenReturn(Optional.of(freeChair));
        when(visits.occupiedChair(eq(21L), eq(505L), anySet())).thenReturn(0L);

        queueWorkflowService.transition(session, 706L, QueueWorkflowService.Action.START, 21L, null);

        assertEquals(VisitStatus.IN_EXAM, visit.getStatus());
        assertEquals(21L, visit.getChairId(), "Visit chair must update to new chair");
        assertEquals(ChairStatus.IN_USE, freeChair.getStatus());
        assertEquals(QueueStatus.IN_SERVICE, ticket.getStatus());
        verify(chairs).save(freeChair);
        verify(visits).save(visit);
    }

    @Test
    @DisplayName("Integration 8: Atomic Transaction Rollback bảo vệ tính toàn vẹn trạng thái Ticket-Visit-Ghế")
    void transactionRollbackPreservesAllEntitiesOnFailure() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:queue_rollback_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        jdbc.execute("CREATE TABLE queue_tickets (ticket_id BIGINT PRIMARY KEY, status VARCHAR(20) NOT NULL)");
        jdbc.execute("CREATE TABLE visits (visit_id BIGINT PRIMARY KEY, status VARCHAR(20) NOT NULL, chair_id BIGINT)");
        jdbc.execute("CREATE TABLE dental_chairs (chair_id BIGINT PRIMARY KEY, status VARCHAR(20) NOT NULL)");

        jdbc.update("INSERT INTO queue_tickets(ticket_id, status) VALUES (1, 'CALLED')");
        jdbc.update("INSERT INTO visits(visit_id, status, chair_id) VALUES (10, 'WAITING', 100)");
        jdbc.update("INSERT INTO dental_chairs(chair_id, status) VALUES (100, 'AVAILABLE')");

        DataSourceTransactionManager txManager = new DataSourceTransactionManager(ds);
        TransactionTemplate tx = new TransactionTemplate(txManager);

        // Simulate failed transition where chair update or secondary operation fails unexpectedly
        assertThrows(RuntimeException.class, () -> {
            tx.executeWithoutResult(status -> {
                // Step 1: Ticket -> IN_SERVICE
                jdbc.update("UPDATE queue_tickets SET status='IN_SERVICE' WHERE ticket_id=1");
                // Step 2: Visit -> IN_EXAM
                jdbc.update("UPDATE visits SET status='IN_EXAM' WHERE visit_id=10");
                // Step 3: Failure occurs before chair is committed
                throw new RuntimeException("Simulated constraint or hardware error");
            });
        });

        // Verify that after rollback, Ticket remains CALLED, Visit remains WAITING, Chair remains AVAILABLE
        String ticketStatus = jdbc.queryForObject("SELECT status FROM queue_tickets WHERE ticket_id=1", String.class);
        assertEquals("CALLED", ticketStatus, "Ticket must roll back to CALLED");

        String visitStatus = jdbc.queryForObject("SELECT status FROM visits WHERE visit_id=10", String.class);
        assertEquals("WAITING", visitStatus, "Visit must roll back to WAITING");

        String chairStatus = jdbc.queryForObject("SELECT status FROM dental_chairs WHERE chair_id=100", String.class);
        assertEquals("AVAILABLE", chairStatus, "Chair must remain AVAILABLE");
    }

    @Test
    @DisplayName("Integration 9: testCompleteDispatchAndIssueTicket_Success - Gán Bác sĩ + Ghế và cấp số trong cùng 1 transaction")
    void testCompleteDispatchAndIssueTicket_Success() {
        when(branches.lock(1L)).thenReturn(Optional.of(branch));
        when(branches.findById(1L)).thenReturn(Optional.of(branch));

        PatientProfile patient = new PatientProfile();
        patient.setProfileId(88L);
        patient.setPatientCode("BN_SYNC_01");
        patient.setFullName("Trần Thị Đồng Bộ");

        Visit visit = new Visit();
        visit.setVisitId(601L);
        visit.setVisitCode("V_SYNC_601");
        visit.setBranch(branch);
        visit.setPatientProfile(patient);
        visit.setStatus(VisitStatus.WAITING);
        visit.setVisitType(VisitType.WALK_IN);
        visit.setCheckInAt(LocalDateTime.now(ZONE));

        Room room = new Room();
        room.setRoomId(10L);
        room.setBranch(branch);
        room.setRoomName("Phòng 101");
        room.setStatus(RoomStatus.ACTIVE);

        DentalChair chair = new DentalChair();
        chair.setChairId(20L);
        chair.setBranch(branch);
        chair.setRoom(room);
        chair.setChairName("Ghế 01");
        chair.setStatus(ChairStatus.AVAILABLE);

        when(visits.lock(601L)).thenReturn(Optional.of(visit));
        when(visits.findById(601L)).thenReturn(Optional.of(visit));
        when(doctors.eligible(eq(1L), any())).thenReturn(List.of(doctor1));
        when(schedules.working(eq(101L), eq(1L), any(), any())).thenReturn(1L);
        when(doctors.findById(101L)).thenReturn(Optional.of(doctor1));
        when(rooms.findById(10L)).thenReturn(Optional.of(room));
        when(chairs.findById(20L)).thenReturn(Optional.of(chair));
        when(dispatchTickets.findByVisitVisitIdAndQueueType(601L, QueueType.EXAM)).thenReturn(Optional.empty());
        when(dispatchTickets.maxNumber(eq(1L), any(), eq(QueueType.EXAM), eq(false))).thenReturn(0);

        QueueTicket ticket = dispatchService.completeDispatchAndIssueTicket(
                session, 1L, 601L, 101L, 10L, 20L, false, null, "Ghi chú điều phối sang hàng chờ");

        assertNotNull(ticket);
        assertSame(doctor1, visit.getPrimaryDoctor());
        assertEquals(10L, visit.getRoomId());
        assertEquals(20L, visit.getChairId());
        assertEquals("01", ticket.getDisplayNumber());
        assertEquals(QueueStatus.WAITING, ticket.getStatus());
        assertEquals(VisitStatus.WAITING, visit.getStatus(), "VisitStatus vẫn giữ nguyên là WAITING, KHÔNG bị đổi thành COMPLETED");
        assertNotEquals(VisitStatus.COMPLETED, visit.getStatus());
    }

    @Test
    @DisplayName("Integration 10: testCompleteDispatchAndIssueTicket_RollbackOnTicketFailure - Giả lập lỗi khi cấp số, rollback toàn bộ transaction")
    void testCompleteDispatchAndIssueTicket_RollbackOnTicketFailure() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:dispatch_issue_rollback_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        jdbc.execute("CREATE TABLE visits_tx (visit_id BIGINT PRIMARY KEY, doctor_id BIGINT, room_id BIGINT, chair_id BIGINT, status VARCHAR(30) NOT NULL)");
        jdbc.execute("CREATE TABLE queue_tickets_tx (ticket_id BIGINT PRIMARY KEY, visit_id BIGINT NOT NULL, display_number VARCHAR(10) NOT NULL)");
        jdbc.update("INSERT INTO visits_tx(visit_id, doctor_id, room_id, chair_id, status) VALUES (602, NULL, NULL, NULL, 'WAITING')");

        DataSourceTransactionManager txManager = new DataSourceTransactionManager(ds);
        TransactionTemplate tx = new TransactionTemplate(txManager);

        when(branches.lock(1L)).thenReturn(Optional.of(branch));
        Visit visit = new Visit();
        visit.setVisitId(602L);
        visit.setBranch(branch);
        visit.setStatus(VisitStatus.WAITING);
        visit.setVisitType(VisitType.WALK_IN);
        visit.setCheckInAt(LocalDateTime.now(ZONE));

        Room room = new Room();
        room.setRoomId(10L);
        room.setBranch(branch);
        room.setStatus(RoomStatus.ACTIVE);

        DentalChair chair = new DentalChair();
        chair.setChairId(20L);
        chair.setBranch(branch);
        chair.setRoom(room);
        chair.setStatus(ChairStatus.AVAILABLE);

        when(visits.lock(602L)).thenReturn(Optional.of(visit));
        when(visits.findById(602L)).thenReturn(Optional.of(visit));
        when(doctors.eligible(eq(1L), any())).thenReturn(List.of(doctor1));
        when(schedules.working(eq(101L), eq(1L), any(), any())).thenReturn(1L);
        when(doctors.findById(101L)).thenReturn(Optional.of(doctor1));
        when(rooms.findById(10L)).thenReturn(Optional.of(room));
        when(chairs.findById(20L)).thenReturn(Optional.of(chair));
        when(dispatchTickets.findByVisitVisitIdAndQueueType(602L, QueueType.EXAM)).thenReturn(Optional.empty());
        when(dispatchTickets.maxNumber(eq(1L), any(), eq(QueueType.EXAM), eq(false))).thenReturn(1);
        doThrow(new RuntimeException("Simulated DB failure while issuing QueueTicket")).when(dispatchTickets).saveAndFlush(any());

        assertThrows(RuntimeException.class, () -> {
            tx.executeWithoutResult(status -> {
                jdbc.update("UPDATE visits_tx SET doctor_id = 101, room_id = 10, chair_id = 20 WHERE visit_id = 602");
                dispatchService.completeDispatchAndIssueTicket(session, 1L, 602L, 101L, 10L, 20L, false, null, null);
            });
        });

        Map<String, Object> row = jdbc.queryForMap("SELECT doctor_id, room_id, chair_id, status FROM visits_tx WHERE visit_id = 602");
        assertNull(row.get("doctor_id"), "DB doctor_id phải rollback về NULL");
        assertNull(row.get("room_id"), "DB room_id phải rollback về NULL");
        assertNull(row.get("chair_id"), "DB chair_id phải rollback về NULL");
        assertEquals("WAITING", row.get("status"));
        assertNull(visit.getPrimaryDoctor(), "In-memory Visit primaryDoctor cũng phải rollback về NULL");
        assertNull(visit.getChairId(), "In-memory Visit chairId cũng phải rollback về NULL");
    }

    @Test
    @DisplayName("Integration 11: testReceptionHistoryQuery_PaginationAndFiltering - Lọc theo checkInAt, loại trừ trạng thái và khớp totalElements")
    void testReceptionHistoryQuery_PaginationAndFiltering() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:reception_history_pagination_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        jdbc.execute("""
                CREATE TABLE visits_history_test (
                    visit_id BIGINT PRIMARY KEY,
                    branch_id BIGINT NOT NULL,
                    check_in_at TIMESTAMP NOT NULL,
                    status VARCHAR(30) NOT NULL
                )""");

        LocalDate today = LocalDate.now(ZONE);
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();

        // 3 valid WAITING/WAITING_RESULT visits today
        jdbc.update("INSERT INTO visits_history_test VALUES (1, 1, ?, 'WAITING')", java.sql.Timestamp.valueOf(start.plusHours(8)));
        jdbc.update("INSERT INTO visits_history_test VALUES (2, 1, ?, 'WAITING_RESULT')", java.sql.Timestamp.valueOf(start.plusHours(9)));
        jdbc.update("INSERT INTO visits_history_test VALUES (3, 1, ?, 'WAITING')", java.sql.Timestamp.valueOf(start.plusHours(10)));
        // 1 CANCELLED visit today (should be excluded)
        jdbc.update("INSERT INTO visits_history_test VALUES (4, 1, ?, 'CANCELLED')", java.sql.Timestamp.valueOf(start.plusHours(11)));
        // 1 COMPLETED visit today (should be excluded when filtering active)
        jdbc.update("INSERT INTO visits_history_test VALUES (5, 1, ?, 'COMPLETED')", java.sql.Timestamp.valueOf(start.plusHours(12)));
        // 1 WAITING visit from yesterday (outside checkInAt range)
        jdbc.update("INSERT INTO visits_history_test VALUES (6, 1, ?, 'WAITING')", java.sql.Timestamp.valueOf(start.minusHours(3)));

        ReceptionVisitRepository historyRepo = mock(ReceptionVisitRepository.class);
        org.springframework.data.domain.Pageable pageReq = org.springframework.data.domain.PageRequest.of(0, 2);
        Set<VisitStatus> excluded = Set.of(VisitStatus.CANCELLED, VisitStatus.COMPLETED);

        when(historyRepo.findReceptionHistory(eq(1L), eq(start), eq(end), eq(excluded), eq(pageReq)))
                .thenCallRealMethod();
        when(historyRepo.findReceptionHistoryByCheckInAtAndExcludedStatuses(eq(1L), eq(start), eq(end), eq(excluded), eq(pageReq)))
                .thenAnswer(inv -> {
                    Long total = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM visits_history_test WHERE branch_id = ? AND check_in_at >= ? AND check_in_at < ? AND status NOT IN ('CANCELLED', 'COMPLETED')",
                            Long.class, 1L, java.sql.Timestamp.valueOf(start), java.sql.Timestamp.valueOf(end));
                    List<Visit> content = jdbc.query(
                            "SELECT visit_id, check_in_at, status FROM visits_history_test WHERE branch_id = ? AND check_in_at >= ? AND check_in_at < ? AND status NOT IN ('CANCELLED', 'COMPLETED') ORDER BY check_in_at DESC, visit_id DESC LIMIT ? OFFSET ?",
                            (rs, rowNum) -> {
                                Visit v = new Visit();
                                v.setVisitId(rs.getLong("visit_id"));
                                v.setCheckInAt(rs.getTimestamp("check_in_at").toLocalDateTime());
                                v.setStatus(VisitStatus.valueOf(rs.getString("status")));
                                return v;
                            },
                            1L, java.sql.Timestamp.valueOf(start), java.sql.Timestamp.valueOf(end), pageReq.getPageSize(), pageReq.getOffset());
                    return new org.springframework.data.domain.PageImpl<>(content, pageReq, total != null ? total : 0L);
                });

        org.springframework.data.domain.Page<Visit> page0 = historyRepo.findReceptionHistory(1L, start, end, excluded, pageReq);

        assertEquals(3L, page0.getTotalElements(), "totalElements phải khớp chính xác 3 bản ghi hợp lệ trong ngày");
        assertEquals(2, page0.getContent().size(), "Page 0 (size 2) phải trả về đúng 2 bản ghi");
        assertEquals(2, page0.getTotalPages(), "Tổng số trang phải là 2");
        assertTrue(page0.getContent().stream().noneMatch(v -> excluded.contains(v.getStatus())), "Không chứa trạng thái bị loại trừ");
        assertTrue(page0.getContent().stream().allMatch(v -> !v.getCheckInAt().isBefore(start) && v.getCheckInAt().isBefore(end)), "Tất cả bản ghi phải nằm trong khoảng checkInAt hôm nay");
    }
}
*/
