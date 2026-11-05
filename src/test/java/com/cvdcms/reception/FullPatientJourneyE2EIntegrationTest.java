package com.cvdcms.reception;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.AppointmentLifecycleService;
import com.cvdcms.service.AppointmentPolicyService;
import com.cvdcms.service.impl.AppointmentLifecycleServiceImpl;
import com.cvdcms.emr.EmrAccess;
import com.cvdcms.emr.EmrAttachmentStorage;
import com.cvdcms.emr.EmrModuleService;
import com.cvdcms.emr.EmrRecordRepository;
import com.cvdcms.emr.EmrRecordStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Phase 4 Comprehensive End-to-End (E2E) Acceptance Test Suite across FT05 -> FT10:
 *
 * Track 1: Pre-booked Patient with Requested Doctor (FT05 -> FT06 -> FT07 -> FT08 -> FT09 -> FT10)
 * Track 2: Walk-in Patient with Least-Loaded Auto-Assignment & Age Priority Upgrade (FT07 -> FT08 -> FT09 -> FT10)
 * Track 3: Emergency Intake with Instant Top-of-Queue Jump & Dynamic Chair Switching (FT07 -> FT08 -> FT09 -> FT10)
 * Track 4: Cross-Cutting Safeguards (Revert Check-in, Public TV Board Zero-PII, Queue Isolation & Out-of-Order Calling)
 */
@DataJpaTest(showSql = false, properties = {
        "spring.datasource.url=jdbc:h2:mem:full_e2e_phase4;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;NON_KEYWORDS=MONTH,YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        ReceptionService.class,
        DispatchService.class,
        QueueWorkflowService.class,
        AppointmentLifecycleServiceImpl.class,
        com.cvdcms.service.impl.ClinicalExaminationBootstrapServiceImpl.class,
        EmrModuleService.class, EmrAttachmentStorage.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FullPatientJourneyE2EIntegrationTest {

    private static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @MockitoBean ReceptionAccess access;
    @MockitoBean AppointmentPolicyService policyService;
    @MockitoBean ObjectMapper objectMapper;
    @MockitoBean EmrAccess emrAccess;

    @org.springframework.beans.factory.annotation.Autowired ReceptionService receptionService;
    @org.springframework.beans.factory.annotation.Autowired DispatchService dispatchService;
    @org.springframework.beans.factory.annotation.Autowired QueueWorkflowService queueWorkflowService;
    @org.springframework.beans.factory.annotation.Autowired AppointmentLifecycleService lifecycleService;

    @org.springframework.beans.factory.annotation.Autowired UserRepository userRepository;
    @org.springframework.beans.factory.annotation.Autowired BranchRepository branchRepository;
    @org.springframework.beans.factory.annotation.Autowired RoleRepository roleRepository;
    @org.springframework.beans.factory.annotation.Autowired StaffProfileRepository staffProfileRepository;
    @org.springframework.beans.factory.annotation.Autowired StaffBranchAssignmentRepository assignmentRepository;
    @org.springframework.beans.factory.annotation.Autowired WorkShiftRepository shiftRepository;
    @org.springframework.beans.factory.annotation.Autowired StaffScheduleRepository scheduleRepository;
    @org.springframework.beans.factory.annotation.Autowired RoomRepository roomRepository;
    @org.springframework.beans.factory.annotation.Autowired DentalChairRepository chairRepository;
    @org.springframework.beans.factory.annotation.Autowired PatientProfileRepository patientProfileRepository;
    @org.springframework.beans.factory.annotation.Autowired ReceptionCheckInAppointmentRepository checkInAppointmentRepository;
    @org.springframework.beans.factory.annotation.Autowired ReceptionVisitRepository visitRepository;
    @org.springframework.beans.factory.annotation.Autowired DispatchTicketRepository ticketRepository;
    @org.springframework.beans.factory.annotation.Autowired AppointmentHistoryRepository historyRepository;
    @org.springframework.beans.factory.annotation.Autowired ReceptionCheckInKeyRepository keyRepository;
    @org.springframework.beans.factory.annotation.Autowired ClinicalExaminationRepository examinationRepository;
    @org.springframework.beans.factory.annotation.Autowired ExaminationDiagnosisRepository diagnosisRepository;
    @org.springframework.beans.factory.annotation.Autowired Icd10DiseaseRepository icdRepository;
    @org.springframework.beans.factory.annotation.Autowired EmrModuleService emrModuleService;
    @org.springframework.beans.factory.annotation.Autowired EmrRecordRepository emrRecordRepository;

    private User staffUser;
    private Branch branch;
    private Role doctorRole;
    private StaffProfile doctorNam;
    private StaffProfile doctorHuong;
    private WorkShift allDayShift;
    private Room room1;
    private Room room2;
    private DentalChair chair1;
    private DentalChair chair2;
    private DentalChair chair3;
    private MockHttpSession session;

    @BeforeEach
    void setUp() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        session = new MockHttpSession();
        String uid = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        // 1. Staff User
        staffUser = new User();
        staffUser.setUsername("reception_" + uid);
        staffUser.setPassword("password123");
        staffUser.setEmail("reception_" + uid + "@clinic.test");
        staffUser.setPhoneNumber("091" + uid);
        staffUser.setUserType(UserType.STAFF);
        staffUser = userRepository.saveAndFlush(staffUser);

        // 2. Branch
        branch = new Branch();
        branch.setBranchCode("BR_" + uid);
        branch.setBranchName("Cơ sở Trung Tâm " + uid);
        branch.setAddress("123 Nguyễn Thị Minh Khai, Q1");
        branch.setOpeningTime(LocalTime.of(7, 0));
        branch.setClosingTime(LocalTime.of(22, 0));
        branch.setWorkingDays("2,3,4,5,6,7,CN");
        branch.setIsActive(true);
        branch = branchRepository.saveAndFlush(branch);

        // Security scope: user has full branch access and can manage queue
        ReceptionAccess.Scope scope = new ReceptionAccess.Scope(staffUser, true, true, List.of(branch.getBranchId()));
        when(access.require(any())).thenReturn(scope);
        when(emrAccess.require(any())).thenReturn(new EmrAccess.Scope(staffUser, true, true,
                Set.of(branch.getBranchId())));

        // 3. Doctor Role
        doctorRole = roleRepository.findByRoleName("BAC_SI")
                .orElseGet(() -> {
                    Role r = new Role();
                    r.setRoleName("BAC_SI");
                    return roleRepository.saveAndFlush(r);
                });

        // 4. Doctor Users & Profiles
        User docUser1 = new User();
        docUser1.setUsername("doc_nam_" + uid);
        docUser1.setPassword("password123");
        docUser1.setEmail("doc_nam_" + uid + "@clinic.test");
        docUser1.setPhoneNumber("098" + uid);
        docUser1.setUserType(UserType.STAFF);
        docUser1 = userRepository.saveAndFlush(docUser1);

        doctorNam = new StaffProfile();
        doctorNam.setUser(docUser1);
        doctorNam.setStaffCode("BS_NAM_" + uid);
        doctorNam.setFullName("BS. CK1 Lê Hoàng Nam");
        doctorNam.setStatus(StaffStatus.ACTIVE);
        doctorNam.setBaseSalary(new BigDecimal("25000000"));
        doctorNam = staffProfileRepository.saveAndFlush(doctorNam);

        User docUser2 = new User();
        docUser2.setUsername("doc_huong_" + uid);
        docUser2.setPassword("password123");
        docUser2.setEmail("doc_huong_" + uid + "@clinic.test");
        docUser2.setPhoneNumber("097" + uid);
        docUser2.setUserType(UserType.STAFF);
        docUser2 = userRepository.saveAndFlush(docUser2);

        doctorHuong = new StaffProfile();
        doctorHuong.setUser(docUser2);
        doctorHuong.setStaffCode("BS_HUONG_" + uid);
        doctorHuong.setFullName("BS. Trần Thu Hương");
        doctorHuong.setStatus(StaffStatus.ACTIVE);
        doctorHuong.setBaseSalary(new BigDecimal("22000000"));
        doctorHuong = staffProfileRepository.saveAndFlush(doctorHuong);

        // 5. Staff Branch Assignments
        StaffBranchAssignment assignNam = new StaffBranchAssignment();
        assignNam.setStaff(doctorNam);
        assignNam.setBranch(branch);
        assignNam.setRole(doctorRole);
        assignNam.setIsPrimary(true);
        assignNam.setStartDate(LocalDate.now(CLINIC_ZONE).minusMonths(1));
        assignNam.setStatus(AssignmentStatus.ACTIVE);
        assignmentRepository.saveAndFlush(assignNam);

        StaffBranchAssignment assignHuong = new StaffBranchAssignment();
        assignHuong.setStaff(doctorHuong);
        assignHuong.setBranch(branch);
        assignHuong.setRole(doctorRole);
        assignHuong.setIsPrimary(true);
        assignHuong.setStartDate(LocalDate.now(CLINIC_ZONE).minusMonths(1));
        assignHuong.setStatus(AssignmentStatus.ACTIVE);
        assignmentRepository.saveAndFlush(assignHuong);

        // 6. Work Shift (covers 00:00 to 23:59 to guarantee current time is working)
        allDayShift = new WorkShift();
        allDayShift.setBranch(branch);
        allDayShift.setShiftCode("SHIFT_ALL_" + uid);
        allDayShift.setShiftName("Ca cả ngày");
        allDayShift.setStartTime(LocalTime.of(0, 0));
        allDayShift.setEndTime(LocalTime.of(23, 59));
        allDayShift.setIsActive(true);
        allDayShift = shiftRepository.saveAndFlush(allDayShift);

        // 7. Staff Schedules for Today
        LocalDate today = LocalDate.now(CLINIC_ZONE);

        StaffSchedule schedNam = new StaffSchedule();
        schedNam.setStaff(doctorNam);
        schedNam.setBranch(branch);
        schedNam.setShift(allDayShift);
        schedNam.setWorkDate(today);
        schedNam.setStatus(ScheduleStatus.SCHEDULED);
        scheduleRepository.saveAndFlush(schedNam);

        StaffSchedule schedHuong = new StaffSchedule();
        schedHuong.setStaff(doctorHuong);
        schedHuong.setBranch(branch);
        schedHuong.setShift(allDayShift);
        schedHuong.setWorkDate(today);
        schedHuong.setStatus(ScheduleStatus.SCHEDULED);
        scheduleRepository.saveAndFlush(schedHuong);

        // 8. Rooms and Dental Chairs
        room1 = new Room();
        room1.setBranch(branch);
        room1.setRoomCode("R101_" + uid);
        room1.setRoomName("Phòng Khám 101");
        room1.setRoomType(RoomType.TREATMENT);
        room1.setStatus(RoomStatus.ACTIVE);
        room1 = roomRepository.saveAndFlush(room1);

        room2 = new Room();
        room2.setBranch(branch);
        room2.setRoomCode("R102_" + uid);
        room2.setRoomName("Phòng Khám 102");
        room2.setRoomType(RoomType.TREATMENT);
        room2.setStatus(RoomStatus.ACTIVE);
        room2 = roomRepository.saveAndFlush(room2);

        chair1 = new DentalChair();
        chair1.setBranch(branch);
        chair1.setRoom(room1);
        chair1.setChairCode("C01_" + uid);
        chair1.setChairName("Ghế Khám 1");
        chair1.setStatus(ChairStatus.AVAILABLE);
        chair1 = chairRepository.saveAndFlush(chair1);

        chair2 = new DentalChair();
        chair2.setBranch(branch);
        chair2.setRoom(room1);
        chair2.setChairCode("C02_" + uid);
        chair2.setChairName("Ghế Khám 2");
        chair2.setStatus(ChairStatus.AVAILABLE);
        chair2 = chairRepository.saveAndFlush(chair2);

        chair3 = new DentalChair();
        chair3.setBranch(branch);
        chair3.setRoom(room2);
        chair3.setChairCode("C03_" + uid);
        chair3.setChairName("Ghế Khám 3");
        chair3.setStatus(ChairStatus.AVAILABLE);
        chair3 = chairRepository.saveAndFlush(chair3);
    }

    private PatientProfile createPatient(String name, String phone, int age) {
        String uid = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        User patientUser = new User();
        patientUser.setUsername("pt_" + uid);
        patientUser.setPassword("password123");
        patientUser.setEmail("pt_" + uid + "@patient.test");
        patientUser.setPhoneNumber(phone);
        patientUser.setUserType(UserType.PATIENT);
        patientUser = userRepository.saveAndFlush(patientUser);

        PatientProfile profile = new PatientProfile();
        profile.setUser(patientUser);
        profile.setPatientCode("BN_" + uid);
        profile.setFullName(name);
        profile.setGender("Nam");
        profile.setDob(LocalDate.now(CLINIC_ZONE).minusYears(age));
        profile.setPhoneNumber(phone);
        profile.setAddress("TP Hồ Chí Minh");
        profile.setPreferredBranch(branch);
        profile.setStatus("ACTIVE");
        return patientProfileRepository.saveAndFlush(profile);
    }

    private Appointment createAppointment(PatientProfile patient, StaffProfile doctor,
                                          AppointmentStatus status, String symptoms) {
        String uid = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Appointment app = new Appointment();
        app.setAppointmentCode("LH_" + uid);
        app.setPatientProfile(patient);
        app.setBranch(branch);
        app.setDoctor(doctor);
        app.setAppointmentDate(LocalDate.now(CLINIC_ZONE));
        app.setPreferredSession(PreferredSession.MORNING);
        app.setInitialSymptoms(symptoms);
        app.setSource(AppointmentSource.ONLINE);
        app.setStatus(status);
        return checkInAppointmentRepository.saveAndFlush(app);
    }

    @Test
    @DisplayName("Track 1: Pre-booked Patient with Requested Doctor (FT05 -> FT06 -> FT07 -> FT08 -> FT09 -> FT10)")
    void testTrack1_PreBookedPatientWithRequestedDoctor_E2E() {
        // [FT05] Patient books appointment with requested doctor (Dr. Nam), initially PENDING
        PatientProfile patient = createPatient("Đỗ Khánh Vy", "0909001001", 28);
        Appointment appointment = createAppointment(patient, doctorNam, AppointmentStatus.PENDING, "Đau nhức răng hàm số 6");
        assertEquals(AppointmentStatus.PENDING, appointment.getStatus());
        assertEquals(doctorNam.getStaffId(), appointment.getDoctor().getStaffId());

        // [FT06 & FT07] Receptionist confirms and checks in patient on arrival
        String requestKey = UUID.randomUUID().toString();
        ReceptionService.VisitResult visitResult = receptionService.confirmAndCheckIn(
                session, appointment.getAppointmentId(), "Đau nhức răng hàm số 6 dữ dội khi nhai", requestKey);

        assertNotNull(visitResult.id());
        Visit visit = visitRepository.findById(visitResult.id()).orElseThrow();
        assertEquals(VisitStatus.WAITING, visit.getStatus());
        assertEquals(VisitType.APPOINTMENT, visit.getVisitType());
        assertEquals(appointment.getAppointmentId(), visit.getAppointmentId());

        // Appointment transitioned to CHECKED_IN
        Appointment checkedInApp = checkInAppointmentRepository.findById(appointment.getAppointmentId()).orElseThrow();
        assertEquals(AppointmentStatus.CHECKED_IN, checkedInApp.getStatus());

        // Audit histories recorded: CONFIRM and CHECK_IN
        List<AppointmentHistory> histories = historyRepository
                .findByAppointment_AppointmentIdOrderByCreatedAtAscHistoryIdAsc(appointment.getAppointmentId());
        assertEquals(2, histories.size());
        assertEquals("CONFIRM", histories.get(0).getAction());
        assertEquals("CHECK_IN", histories.get(1).getAction());

        // [FT08] Dispatch board loads: detects requested doctor Dr. Nam
        DispatchService.Board board = dispatchService.board(session, branch.getBranchId());
        assertTrue(board.requests().containsKey(appointment.getAppointmentId()));
        DispatchService.DoctorRequest requestInfo = board.requests().get(appointment.getAppointmentId());
        assertEquals(doctorNam.getStaffId(), requestInfo.id());
        assertTrue(requestInfo.available(), "Requested doctor must be on duty");

        // Dispatch assigns Dr. Nam, Room 101, Chair 1
        dispatchService.assign(session, branch.getBranchId(), visit.getVisitId(),
                doctorNam.getStaffId(), room1.getRoomId(), chair1.getChairId(), null);

        Visit assignedVisit = visitRepository.findById(visit.getVisitId()).orElseThrow();
        assertEquals(doctorNam.getStaffId(), assignedVisit.getPrimaryDoctor().getStaffId());
        assertEquals(room1.getRoomId(), assignedVisit.getRoomId());
        assertEquals(chair1.getChairId(), assignedVisit.getChairId());

        // [FT09] Issue Exam Ticket: Normal sequence
        QueueTicket ticket = dispatchService.issue(session, branch.getBranchId(), visit.getVisitId(), false, null);
        assertNotNull(ticket.getTicketId());
        assertEquals("01", ticket.getDisplayNumber());
        assertEquals(1, ticket.getTicketNumber());
        assertFalse(ticket.getIsPriority());
        assertFalse(ticket.getIsEmergency());
        assertEquals(0, ticket.getPriorityScore());
        assertEquals(QueueStatus.WAITING, ticket.getStatus());

        // Idempotency: re-issuing ticket for same visit returns identical ticket without advancing sequence
        QueueTicket reissuedTicket = dispatchService.issue(session, branch.getBranchId(), visit.getVisitId(), false, null);
        assertEquals(ticket.getTicketId(), reissuedTicket.getTicketId());
        assertEquals("01", reissuedTicket.getDisplayNumber());

        // [FT10] Doctor Nam calls next ticket
        QueueTicket calledTicket = queueWorkflowService.callNext(session, branch.getBranchId(), doctorNam.getStaffId());
        assertEquals(ticket.getTicketId(), calledTicket.getTicketId());
        assertEquals(QueueStatus.CALLED, calledTicket.getStatus());
        assertNotNull(calledTicket.getCalledAt());

        // [FT10] Start Examination
        queueWorkflowService.transition(session, ticket.getTicketId(), QueueWorkflowService.Action.START, null, null);

        QueueTicket serviceTicket = ticketRepository.findById(ticket.getTicketId()).orElseThrow();
        assertEquals(QueueStatus.IN_SERVICE, serviceTicket.getStatus());

        Visit examVisit = visitRepository.findById(visit.getVisitId()).orElseThrow();
        assertEquals(VisitStatus.IN_EXAM, examVisit.getStatus());

        // Doctor records the clinical conclusion and a coded main diagnosis.
        ClinicalExamination examination = examinationRepository.findByVisit_VisitId(visit.getVisitId()).orElseThrow();
        examination.setSummaryAssessment("Sâu răng cần điều trị và theo dõi");
        examinationRepository.saveAndFlush(examination);
        Icd10Disease disease = new Icd10Disease();
        disease.setIcd10Code("K02." + UUID.randomUUID().toString().substring(0, 6));
        disease.setDiseaseNameVi("Sâu răng");
        disease = icdRepository.saveAndFlush(disease);
        ExaminationDiagnosis diagnosis = new ExaminationDiagnosis();
        diagnosis.setClinicalExamination(examination); diagnosis.setVisit(examVisit);
        diagnosis.setDiagnosisType(DiagnosisType.MAIN);
        diagnosis.setIcd10Disease(disease); diagnosis.setDiagnosisText("Sâu răng");
        diagnosisRepository.saveAndFlush(diagnosis);

        Long emrId = emrModuleService.create(session, visit.getVisitId());
        assertNotNull(emrId);
        assertEquals(EmrRecordStatus.DRAFT, emrRecordRepository.findByVisit_VisitId(visit.getVisitId())
                .orElseThrow().getStatus());
        assertEquals(visit.getVisitId(), emrRecordRepository.findById(emrId).orElseThrow()
                .getVisit().getVisitId());

        DentalChair usedChair = chairRepository.findById(chair1.getChairId()).orElseThrow();
        assertEquals(ChairStatus.IN_USE, usedChair.getStatus());

        // Doctor finishes clinical exam -> Visit moves to WAITING_PAYMENT
        examVisit.setStatus(VisitStatus.WAITING_PAYMENT);
        visitRepository.saveAndFlush(examVisit);

        // [FT10] Complete ticket (DONE) -> Chair automatically transitions to CLEANING
        queueWorkflowService.transition(session, ticket.getTicketId(), QueueWorkflowService.Action.DONE, null, null);

        QueueTicket doneTicket = ticketRepository.findById(ticket.getTicketId()).orElseThrow();
        assertEquals(QueueStatus.DONE, doneTicket.getStatus());
        assertNotNull(doneTicket.getCompletedAt());

        DentalChair cleanedChair = chairRepository.findById(chair1.getChairId()).orElseThrow();
        assertEquals(ChairStatus.CLEANING, cleanedChair.getStatus());

        // Payment completes the visit before the appointment can be closed.
        examVisit.setStatus(VisitStatus.COMPLETED);
        visitRepository.saveAndFlush(examVisit);
        // Appointment Lifecycle Completion triggered by Visit finish
        lifecycleService.completeByVisit(visit.getVisitId(), staffUser.getUserId());

        Appointment completedApp = checkInAppointmentRepository.findById(appointment.getAppointmentId()).orElseThrow();
        assertEquals(AppointmentStatus.COMPLETED, completedApp.getStatus());

        List<AppointmentHistory> finalHistories = historyRepository
                .findByAppointment_AppointmentIdOrderByCreatedAtAscHistoryIdAsc(appointment.getAppointmentId());
        assertEquals(3, finalHistories.size());
        assertEquals("COMPLETE", finalHistories.get(2).getAction());
    }

    @Test
    @DisplayName("Track 2: Walk-in Patient with Least-Loaded Auto-Assignment & Age Priority Upgrade (FT07 -> FT08 -> FT09 -> FT10)")
    void testTrack2_WalkInPatient_LeastLoadedAutoAssignment_AgePriorityUpgrade_E2E() {
        // Prepare doctor loads: Doctor Nam already has 1 waiting visit
        PatientProfile busyPatient = createPatient("Bệnh nhân Đang chờ", "0911000111", 30);
        Visit busyVisit = new Visit();
        busyVisit.setPatientProfile(busyPatient);
        busyVisit.setBranch(branch);
        busyVisit.setVisitType(VisitType.WALK_IN);
        busyVisit.setCheckInAt(LocalDateTime.now(CLINIC_ZONE));
        busyVisit.setStatus(VisitStatus.WAITING);
        busyVisit.setPrimaryDoctor(doctorNam);
        visitRepository.saveAndFlush(busyVisit);

        // [FT07] Elderly walk-in patient arrives without prior appointment
        PatientProfile elderlyPatient = createPatient("Cụ Nguyễn Văn Lão", "0933999888", 78);
        String requestKey = UUID.randomUUID().toString();

        ReceptionService.VisitResult walkInResult = receptionService.checkInWalkIn(
                session, branch.getBranchId(), elderlyPatient.getProfileId(),
                "Đau buốt răng khi uống nước lạnh", false, requestKey);

        assertNotNull(walkInResult.id());
        Visit walkInVisit = visitRepository.findById(walkInResult.id()).orElseThrow();
        assertEquals(VisitStatus.WAITING, walkInVisit.getStatus());
        assertEquals(VisitType.WALK_IN, walkInVisit.getVisitType());
        assertNull(walkInVisit.getAppointmentId(), "Walk-in visits must not be linked to any appointment");

        // [FT08] Dispatch without doctorId: Server auto-assigns to least-loaded doctor (Dr. Huong with load 0 vs Dr. Nam with load 1)
        dispatchService.assign(session, branch.getBranchId(), walkInVisit.getVisitId(),
                null, room2.getRoomId(), chair3.getChairId(), null);

        Visit autoAssignedVisit = visitRepository.findById(walkInVisit.getVisitId()).orElseThrow();
        assertEquals(doctorHuong.getStaffId(), autoAssignedVisit.getPrimaryDoctor().getStaffId(),
                "Server must auto-select least-loaded doctor (Dr. Huong)");
        assertEquals(room2.getRoomId(), autoAssignedVisit.getRoomId());
        assertEquals(chair3.getChairId(), autoAssignedVisit.getChairId());

        // [FT09] Normal ticket issued for walk-in patient
        QueueTicket walkInTicket = dispatchService.issue(session, branch.getBranchId(), walkInVisit.getVisitId(), false, null);
        assertEquals(1, walkInTicket.getTicketNumber());
        assertEquals("01", walkInTicket.getDisplayNumber());
        assertEquals(0, walkInTicket.getPriorityScore());

        // [FT10] Priority Upgrade: Staff identifies patient age >= 65 and triggers PRIORITIZE
        queueWorkflowService.transition(session, walkInTicket.getTicketId(),
                QueueWorkflowService.Action.PRIORITIZE, null, "Bệnh nhân 78 tuổi, sức khỏe yếu cần ưu tiên");

        QueueTicket prioritizedTicket = ticketRepository.findById(walkInTicket.getTicketId()).orElseThrow();
        assertEquals(50, prioritizedTicket.getPriorityScore());
        assertEquals("Bệnh nhân 78 tuổi, sức khỏe yếu cần ưu tiên", prioritizedTicket.getPriorityReason());

        // Priority ordering verification: Add a subsequent normal walk-in ticket for Dr. Huong
        PatientProfile youngerPatient = createPatient("Anh Lê Trẻ", "0944111222", 25);
        ReceptionService.VisitResult secondWalkIn = receptionService.checkInWalkIn(
                session, branch.getBranchId(), youngerPatient.getProfileId(),
                "Lấy cao răng", false, UUID.randomUUID().toString());
        dispatchService.assign(session, branch.getBranchId(), secondWalkIn.id(),
                doctorHuong.getStaffId(), room2.getRoomId(), chair3.getChairId(), null);
        QueueTicket normalTicket = dispatchService.issue(session, branch.getBranchId(), secondWalkIn.id(), false, null);
        assertEquals("02", normalTicket.getDisplayNumber());
        assertEquals(0, normalTicket.getPriorityScore());

        // Dr. Huong calls next -> The prioritized ticket (score 50) is selected ahead of normal ticket (score 0)
        QueueTicket calledByHuong = queueWorkflowService.callNext(session, branch.getBranchId(), doctorHuong.getStaffId());
        assertEquals(walkInTicket.getTicketId(), calledByHuong.getTicketId(),
                "Prioritized ticket must be called before standard tickets");
        assertEquals(QueueStatus.CALLED, calledByHuong.getStatus());

        // Start and Finish
        queueWorkflowService.transition(session, walkInTicket.getTicketId(), QueueWorkflowService.Action.START, null, null);
        DentalChair chair = chairRepository.findById(chair3.getChairId()).orElseThrow();
        assertEquals(ChairStatus.IN_USE, chair.getStatus());

        walkInVisit = visitRepository.findById(walkInVisit.getVisitId()).orElseThrow();
        walkInVisit.setStatus(VisitStatus.WAITING_PAYMENT);
        visitRepository.saveAndFlush(walkInVisit);

        queueWorkflowService.transition(session, walkInTicket.getTicketId(), QueueWorkflowService.Action.DONE, null, null);
        chair = chairRepository.findById(chair3.getChairId()).orElseThrow();
        assertEquals(ChairStatus.CLEANING, chair.getStatus());
    }

    @Test
    @DisplayName("Track 3: Emergency Intake with Instant Top-of-Queue Jump & Dynamic Chair Switching (FT07 -> FT08 -> FT09 -> FT10)")
    void testTrack3_EmergencyIntake_InstantTopQueue_DynamicChairSwitch_E2E() {
        // [FT07] Emergency patient arrives with acute oral bleeding
        PatientProfile emergencyPatient = createPatient("Nguyễn Cấp Cứu", "0999115115", 35);
        String requestKey = UUID.randomUUID().toString();

        ReceptionService.VisitResult emergencyResult = receptionService.checkInWalkIn(
                session, branch.getBranchId(), emergencyPatient.getProfileId(),
                "Chảy máu ổ răng ồ ạt sau nhổ tại nhà", true, requestKey);

        assertNotNull(emergencyResult.id());
        Visit emergencyVisit = visitRepository.findById(emergencyResult.id()).orElseThrow();
        assertEquals(VisitStatus.WAITING, emergencyVisit.getStatus());
        assertEquals(VisitType.EMERGENCY, emergencyVisit.getVisitType());

        // [FT08] Assign to Dr. Nam and Room 101, Chair 1
        dispatchService.assign(session, branch.getBranchId(), emergencyVisit.getVisitId(),
                doctorNam.getStaffId(), room1.getRoomId(), chair1.getChairId(), null);

        // [FT09] Emergency ticket issuance requires priority=true
        assertThrows(ResponseStatusException.class, () ->
                dispatchService.issue(session, branch.getBranchId(), emergencyVisit.getVisitId(), false, null));

        QueueTicket emergencyTicket = dispatchService.issue(session, branch.getBranchId(), emergencyVisit.getVisitId(),
                true, "Ca cấp cứu chảy máu ổ răng nặng");
        assertEquals("P-01", emergencyTicket.getDisplayNumber());
        assertTrue(emergencyTicket.getIsEmergency());
        assertTrue(emergencyTicket.getIsPriority());
        assertEquals(100, emergencyTicket.getPriorityScore());

        // Queue Head Verification: Add an existing normal ticket for Dr. Nam
        PatientProfile normalPatient = createPatient("Bệnh nhân Thường", "0988777666", 40);
        ReceptionService.VisitResult normalResult = receptionService.checkInWalkIn(
                session, branch.getBranchId(), normalPatient.getProfileId(),
                "Khám sâu răng thông thường", false, UUID.randomUUID().toString());
        dispatchService.assign(session, branch.getBranchId(), normalResult.id(),
                doctorNam.getStaffId(), room1.getRoomId(), chair1.getChairId(), null);
        QueueTicket normalTicket = dispatchService.issue(session, branch.getBranchId(), normalResult.id(), false, null);

        // Doctor Nam calls next -> Emergency ticket (score 100) instantly called first
        QueueTicket nextCalled = queueWorkflowService.callNext(session, branch.getBranchId(), doctorNam.getStaffId());
        assertEquals(emergencyTicket.getTicketId(), nextCalled.getTicketId(),
                "Emergency ticket must immediately top the queue ahead of normal tickets");

        // [FT10 Dynamic Chair Switching before START]
        // Simulate chair 1 unexpectedly occupied by another procedure
        chair1.setStatus(ChairStatus.IN_USE);
        chairRepository.saveAndFlush(chair1);

        // Attempting to START with original occupied chair1 fails
        assertThrows(ResponseStatusException.class, () ->
                queueWorkflowService.transition(session, emergencyTicket.getTicketId(),
                        QueueWorkflowService.Action.START, null, null));

        // Switch to alternative available chair 2 in Room 101 dynamically on START
        queueWorkflowService.transition(session, emergencyTicket.getTicketId(),
                QueueWorkflowService.Action.START, chair2.getChairId(), null);

        // Verify chair 2 is now in use and visit's chair is updated
        DentalChair switchedChair = chairRepository.findById(chair2.getChairId()).orElseThrow();
        assertEquals(ChairStatus.IN_USE, switchedChair.getStatus());

        Visit startedEmergencyVisit = visitRepository.findById(emergencyVisit.getVisitId()).orElseThrow();
        assertEquals(chair2.getChairId(), startedEmergencyVisit.getChairId(),
                "Visit chairId must be dynamically updated to Chair 2");
        assertEquals(VisitStatus.IN_EXAM, startedEmergencyVisit.getStatus());

        // Conclude exam and complete ticket
        startedEmergencyVisit.setStatus(VisitStatus.WAITING_PAYMENT);
        visitRepository.saveAndFlush(startedEmergencyVisit);

        queueWorkflowService.transition(session, emergencyTicket.getTicketId(),
                QueueWorkflowService.Action.DONE, null, null);

        switchedChair = chairRepository.findById(chair2.getChairId()).orElseThrow();
        assertEquals(ChairStatus.CLEANING, switchedChair.getStatus());
    }

    @Test
    @DisplayName("Track 4: Cross-Cutting Safeguards (Revert Check-in, Public TV Board Zero-PII, Queue Isolation & Out-of-Order Calling)")
    void testTrack4_CrossCuttingSafeguards_E2E() {
        // Safeguard 1: Revert Check-In
        PatientProfile patient = createPatient("Lê Hoài Nam", "0912345678", 32);
        Appointment appointment = createAppointment(patient, doctorNam, AppointmentStatus.CONFIRMED, "Khám tổng quát");

        String requestKey = UUID.randomUUID().toString();
        ReceptionService.VisitResult checkInResult = receptionService.checkInAppointment(
                session, appointment.getAppointmentId(), "Khám tổng quát định kỳ", requestKey);
        assertEquals(AppointmentStatus.CHECKED_IN,
                checkInAppointmentRepository.findById(appointment.getAppointmentId()).orElseThrow().getStatus());

        // Patient checked in by mistake -> Revert check-in
        receptionService.revertCheckIn(session, appointment.getAppointmentId());

        Appointment revertedApp = checkInAppointmentRepository.findById(appointment.getAppointmentId()).orElseThrow();
        assertEquals(AppointmentStatus.CONFIRMED, revertedApp.getStatus());

        Visit cancelledVisit = visitRepository.findById(checkInResult.id()).orElseThrow();
        assertEquals(VisitStatus.CANCELLED, cancelledVisit.getStatus());

        // Can cleanly re-check-in after reverting
        ReceptionService.VisitResult reCheckIn = receptionService.checkInAppointment(
                session, appointment.getAppointmentId(), "Khám tổng quát định kỳ", UUID.randomUUID().toString());
        assertNotEquals(checkInResult.id(), reCheckIn.id());
        assertEquals(AppointmentStatus.CHECKED_IN,
                checkInAppointmentRepository.findById(appointment.getAppointmentId()).orElseThrow().getStatus());

        // Safeguard 2: Public TV Board Zero-PII Protection
        dispatchService.assign(session, branch.getBranchId(), reCheckIn.id(),
                doctorNam.getStaffId(), room1.getRoomId(), chair1.getChairId(), null);
        QueueTicket ticket = dispatchService.issue(session, branch.getBranchId(), reCheckIn.id(), false, null);
        queueWorkflowService.callNext(session, branch.getBranchId(), doctorNam.getStaffId());

        QueueWorkflowService.PublicBoard publicBoard = queueWorkflowService.publicBoard(branch.getBranchId());
        assertNotNull(publicBoard);
        assertEquals(branch.getBranchName(), publicBoard.branch());
        assertEquals(1, publicBoard.called().size());

        QueueWorkflowService.PublicNumber publicCalled = publicBoard.called().get(0);
        assertEquals(ticket.getDisplayNumber(), publicCalled.number());
        assertEquals("BS. CK1 Lê Hoàng Nam", publicCalled.doctor());
        assertEquals("Phòng Khám 101", publicCalled.room());
        assertEquals("Ghế Khám 1", publicCalled.chair());

        // Assert absolutely NO Patient PII exposed on TV Board
        String publicBoardString = publicBoard.toString();
        assertFalse(publicBoardString.contains("Lê Hoài Nam"), "Public TV board must not reveal patient name");
        assertFalse(publicBoardString.contains("0912345678"), "Public TV board must not reveal patient phone");
        assertFalse(publicBoardString.contains("Khám tổng quát"), "Public TV board must not reveal patient symptoms");

        // Safeguard 3: Queue Isolation between Doctors
        // Prepare ticket for Doctor Huong
        PatientProfile huongPatient = createPatient("Vũ Thị Mai", "0966555444", 26);
        ReceptionService.VisitResult huongResult = receptionService.checkInWalkIn(
                session, branch.getBranchId(), huongPatient.getProfileId(),
                "Tẩy trắng răng", false, UUID.randomUUID().toString());
        dispatchService.assign(session, branch.getBranchId(), huongResult.id(),
                doctorHuong.getStaffId(), room2.getRoomId(), chair3.getChairId(), null);
        QueueTicket huongTicket = dispatchService.issue(session, branch.getBranchId(), huongResult.id(), false, null);

        // Calling next for Doctor Huong only affects Doctor Huong's queue
        QueueTicket calledHuongTicket = queueWorkflowService.callNext(session, branch.getBranchId(), doctorHuong.getStaffId());
        assertEquals(huongTicket.getTicketId(), calledHuongTicket.getTicketId());

        // Doctor Nam has no other waiting tickets -> callNext for Doctor Nam fails with CONFLICT
        assertThrows(ResponseStatusException.class, () ->
                queueWorkflowService.callNext(session, branch.getBranchId(), doctorNam.getStaffId()));

        // Safeguard 4: Out-of-Order Calling Rules
        // Add two tickets for Doctor Huong: ticketA and ticketB
        ReceptionService.VisitResult resA = receptionService.checkInWalkIn(session, branch.getBranchId(),
                createPatient("Bệnh nhân A", "0977000001", 20).getProfileId(), "Khám A", false, UUID.randomUUID().toString());
        dispatchService.assign(session, branch.getBranchId(), resA.id(), doctorHuong.getStaffId(), room2.getRoomId(), chair3.getChairId(), null);
        QueueTicket ticketA = dispatchService.issue(session, branch.getBranchId(), resA.id(), false, null);

        ReceptionService.VisitResult resB = receptionService.checkInWalkIn(session, branch.getBranchId(),
                createPatient("Bệnh nhân B", "0977000002", 21).getProfileId(), "Khám B", false, UUID.randomUUID().toString());
        dispatchService.assign(session, branch.getBranchId(), resB.id(), doctorHuong.getStaffId(), room2.getRoomId(), chair3.getChairId(), null);
        QueueTicket ticketB = dispatchService.issue(session, branch.getBranchId(), resB.id(), false, null);

        // Complete the earlier called huongTicket to allow doctor Huong to call again
        queueWorkflowService.transition(session, huongTicket.getTicketId(), QueueWorkflowService.Action.START, null, null);
        Visit huongVisit = visitRepository.findById(huongResult.id()).orElseThrow();
        huongVisit.setStatus(VisitStatus.WAITING_PAYMENT);
        visitRepository.saveAndFlush(huongVisit);
        queueWorkflowService.transition(session, huongTicket.getTicketId(), QueueWorkflowService.Action.DONE, null, null);

        // ticketA is front of line. Calling ticketB out-of-order without permission throws error
        User regularStaff = new User();
        regularStaff.setUserId(999L);
        when(access.require(session)).thenReturn(new ReceptionAccess.Scope(regularStaff, false, false, List.of(branch.getBranchId())));

        assertThrows(ResponseStatusException.class, () ->
                queueWorkflowService.transition(session, ticketB.getTicketId(), QueueWorkflowService.Action.CALL, null, "Lý do hợp lệ trên mười ký tự"));

        // With management permission but without sufficient reason length (< 10 chars) -> fails
        when(access.require(session)).thenReturn(new ReceptionAccess.Scope(staffUser, true, true, List.of(branch.getBranchId())));
        assertThrows(ResponseStatusException.class, () ->
                queueWorkflowService.transition(session, ticketB.getTicketId(), QueueWorkflowService.Action.CALL, null, "Ngắn"));

        // With management permission and valid justification (>= 10 chars) -> succeeds
        queueWorkflowService.transition(session, ticketB.getTicketId(), QueueWorkflowService.Action.CALL, null, "Bệnh nhân có lịch hẹn trước cần vào sớm");
        assertEquals(QueueStatus.CALLED, ticketRepository.findById(ticketB.getTicketId()).orElseThrow().getStatus());
    }
}
