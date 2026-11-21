package com.cvdcms.receptionappointment;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.reception.ReceptionCheckInKeyRepository;
import com.cvdcms.reception.ReceptionConsultationService;
import com.cvdcms.reception.ReceptionService;
import com.cvdcms.reception.ReceptionVisitRepository;
import com.cvdcms.repository.*;
import com.cvdcms.service.AppointmentPolicyService;
import com.cvdcms.service.LeaveRequestService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Chạy trực tiếp trên MySQL 8 InnoDB (schema cô lập cv_dcms_verify_tmp, ddl-auto=create-drop)
 * để xác minh Stage A, B1 lock ordering, và Giai đoạn C không dính deadlock InnoDB (1213).
 * Không đụng vào database ứng dụng cv_dcms_db.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/cv_dcms_verify_tmp?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Ho_Chi_Minh&zeroDateTimeBehavior=convertToNull&useUnicode=true&characterEncoding=UTF-8",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfSystemProperty(named = "appointment.mysql.integration", matches = "true")
class AppointmentMySqlB1AndPartCIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ReceptionAppointmentService receptionAppointmentService;
    @Autowired private ReceptionService receptionService;
    @Autowired private LeaveRequestService leaveRequestService;
    @Autowired private ReceptionConsultationService receptionConsultationService;

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
    private static final AtomicLong SEQ = new AtomicLong(9000);

    private String uid;
    private LocalDate today;
    private LocalDate futureDate;
    private Branch branchA;
    private Branch branchB;
    private WorkShift morningA;
    private WorkShift morningB;
    private StaffProfile docLow;
    private StaffProfile docHigh;
    private User receptionUser;
    private User dispatcherUser;
    private User adminUser;
    private PatientProfile patient1;
    private PatientProfile patient2;

    @BeforeEach
    void setUp() {
        receptionService.setClock(Clock.system(CLINIC_ZONE));
        try {
            jdbcTemplate.execute("ALTER TABLE patient_profiles MODIFY COLUMN user_id BIGINT NULL");
        } catch (Exception ignored) {
        }
        cleanAll();

        uid = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        today = LocalDate.now(CLINIC_ZONE);
        futureDate = today.plusDays(4);

        Permission pView = ensurePermission("APPOINTMENT_VIEW", "Xem lịch hẹn", "RECEPTION");
        Permission pManage = ensurePermission("APPOINTMENT_MANAGE", "Quản lý lịch hẹn", "RECEPTION");
        Permission pCheckIn = ensurePermission("RECEPTION_CHECKIN", "Tiếp đón", "RECEPTION");
        Permission pDispatch = ensurePermission("CLINICAL_DISPATCH", "Điều phối", "RECEPTION");
        Permission pRecord = ensurePermission("MEDICAL_RECORD_VIEW", "Xem hồ sơ", "CLINICAL");

        Role recRole = ensureRole("LE_TAN", "Lễ tân", Set.of(pView, pManage, pCheckIn));
        Role dispRole = ensureRole("DISP_M_" + uid, "Điều phối", Set.of(pView, pManage, pCheckIn, pDispatch));
        Role adminRole = ensureRole("ADMIN", "Admin", Set.of(pView, pManage, pCheckIn, pDispatch));
        Role docRole = ensureRole("BAC_SI", "Bác sĩ", Set.of(pRecord));

        branchA = createBranch("MA_" + uid, "MySQL Branch A");
        branchB = createBranch("MB_" + uid, "MySQL Branch B");
        morningA = createShift(branchA, "SA_" + uid, LocalTime.of(7, 30), LocalTime.of(12, 0));
        morningB = createShift(branchB, "SB_" + uid, LocalTime.of(7, 30), LocalTime.of(12, 0));

        docLow = createDoctor("MD1_" + uid, "BS. MySQL 1", docRole, branchA);
        docHigh = createDoctor("MD2_" + uid, "BS. MySQL 2", docRole, branchA);
        assertTrue(docLow.getStaffId() < docHigh.getStaffId());
        addAssignment(docLow, branchB, docRole);

        for (LocalDate d : List.of(today, futureDate)) {
            createSchedule(docLow, branchA, morningA, d);
            createSchedule(docHigh, branchA, morningA, d);
            createSchedule(docLow, branchB, morningB, d);
        }

        receptionUser = createStaffUser("mrec_" + uid, branchA, recRole, "Lễ tân MySQL");
        dispatcherUser = createStaffUser("mdisp_" + uid, branchA, dispRole, "Điều phối MySQL");
        adminUser = createStaffUser("madm_" + uid, branchA, adminRole, "Admin MySQL");

        patient1 = createPatient("MP1_" + uid, "Bệnh Nhân MySQL 1");
        patient2 = createPatient("MP2_" + uid, "Bệnh Nhân MySQL 2");
    }

    @AfterEach
    void tearDown() {
        receptionService.setClock(Clock.system(CLINIC_ZONE));
    }

    @Test
    @DisplayName("MySQL 8: Xác minh engine InnoDB & READ_COMMITTED + khóa Stage A/B1/C đồng thời không deadlock")
    void realMySql8ConcurrencyAndWorkflowVerification() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            String product = conn.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            assertTrue(product.contains("mysql"), "Expected MySQL 8, got " + product);
        }
        String engine = jdbcTemplate.queryForObject(
                "SELECT ENGINE FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'appointments'",
                String.class);
        assertNotNull(engine);
        assertEquals("InnoDB", engine);

        // 1. Stage A: 2 luồng tạo hồ sơ mới đồng thời trên InnoDB -> Branch lock trước INSERT -> 0 deadlock
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier stageABarrier = new CyclicBarrier(2);
            Future<ReceptionAppointmentService.AppointmentView> np1 = pool.submit(() -> {
                stageABarrier.await(5, TimeUnit.SECONDS);
                return receptionAppointmentService.create(sessionFor(dispatcherUser, branchA),
                        new ReceptionAppointmentService.CreateRequest(
                                branchA.getBranchId(), null, futureDate, PreferredSession.MORNING,
                                docLow.getStaffId(), null, "Tạo mới MySQL 1", LocalTime.of(8, 0),
                                "Nguyễn Mới Một", phone10(), "Đà Nẵng", "WALK_IN", false, null,
                                LocalDate.of(1995, 1, 1), "Nam", null, null, null, null, true));
            });
            Future<ReceptionAppointmentService.AppointmentView> np2 = pool.submit(() -> {
                stageABarrier.await(5, TimeUnit.SECONDS);
                return receptionAppointmentService.create(sessionFor(dispatcherUser, branchA),
                        new ReceptionAppointmentService.CreateRequest(
                                branchA.getBranchId(), null, futureDate, PreferredSession.MORNING,
                                docHigh.getStaffId(), null, "Tạo mới MySQL 2", LocalTime.of(8, 0),
                                "Trần Mới Hai", phone10(), "Đà Nẵng", "WALK_IN", false, null,
                                LocalDate.of(1994, 5, 1), "Nữ", null, null, null, null, true));
            });
            assertNotNull(np1.get(15, TimeUnit.SECONDS).id());
            assertNotNull(np2.get(15, TimeUnit.SECONDS).id());

            // 2. B1: Swap ngược chiều docLow<->docHigh trên InnoDB -> 0 deadlock
            Appointment a1 = saveAppointment("MS1_" + uid, patient1, branchA, docLow, futureDate, LocalTime.of(9, 0), AppointmentStatus.CONFIRMED);
            Appointment a2 = saveAppointment("MS2_" + uid, patient2, branchA, docHigh, futureDate, LocalTime.of(9, 30), AppointmentStatus.CONFIRMED);

            CyclicBarrier swapBarrier = new CyclicBarrier(2);
            Future<ReceptionAppointmentService.AppointmentView> fSwap1 = pool.submit(() -> {
                swapBarrier.await(5, TimeUnit.SECONDS);
                return receptionAppointmentService.update(sessionFor(dispatcherUser, branchA), a1.getAppointmentId(),
                        new ReceptionAppointmentService.UpdateRequest(futureDate, PreferredSession.MORNING, docHigh.getStaffId(),
                                null, "Đổi chéo 1", "MySQL swap", LocalTime.of(10, 0)));
            });
            Future<ReceptionAppointmentService.AppointmentView> fSwap2 = pool.submit(() -> {
                swapBarrier.await(5, TimeUnit.SECONDS);
                return receptionAppointmentService.update(sessionFor(dispatcherUser, branchA), a2.getAppointmentId(),
                        new ReceptionAppointmentService.UpdateRequest(futureDate, PreferredSession.MORNING, docLow.getStaffId(),
                                null, "Đổi chéo 2", "MySQL swap", LocalTime.of(10, 0)));
            });
            assertEquals(docHigh.getStaffId(), fSwap1.get(15, TimeUnit.SECONDS).doctorId());
            assertEquals(docLow.getStaffId(), fSwap2.get(15, TimeUnit.SECONDS).doctorId());

            // 3. Part C: Duyệt nghỉ phép giữ NEEDS_REASSIGNMENT, chặn check-in 409, lễ tân đổi bác sĩ -> check-in OK
            receptionService.setClock(Clock.fixed(LocalDateTime.of(today, LocalTime.of(8, 30)).atZone(CLINIC_ZONE).toInstant(), CLINIC_ZONE));
            Appointment todayAppt = saveAppointment("MT_" + uid, patient1, branchA, docLow, today, LocalTime.of(9, 0), AppointmentStatus.CONFIRMED);

            LeaveRequest leave = new LeaveRequest();
            leave.setStaff(docLow);
            leave.setBranch(branchA);
            leave.setShift(morningA);
            leave.setStartDate(today);
            leave.setEndDate(today);
            leave.setLeaveType(LeaveType.ANNUAL);
            leave.setReason("MySQL leave verification");
            leave.setStatus(LeaveStatus.PENDING);
            leave = leaveRequestRepository.saveAndFlush(leave);
            leaveRequestService.approveLeaveRequest(leave.getLeaveId(), adminUser.getUserId(), "OK");

            assertEquals(AppointmentStatus.CONFIRMED, appointmentRepository.findById(todayAppt.getAppointmentId()).orElseThrow().getStatus());
            ResponseStatusException blocked = assertThrows(ResponseStatusException.class, () ->
                    receptionService.checkInAppointment(sessionFor(receptionUser, branchA), todayAppt.getAppointmentId(),
                            "Check-in bác sĩ nghỉ", UUID.randomUUID().toString()));
            assertEquals(409, blocked.getStatusCode().value());

            receptionAppointmentService.update(sessionFor(receptionUser, branchA), todayAppt.getAppointmentId(),
                    new ReceptionAppointmentService.UpdateRequest(today, PreferredSession.MORNING, docHigh.getStaffId(),
                            null, "Khám răng", "Đổi sang BS 2 trên MySQL", LocalTime.of(9, 0)));
            ReceptionService.VisitResult v = receptionService.checkInAppointment(
                    sessionFor(receptionUser, branchA), todayAppt.getAppointmentId(),
                    "Khám răng", UUID.randomUUID().toString());
            assertEquals(VisitStatus.WAITING, v.status());

            // 4. Part C: Race claim BookingConsultationRequest trên MySQL InnoDB -> 1 thắng, 1 nhận 409
            BookingConsultationRequest bcr = new BookingConsultationRequest();
            bcr.setRequestCode("MBCR_" + uid);
            bcr.setRequestKey(UUID.randomUUID().toString());
            bcr.setBranch(branchA);
            bcr.setGuestName("Guest MySQL");
            bcr.setGuestPhone(phone10());
            bcr.setPreferredDate(futureDate);
            bcr.setPreferredSession(PreferredSession.MORNING);
            bcr.setStatus(ConsultationRequestStatus.NEW);
            bcr = consultationRequestRepository.saveAndFlush(bcr);
            final Long bcrId = bcr.getRequestId();

            CyclicBarrier claimBarrier = new CyclicBarrier(2);
            AtomicInteger claimOk = new AtomicInteger();
            AtomicInteger claimConflict = new AtomicInteger();
            Future<Void> c1 = pool.submit(() -> {
                claimBarrier.await(5, TimeUnit.SECONDS);
                try {
                    receptionConsultationService.claim(sessionFor(receptionUser, branchA), bcrId,
                            new ReceptionConsultationService.ClaimRequest("Claim A"), null);
                    claimOk.incrementAndGet();
                } catch (ResponseStatusException ex) {
                    assertEquals(409, ex.getStatusCode().value());
                    claimConflict.incrementAndGet();
                }
                return null;
            });
            Future<Void> c2 = pool.submit(() -> {
                claimBarrier.await(5, TimeUnit.SECONDS);
                try {
                    receptionConsultationService.claim(sessionFor(dispatcherUser, branchA), bcrId,
                            new ReceptionConsultationService.ClaimRequest("Claim B"), null);
                    claimOk.incrementAndGet();
                } catch (ResponseStatusException ex) {
                    assertEquals(409, ex.getStatusCode().value());
                    claimConflict.incrementAndGet();
                }
                return null;
            });
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

    private String phone10() {
        return "09" + String.format("%08d", SEQ.incrementAndGet());
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

    private Role ensureRole(String name, String desc, Set<Permission> perms) {
        return roleRepository.findByRoleName(name).orElseGet(() -> {
            Role r = new Role();
            r.setRoleName(name);
            r.setDescription(desc);
            r.setIsActive(true);
            r.setPermissions(new HashSet<>(perms));
            return roleRepository.saveAndFlush(r);
        });
    }

    private Branch createBranch(String code, String name) {
        Branch b = new Branch();
        b.setBranchCode(code);
        b.setBranchName(name);
        b.setAddress("MySQL Verify");
        b.setPhone("028" + String.format("%07d", SEQ.incrementAndGet()));
        b.setOpeningTime(LocalTime.of(0, 0));
        b.setClosingTime(LocalTime.of(23, 59));
        b.setWorkingDays("2,3,4,5,6,7,CN");
        b.setIsActive(true);
        return branchRepository.saveAndFlush(b);
    }

    private WorkShift createShift(Branch b, String code, LocalTime start, LocalTime end) {
        WorkShift s = new WorkShift();
        s.setBranch(b);
        s.setShiftCode(code);
        s.setShiftName(code);
        s.setStartTime(start);
        s.setEndTime(end);
        s.setIsActive(true);
        return workShiftRepository.saveAndFlush(s);
    }

    private StaffProfile createDoctor(String code, String name, Role docRole, Branch primary) {
        User u = new User();
        u.setUsername("mu_" + code.toLowerCase(Locale.ROOT));
        u.setPassword("pw");
        u.setEmail(code.toLowerCase(Locale.ROOT) + "@mysql.test");
        u.setPhoneNumber(phone10());
        u.setUserType(UserType.STAFF);
        u.setBranch(primary);
        u.setIsActive(true);
        u.setStatus("ACTIVE");
        u.setRoles(new HashSet<>(Set.of(docRole)));
        u = userRepository.saveAndFlush(u);

        StaffProfile sp = new StaffProfile();
        sp.setUser(u);
        sp.setStaffCode(code);
        sp.setFullName(name);
        sp.setStatus(StaffStatus.ACTIVE);
        sp.setBaseSalary(new BigDecimal("20000000"));
        sp = staffProfileRepository.saveAndFlush(sp);
        addAssignment(sp, primary, docRole);
        return sp;
    }

    private void addAssignment(StaffProfile sp, Branch b, Role r) {
        StaffBranchAssignment sba = new StaffBranchAssignment();
        sba.setStaff(sp);
        sba.setBranch(b);
        sba.setRole(r);
        sba.setIsPrimary(true);
        sba.setStartDate(today.minusDays(10));
        sba.setStatus(AssignmentStatus.ACTIVE);
        branchAssignmentRepository.saveAndFlush(sba);
    }

    private void createSchedule(StaffProfile sp, Branch b, WorkShift s, LocalDate d) {
        StaffSchedule sch = new StaffSchedule();
        sch.setStaff(sp);
        sch.setBranch(b);
        sch.setShift(s);
        sch.setWorkDate(d);
        sch.setStatus(ScheduleStatus.SCHEDULED);
        staffScheduleRepository.saveAndFlush(sch);
    }

    private User createStaffUser(String username, Branch b, Role role, String name) {
        User u = new User();
        u.setUsername(username);
        u.setPassword("pw");
        u.setEmail(username + "@mysql.test");
        u.setPhoneNumber(phone10());
        u.setUserType(UserType.STAFF);
        u.setBranch(b);
        u.setIsActive(true);
        u.setStatus("ACTIVE");
        u.setRoles(new HashSet<>(Set.of(role)));
        u = userRepository.saveAndFlush(u);

        StaffProfile sp = new StaffProfile();
        sp.setUser(u);
        sp.setStaffCode("MN_" + SEQ.incrementAndGet());
        sp.setFullName(name);
        sp.setStatus(StaffStatus.ACTIVE);
        sp.setBaseSalary(BigDecimal.ZERO);
        sp = staffProfileRepository.saveAndFlush(sp);
        addAssignment(sp, b, role);
        return u;
    }

    private PatientProfile createPatient(String code, String name) {
        PatientProfile p = new PatientProfile();
        p.setPatientCode(code);
        p.setFullName(name);
        p.setPhoneNumber(phone10());
        p.setGender("Nam");
        p.setAddress("MySQL Test");
        p.setPreferredBranch(branchA);
        p.setStatus("ACTIVE");
        return patientProfileRepository.saveAndFlush(p);
    }

    private Appointment saveAppointment(String code, PatientProfile p, Branch b, StaffProfile doc,
                                        LocalDate d, LocalTime t, AppointmentStatus status) {
        Appointment a = new Appointment();
        a.setAppointmentCode(code);
        a.setPatientProfile(p);
        a.setBranch(b);
        a.setDoctor(doc);
        a.setAppointmentDate(d);
        a.setPreferredSession(PreferredSession.MORNING);
        a.setExpectedTime(t);
        a.setDurationMinutes(30);
        a.syncExpectedEndTime();
        a.setInitialSymptoms("MySQL 8 test");
        a.setSource(AppointmentSource.RECEPTIONIST);
        a.setStatus(status);
        return appointmentRepository.saveAndFlush(a);
    }

    private HttpSession sessionFor(User u, Branch b) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("loggedInUser", u);
        s.setAttribute("currentBranchId", b.getBranchId());
        return s;
    }
}
