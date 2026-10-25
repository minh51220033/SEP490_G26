package com.cvdcms.patient;

import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.cvdcms.entity.enums.DocumentType;

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
        shared(model, "/reception/patients", "patients", true, false);
        return "patient-records/list";
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
        model.addAttribute("documents", service.documents(session, id));
        model.addAttribute("photos", List.of());
        model.addAttribute("plans", List.of());
        shared(model, "/reception/patients", "patients", true, false);
        return "patient-records/detail";
    }

    @GetMapping("/{id}/visits/{visitId}")
    public String visit(@PathVariable Long id, @PathVariable Long visitId, Model model,
                        HttpSession session) {
        model.addAttribute("patient", service.get(session, id));
        model.addAttribute("detail", service.visit(session, id, visitId));
        shared(model, "/reception/patients", "patients", true, false);
        return "patient-records/visit";
    }

    @PostMapping("/{id}/documents")
    public String uploadDocument(@PathVariable Long id, @RequestParam DocumentType documentType,
            @RequestParam("file") MultipartFile file, HttpSession session,
            RedirectAttributes redirect) {
        try {
            service.uploadDocument(session, id, documentType, file);
            redirect.addFlashAttribute("documentSuccess", "Đã tải lên bản scan giấy tờ.");
        } catch (ResponseStatusException ex) {
            redirect.addFlashAttribute("documentError", ex.getReason());
        }
        return "redirect:/reception/patients/" + id;
    }

    @GetMapping("/{id}/documents/{documentId}")
    public ResponseEntity<Resource> downloadDocument(@PathVariable Long id,
            @PathVariable Long documentId, HttpSession session) {
        var file = service.downloadDocument(session, id, documentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.originalName(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(file.resource());
    }

    private void shared(Model model, String basePath, String sidebarMenu,
                        boolean canManageAdministrative, boolean canViewClinical) {
        model.addAttribute("recordBasePath", basePath);
        model.addAttribute("sidebarMenu", sidebarMenu);
        model.addAttribute("canManageAdministrative", canManageAdministrative);
        model.addAttribute("canViewClinical", canViewClinical);
    }
}
