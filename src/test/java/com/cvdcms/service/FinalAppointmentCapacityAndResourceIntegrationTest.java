package com.cvdcms.service;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.patientportal.PortalAppointmentService;
import com.cvdcms.repository.*;
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
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:final_appt_cap_res_it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;NON_KEYWORDS=MONTH,YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FinalAppointmentCapacityAndResourceIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    @Autowired private PortalAppointmentService portalAppointmentService;
    @Autowired private BranchRepository branchRepository;
    @Autowired private RoomRepository roomRepository;
    @Autowired private DentalChairRepository chairRepository;
    @Autowired private ClinicEquipmentRepository equipmentRepository;
    @Autowired private ServiceResourceRequirementRepository requirementRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffProfileRepository staffProfileRepository;
    @Autowired private StaffBranchAssignmentRepository branchAssignmentRepository;
    @Autowired private WorkShiftRepository workShiftRepository;
    @Autowired private StaffScheduleRepository staffScheduleRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private PatientProfileRepository patientProfileRepository;
    @Autowired private ServiceCategoryRepository serviceCategoryRepository;
    @Autowired private ServiceRepository serviceRepository;
    @Autowired private AppointmentRepository appointmentRepository;

    private Branch branch;
    private Room room1;
    private WorkShift morningShift;
    private WorkShift afternoonShift;
    private Role docRole;
    private Role patRole;
    private Service svc15;
    private Service svc30;
    private Service svc60;
    private Service svc90;
    private Service svcLaser25;
    private Service svcNoEq30;
    private LocalDate tomorrow;

    @BeforeEach
    void setUp() {
        int id = SEQ.incrementAndGet();
        tomorrow = LocalDate.now(ZONE).plusDays(2);

        branch = new Branch();
        branch.setBranchCode("CS-FIN-" + id);
        branch.setBranchName("Chi nhanh Final " + id);
        branch.setAddress("Ha Noi");
        branch.setIsActive(true);
        branch.setResourceEngineEnabled(false);
        branch.setOpeningTime(LocalTime.of(8, 0));
        branch.setClosingTime(LocalTime.of(21, 0));
        branch = branchRepository.saveAndFlush(branch);

        room1 = new Room();
        room1.setBranch(branch);
        room1.setRoomCode("P101-" + id);
        room1.setRoomName("Phong 101");
        room1.setRoomType(RoomType.TREATMENT);
        room1.setStatus(RoomStatus.ACTIVE);
        room1 = roomRepository.saveAndFlush(room1);

        morningShift = new WorkShift();
        morningShift.setBranch(branch);
        morningShift.setShiftCode("SANG-" + id);
        morningShift.setShiftName("Ca Sang");
        morningShift.setStartTime(LocalTime.of(8, 0));
        morningShift.setEndTime(LocalTime.of(12, 0));
        morningShift.setIsActive(true);
        morningShift = workShiftRepository.saveAndFlush(morningShift);

        afternoonShift = new WorkShift();
        afternoonShift.setBranch(branch);
        afternoonShift.setShiftCode("CHIEU-" + id);
        afternoonShift.setShiftName("Ca Chieu");
        afternoonShift.setStartTime(LocalTime.of(13, 0));
        afternoonShift.setEndTime(LocalTime.of(17, 0));
        afternoonShift.setIsActive(true);
        afternoonShift = workShiftRepository.saveAndFlush(afternoonShift);

        docRole = ensureRole("BAC_SI");
        patRole = ensureRole("BENH_NHAN");

        ServiceCategory cat = new ServiceCategory();
        cat.setCategoryCode("CAT-FIN-" + id);
        cat.setCategoryName("Nha khoa " + id);
        cat.setCategoryType(ServiceType.PROCEDURE);
        cat.setIsActive(true);
        cat = serviceCategoryRepository.saveAndFlush(cat);

        svc15 = createService(cat, "DV15-" + id, "Kham 15p", 15, true);
        svc30 = createService(cat, "DV30-" + id, "Kham 30p", 30, true);
        svc60 = createService(cat, "DV60-" + id, "Nho rang 60p", 60, true);
        svc90 = createService(cat, "DV90-" + id, "Implant 90p", 90, true);
        svcLaser25 = createService(cat, "DVLASER-" + id, "Laser 25p", 25, true);
        svcNoEq30 = createService(cat, "DVNOEQ-" + id, "Chup X-Quang 30p", 30, false);

        ServiceResourceRequirement laserReq = new ServiceResourceRequirement();
        laserReq.setService(svcLaser25);
        laserReq.setResourceKind(ResourceRequirementKind.EQUIPMENT_TYPE);
        laserReq.setEquipmentType(EquipmentType.LASER);
        laserReq.setIsRequired(true);
        requirementRepository.saveAndFlush(laserReq);

        ServiceResourceRequirement xrayReq = new ServiceResourceRequirement();
        xrayReq.setService(svcNoEq30);
        xrayReq.setResourceKind(ResourceRequirementKind.EQUIPMENT_TYPE);
        xrayReq.setEquipmentType(EquipmentType.XRAY);
        xrayReq.setIsRequired(true);
        requirementRepository.saveAndFlush(xrayReq);
    }

    private Role ensureRole(String name) {
        return roleRepository.findByRoleName(name).orElseGet(() -> {
            Role r = new Role();
            r.setRoleName(name);
            r.setIsActive(true);
            return roleRepository.saveAndFlush(r);
        });
    }

    private Service createService(ServiceCategory cat, String code, String name, int duration, boolean requiresChair) {
        Service s = new Service();
        s.setCategory(cat);
        s.setServiceCode(code);
        s.setServiceName(name);
        s.setServiceType(ServiceType.PROCEDURE);
        s.setEstimatedDurationMinutes(duration);
        s.setProcedureDurationMinutes(duration);
        s.setRequiresChair(requiresChair);
        s.setBasePrice(BigDecimal.valueOf(300000));
        s.setStatus(ServiceStatus.ACTIVE);
        s.setIsBookableOnline(true);
        return serviceRepository.saveAndFlush(s);
    }

    private DentalChair createChair(String code, ChairStatus status) {
        DentalChair c = new DentalChair();
        c.setBranch(branch);
        c.setRoom(room1);
        c.setChairCode(code);
        c.setChairName("Ghe " + code);
        c.setStatus(status);
        return chairRepository.saveAndFlush(c);
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

    private StaffProfile createDoctor(String suffix, WorkShift shift) {
        int id = SEQ.incrementAndGet();
        User u = createUser("bs_fin_" + suffix + "_" + id, docRole, UserType.STAFF);

        StaffProfile sp = new StaffProfile();
        sp.setUser(u);
        sp.setStaffCode("NVFIN" + id);
        sp.setFullName("BS " + suffix);
        sp.setSpecialty("Tong quat");
        sp.setStatus(StaffStatus.ACTIVE);
        sp = staffProfileRepository.saveAndFlush(sp);

        StaffBranchAssignment sba = new StaffBranchAssignment();
        sba.setStaff(sp);
        sba.setBranch(branch);
        sba.setRole(docRole);
        sba.setStatus(AssignmentStatus.ACTIVE);
        sba.setIsPrimary(true);
        sba.setStartDate(tomorrow.minusDays(5));
        branchAssignmentRepository.saveAndFlush(sba);

        StaffSchedule ss = new StaffSchedule();
        ss.setBranch(branch);
        ss.setStaff(sp);
        ss.setShift(shift);
        ss.setWorkDate(tomorrow);
        ss.setStatus(ScheduleStatus.SCHEDULED);
        staffScheduleRepository.saveAndFlush(ss);

        return sp;
    }

    private MockHttpSession patientSession(String suffix) {
        int id = SEQ.incrementAndGet();
        User u = createUser("pat_fin_" + suffix + "_" + id, patRole, UserType.PATIENT);

        PatientProfile p = new PatientProfile();
        p.setUser(u);
        p.setPatientCode("BNFIN" + id);
        p.setFullName("BN " + suffix);
        p.setPhoneNumber("092" + String.format("%07d", id));
        p.setAddress("Ha Noi");
        p.setGender("MALE");
        p.setPreferredBranch(branch);
        p.setStatus("ACTIVE");
        p = patientProfileRepository.saveAndFlush(p);

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", u);
        session.setAttribute("PROFILE_ID", p.getProfileId());
        return session;
    }

    private Long profileId(MockHttpSession session) {
        return (Long) session.getAttribute("PROFILE_ID");
    }

    private LocalTime endTimeOf(Long appointmentId) {
        return appointmentRepository.findById(appointmentId).orElseThrow().getExpectedEndTime();
    }

    @Test
    @DisplayName("15/30/60/90m durations + multi-service sequential slots & idempotent replay")
    void durationVariantsAndMultiServiceBooking() {
        createChair("G1-" + SEQ.incrementAndGet(), ChairStatus.AVAILABLE);
        StaffProfile doc = createDoctor("DUR", morningShift);

        var s1 = patientSession("d15");
        var v15 = portalAppointmentService.book(s1, new PortalAppointmentService.BookingRequest(
                profileId(s1), branch.getBranchId(), tomorrow, PreferredSession.MORNING,
                doc.getStaffId(), svc15.getServiceId(), "15m", UUID.randomUUID().toString(), LocalTime.of(8, 0)));
        assertEquals(15, v15.durationMinutes());
        assertEquals(LocalTime.of(8, 15), endTimeOf(v15.id()));

        var s2 = patientSession("d30");
        var v30 = portalAppointmentService.book(s2, new PortalAppointmentService.BookingRequest(
                profileId(s2), branch.getBranchId(), tomorrow, PreferredSession.MORNING,
                doc.getStaffId(), svc30.getServiceId(), "30m", UUID.randomUUID().toString(), LocalTime.of(8, 15)));
        assertEquals(30, v30.durationMinutes());
        assertEquals(LocalTime.of(8, 45), endTimeOf(v30.id()));

        var s3 = patientSession("d60");
        var v60 = portalAppointmentService.book(s3, new PortalAppointmentService.BookingRequest(
                profileId(s3), branch.getBranchId(), tomorrow, PreferredSession.MORNING,
                doc.getStaffId(), svc60.getServiceId(), "60m", UUID.randomUUID().toString(), LocalTime.of(8, 45)));
        assertEquals(60, v60.durationMinutes());
        assertEquals(LocalTime.of(9, 45), endTimeOf(v60.id()));

        var s4 = patientSession("d90");
        var v90 = portalAppointmentService.book(s4, new PortalAppointmentService.BookingRequest(
                profileId(s4), branch.getBranchId(), tomorrow, PreferredSession.MORNING,
                doc.getStaffId(), svc90.getServiceId(), "90m", UUID.randomUUID().toString(), LocalTime.of(9, 45)));
        assertEquals(90, v90.durationMinutes());
        assertEquals(LocalTime.of(11, 15), endTimeOf(v90.id()));

        StaffProfile doc2 = createDoctor("MULTI", afternoonShift);
        createChair("G2-" + SEQ.incrementAndGet(), ChairStatus.AVAILABLE);
        var s5 = patientSession("multi");
        ResponseStatusException exMulti = assertThrows(ResponseStatusException.class, () ->
                portalAppointmentService.bookMultiService(s5, new PortalAppointmentService.MultiServiceBookingRequest(
                        profileId(s5), branch.getBranchId(), tomorrow, PreferredSession.AFTERNOON,
                        doc2.getStaffId(), List.of(svc30.getServiceId(), svc60.getServiceId()),
                        LocalTime.of(13, 0), "multi", UUID.randomUUID().toString())));
        assertEquals(400, exMulti.getStatusCode().value());

        String reqKey = UUID.randomUUID().toString();
        var single = portalAppointmentService.bookMultiService(s5, new PortalAppointmentService.MultiServiceBookingRequest(
                profileId(s5), branch.getBranchId(), tomorrow, PreferredSession.AFTERNOON,
                doc2.getStaffId(), List.of(svc30.getServiceId()),
                LocalTime.of(13, 0), "single", reqKey));
        assertEquals(30, single.totalDurationMinutes());
        assertEquals(1, single.appointments().size());
        assertEquals(LocalTime.of(13, 0), single.appointments().get(0).expectedTime());
        assertEquals(LocalTime.of(13, 30), endTimeOf(single.appointments().get(0).id()));

        var replay = portalAppointmentService.bookMultiService(s5, new PortalAppointmentService.MultiServiceBookingRequest(
                profileId(s5), branch.getBranchId(), tomorrow, PreferredSession.AFTERNOON,
                doc2.getStaffId(), List.of(svc30.getServiceId()),
                LocalTime.of(13, 0), "single", reqKey));
        assertEquals(single.bookingGroupCode(), replay.bookingGroupCode());
        assertEquals(1, replay.appointments().size());
    }

    @Test
    @DisplayName("Same doctor overlapping rejected (409) and adjacent boundary [09:00,10:00) vs [10:00,10:30) allowed")
    void doctorOverlapRejectedAndBoundaryAllowed() {
        createChair("G1-" + SEQ.incrementAndGet(), ChairStatus.AVAILABLE);
        createChair("G2-" + SEQ.incrementAndGet(), ChairStatus.AVAILABLE);
        StaffProfile doc = createDoctor("OV", morningShift);

        var s1 = patientSession("ov1");
        portalAppointmentService.book(s1, new PortalAppointmentService.BookingRequest(
                profileId(s1), branch.getBranchId(), tomorrow, PreferredSession.MORNING,
                doc.getStaffId(), svc60.getServiceId(), "first", UUID.randomUUID().toString(), LocalTime.of(9, 0)));

        var s2 = patientSession("ov2");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                portalAppointmentService.book(s2, new PortalAppointmentService.BookingRequest(
                        profileId(s2), branch.getBranchId(), tomorrow, PreferredSession.MORNING,
                        doc.getStaffId(), svc30.getServiceId(), "overlap", UUID.randomUUID().toString(), LocalTime.of(9, 30))));
        assertEquals(409, ex.getStatusCode().value());

        var vBoundary = portalAppointmentService.book(s2, new PortalAppointmentService.BookingRequest(
                profileId(s2), branch.getBranchId(), tomorrow, PreferredSession.MORNING,
                doc.getStaffId(), svc30.getServiceId(), "adjacent", UUID.randomUUID().toString(), LocalTime.of(10, 0)));
        assertEquals(LocalTime.of(10, 0), vBoundary.expectedTime());
    }

    @Test
    @DisplayName("Chair exhaustion (1 chair < 2 doctors) and 0 operational chairs rejected (409)")
    void chairExhaustionAndZeroChairsRejected() {
        DentalChair chair = createChair("G1-" + SEQ.incrementAndGet(), ChairStatus.AVAILABLE);
        StaffProfile d1 = createDoctor("C1", morningShift);
        StaffProfile d2 = createDoctor("C2", morningShift);

        var s1 = patientSession("c1");
        portalAppointmentService.book(s1, new PortalAppointmentService.BookingRequest(
                profileId(s1), branch.getBranchId(), tomorrow, PreferredSession.MORNING,
                d1.getStaffId(), svc30.getServiceId(), "ok", UUID.randomUUID().toString(), LocalTime.of(9, 0)));

        var s2 = patientSession("c2");
        ResponseStatusException exFull = assertThrows(ResponseStatusException.class, () ->
                portalAppointmentService.book(s2, new PortalAppointmentService.BookingRequest(
                        profileId(s2), branch.getBranchId(), tomorrow, PreferredSession.MORNING,
                        d2.getStaffId(), svc30.getServiceId(), "no chair", UUID.randomUUID().toString(), LocalTime.of(9, 0))));
        assertEquals(409, exFull.getStatusCode().value());

        chair.setStatus(ChairStatus.MAINTENANCE);
        chairRepository.saveAndFlush(chair);

        var s3 = patientSession("c3");
        ResponseStatusException exZero = assertThrows(ResponseStatusException.class, () ->
                portalAppointmentService.book(s3, new PortalAppointmentService.BookingRequest(
                        profileId(s3), branch.getBranchId(), tomorrow, PreferredSession.MORNING,
                        d1.getStaffId(), svc30.getServiceId(), "0 chair", UUID.randomUUID().toString(), LocalTime.of(10, 0))));
        assertEquals(409, exZero.getStatusCode().value());
    }

    @Test
    @DisplayName("Approved leave & outside shift rejected; cancel releases slot for next patient")
    void leaveOutsideShiftAndCancelRelease() {
        createChair("G1-" + SEQ.incrementAndGet(), ChairStatus.AVAILABLE);
        StaffProfile d1 = createDoctor("LV", morningShift);

        var s1 = patientSession("lv1");
        var booked = portalAppointmentService.book(s1, new PortalAppointmentService.BookingRequest(
                profileId(s1), branch.getBranchId(), tomorrow, PreferredSession.MORNING,
                d1.getStaffId(), svc60.getServiceId(), "hold", UUID.randomUUID().toString(), LocalTime.of(9, 0)));

        var s2 = patientSession("lv2");
        assertThrows(ResponseStatusException.class, () ->
                portalAppointmentService.book(s2, new PortalAppointmentService.BookingRequest(
                        profileId(s2), branch.getBranchId(), tomorrow, PreferredSession.MORNING,
                        d1.getStaffId(), svc30.getServiceId(), "blocked", UUID.randomUUID().toString(), LocalTime.of(9, 0))));

        portalAppointmentService.cancel(s1, booked.id(), new PortalAppointmentService.CancelRequest("Doi lich"));
        var rebooked = portalAppointmentService.book(s2, new PortalAppointmentService.BookingRequest(
                profileId(s2), branch.getBranchId(), tomorrow, PreferredSession.MORNING,
                d1.getStaffId(), svc30.getServiceId(), "reopened", UUID.randomUUID().toString(), LocalTime.of(9, 0)));
        assertEquals(LocalTime.of(9, 0), rebooked.expectedTime());

        // Outside shift end (11:30 + 60m = 12:30 > 12:00)
        assertThrows(ResponseStatusException.class, () ->
                portalAppointmentService.book(s2, new PortalAppointmentService.BookingRequest(
                        profileId(s2), branch.getBranchId(), tomorrow, PreferredSession.MORNING,
                        d1.getStaffId(), svc60.getServiceId(), "overflow", UUID.randomUUID().toString(), LocalTime.of(11, 30))));

        // Approved leave blocks doctor on that day
        LeaveRequest lr = new LeaveRequest();
        lr.setStaff(d1);
        lr.setBranch(branch);
        lr.setShift(morningShift);
        lr.setStartDate(tomorrow);
        lr.setEndDate(tomorrow);
        lr.setStatus(LeaveStatus.APPROVED);
        lr.setReason("Nghi phep");
        leaveRequestRepository.saveAndFlush(lr);

        var s3 = patientSession("lv3");
        assertThrows(ResponseStatusException.class, () ->
                portalAppointmentService.book(s3, new PortalAppointmentService.BookingRequest(
                        profileId(s3), branch.getBranchId(), tomorrow, PreferredSession.MORNING,
                        d1.getStaffId(), svc30.getServiceId(), "on leave", UUID.randomUUID().toString(), LocalTime.of(10, 30))));
    }

    @Test
    @DisplayName("Equipment capacity: missing XRAY rejected (409), 1 LASER machine caps concurrent bookings at 1")
    void equipmentCapacityInLegacyMode() {
        createChair("G1-" + SEQ.incrementAndGet(), ChairStatus.AVAILABLE);
        createChair("G2-" + SEQ.incrementAndGet(), ChairStatus.AVAILABLE);
        StaffProfile d1 = createDoctor("EQ1", morningShift);
        StaffProfile d2 = createDoctor("EQ2", morningShift);

        var sNoEq = patientSession("noeq");
        ResponseStatusException exMissing = assertThrows(ResponseStatusException.class, () ->
                portalAppointmentService.book(sNoEq, new PortalAppointmentService.BookingRequest(
                        profileId(sNoEq), branch.getBranchId(), tomorrow, PreferredSession.MORNING,
                        d1.getStaffId(), svcNoEq30.getServiceId(), "xray", UUID.randomUUID().toString(), LocalTime.of(9, 0))));
        assertEquals(409, exMissing.getStatusCode().value());

        ClinicEquipment laser = new ClinicEquipment();
        laser.setBranch(branch);
        laser.setEquipmentCode("EQ-LASER-" + SEQ.incrementAndGet());
        laser.setEquipmentName("May Laser 01");
        laser.setEquipmentType(EquipmentType.LASER);
        laser.setStatus(EquipmentStatus.ACTIVE);
        laser.setAllocationMode(EquipmentAllocationMode.FIXED_ROOM);
        laser.setFixedRoom(room1);
        equipmentRepository.saveAndFlush(laser);

        var sL1 = patientSession("l1");
        portalAppointmentService.book(sL1, new PortalAppointmentService.BookingRequest(
                profileId(sL1), branch.getBranchId(), tomorrow, PreferredSession.MORNING,
                d1.getStaffId(), svcLaser25.getServiceId(), "laser1", UUID.randomUUID().toString(), LocalTime.of(9, 0)));

        var sL2 = patientSession("l2");
        ResponseStatusException exLaserFull = assertThrows(ResponseStatusException.class, () ->
                portalAppointmentService.book(sL2, new PortalAppointmentService.BookingRequest(
                        profileId(sL2), branch.getBranchId(), tomorrow, PreferredSession.MORNING,
                        d2.getStaffId(), svcLaser25.getServiceId(), "laser2", UUID.randomUUID().toString(), LocalTime.of(9, 0))));
        assertEquals(409, exLaserFull.getStatusCode().value());
    }

    @Test
    @DisplayName("Concurrent 2-thread race on same doctor slot: only 1 succeeds, 1 gets 409")
    void concurrentTwoThreadsRace() throws Exception {
        createChair("G1-" + SEQ.incrementAndGet(), ChairStatus.AVAILABLE);
        createChair("G2-" + SEQ.incrementAndGet(), ChairStatus.AVAILABLE);
        StaffProfile doc = createDoctor("RACE", afternoonShift);

        var s1 = patientSession("r1");
        var s2 = patientSession("r2");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();

        Callable<Void> task1 = () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                portalAppointmentService.book(s1, new PortalAppointmentService.BookingRequest(
                        profileId(s1), branch.getBranchId(), tomorrow, PreferredSession.AFTERNOON,
                        doc.getStaffId(), svc30.getServiceId(), "r1", UUID.randomUUID().toString(), LocalTime.of(14, 0)));
                ok.incrementAndGet();
            } catch (ResponseStatusException ex) {
                if (ex.getStatusCode().value() == 409) conflict.incrementAndGet();
            }
            return null;
        };
        Callable<Void> task2 = () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                portalAppointmentService.book(s2, new PortalAppointmentService.BookingRequest(
                        profileId(s2), branch.getBranchId(), tomorrow, PreferredSession.AFTERNOON,
                        doc.getStaffId(), svc30.getServiceId(), "r2", UUID.randomUUID().toString(), LocalTime.of(14, 0)));
                ok.incrementAndGet();
            } catch (ResponseStatusException ex) {
                if (ex.getStatusCode().value() == 409) conflict.incrementAndGet();
            }
            return null;
        };

        Future<Void> f1 = pool.submit(task1);
        Future<Void> f2 = pool.submit(task2);
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(1, ok.get());
        assertEquals(1, conflict.get());
    }

    @Test
    @DisplayName("Resource capacity matrix: 1/1 -> 1, 2/2 -> 2, 3/2 -> 2, 2/4 -> 2, 4/4 -> 4")
    void doctorChairMatrix() {
        assertEquals(1, runMatrixCase(1, 1));
        assertEquals(2, runMatrixCase(2, 2));
        assertEquals(2, runMatrixCase(3, 2));
        assertEquals(2, runMatrixCase(2, 4));
        assertEquals(4, runMatrixCase(4, 4));
    }

    private int runMatrixCase(int doctorCount, int chairCount) {
        int id = SEQ.incrementAndGet();
        Branch b = new Branch();
        b.setBranchCode("MX-" + doctorCount + "-" + chairCount + "-" + id);
        b.setBranchName("Matrix " + doctorCount + "/" + chairCount);
        b.setAddress("Ha Noi");
        b.setIsActive(true);
        b.setResourceEngineEnabled(false);
        b.setOpeningTime(LocalTime.of(8, 0));
        b.setClosingTime(LocalTime.of(21, 0));
        b = branchRepository.saveAndFlush(b);

        Room r = new Room();
        r.setBranch(b);
        r.setRoomCode("RMX-" + id);
        r.setRoomName("Phong MX");
        r.setRoomType(RoomType.TREATMENT);
        r.setStatus(RoomStatus.ACTIVE);
        r = roomRepository.saveAndFlush(r);

        for (int i = 0; i < chairCount; i++) {
            DentalChair c = new DentalChair();
            c.setBranch(b);
            c.setRoom(r);
            c.setChairCode("CMX-" + id + "-" + i);
            c.setChairName("Ghe " + i);
            c.setStatus(ChairStatus.AVAILABLE);
            chairRepository.saveAndFlush(c);
        }

        for (int i = 0; i < doctorCount; i++) {
            int seq = SEQ.incrementAndGet();
            User u = createUser("bsmx_" + seq, docRole, UserType.STAFF);

            StaffProfile sp = new StaffProfile();
            sp.setUser(u);
            sp.setStaffCode("NVMX" + seq);
            sp.setFullName("BS MX " + i);
            sp.setSpecialty("Tong quat");
            sp.setStatus(StaffStatus.ACTIVE);
            sp = staffProfileRepository.saveAndFlush(sp);

            StaffBranchAssignment sba = new StaffBranchAssignment();
            sba.setStaff(sp);
            sba.setBranch(b);
            sba.setRole(docRole);
            sba.setStatus(AssignmentStatus.ACTIVE);
            sba.setIsPrimary(true);
            sba.setStartDate(tomorrow.minusDays(5));
            branchAssignmentRepository.saveAndFlush(sba);

            StaffSchedule ss = new StaffSchedule();
            ss.setBranch(b);
            ss.setStaff(sp);
            ss.setShift(morningShift);
            ss.setWorkDate(tomorrow);
            ss.setStatus(ScheduleStatus.SCHEDULED);
            staffScheduleRepository.saveAndFlush(ss);
        }

        int booked = 0;
        int attempts = Math.max(doctorCount, chairCount) + 1;
        for (int i = 0; i < attempts; i++) {
            var sess = patientSession("mx" + i);
            try {
                portalAppointmentService.book(sess, new PortalAppointmentService.BookingRequest(
                        profileId(sess), b.getBranchId(), tomorrow, PreferredSession.MORNING,
                        null, svc30.getServiceId(), "mx", UUID.randomUUID().toString(), LocalTime.of(9, 0)));
                booked++;
            } catch (ResponseStatusException ex) {
                assertEquals(409, ex.getStatusCode().value());
            }
        }
        return booked;
    }
}

