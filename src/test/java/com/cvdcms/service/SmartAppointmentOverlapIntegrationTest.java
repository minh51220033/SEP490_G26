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
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:smart_overlap_it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;NON_KEYWORDS=MONTH,YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SmartAppointmentOverlapIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Bangkok");
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    @Autowired private AppointmentOverlapService overlapService;
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
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private PatientProfileRepository patientProfileRepository;
    @Autowired private ServiceCategoryRepository serviceCategoryRepository;
    @Autowired private ServiceRepository serviceRepository;
    @Autowired private AppointmentRepository appointmentRepository;

    private Branch branch;
    private Room room;
    private WorkShift morningShift;
    private Role docRole;
    private Role patientRole;
    private StaffProfile docA;
    private StaffProfile docB;
    private Service svc15;
    private Service svc30;
    private Service svc60;
    private Service svc90;
    private LocalDate futureDate;

    @BeforeEach
    void setUp() {
        int id = SEQ.incrementAndGet();
        futureDate = LocalDate.now(ZONE).plusDays(5);

        branch = new Branch();
        branch.setBranchCode("CS-OV-" + id);
        branch.setBranchName("Chi nhanh Overlap " + id);
        branch.setAddress("Ha Noi");
        branch.setIsActive(true);
        branch.setResourceEngineEnabled(false);
        branch.setOpeningTime(LocalTime.of(8, 0));
        branch.setClosingTime(LocalTime.of(21, 0));
        branch = branchRepository.saveAndFlush(branch);

        room = new Room();
        room.setBranch(branch);
        room.setRoomCode("ROV-" + id);
        room.setRoomName("Phong OV " + id);
        room.setRoomType(RoomType.TREATMENT);
        room.setStatus(RoomStatus.ACTIVE);
        room = roomRepository.saveAndFlush(room);

        createChair("G-OV1-" + id);
        createChair("G-OV2-" + id);

        morningShift = new WorkShift();
        morningShift.setBranch(branch);
        morningShift.setShiftCode("SANG-OV-" + id);
        morningShift.setShiftName("Ca Sang OV");
        morningShift.setStartTime(LocalTime.of(8, 0));
        morningShift.setEndTime(LocalTime.of(12, 0));
        morningShift.setIsActive(true);
        morningShift = workShiftRepository.saveAndFlush(morningShift);

        docRole = roleRepository.findByRoleName("BAC_SI").orElseGet(() -> {
            Role r = new Role();
            r.setRoleName("BAC_SI");
            return roleRepository.saveAndFlush(r);
        });
        patientRole = roleRepository.findByRoleName("BENH_NHAN").orElseGet(() -> {
            Role r = new Role();
            r.setRoleName("BENH_NHAN");
            return roleRepository.saveAndFlush(r);
        });

        docA = createDoctor("doca_" + id, "BS Doc A " + id);
        docB = createDoctor("docb_" + id, "BS Doc B " + id);

        ServiceCategory cat = new ServiceCategory();
        cat.setCategoryCode("CAT-OV-" + id);
        cat.setCategoryName("Danh muc OV " + id);
        cat.setIsActive(true);
        cat = serviceCategoryRepository.saveAndFlush(cat);

        svc15 = createService(cat, "OV15-" + id, "Dich vu 15p", 15);
        svc30 = createService(cat, "OV30-" + id, "Dich vu 30p", 30);
        svc60 = createService(cat, "OV60-" + id, "Dich vu 60p", 60);
        svc90 = createService(cat, "OV90-" + id, "Dich vu 90p", 90);
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

    private StaffProfile createDoctor(String username, String fullName) {
        int uSeq = SEQ.incrementAndGet();
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@test.local");
        u.setPhoneNumber("091" + String.format("%07d", uSeq));
        u.setPassword("pw");
        u.setUserType(UserType.STAFF);
        u.setIsActive(true);
        u.setStatus("ACTIVE");
        u.setBranch(branch);
        u = userRepository.saveAndFlush(u);

        StaffProfile sp = new StaffProfile();
        sp.setUser(u);
        sp.setStaffCode("ST-" + username);
        sp.setFullName(fullName);
        sp.setStatus(StaffStatus.ACTIVE);
        sp = staffProfileRepository.saveAndFlush(sp);

        StaffBranchAssignment sba = new StaffBranchAssignment();
        sba.setStaff(sp);
        sba.setBranch(branch);
        sba.setRole(docRole);
        sba.setStartDate(futureDate.minusDays(5));
        sba.setStatus(AssignmentStatus.ACTIVE);
        branchAssignmentRepository.saveAndFlush(sba);

        StaffSchedule sc = new StaffSchedule();
        sc.setStaff(sp);
        sc.setBranch(branch);
        sc.setShift(morningShift);
        sc.setWorkDate(futureDate);
        sc.setStatus(ScheduleStatus.SCHEDULED);
        staffScheduleRepository.saveAndFlush(sc);
        return sp;
    }

    private Service createService(ServiceCategory cat, String code, String name, int minutes) {
        Service s = new Service();
        s.setCategory(cat);
        s.setServiceCode(code);
        s.setServiceName(name);
        s.setServiceType(ServiceType.PROCEDURE);
        s.setBasePrice(BigDecimal.valueOf(200000));
        s.setEstimatedDurationMinutes(minutes);
        s.setProcedureDurationMinutes(minutes);
        s.setPreparationDurationMinutes(0);
        s.setTurnoverDurationMinutes(0);
        s.setStatus(ServiceStatus.ACTIVE);
        s.setIsBookableOnline(true);
        return serviceRepository.saveAndFlush(s);
    }

    private PatientProfile createPatient(String username, String fullName) {
        int uSeq = SEQ.incrementAndGet();
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@test.local");
        u.setPhoneNumber("092" + String.format("%07d", uSeq));
        u.setPassword("pw");
        u.setUserType(UserType.PATIENT);
        u.setIsActive(true);
        u.setStatus("ACTIVE");
        u.setBranch(branch);
        u = userRepository.saveAndFlush(u);

        PatientProfile p = new PatientProfile();
        p.setUser(u);
        p.setPatientCode("BN-" + username);
        p.setFullName(fullName);
        p.setPhoneNumber("095" + String.format("%07d", uSeq));
        p.setAddress("Ha Noi");
        p.setGender("MALE");
        p.setStatus("ACTIVE");
        p.setPreferredBranch(branch);
        return patientProfileRepository.saveAndFlush(p);
    }

    private MockHttpSession sessionFor(PatientProfile p) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("loggedInUser", p.getUser());
        return s;
    }

    private void seedAppointment(PatientProfile p, StaffProfile doc, LocalTime start, int durMin, AppointmentStatus status) {
        Appointment a = new Appointment();
        a.setAppointmentCode("LH" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        a.setBranch(branch);
        a.setPatientProfile(p);
        a.setDoctor(doc);
        a.setIsDoctorRequested(doc != null);
        a.setService(svc30);
        a.setAppointmentDate(futureDate);
        a.setPreferredSession(PreferredSession.MORNING);
        a.setExpectedTime(start);
        a.setDurationMinutes(durMin);
        a.syncExpectedEndTime();
        a.setStatus(status);
        appointmentRepository.saveAndFlush(a);
    }

    @Test
    void testA1_noOverlap() {
        PatientProfile p = createPatient("p_a1_" + futureDate.getDayOfMonth(), "Benh nhan A1");
        var chk = overlapService.checkOverlap(
                branch.getBranchId(), futureDate, PreferredSession.MORNING,
                LocalTime.of(8, 30), docA.getStaffId(), svc30.getServiceId(), null);

        assertTrue(chk.validOperatingWindow());
        assertFalse(chk.clinicOverlap());
        assertFalse(chk.doctorOverlap());
        assertEquals("NO_OVERLAP", chk.clinicStatus());
        assertEquals("NO_OVERLAP", chk.doctorStatus());

        var view = portalAppointmentService.book(sessionFor(p), new PortalAppointmentService.BookingRequest(
                p.getProfileId(), branch.getBranchId(), futureDate, PreferredSession.MORNING,
                docA.getStaffId(), svc30.getServiceId(), "Kham thuong", UUID.randomUUID().toString(),
                LocalTime.of(8, 30), false));
        assertNotNull(view.id());
        assertEquals(LocalTime.of(8, 30), view.expectedTime());
    }

    @Test
    void testA2_onlyClinicOverlap() {
        PatientProfile p1 = createPatient("p_a2_1_" + SEQ.get(), "BN A2-1");
        PatientProfile p2 = createPatient("p_a2_2_" + SEQ.get(), "BN A2-2");
        // 2 doctors, 2 chairs -> branchCapacity = 2, threshold = ceil(2*0.75) = 2
        seedAppointment(p1, docA, LocalTime.of(9, 0), 30, AppointmentStatus.CONFIRMED);
        seedAppointment(p2, docB, LocalTime.of(9, 0), 30, AppointmentStatus.PENDING);

        // No specific doctor selected -> must NOT generate fake Doctor Overlap
        var chk = overlapService.checkOverlap(
                branch.getBranchId(), futureDate, PreferredSession.MORNING,
                LocalTime.of(9, 0), null, svc30.getServiceId(), null);

        assertTrue(chk.clinicOverlap());
        assertEquals("OVERLAP_WARNING", chk.clinicStatus());
        assertFalse(chk.doctorOverlap());
        assertEquals("NOT_APPLICABLE", chk.doctorStatus());
        assertFalse(chk.clinicSuggestions().isEmpty());
        assertTrue(chk.clinicSuggestions().stream().allMatch(s -> "NO_OVERLAP".equals(s.clinicStatus())));
    }

    @Test
    void testA3_onlyDoctorOverlap() {
        PatientProfile p1 = createPatient("p_a3_1_" + SEQ.get(), "BN A3-1");
        // Only 1 appointment at 09:00 on docA (1 < threshold 2), so Clinic is WITHIN_CAPACITY, NOT clinicOverlap
        seedAppointment(p1, docA, LocalTime.of(9, 0), 30, AppointmentStatus.CONFIRMED);

        var chk = overlapService.checkOverlap(
                branch.getBranchId(), futureDate, PreferredSession.MORNING,
                LocalTime.of(9, 15), docA.getStaffId(), svc30.getServiceId(), null);

        assertFalse(chk.clinicOverlap());
        assertEquals("WITHIN_CAPACITY", chk.clinicStatus());
        assertTrue(chk.doctorOverlap());
        assertEquals("HAS_OVERLAP", chk.doctorStatus());
        assertFalse(chk.doctorSuggestions().isEmpty());
        assertTrue(chk.doctorSuggestions().stream().allMatch(s -> "NO_OVERLAP".equals(s.doctorStatus())));
    }

    @Test
    void testA4_bothClinicAndDoctorOverlap() {
        PatientProfile p1 = createPatient("p_a4_1_" + SEQ.get(), "BN A4-1");
        PatientProfile p2 = createPatient("p_a4_2_" + SEQ.get(), "BN A4-2");
        seedAppointment(p1, docA, LocalTime.of(9, 0), 30, AppointmentStatus.CONFIRMED);
        seedAppointment(p2, docB, LocalTime.of(9, 0), 30, AppointmentStatus.CONFIRMED);

        var chk = overlapService.checkOverlap(
                branch.getBranchId(), futureDate, PreferredSession.MORNING,
                LocalTime.of(9, 0), docA.getStaffId(), svc30.getServiceId(), null);

        assertTrue(chk.clinicOverlap());
        assertTrue(chk.doctorOverlap());
        assertNotNull(chk.clinicWarningMessage());
        assertNotNull(chk.doctorWarningMessage());
        assertFalse(chk.combinedSuggestions().isEmpty());
        assertTrue(chk.combinedSuggestions().stream()
                .allMatch(s -> "NO_OVERLAP".equals(s.clinicStatus()) && "NO_OVERLAP".equals(s.doctorStatus())));
        // Verify no duplicate startTime across combined, clinic, and doctor suggestion groups
        var seen = new java.util.HashSet<LocalTime>();
        chk.combinedSuggestions().forEach(s -> assertTrue(seen.add(s.startTime())));
        chk.clinicSuggestions().forEach(s -> assertTrue(seen.add(s.startTime())));
        chk.doctorSuggestions().forEach(s -> assertTrue(seen.add(s.startTime())));
    }

    @Test
    void testA5_keepOverlappingTimeAndDoctor() {
        PatientProfile p1 = createPatient("p_a5_1_" + SEQ.get(), "BN A5-1");
        PatientProfile p2 = createPatient("p_a5_2_" + SEQ.get(), "BN A5-2");
        seedAppointment(p1, docA, LocalTime.of(10, 0), 30, AppointmentStatus.CONFIRMED);

        // Without confirmOverlap, booking throws 409
        assertThrows(ResponseStatusException.class, () -> portalAppointmentService.book(sessionFor(p2),
                new PortalAppointmentService.BookingRequest(
                        p2.getProfileId(), branch.getBranchId(), futureDate, PreferredSession.MORNING,
                        docA.getStaffId(), svc30.getServiceId(), "Giu gio overlap", UUID.randomUUID().toString(),
                        LocalTime.of(10, 0), false)));

        // With confirmOverlap=true, booking succeeds keeping exact 10:00 and docA
        var saved = portalAppointmentService.book(sessionFor(p2),
                new PortalAppointmentService.BookingRequest(
                        p2.getProfileId(), branch.getBranchId(), futureDate, PreferredSession.MORNING,
                        docA.getStaffId(), svc30.getServiceId(), "Giu gio overlap", UUID.randomUUID().toString(),
                        LocalTime.of(10, 0), true));

        assertEquals(LocalTime.of(10, 0), saved.expectedTime());
        assertEquals(docA.getStaffId(), saved.requestedDoctorId());
        Appointment entity = appointmentRepository.findById(saved.id()).orElseThrow();
        assertTrue(Boolean.TRUE.equals(entity.getOverlapWarningAcknowledged()));
    }

    @Test
    void testA6_selectSuggestedSlotAndRecheck() {
        PatientProfile p1 = createPatient("p_a6_1_" + SEQ.get(), "BN A6-1");
        PatientProfile p2 = createPatient("p_a6_2_" + SEQ.get(), "BN A6-2");
        seedAppointment(p1, docA, LocalTime.of(9, 0), 30, AppointmentStatus.CONFIRMED);

        var chk1 = overlapService.checkOverlap(
                branch.getBranchId(), futureDate, PreferredSession.MORNING,
                LocalTime.of(9, 0), docA.getStaffId(), svc30.getServiceId(), null);
        assertTrue(chk1.doctorOverlap());
        LocalTime suggested = chk1.doctorSuggestions().get(0).startTime();

        var chk2 = overlapService.checkOverlap(
                branch.getBranchId(), futureDate, PreferredSession.MORNING,
                suggested, docA.getStaffId(), svc30.getServiceId(), null);
        assertFalse(chk2.doctorOverlap());

        var saved = portalAppointmentService.book(sessionFor(p2),
                new PortalAppointmentService.BookingRequest(
                        p2.getProfileId(), branch.getBranchId(), futureDate, PreferredSession.MORNING,
                        docA.getStaffId(), svc30.getServiceId(), "Chon gio goi y", UUID.randomUUID().toString(),
                        suggested, false));
        assertEquals(suggested, saved.expectedTime());
    }

    @Test
    void testA7_differentServiceDurations() {
        PatientProfile p1 = createPatient("p_a7_1_" + SEQ.get(), "BN A7-1");
        seedAppointment(p1, docA, LocalTime.of(9, 0), 30, AppointmentStatus.CONFIRMED);

        for (Service svc : new Service[]{svc15, svc30, svc60, svc90}) {
            int dur = svc.getEstimatedDurationMinutes();
            var chk = overlapService.checkOverlap(
                    branch.getBranchId(), futureDate, PreferredSession.MORNING,
                    LocalTime.of(9, 0), docA.getStaffId(), svc.getServiceId(), null);
            assertEquals(dur, chk.durationMinutes());
            for (var s : chk.doctorSuggestions()) {
                assertEquals(dur, Duration.between(s.startTime(), s.endTime()).toMinutes());
                assertFalse(s.endTime().isAfter(LocalTime.of(12, 0)));
            }
        }
    }

    @Test
    void testA8_leavesAndOutsideShiftStayHardBlocked() {
        PatientProfile p = createPatient("p_a8_" + SEQ.get(), "BN A8");

        // Outside morning shift (12:30) -> invalidOperatingWindow=false and hard-blocked even with confirmOverlap=true
        var outside = overlapService.checkOverlap(
                branch.getBranchId(), futureDate, PreferredSession.MORNING,
                LocalTime.of(12, 30), docA.getStaffId(), svc30.getServiceId(), null);
        assertFalse(outside.validOperatingWindow());
        assertThrows(ResponseStatusException.class, () -> portalAppointmentService.book(sessionFor(p),
                new PortalAppointmentService.BookingRequest(
                        p.getProfileId(), branch.getBranchId(), futureDate, PreferredSession.MORNING,
                        docA.getStaffId(), svc30.getServiceId(), "Ngoai ca", UUID.randomUUID().toString(),
                        LocalTime.of(12, 30), true)));

        // Approved leave on docA -> hard-blocked even with confirmOverlap=true
        LeaveRequest leave = new LeaveRequest();
        leave.setStaff(docA);
        leave.setBranch(branch);
        leave.setStartDate(futureDate);
        leave.setEndDate(futureDate);
        leave.setShift(morningShift);
        leave.setReason("Nghi phep");
        leave.setStatus(LeaveStatus.APPROVED);
        leaveRequestRepository.saveAndFlush(leave);

        var onLeave = overlapService.checkOverlap(
                branch.getBranchId(), futureDate, PreferredSession.MORNING,
                LocalTime.of(9, 0), docA.getStaffId(), svc30.getServiceId(), null);
        assertFalse(onLeave.validOperatingWindow());
        assertThrows(ResponseStatusException.class, () -> portalAppointmentService.book(sessionFor(p),
                new PortalAppointmentService.BookingRequest(
                        p.getProfileId(), branch.getBranchId(), futureDate, PreferredSession.MORNING,
                        docA.getStaffId(), svc30.getServiceId(), "Nghi phep", UUID.randomUUID().toString(),
                        LocalTime.of(9, 0), true)));
    }
}
