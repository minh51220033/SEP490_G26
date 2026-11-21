package com.cvdcms.receptionappointment;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.reception.ReceptionCheckInKeyRepository;
import com.cvdcms.reception.ReceptionConsultationService;
import com.cvdcms.reception.ReceptionService;
import com.cvdcms.reception.ReceptionVisitRepository;
import com.cvdcms.repository.*;
import com.cvdcms.service.AppointmentPolicyService;
import com.cvdcms.service.ClinicQueueCoordinatorService;
import com.cvdcms.service.LeaveRequestService;
import com.cvdcms.service.StaffScheduleService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:appt_part_c_integration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;NON_KEYWORDS=MONTH,YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AppointmentReceptionPartCIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private LeaveRequestService leaveRequestService;
    @Autowired private StaffScheduleService staffScheduleService;
    @Autowired private ReceptionAppointmentService receptionAppointmentService;
    @Autowired private ReceptionService receptionService;
    @Autowired private ReceptionConsultationService receptionConsultationService;
    @Autowired private ClinicQueueCoordinatorService clinicQueueCoordinatorService;

    @Autowired private BranchRepository branchRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PatientProfileRepository patientProfileRepository;
    @Autowired private StaffProfileRepository staffProfileRepository;
    @Autowired private StaffBranchAssignmentRepository branchAssignmentRepository;
    @Autowired private WorkShiftRepository workShiftRepository;
    @Autowired private StaffScheduleRepository staffScheduleRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private AppointmentHistoryRepository appointmentHistoryRepository;
    @Autowired private CustomerCareTaskRepository careTaskRepository;
    @Autowired private SystemNotificationRepository notificationRepository;
    @Autowired private ReceptionVisitRepository visitRepository;
    @Autowired private ReceptionCheckInKeyRepository checkInKeyRepository;
    @Autowired private ClinicalQueueTicketRepository queueTicketRepository;
    @Autowired private BookingConsultationRequestRepository consultationRequestRepository;
    @Autowired private BookingConsultationLogRepository consultationLogRepository;
    @Autowired private SystemAuditLogRepository auditLogRepository;

    private static final ZoneId CLINIC_ZONE = AppointmentPolicyService.CLINIC_ZONE;
    private static final AtomicLong SEQ = new AtomicLong(5000);

    private String uid;
    private LocalDate today;
    private LocalDate futureDate;
    private Branch branch;
    private WorkShift morningShift;
    private WorkShift afternoonShift;
    private StaffProfile doctorA;
    private StaffProfile doctorB;
    private User receptionistOnly;
    private User dispatcherUser;
    private User adminReviewer;
    private User patientUser1;
    private PatientProfile patient1;
    private PatientProfile patient2;
    private PatientProfile patient3;
    private PatientProfile patient4;
    private PatientProfile patient5;
    private PatientProfile patient6;

    @BeforeEach
    void setUp() {
        receptionService.setClock(Clock.system(CLINIC_ZONE));
        clinicQueueCoordinatorService.setClock(Clock.system(CLINIC_ZONE));
        try {
            jdbcTemplate.execute("ALTER TABLE patient_profiles ALTER COLUMN user_id SET NULL");
        } catch (Exception ignored) {
        }
        cleanAll();

        uid = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        today = LocalDate.now(CLINIC_ZONE);
        futureDate = today.plusDays(3);

        Permission pView = ensurePermission("APPOINTMENT_VIEW", "Xem lịch hẹn", "RECEPTION");
        Permission pManage = ensurePermission("APPOINTMENT_MANAGE", "Quản lý lịch hẹn", "RECEPTION");
        Permission pCheckIn = ensurePermission("RECEPTION_CHECKIN", "Tiếp đón", "RECEPTION");
        Permission pDispatch = ensurePermission("CLINICAL_DISPATCH", "Điều phối", "RECEPTION");
        Permission pRecord = ensurePermission("MEDICAL_RECORD_VIEW", "Xem hồ sơ", "CLINICAL");

        Role receptionRole = ensureRole("LE_TAN", "Lễ tân tiếp đón", Set.of(pView, pManage, pCheckIn));
        Role dispatchRole = ensureRole("DISP_C_" + uid, "Lễ tân điều phối", Set.of(pView, pManage, pCheckIn, pDispatch));
        Role adminRole = ensureRole("ADMIN", "Quản trị viên", Set.of(pView, pManage, pCheckIn, pDispatch));
        Role doctorRole = ensureRole("BAC_SI", "Bác sĩ", Set.of(pRecord));
        Role patientRole = ensureRole("BENH_NHAN", "Bệnh nhân", Set.of());

        branch = new Branch();
        branch.setBranchCode("BRC_" + uid);
        branch.setBranchName("Cơ sở Part C " + uid);
        branch.setAddress("Đà Nẵng");
        branch.setPhone("0236" + SEQ.incrementAndGet());
        branch.setOpeningTime(LocalTime.of(0, 0));
        branch.setClosingTime(LocalTime.of(23, 59));
        branch.setWorkingDays("2,3,4,5,6,7,CN");
        branch.setIsActive(true);
        branch = branchRepository.saveAndFlush(branch);

        morningShift = createShift("CA_SANG_" + uid, "Ca Sáng", LocalTime.of(7, 30), LocalTime.of(12, 0));
        afternoonShift = createShift("CA_CHIEU_" + uid, "Ca Chiều", LocalTime.of(13, 0), LocalTime.of(22, 0));

        doctorA = createDoctor("BS_A_" + uid, "BS. An", doctorRole);
        doctorB = createDoctor("BS_B_" + uid, "BS. Bình", doctorRole);

        for (LocalDate d : List.of(today, futureDate)) {
            createSchedule(doctorA, morningShift, d);
            createSchedule(doctorA, afternoonShift, d);
            createSchedule(doctorB, morningShift, d);
            createSchedule(doctorB, afternoonShift, d);
        }

        receptionistOnly = createStaffUser("rec_" + uid, receptionRole, "Lễ tân");
        dispatcherUser = createStaffUser("disp_" + uid, dispatchRole, "Điều phối");
        adminReviewer = createStaffUser("adm_" + uid, adminRole, "Quản trị");

        patientUser1 = createPatientUser("pat1_" + uid, patientRole);
        patient1 = createPatient("BNC1_" + uid, "Bệnh Nhân Một", patientUser1);
        patient2 = createPatient("BNC2_" + uid, "Bệnh Nhân Hai", null);
        patient3 = createPatient("BNC3_" + uid, "Bệnh Nhân Ba", null);
        patient4 = createPatient("BNC4_" + uid, "Bệnh Nhân Bốn", null);
        patient5 = createPatient("BNC5_" + uid, "Bệnh Nhân Năm", null);
        patient6 = createPatient("BNC6_" + uid, "Bệnh Nhân Sáu", null);
    }

    @AfterEach
    void tearDown() {
        receptionService.setClock(Clock.system(CLINIC_ZONE));
        clinicQueueCoordinatorService.setClock(Clock.system(CLINIC_ZONE));
    }

    @Test
    @DisplayName("C1: Duyệt nghỉ phép giữ lịch PENDING/CONFIRMED ở NEEDS_REASSIGNMENT (không tự hủy) & chặn check-in bác sĩ nghỉ")
    void doctorLeaveMarksNeedsReassignmentInsteadOfCancellingAndBlocksCheckIn() {
        receptionService.setClock(Clock.fixed(LocalDateTime.of(today, LocalTime.of(9, 0)).atZone(CLINIC_ZONE).toInstant(), CLINIC_ZONE));

        Appointment pendingToday = saveAppointment("LHP_" + uid, patient1, doctorA, today, PreferredSession.MORNING,
                LocalTime.of(9, 0), AppointmentStatus.PENDING);
        Appointment confirmedToday = saveAppointment("LHC_" + uid, patient2, doctorA, today, PreferredSession.MORNING,
                LocalTime.of(9, 30), AppointmentStatus.CONFIRMED);

        LeaveRequest leave = new LeaveRequest();
        leave.setStaff(doctorA);
        leave.setBranch(branch);
        leave.setShift(morningShift);
        leave.setStartDate(today);
        leave.setEndDate(today);
        leave.setLeaveType(LeaveType.ANNUAL);
        leave.setReason("Nghỉ đột xuất có việc gia đình");
        leave.setStatus(LeaveStatus.PENDING);
        leave = leaveRequestRepository.saveAndFlush(leave);

        int affectedCount = leaveRequestService.approveLeaveRequest(leave.getLeaveId(), adminReviewer.getUserId(), "Duyệt gấp");
        assertEquals(2, affectedCount);

        Appointment refreshedPending = appointmentRepository.findById(pendingToday.getAppointmentId()).orElseThrow();
        Appointment refreshedConfirmed = appointmentRepository.findById(confirmedToday.getAppointmentId()).orElseThrow();
        assertEquals(AppointmentStatus.PENDING, refreshedPending.getStatus(), "Lịch PENDING không được tự động hủy");
        assertEquals(AppointmentStatus.CONFIRMED, refreshedConfirmed.getStatus(), "Lịch CONFIRMED không được tự động hủy");

        List<AppointmentHistory> hist = appointmentHistoryRepository
                .findByAppointment_AppointmentIdOrderByCreatedAtAscHistoryIdAsc(confirmedToday.getAppointmentId());
        assertTrue(hist.stream().anyMatch(h -> "NEEDS_REASSIGNMENT".equals(h.getAction())));

        var detail = receptionAppointmentService.detail(sessionFor(receptionistOnly), confirmedToday.getAppointmentId());
        assertTrue(detail.appointment().doctorUnavailable());
        assertTrue(detail.appointment().isNeedsReassignment());
        assertEquals("NEEDS_REASSIGNMENT", detail.appointment().getWorkflowFlag());

        List<SystemNotification> notifs = notificationRepository.findAll();
        assertTrue(notifs.stream().anyMatch(n -> n.getUser().getUserId().equals(patientUser1.getUserId())
                && n.getTitle().contains("điều phối lại")));
        assertTrue(notifs.stream().anyMatch(n -> n.getUser().getUserId().equals(receptionistOnly.getUserId())
                && n.getTitle().contains("NEEDS_REASSIGNMENT")));

        // Cả checkInAppointment (CONFIRMED) lẫn confirmAndCheckIn (PENDING) đều phải chặn 409 CONFLICT
        ResponseStatusException checkInEx = assertThrows(ResponseStatusException.class, () ->
                receptionService.checkInAppointment(sessionFor(receptionistOnly), confirmedToday.getAppointmentId(),
                        "Đau răng", UUID.randomUUID().toString()));
        assertEquals(409, checkInEx.getStatusCode().value());
        assertTrue(checkInEx.getReason() != null && checkInEx.getReason().contains("NEEDS_REASSIGNMENT"));

        ResponseStatusException confirmCheckInEx = assertThrows(ResponseStatusException.class, () ->
                receptionService.confirmAndCheckIn(sessionFor(receptionistOnly), pendingToday.getAppointmentId(),
                        "Đau răng", UUID.randomUUID().toString()));
        assertEquals(409, confirmCheckInEx.getStatusCode().value());

        assertEquals(0, visitRepository.findAll().size());
    }

    @Test
    @DisplayName("C2: Lễ tân (không cần CLINICAL_DISPATCH) được đổi bác sĩ / đề xuất dời lịch khi NEEDS_REASSIGNMENT, nhưng bị 403 với lịch bình thường")
    void receptionistCanReassignUnavailableDoctorOrProposeRescheduleWhileHealthyRbacStaysStrict() {
        receptionService.setClock(Clock.fixed(LocalDateTime.of(today, LocalTime.of(9, 15)).atZone(CLINIC_ZONE).toInstant(), CLINIC_ZONE));

        Appointment healthyAppt = saveAppointment("LHH_" + uid, patient2, doctorA, today, PreferredSession.MORNING,
                LocalTime.of(9, 30), AppointmentStatus.CONFIRMED);

        // Khi bác sĩ A đang trực bình thường: lễ tân thuần túy (chỉ có APPOINTMENT_MANAGE) đổi bác sĩ -> 403 FORBIDDEN
        ResponseStatusException forbiddenEx = assertThrows(ResponseStatusException.class, () ->
                receptionAppointmentService.update(sessionFor(receptionistOnly), healthyAppt.getAppointmentId(),
                        new ReceptionAppointmentService.UpdateRequest(
                                today, PreferredSession.MORNING, doctorB.getStaffId(), null,
                                "Đau răng", "Thử đổi bác sĩ trái quyền", LocalTime.of(9, 30))));
        assertEquals(403, forbiddenEx.getStatusCode().value());

        // Khi bác sĩ A được duyệt nghỉ -> lịch chuyển sang NEEDS_REASSIGNMENT -> lễ tân được phép điều phối sang BS B & check-in
        LeaveRequest leave = new LeaveRequest();
        leave.setStaff(doctorA);
        leave.setBranch(branch);
        leave.setShift(morningShift);
        leave.setStartDate(today);
        leave.setEndDate(today);
        leave.setLeaveType(LeaveType.SICK);
        leave.setReason("Ốm đột xuất");
        leave.setStatus(LeaveStatus.PENDING);
        leave = leaveRequestRepository.saveAndFlush(leave);
        leaveRequestService.approveLeaveRequest(leave.getLeaveId(), adminReviewer.getUserId(), "Duyệt ốm");

        var reassigned = receptionAppointmentService.update(sessionFor(receptionistOnly), healthyAppt.getAppointmentId(),
                new ReceptionAppointmentService.UpdateRequest(
                        today, PreferredSession.MORNING, doctorB.getStaffId(), null,
                        "Đau răng", "Điều phối sang BS Bình do BS An nghỉ phép", LocalTime.of(9, 30)));
        assertEquals(doctorB.getStaffId(), reassigned.doctorId());
        assertFalse(reassigned.doctorUnavailable());
        assertFalse(reassigned.isNeedsReassignment());

        ReceptionService.VisitResult checkedIn = receptionService.checkInAppointment(
                sessionFor(receptionistOnly), healthyAppt.getAppointmentId(),
                "Đau răng", UUID.randomUUID().toString());
        assertEquals(VisitStatus.WAITING, checkedIn.status());
        assertEquals(doctorB.getStaffId(), visitRepository.findById(checkedIn.id()).orElseThrow().getPrimaryDoctor().getStaffId());
    }

    @Test
    @DisplayName("C3: Phân loại hàng đợi EARLY_WAITING (sớm >15p, tách khỏi hàng gọi lượt trước giờ hẹn), ON_TIME, LATE_BOOKING, NO_SHOW")
    void queuePriorityClassifiesEarlyOnTimeLateAndNoShowAccurately() {
        LocalTime slotTime = LocalTime.of(10, 0);

        // Kiểm chứng EARLY_WAITING tách khỏi hàng gọi lượt ngay cả khi bàn bác sĩ đang rảnh
        Clock clock0910 = Clock.fixed(LocalDateTime.of(today, LocalTime.of(9, 10)).atZone(CLINIC_ZONE).toInstant(), CLINIC_ZONE);
        receptionService.setClock(clock0910);
        clinicQueueCoordinatorService.setClock(clock0910);
        Appointment isolatedEarly = saveAppointment("LHE0_" + uid, patient1, doctorA, today, PreferredSession.MORNING, slotTime, AppointmentStatus.CONFIRMED);
        var isolatedEarlyVisit = receptionService.checkInAppointment(sessionFor(dispatcherUser), isolatedEarly.getAppointmentId(), "Đến rất sớm chờ giờ", UUID.randomUUID().toString());
        QueueTicket earlyDocATicket = queueTicketRepository.findForBoard(branch.getBranchId(), today, QueueType.EXAM).stream()
                .filter(t -> t.getVisit().getVisitId().equals(isolatedEarlyVisit.id()))
                .findFirst().orElseThrow();
        assertEquals(QueueStatus.WAITING, earlyDocATicket.getStatus(), "EARLY_WAITING không được tự động gọi lên CALLED khi chưa đến cửa sổ -15p");
        assertNull(earlyDocATicket.getCalledAt());
        var docADeskEarly = clinicQueueCoordinatorService.getDoctorDeskStatus(doctorA.getUser(), branch.getBranchId());
        assertNull(docADeskEarly.calledVisit());
        assertEquals(0, docADeskEarly.waitingCount());

        // Khi đồng hồ đạt 09:45 (expectedTime - 15p), job nâng hạng và tự gọi khi bàn rảnh
        Clock clock0945 = Clock.fixed(LocalDateTime.of(today, LocalTime.of(9, 45)).atZone(CLINIC_ZONE).toInstant(), CLINIC_ZONE);
        clinicQueueCoordinatorService.setClock(clock0945);
        int promoted = clinicQueueCoordinatorService.promoteEligibleEarlyWaitingTickets(LocalDateTime.of(today, LocalTime.of(9, 45)));
        assertEquals(1, promoted);
        QueueTicket promotedTicket = queueTicketRepository.findById(earlyDocATicket.getTicketId()).orElseThrow();
        assertEquals(QueueStatus.CALLED, promotedTicket.getStatus());
        assertEquals(75, promotedTicket.getPriorityScore());
        assertTrue(promotedTicket.getPriorityReason().startsWith("ON_TIME:"));

        // Khôi phục đồng hồ 09:10 trên bàn BS B để kiểm chứng 4 tầng (đến trễ/sớm/đúng giờ/vãng lai/cấp cứu)
        Appointment earlyAppt = saveAppointment("LHE_" + uid, patient2, doctorB, today, PreferredSession.MORNING, slotTime, AppointmentStatus.CONFIRMED);
        Appointment onTimeAppt = saveAppointment("LHO_" + uid, patient3, doctorB, today, PreferredSession.MORNING, slotTime, AppointmentStatus.CONFIRMED);
        Appointment lateAppt = saveAppointment("LHL_" + uid, patient4, doctorB, today, PreferredSession.MORNING, slotTime, AppointmentStatus.CONFIRMED);
        Appointment noShowAppt = saveAppointment("LHN_" + uid, patient5, doctorB, today, PreferredSession.MORNING, slotTime, AppointmentStatus.CONFIRMED);

        // 1. Check-in trễ +20p (LATE_BOOKING -> Tier 4, score 25)
        Clock clock1020 = Clock.fixed(LocalDateTime.of(today, LocalTime.of(10, 20)).atZone(CLINIC_ZONE).toInstant(), CLINIC_ZONE);
        receptionService.setClock(clock1020);
        clinicQueueCoordinatorService.setClock(clock1020);
        var lateVisit = receptionService.checkInAppointment(sessionFor(dispatcherUser), lateAppt.getAppointmentId(), "Đến trễ 20p", UUID.randomUUID().toString());

        // 2. Check-in sớm -50p (EARLY_WAITING -> Tier 3, score 50, KHÔNG bị gắn LATE_BOOKING)
        receptionService.setClock(clock0910);
        clinicQueueCoordinatorService.setClock(clock0910);
        var earlyVisit = receptionService.checkInAppointment(sessionFor(dispatcherUser), earlyAppt.getAppointmentId(), "Đến sớm chờ", UUID.randomUUID().toString());

        // 3. Check-in đúng giờ -5p (ON_TIME -> Tier 2, score 75, P-XX)
        Clock clock0955 = Clock.fixed(LocalDateTime.of(today, LocalTime.of(9, 55)).atZone(CLINIC_ZONE).toInstant(), CLINIC_ZONE);
        receptionService.setClock(clock0955);
        clinicQueueCoordinatorService.setClock(clock0955);
        var onTimeVisit = receptionService.checkInAppointment(sessionFor(dispatcherUser), onTimeAppt.getAppointmentId(), "Đúng giờ", UUID.randomUUID().toString());

        // 4. Vãng lai (WALK_IN -> Tier 3, score 50) & Cấp cứu (EMERGENCY -> Tier 1, score 100)
        var walkInVisit = receptionService.checkInWalkIn(sessionFor(dispatcherUser), branch.getBranchId(), patient5.getProfileId(),
                "Khám vãng lai răng sâu", false, false, UUID.randomUUID().toString());
        var emergencyVisit = receptionService.checkInWalkIn(sessionFor(dispatcherUser), branch.getBranchId(), patient6.getProfileId(),
                "Chảy máu chân răng cấp cứu", true, true, UUID.randomUUID().toString());

        // 5. Quá +30p -> chặn check-in 409 & cho phép đánh dấu NO_SHOW
        Clock clock1035 = Clock.fixed(LocalDateTime.of(today, LocalTime.of(10, 35)).atZone(CLINIC_ZONE).toInstant(), CLINIC_ZONE);
        receptionService.setClock(clock1035);
        clinicQueueCoordinatorService.setClock(clock1035);
        ResponseStatusException expiredEx = assertThrows(ResponseStatusException.class, () ->
                receptionService.checkInAppointment(sessionFor(dispatcherUser), noShowAppt.getAppointmentId(), "Quá 35p", UUID.randomUUID().toString()));
        assertEquals(409, expiredEx.getStatusCode().value());
        receptionAppointmentService.markNoShow(noShowAppt.getAppointmentId(), LocalDateTime.of(today, LocalTime.of(10, 35)));
        assertEquals(AppointmentStatus.NO_SHOW, appointmentRepository.findById(noShowAppt.getAppointmentId()).orElseThrow().getStatus());

        List<QueueTicket> boardTickets = queueTicketRepository.findForBoard(branch.getBranchId(), today, QueueType.EXAM);
        QueueTicket tEarly = boardTickets.stream().filter(t -> t.getVisit().getVisitId().equals(earlyVisit.id())).findFirst().orElseThrow();
        QueueTicket tOnTime = boardTickets.stream().filter(t -> t.getVisit().getVisitId().equals(onTimeVisit.id())).findFirst().orElseThrow();
        QueueTicket tLate = boardTickets.stream().filter(t -> t.getVisit().getVisitId().equals(lateVisit.id())).findFirst().orElseThrow();
        QueueTicket tWalkIn = boardTickets.stream().filter(t -> t.getVisit().getVisitId().equals(walkInVisit.id())).findFirst().orElseThrow();
        QueueTicket tEmergency = boardTickets.stream().filter(t -> t.getVisit().getVisitId().equals(emergencyVisit.id())).findFirst().orElseThrow();

        assertEquals(50, tEarly.getPriorityScore());
        assertFalse(Boolean.TRUE.equals(tEarly.getIsPriority()));
        assertTrue(tEarly.getPriorityReason().startsWith("EARLY_WAITING:"));
        assertEquals(3, clinicQueueCoordinatorService.queueTierRank(tEarly));

        assertEquals(75, tOnTime.getPriorityScore());
        assertTrue(Boolean.TRUE.equals(tOnTime.getIsPriority()));
        assertTrue(tOnTime.getDisplayNumber().startsWith("P-"));
        assertEquals(2, clinicQueueCoordinatorService.queueTierRank(tOnTime));

        assertEquals(25, tLate.getPriorityScore());
        assertTrue(tLate.getPriorityReason().startsWith("LATE_BOOKING:"));
        assertEquals(4, clinicQueueCoordinatorService.queueTierRank(tLate));

        assertEquals(1, clinicQueueCoordinatorService.queueTierRank(tEmergency));
        assertEquals(3, clinicQueueCoordinatorService.queueTierRank(tWalkIn));

        List<QueueTicket> sorted = new ArrayList<>(List.of(tLate, tWalkIn, tEarly, tOnTime, tEmergency));
        sorted.sort(clinicQueueCoordinatorService.waitingQueueComparator());
        assertEquals(1, clinicQueueCoordinatorService.queueTierRank(sorted.get(0)));
        assertEquals(tOnTime.getTicketId(), sorted.get(1).getTicketId());
        assertEquals(tLate.getTicketId(), sorted.get(4).getTicketId());
    }

    @Test
    @DisplayName("C4: Không cho xóa StaffSchedule khi đang có lịch hẹn ACTIVE phụ thuộc vào ca trực")
    void staffScheduleRemoveBlockedWhileActiveAppointmentDependsOnShift() {
        StaffSchedule futureDocAShift = staffScheduleRepository.findDoctorSchedulesForDate(doctorA.getStaffId(), futureDate, ScheduleStatus.SCHEDULED)
                .stream()
                .filter(s -> s.getShift().getShiftId().equals(morningShift.getShiftId()))
                .findFirst().orElseThrow();

        Appointment booked = saveAppointment("LHS_" + uid, patient1, doctorA, futureDate, PreferredSession.MORNING,
                LocalTime.of(9, 0), AppointmentStatus.CONFIRMED);

        IllegalStateException blockedEx = assertThrows(IllegalStateException.class, () ->
                staffScheduleService.remove(futureDocAShift.getScheduleId()));
        assertTrue(blockedEx.getMessage().contains(booked.getAppointmentCode()));

        // Sau khi dời sang BS B -> được phép xóa ca của BS A
        receptionAppointmentService.update(sessionFor(dispatcherUser), booked.getAppointmentId(),
                new ReceptionAppointmentService.UpdateRequest(
                        futureDate, PreferredSession.MORNING, doctorB.getStaffId(), null,
                        "Đổi sang BS Bình", "Chuyển bác sĩ trước khi xóa ca", LocalTime.of(9, 0)));

        assertFalse(staffScheduleService.remove(futureDocAShift.getScheduleId()));
        assertTrue(staffScheduleRepository.findById(futureDocAShift.getScheduleId()).isEmpty());
    }

    @Test
    @DisplayName("C5: Race Check-in vs Duyệt nghỉ phép & Race hai lễ tân nhận cùng yêu cầu tư vấn Guest")
    void concurrentCheckInVsLeaveApprovalAndConcurrentConsultationClaim() throws Exception {
        receptionService.setClock(Clock.fixed(LocalDateTime.of(today, LocalTime.of(9, 0)).atZone(CLINIC_ZONE).toInstant(), CLINIC_ZONE));

        Appointment raceAppt = saveAppointment("LHR_" + uid, patient1, doctorA, today, PreferredSession.MORNING,
                LocalTime.of(9, 15), AppointmentStatus.CONFIRMED);

        LeaveRequest leave = new LeaveRequest();
        leave.setStaff(doctorA);
        leave.setBranch(branch);
        leave.setShift(morningShift);
        leave.setStartDate(today);
        leave.setEndDate(today);
        leave.setLeaveType(LeaveType.ANNUAL);
        leave.setReason("Nghỉ cùng lúc check-in");
        leave.setStatus(LeaveStatus.PENDING);
        leave = leaveRequestRepository.saveAndFlush(leave);
        final Long leaveId = leave.getLeaveId();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CyclicBarrier b1 = new CyclicBarrier(2);
            Future<String> fLeave = pool.submit(() -> {
                b1.await(5, TimeUnit.SECONDS);
                leaveRequestService.approveLeaveRequest(leaveId, adminReviewer.getUserId(), "Duyệt race");
                return "LEAVE_APPROVED";
            });
            Future<String> fCheckIn = pool.submit(() -> {
                b1.await(5, TimeUnit.SECONDS);
                try {
                    receptionService.checkInAppointment(sessionFor(dispatcherUser), raceAppt.getAppointmentId(),
                            "Race check-in", UUID.randomUUID().toString());
                    return "CHECKED_IN";
                } catch (ResponseStatusException ex) {
                    return "BLOCKED_" + ex.getStatusCode().value();
                }
            });

            assertEquals("LEAVE_APPROVED", fLeave.get(15, TimeUnit.SECONDS));
            String checkOutcome = fCheckIn.get(15, TimeUnit.SECONDS);
            assertTrue("CHECKED_IN".equals(checkOutcome) || checkOutcome.startsWith("BLOCKED_"),
                    "Unexpected check-in race outcome: " + checkOutcome);

            // Race claim tư vấn Guest
            BookingConsultationRequest req = new BookingConsultationRequest();
            req.setRequestCode("BCR_" + uid);
            req.setRequestKey(UUID.randomUUID().toString());
            req.setBranch(branch);
            req.setGuestName("Khách Tư Vấn");
            req.setGuestPhone("09" + SEQ.incrementAndGet());
            req.setPreferredDate(futureDate);
            req.setPreferredSession(PreferredSession.MORNING);
            req.setStatus(ConsultationRequestStatus.NEW);
            req = consultationRequestRepository.saveAndFlush(req);
            final Long reqId = req.getRequestId();

            CyclicBarrier b2 = new CyclicBarrier(2);
            AtomicInteger claimOk = new AtomicInteger();
            AtomicInteger claimConflict = new AtomicInteger();
            Callable<Void> claimTask1 = () -> {
                b2.await(5, TimeUnit.SECONDS);
                try {
                    receptionConsultationService.claim(sessionFor(receptionistOnly), reqId,
                            new ReceptionConsultationService.ClaimRequest("Nhận A"), null);
                    claimOk.incrementAndGet();
                } catch (ResponseStatusException ex) {
                    assertEquals(409, ex.getStatusCode().value());
                    claimConflict.incrementAndGet();
                }
                return null;
            };
            Callable<Void> claimTask2 = () -> {
                b2.await(5, TimeUnit.SECONDS);
                try {
                    receptionConsultationService.claim(sessionFor(dispatcherUser), reqId,
                            new ReceptionConsultationService.ClaimRequest("Nhận B"), null);
                    claimOk.incrementAndGet();
                } catch (ResponseStatusException ex) {
                    assertEquals(409, ex.getStatusCode().value());
                    claimConflict.incrementAndGet();
                }
                return null;
            };
            Future<Void> c1 = pool.submit(claimTask1);
            Future<Void> c2 = pool.submit(claimTask2);
            c1.get(15, TimeUnit.SECONDS);
            c2.get(15, TimeUnit.SECONDS);
            assertEquals(1, claimOk.get());
            assertEquals(1, claimConflict.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private void cleanAll() {
        jdbcTemplate.execute("DELETE FROM portal_booking_requests");
        consultationLogRepository.deleteAll();
        consultationRequestRepository.deleteAll();
        jdbcTemplate.execute("DELETE FROM clinical_examinations");
        queueTicketRepository.deleteAll();
        checkInKeyRepository.deleteAll();
        visitRepository.deleteAll();
        careTaskRepository.deleteAll();
        appointmentHistoryRepository.deleteAll();
        appointmentRepository.deleteAll();
        leaveRequestRepository.deleteAll();
        staffScheduleRepository.deleteAll();
        notificationRepository.deleteAll();
        auditLogRepository.deleteAll();
    }

    private Permission ensurePermission(String code, String desc, String module) {
        return permissionRepository.findByPermissionCode(code).orElseGet(() -> {
            Permission p = new Permission();
            p.setPermissionCode(code);
            p.setDescription(desc);
            p.setModule(module);
            return permissionRepository.saveAndFlush(p);
        });
    }

    private Role ensureRole(String roleName, String desc, Set<Permission> perms) {
        return roleRepository.findByRoleName(roleName).orElseGet(() -> {
            Role r = new Role();
            r.setRoleName(roleName);
            r.setDescription(desc);
            r.setIsActive(true);
            r.setPermissions(new HashSet<>(perms));
            return roleRepository.saveAndFlush(r);
        });
    }

    private WorkShift createShift(String code, String name, LocalTime start, LocalTime end) {
        WorkShift s = new WorkShift();
        s.setBranch(branch);
        s.setShiftCode(code);
        s.setShiftName(name);
        s.setStartTime(start);
        s.setEndTime(end);
        s.setIsActive(true);
        return workShiftRepository.saveAndFlush(s);
    }

    private StaffProfile createDoctor(String code, String name, Role doctorRole) {
        long n = SEQ.incrementAndGet();
        User u = new User();
        u.setUsername("u_" + code.toLowerCase(Locale.ROOT));
        u.setPassword("pw");
        u.setEmail(code.toLowerCase(Locale.ROOT) + "@clinic.test");
        u.setPhoneNumber("090" + n);
        u.setUserType(UserType.STAFF);
        u.setBranch(branch);
        u.setIsActive(true);
        u.setStatus("ACTIVE");
        u.setRoles(new HashSet<>(Set.of(doctorRole)));
        u = userRepository.saveAndFlush(u);

        StaffProfile sp = new StaffProfile();
        sp.setUser(u);
        sp.setStaffCode(code);
        sp.setFullName(name);
        sp.setStatus(StaffStatus.ACTIVE);
        sp.setBaseSalary(new BigDecimal("18000000"));
        sp = staffProfileRepository.saveAndFlush(sp);

        StaffBranchAssignment sba = new StaffBranchAssignment();
        sba.setStaff(sp);
        sba.setBranch(branch);
        sba.setRole(doctorRole);
        sba.setIsPrimary(true);
        sba.setStartDate(today.minusDays(10));
        sba.setStatus(AssignmentStatus.ACTIVE);
        branchAssignmentRepository.saveAndFlush(sba);
        return sp;
    }

    private void createSchedule(StaffProfile staff, WorkShift shift, LocalDate date) {
        StaffSchedule sch = new StaffSchedule();
        sch.setStaff(staff);
        sch.setBranch(branch);
        sch.setShift(shift);
        sch.setWorkDate(date);
        sch.setStatus(ScheduleStatus.SCHEDULED);
        staffScheduleRepository.saveAndFlush(sch);
    }

    private User createStaffUser(String username, Role role, String name) {
        long n = SEQ.incrementAndGet();
        User u = new User();
        u.setUsername(username);
        u.setPassword("pw");
        u.setEmail(username + "@clinic.test");
        u.setPhoneNumber("091" + n);
        u.setUserType(UserType.STAFF);
        u.setBranch(branch);
        u.setIsActive(true);
        u.setStatus("ACTIVE");
        u.setRoles(new HashSet<>(Set.of(role)));
        u = userRepository.saveAndFlush(u);

        StaffProfile sp = new StaffProfile();
        sp.setUser(u);
        sp.setStaffCode("NV_" + n);
        sp.setFullName(name);
        sp.setStatus(StaffStatus.ACTIVE);
        sp.setBaseSalary(BigDecimal.ZERO);
        sp = staffProfileRepository.saveAndFlush(sp);

        StaffBranchAssignment sba = new StaffBranchAssignment();
        sba.setStaff(sp);
        sba.setBranch(branch);
        sba.setRole(role);
        sba.setIsPrimary(true);
        sba.setStartDate(today.minusDays(10));
        sba.setStatus(AssignmentStatus.ACTIVE);
        branchAssignmentRepository.saveAndFlush(sba);
        return u;
    }

    private User createPatientUser(String username, Role patientRole) {
        long n = SEQ.incrementAndGet();
        User u = new User();
        u.setUsername(username);
        u.setPassword("pw");
        u.setEmail(username + "@clinic.test");
        u.setPhoneNumber("092" + n);
        u.setUserType(UserType.PATIENT);
        u.setBranch(branch);
        u.setIsActive(true);
        u.setStatus("ACTIVE");
        u.setRoles(new HashSet<>(Set.of(patientRole)));
        return userRepository.saveAndFlush(u);
    }

    private PatientProfile createPatient(String code, String name, User user) {
        long n = SEQ.incrementAndGet();
        PatientProfile p = new PatientProfile();
        p.setUser(user);
        p.setPatientCode(code);
        p.setFullName(name);
        p.setPhoneNumber("08" + n);
        p.setGender("Nam");
        p.setAddress("Đà Nẵng");
        p.setPreferredBranch(branch);
        p.setStatus("ACTIVE");
        return patientProfileRepository.saveAndFlush(p);
    }

    private Appointment saveAppointment(String code, PatientProfile patient, StaffProfile doctor,
                                        LocalDate date, PreferredSession session, LocalTime expectedTime,
                                        AppointmentStatus status) {
        Appointment a = new Appointment();
        a.setAppointmentCode(code);
        a.setBranch(branch);
        a.setPatientProfile(patient);
        a.setDoctor(doctor);
        a.setAppointmentDate(date);
        a.setPreferredSession(session);
        a.setExpectedTime(expectedTime);
        a.setDurationMinutes(30);
        a.syncExpectedEndTime();
        a.setInitialSymptoms("Khám nha khoa Part C");
        a.setSource(AppointmentSource.RECEPTIONIST);
        a.setStatus(status);
        return appointmentRepository.saveAndFlush(a);
    }

    private HttpSession sessionFor(User user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", user);
        session.setAttribute("currentBranchId", branch.getBranchId());
        return session;
    }
}
