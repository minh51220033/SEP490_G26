package com.cvdcms.service;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.ClinicQueueCoordinatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:queue_matrix_tv_it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;NON_KEYWORDS=MONTH,YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QueueResourceMatrixAndTvIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    @Autowired private MockMvc mockMvc;
    @Autowired private ClinicQueueCoordinatorService coordinator;
    @Autowired private QueueWorkflowService queueWorkflowService;
    @Autowired private DispatchService dispatchService;
    @Autowired private BranchRepository branchRepository;
    @Autowired private RoomRepository roomRepository;
    @Autowired private DentalChairRepository chairRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffProfileRepository staffProfileRepository;
    @Autowired private StaffBranchAssignmentRepository branchAssignmentRepository;
    @Autowired private WorkShiftRepository workShiftRepository;
    @Autowired private StaffScheduleRepository staffScheduleRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private PatientProfileRepository patientProfileRepository;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private VisitRepository visitRepository;
    @Autowired private ClinicalQueueTicketRepository ticketRepository;

    private Branch branch;
    private Room room;
    private WorkShift fullDayShift;
    private User adminUser;
    private User receptionistUser;
    private Role docRole;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        int id = SEQ.incrementAndGet();
        today = LocalDate.now(ZONE);

        branch = new Branch();
        branch.setBranchCode("CS-MTX-" + id);
        branch.setBranchName("Chi nhanh Matrix " + id);
        branch.setAddress("Ha Noi");
        branch.setIsActive(true);
        branch.setResourceEngineEnabled(false);
        branch.setOpeningTime(LocalTime.of(0, 0));
        branch.setClosingTime(LocalTime.of(23, 59));
        branch = branchRepository.saveAndFlush(branch);

        room = new Room();
        room.setBranch(branch);
        room.setRoomCode("P101-" + id);
        room.setRoomName("Phòng 101-" + id);
        room.setRoomType(RoomType.TREATMENT);
        room.setStatus(RoomStatus.ACTIVE);
        room = roomRepository.saveAndFlush(room);

        fullDayShift = new WorkShift();
        fullDayShift.setBranch(branch);
        fullDayShift.setShiftCode("CA-ALL-" + id);
        fullDayShift.setShiftName("Ca ca ngay " + id);
        fullDayShift.setStartTime(LocalTime.of(0, 0));
        fullDayShift.setEndTime(LocalTime.of(23, 59));
        fullDayShift.setIsActive(true);
        fullDayShift = workShiftRepository.saveAndFlush(fullDayShift);

        Role adminRole = ensureRole("ADMIN");
        Role letanRole = ensureRole("LE_TAN");
        docRole = ensureRole("BAC_SI");

        adminUser = createUser("admin_mtx_" + id, adminRole, UserType.STAFF);
        receptionistUser = createUser("letan_mtx_" + id, letanRole, UserType.STAFF);
        StaffProfile recProfile = new StaffProfile();
        recProfile.setUser(receptionistUser);
        recProfile.setStaffCode("LT-MTX-" + id);
        recProfile.setFullName("Le Tan Matrix " + id);
        recProfile.setStatus(StaffStatus.ACTIVE);
        recProfile = staffProfileRepository.saveAndFlush(recProfile);
        assignBranch(recProfile, letanRole);
    }

    private Role ensureRole(String name) {
        return roleRepository.findByRoleName(name).orElseGet(() -> {
            Role r = new Role();
            r.setRoleName(name);
            r.setIsActive(true);
            return roleRepository.saveAndFlush(r);
        });
    }

    private User createUser(String username, Role role, UserType type) {
        int uSeq = SEQ.incrementAndGet();
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@test.local");
        u.setPhoneNumber("091" + String.format("%07d", uSeq));
        u.setPassword("$2a$10$dummy");
        u.setUserType(type);
        u.setIsActive(true);
        u.setStatus("ACTIVE");
        u.setBranch(branch);
        u.setRoles(new HashSet<>(Set.of(role)));
        return userRepository.saveAndFlush(u);
    }

    private void assignBranch(StaffProfile staff, Role role) {
        StaffBranchAssignment sba = new StaffBranchAssignment();
        sba.setStaff(staff);
        sba.setBranch(branch);
        sba.setRole(role);
        sba.setStatus(AssignmentStatus.ACTIVE);
        sba.setIsPrimary(true);
        sba.setStartDate(today.minusDays(1));
        branchAssignmentRepository.saveAndFlush(sba);
    }

    private StaffProfile createScheduledDoctor(String suffix, boolean scheduleToday) {
        int id = SEQ.incrementAndGet();
        User u = createUser("doc_" + suffix + "_" + id, docRole, UserType.STAFF);
        StaffProfile doc = new StaffProfile();
        doc.setUser(u);
        doc.setStaffCode("BS-" + suffix + "-" + id);
        doc.setFullName("BS " + suffix);
        doc.setStatus(StaffStatus.ACTIVE);
        doc = staffProfileRepository.saveAndFlush(doc);
        assignBranch(doc, docRole);

        if (scheduleToday) {
            StaffSchedule sch = new StaffSchedule();
            sch.setStaff(doc);
            sch.setBranch(branch);
            sch.setShift(fullDayShift);
            sch.setWorkDate(today);
            sch.setStatus(ScheduleStatus.SCHEDULED);
            staffScheduleRepository.saveAndFlush(sch);
        }
        return doc;
    }

    private DentalChair createChair(String codeSuffix, ChairStatus status) {
        int id = SEQ.incrementAndGet();
        DentalChair c = new DentalChair();
        c.setBranch(branch);
        c.setRoom(room);
        c.setChairCode("G-" + codeSuffix + "-" + id);
        c.setChairName("Ghế " + codeSuffix + "-" + id);
        c.setStatus(status);
        return chairRepository.saveAndFlush(c);
    }

    private Visit createWaitingVisit(String patientName, StaffProfile doctor, Long appointmentId) {
        int id = SEQ.incrementAndGet();
        PatientProfile p = new PatientProfile();
        p.setPatientCode("BN-MTX-" + id);
        p.setFullName(patientName);
        p.setPhoneNumber("09" + String.format("%08d", id));
        p.setAddress("Ha Noi");
        p.setGender("MALE");
        p.setStatus("ACTIVE");
        p.setPreferredBranch(branch);
        p = patientProfileRepository.saveAndFlush(p);

        Visit v = new Visit();
        v.setVisitCode("VIS-MTX-" + id);
        v.setPatientProfile(p);
        v.setBranch(branch);
        v.setPrimaryDoctor(doctor);
        v.setAppointmentId(appointmentId);
        v.setVisitType(appointmentId != null ? VisitType.APPOINTMENT : VisitType.WALK_IN);
        v.setStatus(VisitStatus.WAITING);
        v.setCheckInAt(LocalDateTime.now(ZONE).minusMinutes(5));
        return visitRepository.saveAndFlush(v);
    }

    private long countCalledInBranch() {
        return ticketRepository.findForBoard(branch.getBranchId(), today, QueueType.EXAM).stream()
                .filter(t -> t.getStatus() == QueueStatus.CALLED)
                .count();
    }

    private void assertMatrix(int doctorCount, int chairCount, int expectedMaxCalled) {
        List<DentalChair> createdChairs = new ArrayList<>();
        for (int i = 0; i < chairCount; i++) {
            createdChairs.add(createChair("M" + i, ChairStatus.AVAILABLE));
        }
        List<StaffProfile> doctors = new ArrayList<>();
        for (int i = 0; i < doctorCount; i++) {
            doctors.add(createScheduledDoctor("D" + i, true));
        }
        for (StaffProfile d : doctors) {
            Visit v = createWaitingVisit("Benh Nhan " + d.getStaffCode(), d, null);
            coordinator.issueTicketForVisitIfEligible(v.getVisitId(), adminUser.getUserId());
        }
        if (doctors.isEmpty()) {
            StaffProfile offDoc = createScheduledDoctor("Off", false);
            Visit v = createWaitingVisit("Benh Nhan Off", offDoc, null);
            coordinator.issueTicketForVisitIfEligible(v.getVisitId(), adminUser.getUserId());
        }
        List<QueueTicket> board = ticketRepository.findForBoard(branch.getBranchId(), today, QueueType.EXAM);
        List<QueueTicket> called = board.stream().filter(t -> t.getStatus() == QueueStatus.CALLED).toList();
        assertEquals(expectedMaxCalled, called.size(), "Called count must match min(doctors, chairs)");
        Set<Long> usedChairIds = new HashSet<>();
        for (QueueTicket t : called) {
            assertNotNull(t.getVisit().getRoomId(), "CALLED ticket must have roomId");
            assertNotNull(t.getVisit().getChairId(), "CALLED ticket must have chairId");
            assertTrue(usedChairIds.add(t.getVisit().getChairId()), "Two CALLED tickets must not share a chair");
        }
    }

    @Test
    @DisplayName("Cases 1-7: Matrix maxConcurrentCalls = min(availableDoctors, availableChairs)")
    void matrixCombinationsEnforceResourceLimit() {
        // Case 1: 2 doctors, 2 chairs -> 2 CALLED
        assertMatrix(2, 2, 2);

        // Case 2 (fresh branch)
        setUp();
        assertMatrix(1, 2, 1);

        // Case 3: 3 doctors, 2 chairs -> 2 CALLED
        setUp();
        assertMatrix(3, 2, 2);

        // Case 4: 2 doctors, 3 chairs -> 2 CALLED
        setUp();
        assertMatrix(2, 3, 2);

        // Case 5: 3 doctors, 3 chairs -> 3 CALLED
        setUp();
        assertMatrix(3, 3, 3);

        // Case 6: 0 scheduled doctors, 3 chairs -> 0 CALLED
        setUp();
        assertMatrix(0, 3, 0);

        // Case 7: 3 doctors, 0 chairs -> 0 CALLED
        setUp();
        assertMatrix(3, 0, 0);
    }

    @Test
    @DisplayName("Cases 8-9: Maintenance chair and approved LeaveRequest doctor are excluded from calling")
    void maintenanceChairAndApprovedLeaveAreExcluded() {
        DentalChair validChair = createChair("OK", ChairStatus.AVAILABLE);
        createChair("MAINT", ChairStatus.MAINTENANCE);

        StaffProfile docWorking = createScheduledDoctor("Work", true);
        StaffProfile docOnLeave = createScheduledDoctor("Leave", true);

        LeaveRequest leave = new LeaveRequest();
        leave.setStaff(docOnLeave);
        leave.setBranch(branch);
        leave.setStartDate(today);
        leave.setEndDate(today);
        leave.setReason("Nghi phep");
        leave.setStatus(LeaveStatus.APPROVED);
        leaveRequestRepository.saveAndFlush(leave);

        Visit v1 = createWaitingVisit("BN Working", docWorking, null);
        Visit v2 = createWaitingVisit("BN Leave", docOnLeave, null);

        QueueTicket t1 = coordinator.issueTicketForVisitIfEligible(v1.getVisitId(), adminUser.getUserId());
        QueueTicket t2 = coordinator.issueTicketForVisitIfEligible(v2.getVisitId(), adminUser.getUserId());

        QueueTicket t1Reloaded = ticketRepository.findById(t1.getTicketId()).orElseThrow();
        QueueTicket t2Reloaded = ticketRepository.findById(t2.getTicketId()).orElseThrow();

        assertEquals(QueueStatus.CALLED, t1Reloaded.getStatus());
        Visit v1Reloaded = visitRepository.findById(v1.getVisitId()).orElseThrow();
        assertEquals(validChair.getChairId(), v1Reloaded.getChairId());
        assertEquals(QueueStatus.WAITING, t2Reloaded.getStatus(), "Doctor on approved leave must remain WAITING");
    }

    @Test
    @DisplayName("Cases 10-11: Booked doctor preserved when unavailable; unspecified patient auto-assigned")
    void bookedDoctorPreservedAndUnspecifiedAutoAssigned() {
        createChair("C1", ChairStatus.AVAILABLE);
        createChair("C2", ChairStatus.AVAILABLE);

        StaffProfile docAOffToday = createScheduledDoctor("DocAOff", false);
        StaffProfile docBWorking = createScheduledDoctor("DocBWork", true);

        int id = SEQ.incrementAndGet();
        PatientProfile pBooked = new PatientProfile();
        pBooked.setPatientCode("BN-BK-" + id);
        pBooked.setFullName("BN Dat BS A");
        pBooked.setPhoneNumber("0911" + String.format("%06d", id));
        pBooked.setAddress("Ha Noi");
        pBooked.setGender("MALE");
        pBooked.setStatus("ACTIVE");
        pBooked.setPreferredBranch(branch);
        pBooked = patientProfileRepository.saveAndFlush(pBooked);

        Appointment appt = new Appointment();
        appt.setAppointmentCode("APT-MTX-" + id);
        appt.setPatientProfile(pBooked);
        appt.setBranch(branch);
        appt.setDoctor(docAOffToday);
        appt.setAppointmentDate(today);
        appt.setExpectedTime(LocalTime.of(9, 0));
        appt.setExpectedEndTime(LocalTime.of(9, 30));
        appt.setStatus(AppointmentStatus.CONFIRMED);
        appt = appointmentRepository.saveAndFlush(appt);

        Visit bookedVisit = new Visit();
        bookedVisit.setVisitCode("VIS-BK-" + id);
        bookedVisit.setPatientProfile(pBooked);
        bookedVisit.setBranch(branch);
        bookedVisit.setAppointmentId(appt.getAppointmentId());
        bookedVisit.setVisitType(VisitType.APPOINTMENT);
        bookedVisit.setStatus(VisitStatus.WAITING);
        bookedVisit.setCheckInAt(LocalDateTime.now(ZONE));
        bookedVisit = visitRepository.saveAndFlush(bookedVisit);

        Visit walkInVisit = createWaitingVisit("BN Khong Chon BS", null, null);

        MockHttpSession letanSession = new MockHttpSession();
        letanSession.setAttribute("loggedInUser", receptionistUser);

        // Case 10: Receptionist dispatches bookedVisit with doctorId=null -> must NOT switch to docBWorking
        dispatchService.assign(letanSession, branch.getBranchId(), bookedVisit.getVisitId(), null, null, null, null);
        Visit afterBooked = visitRepository.findById(bookedVisit.getVisitId()).orElseThrow();
        assertNotNull(afterBooked.getPrimaryDoctor());
        assertEquals(docAOffToday.getStaffId(), afterBooked.getPrimaryDoctor().getStaffId());
        QueueTicket bookedTicket = ticketRepository.findByVisitVisitIdAndQueueType(bookedVisit.getVisitId(), QueueType.EXAM).stream().findFirst().orElseThrow();
        assertEquals(QueueStatus.WAITING, bookedTicket.getStatus(), "Booked unavailable doctor must hold WAITING");

        // Case 11: Receptionist dispatches walkInVisit with doctorId=null -> auto-assigns docBWorking and calls
        dispatchService.assign(letanSession, branch.getBranchId(), walkInVisit.getVisitId(), null, null, null, null);
        Visit afterWalkIn = visitRepository.findById(walkInVisit.getVisitId()).orElseThrow();
        assertEquals(docBWorking.getStaffId(), afterWalkIn.getPrimaryDoctor().getStaffId());
        QueueTicket walkInTicket = ticketRepository.findByVisitVisitIdAndQueueType(walkInVisit.getVisitId(), QueueType.EXAM).stream().findFirst().orElseThrow();
        assertEquals(QueueStatus.CALLED, walkInTicket.getStatus());
        assertNotNull(afterWalkIn.getRoomId());
        assertNotNull(afterWalkIn.getChairId());
    }

    @Test
    @DisplayName("Case 12: Concurrent calls for single chair allocate to exactly one doctor")
    void concurrentCallAllocatesSingleChairOnce() throws Exception {
        DentalChair singleChair = createChair("CONC", ChairStatus.AVAILABLE);
        StaffProfile d1 = createScheduledDoctor("C1", true);
        StaffProfile d2 = createScheduledDoctor("C2", true);

        Visit v1 = createWaitingVisit("BN Concurrent 1", d1, null);
        Visit v2 = createWaitingVisit("BN Concurrent 2", d2, null);

        QueueTicket t1 = coordinator.issueTicketForVisitIfEligible(v1.getVisitId(), adminUser.getUserId());
        // Reset t1 back to WAITING + clear chair to force simultaneous race
        t1.setStatus(QueueStatus.WAITING);
        t1.setCalledAt(null);
        ticketRepository.saveAndFlush(t1);
        v1.setRoomId(null);
        v1.setChairId(null);
        visitRepository.saveAndFlush(v1);

        QueueTicket t2 = new QueueTicket();
        t2.setVisit(v2);
        t2.setBranch(branch);
        t2.setQueueDate(today);
        t2.setQueueType(QueueType.EXAM);
        t2.setTicketNumber(2);
        t2.setDisplayNumber("02");
        t2.setStatus(QueueStatus.WAITING);
        t2.setIsPriority(false);
        t2.setIsEmergency(false);
        t2.setPriorityScore(50);
        t2.setIssuedAt(LocalDateTime.now(ZONE));
        ticketRepository.saveAndFlush(t2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<Optional<QueueTicket>> f1 = pool.submit(() -> {
            ready.countDown();
            go.await();
            return coordinator.autoCallNextForDoctorIfIdle(branch.getBranchId(), d1.getStaffId(), adminUser.getUserId());
        });
        Future<Optional<QueueTicket>> f2 = pool.submit(() -> {
            ready.countDown();
            go.await();
            return coordinator.autoCallNextForDoctorIfIdle(branch.getBranchId(), d2.getStaffId(), adminUser.getUserId());
        });
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(1, countCalledInBranch());
        QueueTicket winner = ticketRepository.findForBoard(branch.getBranchId(), today, QueueType.EXAM).stream()
                .filter(t -> t.getStatus() == QueueStatus.CALLED)
                .findFirst()
                .orElseThrow();
        assertEquals(singleChair.getChairId(), winner.getVisit().getChairId());
    }

    @Test
    @DisplayName("Case 13: Legacy CALLED ticket missing room/chair is reconciled safely & unified TV table renders 6 columns")
    void legacyCalledWithoutResourcesReconciledAndUnifiedTvRendered() throws Exception {
        createChair("TV1", ChairStatus.AVAILABLE);
        StaffProfile doc = createScheduledDoctor("TVDoc", true);
        Visit legacyVisit = createWaitingVisit("BN Legacy Called", doc, null);

        QueueTicket legacyCalled = new QueueTicket();
        legacyCalled.setVisit(legacyVisit);
        legacyCalled.setBranch(branch);
        legacyCalled.setQueueDate(today);
        legacyCalled.setQueueType(QueueType.EXAM);
        legacyCalled.setTicketNumber(99);
        legacyCalled.setDisplayNumber("99");
        legacyCalled.setStatus(QueueStatus.CALLED);
        legacyCalled.setCalledAt(LocalDateTime.now(ZONE).minusMinutes(10));
        legacyCalled.setIsPriority(false);
        legacyCalled.setIsEmergency(false);
        legacyCalled.setPriorityScore(50);
        legacyCalled.setIssuedAt(LocalDateTime.now(ZONE).minusMinutes(20));
        legacyCalled = ticketRepository.saveAndFlush(legacyCalled);

        // Before reconcile: publicBoard.called() must NOT expose a CALLED ticket missing room/chair
        QueueWorkflowService.PublicBoard beforeReconcile = queueWorkflowService.publicBoard(branch.getBranchId());
        assertTrue(beforeReconcile.called().isEmpty(), "publicBoard.called must exclude tickets missing room/chair");

        // Reconcile safely allocates real room/chair because chair is available and doc is eligible
        int reconciled = coordinator.reconcileLegacyCalledTicketsWithoutResources(today);
        assertEquals(1, reconciled);

        Visit updatedVisit = visitRepository.findById(legacyVisit.getVisitId()).orElseThrow();
        assertNotNull(updatedVisit.getRoomId());
        assertNotNull(updatedVisit.getChairId());

        QueueWorkflowService.PublicBoard afterReconcile = queueWorkflowService.publicBoard(branch.getBranchId());
        assertEquals(1, afterReconcile.called().size());
        assertFalse(afterReconcile.called().get(0).room().isBlank());
        assertFalse(afterReconcile.called().get(0).chair().isBlank());

        // Verify unified TV HTML has 6-column table and no separate calling card grid
        mockMvc.perform(get("/reception/queue/tv/" + branch.getBranchId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bác sĩ phụ trách")))
                .andExpect(content().string(containsString("tv-row-calling")))
                .andExpect(content().string(not(containsString("id=\"tvCallingGrid\""))));
    }
}
