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
import java.util.List;
import java.util.Optional;

@Repository
public interface SystemNotificationRepository extends JpaRepository<SystemNotification, Long> {
    List<SystemNotification> findByUser_UserIdOrderByCreatedAtDesc(Long userId);
    long countByUser_UserIdAndIsReadFalse(Long userId);

    List<SystemNotification> findByUser_UserIdAndNotificationTypeOrderByCreatedAtDesc(
            Long userId, NotificationType notificationType, Pageable pageable);

    long countByUser_UserIdAndNotificationTypeAndIsReadFalse(
            Long userId, NotificationType notificationType);

    Optional<SystemNotification> findByNotificationIdAndUser_UserId(Long notificationId, Long userId);

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

