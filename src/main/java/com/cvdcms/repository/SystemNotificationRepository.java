package com.cvdcms.repository;

import com.cvdcms.entity.SystemNotification;
import com.cvdcms.entity.enums.NotificationType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository truy vấn và cập nhật bảng thông báo hệ thống ({@code system_notifications}).
 *
 * <p><b>Chức năng:</b> Lấy danh sách thông báo của từng người dùng (toàn bộ hoặc lọc theo {@link NotificationType} như {@code CUSTOMER_CARE}),
 * đếm số thông báo chưa đọc và đánh dấu đã đọc đơn lẻ hoặc hàng loạt.</p>
 * <p><b>Được gọi từ:</b> {@link com.cvdcms.service.CustomerCareNotificationService} và các service thông báo khác trong hệ thống.</p>
 */
@Repository
public interface SystemNotificationRepository extends JpaRepository<SystemNotification, Long> {
    /**
     * Lấy toàn bộ thông báo của một người dùng theo thời gian tạo giảm dần (mới nhất trước).
     */
    List<SystemNotification> findByUser_UserIdOrderByCreatedAtDesc(Long userId);

    /**
     * Lấy danh sách thông báo có giới hạn/phân trang của một người dùng theo thời gian tạo giảm dần.
     */
    List<SystemNotification> findByUser_UserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * Lấy danh sách thông báo của người dùng thuộc một tập các loại thông báo cụ thể (phân trang).
     */
    List<SystemNotification> findByUser_UserIdAndNotificationTypeInOrderByCreatedAtDesc(
            Long userId, Collection<NotificationType> types, Pageable pageable);

    /**
     * Đếm tổng số thông báo chưa đọc của một người dùng.
     */
    long countByUser_UserIdAndIsReadFalse(Long userId);

    /**
     * Lấy danh sách thông báo của người dùng theo đúng một loại thông báo (ví dụ {@code NotificationType.CUSTOMER_CARE}).
     */
    List<SystemNotification> findByUser_UserIdAndNotificationTypeOrderByCreatedAtDesc(
            Long userId, NotificationType notificationType, Pageable pageable);

    /**
     * Đếm số thông báo chưa đọc của người dùng theo một loại thông báo cụ thể.
     */
    long countByUser_UserIdAndNotificationTypeAndIsReadFalse(
            Long userId, NotificationType notificationType);

    /**
     * Tìm một thông báo theo ID kèm kiểm tra quyền sở hữu của đúng người dùng {@code userId}.
     */
    Optional<SystemNotification> findByNotificationIdAndUser_UserId(Long notificationId, Long userId);

    /**
     * Đánh dấu tất cả thông báo chưa đọc của một người dùng là đã đọc cùng thời điểm {@code readAt}.
     *
     * @return Số bản ghi thông báo đã được cập nhật
     */
    @Modifying
    @Query("""
        UPDATE SystemNotification n
        SET n.isRead = true, n.readAt = :readAt
        WHERE n.user.userId = :userId
          AND n.isRead = false
        """)
    int markAllReadByUserId(
            @Param("userId") Long userId,
            @Param("readAt") LocalDateTime readAt);

    /**
     * Đánh dấu tất cả thông báo chưa đọc thuộc một loại cụ thể (ví dụ CSKH) của người dùng là đã đọc.
     *
     * @return Số bản ghi thông báo đã được cập nhật
     */
    @Modifying
    @Query("""
        UPDATE SystemNotification n
        SET n.isRead = true, n.readAt = :readAt
        WHERE n.user.userId = :userId
          AND n.notificationType = :notificationType
          AND n.isRead = false
        """)
    int markAllReadByUserAndType(
            @Param("userId") Long userId,
            @Param("notificationType") NotificationType notificationType,
            @Param("readAt") LocalDateTime readAt);
}

