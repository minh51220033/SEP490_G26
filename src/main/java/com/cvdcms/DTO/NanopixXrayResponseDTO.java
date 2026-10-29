package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO trả về chi tiết kết quả phim X-quang phục vụ danh sách & trình xem ảnh (SCR-12.1).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NanopixXrayResponseDTO {

    private Long syncLogId;
    private String externalStudyUid;
    private String externalPatientId;
    private String rawPatientName;
    private String imageUrl;

    private String syncStatus;
    private String syncStatusLabel;

    // Thông tin bệnh nhân đã khớp (nếu MATCHED)
    private Long profileId;
    private String patientCode;
    private String patientName;
    private String patientPhone;
    private String patientDob;

    // Thông tin ca khám (Visit) đã liên kết
    private Long visitId;
    private String visitCode;
    private String visitDate;
    private Long branchId;
    private String branchName;

    // Thông tin chỉ định cận lâm sàng (nếu có)
    private Long orderItemId;
    private String serviceName;
    private String toothNumbers;

    // Lịch sử liên kết thủ công
    private Boolean isManualLinked;
    private String linkedByName;
    private String linkedAt;

    // Thời gian chụp và đồng bộ
    private LocalDateTime capturedAt;
    private String capturedAtFormatted;
    private LocalDateTime syncTime;
    private String syncTimeFormatted;

    // Thông tin thiết bị nguồn
    private String deviceSerialNumber;
    private String deviceModel;

    // Thông báo lỗi / Ghi chú
    private String errorMessage;
    private String notes;
}
