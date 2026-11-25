package com.cvdcms.service;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.AppointmentPolicyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kiểm thử tích hợp Đợt 2B – Quản lý yêu cầu tư vấn Guest tại Lễ tân:
 * Chạy với Spring Security, CSRF, JPA/Hibernate trên H2 MODE=MySQL và Transaction thật.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:reception_consultation_2b;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;NON_KEYWORDS=MONTH,YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReceptionConsultationWorkflowIntegrationTest {

    @TestConfiguration
    static class ConvertRollbackProbeConfig {
        static final AtomicBoolean FAIL_ON_CONVERT = new AtomicBoolean(false);

        @Bean
        ConvertFailureProbe convertFailureProbe() {
            return new ConvertFailureProbe();
        }
    }

    static class ConvertFailureProbe {
        @EventListener
        public void onConverted(ReceptionConsultationService.ConsultationConvertedEvent event) {
            if (ConvertRollbackProbeConfig.FAIL_ON_CONVERT.get()) {
                throw new IllegalStateException("Simulated post-convert failure inside transaction");
            }
        }
    }

    @Autowired private WebApplicationContext webContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired private BranchRepository branchRepository;
    @Autowired private ServiceCategoryRepository serviceCategoryRepository;
    @Autowired private ServiceRepository serviceRepository;
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
    @Autowired private CustomerCareInteractionRepository careInteractionRepository;
    @Autowired private CustomerCareTaskRepository careTaskRepository;
    @Autowired private SystemNotificationRepository notificationRepository;
    @Autowired private SystemAuditLogRepository auditLogRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;
    private String uid;
    private LocalDate futureDate;

    private Branch branchA;
    private Branch branchB;
    private com.cvdcms.entity.Service activeOnlineService;
    private com.cvdcms.entity.Service activeOfflineService;
    private com.cvdcms.entity.Service inactiveService;
    private StaffProfile doctorA;
    private StaffProfile inactiveDoctor;
    private StaffProfile doctorB;

    private User adminUser;
    private User receptionistA1;
    private User receptionistA2;
    private User receptionistB;
    private User viewerA;
    private User noBranchStaff;
    private User pureDoctorUser;
    private User dispatchReceptionistA;

    @BeforeEach
    void setUp() {
        ConvertRollbackProbeConfig.FAIL_ON_CONVERT.set(false);
        try {
            jdbcTemplate.execute("ALTER TABLE patient_profiles ALTER COLUMN user_id SET NULL");
        } catch (Exception ignored) {
        }

        consultationLogRepository.deleteAll();
        consultationRepository.deleteAll();
        notificationRepository.deleteAll();
        careInteractionRepository.deleteAll();
        careTaskRepository.deleteAll();
        appointmentHistoryRepository.deleteAll();
        appointmentRepository.deleteAll();
        auditLogRepository.deleteAll();

        mockMvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        uid = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        futureDate = LocalDate.now(AppointmentPolicyService.CLINIC_ZONE).plusDays(5);

        Permission pView = ensurePermission("APPOINTMENT_VIEW", "Xem lịch hẹn", "RECEPTION");
        Permission pManage = ensurePermission("APPOINTMENT_MANAGE", "Quản lý lịch hẹn", "RECEPTION");
        Permission pDispatch = ensurePermission("CLINICAL_DISPATCH", "Điều phối bác sĩ", "RECEPTION");
        Permission pRecord = ensurePermission("MEDICAL_RECORD_VIEW", "Xem hồ sơ bệnh án", "CLINICAL");

        Role adminRole = ensureRole("ADMIN", "Quản trị viên", Set.of(pView, pManage, pDispatch));
        Role recepManageRole = ensureRole("LE_TAN_M_" + uid, "Lễ tân quản lý", Set.of(pView, pManage));
        Role recepDispatchRole = ensureRole("LE_TAN_D_" + uid, "Lễ tân điều phối", Set.of(pView, pManage, pDispatch));
        Role recepViewRole = ensureRole("LE_TAN_V_" + uid, "Lễ tân chỉ xem", Set.of(pView));
        Role doctorRole = ensureRole("BAC_SI", "Bác sĩ", Set.of(pRecord));

        branchA = createBranch("BA_" + uid, "Chi nhánh Quận 1 " + uid, true);
        branchB = createBranch("BB_" + uid, "Chi nhánh Cầu Giấy " + uid, true);

        ServiceCategory activeCat = new ServiceCategory();
        activeCat.setCategoryCode("CAT_" + uid);
        activeCat.setCategoryName("Khám tổng quát");
        activeCat.setCategoryType(ServiceType.CONSULTATION);
        activeCat.setIsActive(true);
        activeCat = serviceCategoryRepository.saveAndFlush(activeCat);

        activeOnlineService = createService(activeCat, "S1_" + uid, "Khám online", ServiceStatus.ACTIVE, true);
        activeOfflineService = createService(activeCat, "S2_" + uid, "Nhổ răng nội bộ", ServiceStatus.ACTIVE, false);
        inactiveService = createService(activeCat, "S3_" + uid, "Dịch vụ ngừng", ServiceStatus.INACTIVE, true);

        doctorA = createDoctor("DRA_" + uid, "BS. Nguyễn Văn An", doctorRole, branchA, StaffStatus.ACTIVE, true);
        inactiveDoctor = createDoctor("DRX_" + uid, "BS. Nghỉ Việc", doctorRole, branchA, StaffStatus.INACTIVE, false);
        doctorB = createDoctor("DRB_" + uid, "BS. Trần Thị Bình", doctorRole, branchB, StaffStatus.ACTIVE, true);

        adminUser = createStaffUser("adm_" + uid, null, adminRole, "Quản Trị Viên", null);
        receptionistA1 = createStaffUser("ltA1_" + uid, branchA, recepManageRole, "Lễ Tân A1", branchA);
        receptionistA2 = createStaffUser("ltA2_" + uid, branchA, recepManageRole, "Lễ Tân A2", branchA);
        receptionistB = createStaffUser("ltB_" + uid, branchB, recepManageRole, "Lễ Tân B", branchB);
        viewerA = createStaffUser("viewA_" + uid, branchA, recepViewRole, "Chỉ Xem A", branchA);
        noBranchStaff = createStaffUser("nobrn_" + uid, null, recepManageRole, "Không Chi Nhánh", null);
        pureDoctorUser = createStaffUser("docu_" + uid, branchA, doctorRole, "Bác Sĩ Thuần Túy", null);
        dispatchReceptionistA = createStaffUser("ltAD_" + uid, branchA, recepDispatchRole, "Điều Phối A", branchA);
    }

    private MockHttpSession sessionFor(User u) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("loggedInUser", u);
        return s;
    }

    private String phoneSeq(int i) {
        int hash = Math.abs((uid + i + System.nanoTime()).hashCode()) % 10_000_000;
        return "09" + i + String.format("%07d", hash);
    }

    private BookingConsultationRequest createConsultation(
            Branch branch,
            String name,
            String phone,
            ConsultationRequestStatus status,
            StaffProfile preferredDoc,
            com.cvdcms.entity.Service svc
    ) {
        BookingConsultationRequest r = new BookingConsultationRequest();
        r.setRequestCode("TV" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase());
        r.setRequestKey(UUID.randomUUID().toString());
        r.setBranch(branch);
        r.setGuestName(name);
        r.setGuestPhone(phone);
        r.setPreferredDate(futureDate);
        r.setPreferredSession(PreferredSession.AFTERNOON);
        r.setPreferredTime(LocalTime.of(15, 0));
        r.setPreferredDoctor(preferredDoc);
        r.setService(svc);
        r.setNotes("Khách cần tư vấn " + name);
        r.setStatus(status);
        return consultationRepository.saveAndFlush(r);
    }

    // =========================================================================
    // 1. Guest không thể gọi API lễ tân & CSRF bắt buộc
    // =========================================================================
    @Test
    @DisplayName("1. Guest không thể gọi API lễ tân; POST thiếu CSRF bị 403")
    void guestCannotAccessReceptionConsultationApisAndCsrfRequired() throws Exception {
        BookingConsultationRequest req = createConsultation(
                branchA, "Khách A", phoneSeq(1), ConsultationRequestStatus.NEW, doctorA, activeOnlineService);

        mockMvc.perform(get("/reception/consultations/api"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/reception/consultations/api/summary"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/reception/consultations/api/" + req.getRequestId() + "/claim")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is3xxRedirection());

        // Đã đăng nhập nhưng thiếu CSRF -> 403 FORBIDDEN
        mockMvc.perform(post("/reception/consultations/api/" + req.getRequestId() + "/claim")
                        .session(sessionFor(receptionistA1))
                        .with(user(receptionistA1.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // 2. Nhân viên không có chi nhánh nhận 403
    // =========================================================================
    @Test
    @DisplayName("2. Nhân viên không có chi nhánh nhận 403 FORBIDDEN, không fallback toàn hệ thống")
    void staffWithoutBranchReceives403() throws Exception {
        createConsultation(branchA, "Khách A", phoneSeq(1), ConsultationRequestStatus.NEW, null, activeOnlineService);

        mockMvc.perform(get("/reception/consultations/api")
                        .session(sessionFor(noBranchStaff))
                        .with(user(noBranchStaff.getUsername()).roles("USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/reception/consultations/api/summary")
                        .session(sessionFor(noBranchStaff))
                        .with(user(noBranchStaff.getUsername()).roles("USER")))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // 3 & 17. Nhân viên chi nhánh A không đọc/sửa chi nhánh B & không sửa branchId
    // =========================================================================
    @Test
    @DisplayName("3 & 17. Nhân viên chi nhánh A không đọc/sửa yêu cầu chi nhánh B và không sửa branchId vượt quyền")
    void crossBranchAccessAndBranchIdTamperingRejectedWith403() throws Exception {
        BookingConsultationRequest reqB = createConsultation(
                branchB, "Khách B", phoneSeq(2), ConsultationRequestStatus.NEW, doctorB, activeOnlineService);
        MockHttpSession sessA = sessionFor(receptionistA1);

        // Truyền branchId=branchB vào list/summary -> 403
        mockMvc.perform(get("/reception/consultations/api")
                        .param("branchId", branchB.getBranchId().toString())
                        .session(sessA)
                        .with(user(receptionistA1.getUsername()).roles("USER")))
                .andExpect(status().isForbidden());

        // Đọc chi tiết, xác minh bệnh nhân, hoặc thao tác trên yêu cầu chi nhánh B -> 403
        mockMvc.perform(get("/reception/consultations/api/" + reqB.getRequestId())
                        .session(sessA)
                        .with(user(receptionistA1.getUsername()).roles("USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/reception/consultations/api/" + reqB.getRequestId() + "/verify-patient")
                        .session(sessA)
                        .with(user(receptionistA1.getUsername()).roles("USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/reception/consultations/api/" + reqB.getRequestId() + "/claim")
                        .with(csrf())
                        .session(sessA)
                        .with(user(receptionistA1.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        // Thử sửa branchId trong body convert của yêu cầu thuộc branchA để trỏ sang branchB -> 403
        BookingConsultationRequest reqA = createConsultation(
                branchA, "Khách A", phoneSeq(3), ConsultationRequestStatus.NEW, doctorA, activeOnlineService);
        Map<String, Object> tamperBody = Map.of(
                "requestKey", UUID.randomUUID().toString(),
                "branchId", branchB.getBranchId(),
                "createNewPatient", true,
                "patientName", "Khách A",
                "patientPhone", reqA.getGuestPhone(),
                "patientDob", "1995-05-10",
                "patientGender", "Nam"
        );
        mockMvc.perform(post("/reception/consultations/api/" + reqA.getRequestId() + "/convert")
                        .with(csrf())
                        .session(sessA)
                        .with(user(receptionistA1.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tamperBody)))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // 4. APPOINTMENT_VIEW chỉ đọc; Bác sĩ thuần túy bị chặn
    // =========================================================================
    @Test
    @DisplayName("4. APPOINTMENT_VIEW chỉ đọc, mọi thao tác sửa trả 403; Bác sĩ thuần túy bị chặn 403")
    void appointmentViewReadOnlyAndPureDoctorDenied() throws Exception {
        BookingConsultationRequest reqA = createConsultation(
                branchA, "Khách Xem", phoneSeq(1), ConsultationRequestStatus.NEW, null, activeOnlineService);

        MockHttpSession viewSess = sessionFor(viewerA);
        mockMvc.perform(get("/reception/consultations/api")
                        .session(viewSess)
                        .with(user(viewerA.getUsername()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));

        mockMvc.perform(get("/reception/consultations/api/" + reqA.getRequestId())
                        .session(viewSess)
                        .with(user(viewerA.getUsername()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canManage").value(false));

        // Chỉ có VIEW -> bị chặn ở claim/contact/close/convert
        mockMvc.perform(post("/reception/consultations/api/" + reqA.getRequestId() + "/claim")
                        .with(csrf()).session(viewSess).with(user(viewerA.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/reception/consultations/api/" + reqA.getRequestId() + "/close")
                        .with(csrf()).session(viewSess).with(user(viewerA.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Thử đóng\"}"))
                .andExpect(status().isForbidden());

        // Bác sĩ thuần túy không có quyền tiếp đón -> 403 ngay cả khi đọc
        MockHttpSession docSess = sessionFor(pureDoctorUser);
        mockMvc.perform(get("/reception/consultations/api")
                        .session(docSess)
                        .with(user(pureDoctorUser.getUsername()).roles("USER")))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // 5 & 6. Hai lễ tân cùng nhận xử lý đồng thời & Không thể ghi đè phụ trách
    // =========================================================================
    @Test
    @DisplayName("5 & 6. Hai lễ tân cùng nhận xử lý đồng thời: chỉ 1 người thành công, không thể ghi đè phụ trách")
    void concurrentClaimAllowsOnlyOneAndPreventsOverwrite() throws Exception {
        BookingConsultationRequest reqA = createConsultation(
                branchA, "Khách Tranh Chấp", phoneSeq(4), ConsultationRequestStatus.NEW, null, activeOnlineService);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger okCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        Callable<Void> c1 = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            int code = mockMvc.perform(post("/reception/consultations/api/" + reqA.getRequestId() + "/claim")
                            .with(csrf()).session(sessionFor(receptionistA1))
                            .with(user(receptionistA1.getUsername()).roles("USER"))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse().getStatus();
            if (code == 200) okCount.incrementAndGet();
            else if (code == 409) conflictCount.incrementAndGet();
            return null;
        };
        Callable<Void> c2 = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            int code = mockMvc.perform(post("/reception/consultations/api/" + reqA.getRequestId() + "/claim")
                            .with(csrf()).session(sessionFor(receptionistA2))
                            .with(user(receptionistA2.getUsername()).roles("USER"))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse().getStatus();
            if (code == 200) okCount.incrementAndGet();
            else if (code == 409) conflictCount.incrementAndGet();
            return null;
        };

        try {
            Future<Void> f1 = pool.submit(c1);
            Future<Void> f2 = pool.submit(c2);
            f1.get(15, TimeUnit.SECONDS);
            f2.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, okCount.get());
        assertEquals(1, conflictCount.get());

        // Thử claim lại trực tiếp bởi người còn lại hoặc ADMIN -> 409 CONFLICT
        mockMvc.perform(post("/reception/consultations/api/" + reqA.getRequestId() + "/claim")
                        .with(csrf()).session(sessionFor(adminUser))
                        .with(user(adminUser.getUsername()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"handledByUserId\":" + receptionistB.getUserId() + "}"))
                .andExpect(status().isConflict());

        BookingConsultationRequest reloaded = consultationRepository.findDetailById(reqA.getRequestId()).orElseThrow();
        assertNotNull(reloaded.getHandledByUser());
        assertNotNull(reloaded.getClaimedAt());
        Long ownerId = reloaded.getHandledByUser().getUserId();
        assertTrue(ownerId.equals(receptionistA1.getUserId()) || ownerId.equals(receptionistA2.getUserId()));
    }

    // =========================================================================
    // 7. Bàn giao hợp lệ có audit log & không bàn giao sang chi nhánh khác
    // =========================================================================
    @Test
    @DisplayName("7. Bàn giao hợp lệ ghi đủ log HANDOVER & SystemAuditLog; chặn bàn giao sang nhân sự chi nhánh khác")
    void handoverUpdatesAssigneeWithLogsAndBlocksCrossBranchTarget() throws Exception {
        BookingConsultationRequest reqA = createConsultation(
                branchA, "Khách Bàn Giao", phoneSeq(5), ConsultationRequestStatus.NEW, null, activeOnlineService);
        MockHttpSession sessA1 = sessionFor(receptionistA1);

        mockMvc.perform(post("/reception/consultations/api/" + reqA.getRequestId() + "/claim")
                        .with(csrf()).session(sessA1).with(user(receptionistA1.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        // Bàn giao sang lễ tân chi nhánh B -> 403 FORBIDDEN
        mockMvc.perform(post("/reception/consultations/api/" + reqA.getRequestId() + "/handover")
                        .with(csrf()).session(sessA1).with(user(receptionistA1.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + receptionistB.getUserId() + ",\"reason\":\"Đổi ca\"}"))
                .andExpect(status().isForbidden());

        // Bàn giao sang lễ tân A2 cùng chi nhánh -> 200 OK + ghi log & audit
        mockMvc.perform(post("/reception/consultations/api/" + reqA.getRequestId() + "/handover")
                        .with(csrf()).session(sessA1).with(user(receptionistA1.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + receptionistA2.getUserId() + ",\"reason\":\"Hết ca sáng\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.handledByUserId").value(receptionistA2.getUserId()));

        List<BookingConsultationLog> logs = consultationLogRepository.findHistoryByRequestId(reqA.getRequestId());
        assertEquals(2, logs.size());
        assertEquals(ConsultationLogAction.HANDOVER, logs.get(1).getAction());
        assertEquals(receptionistA1.getUserId(), logs.get(1).getActorUser().getUserId());
        assertEquals(receptionistA2.getUserId(), logs.get(1).getTargetUser().getUserId());
        assertFalse(auditLogRepository.findByEntityTableAndEntityIdOrderByCreatedAtDesc("booking_consultation_requests", reqA.getRequestId()).isEmpty());
    }

    // =========================================================================
    // 8, 9, 10, 19. Liên hệ nhiều lần không ghi đè; CLOSED/CANCELLED/CONVERTED chặn thao tác
    // =========================================================================
    @Test
    @DisplayName("8, 9, 10, 19. Liên hệ không ghi đè lịch sử; CLOSED, CANCELLED cũ và CONVERTED bị chặn liên hệ/chuyển đổi")
    void contactLogsAppendWithoutOverwriteAndTerminalStatesAreBlocked() throws Exception {
        BookingConsultationRequest req = createConsultation(
                branchA, "Khách Liên Hệ", phoneSeq(6), ConsultationRequestStatus.NEW, null, activeOnlineService);
        MockHttpSession sessA1 = sessionFor(receptionistA1);

        // Lần gọi 1: NO_ANSWER -> NEW chuyển CONTACTED
        mockMvc.perform(post("/reception/consultations/api/" + req.getRequestId() + "/contact")
                        .with(csrf()).session(sessA1).with(user(receptionistA1.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactResult\":\"NO_ANSWER\",\"notes\":\"Thuê bao không nghe máy lần 1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.status").value("CONTACTED"));

        // Lần gọi 2: CONNECTED -> giữ CONTACTED, lưu thêm bản ghi log thứ 2
        mockMvc.perform(post("/reception/consultations/api/" + req.getRequestId() + "/contact")
                        .with(csrf()).session(sessA1).with(user(receptionistA1.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactResult\":\"CONNECTED\",\"notes\":\"Khách hẹn gọi lại chiều mai\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs.length()").value(2));

        List<BookingConsultationLog> contactLogs = consultationLogRepository.findHistoryByRequestId(req.getRequestId());
        assertEquals("Thuê bao không nghe máy lần 1", contactLogs.get(0).getNotes());
        assertEquals(ConsultationContactResult.NO_ANSWER, contactLogs.get(0).getContactResult());
        assertEquals("Khách hẹn gọi lại chiều mai", contactLogs.get(1).getNotes());
        assertEquals(ConsultationContactResult.CONNECTED, contactLogs.get(1).getContactResult());

        // Đóng yêu cầu -> CLOSED
        mockMvc.perform(post("/reception/consultations/api/" + req.getRequestId() + "/close")
                        .with(csrf()).session(sessA1).with(user(receptionistA1.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Khách báo đã khám nơi khác\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.status").value("CLOSED"));

        // Test #8 & #9: Không thể liên hệ hoặc chuyển đổi yêu cầu CLOSED
        mockMvc.perform(post("/reception/consultations/api/" + req.getRequestId() + "/contact")
                        .with(csrf()).session(sessA1).with(user(receptionistA1.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactResult\":\"CONNECTED\",\"notes\":\"Gọi lại\"}"))
                .andExpect(status().isConflict());

        Map<String, Object> convertPayload = Map.of(
                "requestKey", UUID.randomUUID().toString(),
                "createNewPatient", true,
                "patientName", "Khách Đóng",
                "patientPhone", phoneSeq(7),
                "patientDob", "1992-01-01",
                "patientGender", "Nam"
        );
        mockMvc.perform(post("/reception/consultations/api/" + req.getRequestId() + "/convert")
                        .with(csrf()).session(sessA1).with(user(receptionistA1.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(convertPayload)))
                .andExpect(status().isConflict());

        // Test #19: Dữ liệu CANCELLED cũ từ Đợt 2 được xử lý tương đương CLOSED
        BookingConsultationRequest legacyCancelled = createConsultation(
                branchA, "Khách Legacy Cancelled", phoneSeq(8), ConsultationRequestStatus.CANCELLED, null, activeOnlineService);
        mockMvc.perform(get("/reception/consultations/api/" + legacyCancelled.getRequestId())
                        .session(sessA1).with(user(receptionistA1.getUsername()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.status").value("CLOSED"));

        mockMvc.perform(post("/reception/consultations/api/" + legacyCancelled.getRequestId() + "/convert")
                        .with(csrf()).session(sessA1).with(user(receptionistA1.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(convertPayload)))
                .andExpect(status().isConflict());
    }

    // =========================================================================
    // 11 & 12. Trùng SĐT không auto-pick; Khách mới tạo hồ sơ sau xác minh
    // =========================================================================
    @Test
    @DisplayName("11 & 12. Trùng SĐT yêu cầu đối chiếu thủ công không auto-pick; Khách mới tạo hồ sơ & chuyển đổi thành công")
    void duplicatePhoneRequiresManualVerificationAndNewPatientCanBeCreatedOnConvert() throws Exception {
        String sharedPhone = phoneSeq(1);
        createPatientProfileWithCode("BN1_" + uid, "Nguyễn Văn Trùng 1", sharedPhone, branchA);
        createPatientProfileWithCode("BN2_" + uid, "Nguyễn Văn Trùng 2", "+84" + sharedPhone.substring(1), branchB);

        BookingConsultationRequest dupReq = createConsultation(
                branchA, "Khách Trùng SĐT", sharedPhone, ConsultationRequestStatus.NEW, null, activeOfflineService);
        MockHttpSession sessA1 = sessionFor(receptionistA1);

        // Bắt buộc verify trả AMBIGUOUS_CONFLICT với 2 ứng viên, không auto-pick
        mockMvc.perform(get("/reception/consultations/api/" + dupReq.getRequestId() + "/verify-patient")
                        .session(sessA1).with(user(receptionistA1.getUsername()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AMBIGUOUS_CONFLICT"))
                .andExpect(jsonPath("$.candidates.length()").value(2));

        // Thử convert mà không truyền profileId hay createNewPatient -> 400 BAD_REQUEST
        mockMvc.perform(post("/reception/consultations/api/" + dupReq.getRequestId() + "/convert")
                        .with(csrf()).session(sessA1).with(user(receptionistA1.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest());
        assertEquals(0, appointmentRepository.count());

        // Test #12: Khách chưa có hồ sơ -> verify NOT_FOUND -> convert với createNewPatient = true + dịch vụ offline ACTIVE
        String brandNewPhone = phoneSeq(9);
        BookingConsultationRequest newGuestReq = createConsultation(
                branchA, "Trần Khách Mới", brandNewPhone, ConsultationRequestStatus.NEW, null, activeOfflineService);

        mockMvc.perform(get("/reception/consultations/api/" + newGuestReq.getRequestId() + "/verify-patient")
                        .session(sessA1).with(user(receptionistA1.getUsername()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_FOUND"));

        Map<String, Object> convertNew = new LinkedHashMap<>();
        convertNew.put("requestKey", UUID.randomUUID().toString());
        convertNew.put("createNewPatient", true);
        convertNew.put("patientName", "Trần Khách Mới");
        convertNew.put("patientPhone", brandNewPhone);
        convertNew.put("patientDob", "1994-08-20");
        convertNew.put("patientGender", "Nữ");
        convertNew.put("patientAddress", "Quận 1, TP.HCM");

        mockMvc.perform(post("/reception/consultations/api/" + newGuestReq.getRequestId() + "/convert")
                        .with(csrf()).session(sessA1).with(user(receptionistA1.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(convertNew)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail.item.status").value("CONVERTED"))
                .andExpect(jsonPath("$.appointment.id").isNumber());

        assertEquals(1, appointmentRepository.count());
        assertTrue(patientProfileRepository.existsByPhoneNumberIn(List.of(brandNewPhone)));
    }

    // =========================================================================
    // 13 & 14 & 15. Bác sĩ không hợp lệ, dịch vụ ngừng hoạt động, hết sức chứa/trùng lịch
    // =========================================================================
    @Test
    @DisplayName("13, 14, 15. Chặn bác sĩ không hợp lệ, dịch vụ ngừng hoạt động, hết sức chứa hoặc trùng lịch cùng buổi")
    void invalidDoctorInactiveServiceAndCapacityOrSameSessionConflictRejected() throws Exception {
        PatientProfile existingPatient = createPatientProfileWithCode("BN3_" + uid, "Lê Thị Đã Có", phoneSeq(2), branchA);
        MockHttpSession dispatchSess = sessionFor(dispatchReceptionistA);

        // Test #13: Guest chọn bác sĩ ngừng hoạt động (inactiveDoctor) -> nếu gán đích danh thất bại 409; nếu không gán đích danh tuyệt đối không gán inactiveDoctor
        BookingConsultationRequest reqInvalidDoc = createConsultation(
                branchA, "Khách Chọn BS Lỗi", phoneSeq(3), ConsultationRequestStatus.NEW, inactiveDoctor, activeOnlineService);

        Map<String, Object> explicitInvalidDoc = Map.of(
                "requestKey", UUID.randomUUID().toString(),
                "profileId", existingPatient.getProfileId(),
                "doctorId", inactiveDoctor.getStaffId()
        );
        mockMvc.perform(post("/reception/consultations/api/" + reqInvalidDoc.getRequestId() + "/convert")
                        .with(csrf()).session(dispatchSess).with(user(dispatchReceptionistA.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(explicitInvalidDoc)))
                .andExpect(status().isConflict());
        assertEquals(0, appointmentRepository.count());

        // Chuyển đổi không ép bác sĩ lỗi -> hệ thống tự phân bổ bác sĩ hợp lệ (doctorA), không bao giờ gắn inactiveDoctor
        Map<String, Object> autoDoctorPayload = Map.of(
                "requestKey", UUID.randomUUID().toString(),
                "profileId", existingPatient.getProfileId()
        );
        MvcResult okDocRes = mockMvc.perform(post("/reception/consultations/api/" + reqInvalidDoc.getRequestId() + "/convert")
                        .with(csrf()).session(dispatchSess).with(user(dispatchReceptionistA.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(autoDoctorPayload)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode okDocJson = objectMapper.readTree(okDocRes.getResponse().getContentAsString());
        assertEquals(doctorA.getStaffId(), okDocJson.path("appointment").path("doctorId").asLong());
        assertNotEquals(inactiveDoctor.getStaffId(), okDocJson.path("appointment").path("doctorId").asLong());

        // Test #14: Dịch vụ ngừng hoạt động -> 400 BAD_REQUEST
        PatientProfile p2 = createPatientProfileWithCode("BN4_" + uid, "Phạm Văn B", phoneSeq(4), branchA);
        BookingConsultationRequest reqBadSvc = createConsultation(
                branchA, "Khách DV Ngừng", phoneSeq(5), ConsultationRequestStatus.NEW, doctorA, inactiveService);
        mockMvc.perform(post("/reception/consultations/api/" + reqBadSvc.getRequestId() + "/convert")
                        .with(csrf()).session(dispatchSess).with(user(dispatchReceptionistA.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + UUID.randomUUID() + "\",\"profileId\":" + p2.getProfileId() + "}"))
                .andExpect(status().isBadRequest());

        // Test #15a: Đặt trùng cùng buổi cho existingPatient -> 409 CONFLICT
        BookingConsultationRequest reqDupAppt = createConsultation(
                branchA, "Khách Trùng Buổi", existingPatient.getPhoneNumber(), ConsultationRequestStatus.NEW, doctorA, activeOnlineService);
        mockMvc.perform(post("/reception/consultations/api/" + reqDupAppt.getRequestId() + "/convert")
                        .with(csrf()).session(dispatchSess).with(user(dispatchReceptionistA.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + UUID.randomUUID() + "\",\"profileId\":" + existingPatient.getProfileId() + "}"))
                .andExpect(status().isConflict());

        // Test #15b: Hết sức chứa chi nhánh/bác sĩ (branchMaxCapacity = 1 đã bị chiếm bởi appointment đầu tiên) -> 409 CONFLICT
        AppointmentSessionPolicy capPolicy = new AppointmentSessionPolicy();
        capPolicy.setBranch(branchA);
        capPolicy.setAppointmentDate(futureDate);
        capPolicy.setPreferredSession(PreferredSession.AFTERNOON);
        capPolicy.setBranchCapacity(1);
        capPolicy.setDoctorCapacity(1);
        sessionPolicyRepository.saveAndFlush(capPolicy);

        BookingConsultationRequest reqFull = createConsultation(
                branchA, "Khách Hết Chỗ", p2.getPhoneNumber(), ConsultationRequestStatus.NEW, doctorA, activeOnlineService);
        mockMvc.perform(post("/reception/consultations/api/" + reqFull.getRequestId() + "/convert")
                        .with(csrf()).session(dispatchSess).with(user(dispatchReceptionistA.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + UUID.randomUUID() + "\",\"profileId\":" + p2.getProfileId() + "}"))
                .andExpect(status().isConflict());
        assertEquals(ConsultationRequestStatus.NEW, consultationRepository.findDetailById(reqFull.getRequestId()).orElseThrow().getStatus());
    }

    // =========================================================================
    // 10 & 16 & 17. Chuyển đổi đồng thời không tạo 2 lịch; Idempotency key replay
    // =========================================================================
    @Test
    @DisplayName("10, 16, 17. Chuyển đổi đồng thời chỉ tạo 1 Appointment; gửi lại cùng requestKey không tạo lịch thứ hai")
    void concurrentConvertAndIdempotentReplayCreateExactlyOneAppointment() throws Exception {
        PatientProfile p1 = createPatientProfileWithCode("BN5_" + uid, "Hoàng Thị Concurrent", phoneSeq(6), branchA);
        BookingConsultationRequest req = createConsultation(
                branchA, "Khách Đồng Thời", p1.getPhoneNumber(), ConsultationRequestStatus.NEW, null, activeOnlineService);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger okCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();
        String winningKey1 = UUID.randomUUID().toString();
        String winningKey2 = UUID.randomUUID().toString();

        Callable<Void> t1 = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            int code = mockMvc.perform(post("/reception/consultations/api/" + req.getRequestId() + "/convert")
                            .with(csrf()).session(sessionFor(receptionistA1))
                            .with(user(receptionistA1.getUsername()).roles("USER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestKey\":\"" + winningKey1 + "\",\"profileId\":" + p1.getProfileId() + "}"))
                    .andReturn().getResponse().getStatus();
            if (code == 200) okCount.incrementAndGet();
            else if (code == 409) conflictCount.incrementAndGet();
            return null;
        };
        Callable<Void> t2 = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            int code = mockMvc.perform(post("/reception/consultations/api/" + req.getRequestId() + "/convert")
                            .with(csrf()).session(sessionFor(receptionistA2))
                            .with(user(receptionistA2.getUsername()).roles("USER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestKey\":\"" + winningKey2 + "\",\"profileId\":" + p1.getProfileId() + "}"))
                    .andReturn().getResponse().getStatus();
            if (code == 200) okCount.incrementAndGet();
            else if (code == 409) conflictCount.incrementAndGet();
            return null;
        };

        try {
            Future<Void> f1 = pool.submit(t1);
            Future<Void> f2 = pool.submit(t2);
            f1.get(15, TimeUnit.SECONDS);
            f2.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, okCount.get());
        assertEquals(1, conflictCount.get());
        assertEquals(1, appointmentRepository.count());

        BookingConsultationRequest converted = consultationRepository.findDetailById(req.getRequestId()).orElseThrow();
        assertEquals(ConsultationRequestStatus.CONVERTED, converted.getStatus());
        String usedKey = converted.getConvertRequestKey();
        User winningOwner = converted.getHandledByUser();

        // Gửi lại đúng convertRequestKey đã thắng -> trả idempotent 200 replayed=true, vẫn 1 Appointment
        mockMvc.perform(post("/reception/consultations/api/" + req.getRequestId() + "/convert")
                        .with(csrf()).session(sessionFor(winningOwner))
                        .with(user(winningOwner.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + usedKey + "\",\"profileId\":" + p1.getProfileId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
        assertEquals(1, appointmentRepository.count());

        // Gửi lại với requestKey mới sau khi đã CONVERTED -> 409 CONFLICT (Test #10)
        mockMvc.perform(post("/reception/consultations/api/" + req.getRequestId() + "/convert")
                        .with(csrf()).session(sessionFor(winningOwner))
                        .with(user(winningOwner.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + UUID.randomUUID() + "\",\"profileId\":" + p1.getProfileId() + "}"))
                .andExpect(status().isConflict());
        assertEquals(1, appointmentRepository.count());
    }

    // =========================================================================
    // 18. Lỗi giữa transaction phải rollback toàn bộ
    // =========================================================================
    @Test
    @DisplayName("18. Lỗi giữa chừng khi convert: rollback sạch PatientProfile mới, Appointment và giữ nguyên trạng thái Consultation")
    void midTransactionFailureRollsBackPatientAppointmentAndConsultation() throws Exception {
        String brandNewPhone = phoneSeq(7);
        BookingConsultationRequest req = createConsultation(
                branchA, "Khách Rollback", brandNewPhone, ConsultationRequestStatus.NEW, null, activeOnlineService);
        long profilesBefore = patientProfileRepository.count();

        ConvertRollbackProbeConfig.FAIL_ON_CONVERT.set(true);
        try {
            Map<String, Object> payload = Map.of(
                    "requestKey", UUID.randomUUID().toString(),
                    "createNewPatient", true,
                    "patientName", "Khách Rollback",
                    "patientPhone", brandNewPhone,
                    "patientDob", "1996-03-15",
                    "patientGender", "Nam"
            );
            assertThrows(Exception.class, () ->
                    mockMvc.perform(post("/reception/consultations/api/" + req.getRequestId() + "/convert")
                            .with(csrf()).session(sessionFor(receptionistA1))
                            .with(user(receptionistA1.getUsername()).roles("USER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(payload))));
        } finally {
            ConvertRollbackProbeConfig.FAIL_ON_CONVERT.set(false);
        }

        entityManager.clear();
        assertEquals(profilesBefore, patientProfileRepository.count(), "Không được để lại PatientProfile dở dang");
        assertEquals(0, appointmentRepository.count(), "Không được để lại Appointment dở dang");
        BookingConsultationRequest untouched = consultationRepository.findDetailById(req.getRequestId()).orElseThrow();
        assertEquals(ConsultationRequestStatus.NEW, untouched.getStatus());
        assertNull(untouched.getConvertedAppointment());
        assertNull(untouched.getConvertRequestKey());
        assertEquals(0, consultationLogRepository.findHistoryByRequestId(req.getRequestId()).size());
    }

    // =========================================================================
    // 20. Summary Polling không lộ PII & Admin xem theo quyền & JS polling an toàn
    // =========================================================================
    @Test
    @DisplayName("20. Summary polling không chứa PII; Admin xem được nhiều chi nhánh; Filter/page giữ nguyên")
    void summaryContainsNoPiiAndAdminCanViewAcrossBranches() throws Exception {
        String secretGuestPhone = phoneSeq(8);
        createConsultation(branchA, "Bí Mật A", secretGuestPhone, ConsultationRequestStatus.NEW, null, activeOnlineService);
        createConsultation(branchB, "Bí Mật B", phoneSeq(9), ConsultationRequestStatus.CONTACTED, null, activeOnlineService);

        MvcResult sumRes = mockMvc.perform(get("/reception/consultations/api/summary")
                        .session(sessionFor(adminUser)).with(user(adminUser.getUsername()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newCount").value(1))
                .andExpect(jsonPath("$.contactedCount").value(1))
                .andReturn();
        String sumBody = sumRes.getResponse().getContentAsString();
        assertFalse(sumBody.contains(secretGuestPhone), "Payload summary polling không được chứa SĐT");
        assertFalse(sumBody.contains("Bí Mật"), "Payload summary polling không được chứa tên khách");

        // Admin thấy đủ 2 bản ghi đa chi nhánh; Lễ tân A chỉ thấy 1 bản ghi của branchA
        mockMvc.perform(get("/reception/consultations/api")
                        .session(sessionFor(adminUser)).with(user(adminUser.getUsername()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/reception/consultations/api")
                        .session(sessionFor(receptionistA1)).with(user(receptionistA1.getUsername()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].branchId").value(branchA.getBranchId()));

        // Kiểm tra lọc theo trạng thái, từ khóa và phân trang
        createConsultation(branchA, "Khách Phân Trang 2", phoneSeq(3), ConsultationRequestStatus.CONTACTED, null, activeOnlineService);
        mockMvc.perform(get("/reception/consultations/api")
                        .param("status", "CONTACTED")
                        .param("keyword", "Phân Trang")
                        .param("page", "0")
                        .param("size", "1")
                        .session(sessionFor(receptionistA1)).with(user(receptionistA1.getUsername()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].guestName").value("Khách Phân Trang 2"));

        // Kiểm tra render trang /reception/consultations cho Lễ tân (200) và chặn Bác sĩ thuần túy (403) & Guest (302)
        mockMvc.perform(get("/reception/consultations"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/reception/consultations")
                        .session(sessionFor(receptionistA1)).with(user(receptionistA1.getUsername()).roles("USER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/reception/consultations")
                        .session(sessionFor(pureDoctorUser)).with(user(pureDoctorUser.getUsername()).roles("USER")))
                .andExpect(status().isForbidden());

        // Kiểm tra tĩnh JS polling 30s giữ filter/trang, single timer & không ghi đè Drawer đang mở
        String consultJs = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/static/js/reception-consultations.js"));
        assertTrue(consultJs.contains("POLL_INTERVAL_MS = 30000"));
        assertTrue(consultJs.contains("clearInterval(pollTimerId)"));
        assertTrue(consultJs.contains("buildListUrl()"));
        assertTrue(consultJs.contains("drawerStaleBanner"));
        assertFalse(consultJs.contains("resetFilters()") && consultJs.contains("pollBackground") && consultJs.indexOf("resetFilters") > consultJs.indexOf("pollBackground"));

        // Kiểm tra dashboard lịch hẹn có link & badge polling không dùng class Tailwind thập phân
        String dashHtml = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/templates/reception/appointments/dashboard.html"));
        assertTrue(dashHtml.contains("/reception/consultations"));
        assertTrue(dashHtml.contains("guestConsultationBadge"));

        String consultHtml = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/templates/reception/consultations/index.html"));
        for (String badClass : List.of("gap-1.5", "gap-2.5", "px-3.5", "py-1.5", "me-1.5", "me-0.5")) {
            assertFalse(consultHtml.contains(badClass), "Không dùng class Bootstrap 5 sai cú pháp: " + badClass);
        }
    }

    @Test
    @DisplayName("21. Nhân viên chỉ có phân công chi nhánh đã hết hạn nhận 403")
    void expiredBranchAssignmentReceives403() throws Exception {
        Role mRole = ensureRole("LE_TAN_EXP_" + uid, "Lễ tân hết hạn",
                Set.of(ensurePermission("APPOINTMENT_MANAGE", "Quản lý lịch hẹn", "RECEPTION")));
        User expiredStaff = createStaffUser("exp_" + uid, null, mRole, "Hết Hạn", null);
        StaffProfile sp = staffProfileRepository.findByUser_UserId(expiredStaff.getUserId()).orElseThrow();

        StaffBranchAssignment expired = new StaffBranchAssignment();
        expired.setStaff(sp);
        expired.setBranch(branchA);
        expired.setRole(mRole);
        expired.setIsPrimary(true);
        expired.setStartDate(futureDate.minusMonths(2));
        expired.setEndDate(LocalDate.now(AppointmentPolicyService.CLINIC_ZONE).minusDays(1));
        expired.setStatus(AssignmentStatus.ACTIVE);
        branchAssignmentRepository.saveAndFlush(expired);

        mockMvc.perform(get("/reception/consultations/api")
                        .session(sessionFor(expiredStaff)).with(user(expiredStaff.getUsername()).roles("USER")))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Test Setup Helpers
    // =========================================================================

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
            WorkShift pm = new WorkShift();
            pm.setBranch(b);
            pm.setShiftCode("PM_" + code);
            pm.setShiftName("Ca Chiều");
            pm.setStartTime(LocalTime.of(13, 30));
            pm.setEndTime(LocalTime.of(17, 30));
            pm.setIsActive(true);
            workShiftRepository.saveAndFlush(pm);
        }
        return b;
    }

    private com.cvdcms.entity.Service createService(
            ServiceCategory cat, String code, String name, ServiceStatus status, boolean bookableOnline) {
        com.cvdcms.entity.Service s = new com.cvdcms.entity.Service();
        s.setCategory(cat);
        s.setServiceCode(code);
        s.setServiceName(name);
        s.setServiceType(ServiceType.CONSULTATION);
        s.setEstimatedDurationMinutes(30);
        s.setIsBookableOnline(bookableOnline);
        s.setStatus(status);
        return serviceRepository.saveAndFlush(s);
    }

    private StaffProfile createDoctor(
            String code, String name, Role doctorRole, Branch branch, StaffStatus status, boolean withSchedule) {
        StaffProfile sp = new StaffProfile();
        sp.setStaffCode(code);
        sp.setFullName(name);
        sp.setStatus(status);
        sp.setBaseSalary(new BigDecimal("20000000"));
        sp = staffProfileRepository.saveAndFlush(sp);

        StaffBranchAssignment sba = new StaffBranchAssignment();
        sba.setStaff(sp);
        sba.setBranch(branch);
        sba.setRole(doctorRole);
        sba.setIsPrimary(true);
        sba.setStartDate(futureDate.minusMonths(1));
        sba.setStatus(status == StaffStatus.ACTIVE ? AssignmentStatus.ACTIVE : AssignmentStatus.ENDED);
        branchAssignmentRepository.saveAndFlush(sba);

        if (withSchedule) {
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

    private PatientProfile createPatientProfileWithCode(String code, String name, String phone, Branch branch) {
        User owner = new User();
        owner.setUsername("u_" + code.toLowerCase());
        owner.setPassword("secret");
        owner.setEmail(code.toLowerCase() + "@test.local");
        owner.setPhoneNumber(phoneSeq(8));
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
