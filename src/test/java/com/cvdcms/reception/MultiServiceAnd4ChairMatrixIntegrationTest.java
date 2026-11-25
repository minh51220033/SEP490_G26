package com.cvdcms.reception;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.patientportal.PortalAppointmentService;
import com.cvdcms.repository.*;
import com.cvdcms.service.ClinicQueueCoordinatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:multi_service_4chair_it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;NON_KEYWORDS=MONTH,YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MultiServiceAnd4ChairMatrixIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    @Autowired private ClinicQueueCoordinatorService coordinator;
    @Autowired private QueueWorkflowService queueWorkflowService;
    @Autowired private DispatchService dispatchService;
    @Autowired private PortalAppointmentService portalAppointmentService;
    @Autowired private BranchRepository branchRepository;
    @Autowired private RoomRepository roomRepository;
    @Autowired private DentalChairRepository chairRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffProfileRepository staffProfileRepository;
    @Autowired private StaffBranchAssignmentRepository branchAssignmentRepository;
    @Autowired private WorkShiftRepository workShiftRepository;
    @Autowired private StaffScheduleRepository staffScheduleRepository;
    @Autowired private PatientProfileRepository patientProfileRepository;
    @Autowired private ServiceCategoryRepository serviceCategoryRepository;
    @Autowired private ServiceRepository serviceRepository;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private VisitRepository visitRepository;
    @Autowired private ClinicalQueueTicketRepository ticketRepository;

    private Branch branch;
    private Room room1;
    private Room room2;
    private WorkShift morningShift;
    private User adminUser;
    private User receptionUser;
    private Role docRole;
    private Service svcExam30;
    private Service svcExtract60;
    private LocalDate today;
    private LocalDate tomorrow;

    @BeforeEach
    void setUp() {
        int id = SEQ.incrementAndGet();
        today = LocalDate.now(ZONE);
        tomorrow = today.plusDays(1);

        branch = new Branch();
        branch.setBranchCode("CS-4C-" + id);
        branch.setBranchName("Chi nhanh 4 Ghe " + id);
        branch.setAddress("Ha Noi");
        branch.setIsActive(true);
        branch.setResourceEngineEnabled(false);
        branch.setOpeningTime(LocalTime.of(8, 0));
        branch.setClosingTime(LocalTime.of(21, 0));
        branch = branchRepository.saveAndFlush(branch);

        room1 = createRoom("P101-" + id, "Phong 101");
        room2 = createRoom("P102-" + id, "Phong 102");

        morningShift = new WorkShift();
        morningShift.setBranch(branch);
        morningShift.setShiftCode("SANG-" + id);
        morningShift.setShiftName("Ca Sang");
        morningShift.setStartTime(LocalTime.of(8, 0));
        morningShift.setEndTime(LocalTime.of(12, 0));
        morningShift.setIsActive(true);
        morningShift = workShiftRepository.saveAndFlush(morningShift);

        Role adminRole = ensureRole("ADMIN");
        Role letanRole = ensureRole("LE_TAN");
        docRole = ensureRole("BAC_SI");

        adminUser = createUser("admin_4c_" + id, adminRole, UserType.STAFF);
        receptionUser = createUser("letan_4c_" + id, letanRole, UserType.STAFF);
        StaffProfile rec = new StaffProfile();
        rec.setUser(receptionUser);
        rec.setStaffCode("LT-4C-" + id);
        rec.setFullName("Le Tan 4C");
        rec.setStatus(StaffStatus.ACTIVE);
        rec = staffProfileRepository.saveAndFlush(rec);
        assignBranch(rec, letanRole);

        ServiceCategory cat = new ServiceCategory();
        cat.setCategoryCode("CAT-4C-" + id);
        cat.setCategoryName("Nha khoa");
        cat.setCategoryType(ServiceType.PROCEDURE);
        cat.setIsActive(true);
        cat = serviceCategoryRepository.saveAndFlush(cat);

        svcExam30 = createService("DV-KHAM-" + id, "Kham tong quat", cat, 30, true);
        svcExtract60 = createService("DV-NHO-" + id, "Nho rang khon", cat, 60, true);
    }

    private Room createRoom(String code, String name) {
        Room r = new Room();
        r.setBranch(branch);
        r.setRoomCode(code);
        r.setRoomName(name);
        r.setRoomType(RoomType.TREATMENT);
        r.setStatus(RoomStatus.ACTIVE);
        return roomRepository.saveAndFlush(r);
    }

    private Service createService(String code, String name, ServiceCategory cat, int duration, boolean requiresChair) {
        Service s = new Service();
        s.setServiceCode(code);
        s.setServiceName(name);
        s.setCategory(cat);
        s.setServiceType(ServiceType.PROCEDURE);
        s.setBasePrice(BigDecimal.valueOf(200000));
        s.setEstimatedDurationMinutes(duration);
        s.setProcedureDurationMinutes(duration);
        s.setRequiresChair(requiresChair);
        s.setIsBookableOnline(true);
        s.setStatus(ServiceStatus.ACTIVE);
        return serviceRepository.saveAndFlush(s);
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
        u.setPhoneNumber("092" + String.format("%07d", uSeq));
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

    private StaffProfile createScheduledDoctor(String suffix, LocalDate date) {
        int id = SEQ.incrementAndGet();
        User u = createUser("doc4c_" + suffix + "_" + id, docRole, UserType.STAFF);
        StaffProfile doc = new StaffProfile();
        doc.setUser(u);
        doc.setStaffCode("BS4C-" + suffix + "-" + id);
        doc.setFullName("BS " + suffix);
        doc.setStatus(StaffStatus.ACTIVE);
        doc = staffProfileRepository.saveAndFlush(doc);
        assignBranch(doc, docRole);

        if (date != null) {
            StaffSchedule sch = new StaffSchedule();
            sch.setStaff(doc);
            sch.setBranch(branch);
            sch.setShift(morningShift);
            sch.setWorkDate(date);
            sch.setStatus(ScheduleStatus.SCHEDULED);
            staffScheduleRepository.saveAndFlush(sch);
        }
        return doc;
    }

    private DentalChair createChair(Room r, String code, ChairStatus status) {
        DentalChair c = new DentalChair();
        c.setBranch(branch);
        c.setRoom(r);
        c.setChairCode(code + "-" + SEQ.incrementAndGet());
        c.setChairName("Ghe " + code);
        c.setStatus(status);
        return chairRepository.saveAndFlush(c);
    }

    private PatientProfile createPatient(String name, User user) {
        int id = SEQ.incrementAndGet();
        PatientProfile p = new PatientProfile();
        p.setUser(user);
        p.setPatientCode("BN4C-" + id);
        p.setFullName(name);
        p.setPhoneNumber("093" + String.format("%07d", id));
        p.setAddress("Ha Noi");
        p.setGender("MALE");
        p.setStatus("ACTIVE");
        p.setPreferredBranch(branch);
        return patientProfileRepository.saveAndFlush(p);
    }

    private Visit createWaitingVisit(String patientName, StaffProfile doctor) {
        int id = SEQ.incrementAndGet();
        PatientProfile p = createPatient(patientName, null);
        Visit v = new Visit();
        v.setVisitCode("VIS4C-" + id);
        v.setPatientProfile(p);
        v.setBranch(branch);
        v.setPrimaryDoctor(doctor);
        v.setVisitType(VisitType.WALK_IN);
        v.setStatus(VisitStatus.WAITING);
        v.setCheckInAt(LocalDateTime.now(ZONE).minusMinutes(5));
        return visitRepository.saveAndFlush(v);
    }

    private void assert4ChairMatrix(int doctorsCount, int chairsCount, int expectedMaxCalled) {
        for (int i = 0; i < chairsCount; i++) {
            createChair(i < 2 ? room1 : room2, "C" + i, ChairStatus.AVAILABLE);
        }
        List<StaffProfile> docs = new ArrayList<>();
        for (int i = 0; i < doctorsCount; i++) {
            docs.add(createScheduledDoctor("D" + i, today));
        }
        for (StaffProfile d : docs) {
            Visit v = createWaitingVisit("BN " + d.getStaffCode(), d);
            coordinator.issueTicketForVisitIfEligible(v.getVisitId(), adminUser.getUserId());
        }
        if (doctorsCount == 0) {
            StaffProfile offDoc = createScheduledDoctor("Off", null);
            Visit v = createWaitingVisit("BN Off", offDoc);
            coordinator.issueTicketForVisitIfEligible(v.getVisitId(), adminUser.getUserId());
        }
        List<QueueTicket> called = ticketRepository.findForBoard(branch.getBranchId(), today, QueueType.EXAM).stream()
                .filter(t -> t.getStatus() == QueueStatus.CALLED).toList();
        assertEquals(expectedMaxCalled, called.size());
        Set<Long> uniqueChairs = new HashSet<>();
        for (QueueTicket t : called) {
            assertNotNull(t.getVisit().getRoomId());
            assertNotNull(t.getVisit().getChairId());
            assertTrue(uniqueChairs.add(t.getVisit().getChairId()));
        }
    }

    @Test
    @DisplayName("Section F: 4-chair x 4-doctor matrix combinations (4x4, 3x4, 2x4, 4x2, 0x4, 4x0)")
    void fourChairMatrixCombinations() {
        assert4ChairMatrix(4, 4, 4);
        setUp();
        assert4ChairMatrix(3, 4, 3);
        setUp();
        assert4ChairMatrix(2, 4, 2);
        setUp();
        assert4ChairMatrix(4, 2, 2);
        setUp();
        assert4ChairMatrix(0, 4, 0);
        setUp();
        assert4ChairMatrix(4, 0, 0);
    }

    @Test
    @DisplayName("Section B & C: Multi-service (>1 service) is rejected (400) while separate single-service bookings work cleanly")
    void multiServiceRejectedAndSeparateSingleServiceBookingsAllowed() {
        StaffProfile doc = createScheduledDoctor("MS", tomorrow);
        Role patientRole = ensureRole("BENH_NHAN");
        User patUser = createUser("pat_ms_" + SEQ.incrementAndGet(), patientRole, UserType.PATIENT);
        PatientProfile profile = createPatient("BN Single Service 1", patUser);

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", patUser);

        // Sending 2 services in a single request must be rejected (400 Bad Request)
        var multiReq = new PortalAppointmentService.MultiServiceBookingRequest(
                profile.getProfileId(), branch.getBranchId(), tomorrow, PreferredSession.MORNING,
                doc.getStaffId(), List.of(svcExam30.getServiceId(), svcExtract60.getServiceId()),
                LocalTime.of(8, 0), "Kham + Nho rang", UUID.randomUUID().toString());
        ResponseStatusException exMulti = assertThrows(ResponseStatusException.class,
                () -> portalAppointmentService.bookMultiService(session, multiReq));
        assertEquals(400, exMulti.getStatusCode().value());

        // Booking 1st service separately succeeds (30p: 08:00-08:30)
        var single1 = portalAppointmentService.book(session, new PortalAppointmentService.BookingRequest(
                profile.getProfileId(), branch.getBranchId(), tomorrow, PreferredSession.MORNING,
                doc.getStaffId(), svcExam30.getServiceId(), "Kham 30p", UUID.randomUUID().toString(), LocalTime.of(8, 0)));
        assertEquals(30, single1.durationMinutes());
        assertEquals(LocalTime.of(8, 0), single1.expectedTime());

        // Booking 2nd service in a separate request succeeds (60p: 08:30-09:30)
        User uSecond = createUser("pat_2nd_" + SEQ.incrementAndGet(), patientRole, UserType.PATIENT);
        PatientProfile pSecond = createPatient("BN Second", uSecond);
        MockHttpSession sSecond = new MockHttpSession();
        sSecond.setAttribute("loggedInUser", uSecond);
        var single2 = portalAppointmentService.book(sSecond, new PortalAppointmentService.BookingRequest(
                pSecond.getProfileId(), branch.getBranchId(), tomorrow, PreferredSession.MORNING,
                doc.getStaffId(), svcExtract60.getServiceId(), "Nho rang 60p", UUID.randomUUID().toString(), LocalTime.of(8, 30)));
        assertEquals(60, single2.durationMinutes());
        assertEquals(LocalTime.of(8, 30), single2.expectedTime());
    }

    @Test
    @DisplayName("Section G: Complete End-to-End 11-step queue + TURNOVER -> AVAILABLE auto-call")
    void endToEndQueueTurnoverAndAutoCall() {
        DentalChair chair = createChair(room1, "E2E", ChairStatus.AVAILABLE);
        StaffProfile doc = createScheduledDoctor("E2E", today);

        Visit v1 = createWaitingVisit("BN E2E 1", doc);
        Visit v2 = createWaitingVisit("BN E2E 2", doc);

        QueueTicket t1 = coordinator.issueTicketForVisitIfEligible(v1.getVisitId(), adminUser.getUserId());
        QueueTicket t2 = coordinator.issueTicketForVisitIfEligible(v2.getVisitId(), adminUser.getUserId());

        // t1 is CALLED with chair, t2 stays WAITING
        assertEquals(QueueStatus.CALLED, ticketRepository.findById(t1.getTicketId()).orElseThrow().getStatus());
        assertEquals(QueueStatus.WAITING, ticketRepository.findById(t2.getTicketId()).orElseThrow().getStatus());

        // Doctor enters exam -> IN_SERVICE & chair IN_USE
        coordinator.enterExaminationForDoctor(doc.getUser(), branch.getBranchId());
        assertEquals(QueueStatus.IN_SERVICE, ticketRepository.findById(t1.getTicketId()).orElseThrow().getStatus());
        assertEquals(ChairStatus.IN_USE, chairRepository.findById(chair.getChairId()).orElseThrow().getStatus());

        // Simulate chair entering TURNOVER -> t2 must NOT be called yet
        chair.setStatus(ChairStatus.TURNOVER);
        chairRepository.saveAndFlush(chair);
        t1.setStatus(QueueStatus.DONE);
        ticketRepository.saveAndFlush(t1);
        v1.setStatus(VisitStatus.COMPLETED);
        visitRepository.saveAndFlush(v1);

        coordinator.autoCallNextForDoctorIfIdle(branch.getBranchId(), doc.getStaffId(), adminUser.getUserId());
        assertEquals(QueueStatus.WAITING, ticketRepository.findById(t2.getTicketId()).orElseThrow().getStatus());

        // Complete chair turnover (TURNOVER -> AVAILABLE) -> automatically calls t2!
        MockHttpSession recSession = new MockHttpSession();
        recSession.setAttribute("loggedInUser", receptionUser);
        queueWorkflowService.completeChairTurnover(recSession, chair.getChairId(), null);

        assertEquals(ChairStatus.AVAILABLE, chairRepository.findById(chair.getChairId()).orElseThrow().getStatus());
        assertEquals(QueueStatus.CALLED, ticketRepository.findById(t2.getTicketId()).orElseThrow().getStatus());
        assertEquals(chair.getChairId(), visitRepository.findById(v2.getVisitId()).orElseThrow().getChairId());
    }

    @Test
    @DisplayName("Waterfall pulls unspecified waiting visit to idle doctor while keeping booked-doctor visit waiting")
    void waterfallPullsUnspecifiedButPreservesBookedDoctor() {
        createChair(room1, "WF1", ChairStatus.AVAILABLE);
        createChair(room2, "WF2", ChairStatus.AVAILABLE);
        StaffProfile docA = createScheduledDoctor("WFA", today);
        StaffProfile docB = createScheduledDoctor("WFB", today);

        // docA gets busy with vBusy
        Visit vBusy = createWaitingVisit("BN Busy A", docA);
        QueueTicket tBusy = coordinator.issueTicketForVisitIfEligible(vBusy.getVisitId(), adminUser.getUserId());
        assertEquals(QueueStatus.CALLED, ticketRepository.findById(tBusy.getTicketId()).orElseThrow().getStatus());

        // Create booked-for-docA visit (must NEVER be pulled by docB)
        int id = SEQ.incrementAndGet();
        PatientProfile pBooked = createPatient("BN Booked A", null);
        Appointment apptA = new Appointment();
        apptA.setAppointmentCode("APT-WF-" + id);
        apptA.setPatientProfile(pBooked);
        apptA.setBranch(branch);
        apptA.setDoctor(docA);
        apptA.setService(svcExam30);
        apptA.setAppointmentDate(today);
        apptA.setExpectedTime(LocalTime.of(10, 0));
        apptA.setExpectedEndTime(LocalTime.of(10, 30));
        apptA.setStatus(AppointmentStatus.CONFIRMED);
        apptA = appointmentRepository.saveAndFlush(apptA);

        Visit vBookedA = new Visit();
        vBookedA.setVisitCode("VIS-WFB-" + id);
        vBookedA.setPatientProfile(pBooked);
        vBookedA.setBranch(branch);
        vBookedA.setPrimaryDoctor(docA);
        vBookedA.setAppointmentId(apptA.getAppointmentId());
        vBookedA.setVisitType(VisitType.APPOINTMENT);
        vBookedA.setStatus(VisitStatus.WAITING);
        vBookedA.setCheckInAt(LocalDateTime.now(ZONE).minusMinutes(4));
        vBookedA = visitRepository.saveAndFlush(vBookedA);
        QueueTicket tBookedA = coordinator.issueTicketForVisitIfEligible(vBookedA.getVisitId(), adminUser.getUserId());
        assertEquals(QueueStatus.WAITING, ticketRepository.findById(tBookedA.getTicketId()).orElseThrow().getStatus());

        // Create unspecified walk-in visit initially assigned to busy docA
        Visit vUnspec = createWaitingVisit("BN Waterfall Unspec", docA);
        QueueTicket tUnspec = coordinator.issueTicketForVisitIfEligible(vUnspec.getVisitId(), adminUser.getUserId());
        assertEquals(QueueStatus.WAITING, ticketRepository.findById(tUnspec.getTicketId()).orElseThrow().getStatus());

        // docB becomes free and runs autoCall -> must pull vUnspec (NOT vBookedA!)
        var calledByB = coordinator.autoCallNextForDoctorIfIdle(branch.getBranchId(), docB.getStaffId(), adminUser.getUserId());
        assertTrue(calledByB.isPresent());
        assertEquals(tUnspec.getTicketId(), calledByB.get().getTicketId());
        assertEquals(docB.getStaffId(), visitRepository.findById(vUnspec.getVisitId()).orElseThrow().getPrimaryDoctor().getStaffId());
        assertEquals(QueueStatus.WAITING, ticketRepository.findById(tBookedA.getTicketId()).orElseThrow().getStatus());
        assertEquals(docA.getStaffId(), visitRepository.findById(vBookedA.getVisitId()).orElseThrow().getPrimaryDoctor().getStaffId());
    }
}
