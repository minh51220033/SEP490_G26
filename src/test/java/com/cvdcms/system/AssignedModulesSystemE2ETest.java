package com.cvdcms.system;

import com.cvdcms.DTO.*;
import com.cvdcms.config.ChatStompPrincipal;
import com.cvdcms.controller.ChatWebSocketController;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-Stack System E2E Suite for Hieudx's 4 assigned modules:
 * 1. Chatbox (FT45 / UC-27): ST-CB-01 .. ST-CB-06
 * 2. Inventory & Branch Stock (FT27 / UC-20): ST-IV-01 .. ST-IV-07
 * 3. Customer Care (FT44 / UC-26): ST-CC-01 .. ST-CC-06
 * 4. Suppliers & Payables (FT26 / UC-19): ST-SP-01 .. ST-SP-05
 *
 * Uses real Spring Boot context, Controllers, Interceptors, Services, Repositories,
 * Spring Messaging / WebSocket controller, and H2 (MODE=MySQL) without modifying cv_dcms_db.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:assigned_st_e2e_db;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;NON_KEYWORDS=MONTH,YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AssignedModulesSystemE2ETest {

    @Autowired private WebApplicationContext wac;
    @Autowired private BranchRepository branchRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffProfileRepository staffRepository;
    @Autowired private StaffBranchAssignmentRepository assignmentRepository;
    @Autowired private PatientProfileRepository patientRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private MedicationRepository medicationRepository;
    @Autowired private MaterialBatchRepository batchRepository;
    @Autowired private BranchInventoryRepository inventoryRepository;
    @Autowired private StockReceiptRepository receiptRepository;
    @Autowired private StockIssueRepository issueRepository;
    @Autowired private SupplierPaymentRepository supplierPaymentRepository;
    @Autowired private VisitRepository visitRepository;
    @Autowired private PrescriptionRepository prescriptionRepository;
    @Autowired private CustomerCareTaskRepository careTaskRepository;
    @Autowired private SystemAuditLogRepository auditLogRepository;
    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private ChatWebSocketController chatWebSocketController;
    @Autowired private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final List<Path> uploadedFilesToCleanup = new ArrayList<>();

    private Branch cn01;
    private Branch cn02;

    private User adminUser;
    private User khoCn01User;
    private User khoCn02User;
    private User ketoanCn01User;
    private User letanCn01User;
    private User letanCn02User;
    private User bacsiCn01User;
    private User patientCn01User;

    private StaffProfile khoCn01Staff;
    private StaffProfile letanCn01Staff;
    private StaffProfile bacsiCn01Staff;
    private PatientProfile patientProfileCn01;

    @BeforeEach
    void initFixtures() {
        SimpleModule timeModule = new SimpleModule();
        timeModule.addSerializer(LocalDate.class, ToStringSerializer.instance);
        timeModule.addSerializer(LocalDateTime.class, ToStringSerializer.instance);
        mapper.registerModule(timeModule);

        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();

        cn01 = createBranch("CN01-" + tag, "Cơ sở Quận 1 - " + tag);
        cn02 = createBranch("CN02-" + tag, "Cơ sở Đống Đa - " + tag);

        adminUser = createStaffUser("admin_" + tag, "ADMIN", "Quản trị " + tag, cn01, false).user();
        var k1 = createStaffUser("kho1_" + tag, "QUAN_LY_KHO", "Thủ kho 1 " + tag, cn01, true);
        khoCn01User = k1.user();
        khoCn01Staff = k1.staff();
        khoCn02User = createStaffUser("kho2_" + tag, "QUAN_LY_KHO", "Thủ kho 2 " + tag, cn02, true).user();
        ketoanCn01User = createStaffUser("kt1_" + tag, "KE_TOAN", "Kế toán 1 " + tag, cn01, true).user();
        var lt1 = createStaffUser("lt1_" + tag, "LE_TAN", "Lễ tân 1 " + tag, cn01, true);
        letanCn01User = lt1.user();
        letanCn01Staff = lt1.staff();
        letanCn02User = createStaffUser("lt2_" + tag, "LE_TAN", "Lễ tân 2 " + tag, cn02, true).user();
        var bs1 = createStaffUser("bs1_" + tag, "BAC_SI", "BS Lâm sàng " + tag, cn01, true);
        bacsiCn01User = bs1.user();
        bacsiCn01Staff = bs1.staff();

        Role bnRole = roleRepository.findByRoleName("BENH_NHAN").orElseThrow();
        patientCn01User = new User();
        patientCn01User.setUsername("bn_" + tag.toLowerCase());
        patientCn01User.setPassword("$2a$10$dummyhashvalue123456789012345678901234567890123456");
        patientCn01User.setEmail("bn_" + tag.toLowerCase() + "@mail.test");
        patientCn01User.setPhoneNumber("0919" + Math.abs(tag.hashCode() % 1000000));
        patientCn01User.setUserType(UserType.PATIENT);
        patientCn01User.setStatus("ACTIVE");
        patientCn01User.setIsActive(true);
        patientCn01User.setBranch(cn01);
        patientCn01User.setRoles(new HashSet<>(Set.of(bnRole)));
        patientCn01User = userRepository.saveAndFlush(patientCn01User);

        patientProfileCn01 = new PatientProfile();
        patientProfileCn01.setUser(patientCn01User);
        patientProfileCn01.setPatientCode("BN-" + tag);
        patientProfileCn01.setFullName("Nguyễn Minh Bệnh Nhân " + tag);
        patientProfileCn01.setPhoneNumber("0919" + String.format("%06d", Math.abs(tag.hashCode()) % 1000000));
        patientProfileCn01.setGender("Nam");
        patientProfileCn01.setDob(LocalDate.of(1995, 5, 10));
        patientProfileCn01.setAddress("123 Phố Huế, Hà Nội");
        patientProfileCn01.setStatus("ACTIVE");
        patientProfileCn01.setPreferredBranch(cn01);
        patientProfileCn01 = patientRepository.saveAndFlush(patientProfileCn01);
    }

    @AfterEach
    void cleanupUploadedArtifacts() {
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
        b.setAddress("123 Clinic St");
        b.setPhone("02439998888");
        b.setOpeningTime(LocalTime.of(8, 0));
        b.setClosingTime(LocalTime.of(20, 0));
        b.setIsActive(true);
        return branchRepository.saveAndFlush(b);
    }

    private record StaffPair(User user, StaffProfile staff) {}

    private StaffPair createStaffUser(String username, String roleName, String fullName, Branch branch, boolean assignBranch) {
        Role role = roleRepository.findByRoleName(roleName).orElseThrow();
        User u = new User();
        u.setUsername(username);
        u.setPassword("$2a$10$dummyhashvalue123456789012345678901234567890123456");
        u.setEmail(username + "@clinic.test");
        u.setPhoneNumber("090" + String.format("%07d", Math.abs(username.hashCode()) % 10000000));
        u.setUserType(UserType.STAFF);
        u.setStatus("ACTIVE");
        u.setIsActive(true);
        u.setBranch(branch);
        u.setRoles(new HashSet<>(Set.of(role)));
        u.setCustomPermissionsEnabled(false);
        u = userRepository.saveAndFlush(u);

        StaffProfile s = new StaffProfile();
        s.setUser(u);
        s.setStaffCode("NV-" + username.toUpperCase());
        s.setFullName(fullName);
        s.setPhoneNumber(u.getPhoneNumber());
        s.setEmail(u.getEmail());
        s.setStatus(StaffStatus.ACTIVE);
        s = staffRepository.saveAndFlush(s);

        if (assignBranch) {
            StaffBranchAssignment a = new StaffBranchAssignment();
            a.setStaff(s);
            a.setBranch(branch);
            a.setRole(role);
            a.setIsPrimary(true);
            a.setStartDate(LocalDate.now().minusDays(10));
            a.setStatus(AssignmentStatus.ACTIVE);
            assignmentRepository.saveAndFlush(a);
        }
        return new StaffPair(u, s);
    }

    private byte[] createNonBlankPngBytes() throws Exception {
        BufferedImage img = new BufferedImage(80, 30, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 80, 30);
        g.setColor(Color.BLUE);
        g.setStroke(new BasicStroke(3));
        g.drawLine(5, 5, 70, 25);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private String uploadTestInvoice(User actor) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "invoice-test.png", "image/png", createNonBlankPngBytes()
        );
        MvcResult res = mockMvc.perform(multipart("/api/inventory/receipts/upload-invoice")
                        .file(file)
                        .sessionAttr("loggedInUser", actor))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = mapper.readTree(res.getResponse().getContentAsString());
        String url = root.get("attachmentUrl").asText();
        trackUploadCleanup(url);
        return url;
    }

    private void trackUploadCleanup(String url) {
        if (url != null && url.startsWith("/uploads/")) {
            Path p = Paths.get(url.substring(1)).toAbsolutePath().normalize();
            uploadedFilesToCleanup.add(p);
        }
    }

    // =========================================================================
    // MODULE 1: CHATBOX (FT45 / UC-27) -> ST-CB-01 .. ST-CB-06
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("ST-CB-01..06: End-to-end Chatbox messaging, unread isolation, 10m edit/recall, self-delete, direct & RBAC")
    void chatboxEndToEndSystemFlows() throws Exception {
        // ST-CB-01: Patient initiates first message (no AI bot), STOMP/REST visible to branch staff & bidirectional reply
        MvcResult patientPage = mockMvc.perform(get("/chat").sessionAttr("loggedInUser", letanCn01User))
                .andExpect(status().isOk()).andReturn();
        assertTrue(patientPage.getResponse().getContentAsString().contains("Hộp thư"));

        ChatSendRequestDTO firstReq = ChatSendRequestDTO.builder()
                .messageText("Xin chào phòng khám, tôi cần hỏi giá cạo vôi răng")
                .clientMessageId("st-cb-01-" + System.nanoTime())
                .build();
        MvcResult firstSendRes = mockMvc.perform(post("/api/chat/messages")
                        .sessionAttr("loggedInUser", patientCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(firstReq)))
                .andExpect(status().isOk()).andReturn();
        JsonNode msg1 = mapper.readTree(firstSendRes.getResponse().getContentAsString());
        long convId = msg1.get("conversationId").asLong();
        long msg1Id = msg1.get("messageId").asLong();
        assertTrue(msg1.get("firstMessageInConversation").asBoolean());
        assertNotNull(msg1.get("systemNotice").asText());
        assertEquals("PATIENT", msg1.get("senderType").asText());

        // Also test WebSocket controller path sending a 2nd patient message
        ChatSendRequestDTO wsReq = ChatSendRequestDTO.builder()
                .conversationId(convId)
                .messageText("Tôi muốn khám cuối tuần")
                .clientMessageId("st-cb-ws-" + System.nanoTime())
                .build();
        chatWebSocketController.handleSendMessage(wsReq,
                new ChatStompPrincipal(patientCn01User.getUserId(), patientCn01User.getUsername()), null);

        // Dedup same clientMessageId returns existing message without creating 2nd row
        long beforeDupCount = chatMessageRepository.countByConversation_ConversationId(convId);
        mockMvc.perform(post("/api/chat/messages")
                        .sessionAttr("loggedInUser", patientCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(firstReq)))
                .andExpect(status().isOk());
        assertEquals(beforeDupCount, chatMessageRepository.countByConversation_ConversationId(convId));

        // ST-CB-02: Per-user independent unread tracking (BR-CHAT-02)
        JsonNode ltUnreadBefore = mapper.readTree(mockMvc.perform(get("/api/chat/unread-count")
                        .sessionAttr("loggedInUser", letanCn01User))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode bsUnreadBefore = mapper.readTree(mockMvc.perform(get("/api/chat/unread-count")
                        .sessionAttr("loggedInUser", bacsiCn01User))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(ltUnreadBefore.get("totalUnreadCount").asLong() >= 2);
        assertTrue(bsUnreadBefore.get("totalUnreadCount").asLong() >= 2);

        // Receptionist marks read -> Receptionist unread=0, Doctor unread stays >=2!
        mockMvc.perform(post("/api/chat/conversations/{id}/read", convId)
                        .sessionAttr("loggedInUser", letanCn01User))
                .andExpect(status().isOk());
        JsonNode ltUnreadAfter = mapper.readTree(mockMvc.perform(get("/api/chat/unread-count")
                        .sessionAttr("loggedInUser", letanCn01User))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode bsUnreadStill = mapper.readTree(mockMvc.perform(get("/api/chat/unread-count")
                        .sessionAttr("loggedInUser", bacsiCn01User))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(0L, ltUnreadAfter.get("totalUnreadCount").asLong());
        assertTrue(bsUnreadStill.get("totalUnreadCount").asLong() >= 2);

        // Receptionist replies to patient
        ChatSendRequestDTO replyReq = ChatSendRequestDTO.builder()
                .conversationId(convId)
                .messageText("Dạ cạo vôi răng tại CN01 giá 300.000đ ạ.")
                .build();
        JsonNode replyNode = mapper.readTree(mockMvc.perform(post("/api/chat/messages")
                        .sessionAttr("loggedInUser", letanCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(replyReq)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long replyMsgId = replyNode.get("messageId").asLong();

        // ST-CB-03: Edit & Recall within 10 mins; block editing others' msg & block >10m expired msg
        mockMvc.perform(put("/api/chat/messages/{id}", replyMsgId)
                        .sessionAttr("loggedInUser", letanCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageText\":\"Cạo vôi siêu âm CN01 là 300.000đ (đã cập nhật).\"}"))
                .andExpect(status().isOk());

        // Patient trying to edit staff message -> 403
        mockMvc.perform(put("/api/chat/messages/{id}", replyMsgId)
                        .sessionAttr("loggedInUser", patientCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageText\":\"Hack edit\"}"))
                .andExpect(status().isForbidden());

        // Patient recalls msg1 -> placeholder text reflected
        JsonNode recalled = mapper.readTree(mockMvc.perform(post("/api/chat/messages/{id}/recall", msg1Id)
                        .sessionAttr("loggedInUser", patientCn01User))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(recalled.path("recalled").asBoolean() || recalled.path("isRecalled").asBoolean());

        // Force msg older than 11 minutes -> recall fails 400 (sent_at is updatable=false in JPA)
        jdbcTemplate.update("UPDATE chat_messages SET sent_at = ? WHERE message_id = ?",
                java.sql.Timestamp.valueOf(LocalDateTime.now().minusMinutes(11)), replyMsgId);
        mockMvc.perform(post("/api/chat/messages/{id}/recall", replyMsgId)
                        .sessionAttr("loggedInUser", letanCn01User))
                .andExpect(status().isBadRequest());

        // ST-CB-04: Self-delete message & conversation hides only for deleting user, intact for counterpart
        mockMvc.perform(delete("/api/chat/messages/{id}/self", replyMsgId)
                        .sessionAttr("loggedInUser", letanCn01User))
                .andExpect(status().isOk());
        JsonNode patMsgs = mapper.readTree(mockMvc.perform(get("/api/chat/conversations/{id}/messages", convId)
                        .sessionAttr("loggedInUser", patientCn01User))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(patMsgs.get("messages").toString().contains("300.000đ (đã cập nhật)"));

        mockMvc.perform(delete("/api/chat/conversations/{id}/self", convId)
                        .sessionAttr("loggedInUser", letanCn01User))
                .andExpect(status().isOk());
        JsonNode ltConvs = mapper.readTree(mockMvc.perform(get("/api/chat/conversations")
                        .sessionAttr("loggedInUser", letanCn01User))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(0, ltConvs.get("content").size());

        // ST-CB-05: Direct internal staff chat (Accountant CN01 <-> Doctor CN01) hidden from Receptionist CN01
        JsonNode directStart = mapper.readTree(mockMvc.perform(post("/api/chat/conversations/start")
                        .sessionAttr("loggedInUser", ketoanCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("targetUserId", bacsiCn01User.getUserId(), "branchId", cn01.getBranchId()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long directConvId = directStart.get("conversationId").asLong();
        mockMvc.perform(post("/api/chat/messages")
                        .sessionAttr("loggedInUser", ketoanCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("conversationId", directConvId, "messageText", "Anh BS check giúp ca đối soát"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/chat/conversations/{id}/messages", directConvId)
                        .sessionAttr("loggedInUser", letanCn01User))
                .andExpect(status().isForbidden());

        // ST-CB-06: Role & Branch Isolation (BR-CHAT-01, BR-CHAT-03)
        mockMvc.perform(get("/api/chat/conversations/{id}/messages", convId)
                        .sessionAttr("loggedInUser", ketoanCn01User))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/chat/conversations/{id}/messages", convId)
                        .sessionAttr("loggedInUser", khoCn01User))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/chat/conversations/{id}/messages", convId)
                        .sessionAttr("loggedInUser", letanCn02User))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/chat/recipients")
                        .sessionAttr("loggedInUser", patientCn01User))
                .andExpect(status().isForbidden());
        assertThrows(AccessDeniedException.class, () ->
                chatWebSocketController.handleSendMessage(wsReq, null, null));
    }

    // =========================================================================
    // MODULE 2: INVENTORY (FT27 / UC-20) -> ST-IV-01 .. ST-IV-07
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("ST-IV-01..07: End-to-end Materials BR-CAT-08, Receipt/Invoice/PDF, FEFO Issue, Expired block, Transfer & Isolation")
    void inventoryEndToEndSystemFlows() throws Exception {
        // ST-IV-01: Create Material & test BR-CAT-08 lock on medication-synced Material
        MaterialCreateDTO matDto = new MaterialCreateDTO();
        matDto.setMaterialCode("VT-E2E-COMP");
        matDto.setMaterialName("Composite E2E 3M");
        matDto.setCategory("Trám răng");
        matDto.setUnit("Ống");
        matDto.setMinStockThreshold(new BigDecimal("10"));

        JsonNode createdMat = mapper.readTree(mockMvc.perform(post("/api/materials")
                        .sessionAttr("loggedInUser", khoCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(matDto)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long matId = createdMat.get("materialId").asLong();

        Material syncedMat = new Material();
        syncedMat.setMaterialCode("TH-E2E-SYNC");
        syncedMat.setMaterialName("Thuốc E2E Sync");
        syncedMat.setCategory("Kháng sinh");
        syncedMat.setUnit("Viên");
        syncedMat.setMinStockThreshold(new BigDecimal("20"));
        syncedMat.setIsActive(true);
        syncedMat = materialRepository.saveAndFlush(syncedMat);

        Medication med = new Medication();
        med.setDrugCode("DRUG-E2E");
        med.setDrugName("Thuốc E2E Sync");
        med.setUnit("Viên");
        med.setMaterial(syncedMat);
        med.setIsActive(true);
        med = medicationRepository.saveAndFlush(med);
        syncedMat.setMedication(med);
        syncedMat = materialRepository.saveAndFlush(syncedMat);

        MaterialUpdateDTO renameAttempt = new MaterialUpdateDTO();
        renameAttempt.setMaterialCode("TH-CHANGED");
        renameAttempt.setMaterialName("Thuốc E2E Sync");
        renameAttempt.setCategory("Kháng sinh");
        renameAttempt.setUnit("Viên");
        renameAttempt.setMinStockThreshold(new BigDecimal("20"));
        mockMvc.perform(put("/api/materials/{id}", syncedMat.getMaterialId())
                        .sessionAttr("loggedInUser", khoCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(renameAttempt)))
                .andExpect(status().isBadRequest());

        // Allowed threshold/category update on synced material
        renameAttempt.setMaterialCode("TH-E2E-SYNC");
        renameAttempt.setMinStockThreshold(new BigDecimal("25"));
        mockMvc.perform(put("/api/materials/{id}", syncedMat.getMaterialId())
                        .sessionAttr("loggedInUser", khoCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(renameAttempt)))
                .andExpect(status().isOk());

        // Create active supplier for receipts
        Supplier sup = new Supplier();
        sup.setSupplierCode("NCC-IV-" + System.nanoTime() % 10000);
        sup.setSupplierName("NCC Vật tư E2E");
        sup.setTaxCode("010888" + String.format("%04d", Math.abs((int) (System.nanoTime() % 10000))));
        sup.setPhoneNumber("0903334455");
        sup.setAddress("Hà Nội");
        sup.setIsActive(true);
        sup = supplierRepository.saveAndFlush(sup);

        // ST-IV-02: Upload invoice + Create Stock Receipt with 2 FEFO batches + PDF & A4 Print
        String invoiceUrl = uploadTestInvoice(khoCn01User);

        StockReceiptCreateDTO receiptDto = new StockReceiptCreateDTO();
        receiptDto.setBranchId(cn01.getBranchId());
        receiptDto.setSupplierId(sup.getSupplierId());
        receiptDto.setSupplierInvoiceNo("HD-E2E-01");
        receiptDto.setReceiptDate(LocalDate.now());
        receiptDto.setAttachmentUrl(invoiceUrl);
        receiptDto.setPaidAmount(new BigDecimal("500000"));
        receiptDto.setPaymentMethod("BANK_TRANSFER");
        receiptDto.setPaymentReferenceNo("UNC-E2E-01");

        StockReceiptCreateDTO.Item bNear = new StockReceiptCreateDTO.Item();
        bNear.setMaterialId(matId);
        bNear.setBatchNumber("LOT-NEAR-01");
        bNear.setExpiryDate(LocalDate.now().plusDays(15));
        bNear.setQuantity(new BigDecimal("5"));
        bNear.setUnitPrice(new BigDecimal("100000"));

        StockReceiptCreateDTO.Item bFar = new StockReceiptCreateDTO.Item();
        bFar.setMaterialId(matId);
        bFar.setBatchNumber("LOT-FAR-02");
        bFar.setExpiryDate(LocalDate.now().plusDays(180));
        bFar.setQuantity(new BigDecimal("10"));
        bFar.setUnitPrice(new BigDecimal("100000"));
        receiptDto.setItems(List.of(bNear, bFar));

        JsonNode receiptRes = mapper.readTree(mockMvc.perform(post("/api/inventory/receipts")
                        .sessionAttr("loggedInUser", khoCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(receiptDto)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long receiptId = receiptRes.get("receiptId").asLong();
        assertEquals(0, new BigDecimal("1500000").compareTo(new BigDecimal(receiptRes.get("totalAmount").asText())));
        assertEquals(0, new BigDecimal("1000000").compareTo(new BigDecimal(receiptRes.get("remainingDebt").asText())));

        MvcResult pdfRes = mockMvc.perform(get("/api/inventory/receipts/{id}/pdf", receiptId)
                        .sessionAttr("loggedInUser", khoCn01User))
                .andExpect(status().isOk()).andReturn();
        assertTrue(pdfRes.getResponse().getContentAsByteArray().length > 500);
        mockMvc.perform(get("/inventory/receipts/{id}/print", receiptId)
                        .sessionAttr("loggedInUser", khoCn01User))
                .andExpect(status().isOk());

        // ST-IV-03: Reject invalid receipt (missing invoice & past expiry) -> zero stock change!
        StockReceiptCreateDTO badReceipt = new StockReceiptCreateDTO();
        badReceipt.setBranchId(cn01.getBranchId());
        badReceipt.setSupplierId(sup.getSupplierId());
        badReceipt.setReceiptDate(LocalDate.now());
        badReceipt.setItems(List.of(bNear));
        mockMvc.perform(post("/api/inventory/receipts")
                        .sessionAttr("loggedInUser", khoCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(badReceipt)))
                .andExpect(status().isBadRequest());
        assertEquals(0, new BigDecimal("15").compareTo(
                inventoryRepository.findByBranch_BranchIdAndMaterial_MaterialId(cn01.getBranchId(), matId)
                        .orElseThrow().getCurrentStock()));

        // ST-IV-04: Stock Issue INTERNAL_USE (qty=7) without batchId -> FEFO consumes LOT-NEAR-01 (5) + LOT-FAR-02 (2) -> stock=8 <= threshold 10 (lowStock)!
        StockIssueCreateDTO fefoIssue = new StockIssueCreateDTO();
        fefoIssue.setBranchId(cn01.getBranchId());
        fefoIssue.setIssueType("INTERNAL_USE");
        fefoIssue.setIssueDate(LocalDate.now());
        StockIssueCreateDTO.Item outItem = new StockIssueCreateDTO.Item();
        outItem.setMaterialId(matId);
        outItem.setQuantity(new BigDecimal("7"));
        fefoIssue.setItems(List.of(outItem));

        mockMvc.perform(post("/api/inventory/issues")
                        .sessionAttr("loggedInUser", khoCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(fefoIssue)))
                .andExpect(status().isCreated());

        MaterialBatch lotNearAfter = batchRepository.findAll().stream()
                .filter(b -> b.getBranch().getBranchId().equals(cn01.getBranchId())
                        && b.getMaterial().getMaterialId().equals(matId)
                        && "LOT-NEAR-01".equals(b.getBatchNumber()))
                .findFirst().orElseThrow();
        MaterialBatch lotFarAfter = batchRepository.findAll().stream()
                .filter(b -> b.getBranch().getBranchId().equals(cn01.getBranchId())
                        && b.getMaterial().getMaterialId().equals(matId)
                        && "LOT-FAR-02".equals(b.getBatchNumber()))
                .findFirst().orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(lotNearAfter.getQuantityRemaining()));
        assertEquals(0, new BigDecimal("8").compareTo(lotFarAfter.getQuantityRemaining()));

        JsonNode summary = mapper.readTree(mockMvc.perform(get("/api/inventory/summary")
                        .param("branchId", cn01.getBranchId().toString())
                        .sessionAttr("loggedInUser", khoCn01User))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(1, summary.get("lowStockItems").asInt());

        // ST-IV-05: Expired batch blocked for INTERNAL_USE/TRANSFER/OTHER, allowed ONLY for DISCARD_EXPIRED; OTHER requires issueReason
        MaterialBatch expBatch = new MaterialBatch();
        expBatch.setBranch(cn01);
        expBatch.setMaterial(materialRepository.findById(matId).orElseThrow());
        expBatch.setSupplier(sup);
        expBatch.setBatchNumber("LOT-EXP-99");
        expBatch.setExpiryDate(LocalDate.now().minusDays(2));
        expBatch.setQuantityRemaining(new BigDecimal("3"));
        expBatch.setImportPrice(new BigDecimal("100000"));
        expBatch.setStatus(BatchStatus.EXPIRED);
        expBatch = batchRepository.saveAndFlush(expBatch);
        BranchInventory invCn01 = inventoryRepository.findByBranch_BranchIdAndMaterial_MaterialId(cn01.getBranchId(), matId).orElseThrow();
        invCn01.setCurrentStock(invCn01.getCurrentStock().add(new BigDecimal("3"))); // 8 + 3 = 11
        inventoryRepository.saveAndFlush(invCn01);

        StockIssueCreateDTO.Item expOut = new StockIssueCreateDTO.Item();
        expOut.setMaterialId(matId);
        expOut.setBatchId(expBatch.getBatchId());
        expOut.setQuantity(new BigDecimal("1"));

        StockIssueCreateDTO useExpired = new StockIssueCreateDTO();
        useExpired.setBranchId(cn01.getBranchId());
        useExpired.setIssueType("INTERNAL_USE");
        useExpired.setIssueDate(LocalDate.now());
        useExpired.setItems(List.of(expOut));
        mockMvc.perform(post("/api/inventory/issues")
                        .sessionAttr("loggedInUser", khoCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(useExpired)))
                .andExpect(status().isBadRequest());

        StockIssueCreateDTO otherNoReason = new StockIssueCreateDTO();
        otherNoReason.setBranchId(cn01.getBranchId());
        otherNoReason.setIssueType("OTHER");
        otherNoReason.setIssueDate(LocalDate.now());
        otherNoReason.setItems(List.of(outItem));
        mockMvc.perform(post("/api/inventory/issues")
                        .sessionAttr("loggedInUser", khoCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(otherNoReason)))
                .andExpect(status().isBadRequest());

        StockIssueCreateDTO discardExp = new StockIssueCreateDTO();
        discardExp.setBranchId(cn01.getBranchId());
        discardExp.setIssueType("DISCARD_EXPIRED");
        discardExp.setIssueDate(LocalDate.now());
        discardExp.setItems(List.of(expOut));
        mockMvc.perform(post("/api/inventory/issues")
                        .sessionAttr("loggedInUser", khoCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(discardExp)))
                .andExpect(status().isCreated());
        assertEquals(0, new BigDecimal("2").compareTo(batchRepository.findById(expBatch.getBatchId()).orElseThrow().getQuantityRemaining()));

        // ST-IV-06: Atomic Inter-branch Transfer CN01 -> CN02 preserving batch & unit price
        StockIssueCreateDTO transferDto = new StockIssueCreateDTO();
        transferDto.setBranchId(cn01.getBranchId());
        transferDto.setDestinationBranchId(cn02.getBranchId());
        transferDto.setIssueType("TRANSFER");
        transferDto.setIssueDate(LocalDate.now());
        StockIssueCreateDTO.Item trItem = new StockIssueCreateDTO.Item();
        trItem.setMaterialId(matId);
        trItem.setBatchId(lotFarAfter.getBatchId());
        trItem.setQuantity(new BigDecimal("3"));
        transferDto.setItems(List.of(trItem));

        JsonNode trRes = mapper.readTree(mockMvc.perform(post("/api/inventory/transfers")
                        .sessionAttr("loggedInUser", khoCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(transferDto)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertEquals(0, new BigDecimal("3").compareTo(
                inventoryRepository.findByBranch_BranchIdAndMaterial_MaterialId(cn02.getBranchId(), matId)
                        .orElseThrow().getCurrentStock()));
        MaterialBatch destLot = batchRepository.findAll().stream()
                .filter(b -> b.getBranch().getBranchId().equals(cn02.getBranchId())
                        && b.getMaterial().getMaterialId().equals(matId)
                        && "LOT-FAR-02".equals(b.getBatchNumber()))
                .findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("100000").compareTo(destLot.getImportPrice()));

        // ST-IV-07: Multi-branch isolation (CN02 user blocked on CN01)
        mockMvc.perform(get("/api/inventory")
                        .param("branchId", cn01.getBranchId().toString())
                        .sessionAttr("loggedInUser", khoCn02User))
                .andExpect(status().isForbidden());
        assertTrue(trRes.get("issueId").asLong() > 0);
    }

    // =========================================================================
    // MODULE 3: CUSTOMER CARE (FT44 / UC-26) -> ST-CC-01 .. ST-CC-06
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("ST-CC-01..06: End-to-end Auto-gen 7d dedup, date filters, BUSY retry, Escalation->Doctor->Notify->Close & Reopen")
    void customerCareEndToEndSystemFlows() throws Exception {
        // Seed completed Visit within 7 days for Auto-Generate
        Visit v = new Visit();
        v.setVisitCode("VIS-CC-" + System.nanoTime() % 100000);
        v.setPatientProfile(patientProfileCn01);
        v.setBranch(cn01);
        v.setPrimaryDoctor(bacsiCn01Staff);
        v.setCheckInAt(LocalDateTime.now().minusDays(1));
        v.setCompletedAt(LocalDateTime.now().minusHours(5));
        v.setStatus(VisitStatus.COMPLETED);
        v.setVisitType(VisitType.WALK_IN);
        v = visitRepository.saveAndFlush(v);

        // ST-CC-01: Auto-generate & 7-day deduplication + date filter + tab summary
        JsonNode gen1 = mapper.readTree(mockMvc.perform(post("/api/customer-care/tasks/auto-generate")
                        .param("branchId", cn01.getBranchId().toString())
                        .sessionAttr("loggedInUser", letanCn01User))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(gen1.get("totalCreated").asInt() >= 1);

        JsonNode gen2 = mapper.readTree(mockMvc.perform(post("/api/customer-care/tasks/auto-generate")
                        .param("branchId", cn01.getBranchId().toString())
                        .sessionAttr("loggedInUser", letanCn01User))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(0, gen2.get("totalCreated").asInt());

        // Create manual overdue task + null dueDate task filter verification
        CustomerCareCreateDTO createDto = new CustomerCareCreateDTO();
        createDto.setBranchId(cn01.getBranchId());
        createDto.setProfileId(patientProfileCn01.getProfileId());
        createDto.setCareGroup("FEEDBACK_COMPLAINT");
        createDto.setTaskType("POST_TREATMENT_FOLLOWUP");
        createDto.setDueDate(LocalDate.now());
        createDto.setAssignedStaffId(letanCn01Staff.getStaffId());
        createDto.setNotes("Khách phản hồi hơi ê răng hàm dưới");
        JsonNode createdTask = mapper.readTree(mockMvc.perform(post("/api/customer-care/tasks")
                        .sessionAttr("loggedInUser", letanCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(createDto)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long taskId = createdTask.get("taskId").asLong();

        // ST-CC-02: BUSY without nextRetryAt -> 400; with future nextRetryAt -> IN_PROGRESS + dueDate updated
        CustomerCareInteractionCreateDTO badBusy = new CustomerCareInteractionCreateDTO();
        badBusy.setChannel("PHONE_CALL");
        badBusy.setContactResult("BUSY");
        badBusy.setNotes("Gọi không được");
        mockMvc.perform(post("/api/customer-care/tasks/{id}/interactions", taskId)
                        .sessionAttr("loggedInUser", letanCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(badBusy)))
                .andExpect(status().isBadRequest());

        CustomerCareInteractionCreateDTO goodBusy = new CustomerCareInteractionCreateDTO();
        goodBusy.setChannel("PHONE_CALL");
        goodBusy.setContactResult("BUSY");
        goodBusy.setNextRetryAt(LocalDateTime.now().plusHours(4));
        goodBusy.setNotes("Khách đang bận họp, hẹn 4h sau gọi lại");
        JsonNode afterBusy = mapper.readTree(mockMvc.perform(post("/api/customer-care/tasks/{id}/interactions", taskId)
                        .sessionAttr("loggedInUser", letanCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(goodBusy)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertEquals("IN_PROGRESS", afterBusy.get("status").asText());

        // ST-CC-04 + ST-CC-05: Escalate to Doctor -> Doctor Notification -> RBAC block Receptionist reply -> Doctor response -> Notify Receptionist -> Complete
        CustomerCareEscalateDTO escDto = new CustomerCareEscalateDTO();
        escDto.setEscalatedToDoctorId(bacsiCn01Staff.getStaffId());
        escDto.setEscalationReason("Bệnh nhân sưng nướu nhẹ vùng răng 36 sau 1 ngày");
        JsonNode afterEsc = mapper.readTree(mockMvc.perform(post("/api/customer-care/tasks/{id}/escalate", taskId)
                        .sessionAttr("loggedInUser", letanCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(escDto)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("ESCALATED_TO_DOCTOR", afterEsc.get("status").asText());

        // Doctor receives unread notification
        JsonNode docNotifs = mapper.readTree(mockMvc.perform(get("/api/customer-care/notifications")
                        .sessionAttr("loggedInUser", bacsiCn01User))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(docNotifs.get("unreadCount").asInt() >= 1);

        // Receptionist blocked from submitting doctor response (BR-CC-03) -> 403
        CustomerCareEscalateDTO docReplyDto = new CustomerCareEscalateDTO();
        docReplyDto.setDoctorResponse("Hướng dẫn súc miệng nước muối sinh lý 3 ngày và hẹn tái khám");
        docReplyDto.setNextStatus("IN_PROGRESS");
        mockMvc.perform(post("/api/customer-care/tasks/{id}/doctor-response", taskId)
                        .sessionAttr("loggedInUser", letanCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(docReplyDto)))
                .andExpect(status().isForbidden());

        // CN02 receptionist blocked from CN01 task -> 403
        mockMvc.perform(get("/api/customer-care/tasks/{id}", taskId)
                        .sessionAttr("loggedInUser", letanCn02User))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/customer-care/tasks")
                        .param("branchId", cn01.getBranchId().toString())
                        .sessionAttr("loggedInUser", khoCn01User))
                .andExpect(status().isForbidden());

        // Doctor submits response
        JsonNode afterDoc = mapper.readTree(mockMvc.perform(post("/api/customer-care/tasks/{id}/doctor-response", taskId)
                        .sessionAttr("loggedInUser", bacsiCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(docReplyDto)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("IN_PROGRESS", afterDoc.get("status").asText());

        // Receptionist receives notification that Doctor responded
        JsonNode ltNotifs = mapper.readTree(mockMvc.perform(get("/api/customer-care/notifications")
                        .sessionAttr("loggedInUser", letanCn01User))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(ltNotifs.get("unreadCount").asInt() >= 1);

        // ST-CC-03: Receptionist completes call -> Task COMPLETED & locked (409) -> Admin Reopen + AuditLog
        CustomerCareInteractionCreateDTO completeCall = new CustomerCareInteractionCreateDTO();
        completeCall.setChannel("PHONE_CALL");
        completeCall.setContactResult("CONNECTED");
        completeCall.setNotes("Đã truyền đạt chỉ định bác sĩ, khách đồng ý lịch theo dõi");
        completeCall.setNextStatus("COMPLETED");
        JsonNode completedRes = mapper.readTree(mockMvc.perform(post("/api/customer-care/tasks/{id}/interactions", taskId)
                        .sessionAttr("loggedInUser", letanCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(completeCall)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertEquals("COMPLETED", completedRes.get("status").asText());

        // Locked task rejects further interactions with 409 Conflict
        mockMvc.perform(post("/api/customer-care/tasks/{id}/interactions", taskId)
                        .sessionAttr("loggedInUser", letanCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(completeCall)))
                .andExpect(status().isConflict());

        // Admin reopens task -> AuditLog recorded
        JsonNode reopened = mapper.readTree(mockMvc.perform(post("/api/customer-care/tasks/{id}/reopen", taskId)
                        .sessionAttr("loggedInUser", adminUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Bệnh nhân gọi lại hỏi thêm thuốc\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("IN_PROGRESS", reopened.get("status").asText());
        assertFalse(auditLogRepository.findByEntityTableAndEntityIdOrderByCreatedAtDesc("customer_care_tasks", taskId).isEmpty());

        // ST-CC-06: Global interaction audit & Patient care timeline + UI HTML
        JsonNode patHistory = mapper.readTree(mockMvc.perform(get("/api/customer-care/patients/{id}/history", patientProfileCn01.getProfileId())
                        .param("branchId", cn01.getBranchId().toString())
                        .sessionAttr("loggedInUser", letanCn01User))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(patHistory.get("totalInteractions").asInt() >= 3);
        mockMvc.perform(get("/customer-care").sessionAttr("loggedInUser", letanCn01User))
                .andExpect(status().isOk());
    }

    // =========================================================================
    // MODULE 4: SUPPLIERS & PAYABLES (FT26 / UC-19) -> ST-SP-01 .. ST-SP-05
    // =========================================================================

    @Test
    @Order(4)
    @DisplayName("ST-SP-01..05: End-to-end Supplier +84/30-char/dup MST, Soft-delete, Receipt->Pay, Overpay/Idempotency & RBAC")
    void suppliersAndPayablesEndToEndSystemFlows() throws Exception {
        // ST-SP-01: Create Supplier with 30-char code & +84 phone (fixed FE/BE parity!) + update
        String code30 = "NCC_E2E_LONG_CODE_" + (System.nanoTime() % 100000);
        if (code30.length() > 30) code30 = code30.substring(0, 30);
        String uniqueMst = "01077" + String.format("%05d", Math.abs((int) (System.nanoTime() % 100000)));

        SupplierCreateDTO createSup = new SupplierCreateDTO();
        createSup.setSupplierCode(code30.toLowerCase());
        createSup.setSupplierName("Công ty Thiết bị Nha khoa E2E");
        createSup.setTaxCode(uniqueMst);
        createSup.setPhoneNumber("+84901234567");
        createSup.setAddress("100 Láng Hạ, Đống Đa, HN");

        JsonNode supNode = mapper.readTree(mockMvc.perform(post("/api/suppliers")
                        .sessionAttr("loggedInUser", khoCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(createSup)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long supId = supNode.get("supplierId").asLong();
        assertEquals(code30.toUpperCase(), supNode.get("supplierCode").asText());
        assertEquals("+84901234567", supNode.get("phoneNumber").asText());

        // ST-SP-02: Duplicate SupplierCode AND Duplicate TaxCode rejected (400), Deactivate/Activate preserves record
        SupplierCreateDTO dupMstDto = new SupplierCreateDTO();
        dupMstDto.setSupplierCode("NCC-OTHER-" + (System.nanoTime() % 10000));
        dupMstDto.setSupplierName("NCC Trùng MST");
        dupMstDto.setTaxCode(uniqueMst);
        dupMstDto.setPhoneNumber("0908887766");
        mockMvc.perform(post("/api/suppliers")
                        .sessionAttr("loggedInUser", khoCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(dupMstDto)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/suppliers/{id}/deactivate", supId)
                        .sessionAttr("loggedInUser", khoCn01User))
                .andExpect(status().isOk());
        assertFalse(supplierRepository.findById(supId).orElseThrow().getIsActive());
        mockMvc.perform(post("/api/suppliers/{id}/activate", supId)
                        .sessionAttr("loggedInUser", khoCn01User))
                .andExpect(status().isOk());
        assertTrue(supplierRepository.findById(supId).orElseThrow().getIsActive());

        // ST-SP-03: Receipt creates branch debt (1,000,000), Pay against receiptId (400,000) & general balance (600,000) -> debt=0
        Material m = new Material();
        m.setMaterialCode("VT-SP-" + (System.nanoTime() % 100000));
        m.setMaterialName("Vật tư Công nợ E2E");
        m.setCategory("Tiêu hao");
        m.setUnit("Hộp");
        m.setMinStockThreshold(new BigDecimal("5"));
        m.setIsActive(true);
        m = materialRepository.saveAndFlush(m);

        String invUrl = uploadTestInvoice(khoCn01User);
        StockReceiptCreateDTO recDto = new StockReceiptCreateDTO();
        recDto.setBranchId(cn01.getBranchId());
        recDto.setSupplierId(supId);
        recDto.setReceiptDate(LocalDate.now());
        recDto.setAttachmentUrl(invUrl);
        recDto.setPaidAmount(BigDecimal.ZERO);
        StockReceiptCreateDTO.Item rItem = new StockReceiptCreateDTO.Item();
        rItem.setMaterialId(m.getMaterialId());
        rItem.setBatchNumber("LOT-SP-01");
        rItem.setExpiryDate(LocalDate.now().plusYears(1));
        rItem.setQuantity(new BigDecimal("10"));
        rItem.setUnitPrice(new BigDecimal("100000"));
        recDto.setItems(List.of(rItem));

        JsonNode recNode = mapper.readTree(mockMvc.perform(post("/api/inventory/receipts")
                        .sessionAttr("loggedInUser", khoCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(recDto)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long recId = recNode.get("receiptId").asLong();

        SupplierPaymentCreateDTO pay1 = new SupplierPaymentCreateDTO();
        pay1.setBranchId(cn01.getBranchId());
        pay1.setSupplierId(supId);
        pay1.setReceiptId(recId);
        pay1.setPaymentDate(LocalDate.now());
        pay1.setAmount(new BigDecimal("400000"));
        pay1.setPaymentMethod("CASH");
        pay1.setClientRequestId("REQ-SP-1-" + System.nanoTime());

        mockMvc.perform(post("/api/inventory/supplier-payments")
                        .sessionAttr("loggedInUser", khoCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(pay1)))
                .andExpect(status().isCreated());

        // ST-SP-04: Overpayment (>600,000) blocked (400) & Duplicate clientRequestId within 30s blocked (400)!
        mockMvc.perform(post("/api/inventory/supplier-payments")
                        .sessionAttr("loggedInUser", khoCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(pay1)))
                .andExpect(status().isBadRequest());

        SupplierPaymentCreateDTO overPay = new SupplierPaymentCreateDTO();
        overPay.setBranchId(cn01.getBranchId());
        overPay.setSupplierId(supId);
        overPay.setReceiptId(recId);
        overPay.setPaymentDate(LocalDate.now());
        overPay.setAmount(new BigDecimal("600001"));
        overPay.setPaymentMethod("BANK_TRANSFER");
        overPay.setReferenceNo("UNC-OVER");
        overPay.setClientRequestId("REQ-SP-OVER-" + System.nanoTime());
        mockMvc.perform(post("/api/inventory/supplier-payments")
                        .sessionAttr("loggedInUser", khoCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(overPay)))
                .andExpect(status().isBadRequest());

        // Settle exact remaining 600,000
        SupplierPaymentCreateDTO settlePay = new SupplierPaymentCreateDTO();
        settlePay.setBranchId(cn01.getBranchId());
        settlePay.setSupplierId(supId);
        settlePay.setPaymentDate(LocalDate.now());
        settlePay.setAmount(new BigDecimal("600000"));
        settlePay.setPaymentMethod("BANK_TRANSFER");
        settlePay.setReferenceNo("UNC-FINAL-01");
        settlePay.setClientRequestId("REQ-SP-2-" + System.nanoTime());
        mockMvc.perform(post("/api/inventory/supplier-payments")
                        .sessionAttr("loggedInUser", khoCn01User)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(settlePay)))
                .andExpect(status().isCreated());

        assertEquals(0, BigDecimal.ZERO.compareTo(
                receiptRepository.findByIdWithDetails(recId).orElseThrow().getTotalAmount()
                        .subtract(supplierPaymentRepository.sumPaidAmountByBranchAndSupplier(cn01.getBranchId(), supId))));

        // ST-SP-05: Role Authorization via FeaturePermissionInterceptor
        // Admin & QUAN_LY_KHO hold SUPPLIER_MANAGE (200);
        // BAC_SI has CLINICAL_LABO read-only on /api/suppliers (for Labo order UI),
        // while /suppliers (302->/home) and /api/inventory/supplier-payments (403) require SUPPLIER_MANAGE;
        // LE_TAN, BENH_NHAN and default KE_TOAN (without custom SUPPLIER_MANAGE) are blocked across supplier management & payables.
        mockMvc.perform(get("/suppliers").sessionAttr("loggedInUser", khoCn01User))
                .andExpect(status().isOk());
        mockMvc.perform(get("/suppliers").sessionAttr("loggedInUser", adminUser))
                .andExpect(status().isOk());
        mockMvc.perform(get("/suppliers").sessionAttr("loggedInUser", ketoanCn01User))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/suppliers").sessionAttr("loggedInUser", bacsiCn01User))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/api/suppliers").sessionAttr("loggedInUser", ketoanCn01User))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/inventory/supplier-payments/debts").param("branchId", String.valueOf(cn01.getBranchId()))
                        .sessionAttr("loggedInUser", bacsiCn01User))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/suppliers").sessionAttr("loggedInUser", letanCn01User))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/suppliers").sessionAttr("loggedInUser", patientCn01User))
                .andExpect(status().isForbidden());
    }
}
