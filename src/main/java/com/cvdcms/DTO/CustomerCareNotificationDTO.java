package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * DTO đại diện cho một thông báo CSKH bền vững lưu trong bảng {@code system_notifications},
 * hiển thị tại Trung tâm thông báo (Notification Center) trên trang {@code /customer-care}.
 *
 * <p><b>Chức năng:</b> Mang tiêu đề, nội dung, siêu dữ liệu công việc ({@code taskId}, {@code taskCode},
 * {@code patientName}, {@code dueDate}, {@code taskStatus}, {@code eventType}), trạng thái đã đọc
 * ({@code isRead}, {@code readAt}), mức độ ưu tiên và đường dẫn điều hướng {@code actionUrl}.</p>
 * <p><b>Được trả về bởi:</b> {@link com.cvdcms.service.CustomerCareNotificationService} và
 * {@code GET /api/customer-care/notifications}, {@code POST /api/customer-care/notifications/{id}/read}.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerCareNotificationDTO {
    private Long notificationId;
    private String title;
    private String message;
    private String notificationType;
    private Long taskId;
    private String taskCode;
    private String patientName;
    private String patientCode;
    private String dueDate;
    private String taskStatus;
    private String eventType;
    private Long branchId;
    private Boolean isRead;
    private LocalDateTime createdAt;
    private LocalDateTime readAt;
    private String priorityLevel;
    private String actionUrl;
}
