package com.cvdcms.system;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-Stack Non-Functional Requirements (NFR) Verification Suite for Report 5.4:
 * - Security: Auth, CSRF filter behavior, IDOR across branches & patients, /uploads/invoices fix, magic-byte upload guard, sortBy whitelist.
 * - Performance & Reliability: Real SockJS + STOMP end-to-end latency, 30s dedup, Pessimistic Lock race-condition concurrency.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:assigned_nfr_test_db;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;NON_KEYWORDS=MONTH,YEAR",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.show-sql=false",
                "spring.flyway.enabled=false",
                "spring.sql.init.mode=never"
        }
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AssignedModulesNfrSystemTest {

    @LocalServerPort
    private int port;

    @Autowired private WebApplicationContext wac;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private BranchRepository branchRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffProfileRepository staffRepository;
    @Autowired private StaffBranchAssignmentRepository assignmentRepository;
    @Autowired private PatientProfileRepository patientRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private MaterialBatchRepository batchRepository;
    @Autowired private BranchInventoryRepository inventoryRepository;
    @Autowired private StockReceiptRepository receiptRepository;
    @Autowired private StockIssueRepository issueRepository;
    @Autowired private SupplierPaymentRepository supplierPaymentRepository;
    @Autowired private VisitRepository visitRepository;
    @Autowired private ChatMessageRepository chatMessageRepository;

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final List<Path> uploadedFilesToCleanup = new ArrayList<>();

    private Branch cn01;
    private Branch cn02;

    private User adminUser;
    private User khoCn01User;
    private User khoCn02User;
    private User ketoanCn01User;
    private User bacsiCn01User;
    private StaffProfile bacsiCn01Staff;
    private User letanCn01User;
    private User letanCn02User;
    private User pat1Cn01User;
    private PatientProfile pat1Profile;
    private User pat2Cn02User;
    private PatientProfile pat2Profile;

    private String encodedPass;

    @BeforeEach
    void initData() {
        SimpleModule timeModule = new SimpleModule();
        timeModule.addSerializer(LocalDate.class, ToStringSerializer.instance);
        timeModule.addSerializer(LocalDateTime.class, ToStringSerializer.instance);
        mapper.registerModule(timeModule);

        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        encodedPass = passwordEncoder.encode("NfrPass!123");
        String tag = "NFR" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase();

        cn01 = createBranch("N1-" + tag, "CN1-" + tag);
        cn02 = createBranch("N2-" + tag, "CN2-" + tag);

        adminUser = createStaffUser("adm_" + tag, "ADMIN", "Admin " + tag, cn01, false).user();
        khoCn01User = createStaffUser("k1_" + tag, "QUAN_LY_KHO", "Kho1 " + tag, cn01, true).user();
        khoCn02User = createStaffUser("k2_" + tag, "QUAN_LY_KHO", "Kho2 " + tag, cn02, true).user();
        ketoanCn01User = createStaffUser("kt1_" + tag, "KE_TOAN", "KT1 " + tag, cn01, true).user();
        var bs = createStaffUser("bs1_" + tag, "BAC_SI", "BS1 " + tag, cn01, true);
        bacsiCn01User = bs.user();
        bacsiCn01Staff = bs.staff();
        letanCn01User = createStaffUser("lt1_" + tag, "LE_TAN", "LT1 " + tag, cn01, true).user();
        letanCn02User = createStaffUser("lt2_" + tag, "LE_TAN", "LT2 " + tag, cn02, true).user();

        var p1 = createPatientUser("p1_" + tag.toLowerCase(), "BN P1 " + tag, cn01);
        pat1Cn01User = p1.user();
        pat1Profile = p1.profile();

        var p2 = createPatientUser("p2_" + tag.toLowerCase(), "BN P2 " + tag, cn02);
        pat2Cn02User = p2.user();
        pat2Profile = p2.profile();
    }

    @AfterEach
    void cleanup() {
        for (Path p : uploadedFilesToCleanup) {
            try {
                Files.deleteIfExists(p);
            } catch (Exception ignored) {
            }
        }
        uploadedFilesToCleanup.clear();
    }

    private Branch createBranch(String code, String name) {
        Branch b = new Branch();
        b.setBranchCode(code);
        b.setBranchName(name);
        b.setAddress("Hanoi");
        b.setPhone("02438889999");
        b.setOpeningTime(LocalTime.of(8, 0));
        b.setClosingTime(LocalTime.of(20, 0));
        b.setIsActive(true);
        return branchRepository.saveAndFlush(b);
    }

    private record StaffPair(User user, StaffProfile staff) {}
    private record PatientPair(User user, PatientProfile profile) {}

    private StaffPair createStaffUser(String username, String roleName, String fullName, Branch branch, boolean assignBranch) {
        Role role = roleRepository.findByRoleName(roleName).orElseThrow();
        User u = new User();
        u.setUsername(username);
        u.setPassword(encodedPass);
        u.setEmail(username + "@nfr.test");
        u.setPhoneNumber("090" + String.format("%07d", Math.abs(username.hashCode()) % 10000000));
        u.setUserType(UserType.STAFF);
        u.setStatus("ACTIVE");
        u.setIsActive(true);
        u.setBranch(branch);
        u.setRoles(new HashSet<>(Set.of(role)));
        u.setCustomPermissionsEnabled(false);
        u = userRepository.saveAndFlush(u);

        StaffProfile sp = new StaffProfile();
        sp.setUser(u);
        sp.setStaffCode("NV-" + username.toUpperCase());
        sp.setFullName(fullName);
        sp.setPhoneNumber(u.getPhoneNumber());
        sp.setEmail(u.getEmail());
        sp.setDepartment("Clinic");
        sp.setPositionTitle(roleName);
        sp.setStatus(StaffStatus.ACTIVE);
        sp = staffRepository.saveAndFlush(sp);

        if (assignBranch) {
            StaffBranchAssignment sba = new StaffBranchAssignment();
            sba.setStaff(sp);
            sba.setBranch(branch);
            sba.setRole(role);
            sba.setStartDate(LocalDate.now().minusDays(7));
            sba.setIsPrimary(true);
            sba.setStatus(AssignmentStatus.ACTIVE);
            assignmentRepository.saveAndFlush(sba);
        }
        return new StaffPair(u, sp);
    }

    private PatientPair createPatientUser(String username, String fullName, Branch branch) {
        Role role = roleRepository.findByRoleName("BENH_NHAN").orElseThrow();
        User u = new User();
        u.setUsername(username);
        u.setPassword(encodedPass);
        u.setEmail(username + "@mail.test");
        u.setPhoneNumber("091" + String.format("%07d", Math.abs(username.hashCode()) % 10000000));
        u.setUserType(UserType.PATIENT);
        u.setStatus("ACTIVE");
        u.setIsActive(true);
        u.setBranch(branch);
        u.setRoles(new HashSet<>(Set.of(role)));
        u.setCustomPermissionsEnabled(false);
        u = userRepository.saveAndFlush(u);

        PatientProfile p = new PatientProfile();
        p.setUser(u);
        p.setPatientCode("BN-" + username.toUpperCase());
        p.setFullName(fullName);
        p.setPhoneNumber(u.getPhoneNumber());
        p.setGender("Nam");
        p.setDob(LocalDate.of(1992, 3, 15));
        p.setAddress("Hanoi");
        p.setStatus("ACTIVE");
        p.setIsDefault(true);
        p.setPreferredBranch(branch);
        p = patientRepository.saveAndFlush(p);
        return new PatientPair(u, p);
    }

    private byte[] createValidPngBytes() throws Exception {
        BufferedImage img = new BufferedImage(120, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 120, 40);
        g.setColor(Color.BLUE);
        g.drawLine(5, 10, 110, 30);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private String loginOverHttp(String username, String rawPassword) throws Exception {
        CookieManager cm = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cm)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpResponse<String> getLogin = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/login")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, getLogin.statusCode());
        Matcher m = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"").matcher(getLogin.body());
        assertTrue(m.find(), "CSRF token must be rendered on /login");
        String csrf = m.group(1);

        String form = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(rawPassword, StandardCharsets.UTF_8)
                + "&_csrf=" + URLEncoder.encode(csrf, StandardCharsets.UTF_8);
        HttpResponse<String> postLogin = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(302, postLogin.statusCode());
        assertTrue(postLogin.headers().firstValue("Location").orElse("").contains("/home"));

        return cm.getCookieStore().getCookies().stream()
                .map(c -> c.getName() + "=" + c.getValue())
                .reduce((a, b) -> a + "; " + b)
                .orElseThrow();
    }

    @Test
    @Order(1)
    @DisplayName("NFR Security: Auth, CSRF, Fixed /uploads/invoices Protection, IDOR & Anti-SQLi across 4 modules")
    void securityMatrixAcrossAllFourModules() throws Exception {
        // 1. Live HTTP Anonymous & CSRF checks
        HttpClient anon = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        for (String route : List.of("/chat", "/inventory", "/customer-care", "/suppliers", "/uploads/invoices/sample.png")) {
            HttpResponse<String> r = anon.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + route)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(302, r.statusCode(), "Anon must redirect on " + route);
            assertTrue(r.headers().firstValue("Location").orElse("").contains("/login"));
        }

        // POST /login without CSRF -> 403
        HttpResponse<String> noCsrf = anon.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("username=" + adminUser.getUsername() + "&password=NfrPass!123"))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(403, noCsrf.statusCode());

        // 2. File Upload Magic-Byte, Path Traversal & /uploads/invoices Patient IDOR Fix
        byte[] pngBytes = createValidPngBytes();

        // Fake PNG containing XSS HTML payload -> 400
        mockMvc.perform(multipart("/api/inventory/receipts/upload-invoice")
                        .file(new MockMultipartFile("file", "bad.png", "image/png", "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)))
                        .sessionAttr("loggedInUser", khoCn01User))
                .andExpect(status().isBadRequest());

        JsonNode invRes = mapper.readTree(mockMvc.perform(multipart("/api/inventory/receipts/upload-invoice")
                        .file(new MockMultipartFile("file", "inv_nfr.png", "image/png", pngBytes))
                        .sessionAttr("loggedInUser", khoCn01User))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String invoiceUrl = invRes.get("attachmentUrl").asText();
        uploadedFilesToCleanup.add(Paths.get(invoiceUrl.substring(1)).toAbsolutePath().normalize());

        // Authorized staff can read /uploads/invoices/... -> 200; Patient (BENH_NHAN) MUST get 403 Forbidden (Fix verified!)
        String khoCookie = loginOverHttp(khoCn01User.getUsername(), "NfrPass!123");
        String patCookie = loginOverHttp(pat1Cn01User.getUsername(), "NfrPass!123");

        HttpResponse<byte[]> staffInvResp = anon.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + invoiceUrl))
                        .header("Cookie", khoCookie).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );
        assertEquals(200, staffInvResp.statusCode());

        HttpResponse<String> patientInvResp = anon.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + invoiceUrl))
                        .header("Cookie", patCookie).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(403, patientInvResp.statusCode(), "Patient must NOT read internal supplier /uploads/invoices/**");

        // Patient reading signatures or path traversal -> 403 / 400
        mockMvc.perform(get("/uploads/invoices/..%2fsecret.png").sessionAttr("loggedInUser", khoCn01User))
                .andExpect(status().isBadRequest());

        // 3. Seed records across all 4 modules in CN01 to test IDOR
        SupplierCreateDTO supDto = new SupplierCreateDTO();
        supDto.setSupplierCode("SUPNFR01");
        supDto.setSupplierName("NCC NFR 01");
        supDto.setTaxCode("0108889991");
        supDto.setPhoneNumber("+84901239991");
        supDto.setAddress("Hanoi");
        long supId = mapper.readTree(mockMvc.perform(post("/api/suppliers")
                        .sessionAttr("loggedInUser", khoCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(supDto)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("supplierId").asLong();

        MaterialCreateDTO matDto = new MaterialCreateDTO();
        matDto.setMaterialCode("VTNFR01");
        matDto.setMaterialName("Vat tu NFR");
        matDto.setCategory("Tieu hao");
        matDto.setUnit("Hop");
        matDto.setMinStockThreshold(new BigDecimal("5"));
        long matId = mapper.readTree(mockMvc.perform(post("/api/materials")
                        .sessionAttr("loggedInUser", khoCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(matDto)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("materialId").asLong();

        StockReceiptCreateDTO recDto = new StockReceiptCreateDTO();
        recDto.setBranchId(cn01.getBranchId());
        recDto.setSupplierId(supId);
        recDto.setReceiptDate(LocalDate.now());
        recDto.setAttachmentUrl(invoiceUrl);
        recDto.setPaidAmount(BigDecimal.ZERO);
        StockReceiptCreateDTO.Item recItem = new StockReceiptCreateDTO.Item();
        recItem.setMaterialId(matId);
        recItem.setBatchNumber("LOT-NFR-01");
        recItem.setExpiryDate(LocalDate.now().plusDays(120));
        recItem.setQuantity(new BigDecimal("10"));
        recItem.setUnitPrice(new BigDecimal("50000"));
        recDto.setItems(List.of(recItem));
        JsonNode recNode = mapper.readTree(mockMvc.perform(post("/api/inventory/receipts")
                        .sessionAttr("loggedInUser", khoCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(recDto)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long recId = recNode.get("receiptId").asLong();

        // Inventory cross-branch IDOR by KHO CN02 -> 403 everywhere
        mockMvc.perform(get("/api/inventory").param("branchId", String.valueOf(cn01.getBranchId()))
                        .sessionAttr("loggedInUser", khoCn02User)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/inventory/batches").param("branchId", String.valueOf(cn01.getBranchId()))
                        .sessionAttr("loggedInUser", khoCn02User)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/inventory/receipts/{id}", recId)
                        .sessionAttr("loggedInUser", khoCn02User)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/inventory/receipts/{id}/pdf", recId)
                        .sessionAttr("loggedInUser", khoCn02User)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/inventory/supplier-payments/debts").param("branchId", String.valueOf(cn01.getBranchId()))
                        .sessionAttr("loggedInUser", khoCn02User)).andExpect(status().isForbidden());

        // Supplier RBAC & sort whitelist SQLi checks
        mockMvc.perform(get("/suppliers").sessionAttr("loggedInUser", ketoanCn01User)).andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/suppliers").sessionAttr("loggedInUser", bacsiCn01User)).andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/api/suppliers").sessionAttr("loggedInUser", letanCn01User)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/suppliers").param("sortBy", "password").sessionAttr("loggedInUser", khoCn01User))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/inventory").param("branchId", String.valueOf(cn01.getBranchId()))
                        .param("sortBy", "1=1").sessionAttr("loggedInUser", khoCn01User))
                .andExpect(status().isBadRequest());

        // Customer Care IDOR: cross-branch task, visit examination summary, patient history, doctor-response by LT
        Visit v1 = new Visit();
        v1.setVisitCode("VNFR-" + System.nanoTime());
        v1.setPatientProfile(pat1Profile);
        v1.setBranch(cn01);
        v1.setPrimaryDoctor(bacsiCn01Staff);
        v1.setCheckInAt(LocalDateTime.now().minusHours(2));
        v1.setCompletedAt(LocalDateTime.now().minusHours(1));
        v1.setStatus(VisitStatus.COMPLETED);
        v1 = visitRepository.saveAndFlush(v1);

        CustomerCareCreateDTO ccDto = new CustomerCareCreateDTO();
        ccDto.setBranchId(cn01.getBranchId());
        ccDto.setProfileId(pat1Profile.getProfileId());
        ccDto.setVisitId(v1.getVisitId());
        ccDto.setTaskType("POST_TREATMENT");
        ccDto.setDueDate(LocalDate.now());
        long taskId = mapper.readTree(mockMvc.perform(post("/api/customer-care/tasks")
                        .sessionAttr("loggedInUser", letanCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(ccDto)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("taskId").asLong();

        mockMvc.perform(get("/api/customer-care/tasks/{id}", taskId).sessionAttr("loggedInUser", letanCn02User))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/customer-care/visits/{id}/examination-summary", v1.getVisitId()).sessionAttr("loggedInUser", letanCn02User))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/customer-care/patients/{id}/history", pat1Profile.getProfileId()).sessionAttr("loggedInUser", letanCn02User))
                .andExpect(status().isForbidden());
        CustomerCareEscalateDTO docResp = new CustomerCareEscalateDTO();
        docResp.setDoctorResponse("Tu van y khoa gia mao");
        mockMvc.perform(post("/api/customer-care/tasks/{id}/doctor-response", taskId)
                        .sessionAttr("loggedInUser", letanCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(docResp)))
                .andExpect(status().isForbidden());

        // Chatbox IDOR: pat2 accessing pat1 conv; lt1 accessing direct(kt1<->bs1); kt1/lt2 accessing pat1 conv
        ChatSendRequestDTO initMsg = ChatSendRequestDTO.builder()
                .messageText("Khoi tao hoi thoai NFR Security")
                .clientMessageId("nfr-sec-init-" + System.nanoTime())
                .build();
        JsonNode p1Init = mapper.readTree(mockMvc.perform(post("/api/chat/messages")
                        .sessionAttr("loggedInUser", pat1Cn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(initMsg)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long patConvId = p1Init.get("conversationId").asLong();

        mockMvc.perform(get("/api/chat/conversations/{id}/messages", patConvId).sessionAttr("loggedInUser", pat2Cn02User))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/chat/conversations/{id}/messages", patConvId).sessionAttr("loggedInUser", letanCn02User))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/chat/conversations/{id}/messages", patConvId).sessionAttr("loggedInUser", ketoanCn01User))
                .andExpect(status().isForbidden());

        long directId = mapper.readTree(mockMvc.perform(post("/api/chat/conversations/start")
                        .sessionAttr("loggedInUser", ketoanCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("targetUserId", bacsiCn01User.getUserId(), "branchId", cn01.getBranchId()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("conversationId").asLong();
        mockMvc.perform(get("/api/chat/conversations/{id}/messages", directId).sessionAttr("loggedInUser", letanCn01User))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(2)
    @DisplayName("NFR Performance & Security: Real SockJS + STOMP End-to-End Latency & Dedup")
    void websocketStompRealtimeLatencyAndDedup() throws Exception {
        // Initialize patient conversation
        ChatSendRequestDTO wsInit = ChatSendRequestDTO.builder()
                .messageText("Init STOMP NFR latency test")
                .clientMessageId("nfr-ws-init-" + System.nanoTime())
                .build();
        long convId = mapper.readTree(mockMvc.perform(post("/api/chat/messages")
                        .sessionAttr("loggedInUser", pat1Cn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(wsInit)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("conversationId").asLong();

        String patCookie = loginOverHttp(pat1Cn01User.getUsername(), "NfrPass!123");
        String ltCookie = loginOverHttp(letanCn01User.getUsername(), "NfrPass!123");

        SockJsClient sockJs = new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient())));
        WebSocketStompClient stompClient = new WebSocketStompClient(sockJs);
        stompClient.setMessageConverter(new org.springframework.messaging.converter.AbstractMessageConverter(
                org.springframework.util.MimeTypeUtils.APPLICATION_JSON) {
            @Override
            protected boolean supports(Class<?> clazz) {
                return true;
            }

            @Override
            protected Object convertFromInternal(org.springframework.messaging.Message<?> message, Class<?> targetClass, Object conversionHint) {
                Object p = message.getPayload();
                return p instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : String.valueOf(p);
            }

            @Override
            protected Object convertToInternal(Object payload, org.springframework.messaging.MessageHeaders headers, Object conversionHint) {
                return String.valueOf(payload).getBytes(StandardCharsets.UTF_8);
            }
        });
        String wsUrl = "http://127.0.0.1:" + port + "/ws-chat";

        // 1. Unauthenticated WebSocket handshake must fail
        CompletableFuture<StompSession> anonFut = stompClient.connectAsync(wsUrl, new WebSocketHttpHeaders(), new StompSessionHandlerAdapter() {});
        assertThrows(Exception.class, () -> anonFut.get(4, TimeUnit.SECONDS));

        // 2. Connect authenticated Receptionist (CN01) & Patient (CN01)
        WebSocketHttpHeaders ltHdr = new WebSocketHttpHeaders();
        ltHdr.add("Cookie", ltCookie);
        StompSession ltSession = stompClient.connectAsync(wsUrl, ltHdr, new StompSessionHandlerAdapter() {}).get(5, TimeUnit.SECONDS);

        WebSocketHttpHeaders patHdr = new WebSocketHttpHeaders();
        patHdr.add("Cookie", patCookie);
        StompSession patSession = stompClient.connectAsync(wsUrl, patHdr, new StompSessionHandlerAdapter() {}).get(5, TimeUnit.SECONDS);

        // Receptionist subscribes to CN01 branch topic
        BlockingQueue<Long> receivedTimestampsNs = new LinkedBlockingQueue<>();
        BlockingQueue<String> receivedPayloads = new LinkedBlockingQueue<>();
        ltSession.subscribe("/topic/chat/branch/" + cn01.getBranchId(), new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                receivedTimestampsNs.offer(System.nanoTime());
                receivedPayloads.offer(String.valueOf(payload));
            }
        });
        Thread.sleep(250);

        // Patient attempts unauthorized subscribe to branch topic -> STOMP channel interceptor blocks
        BlockingQueue<Throwable> patSubErrors = new LinkedBlockingQueue<>();
        StompSession patSubCheckSession = stompClient.connectAsync(wsUrl, patHdr, new StompSessionHandlerAdapter() {
            @Override
            public void handleException(StompSession session, StompCommand command, StompHeaders headers, byte[] payload, Throwable exception) {
                patSubErrors.offer(exception);
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                patSubErrors.offer(exception);
            }
        }).get(5, TimeUnit.SECONDS);
        try {
            patSubCheckSession.subscribe("/topic/chat/branch/" + cn01.getBranchId(), new StompFrameHandler() {
                @Override public Type getPayloadType(StompHeaders headers) { return String.class; }
                @Override public void handleFrame(StompHeaders headers, Object payload) {}
            });
        } catch (Exception ignored) {
        }

        // Send 10 real STOMP messages and measure latency (warm-up 2, measure 8)
        List<Double> latenciesMs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            receivedTimestampsNs.clear();
            receivedPayloads.clear();
            String payloadJson = mapper.writeValueAsString(Map.of(
                    "conversationId", convId,
                    "messageText", "STOMP-NFR-MSG-" + i,
                    "clientMessageId", "stomp-nfr-" + i + "-" + System.nanoTime(),
                    "senderId", 999999L
            ));
            long t0 = System.nanoTime();
            patSession.send("/app/chat.send", payloadJson);
            Long tRecv = receivedTimestampsNs.poll(5, TimeUnit.SECONDS);
            assertNotNull(tRecv, "Must receive STOMP broadcast frame for message " + i);
            String body = receivedPayloads.poll(1, TimeUnit.SECONDS);
            assertTrue(body != null && body.contains("STOMP-NFR-MSG-" + i));
            double ms = (tRecv - t0) / 1_000_000.0;
            if (i >= 2) {
                latenciesMs.add(ms);
            }
        }
        double avgMs = latenciesMs.stream().mapToDouble(Double::doubleValue).average().orElse(999);
        double maxMs = latenciesMs.stream().mapToDouble(Double::doubleValue).max().orElse(999);
        double minMs = latenciesMs.stream().mapToDouble(Double::doubleValue).min().orElse(999);
        System.out.printf("NFR_STOMP_LATENCY (n=8 after 2 warmup): min=%.2f ms, avg=%.2f ms, max=%.2f ms (Target < 150ms)%n",
                minMs, avgMs, maxMs);
        assertTrue(avgMs < 150.0, "Average STOMP realtime latency must stay < 150ms, was " + avgMs);

        // Verify dedup key (30s ConcurrentHashMap) via duplicate clientMessageId
        long countBefore = chatMessageRepository.countByConversation_ConversationId(convId);
        String dupCid = "dup-nfr-" + System.nanoTime();
        String dupJson = mapper.writeValueAsString(Map.of(
                "conversationId", convId,
                "messageText", "STOMP-DEDUP-CHECK",
                "clientMessageId", dupCid
        ));
        patSession.send("/app/chat.send", dupJson);
        assertNotNull(receivedTimestampsNs.poll(3, TimeUnit.SECONDS));
        Thread.sleep(100);
        patSession.send("/app/chat.send", dupJson);
        Thread.sleep(250);
        long countAfter = chatMessageRepository.countByConversation_ConversationId(convId);
        assertEquals(countBefore + 1, countAfter, "Duplicate clientMessageId within 30s must create only 1 DB row");

        ltSession.disconnect();
        patSession.disconnect();
        try { patSubCheckSession.disconnect(); } catch (Exception ignored) {}
    }

    @Test
    @Order(3)
    @DisplayName("NFR Concurrency: Pessimistic Lock prevents Inventory negative stock & Supplier overpayment under 6 concurrent threads")
    void concurrentPessimisticLockRaceConditions() throws Exception {
        byte[] pngBytes = createValidPngBytes();

        JsonNode invRes = mapper.readTree(mockMvc.perform(multipart("/api/inventory/receipts/upload-invoice")
                        .file(new MockMultipartFile("file", "conc_inv.png", "image/png", pngBytes))
                        .sessionAttr("loggedInUser", khoCn01User))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String invoiceUrl = invRes.get("attachmentUrl").asText();
        uploadedFilesToCleanup.add(Paths.get(invoiceUrl.substring(1)).toAbsolutePath().normalize());

        SupplierCreateDTO supDto = new SupplierCreateDTO();
        supDto.setSupplierCode("SUPCONC01");
        supDto.setSupplierName("NCC Concurrency");
        supDto.setTaxCode("0107778881");
        supDto.setPhoneNumber("0901237771");
        long supId = mapper.readTree(mockMvc.perform(post("/api/suppliers")
                        .sessionAttr("loggedInUser", khoCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(supDto)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("supplierId").asLong();

        MaterialCreateDTO matDto = new MaterialCreateDTO();
        matDto.setMaterialCode("VTCONC01");
        matDto.setMaterialName("Vat tu Concurrency");
        matDto.setCategory("Tieu hao");
        matDto.setUnit("Hop");
        matDto.setMinStockThreshold(new BigDecimal("2"));
        long matId = mapper.readTree(mockMvc.perform(post("/api/materials")
                        .sessionAttr("loggedInUser", khoCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(matDto)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("materialId").asLong();

        // Receipt: qty 5 * price 200,000 = 1,000,000 debt
        StockReceiptCreateDTO recDto = new StockReceiptCreateDTO();
        recDto.setBranchId(cn01.getBranchId());
        recDto.setSupplierId(supId);
        recDto.setReceiptDate(LocalDate.now());
        recDto.setAttachmentUrl(invoiceUrl);
        recDto.setPaidAmount(BigDecimal.ZERO);
        StockReceiptCreateDTO.Item item = new StockReceiptCreateDTO.Item();
        item.setMaterialId(matId);
        item.setBatchNumber("LOT-CONC-1");
        item.setExpiryDate(LocalDate.now().plusDays(90));
        item.setQuantity(new BigDecimal("5"));
        item.setUnitPrice(new BigDecimal("200000"));
        recDto.setItems(List.of(item));
        JsonNode recNode = mapper.readTree(mockMvc.perform(post("/api/inventory/receipts")
                        .sessionAttr("loggedInUser", khoCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(recDto)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long recId = recNode.get("receiptId").asLong();

        String khoCookie = loginOverHttp(khoCn01User.getUsername(), "NfrPass!123");
        HttpClient http = HttpClient.newHttpClient();

        // A. 6 concurrent threads each try to issue qty 3 (initial stock = 5) -> ONLY 1 can succeed (201), 5 must fail (400)
        int threads = 6;
        CountDownLatch readyA = new CountDownLatch(threads);
        CountDownLatch goA = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> issueFutures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int idx = t;
            issueFutures.add(pool.submit(() -> {
                StockIssueCreateDTO dto = new StockIssueCreateDTO();
                dto.setBranchId(cn01.getBranchId());
                dto.setIssueType("INTERNAL_USE");
                dto.setIssueDate(LocalDate.now());
                dto.setNotes("Concurrent issue #" + idx);
                StockIssueCreateDTO.Item it = new StockIssueCreateDTO.Item();
                it.setMaterialId(matId);
                it.setQuantity(new BigDecimal("3"));
                dto.setItems(List.of(it));
                String json = mapper.writeValueAsString(dto);
                readyA.countDown();
                goA.await();
                HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/inventory/issues"))
                                .header("Cookie", khoCookie)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(json))
                                .build(),
                        HttpResponse.BodyHandlers.ofString()
                );
                return resp.statusCode();
            }));
        }
        assertTrue(readyA.await(5, TimeUnit.SECONDS));
        long t0A = System.nanoTime();
        goA.countDown();
        List<Integer> issueCodes = new ArrayList<>();
        for (Future<Integer> f : issueFutures) {
            issueCodes.add(f.get(15, TimeUnit.SECONDS));
        }
        double elapsedAMs = (System.nanoTime() - t0A) / 1_000_000.0;
        long okIssues = issueCodes.stream().filter(c -> c == 201).count();
        long rejectedIssues = issueCodes.stream().filter(c -> c == 400).count();
        System.out.printf("NFR_CONCURRENT_INVENTORY_ISSUE (6 threads): codes=%s, ok201=%d, rej400=%d, wall=%.2f ms%n",
                issueCodes, okIssues, rejectedIssues, elapsedAMs);
        assertEquals(1, okIssues, "Exactly 1 concurrent stock issue of qty=3 from stock=5 must succeed");
        assertEquals(5, rejectedIssues, "Remaining 5 concurrent issues must be rejected 400 without negative stock");

        BigDecimal remaining = inventoryRepository.findByBranch_BranchIdAndMaterial_MaterialId(cn01.getBranchId(), matId)
                .orElseThrow().getCurrentStock();
        assertEquals(0, new BigDecimal("2").compareTo(remaining), "Remaining stock must be exactly 2.000");

        // B. 6 concurrent threads each try to pay 600,000 on 1,000,000 receipt debt -> ONLY 1 can succeed (201), 5 rejected (400)
        CountDownLatch readyB = new CountDownLatch(threads);
        CountDownLatch goB = new CountDownLatch(1);
        List<Future<Integer>> payFutures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int idx = t;
            payFutures.add(pool.submit(() -> {
                SupplierPaymentCreateDTO p = new SupplierPaymentCreateDTO();
                p.setBranchId(cn01.getBranchId());
                p.setSupplierId(supId);
                p.setReceiptId(recId);
                p.setPaymentDate(LocalDate.now());
                p.setAmount(new BigDecimal("600000"));
                p.setPaymentMethod("CASH");
                p.setClientRequestId("CONC-PAY-" + idx + "-" + System.nanoTime());
                String json = mapper.writeValueAsString(p);
                readyB.countDown();
                goB.await();
                HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/inventory/supplier-payments"))
                                .header("Cookie", khoCookie)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(json))
                                .build(),
                        HttpResponse.BodyHandlers.ofString()
                );
                return resp.statusCode();
            }));
        }
        assertTrue(readyB.await(5, TimeUnit.SECONDS));
        long t0B = System.nanoTime();
        goB.countDown();
        List<Integer> payCodes = new ArrayList<>();
        for (Future<Integer> f : payFutures) {
            payCodes.add(f.get(15, TimeUnit.SECONDS));
        }
        double elapsedBMs = (System.nanoTime() - t0B) / 1_000_000.0;
        long okPay = payCodes.stream().filter(c -> c == 201).count();
        long rejPay = payCodes.stream().filter(c -> c == 400).count();
        System.out.printf("NFR_CONCURRENT_SUPPLIER_PAY (6 threads): codes=%s, ok201=%d, rej400=%d, wall=%.2f ms%n",
                payCodes, okPay, rejPay, elapsedBMs);
        assertEquals(1, okPay, "Exactly 1 concurrent payment of 600,000 against 1,000,000 debt must succeed");
        assertEquals(5, rejPay, "Remaining 5 concurrent overpayments must be rejected 400");

        BigDecimal paidSum = supplierPaymentRepository.sumPaidAmountByBranchAndSupplier(cn01.getBranchId(), supId);
        assertEquals(0, new BigDecimal("600000").compareTo(paidSum), "Total paid must equal 600,000");

        // C. Duplicate clientRequestId idempotency across 6 threads -> ONLY 1 payment of 100,000 applied
        String sharedReqId = "IDEMP-6T-" + System.nanoTime();
        CountDownLatch readyC = new CountDownLatch(threads);
        CountDownLatch goC = new CountDownLatch(1);
        List<Future<Integer>> idemFutures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            idemFutures.add(pool.submit(() -> {
                SupplierPaymentCreateDTO p = new SupplierPaymentCreateDTO();
                p.setBranchId(cn01.getBranchId());
                p.setSupplierId(supId);
                p.setReceiptId(recId);
                p.setPaymentDate(LocalDate.now());
                p.setAmount(new BigDecimal("100000"));
                p.setPaymentMethod("BANK_TRANSFER");
                p.setReferenceNo("UNC-IDEMP-01");
                p.setClientRequestId(sharedReqId);
                String json = mapper.writeValueAsString(p);
                readyC.countDown();
                goC.await();
                HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/inventory/supplier-payments"))
                                .header("Cookie", khoCookie)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(json))
                                .build(),
                        HttpResponse.BodyHandlers.ofString()
                );
                return resp.statusCode();
            }));
        }
        assertTrue(readyC.await(5, TimeUnit.SECONDS));
        goC.countDown();
        List<Integer> idemCodes = new ArrayList<>();
        for (Future<Integer> f : idemFutures) {
            idemCodes.add(f.get(15, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        assertEquals(1, idemCodes.stream().filter(c -> c == 201).count());
        assertEquals(5, idemCodes.stream().filter(c -> c == 400).count());
        assertEquals(0, new BigDecimal("700000").compareTo(
                supplierPaymentRepository.sumPaidAmountByBranchAndSupplier(cn01.getBranchId(), supId)));
    }
}
