package com.cvdcms.DTO.availability;

import java.time.LocalDateTime;

/**
 * Snapshot các mốc thời gian 3 pha của dịch vụ theo khoảng nửa mở [start, end).
 */
public record ServiceTimeWindow(
        int prepMinutes,
        int procedureMinutes,
        int turnoverMinutes,
        int totalMinutes,
        boolean roomExclusive,
        boolean requiresChair,
        boolean doctorRequiredInPrep,
        boolean doctorRequiredInTurnover,
        LocalDateTime t0,
        LocalDateTime t1,
        LocalDateTime t2,
        LocalDateTime t3,
        LocalDateTime doctorStart,
        LocalDateTime doctorEnd
) {}
