package com.cvdcms.config;

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
import java.util.Map;

/**
 * Cấu hình WebSocket + STOMP cho chức năng Realtime Chat theo Cơ sở:
 * - Endpoint kết nối: /ws-chat (hỗ trợ cả WebSocket thuần và SockJS fallback).
 * - Prefix gửi tin từ client: /app (ví dụ: /app/chat.send).
 * - Broker phát tin: /topic (cho cơ sở & Admin) và /queue (cho hàng đợi cá nhân /user/queue/chat/messages).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final ChatWebSocketHandshakeInterceptor handshakeInterceptor;
    private final ChatStompChannelInterceptor stompChannelInterceptor;

    public WebSocketConfig(
            ChatWebSocketHandshakeInterceptor handshakeInterceptor,
            ChatStompChannelInterceptor stompChannelInterceptor) {
        this.handshakeInterceptor = handshakeInterceptor;
        this.stompChannelInterceptor = stompChannelInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

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

        registry.addEndpoint("/ws-chat")
                .addInterceptors(handshakeInterceptor)
                .setHandshakeHandler(handshakeHandler)
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompChannelInterceptor);
    }
}
