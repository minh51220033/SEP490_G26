package com.cvdcms.receptionappointment;

import com.cvdcms.patient.PatientAccess;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/reception/appointments")
public class ReceptionAppointmentPageController {
    private final PatientAccess access;
    private final ReceptionAppointmentService appointments;

    public ReceptionAppointmentPageController(PatientAccess access, ReceptionAppointmentService appointments) {
        this.access = access; this.appointments = appointments;
    }
    @ModelAttribute("roleNames")
    public List<String> roles(HttpSession session) {
        var scope = access.require(session);
        return scope.admin() ? List.of("ADMIN", "LE_TAN") : List.of("LE_TAN");
    }
    @GetMapping
    public String dashboard() { return "reception/appointments/dashboard"; }
    @GetMapping("/calendar")
    public String calendar() { return "reception/appointments/calendar"; }
    @GetMapping("/new")
    public String create(Model model) {
        model.addAttribute("appointmentId", null);
        return "reception/appointments/form";
    }
    @GetMapping("/no-shows")
    public String noShows() { return "reception/appointments/no-shows"; }
    @GetMapping("/{id}")
    public String detail(HttpSession session, @PathVariable Long id, Model model) {
        appointments.detail(session, id);
        model.addAttribute("appointmentId", id);
        return "reception/appointments/detail";
    }
    @GetMapping("/{id}/edit")
    public String edit(HttpSession session, @PathVariable Long id, Model model) {
        appointments.detail(session, id);
        model.addAttribute("appointmentId", id);
        return "reception/appointments/form";
    }
}
