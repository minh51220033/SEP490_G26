package com.cvdcms.config;

import com.cvdcms.entity.StaffProfile;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.service.ChatService;
import com.cvdcms.service.CustomerCareService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
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
 * Interceptor bảo mật các khung tin STOMP đầu vào ({@code CONNECT}, {@code SUBSCRIBE}, {@code SEND}).
 * <p>
 * <strong>LƯU Ý DÙNG CHUNG:</strong> Interceptor này bảo vệ đồng thời nhiều phân hệ realtime trên cùng
 * kết nối {@code /ws-chat}:
 * <ul>
 *     <li><b>Chatbox:</b> {@code /user/queue/chat/messages}, {@code /topic/chat/branch/{branchId}},
 *     {@code /topic/chat/admin} và đích gửi {@code /app/chat.send}.</li>
 *     <li><b>CSKH (Customer Care):</b> {@code /user/queue/customer-care/notifications},
 *     {@code /topic/customer-care/branch/{branchId}}, {@code /topic/customer-care/admin}.</li>
 *     <li><b>Hàng đợi khám &amp; Bác sĩ (Queue/Doctor):</b> {@code /topic/queue/branch/{branchId}},
 *     {@code /topic/queue/doctor/{doctorId}}, {@code /user/queue/doctor/notifications}.</li>
 * </ul>
 * Mọi yêu cầu chưa đăng nhập, đăng ký kênh ngoài phạm vi cơ sở/vai trò hoặc gửi vào đích không hợp lệ
 * đều bị chặn bằng {@link AccessDeniedException}.
 */
@Component
public class ChatStompChannelInterceptor implements ChannelInterceptor {

    private static final String BRANCH_TOPIC_PREFIX = "/topic/chat/branch/";
    private static final String ADMIN_TOPIC = "/topic/chat/admin";
    private static final String USER_QUEUE = "/user/queue/chat/messages";
    private static final String CC_BRANCH_TOPIC_PREFIX = "/topic/customer-care/branch/";
    private static final String CC_ADMIN_TOPIC = "/topic/customer-care/admin";
    private static final String CC_USER_QUEUE = "/user/queue/customer-care/notifications";
    private static final String QUEUE_BRANCH_TOPIC_PREFIX = "/topic/queue/branch/";
    private static final String QUEUE_DOCTOR_TOPIC_PREFIX = "/topic/queue/doctor/";
    private static final String SEND_DEST = "/app/chat.send";

    private final ChatService chatService;
    private CustomerCareService customerCareService;
    private StaffProfileRepository staffProfileRepository;

    public ChatStompChannelInterceptor(ChatService chatService) {
        this.chatService = chatService;
    }

    @Autowired
    public void setCustomerCareService(@Lazy CustomerCareService customerCareService) {
        this.customerCareService = customerCareService;
    }

    @Autowired(required = false)
    public void setStaffProfileRepository(@Lazy StaffProfileRepository staffProfileRepository) {
        this.staffProfileRepository = staffProfileRepository;
    }

    /**
     * Chặn và xác thực từng khung tin STOMP trước khi chuyển vào broker:
     * <ul>
     *     <li>{@code CONNECT}: bắt buộc có {@link ChatStompPrincipal} hợp lệ từ session handshake.</li>
     *     <li>{@code SUBSCRIBE}: kiểm tra quyền truy cập kênh qua {@link #validateSubscription(Long, String)}.</li>
     *     <li>{@code SEND}: chỉ cho phép gửi vào đích {@code /app/chat.send}.</li>
     * </ul>
     */
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

    /**
     * Trích xuất {@link ChatStompPrincipal} từ header STOMP hoặc khôi phục từ {@code sessionAttributes}
     * đã được gắn trong {@link ChatWebSocketHandshakeInterceptor}.
     */
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

    /**
     * Kiểm tra phân quyền khi người dùng đăng ký (SUBSCRIBE) một kênh STOMP bất kỳ:
     * <ul>
     *     <li>Hàng đợi cá nhân (Chat, CSKH, thông báo Bác sĩ): mọi tài khoản đã đăng nhập đều được phép.</li>
     *     <li>Kênh tổng hợp Admin ({@code /topic/chat/admin}, {@code /topic/customer-care/admin}): chỉ ADMIN.</li>
     *     <li>Kênh CSKH theo cơ sở ({@code /topic/customer-care/branch/{branchId}}): kiểm tra quyền cơ sở qua CSKH &amp; ChatService.</li>
     *     <li>Kênh hàng đợi theo cơ sở ({@code /topic/queue/branch/{branchId}}): chỉ nhân sự thuộc cơ sở hoặc ADMIN.</li>
     *     <li>Kênh hàng đợi riêng bác sĩ ({@code /topic/queue/doctor/{doctorId}}): chỉ chính bác sĩ đó hoặc ADMIN.</li>
     *     <li>Kênh chat theo cơ sở ({@code /topic/chat/branch/{branchId}}): chỉ Lễ tân/Bác sĩ thuộc cơ sở hoặc ADMIN.</li>
     * </ul>
     *
     * @throws AccessDeniedException nếu kênh không nằm trong danh sách cho phép hoặc vượt quá quyền hạn
     */
    public void validateSubscription(Long userId, String destination) {
        if (USER_QUEUE.equals(destination) || "/queue/chat/messages".equals(destination)
                || CC_USER_QUEUE.equals(destination) || "/queue/customer-care/notifications".equals(destination)
                || "/user/queue/doctor/notifications".equals(destination) || "/queue/doctor/notifications".equals(destination)) {
            return;
        }
        if (ADMIN_TOPIC.equals(destination) || CC_ADMIN_TOPIC.equals(destination)) {
            if (!chatService.isAdminUser(userId)) {
                throw new AccessDeniedException("Chỉ Admin mới có quyền theo dõi kênh tổng hợp toàn hệ thống.");
            }
            return;
        }
        if (destination.startsWith(CC_BRANCH_TOPIC_PREFIX)) {
            String branchPart = destination.substring(CC_BRANCH_TOPIC_PREFIX.length()).trim();
            Long branchId;
            try {
                branchId = Long.parseLong(branchPart);
            } catch (NumberFormatException ex) {
                throw new AccessDeniedException("Mã cơ sở trong kênh CSKH STOMP không hợp lệ.");
            }
            if (customerCareService != null) {
                try {
                    customerCareService.getAccessibleBranches(userId);
                    if (!chatService.canAccessBranchTopic(userId, branchId)) {
                        throw new AccessDeniedException("Bạn không có quyền theo dõi kênh CSKH của cơ sở #" + branchId + ".");
                    }
                    return;
                } catch (AccessDeniedException ade) {
                    throw ade;
                } catch (Exception ex) {
                    throw new AccessDeniedException("Bạn không có quyền theo dõi kênh CSKH của cơ sở #" + branchId + ".");
                }
            }
            if (!chatService.canAccessBranchTopic(userId, branchId)) {
                throw new AccessDeniedException("Bạn không có quyền theo dõi kênh CSKH của cơ sở #" + branchId + ".");
            }
            return;
        }
        if (destination.startsWith(QUEUE_BRANCH_TOPIC_PREFIX)) {
            String branchPart = destination.substring(QUEUE_BRANCH_TOPIC_PREFIX.length()).trim();
            Long branchId;
            try {
                branchId = Long.parseLong(branchPart);
            } catch (NumberFormatException ex) {
                throw new AccessDeniedException("Mã cơ sở trong kênh hàng đợi STOMP không hợp lệ.");
            }
            if (!chatService.canAccessBranchTopic(userId, branchId)) {
                throw new AccessDeniedException("Bạn không có quyền theo dõi hàng đợi của cơ sở #" + branchId + ".");
            }
            return;
        }
        if (destination.startsWith(QUEUE_DOCTOR_TOPIC_PREFIX)) {
            String doctorPart = destination.substring(QUEUE_DOCTOR_TOPIC_PREFIX.length()).trim();
            Long doctorId;
            try {
                doctorId = Long.parseLong(doctorPart);
            } catch (NumberFormatException ex) {
                throw new AccessDeniedException("Mã bác sĩ trong kênh hàng đợi STOMP không hợp lệ.");
            }
            if (chatService.isAdminUser(userId)) {
                return;
            }
            Long ownStaffId = staffProfileRepository != null
                    ? staffProfileRepository.findByUser_UserId(userId).map(StaffProfile::getStaffId).orElse(null)
                    : null;
            if (!doctorId.equals(ownStaffId)) {
                throw new AccessDeniedException("Bạn không có quyền theo dõi hàng đợi riêng của bác sĩ khác.");
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
