package com.cvdcms.event;

import java.time.LocalDateTime;

/**
 * Sự kiện nghiệp vụ hàng đợi và giải phóng slot phát sinh sau khi transaction commit thành công.
 */
public record ClinicQueueDomainEvent(
        String eventType,
        Long branchId,
        Long doctorId,
        Long visitId,
        Long appointmentId,
        String status,
        String summary,
        LocalDateTime occurredAt
) {
}
