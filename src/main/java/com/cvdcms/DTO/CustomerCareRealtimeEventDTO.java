package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Payload sự kiện WebSocket STOMP cho module Chăm sóc khách hàng.
 * Chỉ chứa siêu dữ liệu điều phối (không chứa nội dung bệnh án/chẩn đoán nhạy cảm).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerCareRealtimeEventDTO {
    private String eventType;
    private Long taskId;
    private String taskCode;
    private Long branchId;
    private String status;
    private LocalDateTime occurredAt;
    private Long actorUserId;
    private String actorName;
    private Long targetDoctorStaffId;
    private Long assignedStaffId;
    private String title;
    private String summary;
    private Long notificationId;
}
