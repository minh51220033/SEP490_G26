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
 * Interceptor bắt tay HTTP sang WebSocket tại endpoint dùng chung {@code /ws-chat}
 * (phục vụ Chatbox, CSKH và hàng đợi khám Queue/Doctor):
 * <ul>
 *     <li>Trích xuất đối tượng {@link User} từ {@link HttpSession} (thuộc tính {@code loggedInUser}).</li>
 *     <li>Kiểm tra tài khoản còn hoạt động ({@code isActive != false} và {@code status == ACTIVE}).</li>
 *     <li>Lưu {@code userId} và {@code username} vào {@code attributes} của phiên WebSocket để
 *     {@link WebSocketConfig} khởi tạo {@link ChatStompPrincipal}.</li>
 *     <li>Tuyệt đối không tin tưởng {@code userId} do phía client tự truyền lên.</li>
 * </ul>
 */
@Component
public class ChatWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    /** Khóa lưu {@code userId} trong thuộc tính phiên WebSocket. */
    public static final String ATTR_USER_ID = "chatUserId";
    /** Khóa lưu {@code username} trong thuộc tính phiên WebSocket. */
    public static final String ATTR_USERNAME = "chatUsername";

    /**
     * Thực thi trước khi hoàn tất bắt tay WebSocket: chỉ cho phép kết nối nếu phiên HTTP
     * đã đăng nhập hợp lệ và tài khoản đang ở trạng thái hoạt động.
     *
     * @return {@code true} để cho phép nâng cấp kết nối, {@code false} để từ chối.
     */
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

    /**
     * Callback sau khi bắt tay hoàn tất (không yêu cầu xử lý bổ sung).
     */
    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        // Không cần xử lý hậu bắt tay
    }
}
