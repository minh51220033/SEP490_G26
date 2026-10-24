package com.cvdcms.config;

import java.security.Principal;

/**
 * Principal đại diện cho người dùng đã đăng nhập trong phiên kết nối WebSocket STOMP.
 * getName() trả về username để SimpMessagingTemplate.convertAndSendToUser(username, ...) định tuyến chính xác.
 */
public class ChatStompPrincipal implements Principal {

    private final Long userId;
    private final String username;

    public ChatStompPrincipal(Long userId, String username) {
        this.userId = userId;
        this.username = username;
    }

    public Long getUserId() {
        return userId;
    }

    @Override
    public String getName() {
        return username;
    }
}
