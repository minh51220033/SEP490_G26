package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO biểu diễn một tin nhắn trong cuộc hội thoại (dùng cho cả REST API và sự kiện WebSocket STOMP).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDTO {

    private Long messageId;
    private Long conversationId;
    private Long branchId;
    private String branchName;

    // Danh tính thực tế của người gửi tin nhắn
    private Long senderId;
    private String senderUsername;
    private String senderFullName;
    private String senderRoleCode;   // BENH_NHAN, LE_TAN, BAC_SI, QUAN_LY_KHO, KE_TOAN, ADMIN
    private String senderRoleLabel;  // Bệnh nhân, Lễ tân, Bác sĩ, Quản lý kho, Kế toán, Admin
    private String senderType;       // PATIENT hoặc STAFF

    // Người khởi tạo cuộc hội thoại (để định tuyến STOMP và phân biệt tin nhắn bên trái/phải tại hộp thư)
    private Long initiatorUserId;
    private String initiatorUsername;
    private String initiatorFullName;
    private String initiatorRoleCode;
    private String initiatorRoleLabel;

    // Người nhận trực tiếp (nếu là hội thoại 1-1 giữa nhân viên/Admin)
    private boolean directConversation;
    private Long recipientUserId;
    private String recipientUsername;
    private String recipientFullName;
    private String recipientRoleCode;
    private String recipientRoleLabel;

    private String messageText;
    private Boolean isRead;
    private LocalDateTime sentAt;

    // Mã định danh tạm phía client để chống nhân đôi tin nhắn trên giao diện
    private String clientMessageId;

    // Đánh dấu đây là tin nhắn đầu tiên trong cuộc hội thoại mới (hiển thị thông báo hệ thống hướng dẫn chờ phản hồi)
    private boolean firstMessageInConversation;
    private String systemNotice;
}
