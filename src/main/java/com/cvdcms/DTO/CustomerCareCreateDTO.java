package com.cvdcms.DTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * DTO nhận dữ liệu đầu vào khi tạo mới hoặc cập nhật/phân công một công việc CSKH thủ công (SCR-44.3).
 *
 * <p><b>Đầu vào:</b> Payload JSON cho {@code POST /api/customer-care/tasks} và {@code PUT /api/customer-care/tasks/{taskId}}.</p>
 * <p><b>Được gửi từ:</b> {@code submitTaskForm()} trong {@code customer-care.js}.</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class CustomerCareCreateDTO {
    /** ID chi nhánh thực hiện công việc CSKH. */
    private Long branchId;
    /** ID hồ sơ bệnh nhân cần chăm sóc. */
    private Long profileId;
    /**
     * Nhóm chăm sóc (POST_TREATMENT_FOLLOWUP, MEDICATION_REMINDER, APPOINTMENT_REMINDER,
     * INSTALLMENT_REMINDER, NO_SHOW_RECALL, NO_SERVICE_FOLLOWUP, FEEDBACK_COMPLAINT).
     */
    private String careGroup;
    /** Loại công việc CSKH (tương ứng enum {@code CustomerCareTaskType}). */
    private String taskType;
    /** Ngày đến hạn cần liên hệ/xử lý công việc. */
    private LocalDate dueDate;
    /** ID nhân viên CSKH/Lễ tân được phân công phụ trách. */
    private Long assignedStaffId;
    /** ID lượt khám liên kết (nếu có). */
    private Long visitId;
    /** ID lịch hẹn liên kết (nếu có). */
    private Long appointmentId;
    /** ID đơn thuốc liên kết (nếu có). */
    private Long prescriptionId;
    /** Ghi chú hướng dẫn/nội dung công việc CSKH. */
    private String notes;
}
