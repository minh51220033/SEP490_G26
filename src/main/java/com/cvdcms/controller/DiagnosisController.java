package com.cvdcms.controller;

import com.cvdcms.DTO.ClinicalPhotoResponseDTO;
import com.cvdcms.DTO.DiagnosisResponseDTO;
import com.cvdcms.DTO.Icd10DiseaseDTO;
import com.cvdcms.DTO.MedicationDTO;
import com.cvdcms.DTO.PerformedServiceResponseDTO;
import com.cvdcms.DTO.PrescriptionItemResponseDTO;
import com.cvdcms.DTO.PrescriptionResponseDTO;
import com.cvdcms.DTO.ServiceDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.DTO.TreatmentPlanResponseDTO;
import com.cvdcms.service.DiagnosisService;
import com.cvdcms.service.ClinicalExaminationBootstrapService;
import com.cvdcms.service.Icd10DiseaseService;
import com.cvdcms.service.PerformedServiceService;
import com.cvdcms.service.PrescriptionService;
import com.cvdcms.service.TreatmentPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Controller
@RequiredArgsConstructor
public class DiagnosisController {

    private final DiagnosisService diagnosisService;
    private final ClinicalExaminationBootstrapService examinations;
    private final Icd10DiseaseService icd10DiseaseService;
    private final PerformedServiceService performedServiceService;
    private final PrescriptionService prescriptionService;
    private final TreatmentPlanService treatmentPlanService;
    private final com.cvdcms.service.ClinicalPhotoService clinicalPhotoService;
    private final com.cvdcms.service.ReExaminationService reExaminationService;
    private final com.cvdcms.service.ParaclinicalOrderService paraclinicalOrderService;
    private final com.cvdcms.service.LaboOrderService laboOrderService;
    private final SupplierRepository supplierRepository;
    private final VisitRepository visitRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final BranchRepository branchRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    private final UserRepository userRepository;
    private final CustomerCareInteractionRepository customerCareInteractionRepository;
    private final CustomerCareTaskRepository customerCareTaskRepository;
    private final com.cvdcms.security.DoctorVisitAccess doctorVisitAccess;
    private final com.cvdcms.service.ClinicalWorkflowService clinicalWorkflowService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.DoctorCoverGrantRepository doctorCoverGrantRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.ClinicalQueueTicketRepository clinicalQueueTicketRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.security.UrlIdCodec urlIdCodec = new com.cvdcms.security.UrlIdCodec();

    public void setDoctorCoverGrantRepository(com.cvdcms.repository.DoctorCoverGrantRepository doctorCoverGrantRepository) {
        this.doctorCoverGrantRepository = doctorCoverGrantRepository;
    }

    public void setClinicalQueueTicketRepository(com.cvdcms.repository.ClinicalQueueTicketRepository clinicalQueueTicketRepository) {
        this.clinicalQueueTicketRepository = clinicalQueueTicketRepository;
    }

    private String encodeVisit(Long visitId) {
        return urlIdCodec != null ? urlIdCodec.encodeVisit(visitId) : "";
    }

    private String encodePatient(Long patientId) {
        return urlIdCodec != null ? urlIdCodec.encodePatient(patientId) : "";
    }

    private Long decodeVisitRef(String visitRef) {
        if (urlIdCodec == null) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        }
        return urlIdCodec.decodeVisitOrThrow(visitRef);
    }

    private Long decodePatientRef(String patientRef) {
        if (urlIdCodec == null) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        }
        return urlIdCodec.decodePatientOrThrow(patientRef);
    }

    private List<Branch> filterAccessibleBranches(List<Branch> branches, boolean isDoctorRestricted, Branch assignedBranch) {
        if (isDoctorRestricted && assignedBranch != null) {
            return List.of(assignedBranch);
        }
        return branches;
    }

    private User getLoggedInUser(jakarta.servlet.http.HttpSession session) {
        if (session == null) return null;
        Object principal = session.getAttribute("loggedInUser");
        if (principal instanceof User user && user.getUserId() != null) {
            return userRepository.findById(user.getUserId()).orElse(null);
        }
        return null;
    }

    private Branch getDoctorAssignedBranch(User user) {
        if (user == null) return null;
        if (user.getBranch() != null) {
            return user.getBranch();
        }
        List<Long> activeBranchIds = staffBranchAssignmentRepository.findActiveBranchIdsByUserId(user.getUserId(), AssignmentStatus.ACTIVE);
        if (activeBranchIds != null && !activeBranchIds.isEmpty()) {
            return branchRepository.findById(activeBranchIds.get(0)).orElse(null);
        }
        List<Long> explicitIds = customerCareTaskRepository.findExplicitBranchIdsByUserId(user.getUserId());
        if (explicitIds != null && !explicitIds.isEmpty()) {
            return branchRepository.findById(explicitIds.get(0)).orElse(null);
        }
        return null;
    }

    @GetMapping({"/doctor/daily-visits", "/doctor/visits"})
    public String dailyVisits(
            @RequestParam(value = "branchId", required = false) Long branchId,
            jakarta.servlet.http.HttpSession session,
            Model model
    ) {
        User currentUser = getLoggedInUser(session);
        boolean isAdmin = currentUser != null && userRepository.hasActiveAdminRole(currentUser.getUserId());
        StaffProfile currentDoctor = currentUser != null
                ? staffProfileRepository.findByUser_UserId(currentUser.getUserId()).orElse(null)
                : null;
        boolean isDoctorAccount = currentDoctor != null && !isAdmin;
        Branch assignedBranch = !isAdmin ? getDoctorAssignedBranch(currentUser) : null;
        boolean isDoctorRestricted = !isAdmin && assignedBranch != null;

        if (isDoctorRestricted) {
            branchId = assignedBranch.getBranchId();
            if (session != null) {
                session.setAttribute("doctorSelectedBranchId", branchId);
            }
        } else {
            if (branchId != null && session != null) {
                session.setAttribute("doctorSelectedBranchId", branchId);
            } else if (session != null && session.getAttribute("doctorSelectedBranchId") != null) {
                branchId = (Long) session.getAttribute("doctorSelectedBranchId");
            }
        }

        String assignedBranchFullName = assignedBranch != null
                ? ((assignedBranch.getBranchCode() != null && !assignedBranch.getBranchCode().isEmpty() ? assignedBranch.getBranchCode() + " - " : "") + assignedBranch.getBranchName())
                : "";

        model.addAttribute("activeMenu", "daily-visits");
        List<Branch> allBranches = branchRepository.findByIsActiveTrueOrderByBranchNameAsc();
        if (allBranches.isEmpty()) {
            allBranches = branchRepository.findAll();
        }
        model.addAttribute("branches", filterAccessibleBranches(allBranches, isDoctorRestricted, assignedBranch));
        model.addAttribute("selectedBranchId", branchId);
        model.addAttribute("isDoctorRestricted", isDoctorRestricted);
        model.addAttribute("doctorAssignedBranchId", assignedBranch != null ? assignedBranch.getBranchId() : null);
        model.addAttribute("doctorAssignedBranchCode", assignedBranch != null ? assignedBranch.getBranchCode() : "");
        model.addAttribute("doctorAssignedBranchName", assignedBranch != null ? assignedBranch.getBranchName() : "");
        model.addAttribute("doctorAssignedBranchFullName", assignedBranchFullName);
        model.addAttribute("currentDoctorStaffId", currentDoctor != null ? currentDoctor.getStaffId() : null);
        model.addAttribute("currentDoctorName", currentDoctor != null ? currentDoctor.getFullName() : "");
        model.addAttribute("isDoctorAccount", isDoctorAccount);
        if (assignedBranch != null) {
            model.addAttribute("currentBranchName", assignedBranchFullName);
        }
        return "doctor/daily-visits";
    }

    @GetMapping({"/doctor/recalls", "/doctor/re-examinations"})
    public String recallsView(Model model) {
        model.addAttribute("activeMenu", "recalls");
        model.addAttribute("doctors", performedServiceService.getAvailableDoctors());
        model.addAttribute("branches", branchRepository.findAll());
        model.addAttribute("stats", reExaminationService.getRecallsStatistics());
        model.addAttribute("pageTitle", "Nhắc hẹn tái khám sau điều trị (SCR-18.2) - DentalFlow");
        // Tinh gọn & Hợp nhất: Chuyển hướng sang phân hệ Quản lý CSKH & Nhắc hẹn tái khám chuẩn (FT44 - UC-26)
        return "redirect:/customer-care";
    }

    public String index(
            Long visitId,
            Long branchId,
            jakarta.servlet.http.HttpSession session,
            RedirectAttributes redirectAttributes,
            Model model
    ) {
        return index(visitId, branchId, false, null, session, redirectAttributes, model);
    }

    @GetMapping({"/doctor/examination", "/doctor/diagnoses", "/doctor/treatment"})
    public String index(
            @RequestParam(value = "visitId", required = false) Long visitId,
            @RequestParam(value = "branchId", required = false) Long branchId,
            @RequestParam(value = "readOnly", required = false, defaultValue = "false") boolean readOnly,
            @RequestParam(value = "fromPatientId", required = false) Long fromPatientId,
            jakarta.servlet.http.HttpSession session,
            RedirectAttributes redirectAttributes,
            Model model
    ) {
        User currentUser = getLoggedInUser(session);
        boolean isAdmin = currentUser != null && userRepository.hasActiveAdminRole(currentUser.getUserId());
        StaffProfile currentDoctor = currentUser != null
                ? staffProfileRepository.findByUser_UserId(currentUser.getUserId()).orElse(null)
                : null;
        boolean isDoctorAccount = currentDoctor != null && !isAdmin;
        Branch assignedBranch = !isAdmin ? getDoctorAssignedBranch(currentUser) : null;
        boolean isDoctorRestricted = !isAdmin && assignedBranch != null;

        String assignedBranchFullName = assignedBranch != null
                ? ((assignedBranch.getBranchCode() != null && !assignedBranch.getBranchCode().isEmpty() ? assignedBranch.getBranchCode() + " - " : "") + assignedBranch.getBranchName())
                : "";

        if (isDoctorRestricted) {
            if (branchId != null && !branchId.equals(assignedBranch.getBranchId())) {
                redirectAttributes.addFlashAttribute("errorMessage",
                        "Cảnh báo: Bác sĩ chỉ được phép tác nghiệp tại cơ sở đang làm việc (" + assignedBranchFullName + ")!");
            }
            branchId = assignedBranch.getBranchId();
            if (session != null) {
                session.setAttribute("doctorSelectedBranchId", branchId);
            }
        } else {
            if (branchId != null && session != null) {
                session.setAttribute("doctorSelectedBranchId", branchId);
            } else if (session != null && session.getAttribute("doctorSelectedBranchId") != null) {
                branchId = (Long) session.getAttribute("doctorSelectedBranchId");
            }
        }

        if (visitId == null) {
            if (currentDoctor != null) {
                final Long docStaffId = currentDoctor.getStaffId();
                java.util.Set<Long> coveredIds = doctorCoverGrantRepository != null
                        ? doctorCoverGrantRepository.findActiveCoveredVisitIds(docStaffId, java.time.LocalDateTime.now())
                        : java.util.Collections.<Long>emptySet();
                List<Visit> myVisits = diagnosisService.getAvailableVisitsForDiagnosis(branchId).stream()
                        .filter(v -> (v.getPrimaryDoctor() != null && docStaffId.equals(v.getPrimaryDoctor().getStaffId()))
                                || coveredIds.contains(v.getVisitId()))
                        .toList();
                if (!myVisits.isEmpty()) {
                    visitId = myVisits.get(0).getVisitId();
                }
            } else {
                List<Visit> availableVisits = diagnosisService.getAvailableVisitsForDiagnosis(branchId);
                if (!availableVisits.isEmpty()) {
                    visitId = availableVisits.get(0).getVisitId();
                } else {
                    List<Visit> allVisits = (branchId != null)
                            ? visitRepository.findRecentVisitsByStatusesAndBranch(List.of(VisitStatus.values()), branchId)
                            : visitRepository.findAll();
                    if (!allVisits.isEmpty()) {
                        visitId = allVisits.get(0).getVisitId();
                    }
                }
            }
        }

        if (visitId != null) {
            String redirectUrl = "redirect:/doctor/examination/" + encodeVisit(visitId) + "/diagnoses";
            List<String> queryParams = new ArrayList<>();
            if (branchId != null) {
                queryParams.add("branchId=" + branchId);
            }
            if (readOnly) {
                queryParams.add("readOnly=true");
            }
            if (fromPatientId != null) {
                queryParams.add("fromPatientRef=" + encodePatient(fromPatientId));
            }
            if (!queryParams.isEmpty()) {
                redirectUrl += "?" + String.join("&", queryParams);
            }
            return redirectUrl;
        }

        // Nếu cơ sở chưa có ca khám nào -> hiển thị trực tiếp màn hình rỗng (không tự tạo ca khám mẫu)
        return renderEmptyDiagnosisPage(branchId, session, model);
    }

    @GetMapping("/doctor/examination/patient/{patientRef}")
    public String examinationByPatient(
            @PathVariable("patientRef") String patientRef,
            @RequestParam(value = "newVisit", defaultValue = "false") boolean forceNewVisit,
            jakarta.servlet.http.HttpSession session
    ) {
        Long patientId = decodePatientRef(patientRef);
        doctorVisitAccess.requirePatientWrite(session, patientId);

        User currentUser = getLoggedInUser(session);
        boolean isAdmin = currentUser != null && userRepository.hasActiveAdminRole(currentUser.getUserId());
        Branch assignedBranch = !isAdmin ? getDoctorAssignedBranch(currentUser) : null;
        StaffProfile currentDoctor = currentUser != null
                ? staffProfileRepository.findByUser_UserId(currentUser.getUserId()).orElse(null)
                : null;

        if (!forceNewVisit) {
            Optional<Visit> activeVisitOpt = visitRepository.findFirstByPatientProfile_ProfileIdAndStatusIn(
                    patientId,
                    List.of(VisitStatus.IN_EXAM, VisitStatus.WAITING, VisitStatus.IN_TREATMENT, VisitStatus.WAITING_PARACLINICAL, VisitStatus.WAITING_RESULT, VisitStatus.WAITING_PAYMENT)
            );
            if (activeVisitOpt.isPresent()) {
                Visit av = activeVisitOpt.get();
                if (isAdmin || assignedBranch == null || (av.getBranch() != null && av.getBranch().getBranchId().equals(assignedBranch.getBranchId()))) {
                    return "redirect:/doctor/examination/" + encodeVisit(av.getVisitId()) + "/diagnoses";
                }
            }
            Optional<Visit> latestVisitOpt = visitRepository.findFirstByPatientProfile_ProfileIdOrderByCheckInAtDesc(patientId);
            if (latestVisitOpt.isPresent()) {
                Visit lv = latestVisitOpt.get();
                if (isAdmin || assignedBranch == null || (lv.getBranch() != null && lv.getBranch().getBranchId().equals(assignedBranch.getBranchId()))) {
                    return "redirect:/doctor/examination/" + encodeVisit(lv.getVisitId()) + "/diagnoses";
                }
            }
        }

        PatientProfile patient = patientProfileRepository.findById(patientId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Không tìm thấy hồ sơ bệnh nhân phù hợp."));

        if (!isAdmin && currentDoctor == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Chỉ tài khoản bác sĩ mới được mở ca khám mới.");
        }

        Visit newVisit = new Visit();
        newVisit.setPatientProfile(patient);
        newVisit.setCheckInAt(LocalDateTime.now());
        newVisit.setStatus(VisitStatus.IN_EXAM);
        newVisit.setVisitType(com.cvdcms.entity.enums.VisitType.WALK_IN);
        newVisit.setInitialSymptoms("Khám và điều trị tại ghế theo yêu cầu");
        Branch resolvedBranch = (!isAdmin && assignedBranch != null)
                ? assignedBranch
                : (patient.getPreferredBranch() != null ? patient.getPreferredBranch() : branchRepository.findAll().stream().findFirst().orElse(null));
        newVisit.setBranch(resolvedBranch);
        newVisit.setPrimaryDoctor(currentDoctor != null
                ? currentDoctor
                : staffProfileRepository.findAll().stream().filter(s -> s.getStaffCode() != null && s.getStaffCode().startsWith("BS")).findFirst().orElse(null));
        long count = visitRepository.count() + 1;
        String genCode = "VIS" + (10000 + count);
        if (visitRepository.findByVisitCode(genCode).isPresent()) {
            genCode = "VIS" + System.currentTimeMillis();
        }
        newVisit.setVisitCode(genCode);
        newVisit = visitRepository.save(newVisit);
        examinations.ensureForAssignedVisit(newVisit.getVisitId());

        return "redirect:/doctor/examination/" + encodeVisit(newVisit.getVisitId()) + "/diagnoses";
    }

    public String examinationDiagnoses(
            Long visitId,
            Long branchIdParam,
            jakarta.servlet.http.HttpSession session,
            RedirectAttributes redirectAttributes,
            Model model
    ) {
        return examinationDiagnosesInternal(visitId, branchIdParam, false, null, session, redirectAttributes, model);
    }

    @GetMapping({"/doctor/examination/{visitRef}/diagnoses", "/doctor/examination/{visitRef}/treatment"})
    public String examinationDiagnoses(
            @PathVariable("visitRef") String visitRef,
            @RequestParam(value = "branchId", required = false) Long branchIdParam,
            @RequestParam(value = "readOnly", required = false, defaultValue = "false") boolean readOnly,
            @RequestParam(value = "fromPatientRef", required = false) String fromPatientRef,
            @RequestParam(value = "fromPatientId", required = false) Long fromPatientId,
            jakarta.servlet.http.HttpSession session,
            RedirectAttributes redirectAttributes,
            Model model
    ) {
        Long visitId = decodeVisitRef(visitRef);
        Long resolvedFromPatientId = (fromPatientRef != null && !fromPatientRef.isBlank())
                ? decodePatientRef(fromPatientRef)
                : fromPatientId;
        return examinationDiagnosesInternal(visitId, branchIdParam, readOnly, resolvedFromPatientId, session, redirectAttributes, model);
    }

    private String examinationDiagnosesInternal(
            Long visitId,
            Long branchIdParam,
            boolean readOnly,
            Long fromPatientId,
            jakarta.servlet.http.HttpSession session,
            RedirectAttributes redirectAttributes,
            Model model
    ) {
        User currentUser = getLoggedInUser(session);
        boolean isAdmin = currentUser != null && userRepository.hasActiveAdminRole(currentUser.getUserId());
        StaffProfile currentDoctor = currentUser != null
                ? staffProfileRepository.findByUser_UserId(currentUser.getUserId()).orElse(null)
                : null;
        boolean isDoctorAccount = currentDoctor != null && !isAdmin;
        Branch assignedBranch = !isAdmin ? getDoctorAssignedBranch(currentUser) : null;
        boolean isDoctorRestricted = !isAdmin && assignedBranch != null;

        String assignedBranchFullName = assignedBranch != null
                ? ((assignedBranch.getBranchCode() != null && !assignedBranch.getBranchCode().isEmpty() ? assignedBranch.getBranchCode() + " - " : "") + assignedBranch.getBranchName())
                : "";

        Visit visit;
        try {
            visit = diagnosisService.getVisitOrThrow(visitId);
        } catch (IllegalArgumentException ex) {
            log.warn("Không tìm thấy ca khám ID={}: {}. Hiển thị màn hình rỗng.", visitId, ex.getMessage());
            return renderEmptyDiagnosisPage(branchIdParam, session, model);
        }

        if (!readOnly) {
            doctorVisitAccess.require(session, visitId);

            // Kiểm tra ca khám có thuộc cơ sở của bác sĩ không
            if (isDoctorRestricted && visit.getBranch() != null && !visit.getBranch().getBranchId().equals(assignedBranch.getBranchId())) {
                redirectAttributes.addFlashAttribute("errorMessage",
                        "Cảnh báo: Ca khám " + visit.getVisitCode() + " thuộc cơ sở khác (" +
                                (visit.getBranch().getBranchCode() != null ? visit.getBranch().getBranchCode() + " - " : "") + visit.getBranch().getBranchName() +
                                "). Bác sĩ chỉ được phép tác động vào các ca khám tại cơ sở đang làm việc (" + assignedBranchFullName + ")!");
                return "redirect:/doctor/daily-visits";
            }

            // Tự động chuyển vé từ CALLED/WAITING sang IN_SERVICE và lượt khám sang IN_EXAM khi Bác sĩ bắt đầu ca khám
            boolean statusChanged = false;
            if (visit.getStatus() == VisitStatus.WAITING || visit.getStatus() == VisitStatus.WAITING_RESULT) {
                visit.setStatus(VisitStatus.IN_EXAM);
                statusChanged = true;
            }
            if (clinicalQueueTicketRepository != null) {
                List<QueueTicket> activeTickets = clinicalQueueTicketRepository.findByVisitVisitIdAndQueueType(visitId, QueueType.EXAM);
                for (QueueTicket t : activeTickets) {
                    if (t.getStatus() == QueueStatus.CALLED || t.getStatus() == QueueStatus.WAITING) {
                        t.setStatus(QueueStatus.IN_SERVICE);
                        if (t.getCalledAt() == null) {
                            t.setCalledAt(LocalDateTime.now());
                        }
                        clinicalQueueTicketRepository.save(t);
                    }
                }
            }
            if (statusChanged) {
                visitRepository.save(visit);
            }
        } else {
            doctorVisitAccess.requireVisitRead(session, visitId);
            if (fromPatientId != null && visit.getPatientProfile() != null
                    && !fromPatientId.equals(visit.getPatientProfile().getProfileId())) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.FORBIDDEN, "Thông tin ca khám không khớp hồ sơ bệnh nhân.");
            }
        }

        List<DiagnosisResponseDTO> diagnoses = diagnosisService.getDiagnosesByVisitId(visitId);
        List<Icd10DiseaseDTO> commonDiseases = icd10DiseaseService.getCommonDentalDiseases();
        List<Visit> otherVisits = (isDoctorRestricted && assignedBranch != null)
                ? visitRepository.findRecentVisitsByStatusesAndBranch(List.of(VisitStatus.values()), assignedBranch.getBranchId())
                : visitRepository.findAll();
        if (!isAdmin && currentDoctor != null) {
            final Long docStaffId = currentDoctor.getStaffId();
            java.util.Set<Long> coveredIds = doctorCoverGrantRepository != null
                    ? doctorCoverGrantRepository.findActiveCoveredVisitIds(docStaffId, LocalDateTime.now())
                    : java.util.Collections.emptySet();
            otherVisits = otherVisits.stream()
                    .filter(v -> v.getVisitId().equals(visitId)
                            || (v.getPrimaryDoctor() != null && docStaffId.equals(v.getPrimaryDoctor().getStaffId()))
                            || coveredIds.contains(v.getVisitId()))
                    .toList();
        }

        // FT16: Dịch vụ thực hiện thực tế
        List<PerformedServiceResponseDTO> performedServices = performedServiceService.getPerformedServicesByVisitId(visitId);
        List<ServiceDTO> activeServices = performedServiceService.getAllActiveServices();
        List<StaffProfile> doctors = performedServiceService.getAvailableDoctors();
        List<StaffProfile> assistants = performedServiceService.getAvailableAssistants();

        // Tính tổng tiền dịch vụ đã thực hiện
        BigDecimal totalPerformedAmount = performedServices.stream()
                .map(PerformedServiceResponseDTO::getFinalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        model.addAttribute("visit", visit);
        model.addAttribute("patient", visit.getPatientProfile());
        model.addAttribute("diagnoses", diagnoses);
        model.addAttribute("commonDiseases", commonDiseases);
        model.addAttribute("diagnosisTypes", DiagnosisType.values());
        model.addAttribute("allVisits", otherVisits);

        // Khám tổng quát & Tiền sử
        com.cvdcms.DTO.GeneralExaminationRequestDTO generalExam = diagnosisService.getGeneralExamination(visitId);
        model.addAttribute("generalExam", generalExam);

        // FT16 Attributes
        model.addAttribute("performedServices", performedServices);
        model.addAttribute("activeServices", activeServices);
        String activeServicesJson = "[]";
        try {
            activeServicesJson = new ObjectMapper().writeValueAsString(activeServices);
        } catch (Exception ignored) {}
        model.addAttribute("activeServicesJson", activeServicesJson);
        model.addAttribute("doctors", doctors);
        model.addAttribute("assistants", assistants);
        model.addAttribute("totalPerformedAmount", totalPerformedAmount);

        // FT18: Đơn thuốc & Dặn dò
        List<PrescriptionResponseDTO> prescriptions = prescriptionService.getPrescriptionsByVisit(visitId);
        List<MedicationDTO> activeMedications = prescriptionService.getActiveMedications();
        model.addAttribute("prescriptions", prescriptions);
        model.addAttribute("activeMedications", activeMedications);

        // Tính tổng tiền đơn thuốc & tổng hợp thanh toán (Dịch vụ khám + Đơn thuốc)
        BigDecimal totalPrescriptionAmount = BigDecimal.ZERO;
        if (prescriptions != null) {
            for (PrescriptionResponseDTO pr : prescriptions) {
                if (pr.getTotalAmount() != null) {
                    totalPrescriptionAmount = totalPrescriptionAmount.add(pr.getTotalAmount());
                } else if (pr.getItems() != null) {
                    for (PrescriptionItemResponseDTO it : pr.getItems()) {
                        if (it.getFinalPrice() != null) {
                            totalPrescriptionAmount = totalPrescriptionAmount.add(it.getFinalPrice());
                        }
                    }
                }
            }
        }
        model.addAttribute("totalPrescriptionAmount", totalPrescriptionAmount);

        BigDecimal grandTotalAmount = (totalPerformedAmount != null ? totalPerformedAmount : BigDecimal.ZERO)
                .add(totalPrescriptionAmount);
        model.addAttribute("grandTotalAmount", grandTotalAmount);

        // FT15: Kế hoạch điều trị & Lộ trình (SCR-15.1, SCR-15.2, SCR-15.3)
        List<TreatmentPlanResponseDTO> treatmentPlans = (visit.getPatientProfile() != null)
                ? treatmentPlanService.getPlansByPatientId(visit.getPatientProfile().getProfileId())
                : Collections.emptyList();
        model.addAttribute("treatmentPlans", treatmentPlans);

        // FT17: Kho ảnh chụp lâm sàng Trước / Sau (SCR-17.1, 17.2, 17.3)
        Long profileId = (visit.getPatientProfile() != null) ? visit.getPatientProfile().getProfileId() : null;
        List<ClinicalPhotoResponseDTO> clinicalPhotos = clinicalPhotoService.getPhotosByProfile(profileId);
        Map<String, Object> photoStats = clinicalPhotoService.getPhotoStatistics(profileId);
        model.addAttribute("clinicalPhotos", clinicalPhotos);
        model.addAttribute("photoStats", photoStats);
        model.addAttribute("photoStages", com.cvdcms.entity.enums.PhotoStage.values());
        model.addAttribute("photoCategories", com.cvdcms.entity.enums.PhotoCategory.values());

        // FT18 (Phần Tái khám): Lịch hẹn tái khám của bệnh nhân (SCR-18.1)
        List<com.cvdcms.DTO.ReExaminationResponseDTO> reExaminations = (profileId != null)
                ? reExaminationService.getReExaminationsByPatient(profileId)
                : Collections.emptyList();
        model.addAttribute("reExaminations", reExaminations);
        model.addAttribute("preferredSessions", com.cvdcms.entity.enums.PreferredSession.values());

        // FT11 / UC-10: Chỉ định cận lâm sàng & Chẩn đoán hình ảnh (SCR-11.2, SCR-11.3)
        List<com.cvdcms.DTO.ParaclinicalOrderItemResponseDTO> paraclinicalOrders = paraclinicalOrderService.getOrdersByVisit(visitId);
        List<com.cvdcms.DTO.ServiceDTO> paraclinicalServices = paraclinicalOrderService.getParaclinicalServices();
        model.addAttribute("paraclinicalOrders", paraclinicalOrders);
        model.addAttribute("paraclinicalServices", paraclinicalServices);

        // FT15 / FT13: Quản lý Đặt xưởng Labo & Mẫu xét nghiệm gửi ngoài (SCR-15.3, SCR-13.1)
        List<com.cvdcms.DTO.LaboOrderResponseDTO> laboOrders = laboOrderService.getOrdersByVisit(visitId);
        Map<String, Object> laboStats = laboOrderService.getLaboStatistics(visitId, profileId);
        List<Supplier> laboSuppliers = supplierRepository.findAll();
        model.addAttribute("laboOrders", laboOrders);
        model.addAttribute("laboStats", laboStats);
        model.addAttribute("laboSuppliers", laboSuppliers);

        // FT44: Lịch sử chăm sóc bệnh nhân sau điều trị (SCR-44.6 & doctor/fragments/patient-care-tab)
        List<CustomerCareInteraction> careInteractions = (profileId != null)
                ? customerCareInteractionRepository.findByProfileIdWithDetails(profileId)
                : Collections.emptyList();
        List<CustomerCareTask> careTasks = (profileId != null)
                ? customerCareTaskRepository.findByPatientProfileIdWithDetails(profileId)
                : Collections.emptyList();
        model.addAttribute("careInteractions", careInteractions);
        model.addAttribute("careTasks", careTasks);

        boolean isClinicalLocked = readOnly || (visit.getStatus() != null &&
                (visit.getStatus() == VisitStatus.WAITING_PAYMENT || visit.getStatus() == VisitStatus.COMPLETED));
        model.addAttribute("isClinicalLocked", isClinicalLocked);
        model.addAttribute("isReadOnlyView", readOnly);
        Long resolvedFromPatientId = fromPatientId != null ? fromPatientId
                : (visit.getPatientProfile() != null ? visit.getPatientProfile().getProfileId() : null);
        model.addAttribute("fromPatientId", resolvedFromPatientId);

        boolean useReceptionPath = false;
        if (session != null && session.getAttribute("permissionCodes") instanceof List<?> perms) {
            boolean hasMedRecordView = perms.stream().anyMatch(p -> "MEDICAL_RECORD_VIEW".equalsIgnoreCase(String.valueOf(p)));
            boolean hasPatientManage = perms.stream().anyMatch(p -> "PATIENT_PROFILE_MANAGE".equalsIgnoreCase(String.valueOf(p)));
            if (!isAdmin && !hasMedRecordView && hasPatientManage) {
                useReceptionPath = true;
            }
        }
        String patientRecordBackUrl = useReceptionPath
                ? (resolvedFromPatientId != null ? "/reception/patients/" + resolvedFromPatientId : "/reception/patients") + "#tab-treatment"
                : (resolvedFromPatientId != null ? "/doctor/patient-records/" + encodePatient(resolvedFromPatientId) : "/doctor/patient-records") + "#tab-treatment";
        model.addAttribute("patientRecordBackUrl", patientRecordBackUrl);
        model.addAttribute("readonlySidebarMenu", useReceptionPath ? "patients" : "doctor-records");
        model.addAttribute("visitToken", encodeVisit(visitId));
        model.addAttribute("patientToken", visit.getPatientProfile() != null ? encodePatient(visit.getPatientProfile().getProfileId()) : "");

        com.cvdcms.DTO.ClinicalCompletionCheckDTO clinicalCheck = null;
        try {
            clinicalCheck = diagnosisService.checkClinicalCompletionReadiness(visitId);
        } catch (Exception ex) {
            log.warn("Không thể kiểm tra điều kiện lâm sàng cho visitId={}: {}", visitId, ex.getMessage());
        }
        model.addAttribute("clinicalCheck", clinicalCheck);

        model.addAttribute("pageTitle", "Khám & Điều trị - Chẩn đoán (FT14), Kế hoạch (FT15), Dịch vụ thực hiện (FT16), Ảnh lâm sàng (FT17) & Đơn thuốc (FT18)");
        model.addAttribute("activeMenu", "examination");

        List<Branch> branches = branchRepository.findByIsActiveTrueOrderByBranchNameAsc();
        if (branches.isEmpty()) {
            branches = branchRepository.findAll();
        }

        Long selectedBranchId = branchIdParam;
        if (isDoctorRestricted) {
            selectedBranchId = assignedBranch.getBranchId();
        } else {
            if (selectedBranchId == null && visit.getBranch() != null) {
                selectedBranchId = visit.getBranch().getBranchId();
            }
            if (selectedBranchId == null && session != null && session.getAttribute("doctorSelectedBranchId") != null) {
                selectedBranchId = (Long) session.getAttribute("doctorSelectedBranchId");
            }
            if (selectedBranchId == null && !branches.isEmpty()) {
                selectedBranchId = branches.get(0).getBranchId();
            }
        }
        if (selectedBranchId != null && session != null) {
            session.setAttribute("doctorSelectedBranchId", selectedBranchId);
        }

        Branch selectedBranch = null;
        if (selectedBranchId != null) {
            for (Branch b : branches) {
                if (b.getBranchId().equals(selectedBranchId)) {
                    selectedBranch = b;
                    break;
                }
            }
        }
        if (selectedBranch == null && !branches.isEmpty()) {
            selectedBranch = branches.get(0);
        }

        branches = filterAccessibleBranches(branches, isDoctorRestricted, assignedBranch);
        model.addAttribute("branches", branches);
        model.addAttribute("selectedBranchId", selectedBranchId);
        model.addAttribute("selectedBranch", selectedBranch);
        model.addAttribute("isDoctorRestricted", isDoctorRestricted);
        model.addAttribute("doctorAssignedBranchId", assignedBranch != null ? assignedBranch.getBranchId() : null);
        model.addAttribute("doctorAssignedBranchCode", assignedBranch != null ? assignedBranch.getBranchCode() : "");
        model.addAttribute("doctorAssignedBranchName", assignedBranch != null ? assignedBranch.getBranchName() : "");
        model.addAttribute("doctorAssignedBranchFullName", assignedBranchFullName);
        model.addAttribute("currentBranchName", selectedBranch != null ? (selectedBranch.getBranchCode() != null && !selectedBranch.getBranchCode().isEmpty() ? selectedBranch.getBranchCode() : selectedBranch.getBranchName()) : (assignedBranch != null ? assignedBranch.getBranchName() : ""));

        return "doctor/diagnoses";
    }

    /**
     * SCR-15.2: Trang chuyen dung Lap Ke hoach & Lo trinh dieu tri (lien ket da chan doan, chuan Bo Y te)
     */
    @GetMapping("/doctor/examination/{visitRef}/treatment-plans/create")
    public String showCreateTreatmentPlanPageByRef(
            @PathVariable("visitRef") String visitRef,
            @RequestParam(value = "diagId", required = false) Long preselectedDiagId,
            jakarta.servlet.http.HttpSession session,
            Model model
    ) {
        Long visitId = decodeVisitRef(visitRef);
        doctorVisitAccess.require(session, visitId);
        return showCreateTreatmentPlanPage(visitId, preselectedDiagId, model);
    }

    public String showCreateTreatmentPlanPage(
            Long visitId,
            Long preselectedDiagId,
            Model model
    ) {
        jakarta.servlet.http.HttpServletRequest request = null;
        try {
            org.springframework.web.context.request.RequestAttributes attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra) {
                request = sra.getRequest();
            }
        } catch (Exception ignored) {}
        jakarta.servlet.http.HttpSession session = request != null ? request.getSession(false) : null;
        if (request != null && doctorVisitAccess != null) {
            doctorVisitAccess.require(session, visitId);
        }

        User currentUser = getLoggedInUser(session);
        boolean isAdmin = currentUser != null && userRepository.hasActiveAdminRole(currentUser.getUserId());
        StaffProfile currentDoctor = currentUser != null
                ? staffProfileRepository.findByUser_UserId(currentUser.getUserId()).orElse(null)
                : null;
        boolean isDoctorAccount = currentDoctor != null && !isAdmin;
        Branch assignedBranch = !isAdmin ? getDoctorAssignedBranch(currentUser) : null;
        boolean isDoctorRestricted = !isAdmin && assignedBranch != null;

        Visit visit;
        try {
            visit = diagnosisService.getVisitOrThrow(visitId);
        } catch (IllegalArgumentException ex) {
            log.warn("Không tìm thấy ca khám phù hợp ({}). Tự động chuyển hướng về trang ca khám.", ex.getMessage());
            return "redirect:/doctor/diagnoses";
        }

        if (isDoctorRestricted && visit.getBranch() != null && !visit.getBranch().getBranchId().equals(assignedBranch.getBranchId())) {
            return "redirect:/doctor/daily-visits";
        }

        List<DiagnosisResponseDTO> diagnoses = diagnosisService.getDiagnosesByVisitId(visitId);
        List<ServiceDTO> activeServices = performedServiceService.getAllActiveServices();
        List<StaffProfile> doctors = performedServiceService.getAvailableDoctors();
        List<Visit> allVisits = (isDoctorRestricted && assignedBranch != null)
                ? visitRepository.findRecentVisitsByStatusesAndBranch(List.of(VisitStatus.values()), assignedBranch.getBranchId())
                : visitRepository.findAll();
        if (!isAdmin && currentDoctor != null) {
            final Long docStaffId = currentDoctor.getStaffId();
            java.util.Set<Long> coveredIds = doctorCoverGrantRepository != null
                    ? doctorCoverGrantRepository.findActiveCoveredVisitIds(docStaffId, LocalDateTime.now())
                    : java.util.Collections.emptySet();
            allVisits = allVisits.stream()
                    .filter(v -> v.getVisitId().equals(visitId)
                            || (v.getPrimaryDoctor() != null && docStaffId.equals(v.getPrimaryDoctor().getStaffId()))
                            || coveredIds.contains(v.getVisitId()))
                    .toList();
        }

        model.addAttribute("visit", visit);
        model.addAttribute("visitToken", encodeVisit(visitId));
        model.addAttribute("patient", visit.getPatientProfile());
        model.addAttribute("patientToken", visit.getPatientProfile() != null ? encodePatient(visit.getPatientProfile().getProfileId()) : "");
        model.addAttribute("diagnoses", diagnoses);
        model.addAttribute("preselectedDiagId", preselectedDiagId);
        model.addAttribute("activeServices", activeServices);
        model.addAttribute("doctors", doctors);
        model.addAttribute("allVisits", allVisits);

        List<com.cvdcms.DTO.WorkflowTemplateResponseDTO> workflowTemplates = (clinicalWorkflowService != null)
                ? clinicalWorkflowService.getAllTemplates()
                : List.of();
        model.addAttribute("workflowTemplates", workflowTemplates);
        String workflowTemplatesJson = "[]";
        try {
            workflowTemplatesJson = new ObjectMapper().writeValueAsString(workflowTemplates);
        } catch (Exception ignored) {}
        model.addAttribute("workflowTemplatesJson", workflowTemplatesJson);

        String activeServicesJson = "[]";
        try {
            activeServicesJson = new ObjectMapper().writeValueAsString(activeServices);
        } catch (Exception ignored) {}
        model.addAttribute("activeServicesJson", activeServicesJson);

        String diagnosesJson = "[]";
        try {
            diagnosesJson = new ObjectMapper().writeValueAsString(diagnoses);
        } catch (Exception ignored) {}
        model.addAttribute("diagnosesJson", diagnosesJson);

        List<Icd10DiseaseDTO> commonIcds = (icd10DiseaseService != null)
                ? icd10DiseaseService.getCommonDentalDiseases()
                : List.of();
        model.addAttribute("commonIcds", commonIcds);
        String commonIcdsJson = "[]";
        try {
            commonIcdsJson = new ObjectMapper().writeValueAsString(commonIcds);
        } catch (Exception ignored) {}
        model.addAttribute("commonIcdsJson", commonIcdsJson);

        boolean isClinicalLocked = visit.getStatus() != null &&
                (visit.getStatus() == VisitStatus.WAITING_PAYMENT || visit.getStatus() == VisitStatus.COMPLETED);
        model.addAttribute("isClinicalLocked", isClinicalLocked);

        model.addAttribute("pageTitle", "Lập Kế hoạch & Lộ trình điều trị - DentalFlow");
        model.addAttribute("activeMenu", "examination");

        return "doctor/treatment-plan-create";
    }

    private String renderEmptyDiagnosisPage(Long branchIdParam, jakarta.servlet.http.HttpSession session, Model model) {
        User currentUser = getLoggedInUser(session);
        boolean isAdmin = currentUser != null && userRepository.hasActiveAdminRole(currentUser.getUserId());
        StaffProfile currentDoctor = currentUser != null
                ? staffProfileRepository.findByUser_UserId(currentUser.getUserId()).orElse(null)
                : null;
        boolean isDoctorAccount = currentDoctor != null && !isAdmin;
        Branch assignedBranch = !isAdmin ? getDoctorAssignedBranch(currentUser) : null;
        boolean isDoctorRestricted = !isAdmin && assignedBranch != null;

        String assignedBranchFullName = assignedBranch != null
                ? ((assignedBranch.getBranchCode() != null && !assignedBranch.getBranchCode().isEmpty() ? assignedBranch.getBranchCode() + " - " : "") + assignedBranch.getBranchName())
                : "";

        List<Branch> branches = branchRepository.findByIsActiveTrueOrderByBranchNameAsc();
        if (branches.isEmpty()) {
            branches = branchRepository.findAll();
        }

        Long selectedBranchId = branchIdParam;
        if (isDoctorRestricted) {
            selectedBranchId = assignedBranch.getBranchId();
        } else {
            if (selectedBranchId == null && session != null && session.getAttribute("doctorSelectedBranchId") != null) {
                selectedBranchId = (Long) session.getAttribute("doctorSelectedBranchId");
            }
            if (selectedBranchId == null && !branches.isEmpty()) {
                selectedBranchId = branches.get(0).getBranchId();
            }
        }
        if (selectedBranchId != null && session != null) {
            session.setAttribute("doctorSelectedBranchId", selectedBranchId);
        }

        Branch selectedBranch = null;
        if (selectedBranchId != null) {
            for (Branch b : branches) {
                if (b.getBranchId().equals(selectedBranchId)) {
                    selectedBranch = b;
                    break;
                }
            }
        }
        if (selectedBranch == null && !branches.isEmpty()) {
            selectedBranch = branches.get(0);
        }

        model.addAttribute("visit", null);
        model.addAttribute("patient", null);
        model.addAttribute("diagnoses", Collections.emptyList());
        model.addAttribute("commonDiseases", Collections.emptyList());
        model.addAttribute("otherVisits", Collections.emptyList());
        model.addAttribute("performedServices", Collections.emptyList());
        model.addAttribute("activeServices", Collections.emptyList());
        model.addAttribute("doctors", performedServiceService.getAvailableDoctors());
        model.addAttribute("assistants", performedServiceService.getAvailableAssistants());
        model.addAttribute("totalPerformedAmount", BigDecimal.ZERO);
        model.addAttribute("treatmentPlans", Collections.emptyList());
        model.addAttribute("prescription", null);
        model.addAttribute("medications", Collections.emptyList());
        model.addAttribute("appointments", Collections.emptyList());
        model.addAttribute("laboOrders", Collections.emptyList());
        model.addAttribute("laboStats", Collections.emptyMap());
        model.addAttribute("laboSuppliers", Collections.emptyList());
        model.addAttribute("careInteractions", Collections.emptyList());
        model.addAttribute("careTasks", Collections.emptyList());
        model.addAttribute("isClinicalLocked", false);
        model.addAttribute("clinicalCheck", null);
        model.addAttribute("pageTitle", "Bàn khám bệnh - DentalFlow");
        model.addAttribute("activeMenu", "examination");
        branches = filterAccessibleBranches(branches, isDoctorRestricted, assignedBranch);
        model.addAttribute("branches", branches);
        model.addAttribute("selectedBranchId", selectedBranchId);
        model.addAttribute("selectedBranch", selectedBranch);
        model.addAttribute("isDoctorRestricted", isDoctorRestricted);
        model.addAttribute("doctorAssignedBranchId", assignedBranch != null ? assignedBranch.getBranchId() : null);
        model.addAttribute("doctorAssignedBranchCode", assignedBranch != null ? assignedBranch.getBranchCode() : "");
        model.addAttribute("doctorAssignedBranchName", assignedBranch != null ? assignedBranch.getBranchName() : "");
        model.addAttribute("doctorAssignedBranchFullName", assignedBranchFullName);
        model.addAttribute("currentBranchName", selectedBranch != null ? (selectedBranch.getBranchCode() != null && !selectedBranch.getBranchCode().isEmpty() ? selectedBranch.getBranchCode() : selectedBranch.getBranchName()) : (assignedBranch != null ? assignedBranch.getBranchName() : ""));
        model.addAttribute("activeServicesJson", "[]");

        return "doctor/diagnoses";
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public String handleVisitNotFound(IllegalArgumentException ex, RedirectAttributes redirectAttributes) {
        log.warn("Lỗi ca khám không tồn tại trong DiagnosisController: {}", ex.getMessage());
        redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        return "redirect:/doctor/daily-visits";
    }
}
