package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO nhận dữ liệu đối chiếu và liên kết kết quả X-quang vào hồ sơ & lượt khám (SCR-12.3).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NanopixReconciliationRequestDTO {

    /**
     * ID hồ sơ bệnh nhân trong hệ thống CV-DCMS (bắt buộc).
     */
    private Long profileId;

    /**
     * ID lượt khám (Visit) muốn gắn kết quả (bắt buộc).
     */
    private Long visitId;

    /**
     * ID chỉ định cận lâm sàng X-quang tương ứng trong lượt khám (tùy chọn).
     */
    private Long orderItemId;

    /**
     * Vị trí răng chỉ định (ví dụ: 18, 21, 36, 47...).
     */
    private String toothNumbers;

    /**
     * Ghi chú đối chiếu của nhân viên hoặc bác sĩ.
     */
    private String notes;
}
