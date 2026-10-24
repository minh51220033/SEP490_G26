package com.cvdcms.controller;

import com.cvdcms.DTO.ChatMessageDTO;
import com.cvdcms.DTO.ChatSendRequestDTO;
import com.cvdcms.config.ChatStompPrincipal;
import com.cvdcms.config.ChatWebSocketHandshakeInterceptor;
import com.cvdcms.service.ChatService;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Controller xử lý tin nhắn Realtime qua STOMP WebSocket (@MessageMapping("/chat.send")).
 * Quy tắc:
 * 1. Luôn xác thực danh tính người gửi từ Principal / Session của kết nối WebSocket (tuyệt đối không tin payload).
 * 2. Lưu tin nhắn vào MySQL (thông qua ChatService.sendMessage) TRƯỚC KHI phát sự kiện STOMP.
 * 3. Phát tin nhắn tới:
 *    - Hàng đợi cá nhân của người khởi tạo, người nhận trực tiếp (nếu có) và người gửi (/user/{username}/queue/chat/messages).
 *    - Nếu là hội thoại chung của cơ sở (directConversation == false): phát tới kênh /topic/chat/branch/{branchId}
 *      (chỉ Lễ tân, Bác sĩ thuộc cơ sở đó và Admin mới được phép đăng ký kênh này).
 *    - Kênh tổng hợp của Admin (/topic/chat/admin).
 */
@Controller
public class ChatWebSocketController {

    private final ChatService chatService;
    private final SimpMessagingTemplate messagingTemplate;

    public ChatWebSocketController(
            ChatService chatService,
            SimpMessagingTemplate messagingTemplate) {
        this.chatService = chatService;
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/chat.send")
    public void handleSendMessage(
            @Payload ChatSendRequestDTO request,
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor) {

        Long currentUserId = extractAuthenticatedUserId(principal, headerAccessor);
        if (currentUserId == null) {
            throw new AccessDeniedException("Phiên kết nối WebSocket chưa được xác thực.");
        }

        // 1. Lưu bền vững vào MySQL trước
        ChatMessageDTO savedMessage = chatService.sendMessage(currentUserId, request);

        // 2. Phát sự kiện STOMP realtime tới người khởi tạo, người nhận trực tiếp, cơ sở tương ứng và Admin
        broadcastSavedMessage(savedMessage);
    }

    /**
     * Phát tin nhắn đã lưu trong MySQL tới đúng các kênh STOMP theo phạm vi cơ sở.
     */
    public void broadcastSavedMessage(ChatMessageDTO savedMessage) {
        if (savedMessage == null) {
            return;
        }

        // 1. Gửi về hàng đợi riêng (/user/{username}/queue/chat/messages) của người khởi tạo, người nhận trực tiếp và người gửi
        Set<String> targetUsernames = new HashSet<>();
        if (savedMessage.getInitiatorUsername() != null && !savedMessage.getInitiatorUsername().isBlank()) {
            targetUsernames.add(savedMessage.getInitiatorUsername().trim());
        }
        if (savedMessage.getRecipientUsername() != null && !savedMessage.getRecipientUsername().isBlank()) {
            targetUsernames.add(savedMessage.getRecipientUsername().trim());
        }
        if (savedMessage.getSenderUsername() != null && !savedMessage.getSenderUsername().isBlank()) {
            targetUsernames.add(savedMessage.getSenderUsername().trim());
        }

        for (String uname : targetUsernames) {
            messagingTemplate.convertAndSendToUser(
                    uname,
                    "/queue/chat/messages",
                    savedMessage
            );
        }

        // 2. Nếu là hội thoại chung của cơ sở (Bệnh nhân nhắn lên cơ sở, không phải chat 1-1 nội bộ):
        // Phát tới kênh /topic/chat/branch/{branchId} dành cho Lễ tân & Bác sĩ của cơ sở đó
        if (!savedMessage.isDirectConversation() && savedMessage.getBranchId() != null) {
            messagingTemplate.convertAndSend(
                    "/topic/chat/branch/" + savedMessage.getBranchId(),
                    savedMessage
            );
        }

        // 3. Phát tới kênh quản trị toàn hệ thống của Admin (/topic/chat/admin)
        messagingTemplate.convertAndSend("/topic/chat/admin", savedMessage);
    }

    private Long extractAuthenticatedUserId(Principal principal, SimpMessageHeaderAccessor headerAccessor) {
        if (principal instanceof ChatStompPrincipal chatPrincipal && chatPrincipal.getUserId() != null) {
            return chatPrincipal.getUserId();
        }
        if (headerAccessor != null) {
            Principal accessorUser = headerAccessor.getUser();
            if (accessorUser instanceof ChatStompPrincipal chatPrincipal && chatPrincipal.getUserId() != null) {
                return chatPrincipal.getUserId();
            }
            Map<String, Object> attrs = headerAccessor.getSessionAttributes();
            if (attrs != null) {
                Object uidObj = attrs.get(ChatWebSocketHandshakeInterceptor.ATTR_USER_ID);
                if (uidObj instanceof Number num) {
                    return num.longValue();
                }
            }
        }
        return null;
    }
}
