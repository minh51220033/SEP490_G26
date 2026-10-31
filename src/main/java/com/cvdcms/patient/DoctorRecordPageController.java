package com.cvdcms.patient;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

@Controller
@RequestMapping("/doctor/patient-records")
public class DoctorRecordPageController {
    private final DoctorRecordService records;

    public DoctorRecordPageController(DoctorRecordService records) { this.records = records; }

    @ModelAttribute("roleNames")
    public List<String> roleNames() { return List.of("BAC_SI"); }

    @GetMapping
    public String list(HttpSession session, Model model,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dob,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "0") int page) {
        model.addAttribute("patients", records.search(session, query, dob, status, branchId, page));
        model.addAttribute("branches", records.allowedBranches(session));
        model.addAttribute("query", query);
        model.addAttribute("dob", dob);
        model.addAttribute("status", status);
        model.addAttribute("branchId", branchId);
        shared(model);
        return "patient-records/list";
    }

    @GetMapping("/{id}")
    public String detail(HttpSession session, Model model, @PathVariable Long id) {
        model.addAttribute("patient", records.get(session, id));
        model.addAttribute("visits", records.visits(session, id));
        model.addAttribute("photos", records.photos(session, id));
        model.addAttribute("plans", records.plans(session, id));
        model.addAttribute("documents", records.documents(session, id));
        model.addAttribute("emrRecords", records.emrRecords(session, id));
        shared(model);
        return "patient-records/detail";
    }

    @GetMapping("/{id}/visits/{visitId}")
    public String visit(HttpSession session, Model model, @PathVariable Long id,
            @PathVariable Long visitId) {
        model.addAttribute("patient", records.get(session, id));
        model.addAttribute("detail", records.visit(session, id, visitId));
        shared(model);
        return "patient-records/visit";
    }

    @GetMapping("/{id}/documents/{documentId}")
    public ResponseEntity<Resource> downloadDocument(HttpSession session, @PathVariable Long id,
            @PathVariable Long documentId) {
        var file = records.downloadDocument(session, id, documentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.originalName(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(file.resource());
    }

    private void shared(Model model) {
        model.addAttribute("recordBasePath", "/doctor/patient-records");
        model.addAttribute("sidebarMenu", "doctor-records");
        model.addAttribute("canManageAdministrative", false);
        model.addAttribute("canViewClinical", true);
    }
}
