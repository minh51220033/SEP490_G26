package com.cvdcms.event;

import com.cvdcms.DTO.CustomerCareRealtimeEventDTO;

import java.util.List;

/**
 * Tập hợp các Domain Event nội bộ của phân hệ Chăm sóc khách hàng (CSKH).
 *
 * <p><b>Chức năng:</b> Kết nối lỏng (decoupling) giữa các phân hệ khám bệnh, kê đơn, lịch hẹn, thanh toán
 * với {@link com.cvdcms.service.CustomerCareAutomationService} và {@link com.cvdcms.service.CustomerCareNotificationService}.
 * Các sự kiện đều được lắng nghe ở pha {@code TransactionPhase.AFTER_COMMIT} để đảm bảo dữ liệu gốc đã được ghi chắc chắn.</p>
 */
public final class CustomerCareDomainEvents {

    private CustomerCareDomainEvents() {
    }

    /** Sự kiện phát ra khi một lượt khám hoàn thành ({@code COMPLETED}) -> tự động tạo task hỏi thăm sau điều trị. */
    public record VisitCompletedCareEvent(Long visitId) {
    }

    /** Sự kiện phát ra khi một đơn thuốc mới được tạo -> tự động tạo task nhắc dùng thuốc. */
    public record PrescriptionCreatedCareEvent(Long prescriptionId) {
    }

    /** Sự kiện phát ra khi một lịch hẹn được tạo mới hoặc cập nhật -> tự động tạo task nhắc lịch hẹn. */
    public record AppointmentUpsertedCareEvent(Long appointmentId) {
    }

    /** Sự kiện phát ra khi bệnh nhân lỡ hẹn ({@code NO_SHOW}) -> tự động tạo task gọi lại đặt lịch mới. */
    public record AppointmentNoShowCareEvent(Long appointmentId) {
    }

    /** Sự kiện phát ra khi ghi nhận thanh toán đợt trả góp -> tự động đồng bộ/đóng task nhắc thanh toán. */
    public record InstallmentPaymentRecordedCareEvent(Long planId) {
    }

    /**
     * Sự kiện nội bộ mang payload WebSocket STOMP để {@link com.cvdcms.service.CustomerCareNotificationService#onRealtimeBroadcast}
     * phát tới topic chi nhánh/admin và hàng đợi riêng của từng người dùng sau khi transaction commit.
     */
    public record CustomerCareRealtimeBroadcastEvent(
            CustomerCareRealtimeEventDTO payload,
            List<Long> targetUserIds,
            boolean broadcastToBranchTopic) {
    }
}
