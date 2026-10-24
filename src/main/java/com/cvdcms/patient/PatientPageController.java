package com.cvdcms.patient;

import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Controller
@RequestMapping("/reception/patients")
public class PatientPageController {
    private final PatientService service;

    public PatientPageController(PatientService service) { this.service = service; }

    @ModelAttribute("roleNames")
    public List<String> roleNames(HttpSession session) {
        var scope = service.scope(session);
        return scope.admin() ? List.of("ADMIN", "LE_TAN") : List.of("LE_TAN");
    }

    @GetMapping
    public String list(@RequestParam(required = false) String query,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dob,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) Long branchId,
                       @RequestParam(defaultValue = "0") int page,
                       Model model, HttpSession session) {
        Page<PatientView> result = service.list(session, query, dob, status, branchId, page, 10);
        model.addAttribute("patients", result);
        model.addAttribute("branches", service.branches(session));
        model.addAttribute("branchId", branchId);
        model.addAttribute("query", query);
        model.addAttribute("dob", dob);
        model.addAttribute("status", status);
        return "reception/patients/list";
    }

    @GetMapping("/new")
    public String create(Model model, HttpSession session) {
        model.addAttribute("branches", service.branches(session));
        model.addAttribute("patient", null);
        return "reception/patients/form";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model, HttpSession session) {
        model.addAttribute("patient", service.get(session, id));
        model.addAttribute("branches", service.branches(session));
        return "reception/patients/form";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model, HttpSession session) {
        model.addAttribute("patient", service.get(session, id));
        model.addAttribute("visits", service.visits(session, id));
        return "reception/patients/detail";
    }

    @GetMapping("/{id}/visits/{visitId}")
    public String visit(@PathVariable Long id, @PathVariable Long visitId, Model model,
                        HttpSession session) {
        model.addAttribute("patient", service.get(session, id));
        model.addAttribute("detail", service.visit(session, id, visitId));
        return "reception/patients/visit";
    }
}
