package com.cvdcms.DTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * DTO nhận dữ liệu khi tạo mới hoặc cập nhật lịch/phân công công việc CSKH (SCR-44.3).
 */
@Getter
@Setter
@NoArgsConstructor
public class CustomerCareCreateDTO {
    private Long branchId;
    private Long profileId;
    /**
     * Nhóm chăm sóc (POST_TREATMENT_FOLLOWUP, MEDICATION_REMINDER, APPOINTMENT_REMINDER,
     * INSTALLMENT_REMINDER, NO_SHOW_RECALL, NO_SERVICE_FOLLOWUP, FEEDBACK_COMPLAINT).
     */
    private String careGroup;
    private String taskType;
    private LocalDate dueDate;
    private Long assignedStaffId;
    private Long visitId;
    private Long appointmentId;
    private Long prescriptionId;
    private String notes;
}
