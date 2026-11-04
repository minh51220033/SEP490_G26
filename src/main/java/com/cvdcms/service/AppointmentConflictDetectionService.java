package com.cvdcms.service;

import com.cvdcms.entity.Appointment;

import java.util.List;

/**
 * Read-only conflict detection service for appointments (including FT18 re-examinations).
 * Inspects doctor assignment, doctor shift schedules, leave requests, branch working days,
 * session validity, duplicate patient bookings, and session/doctor capacity.
 */
public interface AppointmentConflictDetectionService {
    record Conflict(String code, String message, boolean blocking) { }

    List<Conflict> detectConflicts(Appointment appointment);
    List<Conflict> detectConflicts(Long appointmentId);
}

