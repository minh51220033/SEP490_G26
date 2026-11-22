package com.cvdcms.service;

import com.cvdcms.controller.PublicBookingRestController;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.patientportal.AccountPatientProfile;
import com.cvdcms.patientportal.AccountPatientProfileRepository;
import com.cvdcms.repository.*;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Kiểm thử tích hợp Đợt 2 – Guest Booking (P0):
 * Chạy trên Spring Security Filter Chain + Spring Data JPA + H2 MODE=MySQL + Transaction thật.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:public_booking_guest_sec;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;NON_KEYWORDS=MONTH,YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never",
        "booking.public.rate-limit.ip-max-requests=5",
        "booking.public.rate-limit.ip-window-seconds=60",
        "booking.public.rate-limit.session-max-requests=5",
        "booking.public.rate-limit.session-window-seconds=60",
        "booking.public.rate-limit.phone-max-requests=3",
        "booking.public.rate-limit.phone-window-seconds=600"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PublicBookingGuestSecurityIntegrationTest {

    @TestConfiguration
    static class RollbackProbeConfig {
        static final AtomicBoolean FAIL_AFTER_CONSULTATION_SAVE = new AtomicBoolean(false);

        @Bean
        ConsultationFailureProbe consultationFailureProbe() {
            return new ConsultationFailureProbe();
        }
    }

    static class ConsultationFailureProbe {
        @EventListener
        public void onConsultationCreated(PublicConsultationService.ConsultationCreatedEvent event) {
            if (RollbackProbeConfig.FAIL_AFTER_CONSULTATION_SAVE.get()) {
                throw new IllegalStateException("Simulated post-persist failure inside transaction");
            }
        }
    }

    @Autowired private WebApplicationContext webContext;
    @Autowired private BranchRepository branchRepository;
    @Autowired private ServiceCategoryRepository serviceCategoryRepository;
    @Autowired private ServiceRepository serviceRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PatientProfileRepository patientProfileRepository;
    @Autowired private AccountPatientProfileRepository accountPatientProfileRepository;
    @Autowired private StaffProfileRepository staffProfileRepository;
    @Autowired private StaffBranchAssignmentRepository branchAssignmentRepository;
    @Autowired private WorkShiftRepository workShiftRepository;
    @Autowired private StaffScheduleRepository staffScheduleRepository;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private AppointmentHistoryRepository appointmentHistoryRepository;
    @Autowired private BookingConsultationRequestRepository consultationRepository;
    @Autowired private AppointmentPolicyService appointmentPolicyService;
    @Autowired private AppointmentSlotService appointmentSlotService;
    @Autowired private PublicBookingRateLimiter rateLimiter;
    @Autowired private PublicConsultationService consultationService;
    @Autowired private EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    private Branch activeBranch;
    private Branch inactiveBranch;
    private com.cvdcms.entity.Service activeOnlineService;
    private com.cvdcms.entity.Service inactiveService;
    private com.cvdcms.entity.Service offlineOnlyService;
    private StaffProfile activeDoctor;
    private User existingUser;
    private PatientProfile existingProfile;
    private User foreignUser;
    private PatientProfile foreignProfile;
    private LocalDate futureDate;
    private String uid;

    @BeforeEach
    void setUp() {
        RollbackProbeConfig.FAIL_AFTER_CONSULTATION_SAVE.set(false);
        rateLimiter.clear();
        consultationRepository.deleteAll();
        appointmentHistoryRepository.deleteAll();
        appointmentRepository.deleteAll();

        mockMvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        uid = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        futureDate = LocalDate.now(AppointmentPolicyService.CLINIC_ZONE).plusDays(5);

        Role patientRole = roleRepository.findByRoleName("BENH_NHAN").orElseGet(() -> {
            Role r = new Role();
            r.setRoleName("BENH_NHAN");
            r.setDisplayName("Bệnh nhân");
            r.setIsActive(true);
            return roleRepository.saveAndFlush(r);
        });
        Role doctorRole = roleRepository.findByRoleName("BAC_SI").orElseGet(() -> {
            Role r = new Role();
            r.setRoleName("BAC_SI");
            r.setDisplayName("Bác sĩ");
            r.setIsActive(true);
            return roleRepository.saveAndFlush(r);
        });

        activeBranch = createBranch("B1_" + uid, "Cơ sở Quận 1 " + uid, true);
        inactiveBranch = createBranch("B0_" + uid, "Cơ sở Ngừng " + uid, false);

        ServiceCategory cat = new ServiceCategory();
        cat.setCategoryCode("CAT_" + uid);
        cat.setCategoryName("Khám tổng quát " + uid);
        cat.setCategoryType(ServiceType.CONSULTATION);
        cat.setIsActive(true);
        cat = serviceCategoryRepository.saveAndFlush(cat);

        activeOnlineService = createService(cat, "SVC1_" + uid, "Khám tư vấn", ServiceStatus.ACTIVE, true);
        inactiveService = createService(cat, "SVC2_" + uid, "Dịch vụ ngừng", ServiceStatus.INACTIVE, true);
        offlineOnlyService = createService(cat, "SVC3_" + uid, "Dịch vụ nội bộ", ServiceStatus.ACTIVE, false);

        activeDoctor = createDoctorWithSchedule("DR_" + uid, "BS. Trần Văn Minh", doctorRole, activeBranch);

        String existingPhone = uniquePhone(1);
        existingUser = createPatientUser("pat1_" + uid, existingPhone, activeBranch, patientRole);
        existingProfile = createPatientProfile(existingUser, "BN1" + uid, "Nguyễn Văn Tồn Tại", existingPhone, activeBranch, true);

        String foreignPhone = uniquePhone(2);
        foreignUser = createPatientUser("pat2_" + uid, foreignPhone, activeBranch, patientRole);
        foreignProfile = createPatientProfile(foreignUser, "BN2" + uid, "Lê Thị Người Khác", foreignPhone, activeBranch, true);
    }

    private String uniquePhone(int prefixDigit) {
        int hash = Math.abs((uid + prefixDigit + System.nanoTime()).hashCode()) % 10_000_000;
        return "09" + prefixDigit + String.format("%07d", hash);
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

    private StaffProfile createDoctorWithSchedule(String code, String name, Role doctorRole, Branch branch) {
        User u = new User();
        u.setUsername("u_" + code);
        u.setPassword("secret");
        u.setEmail("u_" + code + "@test.local");
        u.setPhoneNumber(uniquePhone(3));
        u.setUserType(UserType.STAFF);
        u.setIsActive(true);
        u.setStatus("ACTIVE");
        u.setBranch(branch);
        u = userRepository.saveAndFlush(u);

        StaffProfile sp = new StaffProfile();
        sp.setUser(u);
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

    private User createPatientUser(String username, String phone, Branch branch, Role patientRole) {
        User u = new User();
        u.setUsername(username);
        u.setPassword("secret");
        u.setEmail(username + "@test.local");
        u.setPhoneNumber(phone);
        u.setUserType(UserType.PATIENT);
        u.setIsActive(true);
        u.setStatus("ACTIVE");
        u.setBranch(branch);
        u.setRoles(Set.of(patientRole));
        return userRepository.saveAndFlush(u);
    }

    private PatientProfile createPatientProfile(User owner, String code, String fullName, String phone, Branch branch, boolean isDefault) {
        PatientProfile p = new PatientProfile();
        p.setUser(owner);
        p.setPatientCode(code);
        p.setFullName(fullName);
        p.setGender("Nam");
        p.setPhoneNumber(phone);
        p.setAddress("TP.HCM");
        p.setPreferredBranch(branch);
        p.setIsDefault(isDefault);
        p.setStatus("ACTIVE");
        p = patientProfileRepository.saveAndFlush(p);

        AccountPatientProfile link = new AccountPatientProfile();
        link.setAccount(owner);
        link.setProfile(p);
        link.setAccessType(isDefault ? AccountPatientProfile.AccessType.SELF : AccountPatientProfile.AccessType.DEPENDENT);
        accountPatientProfileRepository.saveAndFlush(link);
        return p;
    }

    private Map<String, Object> guestPayload(String name, String phone, String requestKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("branchId", activeBranch.getBranchId());
        payload.put("serviceId", activeOnlineService.getServiceId());
        payload.put("doctorId", activeDoctor.getStaffId());
        payload.put("appointmentDate", futureDate.toString());
        payload.put("preferredSession", "AFTERNOON");
        payload.put("slotTime", "15:30");
        payload.put("fullName", name);
        payload.put("phoneNumber", phone);
        payload.put("note", "Đau răng nhẹ");
        payload.put("requestKey", requestKey);
        return payload;
    }

    // =========================================================================
    // 1. Guest có số điện thoại đã tồn tại
    // =========================================================================
    @Test
    @DisplayName("1. Guest dùng SĐT đã tồn tại: không gắn hồ sơ, không tạo Appointment/User/Consultation, trả thông báo trung lập")
    void guestWithExistingPhoneDoesNotAttachProfileOrCreateAppointmentAndHidesPii() throws Exception {
        long usersBefore = userRepository.count();
        long profilesBefore = patientProfileRepository.count();

        MvcResult result = mockMvc.perform(post("/api/public/booking")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                guestPayload("Kẻ Dò Thông Tin", existingProfile.getPhoneNumber(), UUID.randomUUID().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.consultationMode").value(true))
                .andExpect(jsonPath("$.appointmentId").doesNotExist())
                .andExpect(jsonPath("$.appointmentCode").doesNotExist())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains(existingProfile.getFullName()));
        assertFalse(body.contains(activeBranch.getBranchName()));
        assertFalse(body.contains(existingProfile.getPatientCode()));
        assertEquals(usersBefore, userRepository.count());
        assertEquals(profilesBefore, patientProfileRepository.count());
        assertEquals(0, appointmentRepository.count());
        assertEquals(0, consultationRepository.count());
    }

    // =========================================================================
    // 2. Guest có số điện thoại mới (bao gồm chuẩn hóa +84)
    // =========================================================================
    @Test
    @DisplayName("2. Guest dùng SĐT mới: tạo yêu cầu tư vấn NEW trong booking_consultation_requests, không tạo User/PatientProfile/Appointment")
    void guestWithNewPhoneCreatesOnlyConsultationRequest() throws Exception {
        long usersBefore = userRepository.count();
        long profilesBefore = patientProfileRepository.count();
        String newPhoneNormalized = uniquePhone(4);
        String newPhoneWith84 = "+84 " + newPhoneNormalized.substring(1, 4) + "-" + newPhoneNormalized.substring(4);
        String reqKey = UUID.randomUUID().toString();

        MvcResult guestNewResult = mockMvc.perform(post("/api/public/booking")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                guestPayload("Khách Mới Tư Vấn", newPhoneWith84, reqKey))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.consultationMode").value(true))
                .andExpect(jsonPath("$.appointmentId").doesNotExist())
                .andReturn();

        // Response của SĐT mới phải hoàn toàn giống cấu trúc & message của SĐT đã tồn tại (anti-enumeration)
        assertEquals(PublicConsultationService.NEUTRAL_GUEST_RESPONSE_MESSAGE,
                objectMapper.readTree(guestNewResult.getResponse().getContentAsString()).get("message").asText());

        assertEquals(usersBefore, userRepository.count());
        assertEquals(profilesBefore, patientProfileRepository.count());
        assertEquals(0, appointmentRepository.count());

        List<BookingConsultationRequest> saved = consultationRepository.findAll();
        assertEquals(1, saved.size());
        BookingConsultationRequest item = saved.get(0);
        assertEquals(ConsultationRequestStatus.NEW, item.getStatus());
        assertEquals(reqKey, item.getRequestKey());
        assertEquals(newPhoneNormalized, item.getGuestPhone());
        assertEquals("Khách Mới Tư Vấn", item.getGuestName());
        assertEquals(activeBranch.getBranchId(), item.getBranch().getBranchId());
        assertEquals(activeOnlineService.getServiceId(), item.getService().getServiceId());
        assertNull(item.getConvertedAppointment());
    }

    // =========================================================================
    // 3. Số điện thoại trùng nhiều PatientProfile (dữ liệu legacy/không nhất quán)
    // =========================================================================
    @Test
    @DisplayName("3. SĐT trùng nhiều PatientProfile không tự lấy existingProfiles.get(0) và không lộ thông tin")
    void duplicateProfilesWithSamePhoneDoesNotPickFirstProfileOrLeakInfo() {
        UserRepository mockUsers = mock(UserRepository.class);
        PatientProfileRepository mockProfiles = mock(PatientProfileRepository.class);
        BookingConsultationRequestRepository mockConsultRepo = mock(BookingConsultationRequestRepository.class);
        String dupPhone = uniquePhone(5);

        when(mockUsers.existsByPhoneNumberIn(anyCollection())).thenReturn(false);
        when(mockProfiles.existsByPhoneNumberIn(anyCollection())).thenReturn(true);

        PublicConsultationService isolatedService = new PublicConsultationService(
                mockUsers, mockProfiles, mockConsultRepo, null, null);
        PublicConsultationService.GuestSubmissionOutcome outcome = isolatedService.submitGuestConsultation(
                new PublicConsultationService.ConsultationCommand(
                        UUID.randomUUID().toString(), activeBranch, activeDoctor, activeOnlineService,
                        "Khách Trùng", dupPhone, futureDate, PreferredSession.AFTERNOON,
                        LocalTime.of(15, 30), "Ghi chú"));

        assertTrue(outcome.consultationMode());
        assertFalse(outcome.createdNewConsultation());
        assertEquals(PublicConsultationService.NEUTRAL_GUEST_RESPONSE_MESSAGE, outcome.message());

        // Kiểm tra thêm trong H2: nếu user A có phone X ở users và hồ sơ B (khác user) cũng gắn phone X ở patient_profiles
        String crossOwnerPhone = existingUser.getPhoneNumber();
        PublicConsultationService.GuestSubmissionOutcome h2Outcome = consultationService.submitGuestConsultation(
                new PublicConsultationService.ConsultationCommand(
                        UUID.randomUUID().toString(), activeBranch, activeDoctor, activeOnlineService,
                        "Khách Dò", crossOwnerPhone, futureDate, PreferredSession.AFTERNOON,
                        LocalTime.of(15, 30), "Ghi chú"));
        assertFalse(h2Outcome.createdNewConsultation());
        assertEquals(0, consultationRepository.count());
        assertEquals(0, appointmentRepository.count());
    }

    // =========================================================================
    // 4. RequestKey giống nhau được gửi nhiều lần
    // =========================================================================
    @Test
    @DisplayName("4. Gửi lại cùng RequestKey + cùng payload chỉ tạo 1 bản ghi; khác payload bị từ chối 409")
    void sameRequestKeyIsIdempotentAndRejectsPayloadTampering() throws Exception {
        String reqKey = UUID.randomUUID().toString();
        String newPhone = uniquePhone(6);
        Map<String, Object> payload = guestPayload("Khách Lặp Key", newPhone, reqKey);
        String json = objectMapper.writeValueAsString(payload);

        mockMvc.perform(post("/api/public/booking").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        mockMvc.perform(post("/api/public/booking").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertEquals(1, consultationRepository.count());

        // Đổi dữ liệu với cùng requestKey -> 409 CONFLICT
        Map<String, Object> tamperedPayload = new LinkedHashMap<>(payload);
        tamperedPayload.put("guestName", "Đổi");
        tamperedPayload.put("fullName", "Tên Khác Hẳn");
        mockMvc.perform(post("/api/public/booking").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tamperedPayload)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
        assertEquals(1, consultationRepository.count());
    }

    // =========================================================================
    // 5. Hai request đồng thời cùng RequestKey
    // =========================================================================
    @Test
    @DisplayName("5. Hai request đồng thời cùng RequestKey trên H2 thật: chỉ tạo duy nhất 1 bản ghi, không sập 500")
    void twoConcurrentRequestsWithSameRequestKeyCreateOnlyOneRecord() throws Exception {
        String sharedKey = UUID.randomUUID().toString();
        String newPhone = uniquePhone(7);
        String json = objectMapper.writeValueAsString(guestPayload("Khách Song Song", newPhone, sharedKey));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger okCount = new AtomicInteger();
        AtomicInteger serverErrors = new AtomicInteger();

        Callable<Void> task = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            MvcResult res = mockMvc.perform(post("/api/public/booking")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andReturn();
            int code = res.getResponse().getStatus();
            if (code == 200) {
                okCount.incrementAndGet();
            } else if (code >= 500) {
                serverErrors.incrementAndGet();
            }
            return null;
        };

        try {
            Future<Void> f1 = pool.submit(task);
            Future<Void> f2 = pool.submit(task);
            f1.get(15, TimeUnit.SECONDS);
            f2.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, serverErrors.get());
        assertEquals(2, okCount.get());
        assertEquals(1, consultationRepository.count(), "Chỉ duy nhất 1 bản ghi được lưu trong booking_consultation_requests");
        assertEquals(0, appointmentRepository.count());
    }

    // =========================================================================
    // 6. Hai request khác RequestKey nhưng cùng thông tin
    // =========================================================================
    @Test
    @DisplayName("6. Hai request khác RequestKey nhưng cùng thông tin: chỉ giữ 1 yêu cầu tư vấn đang mở")
    void twoRequestsWithDifferentRequestKeysAndSameInfoDoNotCreateDuplicates() throws Exception {
        String newPhone = uniquePhone(8);
        String json1 = objectMapper.writeValueAsString(guestPayload("Khách Gửi Hai Lần", newPhone, UUID.randomUUID().toString()));
        String json2 = objectMapper.writeValueAsString(guestPayload("Khách Gửi Hai Lần", newPhone, UUID.randomUUID().toString()));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger okCount = new AtomicInteger();

        Callable<Void> c1 = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            if (mockMvc.perform(post("/api/public/booking").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json1))
                    .andReturn().getResponse().getStatus() == 200) {
                okCount.incrementAndGet();
            }
            return null;
        };
        Callable<Void> c2 = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            if (mockMvc.perform(post("/api/public/booking").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json2))
                    .andReturn().getResponse().getStatus() == 200) {
                okCount.incrementAndGet();
            }
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

        assertEquals(2, okCount.get());
        assertEquals(1, consultationRepository.count(), "Chống trùng bản ghi tư vấn đang mở khi khác requestKey");
    }

    // =========================================================================
    // 7. Guest cố truyền profileId của người khác
    // =========================================================================
    @Test
    @DisplayName("7. Guest cố truyền profileId của người khác bị chặn 403 FORBIDDEN")
    void guestSendingForeignProfileIdIsRejectedWith403() throws Exception {
        Map<String, Object> tampered = guestPayload("Khách Giả Mạo", uniquePhone(9), UUID.randomUUID().toString());
        tampered.put("profileId", foreignProfile.getProfileId());

        mockMvc.perform(post("/api/public/booking")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tampered)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        assertEquals(0, consultationRepository.count());
        assertEquals(0, appointmentRepository.count());
    }

    // =========================================================================
    // 8. Guest truyền branchId không hợp lệ
    // =========================================================================
    @Test
    @DisplayName("8. Guest truyền branchId không tồn tại hoặc null bị từ chối 400 BAD_REQUEST")
    void guestWithInvalidOrMissingBranchIdIsRejected() throws Exception {
        Map<String, Object> missingBranch = guestPayload("Khách A", uniquePhone(1), UUID.randomUUID().toString());
        missingBranch.put("branchId", null);
        mockMvc.perform(post("/api/public/booking").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(missingBranch)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        Map<String, Object> unknownBranch = guestPayload("Khách A", uniquePhone(2), UUID.randomUUID().toString());
        unknownBranch.put("branchId", 999999L);
        mockMvc.perform(post("/api/public/booking").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unknownBranch)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        assertEquals(0, consultationRepository.count());
    }

    // =========================================================================
    // 9. Chi nhánh ngừng hoạt động
    // =========================================================================
    @Test
    @DisplayName("9. Chi nhánh ngừng hoạt động bị từ chối 400 BAD_REQUEST")
    void inactiveBranchIsRejected() throws Exception {
        Map<String, Object> payload = guestPayload("Khách CN Ngừng", uniquePhone(3), UUID.randomUUID().toString());
        payload.put("branchId", inactiveBranch.getBranchId());

        mockMvc.perform(post("/api/public/booking").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        assertEquals(0, consultationRepository.count());
    }

    // =========================================================================
    // 10. Dịch vụ không hoạt động (INACTIVE / không bookableOnline / không tồn tại)
    // =========================================================================
    @Test
    @DisplayName("10. Dịch vụ ngừng hoạt động, không nhận đặt online hoặc không tồn tại bị từ chối 400 BAD_REQUEST")
    void inactiveOrOfflineOrUnknownServiceIsRejected() throws Exception {
        for (Long badSvcId : List.of(inactiveService.getServiceId(), offlineOnlyService.getServiceId(), 888888L)) {
            Map<String, Object> payload = guestPayload("Khách DV Lỗi", uniquePhone(4), UUID.randomUUID().toString());
            payload.put("serviceId", badSvcId);

            mockMvc.perform(post("/api/public/booking").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(payload)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        // GET /slots với dịch vụ inactive phải trả về rỗng
        mockMvc.perform(get("/api/public/booking/slots")
                        .param("branchId", activeBranch.getBranchId().toString())
                        .param("date", futureDate.toString())
                        .param("sessionPeriod", "AFTERNOON")
                        .param("serviceId", inactiveService.getServiceId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        assertEquals(0, consultationRepository.count());
    }

    // =========================================================================
    // 11. Rate limit bị vượt ngưỡng (không thể bypass bằng đổi field)
    // =========================================================================
    @Test
    @DisplayName("11. Vượt ngưỡng Rate Limit trả 429 TOO_MANY_REQUESTS, không bypass được khi đổi requestKey/tên/SĐT/note")
    void rateLimitBlocksSpamAcrossChangedFieldsAndDifferentIpsWithoutLeakingPhoneExistence() throws Exception {
        // 5 request liên tiếp từ cùng IP (mỗi lần đổi SĐT mới & đã tồn tại, đổi tên, đổi requestKey) -> lần 6 bị 429
        for (int i = 0; i < 5; i++) {
            String phone = (i == 0) ? foreignProfile.getPhoneNumber() : uniquePhone(2 + i);
            Map<String, Object> p = guestPayload("Spam User " + i, phone, UUID.randomUUID().toString());
            p.put("note", "Note random " + UUID.randomUUID());
            mockMvc.perform(post("/api/public/booking")
                            .with(csrf())
                            .with(req -> { req.setRemoteAddr("10.10.0.50"); return req; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(p)))
                    .andExpect(status().isOk());
        }

        MvcResult blockedIp = mockMvc.perform(post("/api/public/booking")
                        .with(csrf())
                        .with(req -> { req.setRemoteAddr("10.10.0.50"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                guestPayload("Bypass Attempt", uniquePhone(9), UUID.randomUUID().toString()))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andReturn();

        // Đổi IP liên tục nhắm vào CÙNG 1 SĐT (phone-max-requests = 3) -> request thứ 4 bị chặn 429
        String victimPhone = existingProfile.getPhoneNumber();
        for (int i = 1; i <= 3; i++) {
            int ipOctet = 10 + i;
            mockMvc.perform(post("/api/public/booking")
                            .with(csrf())
                            .with(req -> { req.setRemoteAddr("192.168.1." + ipOctet); return req; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    guestPayload("Distributed " + i, victimPhone, UUID.randomUUID().toString()))))
                    .andExpect(status().isOk());
        }
        MvcResult blockedPhone = mockMvc.perform(post("/api/public/booking")
                        .with(csrf())
                        .with(req -> { req.setRemoteAddr("192.168.1.99"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                guestPayload("Distributed 4", victimPhone, UUID.randomUUID().toString()))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andReturn();

        // Hai response 429 (khác SĐT vs trùng SĐT tồn tại) phải hoàn toàn giống nhau, không lộ SĐT
        assertEquals(blockedIp.getResponse().getContentAsString(), blockedPhone.getResponse().getContentAsString());
        assertFalse(blockedPhone.getResponse().getContentAsString().contains(victimPhone));
    }

    // =========================================================================
    // 12. CSRF và quyền truy cập endpoint
    // =========================================================================
    @Test
    @DisplayName("12. POST /api/public/booking bắt buộc có CSRF token hợp lệ & chặn tài khoản khác; GET công khai không lộ hồ sơ")
    void csrfAndAccessControlOnPublicBookingEndpoints() throws Exception {
        // Anonymous GET /context trả false và profiles rỗng
        mockMvc.perform(get("/api/public/booking/context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.profiles").isEmpty());

        String newPhone = uniquePhone(6);
        String guestJson = objectMapper.writeValueAsString(
                guestPayload("Guest CSRF", newPhone, UUID.randomUUID().toString()));

        // Guest POST thiếu CSRF -> bị Spring Security từ chối 403 FORBIDDEN
        mockMvc.perform(post("/api/public/booking")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(guestJson))
                .andExpect(status().isForbidden());
        assertEquals(0, consultationRepository.count());

        // Logged-in patient POST thiếu CSRF -> bị từ chối 403 FORBIDDEN
        MockHttpSession loggedSession = new MockHttpSession();
        loggedSession.setAttribute("loggedInUser", existingUser);
        Map<String, Object> loggedPayload = guestPayload(null, null, UUID.randomUUID().toString());
        loggedPayload.put("profileId", existingProfile.getProfileId());
        mockMvc.perform(post("/api/public/booking")
                        .session(loggedSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loggedPayload)))
                .andExpect(status().isForbidden());

        // Logged-in patient gửi với profileId của tài khoản khác + có CSRF -> bị chặn 403
        Map<String, Object> idorPayload = new LinkedHashMap<>(loggedPayload);
        idorPayload.put("profileId", foreignProfile.getProfileId());
        mockMvc.perform(post("/api/public/booking")
                        .with(csrf())
                        .session(loggedSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(idorPayload)))
                .andExpect(status().isForbidden());
        assertEquals(0, appointmentRepository.count());

        // Logged-in patient gửi với profileId chính chủ + có CSRF -> thành công tạo Appointment
        mockMvc.perform(post("/api/public/booking")
                        .with(csrf())
                        .session(loggedSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loggedPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.appointmentId").exists());
        assertEquals(1, appointmentRepository.count());
    }

    // =========================================================================
    // 13. Lỗi lưu dữ liệu giữa chừng không để lại bản ghi dở dang
    // =========================================================================
    @Test
    @DisplayName("13. Lỗi phát sinh giữa chừng sau saveAndFlush trong transaction: rollback sạch, không để bản ghi dở dang")
    void midTransactionFailureRollsBackConsultationRequestCompletely() throws Exception {
        long usersBefore = userRepository.count();
        long profilesBefore = patientProfileRepository.count();
        RollbackProbeConfig.FAIL_AFTER_CONSULTATION_SAVE.set(true);
        try {
            String json = objectMapper.writeValueAsString(
                    guestPayload("Khách Lỗi Rollback", uniquePhone(8), UUID.randomUUID().toString()));
            assertThrows(Exception.class, () ->
                    mockMvc.perform(post("/api/public/booking")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json)));
        } finally {
            RollbackProbeConfig.FAIL_AFTER_CONSULTATION_SAVE.set(false);
        }

        entityManager.clear();
        assertEquals(0, consultationRepository.count(), "Rollback phải xóa sạch bản ghi dở dang");
        assertEquals(0, appointmentRepository.count());
        assertEquals(usersBefore, userRepository.count());
        assertEquals(profilesBefore, patientProfileRepository.count());
    }

    // =========================================================================
    // 14. Yêu cầu tư vấn Guest không làm thay đổi sức chứa hoặc lịch hẹn chính thức
    // =========================================================================
    @Test
    @DisplayName("14. Yêu cầu tư vấn Guest không chiếm slot hay sức chứa bác sĩ; bệnh nhân thật vẫn đặt được đúng giờ đó")
    void guestConsultationDoesNotConsumeBranchOrDoctorOrSlotCapacity() throws Exception {
        var availBefore = appointmentPolicyService.availability(
                activeBranch, futureDate, PreferredSession.AFTERNOON, null, AppointmentActorType.PATIENT);
        var slotsBefore = appointmentSlotService.available(
                activeBranch, futureDate, PreferredSession.AFTERNOON, activeDoctor.getStaffId(), 30, null, AppointmentActorType.PATIENT);
        int slot1530Before = slotsBefore.stream()
                .filter(s -> s.time().equals(LocalTime.of(15, 30)))
                .findFirst().orElseThrow().remaining();

        // 3 Guest mới khác nhau cùng gửi yêu cầu tư vấn chọn đúng activeDoctor lúc 15:30
        for (int i = 1; i <= 3; i++) {
            final int guestIdx = i;
            Map<String, Object> payload = guestPayload("Guest " + i, uniquePhone(i), UUID.randomUUID().toString());
            mockMvc.perform(post("/api/public/booking")
                            .with(csrf())
                            .with(req -> { req.setRemoteAddr("172.16.0." + guestIdx); return req; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(payload)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.consultationMode").value(true));
        }

        assertEquals(3, consultationRepository.count());
        assertEquals(0, appointmentRepository.count());

        // Sức chứa chi nhánh, bác sĩ và khung giờ 15:30 phải giữ nguyên 100%
        var availAfter = appointmentPolicyService.availability(
                activeBranch, futureDate, PreferredSession.AFTERNOON, null, AppointmentActorType.PATIENT);
        var slotsAfter = appointmentSlotService.available(
                activeBranch, futureDate, PreferredSession.AFTERNOON, activeDoctor.getStaffId(), 30, null, AppointmentActorType.PATIENT);
        int slot1530After = slotsAfter.stream()
                .filter(s -> s.time().equals(LocalTime.of(15, 30)))
                .findFirst().orElseThrow().remaining();

        assertEquals(availBefore.remainingBranch(), availAfter.remainingBranch());
        assertEquals(availBefore.doctors().get(0).remaining(), availAfter.doctors().get(0).remaining());
        assertEquals(slot1530Before, slot1530After);

        // Bệnh nhân đã đăng nhập vẫn đặt thành công đúng khung 15:30 với bác sĩ này
        MockHttpSession loggedSession = new MockHttpSession();
        loggedSession.setAttribute("loggedInUser", existingUser);
        Map<String, Object> realBooking = guestPayload(null, null, UUID.randomUUID().toString());
        realBooking.put("profileId", existingProfile.getProfileId());
        MvcResult bookRes = mockMvc.perform(post("/api/public/booking")
                        .with(csrf())
                        .with(req -> { req.setRemoteAddr("172.16.0.100"); return req; })
                        .session(loggedSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(realBooking)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();

        JsonNode jsonNode = objectMapper.readTree(bookRes.getResponse().getContentAsString());
        assertTrue(jsonNode.get("appointmentId").asLong() > 0);
        assertEquals(1, appointmentRepository.count());
    }
}
