package com.cvdcms.receptionappointment;

import com.cvdcms.entity.enums.AppointmentStatus;
import com.cvdcms.entity.enums.PreferredSession;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/reception/appointments/api")
public class ReceptionAppointmentRestController {
    private final ReceptionAppointmentService appointments;

    public ReceptionAppointmentRestController(ReceptionAppointmentService appointments) {
        this.appointments = appointments;
    }

    @GetMapping("/options")
    public ReceptionAppointmentService.Options options(HttpSession session) {
        return appointments.options(session);
    }
    @GetMapping("/patients")
    public List<ReceptionAppointmentService.PatientOption> patients(HttpSession session,
            @RequestParam Long branchId, @RequestParam String query) {
        return appointments.searchPatients(session, branchId, query);
    }
    @GetMapping("/availability")
    public ReceptionAppointmentRules.Availability availability(HttpSession session, @RequestParam Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam PreferredSession sessionPeriod,
            @RequestParam(required = false) Long excludeAppointmentId) {
        return appointments.availability(session, branchId, date, sessionPeriod, excludeAppointmentId);
    }
    @GetMapping("/doctors")
    public List<ReceptionAppointmentRules.DoctorOption> doctors(HttpSession session, @RequestParam Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return appointments.doctors(session, branchId, date);
    }
    @GetMapping("/dashboard")
    public ReceptionAppointmentService.Dashboard dashboard(HttpSession session,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long branchId, @RequestParam(required = false) Long doctorId) {
        return appointments.dashboard(session, date, branchId, doctorId);
    }
    @GetMapping("/calendar")
    public List<ReceptionAppointmentService.AppointmentView> calendar(HttpSession session,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(required = false) Long branchId, @RequestParam(required = false) Long doctorId,
            @RequestParam(required = false) AppointmentStatus status) {
        return appointments.calendar(session, start, end, branchId, doctorId, status);
    }
    @GetMapping("/no-shows")
    public List<ReceptionAppointmentService.AppointmentView> noShows(HttpSession session,
            @RequestParam(required = false) Long branchId) {
        return appointments.noShows(session, branchId);
    }
    @GetMapping("/{id}")
    public ReceptionAppointmentService.Detail detail(HttpSession session, @PathVariable Long id) {
        return appointments.detail(session, id);
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReceptionAppointmentService.AppointmentView create(HttpSession session,
            @Valid @RequestBody ReceptionAppointmentService.CreateRequest request) {
        return appointments.create(session, request);
    }
    @PutMapping("/{id}")
    public ReceptionAppointmentService.AppointmentView update(HttpSession session, @PathVariable Long id,
            @Valid @RequestBody ReceptionAppointmentService.UpdateRequest request) {
        return appointments.update(session, id, request);
    }
    @PostMapping("/{id}/confirm")
    public ReceptionAppointmentService.AppointmentView confirm(HttpSession session, @PathVariable Long id,
            @Valid @RequestBody ReceptionAppointmentService.ConfirmRequest request) {
        return appointments.confirm(session, id, request);
    }
    @PostMapping("/{id}/cancel")
    public ReceptionAppointmentService.AppointmentView cancel(HttpSession session, @PathVariable Long id,
            @Valid @RequestBody ReceptionAppointmentService.CancelRequest request) {
        return appointments.cancel(session, id, request);
    }
}
