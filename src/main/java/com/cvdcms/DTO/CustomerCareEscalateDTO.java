package com.cvdcms.DTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO nhận dữ liệu khi chuyển ca cho Bác sĩ hỗ trợ chuyên môn hoặc khi Bác sĩ nhập phản hồi (SCR-44.5).
 */
@Getter
@Setter
@NoArgsConstructor
public class CustomerCareEscalateDTO {
    /**
     * ID nhân sự Bác sĩ tiếp nhận (staff_id).
     */
    private Long escalatedToDoctorId;
    /**
     * Ghi chú triệu chứng / lý do cần Bác sĩ tư vấn chuyên môn.
     */
    private String escalationReason;
    /**
     * Nội dung chỉ định / phản hồi chuyên môn của Bác sĩ.
     */
    private String doctorResponse;
    /**
     * Trạng thái công việc sau khi Bác sĩ phản hồi (IN_PROGRESS để CSKH gọi lại báo BN, hoặc COMPLETED).
     */
    private String nextStatus;
}
