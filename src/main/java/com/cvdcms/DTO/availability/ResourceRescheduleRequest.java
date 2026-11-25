package com.cvdcms.DTO.availability;

import com.cvdcms.entity.enums.AppointmentStatus;
import com.cvdcms.entity.enums.PreferredSession;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Yêu cầu dời lịch / đổi bác sĩ / đổi dịch vụ trong cùng transaction (Milestone 5).
 */
public record ResourceRescheduleRequest(
        Long appointmentId,
        Long expectedBranchId,
        Long newServiceId,
        LocalDate newDate,
        LocalTime newStartTime,
        PreferredSession newSession,
        Long targetDoctorId,
        boolean allowDoctorAutoAssign,
        String releaseReason,
        AppointmentStatus targetStatus,
        boolean incrementRescheduleCount,
        boolean immediateDispatch
) {
    public static ResourceRescheduleRequest reschedule(
            Long appointmentId,
            Long expectedBranchId,
            Long newServiceId,
            LocalDate newDate,
            LocalTime newStartTime,
            PreferredSession newSession,
            Long targetDoctorId,
            boolean allowDoctorAutoAssign,
            String releaseReason,
            AppointmentStatus targetStatus,
            boolean incrementRescheduleCount) {
        return new ResourceRescheduleRequest(
                appointmentId,
                expectedBranchId,
                newServiceId,
                newDate,
                newStartTime,
                newSession,
                targetDoctorId,
                allowDoctorAutoAssign,
                releaseReason,
                targetStatus,
                incrementRescheduleCount,
                false
        );
    }
}
