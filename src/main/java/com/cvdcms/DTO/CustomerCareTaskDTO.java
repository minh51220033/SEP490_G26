package com.cvdcms.DTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO trả về thông tin công việc chăm sóc khách hàng (SCR-44.1, SCR-44.2, SCR-44.4, SCR-44.5).
 */
@Getter
@Setter
@NoArgsConstructor
public class CustomerCareTaskDTO {
    private Long taskId;
    private String taskCode;

    // Thông tin bệnh nhân
    private Long profileId;
    private String patientCode;
    private String patientName;
    private String patientPhone;
    private String patientGender;
    private LocalDate patientDob;
    private String patientAddress;

    // Thông tin chi nhánh
    private Long branchId;
    private String branchCode;
    private String branchName;

    // Loại công việc & Nhóm phân loại CSKH (SCR-44.2)
    private String taskType;
    private String careGroup;
    private String careGroupLabel;

    // Thông tin hồ sơ gốc liên kết (Ca khám, Lịch hẹn, Đơn thuốc)
    private Long visitId;
    private String visitCode;
    private LocalDateTime visitCheckInAt;
    private String visitSymptoms;

    private Long appointmentId;
    private String appointmentCode;
    private LocalDate appointmentDate;
    private String appointmentServiceName;

    private Long prescriptionId;
    private String prescriptionCode;
    private String prescriptionDiagnosis;
    private String prescriptionAdvice;

    // Nhân viên CSKH được phân công
    private Long assignedStaffId;
    private String assignedStaffCode;
    private String assignedStaffName;

    // Bác sĩ tiếp nhận hỗ trợ chuyên môn (SCR-44.5)
    private Long escalatedToDoctorId;
    private String escalatedToDoctorCode;
    private String escalatedToDoctorName;
    private LocalDateTime escalatedAt;
    private String doctorResponse;

    // Thời hạn & Trạng thái
    private LocalDate dueDate;
    private boolean overdue;
    private boolean dueToday;
    private String status;
    private String statusLabel;
    private String notes;

    // Thông tin tương tác gần nhất (SCR-44.4)
    private int interactionCount;
    private String lastChannel;
    private String lastContactResult;
    private String lastInteractionNotes;
    private LocalDateTime lastContactAt;
    private LocalDateTime nextRetryAt;

    // Danh sách lịch sử tương tác chi tiết của công việc này
    private List<CustomerCareInteractionDTO> interactions = new ArrayList<>();
}
