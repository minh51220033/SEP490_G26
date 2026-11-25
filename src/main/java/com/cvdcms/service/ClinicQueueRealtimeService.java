package com.cvdcms.service;

import com.cvdcms.event.ClinicQueueDomainEvent;
import com.cvdcms.event.PublicQueueStompEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tái sử dụng hệ thống WebSocket STOMP hiện có ({@link SimpMessagingTemplate}) để phát cập nhật
 * hàng đợi & giải phóng slot theo thời gian thực sau khi transaction commit thành công.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClinicQueueRealtimeService {

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onClinicQueueEvent(ClinicQueueDomainEvent event) {
        if (event == null || messagingTemplate == null) {
            return;
        }
        try {
            if (event.branchId() != null) {
                // Kênh công khai chỉ phát sự kiện tối giản, không chứa PII hay tên/ID bác sĩ
                PublicQueueStompEvent publicEvent = new PublicQueueStompEvent(
                        event.eventType(),
                        event.branchId(),
                        event.status(),
                        event.occurredAt()
                );
                messagingTemplate.convertAndSend("/topic/queue/branch/" + event.branchId(), publicEvent);
            }
            if (event.doctorId() != null) {
                messagingTemplate.convertAndSend("/topic/queue/doctor/" + event.doctorId(), event);
            }
        } catch (Exception ex) {
            log.warn("Lỗi phát WebSocket hàng đợi ({}): {}", event.eventType(), ex.getMessage());
        }
    }
}
