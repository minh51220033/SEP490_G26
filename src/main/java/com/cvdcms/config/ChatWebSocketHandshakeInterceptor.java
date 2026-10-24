package com.cvdcms.config;

import com.cvdcms.entity.User;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Interceptor bắt tay HTTP sang WebSocket (/ws-chat):
 * Trích xuất đối tượng User từ HttpSession ("loggedInUser") và gắn vào attributes của phiên WebSocket.
 * Tuyệt đối không tin tưởng userId gửi từ phía client.
 */
@Component
public class ChatWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER_ID = "chatUserId";
    public static final String ATTR_USERNAME = "chatUsername";

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {

        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpSession session = servletRequest.getServletRequest().getSession(false);
            if (session != null) {
                Object obj = session.getAttribute("loggedInUser");
                if (obj instanceof User user && user.getUserId() != null && user.getUsername() != null) {
                    if (Boolean.FALSE.equals(user.getIsActive())
                            || (user.getStatus() != null && !"ACTIVE".equalsIgnoreCase(user.getStatus()))) {
                        return false;
                    }
                    attributes.put(ATTR_USER_ID, user.getUserId());
                    attributes.put(ATTR_USERNAME, user.getUsername());
                    return true;
                }
            }
        }
        // Từ chối bắt tay WebSocket nếu chưa đăng nhập
        return false;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        // Không cần xử lý hậu bắt tay
    }
}
