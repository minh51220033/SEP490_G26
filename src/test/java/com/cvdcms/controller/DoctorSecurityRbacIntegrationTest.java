package com.cvdcms.controller;

import com.cvdcms.DTO.DiagnosisRequestDTO;
import com.cvdcms.DTO.GeneralExaminationRequestDTO;
import com.cvdcms.DTO.PerformedServiceRequestDTO;
import com.cvdcms.DTO.PrescriptionItemRequestDTO;
import com.cvdcms.DTO.PrescriptionRequestDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration Test kiá»ƒm tra PhÃ¢n quyá»n báº£o máº­t (Security RBAC) cá»§a Role BÃ¡c sÄ© (Doctor),
 * Lá»… tÃ¢n (Receptionist) vÃ  Bá»‡nh nhÃ¢n (Patient) trong há»‡ thá»‘ng nha khoa CV-DCMS.
 *
 * CÃ¡c ká»‹ch báº£n kiá»ƒm thá»­:
 * 1. BÃ¡c sÄ© (BAC_SI) Ä‘Æ°á»£c phÃ©p gá»i cÃ¡c API chuyÃªn mÃ´n lÃ¢m sÃ ng (ghi cháº©n Ä‘oÃ¡n, kÃª Ä‘Æ¡n, ghi nháº­n thá»§ thuáº­t).
 * 2. Lá»… tÃ¢n (LE_TAN) cá»‘ tÃ¬nh gá»i API lÃ¢m sÃ ng cá»§a BÃ¡c sÄ© -> Bá»‹ cháº·n vá»›i HTTP 403 Forbidden.
 * 3. Bá»‡nh nhÃ¢n (BENH_NHAN) cá»‘ tÃ¬nh gá»i API lÃ¢m sÃ ng cá»§a BÃ¡c sÄ© -> Bá»‹ cháº·n vá»›i HTTP 403 Forbidden.
 * 4. BÃ¡c sÄ© (BAC_SI) cá»‘ tÃ¬nh can thiá»‡p sá»­a danh má»¥c giÃ¡ gá»‘c FT02 -> Bá»‹ cháº·n vá»›i HTTP 403 Forbidden.
 * 5. KhÃ¡ch vÃ£ng lai (Guest) chÆ°a Ä‘Äƒng nháº­p -> Bá»‹ chuyá»ƒn hÆ°á»›ng / tá»« chá»‘i truy cáº­p.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:doctor_security_rbac_db;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;NON_KEYWORDS=MONTH,YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class DoctorSecurityRbacIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @Autowired private BranchRepository branchRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private StaffProfileRepository staffProfileRepository;
    @Autowired private StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    @Autowired private PatientProfileRepository patientProfileRepository;
    @Autowired private VisitRepository visitRepository;
    @Autowired private ClinicalExaminationRepository clinicalExamRepository;
    @Autowired private Icd10DiseaseRepository icd10DiseaseRepository;
    @Autowired private ServiceCategoryRepository serviceCategoryRepository;
    @Autowired private ServiceRepository serviceRepository;
    @Autowired private MedicationRepository medicationRepository;

    private Branch branch;
    private User doctorUser;
    private StaffProfile doctorStaff;
    private User receptionistUser;
    private StaffProfile receptionistStaff;
    private User patientUser;
    private PatientProfile patient;
    private Visit activeVisit;
    private Icd10Disease icd10Disease;
    private com.cvdcms.entity.Service dentalService;
    private Medication medication;

    @BeforeEach
    void setupSecurityFixtures() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        // 1. CÆ¡ sá»Ÿ phÃ²ng khÃ¡m
        branch = branchRepository.findAll().stream().findFirst().orElseGet(() -> {
            Branch b = new Branch();
            b.setBranchCode("CN-RBAC-01");
            b.setBranchName("Nha Khoa SmileCare Cáº§u Giáº¥y");
            b.setAddress("45 XuÃ¢n Thá»§y, Cáº§u Giáº¥y, HÃ  Ná»™i");
            b.setPhone("02437776666");
            b.setOpeningTime(LocalTime.of(8, 0));
            b.setClosingTime(LocalTime.of(20, 0));
            b.setIsActive(true);
            return branchRepository.save(b);
        });

        // 2. Táº¡o hoáº·c náº¡p danh má»¥c quyá»n tÄ©nh chuáº©n
        Set<String> doctorCodes = Set.of(
                "CLINICAL_DAILY_VISITS", "CLINICAL_WORKFLOWS", "CLINICAL_DISPATCH",
                "PRESCRIPTION_WRITE", "TREATMENT_PLAN_MANAGE", "CLINICAL_PHOTOS",
                "CLINICAL_XRAY", "CLINICAL_LABO", "MEDICAL_RECORD_VIEW",
                "APPOINTMENT_VIEW", "RECEPTION_MEMBERSHIPS", "CUSTOMER_CARE_MANAGE",
                "SCHEDULE_MY", "HR_MY_ATTENDANCE", "HR_LEAVE_REQUESTS"
        );
        Set<Permission> doctorPermissions = new HashSet<>();
        for (String code : doctorCodes) {
            Permission p = permissionRepository.findByPermissionCodeIgnoreCase(code).orElseGet(() -> {
                Permission perm = new Permission();
                perm.setPermissionCode(code);
                perm.setModule("CLINICAL");
                perm.setDescription("Quyá»n " + code);
                return permissionRepository.save(perm);
            });
            doctorPermissions.add(p);
        }

        Set<String> receptionistCodes = Set.of(
                "PATIENT_PROFILE_MANAGE", "RECEPTION_MEMBERSHIPS", "APPOINTMENT_MANAGE",
                "RECEPTION_CHECKIN", "APPOINTMENT_VIEW", "RECEPTION_DISPATCH",
                "CUSTOMER_CARE_MANAGE", "SCHEDULE_MY", "HR_MY_ATTENDANCE", "HR_LEAVE_REQUESTS"
        );
        Set<Permission> receptionistPermissions = new HashSet<>();
        for (String code : receptionistCodes) {
            Permission p = permissionRepository.findByPermissionCodeIgnoreCase(code).orElseGet(() -> {
                Permission perm = new Permission();
                perm.setPermissionCode(code);
                perm.setModule("RECEPTION");
                perm.setDescription("Quyá»n " + code);
                return permissionRepository.save(perm);
            });
            receptionistPermissions.add(p);
        }

        // Vai trÃ² BÃ¡c sÄ© (BAC_SI)
        Role doctorRole = roleRepository.findAllWithPermissions().stream()
                .filter(r -> "BAC_SI".equalsIgnoreCase(r.getRoleName()))
                .findFirst()
                .orElseGet(() -> {
                    Role r = new Role();
                    r.setRoleName("BAC_SI");
                    r.setDescription("BÃ¡c sÄ© Ä‘iá»u trá»‹ nha khoa");
                    r.setIsActive(true);
                    return roleRepository.saveAndFlush(r);
                });
        doctorRole.setPermissions(new HashSet<>(doctorPermissions));
        doctorRole = roleRepository.saveAndFlush(doctorRole);

        // Vai trÃ² Lá»… tÃ¢n (LE_TAN)
        Role receptionistRole = roleRepository.findAllWithPermissions().stream()
                .filter(r -> "LE_TAN".equalsIgnoreCase(r.getRoleName()))
                .findFirst()
                .orElseGet(() -> {
                    Role r = new Role();
                    r.setRoleName("LE_TAN");
                    r.setDescription("Lá»… tÃ¢n phÃ²ng khÃ¡m");
                    r.setIsActive(true);
                    return roleRepository.saveAndFlush(r);
                });
        receptionistRole.setPermissions(new HashSet<>(receptionistPermissions));
        receptionistRole = roleRepository.saveAndFlush(receptionistRole);

        // Vai trÃ² Bá»‡nh nhÃ¢n (BENH_NHAN)
        Role patientRole = roleRepository.findAllWithPermissions().stream()
                .filter(r -> "BENH_NHAN".equalsIgnoreCase(r.getRoleName()))
                .findFirst()
                .orElseGet(() -> {
                    Role r = new Role();
                    r.setRoleName("BENH_NHAN");
                    r.setDescription("KhÃ¡ch hÃ ng / Bá»‡nh nhÃ¢n");
                    r.setIsActive(true);
                    return roleRepository.saveAndFlush(r);
                });
        patientRole.setPermissions(new HashSet<>());
        patientRole = roleRepository.saveAndFlush(patientRole);

        // 5. Táº¡o tÃ i khoáº£n BÃ¡c sÄ©
        String docUsername = "dr.security." + System.currentTimeMillis();
        doctorUser = new User();
        doctorUser.setUsername(docUsername);
        doctorUser.setPassword("$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5m2k7m2oYIev1q2Zk7xN5wQ1hJ7uS");
        doctorUser.setEmail(docUsername + "@smilecare.vn");
        doctorUser.setPhoneNumber("0977" + (System.currentTimeMillis() % 1000000));
        doctorUser.setStatus("ACTIVE");
        doctorUser.setIsActive(true);
        doctorUser.setUserType(UserType.STAFF);
        doctorUser.setBranch(branch);
        doctorUser.setRoles(new HashSet<>(Set.of(doctorRole)));
        doctorUser.setCustomPermissionsEnabled(false);
        doctorUser = userRepository.saveAndFlush(doctorUser);

        doctorStaff = new StaffProfile();
        doctorStaff.setStaffCode("BS" + (System.currentTimeMillis() % 10000));
        doctorStaff.setFullName("BS. Nguyá»…n VÄƒn An");
        doctorStaff.setUser(doctorUser);
        doctorStaff.setStatus(StaffStatus.ACTIVE);
        doctorStaff = staffProfileRepository.save(doctorStaff);

        StaffBranchAssignment docAssign = new StaffBranchAssignment();
        docAssign.setStaff(doctorStaff);
        docAssign.setBranch(branch);
        docAssign.setRole(doctorRole);
        docAssign.setStatus(AssignmentStatus.ACTIVE);
        docAssign.setStartDate(LocalDate.now());
        docAssign.setIsPrimary(true);
        staffBranchAssignmentRepository.save(docAssign);

        // 6. Táº¡o tÃ i khoáº£n Lá»… tÃ¢n
        String recUsername = "rec.security." + System.currentTimeMillis();
        receptionistUser = new User();
        receptionistUser.setUsername(recUsername);
        receptionistUser.setPassword("$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5m2k7m2oYIev1q2Zk7xN5wQ1hJ7uS");
        receptionistUser.setEmail(recUsername + "@smilecare.vn");
        receptionistUser.setPhoneNumber("0966" + (System.currentTimeMillis() % 1000000));
        receptionistUser.setStatus("ACTIVE");
        receptionistUser.setIsActive(true);
        receptionistUser.setUserType(UserType.STAFF);
        receptionistUser.setBranch(branch);
        receptionistUser.setRoles(new HashSet<>(Set.of(receptionistRole)));
        receptionistUser.setCustomPermissionsEnabled(false);
        receptionistUser = userRepository.saveAndFlush(receptionistUser);

        receptionistStaff = new StaffProfile();
        receptionistStaff.setStaffCode("LT" + (System.currentTimeMillis() % 10000));
        receptionistStaff.setFullName("Lá»… tÃ¢n LÃª Thá»‹ Hoa");
        receptionistStaff.setUser(receptionistUser);
        receptionistStaff.setStatus(StaffStatus.ACTIVE);
        receptionistStaff = staffProfileRepository.save(receptionistStaff);

        StaffBranchAssignment recAssign = new StaffBranchAssignment();
        recAssign.setStaff(receptionistStaff);
        recAssign.setBranch(branch);
        recAssign.setRole(receptionistRole);
        recAssign.setStatus(AssignmentStatus.ACTIVE);
        recAssign.setStartDate(LocalDate.now());
        recAssign.setIsPrimary(true);
        staffBranchAssignmentRepository.save(recAssign);

        // 7. Táº¡o tÃ i khoáº£n & Há»“ sÆ¡ Bá»‡nh nhÃ¢n
        String patUsername = "pat.security." + System.currentTimeMillis();
        patientUser = new User();
        patientUser.setUsername(patUsername);
        patientUser.setPassword("$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5m2k7m2oYIev1q2Zk7xN5wQ1hJ7uS");
        patientUser.setEmail(patUsername + "@gmail.com");
        patientUser.setPhoneNumber("0933" + (System.currentTimeMillis() % 1000000));
        patientUser.setStatus("ACTIVE");
        patientUser.setIsActive(true);
        patientUser.setUserType(UserType.PATIENT);
        patientUser.setRoles(new HashSet<>(Set.of(patientRole)));
        patientUser = userRepository.saveAndFlush(patientUser);

        patient = new PatientProfile();
        patient.setUser(patientUser);
        patient.setPatientCode("BN" + (System.currentTimeMillis() % 100000));
        patient.setFullName("Trá»‹nh Thu Trang");
        patient.setPhoneNumber(patientUser.getPhoneNumber());
        patient.setAddress("Sá»‘ 45 XuÃ¢n Thá»§y, Cáº§u Giáº¥y, HÃ  Ná»™i");
        patient.setGender("Ná»¯");
        patient.setDob(LocalDate.of(1998, 10, 12));
        patient.setStatus("ACTIVE");
        patient.setPreferredBranch(branch);
        patient.setDepositBalance(BigDecimal.ZERO);
        patient = patientProfileRepository.save(patient);

        // 8. Táº¡o ca khÃ¡m Ä‘ang hoáº¡t Ä‘á»™ng (IN_EXAM)
        activeVisit = new Visit();
        activeVisit.setVisitCode("VIS" + System.currentTimeMillis());
        activeVisit.setPatientProfile(patient);
        activeVisit.setBranch(branch);
        activeVisit.setPrimaryDoctor(doctorStaff);
        activeVisit.setCheckInAt(LocalDateTime.now().minusMinutes(20));
        activeVisit.setStatus(VisitStatus.IN_EXAM);
        activeVisit.setVisitType(VisitType.WALK_IN);
        activeVisit.setInitialSymptoms("Äau nhá»©c Ãª buá»‘t vÃ¹ng rÄƒng hÃ m");
        activeVisit = visitRepository.save(activeVisit);

        ClinicalExamination exam = new ClinicalExamination();
        exam.setVisit(activeVisit);
        exam.setPatientProfile(patient);
        exam.setDoctor(doctorStaff);
        exam.setChiefComplaint(activeVisit.getInitialSymptoms());
        exam.setExamTime(LocalDateTime.now());
        clinicalExamRepository.save(exam);

        // 9. Dá»¯ liá»‡u Danh má»¥c bá»‡nh & Dá»‹ch vá»¥ & Thuá»‘c
        icd10Disease = icd10DiseaseRepository.findByIcd10Code("K02.1").orElseGet(() -> {
            Icd10Disease d = new Icd10Disease();
            d.setIcd10Code("K02.1");
            d.setDiseaseNameVi("SÃ¢u ngÃ  rÄƒng");
            d.setDiseaseNameEn("Caries of dentine");
            d.setIsDentalCommon(true);
            d.setIsActive(true);
            return icd10DiseaseRepository.save(d);
        });

        ServiceCategory cat = serviceCategoryRepository.findByCategoryCode("CAT_GEN").orElseGet(() -> {
            ServiceCategory sc = new ServiceCategory();
            sc.setCategoryCode("CAT_GEN");
            sc.setCategoryName("Äiá»u trá»‹ tá»•ng quÃ¡t");
            sc.setCategoryType(ServiceType.PROCEDURE);
            sc.setIsActive(true);
            return serviceCategoryRepository.save(sc);
        });

        dentalService = serviceRepository.findByServiceCode("SRV_COMP").orElseGet(() -> {
            com.cvdcms.entity.Service s = new com.cvdcms.entity.Service();
            s.setCategory(cat);
            s.setServiceCode("SRV_COMP");
            s.setServiceName("HÃ n rÄƒng tháº©m má»¹ Composite");
            s.setServiceType(ServiceType.PROCEDURE);
            s.setUnit("RÄƒng");
            s.setBasePrice(new BigDecimal("300000.00"));
            s.setStatus(ServiceStatus.ACTIVE);
            return serviceRepository.save(s);
        });

        medication = medicationRepository.findByDrugCodeIgnoreCase("MED-PARA-500").orElseGet(() -> {
            Medication m = new Medication();
            m.setDrugCode("MED-PARA-500");
            m.setDrugName("Paracetamol 500mg");
            m.setUnit("ViÃªn");
            m.setUsageInstruction("1 viÃªn khi Ä‘au");
            m.setSalePrice(new BigDecimal("3000.00"));
            m.setIsActive(true);
            return medicationRepository.save(m);
        });
    }

    // =========================================================================
    // 1. BÃC SÄ¨ (BAC_SI): ÄÆ¯á»¢C PHÃ‰P THá»°C HIá»†N CÃC THAO TÃC LÃ‚M SÃ€NG
    // =========================================================================

    @Test
    @DisplayName("RBAC BÃ¡c sÄ©: ÄÆ°á»£c phÃ©p ghi nháº­n cháº©n Ä‘oÃ¡n ICD-10 (HTTP 201 Created)")
    void doctorAuthorizedToAddDiagnosis() throws Exception {
        DiagnosisRequestDTO request = DiagnosisRequestDTO.builder()
                .visitId(activeVisit.getVisitId())
                .icd10Code(icd10Disease.getIcd10Code())
                .diagnosisType(DiagnosisType.MAIN)
                .toothNumbers("36 (Máº·t O)")
                .notes("Cháº©n Ä‘oÃ¡n chÃ­nh xÃ¡c thá»±c bá»Ÿi BÃ¡c sÄ©")
                .build();

        mockMvc.perform(post("/api/v1/diagnoses")
                        .sessionAttr("loggedInUser", doctorUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.icd10Code").value("K02.1"))
                .andExpect(jsonPath("$.toothNumbers").value("36 (Máº·t O)"));
    }

    @Test
    @DisplayName("RBAC BÃ¡c sÄ©: ÄÆ°á»£c phÃ©p kÃª Ä‘Æ¡n thuá»‘c (HTTP 201 Created)")
    void doctorAuthorizedToCreatePrescription() throws Exception {
        PrescriptionItemRequestDTO item = PrescriptionItemRequestDTO.builder()
                .medicationId(medication.getMedicationId())
                .drugName(medication.getDrugName())
                .dosage("1 viÃªn khi Ä‘au")
                .quantity(new BigDecimal("10"))
                .unit("ViÃªn")
                .unitPrice(medication.getSalePrice())
                .build();

        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
                .visitId(activeVisit.getVisitId())
                .doctorId(doctorStaff.getStaffId())
                .prescriptionTitle("ÄÆ¡n thuá»‘c giáº£m Ä‘au sau khÃ¡m")
                .items(List.of(item))
                .build();

        mockMvc.perform(post("/api/doctor/prescriptions")
                        .sessionAttr("loggedInUser", doctorUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("RBAC BÃ¡c sÄ©: ÄÆ°á»£c phÃ©p ghi nháº­n dá»‹ch vá»¥ thá»±c hiá»‡n (HTTP 201 Created)")
    void doctorAuthorizedToCreatePerformedService() throws Exception {
        PerformedServiceRequestDTO request = PerformedServiceRequestDTO.builder()
                .visitId(activeVisit.getVisitId())
                .serviceId(dentalService.getServiceId())
                .doctorId(doctorStaff.getStaffId())
                .toothNumbers("36")
                .quantity(BigDecimal.ONE)
                .unitPrice(dentalService.getBasePrice())
                .finalPrice(dentalService.getBasePrice())
                .build();

        mockMvc.perform(post("/api/doctor/performed-services")
                        .sessionAttr("loggedInUser", doctorUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("RBAC BÃ¡c sÄ©: ÄÆ°á»£c phÃ©p lÆ°u khÃ¡m tá»•ng quÃ¡t & triá»‡u chá»©ng lÃ¢m sÃ ng (HTTP 200 OK)")
    void doctorAuthorizedToSaveGeneralExamination() throws Exception {
        GeneralExaminationRequestDTO request = GeneralExaminationRequestDTO.builder()
                .visitId(activeVisit.getVisitId())
                .initialSymptoms("KhÃ¡m Ä‘á»‹nh ká»³ vÃ  Ä‘au nháº¹ rÄƒng 36")
                .hasAllergies(false)
                .tmjStatus("NORMAL")
                .hygieneStatus("GOOD")
                .gingivaStatus("HEALTHY")
                .build();

        mockMvc.perform(post("/api/doctor/visits/" + activeVisit.getVisitId() + "/general-examination")
                        .sessionAttr("loggedInUser", doctorUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // =========================================================================
    // 2. Lá»„ TÃ‚N (LE_TAN): Cá» TÃŒNH Gá»ŒI API LÃ‚M SÃ€NG BÃC SÄ¨ -> 403 FORBIDDEN
    // =========================================================================

    @Test
    @DisplayName("RBAC Lá»… tÃ¢n: Cá»‘ tÃ¬nh gá»i API kÃª Ä‘Æ¡n thuá»‘c bá»‹ cháº·n HTTP 403 Forbidden")
    void receptionistCannotCreatePrescriptionForbidden() throws Exception {
        PrescriptionItemRequestDTO item = PrescriptionItemRequestDTO.builder()
                .drugName("Thuá»‘c cáº¥m kÃª")
                .quantity(new BigDecimal("10"))
                .unit("ViÃªn")
                .build();

        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
                .visitId(activeVisit.getVisitId())
                .doctorId(doctorStaff.getStaffId())
                .prescriptionTitle("Lá»… tÃ¢n cá»‘ tÃ¬nh kÃª Ä‘Æ¡n")
                .items(List.of(item))
                .build();

        // Lá»… tÃ¢n thiáº¿u quyá»n PRESCRIPTION_WRITE -> Interceptor cháº·n vÃ  tráº£ vá» HTTP 403
        mockMvc.perform(post("/api/doctor/prescriptions")
                        .sessionAttr("loggedInUser", receptionistUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("RBAC Lá»… tÃ¢n: Cá»‘ tÃ¬nh gá»i API thÃªm cháº©n Ä‘oÃ¡n ICD-10 bá»‹ cháº·n HTTP 403 Forbidden")
    void receptionistCannotAddDiagnosisForbidden() throws Exception {
        DiagnosisRequestDTO request = DiagnosisRequestDTO.builder()
                .visitId(activeVisit.getVisitId())
                .icd10Code(icd10Disease.getIcd10Code())
                .diagnosisType(DiagnosisType.MAIN)
                .toothNumbers("36")
                .build();

        // Lá»… tÃ¢n thiáº¿u quyá»n CLINICAL_WORKFLOWS / CLINICAL_DAILY_VISITS -> 403 Forbidden
        mockMvc.perform(post("/api/v1/diagnoses")
                        .sessionAttr("loggedInUser", receptionistUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("RBAC Lá»… tÃ¢n: Cá»‘ tÃ¬nh gá»i API ghi nháº­n dá»‹ch vá»¥ thá»±c hiá»‡n bá»‹ cháº·n HTTP 403 Forbidden")
    void receptionistCannotCreatePerformedServiceForbidden() throws Exception {
        PerformedServiceRequestDTO request = PerformedServiceRequestDTO.builder()
                .visitId(activeVisit.getVisitId())
                .serviceId(dentalService.getServiceId())
                .doctorId(doctorStaff.getStaffId())
                .quantity(BigDecimal.ONE)
                .build();

        // Lá»… tÃ¢n khÃ´ng cÃ³ quyá»n CLINICAL_WORKFLOWS -> 403 Forbidden
        mockMvc.perform(post("/api/doctor/performed-services")
                        .sessionAttr("loggedInUser", receptionistUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // 3. Bá»†NH NHÃ‚N (BENH_NHAN): Cá» TÃŒNH Gá»ŒI API LÃ‚M SÃ€NG -> 403 FORBIDDEN
    // =========================================================================

    @Test
    @DisplayName("RBAC Bá»‡nh nhÃ¢n: Cá»‘ tÃ¬nh gá»i API kÃª Ä‘Æ¡n thuá»‘c bá»‹ cháº·n HTTP 403 Forbidden")
    void patientCannotCreatePrescriptionForbidden() throws Exception {
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
                .visitId(activeVisit.getVisitId())
                .prescriptionTitle("Bá»‡nh nhÃ¢n tá»± kÃª Ä‘Æ¡n")
                .build();

        mockMvc.perform(post("/api/doctor/prescriptions")
                        .sessionAttr("loggedInUser", patientUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("RBAC Bá»‡nh nhÃ¢n: Cá»‘ tÃ¬nh gá»i API sá»­a cháº©n Ä‘oÃ¡n bá»‹ cháº·n HTTP 403 Forbidden")
    void patientCannotAddDiagnosisForbidden() throws Exception {
        DiagnosisRequestDTO request = DiagnosisRequestDTO.builder()
                .visitId(activeVisit.getVisitId())
                .icd10Code(icd10Disease.getIcd10Code())
                .diagnosisType(DiagnosisType.MAIN)
                .build();

        mockMvc.perform(post("/api/v1/diagnoses")
                        .sessionAttr("loggedInUser", patientUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // 4. BÃC SÄ¨ (BAC_SI): Cá» TÃŒNH CAN THIá»†P Sá»¬A DANH Má»¤C GIÃ Gá»C FT02 -> 403 FORBIDDEN
    // =========================================================================

    @Test
    @DisplayName("RBAC BÃ¡c sÄ©: Cá»‘ tÃ¬nh truy cáº­p trang quáº£n lÃ½ báº£ng giÃ¡ FT02 bá»‹ cháº·n HTTP 403 Forbidden")
    void doctorCannotAccessAdminServiceCatalogGetForbidden() throws Exception {
        // Interceptor phÃ¡t hiá»‡n tÃ i khoáº£n khÃ´ng cÃ³ quyá»n CATALOG_SERVICES / CATALOG_MANAGE -> 403 Forbidden
        mockMvc.perform(get("/admin/services")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .sessionAttr("loggedInUser", doctorUser))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("RBAC BÃ¡c sÄ©: Cá»‘ tÃ¬nh thÃªm dá»‹ch vá»¥ má»›i vÃ o danh má»¥c FT02 bá»‹ cháº·n HTTP 403 Forbidden")
    void doctorCannotCreateServiceInCatalogForbidden() throws Exception {
        mockMvc.perform(post("/admin/services")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .sessionAttr("loggedInUser", doctorUser)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("serviceCode", "DV-HACK-01")
                        .param("serviceName", "Dá»‹ch vá»¥ do BÃ¡c sÄ© tá»± táº¡o trÃ¡i phÃ©p")
                        .param("basePrice", "100000.00"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("RBAC BÃ¡c sÄ©: Cá»‘ tÃ¬nh sá»­a Ä‘á»•i giÃ¡ gá»‘c dá»‹ch vá»¥ FT02 bá»‹ cháº·n HTTP 403 Forbidden")
    void doctorCannotUpdateServicePriceInCatalogForbidden() throws Exception {
        mockMvc.perform(post("/admin/services/" + dentalService.getServiceId() + "/update")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .sessionAttr("loggedInUser", doctorUser)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("serviceCode", dentalService.getServiceCode())
                        .param("serviceName", "Sá»­a Ä‘á»•i giÃ¡ dá»‹ch vá»¥")
                        .param("basePrice", "50000.00"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("RBAC BÃ¡c sÄ©: Truy cáº­p trang quáº£n trá»‹ FT02 qua Web UI thÃ´ng thÆ°á»ng bá»‹ chuyá»ƒn hÆ°á»›ng 302 vá» /home")
    void doctorAccessingAdminServicesViaWebUiRedirectsToHome() throws Exception {
        mockMvc.perform(get("/admin/services")
                        .sessionAttr("loggedInUser", doctorUser))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/home"));
    }

    // =========================================================================
    // 5. GUEST (CHÆ¯A ÄÄ‚NG NHáº¬P): TRUY Cáº¬P TÃ€I NGUYÃŠN Bá»Š Tá»ª CHá»I
    // =========================================================================

    @Test
    @DisplayName("Security: Guest chÆ°a Ä‘Äƒng nháº­p truy cáº­p trang danh sÃ¡ch khÃ¡m bá»‹ chuyá»ƒn hÆ°á»›ng /login")
    void guestAccessToDoctorPageRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/admin/services"))
                .andExpect(status().is3xxRedirection());
    }
}

