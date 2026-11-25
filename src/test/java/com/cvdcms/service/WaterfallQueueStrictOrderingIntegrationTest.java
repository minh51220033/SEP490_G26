package com.cvdcms.service;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
        "spring.datasource.url=jdbc:h2:mem:waterfall_strict_it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;NON_KEYWORDS=MONTH,YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WaterfallQueueStrictOrderingIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    @Autowired private ClinicQueueCoordinatorService coordinator;
    @Autowired private QueueWorkflowService queueWorkflowService;
    @Autowired private BranchRepository branchRepository;
    @Autowired private RoomRepository roomRepository;
    @Autowired private DentalChairRepository chairRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffProfileRepository staffProfileRepository;
    @Autowired private StaffBranchAssignmentRepository branchAssignmentRepository;
    @Autowired private WorkShiftRepository workShiftRepository;
    @Autowired private StaffScheduleRepository staffScheduleRepository;
    @Autowired private DoctorServicePermissionRepository doctorServicePermissionRepository;
    @Autowired private PatientProfileRepository patientProfileRepository;
    @Autowired private ServiceCategoryRepository serviceCategoryRepository;
    @Autowired private ServiceRepository serviceRepository;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private VisitRepository visitRepository;
    @Autowired private ClinicalQueueTicketRepository ticketRepository;

    private Branch branch;
    private Room room1;
    private WorkShift allDayShift;
    private Role docRole;
    private User actorUser;
    private Service svcGeneral;
    private Service svcXray;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        int id = SEQ.incrementAndGet();
        today = LocalDate.now(ZONE);

        branch = new Branch();
        branch.setBranchCode("CS-WF-" + id);
        branch.setBranchName("Chi nhanh Waterfall " + id);
        branch.setAddress("Ha Noi");
        branch.setIsActive(true);
        branch.setResourceEngineEnabled(false);
        branch.setOpeningTime(LocalTime.of(0, 0));
        branch.setClosingTime(LocalTime.of(23, 59));
        branch = branchRepository.saveAndFlush(branch);

        room1 = new Room();
        room1.setBranch(branch);
        room1.setRoomCode("PWF-" + id);
        room1.setRoomName("Phong WF");
        room1.setRoomType(RoomType.TREATMENT);
        room1.setStatus(RoomStatus.ACTIVE);
        room1 = roomRepository.saveAndFlush(room1);

        allDayShift = new WorkShift();
        allDayShift.setBranch(branch);
        allDayShift.setShiftCode("ALL-" + id);
        allDayShift.setShiftName("Ca Ngay");
        allDayShift.setStartTime(LocalTime.of(0, 0));
        allDayShift.setEndTime(LocalTime.of(23, 59));
        allDayShift.setIsActive(true);
        allDayShift = workShiftRepository.saveAndFlush(allDayShift);

        docRole = ensureRole("BAC_SI");
        Role adminRole = ensureRole("ADMIN");
        actorUser = createUser("adm_wf_" + id, adminRole, UserType.STAFF);

        ServiceCategory cat = new ServiceCategory();
        cat.setCategoryCode("CAT-WF-" + id);
        cat.setCategoryName("Nha khoa WF");
        cat.setCategoryType(ServiceType.PROCEDURE);
        cat.setIsActive(true);
        cat = serviceCategoryRepository.saveAndFlush(cat);

        svcGeneral = createService(cat, "DV-GEN-" + id, "Kham tong quat", 30);
        svcXray = createService(cat, "DV-XR-" + id, "Chup X-Quang", 15);
    }

    private Role ensureRole(String name) {
        return roleRepository.findByRoleName(name).orElseGet(() -> {
            Role r = new Role();
            r.setRoleName(name);
            r.setIsActive(true);
            return roleRepository.saveAndFlush(r);
        });
    }

    private Service createService(ServiceCategory cat, String code, String name, int dur) {
        Service s = new Service();
        s.setCategory(cat);
        s.setServiceCode(code);
        s.setServiceName(name);
        s.setServiceType(ServiceType.PROCEDURE);
        s.setBasePrice(BigDecimal.valueOf(200000));
        s.setEstimatedDurationMinutes(dur);
        s.setProcedureDurationMinutes(dur);
        s.setRequiresChair(true);
        s.setIsBookableOnline(true);
        s.setStatus(ServiceStatus.ACTIVE);
        return serviceRepository.saveAndFlush(s);
    }

    private User createUser(String username, Role role, UserType type) {
        int uSeq = SEQ.incrementAndGet();
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@test.local");
        u.setPhoneNumber("094" + String.format("%07d", uSeq));
        u.setPassword("$2a$10$dummy");
        u.setUserType(type);
        u.setIsActive(true);
        u.setStatus("ACTIVE");
        u.setBranch(branch);
        u.setRoles(new HashSet<>(Set.of(role)));
        return userRepository.saveAndFlush(u);
    }

    private StaffProfile createDoctor(String name) {
        int id = SEQ.incrementAndGet();
        User u = createUser("doc_wf_" + id, docRole, UserType.STAFF);
        StaffProfile doc = new StaffProfile();
        doc.setUser(u);
        doc.setStaffCode("BSWF-" + id);
        doc.setFullName(name);
        doc.setStatus(StaffStatus.ACTIVE);
        doc = staffProfileRepository.saveAndFlush(doc);

        StaffBranchAssignment sba = new StaffBranchAssignment();
        sba.setStaff(doc);
        sba.setBranch(branch);
        sba.setRole(docRole);
        sba.setStatus(AssignmentStatus.ACTIVE);
        sba.setIsPrimary(true);
        sba.setStartDate(today.minusDays(1));
        branchAssignmentRepository.saveAndFlush(sba);

        StaffSchedule ss = new StaffSchedule();
        ss.setBranch(branch);
        ss.setStaff(doc);
        ss.setShift(allDayShift);
        ss.setWorkDate(today);
        ss.setStatus(ScheduleStatus.SCHEDULED);
        staffScheduleRepository.saveAndFlush(ss);
        return doc;
    }

    private DentalChair createChair(String code, ChairStatus status) {
        DentalChair c = new DentalChair();
        c.setBranch(branch);
        c.setRoom(room1);
        c.setChairCode(code + "-" + SEQ.incrementAndGet());
        c.setChairName("Ghe " + code);
        c.setStatus(status);
        return chairRepository.saveAndFlush(c);
    }

    private PatientProfile createPatient(String name) {
        int id = SEQ.incrementAndGet();
        PatientProfile p = new PatientProfile();
        p.setPatientCode("BNWF-" + id);
        p.setFullName(name);
        p.setPhoneNumber("095" + String.format("%07d", id));
        p.setAddress("Ha Noi");
        p.setGender("MALE");
        p.setStatus("ACTIVE");
        p.setPreferredBranch(branch);
        return patientProfileRepository.saveAndFlush(p);
    }

    private QueueTicket createWaitingTicket(String patientName, StaffProfile primaryDoctor, Appointment appt, int ticketNumber, LocalDateTime issuedAt) {
        PatientProfile p = createPatient(patientName);
        Visit v = new Visit();
        v.setVisitCode("VWF-" + SEQ.incrementAndGet());
        v.setPatientProfile(p);
        v.setBranch(branch);
        v.setPrimaryDoctor(primaryDoctor);
        v.setAppointmentId(appt != null ? appt.getAppointmentId() : null);
        v.setVisitType(appt != null ? VisitType.APPOINTMENT : VisitType.WALK_IN);
        v.setStatus(VisitStatus.WAITING);
        v.setIsBookedAndOnTime(false);
        v.setCheckInAt(issuedAt);
        v = visitRepository.saveAndFlush(v);

        QueueTicket t = new QueueTicket();
        t.setVisit(v);
        t.setBranch(branch);
        t.setQueueDate(today);
        t.setQueueType(QueueType.EXAM);
        t.setTicketNumber(ticketNumber);
        t.setDisplayNumber(String.format("%02d", ticketNumber));
        t.setStatus(QueueStatus.WAITING);
        t.setIsPriority(false);
        t.setIsEmergency(false);
        t.setPriorityScore(50);
        t.setPriorityReason("WALK_IN");
        t.setIssuedAt(issuedAt);
        return ticketRepository.saveAndFlush(t);
    }

    private Appointment createAppointment(StaffProfile doctor, boolean requested, Service svc) {
        PatientProfile p = createPatient("BN Appt " + SEQ.incrementAndGet());
        Appointment a = new Appointment();
        a.setAppointmentCode("APT-WF-" + SEQ.incrementAndGet());
        a.setPatientProfile(p);
        a.setBranch(branch);
        a.setDoctor(doctor);
        a.setIsDoctorRequested(requested);
        a.setService(svc);
        a.setAppointmentDate(today);
        a.setPreferredSession(PreferredSession.MORNING);
        a.setExpectedTime(LocalTime.of(10, 0));
        a.setDurationMinutes(AppointmentSlotService.duration(svc));
        a.syncExpectedEndTime();
        a.setSource(AppointmentSource.ONLINE);
        a.setStatus(AppointmentStatus.CONFIRMED);
        return appointmentRepository.saveAndFlush(a);
    }

    @Test
    @DisplayName("Test 1: Hai BN cho (05 truoc, 07 sau), BS Tran B ranh truoc -> goi 05, BS Pham D ranh sau -> goi 07")
    void test1TwoWaitingPatientsOrderedRegardlessOfWhichDoctorFreesFirst() {
        createChair("C1", ChairStatus.AVAILABLE);
        createChair("C2", ChairStatus.AVAILABLE);
        StaffProfile bsTranB = createDoctor("BS Tran B");
        StaffProfile bsPhamD = createDoctor("BS Pham D");

        LocalDateTime base = LocalDateTime.now(ZONE).minusMinutes(10);
        QueueTicket t05 = createWaitingTicket("Benh nhan 05", null, null, 5, base);
        QueueTicket t07 = createWaitingTicket("Benh nhan 07", null, null, 7, base.plusMinutes(2));

        // TV Display shows "Chưa phân công" for WAITING general queue
        var tvBefore = queueWorkflowService.publicBoard(branch.getBranchId());
        assertEquals(2, tvBefore.queueList().size());
        assertEquals("Chưa phân công", tvBefore.queueList().get(0).doctorDisplay());
        assertEquals("Chưa phân công", tvBefore.queueList().get(1).doctorDisplay());

        // BS Tran B frees first -> must call 05
        var called1 = coordinator.autoCallNextForDoctorIfIdle(branch.getBranchId(), bsTranB.getStaffId(), actorUser.getUserId());
        assertTrue(called1.isPresent());
        assertEquals(t05.getTicketId(), called1.get().getTicketId());
        assertEquals(bsTranB.getStaffId(), visitRepository.findById(t05.getVisit().getVisitId()).orElseThrow().getPrimaryDoctor().getStaffId());

        // BS Pham D frees next -> must call 07
        var called2 = coordinator.autoCallNextForDoctorIfIdle(branch.getBranchId(), bsPhamD.getStaffId(), actorUser.getUserId());
        assertTrue(called2.isPresent());
        assertEquals(t07.getTicketId(), called2.get().getTicketId());
        assertEquals(bsPhamD.getStaffId(), visitRepository.findById(t07.getVisit().getVisitId()).orElseThrow().getPrimaryDoctor().getStaffId());
    }

    @Test
    @DisplayName("Test 2: Phieu 07 truoc day gan BS Pham D nhung 05 dung truoc -> BS Pham D ranh phai goi 05, khong de 07 nhay hang")
    void test2LaterTicketWithTentativeDoctorCannotJumpAheadOfEarlierTicket() {
        createChair("C1", ChairStatus.AVAILABLE);
        createChair("C2", ChairStatus.AVAILABLE);
        StaffProfile bsTranB = createDoctor("BS Tran B");
        StaffProfile bsPhamD = createDoctor("BS Pham D");

        LocalDateTime base = LocalDateTime.now(ZONE).minusMinutes(10);
        QueueTicket t05 = createWaitingTicket("Benh nhan 05", bsTranB, null, 5, base);
        QueueTicket t07 = createWaitingTicket("Benh nhan 07", bsPhamD, null, 7, base.plusMinutes(2));

        // BS Pham D finishes / becomes idle first -> must call 05 (head of queue), NOT 07!
        var called = coordinator.autoCallNextForDoctorIfIdle(branch.getBranchId(), bsPhamD.getStaffId(), actorUser.getUserId());
        assertTrue(called.isPresent());
        assertEquals(t05.getTicketId(), called.get().getTicketId());
        assertEquals(QueueStatus.WAITING, ticketRepository.findById(t07.getTicketId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("Test 3: Dau hang 05 can dich vu tong quat ma BS X-Quang khong lam duoc -> BS X-Quang goi 07, 05 giu nguyen vi tri dau hang")
    void test3HeadOfQueuePreservedWhenIdleDoctorLacksRequiredServicePermission() {
        createChair("C1", ChairStatus.AVAILABLE);
        createChair("C2", ChairStatus.AVAILABLE);
        StaffProfile bsXray = createDoctor("BS X-Quang");
        StaffProfile bsGeneral = createDoctor("BS Tong Quat");

        DoctorServicePermission perm = new DoctorServicePermission();
        perm.setStaff(bsXray);
        perm.setService(svcXray);
        perm.setBranch(branch);
        perm.setEffectiveFrom(today.minusDays(1));
        perm.setStatus(DoctorServicePermissionStatus.ACTIVE);
        doctorServicePermissionRepository.saveAndFlush(perm);

        Appointment apptGen = createAppointment(null, false, svcGeneral);
        Appointment apptXray = createAppointment(null, false, svcXray);

        LocalDateTime base = LocalDateTime.now(ZONE).minusMinutes(10);
        QueueTicket t05 = createWaitingTicket("BN Tong Quat 05", null, apptGen, 5, base);
        QueueTicket t07 = createWaitingTicket("BN X-Quang 07", null, apptXray, 7, base.plusMinutes(2));

        // BS X-Quang becomes idle -> cannot serve svcGeneral (05), calls 07; 05 stays WAITING at head
        var calledXray = coordinator.autoCallNextForDoctorIfIdle(branch.getBranchId(), bsXray.getStaffId(), actorUser.getUserId());
        assertTrue(calledXray.isPresent());
        assertEquals(t07.getTicketId(), calledXray.get().getTicketId());
        assertEquals(QueueStatus.WAITING, ticketRepository.findById(t05.getTicketId()).orElseThrow().getStatus());

        // BS General becomes idle -> immediately calls 05
        var calledGen = coordinator.autoCallNextForDoctorIfIdle(branch.getBranchId(), bsGeneral.getStaffId(), actorUser.getUserId());
        assertTrue(calledGen.isPresent());
        assertEquals(t05.getTicketId(), calledGen.get().getTicketId());
    }

    @Test
    @DisplayName("Test 4: Dat rieng BS Pham D (isDoctorRequested=true) -> BS Tran B ranh KHONG duoc tu dong keo")
    void test4BookedDoctorPatientNotPulledByOtherIdleDoctor() {
        createChair("C1", ChairStatus.AVAILABLE);
        StaffProfile bsPhamD = createDoctor("BS Pham D");
        StaffProfile bsTranB = createDoctor("BS Tran B");

        Appointment bookedAppt = createAppointment(bsPhamD, true, svcGeneral);
        QueueTicket t05 = createWaitingTicket("BN Dat Rieng Pham D", bsPhamD, bookedAppt, 5, LocalDateTime.now(ZONE).minusMinutes(5));

        var calledByTranB = coordinator.autoCallNextForDoctorIfIdle(branch.getBranchId(), bsTranB.getStaffId(), actorUser.getUserId());
        assertTrue(calledByTranB.isEmpty(), "BS Tran B must NOT pull patient who requested BS Pham D");
        assertEquals(QueueStatus.WAITING, ticketRepository.findById(t05.getTicketId()).orElseThrow().getStatus());

        var calledByPhamD = coordinator.autoCallNextForDoctorIfIdle(branch.getBranchId(), bsPhamD.getStaffId(), actorUser.getUserId());
        assertTrue(calledByPhamD.isPresent());
        assertEquals(t05.getTicketId(), calledByPhamD.get().getTicketId());
    }

    @Test
    @DisplayName("Test 5: Nhieu BN cung dat rieng 1 bac si -> goi dung thu tu waitingQueueComparator")
    void test5MultiplePatientsBookedSameDoctorFollowQueueOrder() {
        createChair("C1", ChairStatus.AVAILABLE);
        StaffProfile bsPhamD = createDoctor("BS Pham D");

        Appointment a1 = createAppointment(bsPhamD, true, svcGeneral);
        Appointment a2 = createAppointment(bsPhamD, true, svcGeneral);
        LocalDateTime base = LocalDateTime.now(ZONE).minusMinutes(10);
        QueueTicket t1 = createWaitingTicket("BN Dat 1", bsPhamD, a1, 1, base);
        QueueTicket t2 = createWaitingTicket("BN Dat 2", bsPhamD, a2, 2, base.plusMinutes(1));

        var first = coordinator.autoCallNextForDoctorIfIdle(branch.getBranchId(), bsPhamD.getStaffId(), actorUser.getUserId());
        assertTrue(first.isPresent());
        assertEquals(t1.getTicketId(), first.get().getTicketId());
        assertEquals(QueueStatus.WAITING, ticketRepository.findById(t2.getTicketId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("Test 6: Hoan thanh ca kham -> giai phong ghe/phong va goi dung benh nhan ke tiep theo thu tu hang doi")
    void test6CompleteVisitReleasesChairAndCallsNextHeadOfQueue() {
        DentalChair chair = createChair("C1", ChairStatus.IN_USE);
        StaffProfile bsPhamD = createDoctor("BS Pham D");

        PatientProfile pCurrent = createPatient("BN Dang Kham");
        Visit vCurrent = new Visit();
        vCurrent.setVisitCode("VCUR-" + SEQ.incrementAndGet());
        vCurrent.setPatientProfile(pCurrent);
        vCurrent.setBranch(branch);
        vCurrent.setPrimaryDoctor(bsPhamD);
        vCurrent.setRoomId(room1.getRoomId());
        vCurrent.setChairId(chair.getChairId());
        vCurrent.setVisitType(VisitType.WALK_IN);
        vCurrent.setStatus(VisitStatus.IN_EXAM);
        vCurrent.setCheckInAt(LocalDateTime.now(ZONE).minusMinutes(20));
        vCurrent = visitRepository.saveAndFlush(vCurrent);

        QueueTicket tCurrent = new QueueTicket();
        tCurrent.setVisit(vCurrent);
        tCurrent.setBranch(branch);
        tCurrent.setQueueDate(today);
        tCurrent.setQueueType(QueueType.EXAM);
        tCurrent.setTicketNumber(1);
        tCurrent.setDisplayNumber("01");
        tCurrent.setStatus(QueueStatus.IN_SERVICE);
        tCurrent.setIssuedAt(LocalDateTime.now(ZONE).minusMinutes(20));
        tCurrent = ticketRepository.saveAndFlush(tCurrent);

        LocalDateTime base = LocalDateTime.now(ZONE).minusMinutes(10);
        QueueTicket t05 = createWaitingTicket("BN Cho 05", null, null, 5, base);
        QueueTicket t07 = createWaitingTicket("BN Cho 07", null, null, 7, base.plusMinutes(2));

        // Complete current visit (completeVisitAndAutoCallNext closes tCurrent & releases chair)
        vCurrent.setStatus(VisitStatus.COMPLETED);
        vCurrent.setActualEndTime(LocalDateTime.now(ZONE));
        visitRepository.saveAndFlush(vCurrent);

        var next = coordinator.completeVisitAndAutoCallNext(vCurrent, actorUser);
        assertTrue(next.isPresent());
        assertEquals(t05.getTicketId(), next.get().getTicketId());
        assertEquals(QueueStatus.WAITING, ticketRepository.findById(t07.getTicketId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("Test 7: Nhieu bac si ranh dong thoi -> phan bo dung 2 BN dau hang khong trung lap")
    void test7MultipleIdleDoctorsReceiveDistinctHeadTickets() {
        createChair("C1", ChairStatus.AVAILABLE);
        createChair("C2", ChairStatus.AVAILABLE);
        createDoctor("BS A");
        createDoctor("BS B");

        LocalDateTime base = LocalDateTime.now(ZONE).minusMinutes(10);
        QueueTicket t1 = createWaitingTicket("BN 1", null, null, 1, base);
        QueueTicket t2 = createWaitingTicket("BN 2", null, null, 2, base.plusMinutes(1));

        int called = coordinator.autoCallNextForAnyIdleDoctor(branch.getBranchId(), actorUser.getUserId());
        assertEquals(2, called);

        Visit v1 = visitRepository.findById(t1.getVisit().getVisitId()).orElseThrow();
        Visit v2 = visitRepository.findById(t2.getVisit().getVisitId()).orElseThrow();
        assertNotNull(v1.getPrimaryDoctor());
        assertNotNull(v2.getPrimaryDoctor());
        assertNotEquals(v1.getPrimaryDoctor().getStaffId(), v2.getPrimaryDoctor().getStaffId());
        assertNotEquals(v1.getChairId(), v2.getChairId());
    }

    @Test
    @DisplayName("Test 8: Race condition 2 luong cung goi autoCallNext -> khong trung ticket, khong trung ghe")
    void test8ConcurrentAutoCallRaceNoDoubleAllocation() throws Exception {
        createChair("C1", ChairStatus.AVAILABLE);
        StaffProfile bs1 = createDoctor("BS Race 1");
        StaffProfile bs2 = createDoctor("BS Race 2");

        QueueTicket tOnly = createWaitingTicket("BN Race", null, null, 1, LocalDateTime.now(ZONE).minusMinutes(5));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger calledCount = new AtomicInteger();

        Callable<Void> r1 = () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            if (coordinator.autoCallNextForDoctorIfIdle(branch.getBranchId(), bs1.getStaffId(), actorUser.getUserId()).isPresent()) {
                calledCount.incrementAndGet();
            }
            return null;
        };
        Callable<Void> r2 = () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            if (coordinator.autoCallNextForDoctorIfIdle(branch.getBranchId(), bs2.getStaffId(), actorUser.getUserId()).isPresent()) {
                calledCount.incrementAndGet();
            }
            return null;
        };

        Future<Void> f1 = pool.submit(r1);
        Future<Void> f2 = pool.submit(r2);
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(1, calledCount.get());
        assertEquals(QueueStatus.CALLED, ticketRepository.findById(tOnly.getTicketId()).orElseThrow().getStatus());
    }
}
