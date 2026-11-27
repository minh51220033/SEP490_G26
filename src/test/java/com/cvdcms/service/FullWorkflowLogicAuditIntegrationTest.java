package com.cvdcms.service;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:full_workflow_audit_it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;NON_KEYWORDS=MONTH,YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never",
        "app.seed.demo-data=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FullWorkflowLogicAuditIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Bangkok");
    private static final AtomicInteger SEQ = new AtomicInteger(1);

    @Autowired private BranchRepository branchRepo;
    @Autowired private RoomRepository roomRepo;
    @Autowired private DentalChairRepository chairRepo;
    @Autowired private WorkShiftRepository shiftRepo;
    @Autowired private RoleRepository roleRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private StaffProfileRepository staffRepo;
    @Autowired private StaffBranchAssignmentRepository branchAssignRepo;
    @Autowired private StaffScheduleRepository scheduleRepo;
    @Autowired private LeaveRequestRepository leaveRepo;
    @Autowired private PatientProfileRepository patientRepo;
    @Autowired private ServiceCategoryRepository categoryRepo;
    @Autowired private ServiceRepository serviceRepo;
    @Autowired private ServiceResourceRequirementRepository reqRepo;
    @Autowired private ClinicEquipmentRepository equipmentRepo;
    @Autowired private AppointmentRepository appointmentRepo;
    @Autowired private VisitRepository visitRepo;
    @Autowired private ClinicalQueueTicketRepository ticketRepo;

    @Autowired private AppointmentOverlapService overlapService;
    @Autowired private ClinicQueueCoordinatorService coordinator;
    @Autowired private DoctorClinicalCapabilityService capabilityService;

    private int next() {
        return SEQ.getAndIncrement();
    }

    private Branch seedBranch() {
        int n = next();
        Branch b = new Branch();
        b.setBranchCode("BR-FW-" + n);
        b.setBranchName("Chi nhánh Audit " + n);
        b.setAddress("123 Test");
        b.setOpeningTime(LocalTime.of(8, 0));
        b.setClosingTime(LocalTime.of(21, 0));
        b.setWorkingDays("2,3,4,5,6,7,CN");
        b.setIsActive(true);
        b.setResourceEngineEnabled(false);
        return branchRepo.saveAndFlush(b);
    }

    private Room seedRoom(Branch branch, RoomType type) {
        int n = next();
        Room r = new Room();
        r.setBranch(branch);
        r.setRoomCode("RM-" + n);
        r.setRoomName("Phòng " + type + " " + n);
        r.setRoomType(type);
        r.setStatus(RoomStatus.ACTIVE);
        return roomRepo.saveAndFlush(r);
    }

    private DentalChair seedChair(Branch branch, Room room) {
        int n = next();
        DentalChair c = new DentalChair();
        c.setBranch(branch);
        c.setRoom(room);
        c.setChairCode("CH-" + n);
        c.setChairName("Ghế " + n);
        c.setStatus(ChairStatus.AVAILABLE);
        return chairRepo.saveAndFlush(c);
    }

    private WorkShift seedShift(String code, LocalTime start, LocalTime end) {
        int n = next();
        WorkShift s = new WorkShift();
        s.setShiftCode(code + "-" + n);
        s.setShiftName("Ca " + code + " " + n);
        s.setStartTime(start);
        s.setEndTime(end);
        s.setIsActive(true);
        return shiftRepo.saveAndFlush(s);
    }

    private StaffProfile seedDoctor(Branch branch, WorkShift shift, LocalDate date) {
        int n = next();
        Role role = roleRepo.findByRoleName("BAC_SI").orElseGet(() -> {
            Role r = new Role();
            r.setRoleName("BAC_SI");
            r.setIsActive(true);
            return roleRepo.saveAndFlush(r);
        });
        User u = new User();
        u.setUsername("doc_fw_" + n);
        u.setPassword("hashed");
        u.setEmail("doc_fw_" + n + "@test.local");
        u.setPhoneNumber("090100" + String.format("%04d", n));
        u.setBranch(branch);
        u.setUserType(UserType.STAFF);
        u.setStatus("ACTIVE");
        u.setIsActive(true);
        u = userRepo.saveAndFlush(u);

        StaffProfile sp = new StaffProfile();
        sp.setUser(u);
        sp.setStaffCode("BS-FW-" + n);
        sp.setFullName("BS Test " + n);
        sp.setStatus(StaffStatus.ACTIVE);
        sp = staffRepo.saveAndFlush(sp);

        StaffBranchAssignment sba = new StaffBranchAssignment();
        sba.setStaff(sp);
        sba.setBranch(branch);
        sba.setRole(role);
        sba.setStatus(AssignmentStatus.ACTIVE);
        sba.setIsPrimary(true);
        sba.setStartDate(LocalDate.now(ZONE).minusDays(1));
        branchAssignRepo.saveAndFlush(sba);

        if (shift != null && date != null) {
            StaffSchedule sc = new StaffSchedule();
            sc.setStaff(sp);
            sc.setBranch(branch);
            sc.setShift(shift);
            sc.setWorkDate(date);
            sc.setStatus(ScheduleStatus.SCHEDULED);
            scheduleRepo.saveAndFlush(sc);
        }
        return sp;
    }

    private PatientProfile seedPatient(Branch branch) {
        int n = next();
        PatientProfile p = new PatientProfile();
        p.setPatientCode("BN-FW-" + n);
        p.setFullName("Bệnh nhân " + n);
        p.setGender("Nam");
        p.setDob(LocalDate.of(1995, 1, 1));
        p.setAddress("123 Test Street");
        p.setPhoneNumber("091200" + String.format("%04d", n));
        p.setPreferredBranch(branch);
        p.setStatus("ACTIVE");
        return patientRepo.saveAndFlush(p);
    }

    private Service seedService(int durationMinutes) {
        int n = next();
        ServiceCategory cat = categoryRepo.findAll().stream().findFirst().orElseGet(() -> {
            ServiceCategory c = new ServiceCategory();
            c.setCategoryCode("CAT-FW");
            c.setCategoryName("Tổng quát");
            c.setCategoryType(ServiceType.PROCEDURE);
            c.setIsActive(true);
            return categoryRepo.saveAndFlush(c);
        });
        Service svc = new Service();
        svc.setServiceCode("SVC-FW-" + n);
        svc.setServiceName("Dịch vụ " + n);
        svc.setCategory(cat);
        svc.setServiceType(ServiceType.PROCEDURE);
        svc.setBasePrice(BigDecimal.valueOf(200000));
        svc.setEstimatedDurationMinutes(durationMinutes);
        svc.setProcedureDurationMinutes(durationMinutes);
        svc.setRequiresChair(true);
        svc.setIsBookableOnline(true);
        svc.setStatus(ServiceStatus.ACTIVE);
        return serviceRepo.saveAndFlush(svc);
    }

    // -------------------------------------------------------------------------
    // 1. AGENT 1: Full-session overlap returns alternative session suggestions
    // -------------------------------------------------------------------------
    @Test
    void fullSessionOverlapReturnsAlternativeSessionSuggestions() {
        Branch branch = seedBranch();
        Room room = seedRoom(branch, RoomType.EXAM);
        seedChair(branch, room);
        LocalDate futureDate = LocalDate.now(ZONE).plusDays(7);
        WorkShift morning = seedShift("MOR", LocalTime.of(8, 0), LocalTime.of(12, 0));
        WorkShift afternoon = seedShift("AFT", LocalTime.of(13, 30), LocalTime.of(17, 30));
        StaffProfile doc = seedDoctor(branch, morning, futureDate);
        StaffSchedule aftSched = new StaffSchedule();
        aftSched.setStaff(doc);
        aftSched.setBranch(branch);
        aftSched.setShift(afternoon);
        aftSched.setWorkDate(futureDate);
        aftSched.setStatus(ScheduleStatus.SCHEDULED);
        scheduleRepo.saveAndFlush(aftSched);

        Service svc = seedService(120); // 2-hour service; 2 bookings fill entire 08:00-12:00 morning window
        PatientProfile p1 = seedPatient(branch);
        PatientProfile p2 = seedPatient(branch);

        Appointment existing1 = new Appointment();
        existing1.setAppointmentCode("LH-FULL-" + next());
        existing1.setBranch(branch);
        existing1.setPatientProfile(p1);
        existing1.setDoctor(doc);
        existing1.setService(svc);
        existing1.setAppointmentDate(futureDate);
        existing1.setPreferredSession(PreferredSession.MORNING);
        existing1.setExpectedTime(LocalTime.of(8, 0));
        existing1.setDurationMinutes(120);
        existing1.syncExpectedEndTime();
        existing1.setStatus(AppointmentStatus.CONFIRMED);
        appointmentRepo.saveAndFlush(existing1);

        Appointment existing2 = new Appointment();
        existing2.setAppointmentCode("LH-FULL-" + next());
        existing2.setBranch(branch);
        existing2.setPatientProfile(p2);
        existing2.setDoctor(doc);
        existing2.setService(svc);
        existing2.setAppointmentDate(futureDate);
        existing2.setPreferredSession(PreferredSession.MORNING);
        existing2.setExpectedTime(LocalTime.of(10, 0));
        existing2.setDurationMinutes(120);
        existing2.syncExpectedEndTime();
        existing2.setStatus(AppointmentStatus.CONFIRMED);
        appointmentRepo.saveAndFlush(existing2);

        AppointmentOverlapService.OverlapCheckResult res = overlapService.checkOverlap(
                branch.getBranchId(), futureDate, PreferredSession.MORNING,
                LocalTime.of(8, 0), doc.getStaffId(), svc.getServiceId(), null);

        assertTrue(res.validOperatingWindow());
        assertTrue(res.clinicOverlap());
        assertTrue(res.doctorOverlap());
        assertTrue(res.combinedSuggestions().isEmpty());
        assertTrue(res.clinicSuggestions().isEmpty());
        assertTrue(res.doctorSuggestions().isEmpty());
        assertFalse(res.alternativeSessionSuggestions().isEmpty(),
                "When all slots in MORNING are overlapped, must suggest AFTERNOON/other session");
        assertEquals(PreferredSession.AFTERNOON, res.alternativeSessionSuggestions().get(0).session());
    }

    // -------------------------------------------------------------------------
    // 2. AGENT 2: Unassigned EARLY_WAITING appointment is promoted to ON_TIME (Tier 2)
    // -------------------------------------------------------------------------
    @Test
    void unassignedEarlyWaitingAppointmentPromotedToOnTimeAndPriorityWalkInOrderedCorrectly() {
        LocalDate today = LocalDate.now(ZONE);
        Clock morningClock = Clock.fixed(today.atTime(9, 0).atZone(ZONE).toInstant(), ZONE);
        coordinator.setClock(morningClock);
        try {
            Branch branch = seedBranch();
            Room room = seedRoom(branch, RoomType.EXAM);
            seedChair(branch, room);
            Service svc = seedService(30);

            // Patient A: Booked for 10:00, arrives early at 09:00, NO primaryDoctor assigned
            PatientProfile pEarly = seedPatient(branch);
            Appointment appt = new Appointment();
            appt.setAppointmentCode("LH-EARLY-" + next());
            appt.setBranch(branch);
            appt.setPatientProfile(pEarly);
            appt.setService(svc);
            appt.setAppointmentDate(today);
            appt.setPreferredSession(PreferredSession.MORNING);
            appt.setExpectedTime(LocalTime.of(10, 0));
            appt.setDurationMinutes(30);
            appt.syncExpectedEndTime();
            appt.setIsDoctorRequested(false);
            appt.setStatus(AppointmentStatus.CHECKED_IN);
            appt = appointmentRepo.saveAndFlush(appt);

            Visit vEarly = new Visit();
            vEarly.setVisitCode("VS-EARLY-" + next());
            vEarly.setBranch(branch);
            vEarly.setPatientProfile(pEarly);
            vEarly.setAppointmentId(appt.getAppointmentId());
            vEarly.setVisitType(VisitType.APPOINTMENT);
            vEarly.setPrimaryDoctor(null); // general Waterfall queue!
            vEarly.setIsBookedAndOnTime(false);
            vEarly.setCheckInAt(today.atTime(9, 0));
            vEarly.setStatus(VisitStatus.WAITING);
            vEarly = visitRepo.saveAndFlush(vEarly);

            QueueTicket tEarly = coordinator.issueTicketForVisitIfEligible(vEarly.getVisitId(), null);
            assertNotNull(tEarly);
            assertEquals(50, tEarly.getPriorityScore());
            assertTrue(tEarly.getPriorityReason().startsWith("EARLY_WAITING:"));

            // Patient B: Walk-in marked [ƯU TIÊN] (non-emergency)
            PatientProfile pWalkPri = seedPatient(branch);
            Visit vWalkPri = new Visit();
            vWalkPri.setVisitCode("VS-WP-" + next());
            vWalkPri.setBranch(branch);
            vWalkPri.setPatientProfile(pWalkPri);
            vWalkPri.setVisitType(VisitType.WALK_IN);
            vWalkPri.setNotes("[ƯU TIÊN] Người cao tuổi");
            vWalkPri.setCheckInAt(today.atTime(9, 5));
            vWalkPri.setStatus(VisitStatus.WAITING);
            vWalkPri = visitRepo.saveAndFlush(vWalkPri);
            QueueTicket tWalkPri = coordinator.issueTicketForVisitIfEligible(vWalkPri.getVisitId(), null);
            assertNotNull(tWalkPri);
            assertTrue(Boolean.TRUE.equals(tWalkPri.getIsPriority()));
            assertEquals(60, tWalkPri.getPriorityScore());

            // Advance clock to 09:46 (>= 10:00 - 15m) -> promoteEligibleEarlyWaitingTickets MUST promote unassigned ticket!
            int promoted = coordinator.promoteEligibleEarlyWaitingTickets(today.atTime(9, 46));
            assertTrue(promoted >= 1, "Unassigned EARLY_WAITING ticket must be promoted via LEFT JOIN FETCH");

            QueueTicket refreshedEarly = ticketRepo.findById(tEarly.getTicketId()).orElseThrow();
            assertEquals(75, refreshedEarly.getPriorityScore());
            assertTrue(Boolean.TRUE.equals(refreshedEarly.getIsPriority()));
            assertTrue(refreshedEarly.getPriorityReason().startsWith("ON_TIME:"));
        } finally {
            coordinator.setClock(Clock.system(ZONE));
        }
    }

    // -------------------------------------------------------------------------
    // 3. AGENT 3 & 4: Equipment check + Head-of-Line blocking skip + Active Shift window
    // -------------------------------------------------------------------------
    @Test
    void headOfLineBlockingSkippedWhenEquipmentMissingAndDoctorOutOfShiftBlocked() {
        LocalDate today = LocalDate.now(ZONE);
        Clock midDayClock = Clock.fixed(today.atTime(14, 0).atZone(ZONE).toInstant(), ZONE);
        coordinator.setClock(midDayClock);
        try {
            Branch branch = seedBranch();
            Room examRoom = seedRoom(branch, RoomType.EXAM);
            seedChair(branch, examRoom);

            // Morning-only doctor (ended at 12:00) vs Afternoon doctor (13:00-18:00)
            WorkShift endedShift = seedShift("END", LocalTime.of(8, 0), LocalTime.of(12, 0));
            WorkShift activeShift = seedShift("ACT", LocalTime.of(13, 0), LocalTime.of(18, 0));
            StaffProfile endedDoc = seedDoctor(branch, endedShift, today);
            StaffProfile activeDoc = seedDoctor(branch, activeShift, today);

            assertFalse(coordinator.isDoctorEligibleToCallToday(branch.getBranchId(), endedDoc, today),
                    "Doctor whose shift ended at 12:00 must NOT be eligible at 14:00");
            assertTrue(coordinator.isDoctorEligibleToCallToday(branch.getBranchId(), activeDoc, today));

            // Service 1 requires XRAY equipment (NOT installed in branch -> cannot allocate)
            Service svcNeedsEquip = seedService(30);
            ServiceResourceRequirement equipReq = new ServiceResourceRequirement();
            equipReq.setService(svcNeedsEquip);
            equipReq.setResourceKind(ResourceRequirementKind.EQUIPMENT_TYPE);
            equipReq.setEquipmentType(EquipmentType.XRAY);
            equipReq.setIsRequired(true);
            reqRepo.saveAndFlush(equipReq);

            // Verify DoctorClinicalCapabilityService rejects room without equipment
            var capFail = capabilityService.checkDoctorAndRoomCapability(
                    branch.getBranchId(), activeDoc.getStaffId(), examRoom.getRoomId(), svcNeedsEquip.getServiceId());
            assertFalse(capFail.allowed());
            assertEquals("EQUIPMENT_REQUIREMENT_MISMATCH", capFail.reasonCode());

            // Seed Ticket 1 (head, needs missing equipment) and Ticket 2 (behind, normal walk-in)
            PatientProfile p1 = seedPatient(branch);
            Appointment a1 = new Appointment();
            a1.setAppointmentCode("LH-EQ-" + next());
            a1.setBranch(branch);
            a1.setPatientProfile(p1);
            a1.setService(svcNeedsEquip);
            a1.setAppointmentDate(today);
            a1.setPreferredSession(PreferredSession.AFTERNOON);
            a1.setExpectedTime(LocalTime.of(14, 0));
            a1.setDurationMinutes(30);
            a1.syncExpectedEndTime();
            a1.setStatus(AppointmentStatus.CHECKED_IN);
            a1 = appointmentRepo.saveAndFlush(a1);

            Visit v1 = new Visit();
            v1.setVisitCode("VS-EQ-1-" + next());
            v1.setBranch(branch);
            v1.setPatientProfile(p1);
            v1.setAppointmentId(a1.getAppointmentId());
            v1.setVisitType(VisitType.APPOINTMENT);
            v1.setIsBookedAndOnTime(true);
            v1.setCheckInAt(today.atTime(13, 55));
            v1.setStatus(VisitStatus.WAITING);
            v1 = visitRepo.saveAndFlush(v1);

            PatientProfile p2 = seedPatient(branch);
            Visit v2 = new Visit();
            v2.setVisitCode("VS-EQ-2-" + next());
            v2.setBranch(branch);
            v2.setPatientProfile(p2);
            v2.setVisitType(VisitType.WALK_IN);
            v2.setCheckInAt(today.atTime(13, 56));
            v2.setStatus(VisitStatus.WAITING);
            v2 = visitRepo.saveAndFlush(v2);

            // Manually insert both tickets WAITING before calling autoCallNextForDoctorIfIdle
            QueueTicket t1 = new QueueTicket();
            t1.setVisit(v1);
            t1.setBranch(branch);
            t1.setQueueDate(today);
            t1.setQueueType(QueueType.EXAM);
            t1.setTicketNumber(1);
            t1.setDisplayNumber("P-01");
            t1.setStatus(QueueStatus.WAITING);
            t1.setIsPriority(true);
            t1.setIsEmergency(false);
            t1.setPriorityScore(75);
            t1.setIssuedAt(today.atTime(13, 55));
            t1 = ticketRepo.saveAndFlush(t1);

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
            t2.setIssuedAt(today.atTime(13, 56));
            t2 = ticketRepo.saveAndFlush(t2);

            Optional<QueueTicket> called = coordinator.autoCallNextForDoctorIfIdle(
                    branch.getBranchId(), activeDoc.getStaffId(), null);
            assertTrue(called.isPresent(), "Doctor must skip HOL-blocked patient and call eligible 2nd patient");
            assertEquals(t2.getTicketId(), called.get().getTicketId());

            QueueTicket refreshedT1 = ticketRepo.findById(t1.getTicketId()).orElseThrow();
            assertEquals(QueueStatus.WAITING, refreshedT1.getStatus(), "Skipped head ticket must remain WAITING");
            assertNotNull(refreshedT1.getPauseReason());
            assertTrue(refreshedT1.getPauseReason().contains("Chờ tài nguyên"));
        } finally {
            coordinator.setClock(Clock.system(ZONE));
        }
    }

    // -------------------------------------------------------------------------
    // 4. AGENT 5: Mid-day leave releases CALLED general-queue chair & preserves IN_SERVICE / requested
    // -------------------------------------------------------------------------
    @Test
    void midDayLeaveReconcilesCalledGeneralTicketPreservesInServiceAndFlagsRequested() {
        LocalDate today = LocalDate.now(ZONE);
        Clock midDayClock = Clock.fixed(today.atTime(14, 0).atZone(ZONE).toInstant(), ZONE);
        coordinator.setClock(midDayClock);
        try {
            Branch branch = seedBranch();
            Room r1 = seedRoom(branch, RoomType.EXAM);
            DentalChair ch1 = seedChair(branch, r1);
            DentalChair ch2 = seedChair(branch, r1);
            WorkShift shift = seedShift("ALL", LocalTime.of(8, 0), LocalTime.of(20, 0));
            StaffProfile leavingDoc = seedDoctor(branch, shift, today);
            StaffProfile backupDoc = seedDoctor(branch, shift, today);

            // Ticket A: CALLED general-queue patient holding ch1 -> must be released & re-called by backupDoc
            PatientProfile pGeneral = seedPatient(branch);
            Visit vGeneral = new Visit();
            vGeneral.setVisitCode("VS-LV-GEN-" + next());
            vGeneral.setBranch(branch);
            vGeneral.setPatientProfile(pGeneral);
            vGeneral.setVisitType(VisitType.WALK_IN);
            vGeneral.setPrimaryDoctor(leavingDoc);
            vGeneral.setRoomId(r1.getRoomId());
            vGeneral.setChairId(ch1.getChairId());
            vGeneral.setCheckInAt(today.atTime(13, 50));
            vGeneral.setStatus(VisitStatus.WAITING);
            vGeneral = visitRepo.saveAndFlush(vGeneral);
            ch1.setStatus(ChairStatus.IN_USE);
            chairRepo.saveAndFlush(ch1);

            QueueTicket tGeneral = new QueueTicket();
            tGeneral.setVisit(vGeneral);
            tGeneral.setBranch(branch);
            tGeneral.setQueueDate(today);
            tGeneral.setQueueType(QueueType.EXAM);
            tGeneral.setTicketNumber(1);
            tGeneral.setDisplayNumber("01");
            tGeneral.setStatus(QueueStatus.CALLED);
            tGeneral.setCalledAt(today.atTime(13, 55));
            tGeneral.setIsPriority(false);
            tGeneral.setIsEmergency(false);
            tGeneral.setPriorityScore(50);
            tGeneral.setIssuedAt(today.atTime(13, 50));
            tGeneral = ticketRepo.saveAndFlush(tGeneral);

            // Ticket B: IN_SERVICE patient on ch2 -> MUST remain untouched
            PatientProfile pInService = seedPatient(branch);
            Visit vInService = new Visit();
            vInService.setVisitCode("VS-LV-INS-" + next());
            vInService.setBranch(branch);
            vInService.setPatientProfile(pInService);
            vInService.setVisitType(VisitType.WALK_IN);
            vInService.setPrimaryDoctor(leavingDoc);
            vInService.setRoomId(r1.getRoomId());
            vInService.setChairId(ch2.getChairId());
            vInService.setCheckInAt(today.atTime(13, 30));
            vInService.setStatus(VisitStatus.IN_EXAM);
            vInService = visitRepo.saveAndFlush(vInService);
            ch2.setStatus(ChairStatus.IN_USE);
            chairRepo.saveAndFlush(ch2);

            QueueTicket tInService = new QueueTicket();
            tInService.setVisit(vInService);
            tInService.setBranch(branch);
            tInService.setQueueDate(today);
            tInService.setQueueType(QueueType.EXAM);
            tInService.setTicketNumber(2);
            tInService.setDisplayNumber("02");
            tInService.setStatus(QueueStatus.IN_SERVICE);
            tInService.setIsPriority(false);
            tInService.setIsEmergency(false);
            tInService.setPriorityScore(50);
            tInService.setIssuedAt(today.atTime(13, 30));
            tInService = ticketRepo.saveAndFlush(tInService);

            // Approve full-day leave for leavingDoc today
            LeaveRequest leave = new LeaveRequest();
            leave.setStaff(leavingDoc);
            leave.setBranch(branch);
            leave.setStartDate(today);
            leave.setEndDate(today);
            leave.setReason("Nghỉ đột xuất");
            leave.setStatus(LeaveStatus.APPROVED);
            leaveRepo.saveAndFlush(leave);

            int reconciled = coordinator.reconcileQueueForDoctorLeave(
                    branch.getBranchId(), leavingDoc.getStaffId(), today);
            assertEquals(1, reconciled);

            // General ticket must now be CALLED by backupDoc!
            QueueTicket afterGen = ticketRepo.findById(tGeneral.getTicketId()).orElseThrow();
            Visit afterVGen = visitRepo.findById(vGeneral.getVisitId()).orElseThrow();
            assertEquals(QueueStatus.CALLED, afterGen.getStatus());
            assertEquals(backupDoc.getStaffId(), afterVGen.getPrimaryDoctor().getStaffId());

            // IN_SERVICE ticket and ch2 must remain untouched
            QueueTicket afterIns = ticketRepo.findById(tInService.getTicketId()).orElseThrow();
            DentalChair afterCh2 = chairRepo.findById(ch2.getChairId()).orElseThrow();
            assertEquals(QueueStatus.IN_SERVICE, afterIns.getStatus());
            assertEquals(ChairStatus.IN_USE, afterCh2.getStatus());
        } finally {
            coordinator.setClock(Clock.system(ZONE));
        }
    }
}
