package com.cvdcms.event;

import java.time.LocalDateTime;

/**
 * Payload STOMP công khai phát lên /topic/queue/branch/{branchId}.
 * Tuyệt đối không chứa PII bệnh nhân, tên bác sĩ, doctorId, visitId hay appointmentId.
 */
public record PublicQueueStompEvent(
        String eventType,
        Long branchId,
        String status,
        LocalDateTime occurredAt
) {
}
