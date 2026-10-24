package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO biểu diễn thông tin một tài khoản trong cùng cơ sở khi nhân viên / Admin tìm kiếm người nhận để gửi tin nhắn.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatUserSearchItemDTO {

    private Long userId;
    private String username;
    private String fullName;
    private String phoneNumber;
    private String email;

    private String roleCode;   // BENH_NHAN, LE_TAN, BAC_SI, QUAN_LY_KHO, KE_TOAN, ADMIN
    private String roleLabel;  // Bệnh nhân, Lễ tân, Bác sĩ, Quản lý kho, Kế toán, Admin

    private Long branchId;
    private String branchCode;
    private String branchName;

    // Nếu đã có cuộc hội thoại đang mở với tài khoản này tại cơ sở thì trả kèm conversationId
    private Long existingConversationId;
}
