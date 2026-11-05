package com.cvdcms.repository;

import com.cvdcms.entity.SystemNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SystemNotificationRepository extends JpaRepository<SystemNotification, Long> {
    List<SystemNotification> findByUser_UserIdOrderByCreatedAtDesc(Long userId);
    long countByUser_UserIdAndIsReadFalse(Long userId);
}

