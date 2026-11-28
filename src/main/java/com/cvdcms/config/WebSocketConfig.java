package com.cvdcms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Arrays;
import java.util.Map;

/**
 * Cấu hình WebSocket + STOMP tập trung cho toàn hệ thống CV-DCMS.
 * <p>
 * <strong>LƯU Ý DÙNG CHUNG:</strong> Cấu hình này không chỉ phục vụ Chatbox nội bộ / bệnh nhân,
 * mà còn <b>dùng chung</b> với các phân hệ realtime khác:
 * <ul>
 *     <li><b>Chatbox:</b> {@code /topic/chat/branch/{branchId}}, {@code /topic/chat/admin},
 *     {@code /user/queue/chat/messages}, và đích gửi STOMP {@code /app/chat.send}.</li>
 *     <li><b>Chăm sóc khách hàng (CSKH):</b> {@code /topic/customer-care/branch/{branchId}},
 *     {@code /topic/customer-care/admin}, {@code /user/queue/customer-care/notifications}.</li>
 *     <li><b>Hàng đợi khám &amp; Bác sĩ (Queue/Doctor):</b> {@code /topic/queue/branch/{branchId}},
 *     {@code /topic/queue/doctor/{doctorId}}, {@code /user/queue/doctor/notifications}.</li>
 * </ul>
 * - Endpoint kết nối chung: {@code /ws-chat} (hỗ trợ cả WebSocket thuần và SockJS fallback).<br>
 * - Prefix gửi tin từ client lên server: {@code /app}.<br>
 * - Broker phát tin: {@code /topic} (broadcast theo cơ sở/Admin) và {@code /queue} (hàng đợi riêng theo user).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${app.websocket.allowed-origins:*}")
    private String allowedOrigins;

    private final ChatWebSocketHandshakeInterceptor handshakeInterceptor;
    private final ChatStompChannelInterceptor stompChannelInterceptor;

    public WebSocketConfig(
            ChatWebSocketHandshakeInterceptor handshakeInterceptor,
            ChatStompChannelInterceptor stompChannelInterceptor) {
        this.handshakeInterceptor = handshakeInterceptor;
        this.stompChannelInterceptor = stompChannelInterceptor;
    }

    /**
     * Đăng ký Simple Broker nội bộ cho {@code /topic}, {@code /queue},
     * prefix nhận lệnh ứng dụng {@code /app} và prefix định tuyến cá nhân {@code /user}.
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    /**
     * Đăng ký endpoint STOMP {@code /ws-chat} (kèm SockJS fallback), gắn
     * {@link ChatWebSocketHandshakeInterceptor} để xác thực từ {@code HttpSession} và
     * ánh xạ thành {@link ChatStompPrincipal} làm danh tính người dùng của phiên WebSocket.
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        DefaultHandshakeHandler handshakeHandler = new DefaultHandshakeHandler() {
            @Override
            protected Principal determineUser(
                    ServerHttpRequest request,
                    WebSocketHandler wsHandler,
                    Map<String, Object> attributes) {
                Object uidObj = attributes.get(ChatWebSocketHandshakeInterceptor.ATTR_USER_ID);
                Object unameObj = attributes.get(ChatWebSocketHandshakeInterceptor.ATTR_USERNAME);
                if (uidObj instanceof Number num && unameObj instanceof String uname) {
                    return new ChatStompPrincipal(num.longValue(), uname);
                }
                return super.determineUser(request, wsHandler, attributes);
            }
        };

        String[] originPatterns = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        registry.addEndpoint("/ws-chat")
                .addInterceptors(handshakeInterceptor)
                .setHandshakeHandler(handshakeHandler)
                .setAllowedOriginPatterns(originPatterns)
                .withSockJS();
    }

    /**
     * Đăng ký {@link ChatStompChannelInterceptor} trên kênh inbound để kiểm soát quyền
     * CONNECT, SUBSCRIBE và SEND cho tất cả kênh Chatbox, CSKH và Queue/Doctor.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompChannelInterceptor);
    }
}
