package com.cvdcms.DTO.availability;

import java.util.List;

/**
 * Kết quả đặt giữ tài nguyên đã commit trong transaction (Milestone 4).
 */
public record ResourceBookingResult(
        Long appointmentId,
        String appointmentCode,
        Long branchId,
        Long doctorId,
        Long roomId,
        Long chairId,
        List<Long> equipmentIds,
        List<Long> reservationIds,
        ServiceTimeWindow timeWindow,
        boolean idempotentReplay
) {}
