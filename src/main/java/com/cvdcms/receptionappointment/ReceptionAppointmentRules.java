package com.cvdcms.receptionappointment;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.enums.AppointmentActorType;
import com.cvdcms.entity.enums.PreferredSession;
import com.cvdcms.service.AppointmentPolicyService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Compatibility adapter for the existing FT05 API. New appointment rules belong
 * to {@link AppointmentPolicyService}; this class only preserves existing DTO types.
 */
@Component
public class ReceptionAppointmentRules {
    static final ZoneId ZONE = AppointmentPolicyService.CLINIC_ZONE;

    private final AppointmentPolicyService policy;

    public ReceptionAppointmentRules(AppointmentPolicyService policy) {
        this.policy = policy;
    }

    public record DoctorOption(Long id, String name, String specialty, int remaining) { }
    public record Availability(boolean available, String reason, int remainingBranch,
                               List<DoctorOption> doctors) { }

    public Availability availability(Branch branch, LocalDate date, PreferredSession session,
                                     Long excludeId) {
        AppointmentPolicyService.Availability result = policy.availability(branch, date, session,
                excludeId, AppointmentActorType.RECEPTIONIST);
        return new Availability(result.available(), result.reason(), result.remainingBranch(),
                result.doctors().stream().map(this::doctorOption).toList());
    }

    public void ensureBookable(Branch branch, PatientProfile profile, LocalDate date,
            PreferredSession session, Long doctorId, Long excludeId) {
        policy.ensureBookable(branch, profile, date, session, doctorId, excludeId,
                AppointmentActorType.RECEPTIONIST);
    }

    public boolean doctorAvailable(Branch branch, LocalDate date, PreferredSession session,
                                   Long doctorId) {
        return policy.doctorAvailable(branch, date, session, doctorId);
    }

    public List<DoctorOption> doctorsForDate(Branch branch, LocalDate date) {
        return policy.doctorsForDate(branch, date, AppointmentActorType.RECEPTIONIST).stream()
                .map(this::doctorOption).toList();
    }

    public boolean sessionEnded(Branch branch, LocalDate date, PreferredSession session,
                                LocalDateTime now) {
        return policy.sessionEnded(branch, date, session, now);
    }

    private DoctorOption doctorOption(AppointmentPolicyService.DoctorOption option) {
        return new DoctorOption(option.id(), option.name(), option.specialty(), option.remaining());
    }
}
