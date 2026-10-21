package com.cvdcms.controller;

import com.cvdcms.DTO.DiagnosisResponseDTO;
import com.cvdcms.DTO.Icd10DiseaseDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.DiagnosisType;
import com.cvdcms.entity.enums.StaffGender;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.entity.enums.VisitType;
import com.cvdcms.repository.*;
import com.cvdcms.DTO.PerformedServiceResponseDTO;
import com.cvdcms.DTO.ServiceDTO;
import com.cvdcms.service.DiagnosisService;
import com.cvdcms.service.Icd10DiseaseService;
import com.cvdcms.service.PerformedServiceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Controller
@RequiredArgsConstructor
public class DiagnosisController {

    private final DiagnosisService diagnosisService;
    private final Icd10DiseaseService icd10DiseaseService;
    private final PerformedServiceService performedServiceService;
    private final VisitRepository visitRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final BranchRepository branchRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final UserRepository userRepository;

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

        // FT16 Attributes
        model.addAttribute("performedServices", performedServices);
        model.addAttribute("activeServices", activeServices);
        model.addAttribute("doctors", doctors);
        model.addAttribute("assistants", assistants);
        model.addAttribute("totalPerformedAmount", totalPerformedAmount);

        model.addAttribute("pageTitle", "Khám & Điều trị - Chẩn đoán (FT14) & Dịch vụ thực hiện (FT16)");
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
