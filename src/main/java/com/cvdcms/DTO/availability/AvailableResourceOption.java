package com.cvdcms.DTO.availability;

import java.util.List;

/**
 * Một tổ hợp tài nguyên khả dụng (Bác sĩ + Ca + Phòng + Ghế + Thiết bị) cho yêu cầu đặt lịch.
 */
public record AvailableResourceOption(
        Long doctorId,
        String doctorCode,
        String doctorName,
        String specialty,
        Long shiftId,
        String shiftName,
        Long roomId,
        String roomCode,
        String roomName,
        String floorLabel,
        Long chairId,
        String chairCode,
        String chairName,
        List<Long> equipmentIds,
        List<String> equipmentCodes,
        ServiceTimeWindow timeWindow
) {}
