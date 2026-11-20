package com.cvdcms.service.impl;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.ChatChannel;
import com.cvdcms.entity.enums.ChatStatus;
import com.cvdcms.entity.enums.SenderType;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.entity.enums.UserType;
import com.cvdcms.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceImplTest {

    @Mock private ChatConversationRepository conversationRepository;
    @Mock private ChatMessageRepository messageRepository;
    @Mock private ChatMessageUserStateRepository messageUserStateRepository;
    @Mock private ChatConversationUserStateRepository conversationUserStateRepository;
    @Mock private UserRepository userRepository;
    @Mock private BranchRepository branchRepository;
    @Mock private StaffProfileRepository staffProfileRepository;
    @Mock private PatientProfileRepository patientProfileRepository;
    @Mock private CustomerCareTaskRepository customerCareTaskRepository;

    private ChatServiceImpl chatService;

    private Branch branch1;
    private Branch branch2;
    private User adminUser;
    private User receptionistUser;
    private User doctorUser;
    private User warehouseUser;
    private User patientUser;
    private StaffProfile receptionistStaff;
    private StaffProfile doctorStaff;
    private StaffProfile warehouseStaff;
    private PatientProfile patientProfile;

    @BeforeEach
    void setUp() {
        chatService = new ChatServiceImpl(
                conversationRepository,
                messageRepository,
                messageUserStateRepository,
                conversationUserStateRepository,
                userRepository,
                branchRepository,
                staffProfileRepository,
                patientProfileRepository,
                customerCareTaskRepository
        );

        branch1 = new Branch();
        branch1.setBranchId(1L);
        branch1.setBranchCode("CS1");
        branch1.setBranchName("Cơ sở 1");

        branch2 = new Branch();
        branch2.setBranchId(2L);
        branch2.setBranchCode("CS2");
        branch2.setBranchName("Cơ sở 2");

        adminUser = activeUser(1L, "admin", UserType.STAFF, null);
        receptionistUser = activeUser(10L, "letan01", UserType.STAFF, branch1);
        doctorUser = activeUser(20L, "bs01", UserType.STAFF, branch1);
        warehouseUser = activeUser(30L, "kho01", UserType.STAFF, branch1);
        patientUser = activeUser(40L, "bn01", UserType.PATIENT, branch1);

        receptionistStaff = new StaffProfile();
        receptionistStaff.setStaffId(100L);
        receptionistStaff.setFullName("Nguyễn Lễ Tân");
        receptionistStaff.setPositionTitle("Lễ tân");
        receptionistStaff.setStatus(StaffStatus.ACTIVE);
        receptionistStaff.setUser(receptionistUser);

        doctorStaff = new StaffProfile();
        doctorStaff.setStaffId(200L);
        doctorStaff.setFullName("BS. Trần Minh");
        doctorStaff.setPositionTitle("Bác sĩ RHM");
        doctorStaff.setStatus(StaffStatus.ACTIVE);
        doctorStaff.setUser(doctorUser);

        warehouseStaff = new StaffProfile();
        warehouseStaff.setStaffId(300L);
        warehouseStaff.setFullName("Lê Thủ Kho");
        warehouseStaff.setDepartment("Kho vật tư");
        warehouseStaff.setStatus(StaffStatus.ACTIVE);
        warehouseStaff.setUser(warehouseUser);

        patientProfile = new PatientProfile();
        patientProfile.setProfileId(400L);
        patientProfile.setPatientCode("BN-400");
        patientProfile.setFullName("Phạm Bệnh Nhân");
        patientProfile.setPhoneNumber("0909000400");
        patientProfile.setPreferredBranch(branch1);

        lenient().when(branchRepository.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(branch1, branch2));
        lenient().when(branchRepository.findById(1L)).thenReturn(Optional.of(branch1));
        lenient().when(branchRepository.findById(2L)).thenReturn(Optional.of(branch2));
        lenient().when(conversationRepository.save(any(ChatConversation.class))).thenAnswer(inv -> {
            ChatConversation c = inv.getArgument(0);
            if (c.getConversationId() == null) c.setConversationId(500L);
            return c;
        });
        lenient().when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            if (m.getMessageId() == null) m.setMessageId(900L);
            return m;
        });
    }

    private User activeUser(Long id, String username, UserType type, Branch branch) {
        User u = new User();
        u.setUserId(id);
        u.setUsername(username);
        u.setIsActive(true);
        u.setStatus("ACTIVE");
        u.setUserType(type);
        u.setBranch(branch);
        return u;
    }

    private void stubRole(User u, String roleName, StaffProfile sp, PatientProfile pp) {
        lenient().when(userRepository.findById(u.getUserId())).thenReturn(Optional.of(u));
        lenient().when(userRepository.hasActiveAdminRole(u.getUserId())).thenReturn("ADMIN".equals(roleName));
        lenient().when(conversationRepository.findActiveRoleNamesByUserId(u.getUserId())).thenReturn(List.of(roleName));
        lenient().when(staffProfileRepository.findByUser_UserId(u.getUserId()))
                .thenReturn(Optional.ofNullable(sp));
        if (pp != null) {
            lenient().when(patientProfileRepository.findByUser_UserIdOrderByIsDefaultDescProfileIdDesc(u.getUserId()))
                    .thenReturn(List.of(pp));
        } else {
            lenient().when(patientProfileRepository.findByUser_UserIdOrderByIsDefaultDescProfileIdDesc(u.getUserId()))
                    .thenReturn(List.of());
        }
    }

    private ChatConversation generalConv(Long cid, User initiator, Branch b) {
        ChatConversation c = new ChatConversation();
        c.setConversationId(cid);
        c.setUser(initiator);
        c.setBranch(b);
        c.setStatus(ChatStatus.STAFF_HANDLING);
        c.setCreatedAt(LocalDateTime.now().minusHours(2));
        c.setLastMessageAt(LocalDateTime.now().minusMinutes(5));
        return c;
    }

    private ChatConversation directConv(Long cid, User init, User rec, Branch b) {
        ChatConversation c = generalConv(cid, init, b);
        c.setRecipientUser(rec);
        return c;
    }

    private ChatMessage msg(Long mid, ChatConversation c, Long senderId, SenderType st, String text, LocalDateTime sentAt) {
        ChatMessage m = new ChatMessage();
        m.setMessageId(mid);
        m.setConversation(c);
        m.setSenderId(senderId);
        m.setSenderType(st);
        m.setMessageText(text);
        m.setSentAt(sentAt);
        m.setIsRead(false);
        m.setIsEdited(false);
        m.setIsRecalled(false);
        return m;
    }

    // ==================== 1. Roles & Permissions & Branches ====================

    @Test
    void resolvePrimaryRoleCode_ReturnsPatientWhenIdNullOrNonPositive() {
        assertEquals("BENH_NHAN", chatService.resolvePrimaryRoleCode(null));
        assertEquals("BENH_NHAN", chatService.resolvePrimaryRoleCode(0L));
    }

    @Test
    void resolvePrimaryRoleCode_MapsAdminAndStrippedRoleNamesAndStaffProfileFallback() {
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        assertEquals("ADMIN", chatService.resolvePrimaryRoleCode(1L));

        when(userRepository.hasActiveAdminRole(20L)).thenReturn(false);
        when(conversationRepository.findActiveRoleNamesByUserId(20L)).thenReturn(List.of("ROLE_DOCTOR"));
        assertEquals("BAC_SI", chatService.resolvePrimaryRoleCode(20L));

        when(conversationRepository.findActiveRoleNamesByUserId(30L)).thenReturn(List.of());
        when(staffProfileRepository.findByUser_UserId(30L)).thenReturn(Optional.of(warehouseStaff));
        assertEquals("QUAN_LY_KHO", chatService.resolvePrimaryRoleCode(30L));

        // Account roles entity fallback + STAFF userType fallback
        User staffWithoutProfile = activeUser(99L, "staff99", UserType.STAFF, branch1);
        when(userRepository.hasActiveAdminRole(99L)).thenReturn(false);
        when(conversationRepository.findActiveRoleNamesByUserId(99L)).thenReturn(List.of());
        when(staffProfileRepository.findByUser_UserId(99L)).thenReturn(Optional.empty());
        when(userRepository.findById(99L)).thenReturn(Optional.of(staffWithoutProfile));
        assertEquals("LE_TAN", chatService.resolvePrimaryRoleCode(99L));
    }

    @Test
    void toRoleLabel_ReturnsLocalizedLabelForAllRoles() {
        assertEquals("Admin", chatService.toRoleLabel("admin"));
        assertEquals("Bác sĩ", chatService.toRoleLabel("BAC_SI"));
        assertEquals("Lễ tân", chatService.toRoleLabel("LE_TAN"));
        assertEquals("Quản lý kho", chatService.toRoleLabel("QUAN_LY_KHO"));
        assertEquals("Kế toán", chatService.toRoleLabel("KE_TOAN"));
        assertEquals("Bệnh nhân", chatService.toRoleLabel(null));
    }

    @Test
    void requireInboxAccess_BlocksPatientAndAllowsStaffOrAdmin() {
        stubRole(patientUser, "BENH_NHAN", null, patientProfile);
        ResponseStatusException forbidden = assertThrows(ResponseStatusException.class,
                () -> chatService.requireInboxAccess(40L));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());

        stubRole(warehouseUser, "QUAN_LY_KHO", warehouseStaff, null);
        assertEquals("QUAN_LY_KHO", chatService.requireInboxAccess(30L));
    }

    @Test
    void isAdminUser_ChecksRoleAccurately() {
        assertFalse(chatService.isAdminUser(null));
        assertFalse(chatService.isAdminUser(0L));

        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        assertTrue(chatService.isAdminUser(1L));

        stubRole(receptionistUser, "LE_TAN", receptionistStaff, null);
        assertFalse(chatService.isAdminUser(10L));
    }

    @Test
    void canAccessBranchTopic_RestrictsByPatientHandlingRoleAndBranchScope() {
        assertFalse(chatService.canAccessBranchTopic(null, 1L));
        assertFalse(chatService.canAccessBranchTopic(10L, null));

        stubRole(adminUser, "ADMIN", null, null);
        assertTrue(chatService.canAccessBranchTopic(1L, 2L));

        stubRole(warehouseUser, "QUAN_LY_KHO", warehouseStaff, null);
        assertFalse(chatService.canAccessBranchTopic(30L, 1L));

        stubRole(receptionistUser, "LE_TAN", receptionistStaff, null);
        assertTrue(chatService.canAccessBranchTopic(10L, 1L));
        assertFalse(chatService.canAccessBranchTopic(10L, 2L));
    }

    @Test
    void getAccessibleBranchesForInbox_UsesUserBranchExplicitOrInferredAndAllForAdmin() {
        stubRole(adminUser, "ADMIN", null, null);
        assertEquals(2, chatService.getAccessibleBranchesForInbox(1L).size());

        User noBranchUser = activeUser(11L, "letan11", UserType.STAFF, null);
        stubRole(noBranchUser, "LE_TAN", null, null);
        when(conversationRepository.findExplicitBranchIdsByUserId(11L)).thenReturn(List.of(2L));
        List<Branch> explicit = chatService.getAccessibleBranchesForInbox(11L);
        assertEquals(1, explicit.size());
        assertEquals(2L, explicit.get(0).getBranchId());
    }

    // ==================== 2. Search Recipients & Start Conversation ====================

    @Test
    void searchRecipientUsers_BlocksPatientAndOtherBranchQueries() {
        stubRole(patientUser, "BENH_NHAN", null, patientProfile);
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class,
                        () -> chatService.searchRecipientUsers(40L, null, null, null)).getStatusCode());

        stubRole(receptionistUser, "LE_TAN", receptionistStaff, null);
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class,
                        () -> chatService.searchRecipientUsers(10L, null, null, 2L)).getStatusCode());
    }

    @Test
    void searchRecipientUsers_HidesPatientsFromWarehouse_AndMatchesMultiProfilePatientsAndExistingConvId() {
        stubRole(warehouseUser, "QUAN_LY_KHO", warehouseStaff, null);
        stubRole(doctorUser, "BAC_SI", doctorStaff, null);
        stubRole(patientUser, "BENH_NHAN", null, patientProfile);

        when(userRepository.findAll()).thenReturn(List.of(warehouseUser, doctorUser, patientUser));
        when(conversationRepository.findAllConversationsWithUserAndBranch()).thenReturn(List.of());

        assertTrue(chatService.searchRecipientUsers(30L, null, "BENH_NHAN", null).isEmpty());
        List<ChatUserSearchItemDTO> whVisible = chatService.searchRecipientUsers(30L, null, null, null);
        assertEquals(1, whVisible.size());
        assertEquals(20L, whVisible.get(0).getUserId());

        // Receptionist searches for child profile under parent patient account -> matched & sorted after staff
        stubRole(receptionistUser, "LE_TAN", receptionistStaff, null);
        PatientProfile secondProfile = new PatientProfile();
        secondProfile.setProfileId(401L);
        secondProfile.setPatientCode("BN-CON");
        secondProfile.setFullName("Bé Tí");
        secondProfile.setPreferredBranch(branch1);
        when(patientProfileRepository.findByUser_UserIdOrderByIsDefaultDescProfileIdDesc(40L))
                .thenReturn(List.of(patientProfile, secondProfile));

        ChatConversation activePatientConv = generalConv(55L, patientUser, branch1);
        when(conversationRepository.findAllConversationsWithUserAndBranch()).thenReturn(List.of(activePatientConv));

        List<ChatUserSearchItemDTO> recResults = chatService.searchRecipientUsers(10L, "BN-CON", null, 1L);
        assertEquals(1, recResults.size());
        assertTrue(recResults.get(0).getFullName().contains("Bé Tí"));
        assertEquals("BN-CON", recResults.get(0).getPatientCode());
        assertEquals(2, recResults.get(0).getPatientProfileCount());
        assertEquals(55L, recResults.get(0).getExistingConversationId());
    }

    @Test
    void searchRecipientUsersPage_ReturnsRequestedSliceAndEmptyOutOfBounds() {
        stubRole(receptionistUser, "LE_TAN", receptionistStaff, null);
        stubRole(doctorUser, "BAC_SI", doctorStaff, null);
        when(userRepository.findAll()).thenReturn(List.of(doctorUser));
        when(conversationRepository.findAllConversationsWithUserAndBranch()).thenReturn(List.of());

        Page<ChatUserSearchItemDTO> page0 = chatService.searchRecipientUsersPage(10L, null, null, null, PageRequest.of(0, 10));
        assertEquals(1, page0.getTotalElements());

        Page<ChatUserSearchItemDTO> page2 = chatService.searchRecipientUsersPage(10L, null, null, null, PageRequest.of(2, 10));
        assertTrue(page2.getContent().isEmpty());
        assertEquals(1, page2.getTotalElements());
    }

    @Test
    void startOrGetConversationWithRecipient_ValidatesBadTargetSelfRoleAndCrossBranch() {
        stubRole(warehouseUser, "QUAN_LY_KHO", warehouseStaff, null);
        stubRole(patientUser, "BENH_NHAN", null, patientProfile);

        User otherBranchStaff = activeUser(60L, "lt_cs2", UserType.STAFF, branch2);
        stubRole(otherBranchStaff, "LE_TAN", null, null);

        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> chatService.startOrGetConversationWithRecipient(30L, null, null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> chatService.startOrGetConversationWithRecipient(30L, 30L, null)).getStatusCode());
        // Warehouse cannot start chat with patient
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> chatService.startOrGetConversationWithRecipient(30L, 40L, 1L)).getStatusCode());
        // Staff cannot start chat across branches
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> chatService.startOrGetConversationWithRecipient(30L, 60L, null)).getStatusCode());
    }

    @Test
    void startOrGetConversationWithRecipient_CreatesOrReusesDirectAndGeneralConversations() {
        stubRole(receptionistUser, "LE_TAN", receptionistStaff, null);
        stubRole(doctorUser, "BAC_SI", doctorStaff, null);

        ChatConversation existingDirect = directConv(88L, receptionistUser, doctorUser, branch1);
        existingDirect.setStatus(ChatStatus.BOT_HANDLING);
        when(conversationRepository.findActiveDirectConversationsBetweenUsers(10L, 20L))
                .thenReturn(List.of(existingDirect));
        when(conversationRepository.findByIdWithDetails(88L)).thenReturn(Optional.of(existingDirect));
        when(messageRepository.findByConversationIdOrderBySentAtAsc(88L)).thenReturn(List.of());

        Map<String, Object> res = chatService.startOrGetConversationWithRecipient(10L, 20L, 1L);
        assertEquals(88L, res.get("conversationId"));
        assertEquals(true, res.get("directConversation"));
        assertEquals(ChatStatus.STAFF_HANDLING, existingDirect.getStatus());
    }

    // ==================== 3. Unread Isolation, Context, Widget & Inbox Search ====================

    @Test
    void getTotalUnreadCount_TracksPerUserReadStateIndependentlyAcrossStaff() {
        stubRole(receptionistUser, "LE_TAN", receptionistStaff, null);
        stubRole(doctorUser, "BAC_SI", doctorStaff, null);
        stubRole(patientUser, "BENH_NHAN", null, patientProfile);

        ChatConversation conv = generalConv(1L, patientUser, branch1);
        LocalDateTime t1 = LocalDateTime.now().minusMinutes(10);
        ChatMessage fromPatient = msg(10L, conv, 40L, SenderType.PATIENT, "Em bị đau răng", t1);
        ChatMessage fromBot = msg(11L, conv, null, SenderType.BOT, "Bot greeting", t1);

        when(conversationRepository.findAllConversationsWithUserAndBranch()).thenReturn(List.of(conv));
        when(messageRepository.findAllByConversationIdsOrderBySentAtAsc(List.of(1L))).thenReturn(List.of(fromBot, fromPatient));

        // Receptionist already read conv at now -> unread = 0
        ChatConversationUserState recReadState = new ChatConversationUserState();
        recReadState.setConversationId(1L);
        recReadState.setUserId(10L);
        recReadState.setLastReadAt(LocalDateTime.now());
        when(conversationUserStateRepository.findByUserIdAndConversationIdIn(10L, List.of(1L)))
                .thenReturn(List.of(recReadState));
        when(messageUserStateRepository.findByUserIdAndConversationIdIn(10L, List.of(1L))).thenReturn(List.of());

        // Doctor has NOT read yet -> unread = 1 even though receptionist already read
        when(conversationUserStateRepository.findByUserIdAndConversationIdIn(20L, List.of(1L))).thenReturn(List.of());
        when(messageUserStateRepository.findByUserIdAndConversationIdIn(20L, List.of(1L))).thenReturn(List.of());

        assertEquals(0L, chatService.getTotalUnreadCount(10L));
        assertEquals(1L, chatService.getTotalUnreadCount(20L));
    }

    @Test
    void getTotalUnreadCount_ForPatientExcludesBotRecalledDeletedAndOwnMessages() {
        stubRole(patientUser, "BENH_NHAN", null, patientProfile);
        ChatConversation conv = generalConv(1L, patientUser, branch1);

        ChatMessage bot = msg(1L, conv, null, SenderType.BOT, "Chào", LocalDateTime.now().minusMinutes(5));
        ChatMessage own = msg(2L, conv, 40L, SenderType.PATIENT, "Hỏi", LocalDateTime.now().minusMinutes(4));
        ChatMessage recalled = msg(3L, conv, 10L, SenderType.STAFF, "Gỡ", LocalDateTime.now().minusMinutes(3));
        recalled.setIsRecalled(true);
        ChatMessage deleted = msg(4L, conv, 10L, SenderType.STAFF, "Xóa riêng", LocalDateTime.now().minusMinutes(2));
        ChatMessage unreadStaff = msg(5L, conv, 10L, SenderType.STAFF, "Chào bạn", LocalDateTime.now().minusMinutes(1));

        ChatMessageUserState deletedState = new ChatMessageUserState();
        deletedState.setMessageId(4L);
        deletedState.setIsDeleted(true);

        when(conversationRepository.findActiveByUserIdAndBranchId(40L, 1L)).thenReturn(List.of(conv));
        when(messageRepository.findByConversationIdOrderBySentAtAsc(1L))
                .thenReturn(List.of(bot, own, recalled, deleted, unreadStaff));
        when(conversationUserStateRepository.findByConversationIdAndUserId(1L, 40L)).thenReturn(Optional.empty());
        when(messageUserStateRepository.findByConversationIdAndUserId(1L, 40L)).thenReturn(List.of(deletedState));

        assertEquals(1L, chatService.getTotalUnreadCount(40L));
    }

    @Test
    void getUserChatContextAndWidgetConversation_RespectPatientAndStaffCapabilities() {
        stubRole(patientUser, "BENH_NHAN", null, patientProfile);
        ChatConversation legacyConv = generalConv(12L, patientUser, null);
        legacyConv.setStatus(ChatStatus.BOT_HANDLING);

        when(conversationRepository.findActiveByUserIdAndBranchId(40L, 1L)).thenReturn(List.of());
        when(conversationRepository.findActiveByUserIdWithNullBranch(40L)).thenReturn(List.of(legacyConv));
        when(messageRepository.findByConversationIdOrderBySentAtAsc(12L)).thenReturn(List.of());

        Map<String, Object> ctx = chatService.getUserChatContext(40L);
        assertEquals(false, ctx.get("canAccessInbox"));
        assertEquals(false, ctx.get("canHandlePatients"));
        assertEquals(1L, ctx.get("branchId"));

        Map<String, Object> widget = chatService.getOrInitMyWidgetConversation(40L);
        assertEquals(12L, widget.get("conversationId"));
        assertEquals(branch1, legacyConv.getBranch());
        assertEquals(ChatStatus.STAFF_HANDLING, legacyConv.getStatus());

        // Warehouse cannot open general patient widget conversation
        stubRole(warehouseUser, "QUAN_LY_KHO", warehouseStaff, null);
        when(conversationRepository.findAllConversationsWithUserAndBranch()).thenReturn(List.of());
        Map<String, Object> whWidget = chatService.getOrInitMyWidgetConversation(30L);
        assertNull(whWidget.get("conversationId"));
        assertEquals(false, whWidget.get("canHandlePatients"));
    }

    @Test
    void searchInboxConversations_FiltersClearedUnreadRoleKeywordAndEnforcesBranchAccess() {
        stubRole(receptionistUser, "LE_TAN", receptionistStaff, null);
        stubRole(patientUser, "BENH_NHAN", null, patientProfile);
        stubRole(doctorUser, "BAC_SI", doctorStaff, null);

        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class,
                        () -> chatService.searchInboxConversations(10L, null, null, 2L, null, PageRequest.of(0, 20)))
                        .getStatusCode());

        ChatConversation b1PatientConv = generalConv(1L, patientUser, branch1);
        ChatConversation b2PatientConv = generalConv(2L, patientUser, branch2);
        ChatConversation clearedConv = directConv(3L, doctorUser, receptionistUser, branch1);

        LocalDateTime now = LocalDateTime.now();
        ChatMessage patientMsg = msg(10L, b1PatientConv, 40L, SenderType.PATIENT, "Tư vấn niềng răng", now.minusMinutes(2));
        ChatMessage oldDirectMsg = msg(11L, clearedConv, 20L, SenderType.STAFF, "Tin cũ", now.minusMinutes(30));

        ChatConversationUserState clearedState = new ChatConversationUserState();
        clearedState.setConversationId(3L);
        clearedState.setUserId(10L);
        clearedState.setClearedAt(now.minusMinutes(10));

        when(conversationRepository.findAllConversationsWithUserAndBranch())
                .thenReturn(List.of(b1PatientConv, b2PatientConv, clearedConv));
        when(conversationUserStateRepository.findByUserIdAndConversationIdIn(eq(10L), anyList()))
                .thenReturn(List.of(clearedState));
        when(messageUserStateRepository.findByUserIdAndConversationIdIn(eq(10L), anyList()))
                .thenReturn(List.of());
        when(messageRepository.findAllByConversationIdsOrderBySentAtAsc(anyList()))
                .thenReturn(List.of(oldDirectMsg, patientMsg));

        Page<ChatConversationDTO> res = chatService.searchInboxConversations(
                10L, "niềng răng", "BENH_NHAN", 1L, true, PageRequest.of(0, 10));

        assertEquals(1, res.getTotalElements());
        assertEquals(1L, res.getContent().get(0).getConversationId());
        assertEquals(1L, res.getContent().get(0).getUnreadCount());
        assertTrue(res.getContent().get(0).isUnread());
    }

    // ==================== 4. Conversation Messages & Send & Read ====================

    @Test
    void getConversationMessages_BlocksUnauthorizedAndMasksRecalledMessages() {
        stubRole(patientUser, "BENH_NHAN", null, patientProfile);
        User otherPatient = activeUser(41L, "bn41", UserType.PATIENT, branch1);
        ChatConversation otherConv = generalConv(9L, otherPatient, branch1);

        when(conversationRepository.findByIdWithDetails(9L)).thenReturn(Optional.of(otherConv));
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class,
                        () -> chatService.getConversationMessages(40L, 9L)).getStatusCode());

        // Warehouse denied access to general patient conversation
        stubRole(warehouseUser, "QUAN_LY_KHO", warehouseStaff, null);
        ChatConversation ownB1PatientConv = generalConv(1L, patientUser, branch1);
        when(conversationRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ownB1PatientConv));
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class,
                        () -> chatService.getConversationMessages(30L, 1L)).getStatusCode());

        // Patient accesses own conversation -> recalled message masked, first initiator notice populated
        ChatMessage m1 = msg(1L, ownB1PatientConv, 40L, SenderType.PATIENT, "Chào PK", LocalDateTime.now().minusMinutes(5));
        ChatMessage m2 = msg(2L, ownB1PatientConv, 40L, SenderType.PATIENT, "Bí mật", LocalDateTime.now().minusMinutes(2));
        m2.setIsRecalled(true);

        when(conversationRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ownB1PatientConv));
        when(messageRepository.findByConversationIdOrderBySentAtAsc(1L)).thenReturn(List.of(m1, m2));

        Map<String, Object> result = chatService.getConversationMessages(40L, 1L);
        @SuppressWarnings("unchecked")
        List<ChatMessageDTO> dtos = (List<ChatMessageDTO>) result.get("messages");
        assertEquals(2, dtos.size());
        assertTrue(dtos.get(0).isFirstMessageInConversation());
        assertNotNull(dtos.get(0).getSystemNotice());
        assertTrue(dtos.get(1).isRecalled());
        assertEquals("Tin nhắn đã được thu hồi", dtos.get(1).getMessageText());
    }

    @Test
    void sendMessage_ValidatesBlankTooLongClientDedupAndPatientRecipientRestriction() {
        stubRole(patientUser, "BENH_NHAN", null, patientProfile);

        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,
                        () -> chatService.sendMessage(40L, ChatSendRequestDTO.builder().messageText("   ").build()))
                        .getStatusCode());

        String overLimit = "a".repeat(2001);
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,
                        () -> chatService.sendMessage(40L, ChatSendRequestDTO.builder().messageText(overLimit).build()))
                        .getStatusCode());

        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class,
                        () -> chatService.sendMessage(40L,
                                ChatSendRequestDTO.builder().recipientUserId(10L).messageText("Hi").build()))
                        .getStatusCode());
    }

    @Test
    void sendMessage_SavesFirstPatientMessageAndDeduplicatesClientMessageId() {
        stubRole(patientUser, "BENH_NHAN", null, patientProfile);
        ChatConversation conv = generalConv(15L, patientUser, branch1);

        when(conversationRepository.findActiveByUserIdAndBranchId(40L, 1L)).thenReturn(List.of(conv));
        when(messageRepository.countByConversation_ConversationId(15L)).thenReturn(0L);

        ChatSendRequestDTO req = ChatSendRequestDTO.builder()
                .messageText("Xin hỏi giá nhổ răng khôn?")
                .clientMessageId("cli-123")
                .build();

        ChatMessageDTO first = chatService.sendMessage(40L, req);
        assertTrue(first.isFirstMessageInConversation());
        assertNotNull(first.getSystemNotice());
        assertEquals("PATIENT", first.getSenderType());
        verify(messageUserStateRepository, times(1)).save(any(ChatMessageUserState.class));

        // Duplicate clientMessageId returns cached DTO without another DB save
        ChatMessageDTO dup = chatService.sendMessage(40L, req);
        assertSame(first, dup);
        verify(messageRepository, times(1)).save(any(ChatMessage.class));
    }

    @Test
    void markConversationAsRead_UpdatesOnlyUnreadIncomingMessagesForViewer() {
        stubRole(doctorUser, "BAC_SI", doctorStaff, null);
        stubRole(patientUser, "BENH_NHAN", null, patientProfile);
        ChatConversation conv = generalConv(1L, patientUser, branch1);

        LocalDateTime now = LocalDateTime.now();
        ChatMessage patientUnread = msg(1L, conv, 40L, SenderType.PATIENT, "Chào BS", now.minusMinutes(2));
        ChatMessage doctorOwn = msg(2L, conv, 20L, SenderType.STAFF, "Chào bạn", now.minusMinutes(1));

        when(conversationRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(conv));
        when(messageRepository.findByConversationIdOrderBySentAtAsc(1L)).thenReturn(List.of(patientUnread, doctorOwn));
        when(conversationUserStateRepository.findByConversationIdAndUserId(1L, 20L)).thenReturn(Optional.empty());
        when(messageUserStateRepository.findByConversationIdAndUserId(1L, 20L)).thenReturn(List.of());
        when(conversationRepository.findAllConversationsWithUserAndBranch()).thenReturn(List.of());

        Map<String, Object> res = chatService.markConversationAsRead(20L, 1L);
        assertEquals(1, res.get("updatedCount"));
        assertEquals(0, res.get("unreadCount"));
        verify(messageUserStateRepository).saveAll(argThat(iterable -> {
            List<ChatMessageUserState> list = (List<ChatMessageUserState>) iterable;
            return list.size() == 1 && list.get(0).getMessageId().equals(1L) && Boolean.TRUE.equals(list.get(0).getIsRead());
        }));
        verify(conversationUserStateRepository).save(any(ChatConversationUserState.class));
    }

    // ==================== 5. Edit, Recall, Delete Message & Delete Conversation ====================

    @Test
    void editMessage_EnforcesOwnershipRecallAndTenMinuteWindow() {
        stubRole(patientUser, "BENH_NHAN", null, patientProfile);
        stubRole(receptionistUser, "LE_TAN", receptionistStaff, null);
        ChatConversation conv = generalConv(1L, patientUser, branch1);

        ChatMessage patientMsg = msg(10L, conv, 40L, SenderType.PATIENT, "Gốc", LocalDateTime.now().minusMinutes(2));
        when(messageRepository.findByIdWithConversation(10L)).thenReturn(Optional.of(patientMsg));
        when(conversationRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(conv));

        // Receptionist tries editing patient message -> 403
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class,
                        () -> chatService.editMessage(10L, 10L, "Sửa trộm")).getStatusCode());

        // Past 10-minute window -> 400
        patientMsg.setSentAt(LocalDateTime.now().minusMinutes(12));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,
                        () -> chatService.editMessage(40L, 10L, "Hết hạn")).getStatusCode());

        // Within 10-minute window -> success
        patientMsg.setSentAt(LocalDateTime.now().minusMinutes(3));
        ChatMessageDTO edited = chatService.editMessage(40L, 10L, "Đã sửa hợp lệ");
        assertEquals("EDIT", edited.getEventType());
        assertEquals("Đã sửa hợp lệ", edited.getMessageText());
        assertTrue(edited.isEdited());
    }

    @Test
    void recallMessage_EnforcesOwnershipAlreadyRecalledWindowAndMasksText() {
        stubRole(patientUser, "BENH_NHAN", null, patientProfile);
        stubRole(receptionistUser, "LE_TAN", receptionistStaff, null);
        ChatConversation conv = generalConv(1L, patientUser, branch1);

        ChatMessage m = msg(20L, conv, 40L, SenderType.PATIENT, "Tin nhầm", LocalDateTime.now().minusMinutes(1));
        when(messageRepository.findByIdWithConversation(20L)).thenReturn(Optional.of(m));
        when(conversationRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(conv));

        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> chatService.recallMessage(10L, 20L)).getStatusCode());

        ChatMessageDTO recalled = chatService.recallMessage(40L, 20L);
        assertEquals("RECALL", recalled.getEventType());
        assertTrue(recalled.isRecalled());
        assertEquals("Tin nhắn đã được thu hồi", recalled.getMessageText());

        // Recalling again throws 400
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> chatService.recallMessage(40L, 20L)).getStatusCode());
    }

    @Test
    void deleteMessageForUserAndDeleteConversationForUser_OnlyAffectCurrentUserState() {
        stubRole(patientUser, "BENH_NHAN", null, patientProfile);
        ChatConversation conv = generalConv(1L, patientUser, branch1);
        ChatMessage m = msg(30L, conv, 10L, SenderType.STAFF, "Chào", LocalDateTime.now().minusMinutes(1));

        when(messageRepository.findByIdWithConversation(30L)).thenReturn(Optional.of(m));
        when(conversationRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(conv));
        when(messageUserStateRepository.findByMessageIdAndUserId(30L, 40L)).thenReturn(Optional.empty());
        when(conversationRepository.findActiveByUserIdAndBranchId(40L, 1L)).thenReturn(List.of());

        Map<String, Object> msgDel = chatService.deleteMessageForUser(40L, 30L);
        assertEquals(30L, msgDel.get("messageId"));
        assertEquals(40L, msgDel.get("deletedForUserId"));
        verify(messageUserStateRepository).save(argThat(s ->
                Boolean.TRUE.equals(s.getIsDeleted()) && Boolean.TRUE.equals(s.getIsRead()) && s.getUserId().equals(40L)));

        when(messageRepository.findByConversationIdOrderBySentAtAsc(1L)).thenReturn(List.of(m));
        when(conversationUserStateRepository.findByConversationIdAndUserId(1L, 40L)).thenReturn(Optional.empty());

        Map<String, Object> convDel = chatService.deleteConversationForUser(40L, 1L);
        assertEquals(1L, convDel.get("conversationId"));
        assertEquals(40L, convDel.get("deletedForUserId"));
        verify(conversationUserStateRepository).save(argThat(s ->
                s.getClearedAt() != null && s.getLastReadAt() != null && s.getUserId().equals(40L)));
    }

    // ==================== 6. Additional Atomic Branch & Edge-Case Tests ====================

    @Test
    void requireInboxAccess_ThrowsUnauthorizedOrForbidden_WhenUserMissingOrInactiveOrLocked() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.UNAUTHORIZED,
                assertThrows(ResponseStatusException.class, () -> chatService.requireInboxAccess(999L)).getStatusCode());

        User inactive = activeUser(998L, "inactive", UserType.STAFF, branch1);
        inactive.setIsActive(false);
        when(userRepository.findById(998L)).thenReturn(Optional.of(inactive));
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> chatService.requireInboxAccess(998L)).getStatusCode());

        User locked = activeUser(997L, "locked", UserType.STAFF, branch1);
        locked.setStatus("LOCKED");
        when(userRepository.findById(997L)).thenReturn(Optional.of(locked));
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> chatService.requireInboxAccess(997L)).getStatusCode());
    }

    @Test
    void resolvePrimaryRoleCode_InfersDoctorAndAccountantAndReadsEntityRoleFallback() {
        when(userRepository.hasActiveAdminRole(20L)).thenReturn(false);
        when(conversationRepository.findActiveRoleNamesByUserId(20L)).thenReturn(List.of());
        when(staffProfileRepository.findByUser_UserId(20L)).thenReturn(Optional.of(doctorStaff));
        assertEquals("BAC_SI", chatService.resolvePrimaryRoleCode(20L));

        StaffProfile accStaff = new StaffProfile();
        accStaff.setDepartment("Kế toán tài chính");
        accStaff.setStatus(StaffStatus.ACTIVE);
        when(userRepository.hasActiveAdminRole(35L)).thenReturn(false);
        when(conversationRepository.findActiveRoleNamesByUserId(35L)).thenReturn(List.of());
        when(staffProfileRepository.findByUser_UserId(35L)).thenReturn(Optional.of(accStaff));
        assertEquals("KE_TOAN", chatService.resolvePrimaryRoleCode(35L));

        Role role = new Role();
        role.setRoleName("THU_NGAN");
        role.setIsActive(true);
        User entUser = activeUser(36L, "tn36", UserType.STAFF, branch1);
        entUser.setRoles(Set.of(role));
        when(userRepository.hasActiveAdminRole(36L)).thenReturn(false);
        when(conversationRepository.findActiveRoleNamesByUserId(36L)).thenReturn(List.of());
        when(staffProfileRepository.findByUser_UserId(36L)).thenReturn(Optional.empty());
        when(userRepository.findById(36L)).thenReturn(Optional.of(entUser));
        assertEquals("KE_TOAN", chatService.resolvePrimaryRoleCode(36L));
    }

    @Test
    void getAccessibleBranchesForInbox_FallsBackToInferredBranchAndAllBranchesOrderByName() {
        User staffUser = activeUser(12L, "lt12", UserType.STAFF, null);
        stubRole(staffUser, "LE_TAN", receptionistStaff, null);
        when(conversationRepository.findExplicitBranchIdsByUserId(12L)).thenReturn(List.of());
        when(customerCareTaskRepository.findInferredBranchIdsByStaffId(100L)).thenReturn(List.of(2L));

        List<Branch> inferred = chatService.getAccessibleBranchesForInbox(12L);
        assertEquals(1, inferred.size());
        assertEquals(2L, inferred.get(0).getBranchId());

        when(branchRepository.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of());
        when(branchRepository.findAllByOrderByBranchNameAsc()).thenReturn(List.of(branch1, branch2));
        stubRole(adminUser, "ADMIN", null, null);
        assertEquals(2, chatService.getAccessibleBranchesForInbox(1L).size());
    }

    @Test
    void searchRecipientUsers_SortsStaffAlphabeticallyBeforePatientsAndSkipsInactiveCandidates() {
        stubRole(receptionistUser, "LE_TAN", receptionistStaff, null);
        stubRole(doctorUser, "BAC_SI", doctorStaff, null);
        stubRole(warehouseUser, "QUAN_LY_KHO", warehouseStaff, null);
        stubRole(patientUser, "BENH_NHAN", null, patientProfile);

        User inactiveStaff = activeUser(90L, "inactive90", UserType.STAFF, branch1);
        inactiveStaff.setIsActive(false);

        when(userRepository.findAll()).thenReturn(List.of(patientUser, inactiveStaff, warehouseUser, doctorUser, receptionistUser));
        when(conversationRepository.findAllConversationsWithUserAndBranch()).thenReturn(List.of());

        List<ChatUserSearchItemDTO> list = chatService.searchRecipientUsers(10L, null, "ALL", null);
        assertEquals(3, list.size());
        // Staff sorted before patient
        assertEquals("BS. Trần Minh", list.get(0).getFullName());
        assertEquals("Lê Thủ Kho", list.get(1).getFullName());
        assertEquals("BENH_NHAN", list.get(2).getRoleCode());
    }

    @Test
    void startOrGetConversationWithRecipient_CreatesNewPatientGeneralAndDirectConversationsWhenNoneActive() {
        stubRole(receptionistUser, "LE_TAN", receptionistStaff, null);
        stubRole(patientUser, "BENH_NHAN", null, patientProfile);
        stubRole(doctorUser, "BAC_SI", doctorStaff, null);

        when(conversationRepository.findActiveByUserIdAndBranchId(40L, 1L)).thenReturn(List.of());
        when(conversationRepository.findActiveByUserIdWithNullBranch(40L)).thenReturn(List.of());
        ChatConversation savedGeneral = generalConv(500L, patientUser, branch1);
        when(conversationRepository.findByIdWithDetails(500L)).thenReturn(Optional.of(savedGeneral));
        when(messageRepository.findByConversationIdOrderBySentAtAsc(500L)).thenReturn(List.of());

        Map<String, Object> generalRes = chatService.startOrGetConversationWithRecipient(10L, 40L, null);
        assertEquals(500L, generalRes.get("conversationId"));
        assertEquals(false, generalRes.get("directConversation"));

        when(conversationRepository.findActiveDirectConversationsBetweenUsers(10L, 20L)).thenReturn(List.of());
        ChatConversation savedDirect = directConv(500L, receptionistUser, doctorUser, branch1);
        when(conversationRepository.findByIdWithDetails(500L)).thenReturn(Optional.of(savedDirect));

        Map<String, Object> directRes = chatService.startOrGetConversationWithRecipient(10L, 20L, 1L);
        assertEquals(true, directRes.get("directConversation"));
    }

    @Test
    void getTotalUnreadCount_WarehouseIgnoresPatientChatsButCountsStaffDirectMessages() {
        stubRole(warehouseUser, "QUAN_LY_KHO", warehouseStaff, null);
        stubRole(patientUser, "BENH_NHAN", null, patientProfile);
        stubRole(doctorUser, "BAC_SI", doctorStaff, null);

        ChatConversation generalPatientConv = generalConv(1L, patientUser, branch1);
        ChatConversation staffDirectConv = directConv(2L, doctorUser, warehouseUser, branch1);
        ChatMessage mPatient = msg(1L, generalPatientConv, 40L, SenderType.PATIENT, "Hi PK", LocalDateTime.now().minusMinutes(2));
        ChatMessage mDoc = msg(2L, staffDirectConv, 20L, SenderType.STAFF, "Kiểm tra tồn kho găng tay", LocalDateTime.now().minusMinutes(1));

        when(conversationRepository.findAllConversationsWithUserAndBranch()).thenReturn(List.of(generalPatientConv, staffDirectConv));
        when(conversationUserStateRepository.findByUserIdAndConversationIdIn(30L, List.of(2L))).thenReturn(List.of());
        when(messageUserStateRepository.findByUserIdAndConversationIdIn(30L, List.of(2L))).thenReturn(List.of());
        when(messageRepository.findAllByConversationIdsOrderBySentAtAsc(List.of(2L))).thenReturn(List.of(mDoc));

        assertEquals(1L, chatService.getTotalUnreadCount(30L));
    }

    @Test
    void searchInboxConversations_ShowsClearedConversationAgainWhenNewerMessageArrivesAfterClearedAt() {
        stubRole(adminUser, "ADMIN", null, null);
        stubRole(doctorUser, "BAC_SI", doctorStaff, null);
        stubRole(receptionistUser, "LE_TAN", receptionistStaff, null);

        ChatConversation direct = directConv(10L, doctorUser, receptionistUser, branch2);
        LocalDateTime now = LocalDateTime.now();
        ChatMessage oldMsg = msg(1L, direct, 20L, SenderType.STAFF, "Tin cũ", now.minusMinutes(20));
        ChatMessage newMsg = msg(2L, direct, 10L, SenderType.STAFF, "Tin mới sau khi xóa", now.minusMinutes(1));

        ChatConversationUserState clearedEarlier = new ChatConversationUserState();
        clearedEarlier.setConversationId(10L);
        clearedEarlier.setUserId(1L);
        clearedEarlier.setClearedAt(now.minusMinutes(10));

        when(conversationRepository.findAllConversationsWithUserAndBranch()).thenReturn(List.of(direct));
        when(conversationUserStateRepository.findByUserIdAndConversationIdIn(1L, List.of(10L))).thenReturn(List.of(clearedEarlier));
        when(messageUserStateRepository.findByUserIdAndConversationIdIn(1L, List.of(10L))).thenReturn(List.of());
        when(messageRepository.findAllByConversationIdsOrderBySentAtAsc(List.of(10L))).thenReturn(List.of(oldMsg, newMsg));

        Page<ChatConversationDTO> page = chatService.searchInboxConversations(1L, null, null, null, null, PageRequest.of(0, 10));
        assertEquals(1, page.getTotalElements());
        assertEquals("Tin mới sau khi xóa", page.getContent().get(0).getLastMessageText());
    }

    @Test
    void searchInboxConversations_ExcludesRecalledMessagesFromKeywordSearch() {
        stubRole(adminUser, "ADMIN", null, null);
        stubRole(patientUser, "BENH_NHAN", null, patientProfile);

        ChatConversation conv = generalConv(1L, patientUser, branch1);
        ChatMessage recalled = msg(1L, conv, 40L, SenderType.PATIENT, "Từ khóa nhạy cảm", LocalDateTime.now().minusMinutes(1));
        recalled.setIsRecalled(true);

        when(conversationRepository.findAllConversationsWithUserAndBranch()).thenReturn(List.of(conv));
        when(conversationUserStateRepository.findByUserIdAndConversationIdIn(1L, List.of(1L))).thenReturn(List.of());
        when(messageUserStateRepository.findByUserIdAndConversationIdIn(1L, List.of(1L))).thenReturn(List.of());
        when(messageRepository.findAllByConversationIdsOrderBySentAtAsc(List.of(1L))).thenReturn(List.of(recalled));

        Page<ChatConversationDTO> res = chatService.searchInboxConversations(1L, "nhạy cảm", null, null, null, PageRequest.of(0, 10));
        assertEquals(0, res.getTotalElements());
    }

    @Test
    void getConversationMessages_ThrowsNotFoundOrForbiddenForDirectThirdPartyOrCrossBranch() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(receptionistUser));
        when(conversationRepository.findByIdWithDetails(999L)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> chatService.getConversationMessages(10L, 999L)).getStatusCode());

        stubRole(receptionistUser, "LE_TAN", receptionistStaff, null);
        User otherRec = activeUser(11L, "lt11", UserType.STAFF, branch1);
        ChatConversation othersDirect = directConv(5L, doctorUser, otherRec, branch1);
        when(conversationRepository.findByIdWithDetails(5L)).thenReturn(Optional.of(othersDirect));
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> chatService.getConversationMessages(10L, 5L)).getStatusCode());

        ChatConversation otherBranch = generalConv(6L, patientUser, branch2);
        when(conversationRepository.findByIdWithDetails(6L)).thenReturn(Optional.of(otherBranch));
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> chatService.getConversationMessages(10L, 6L)).getStatusCode());
    }

    @Test
    void getConversationMessages_FiltersPerUserDeletedAndClearedMessagesPreservingNewerOnes() {
        stubRole(patientUser, "BENH_NHAN", null, patientProfile);
        ChatConversation conv = generalConv(1L, patientUser, branch1);
        LocalDateTime now = LocalDateTime.now();

        ChatMessage mBeforeClear = msg(1L, conv, 40L, SenderType.PATIENT, "Trước khi xóa", now.minusMinutes(10));
        ChatMessage mSingleDeleted = msg(2L, conv, 10L, SenderType.STAFF, "Tin bị xóa đơn", now.minusMinutes(3));
        ChatMessage mVisible = msg(3L, conv, 10L, SenderType.STAFF, "Tin hiển thị", now.minusMinutes(1));

        ChatConversationUserState convState = new ChatConversationUserState();
        convState.setClearedAt(now.minusMinutes(5));
        ChatMessageUserState msgState = new ChatMessageUserState();
        msgState.setMessageId(2L);
        msgState.setIsDeleted(true);

        when(conversationRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(conv));
        when(messageRepository.findByConversationIdOrderBySentAtAsc(1L)).thenReturn(List.of(mBeforeClear, mSingleDeleted, mVisible));
        when(conversationUserStateRepository.findByConversationIdAndUserId(1L, 40L)).thenReturn(Optional.of(convState));
        when(messageUserStateRepository.findByConversationIdAndUserId(1L, 40L)).thenReturn(List.of(msgState));

        Map<String, Object> res = chatService.getConversationMessages(40L, 1L);
        @SuppressWarnings("unchecked")
        List<ChatMessageDTO> dtos = (List<ChatMessageDTO>) res.get("messages");
        assertEquals(1, dtos.size());
        assertEquals(3L, dtos.get(0).getMessageId());
    }

    @Test
    void sendMessage_RequiresRecipientForWarehouseWithoutConversationId_AndAssignsStaffOnReply() {
        stubRole(warehouseUser, "QUAN_LY_KHO", warehouseStaff, null);
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,
                        () -> chatService.sendMessage(30L, ChatSendRequestDTO.builder().messageText("Hello").build()))
                        .getStatusCode());

        // Receptionist replies to closed patient conversation -> reopens to STAFF_HANDLING & sets assignedStaff
        stubRole(receptionistUser, "LE_TAN", receptionistStaff, null);
        stubRole(patientUser, "BENH_NHAN", null, patientProfile);
        ChatConversation closedConv = generalConv(1L, patientUser, null);
        closedConv.setStatus(ChatStatus.CLOSED);
        when(conversationRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(closedConv));
        when(messageRepository.countByConversation_ConversationId(1L)).thenReturn(1L);

        ChatMessageDTO reply = chatService.sendMessage(10L,
                ChatSendRequestDTO.builder().conversationId(1L).messageText("Phòng khám xin chào bạn").build());
        assertEquals("STAFF", reply.getSenderType());
        assertFalse(reply.isFirstMessageInConversation());
        assertEquals(receptionistStaff, closedConv.getAssignedStaff());
        assertEquals(ChatStatus.STAFF_HANDLING, closedConv.getStatus());
        assertNotNull(closedConv.getBranch());
    }

    @Test
    void sendMessage_CreatesDirectConversationWhenRecipientUserIdProvided() {
        stubRole(warehouseUser, "QUAN_LY_KHO", warehouseStaff, null);
        stubRole(doctorUser, "BAC_SI", doctorStaff, null);

        when(conversationRepository.findActiveDirectConversationsBetweenUsers(30L, 20L)).thenReturn(List.of());
        ChatMessageDTO dto = chatService.sendMessage(30L,
                ChatSendRequestDTO.builder().recipientUserId(20L).messageText("Gửi bác sĩ Minh").build());

        assertTrue(dto.isDirectConversation());
        assertEquals(20L, dto.getRecipientUserId());
        assertEquals("Gửi bác sĩ Minh", dto.getMessageText());
    }

    @Test
    void editMessage_ThrowsBadRequestOrNotFound_WhenInvalidIdMissingTextOrAlreadyRecalled() {
        when(userRepository.findById(40L)).thenReturn(Optional.of(patientUser));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> chatService.editMessage(40L, 0L, "abc")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> chatService.editMessage(40L, 1L, "   ")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> chatService.editMessage(40L, 1L, "x".repeat(2001))).getStatusCode());

        when(messageRepository.findByIdWithConversation(99L)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> chatService.editMessage(40L, 99L, "Hợp lệ")).getStatusCode());

        stubRole(patientUser, "BENH_NHAN", null, patientProfile);
        ChatConversation conv = generalConv(1L, patientUser, branch1);
        ChatMessage recalledMsg = msg(10L, conv, 40L, SenderType.PATIENT, "Gỡ rồi", LocalDateTime.now().minusMinutes(1));
        recalledMsg.setIsRecalled(true);
        when(messageRepository.findByIdWithConversation(10L)).thenReturn(Optional.of(recalledMsg));
        when(conversationRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(conv));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> chatService.editMessage(40L, 10L, "Sửa lại")).getStatusCode());
    }

    @Test
    void recallMessage_ThrowsBadRequest_WhenInvalidIdNotFoundOrWindowExpired() {
        when(userRepository.findById(40L)).thenReturn(Optional.of(patientUser));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> chatService.recallMessage(40L, null)).getStatusCode());

        when(messageRepository.findByIdWithConversation(99L)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> chatService.recallMessage(40L, 99L)).getStatusCode());

        stubRole(patientUser, "BENH_NHAN", null, patientProfile);
        ChatConversation conv = generalConv(1L, patientUser, branch1);
        ChatMessage expired = msg(11L, conv, 40L, SenderType.PATIENT, "Quá hạn", LocalDateTime.now().minusMinutes(11));
        when(messageRepository.findByIdWithConversation(11L)).thenReturn(Optional.of(expired));
        when(conversationRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(conv));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> chatService.recallMessage(40L, 11L)).getStatusCode());
    }

    @Test
    void deleteMessageForUser_AllowsRecipientToDeleteIncomingMessageForSelfAndRejectsInvalidOrMissing() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(receptionistUser));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> chatService.deleteMessageForUser(10L, -1L)).getStatusCode());

        when(messageRepository.findByIdWithConversation(99L)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> chatService.deleteMessageForUser(10L, 99L)).getStatusCode());

        stubRole(receptionistUser, "LE_TAN", receptionistStaff, null);
        stubRole(patientUser, "BENH_NHAN", null, patientProfile);
        ChatConversation conv = generalConv(1L, patientUser, branch1);
        ChatMessage patientMsg = msg(30L, conv, 40L, SenderType.PATIENT, "Xin chào", LocalDateTime.now());
        when(messageRepository.findByIdWithConversation(30L)).thenReturn(Optional.of(patientMsg));
        when(conversationRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(conv));
        when(messageUserStateRepository.findByMessageIdAndUserId(30L, 10L)).thenReturn(Optional.empty());
        when(conversationRepository.findAllConversationsWithUserAndBranch()).thenReturn(List.of());

        Map<String, Object> res = chatService.deleteMessageForUser(10L, 30L);
        assertEquals(10L, res.get("deletedForUserId"));
        verify(messageUserStateRepository).save(argThat(s -> s.getUserId().equals(10L) && Boolean.TRUE.equals(s.getIsDeleted())));
    }

    @Test
    void deleteConversationForUser_ThrowsBadRequestOrNotFound_WhenInvalidOrMissing() {
        when(userRepository.findById(40L)).thenReturn(Optional.of(patientUser));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> chatService.deleteConversationForUser(40L, 0L)).getStatusCode());

        when(conversationRepository.findByIdWithDetails(999L)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> chatService.deleteConversationForUser(40L, 999L)).getStatusCode());
    }
}
