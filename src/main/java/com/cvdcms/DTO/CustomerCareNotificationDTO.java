package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * DTO thông báo bền vững của Notification Center trên trang /customer-care.
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
    private Long branchId;
    private Boolean isRead;
    private LocalDateTime createdAt;
    private LocalDateTime readAt;
    private String priorityLevel;
    private String actionUrl;
}
