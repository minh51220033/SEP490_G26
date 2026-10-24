package com.cvdcms.controller;

import com.cvdcms.DTO.ChatConversationDTO;
import com.cvdcms.DTO.ChatMessageDTO;
import com.cvdcms.DTO.ChatSendRequestDTO;
import com.cvdcms.DTO.ChatUserSearchItemDTO;
import com.cvdcms.entity.User;
import com.cvdcms.service.ChatService;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API Controller phục vụ chức năng Realtime Chat theo Cơ sở:
 * - Cung cấp ngữ cảnh người dùng & lịch sử hội thoại cho Floating Widget (/api/chat/context, /api/chat/my-conversation).
 * - Tìm kiếm tất cả các tài khoản trong cùng cơ sở để gửi tin nhắn cho các vai trò ngoài Bệnh nhân (/api/chat/recipients).
 * - Khởi tạo hoặc mở cuộc hội thoại với người nhận đã chọn (/api/chat/conversations/start).
 * - Tìm kiếm, lọc theo vai trò, lọc theo cơ sở và phân trang cho Hộp thư (/api/chat/conversations).
 * - Lấy chi tiết lịch sử tin nhắn (/api/chat/conversations/{id}/messages).
 * - Gửi tin nhắn qua HTTP POST và phát đồng bộ qua STOMP (/api/chat/messages).
 * - Đánh dấu hội thoại đã đọc (/api/chat/conversations/{id}/read).
 */
@RestController
@RequestMapping("/api/chat")
public class ChatRestController {

    private final ChatService chatService;
    private final ChatWebSocketController chatWebSocketController;

    public ChatRestController(
            ChatService chatService,
            ChatWebSocketController chatWebSocketController) {
        this.chatService = chatService;
        this.chatWebSocketController = chatWebSocketController;
    }

    private Long requireSessionUserId(HttpSession session) {
        Object obj = session != null ? session.getAttribute("loggedInUser") : null;
        if (obj instanceof User user && user.getUserId() != null) {
            return user.getUserId();
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập để sử dụng chức năng chat.");
    }

    /**
     * Kiểm tra trạng thái đăng nhập và thông tin cơ sở của người dùng cho Floating Chat Widget.
     */
    @GetMapping("/context")
    public ResponseEntity<Map<String, Object>> getChatContext(HttpSession session) {
        Object obj = session != null ? session.getAttribute("loggedInUser") : null;
        if (!(obj instanceof User user) || user.getUserId() == null) {
            Map<String, Object> unauth = new LinkedHashMap<>();
            unauth.put("authenticated", false);
            return ResponseEntity.ok(unauth);
        }
        try {
            return ResponseEntity.ok(chatService.getUserChatContext(user.getUserId()));
        } catch (Exception ex) {
            Map<String, Object> unauth = new LinkedHashMap<>();
            unauth.put("authenticated", false);
            return ResponseEntity.ok(unauth);
        }
    }

    /**
     * Lấy cuộc hội thoại hiện tại (kèm lịch sử tin nhắn) của người dùng đang đăng nhập trên Floating Widget.
     */
    @GetMapping("/my-conversation")
    public ResponseEntity<Map<String, Object>> getMyWidgetConversation(HttpSession session) {
        Long userId = requireSessionUserId(session);
        return ResponseEntity.ok(chatService.getOrInitMyWidgetConversation(userId));
    }

    /**
     * Tìm kiếm tất cả các tài khoản trong cùng cơ sở để gửi tin nhắn.
     * Chỉ cho phép các vai trò ngoài Bệnh nhân (LE_TAN, BAC_SI, QUAN_LY_KHO, KE_TOAN, ADMIN).
     * Bệnh nhân gọi API này sẽ bị từ chối với lỗi 403 FORBIDDEN.
     */
    @GetMapping("/recipients")
    public ResponseEntity<List<ChatUserSearchItemDTO>> searchRecipientUsers(
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "role", required = false) String role,
            @RequestParam(name = "branchId", required = false) Long branchId,
            HttpSession session) {

        Long userId = requireSessionUserId(session);
        return ResponseEntity.ok(chatService.searchRecipientUsers(userId, keyword, role, branchId));
    }

    /**
     * Mở hoặc tạo mới cuộc hội thoại với một tài khoản người nhận cụ thể trong cùng cơ sở.
     * Chỉ dành cho các vai trò ngoài Bệnh nhân (LE_TAN, BAC_SI, QUAN_LY_KHO, KE_TOAN, ADMIN).
     */
    @PostMapping("/conversations/start")
    public ResponseEntity<Map<String, Object>> startConversationWithRecipient(
            @RequestBody Map<String, Object> body,
            HttpSession session) {

        Long userId = requireSessionUserId(session);
        Long targetUserId = null;
        Long branchId = null;

        if (body != null) {
            Object targetObj = body.get("targetUserId");
            if (targetObj instanceof Number num) {
                targetUserId = num.longValue();
            } else if (targetObj instanceof String str && !str.isBlank()) {
                targetUserId = Long.parseLong(str.trim());
            }

            Object branchObj = body.get("branchId");
            if (branchObj instanceof Number num) {
                branchId = num.longValue();
            } else if (branchObj instanceof String str && !str.isBlank()) {
                branchId = Long.parseLong(str.trim());
            }
        }

        return ResponseEntity.ok(chatService.startOrGetConversationWithRecipient(userId, targetUserId, branchId));
    }

    /**
     * Tìm kiếm, lọc và phân trang danh sách cuộc hội thoại cho Hộp thư nhân viên / Admin.
     */
    @GetMapping("/conversations")
    public ResponseEntity<Page<ChatConversationDTO>> searchConversations(
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "initiatorRole", required = false) String initiatorRole,
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "unreadOnly", required = false) Boolean unreadOnly,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "30") int size,
            HttpSession session) {

        Long userId = requireSessionUserId(session);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize);

        Page<ChatConversationDTO> result = chatService.searchInboxConversations(
                userId, keyword, initiatorRole, branchId, unreadOnly, pageable
        );
        return ResponseEntity.ok(result);
    }

    /**
     * Xem lịch sử tin nhắn của một cuộc hội thoại.
     */
    @GetMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<Map<String, Object>> getConversationMessages(
            @PathVariable("conversationId") Long conversationId,
            HttpSession session) {

        Long userId = requireSessionUserId(session);
        return ResponseEntity.ok(chatService.getConversationMessages(userId, conversationId));
    }

    /**
     * Gửi tin nhắn mới (lưu MySQL và phát sự kiện STOMP tới các bên liên quan).
     */
    @PostMapping("/messages")
    public ResponseEntity<ChatMessageDTO> sendMessage(
            @RequestBody ChatSendRequestDTO request,
            HttpSession session) {

        Long userId = requireSessionUserId(session);
        ChatMessageDTO savedMessage = chatService.sendMessage(userId, request);
        chatWebSocketController.broadcastSavedMessage(savedMessage);
        return ResponseEntity.ok(savedMessage);
    }

    /**
     * Đánh dấu cuộc hội thoại là đã đọc.
     */
    @PostMapping("/conversations/{conversationId}/read")
    public ResponseEntity<Map<String, Object>> markConversationAsRead(
            @PathVariable("conversationId") Long conversationId,
            HttpSession session) {

        Long userId = requireSessionUserId(session);
        return ResponseEntity.ok(chatService.markConversationAsRead(userId, conversationId));
    }
}
