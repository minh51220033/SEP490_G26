package com.cvdcms.DTO.availability;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Yêu cầu kiểm tra khả dụng đa tài nguyên (Read-only).
 */
public record AvailabilityCheckRequest(
        Long branchId,
        Long serviceId,
        LocalDate appointmentDate,
        LocalTime startTime,
        Long preferredDoctorId,
        Long excludeAppointmentId,
        boolean immediateDispatch
) {
    public static AvailabilityCheckRequest forSlot(
            Long branchId,
            Long serviceId,
            LocalDate appointmentDate,
            LocalTime startTime) {
        return new AvailabilityCheckRequest(branchId, serviceId, appointmentDate, startTime, null, null, false);
    }

    public static AvailabilityCheckRequest forDoctorSlot(
            Long branchId,
            Long serviceId,
            LocalDate appointmentDate,
            LocalTime startTime,
            Long preferredDoctorId) {
        return new AvailabilityCheckRequest(branchId, serviceId, appointmentDate, startTime, preferredDoctorId, null, false);
    }
}
