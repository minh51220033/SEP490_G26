package com.cvdcms.service;

import com.cvdcms.entity.SystemNotification;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.NotificationType;
import com.cvdcms.repository.SystemNotificationRepository;
import com.cvdcms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DoctorNotificationService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    private final SystemNotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    public record DoctorNotificationRealtimeEvent(String username, Map<String, Object> payload) {}

    @Transactional
    public void notifyDoctor(
            Long targetUserId,
            Long branchId,
            NotificationType type,
            String title,
            String message,
            String priorityLevel,
            String actionUrl) {
        if (targetUserId == null || title == null || title.isBlank()) {
            return;
        }
        userRepository.findById(targetUserId).ifPresent(user -> {
            if (!Boolean.TRUE.equals(user.getIsActive())) {
                return;
            }
            SystemNotification notif = new SystemNotification();
            notif.setUser(user);
            notif.setTitle(title.length() > 195 ? title.substring(0, 195) : title);
            notif.setMessage(message != null && !message.isBlank() ? message : title);
            notif.setNotificationType(type != null ? type : NotificationType.SYSTEM);
            notif.setIsRead(false);
            notif.setBranchId(branchId);
            notif.setPriorityLevel(priorityLevel != null ? priorityLevel : "NORMAL");
            notif.setActionUrl(actionUrl);
            SystemNotification saved = notificationRepository.save(notif);

            if (user.getUsername() != null && !user.getUsername().isBlank()) {
                eventPublisher.publishEvent(new DoctorNotificationRealtimeEvent(
                        user.getUsername().trim(),
                        toItemMap(saved)
                ));
            }
        });
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getNotifications(Long userId, String category, boolean unreadOnly, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 80));
        List<SystemNotification> raw = notificationRepository.findByUser_UserIdOrderByCreatedAtDesc(
                userId, PageRequest.of(0, 80));

        long unreadCount = notificationRepository.countByUser_UserIdAndIsReadFalse(userId);
        Map<String, Long> categoryCounts = new LinkedHashMap<>();
        categoryCounts.put("ALL", 0L);
        categoryCounts.put("HANDOVER", 0L);
        categoryCounts.put("LAB_RESULT", 0L);
        categoryCounts.put("CUSTOMER_CARE", 0L);
        categoryCounts.put("LEAVE_APPROVAL", 0L);

        List<Map<String, Object>> filtered = new ArrayList<>();
        String normCategory = category != null ? category.trim().toUpperCase(Locale.ROOT) : "ALL";

        for (SystemNotification n : raw) {
            String itemCat = resolveCategory(n);
            categoryCounts.put("ALL", categoryCounts.get("ALL") + 1L);
            if (categoryCounts.containsKey(itemCat)) {
                categoryCounts.put(itemCat, categoryCounts.get(itemCat) + 1L);
            }
            if (unreadOnly && Boolean.TRUE.equals(n.getIsRead())) {
                continue;
            }
            if (!"ALL".equals(normCategory) && !normCategory.equals(itemCat)) {
                continue;
            }
            if (filtered.size() < safeLimit) {
                filtered.add(toItemMap(n));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("unreadCount", unreadCount);
        out.put("categoryCounts", categoryCounts);
        out.put("notifications", filtered);
        return out;
    }

    @Transactional
    public Map<String, Object> markAsRead(Long notificationId, Long userId) {
        SystemNotification notif = notificationRepository.findByNotificationIdAndUser_UserId(notificationId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy thông báo."));
        if (!Boolean.TRUE.equals(notif.getIsRead())) {
            notif.setIsRead(true);
            notif.setReadAt(LocalDateTime.now());
            notificationRepository.save(notif);
        }
        long unreadCount = notificationRepository.countByUser_UserIdAndIsReadFalse(userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("unreadCount", unreadCount);
        out.put("notification", toItemMap(notif));
        return out;
    }

    @Transactional
    public Map<String, Object> markAllAsRead(Long userId) {
        int updated = notificationRepository.markAllReadByUserId(userId, LocalDateTime.now());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("updatedCount", updated);
        out.put("unreadCount", 0L);
        return out;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRealtimeDoctorNotification(DoctorNotificationRealtimeEvent event) {
        if (event == null || messagingTemplate == null || event.username() == null || event.payload() == null) {
            return;
        }
        try {
            messagingTemplate.convertAndSendToUser(
                    event.username(),
                    "/queue/doctor/notifications",
                    event.payload()
            );
        } catch (Exception ex) {
            log.debug("Không thể phát WebSocket thông báo bác sĩ: {}", ex.getMessage());
        }
    }

    private String resolveCategory(SystemNotification n) {
        NotificationType t = n.getNotificationType();
        if (t == NotificationType.LAB_RESULT) return "LAB_RESULT";
        if (t == NotificationType.CUSTOMER_CARE) return "CUSTOMER_CARE";
        if (t == NotificationType.LEAVE_APPROVAL) return "LEAVE_APPROVAL";
        if (t == NotificationType.SYSTEM) return "HANDOVER";
        return "ALL";
    }

    private Map<String, Object> toItemMap(SystemNotification n) {
        String cat = resolveCategory(n);
        String typeLabel = switch (cat) {
            case "HANDOVER" -> "Chuyển BS / Ca khám";
            case "LAB_RESULT" -> "Cận lâm sàng & X-Quang";
            case "CUSTOMER_CARE" -> "CSKH hỗ trợ";
            case "LEAVE_APPROVAL" -> "Lịch trực & Nghỉ phép";
            default -> n.getNotificationType() == NotificationType.APPOINTMENT ? "Lịch hẹn" : "Hệ thống";
        };
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("notificationId", n.getNotificationId());
        m.put("title", n.getTitle());
        m.put("message", n.getMessage());
        m.put("category", cat);
        m.put("notificationType", n.getNotificationType() != null ? n.getNotificationType().name() : "SYSTEM");
        m.put("typeLabel", typeLabel);
        m.put("priorityLevel", n.getPriorityLevel() != null ? n.getPriorityLevel() : "NORMAL");
        m.put("isRead", Boolean.TRUE.equals(n.getIsRead()));
        m.put("actionUrl", n.getActionUrl());
        m.put("branchId", n.getBranchId());
        m.put("createdAt", n.getCreatedAt() != null ? n.getCreatedAt().toString() : null);
        m.put("createdAtText", n.getCreatedAt() != null ? n.getCreatedAt().format(TIME_FMT) : "");
        m.put("relativeTime", formatRelative(n.getCreatedAt()));
        return m;
    }

    private String formatRelative(LocalDateTime dt) {
        if (dt == null) return "";
        long mins = Math.max(0, Duration.between(dt, LocalDateTime.now()).toMinutes());
        if (mins < 1) return "Vừa xong";
        if (mins < 60) return mins + " phút trước";
        long hours = mins / 60;
        if (hours < 24) return hours + " giờ trước";
        return (hours / 24) + " ngày trước";
    }
}
