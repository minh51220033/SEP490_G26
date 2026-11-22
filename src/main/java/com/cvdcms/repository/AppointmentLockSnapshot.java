package com.cvdcms.repository;

import com.cvdcms.entity.Appointment;
import com.cvdcms.entity.enums.AppointmentStatus;
import com.cvdcms.entity.enums.PreferredSession;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Projection nhẹ chỉ đọc các trường định danh, khóa ngoại và trạng thái của lịch hẹn trước khi xin khóa bi quan,
 * tránh nạp sớm entity cũ vào Hibernate First-Level Cache.
 */
public record AppointmentLockSnapshot(
        Long appointmentId,
        Long patientProfileId,
        Long branchId,
        Long doctorId,
        LocalDate appointmentDate,
        LocalTime expectedTime,
        PreferredSession preferredSession,
        AppointmentStatus status
) {
    public static AppointmentLockSnapshot from(Appointment appointment) {
        if (appointment == null) {
            return null;
        }
        return new AppointmentLockSnapshot(
                appointment.getAppointmentId(),
                appointment.getPatientProfile() != null ? appointment.getPatientProfile().getProfileId() : null,
                appointment.getBranch() != null ? appointment.getBranch().getBranchId() : null,
                appointment.getDoctor() != null ? appointment.getDoctor().getStaffId() : null,
                appointment.getAppointmentDate(),
                appointment.getExpectedTime(),
                appointment.getPreferredSession(),
                appointment.getStatus()
        );
    }

    public boolean matchesLockedState(Appointment locked) {
        if (locked == null) {
            return false;
        }
        Long lockedProfileId = locked.getPatientProfile() != null ? locked.getPatientProfile().getProfileId() : null;
        Long lockedBranchId = locked.getBranch() != null ? locked.getBranch().getBranchId() : null;
        Long lockedDoctorId = locked.getDoctor() != null ? locked.getDoctor().getStaffId() : null;
        return Objects.equals(patientProfileId, lockedProfileId)
                && Objects.equals(branchId, lockedBranchId)
                && Objects.equals(doctorId, lockedDoctorId)
                && Objects.equals(appointmentDate, locked.getAppointmentDate())
                && Objects.equals(expectedTime, locked.getExpectedTime())
                && preferredSession == locked.getPreferredSession()
                && status == locked.getStatus();
    }
}
