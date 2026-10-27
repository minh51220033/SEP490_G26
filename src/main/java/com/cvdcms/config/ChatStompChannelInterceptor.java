package com.cvdcms.config;

import com.cvdcms.service.ChatService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Map;

/**
 * Interceptor bảo mật các khung tin STOMP (CONNECT, SUBSCRIBE, SEND):
 * - Chặn người dùng chưa đăng nhập.
 * - Chặn Bệnh nhân hoặc nhân viên cơ sở khác SUBSCRIBE vào /topic/chat/branch/{branchId} không thuộc phạm vi của mình.
 * - Chặn người dùng không phải Admin SUBSCRIBE vào /topic/chat/admin.
 */
@Component
public class ChatStompChannelInterceptor implements ChannelInterceptor {

    private static final String BRANCH_TOPIC_PREFIX = "/topic/chat/branch/";
    private static final String ADMIN_TOPIC = "/topic/chat/admin";
    private static final String USER_QUEUE = "/user/queue/chat/messages";
    private static final String SEND_DEST = "/app/chat.send";

    private final ChatService chatService;

    public ChatStompChannelInterceptor(ChatService chatService) {
        this.chatService = chatService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        ChatStompPrincipal principal = resolvePrincipal(accessor);

        if (command == StompCommand.CONNECT) {
            if (principal == null || principal.getUserId() == null) {
                throw new AccessDeniedException("Vui lòng đăng nhập trước khi kết nối hệ thống chat.");
            }
            accessor.setUser(principal);
            return message;
        }

        if (command == StompCommand.SUBSCRIBE) {
            if (principal == null || principal.getUserId() == null) {
                throw new AccessDeniedException("Phiên kết nối chat chưa được xác thực.");
            }
            String destination = accessor.getDestination();
            if (destination == null || destination.isBlank()) {
                throw new AccessDeniedException("Kênh đăng ký STOMP không hợp lệ.");
            }
            validateSubscription(principal.getUserId(), destination.trim());
            return message;
        }

        if (command == StompCommand.SEND) {
            if (principal == null || principal.getUserId() == null) {
                throw new AccessDeniedException("Phiên kết nối chat chưa được xác thực.");
            }
            String destination = accessor.getDestination();
            if (destination == null || !SEND_DEST.equals(destination.trim())) {
                throw new AccessDeniedException("Đích gửi tin nhắn STOMP không hợp lệ.");
            }
            return message;
        }

        return message;
    }

    private ChatStompPrincipal resolvePrincipal(StompHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (user instanceof ChatStompPrincipal stompPrincipal) {
            return stompPrincipal;
        }
        Map<String, Object> sessionAttrs = accessor.getSessionAttributes();
        if (sessionAttrs != null) {
            Object uidObj = sessionAttrs.get(ChatWebSocketHandshakeInterceptor.ATTR_USER_ID);
            Object unameObj = sessionAttrs.get(ChatWebSocketHandshakeInterceptor.ATTR_USERNAME);
            if (uidObj instanceof Number num && unameObj instanceof String uname) {
                ChatStompPrincipal created = new ChatStompPrincipal(num.longValue(), uname);
                accessor.setUser(created);
                return created;
            }
        }
        return null;
    }

    public void validateSubscription(Long userId, String destination) {
        if (USER_QUEUE.equals(destination) || "/queue/chat/messages".equals(destination)) {
            return;
        }
        if (ADMIN_TOPIC.equals(destination)) {
            if (!chatService.isAdminUser(userId)) {
                throw new AccessDeniedException("Chỉ Admin mới có quyền theo dõi kênh hội thoại toàn hệ thống.");
            }
            return;
        }
        if (destination.startsWith(BRANCH_TOPIC_PREFIX)) {
            String branchPart = destination.substring(BRANCH_TOPIC_PREFIX.length()).trim();
            Long branchId;
            try {
                branchId = Long.parseLong(branchPart);
            } catch (NumberFormatException ex) {
                throw new AccessDeniedException("Mã cơ sở trong kênh STOMP không hợp lệ.");
            }
            if (!chatService.canAccessBranchTopic(userId, branchId)) {
                throw new AccessDeniedException("Bạn không có quyền theo dõi kênh hội thoại của cơ sở #" + branchId + ".");
            }
            return;
        }
        throw new AccessDeniedException("Không được phép đăng ký kênh STOMP: " + destination);
    }
}
