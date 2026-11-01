package com.cvdcms.service;

import com.cvdcms.entity.Appointment;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.enums.AppointmentActorType;
import com.cvdcms.entity.enums.PreferredSession;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

public interface AppointmentPolicyService {
    ZoneId CLINIC_ZONE = ZoneId.of("Asia/Bangkok");

    record DoctorOption(Long id, String name, String specialty, int remaining) { }
    record Availability(boolean available, String reason, int remainingBranch,
                        List<DoctorOption> doctors) { }

    Availability availability(Branch branch, LocalDate date, PreferredSession session,
                              Long excludeAppointmentId, AppointmentActorType actorType);

    void ensureBookable(Branch branch, PatientProfile profile, LocalDate date,
                        PreferredSession session, Long doctorId, Long excludeAppointmentId,
                        AppointmentActorType actorType);

    List<DoctorOption> doctorsForDate(Branch branch, LocalDate date, AppointmentActorType actorType);

    boolean doctorAvailable(Branch branch, LocalDate date, PreferredSession session, Long doctorId);

    boolean sessionEnded(Branch branch, LocalDate date, PreferredSession session, LocalDateTime now);

    void ensureModificationCutoff(Appointment appointment, AppointmentActorType actorType);
}
