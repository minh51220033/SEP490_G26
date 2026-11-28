package com.cvdcms.config;

import java.security.Principal;

/**
 * Đối tượng {@link Principal} đại diện cho người dùng đã đăng nhập trong phiên kết nối WebSocket STOMP
 * (dùng chung cho Chatbox, CSKH và Queue/Doctor trên endpoint {@code /ws-chat}).
 * <p>
 * {@link #getName()} trả về {@code username} để {@code SimpMessagingTemplate.convertAndSendToUser(username, ...)}
 * định tuyến chính xác tới hàng đợi riêng {@code /user/{username}/queue/...}.
 */
public class ChatStompPrincipal implements Principal {

    private final Long userId;
    private final String username;

    /**
     * Khởi tạo Principal từ thông tin tài khoản đã xác thực trong bước bắt tay WebSocket.
     *
     * @param userId   mã định danh người dùng trong bảng {@code users}
     * @param username tên đăng nhập dùng làm khóa định tuyến STOMP theo người dùng
     */
    public ChatStompPrincipal(Long userId, String username) {
        this.userId = userId;
        this.username = username;
    }

    /**
     * Trả về {@code userId} của người dùng hiện tại để phục vụ kiểm tra phân quyền kênh STOMP.
     */
    public Long getUserId() {
        return userId;
    }

    /**
     * Trả về {@code username} theo chuẩn {@link Principal#getName()}.
     */
    @Override
    public String getName() {
        return username;
    }
}
