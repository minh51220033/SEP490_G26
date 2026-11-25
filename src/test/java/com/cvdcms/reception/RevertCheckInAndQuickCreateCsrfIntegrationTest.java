package com.cvdcms.reception;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.patient.PatientRestController;
import com.cvdcms.receptionappointment.ReceptionAppointmentService;
import com.cvdcms.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Kiểm thử tích hợp cho 2 vấn đề phụ phát hiện trong Browser E2E:
 * - Vấn đề 1: Hoàn tác tiếp đón (revertCheckIn), giữ nguyên lịch sử Visit đã CANCELLED (appointmentId không bị xóa),
 *   không coi Visit CANCELLED là Visit hoạt động, trang chi tiết /reception/appointments/{id}, check-in lại lần thứ 2
 *   tạo Visit và QueueTicket mới, và chống tạo trùng khi tải lại trang / gửi lặp request.
 * - Vấn đề 2: Bảo vệ CSRF trên /patients/quick-create (cơ chế CookieCsrfTokenRepository + header/token),
 *   từ chối 403 khi thiếu hoặc sai CSRF token, và chống tạo trùng PatientProfile khi gửi request lặp (409 Conflict).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:revert_checkin_quick_create_csrf_db;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;NON_KEYWORDS=MONTH,YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RevertCheckInAndQuickCreateCsrfIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private ObjectMapper objectMapper;

    @Autowired private ReceptionService receptionService;
    @Autowired private ReceptionAppointmentService receptionAppointmentService;
    @Autowired private ReceptionVisitRepository receptionVisitRepository;
    @Autowired private DispatchTicketRepository queueTicketRepository;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private PatientProfileRepository patientProfileRepository;
    @Autowired private BranchRepository branchRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private StaffProfileRepository staffProfileRepository;
    @Autowired private StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    @Autowired private WorkShiftRepository workShiftRepository;
    @Autowired private StaffScheduleRepository staffScheduleRepository;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private MockMvc mockMvc;
    private Branch branch;
    private User receptionistUser;
    private StaffProfile doctorStaff;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        today = LocalDate.now(ZONE);

        branch = branchRepository.findAll().stream().findFirst().orElseGet(() -> {
            Branch b = new Branch();
            b.setBranchCode("CN-REVERT-01");
            b.setBranchName("Nha Khoa SmileCare Trung Tâm");
            b.setAddress("123 Nguyễn Trãi, Hà Nội");
            b.setPhone("02438889999");
            b.setOpeningTime(LocalTime.of(0, 0));
            b.setClosingTime(LocalTime.of(23, 59));
            b.setWorkingDays("2,3,4,5,6,7,CN");
            b.setIsActive(true);
            return branchRepository.saveAndFlush(b);
        });
        branch.setOpeningTime(LocalTime.of(0, 0));
        branch.setClosingTime(LocalTime.of(23, 59));
        branch = branchRepository.saveAndFlush(branch);

        Set<String> recPerms = Set.of(
                "PATIENT_PROFILE_MANAGE", "RECEPTION_MEMBERSHIPS", "APPOINTMENT_MANAGE",
                "RECEPTION_CHECKIN", "APPOINTMENT_VIEW", "RECEPTION_DISPATCH"
        );
        Set<Permission> permissions = new HashSet<>();
        for (String code : recPerms) {
            Permission p = permissionRepository.findByPermissionCodeIgnoreCase(code).orElseGet(() -> {
                Permission perm = new Permission();
                perm.setPermissionCode(code);
                perm.setModule("RECEPTION");
                perm.setDescription("Permission " + code);
                return permissionRepository.saveAndFlush(perm);
            });
            permissions.add(p);
        }

        Role receptionistRole = roleRepository.findAllWithPermissions().stream()
                .filter(r -> "LE_TAN".equalsIgnoreCase(r.getRoleName()))
                .findFirst()
                .orElseGet(() -> {
                    Role r = new Role();
                    r.setRoleName("LE_TAN");
                    r.setDescription("Lễ tân");
                    r.setIsActive(true);
                    return roleRepository.saveAndFlush(r);
                });
        receptionistRole.setPermissions(new HashSet<>(permissions));
        receptionistRole = roleRepository.saveAndFlush(receptionistRole);

        Role doctorRole = roleRepository.findAllWithPermissions().stream()
                .filter(r -> "BAC_SI".equalsIgnoreCase(r.getRoleName()))
                .findFirst()
                .orElseGet(() -> {
                    Role r = new Role();
                    r.setRoleName("BAC_SI");
                    r.setDescription("Bác sĩ");
                    r.setIsActive(true);
                    return roleRepository.saveAndFlush(r);
                });

        String recUsername = "letan.revert." + System.nanoTime();
        receptionistUser = new User();
        receptionistUser.setUsername(recUsername);
        receptionistUser.setPassword("$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5m2k7m2oYIev1q2Zk7xN5wQ1hJ7uS");
        receptionistUser.setEmail(recUsername + "@smilecare.vn");
        receptionistUser.setPhoneNumber("0961" + (System.nanoTime() % 1000000));
        receptionistUser.setStatus("ACTIVE");
        receptionistUser.setIsActive(true);
        receptionistUser.setUserType(UserType.STAFF);
        receptionistUser.setBranch(branch);
        receptionistUser.setRoles(new HashSet<>(Set.of(receptionistRole)));
        receptionistUser.setCustomPermissionsEnabled(false);
        receptionistUser = userRepository.saveAndFlush(receptionistUser);

        StaffProfile recStaff = new StaffProfile();
        recStaff.setStaffCode("LT" + (System.nanoTime() % 100000));
        recStaff.setFullName("Lễ Tân Kiểm Thử");
        recStaff.setUser(receptionistUser);
        recStaff.setStatus(StaffStatus.ACTIVE);
        recStaff = staffProfileRepository.saveAndFlush(recStaff);

        StaffBranchAssignment recAssign = new StaffBranchAssignment();
        recAssign.setStaff(recStaff);
        recAssign.setBranch(branch);
        recAssign.setRole(receptionistRole);
        recAssign.setStatus(AssignmentStatus.ACTIVE);
        recAssign.setStartDate(today.minusDays(10));
        recAssign.setIsPrimary(true);
        staffBranchAssignmentRepository.saveAndFlush(recAssign);

        String docUsername = "bs.revert." + System.nanoTime();
        User docUser = new User();
        docUser.setUsername(docUsername);
        docUser.setPassword("$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5m2k7m2oYIev1q2Zk7xN5wQ1hJ7uS");
        docUser.setEmail(docUsername + "@smilecare.vn");
        docUser.setPhoneNumber("0972" + (System.nanoTime() % 1000000));
        docUser.setStatus("ACTIVE");
        docUser.setIsActive(true);
        docUser.setUserType(UserType.STAFF);
        docUser.setBranch(branch);
        docUser.setRoles(new HashSet<>(Set.of(doctorRole)));
        docUser = userRepository.saveAndFlush(docUser);

        doctorStaff = new StaffProfile();
        doctorStaff.setStaffCode("BS" + (System.nanoTime() % 100000));
        doctorStaff.setFullName("BS. Trần Kiểm Thử");
        doctorStaff.setUser(docUser);
        doctorStaff.setStatus(StaffStatus.ACTIVE);
        doctorStaff = staffProfileRepository.saveAndFlush(doctorStaff);

        StaffBranchAssignment docAssign = new StaffBranchAssignment();
        docAssign.setStaff(doctorStaff);
        docAssign.setBranch(branch);
        docAssign.setRole(doctorRole);
        docAssign.setStatus(AssignmentStatus.ACTIVE);
        docAssign.setStartDate(today.minusDays(10));
        docAssign.setIsPrimary(true);
        staffBranchAssignmentRepository.saveAndFlush(docAssign);

        WorkShift fullDayShift = workShiftRepository.findAll().stream().findFirst().orElseGet(() -> {
            WorkShift ws = new WorkShift();
            ws.setBranch(branch);
            ws.setShiftCode("CA-FULL");
            ws.setShiftName("Ca Toàn Thời Gian");
            ws.setStartTime(LocalTime.of(0, 0));
            ws.setEndTime(LocalTime.of(23, 59));
            return workShiftRepository.saveAndFlush(ws);
        });

        StaffSchedule schedule = new StaffSchedule();
        schedule.setStaff(doctorStaff);
        schedule.setBranch(branch);
        schedule.setShift(fullDayShift);
        schedule.setWorkDate(today);
        schedule.setStatus(ScheduleStatus.SCHEDULED);
        staffScheduleRepository.saveAndFlush(schedule);
    }

    /**
     * ObjectMapper test context không đăng ký module JSR-310 cho LocalDate, nên tự chuyển payload sang Map
     * với dob dạng chuỗi ISO (yyyy-MM-dd) – đúng định dạng mà modal #addPatientModal gửi lên.
     */
    private String toJson(PatientRestController.QuickPatientRequest req) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", req.fullName());
        body.put("phoneNumber", req.phoneNumber());
        body.put("dob", req.dob() != null ? req.dob().toString() : null);
        body.put("gender", req.gender());
        body.put("address", req.address());
        body.put("customerSource", req.customerSource());
        body.put("referralSource", req.referralSource());
        body.put("branchId", req.branchId());
        body.put("branchName", req.branchName());
        body.put("allergyDetails", req.allergyDetails());
        body.put("underlyingDiseaseDetails", req.underlyingDiseaseDetails());
        return objectMapper.writeValueAsString(body);
    }

    private MockHttpSession newReceptionSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", receptionistUser);
        session.setAttribute("currentBranchId", branch.getBranchId());
        return session;
    }

    private PreferredSession currentPreferredSession() {
        LocalTime now = LocalTime.now(ZONE);
        if (now.isBefore(LocalTime.of(12, 0))) return PreferredSession.MORNING;
        if (now.isBefore(LocalTime.of(17, 0))) return PreferredSession.AFTERNOON;
        return PreferredSession.EVENING;
    }

    private PatientProfile createTestPatient(String fullName, String phone) {
        PatientProfile p = new PatientProfile();
        p.setPatientCode("BN" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
        p.setFullName(fullName);
        p.setPhoneNumber(phone);
        p.setDob(LocalDate.of(1995, 5, 15));
        p.setGender("Nam");
        p.setAddress("Hà Nội");
        p.setPreferredBranch(branch);
        p.setStatus("ACTIVE");
        p.setDepositBalance(BigDecimal.ZERO);
        return patientProfileRepository.saveAndFlush(p);
    }

    private Appointment createConfirmedAppointmentForToday(PatientProfile patient) {
        Appointment a = new Appointment();
        a.setAppointmentCode("LH" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase());
        a.setBranch(branch);
        a.setPatientProfile(patient);
        a.setDoctor(doctorStaff);
        a.setAppointmentDate(today);
        a.setPreferredSession(currentPreferredSession());
        a.setExpectedTime(LocalTime.now(ZONE).withSecond(0).withNano(0));
        a.setStatus(AppointmentStatus.CONFIRMED);
        a.setInitialSymptoms("Khám tổng quát và lấy cao răng");
        return appointmentRepository.saveAndFlush(a);
    }

    // =========================================================================
    // VẤN ĐỀ 1: HOÀN TÁC TIẾP ĐÓN (revertCheckIn) & CHECK-IN LẦN 2
    // =========================================================================

    @Test
    @DisplayName("Vấn đề 1: Check-in lần đầu -> Hoàn tác tiếp đón (/reception/appointments/{id}/revert) -> Mở trang chi tiết -> Check-in lần 2 -> Chống tạo trùng")
    void checkInRevertDetailAndSecondCheckInMaintainConsistentHistoryAndNoDuplicates() throws Exception {
        MockHttpSession session = newReceptionSession();
        PatientProfile patient = createTestPatient("Nguyễn Văn Hoàn Tác", "0918273645");
        Appointment appt = createConfirmedAppointmentForToday(patient);
        Long apptId = appt.getAppointmentId();

        // 1. Check-in lần đầu
        String reqKey1 = UUID.randomUUID().toString();
        ReceptionService.VisitResult firstCheckIn = receptionService.checkInAppointment(
                session, apptId, "Khám răng hàm dưới bên phải", reqKey1);
        assertNotNull(firstCheckIn);
        Long firstVisitId = firstCheckIn.id();

        Appointment afterFirstCheckIn = appointmentRepository.findById(apptId).orElseThrow();
        assertEquals(AppointmentStatus.CHECKED_IN, afterFirstCheckIn.getStatus());

        Visit firstVisit = receptionVisitRepository.findById(firstVisitId).orElseThrow();
        assertEquals(VisitStatus.WAITING, firstVisit.getStatus());
        assertEquals(apptId, firstVisit.getAppointmentId());

        List<QueueTicket> firstTickets = queueTicketRepository.findAllByVisitAndType(firstVisitId, QueueType.EXAM);
        assertEquals(1, firstTickets.size(), "Phải tạo đúng 1 QueueTicket cho lần check-in đầu tiên");
        // Bác sĩ đang rảnh có thể được hàng chờ tự gọi ngay (CALLED) theo nghiệp vụ hiện có; cả hai đều là vé đang hoạt động.
        assertTrue(Set.of(QueueStatus.WAITING, QueueStatus.CALLED).contains(firstTickets.get(0).getStatus()),
                "QueueTicket lần đầu phải ở trạng thái hoạt động (WAITING/CALLED), thực tế: " + firstTickets.get(0).getStatus());

        // 2. Hoàn tác tiếp đón qua endpoint POST /reception/appointments/{id}/revert
        mockMvc.perform(post("/reception/appointments/" + apptId + "/revert")
                        .session(session)
                        .with(csrf())
                        .param("branchId", String.valueOf(branch.getBranchId()))
                        .param("returnUrl", "/reception/appointments/" + apptId))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reception/appointments/" + apptId))
                .andExpect(flash().attributeExists("notice"));

        // Kiểm tra sau hoàn tác:
        // - Appointment quay về CONFIRMED
        // - Visit #1 chuyển sang CANCELLED nhưng VẪN GIỮ appointmentId (không xóa/ghi đè lịch sử Visit cũ)
        // - QueueTicket #1 chuyển sang CANCELLED
        // - Truy vấn Visit hoạt động findByAppointmentId trả về Optional.empty()
        Appointment afterRevert = appointmentRepository.findById(apptId).orElseThrow();
        assertEquals(AppointmentStatus.CONFIRMED, afterRevert.getStatus());

        Visit cancelledVisit = receptionVisitRepository.findById(firstVisitId).orElseThrow();
        assertEquals(VisitStatus.CANCELLED, cancelledVisit.getStatus());
        assertEquals(apptId, cancelledVisit.getAppointmentId(),
                "Visit cũ đã CANCELLED phải giữ nguyên appointmentId để bảo toàn lịch sử");

        List<QueueTicket> cancelledTickets = queueTicketRepository.findAllByVisitAndType(firstVisitId, QueueType.EXAM);
        assertEquals(1, cancelledTickets.size());
        assertEquals(QueueStatus.CANCELLED, cancelledTickets.get(0).getStatus());

        // findByAppointmentId dùng PESSIMISTIC_WRITE nên phải gọi trong transaction như production
        Optional<Visit> activeAfterRevert = new TransactionTemplate(transactionManager)
                .execute(tx -> receptionVisitRepository.findByAppointmentId(apptId));
        assertTrue(activeAfterRevert != null && activeAfterRevert.isEmpty(),
                "Không được lấy Visit đã CANCELLED làm Visit đang hoạt động");

        // Dashboard tiếp đón không được hiển thị Visit đã CANCELLED trong danh sách lượt khám hoạt động
        ReceptionService.Dashboard dashAfterRevert = receptionService.dashboard(
                session, branch.getBranchId(), null, today);
        assertTrue(dashAfterRevert.visits().stream().noneMatch(v -> v.id().equals(firstVisitId)),
                "Dashboard không được liệt kê Visit đã CANCELLED là lượt khám đang hoạt động");

        // 3. Mở trang chi tiết /reception/appointments/{id} sau khi hoàn tác
        mockMvc.perform(get("/reception/appointments/" + apptId).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("reception/appointments/detail"));

        var detailAfterRevert = receptionAppointmentService.detail(session, apptId);
        assertEquals(AppointmentStatus.CONFIRMED, detailAfterRevert.appointment().status());

        // 4. Check-in lại lần thứ hai -> phải tạo đúng Visit mới và QueueTicket mới
        String reqKey2 = UUID.randomUUID().toString();
        ReceptionService.VisitResult secondCheckIn = receptionService.checkInAppointment(
                session, apptId, "Khám lại sau khi hoàn tác tiếp đón", reqKey2);
        assertNotNull(secondCheckIn);
        Long secondVisitId = secondCheckIn.id();
        assertNotEquals(firstVisitId, secondVisitId, "Check-in lần 2 sau hoàn tác phải tạo Visit mới");

        // 5. Gửi lặp request với cùng requestKey hoặc gọi checkInAppointment khi đã có Visit hoạt động -> Idempotent, không tạo trùng
        ReceptionService.VisitResult duplicateBySameKey = receptionService.checkInAppointment(
                session, apptId, "Khám lại sau khi hoàn tác tiếp đón", reqKey2);
        assertEquals(secondVisitId, duplicateBySameKey.id(), "Request lặp cùng requestKey phải trả về đúng Visit mới đang hoạt động");

        ReceptionService.VisitResult duplicateByNewKey = receptionService.checkInAppointment(
                session, apptId, "Khám lại sau khi hoàn tác tiếp đón", UUID.randomUUID().toString());
        assertEquals(secondVisitId, duplicateByNewKey.id(), "Request lặp khác requestKey khi đã CHECKED_IN không được tạo thêm Visit thứ 3");

        // Mở lại trang chi tiết /reception/appointments/{id} khi có cả Visit cũ (CANCELLED) và Visit mới (WAITING)
        mockMvc.perform(get("/reception/appointments/" + apptId).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("reception/appointments/detail"));
        var detailAfterSecondCheckIn = receptionAppointmentService.detail(session, apptId);
        assertEquals(AppointmentStatus.CHECKED_IN, detailAfterSecondCheckIn.appointment().status());

        // 6. Kiểm tra DB: Tổng cộng đúng 2 Visit gắn với appointmentId (1 CANCELLED lịch sử + 1 WAITING đang hoạt động)
        List<Visit> allVisitsForAppt = receptionVisitRepository.findAllByAppointmentIdOrderByVisitIdDesc(apptId);
        assertEquals(2, allVisitsForAppt.size(), "DB phải lưu đúng 1 Visit CANCELLED lịch sử và 1 Visit WAITING mới");

        List<Visit> activeVisitsForAppt = allVisitsForAppt.stream()
                .filter(v -> v.getStatus() != VisitStatus.CANCELLED)
                .toList();
        assertEquals(1, activeVisitsForAppt.size(), "Chỉ được tồn tại duy nhất 1 Visit đang hoạt động");
        assertEquals(secondVisitId, activeVisitsForAppt.get(0).getVisitId());

        List<QueueTicket> secondVisitTickets = queueTicketRepository.findAllByVisitAndType(secondVisitId, QueueType.EXAM);
        assertEquals(1, secondVisitTickets.size(), "Visit mới chỉ được có đúng 1 QueueTicket");
        assertTrue(Set.of(QueueStatus.WAITING, QueueStatus.CALLED).contains(secondVisitTickets.get(0).getStatus()),
                "QueueTicket của Visit mới phải ở trạng thái hoạt động (WAITING/CALLED), thực tế: " + secondVisitTickets.get(0).getStatus());

        // Dashboard chỉ hiển thị Visit mới (#2), không hiển thị Visit cũ (#1)
        ReceptionService.Dashboard dashAfterSecondCheckIn = receptionService.dashboard(
                session, branch.getBranchId(), null, today);
        assertTrue(dashAfterSecondCheckIn.visits().stream().anyMatch(v -> v.id().equals(secondVisitId)));
        assertTrue(dashAfterSecondCheckIn.visits().stream().noneMatch(v -> v.id().equals(firstVisitId)));
    }

    // =========================================================================
    // VẤN ĐỀ 2: CSRF & CHỐNG TẠO TRÙNG TRONG MODAL TẠO BỆNH NHÂN (/patients/quick-create)
    // =========================================================================

    @Test
    @DisplayName("Vấn đề 2.1: Tạo bệnh nhân hợp lệ khi có CSRF token -> 201 Created")
    void quickCreatePatientWithValidCsrfTokenSucceeds() throws Exception {
        MockHttpSession session = newReceptionSession();
        String phone = "0945112233";
        PatientRestController.QuickPatientRequest payload = new PatientRestController.QuickPatientRequest(
                "Lê Thị Hợp Lệ CSRF",
                phone,
                LocalDate.of(1997, 8, 20),
                "Nữ",
                "Cầu Giấy, Hà Nội",
                "Vãng lai",
                "Vãng lai",
                branch.getBranchId(),
                branch.getBranchName(),
                null,
                null
        );

        mockMvc.perform(post("/patients/quick-create")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(toJson(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fullName").value("Lê Thị Hợp Lệ CSRF"))
                .andExpect(jsonPath("$.phoneNumber").value(phone))
                .andExpect(jsonPath("$.code").isNotEmpty());
    }

    @Test
    @DisplayName("Vấn đề 2.2: Tạo bệnh nhân khi thiếu CSRF token -> Bị từ chối 403 Forbidden")
    void quickCreatePatientWithoutCsrfTokenIsRejectedWith403() throws Exception {
        MockHttpSession session = newReceptionSession();
        String phone = "0945998877";
        PatientRestController.QuickPatientRequest payload = new PatientRestController.QuickPatientRequest(
                "Trần Văn Thiếu CSRF",
                phone,
                LocalDate.of(1992, 3, 10),
                "Nam",
                "Đống Đa, Hà Nội",
                "Vãng lai",
                null,
                branch.getBranchId(),
                null,
                null,
                null
        );

        mockMvc.perform(post("/patients/quick-create")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(toJson(payload)))
                .andExpect(status().isForbidden());

        long count = patientProfileRepository.findAll().stream()
                .filter(p -> phone.equals(p.getPhoneNumber()))
                .count();
        assertEquals(0, count, "Không được tạo hồ sơ bệnh nhân khi thiếu CSRF token");
    }

    @Test
    @DisplayName("Vấn đề 2.3: Tạo bệnh nhân khi CSRF token sai/hết hạn -> Bị từ chối 403 Forbidden")
    void quickCreatePatientWithInvalidCsrfTokenIsRejectedWith403() throws Exception {
        MockHttpSession session = newReceptionSession();
        String phone = "0945665544";
        PatientRestController.QuickPatientRequest payload = new PatientRestController.QuickPatientRequest(
                "Phạm Thị Sai CSRF",
                phone,
                LocalDate.of(1994, 11, 5),
                "Nữ",
                "Ba Đình, Hà Nội",
                "Vãng lai",
                null,
                branch.getBranchId(),
                null,
                null,
                null
        );

        mockMvc.perform(post("/patients/quick-create")
                        .session(session)
                        .cookie(new Cookie("XSRF-TOKEN", "expected-valid-token-123"))
                        .header("X-XSRF-TOKEN", "wrong-tampered-token-999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(toJson(payload)))
                .andExpect(status().isForbidden());

        long count = patientProfileRepository.findAll().stream()
                .filter(p -> phone.equals(p.getPhoneNumber()))
                .count();
        assertEquals(0, count, "Không được tạo hồ sơ bệnh nhân khi CSRF token không hợp lệ");
    }

    @Test
    @DisplayName("Vấn đề 2.4: Không tạo trùng PatientProfile khi gửi request lặp (409 Conflict)")
    void quickCreatePatientRejectsDuplicateRepeatedRequestWith409() throws Exception {
        MockHttpSession session = newReceptionSession();
        String phone = "0945334455";
        PatientRestController.QuickPatientRequest payload = new PatientRestController.QuickPatientRequest(
                "Hoàng Văn Chống Trùng",
                phone,
                LocalDate.of(1991, 6, 18),
                "Nam",
                "Thanh Xuân, Hà Nội",
                "Vãng lai",
                "Vãng lai",
                branch.getBranchId(),
                branch.getBranchName(),
                null,
                null
        );

        // Request lần 1 -> 201 Created
        mockMvc.perform(post("/patients/quick-create")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(toJson(payload)))
                .andExpect(status().isCreated());

        // Request lặp lần 2 cùng số điện thoại -> 409 Conflict
        mockMvc.perform(post("/patients/quick-create")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(toJson(payload)))
                .andExpect(status().isConflict());

        long count = patientProfileRepository.findAll().stream()
                .filter(p -> phone.equals(p.getPhoneNumber()))
                .count();
        assertEquals(1, count, "DB chỉ được có duy nhất 1 PatientProfile cho số điện thoại khi gửi request lặp");
    }

    // =========================================================================
    // MỐC ĐẾN TRỄ (+16, +30, +31 PHÚT) VỚI LỊCH HẸN CÓ expectedTime CỤ THỂ
    // =========================================================================

    @Test
    @DisplayName("Mốc đến trễ với expectedTime cụ thể: +16p -> LATE_BOOKING, +30p -> LATE_BOOKING, +31p -> chặn NO_SHOW (409)")
    void lateBookingAndNoShowCutoffAt16And30And31MinutesWithSpecificExpectedTime() {
        MockHttpSession session = newReceptionSession();
        LocalTime fixedExpectedTime = LocalTime.of(10, 0);

        try {
            // 1. Mốc +16 phút (10:16 cho hẹn 10:00) -> vẫn cho check-in nhưng LATE_BOOKING (isBookedAndOnTime = false)
            receptionService.setClock(Clock.fixed(
                    LocalDateTime.of(today, LocalTime.of(10, 16)).atZone(ZONE).toInstant(), ZONE));
            PatientProfile p16 = createTestPatient("BN Trễ 16 Phút", "0945160016");
            Appointment appt16 = new Appointment();
            appt16.setAppointmentCode("LH16M" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
            appt16.setBranch(branch);
            appt16.setPatientProfile(p16);
            appt16.setDoctor(doctorStaff);
            appt16.setAppointmentDate(today);
            appt16.setPreferredSession(PreferredSession.MORNING);
            appt16.setExpectedTime(fixedExpectedTime);
            appt16.setStatus(AppointmentStatus.CONFIRMED);
            appt16.setInitialSymptoms("Kiểm thử mốc trễ 16 phút");
            appt16 = appointmentRepository.saveAndFlush(appt16);

            ReceptionService.VisitResult res16 = receptionService.checkInAppointment(
                    session, appt16.getAppointmentId(), "Đến trễ 16 phút", UUID.randomUUID().toString());
            Visit visit16 = receptionVisitRepository.findById(res16.id()).orElseThrow();
            assertFalse(Boolean.TRUE.equals(visit16.getIsBookedAndOnTime()),
                    "Mốc +16 phút không được tính là đúng giờ (isBookedAndOnTime phải false)");
            List<QueueTicket> tickets16 = queueTicketRepository.findAllByVisitAndType(visit16.getVisitId(), QueueType.EXAM);
            assertEquals(1, tickets16.size());
            assertTrue(tickets16.get(0).getPriorityReason() != null
                            && tickets16.get(0).getPriorityReason().contains("LATE_BOOKING"),
                    "Mốc +16 phút phải gắn nhãn LATE_BOOKING, thực tế: " + tickets16.get(0).getPriorityReason());

            // 2. Mốc +30 phút (10:30 cho hẹn 10:00) -> biên cuối vẫn cho check-in dạng LATE_BOOKING
            receptionService.setClock(Clock.fixed(
                    LocalDateTime.of(today, LocalTime.of(10, 30)).atZone(ZONE).toInstant(), ZONE));
            PatientProfile p30 = createTestPatient("BN Trễ 30 Phút", "0945300030");
            Appointment appt30 = new Appointment();
            appt30.setAppointmentCode("LH30M" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
            appt30.setBranch(branch);
            appt30.setPatientProfile(p30);
            appt30.setDoctor(doctorStaff);
            appt30.setAppointmentDate(today);
            appt30.setPreferredSession(PreferredSession.MORNING);
            appt30.setExpectedTime(fixedExpectedTime);
            appt30.setStatus(AppointmentStatus.CONFIRMED);
            appt30.setInitialSymptoms("Kiểm thử mốc trễ 30 phút");
            appt30 = appointmentRepository.saveAndFlush(appt30);

            ReceptionService.VisitResult res30 = receptionService.checkInAppointment(
                    session, appt30.getAppointmentId(), "Đến trễ 30 phút", UUID.randomUUID().toString());
            Visit visit30 = receptionVisitRepository.findById(res30.id()).orElseThrow();
            assertFalse(Boolean.TRUE.equals(visit30.getIsBookedAndOnTime()),
                    "Mốc +30 phút không được tính là đúng giờ");
            List<QueueTicket> tickets30 = queueTicketRepository.findAllByVisitAndType(visit30.getVisitId(), QueueType.EXAM);
            assertEquals(1, tickets30.size());
            assertTrue(tickets30.get(0).getPriorityReason() != null
                            && tickets30.get(0).getPriorityReason().contains("LATE_BOOKING"),
                    "Mốc +30 phút phải gắn nhãn LATE_BOOKING, thực tế: " + tickets30.get(0).getPriorityReason());

            // 3. Mốc +31 phút (10:31 cho hẹn 10:00) -> bị chặn 409 CONFLICT (quá 30 phút NO_SHOW), không tạo Visit
            receptionService.setClock(Clock.fixed(
                    LocalDateTime.of(today, LocalTime.of(10, 31)).atZone(ZONE).toInstant(), ZONE));
            PatientProfile p31 = createTestPatient("BN Quá Hạn 31 Phút", "0945310031");
            Appointment appt31 = new Appointment();
            appt31.setAppointmentCode("LH31M" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
            appt31.setBranch(branch);
            appt31.setPatientProfile(p31);
            appt31.setDoctor(doctorStaff);
            appt31.setAppointmentDate(today);
            appt31.setPreferredSession(PreferredSession.MORNING);
            appt31.setExpectedTime(fixedExpectedTime);
            appt31.setStatus(AppointmentStatus.CONFIRMED);
            appt31.setInitialSymptoms("Kiểm thử mốc quá hạn 31 phút");
            final Long appt31Id = appointmentRepository.saveAndFlush(appt31).getAppointmentId();

            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                    receptionService.checkInAppointment(session, appt31Id, "Đến trễ 31 phút", UUID.randomUUID().toString()));
            assertEquals(409, ex.getStatusCode().value());
            assertTrue(ex.getReason() != null && ex.getReason().contains("NO_SHOW"),
                    "Thông báo chặn mốc +31 phút phải nêu rõ NO_SHOW, thực tế: " + ex.getReason());
            assertTrue(receptionVisitRepository.findAllByAppointmentIdOrderByVisitIdDesc(appt31Id).isEmpty(),
                    "Mốc +31 phút bị chặn không được tạo Visit mới");
        } finally {
            receptionService.setClock(Clock.system(ZONE));
        }
    }
}
