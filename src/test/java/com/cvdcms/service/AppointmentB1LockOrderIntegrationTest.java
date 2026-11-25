package com.cvdcms.service;

import com.cvdcms.DTO.ReExaminationResponseDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.entity.AccountPatientProfile;
import com.cvdcms.repository.AccountPatientProfileRepository;
import com.cvdcms.service.PortalAppointmentService;
import com.cvdcms.repository.ReceptionCheckInKeyRepository;
import com.cvdcms.service.ReceptionService;
import com.cvdcms.repository.ReceptionVisitRepository;
import com.cvdcms.repository.*;
import com.cvdcms.service.AppointmentPolicyService;
import com.cvdcms.service.ReExaminationService;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm thử tích hợp Giai đoạn B1 trên JPA/Hibernate + Transaction thật (H2 MODE=MySQL):
 * - Xác minh thứ tự SQL lock thực tế (StatementInspector + JDBC parameter capture):
 *   PatientProfile -> Branch -> StaffProfile (staffId ASC) -> StaffSchedule -> Appointment (ASC) -> Visit.
 * - Xác minh Post-lock Verification (409 CONFLICT) khi Appointment trôi trạng thái/bác sĩ/giờ trong lúc chờ khóa.
 * - Xác minh xử lý expectedTime == null + doctorId != null (vẫn khóa bác sĩ/ca trực, giữ expectedTime == null,
 *   và bảo toàn doctorCapacity).
 * - Xác minh concurrency cross-branch dùng chung bác sĩ, swap bác sĩ A <-> B, và rollback sạch.
 *
 * Lưu ý phạm vi (Mục 8.D): Chạy trên H2 MODE=MySQL; MySQL 8 InnoDB concurrency runtime vẫn là NOT VERIFIED ở bước B1.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:appt_b1_lock_order;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;NON_KEYWORDS=MONTH,YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never",
        "appointments.branch-capacity=24",
        "appointments.doctor-capacity=5",
        "appointments.doctor-concurrent-per-slot=3",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.cvdcms.service.AppointmentB1LockOrderIntegrationTest$SqlCaptureInspector"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AppointmentB1LockOrderIntegrationTest.JdbcCaptureConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AppointmentB1LockOrderIntegrationTest {

    public record BoundQuery(String sql, List<Object> params) { }

    public static class SqlCaptureInspector implements StatementInspector {
        private static final AtomicBoolean RECORDING = new AtomicBoolean(false);
        private static final List<String> CAPTURED_SQL = new CopyOnWriteArrayList<>();

        public static void start() {
            CAPTURED_SQL.clear();
            RECORDING.set(true);
        }

        public static List<String> stopAndGet() {
            RECORDING.set(false);
            return List.copyOf(CAPTURED_SQL);
        }

        @Override
        public String inspect(String sql) {
            if (RECORDING.get() && sql != null) {
                CAPTURED_SQL.add(sql.toLowerCase(Locale.ROOT));
            }
            return sql;
        }
    }

    @TestConfiguration
    static class JdbcCaptureConfig {
        static final AtomicBoolean RECORDING = new AtomicBoolean(false);
        static final List<BoundQuery> CAPTURED = new CopyOnWriteArrayList<>();

        static void start() {
            CAPTURED.clear();
            RECORDING.set(true);
        }

        static List<BoundQuery> stopAndGet() {
            RECORDING.set(false);
            return List.copyOf(CAPTURED);
        }

        @Bean
        BeanPostProcessor dataSourceSpyPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                    if (bean instanceof DataSource ds) {
                        return Proxy.newProxyInstance(
                                ds.getClass().getClassLoader(),
                                new Class<?>[]{DataSource.class},
                                (proxy, method, args) -> {
                                    Object res = method.invoke(ds, args);
                                    if ("getConnection".equals(method.getName()) && res instanceof Connection conn) {
                                        return wrapConnection(conn);
                                    }
                                    return res;
                                });
                    }
                    return bean;
                }
            };
        }

        private static Connection wrapConnection(Connection conn) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        Object res = method.invoke(conn, args);
                        if ("prepareStatement".equals(method.getName())
                                && args != null && args.length > 0 && args[0] instanceof String sql
                                && res instanceof PreparedStatement ps) {
                            return wrapPreparedStatement(ps, sql);
                        }
                        return res;
                    });
        }

        private static PreparedStatement wrapPreparedStatement(PreparedStatement ps, String rawSql) {
            TreeMap<Integer, Object> bound = new TreeMap<>();
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (("setLong".equals(name) || "setInt".equals(name) || "setString".equals(name)
                                || "setObject".equals(name)) && args != null && args.length >= 2 && args[0] instanceof Integer idx) {
                            bound.put(idx, args[1]);
                        } else if ("clearParameters".equals(name)) {
                            bound.clear();
                        } else if (("executeQuery".equals(name) || "executeUpdate".equals(name) || "execute".equals(name))
                                && RECORDING.get()) {
                            CAPTURED.add(new BoundQuery(rawSql.toLowerCase(Locale.ROOT), List.copyOf(bound.values())));
                        }
                        return method.invoke(ps, args);
                    });
        }
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ReceptionAppointmentService receptionAppointmentService;
    @Autowired private PortalAppointmentService portalAppointmentService;
    @Autowired private ReExaminationService reExaminationService;
    @Autowired private ReceptionService receptionService;

    @Autowired private BranchRepository branchRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PatientProfileRepository patientProfileRepository;
    @Autowired private AccountPatientProfileRepository accountPatientProfileRepository;
    @Autowired private StaffProfileRepository staffProfileRepository;
    @Autowired private StaffBranchAssignmentRepository branchAssignmentRepository;
    @Autowired private WorkShiftRepository workShiftRepository;
    @Autowired private StaffScheduleRepository staffScheduleRepository;
    @Autowired private AppointmentSessionPolicyRepository sessionPolicyRepository;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private AppointmentHistoryRepository appointmentHistoryRepository;
    @Autowired private CustomerCareTaskRepository careTaskRepository;
    @Autowired private ReceptionVisitRepository visitRepository;
    @Autowired private ReceptionCheckInKeyRepository checkInKeyRepository;
    @Autowired private ClinicalQueueTicketRepository queueTicketRepository;
    @Autowired private SystemAuditLogRepository auditLogRepository;

    private static final ZoneId CLINIC_ZONE = AppointmentPolicyService.CLINIC_ZONE;
    private static final AtomicLong SEQ = new AtomicLong(1000);

    private String uid;
    private LocalDate today;
    private LocalDate futureDate;
    private LocalDate secondFutureDate;
    private Branch branchA;
    private Branch branchB;
    private WorkShift morningShiftA;
    private WorkShift afternoonShiftA;
    private WorkShift morningShiftB;
    private StaffProfile docLow;
    private StaffProfile docHigh;
    private User dispatcherA;
    private User dispatcherAdmin;
    private User patientUser1;
    private User patientUser2;
    private PatientProfile patient1;
    private PatientProfile patient2;

    @BeforeEach
    void setUp() {
        SqlCaptureInspector.stopAndGet();
        JdbcCaptureConfig.stopAndGet();
        receptionService.setClock(Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")));

        try {
            jdbcTemplate.execute("ALTER TABLE patient_profiles ALTER COLUMN user_id SET NULL");
        } catch (Exception ignored) {
        }

        jdbcTemplate.execute("DELETE FROM portal_booking_requests");
        jdbcTemplate.execute("DELETE FROM booking_consultation_logs");
        jdbcTemplate.execute("DELETE FROM booking_consultation_requests");
        cleanTransientVisitData();
        careTaskRepository.deleteAll();
        appointmentHistoryRepository.deleteAll();
        appointmentRepository.deleteAll();
        auditLogRepository.deleteAll();
        sessionPolicyRepository.deleteAll();

        uid = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        today = LocalDate.now(CLINIC_ZONE);
        futureDate = today.plusDays(4);
        secondFutureDate = today.plusDays(5);

        Permission pView = ensurePermission("APPOINTMENT_VIEW", "Xem lịch hẹn", "RECEPTION");
        Permission pManage = ensurePermission("APPOINTMENT_MANAGE", "Quản lý lịch hẹn", "RECEPTION");
        Permission pCheckIn = ensurePermission("RECEPTION_CHECKIN", "Tiếp đón", "RECEPTION");
        Permission pDispatch = ensurePermission("CLINICAL_DISPATCH", "Điều phối bác sĩ", "RECEPTION");
        Permission pRecord = ensurePermission("MEDICAL_RECORD_VIEW", "Xem hồ sơ", "CLINICAL");

        Role dispatchRole = ensureRole("DISP_B1_" + uid, "Lễ tân điều phối B1",
                Set.of(pView, pManage, pCheckIn, pDispatch));
        Role adminRole = ensureRole("ADMIN", "Quản trị viên",
                Set.of(pView, pManage, pCheckIn, pDispatch));
        Role doctorRole = ensureRole("BAC_SI", "Bác sĩ", Set.of(pRecord));
        Role patientRole = ensureRole("BENH_NHAN", "Bệnh nhân", Set.of());

        branchA = createBranch("B1A_" + uid, "Cơ sở B1 A " + uid);
        branchB = createBranch("B1B_" + uid, "Cơ sở B1 B " + uid);

        morningShiftA = createShift(branchA, "SANG_A_" + uid, "Ca sáng A", LocalTime.of(8, 0), LocalTime.of(12, 0));
        afternoonShiftA = createShift(branchA, "CHIEU_A_" + uid, "Ca chiều A", LocalTime.of(13, 30), LocalTime.of(17, 30));
        morningShiftB = createShift(branchB, "SANG_B_" + uid, "Ca sáng B", LocalTime.of(8, 0), LocalTime.of(12, 0));

        // Đảm bảo docLow.staffId < docHigh.staffId
        docLow = createDoctor("DR1_" + uid, "BS. Nguyễn Thấp", doctorRole, branchA);
        docHigh = createDoctor("DR2_" + uid, "BS. Trần Cao", doctorRole, branchA);
        assertTrue(docLow.getStaffId() < docHigh.getStaffId());

        // Cấp phép docLow làm cả ở branchB (cross-branch cùng bác sĩ)
        addAssignment(docLow, branchB, doctorRole);

        // Lịch trực cho today, futureDate và secondFutureDate
        for (LocalDate d : List.of(today, futureDate, secondFutureDate)) {
            createSchedule(docLow, branchA, morningShiftA, d);
            createSchedule(docLow, branchA, afternoonShiftA, d);
            createSchedule(docHigh, branchA, morningShiftA, d);
            createSchedule(docHigh, branchA, afternoonShiftA, d);
        }
        // Riêng docLow trực CN B ở secondFutureDate sáng hoặc CN B ở futureDate?
        // Lưu ý: không trùng ca với chính nó trên UI, nhưng cùng ngày khác ca hoặc CN B test cross-branch:
        createSchedule(docLow, branchB, morningShiftB, secondFutureDate);
        createSchedule(docLow, branchB, morningShiftB, futureDate);

        dispatcherA = createStaffUser("dispA_" + uid, branchA, dispatchRole, "Điều phối A", branchA);
        dispatcherAdmin = createStaffUser("admB1_" + uid, branchA, adminRole, "Admin B1", branchA);

        patientUser1 = createPatientUser("pat1_" + uid, branchA, patientRole);
        patientUser2 = createPatientUser("pat2_" + uid, branchA, patientRole);

        patient1 = createPatientProfile("BNB1_1_" + uid, "Bệnh Nhân Một", phoneSeq(), branchA, patientUser1);
        patient2 = createPatientProfile("BNB1_2_" + uid, "Bệnh Nhân Hai", phoneSeq(), branchA, patientUser2);
    }

    @AfterEach
    void tearDown() {
        SqlCaptureInspector.stopAndGet();
        JdbcCaptureConfig.stopAndGet();
        receptionService.setClock(Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")));
    }

    private void cleanTransientVisitData() {
        queueTicketRepository.deleteAll();
        jdbcTemplate.execute("DELETE FROM clinical_examinations");
        checkInKeyRepository.deleteAll();
        visitRepository.deleteAll();
    }

    @Test
    @DisplayName("8.A#1 + 8.C: Update lịch giữ nguyên bác sĩ phát SQL lock PatientProfile -> Branch -> StaffProfile -> StaffSchedule -> Appointment")
    void updateSameDoctorFollowsStrictLockOrder() {
        var created = receptionAppointmentService.create(sessionFor(dispatcherA),
                new ReceptionAppointmentService.CreateRequest(
                        branchA.getBranchId(), patient1.getProfileId(), futureDate, PreferredSession.MORNING,
                        docLow.getStaffId(), null, "Khám răng", LocalTime.of(8, 30)));

        startCapture();
        var updated = receptionAppointmentService.update(sessionFor(dispatcherA), created.id(),
                new ReceptionAppointmentService.UpdateRequest(
                        futureDate, PreferredSession.MORNING, docLow.getStaffId(), null,
                        "Đổi giờ nhẹ", "Khách xin dời giờ", LocalTime.of(9, 30)));
        CaptureResult cap = stopCapture();

        assertEquals(LocalTime.of(9, 30), updated.expectedTime());
        assertEquals(docLow.getStaffId(), updated.doctorId());
        assertTableLockHierarchy(cap.sqls(), true, true, true);
    }

    @Test
    @DisplayName("8.A#2 + 8.C: Update đổi bác sĩ từ ID cao -> ID thấp phải khóa StaffProfile theo staffId ASC trước Appointment")
    void updateChangingDoctorLocksStaffProfilesAscendingBeforeAppointment() {
        var appt = receptionAppointmentService.create(sessionFor(dispatcherA),
                new ReceptionAppointmentService.CreateRequest(
                        branchA.getBranchId(), patient1.getProfileId(), futureDate, PreferredSession.MORNING,
                        docHigh.getStaffId(), null, "Khám tổng quát", LocalTime.of(9, 0)));

        startCapture();
        var updated = receptionAppointmentService.update(sessionFor(dispatcherA), appt.id(),
                new ReceptionAppointmentService.UpdateRequest(
                        futureDate, PreferredSession.MORNING, docLow.getStaffId(), null,
                        "Chuyển sang BS thấp", "Điều phối lại bác sĩ", LocalTime.of(10, 0)));
        CaptureResult cap = stopCapture();

        assertEquals(docLow.getStaffId(), updated.doctorId());
        assertEquals(LocalTime.of(10, 0), updated.expectedTime());
        assertTableLockHierarchy(cap.sqls(), true, true, true);

        // Xác minh JDBC parameter thực tế: docLow.staffId PHẢI được khóa trước docHigh.staffId!
        List<Long> lockedStaffIds = cap.boundQueries().stream()
                .filter(q -> q.sql().contains("from staff_profiles") && q.sql().contains("for update"))
                .map(q -> ((Number) q.params().get(0)).longValue())
                .toList();
        int idxLow = lockedStaffIds.indexOf(docLow.getStaffId());
        int idxHigh = lockedStaffIds.indexOf(docHigh.getStaffId());
        assertTrue(idxLow >= 0 && idxHigh > idxLow,
                "Phải khóa StaffProfile theo staffId ASC (" + docLow.getStaffId() + " trước " + docHigh.getStaffId() + "), thực tế: " + lockedStaffIds);
    }

    @Test
    @DisplayName("8.A#3: Hai request đồng thời đổi chéo bác sĩ A->B và B->A không deadlock và giữ dữ liệu nhất quán")
    void concurrentDoctorSwapAToBAndBToASucceedsCleanly() throws Exception {
        var appt1 = receptionAppointmentService.create(sessionFor(dispatcherA),
                new ReceptionAppointmentService.CreateRequest(
                        branchA.getBranchId(), patient1.getProfileId(), futureDate, PreferredSession.MORNING,
                        docHigh.getStaffId(), null, "Ca 1", LocalTime.of(8, 30)));
        var appt2 = receptionAppointmentService.create(sessionFor(dispatcherA),
                new ReceptionAppointmentService.CreateRequest(
                        branchA.getBranchId(), patient2.getProfileId(), futureDate, PreferredSession.MORNING,
                        docLow.getStaffId(), null, "Ca 2", LocalTime.of(10, 0)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<ReceptionAppointmentService.AppointmentView> f1 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return receptionAppointmentService.update(sessionFor(dispatcherA), appt1.id(),
                        new ReceptionAppointmentService.UpdateRequest(
                                futureDate, PreferredSession.MORNING, docLow.getStaffId(), null,
                                "Đổi sang BS Thấp", "Swap 1", LocalTime.of(8, 30)));
            });
            Future<ReceptionAppointmentService.AppointmentView> f2 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return receptionAppointmentService.update(sessionFor(dispatcherA), appt2.id(),
                        new ReceptionAppointmentService.UpdateRequest(
                                futureDate, PreferredSession.MORNING, docHigh.getStaffId(), null,
                                "Đổi sang BS Cao", "Swap 2", LocalTime.of(10, 0)));
            });

            var res1 = f1.get(15, TimeUnit.SECONDS);
            var res2 = f2.get(15, TimeUnit.SECONDS);
            assertEquals(docLow.getStaffId(), res1.doctorId());
            assertEquals(docHigh.getStaffId(), res2.doctorId());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("8.A#4, #5 + 8.C: Confirm và QuickStatus(CONFIRMED) khóa StaffProfile/StaffSchedule trước Appointment")
    void confirmAndQuickStatusConfirmedFollowStrictLockOrder() {
        var a1 = receptionAppointmentService.create(sessionFor(dispatcherA),
                new ReceptionAppointmentService.CreateRequest(
                        branchA.getBranchId(), patient1.getProfileId(), futureDate, PreferredSession.MORNING,
                        docLow.getStaffId(), null, "Chờ confirm", LocalTime.of(8, 30),
                        null, null, null, null, true, null));
        var a2 = receptionAppointmentService.create(sessionFor(dispatcherA),
                new ReceptionAppointmentService.CreateRequest(
                        branchA.getBranchId(), patient2.getProfileId(), futureDate, PreferredSession.MORNING,
                        docHigh.getStaffId(), null, "Chờ quickStatus", LocalTime.of(9, 30),
                        null, null, null, null, true, null));

        startCapture();
        var c1 = receptionAppointmentService.confirm(sessionFor(dispatcherA), a1.id(),
                new ReceptionAppointmentService.ConfirmRequest(docLow.getStaffId()));
        CaptureResult capConfirm = stopCapture();

        assertEquals(AppointmentStatus.CONFIRMED, c1.status());
        assertTableLockHierarchy(capConfirm.sqls(), true, true, true);

        startCapture();
        var c2 = receptionAppointmentService.quickStatus(sessionFor(dispatcherA), a2.id(),
                new ReceptionAppointmentService.QuickStatusRequest("CONFIRMED", "Xác nhận nhanh"));
        CaptureResult capQuick = stopCapture();

        assertEquals(AppointmentStatus.CONFIRMED, c2.status());
        assertTableLockHierarchy(capQuick.sqls(), true, true, true);
    }

    @Test
    @DisplayName("8.A#6 + 8.C: Portal reschedule đổi bác sĩ/ngày giữ lock order StaffProfile (ASC) -> StaffSchedule -> Appointment")
    void portalRescheduleFollowsStrictLockOrder() {
        var booked = portalAppointmentService.book(sessionFor(patientUser1),
                new PortalAppointmentService.BookingRequest(
                        patient1.getProfileId(), branchA.getBranchId(), futureDate, PreferredSession.MORNING,
                        docHigh.getStaffId(), null, "Đặt online", UUID.randomUUID().toString(), LocalTime.of(9, 0)));

        startCapture();
        var rescheduled = portalAppointmentService.reschedule(sessionFor(patientUser1), booked.id(),
                new PortalAppointmentService.RescheduleRequest(
                        secondFutureDate, PreferredSession.MORNING, docLow.getStaffId(),
                        "Đổi bác sĩ và ngày", LocalTime.of(10, 30)));
        CaptureResult cap = stopCapture();

        assertEquals(AppointmentStatus.PENDING, rescheduled.status());
        assertEquals(docLow.getStaffId(), rescheduled.requestedDoctorId());
        assertEquals(secondFutureDate, rescheduled.date());
        assertEquals(1, rescheduled.rescheduleCount());
        assertTableLockHierarchy(cap.sqls(), true, true, true);

        List<Long> lockedStaffIds = cap.boundQueries().stream()
                .filter(q -> q.sql().contains("from staff_profiles") && q.sql().contains("for update"))
                .map(q -> ((Number) q.params().get(0)).longValue())
                .toList();
        assertTrue(lockedStaffIds.indexOf(docLow.getStaffId()) < lockedStaffIds.indexOf(docHigh.getStaffId()));
    }

    @Test
    @DisplayName("8.A#7 + 8.C: ReExam updateAppointmentDate khóa PatientProfile -> Branch -> StaffProfile -> StaffSchedule -> Appointment")
    void reExaminationUpdateAppointmentDateFollowsLockOrder() {
        Appointment reExam = new Appointment();
        reExam.setAppointmentCode("LHRE_" + uid);
        reExam.setPatientProfile(patient1);
        reExam.setBranch(branchA);
        reExam.setDoctor(docLow);
        reExam.setAppointmentDate(futureDate);
        reExam.setPreferredSession(PreferredSession.MORNING);
        reExam.setExpectedTime(LocalTime.of(9, 0));
        reExam.setDurationMinutes(30);
        reExam.syncExpectedEndTime();
        reExam.setStatus(AppointmentStatus.CONFIRMED);
        reExam.setSource(AppointmentSource.RECEPTIONIST);
        reExam = appointmentRepository.saveAndFlush(reExam);

        startCapture();
        ReExaminationResponseDTO res = reExaminationService.updateAppointmentDate(
                reExam.getAppointmentId(), secondFutureDate, "10:30", PreferredSession.MORNING);
        CaptureResult cap = stopCapture();

        assertEquals(secondFutureDate, res.getAppointmentDate());
        assertEquals(LocalTime.of(10, 30), res.getExpectedTime());
        assertTableLockHierarchy(cap.sqls(), true, true, true);
    }

    @Test
    @DisplayName("8.A#8, #9 + 8.C: Check-in (có doctorId & không doctorId) đúng thứ tự khóa & chặn bác sĩ INACTIVE")
    void checkInFollowsLockOrderAndRejectsInactiveDoctor() {
        receptionService.setClock(Clock.fixed(LocalDateTime.of(today, LocalTime.of(9, 0)).atZone(CLINIC_ZONE).toInstant(), CLINIC_ZONE));

        Appointment withDoc = saveTodayAppointment(patient1, branchA, docLow, LocalTime.of(9, 10), AppointmentStatus.CONFIRMED);
        startCapture();
        ReceptionService.VisitResult v1 = receptionService.checkInAppointment(
                sessionFor(dispatcherA), withDoc.getAppointmentId(), "Đau răng", UUID.randomUUID().toString());
        CaptureResult capWithDoc = stopCapture();

        assertEquals(VisitStatus.WAITING, v1.status());
        assertTableLockHierarchy(capWithDoc.sqls(), true, false, true);
        int staffLockIdx = firstIdx(capWithDoc.sqls(), s -> s.contains("from staff_profiles") && s.contains("for update"));
        int apptLockIdx = firstIdx(capWithDoc.sqls(), s -> s.contains("from appointments") && s.contains("for update"));
        int visitInsertIdx = firstIdx(capWithDoc.sqls(), s -> s.contains("insert into visits"));
        assertTrue(staffLockIdx >= 0 && apptLockIdx > staffLockIdx && visitInsertIdx > apptLockIdx);
        assertEquals(docLow.getStaffId(), visitRepository.findById(v1.id()).orElseThrow().getPrimaryDoctor().getStaffId());

        // Check-in appointment không có doctorId via confirmAndCheckIn
        Appointment noDoc = saveTodayAppointment(patient2, branchA, null, LocalTime.of(9, 10), AppointmentStatus.PENDING);
        startCapture();
        ReceptionService.VisitResult v2 = receptionService.confirmAndCheckIn(
                sessionFor(dispatcherA), noDoc.getAppointmentId(), "Không có BS trước", UUID.randomUUID().toString());
        CaptureResult capNoDoc = stopCapture();

        assertEquals(VisitStatus.WAITING, v2.status());
        assertEquals(-1, firstIdx(capNoDoc.sqls(), s -> s.contains("from staff_profiles") && s.contains("for update")));
        assertTableLockHierarchy(capNoDoc.sqls(), false, false, true);

        // Nếu bác sĩ chuyển sang INACTIVE -> chặn check-in 409 CONFLICT
        cleanTransientVisitData();
        docHigh.setStatus(StaffStatus.INACTIVE);
        staffProfileRepository.saveAndFlush(docHigh);
        Appointment inactiveDocAppt = saveTodayAppointment(patient1, branchA, docHigh, LocalTime.of(9, 10), AppointmentStatus.CONFIRMED);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                receptionService.checkInAppointment(sessionFor(dispatcherA), inactiveDocAppt.getAppointmentId(), "Test", UUID.randomUUID().toString()));
        assertEquals(409, ex.getStatusCode().value());
    }

    @Test
    @DisplayName("8.A#10: Phát hiện Appointment trôi trạng thái/bác sĩ/giờ khi đang chờ khóa ở tất cả 6 luồng (409 CONFLICT)")
    void postLockVerificationDetectsConcurrentStateDriftAcrossAllFlows() throws Exception {
        // 1. Update drift (doctor changed concurrently while waiting for PatientProfile lock)
        var aUpdate = createDirectAppointment(patient1, branchA, docHigh, futureDate, PreferredSession.MORNING, LocalTime.of(8, 30), AppointmentStatus.PENDING);
        assertConflictOnConcurrentMutation(aUpdate.getAppointmentId(), patient1.getProfileId(),
                () -> jdbcTemplate.update("UPDATE appointments SET doctor_id = ? WHERE appointment_id = ?", docLow.getStaffId(), aUpdate.getAppointmentId()),
                () -> receptionAppointmentService.update(sessionFor(dispatcherA), aUpdate.getAppointmentId(),
                        new ReceptionAppointmentService.UpdateRequest(futureDate, PreferredSession.MORNING, docHigh.getStaffId(), null, "Test", "Drift", LocalTime.of(9, 30))));
        assertEquals(docLow.getStaffId(), appointmentRepository.findById(aUpdate.getAppointmentId()).orElseThrow().getDoctor().getStaffId());

        // 2. Confirm drift (concurrently cancelled)
        var aConfirm = createDirectAppointment(patient2, branchA, docLow, futureDate, PreferredSession.MORNING, LocalTime.of(10, 0), AppointmentStatus.PENDING);
        assertConflictOnConcurrentMutation(aConfirm.getAppointmentId(), patient2.getProfileId(),
                () -> jdbcTemplate.update("UPDATE appointments SET status = 'CANCELLED' WHERE appointment_id = ?", aConfirm.getAppointmentId()),
                () -> receptionAppointmentService.confirm(sessionFor(dispatcherA), aConfirm.getAppointmentId(), new ReceptionAppointmentService.ConfirmRequest(docLow.getStaffId())));
        assertEquals(AppointmentStatus.CANCELLED, appointmentRepository.findById(aConfirm.getAppointmentId()).orElseThrow().getStatus());

        // 3. QuickStatus(CONFIRMED) drift (expectedTime changed concurrently)
        var aQuick = createDirectAppointment(patient1, branchA, docLow, secondFutureDate, PreferredSession.MORNING, LocalTime.of(8, 30), AppointmentStatus.PENDING);
        assertConflictOnConcurrentMutation(aQuick.getAppointmentId(), patient1.getProfileId(),
                () -> jdbcTemplate.update("UPDATE appointments SET expected_time = ? WHERE appointment_id = ?", LocalTime.of(11, 0), aQuick.getAppointmentId()),
                () -> receptionAppointmentService.quickStatus(sessionFor(dispatcherA), aQuick.getAppointmentId(), new ReceptionAppointmentService.QuickStatusRequest("CONFIRMED", null)));
        assertEquals(AppointmentStatus.PENDING, appointmentRepository.findById(aQuick.getAppointmentId()).orElseThrow().getStatus());

        // 4. Portal reschedule drift (date changed concurrently)
        var aPortal = createDirectAppointment(patient2, branchA, docLow, secondFutureDate, PreferredSession.MORNING, LocalTime.of(9, 0), AppointmentStatus.CONFIRMED);
        assertConflictOnConcurrentMutation(aPortal.getAppointmentId(), patient2.getProfileId(),
                () -> jdbcTemplate.update("UPDATE appointments SET appointment_date = ? WHERE appointment_id = ?", secondFutureDate.plusDays(1), aPortal.getAppointmentId()),
                () -> portalAppointmentService.reschedule(sessionFor(patientUser2), aPortal.getAppointmentId(),
                        new PortalAppointmentService.RescheduleRequest(futureDate, PreferredSession.AFTERNOON, docHigh.getStaffId(), "Dời", LocalTime.of(14, 0))));
        assertEquals(secondFutureDate.plusDays(1), appointmentRepository.findById(aPortal.getAppointmentId()).orElseThrow().getAppointmentDate());

        // 5. ReExaminationServiceImpl.updateAppointmentDate drift (status changed concurrently)
        var aReExam = createDirectAppointment(patient1, branchA, docHigh, futureDate.plusDays(2), PreferredSession.MORNING, LocalTime.of(8, 0), AppointmentStatus.CONFIRMED);
        assertConflictOnConcurrentMutation(aReExam.getAppointmentId(), patient1.getProfileId(),
                () -> jdbcTemplate.update("UPDATE appointments SET doctor_id = ? WHERE appointment_id = ?", docLow.getStaffId(), aReExam.getAppointmentId()),
                () -> reExaminationService.updateAppointmentDate(aReExam.getAppointmentId(), futureDate.plusDays(3), "09:30", PreferredSession.MORNING));
        assertEquals(futureDate.plusDays(2), appointmentRepository.findById(aReExam.getAppointmentId()).orElseThrow().getAppointmentDate());

        // 6. ReceptionService checkInAppointment & confirmAndCheckIn drift
        receptionService.setClock(Clock.fixed(LocalDateTime.of(today, LocalTime.of(9, 0)).atZone(CLINIC_ZONE).toInstant(), CLINIC_ZONE));
        Appointment aCheckIn = saveTodayAppointment(patient1, branchA, docLow, LocalTime.of(9, 10), AppointmentStatus.CONFIRMED);
        assertConflictOnConcurrentMutation(aCheckIn.getAppointmentId(), patient1.getProfileId(),
                () -> jdbcTemplate.update("UPDATE appointments SET doctor_id = ? WHERE appointment_id = ?", docHigh.getStaffId(), aCheckIn.getAppointmentId()),
                () -> receptionService.checkInAppointment(sessionFor(dispatcherA), aCheckIn.getAppointmentId(), "Test", UUID.randomUUID().toString()));
        assertEquals(0, visitRepository.count());

        Appointment aConfCheckIn = saveTodayAppointment(patient2, branchA, docLow, LocalTime.of(9, 10), AppointmentStatus.PENDING);
        assertConflictOnConcurrentMutation(aConfCheckIn.getAppointmentId(), patient2.getProfileId(),
                () -> jdbcTemplate.update("UPDATE appointments SET status = 'CANCELLED' WHERE appointment_id = ?", aConfCheckIn.getAppointmentId()),
                () -> receptionService.confirmAndCheckIn(sessionFor(dispatcherA), aConfCheckIn.getAppointmentId(), "Test", UUID.randomUUID().toString()));
        assertEquals(0, visitRepository.count());
    }

    @Test
    @DisplayName("8.A#11, #12: Branch INACTIVE bị từ chối và rollback không để lại dữ liệu dở dang khi thất bại")
    void inactiveBranchRejectedAndRollbackLeavesCleanDatabase() {
        var appt = receptionAppointmentService.create(sessionFor(dispatcherA),
                new ReceptionAppointmentService.CreateRequest(
                        branchA.getBranchId(), patient1.getProfileId(), futureDate, PreferredSession.MORNING,
                        docLow.getStaffId(), null, "Ban đầu", LocalTime.of(9, 0)));
        long histBefore = appointmentHistoryRepository.findByAppointment_AppointmentIdOrderByCreatedAtAscHistoryIdAsc(appt.id()).size();

        // Rollback khi chọn khung giờ ngoài ca (11:45 + 30p > 12:00) ở update & portal reschedule
        assertThrows(ResponseStatusException.class, () ->
                receptionAppointmentService.update(sessionFor(dispatcherA), appt.id(),
                        new ReceptionAppointmentService.UpdateRequest(
                                futureDate, PreferredSession.MORNING, docHigh.getStaffId(), null,
                                "Sai giờ", "Out of range", LocalTime.of(11, 45))));
        Appointment afterUpdateFail = appointmentRepository.findById(appt.id()).orElseThrow();
        assertEquals(docLow.getStaffId(), afterUpdateFail.getDoctor().getStaffId());
        assertEquals(LocalTime.of(9, 0), afterUpdateFail.getExpectedTime());
        assertEquals(0, afterUpdateFail.getRescheduleCount());
        assertEquals(histBefore, appointmentHistoryRepository.findByAppointment_AppointmentIdOrderByCreatedAtAscHistoryIdAsc(appt.id()).size());

        // Chặn Branch không hoạt động sau lock
        jdbcTemplate.update("UPDATE branches SET is_active = 0 WHERE branch_id = ?", branchA.getBranchId());
        ResponseStatusException updEx = assertThrows(ResponseStatusException.class, () ->
                receptionAppointmentService.update(sessionFor(dispatcherAdmin), appt.id(),
                        new ReceptionAppointmentService.UpdateRequest(futureDate, PreferredSession.MORNING, docLow.getStaffId(), null, "X", "Y", LocalTime.of(9, 0))));
        assertEquals(400, updEx.getStatusCode().value());

        ResponseStatusException resEx = assertThrows(ResponseStatusException.class, () ->
                portalAppointmentService.reschedule(sessionFor(patientUser1), appt.id(),
                        new PortalAppointmentService.RescheduleRequest(secondFutureDate, PreferredSession.MORNING, docLow.getStaffId(), "Dời", LocalTime.of(9, 0))));
        assertEquals(400, resEx.getStatusCode().value());

        assertThrows(IllegalStateException.class, () ->
                reExaminationService.updateAppointmentDate(appt.id(), secondFutureDate, "09:30", PreferredSession.MORNING));
    }

    @Test
    @DisplayName("8.B#1, #2, #3, #5 + 8.C: expectedTime == null + doctorId != null khóa bác sĩ, giữ nguyên null và không vượt doctorCapacity")
    void flexibleDoctorBookingLocksDoctorPreservesNullTimeAndEnforcesCapacity() throws Exception {
        // Đặt doctorCapacity = 1 cho branchA futureDate MORNING
        AppointmentSessionPolicy policy = new AppointmentSessionPolicy();
        policy.setBranch(branchA);
        policy.setAppointmentDate(futureDate);
        policy.setPreferredSession(PreferredSession.MORNING);
        policy.setBranchCapacity(10);
        policy.setDoctorCapacity(1);
        sessionPolicyRepository.saveAndFlush(policy);

        // 1. Tạo lịch linh hoạt (expectedTime == null, doctorId != null) -> phải khóa StaffProfile + StaffSchedule + Appointment
        startCapture();
        var flex = receptionAppointmentService.create(sessionFor(dispatcherA),
                new ReceptionAppointmentService.CreateRequest(
                        branchA.getBranchId(), patient1.getProfileId(), futureDate, PreferredSession.MORNING,
                        docLow.getStaffId(), null, "Khám linh hoạt", null));
        CaptureResult cap = stopCapture();

        assertNull(flex.expectedTime(), "Không tự ý gán giờ cố định cho lịch linh hoạt");
        assertTableLockHierarchy(cap.sqls(), true, true, false);
        assertTrue(firstIdx(cap.sqls(), s -> s.contains("from appointments") && s.contains("for update")) >= 0,
                "Phải khóa bảng appointments (lockDoctorAppointmentsForDate) để chặn vượt doctorCapacity");

        // 2. Xác nhận lịch linh hoạt của bệnh nhân khác với cùng bác sĩ đã đầy -> 409 CONFLICT
        var secondReq = new ReceptionAppointmentService.CreateRequest(
                branchA.getBranchId(), patient2.getProfileId(), futureDate, PreferredSession.MORNING,
                docLow.getStaffId(), null, "Người 2 đòi BS Low", null);
        ResponseStatusException capEx = assertThrows(ResponseStatusException.class, () ->
                receptionAppointmentService.create(sessionFor(dispatcherA), secondReq));
        assertEquals(409, capEx.getStatusCode().value());

        // 3. Race condition đồng thời (expectedTime == null & doctorId != null) tranh suất cuối doctorCapacity = 1 ở secondFutureDate
        AppointmentSessionPolicy racePolicy = new AppointmentSessionPolicy();
        racePolicy.setBranch(branchA);
        racePolicy.setAppointmentDate(secondFutureDate);
        racePolicy.setPreferredSession(PreferredSession.MORNING);
        racePolicy.setBranchCapacity(10);
        racePolicy.setDoctorCapacity(1);
        sessionPolicyRepository.saveAndFlush(racePolicy);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        try {
            Callable<Void> r1 = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    receptionAppointmentService.create(sessionFor(dispatcherA),
                            new ReceptionAppointmentService.CreateRequest(
                                    branchA.getBranchId(), patient1.getProfileId(), secondFutureDate, PreferredSession.MORNING,
                                    docHigh.getStaffId(), null, "Race 1", null));
                    ok.incrementAndGet();
                } catch (ResponseStatusException ex) {
                    if (ex.getStatusCode().value() == 409) conflict.incrementAndGet();
                    else throw ex;
                }
                return null;
            };
            Callable<Void> r2 = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    portalAppointmentService.book(sessionFor(patientUser2),
                            new PortalAppointmentService.BookingRequest(
                                    patient2.getProfileId(), branchA.getBranchId(), secondFutureDate, PreferredSession.MORNING,
                                    docHigh.getStaffId(), null, "Race 2", UUID.randomUUID().toString(), null));
                    ok.incrementAndGet();
                } catch (ResponseStatusException ex) {
                    if (ex.getStatusCode().value() == 409) conflict.incrementAndGet();
                    else throw ex;
                }
                return null;
            };
            Future<Void> f1 = pool.submit(r1);
            Future<Void> f2 = pool.submit(r2);
            f1.get(15, TimeUnit.SECONDS);
            f2.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, ok.get(), "Chỉ đúng 1 lịch linh hoạt chiếm được doctorCapacity cuối cùng");
        assertEquals(1, conflict.get());

        // 4. Kết hợp lịch có expectedTime != null (1) + lịch linh hoạt expectedTime == null (1) không vượt doctorCapacity = 2
        AppointmentSessionPolicy mixPolicy = new AppointmentSessionPolicy();
        mixPolicy.setBranch(branchA);
        mixPolicy.setAppointmentDate(secondFutureDate);
        mixPolicy.setPreferredSession(PreferredSession.AFTERNOON);
        mixPolicy.setBranchCapacity(10);
        mixPolicy.setDoctorCapacity(2);
        sessionPolicyRepository.saveAndFlush(mixPolicy);

        var slotted = receptionAppointmentService.create(sessionFor(dispatcherA),
                new ReceptionAppointmentService.CreateRequest(
                        branchA.getBranchId(), patient1.getProfileId(), secondFutureDate, PreferredSession.AFTERNOON,
                        docLow.getStaffId(), null, "Slotted", LocalTime.of(14, 0)));
        assertNotNull(slotted.expectedTime());

        var pendingFlex = createDirectAppointment(patient2, branchA, docHigh, secondFutureDate, PreferredSession.AFTERNOON, null, AppointmentStatus.PENDING);
        // Đổi bác sĩ của pendingFlex sang docLow qua update (expectedTime vẫn null) -> chiếm suất thứ 2/2
        var movedFlex = receptionAppointmentService.update(sessionFor(dispatcherA), pendingFlex.getAppointmentId(),
                new ReceptionAppointmentService.UpdateRequest(secondFutureDate, PreferredSession.AFTERNOON, docLow.getStaffId(), null, "Flex 2", "Đổi BS"));
        assertNull(movedFlex.expectedTime());

        // Tạo bệnh nhân thứ 3 thử đặt cho docLow (đã đủ 2/2) -> phải bị 409 CONFLICT
        PatientProfile patient3 = createPatientProfile("BNB1_3_" + uid, "Bệnh Nhân Ba", phoneSeq(), branchA, null);
        assertThrows(ResponseStatusException.class, () ->
                receptionAppointmentService.create(sessionFor(dispatcherA),
                        new ReceptionAppointmentService.CreateRequest(
                                branchA.getBranchId(), patient3.getProfileId(), secondFutureDate, PreferredSession.AFTERNOON,
                                docLow.getStaffId(), null, "Vượt capacity", null)));
    }

    @Test
    @DisplayName("8.B#4: Hai Branch khác nhau dùng chung một bác sĩ (create ở CN A song song update ở CN B) không deadlock")
    void twoBranchesSharingSameDoctorSerializeAtStaffProfileWithoutDeadlock() throws Exception {
        PatientProfile pB = createPatientProfile("BNBRB_" + uid, "Bệnh Nhân CN B", phoneSeq(), branchB, null);
        // Cho phép dispatcherAdmin thao tác cả branchA và branchB
        addStaffBranchAssignmentForUser(dispatcherAdmin, branchB);

        var apptB = receptionAppointmentService.create(sessionFor(dispatcherAdmin),
                new ReceptionAppointmentService.CreateRequest(
                        branchB.getBranchId(), pB.getProfileId(), futureDate, PreferredSession.MORNING,
                        docLow.getStaffId(), null, "Lịch CN B", LocalTime.of(8, 30)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<ReceptionAppointmentService.AppointmentView> createA = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return receptionAppointmentService.create(sessionFor(dispatcherAdmin),
                        new ReceptionAppointmentService.CreateRequest(
                                branchA.getBranchId(), patient1.getProfileId(), futureDate, PreferredSession.MORNING,
                                docLow.getStaffId(), null, "Lịch CN A khung giờ khác", LocalTime.of(10, 30)));
            });
            Future<ReceptionAppointmentService.AppointmentView> updateB = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return receptionAppointmentService.update(sessionFor(dispatcherAdmin), apptB.id(),
                        new ReceptionAppointmentService.UpdateRequest(
                                futureDate, PreferredSession.MORNING, docLow.getStaffId(), null,
                                "Dời trong CN B", "Cross-branch safe", LocalTime.of(9, 15)));
            });

            var resA = createA.get(15, TimeUnit.SECONDS);
            var resB = updateB.get(15, TimeUnit.SECONDS);
            assertEquals(branchA.getBranchId(), resA.branchId());
            assertEquals(branchB.getBranchId(), resB.branchId());
            assertEquals(LocalTime.of(9, 15), resB.expectedTime());
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== HELPERS ====================

    private void assertConflictOnConcurrentMutation(
            Long appointmentId,
            Long profileId,
            Runnable concurrentDbMutation,
            Callable<?> targetOperation
    ) throws Exception {
        CountDownLatch preReadDone = new CountDownLatch(1);
        CountDownLatch lockAcquired = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Tx1 giữ PatientProfile trước nhằm chặn Tx2 ngay trước bước lấy lock, mô phỏng cửa sổ chờ khóa
            Future<?> blocker = pool.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    patientProfileRepository.findByIdForUpdate(profileId);
                    lockAcquired.countDown();
                    try {
                        assertTrue(preReadDone.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    concurrentDbMutation.run();
                });
            });
            assertTrue(lockAcquired.await(5, TimeUnit.SECONDS));

            // Đặt delay nhỏ để target chạy qua bước pre-read trước khi kẹt trên PatientProfile lock
            Future<Throwable> victim = pool.submit(() -> {
                try {
                    targetOperation.call();
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            Thread.sleep(150);
            preReadDone.countDown();

            blocker.get(10, TimeUnit.SECONDS);
            Throwable caught = victim.get(10, TimeUnit.SECONDS);
            assertNotNull(caught, "Phải từ chối khi Appointment bị đổi trong lúc chờ khóa, id=" + appointmentId);
            assertInstanceOf(ResponseStatusException.class, caught);
            assertEquals(409, ((ResponseStatusException) caught).getStatusCode().value());
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertTableLockHierarchy(List<String> sqls, boolean expectStaff, boolean expectSchedule, boolean expectAppointment) {
        int pIdx = firstIdx(sqls, s -> s.contains("from patient_profiles") && s.contains("for update"));
        int bIdx = firstIdx(sqls, s -> s.contains("from branches") && s.contains("for update"));
        int sIdx = firstIdx(sqls, s -> s.contains("from staff_profiles") && s.contains("for update"));
        int schIdx = firstIdx(sqls, s -> s.contains("from staff_schedules") && s.contains("for update"));
        int aIdx = firstIdx(sqls, s -> s.contains("from appointments") && s.contains("for update"));

        assertTrue(pIdx >= 0, "Thiếu lock PatientProfile: " + sqls);
        assertTrue(bIdx > pIdx, "Branch phải khóa sau PatientProfile: " + sqls);
        if (expectStaff) {
            assertTrue(sIdx > bIdx, "StaffProfile phải khóa sau Branch: " + sqls);
            if (expectSchedule) {
                assertTrue(schIdx > sIdx, "StaffSchedule phải khóa sau StaffProfile: " + sqls);
            }
            if (expectAppointment) {
                assertTrue(aIdx > sIdx && (!expectSchedule || aIdx > schIdx),
                        "Appointment phải khóa sau StaffProfile & StaffSchedule: " + sqls);
            }
        } else if (expectAppointment) {
            assertTrue(aIdx > bIdx, "Appointment phải khóa sau Branch: " + sqls);
        }
    }

    private record CaptureResult(List<String> sqls, List<BoundQuery> boundQueries) { }

    private void startCapture() {
        SqlCaptureInspector.start();
        JdbcCaptureConfig.start();
    }

    private CaptureResult stopCapture() {
        return new CaptureResult(SqlCaptureInspector.stopAndGet(), JdbcCaptureConfig.stopAndGet());
    }

    private int firstIdx(List<String> sqls, java.util.function.Predicate<String> pred) {
        for (int i = 0; i < sqls.size(); i++) {
            if (pred.test(sqls.get(i))) return i;
        }
        return -1;
    }

    private MockHttpSession sessionFor(User u) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("loggedInUser", u);
        return s;
    }

    private String phoneSeq() {
        return "09" + String.format("%08d", SEQ.incrementAndGet());
    }

    private Permission ensurePermission(String code, String desc, String module) {
        return permissionRepository.findByPermissionCode(code).orElseGet(() -> {
            Permission p = new Permission();
            p.setPermissionCode(code); p.setDescription(desc); p.setModule(module);
            return permissionRepository.saveAndFlush(p);
        });
    }

    private Role ensureRole(String name, String display, Set<Permission> perms) {
        return roleRepository.findByRoleName(name).orElseGet(() -> {
            Role r = new Role();
            r.setRoleName(name); r.setDisplayName(display); r.setIsActive(true);
            r.setPermissions(new HashSet<>(perms));
            return roleRepository.saveAndFlush(r);
        });
    }

    private Branch createBranch(String code, String name) {
        Branch b = new Branch();
        b.setBranchCode(code); b.setBranchName(name); b.setAddress("100 Test");
        b.setOpeningTime(LocalTime.of(7, 30)); b.setClosingTime(LocalTime.of(20, 30));
        b.setWorkingDays("2,3,4,5,6,7,CN"); b.setIsActive(true);
        return branchRepository.saveAndFlush(b);
    }

    private WorkShift createShift(Branch branch, String code, String name, LocalTime start, LocalTime end) {
        WorkShift ws = new WorkShift();
        ws.setBranch(branch); ws.setShiftCode(code); ws.setShiftName(name);
        ws.setStartTime(start); ws.setEndTime(end); ws.setIsActive(true);
        return workShiftRepository.saveAndFlush(ws);
    }

    private StaffProfile createDoctor(String code, String name, Role doctorRole, Branch branch) {
        StaffProfile sp = new StaffProfile();
        sp.setStaffCode(code); sp.setFullName(name); sp.setStatus(StaffStatus.ACTIVE);
        sp.setBaseSalary(new BigDecimal("20000000"));
        sp = staffProfileRepository.saveAndFlush(sp);
        addAssignment(sp, branch, doctorRole);
        return sp;
    }

    private void addAssignment(StaffProfile sp, Branch branch, Role role) {
        StaffBranchAssignment sba = new StaffBranchAssignment();
        sba.setStaff(sp); sba.setBranch(branch); sba.setRole(role);
        sba.setIsPrimary(false); sba.setStartDate(today.minusMonths(1));
        sba.setStatus(AssignmentStatus.ACTIVE);
        branchAssignmentRepository.saveAndFlush(sba);
    }

    private void createSchedule(StaffProfile sp, Branch branch, WorkShift ws, LocalDate date) {
        StaffSchedule sch = new StaffSchedule();
        sch.setStaff(sp); sch.setBranch(branch); sch.setShift(ws);
        sch.setWorkDate(date); sch.setStatus(ScheduleStatus.SCHEDULED);
        staffScheduleRepository.saveAndFlush(sch);
    }

    private User createStaffUser(String username, Branch homeBranch, Role role, String fullName, Branch assignBranch) {
        User u = new User();
        u.setUsername(username); u.setPassword("secret"); u.setEmail(username + "@b1.test");
        u.setPhoneNumber(phoneSeq()); u.setUserType(UserType.STAFF);
        u.setIsActive(true); u.setStatus("ACTIVE"); u.setBranch(homeBranch);
        u.setRoles(Set.of(role));
        u = userRepository.saveAndFlush(u);

        StaffProfile sp = new StaffProfile();
        sp.setUser(u); sp.setStaffCode("NV_" + username.toUpperCase()); sp.setFullName(fullName);
        sp.setStatus(StaffStatus.ACTIVE); sp.setBaseSalary(new BigDecimal("10000000"));
        sp = staffProfileRepository.saveAndFlush(sp);

        if (assignBranch != null) {
            StaffBranchAssignment sba = new StaffBranchAssignment();
            sba.setStaff(sp); sba.setBranch(assignBranch); sba.setRole(role);
            sba.setIsPrimary(true); sba.setStartDate(today.minusMonths(1)); sba.setStatus(AssignmentStatus.ACTIVE);
            branchAssignmentRepository.saveAndFlush(sba);
        }
        return u;
    }

    private void addStaffBranchAssignmentForUser(User u, Branch b) {
        StaffProfile sp = staffProfileRepository.findByUser_UserId(u.getUserId()).orElseThrow();
        Role r = u.getRoles().iterator().next();
        addAssignment(sp, b, r);
    }

    private User createPatientUser(String username, Branch branch, Role role) {
        User u = new User();
        u.setUsername(username); u.setPassword("secret"); u.setEmail(username + "@pat.test");
        u.setPhoneNumber(phoneSeq()); u.setUserType(UserType.PATIENT);
        u.setIsActive(true); u.setStatus("ACTIVE"); u.setBranch(branch);
        u.setRoles(Set.of(role));
        return userRepository.saveAndFlush(u);
    }

    private PatientProfile createPatientProfile(String code, String name, String phone, Branch branch, User owner) {
        PatientProfile p = new PatientProfile();
        p.setUser(owner); p.setPatientCode(code); p.setFullName(name);
        p.setDob(LocalDate.of(1992, 6, 15)); p.setGender("Nam"); p.setPhoneNumber(phone);
        p.setAddress("12 Test"); p.setPreferredBranch(branch); p.setIsDefault(true); p.setStatus("ACTIVE");
        p = patientProfileRepository.saveAndFlush(p);

        if (owner != null) {
            AccountPatientProfile link = new AccountPatientProfile();
            link.setAccount(owner); link.setProfile(p);
            link.setAccessType(AccountPatientProfile.AccessType.SELF);
            accountPatientProfileRepository.saveAndFlush(link);
        }
        return p;
    }

    private Appointment saveTodayAppointment(PatientProfile p, Branch b, StaffProfile doc, LocalTime time, AppointmentStatus status) {
        return createDirectAppointment(p, b, doc, today, PreferredSession.MORNING, time, status);
    }

    private Appointment createDirectAppointment(PatientProfile p, Branch b, StaffProfile doc,
                                                LocalDate date, PreferredSession session,
                                                LocalTime time, AppointmentStatus status) {
        Appointment a = new Appointment();
        a.setAppointmentCode("LHB1_" + SEQ.incrementAndGet());
        a.setPatientProfile(p); a.setBranch(b); a.setDoctor(doc);
        a.setAppointmentDate(date); a.setPreferredSession(session);
        a.setExpectedTime(time); a.setDurationMinutes(30);
        a.syncExpectedEndTime();
        a.setStatus(status); a.setSource(AppointmentSource.RECEPTIONIST);
        return appointmentRepository.saveAndFlush(a);
    }
}
