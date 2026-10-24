package com.cvdcms.DTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * DTO nhận dữ liệu khi nhân viên CSKH ghi kết quả liên hệ bệnh nhân (SCR-44.4).
 */
@Getter
@Setter
@NoArgsConstructor
public class CustomerCareInteractionCreateDTO {
    /**
     * Kênh liên hệ: PHONE_CALL, ZALO, SMS, IN_PERSON.
     */
    private String channel;
    /**
     * Kết quả liên hệ: CONNECTED, NO_ANSWER, BUSY, SCHEDULED_APPOINTMENT.
     */
    private String contactResult;
    /**
     * Nội dung trao đổi / ghi chú kết quả liên hệ.
     */
    private String notes;
    /**
     * Thời gian hẹn gọi lại (Bắt buộc khi contactResult = NO_ANSWER hoặc BUSY).
     */
    private LocalDateTime nextRetryAt;
    /**
     * Nhân viên thực hiện liên hệ (nếu chọn thủ công trên form).
     */
    private Long staffId;
    /**
     * Trạng thái công việc muốn chuyển sang sau khi liên hệ (COMPLETED, IN_PROGRESS, CANCELLED).
     */
    private String nextStatus;
}
