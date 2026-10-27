package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO biểu diễn thông tin cuộc hội thoại trong danh sách hộp thư cơ sở / Admin và khung chat nổi.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatConversationDTO {

    private Long conversationId;

    // Thông tin người khởi tạo cuộc hội thoại (Initiator)
    private Long initiatorUserId;
    private String initiatorUsername;
    private String initiatorFullName;
    private String initiatorPhone;
    private String initiatorRoleCode;   // BENH_NHAN, LE_TAN, BAC_SI, QUAN_LY_KHO, KE_TOAN, ADMIN
    private String initiatorRoleLabel;  // Bệnh nhân, Lễ tân, Bác sĩ, Quản lý kho, Kế toán, Admin

    // Thông tin người nhận cụ thể (nếu là hội thoại trực tiếp 1-1 giữa các nhân viên/Admin)
    private boolean directConversation;
    private Long recipientUserId;
    private String recipientUsername;
    private String recipientFullName;
    private String recipientPhone;
    private String recipientRoleCode;
    private String recipientRoleLabel;

    // Thông tin đối tác hiển thị trên danh sách đối với người đang xem (Counterpart Display)
    private Long counterpartUserId;
    private String counterpartUsername;
    private String counterpartFullName;
    private String counterpartPhone;
    private String counterpartRoleCode;
    private String counterpartRoleLabel;

    // Thông tin cơ sở (Branch) gắn với hội thoại
    private Long branchId;
    private String branchCode;
    private String branchName;

    // Trạng thái hội thoại (STAFF_HANDLING / CLOSED)
    private String status;

    // Thông tin tin nhắn gần nhất
    private String lastMessageText;
    private Long lastMessageSenderId;
    private String lastMessageSenderName;
    private LocalDateTime lastMessageAt;
    private LocalDateTime createdAt;

    // Số lượng và trạng thái tin nhắn chưa đọc
    private long unreadCount;
    private boolean unread;
}
