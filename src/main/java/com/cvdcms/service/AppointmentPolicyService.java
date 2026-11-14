package com.cvdcms.service;

import com.cvdcms.entity.Appointment;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.enums.AppointmentActorType;
import com.cvdcms.entity.enums.PreferredSession;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

public interface AppointmentPolicyService {
    ZoneId CLINIC_ZONE = ZoneId.of("Asia/Bangkok");

    record DoctorOption(Long id, String name, String specialty, int remaining) { }
    record Availability(boolean available, String reason, int remainingBranch,
                        List<DoctorOption> doctors) { }
    record SessionLoadSummary(PreferredSession session, String sessionName, int bookedCount,
                              int capacity, int remaining, String loadStatus, String recommendation) { }
    record SessionRange(LocalTime start, LocalTime end, boolean active) { }
    record SessionWindowInfo(PreferredSession session, String sessionName, LocalTime startTime,
                             LocalTime endTime, String label, boolean active) { }

    Availability availability(Branch branch, LocalDate date, PreferredSession session,
                              Long excludeAppointmentId, AppointmentActorType actorType);

    List<SessionLoadSummary> dayLoadOverview(Branch branch, LocalDate date, AppointmentActorType actorType);

    void ensureBookable(Branch branch, PatientProfile profile, LocalDate date,
                        PreferredSession session, Long doctorId, Long excludeAppointmentId,
                        AppointmentActorType actorType);

    List<DoctorOption> doctorsForDate(Branch branch, LocalDate date, AppointmentActorType actorType);

    boolean doctorAvailable(Branch branch, LocalDate date, PreferredSession session, Long doctorId);

    boolean sessionEnded(Branch branch, LocalDate date, PreferredSession session, LocalDateTime now);

    void ensureModificationCutoff(Appointment appointment, AppointmentActorType actorType);

    SessionRange sessionRange(Branch branch, PreferredSession session);

    List<SessionWindowInfo> sessionWindows(Branch branch);
}
