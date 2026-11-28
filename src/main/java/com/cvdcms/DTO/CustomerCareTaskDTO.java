package com.cvdcms.DTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DTO trả về đầy đủ thông tin một công việc Chăm sóc khách hàng (CSKH - SCR-44.1, 44.2, 44.4, 44.5).
 *
 * <p><b>Chức năng:</b> Gom toàn bộ dữ liệu hiển thị cho bảng danh sách, lịch biểu, modal chi tiết và drawer:
 * thông tin bệnh nhân, chi nhánh, nhóm chăm sóc, liên kết hồ sơ khám lâm sàng / chẩn đoán / lời dặn bác sĩ /
 * đơn thuốc / lịch hẹn, nhân viên phụ trách, thông tin chuyển bác sĩ & phản hồi chuyên môn, trạng thái quá hạn,
 * lần tương tác gần nhất, thông tin bác sĩ nghỉ phép (FT01) và thông tin nhắc đóng tiền theo đợt (FT02).</p>
 * <p><b>Được dùng bởi:</b> {@link com.cvdcms.controller.CustomerCareRestController} và {@code customer-care.js}.</p>
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

    // Thông tin hồ sơ gốc liên kết (Ca khám, Khám lâm sàng, Chẩn đoán, Lời dặn điều trị, Lịch hẹn, Đơn thuốc)
    private Long visitId;
    private String visitCode;
    private LocalDateTime visitCheckInAt;
    private String visitSymptoms;
    private String visitStatus;
    private String visitNotes;

    private Long doctorId;
    private String doctorCode;
    private String doctorName;

    private Long examId;
    private LocalDateTime examTime;
    private String chiefComplaint;
    private String summaryAssessment;
    private String diagnosisSummary;
    private String treatmentInstructions;
    private String prescriptionSummary;
    private LocalDate followupDate;
    private String examinationDetailUrl;

    private List<Map<String, Object>> diagnoses = new ArrayList<>();
    private List<Map<String, Object>> prescriptionItems = new ArrayList<>();

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
    private String escalationReason;
    private Long escalatedByUserId;
    private String escalatedByName;

    // Thời hạn & Trạng thái
    private LocalDate dueDate;
    private boolean overdue;
    private boolean dueToday;
    private String dueBucket;
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

    // FT01 — Bác sĩ nghỉ phép
    private Long leaveRequestId;
    private String leaveDoctorName;
    private LocalDate leaveStartDate;
    private LocalDate leaveEndDate;
    private String leaveShiftName;
    private String appointmentExpectedTime;
    private String appointmentStatus;

    // FT02 — Nhắc thanh toán từng đợt (8 chỉ số)
    private Long treatmentPlanId;
    private String treatmentPlanCode;
    private String treatmentPlanName;
    private java.math.BigDecimal planTotalCost;
    private java.math.BigDecimal planPaidAmount;
    private java.math.BigDecimal planRemainingAmount;
    private Long installmentId;
    private Integer installmentOrder;
    private String installmentPhaseName;
    private LocalDate installmentExpectedDate;
    private java.math.BigDecimal installmentExpectedAmount;
    private java.math.BigDecimal installmentPaidAmount;
    private java.math.BigDecimal installmentRemainingAmount;
    private String reminderStage;
    private LocalDateTime lastRemindedAt;

    // Danh sách lịch sử tương tác chi tiết của công việc này
    private List<CustomerCareInteractionDTO> interactions = new ArrayList<>();
}
