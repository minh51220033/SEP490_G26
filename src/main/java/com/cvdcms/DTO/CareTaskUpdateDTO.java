package com.cvdcms.DTO;

import com.cvdcms.entity.enums.CustomerCareTaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO gọn dùng để cập nhật nhanh trạng thái và kết quả liên hệ của một công việc CSKH.
 *
 * <p><b>Chức năng:</b> Chứa ID công việc, trạng thái đích ({@link CustomerCareTaskStatus}),
 * kết quả liên hệ ({@code CONFIRMED}, {@code RESCHEDULE}, {@code NO_ANSWER}, {@code CANCELLED}) và ghi chú.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CareTaskUpdateDTO {
    /** ID công việc CSKH cần cập nhật. */
    private Long taskId;
    /** Trạng thái mới của công việc CSKH. */
    private CustomerCareTaskStatus status;
    /** Mã kết quả liên hệ: CONFIRMED, RESCHEDULE, NO_ANSWER, CANCELLED. */
    private String contactResult; // "CONFIRMED", "RESCHEDULE", "NO_ANSWER", "CANCELLED"
    /** Ghi chú bổ sung sau khi liên hệ. */
    private String notes;
}
