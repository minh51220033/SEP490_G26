package com.cvdcms.controller;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.entity.AccountPatientProfile;
import com.cvdcms.repository.AccountPatientProfileRepository;
import com.cvdcms.repository.*;
import com.cvdcms.repository.ReceptionVisitRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:home_branch_switch_repro;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;NON_KEYWORDS=MONTH,YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class HomeDirectBranchSwitchIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private BranchRepository branchRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PatientProfileRepository patientProfileRepository;
    @Autowired private AccountPatientProfileRepository accountPatientProfileRepository;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private ReceptionVisitRepository visitRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;
    private Branch b1Q1;
    private Branch b2CauGiay;
    private Branch b3DongDa;
    private Branch b4BacGiang;
    private Branch b5Inactive;
    private User patientUser;
    private PatientProfile profileSelf;
    private PatientProfile profileDep;
    private Appointment historicalAppt;
    private Visit historicalVisit;
    private String username;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        String uid = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        Role patientRole = roleRepository.findByRoleName("BENH_NHAN").orElseGet(() -> {
            Role r = new Role();
            r.setRoleName("BENH_NHAN");
            r.setDisplayName("Bệnh nhân");
            r.setIsActive(true);
            return roleRepository.saveAndFlush(r);
        });

        b1Q1 = saveBranch("Q1_" + uid, "Cơ sở Quận 1 " + uid, "0901111111", true);
        b2CauGiay = saveBranch("CG_" + uid, "Cơ sở Cầu Giấy " + uid, "0902222222", true);
        b3DongDa = saveBranch("DD_" + uid, "Cơ sở Đống Đa " + uid, "0903333333", true);
        b4BacGiang = saveBranch("BG_" + uid, "Cơ sở Bắc Giang " + uid, "0904444444", true);
        b5Inactive = saveBranch("IN_" + uid, "Cơ sở Ngừng " + uid, "0905555555", false);

        username = "patient_" + uid;
        patientUser = new User();
        patientUser.setUsername(username);
        patientUser.setPassword(passwordEncoder.encode("Secret@123"));
        patientUser.setEmail(username + "@test.local");
        patientUser.setPhoneNumber("091" + String.format("%07d", Math.abs(uid.hashCode() % 10000000)));
        patientUser.setUserType(UserType.PATIENT);
        patientUser.setStatus("ACTIVE");
        patientUser.setIsActive(true);
        // Khởi tạo tài khoản với User.branch = Cơ sở Cầu Giấy (không phải Quận 1)
        patientUser.setBranch(b2CauGiay);
        patientUser.setRoles(new HashSet<>(Set.of(patientRole)));
        patientUser = userRepository.saveAndFlush(patientUser);

        profileSelf = saveProfile("BN1_" + uid, "Nguyễn Văn An", b2CauGiay, true, AccountPatientProfile.AccessType.SELF);
        profileDep = saveProfile("BN2_" + uid, "Nguyễn Bé Bông", b2CauGiay, false, AccountPatientProfile.AccessType.DEPENDENT);

        historicalAppt = new Appointment();
        historicalAppt.setAppointmentCode("LH_" + uid);
        historicalAppt.setBranch(b1Q1);
        historicalAppt.setPatientProfile(profileSelf);
        historicalAppt.setAppointmentDate(LocalDate.now().plusDays(1));
        historicalAppt.setPreferredSession(PreferredSession.MORNING);
        historicalAppt.setExpectedTime(LocalTime.of(9, 0));
        historicalAppt.setStatus(AppointmentStatus.CONFIRMED);
        historicalAppt = appointmentRepository.saveAndFlush(historicalAppt);

        historicalVisit = new Visit();
        historicalVisit.setVisitCode("VS_" + uid);
        historicalVisit.setAppointmentId(historicalAppt.getAppointmentId());
        historicalVisit.setBranch(b1Q1);
        historicalVisit.setPatientProfile(profileSelf);
        historicalVisit.setStatus(VisitStatus.WAITING);
        historicalVisit.setCheckInAt(LocalDateTime.now());
        historicalVisit = visitRepository.saveAndFlush(historicalVisit);
    }

    @Test
    @DisplayName("Kịch bản A, B, C, D: Đăng nhập nhận đúng Cầu Giấy, chuyển 5 lần không F5, đăng xuất & đăng nhập lại vẫn giữ cơ sở mới")
    void loginHomeShowsAccountBranchSwitches5TimesWithoutReloadAndPersistsAcrossRelogin() throws Exception {
        // KỊCH BẢN A: Đăng nhập với User.branch = Cầu Giấy (branchId = b2CauGiay)
        SessionJar loginState = loginAs(username, "Secret@123");

        // Kiểm tra ngay trang /home (đích redirect sau login)
        MvcResult homeGet = mockMvc.perform(get("/home").session(loginState.session).cookie(loginState.cookies)).andReturn();
        loginState.cookies = mergeCookies(loginState.cookies, homeGet.getResponse().getCookies());
        assertEquals(200, homeGet.getResponse().getStatus());
        String homeHtml = homeGet.getResponse().getContentAsString();
        assertBranchActiveInCarouselHtml(homeHtml, b2CauGiay.getBranchId(), b2CauGiay.getBranchName());
        assertBranchSwitchableInCarouselHtml(homeHtml, b1Q1.getBranchId());
        assertBranchSwitchableInCarouselHtml(homeHtml, b3DongDa.getBranchId());
        assertFalse(homeHtml.contains("window.location.reload"), "Trang Home tuyệt đối không được dùng window.location.reload()");

        // Kiểm tra thêm trang / và lấy CSRF meta
        MvcResult rootGet = mockMvc.perform(get("/").session(loginState.session).cookie(loginState.cookies)).andReturn();
        loginState.cookies = mergeCookies(loginState.cookies, rootGet.getResponse().getCookies());
        String html = rootGet.getResponse().getContentAsString();
        assertBranchActiveInCarouselHtml(html, b2CauGiay.getBranchId(), b2CauGiay.getBranchName());
        String csrfToken = extractMeta(html, "_csrf");
        String csrfHeader = extractMeta(html, "_csrf_header");
        assertNotNull(csrfToken);
        assertNotNull(csrfHeader);

        // KỊCH BẢN B: Chuyển liên tiếp 5 lần KHÔNG gọi GET / hay /home lại (không F5)
        List<Branch> sequence = List.of(b3DongDa, b1Q1, b4BacGiang, b2CauGiay, b3DongDa);
        for (int i = 0; i < sequence.size(); i++) {
            Branch target = sequence.get(i);
            MvcResult res = mockMvc.perform(post("/portal/switch-branch/ajax")
                    .session(loginState.session)
                    .cookie(loginState.cookies)
                    .header(csrfHeader, csrfToken)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .param("branchId", String.valueOf(target.getBranchId())))
                    .andReturn();
            loginState.cookies = mergeCookies(loginState.cookies, res.getResponse().getCookies());

            int status = res.getResponse().getStatus();
            String respBody = res.getResponse().getContentAsString();
            System.out.println("[SWITCH #" + (i + 1) + "] target=" + target.getBranchId()
                    + " status=" + status + " body=" + respBody);

            assertEquals(200, status, "Lần chuyển #" + (i + 1) + " thất bại! body=" + respBody);
            assertTrue(respBody.contains("\"success\":true"));
            assertTrue(respBody.contains("\"branchId\":" + target.getBranchId()));
            assertTrue(respBody.contains(target.getPhone()));

            // Kiểm tra DB thực tế đồng bộ User.branch + toàn bộ PatientProfile.preferredBranch
            User dbUser = userRepository.findById(patientUser.getUserId()).orElseThrow();
            PatientProfile dbSelf = patientProfileRepository.findById(profileSelf.getProfileId()).orElseThrow();
            PatientProfile dbDep = patientProfileRepository.findById(profileDep.getProfileId()).orElseThrow();
            assertEquals(target.getBranchId(), dbUser.getBranch().getBranchId());
            assertEquals(target.getBranchId(), dbSelf.getPreferredBranch().getBranchId());
            assertEquals(target.getBranchId(), dbDep.getPreferredBranch().getBranchId());
        }

        // Dữ liệu lịch hẹn & lượt khám lịch sử vẫn giữ nguyên Quận 1
        assertEquals(b1Q1.getBranchId(), appointmentRepository.findById(historicalAppt.getAppointmentId()).orElseThrow().getBranch().getBranchId());
        assertEquals(b1Q1.getBranchId(), visitRepository.findById(historicalVisit.getVisitId()).orElseThrow().getBranch().getBranchId());

        // Kiểm tra chọn lại chính cơ sở hiện tại (Đống Đa) -> vẫn 200 idempotent
        MvcResult sameRes = mockMvc.perform(post("/portal/switch-branch/ajax")
                .session(loginState.session).cookie(loginState.cookies).header(csrfHeader, csrfToken)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).accept(MediaType.APPLICATION_JSON)
                .param("branchId", String.valueOf(b3DongDa.getBranchId())))
                .andReturn();
        assertEquals(200, sameRes.getResponse().getStatus());
        loginState.cookies = mergeCookies(loginState.cookies, sameRes.getResponse().getCookies());

        // Kiểm tra branchId inactive -> 400 BAD_REQUEST, giữ nguyên Đống Đa, không làm hỏng lần chuyển sau
        MvcResult badRes = mockMvc.perform(post("/portal/switch-branch/ajax")
                .session(loginState.session).cookie(loginState.cookies).header(csrfHeader, csrfToken)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).accept(MediaType.APPLICATION_JSON)
                .param("branchId", String.valueOf(b5Inactive.getBranchId())))
                .andReturn();
        assertEquals(400, badRes.getResponse().getStatus());
        assertEquals(b3DongDa.getBranchId(), userRepository.findById(patientUser.getUserId()).orElseThrow().getBranch().getBranchId());
        loginState.cookies = mergeCookies(loginState.cookies, badRes.getResponse().getCookies());

        // Sau lỗi 400 vẫn chuyển tiếp bình thường sang Cầu Giấy
        MvcResult backToCg = mockMvc.perform(post("/portal/switch-branch/ajax")
                .session(loginState.session).cookie(loginState.cookies).header(csrfHeader, csrfToken)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).accept(MediaType.APPLICATION_JSON)
                .param("branchId", String.valueOf(b2CauGiay.getBranchId())))
                .andReturn();
        assertEquals(200, backToCg.getResponse().getStatus());
        loginState.cookies = mergeCookies(loginState.cookies, backToCg.getResponse().getCookies());

        // KỊCH BẢN C: Chuyển Đống Đa -> Đăng xuất -> Đăng nhập lại -> Home hiển thị đúng Đống Đa!
        MvcResult switchFinal = mockMvc.perform(post("/portal/switch-branch/ajax")
                .session(loginState.session).cookie(loginState.cookies).header(csrfHeader, csrfToken)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).accept(MediaType.APPLICATION_JSON)
                .param("branchId", String.valueOf(b3DongDa.getBranchId())))
                .andReturn();
        assertEquals(200, switchFinal.getResponse().getStatus());
        loginState.cookies = mergeCookies(loginState.cookies, switchFinal.getResponse().getCookies());

        MvcResult logoutRes = mockMvc.perform(post("/logout")
                .session(loginState.session).cookie(loginState.cookies).header(csrfHeader, csrfToken))
                .andReturn();
        assertEquals(302, logoutRes.getResponse().getStatus());

        // Đăng nhập lại trong phiên mới hoàn toàn
        SessionJar relogin = loginAs(username, "Secret@123");
        MvcResult reloginHome = mockMvc.perform(get("/home").session(relogin.session).cookie(relogin.cookies)).andReturn();
        assertEquals(200, reloginHome.getResponse().getStatus());
        String reloginHomeHtml = reloginHome.getResponse().getContentAsString();
        assertBranchActiveInCarouselHtml(reloginHomeHtml, b3DongDa.getBranchId(), b3DongDa.getBranchName());
        assertBranchSwitchableInCarouselHtml(reloginHomeHtml, b1Q1.getBranchId());
        assertBranchSwitchableInCarouselHtml(reloginHomeHtml, b2CauGiay.getBranchId());
    }

    private SessionJar loginAs(String uname, String rawPassword) throws Exception {
        MvcResult loginGet = mockMvc.perform(get("/login")).andReturn();
        MockHttpSession session = (MockHttpSession) loginGet.getRequest().getSession(true);
        Cookie[] jar = mergeCookies(null, loginGet.getResponse().getCookies());
        String loginCsrf = extractInputCsrf(loginGet.getResponse().getContentAsString());
        assertNotNull(loginCsrf);

        MvcResult loginPost = mockMvc.perform(post("/login")
                .session(session)
                .cookie(jar)
                .param("username", uname)
                .param("password", rawPassword)
                .param("_csrf", loginCsrf)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andReturn();
        assertEquals(302, loginPost.getResponse().getStatus());
        assertEquals("/home", loginPost.getResponse().getRedirectedUrl());
        assertNotNull(session.getAttribute("loggedInUser"));
        jar = mergeCookies(jar, loginPost.getResponse().getCookies());
        return new SessionJar(session, jar);
    }

    private static void assertBranchActiveInCarouselHtml(String html, Long expectedBranchId, String expectedBranchName) {
        String slideHtml = extractSlideArticle(html, expectedBranchId);
        assertNotNull(slideHtml, "Không tìm thấy thẻ cơ sở branchId=" + expectedBranchId);
        assertTrue(slideHtml.contains("data-is-current=\"true\""),
                "Cơ sở " + expectedBranchName + " phải có data-is-current=true nhưng HTML là: " + slideHtml);
        assertTrue(slideHtml.contains("data-current-branch-btn"),
                "Cơ sở " + expectedBranchName + " phải hiển thị nút Cơ sở hiện tại");
        assertTrue(html.contains("<strong id=\"cvUserBarBranchName\" class=\"text-primary\">" + expectedBranchName + "</strong>"),
                "Thanh tiện ích tài khoản phải hiển thị cơ sở đang xem: " + expectedBranchName);
    }

    private static void assertBranchSwitchableInCarouselHtml(String html, Long branchId) {
        String slideHtml = extractSlideArticle(html, branchId);
        assertNotNull(slideHtml, "Không tìm thấy thẻ cơ sở branchId=" + branchId);
        assertTrue(slideHtml.contains("data-is-current=\"false\""),
                "Cơ sở phụ branchId=" + branchId + " phải có data-is-current=false");
        assertTrue(slideHtml.contains("data-direct-switch-branch"),
                "Cơ sở phụ branchId=" + branchId + " phải có nút Chuyển sang cơ sở này");
    }

    private static String extractSlideArticle(String html, Long branchId) {
        Matcher m = Pattern.compile(
                "<article[^>]*data-branch-id=\"" + branchId + "\"[^>]*>[\\s\\S]*?</article>"
        ).matcher(html);
        return m.find() ? m.group(0) : null;
    }

    private static String extractInputCsrf(String html) {
        if (html == null) return null;
        Matcher m1 = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]*)\"").matcher(html);
        if (m1.find()) return m1.group(1);
        Matcher m2 = Pattern.compile("<input[^>]*value=\"([^\"]*)\"[^>]*name=\"_csrf\"").matcher(html);
        return m2.find() ? m2.group(1) : null;
    }

    private static String extractMeta(String html, String name) {
        if (html == null) return null;
        Matcher m1 = Pattern.compile("<meta[^>]*name=\"" + Pattern.quote(name) + "\"[^>]*content=\"([^\"]*)\"").matcher(html);
        if (m1.find()) return m1.group(1);
        Matcher m2 = Pattern.compile("<meta[^>]*content=\"([^\"]*)\"[^>]*name=\"" + Pattern.quote(name) + "\"").matcher(html);
        return m2.find() ? m2.group(1) : null;
    }

    private static Cookie[] mergeCookies(Cookie[] current, Cookie[] incoming) {
        Map<String, Cookie> map = new LinkedHashMap<>();
        if (current != null) {
            for (Cookie c : current) {
                if (c != null) map.put(c.getName(), c);
            }
        }
        if (incoming != null) {
            for (Cookie c : incoming) {
                if (c != null) {
                    if (c.getMaxAge() == 0) map.remove(c.getName());
                    else map.put(c.getName(), c);
                }
            }
        }
        return map.values().toArray(new Cookie[0]);
    }

    private Branch saveBranch(String code, String name, String phone, boolean active) {
        Branch b = new Branch();
        b.setBranchCode(code);
        b.setBranchName(name);
        b.setAddress("123 Street " + code);
        b.setPhone(phone);
        b.setOpeningTime(LocalTime.of(8, 0));
        b.setClosingTime(LocalTime.of(20, 0));
        b.setWorkingDays("2,3,4,5,6,7,CN");
        b.setIsActive(active);
        return branchRepository.saveAndFlush(b);
    }

    private PatientProfile saveProfile(String code, String name, Branch branch, boolean isDefault, AccountPatientProfile.AccessType type) {
        PatientProfile p = new PatientProfile();
        p.setUser(patientUser);
        p.setPatientCode(code);
        p.setFullName(name);
        p.setGender("Nam");
        p.setPhoneNumber("098" + String.format("%07d", Math.abs(code.hashCode() % 10000000)));
        p.setAddress("HCM");
        p.setPreferredBranch(branch);
        p.setIsDefault(isDefault);
        p.setStatus("ACTIVE");
        p = patientProfileRepository.saveAndFlush(p);

        AccountPatientProfile link = new AccountPatientProfile();
        link.setAccount(patientUser);
        link.setProfile(p);
        link.setAccessType(type);
        accountPatientProfileRepository.saveAndFlush(link);
        return p;
    }

    private static final class SessionJar {
        final MockHttpSession session;
        Cookie[] cookies;

        SessionJar(MockHttpSession session, Cookie[] cookies) {
            this.session = session;
            this.cookies = cookies;
        }
    }
}
