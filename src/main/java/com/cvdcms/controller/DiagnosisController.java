package com.cvdcms.controller;

import com.cvdcms.DTO.ClinicalPhotoResponseDTO;
import com.cvdcms.DTO.DiagnosisResponseDTO;
import com.cvdcms.DTO.Icd10DiseaseDTO;
import com.cvdcms.DTO.MedicationDTO;
import com.cvdcms.DTO.PerformedServiceResponseDTO;
import com.cvdcms.DTO.PrescriptionResponseDTO;
import com.cvdcms.DTO.ServiceDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.DiagnosisType;
import com.cvdcms.entity.enums.StaffGender;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.entity.enums.VisitType;
import com.cvdcms.repository.*;
import com.cvdcms.DTO.TreatmentPlanResponseDTO;
import com.cvdcms.service.DiagnosisService;
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
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Controller
@RequiredArgsConstructor
public class DiagnosisController {

    private final DiagnosisService diagnosisService;
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
    private final UserRepository userRepository;

    @GetMapping({"/doctor/daily-visits", "/doctor/visits"})
    public String dailyVisits(Model model) {
        model.addAttribute("activeMenu", "daily-visits");
        model.addAttribute("branches", branchRepository.findAll());
        return "doctor/daily-visits";
    }

    @GetMapping({"/doctor/recalls", "/doctor/re-examinations"})
    public String recallsView(Model model) {
        model.addAttribute("activeMenu", "recalls");
        model.addAttribute("doctors", performedServiceService.getAvailableDoctors());
        model.addAttribute("branches", branchRepository.findAll());
        model.addAttribute("stats", reExaminationService.getRecallsStatistics());
        model.addAttribute("pageTitle", "Nhắc hẹn tái khám sau điều trị (SCR-18.2) - DentalFlow");
        return "doctor/recalls";
    }

    @GetMapping({"/doctor/diagnoses", "/doctor/treatment"})
    public String index(
            @RequestParam(value = "visitId", required = false) Long visitId,
            Model model
    ) {
        if (visitId == null) {
            List<Visit> availableVisits = diagnosisService.getAvailableVisitsForDiagnosis();
            if (!availableVisits.isEmpty()) {
                visitId = availableVisits.get(0).getVisitId();
            } else {
                List<Visit> allVisits = visitRepository.findAll();
                if (!allVisits.isEmpty()) {
                    visitId = allVisits.get(0).getVisitId();
                } else {
                    // Tự động tạo dữ liệu ca khám mẫu để trải nghiệm giao diện ngay lập tức
                    Visit sampleVisit = createSampleVisitIfEmpty();
                    visitId = sampleVisit.getVisitId();
                }
            }
        }
        return "redirect:/doctor/examination/" + visitId + "/diagnoses";
    }

    @GetMapping("/doctor/examination/patient/{patientId}")
    public String examinationByPatient(
            @PathVariable("patientId") Long patientId,
            @RequestParam(value = "newVisit", defaultValue = "false") boolean forceNewVisit
    ) {
        if (!forceNewVisit) {
            Optional<Visit> activeVisitOpt = visitRepository.findFirstByPatientProfile_ProfileIdAndStatusIn(
                    patientId,
                    List.of(VisitStatus.IN_EXAM, VisitStatus.WAITING, VisitStatus.IN_TREATMENT, VisitStatus.WAITING_PARACLINICAL, VisitStatus.WAITING_RESULT, VisitStatus.WAITING_PAYMENT)
            );
            if (activeVisitOpt.isPresent()) {
                return "redirect:/doctor/examination/" + activeVisitOpt.get().getVisitId() + "/diagnoses";
            }
            Optional<Visit> latestVisitOpt = visitRepository.findFirstByPatientProfile_ProfileIdOrderByCheckInAtDesc(patientId);
            if (latestVisitOpt.isPresent()) {
                return "redirect:/doctor/examination/" + latestVisitOpt.get().getVisitId() + "/diagnoses";
            }
        }

        // Tạo ca khám mới nếu chưa có
        PatientProfile patient = patientProfileRepository.findById(patientId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bệnh nhân ID: " + patientId));

        Visit newVisit = new Visit();
        newVisit.setPatientProfile(patient);
        newVisit.setCheckInAt(LocalDateTime.now());
        newVisit.setStatus(VisitStatus.IN_EXAM);
        newVisit.setVisitType(com.cvdcms.entity.enums.VisitType.WALK_IN);
        newVisit.setInitialSymptoms("Khám và điều trị tại ghế theo yêu cầu");
        newVisit.setBranch(patient.getPreferredBranch() != null ? patient.getPreferredBranch() : branchRepository.findAll().get(0));
        newVisit.setPrimaryDoctor(staffProfileRepository.findAll().stream().filter(s -> s.getStaffCode() != null && s.getStaffCode().startsWith("BS")).findFirst().orElse(null));
        long count = visitRepository.count() + 1;
        newVisit.setVisitCode("VIS" + (10000 + count));
        newVisit = visitRepository.save(newVisit);

        return "redirect:/doctor/examination/" + newVisit.getVisitId() + "/diagnoses";
    }

    @GetMapping({"/doctor/examination/{visitId}/diagnoses", "/doctor/examination/{visitId}/treatment"})
    public String examinationDiagnoses(
            @PathVariable("visitId") Long visitId,
            Model model
    ) {
        Visit visit = diagnosisService.getVisitOrThrow(visitId);
        List<DiagnosisResponseDTO> diagnoses = diagnosisService.getDiagnosesByVisitId(visitId);
        List<Icd10DiseaseDTO> commonDiseases = icd10DiseaseService.getCommonDentalDiseases();
        List<Visit> otherVisits = visitRepository.findAll();

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

        boolean isClinicalLocked = visit.getStatus() != null &&
                (visit.getStatus() == VisitStatus.WAITING_PAYMENT || visit.getStatus() == VisitStatus.COMPLETED);
        model.addAttribute("isClinicalLocked", isClinicalLocked);

        model.addAttribute("pageTitle", "Khám & Điều trị - Chẩn đoán (FT14), Kế hoạch (FT15), Dịch vụ thực hiện (FT16), Ảnh lâm sàng (FT17) & Đơn thuốc (FT18)");
        model.addAttribute("activeMenu", "examination");

        return "doctor/diagnoses";
    }

    private Visit createSampleVisitIfEmpty() {
        Branch branch = branchRepository.findAll().stream().findFirst().orElseGet(() -> {
            Branch b = new Branch();
            b.setBranchCode("CS-MAU-01");
            b.setBranchName("Cơ sở mẫu 1");
            b.setAddress("Địa chỉ cơ sở mẫu 1");
            b.setIsActive(true);
            return branchRepository.save(b);
        });

        User user = userRepository.findAll().stream().findFirst().orElseGet(() -> {
            User u = new User();
            u.setUsername("mau1_demo");
            u.setPassword("$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe");
            u.setEmail("mau1@dentalclinic.com");
            u.setPhoneNumber("0900000001");
            return userRepository.save(u);
        });

        PatientProfile patient = patientProfileRepository.findAll().stream().findFirst().orElseGet(() -> {
            PatientProfile p = new PatientProfile();
            p.setUser(user);
            p.setPatientCode("BN-MAU-01");
            p.setFullName("Bệnh nhân mẫu 1");
            p.setGender("Nam");
            p.setDob(LocalDate.of(1995, 1, 1));
            p.setPhoneNumber("0900000001");
            p.setAddress("Địa chỉ mẫu 1");
            p.setPreferredBranch(branch);
            p.setDepositBalance(BigDecimal.ZERO);
            p.setStatus("ACTIVE");
            return patientProfileRepository.save(p);
        });

        StaffProfile doctor = staffProfileRepository.findAll().stream().findFirst().orElseGet(() -> {
            StaffProfile sp = new StaffProfile();
            sp.setUser(user);
            sp.setStaffCode("BS-MAU-01");
            sp.setFullName("Bác sĩ mẫu 1");
            sp.setSpecialty("Nha khoa mẫu 1");
            sp.setGender(StaffGender.MALE);
            sp.setStatus(StaffStatus.ACTIVE);
            return staffProfileRepository.save(sp);
        });

        Visit visit = new Visit();
        visit.setVisitCode("VIS-MAU-01");
        visit.setPatientProfile(patient);
        visit.setBranch(branch);
        visit.setPrimaryDoctor(doctor);
        visit.setVisitType(VisitType.APPOINTMENT);
        visit.setStatus(VisitStatus.IN_EXAM);
        visit.setInitialSymptoms("Triệu chứng mẫu 1: Đau nhức răng");
        visit.setCheckInAt(LocalDateTime.now());
        return visitRepository.save(visit);
    }
}
