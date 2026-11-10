//package com.cvdcms;
//
//import com.cvdcms.DTO.ChatConversationDTO;
//import com.cvdcms.DTO.ChatMessageDTO;
//import com.cvdcms.DTO.ChatSendRequestDTO;
//import com.cvdcms.DTO.ChatUserSearchItemDTO;
//import com.cvdcms.config.ChatStompChannelInterceptor;
//import com.cvdcms.entity.*;
//import com.cvdcms.entity.enums.ChatStatus;
//import com.cvdcms.entity.enums.SenderType;
//import com.cvdcms.entity.enums.StaffStatus;
//import com.cvdcms.repository.*;
//import com.cvdcms.service.ChatService;
//import com.cvdcms.service.impl.ChatServiceImpl;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//import org.springframework.data.domain.Page;
//import org.springframework.data.domain.PageRequest;
//import org.springframework.security.access.AccessDeniedException;
//import org.springframework.web.server.ResponseStatusException;
//
//import java.time.LocalDateTime;
//import java.util.*;
//import java.util.concurrent.atomic.AtomicLong;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.*;
//import static org.mockito.Mockito.*;
//
///**
// * Unit Test kiểm thử toàn diện chức năng Realtime Chat theo Cơ sở (Branch-Scoped Realtime Chat):
// * 1. Không có AI Chatbot / Không tạo tin nhắn BOT; hiển thị đúng thông báo hệ thống ở tin nhắn đầu tiên.
// * 2. Chỉ Lễ tân (LE_TAN), Bác sĩ (BAC_SI) thuộc cùng cơ sở và Admin (ADMIN) mới được nhận, xem và phản hồi tin nhắn của Bệnh nhân.
// *    Quản lý kho (QUAN_LY_KHO) và Kế toán (KE_TOAN) cùng cơ sở bị chặn hoàn toàn việc xem/phản hồi tin nhắn của Bệnh nhân.
// * 3. Chỉ Admin mới được xem tin nhắn của tất cả cơ sở; các role khác được phân ở cơ sở nào chỉ xem được tin nhắn ở cơ sở đó.
// * 4. Các vai trò ngoài Bệnh nhân có thể tìm kiếm tài khoản được phép trong cùng cơ sở để gửi tin nhắn (hỗ trợ 1 tài khoản bệnh nhân có nhiều hồ sơ).
// * 5. Quản lý trạng thái Đã đọc / Chưa đọc độc lập theo từng tài khoản (User ID) và cập nhật Badge tổng tin chưa đọc chính xác.
// * 6. Chỉnh sửa và Thu hồi tin nhắn trong giới hạn 10 phút kể từ lúc gửi.
// * 7. Xóa tin nhắn / Xóa cuộc trò chuyện ở phía tôi (Self-delete) độc lập cho từng tài khoản.
// */
//@ExtendWith(MockitoExtension.class)
//class ChatWorkflowUnitTest {
//
//    @Mock
//    private ChatConversationRepository conversationRepository;
//    @Mock
//    private ChatMessageRepository messageRepository;
//    @Mock
//    private ChatMessageUserStateRepository messageUserStateRepository;
//    @Mock
//    private ChatConversationUserStateRepository conversationUserStateRepository;
//    @Mock
//    private UserRepository userRepository;
//    @Mock
//    private BranchRepository branchRepository;
//    @Mock
//    private StaffProfileRepository staffProfileRepository;
//    @Mock
//    private PatientProfileRepository patientProfileRepository;
//    @Mock
//    private CustomerCareTaskRepository customerCareTaskRepository;
//
//    private ChatService chatService;
//    private ChatStompChannelInterceptor stompInterceptor;
//
//    private Branch branch1;
//    private Branch branch2;
//
//    private User adminUser;
//    private User recep1Branch1;
//    private User recep2Branch1;
//    private User doctorBranch1;
//    private User warehouseBranch1;
//    private User recepBranch2;
//    private User patientBranch1;
//
//    private final Map<Long, ChatConversation> conversationStore = new LinkedHashMap<>();
//    private final List<ChatMessage> messageStore = new ArrayList<>();
//    private final Map<String, ChatMessageUserState> messageUserStateStore = new LinkedHashMap<>();
//    private final Map<String, ChatConversationUserState> conversationUserStateStore = new LinkedHashMap<>();
//    private final AtomicLong convSeq = new AtomicLong(100);
//    private final AtomicLong msgSeq = new AtomicLong(1000);
//    private final AtomicLong stateSeq = new AtomicLong(5000);
//
//    @BeforeEach
//    void setUp() {
//        chatService = new ChatServiceImpl(
//                conversationRepository,
//                messageRepository,
//                messageUserStateRepository,
//                conversationUserStateRepository,
//                userRepository,
//                branchRepository,
//                staffProfileRepository,
//                patientProfileRepository,
//                customerCareTaskRepository
//        );
//        stompInterceptor = new ChatStompChannelInterceptor(chatService);
//
//        branch1 = new Branch();
//        branch1.setBranchId(1L);
//        branch1.setBranchCode("CN01");
//        branch1.setBranchName("Nha Khoa CV-DCMS Quận 1");
//        branch1.setIsActive(true);
//
//        branch2 = new Branch();
//        branch2.setBranchId(2L);
//        branch2.setBranchCode("CN02");
//        branch2.setBranchName("Nha Khoa CV-DCMS Quận 3");
//        branch2.setIsActive(true);
//
//        lenient().when(branchRepository.findByIsActiveTrueOrderByBranchNameAsc())
//                .thenReturn(List.of(branch1, branch2));
//        lenient().when(branchRepository.findById(1L)).thenReturn(Optional.of(branch1));
//        lenient().when(branchRepository.findById(2L)).thenReturn(Optional.of(branch2));
//
//        // 1. Admin
//        adminUser = buildUser(1L, "admin", "ADMIN", null);
//        lenient().when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
//
//        // 2. Lễ tân 1 tại Cơ sở 1
//        recep1Branch1 = buildUser(10L, "letan01", "LE_TAN", branch1);
//        mockStaffProfile(10L, 101L, "Lê Thị Lễ Tân A", "Lễ tân", "Nhân viên CSKH");
//        lenient().when(conversationRepository.findExplicitBranchIdsByUserId(10L)).thenReturn(List.of(1L));
//
//        // 3. Lễ tân 2 tại Cơ sở 1
//        recep2Branch1 = buildUser(12L, "letan02", "LE_TAN", branch1);
//        mockStaffProfile(12L, 102L, "Trần Văn Lễ Tân B", "Lễ tân", "Lễ tân");
//        lenient().when(conversationRepository.findExplicitBranchIdsByUserId(12L)).thenReturn(List.of(1L));
//
//        // 4. Bác sĩ tại Cơ sở 1
//        doctorBranch1 = buildUser(11L, "bacsi01", "BAC_SI", branch1);
//        mockStaffProfile(11L, 103L, "Dr. Nguyễn Văn Bác Sĩ", "Lâm sàng", "Bác sĩ RHM");
//        lenient().when(conversationRepository.findExplicitBranchIdsByUserId(11L)).thenReturn(List.of(1L));
//
//        // 5. Quản lý kho tại Cơ sở 1
//        warehouseBranch1 = buildUser(14L, "kho01", "QUAN_LY_KHO", branch1);
//        mockStaffProfile(14L, 104L, "Hoàng Văn Quản Lý Kho", "Kho vật tư", "Quản lý kho");
//        lenient().when(conversationRepository.findExplicitBranchIdsByUserId(14L)).thenReturn(List.of(1L));
//
//        // 6. Lễ tân tại Cơ sở 2
//        recepBranch2 = buildUser(15L, "letan_cn2", "LE_TAN", branch2);
//        mockStaffProfile(15L, 105L, "Phạm Thị Lễ Tân CN2", "Lễ tân", "Lễ tân");
//        lenient().when(conversationRepository.findExplicitBranchIdsByUserId(15L)).thenReturn(List.of(2L));
//
//        // 7. Bệnh nhân gắn với Cơ sở 1 (có 2 hồ sơ bệnh nhân dưới cùng 1 tài khoản)
//        patientBranch1 = buildUser(20L, "benhnhan01", "BENH_NHAN", branch1);
//        PatientProfile patientProfile1 = new PatientProfile();
//        patientProfile1.setProfileId(200L);
//        patientProfile1.setPatientCode("BN000200");
//        patientProfile1.setUser(patientBranch1);
//        patientProfile1.setFullName("Nguyễn Văn Hùng");
//        patientProfile1.setPhoneNumber("090000020");
//        patientProfile1.setRelationship("Bản thân");
//        patientProfile1.setIsDefault(true);
//        patientProfile1.setPreferredBranch(branch1);
//
//        PatientProfile patientProfile2 = new PatientProfile();
//        patientProfile2.setProfileId(201L);
//        patientProfile2.setPatientCode("BN000201");
//        patientProfile2.setUser(patientBranch1);
//        patientProfile2.setFullName("Bé Nguyễn Minh Khôi");
//        patientProfile2.setPhoneNumber("090000020");
//        patientProfile2.setRelationship("Con trai");
//        patientProfile2.setIsDefault(false);
//        patientProfile2.setPreferredBranch(branch1);
//
//        lenient().when(patientProfileRepository.findFirstByUser_UserIdAndIsDefaultTrue(20L))
//                .thenReturn(Optional.of(patientProfile1));
//        lenient().when(patientProfileRepository.findByUser_UserIdOrderByIsDefaultDescProfileIdAsc(20L))
//                .thenReturn(List.of(patientProfile1, patientProfile2));
//
//        lenient().when(userRepository.findAll()).thenReturn(List.of(
//                adminUser, recep1Branch1, recep2Branch1, doctorBranch1, warehouseBranch1, recepBranch2, patientBranch1
//        ));
//
//        lenient().when(conversationRepository.save(any(ChatConversation.class))).thenAnswer(inv -> {
//            ChatConversation c = inv.getArgument(0);
//            if (c.getConversationId() == null) {
//                c.setConversationId(convSeq.incrementAndGet());
//            }
//            conversationStore.put(c.getConversationId(), c);
//            return c;
//        });
//
//        lenient().when(conversationRepository.findByIdWithDetails(anyLong())).thenAnswer(inv -> {
//            Long id = inv.getArgument(0);
//            return Optional.ofNullable(conversationStore.get(id));
//        });
//
//        lenient().when(conversationRepository.findActiveByUserIdAndBranchId(anyLong(), anyLong())).thenAnswer(inv -> {
//            Long uid = inv.getArgument(0);
//            Long bid = inv.getArgument(1);
//            return conversationStore.values().stream()
//                    .filter(c -> c.getUser() != null && Objects.equals(c.getUser().getUserId(), uid))
//                    .filter(c -> c.getRecipientUser() == null)
//                    .filter(c -> c.getBranch() != null && Objects.equals(c.getBranch().getBranchId(), bid))
//                    .filter(c -> c.getStatus() != ChatStatus.CLOSED)
//                    .toList();
//        });
//
//        lenient().when(conversationRepository.findActiveDirectConversationsBetweenUsers(anyLong(), anyLong())).thenAnswer(inv -> {
//            Long uA = inv.getArgument(0);
//            Long uB = inv.getArgument(1);
//            return conversationStore.values().stream()
//                    .filter(c -> c.getRecipientUser() != null && c.getUser() != null)
//                    .filter(c -> {
//                        Long id1 = c.getUser().getUserId();
//                        Long id2 = c.getRecipientUser().getUserId();
//                        return (Objects.equals(id1, uA) && Objects.equals(id2, uB))
//                                || (Objects.equals(id1, uB) && Objects.equals(id2, uA));
//                    })
//                    .filter(c -> c.getStatus() != ChatStatus.CLOSED)
//                    .toList();
//        });
//
//        lenient().when(conversationRepository.findAllConversationsWithUserAndBranch()).thenAnswer(inv ->
//                new ArrayList<>(conversationStore.values())
//        );
//
//        lenient().when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> {
//            ChatMessage m = inv.getArgument(0);
//            if (m.getMessageId() == null) {
//                m.setMessageId(msgSeq.incrementAndGet());
//                messageStore.add(m);
//            } else {
//                int idx = -1;
//                for (int i = 0; i < messageStore.size(); i++) {
//                    if (Objects.equals(messageStore.get(i).getMessageId(), m.getMessageId())) {
//                        idx = i;
//                        break;
//                    }
//                }
//                if (idx >= 0) {
//                    messageStore.set(idx, m);
//                } else {
//                    messageStore.add(m);
//                }
//            }
//            return m;
//        });
//
//        lenient().when(messageRepository.findByIdWithConversation(anyLong())).thenAnswer(inv -> {
//            Long mid = inv.getArgument(0);
//            return messageStore.stream()
//                    .filter(m -> Objects.equals(m.getMessageId(), mid))
//                    .findFirst();
//        });
//
//        lenient().when(messageRepository.countByConversation_ConversationId(anyLong())).thenAnswer(inv -> {
//            Long cid = inv.getArgument(0);
//            return messageStore.stream()
//                    .filter(m -> m.getConversation() != null && Objects.equals(m.getConversation().getConversationId(), cid))
//                    .count();
//        });
//
//        lenient().when(messageRepository.findByConversationIdOrderBySentAtAsc(anyLong())).thenAnswer(inv -> {
//            Long cid = inv.getArgument(0);
//            return messageStore.stream()
//                    .filter(m -> m.getConversation() != null && Objects.equals(m.getConversation().getConversationId(), cid))
//                    .toList();
//        });
//
//        lenient().when(messageRepository.findAllByConversationIdsOrderBySentAtAsc(anyCollection())).thenAnswer(inv -> {
//            Collection<Long> cids = inv.getArgument(0);
//            return messageStore.stream()
//                    .filter(m -> m.getConversation() != null && cids.contains(m.getConversation().getConversationId()))
//                    .toList();
//        });
//
//        lenient().when(messageRepository.findLatestMessagesByConversationIds(anyCollection())).thenAnswer(inv -> {
//            Collection<Long> cids = inv.getArgument(0);
//            Map<Long, ChatMessage> latest = new HashMap<>();
//            for (ChatMessage m : messageStore) {
//                Long cid = m.getConversation().getConversationId();
//                if (cids.contains(cid)) {
//                    latest.put(cid, m);
//                }
//            }
//            return new ArrayList<>(latest.values());
//        });
//
//        lenient().when(messageRepository.findConversationIdsByMessageKeyword(anyString())).thenAnswer(inv -> {
//            String kw = ((String) inv.getArgument(0)).toLowerCase(Locale.ROOT);
//            return messageStore.stream()
//                    .filter(m -> m.getMessageText() != null && m.getMessageText().toLowerCase(Locale.ROOT).contains(kw))
//                    .map(m -> m.getConversation().getConversationId())
//                    .distinct()
//                    .toList();
//        });
//
//        // In-memory store wiring for ChatMessageUserStateRepository
//        lenient().when(messageUserStateRepository.save(any(ChatMessageUserState.class))).thenAnswer(inv -> {
//            ChatMessageUserState s = inv.getArgument(0);
//            if (s.getId() == null) {
//                s.setId(stateSeq.incrementAndGet());
//            }
//            messageUserStateStore.put(s.getMessageId() + ":" + s.getUserId(), s);
//            return s;
//        });
//
//        lenient().when(messageUserStateRepository.saveAll(anyIterable())).thenAnswer(inv -> {
//            Iterable<ChatMessageUserState> list = inv.getArgument(0);
//            List<ChatMessageUserState> saved = new ArrayList<>();
//            for (ChatMessageUserState s : list) {
//                if (s.getId() == null) {
//                    s.setId(stateSeq.incrementAndGet());
//                }
//                messageUserStateStore.put(s.getMessageId() + ":" + s.getUserId(), s);
//                saved.add(s);
//            }
//            return saved;
//        });
//
//        lenient().when(messageUserStateRepository.findByMessageIdAndUserId(anyLong(), anyLong())).thenAnswer(inv -> {
//            Long mid = inv.getArgument(0);
//            Long uid = inv.getArgument(1);
//            return Optional.ofNullable(messageUserStateStore.get(mid + ":" + uid));
//        });
//
//        lenient().when(messageUserStateRepository.findByConversationIdAndUserId(anyLong(), anyLong())).thenAnswer(inv -> {
//            Long cid = inv.getArgument(0);
//            Long uid = inv.getArgument(1);
//            return messageUserStateStore.values().stream()
//                    .filter(s -> Objects.equals(s.getConversationId(), cid) && Objects.equals(s.getUserId(), uid))
//                    .toList();
//        });
//
//        lenient().when(messageUserStateRepository.findByUserIdAndConversationIdIn(anyLong(), anyCollection())).thenAnswer(inv -> {
//            Long uid = inv.getArgument(0);
//            Collection<Long> cids = inv.getArgument(1);
//            return messageUserStateStore.values().stream()
//                    .filter(s -> Objects.equals(s.getUserId(), uid) && cids.contains(s.getConversationId()))
//                    .toList();
//        });
//
//        // In-memory store wiring for ChatConversationUserStateRepository
//        lenient().when(conversationUserStateRepository.save(any(ChatConversationUserState.class))).thenAnswer(inv -> {
//            ChatConversationUserState s = inv.getArgument(0);
//            if (s.getId() == null) {
//                s.setId(stateSeq.incrementAndGet());
//            }
//            conversationUserStateStore.put(s.getConversationId() + ":" + s.getUserId(), s);
//            return s;
//        });
//
//        lenient().when(conversationUserStateRepository.findByConversationIdAndUserId(anyLong(), anyLong())).thenAnswer(inv -> {
//            Long cid = inv.getArgument(0);
//            Long uid = inv.getArgument(1);
//            return Optional.ofNullable(conversationUserStateStore.get(cid + ":" + uid));
//        });
//
//        lenient().when(conversationUserStateRepository.findByUserIdAndConversationIdIn(anyLong(), anyCollection())).thenAnswer(inv -> {
//            Long uid = inv.getArgument(0);
//            Collection<Long> cids = inv.getArgument(1);
//            return conversationUserStateStore.values().stream()
//                    .filter(s -> Objects.equals(s.getUserId(), uid) && cids.contains(s.getConversationId()))
//                    .toList();
//        });
//    }
//
//    private User buildUser(Long userId, String username, String roleName, Branch branch) {
//        User u = new User();
//        u.setUserId(userId);
//        u.setUsername(username);
//        u.setPhoneNumber("0900000" + userId);
//        u.setIsActive(true);
//        u.setStatus("ACTIVE");
//        u.setBranch(branch);
//
//        Role r = new Role();
//        r.setRoleId(userId);
//        r.setRoleName(roleName);
//        r.setIsActive(true);
//        u.setRoles(new HashSet<>(Set.of(r)));
//
//        lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(u));
//        lenient().when(conversationRepository.findActiveRoleNamesByUserId(userId)).thenReturn(List.of(roleName));
//        return u;
//    }
//
//    private void mockStaffProfile(Long userId, Long staffId, String fullName, String dept, String pos) {
//        StaffProfile sp = new StaffProfile();
//        sp.setStaffId(staffId);
//        sp.setFullName(fullName);
//        sp.setDepartment(dept);
//        sp.setPositionTitle(pos);
//        sp.setStatus(StaffStatus.ACTIVE);
//        lenient().when(staffProfileRepository.findByUser_UserId(userId)).thenReturn(Optional.of(sp));
//    }
//
//    @Test
//    @DisplayName("1. Bệnh nhân gửi tin đầu tiên -> Chỉ Lễ tân, Bác sĩ cùng cơ sở và Admin nhận & phản hồi")
//    void testPatientFirstMessageAndMultipleStaffRepliesInSharedBranchConversation() {
//        ChatMessageDTO firstMsg = chatService.sendMessage(
//                patientBranch1.getUserId(),
//                ChatSendRequestDTO.builder()
//                        .messageText("Chào phòng khám, tôi muốn hỏi lịch khám răng sứ tại Quận 1.")
//                        .clientMessageId("c_001")
//                        .build()
//        );
//
//        assertNotNull(firstMsg.getConversationId());
//        assertEquals(1L, firstMsg.getBranchId());
//        assertEquals(patientBranch1.getUserId(), firstMsg.getSenderId());
//        assertEquals("Nguyễn Văn Hùng", firstMsg.getSenderFullName());
//        assertEquals("BENH_NHAN", firstMsg.getSenderRoleCode());
//        assertEquals("Bệnh nhân", firstMsg.getSenderRoleLabel());
//        assertEquals(SenderType.PATIENT.name(), firstMsg.getSenderType());
//        assertTrue(firstMsg.isFirstMessageInConversation());
//        assertEquals(ChatService.FIRST_MESSAGE_SYSTEM_NOTICE, firstMsg.getSystemNotice());
//
//        ChatConversation conv = conversationStore.get(firstMsg.getConversationId());
//        assertEquals(ChatStatus.STAFF_HANDLING, conv.getStatus());
//
//        // Lễ tân A tại Cơ sở 1 phản hồi
//        ChatMessageDTO replyRecepA = chatService.sendMessage(
//                recep1Branch1.getUserId(),
//                ChatSendRequestDTO.builder()
//                        .conversationId(firstMsg.getConversationId())
//                        .messageText("Chào anh Hùng, Lễ tân A tại Quận 1 xin hỗ trợ anh ạ.")
//                        .build()
//        );
//        assertEquals(firstMsg.getConversationId(), replyRecepA.getConversationId());
//        assertEquals("Lê Thị Lễ Tân A", replyRecepA.getSenderFullName());
//        assertEquals("LE_TAN", replyRecepA.getSenderRoleCode());
//
//        // Lễ tân B tại Cơ sở 1 phản hồi
//        ChatMessageDTO replyRecepB = chatService.sendMessage(
//                recep2Branch1.getUserId(),
//                ChatSendRequestDTO.builder()
//                        .conversationId(firstMsg.getConversationId())
//                        .messageText("Em là Lễ tân B, gửi anh bảng giá tham khảo ạ.")
//                        .build()
//        );
//        assertEquals(firstMsg.getConversationId(), replyRecepB.getConversationId());
//        assertEquals("Trần Văn Lễ Tân B", replyRecepB.getSenderFullName());
//
//        // Bác sĩ tại Cơ sở 1 phản hồi
//        ChatMessageDTO replyDoctor = chatService.sendMessage(
//                doctorBranch1.getUserId(),
//                ChatSendRequestDTO.builder()
//                        .conversationId(firstMsg.getConversationId())
//                        .messageText("Bác sĩ tư vấn thêm: anh nên chụp phim X-quang trước khi bọc sứ nhé.")
//                        .build()
//        );
//        assertEquals(firstMsg.getConversationId(), replyDoctor.getConversationId());
//        assertEquals("Dr. Nguyễn Văn Bác Sĩ", replyDoctor.getSenderFullName());
//        assertEquals("BAC_SI", replyDoctor.getSenderRoleCode());
//
//        // Quản lý kho (cùng Cơ sở 1) KHÔNG được phép xem hay phản hồi hội thoại của Bệnh nhân -> 403 FORBIDDEN
//        assertThrows(ResponseStatusException.class, () ->
//                chatService.getConversationMessages(warehouseBranch1.getUserId(), firstMsg.getConversationId())
//        );
//        assertThrows(ResponseStatusException.class, () ->
//                chatService.sendMessage(
//                        warehouseBranch1.getUserId(),
//                        ChatSendRequestDTO.builder()
//                                .conversationId(firstMsg.getConversationId())
//                                .messageText("Quản lý kho không được trả lời bệnh nhân")
//                                .build()
//                )
//        );
//        // Quản lý kho mở danh sách hộp thư cũng không thấy hội thoại của Bệnh nhân
//        Page<ChatConversationDTO> warehouseInbox = chatService.searchInboxConversations(
//                warehouseBranch1.getUserId(), null, "ALL", 1L, false, PageRequest.of(0, 20)
//        );
//        assertEquals(0, warehouseInbox.getTotalElements());
//
//        assertEquals(1, conversationStore.size());
//        assertEquals(4, messageStore.size());
//    }
//
//    @Test
//    @DisplayName("2. Cô lập theo cơ sở & Quyền nhận kênh STOMP: Chỉ Lễ tân, Bác sĩ cùng cơ sở và Admin nhận kênh cơ sở")
//    void testBranchIsolationAndStompSubscriptionSecurity() {
//        ChatMessageDTO patientMsg = chatService.sendMessage(
//                patientBranch1.getUserId(),
//                ChatSendRequestDTO.builder().messageText("Tôi cần hỗ trợ tại Quận 1").build()
//        );
//        Long convIdBranch1 = patientMsg.getConversationId();
//
//        // Nhân viên Cơ sở 2 bị chặn xem/gửi/lọc tin nhắn của Cơ sở 1 -> 403 FORBIDDEN
//        assertThrows(ResponseStatusException.class, () ->
//                chatService.getConversationMessages(recepBranch2.getUserId(), convIdBranch1)
//        );
//        assertThrows(ResponseStatusException.class, () ->
//                chatService.sendMessage(
//                        recepBranch2.getUserId(),
//                        ChatSendRequestDTO.builder()
//                                .conversationId(convIdBranch1)
//                                .messageText("Trả lời trái phép từ CN2")
//                                .build()
//                )
//        );
//        assertThrows(ResponseStatusException.class, () ->
//                chatService.searchInboxConversations(recepBranch2.getUserId(), null, "ALL", 1L, false, PageRequest.of(0, 20))
//        );
//
//        // Kiểm tra bảo mật kênh STOMP SUBSCRIBE:
//        // - Lễ tân & Bác sĩ Cơ sở 1 được phép đăng ký /topic/chat/branch/1
//        assertDoesNotThrow(() -> stompInterceptor.validateSubscription(recep1Branch1.getUserId(), "/topic/chat/branch/1"));
//        assertDoesNotThrow(() -> stompInterceptor.validateSubscription(doctorBranch1.getUserId(), "/topic/chat/branch/1"));
//
//        // - Quản lý kho (dù cùng Cơ sở 1) KHÔNG được đăng ký kênh tin nhắn Bệnh nhân /topic/chat/branch/1
//        assertThrows(AccessDeniedException.class, () ->
//                stompInterceptor.validateSubscription(warehouseBranch1.getUserId(), "/topic/chat/branch/1")
//        );
//
//        // - Lễ tân Cơ sở 2 và Bệnh nhân bị chặn đăng ký /topic/chat/branch/1
//        assertThrows(AccessDeniedException.class, () ->
//                stompInterceptor.validateSubscription(recepBranch2.getUserId(), "/topic/chat/branch/1")
//        );
//        assertThrows(AccessDeniedException.class, () ->
//                stompInterceptor.validateSubscription(patientBranch1.getUserId(), "/topic/chat/branch/1")
//        );
//
//        // - Chỉ Admin được đăng ký /topic/chat/admin và mọi cơ sở
//        assertThrows(AccessDeniedException.class, () ->
//                stompInterceptor.validateSubscription(recep1Branch1.getUserId(), "/topic/chat/admin")
//        );
//        assertDoesNotThrow(() -> stompInterceptor.validateSubscription(adminUser.getUserId(), "/topic/chat/admin"));
//        assertDoesNotThrow(() -> stompInterceptor.validateSubscription(adminUser.getUserId(), "/topic/chat/branch/1"));
//        assertDoesNotThrow(() -> stompInterceptor.validateSubscription(adminUser.getUserId(), "/topic/chat/branch/2"));
//
//        // Admin có thể xem và trả lời hội thoại của Cơ sở 1
//        ChatMessageDTO adminReply = chatService.sendMessage(
//                adminUser.getUserId(),
//                ChatSendRequestDTO.builder()
//                        .conversationId(convIdBranch1)
//                        .messageText("Admin hỗ trợ xác nhận lịch hẹn tại Quận 1.")
//                        .build()
//        );
//        assertEquals("ADMIN", adminReply.getSenderRoleCode());
//    }
//
//    @Test
//    @DisplayName("3. Tìm kiếm từ khóa + Lọc theo Vai trò người khởi tạo (Initiator Role) + Lọc Cơ sở kết hợp AND")
//    void testSearchAndInitiatorRoleFilterWithAndLogic() {
//        ChatMessageDTO m1 = chatService.sendMessage(
//                patientBranch1.getUserId(),
//                ChatSendRequestDTO.builder().messageText("Tôi bị ê buốt răng hàm dưới").build()
//        );
//        chatService.sendMessage(
//                recep1Branch1.getUserId(),
//                ChatSendRequestDTO.builder()
//                        .conversationId(m1.getConversationId())
//                        .messageText("Mời anh Hùng qua cơ sở Quận 1 khám ngay nhé")
//                        .build()
//        );
//
//        chatService.sendMessage(
//                doctorBranch1.getUserId(),
//                ChatSendRequestDTO.builder().messageText("Lễ tân chuẩn bị hồ sơ ca cấy ghép Implant chiều nay nhé").build()
//        );
//
//        Page<ChatConversationDTO> patientConvs = chatService.searchInboxConversations(
//                adminUser.getUserId(), null, "BENH_NHAN", 1L, false, PageRequest.of(0, 20)
//        );
//        assertEquals(1, patientConvs.getTotalElements());
//        assertEquals("BENH_NHAN", patientConvs.getContent().get(0).getInitiatorRoleCode());
//
//        Page<ChatConversationDTO> doctorConvs = chatService.searchInboxConversations(
//                recep1Branch1.getUserId(), "implant", "BAC_SI", 1L, false, PageRequest.of(0, 20)
//        );
//        assertEquals(1, doctorConvs.getTotalElements());
//        assertEquals("BAC_SI", doctorConvs.getContent().get(0).getInitiatorRoleCode());
//
//        Page<ChatConversationDTO> noMatch = chatService.searchInboxConversations(
//                recep1Branch1.getUserId(), "ê buốt", "BAC_SI", 1L, false, PageRequest.of(0, 20)
//        );
//        assertEquals(0, noMatch.getTotalElements());
//    }
//
//    @Test
//    @DisplayName("4. Tìm kiếm tài khoản cùng cơ sở & Hỗ trợ tài khoản Bệnh nhân có nhiều hồ sơ (Multi-Profile)")
//    void testSearchRecipientsInSameBranchAndDirectMessaging() {
//        // 1. Bệnh nhân gọi tìm kiếm tài khoản hoặc tạo hội thoại với người nhận cụ thể -> bị chặn 403 FORBIDDEN
//        assertThrows(ResponseStatusException.class, () ->
//                chatService.searchRecipientUsers(patientBranch1.getUserId(), null, "ALL", null)
//        );
//        assertThrows(ResponseStatusException.class, () ->
//                chatService.startOrGetConversationWithRecipient(patientBranch1.getUserId(), doctorBranch1.getUserId(), 1L)
//        );
//
//        // 2. Bác sĩ tại Cơ sở 1 tìm kiếm tài khoản trong cùng Cơ sở 1:
//        List<ChatUserSearchItemDTO> doctorSearch = chatService.searchRecipientUsers(
//                doctorBranch1.getUserId(), null, "ALL", null
//        );
//        Set<Long> doctorFoundIds = new HashSet<>();
//        for (ChatUserSearchItemDTO item : doctorSearch) {
//            doctorFoundIds.add(item.getUserId());
//        }
//        assertTrue(doctorFoundIds.contains(patientBranch1.getUserId()));
//        assertTrue(doctorFoundIds.contains(recep1Branch1.getUserId()));
//        assertTrue(doctorFoundIds.contains(warehouseBranch1.getUserId()));
//        assertFalse(doctorFoundIds.contains(recepBranch2.getUserId()));
//
//        // Tìm kiếm Bệnh nhân theo tên hồ sơ phụ ("Minh Khôi") vẫn tìm đúng 1 tài khoản duy nhất của bệnh nhân đó
//        List<ChatUserSearchItemDTO> multiProfileSearch = chatService.searchRecipientUsers(
//                recep1Branch1.getUserId(), "Minh Khôi", "BENH_NHAN", 1L
//        );
//        assertEquals(1, multiProfileSearch.size());
//        assertEquals(patientBranch1.getUserId(), multiProfileSearch.get(0).getUserId());
//        assertTrue(multiProfileSearch.get(0).getFullName().contains("Hồ sơ liên kết"));
//
//        // 3. Quản lý kho tại Cơ sở 1 tìm kiếm tài khoản trong cùng Cơ sở 1:
//        List<ChatUserSearchItemDTO> warehouseSearch = chatService.searchRecipientUsers(
//                warehouseBranch1.getUserId(), null, "ALL", null
//        );
//        Set<Long> warehouseFoundIds = new HashSet<>();
//        for (ChatUserSearchItemDTO item : warehouseSearch) {
//            warehouseFoundIds.add(item.getUserId());
//        }
//        assertTrue(warehouseFoundIds.contains(recep1Branch1.getUserId()));
//        assertTrue(warehouseFoundIds.contains(doctorBranch1.getUserId()));
//        assertFalse(warehouseFoundIds.contains(patientBranch1.getUserId())); // Không thấy Bệnh nhân
//        assertFalse(warehouseFoundIds.contains(recepBranch2.getUserId()));   // Không thấy nhân viên Cơ sở 2
//
//        // Nếu Quản lý kho cố tình tạo hội thoại với Bệnh nhân -> bị chặn 403 FORBIDDEN
//        assertThrows(ResponseStatusException.class, () ->
//                chatService.startOrGetConversationWithRecipient(warehouseBranch1.getUserId(), patientBranch1.getUserId(), 1L)
//        );
//
//        // 4. Bác sĩ tại Cơ sở 1 chọn Quản lý kho cùng Cơ sở 1 để gửi tin nhắn trực tiếp
//        Map<String, Object> directConvInfo = chatService.startOrGetConversationWithRecipient(
//                doctorBranch1.getUserId(), warehouseBranch1.getUserId(), 1L
//        );
//        Long directConvId = ((Number) directConvInfo.get("conversationId")).longValue();
//        assertTrue((Boolean) directConvInfo.get("directConversation"));
//
//        chatService.sendMessage(
//                doctorBranch1.getUserId(),
//                ChatSendRequestDTO.builder()
//                        .conversationId(directConvId)
//                        .messageText("Anh Kho chuẩn bị giúp tôi 5 trụ Implant Hàn Quốc cho ca chiều nay nhé.")
//                        .build()
//        );
//
//        // Quản lý kho xem danh sách hộp thư sẽ thấy hội thoại trực tiếp với Bác sĩ cùng Cơ sở 1
//        Page<ChatConversationDTO> warehouseInbox = chatService.searchInboxConversations(
//                warehouseBranch1.getUserId(), null, "BAC_SI", 1L, false, PageRequest.of(0, 20)
//        );
//        assertEquals(1, warehouseInbox.getTotalElements());
//        assertEquals("Dr. Nguyễn Văn Bác Sĩ", warehouseInbox.getContent().get(0).getCounterpartFullName());
//    }
//
//    @Test
//    @DisplayName("5. Quản lý trạng thái Đã đọc / Chưa đọc độc lập theo từng tài khoản và Badge tổng tin chưa đọc")
//    void testPerAccountUnreadBadgeAndIndependentMarkAsRead() {
//        ChatMessageDTO m1 = chatService.sendMessage(
//                patientBranch1.getUserId(),
//                ChatSendRequestDTO.builder().messageText("Tin nhắn 1 từ bệnh nhân").build()
//        );
//        chatService.sendMessage(
//                patientBranch1.getUserId(),
//                ChatSendRequestDTO.builder()
//                        .conversationId(m1.getConversationId())
//                        .messageText("Tin nhắn 2 từ bệnh nhân")
//                        .build()
//        );
//
//        // Cả Lễ tân A, Lễ tân B và Bác sĩ cùng Cơ sở 1 đều thấy 2 tin nhắn chưa đọc ở Badge Sidebar
//        assertEquals(2L, chatService.getTotalUnreadCount(recep1Branch1.getUserId()));
//        assertEquals(2L, chatService.getTotalUnreadCount(recep2Branch1.getUserId()));
//        assertEquals(2L, chatService.getTotalUnreadCount(doctorBranch1.getUserId()));
//
//        // Lễ tân A mở cuộc trò chuyện và đánh dấu đã đọc
//        Map<String, Object> readResultA = chatService.markConversationAsRead(recep1Branch1.getUserId(), m1.getConversationId());
//        assertEquals(0L, ((Number) readResultA.get("totalUnreadCount")).longValue());
//        assertEquals(0L, chatService.getTotalUnreadCount(recep1Branch1.getUserId()));
//
//        // Lễ tân B và Bác sĩ vẫn chưa đọc -> số tin chưa đọc của họ vẫn giữ nguyên là 2!
//        assertEquals(2L, chatService.getTotalUnreadCount(recep2Branch1.getUserId()));
//        assertEquals(2L, chatService.getTotalUnreadCount(doctorBranch1.getUserId()));
//    }
//
//    @Test
//    @DisplayName("6. Chỉnh sửa và Thu hồi tin nhắn trong vòng 10 phút; chặn quá 10 phút hoặc không phải người gửi")
//    void testEditAndRecallMessageWithin10MinuteWindow() {
//        ChatMessageDTO sent = chatService.sendMessage(
//                patientBranch1.getUserId(),
//                ChatSendRequestDTO.builder().messageText("Cho tôi đặt lịch khám lúc 9h sáng mai").build()
//        );
//        assertTrue(sent.isCanEditOrRecall());
//
//        // Người khác không được sửa hoặc thu hồi tin nhắn của bệnh nhân -> 403 FORBIDDEN
//        assertThrows(ResponseStatusException.class, () ->
//                chatService.editMessage(recep1Branch1.getUserId(), sent.getMessageId(), "Sửa trái phép")
//        );
//        assertThrows(ResponseStatusException.class, () ->
//                chatService.recallMessage(recep1Branch1.getUserId(), sent.getMessageId())
//        );
//
//        // Chính người gửi sửa tin nhắn trong vòng 10 phút -> Thành công
//        ChatMessageDTO edited = chatService.editMessage(
//                patientBranch1.getUserId(),
//                sent.getMessageId(),
//                "Cho tôi đổi lịch khám sang 14h chiều mai nhé"
//        );
//        assertEquals("Cho tôi đổi lịch khám sang 14h chiều mai nhé", edited.getMessageText());
//        assertTrue(edited.isEdited());
//        assertEquals("EDIT", edited.getEventType());
//
//        // Chính người gửi thu hồi tin nhắn trong vòng 10 phút -> Thành công
//        ChatMessageDTO recalled = chatService.recallMessage(patientBranch1.getUserId(), sent.getMessageId());
//        assertTrue(recalled.isRecalled());
//        assertEquals(ChatService.RECALLED_MESSAGE_PLACEHOLDER, recalled.getMessageText());
//        assertEquals("RECALL", recalled.getEventType());
//
//        // Tin nhắn đã quá 10 phút không được phép sửa hoặc thu hồi
//        ChatMessageDTO oldMsgDto = chatService.sendMessage(
//                recep1Branch1.getUserId(),
//                ChatSendRequestDTO.builder()
//                        .conversationId(sent.getConversationId())
//                        .messageText("Tin nhắn đã gửi từ 15 phút trước")
//                        .build()
//        );
//        ChatMessage rawOldMsg = messageStore.stream()
//                .filter(m -> Objects.equals(m.getMessageId(), oldMsgDto.getMessageId()))
//                .findFirst()
//                .orElseThrow();
//        rawOldMsg.setSentAt(LocalDateTime.now().minusMinutes(15));
//
//        assertThrows(ResponseStatusException.class, () ->
//                chatService.editMessage(recep1Branch1.getUserId(), oldMsgDto.getMessageId(), "Sửa sau 15 phút")
//        );
//        assertThrows(ResponseStatusException.class, () ->
//                chatService.recallMessage(recep1Branch1.getUserId(), oldMsgDto.getMessageId())
//        );
//    }
//
//    @Test
//    @DisplayName("7. Xóa tin nhắn và Xóa cuộc trò chuyện ở phía tôi (Self-delete) không làm mất dữ liệu của người khác")
//    void testSelfDeleteMessageAndConversationIsolation() {
//        ChatMessageDTO m1 = chatService.sendMessage(
//                patientBranch1.getUserId(),
//                ChatSendRequestDTO.builder().messageText("Tin nhắn thứ nhất").build()
//        );
//        ChatMessageDTO m2 = chatService.sendMessage(
//                recep1Branch1.getUserId(),
//                ChatSendRequestDTO.builder()
//                        .conversationId(m1.getConversationId())
//                        .messageText("Tin nhắn phản hồi thứ hai")
//                        .build()
//        );
//
//        // Lễ tân A xóa tin nhắn m1 ở phía mình
//        chatService.deleteMessageForUser(recep1Branch1.getUserId(), m1.getMessageId());
//
//        // Lễ tân A chỉ còn thấy 1 tin nhắn (m2), trong khi Bệnh nhân và Lễ tân B vẫn thấy đủ 2 tin nhắn
//        @SuppressWarnings("unchecked")
//        List<ChatMessageDTO> recepAMessages = (List<ChatMessageDTO>) chatService
//                .getConversationMessages(recep1Branch1.getUserId(), m1.getConversationId())
//                .get("messages");
//        assertEquals(1, recepAMessages.size());
//        assertEquals(m2.getMessageId(), recepAMessages.get(0).getMessageId());
//
//        @SuppressWarnings("unchecked")
//        List<ChatMessageDTO> patientMessages = (List<ChatMessageDTO>) chatService
//                .getConversationMessages(patientBranch1.getUserId(), m1.getConversationId())
//                .get("messages");
//        assertEquals(2, patientMessages.size());
//
//        // Lễ tân A xóa toàn bộ cuộc trò chuyện ở phía mình -> Ẩn khỏi hộp thư của Lễ tân A nhưng Lễ tân B vẫn thấy bình thường
//        chatService.deleteConversationForUser(recep1Branch1.getUserId(), m1.getConversationId());
//        Page<ChatConversationDTO> recepAInbox = chatService.searchInboxConversations(
//                recep1Branch1.getUserId(), null, "ALL", 1L, false, PageRequest.of(0, 20)
//        );
//        assertEquals(0, recepAInbox.getTotalElements());
//
//        Page<ChatConversationDTO> recepBInbox = chatService.searchInboxConversations(
//                recep2Branch1.getUserId(), null, "ALL", 1L, false, PageRequest.of(0, 20)
//        );
//        assertEquals(1, recepBInbox.getTotalElements());
//    }
//}
