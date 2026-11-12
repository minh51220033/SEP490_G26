package com.cvdcms.patient;

import com.cvdcms.emr.EmrRecordStatus;
import com.cvdcms.entity.Invoice;
import com.cvdcms.entity.enums.DocumentType;
import com.cvdcms.entity.enums.PhotoCategory;
import com.cvdcms.entity.enums.PhotoStage;
import com.cvdcms.service.ClinicalPhotoService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.format.annotation.DateTimeFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@Controller
@RequestMapping("/doctor/patient-records")
public class DoctorRecordPageController {
    private final DoctorRecordService records;
    private final com.cvdcms.repository.InvoiceRepository invoiceRepository;
    private final PatientOverviewService overviewService;
    @Autowired(required = false)
    private ClinicalPhotoService clinicalPhotoService;
    @Autowired(required = false)
    private com.cvdcms.repository.UserRepository userRepository;

    public DoctorRecordPageController(DoctorRecordService records,
                                      com.cvdcms.repository.InvoiceRepository invoiceRepository,
                                      PatientOverviewService overviewService) {
        this.records = records;
        this.invoiceRepository = invoiceRepository;
        this.overviewService = overviewService;
    }

    @ModelAttribute("roleNames")
    public List<String> roleNames(HttpSession session) {
        if (session != null && session.getAttribute("roleNames") instanceof List<?> sessionRoles && !sessionRoles.isEmpty()) {
            return sessionRoles.stream().map(String::valueOf).toList();
        }
        return records.isAdmin(session) ? List.of("ADMIN") : List.of("BAC_SI");
    }

    @GetMapping
    public String list(HttpSession session, Model model,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dob,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        if (size != 10 && size != 25 && size != 50 && size != 100) {
            size = 10;
        }
        Page<PatientView> resultPage = records.search(session, query, dob, status, branchId, page, size);
        long totalItems = resultPage.getTotalElements();
        int totalPages = resultPage.getTotalPages();
        int currentPage = resultPage.getNumber();
        if (currentPage >= totalPages && totalPages > 0) {
            currentPage = totalPages - 1;
            resultPage = records.search(session, query, dob, status, branchId, currentPage, size);
            totalItems = resultPage.getTotalElements();
            totalPages = resultPage.getTotalPages();
        }
        long toItem = Math.min(((long) (currentPage + 1) * size), totalItems);
        long fromItem = totalItems == 0 ? 0 : Math.min(((long) currentPage * size) + 1, toItem);

        model.addAttribute("patients", resultPage);
        model.addAttribute("items", resultPage.getContent());
        model.addAttribute("totalItems", totalItems);
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("pageSize", size);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("fromItem", fromItem);
        model.addAttribute("toItem", toItem);
        model.addAttribute("keyword", query);
        model.addAttribute("branches", records.allowedBranches(session));
        model.addAttribute("query", query);
        model.addAttribute("dob", dob);
        model.addAttribute("status", status);
        model.addAttribute("branchId", branchId);
        shared(model, session);
        return "patient-records/list";
    }

    @GetMapping("/{id}")
    public String detail(HttpSession session, Model model, @PathVariable Long id,
                         @RequestParam(required = false) String tab) {
        PatientView patient = records.get(session, id);
        model.addAttribute("patient", patient);

        List<VisitView> visitList = records.visits(session, id);
        model.addAttribute("visits", visitList);

        // Find active visit if any
        VisitView activeVisit = visitList.stream()
                .filter(v -> "WAITING".equalsIgnoreCase(v.status()) ||
                             "IN_EXAM".equalsIgnoreCase(v.status()) ||
                             "WAITING_PARACLINICAL".equalsIgnoreCase(v.status()) ||
                             "WAITING_RESULT".equalsIgnoreCase(v.status()) ||
                             "IN_TREATMENT".equalsIgnoreCase(v.status()) ||
                             "WAITING_PAYMENT".equalsIgnoreCase(v.status()))
                .findFirst().orElse(null);
        model.addAttribute("activeVisit", activeVisit);

        List<DoctorRecordService.PhotoView> photoList = records.photos(session, id);
        model.addAttribute("photos", photoList);

        model.addAttribute("plans", records.plans(session, id));
        model.addAttribute("documents", records.documents(session, id));

        List<DoctorRecordService.EmrView> emrList = records.emrRecords(session, id);
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
        model.addAttribute("treatmentTimeline", records.treatmentTimeline(session, id));
        model.addAttribute("auditLogs", records.auditLogs(session, id));

        // Permissions & Billing access check
        boolean admin = records.isAdmin(session);
        boolean isDoctor = records.isDoctor(session);
        Long currentUserId = records.extractUserId(session);
        boolean isAccountantOrCashier = false;
        Object principal = session == null ? null : session.getAttribute("loggedInUser");
        if (principal instanceof com.cvdcms.entity.User u && u.getRoles() != null) {
            isAccountantOrCashier = u.getRoles().stream().anyMatch(r ->
                    "KE_TOAN".equalsIgnoreCase(r.getRoleName()) || "ROLE_KE_TOAN".equalsIgnoreCase(r.getRoleName()) ||
                    "THU_NGAN".equalsIgnoreCase(r.getRoleName()) || "ROLE_THU_NGAN".equalsIgnoreCase(r.getRoleName()) ||
                    "ADMIN".equalsIgnoreCase(r.getRoleName()) || "ROLE_ADMIN".equalsIgnoreCase(r.getRoleName()));
        }
        boolean hasFinancePerm = false;
        if (userRepository != null && currentUserId != null) {
            hasFinancePerm = userRepository.hasActiveFinanceRole(currentUserId)
                    || userRepository.hasActivePermission(currentUserId, "INVOICE_VIEW")
                    || userRepository.hasActivePermission(currentUserId, "INVOICE_CREATE")
                    || userRepository.hasActivePermission(currentUserId, "PAYMENT_COLLECT")
                    || userRepository.hasActivePermission(currentUserId, "FINANCE_DASHBOARD")
                    || userRepository.hasActivePermission(currentUserId, "FINANCE_DEBT");
        }
        boolean canViewBilling = admin || isAccountantOrCashier || hasFinancePerm;
        boolean canManageBilling = admin || isAccountantOrCashier;
        model.addAttribute("canManageBilling", canManageBilling);
        model.addAttribute("canViewBilling", canViewBilling);
        model.addAttribute("canPerformClinical", isDoctor);

        // Invoices and financial summary
        List<Invoice> invoiceList = List.of();
        if (canViewBilling) {
            invoiceList = invoiceRepository.findByPatientProfile_ProfileIdOrderByIssuedAtDesc(id);
            if (!admin) {
                java.util.Set<Long> allowedBranches = records.allowedBranches(session).stream()
                        .map(com.cvdcms.entity.Branch::getBranchId)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet());
                if (!allowedBranches.isEmpty()) {
                    invoiceList = invoiceList.stream()
                            .filter(inv -> inv.getBranch() != null && allowedBranches.contains(inv.getBranch().getBranchId()))
                            .toList();
                }
            }
        }
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

        // Bổ sung dữ liệu hợp nhất từ PatientOverviewService
        model.addAttribute("appointments", overviewService.getPatientAppointments(id));
        model.addAttribute("careData", overviewService.getPatientCareHistory(id, patient.branchId(), currentUserId));
        model.addAttribute("prescriptions", overviewService.getPatientPrescriptions(id));
        model.addAttribute("laboOrders", overviewService.getPatientLaboOrders(id));
        model.addAttribute("performedServices", overviewService.getPatientPerformedServices(id));
        model.addAttribute("diagnoses", overviewService.getPatientDiagnoses(id));
        model.addAttribute("treatmentPlans", overviewService.getPatientTreatmentPlans(id));

        model.addAttribute("currentTab", tab != null ? tab : "info");
        shared(model, session);
        return "patient-records/detail";
    }

    @PostMapping("/{id}/health-notes")
    public String updateHealthNotes(HttpSession session, @PathVariable Long id,
                                    @RequestParam(required = false) String allergyDetails,
                                    @RequestParam(required = false) String underlyingDiseaseDetails,
                                    RedirectAttributes redirect) {
        try {
            records.updateHealthNotes(session, id, allergyDetails, underlyingDiseaseDetails);
            redirect.addFlashAttribute("documentSuccess", "Đã cập nhật thông tin dị ứng và bệnh nền thành công.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("documentError", "Không thể cập nhật: " + ex.getMessage());
        }
        return "redirect:/doctor/patient-records/" + id;
    }

    @PostMapping("/{id}/change-code")
    public String changeCode(HttpSession session, @PathVariable Long id,
                             @RequestParam String newCode,
                             RedirectAttributes redirect) {
        try {
            records.updatePatientCode(session, id, newCode);
            redirect.addFlashAttribute("documentSuccess", "Đã đổi mã bệnh nhân thành công.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("documentError", "Không thể đổi mã: " + ex.getMessage());
        }
        return "redirect:/doctor/patient-records/" + id;
    }

    @PostMapping("/{id}/merge")
    public String mergeRecords(HttpSession session, @PathVariable Long id,
                               @RequestParam(required = false) String targetCode,
                               RedirectAttributes redirect) {
        redirect.addFlashAttribute("documentSuccess", "Đã ghi nhận yêu cầu gộp hồ sơ.");
        return "redirect:/doctor/patient-records/" + id;
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id) {
        return "redirect:/reception/patients/" + id + "/edit";
    }

    @PostMapping("/{id}/documents")
    public String uploadDocument(HttpSession session, @PathVariable Long id,
                                 @RequestParam DocumentType documentType,
                                 @RequestParam("file") MultipartFile file,
                                 RedirectAttributes redirect) {
        try {
            records.uploadDocument(session, id, documentType, file);
            redirect.addFlashAttribute("documentSuccess", "Đã tải lên bản scan giấy tờ thành công.");
        } catch (ResponseStatusException ex) {
            redirect.addFlashAttribute("documentError", ex.getReason());
        } catch (Exception ex) {
            redirect.addFlashAttribute("documentError", "Không thể tải lên giấy tờ: " + ex.getMessage());
        }
        return "redirect:/doctor/patient-records/" + id + "#info";
    }

    @PostMapping("/{id}/documents/{documentId}/type")
    public String updateDocumentType(HttpSession session, @PathVariable Long id,
                                     @PathVariable Long documentId,
                                     @RequestParam DocumentType newType,
                                     RedirectAttributes redirect) {
        try {
            records.updateDocumentType(session, id, documentId, newType);
            redirect.addFlashAttribute("documentSuccess", "Đã cập nhật loại giấy tờ.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("documentError", "Lỗi: " + ex.getMessage());
        }
        return "redirect:/doctor/patient-records/" + id;
    }

    @PostMapping("/{id}/documents/{documentId}/delete")
    public String deleteDocument(HttpSession session, @PathVariable Long id,
                                 @PathVariable Long documentId,
                                 RedirectAttributes redirect) {
        try {
            records.deleteDocument(session, id, documentId);
            redirect.addFlashAttribute("documentSuccess", "Đã xóa bản scan giấy tờ.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("documentError", "Lỗi: " + ex.getMessage());
        }
        return "redirect:/doctor/patient-records/" + id;
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
        return "redirect:/doctor/patient-records/" + id + "#photos";
    }

    @PostMapping("/{id}/photos/{photoId}/edit")
    public String editPhoto(HttpSession session, @PathVariable Long id,
                            @PathVariable Long photoId,
                            @RequestParam(required = false) String caption,
                            @RequestParam(required = false) String stage,
                            @RequestParam(required = false) String category,
                            RedirectAttributes redirect) {
        try {
            records.updatePhoto(session, photoId, caption, stage, category);
            redirect.addFlashAttribute("documentSuccess", "Đã cập nhật thông tin ảnh lâm sàng.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("documentError", "Lỗi: " + ex.getMessage());
        }
        return "redirect:/doctor/patient-records/" + id + "#photos";
    }

    @PostMapping("/{id}/photos/{photoId}/delete")
    public String deletePhoto(HttpSession session, @PathVariable Long id,
                              @PathVariable Long photoId,
                              RedirectAttributes redirect) {
        try {
            records.deletePhoto(session, photoId);
            redirect.addFlashAttribute("documentSuccess", "Đã xóa ảnh lâm sàng thành công.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("documentError", "Lỗi: " + ex.getMessage());
        }
        return "redirect:/doctor/patient-records/" + id + "#photos";
    }

    @GetMapping("/{id}/visits/{visitId}")
    public String visit(HttpSession session, Model model, @PathVariable Long id,
            @PathVariable Long visitId) {
        return "redirect:/doctor/examination/" + visitId + "/diagnoses?readOnly=true&fromPatientId=" + id;
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

    private void shared(Model model, HttpSession session) {
        boolean admin = records.isAdmin(session);
        model.addAttribute("recordBasePath", "/doctor/patient-records");
        model.addAttribute("sidebarMenu", "doctor-records");
        model.addAttribute("isAdmin", admin);
        model.addAttribute("canManageAdministrative", admin);
        model.addAttribute("canViewClinical", true);
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public String handleStatusException(org.springframework.web.server.ResponseStatusException ex, RedirectAttributes redirect) {
        if (ex.getStatusCode().value() == 401) {
            return "redirect:/login";
        }
        redirect.addFlashAttribute("documentError", ex.getReason() != null ? ex.getReason() : "Không thể truy cập hồ sơ này.");
        return "redirect:/doctor/patient-records";
    }
}
