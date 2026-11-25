package com.cvdcms.DTO.availability;

import com.cvdcms.entity.enums.AppointmentSource;
import com.cvdcms.entity.enums.PreferredSession;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Yêu cầu đặt giữ tài nguyên trong transaction (Milestone 4).
 */
public record ResourceBookingRequest(
        Long branchId,
        Long profileId,
        Long serviceId,
        LocalDate appointmentDate,
        LocalTime startTime,
        Long preferredDoctorId,
        boolean allowDoctorAutoAssign,
        Long existingAppointmentId,
        PreferredSession preferredSession,
        AppointmentSource source,
        String initialSymptoms,
        String idempotencyKey,
        boolean immediateDispatch
) {
    public static ResourceBookingRequest newBooking(
            Long branchId,
            Long profileId,
            Long serviceId,
            LocalDate appointmentDate,
            LocalTime startTime,
            Long preferredDoctorId,
            boolean allowDoctorAutoAssign,
            String idempotencyKey) {
        return new ResourceBookingRequest(
                branchId, profileId, serviceId, appointmentDate, startTime,
                preferredDoctorId, allowDoctorAutoAssign, null,
                null, AppointmentSource.ONLINE, null, idempotencyKey, false);
    }

    public static ResourceBookingRequest forExistingAppointment(
            Long existingAppointmentId,
            Long branchId,
            Long profileId,
            Long serviceId,
            LocalDate appointmentDate,
            LocalTime startTime,
            Long preferredDoctorId,
            boolean allowDoctorAutoAssign) {
        return new ResourceBookingRequest(
                branchId, profileId, serviceId, appointmentDate, startTime,
                preferredDoctorId, allowDoctorAutoAssign, existingAppointmentId,
                null, AppointmentSource.ONLINE, null, null, false);
    }
}
