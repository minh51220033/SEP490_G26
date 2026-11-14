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
<<<<<<< Updated upstream
=======
    @Autowired(required = false)
    private com.cvdcms.repository.UserRepository userRepository;
    @Autowired(required = false)
    private com.cvdcms.security.DoctorVisitAccess doctorVisitAccess;
    @Autowired(required = false)
    private com.cvdcms.security.UrlIdCodec urlIdCodec = new com.cvdcms.security.UrlIdCodec();

    private Long decodePatientRef(String patientRef) {
        return urlIdCodec.decodePatientOrThrow(patientRef);
    }

    private Long decodeVisitRef(String visitRef) {
        return urlIdCodec.decodeVisitOrThrow(visitRef);
    }

    private String encodePatient(Long id) {
        return urlIdCodec.encodePatient(id);
    }
>>>>>>> Stashed changes

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

    @GetMapping("/{patientRef}")
    public String detail(HttpSession session, Model model, @PathVariable String patientRef,
                         @RequestParam(required = false) String tab) {
        Long id = decodePatientRef(patientRef);
        PatientView patient = records.get(session, id);
        model.addAttribute("patient", patient);
        model.addAttribute("patientRouteId", encodePatient(id));

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

        // Bổ sung dữ liệu hợp nhất từ PatientOverviewService
        Long currentUserId = records.extractUserId(session);
        model.addAttribute("appointments", overviewService.getPatientAppointments(id));
        model.addAttribute("careData", overviewService.getPatientCareHistory(id, patient.branchId(), currentUserId));
        model.addAttribute("prescriptions", overviewService.getPatientPrescriptions(id));
        model.addAttribute("laboOrders", overviewService.getPatientLaboOrders(id));
        model.addAttribute("performedServices", overviewService.getPatientPerformedServices(id));
        model.addAttribute("diagnoses", overviewService.getPatientDiagnoses(id));
        model.addAttribute("treatmentPlans", overviewService.getPatientTreatmentPlans(id));

        // Permissions
        boolean admin = records.isAdmin(session);
        boolean isDoctor = records.isDoctor(session);
        model.addAttribute("canManageBilling", admin);
        model.addAttribute("canPerformClinical", isDoctor);
        model.addAttribute("currentTab", tab != null ? tab : "info");
        shared(model, session);
        return "patient-records/detail";
    }

    @PostMapping("/{patientRef}/health-notes")
    public String updateHealthNotes(HttpSession session, @PathVariable String patientRef,
                                    @RequestParam(required = false) String allergyDetails,
                                    @RequestParam(required = false) String underlyingDiseaseDetails,
                                    RedirectAttributes redirect) {
        Long id = decodePatientRef(patientRef);
        String routeRef = encodePatient(id);
        try {
            records.updateHealthNotes(session, id, allergyDetails, underlyingDiseaseDetails);
            redirect.addFlashAttribute("documentSuccess", "Đã cập nhật thông tin dị ứng và bệnh nền thành công.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("documentError", "Không thể cập nhật: " + ex.getMessage());
        }
        return "redirect:/doctor/patient-records/" + routeRef;
    }

    @PostMapping("/{patientRef}/change-code")
    public String changeCode(HttpSession session, @PathVariable String patientRef,
                             @RequestParam String newCode,
                             RedirectAttributes redirect) {
        Long id = decodePatientRef(patientRef);
        String routeRef = encodePatient(id);
        try {
            records.updatePatientCode(session, id, newCode);
            redirect.addFlashAttribute("documentSuccess", "Đã đổi mã bệnh nhân thành công.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("documentError", "Không thể đổi mã: " + ex.getMessage());
        }
        return "redirect:/doctor/patient-records/" + routeRef;
    }

    @PostMapping("/{patientRef}/merge")
    public String mergeRecords(HttpSession session, @PathVariable String patientRef,
                               @RequestParam(required = false) String targetCode,
                               RedirectAttributes redirect) {
        Long id = decodePatientRef(patientRef);
        records.get(session, id);
        String routeRef = encodePatient(id);
        redirect.addFlashAttribute("documentSuccess", "Đã ghi nhận yêu cầu gộp hồ sơ.");
        return "redirect:/doctor/patient-records/" + routeRef;
    }

    @GetMapping("/{patientRef}/edit")
    public String edit(HttpSession session, @PathVariable String patientRef) {
        Long id = decodePatientRef(patientRef);
        records.get(session, id);
        return "redirect:/reception/patients/" + id + "/edit";
    }

    @PostMapping("/{patientRef}/documents")
    public String uploadDocument(HttpSession session, @PathVariable String patientRef,
                                 @RequestParam DocumentType documentType,
                                 @RequestParam("file") MultipartFile file,
                                 RedirectAttributes redirect) {
        Long id = decodePatientRef(patientRef);
        String routeRef = encodePatient(id);
        try {
            records.uploadDocument(session, id, documentType, file);
            redirect.addFlashAttribute("documentSuccess", "Đã tải lên bản scan giấy tờ thành công.");
        } catch (ResponseStatusException ex) {
            redirect.addFlashAttribute("documentError", ex.getReason());
        } catch (Exception ex) {
            redirect.addFlashAttribute("documentError", "Không thể tải lên giấy tờ: " + ex.getMessage());
        }
        return "redirect:/doctor/patient-records/" + routeRef + "#info";
    }

    @PostMapping("/{patientRef}/documents/{documentId}/type")
    public String updateDocumentType(HttpSession session, @PathVariable String patientRef,
                                     @PathVariable Long documentId,
                                     @RequestParam DocumentType newType,
                                     RedirectAttributes redirect) {
        Long id = decodePatientRef(patientRef);
        String routeRef = encodePatient(id);
        try {
            records.updateDocumentType(session, id, documentId, newType);
            redirect.addFlashAttribute("documentSuccess", "Đã cập nhật loại giấy tờ.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("documentError", "Lỗi: " + ex.getMessage());
        }
        return "redirect:/doctor/patient-records/" + routeRef;
    }

    @PostMapping("/{patientRef}/documents/{documentId}/delete")
    public String deleteDocument(HttpSession session, @PathVariable String patientRef,
                                 @PathVariable Long documentId,
                                 RedirectAttributes redirect) {
        Long id = decodePatientRef(patientRef);
        String routeRef = encodePatient(id);
        try {
            records.deleteDocument(session, id, documentId);
            redirect.addFlashAttribute("documentSuccess", "Đã xóa bản scan giấy tờ.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("documentError", "Lỗi: " + ex.getMessage());
        }
        return "redirect:/doctor/patient-records/" + routeRef;
    }

    @PostMapping("/{patientRef}/photos")
    public String uploadPhoto(HttpSession session, @PathVariable String patientRef,
                              @RequestParam(required = false) Long visitId,
                              @RequestParam(defaultValue = "BEFORE") String stage,
                              @RequestParam(defaultValue = "INTRAORAL") String category,
                              @RequestParam(required = false) String caption,
                              @RequestParam("file") MultipartFile file,
                              RedirectAttributes redirect) {
        Long id = decodePatientRef(patientRef);
        records.get(session, id);
        if (visitId != null && doctorVisitAccess != null) {
            doctorVisitAccess.requireVisitRead(session, visitId);
        }
        String routeRef = encodePatient(id);
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
        return "redirect:/doctor/patient-records/" + routeRef + "#photos";
    }

    @PostMapping("/{patientRef}/photos/{photoId}/edit")
    public String editPhoto(HttpSession session, @PathVariable String patientRef,
                            @PathVariable Long photoId,
                            @RequestParam(required = false) String caption,
                            @RequestParam(required = false) String stage,
                            @RequestParam(required = false) String category,
                            RedirectAttributes redirect) {
        Long id = decodePatientRef(patientRef);
        String routeRef = encodePatient(id);
        try {
            records.updatePhotoForPatient(session, id, photoId, caption, stage, category);
            redirect.addFlashAttribute("documentSuccess", "Đã cập nhật thông tin ảnh lâm sàng.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("documentError", "Lỗi: " + ex.getMessage());
        }
        return "redirect:/doctor/patient-records/" + routeRef + "#photos";
    }

    @PostMapping("/{patientRef}/photos/{photoId}/delete")
    public String deletePhoto(HttpSession session, @PathVariable String patientRef,
                              @PathVariable Long photoId,
                              RedirectAttributes redirect) {
        Long id = decodePatientRef(patientRef);
        String routeRef = encodePatient(id);
        try {
            records.deletePhotoForPatient(session, id, photoId);
            redirect.addFlashAttribute("documentSuccess", "Đã xóa ảnh lâm sàng thành công.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("documentError", "Lỗi: " + ex.getMessage());
        }
        return "redirect:/doctor/patient-records/" + routeRef + "#photos";
    }

    @GetMapping("/{patientRef}/visits/{visitRef}")
    public String visit(HttpSession session, Model model, @PathVariable String patientRef,
            @PathVariable String visitRef) {
        Long id = decodePatientRef(patientRef);
        Long visitId = decodeVisitRef(visitRef);
        var detail = records.visit(session, id, visitId);
        if (doctorVisitAccess != null) {
            doctorVisitAccess.requireVisitRead(session, visitId);
        }
        PatientView patient = records.get(session, id);
        model.addAttribute("patient", patient);
        model.addAttribute("patientRouteId", encodePatient(id));
        model.addAttribute("detail", detail);
        model.addAttribute("visit", detail != null ? detail.visit() : null);
        LocalDate visitDate = detail != null && detail.visit() != null && detail.visit().checkInAt() != null
                ? detail.visit().checkInAt().toLocalDate()
                : null;
        List<com.cvdcms.entity.Appointment> patientAppointments = overviewService.getPatientAppointments(id);
        List<PatientService.FollowUp> followUps = (patientAppointments != null ? patientAppointments : List.<com.cvdcms.entity.Appointment>of()).stream()
                .filter(java.util.Objects::nonNull)
                .filter(a -> a.getAppointmentDate() != null
                        && (visitDate == null || a.getAppointmentDate().isAfter(visitDate)))
                .filter(a -> a.getStatus() == com.cvdcms.entity.enums.AppointmentStatus.PENDING
                        || a.getStatus() == com.cvdcms.entity.enums.AppointmentStatus.CONFIRMED)
                .sorted(java.util.Comparator.comparing(com.cvdcms.entity.Appointment::getAppointmentDate)
                        .thenComparing(com.cvdcms.entity.Appointment::getExpectedTime,
                                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .map(a -> new PatientService.FollowUp(
                        a.getAppointmentCode(),
                        a.getAppointmentDate(),
                        a.getExpectedTime(),
                        a.getStatus().name(),
                        a.getDoctor() == null ? null : a.getDoctor().getFullName(),
                        a.getService() == null ? null : a.getService().getServiceName()))
                .toList();
        model.addAttribute("followUps", followUps);
        model.addAttribute("treatmentPlans", overviewService.getPatientTreatmentPlans(id));
        model.addAttribute("canPerformClinical", canPerformClinicalOnVisit(session, visitId));
        model.addAttribute("canManageEmr", records.isAdmin(session) || records.isDoctor(session));
        shared(model, session);
        return "patient-records/visit";
    }

    private boolean canPerformClinicalOnVisit(HttpSession session, Long visitId) {
        if (session == null) return false;
        if (records.isAdmin(session)) return true;
        if (!records.isDoctor(session)) return false;
        if (doctorVisitAccess == null || visitId == null) return true;
        try {
            doctorVisitAccess.require(session, visitId);
            return true;
        } catch (ResponseStatusException ex) {
            return false;
        }
    }

    @GetMapping("/{patientRef}/documents/{documentId}")
    public ResponseEntity<Resource> downloadDocument(HttpSession session, @PathVariable String patientRef,
            @PathVariable Long documentId) {
        Long id = decodePatientRef(patientRef);
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
