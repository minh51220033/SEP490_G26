package com.cvdcms.DTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO dùng chung cho luồng chuyển công việc CSKH lên Bác sĩ hỗ trợ chuyên môn và luồng Bác sĩ phản hồi (SCR-44.5).
 *
 * <p><b>Đầu vào:</b> Payload JSON cho {@code POST /api/customer-care/tasks/{taskId}/escalate}
 * và {@code POST /api/customer-care/tasks/{taskId}/doctor-response}.</p>
 * <p><b>Xử lý thực tế:</b> Khi chuyển bác sĩ dùng {@code escalatedToDoctorId} + {@code escalationReason};
 * khi bác sĩ phản hồi dùng {@code doctorResponse} + {@code nextStatus} ({@code IN_PROGRESS} để chuyển lại lễ tân
 * hoặc {@code COMPLETED} để đóng công việc).</p>
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
