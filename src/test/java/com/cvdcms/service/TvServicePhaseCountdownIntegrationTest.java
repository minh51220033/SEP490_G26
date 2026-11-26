package com.cvdcms.service;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:tv_countdown_it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;NON_KEYWORDS=MONTH,YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TvServicePhaseCountdownIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Bangkok");
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    @Autowired private QueueWorkflowService queueWorkflowService;
    @Autowired private ClinicQueueCoordinatorService coordinator;
    @Autowired private BranchRepository branchRepository;
    @Autowired private RoomRepository roomRepository;
    @Autowired private DentalChairRepository chairRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffProfileRepository staffProfileRepository;
    @Autowired private StaffBranchAssignmentRepository branchAssignmentRepository;
    @Autowired private PatientProfileRepository patientProfileRepository;
    @Autowired private ServiceCategoryRepository serviceCategoryRepository;
    @Autowired private ServiceRepository serviceRepository;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private VisitRepository visitRepository;
    @Autowired private ClinicalQueueTicketRepository ticketRepository;

    private Branch branch;
    private Room room;
    private DentalChair chair1;
    private DentalChair chair2;
    private User staffUser;
    private StaffProfile doctor;
    private ServiceCategory cat;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        int id = SEQ.incrementAndGet();
        today = LocalDate.now(ZONE);

        branch = new Branch();
        branch.setBranchCode("CS-TV-" + id);
        branch.setBranchName("Chi nhanh TV " + id);
        branch.setAddress("Ha Noi");
        branch.setIsActive(true);
        branch.setResourceEngineEnabled(false);
        branch.setOpeningTime(LocalTime.of(0, 0));
        branch.setClosingTime(LocalTime.of(23, 59));
        branch = branchRepository.saveAndFlush(branch);

        room = new Room();
        room.setBranch(branch);
        room.setRoomCode("RTV-" + id);
        room.setRoomName("Phong TV " + id);
        room.setRoomType(RoomType.TREATMENT);
        room.setStatus(RoomStatus.ACTIVE);
        room = roomRepository.saveAndFlush(room);

        chair1 = createChair("TV-C1-" + id);
        chair2 = createChair("TV-C2-" + id);

        Role adminRole = roleRepository.findByRoleName("ADMIN").orElseGet(() -> {
            Role r = new Role();
            r.setRoleName("ADMIN");
            return roleRepository.saveAndFlush(r);
        });

        staffUser = new User();
        staffUser.setUsername("tv_admin_" + id);
        staffUser.setEmail("tv_admin_" + id + "@test.local");
        staffUser.setPhoneNumber("093" + String.format("%07d", id));
        staffUser.setPassword("pw");
        staffUser.setUserType(UserType.STAFF);
        staffUser.setIsActive(true);
        staffUser.setStatus("ACTIVE");
        staffUser.setBranch(branch);
        staffUser.getRoles().add(adminRole);
        staffUser = userRepository.saveAndFlush(staffUser);

        doctor = new StaffProfile();
        doctor.setUser(staffUser);
        doctor.setStaffCode("BS-TV-" + id);
        doctor.setFullName("BS TV " + id);
        doctor.setStatus(StaffStatus.ACTIVE);
        doctor = staffProfileRepository.saveAndFlush(doctor);

        StaffBranchAssignment sba = new StaffBranchAssignment();
        sba.setStaff(doctor);
        sba.setBranch(branch);
        sba.setRole(adminRole);
        sba.setStatus(AssignmentStatus.ACTIVE);
        sba.setIsPrimary(true);
        sba.setStartDate(today.minusDays(1));
        branchAssignmentRepository.saveAndFlush(sba);

        cat = new ServiceCategory();
        cat.setCategoryCode("CAT-TV-" + id);
        cat.setCategoryName("Cat TV " + id);
        cat.setIsActive(true);
        cat = serviceCategoryRepository.saveAndFlush(cat);
    }

    private DentalChair createChair(String code) {
        DentalChair c = new DentalChair();
        c.setBranch(branch);
        c.setRoom(room);
        c.setChairCode(code);
        c.setChairName("Ghe " + code);
        c.setStatus(ChairStatus.AVAILABLE);
        return chairRepository.saveAndFlush(c);
    }

    private Service createService(int prep, int exam, int clean) {
        Service s = new Service();
        s.setCategory(cat);
        s.setServiceCode("SVC-" + prep + "-" + exam + "-" + clean + "-" + SEQ.incrementAndGet());
        s.setServiceName("DV " + prep + "/" + exam + "/" + clean);
        s.setServiceType(ServiceType.PROCEDURE);
        s.setBasePrice(BigDecimal.valueOf(300000));
        s.setPreparationDurationMinutes(prep);
        s.setProcedureDurationMinutes(exam);
        s.setTurnoverDurationMinutes(clean);
        s.setEstimatedDurationMinutes(prep + exam + clean);
        s.setStatus(ServiceStatus.ACTIVE);
        s.setIsBookableOnline(true);
        return serviceRepository.saveAndFlush(s);
    }

    private QueueTicket createInServiceTicket(String ticketNum, Service svc, DentalChair chair, LocalDateTime actualStart) {
        int n = SEQ.incrementAndGet();
        PatientProfile p = new PatientProfile();
        p.setUser(staffUser);
        p.setPatientCode("BN-TV-" + n);
        p.setFullName("Benh nhan TV " + n);
        p.setPhoneNumber("09000000" + String.format("%02d", n % 100));
        p.setAddress("Ha Noi");
        p.setGender("MALE");
        p.setStatus("ACTIVE");
        p.setPreferredBranch(branch);
        p = patientProfileRepository.saveAndFlush(p);

        Appointment a = new Appointment();
        a.setAppointmentCode("LHTV" + n);
        a.setBranch(branch);
        a.setPatientProfile(p);
        a.setDoctor(doctor);
        a.setIsDoctorRequested(true);
        a.setService(svc);
        a.setAppointmentDate(today);
        a.setPreferredSession(PreferredSession.MORNING);
        a.setExpectedTime(LocalTime.of(8, 0));
        a.setDurationMinutes(svc.getEstimatedDurationMinutes());
        a.setPreparationDurationMinutes(svc.getPreparationDurationMinutes());
        a.setProcedureDurationMinutes(svc.getProcedureDurationMinutes());
        a.setTurnoverDurationMinutes(svc.getTurnoverDurationMinutes());
        a.syncExpectedEndTime();
        a.setStatus(AppointmentStatus.CHECKED_IN);
        a = appointmentRepository.saveAndFlush(a);

        Visit v = new Visit();
        v.setVisitCode("VSTV" + n);
        v.setBranch(branch);
        v.setPatientProfile(p);
        v.setAppointmentId(a.getAppointmentId());
        v.setPrimaryDoctor(doctor);
        v.setRoomId(room.getRoomId());
        v.setChairId(chair.getChairId());
        v.setCheckInAt(actualStart.minusMinutes(15));
        v.setActualStartTime(actualStart);
        v.setStatus(VisitStatus.IN_EXAM);
        v = visitRepository.saveAndFlush(v);

        chair.setStatus(ChairStatus.IN_USE);
        chairRepository.saveAndFlush(chair);

        QueueTicket t = new QueueTicket();
        t.setBranch(branch);
        t.setVisit(v);
        t.setQueueDate(today);
        t.setQueueType(QueueType.EXAM);
        t.setTicketNumber(n);
        t.setDisplayNumber(ticketNum);
        t.setStatus(QueueStatus.IN_SERVICE);
        t.setIssuedAt(actualStart.minusMinutes(15));
        t.setCalledAt(actualStart.minusMinutes(2));
        return ticketRepository.saveAndFlush(t);
    }

    @Test
    void testB1_threePhaseProgression() {
        Service svc = createService(5, 30, 5);
        // 2 minutes elapsed -> PREP
        createInServiceTicket("01", svc, chair1, LocalDateTime.now(ZONE).minusMinutes(2));
        var board1 = queueWorkflowService.publicBoard(branch.getBranchId());
        var p1 = board1.queueList().get(0).phaseProgress();
        assertNotNull(p1);
        assertEquals("PREP", p1.currentPhase());
        assertTrue(p1.hasPrep());
        assertTrue(p1.hasCleaning());
        assertEquals(2400L, p1.totalDurationSeconds());

        // 10 minutes elapsed -> EXAM
        Visit v = visitRepository.findAll().stream().filter(x -> x.getBranch().getBranchId().equals(branch.getBranchId())).findFirst().orElseThrow();
        v.setActualStartTime(LocalDateTime.now(ZONE).minusMinutes(10));
        visitRepository.saveAndFlush(v);
        var p2 = queueWorkflowService.publicBoard(branch.getBranchId()).queueList().get(0).phaseProgress();
        assertEquals("EXAM", p2.currentPhase());

        // 37 minutes elapsed -> CLEANING
        v.setActualStartTime(LocalDateTime.now(ZONE).minusMinutes(37));
        visitRepository.saveAndFlush(v);
        var p3 = queueWorkflowService.publicBoard(branch.getBranchId()).queueList().get(0).phaseProgress();
        assertEquals("CLEANING", p3.currentPhase());
    }

    @Test
    void testB2_zeroPrepSkipped() {
        Service svc = createService(0, 30, 5);
        createInServiceTicket("02", svc, chair1, LocalDateTime.now(ZONE).minusMinutes(1));
        var p = queueWorkflowService.publicBoard(branch.getBranchId()).queueList().get(0).phaseProgress();
        assertFalse(p.hasPrep());
        assertEquals("EXAM", p.currentPhase());
    }

    @Test
    void testB3_zeroCleaningSkipped() {
        Service svc = createService(5, 30, 0);
        createInServiceTicket("03", svc, chair1, LocalDateTime.now(ZONE).minusMinutes(36));
        var p = queueWorkflowService.publicBoard(branch.getBranchId()).queueList().get(0).phaseProgress();
        assertFalse(p.hasCleaning());
        assertEquals("OVERTIME", p.currentPhase());
        assertTrue(p.overtime());
    }

    @Test
    void testB4_B5_B10_reloadAndMultiTvConsistent() {
        Service svc = createService(5, 30, 5);
        LocalDateTime start = LocalDateTime.now(ZONE).minusMinutes(7);
        createInServiceTicket("04", svc, chair1, start);

        var tv1 = queueWorkflowService.publicBoard(branch.getBranchId());
        var tv2 = queueWorkflowService.publicBoard(branch.getBranchId());
        long expectedStartMs = start.atZone(ZONE).toInstant().toEpochMilli();
        assertEquals(expectedStartMs, tv1.queueList().get(0).phaseProgress().actualStartTimeEpochMs());
        assertEquals(expectedStartMs, tv2.queueList().get(0).phaseProgress().actualStartTimeEpochMs());
        assertEquals("EXAM", tv1.queueList().get(0).phaseProgress().currentPhase());
        assertEquals("EXAM", tv2.queueList().get(0).phaseProgress().currentPhase());
    }

    @Test
    void testB6_lateDoctorStartUsesActualStartTime() {
        Service svc = createService(5, 30, 5);
        int n = SEQ.incrementAndGet();
        PatientProfile p = new PatientProfile();
        p.setUser(staffUser);
        p.setPatientCode("BN-LATE-" + n);
        p.setFullName("BN Late");
        p.setPhoneNumber("0901112233");
        p.setAddress("Ha Noi");
        p.setGender("MALE");
        p.setStatus("ACTIVE");
        p.setPreferredBranch(branch);
        p = patientProfileRepository.saveAndFlush(p);

        Visit v = new Visit();
        v.setVisitCode("VSLATE" + n);
        v.setBranch(branch);
        v.setPatientProfile(p);
        v.setPrimaryDoctor(doctor);
        v.setRoomId(room.getRoomId());
        v.setChairId(chair1.getChairId());
        v.setCheckInAt(LocalDateTime.now(ZONE).minusMinutes(45));
        v.setStatus(VisitStatus.WAITING);
        v = visitRepository.saveAndFlush(v);

        QueueTicket t = new QueueTicket();
        t.setBranch(branch);
        t.setVisit(v);
        t.setQueueDate(today);
        t.setQueueType(QueueType.EXAM);
        t.setTicketNumber(n);
        t.setDisplayNumber("06");
        t.setStatus(QueueStatus.CALLED);
        t.setIssuedAt(LocalDateTime.now(ZONE).minusMinutes(45));
        t.setCalledAt(LocalDateTime.now(ZONE).minusMinutes(20));
        t = ticketRepository.saveAndFlush(t);

        // Before entering exam, no actualStartTime and no phaseProgress
        assertNull(v.getActualStartTime());

        // Doctor clicks Start Exam now
        coordinator.enterExaminationForDoctor(staffUser, branch.getBranchId());
        Visit started = visitRepository.findById(v.getVisitId()).orElseThrow();
        assertNotNull(started.getActualStartTime());
        assertTrue(started.getActualStartTime().isAfter(v.getCheckInAt().plusMinutes(30)));

        var pProg = queueWorkflowService.publicBoard(branch.getBranchId()).queueList().get(0).phaseProgress();
        assertNotNull(pProg);
        assertTrue(pProg.elapsedSeconds() < 10);
    }

    @Test
    void testB7_overtimeDoesNotAutoCompleteOrReleaseChair() {
        Service svc = createService(5, 30, 5);
        QueueTicket t = createInServiceTicket("07", svc, chair1, LocalDateTime.now(ZONE).minusMinutes(50));

        var board = queueWorkflowService.publicBoard(branch.getBranchId());
        var prog = board.queueList().get(0).phaseProgress();
        assertTrue(prog.overtime());
        assertEquals("OVERTIME", prog.currentPhase());
        assertEquals("Đã vượt thời gian dự kiến", prog.currentPhaseLabel());
        assertTrue(prog.overtimeSeconds() >= 590);

        QueueTicket refreshedTicket = ticketRepository.findById(t.getTicketId()).orElseThrow();
        Visit refreshedVisit = visitRepository.findById(t.getVisit().getVisitId()).orElseThrow();
        DentalChair refreshedChair = chairRepository.findById(chair1.getChairId()).orElseThrow();
        assertEquals(QueueStatus.IN_SERVICE, refreshedTicket.getStatus());
        assertEquals(VisitStatus.IN_EXAM, refreshedVisit.getStatus());
        assertEquals(ChairStatus.IN_USE, refreshedChair.getStatus());
    }

    @Test
    void testB8_examDoneVsChairTurnover() {
        Service svc = createService(5, 30, 5);
        QueueTicket t = createInServiceTicket("08", svc, chair1, LocalDateTime.now(ZONE).minusMinutes(35));

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", staffUser);
        session.setAttribute("currentBranchId", branch.getBranchId());
        session.setAttribute("isAdmin", true);
        session.setAttribute("roleNames", java.util.List.of("ADMIN"));

        queueWorkflowService.transition(session, t.getTicketId(), QueueWorkflowService.Action.DONE, "Kham xong");

        // Ticket is DONE (patient no longer IN_SERVICE), Chair is in TURNOVER (shown in cleaningChairs)
        assertEquals(QueueStatus.DONE, ticketRepository.findById(t.getTicketId()).orElseThrow().getStatus());
        DentalChair chairTurnover = chairRepository.findById(chair1.getChairId()).orElseThrow();
        assertEquals(ChairStatus.TURNOVER, chairTurnover.getStatus());
        assertNotNull(chairTurnover.getTurnoverStartedAt());

        var boardDuringClean = queueWorkflowService.publicBoard(branch.getBranchId());
        assertTrue(boardDuringClean.queueList().stream().noneMatch(r -> "08".equals(r.ticketNumber())));
        assertEquals(1, boardDuringClean.cleaningChairs().size());

        // Complete chair turnover -> Chair returns to AVAILABLE
        queueWorkflowService.completeChairTurnover(session, chair1.getChairId(), null);
        assertEquals(ChairStatus.AVAILABLE, chairRepository.findById(chair1.getChairId()).orElseThrow().getStatus());
        assertTrue(queueWorkflowService.publicBoard(branch.getBranchId()).cleaningChairs().isEmpty());
    }

    @Test
    void testB9_twoPatientsTwoChairsIndependent() {
        Service svcA = createService(5, 30, 5);
        Service svcB = createService(0, 15, 0);
        createInServiceTicket("09A", svcA, chair1, LocalDateTime.now(ZONE).minusMinutes(2));
        createInServiceTicket("09B", svcB, chair2, LocalDateTime.now(ZONE).minusMinutes(10));

        var board = queueWorkflowService.publicBoard(branch.getBranchId());
        var rowA = board.queueList().stream().filter(r -> "09A".equals(r.ticketNumber())).findFirst().orElseThrow();
        var rowB = board.queueList().stream().filter(r -> "09B".equals(r.ticketNumber())).findFirst().orElseThrow();

        assertEquals("PREP", rowA.phaseProgress().currentPhase());
        assertEquals("EXAM", rowB.phaseProgress().currentPhase());
        assertEquals(2400L, rowA.phaseProgress().totalDurationSeconds());
        assertEquals(900L, rowB.phaseProgress().totalDurationSeconds());
    }
}
