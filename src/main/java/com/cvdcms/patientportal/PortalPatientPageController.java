package com.cvdcms.patientportal;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Controller
@RequestMapping("/portal/records")
public class PortalPatientPageController {
    private final PortalPatientService patients;
    private final PortalAccess access;

    public PortalPatientPageController(PortalPatientService patients, PortalAccess access) {
        this.patients = patients; this.access = access;
    }

    @ModelAttribute("roleNames")
    public List<String> roles(HttpSession session) {
        access.requireUser(session);
        return List.of("BENH_NHAN");
    }

    @GetMapping
    public String list(HttpSession session, Model model) {
        model.addAttribute("records", patients.list(session));
        return "portal/records/list";
    }

    @GetMapping("/new")
    public String create(HttpSession session, Model model) {
        model.addAttribute("branches", patients.branches(session));
        model.addAttribute("record", null);
        return "portal/records/form";
    }

    @GetMapping("/{id}/edit")
    public String edit(HttpSession session, @PathVariable Long id, Model model) {
        PortalProfileView record = patients.get(session, id);
        if (record.self()) throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
        model.addAttribute("record", record);
        model.addAttribute("branches", patients.branches(session));
        return "portal/records/form";
    }

    @GetMapping("/{id}")
    public String detail(HttpSession session, @PathVariable Long id, Model model) {
        model.addAttribute("record", patients.get(session, id));
        model.addAttribute("visits", patients.visits(session, id));
        return "portal/records/detail";
    }

    @GetMapping("/{id}/visits/{visitId}")
    public String visit(HttpSession session, @PathVariable Long id, @PathVariable Long visitId, Model model) {
        model.addAttribute("record", patients.get(session, id));
        model.addAttribute("detail", patients.visit(session, id, visitId));
        return "portal/records/visit";
    }

}
