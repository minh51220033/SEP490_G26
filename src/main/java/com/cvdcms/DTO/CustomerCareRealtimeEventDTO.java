package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * DTO payload cho sự kiện đẩy thời gian thực qua WebSocket STOMP trong phân hệ Chăm sóc khách hàng (CSKH).
 *
 * <p><b>Chức năng:</b> Thông báo tức thời tới trình duyệt khi có công việc CSKH mới, được phân công,
 * chuyển lên bác sĩ, bác sĩ phản hồi, hoàn thành hoặc mở lại để tự động làm mới bảng dữ liệu, KPI,
 * lịch biểu và chuông thông báo mà không cần tải lại trang.</p>
 * <p><b>Bảo mật:</b> Chỉ chứa siêu dữ liệu điều phối ({@code eventType}, {@code taskId}, {@code branchId},
 * {@code status}, người thao tác/nhận), không chứa nội dung bệnh án hay chẩn đoán nhạy cảm.</p>
 * <p><b>Được phát từ:</b> {@link com.cvdcms.service.CustomerCareNotificationService} và tiếp nhận bởi
 * {@code handleIncomingRealtimeEvent()} trong {@code customer-care.js}.</p>
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
