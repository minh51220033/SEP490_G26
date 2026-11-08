package com.cvdcms.patient;

import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.DocumentType;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;

@Controller
@RequestMapping("/admin/patients")
public class AdminPatientPageController {

    private final PatientOverviewService overviewService;
    private final PatientDocumentStorageService documentStorageService;

    public AdminPatientPageController(PatientOverviewService overviewService,
                                      PatientDocumentStorageService documentStorageService) {
        this.overviewService = overviewService;
        this.documentStorageService = documentStorageService;
    }

    @ModelAttribute("roleNames")
    public List<String> roleNames() {
        return List.of("ADMIN");
    }

    @GetMapping
    public String list(@RequestParam(required = false) String query,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dob,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) Long branchId,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "10") int size,
                       Model model, HttpSession session) {
        if (size != 10 && size != 25 && size != 50 && size != 100) {
            size = 10;
        }
        User adminUser = overviewService.requireAdminOrManager(session);
        var result = overviewService.searchPatients(branchId, query, dob, status, page, size);
        long totalItems = result.getTotalElements();
        int totalPages = result.getTotalPages();
        int currentPage = result.getNumber();
        if (currentPage >= totalPages && totalPages > 0) {
            currentPage = totalPages - 1;
            result = overviewService.searchPatients(branchId, query, dob, status, currentPage, size);
            totalItems = result.getTotalElements();
            totalPages = result.getTotalPages();
        }
        long toItem = Math.min(((long) (currentPage + 1) * size), totalItems);
        long fromItem = totalItems == 0 ? 0 : Math.min(((long) currentPage * size) + 1, toItem);

        model.addAttribute("patients", result);
        model.addAttribute("items", result.getContent());
        model.addAttribute("totalItems", totalItems);
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("pageSize", size);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("fromItem", fromItem);
        model.addAttribute("toItem", toItem);
        model.addAttribute("keyword", query);
        model.addAttribute("branches", overviewService.getAllActiveBranches());
        model.addAttribute("branchId", branchId);
        model.addAttribute("query", query);
        model.addAttribute("dob", dob);
        model.addAttribute("status", status);
        model.addAttribute("recordBasePath", "/admin/patients");
        model.addAttribute("activeMenu", "admin-patients");
        model.addAttribute("sidebarMenu", "admin-patients");
        model.addAttribute("isAdmin", true);
        model.addAttribute("loggedInUser", adminUser);
        return "admin/patients/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id,
                         @RequestParam(required = false) String tab,
                         Model model, HttpSession session) {
        User adminUser = overviewService.requireAdminOrManager(session);
        PatientOverviewDTO patient = overviewService.getPatientOverview(id);

        model.addAttribute("patient", patient);
        model.addAttribute("visits", overviewService.getPatientVisits(id));
        model.addAttribute("appointments", overviewService.getPatientAppointments(id));
        model.addAttribute("prescriptions", overviewService.getPatientPrescriptions(id));
        model.addAttribute("laboOrders", overviewService.getPatientLaboOrders(id));
        model.addAttribute("photos", overviewService.getPatientPhotos(id));
        model.addAttribute("invoices", overviewService.getPatientInvoices(id));
        model.addAttribute("emrRecords", overviewService.getPatientEmrRecords(id));
        model.addAttribute("documents", overviewService.getPatientDocuments(id));
        model.addAttribute("audits", overviewService.getPatientAudits(id));
        model.addAttribute("plans", overviewService.getPatientTreatmentPlans(id));
        model.addAttribute("performedServices", overviewService.getPatientPerformedServices(id));
        model.addAttribute("diagnoses", overviewService.getPatientDiagnoses(id));
        model.addAttribute("examinations", overviewService.getPatientExaminations(id));
        model.addAttribute("careData", overviewService.getPatientCareHistory(id, patient.getBranchId(), adminUser.getUserId()));

        model.addAttribute("currentTab", tab != null ? tab : "personal");
        model.addAttribute("recordBasePath", "/admin/patients");
        model.addAttribute("activeMenu", "admin-patients");
        model.addAttribute("sidebarMenu", "admin-patients");
        model.addAttribute("isAdmin", true);
        model.addAttribute("loggedInUser", adminUser);
        model.addAttribute("canManageAdministrative", true);
        model.addAttribute("canViewClinical", true);
        model.addAttribute("canPerformClinical", false); // Admin chỉ xem chuyên môn, không ký/kê đơn thay BS

        return "admin/patients/detail";
    }

    @GetMapping("/{id}/documents/{documentId}")
    public ResponseEntity<Resource> downloadDocument(@PathVariable Long id,
                                                     @PathVariable Long documentId,
                                                     HttpSession session) {
        overviewService.requireAdminOrManager(session);
        var file = documentStorageService.download(id, documentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.originalName(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(file.resource());
    }

    @PostMapping("/{id}/documents")
    public String uploadDocument(@PathVariable Long id,
                                 @RequestParam DocumentType documentType,
                                 @RequestParam("file") MultipartFile file,
                                 HttpSession session,
                                 RedirectAttributes redirect) {
        User user = overviewService.requireAdminOrManager(session);
        try {
            documentStorageService.upload(id, user, documentType, file);
            redirect.addFlashAttribute("documentSuccess", "Đã tải lên bản scan giấy tờ thành công.");
        } catch (ResponseStatusException ex) {
            redirect.addFlashAttribute("documentError", ex.getReason());
        } catch (Exception ex) {
            redirect.addFlashAttribute("documentError", "Không thể tải lên giấy tờ: " + ex.getMessage());
        }
        return "redirect:/admin/patients/" + id + "?tab=personal";
    }
}

