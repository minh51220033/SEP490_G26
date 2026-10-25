package com.cvdcms.patientportal;

import com.cvdcms.entity.enums.PreferredSession;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/portal/appointments/api")
public class PortalAppointmentRestController {
    private final PortalAppointmentService appointments;
    private final PortalPatientService patients;

    public PortalAppointmentRestController(PortalAppointmentService appointments, PortalPatientService patients) {
        this.appointments = appointments; this.patients = patients;
    }

    @GetMapping("/options")
    public Options options(HttpSession session) {
        return new Options(patients.list(session).stream()
                .map(p -> new ProfileOption(p.id(), p.code(), p.fullName())).toList(),
                appointments.branches(session).stream()
                        .map(b -> new BranchOption(b.getBranchId(), b.getBranchName())).toList(),
                appointments.bookableServices(session).stream()
                        .map(s -> new ServiceOption(s.getServiceId(), s.getServiceName())).toList());
    }

    @GetMapping("/availability")
    public PortalAppointmentService.Availability availability(HttpSession session, @RequestParam Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam PreferredSession sessionPeriod,
            @RequestParam(required = false) Long excludeAppointmentId) {
        return appointments.availability(session, branchId, date, sessionPeriod, excludeAppointmentId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PortalAppointmentService.AppointmentView book(HttpSession session,
            @Valid @RequestBody PortalAppointmentService.BookingRequest request) {
        return appointments.book(session, request);
    }

    @GetMapping
    public List<PortalAppointmentService.AppointmentView> list(HttpSession session, @RequestParam Long profileId) {
        return appointments.list(session, profileId);
    }

    @GetMapping("/{id}")
    public PortalAppointmentService.Detail detail(HttpSession session, @PathVariable Long id) {
        return appointments.detail(session, id);
    }

    @PutMapping("/{id}/reschedule")
    public PortalAppointmentService.AppointmentView reschedule(HttpSession session, @PathVariable Long id,
            @Valid @RequestBody PortalAppointmentService.RescheduleRequest request) {
        return appointments.reschedule(session, id, request);
    }

    @PostMapping("/{id}/cancel")
    public PortalAppointmentService.AppointmentView cancel(HttpSession session, @PathVariable Long id,
            @Valid @RequestBody PortalAppointmentService.CancelRequest request) {
        return appointments.cancel(session, id, request);
    }

    public record ProfileOption(Long id, String code, String name) {}
    public record BranchOption(Long id, String name) {}
    public record ServiceOption(Long id, String name) {}
    public record Options(List<ProfileOption> profiles, List<BranchOption> branches, List<ServiceOption> services) {}
}
