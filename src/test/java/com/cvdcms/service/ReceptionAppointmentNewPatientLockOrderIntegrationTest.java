package com.cvdcms.service;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.service.PatientService;
import com.cvdcms.service.ReceptionConsultationService;
import com.cvdcms.repository.*;
import com.cvdcms.service.AppointmentPolicyService;
import jakarta.persistence.EntityManager;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kiểm thử tích hợp Giai đoạn A trên JPA/Hibernate + Transaction thật (H2 MODE=MySQL):
 * - Chứng minh thứ tự SQL lock thực tế: Branch FOR UPDATE -> INSERT patient_profiles -> Appointment
 *   cho nhánh tạo hồ sơ bệnh nhân mới.
 * - Chứng minh bảo toàn thứ tự PatientProfile -> Branch cho hồ sơ có sẵn.
 * - Chứng minh làm mới Hibernate L1 Cache khi kiểm tra trạng thái ACTIVE của Branch sau khi lấy lock.
 * - Chứng minh rollback đồng bộ khi tạo Appointment thất bại sau bước PatientService.create(),
 *   từ chối sai quyền/Branch INACTIVE và xử lý trùng SĐT/unique constraint.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:reception_appt_phase_a;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;NON_KEYWORDS=MONTH,YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.cvdcms.service.ReceptionAppointmentNewPatientLockOrderIntegrationTest$SqlCaptureInspector"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReceptionAppointmentNewPatientLockOrderIntegrationTest {

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

    @Autowired private WebApplicationContext webContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ReceptionAppointmentService receptionAppointmentService;
    @Autowired private ReceptionConsultationService receptionConsultationService;
    @Autowired private PatientService patientService;

    @Autowired private BranchRepository branchRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PatientProfileRepository patientProfileRepository;
    @Autowired private StaffProfileRepository staffProfileRepository;
    @Autowired private StaffBranchAssignmentRepository branchAssignmentRepository;
    @Autowired private WorkShiftRepository workShiftRepository;
    @Autowired private StaffScheduleRepository staffScheduleRepository;
    @Autowired private AppointmentSessionPolicyRepository sessionPolicyRepository;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private AppointmentHistoryRepository appointmentHistoryRepository;
    @Autowired private BookingConsultationRequestRepository consultationRepository;
    @Autowired private BookingConsultationLogRepository consultationLogRepository;
    @Autowired private CustomerCareTaskRepository careTaskRepository;
    @Autowired private SystemAuditLogRepository auditLogRepository;

    private MockMvc mockMvc;
    private String uid;
    private LocalDate futureDate;
    private Branch branchA;
    private Branch branchB;
    private Branch inactiveBranch;
    private User receptionistA;
    private StaffProfile doctorA;
    private PatientProfile existingProfileA;

    @BeforeEach
    void setUp() {
        SqlCaptureInspector.stopAndGet();
        try {
            jdbcTemplate.execute("ALTER TABLE patient_profiles ALTER COLUMN user_id SET NULL");
        } catch (Exception ignored) {
        }

        consultationLogRepository.deleteAll();
        consultationRepository.deleteAll();
        careTaskRepository.deleteAll();
        appointmentHistoryRepository.deleteAll();
        appointmentRepository.deleteAll();
        auditLogRepository.deleteAll();

        mockMvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        uid = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        futureDate = LocalDate.now(AppointmentPolicyService.CLINIC_ZONE).plusDays(6);

        Permission pView = ensurePermission("APPOINTMENT_VIEW", "Xem lịch hẹn", "RECEPTION");
        Permission pManage = ensurePermission("APPOINTMENT_MANAGE", "Quản lý lịch hẹn", "RECEPTION");
        Permission pRecord = ensurePermission("MEDICAL_RECORD_VIEW", "Xem hồ sơ", "CLINICAL");

        Role recepRole = ensureRole("LE_TAN_PA_" + uid, "Lễ tân Phase A", Set.of(pView, pManage));
        Role doctorRole = ensureRole("BAC_SI", "Bác sĩ", Set.of(pRecord));

        branchA = createBranch("PA_A_" + uid, "Chi nhánh A " + uid, true);
        branchB = createBranch("PA_B_" + uid, "Chi nhánh B " + uid, true);
        inactiveBranch = createBranch("PA_I_" + uid, "Chi nhánh Ngừng " + uid, false);

        doctorA = createDoctor("DRPA_" + uid, "BS. Trần An", doctorRole, branchA);
        receptionistA = createStaffUser("ltPA_" + uid, branchA, recepRole, "Lễ Tân PA", branchA);
        existingProfileA = createPatientProfile("BNPA" + uid, "Bệnh Nhân Cũ", phoneSeq(1), branchA);
    }

    @Test
    @DisplayName("1+3. Tạo lịch hẹn với hồ sơ mới tại Branch ACTIVE khóa Branch (FOR UPDATE) trước INSERT patient_profiles")
    void createNewPatientAtActiveBranchLocksBranchBeforeInsertAndPersists() {
        String newPhone = phoneSeq(2);
        var req = new ReceptionAppointmentService.CreateRequest(
                branchA.getBranchId(), null, futureDate, PreferredSession.MORNING,
                null, null, "Đau răng hàm", LocalTime.of(9, 0),
                "Khách Mới Phase A", newPhone, "10 Lê Lợi", "Vãng lai",
                false, null, LocalDate.of(1995, 5, 20), "Nam",
                null, null, null, null, false
        );

        SqlCaptureInspector.start();
        ReceptionAppointmentService.AppointmentView view = receptionAppointmentService.create(sessionFor(receptionistA), req);
        List<String> sqls = SqlCaptureInspector.stopAndGet();

        assertNotNull(view.id());
        assertNotNull(view.profileId());
        assertFalse(patientProfileRepository.findByPhoneNumber(newPhone).isEmpty());
        assertTrue(appointmentRepository.findById(view.id()).isPresent());

        int branchLockIdx = firstIndexMatching(sqls, s -> s.contains("from branches") && s.contains("for update"));
        int insertProfileIdx = firstIndexMatching(sqls, s -> s.contains("insert into patient_profiles"));
        int insertApptIdx = firstIndexMatching(sqls, s -> s.contains("insert into appointments"));

        assertTrue(branchLockIdx >= 0, "Phải thực hiện SELECT branches FOR UPDATE: " + sqls);
        assertTrue(insertProfileIdx > branchLockIdx,
                "SELECT branches FOR UPDATE phải chạy TRƯỚC INSERT patient_profiles; branchLockIdx=" + branchLockIdx + ", insertProfileIdx=" + insertProfileIdx);
        assertTrue(insertApptIdx > insertProfileIdx,
                "INSERT appointments phải chạy sau INSERT patient_profiles");
    }

    @Test
    @DisplayName("2. Tạo lịch hẹn với PatientProfile đã tồn tại vẫn giữ thứ tự khóa PatientProfile -> Branch")
    void createWithExistingPatientPreservesPatientBeforeBranchLock() {
        var req = new ReceptionAppointmentService.CreateRequest(
                branchA.getBranchId(), existingProfileA.getProfileId(), futureDate, PreferredSession.MORNING,
                null, null, "Tái khám định kỳ", LocalTime.of(9, 30)
        );

        SqlCaptureInspector.start();
        ReceptionAppointmentService.AppointmentView view = receptionAppointmentService.create(sessionFor(receptionistA), req);
        List<String> sqls = SqlCaptureInspector.stopAndGet();

        assertNotNull(view.id());
        assertEquals(existingProfileA.getProfileId(), view.profileId());

        int profileLockIdx = firstIndexMatching(sqls, s -> s.contains("from patient_profiles") && s.contains("for update"));
        int branchLockIdx = firstIndexMatching(sqls, s -> s.contains("from branches") && s.contains("for update"));

        assertTrue(profileLockIdx >= 0 && branchLockIdx > profileLockIdx,
                "Nhánh hồ sơ có sẵn phải khóa PatientProfile trước Branch: profileLockIdx=" + profileLockIdx + ", branchLockIdx=" + branchLockIdx);
    }

    @Test
    @DisplayName("4. Không tạo hồ sơ mới tại Branch INACTIVE (trực tiếp và khi Branch nằm sẵn trong Hibernate L1 Cache)")
    void inactiveBranchRejectedEvenWithStaleHibernateL1Cache() {
        Role adminRole = ensureRole("ADMIN", "Quản trị viên", Set.of(
                ensurePermission("APPOINTMENT_VIEW", "Xem lịch hẹn", "RECEPTION"),
                ensurePermission("APPOINTMENT_MANAGE", "Quản lý lịch hẹn", "RECEPTION")));
        User adminUser = createStaffUser("admPA_" + uid, null, adminRole, "Admin PA", null);

        // A. Branch đã INACTIVE từ trước (Admin có quyền chi nhánh vẫn bị chặn ở bước kiểm tra isActive)
        String phoneDirectInactive = phoneSeq(3);
        long profileCountBefore = patientProfileRepository.count();
        var directReq = new ReceptionAppointmentService.CreateRequest(
                inactiveBranch.getBranchId(), null, futureDate, PreferredSession.MORNING,
                null, null, "Khám mới", null,
                "Khách Branch Tắt", phoneDirectInactive, "Hà Nội", "Vãng lai",
                false, null, LocalDate.of(1992, 1, 1), "Nữ",
                null, null, null, null, false
        );
        ResponseStatusException directEx = assertThrows(ResponseStatusException.class, () ->
                receptionAppointmentService.create(sessionFor(adminUser), directReq));
        assertEquals(400, directEx.getStatusCode().value());
        assertTrue(patientProfileRepository.findByPhoneNumber(phoneDirectInactive).isEmpty());

        // B. Branch vừa bị tắt trong DB nhưng đang nằm với isActive=true trong Hibernate L1 Cache
        String phoneStaleL1 = phoneSeq(4);
        TransactionTemplate outerTx = new TransactionTemplate(transactionManager);
        TransactionTemplate requiresNewTx = new TransactionTemplate(transactionManager);
        requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                outerTx.executeWithoutResult(status -> {
                    // Cố ý nạp BranchA (isActive=true) vào L1 Persistence Context trước khi khóa
                    Branch cached = entityManager.find(Branch.class, branchA.getBranchId());
                    assertTrue(Boolean.TRUE.equals(cached.getIsActive()));

                    // Một transaction độc lập cập nhật is_active = false trong DB
                    requiresNewTx.executeWithoutResult(inner ->
                            jdbcTemplate.update("UPDATE branches SET is_active = 0 WHERE branch_id = ?", branchA.getBranchId()));

                    // Gọi ReceptionAppointmentService.create trong cùng Persistence Context có L1 cache cũ
                    var staleReq = new ReceptionAppointmentService.CreateRequest(
                            branchA.getBranchId(), null, futureDate, PreferredSession.MORNING,
                            null, null, "Khám mới", null,
                            "Khách L1 Cũ", phoneStaleL1, "Đà Nẵng", "Vãng lai",
                            false, null, LocalDate.of(1992, 1, 1), "Nữ",
                            null, null, null, null, false
                    );
                    receptionAppointmentService.create(sessionFor(receptionistA), staleReq);
                })
        );

        assertEquals(400, ex.getStatusCode().value());
        assertTrue(patientProfileRepository.findByPhoneNumber(phoneStaleL1).isEmpty(),
                "Không được tạo hồ sơ khi Branch vừa chuyển INACTIVE");
        assertEquals(profileCountBefore, patientProfileRepository.count());
        assertEquals(0, appointmentRepository.count());
    }

    @Test
    @DisplayName("5. Không tạo hồ sơ mới khi người dùng không có quyền tại Branch")
    void unauthorizedBranchForbiddenAndCreatesNoProfile() {
        String phoneNoAccess = phoneSeq(5);
        long countBefore = patientProfileRepository.count();
        var req = new ReceptionAppointmentService.CreateRequest(
                branchB.getBranchId(), null, futureDate, PreferredSession.MORNING,
                null, null, "Khám mới", null,
                "Khách Sai Chi Nhánh", phoneNoAccess, "TP.HCM", "Vãng lai",
                false, null, LocalDate.of(1990, 2, 2), "Nam",
                null, null, null, null, false
        );

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                receptionAppointmentService.create(sessionFor(receptionistA), req));

        assertEquals(403, ex.getStatusCode().value());
        assertTrue(patientProfileRepository.findByPhoneNumber(phoneNoAccess).isEmpty());
        assertEquals(countBefore, patientProfileRepository.count());
        assertEquals(0, appointmentRepository.count());
    }

    @Test
    @DisplayName("6. Nếu tạo Appointment thất bại sau bước PatientService.create(), hồ sơ mới phải rollback toàn bộ")
    void rollbackRemovesNewPatientProfileAndAuditWhenAppointmentStepFails() {
        // Đặt vào khung giờ đã qua ranh giới làm việc (11:45 + 30 phút = 12:15 > 12:00) để slots.resolveFlexibly ném lỗi SAU PatientService.create()
        String rollbackPhone = phoneSeq(6);
        long profilesBefore = patientProfileRepository.count();
        long auditsBefore = auditLogRepository.count();

        var req = new ReceptionAppointmentService.CreateRequest(
                branchA.getBranchId(), null, futureDate, PreferredSession.MORNING,
                null, null, "Đau răng", LocalTime.of(11, 45),
                "Khách Rollback", rollbackPhone, "Huế", "Vãng lai",
                false, null, LocalDate.of(1991, 8, 8), "Nam",
                null, null, null, null, false
        );

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                receptionAppointmentService.create(sessionFor(receptionistA), req));

        assertEquals(400, ex.getStatusCode().value());
        assertTrue(patientProfileRepository.findByPhoneNumber(rollbackPhone).isEmpty(),
                "PatientProfile mới tạo trong cùng transaction bắt buộc phải bị rollback");
        assertEquals(profilesBefore, patientProfileRepository.count());
        assertEquals(auditsBefore, auditLogRepository.count(),
                "Audit log của PatientService.create() cũng phải rollback đồng bộ");
        assertEquals(0, appointmentRepository.count());
    }

    @Test
    @DisplayName("6b. Guest Convert (createNewPatient=true) khóa Branch trước INSERT patient_profiles và rollback sạch nếu lỗi slot")
    void consultationConvertNewPatientFollowsLockOrderAndRollsBackOnFailure() {
        String okPhone = phoneSeq(7);
        BookingConsultationRequest okConsult = createConsultation(branchA, "Khách Tư Vấn", okPhone);

        var okConvert = new ReceptionConsultationService.ConvertRequest(
                UUID.randomUUID().toString(), branchA.getBranchId(),
                null, true, "Khách Tư Vấn", okPhone, LocalDate.of(1994, 7, 7), "Nữ",
                "123 Nguyễn Trãi", null, null, null, null, false,
                futureDate, PreferredSession.MORNING, LocalTime.of(9, 0), false,
                null, false, null, "Đau răng cửa", false, "Convert hợp lệ"
        );

        SqlCaptureInspector.start();
        ReceptionConsultationService.ConvertResult res = receptionConsultationService.convertToAppointment(
                sessionFor(receptionistA), okConsult.getRequestId(), okConvert, null);
        List<String> sqls = SqlCaptureInspector.stopAndGet();

        assertFalse(res.replayed());
        assertNotNull(res.appointment().id());
        assertFalse(patientProfileRepository.findByPhoneNumber(okPhone).isEmpty());

        int reqLockIdx = firstIndexMatching(sqls, s -> s.contains("from booking_consultation_requests") && s.contains("for update"));
        int branchLockIdx = firstIndexMatching(sqls, s -> s.contains("from branches") && s.contains("for update"));
        int insertProfileIdx = firstIndexMatching(sqls, s -> s.contains("insert into patient_profiles"));
        assertTrue(reqLockIdx >= 0 && branchLockIdx > reqLockIdx && insertProfileIdx > branchLockIdx,
                "Thứ tự SQL bắt buộc: booking_consultation_requests FOR UPDATE -> branches FOR UPDATE -> INSERT patient_profiles; sqls=" + sqls);

        // Rollback khi slot thất bại sau tạo hồ sơ
        String failPhone = phoneSeq(8);
        BookingConsultationRequest failConsult = createConsultation(branchA, "Khách Lỗi Slot", failPhone);
        var failConvert = new ReceptionConsultationService.ConvertRequest(
                UUID.randomUUID().toString(), branchA.getBranchId(),
                null, true, "Khách Lỗi Slot", failPhone, LocalDate.of(1994, 7, 7), "Nam",
                "123 Nguyễn Trãi", null, null, null, null, false,
                futureDate, PreferredSession.MORNING, LocalTime.of(11, 50), false,
                null, false, null, "Đau răng", false, "Giờ vượt ca"
        );
        assertThrows(ResponseStatusException.class, () ->
                receptionConsultationService.convertToAppointment(sessionFor(receptionistA), failConsult.getRequestId(), failConvert, null));

        assertTrue(patientProfileRepository.findByPhoneNumber(failPhone).isEmpty());
        BookingConsultationRequest reloaded = consultationRepository.findById(failConsult.getRequestId()).orElseThrow();
        assertEquals(ConsultationRequestStatus.NEW, reloaded.getStatus());
        assertNull(reloaded.getConvertedAppointment());
    }

    @Test
    @DisplayName("7. Không tạo trùng PatientProfile theo SĐT (uk_patient_phone) khi tạo đồng thời và rollback sạch qua HTTP")
    void duplicatePhoneBlockedAndConcurrentDistinctNewPatientsAtSameBranchSucceedWithoutDeadlock() throws Exception {
        // A. Trùng SĐT trên hồ sơ mới qua ReceptionConsultationRestController bị 409 CONFLICT, không tạo hồ sơ thứ 2
        BookingConsultationRequest consultDup = createConsultation(branchA, "Trùng SĐT", existingProfileA.getPhoneNumber());
        String dupPayload = String.format("""
                {
                  "requestKey": "%s",
                  "branchId": %d,
                  "createNewPatient": true,
                  "patientName": "Người Khác Tên",
                  "patientPhone": "%s",
                  "patientDob": "1995-03-10",
                  "patientGender": "Nữ",
                  "patientAddress": "Hà Nội",
                  "confirmDuplicate": true,
                  "date": "%s",
                  "session": "MORNING",
                  "expectedTime": "09:30"
                }
                """, UUID.randomUUID(), branchA.getBranchId(), existingProfileA.getPhoneNumber(), futureDate);

        MvcResult dupResult = mockMvc.perform(post("/reception/consultations/api/" + consultDup.getRequestId() + "/convert")
                        .session(sessionFor(receptionistA))
                        .with(user(receptionistA.getUsername()).roles("USER"))
                        .with(csrf())
                        .contentType("application/json")
                        .content(dupPayload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andReturn();
        assertFalse(dupResult.getResponse().getContentAsString().contains("SQL"));
        assertEquals(1, patientProfileRepository.findByPhoneNumber(existingProfileA.getPhoneNumber()).size());

        // B. 2 luồng đồng thời tạo 2 bệnh nhân mới hợp lệ (SĐT khác nhau) trên cùng 1 Branch (trên H2) đều thành công sạch sẽ
        final String phone1 = phoneSeq(3);
        final String phone2 = phoneSeq(4);

        BookingConsultationRequest c1 = createConsultation(branchA, "Song Song 1", phone1);
        BookingConsultationRequest c2 = createConsultation(branchA, "Song Song 2", phone2);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<ReceptionConsultationService.ConvertResult> t1 = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return receptionConsultationService.convertToAppointment(
                        sessionFor(receptionistA), c1.getRequestId(),
                        new ReceptionConsultationService.ConvertRequest(
                                UUID.randomUUID().toString(), branchA.getBranchId(), null, true,
                                "Song Song 1", phone1, LocalDate.of(1993, 1, 1), "Nam", "Hà Nội",
                                null, null, null, null, false, futureDate, PreferredSession.MORNING,
                                LocalTime.of(10, 0), false, null, false, null, "Khám", false, null),
                        null);
            };
            Callable<ReceptionConsultationService.ConvertResult> t2 = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return receptionConsultationService.convertToAppointment(
                        sessionFor(receptionistA), c2.getRequestId(),
                        new ReceptionConsultationService.ConvertRequest(
                                UUID.randomUUID().toString(), branchA.getBranchId(), null, true,
                                "Song Song 2", phone2, LocalDate.of(1994, 2, 2), "Nữ", "Hà Nội",
                                null, null, null, null, false, futureDate, PreferredSession.MORNING,
                                LocalTime.of(10, 30), false, null, false, null, "Khám", false, null),
                        null);
            };
            Future<ReceptionConsultationService.ConvertResult> f1 = pool.submit(t1);
            Future<ReceptionConsultationService.ConvertResult> f2 = pool.submit(t2);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            assertNotNull(f1.get(15, TimeUnit.SECONDS).appointment().id());
            assertNotNull(f2.get(15, TimeUnit.SECONDS).appointment().id());
            assertEquals(1, patientProfileRepository.findByPhoneNumber(phone1).size());
            assertEquals(1, patientProfileRepository.findByPhoneNumber(phone2).size());
        } finally {
            pool.shutdownNow();
        }
    }

    private int firstIndexMatching(List<String> sqls, java.util.function.Predicate<String> predicate) {
        for (int i = 0; i < sqls.size(); i++) {
            if (predicate.test(sqls.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private MockHttpSession sessionFor(User u) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("loggedInUser", u);
        return s;
    }

    private static final AtomicInteger PHONE_COUNTER = new AtomicInteger(10_000_000);

    private String phoneSeq(int i) {
        return "09" + String.format("%08d", PHONE_COUNTER.addAndGet(1 + Math.max(0, i)));
    }

    private BookingConsultationRequest createConsultation(Branch branch, String name, String phone) {
        BookingConsultationRequest r = new BookingConsultationRequest();
        r.setRequestCode("TV" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase());
        r.setRequestKey(UUID.randomUUID().toString());
        r.setBranch(branch);
        r.setGuestName(name);
        r.setGuestPhone(phone);
        r.setPreferredDate(futureDate);
        r.setPreferredSession(PreferredSession.MORNING);
        r.setPreferredTime(LocalTime.of(9, 0));
        r.setStatus(ConsultationRequestStatus.NEW);
        return consultationRepository.saveAndFlush(r);
    }

    private Permission ensurePermission(String code, String description, String module) {
        return permissionRepository.findByPermissionCode(code).orElseGet(() -> {
            Permission p = new Permission();
            p.setPermissionCode(code);
            p.setDescription(description);
            p.setModule(module);
            return permissionRepository.saveAndFlush(p);
        });
    }

    private Role ensureRole(String roleName, String displayName, Set<Permission> perms) {
        return roleRepository.findByRoleName(roleName).orElseGet(() -> {
            Role r = new Role();
            r.setRoleName(roleName);
            r.setDisplayName(displayName);
            r.setIsActive(true);
            r.setPermissions(new HashSet<>(perms));
            return roleRepository.saveAndFlush(r);
        });
    }

    private Branch createBranch(String code, String name, boolean active) {
        Branch b = new Branch();
        b.setBranchCode(code);
        b.setBranchName(name);
        b.setAddress("123 Đường Kiểm Thử");
        b.setOpeningTime(LocalTime.of(8, 0));
        b.setClosingTime(LocalTime.of(20, 0));
        b.setWorkingDays("2,3,4,5,6,7,CN");
        b.setIsActive(active);
        b = branchRepository.saveAndFlush(b);

        if (active) {
            WorkShift am = new WorkShift();
            am.setBranch(b);
            am.setShiftCode("AM_" + code);
            am.setShiftName("Ca Sáng");
            am.setStartTime(LocalTime.of(8, 0));
            am.setEndTime(LocalTime.of(12, 0));
            am.setIsActive(true);
            workShiftRepository.saveAndFlush(am);
        }
        return b;
    }

    private StaffProfile createDoctor(String code, String name, Role doctorRole, Branch branch) {
        StaffProfile sp = new StaffProfile();
        sp.setStaffCode(code);
        sp.setFullName(name);
        sp.setStatus(StaffStatus.ACTIVE);
        sp.setBaseSalary(new BigDecimal("20000000"));
        sp = staffProfileRepository.saveAndFlush(sp);

        StaffBranchAssignment sba = new StaffBranchAssignment();
        sba.setStaff(sp);
        sba.setBranch(branch);
        sba.setRole(doctorRole);
        sba.setIsPrimary(true);
        sba.setStartDate(futureDate.minusMonths(1));
        sba.setStatus(AssignmentStatus.ACTIVE);
        branchAssignmentRepository.saveAndFlush(sba);

        for (WorkShift ws : workShiftRepository.findAll()) {
            if (ws.getBranch() != null && branch.getBranchId().equals(ws.getBranch().getBranchId())) {
                StaffSchedule sch = new StaffSchedule();
                sch.setStaff(sp);
                sch.setBranch(branch);
                sch.setShift(ws);
                sch.setWorkDate(futureDate);
                sch.setStatus(ScheduleStatus.SCHEDULED);
                staffScheduleRepository.saveAndFlush(sch);
            }
        }
        return sp;
    }

    private User createStaffUser(String username, Branch homeBranch, Role role, String fullName, Branch assignBranch) {
        User u = new User();
        u.setUsername(username);
        u.setPassword("secret");
        u.setEmail(username + "@test.local");
        u.setPhoneNumber(phoneSeq(9));
        u.setUserType(UserType.STAFF);
        u.setIsActive(true);
        u.setStatus("ACTIVE");
        u.setBranch(homeBranch);
        u.setRoles(Set.of(role));
        u = userRepository.saveAndFlush(u);

        StaffProfile sp = new StaffProfile();
        sp.setUser(u);
        sp.setStaffCode("NV_" + username.toUpperCase());
        sp.setFullName(fullName);
        sp.setStatus(StaffStatus.ACTIVE);
        sp.setBaseSalary(new BigDecimal("10000000"));
        sp = staffProfileRepository.saveAndFlush(sp);

        if (assignBranch != null) {
            StaffBranchAssignment sba = new StaffBranchAssignment();
            sba.setStaff(sp);
            sba.setBranch(assignBranch);
            sba.setRole(role);
            sba.setIsPrimary(true);
            sba.setStartDate(futureDate.minusMonths(1));
            sba.setStatus(AssignmentStatus.ACTIVE);
            branchAssignmentRepository.saveAndFlush(sba);
        }
        return u;
    }

    private PatientProfile createPatientProfile(String code, String name, String phone, Branch branch) {
        User owner = new User();
        owner.setUsername("u_" + code.toLowerCase());
        owner.setPassword("secret");
        owner.setEmail(code.toLowerCase() + "@test.local");
        owner.setPhoneNumber(phoneSeq(0));
        owner.setUserType(UserType.PATIENT);
        owner.setIsActive(true);
        owner.setStatus("ACTIVE");
        owner.setBranch(branch);
        owner = userRepository.saveAndFlush(owner);

        PatientProfile p = new PatientProfile();
        p.setUser(owner);
        p.setPatientCode(code);
        p.setFullName(name);
        p.setDob(LocalDate.of(1993, 5, 12));
        p.setGender("Nam");
        p.setPhoneNumber(phone);
        p.setAddress("12 Đường Test");
        p.setPreferredBranch(branch);
        p.setIsDefault(true);
        p.setStatus("ACTIVE");
        return patientProfileRepository.saveAndFlush(p);
    }
}
