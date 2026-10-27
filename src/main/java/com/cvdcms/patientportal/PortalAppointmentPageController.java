package com.cvdcms.patientportal;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.ui.Model;

import java.util.List;

@Controller
@RequestMapping("/portal/appointments")
public class PortalAppointmentPageController {
    private final PortalAccess access;
    private final PortalAppointmentService appointments;

    public PortalAppointmentPageController(PortalAccess access, PortalAppointmentService appointments) {
        this.access = access; this.appointments = appointments;
    }

    @ModelAttribute("roleNames")
    public List<String> roles(HttpSession session) {
        access.requireUser(session);
        return List.of("BENH_NHAN");
    }

    @GetMapping
    public String page() { return "portal/appointments/index"; }

    @GetMapping("/new")
    public String newAppointment(Model model) {
        model.addAttribute("appointmentId", null);
        return "portal/appointments/form";
    }

    @GetMapping("/{id}")
    public String detail(HttpSession session, @PathVariable Long id, Model model) {
        appointments.detail(session, id);
        model.addAttribute("appointmentId", id);
        return "portal/appointments/detail";
    }

    @GetMapping("/{id}/change")
    public String change(HttpSession session, @PathVariable Long id, Model model) {
        appointments.detail(session, id);
        model.addAttribute("appointmentId", id);
        return "portal/appointments/form";
    }
}
