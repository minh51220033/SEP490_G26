package com.cvdcms.event;

import com.cvdcms.DTO.CustomerCareRealtimeEventDTO;

import java.util.List;

/**
 * Các Domain Event nội bộ kích hoạt tự động tạo công việc CSKH & phát WebSocket sau khi commit transaction.
 */
public final class CustomerCareDomainEvents {

    private CustomerCareDomainEvents() {
    }

    public record VisitCompletedCareEvent(Long visitId) {
    }

    public record PrescriptionCreatedCareEvent(Long prescriptionId) {
    }

    public record AppointmentUpsertedCareEvent(Long appointmentId) {
    }

    public record AppointmentNoShowCareEvent(Long appointmentId) {
    }

    public record CustomerCareRealtimeBroadcastEvent(
            CustomerCareRealtimeEventDTO payload,
            List<Long> targetUserIds,
            boolean broadcastToBranchTopic) {
    }
}
