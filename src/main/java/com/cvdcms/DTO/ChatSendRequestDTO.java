package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO nhận yêu cầu gửi tin nhắn từ client (qua STOMP /app/chat.send hoặc REST POST /api/chat/messages).
 * Lưu ý bảo mật: Backend luôn xác định người gửi từ phiên đăng nhập thực tế (HttpSession / Principal).
 * Trường recipientUserId chỉ được phép sử dụng khi người gửi KHÔNG phải là Bệnh nhân.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSendRequestDTO {

    /**
     * ID cuộc hội thoại (nếu gửi từ hộp thư nhân viên/Admin hoặc gửi tiếp trong cuộc hội thoại đã có).
     */
    private Long conversationId;

    /**
     * ID người nhận cụ thể trong cùng cơ sở (chỉ dành cho nhân viên/Admin khi bắt đầu nhắn tin cho một tài khoản cụ thể;
     * Bệnh nhân không được sử dụng trường này).
     */
    private Long recipientUserId;

    /**
     * Nội dung tin nhắn văn bản.
     */
    private String messageText;

    /**
     * Mã định danh tin nhắn phía client để chống gửi trùng lặp khi click nhanh.
     */
    private String clientMessageId;
}
