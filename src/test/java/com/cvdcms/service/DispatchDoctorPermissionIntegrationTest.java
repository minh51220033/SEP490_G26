package com.cvdcms.service;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dispatch_doctor_perm_it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;NON_KEYWORDS=MONTH,YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DispatchDoctorPermissionIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private DispatchService dispatchService;
    @Autowired private BranchRepository branchRepository;
    @Autowired private RoomRepository roomRepository;
    @Autowired private DentalChairRepository chairRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffProfileRepository staffProfileRepository;
    @Autowired private StaffBranchAssignmentRepository branchAssignmentRepository;
    @Autowired private WorkShiftRepository workShiftRepository;
    @Autowired private StaffScheduleRepository staffScheduleRepository;
    @Autowired private PatientProfileRepository patientProfileRepository;
    @Autowired private VisitRepository visitRepository;
    @Autowired private ClinicalQueueTicketRepository queueTicketRepository;
    @Autowired private SystemAuditLogRepository auditLogRepository;

    private MockMvc mockMvc;

    private Branch branch1;
    private Branch branch2;
    private Room room1;
    private DentalChair chair1;
    private User leTanUser;
    private User adminUser;
    private StaffProfile doctor1Branch1;
    private StaffProfile doctor2Branch1;
    private StaffProfile doctorBranch2;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(ZONE);
        Clock fixedClock = Clock.fixed(today.atTime(10, 0).atZone(ZONE).toInstant(), ZONE);
        dispatchService.setClock(fixedClock);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        String uid = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        branch1 = createBranch("CN1-" + uid, "Chi nhánh 1 " + uid);
        branch2 = createBranch("CN2-" + uid, "Chi nhánh 2 " + uid);

        room1 = new Room();
        room1.setBranch(branch1);
        room1.setRoomCode("R1-" + uid);
        room1.setRoomName("Phòng 101");
        room1.setRoomType(RoomType.TREATMENT);
        room1.setStatus(RoomStatus.ACTIVE);
        room1 = roomRepository.saveAndFlush(room1);

        chair1 = new DentalChair();
        chair1.setBranch(branch1);
        chair1.setRoom(room1);
        chair1.setChairCode("G1-" + uid);
        chair1.setChairName("Ghế 01");
        chair1.setStatus(ChairStatus.AVAILABLE);
        chair1 = chairRepository.saveAndFlush(chair1);

        Permission dispPerm = ensurePermission("RECEPTION_DISPATCH");
        Permission checkinPerm = ensurePermission("RECEPTION_CHECKIN");
        Permission aptManagePerm = ensurePermission("APPOINTMENT_MANAGE");
        Permission aptViewPerm = ensurePermission("APPOINTMENT_VIEW");
        Permission clinicalDispPerm = ensurePermission("CLINICAL_DISPATCH");

        Role leTanRole = ensureRole("LE_TAN", List.of(dispPerm, checkinPerm, aptManagePerm, aptViewPerm));
        Role adminRole = ensureRole("ADMIN", List.of(dispPerm, checkinPerm, aptManagePerm, aptViewPerm, clinicalDispPerm));
        Role docRole = ensureRole("BAC_SI", List.of(clinicalDispPerm, aptViewPerm));

        leTanUser = createUser("letan_" + uid, branch1, leTanRole);
        createStaff(leTanUser, "LT-" + uid, "Lễ tân " + uid, branch1, leTanRole);

        adminUser = createUser("admin_" + uid, branch1, adminRole);
        createStaff(adminUser, "ADM-" + uid, "Quản trị " + uid, branch1, adminRole);

        User doc1User = createUser("doc1_" + uid, branch1, docRole);
        doctor1Branch1 = createStaff(doc1User, "BS1-" + uid, "BS. Nguyễn Một " + uid, branch1, docRole);

        User doc2User = createUser("doc2_" + uid, branch1, docRole);
        doctor2Branch1 = createStaff(doc2User, "BS2-" + uid, "BS. Trần Hai " + uid, branch1, docRole);

        User docB2User = createUser("docb2_" + uid, branch2, docRole);
        doctorBranch2 = createStaff(docB2User, "BSB2-" + uid, "BS. Chi Nhánh Hai " + uid, branch2, docRole);

        WorkShift shiftB1 = createAllDayShift(branch1, "CA1-" + uid);
        WorkShift shiftB2 = createAllDayShift(branch2, "CA2-" + uid);

        scheduleDoctor(doctor1Branch1, branch1, shiftB1);
        scheduleDoctor(doctor2Branch1, branch1, shiftB1);
        scheduleDoctor(doctorBranch2, branch2, shiftB2);
    }

    @Test
    @DisplayName("AC1: LE_TAN gửi doctorId hợp lệ bị từ chối với lỗi phân quyền và không ghi đè DB")
    void ac1_leTanWithValidDoctorIdIsRejected() throws Exception {
        Visit visit = createWaitingVisit(branch1, null, "Đau răng hàm");
        MockHttpSession leTanSession = sessionWith(leTanUser, "LE_TAN", false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> dispatchService.assign(leTanSession, branch1.getBranchId(), visit.getVisitId(),
                        doctor1Branch1.getStaffId(), room1.getRoomId(), chair1.getChairId(), null));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());

        mockMvc.perform(post("/reception/dispatch/" + visit.getVisitId() + "/assign")
                        .session(leTanSession)
                        .with(csrf())
                        .param("branchId", String.valueOf(branch1.getBranchId()))
                        .param("doctorId", String.valueOf(doctor1Branch1.getStaffId()))
                        .param("chairId", String.valueOf(chair1.getChairId()))
                        .param("issueTicket", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reception/dispatch?branchId=" + branch1.getBranchId()))
                .andExpect(flash().attributeExists("error"))
                .andExpect(flash().attribute("error",
                        org.hamcrest.Matchers.containsString("Lễ tân không được tự ý chọn hoặc đổi bác sĩ")));

        Visit persisted = visitRepository.findById(visit.getVisitId()).orElseThrow();
        assertNull(persisted.getPrimaryDoctor());
        assertTrue(queueTicketRepository.findByVisitIdAndTypeOrderByDesc(visit.getVisitId(), QueueType.EXAM).isEmpty());
    }

    @Test
    @DisplayName("AC2: LE_TAN gửi doctorId giả hoặc bác sĩ khác chi nhánh đều bị chặn 403 nhất quán")
    void ac2_leTanWithFakeOrCrossBranchDoctorIdIsRejected() throws Exception {
        Visit visit = createWaitingVisit(branch1, null, "Kiểm tra răng cửa");
        MockHttpSession leTanSession = sessionWith(leTanUser, "LE_TAN", false);

        // Fake doctorId
        ResponseStatusException fakeEx = assertThrows(ResponseStatusException.class,
                () -> dispatchService.assign(leTanSession, branch1.getBranchId(), visit.getVisitId(),
                        999999L, null, null, null));
        assertEquals(HttpStatus.FORBIDDEN, fakeEx.getStatusCode());

        mockMvc.perform(post("/reception/dispatch/" + visit.getVisitId() + "/assign")
                        .session(leTanSession)
                        .with(csrf())
                        .param("branchId", String.valueOf(branch1.getBranchId()))
                        .param("doctorId", "999999")
                        .param("issueTicket", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("error"));

        // Cross-branch doctorId
        ResponseStatusException crossEx = assertThrows(ResponseStatusException.class,
                () -> dispatchService.assign(leTanSession, branch1.getBranchId(), visit.getVisitId(),
                        doctorBranch2.getStaffId(), null, null, null));
        assertEquals(HttpStatus.FORBIDDEN, crossEx.getStatusCode());

        mockMvc.perform(post("/reception/dispatch/" + visit.getVisitId() + "/assign")
                        .session(leTanSession)
                        .with(csrf())
                        .param("branchId", String.valueOf(branch1.getBranchId()))
                        .param("doctorId", String.valueOf(doctorBranch2.getStaffId())))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("error"));

        Visit persisted = visitRepository.findById(visit.getVisitId()).orElseThrow();
        assertNull(persisted.getPrimaryDoctor());
        assertTrue(queueTicketRepository.findByVisitIdAndTypeOrderByDesc(visit.getVisitId(), QueueType.EXAM).isEmpty());
    }

    @Test
    @DisplayName("AC3 & AC5: LE_TAN không truyền doctorId (hoặc rỗng) thành công qua tự động phân bổ Least-Loaded")
    void ac3AndAc5_leTanWithoutDoctorIdAutoAssignsLeastLoadedDoctor() throws Exception {
        // Seed doctor1Branch1 with 1 waiting visit so doctor2Branch1 (load=0) is least-loaded
        createWaitingVisit(branch1, doctor1Branch1, "Ca đang chờ trước");
        Visit walkInVisit = createWaitingVisit(branch1, null, "Khám tổng quát vãng lai");
        MockHttpSession leTanSession = sessionWith(leTanUser, "LE_TAN", false);

        mockMvc.perform(post("/reception/dispatch/" + walkInVisit.getVisitId() + "/assign")
                        .session(leTanSession)
                        .with(csrf())
                        .param("branchId", String.valueOf(branch1.getBranchId()))
                        .param("doctorId", "")
                        .param("chairId", String.valueOf(chair1.getChairId()))
                        .param("issueTicket", "true")
                        .param("note", "Khách vãng lai đến sớm"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reception/dispatch?branchId=" + branch1.getBranchId()))
                .andExpect(flash().attributeExists("notice"))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.nullValue()));

        Visit persisted = visitRepository.findById(walkInVisit.getVisitId()).orElseThrow();
        assertNotNull(persisted.getPrimaryDoctor());
        assertEquals(doctor2Branch1.getStaffId(), persisted.getPrimaryDoctor().getStaffId());
        assertEquals(chair1.getChairId(), persisted.getChairId());
        assertFalse(queueTicketRepository.findByVisitIdAndTypeOrderByDesc(walkInVisit.getVisitId(), QueueType.EXAM).isEmpty());
    }

    @Test
    @DisplayName("AC4: ADMIN phân công và đổi bác sĩ thủ công hoạt động bình thường")
    void ac4_adminCanManuallyAssignAndTransferDoctor() throws Exception {
        // Seed an already-CALLED ticket for doctor1Branch1 so the new visit remains WAITING in queue for transfer
        Visit busyVisit = createWaitingVisit(branch1, doctor1Branch1, "Ca đang khám trước");
        QueueTicket busyTicket = new QueueTicket();
        busyTicket.setVisit(busyVisit);
        busyTicket.setBranch(branch1);
        busyTicket.setQueueDate(today);
        busyTicket.setQueueType(QueueType.EXAM);
        busyTicket.setTicketNumber(1);
        busyTicket.setDisplayNumber("01");
        busyTicket.setStatus(QueueStatus.CALLED);
        busyTicket.setIsPriority(false);
        busyTicket.setIsEmergency(false);
        busyTicket.setPriorityScore(50);
        busyTicket.setIssuedAt(LocalDateTime.of(today, LocalTime.of(9, 0)));
        busyTicket.setCalledAt(LocalDateTime.of(today, LocalTime.of(9, 5)));
        queueTicketRepository.saveAndFlush(busyTicket);

        Visit visit = createWaitingVisit(branch1, null, "Nhổ răng khôn");
        MockHttpSession adminSession = sessionWith(adminUser, "ADMIN", true);

        // Initial manual assignment by ADMIN
        mockMvc.perform(post("/reception/dispatch/" + visit.getVisitId() + "/assign")
                        .session(adminSession)
                        .with(csrf())
                        .param("branchId", String.valueOf(branch1.getBranchId()))
                        .param("doctorId", String.valueOf(doctor1Branch1.getStaffId()))
                        .param("chairId", String.valueOf(chair1.getChairId()))
                        .param("issueTicket", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("notice"));

        Visit assigned = visitRepository.findById(visit.getVisitId()).orElseThrow();
        assertNotNull(assigned.getPrimaryDoctor());
        assertEquals(doctor1Branch1.getStaffId(), assigned.getPrimaryDoctor().getStaffId());

        // Transfer doctor by ADMIN with valid audit reason
        mockMvc.perform(post("/reception/dispatch/" + visit.getVisitId() + "/assign")
                        .session(adminSession)
                        .with(csrf())
                        .param("branchId", String.valueOf(branch1.getBranchId()))
                        .param("doctorId", String.valueOf(doctor2Branch1.getStaffId()))
                        .param("reason", "Bệnh nhân yêu cầu đổi sang bác sĩ trực phòng bên cạnh"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("notice"));

        Visit transferred = visitRepository.findById(visit.getVisitId()).orElseThrow();
        assertEquals(doctor2Branch1.getStaffId(), transferred.getPrimaryDoctor().getStaffId());
        assertTrue(auditLogRepository.findAll().stream()
                .anyMatch(l -> "TRANSFER_DOCTOR_QUEUE".equals(l.getAction()) && visit.getVisitId().equals(l.getEntityId())));
    }

    @Test
    @DisplayName("AC7: dispatch.html ẩn chọn/đổi BS với LE_TAN, hiện thông báo tự động phân bổ, giữ nguyên cho ADMIN; JS chặn gửi doctorId")
    void ac7_dispatchUiHidesManualDoctorControlsForLeTanAndPreservesForAdmin() throws Exception {
        createWaitingVisit(branch1, doctor1Branch1, "Lượt đã có BS để kiểm tra nút Đổi BS");
        createWaitingVisit(branch1, null, "Lượt chưa có BS");

        MockHttpSession leTanSession = sessionWith(leTanUser, "LE_TAN", false);
        MvcResult leTanResult = mockMvc.perform(get("/reception/dispatch")
                        .session(leTanSession)
                        .param("branchId", String.valueOf(branch1.getBranchId())))
                .andExpect(status().isOk())
                .andExpect(view().name("reception/dispatch"))
                .andExpect(model().attribute("canAssignDoctor", false))
                .andReturn();
        String leTanHtml = leTanResult.getResponse().getContentAsString();

        assertTrue(leTanHtml.contains("data-can-manual-doctor-assign=\"false\""));
        assertTrue(leTanHtml.contains("Hệ thống tự động phân bổ bác sĩ"));
        assertFalse(leTanHtml.contains("id=\"modalDoctorSelect\""));
        assertFalse(leTanHtml.contains("btn-open-transfer"));
        assertFalse(leTanHtml.contains("id=\"dispatchTransferModal\""));
        assertTrue(leTanHtml.contains("id=\"dispatchDoctorFilter\""), "Bộ lọc xem bảng theo BS vẫn giữ cho lễ tân");
        assertTrue(leTanHtml.contains("id=\"modalChairSelect\""));

        MockHttpSession adminSession = sessionWith(adminUser, "ADMIN", true);
        MvcResult adminResult = mockMvc.perform(get("/reception/dispatch")
                        .session(adminSession)
                        .param("branchId", String.valueOf(branch1.getBranchId())))
                .andExpect(status().isOk())
                .andExpect(view().name("reception/dispatch"))
                .andExpect(model().attribute("canAssignDoctor", true))
                .andReturn();
        String adminHtml = adminResult.getResponse().getContentAsString();

        assertTrue(adminHtml.contains("data-can-manual-doctor-assign=\"true\""));
        assertTrue(adminHtml.contains("id=\"modalDoctorSelect\""));
        assertTrue(adminHtml.contains("btn-open-transfer"));
        assertTrue(adminHtml.contains("id=\"dispatchTransferModal\""));

        String dispatchJs = Files.readString(Path.of("src/main/resources/static/js/reception-dispatch.js"));
        assertTrue(dispatchJs.contains("canManualDoctorAssign"));
        assertTrue(dispatchJs.contains("sanitiseHiddenDoctorInputs"));
        assertTrue(dispatchJs.contains("if (!canManualDoctorAssign()) return;"));
    }

    private Branch createBranch(String code, String name) {
        Branch b = new Branch();
        b.setBranchCode(code);
        b.setBranchName(name);
        b.setAddress("123 Đường Test");
        b.setWorkingDays("2,3,4,5,6,7,CN");
        b.setOpeningTime(LocalTime.of(0, 0));
        b.setClosingTime(LocalTime.of(23, 59));
        b.setIsActive(true);
        return branchRepository.saveAndFlush(b);
    }

    private Permission ensurePermission(String code) {
        return permissionRepository.findByPermissionCode(code).orElseGet(() -> {
            Permission p = new Permission();
            p.setPermissionCode(code);
            p.setModule("RECEPTION");
            p.setDescription(code);
            return permissionRepository.saveAndFlush(p);
        });
    }

    private Role ensureRole(String roleName, List<Permission> perms) {
        return roleRepository.findAllWithPermissions().stream()
                .filter(r -> roleName.equalsIgnoreCase(r.getRoleName()))
                .findFirst()
                .map(r -> {
                    Set<Permission> merged = new HashSet<>(r.getPermissions() != null ? r.getPermissions() : Set.of());
                    merged.addAll(perms);
                    r.setPermissions(merged);
                    return roleRepository.saveAndFlush(r);
                })
                .orElseGet(() -> {
                    Role r = new Role();
                    r.setRoleName(roleName);
                    r.setDescription(roleName);
                    r.setIsActive(true);
                    r.setPermissions(new HashSet<>(perms));
                    return roleRepository.saveAndFlush(r);
                });
    }

    private User createUser(String username, Branch branch, Role role) {
        User u = new User();
        u.setUsername(username);
        u.setPassword("secret");
        u.setEmail(username + "@clinic.test");
        u.setPhoneNumber("09" + UUID.randomUUID().toString().replaceAll("[^0-9]", "5").substring(0, 8));
        u.setUserType(UserType.STAFF);
        u.setBranch(branch);
        u.setStatus("ACTIVE");
        u.setIsActive(true);
        u.setRoles(new HashSet<>(List.of(role)));
        return userRepository.saveAndFlush(u);
    }

    private StaffProfile createStaff(User user, String code, String fullName, Branch branch, Role role) {
        StaffProfile s = new StaffProfile();
        s.setUser(user);
        s.setStaffCode(code);
        s.setFullName(fullName);
        s.setBaseSalary(new BigDecimal("15000000"));
        s.setStatus(StaffStatus.ACTIVE);
        s = staffProfileRepository.saveAndFlush(s);

        StaffBranchAssignment sba = new StaffBranchAssignment();
        sba.setStaff(s);
        sba.setBranch(branch);
        sba.setRole(role);
        sba.setStartDate(today.minusDays(1));
        sba.setIsPrimary(true);
        sba.setStatus(AssignmentStatus.ACTIVE);
        branchAssignmentRepository.saveAndFlush(sba);
        return s;
    }

    private WorkShift createAllDayShift(Branch branch, String code) {
        WorkShift shift = new WorkShift();
        shift.setBranch(branch);
        shift.setShiftCode(code);
        shift.setShiftName("Ca cả ngày");
        shift.setStartTime(LocalTime.of(0, 0));
        shift.setEndTime(LocalTime.of(23, 59));
        shift.setIsActive(true);
        return workShiftRepository.saveAndFlush(shift);
    }

    private void scheduleDoctor(StaffProfile doctor, Branch branch, WorkShift shift) {
        StaffSchedule sch = new StaffSchedule();
        sch.setBranch(branch);
        sch.setStaff(doctor);
        sch.setShift(shift);
        sch.setWorkDate(today);
        sch.setStatus(ScheduleStatus.SCHEDULED);
        staffScheduleRepository.saveAndFlush(sch);
    }

    private Visit createWaitingVisit(Branch branch, StaffProfile primaryDoctor, String symptoms) {
        String uid = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        PatientProfile p = new PatientProfile();
        p.setPatientCode("BN-" + uid);
        p.setFullName("Bệnh nhân " + uid);
        p.setPhoneNumber("090" + UUID.randomUUID().toString().replaceAll("[^0-9]", "3").substring(0, 7));
        p.setAddress("123 Đường Test");
        p.setGender("Nam");
        p.setDob(LocalDate.of(1995, 1, 1));
        p.setPreferredBranch(branch);
        p.setStatus("ACTIVE");
        p.setDepositBalance(BigDecimal.ZERO);
        p = patientProfileRepository.saveAndFlush(p);

        Visit v = new Visit();
        v.setVisitCode("VIS-" + uid);
        v.setBranch(branch);
        v.setPatientProfile(p);
        v.setVisitType(VisitType.WALK_IN);
        v.setStatus(VisitStatus.WAITING);
        v.setPrimaryDoctor(primaryDoctor);
        v.setInitialSymptoms(symptoms);
        v.setCheckInAt(LocalDateTime.of(today, LocalTime.of(9, 45)));
        return visitRepository.saveAndFlush(v);
    }

    private MockHttpSession sessionWith(User user, String roleName, boolean isAdmin) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", user);
        session.setAttribute("roleNames", List.of(roleName));
        session.setAttribute("isAdmin", isAdmin);
        session.setAttribute("currentBranchId", branch1.getBranchId());
        return session;
    }
}
