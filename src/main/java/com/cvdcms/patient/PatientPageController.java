package com.cvdcms.patient;

import com.cvdcms.emr.EmrRecordStatus;
import com.cvdcms.entity.Invoice;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.DocumentType;
import com.cvdcms.entity.enums.PhotoCategory;
import com.cvdcms.entity.enums.PhotoStage;
import com.cvdcms.service.ClinicalPhotoService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Controller
@RequestMapping("/reception/patients")
public class PatientPageController {
    private final PatientService service;
    private final com.cvdcms.repository.InvoiceRepository invoiceRepository;
    private final PatientOverviewService overviewService;
    @Autowired(required = false)
    private ClinicalPhotoService clinicalPhotoService;
<<<<<<< Updated upstream
=======
    @Autowired(required = false)
    private com.cvdcms.repository.UserRepository userRepository;
    @Autowired(required = false)
    private com.cvdcms.security.DoctorVisitAccess doctorVisitAccess;
>>>>>>> Stashed changes

    public PatientPageController(PatientService service,
                                 com.cvdcms.repository.InvoiceRepository invoiceRepository,
                                 PatientOverviewService overviewService) {
        this.service = service;
        this.invoiceRepository = invoiceRepository;
        this.overviewService = overviewService;
    }

    @ModelAttribute("roleNames")
    public List<String> roleNames(HttpSession session) {
        if (session != null && session.getAttribute("roleNames") instanceof List<?> sessionRoles && !sessionRoles.isEmpty()) {
            return sessionRoles.stream().map(String::valueOf).toList();
        }
        var scope = service.scope(session);
        if (scope.admin()) {
            return List.of("ADMIN");
        }
        if (scope.user() != null && scope.user().getRoles() != null) {
            List<String> userRoles = scope.user().getRoles().stream()
                    .filter(r -> Boolean.TRUE.equals(r.getIsActive()) && r.getRoleName() != null)
                    .map(r -> r.getRoleName().toUpperCase().replace("ROLE_", ""))
                    .toList();
            if (!userRoles.isEmpty()) {
                return userRoles;
            }
        }
        return List.of("LE_TAN");
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
        Page<PatientView> result = service.list(session, query, dob, status, branchId, page, size);
        long totalItems = result.getTotalElements();
        int totalPages = result.getTotalPages();
        int currentPage = result.getNumber();
        if (currentPage >= totalPages && totalPages > 0) {
            currentPage = totalPages - 1;
            result = service.list(session, query, dob, status, branchId, currentPage, size);
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
        model.addAttribute("branches", service.branches(session));
        model.addAttribute("branchId", branchId);
        model.addAttribute("query", query);
        model.addAttribute("dob", dob);
        model.addAttribute("status", status);
        boolean canManageAdministrative = canManageAdmin(session);
        boolean canViewClinical = canViewClinicalRecords(session);
        shared(model, "/reception/patients", "patients", canManageAdministrative, canViewClinical);
        return "patient-records/list";
    }

    @GetMapping("/new")
    public String create(@RequestParam(required = false) Long branchId,
                         @RequestParam(required = false) String phone,
                         Model model, HttpSession session) {
        model.addAttribute("branches", service.branches(session));
        model.addAttribute("patient", null);
        model.addAttribute("prefillBranchId", branchId);
        model.addAttribute("prefillPhone", phone != null && phone.matches("0[0-9]{9,10}") ? phone : "");
        return "reception/patients/form";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model, HttpSession session) {
        model.addAttribute("patient", service.get(session, id));
        model.addAttribute("branches", service.branches(session));
        return "reception/patients/form";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id,
                         @RequestParam(required = false) String tab,
                         Model model, HttpSession session) {
        PatientView patient = service.get(session, id);
        model.addAttribute("patient", patient);
        model.addAttribute("patientRouteId", String.valueOf(id));

        List<ReceptionVisitView> visitList = service.visits(session, id);
        model.addAttribute("visits", visitList);

        // Find active visit if any
        ReceptionVisitView activeVisit = visitList.stream()
                .filter(v -> "WAITING".equalsIgnoreCase(v.status()) ||
                             "IN_EXAM".equalsIgnoreCase(v.status()) ||
                             "WAITING_PARACLINICAL".equalsIgnoreCase(v.status()) ||
                             "WAITING_RESULT".equalsIgnoreCase(v.status()) ||
                             "IN_TREATMENT".equalsIgnoreCase(v.status()) ||
                             "WAITING_PAYMENT".equalsIgnoreCase(v.status()))
                .findFirst().orElse(null);
        model.addAttribute("activeVisit", activeVisit);

        List<DoctorRecordService.PhotoView> photoList = service.photos(session, id);
        model.addAttribute("photos", photoList);

        model.addAttribute("plans", List.of());
        model.addAttribute("documents", service.documents(session, id));

        List<DoctorRecordService.EmrView> emrList = service.emrRecords(session, id);
        model.addAttribute("emrRecords", emrList);

        // EMR KPI counts
        long emrTotal = emrList.size();
        long emrDraft = emrList.stream().filter(e -> e.status() == EmrRecordStatus.DRAFT || e.status() == EmrRecordStatus.AMENDMENT_DRAFT).count();
        long emrPending = emrList.stream().filter(e -> e.status() == EmrRecordStatus.DOCTOR_SIGNATURE_PENDING || e.status() == EmrRecordStatus.PATIENT_SIGNATURE_PENDING || e.status() == EmrRecordStatus.READY_TO_LOCK).count();
        long emrSigned = emrList.stream().filter(e -> e.status() == EmrRecordStatus.SIGNED).count();
        model.addAttribute("emrTotal", emrTotal);
        model.addAttribute("emrDraft", emrDraft);
        model.addAttribute("emrPending", emrPending);
        model.addAttribute("emrSigned", emrSigned);

        // Treatment Timeline & Audit logs
        model.addAttribute("treatmentTimeline", service.treatmentTimeline(session, id));
        model.addAttribute("auditLogs", service.auditLogs(session, id));

        // Invoices and financial summary
        List<Invoice> invoiceList = invoiceRepository.findByPatientProfile_ProfileIdOrderByIssuedAtDesc(id);
        model.addAttribute("invoices", invoiceList);

        BigDecimal totalIncurred = BigDecimal.ZERO;
        BigDecimal totalPaid = BigDecimal.ZERO;
        BigDecimal totalDebt = BigDecimal.ZERO;
        for (Invoice inv : invoiceList) {
            if (inv.getStatus() != com.cvdcms.entity.enums.InvoiceStatus.CANCELLED) {
                if (inv.getTotalAmount() != null) totalIncurred = totalIncurred.add(inv.getTotalAmount());
                if (inv.getPaidAmount() != null) totalPaid = totalPaid.add(inv.getPaidAmount());
                if (inv.getRemainingDebt() != null) totalDebt = totalDebt.add(inv.getRemainingDebt());
            }
        }
        model.addAttribute("totalIncurred", totalIncurred);
        model.addAttribute("totalPaid", totalPaid);
        model.addAttribute("totalDebt", totalDebt);

        // Bổ sung các model dữ liệu hợp nhất
        Object principal = session == null ? null : session.getAttribute("loggedInUser");
        Long currentUserId = null;
        if (principal instanceof com.cvdcms.entity.User u) {
            currentUserId = u.getUserId();
        }
        model.addAttribute("appointments", overviewService.getPatientAppointments(id));
        model.addAttribute("careData", overviewService.getPatientCareHistory(id, patient.branchId(), currentUserId));
        model.addAttribute("prescriptions", overviewService.getPatientPrescriptions(id));
        model.addAttribute("laboOrders", overviewService.getPatientLaboOrders(id));
        model.addAttribute("performedServices", overviewService.getPatientPerformedServices(id));
        model.addAttribute("diagnoses", overviewService.getPatientDiagnoses(id));
        model.addAttribute("treatmentPlans", overviewService.getPatientTreatmentPlans(id));
        boolean isDoctor = checkDoctorRole(session);
        model.addAttribute("canPerformClinical", isDoctor);
        model.addAttribute("currentTab", tab != null ? tab : "info");

        // Billing permission: only true for accounting/cashier roles
        boolean canManageBilling = false;
        if (principal instanceof com.cvdcms.entity.User user && user.getRoles() != null) {
            canManageBilling = user.getRoles().stream()
                    .anyMatch(r -> "KE_TOAN".equalsIgnoreCase(r.getRoleName()) ||
                                   "ROLE_KE_TOAN".equalsIgnoreCase(r.getRoleName()) ||
                                   "ADMIN".equalsIgnoreCase(r.getRoleName()) ||
                                   "ROLE_ADMIN".equalsIgnoreCase(r.getRoleName()) ||
                                   "THU_NGAN".equalsIgnoreCase(r.getRoleName()) ||
                                   "ROLE_THU_NGAN".equalsIgnoreCase(r.getRoleName()));
        }
        model.addAttribute("canManageBilling", canManageBilling);
        boolean canManageAdministrative = canManageAdmin(session);
        boolean canViewClinical = canViewClinicalRecords(session);
        shared(model, "/reception/patients", "patients", canManageAdministrative, canViewClinical);
        return "patient-records/detail";
    }

    @PostMapping("/{id}/health-notes")
    public String updateHealthNotes(HttpSession session, @PathVariable Long id,
                                    @RequestParam(required = false) String allergyDetails,
                                    @RequestParam(required = false) String underlyingDiseaseDetails,
                                    RedirectAttributes redirect) {
        try {
            service.updateHealthNotes(session, id, allergyDetails, underlyingDiseaseDetails);
            redirect.addFlashAttribute("documentSuccess", "Đã cập nhật thông tin dị ứng và bệnh nền thành công.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("documentError", "Không thể cập nhật: " + ex.getMessage());
        }
        return "redirect:/reception/patients/" + id;
    }

    @PostMapping("/{id}/change-code")
    public String changeCode(HttpSession session, @PathVariable Long id,
                             @RequestParam String newCode,
                             RedirectAttributes redirect) {
        try {
            service.updatePatientCode(session, id, newCode);
            redirect.addFlashAttribute("documentSuccess", "Đã đổi mã bệnh nhân thành công.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("documentError", "Không thể đổi mã: " + ex.getMessage());
        }
        return "redirect:/reception/patients/" + id;
    }

    @PostMapping("/{id}/merge")
    public String mergeRecords(HttpSession session, @PathVariable Long id,
                               @RequestParam(required = false) String targetCode,
                               RedirectAttributes redirect) {
        redirect.addFlashAttribute("documentSuccess", "Đã ghi nhận yêu cầu gộp hồ sơ.");
        return "redirect:/reception/patients/" + id;
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

    @PostMapping("/{id}/documents/{documentId}/type")
    public String updateDocumentType(HttpSession session, @PathVariable Long id,
                                     @PathVariable Long documentId,
                                     @RequestParam DocumentType newType,
                                     RedirectAttributes redirect) {
        try {
            service.updateDocumentType(session, id, documentId, newType);
            redirect.addFlashAttribute("documentSuccess", "Đã cập nhật loại giấy tờ.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("documentError", "Lỗi: " + ex.getMessage());
        }
        return "redirect:/reception/patients/" + id;
    }

    @PostMapping("/{id}/documents/{documentId}/delete")
    public String deleteDocument(HttpSession session, @PathVariable Long id,
                                 @PathVariable Long documentId,
                                 RedirectAttributes redirect) {
        try {
            service.deleteDocument(session, id, documentId);
            redirect.addFlashAttribute("documentSuccess", "Đã xóa bản scan giấy tờ.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("documentError", "Lỗi: " + ex.getMessage());
        }
        return "redirect:/reception/patients/" + id;
    }

    @PostMapping("/{id}/photos")
    public String uploadPhoto(HttpSession session, @PathVariable Long id,
                              @RequestParam(required = false) Long visitId,
                              @RequestParam(defaultValue = "BEFORE") String stage,
                              @RequestParam(defaultValue = "INTRAORAL") String category,
                              @RequestParam(required = false) String caption,
                              @RequestParam("file") MultipartFile file,
                              RedirectAttributes redirect) {
        try {
            if (clinicalPhotoService != null && !file.isEmpty()) {
                PhotoStage pStage = PhotoStage.valueOf(stage.toUpperCase());
                PhotoCategory pCat = PhotoCategory.valueOf(category.toUpperCase());
                clinicalPhotoService.uploadPhoto(id, visitId, pStage, pCat, caption, file);
                redirect.addFlashAttribute("documentSuccess", "Đã tải lên ảnh lâm sàng mới thành công.");
            }
        } catch (Exception ex) {
            redirect.addFlashAttribute("documentError", "Không thể tải ảnh: " + ex.getMessage());
        }
        return "redirect:/reception/patients/" + id + "#photos";
    }

    @PostMapping("/{id}/photos/{photoId}/edit")
    public String editPhoto(HttpSession session, @PathVariable Long id,
                            @PathVariable Long photoId,
                            @RequestParam(required = false) String caption,
                            @RequestParam(required = false) String stage,
                            @RequestParam(required = false) String category,
                            RedirectAttributes redirect) {
        service.get(session, id);
        try {
            if (clinicalPhotoService != null) {
                var existing = clinicalPhotoService.getPhotosByProfile(id).stream()
                        .anyMatch(p -> photoId.equals(p.getPhotoId()));
                if (!existing) {
                    throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Không tìm thấy ảnh thuộc hồ sơ này.");
                }
                PhotoStage pStage = stage != null ? PhotoStage.valueOf(stage.toUpperCase()) : null;
                PhotoCategory pCat = category != null ? PhotoCategory.valueOf(category.toUpperCase()) : null;
                clinicalPhotoService.updatePhoto(photoId, caption, pStage, pCat);
                redirect.addFlashAttribute("documentSuccess", "Đã cập nhật thông tin ảnh lâm sàng.");
            }
        } catch (Exception ex) {
            redirect.addFlashAttribute("documentError", "Lỗi: " + ex.getMessage());
        }
        return "redirect:/reception/patients/" + id + "#photos";
    }

    @PostMapping("/{id}/photos/{photoId}/delete")
    public String deletePhoto(HttpSession session, @PathVariable Long id,
                              @PathVariable Long photoId,
                              RedirectAttributes redirect) {
        service.get(session, id);
        try {
            if (clinicalPhotoService != null) {
                var existing = clinicalPhotoService.getPhotosByProfile(id).stream()
                        .anyMatch(p -> photoId.equals(p.getPhotoId()));
                if (!existing) {
                    throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Không tìm thấy ảnh thuộc hồ sơ này.");
                }
                clinicalPhotoService.deletePhoto(photoId);
                redirect.addFlashAttribute("documentSuccess", "Đã xóa ảnh lâm sàng thành công.");
            }
        } catch (Exception ex) {
            redirect.addFlashAttribute("documentError", "Lỗi: " + ex.getMessage());
        }
        return "redirect:/reception/patients/" + id + "#photos";
    }

    @GetMapping("/{id}/visits/{visitId}")
    public String visit(@PathVariable Long id, @PathVariable Long visitId, Model model,
                        HttpSession session) {
        var detail = service.visit(session, id, visitId);
        PatientView patient = service.get(session, id);
        model.addAttribute("patient", patient);
        model.addAttribute("patientRouteId", String.valueOf(id));
        model.addAttribute("detail", detail);
        model.addAttribute("visit", detail.visit());
        model.addAttribute("followUps", detail.followUps() != null ? detail.followUps() : List.of());
        model.addAttribute("treatmentPlans", overviewService.getPatientTreatmentPlans(id));
        model.addAttribute("canPerformClinical", canPerformClinicalOnVisit(session, visitId));
        model.addAttribute("canManageEmr", service.scope(session).admin() || checkDoctorRole(session));
        shared(model, "/reception/patients", "patients", canManageAdmin(session), canViewClinicalRecords(session));
        return "patient-records/visit";
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

    @ExceptionHandler(ResponseStatusException.class)
    public String handleStatusException(ResponseStatusException ex, RedirectAttributes redirect) {
        if (ex.getStatusCode().value() == 401) {
            return "redirect:/login";
        }
        redirect.addFlashAttribute("documentError", ex.getReason() != null ? ex.getReason() : "Không thể truy cập hồ sơ này.");
        return "redirect:/reception/patients";
    }

    private boolean canManageAdmin(HttpSession session) {
        if (session == null) return false;
        var scope = service.scope(session);
        if (scope.admin()) return true;
        if (session.getAttribute("permissionCodes") instanceof List<?> perms) {
            return perms.stream().anyMatch(p -> "PATIENT_PROFILE_MANAGE".equalsIgnoreCase(String.valueOf(p)));
        }
        return false;
    }

    private boolean canViewClinicalRecords(HttpSession session) {
        if (session == null) return false;
        var scope = service.scope(session);
        if (scope.admin() || checkDoctorRole(session)) return true;
        if (session.getAttribute("permissionCodes") instanceof List<?> perms) {
            return perms.stream().anyMatch(p -> "MEDICAL_RECORD_VIEW".equalsIgnoreCase(String.valueOf(p)));
        }
        return false;
    }

    private boolean canPerformClinicalOnVisit(HttpSession session, Long visitId) {
        if (session == null) return false;
        var scope = service.scope(session);
        if (scope.admin()) return true;
        if (!checkDoctorRole(session)) return false;
        if (doctorVisitAccess == null || visitId == null) return true;
        try {
            doctorVisitAccess.require(session, visitId);
            return true;
        } catch (ResponseStatusException ex) {
            return false;
        }
    }

    private boolean checkDoctorRole(HttpSession session) {
        if (session == null) return false;
        if (session.getAttribute("roleNames") instanceof List<?> roles) {
            if (roles.stream().anyMatch(r -> "BAC_SI".equalsIgnoreCase(String.valueOf(r)) || "ROLE_BAC_SI".equalsIgnoreCase(String.valueOf(r)))) {
                return true;
            }
        }
        var scope = service.scope(session);
        if (scope.user() != null && scope.user().getRoles() != null) {
            return scope.user().getRoles().stream().anyMatch(r ->
                    Boolean.TRUE.equals(r.getIsActive()) && r.getRoleName() != null &&
                    ("BAC_SI".equalsIgnoreCase(r.getRoleName()) || "ROLE_BAC_SI".equalsIgnoreCase(r.getRoleName())));
        }
        return false;
    }

    private void shared(Model model, String basePath, String sidebarMenu,
                        boolean canManageAdministrative, boolean canViewClinical) {
        model.addAttribute("recordBasePath", basePath);
        model.addAttribute("sidebarMenu", sidebarMenu);
        model.addAttribute("canManageAdministrative", canManageAdministrative);
        model.addAttribute("canViewClinical", canViewClinical);
    }
}
