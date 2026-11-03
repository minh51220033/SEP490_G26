package com.cvdcms.service;

import com.cvdcms.DTO.*;
import com.cvdcms.emr.EmrModuleService;
import com.cvdcms.emr.EmrRecord;
import com.cvdcms.emr.EmrRecordRepository;
import com.cvdcms.emr.EmrRecordStatus;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Test kiá»ƒm tra luá»“ng khÃ¡m lÃ¢m sÃ ng liÃªn hoÃ n (End-to-End) vÃ 
 * tÃ­nh toÃ n váº¹n Transaction (Rollback & Data Integrity) cá»§a Role BÃ¡c sÄ©.
 *
 * Tham chiáº¿u chuáº©n:
 * - src/test/java/com/cvdcms/service/AppointmentPhase3IntegrationTest.java
 * - FT10 (Tiáº¿p nháº­n ca khÃ¡m), FT11 (KhÃ¡m lÃ¢m sÃ ng), FT12 (X-ray), FT13 (Labo),
 *   FT14 (Cháº©n Ä‘oÃ¡n ICD-10 & Odontogram), FT16 (Thá»±c hiá»‡n dá»‹ch vá»¥),
 *   FT18 (KÃª Ä‘Æ¡n & Háº¹n tÃ¡i khÃ¡m), FT21 (Sáºµn sÃ ng láº­p hÃ³a Ä‘Æ¡n viá»‡n phÃ­),
 *   EMR (Bá»‡nh Ã¡n Ä‘iá»‡n tá»­ & KhÃ³a báº¥t biáº¿n BR-EMR-02).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:doctor_clinical_e2e_db;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;NON_KEYWORDS=MONTH,YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class DoctorClinicalWorkflowIntegrationTest {

    @Autowired private BranchRepository branchRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private StaffProfileRepository staffProfileRepository;
    @Autowired private StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    @Autowired private PatientProfileRepository patientProfileRepository;
    @Autowired private VisitRepository visitRepository;
    @Autowired private ClinicalExaminationRepository clinicalExamRepository;
    @Autowired private Icd10DiseaseRepository icd10DiseaseRepository;
    @Autowired private ServiceCategoryRepository serviceCategoryRepository;
    @Autowired private ServiceRepository serviceRepository;
    @Autowired private MedicationRepository medicationRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private NanopixXraySyncLogRepository nanopixXraySyncLogRepository;
    @Autowired private ExaminationDiagnosisRepository diagnosisRepository;
    @Autowired private PerformedServiceRepository performedServiceRepository;
    @Autowired private PrescriptionRepository prescriptionRepository;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private EmrRecordRepository emrRecordRepository;

    @Autowired private DiagnosisService diagnosisService;
    @Autowired private PerformedServiceService performedServiceService;
    @Autowired private PrescriptionService prescriptionService;
    @Autowired private ReExaminationService reExaminationService;
    @Autowired private LaboOrderService laboOrderService;
    @Autowired private NanopixXrayService nanopixXrayService;
    @Autowired private InvoiceService invoiceService;
    @Autowired private EmrModuleService emrModuleService;

    private Branch branch;
    private User doctorUser;
    private StaffProfile doctorStaff;
    private User patientUser;
    private PatientProfile patient;
    private Icd10Disease diseaseK021;
    private Icd10Disease diseaseK050;
    private com.cvdcms.entity.Service compositeService;
    private Medication amoxMed;
    private Medication paraMed;
    private Supplier laboSupplier;

    @BeforeEach
    void setupFixtures() {
        // 1. Táº¡o cÆ¡ sá»Ÿ phÃ²ng khÃ¡m (Branch)
        branch = branchRepository.findAll().stream().findFirst().orElseGet(() -> {
            Branch b = new Branch();
            b.setBranchCode("CN-HANOI-01");
            b.setBranchName("Nha Khoa SmileCare HÃ  Ná»™i");
            b.setAddress("123 Phá»‘ Huáº¿, Hai BÃ  TrÆ°ng, HÃ  Ná»™i");
            b.setPhone("02439998888");
            b.setOpeningTime(LocalTime.of(8, 0));
            b.setClosingTime(LocalTime.of(20, 0));
            b.setWorkingDays("2,3,4,5,6,7,CN");
            b.setIsActive(true);
            return branchRepository.save(b);
        });

        // 2. Táº¡o vai trÃ² BÃ¡c sÄ© (Role BAC_SI)
        Role doctorRole = roleRepository.findByRoleName("BAC_SI").orElseGet(() -> {
            Role r = new Role();
            r.setRoleName("BAC_SI");
            r.setDescription("BÃ¡c sÄ© Ä‘iá»u trá»‹ nha khoa");
            r.setIsActive(true);
            return roleRepository.save(r);
        });

        // 3. Táº¡o tÃ i khoáº£n BÃ¡c sÄ© (Doctor User)
        String doctorUsername = "dr.workflow." + System.currentTimeMillis();
        doctorUser = new User();
        doctorUser.setUsername(doctorUsername);
        doctorUser.setPassword("$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5m2k7m2oYIev1q2Zk7xN5wQ1hJ7uS");
        doctorUser.setEmail(doctorUsername + "@smilecare.vn");
        doctorUser.setPhoneNumber("0988" + (System.currentTimeMillis() % 1000000));
        doctorUser.setStatus("ACTIVE");
        doctorUser.setIsActive(true);
        doctorUser.setUserType(UserType.STAFF);
        doctorUser.setBranch(branch);
        doctorUser.setRoles(Set.of(doctorRole));
        doctorUser = userRepository.save(doctorUser);

        // 4. Táº¡o há»“ sÆ¡ nhÃ¢n viÃªn y táº¿ (StaffProfile cho BÃ¡c sÄ©)
        doctorStaff = new StaffProfile();
        doctorStaff.setStaffCode("BS" + (System.currentTimeMillis() % 10000));
        doctorStaff.setFullName("BÃ¡c sÄ© CKI Nguyá»…n VÄƒn Minh");
        doctorStaff.setUser(doctorUser);
        doctorStaff.setGender(StaffGender.MALE);
        doctorStaff.setStatus(StaffStatus.ACTIVE);
        doctorStaff.setDepartment("Khoa Äiá»u trá»‹ tá»•ng quÃ¡t");
        doctorStaff.setPositionTitle("BÃ¡c sÄ© Ä‘iá»u trá»‹ chÃ­nh");
        doctorStaff = staffProfileRepository.save(doctorStaff);

        // PhÃ¢n bá»• cÆ¡ sá»Ÿ
        StaffBranchAssignment assignment = new StaffBranchAssignment();
        assignment.setStaff(doctorStaff);
        assignment.setBranch(branch);
        assignment.setRole(doctorRole);
        assignment.setStatus(AssignmentStatus.ACTIVE);
        assignment.setStartDate(LocalDate.now());
        assignment.setIsPrimary(true);
        staffBranchAssignmentRepository.save(assignment);

        // 5. Táº¡o tÃ i khoáº£n & Há»“ sÆ¡ bá»‡nh nhÃ¢n (PatientProfile)
        String patientUsername = "patient." + System.currentTimeMillis();
        patientUser = new User();
        patientUser.setUsername(patientUsername);
        patientUser.setPassword("$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5m2k7m2oYIev1q2Zk7xN5wQ1hJ7uS");
        patientUser.setEmail(patientUsername + "@gmail.com");
        patientUser.setPhoneNumber("0912" + (System.currentTimeMillis() % 1000000));
        patientUser.setStatus("ACTIVE");
        patientUser.setIsActive(true);
        patientUser.setUserType(UserType.PATIENT);
        patientUser = userRepository.save(patientUser);

        patient = new PatientProfile();
        patient.setUser(patientUser);
        patient.setPatientCode("BN" + (System.currentTimeMillis() % 100000));
        patient.setFullName("LÃª HoÃ ng Yáº¿n");
        patient.setPhoneNumber(patientUser.getPhoneNumber());
        patient.setAddress("Sá»‘ 12 Cáº§u Giáº¥y, HÃ  Ná»™i");
        patient.setGender("Ná»¯");
        patient.setDob(LocalDate.of(1995, 5, 20));
        patient.setStatus("ACTIVE");
        patient.setPreferredBranch(branch);
        patient.setDepositBalance(BigDecimal.ZERO);
        patient = patientProfileRepository.save(patient);

        // 6. Danh má»¥c bá»‡nh ICD-10
        diseaseK021 = icd10DiseaseRepository.findByIcd10Code("K02.1").orElseGet(() -> {
            Icd10Disease d = new Icd10Disease();
            d.setIcd10Code("K02.1");
            d.setDiseaseNameVi("SÃ¢u ngÃ  rÄƒng");
            d.setDiseaseNameEn("Caries of dentine");
            d.setIsDentalCommon(true);
            d.setIsActive(true);
            return icd10DiseaseRepository.save(d);
        });

        diseaseK050 = icd10DiseaseRepository.findByIcd10Code("K05.0").orElseGet(() -> {
            Icd10Disease d = new Icd10Disease();
            d.setIcd10Code("K05.0");
            d.setDiseaseNameVi("ViÃªm nÆ°á»›u cáº¥p");
            d.setDiseaseNameEn("Acute gingivitis");
            d.setIsDentalCommon(true);
            d.setIsActive(true);
            return icd10DiseaseRepository.save(d);
        });

        // 7. NhÃ³m danh má»¥c vÃ  Dá»‹ch vá»¥ nha khoa
        ServiceCategory category = serviceCategoryRepository.findByCategoryCode("NHAKHOA_DT").orElseGet(() -> {
            ServiceCategory sc = new ServiceCategory();
            sc.setCategoryCode("NHAKHOA_DT");
            sc.setCategoryName("Nha khoa Ä‘iá»u trá»‹ phá»¥c há»“i");
            sc.setCategoryType(ServiceType.PROCEDURE);
            sc.setIsActive(true);
            return serviceCategoryRepository.save(sc);
        });

        compositeService = serviceRepository.findByServiceCode("DV-TRAM-01").orElseGet(() -> {
            com.cvdcms.entity.Service s = new com.cvdcms.entity.Service();
            s.setCategory(category);
            s.setServiceCode("DV-TRAM-01");
            s.setServiceName("TrÃ¡m rÄƒng tháº©m má»¹ Composite");
            s.setServiceType(ServiceType.PROCEDURE);
            s.setUnit("RÄƒng");
            s.setBasePrice(new BigDecimal("350000.00"));
            s.setRequiresTooth(true);
            s.setStatus(ServiceStatus.ACTIVE);
            return serviceRepository.save(s);
        });

        // 8. Thuá»‘c nha khoa
        amoxMed = medicationRepository.findByDrugCodeIgnoreCase("MED-AMOX-500").orElseGet(() -> {
            Medication m = new Medication();
            m.setDrugCode("MED-AMOX-500");
            m.setDrugName("Amoxicillin 500mg");
            m.setUnit("ViÃªn");
            m.setUsageInstruction("1 viÃªn x 3 láº§n/ngÃ y");
            m.setSalePrice(new BigDecimal("5000.00"));
            m.setIsActive(true);
            return medicationRepository.save(m);
        });

        paraMed = medicationRepository.findByDrugCodeIgnoreCase("MED-PARA-500").orElseGet(() -> {
            Medication m = new Medication();
            m.setDrugCode("MED-PARA-500");
            m.setDrugName("Paracetamol 500mg");
            m.setUnit("ViÃªn");
            m.setUsageInstruction("1 viÃªn khi Ä‘au");
            m.setSalePrice(new BigDecimal("3000.00"));
            m.setIsActive(true);
            return medicationRepository.save(m);
        });

        // 9. NhÃ  cung cáº¥p Labo ngoÃ i
        laboSupplier = supplierRepository.findBySupplierCodeIgnoreCase("LAB-DENT-01").orElseGet(() -> {
            Supplier sup = new Supplier();
            sup.setSupplierCode("LAB-DENT-01");
            sup.setSupplierName("Trung tÃ¢m Labo Nha khoa Ká»¹ thuáº­t sá»‘");
            sup.setTaxCode("0109988776");
            sup.setPhoneNumber("02438889999");
            sup.setAddress("Äá»‘ng Äa, HÃ  Ná»™i");
            sup.setIsActive(true);
            return supplierRepository.save(sup);
        });
    }

    private Visit createInitialWaitingVisit() {
        Visit v = new Visit();
        v.setVisitCode("VIS" + System.currentTimeMillis());
        v.setPatientProfile(patient);
        v.setBranch(branch);
        v.setPrimaryDoctor(doctorStaff);
        v.setCheckInAt(LocalDateTime.now().minusMinutes(30));
        v.setStatus(VisitStatus.WAITING);
        v.setVisitType(VisitType.WALK_IN);
        v.setInitialSymptoms("Äau buá»‘t rÄƒng hÃ m dÆ°á»›i khi nhai");
        return visitRepository.save(v);
    }

    @Test
    @DisplayName("Ká»‹ch báº£n 3a (Happy Path E2E): Luá»“ng khÃ¡m hoÃ n chá»‰nh tá»« Tiáº¿p nháº­n -> KhÃ¡m -> CLS/Labo -> ICD-10 -> Thá»§ thuáº­t -> KÃª Ä‘Æ¡n & TÃ¡i khÃ¡m -> HoÃ n táº¥t WAITING_PAYMENT & EMR")
    void doctorClinicalWorkflowCompleteEndToEndHappyPath() throws Exception {
        // =========================================================================
        // BÆ°á»›c 1: Tiáº¿p nháº­n Visit tá»« hÃ ng Ä‘á»£i (FT10)
        // =========================================================================
        Visit initialVisit = createInitialWaitingVisit();
        assertEquals(VisitStatus.WAITING, initialVisit.getStatus(), "Ban Ä‘áº§u ca khÃ¡m á»Ÿ tráº¡ng thÃ¡i hÃ ng chá» (WAITING)");

        // BÃ¡c sÄ© má»Ÿ ca khÃ¡m táº¡i gháº¿ nha -> Tráº¡ng thÃ¡i chuyá»ƒn sang Äang khÃ¡m (IN_EXAM)
        initialVisit.setStatus(VisitStatus.IN_EXAM);
        final Visit visit = visitRepository.save(initialVisit);
        assertEquals(VisitStatus.IN_EXAM, visit.getStatus(), "BÃ¡c sÄ© tiáº¿p nháº­n ca khÃ¡m chuyá»ƒn tráº¡ng thÃ¡i sang IN_EXAM");

        // Äáº£m báº£o táº¡o Ä‘á»£t khÃ¡m lÃ¢m sÃ ng ClinicalExamination ban Ä‘áº§u
        ClinicalExamination exam = clinicalExamRepository.findByVisit_VisitId(visit.getVisitId()).orElseGet(() -> {
            ClinicalExamination ce = new ClinicalExamination();
            ce.setVisit(visit);
            ce.setPatientProfile(patient);
            ce.setDoctor(doctorStaff);
            ce.setChiefComplaint(visit.getInitialSymptoms());
            ce.setExamTime(LocalDateTime.now());
            return clinicalExamRepository.save(ce);
        });
        assertNotNull(exam.getExamId(), "Báº£n ghi khÃ¡m lÃ¢m sÃ ng ClinicalExamination pháº£i Ä‘Æ°á»£c khá»Ÿi táº¡o");

        // =========================================================================
        // BÆ°á»›c 2: BÃ¡c sÄ© cáº­p nháº­t triá»‡u chá»©ng lÃ¢m sÃ ng & tiá»n sá»­ y khoa (FT11)
        // =========================================================================
        GeneralExaminationRequestDTO generalExamDTO = GeneralExaminationRequestDTO.builder()
                .visitId(visit.getVisitId())
                .initialSymptoms("Äau buá»‘t rÄƒng 46 khi Äƒn Ä‘á»“ ngá»t/láº¡nh, cáº£m giÃ¡c Ãª buá»‘t kÃ©o dÃ i 10-15 giÃ¢y")
                .hasAllergies(true)
                .allergyDetails("Dá»‹ á»©ng Penicillin")
                .hasCardiovascularDisease(false)
                .hasDiabetes(false)
                .hasBleedingDisorder(false)
                .isPregnant(false)
                .tmjStatus("NORMAL")
                .hygieneStatus("MEDIUM")
                .gingivaStatus("GINGIVITIS")
                .build();

        diagnosisService.saveGeneralExamination(generalExamDTO);

        Visit updatedVisitAfterExam = visitRepository.findById(visit.getVisitId()).orElseThrow();
        assertEquals("Äau buá»‘t rÄƒng 46 khi Äƒn Ä‘á»“ ngá»t/láº¡nh, cáº£m giÃ¡c Ãª buá»‘t kÃ©o dÃ i 10-15 giÃ¢y",
                updatedVisitAfterExam.getInitialSymptoms(), "Triá»‡u chá»©ng lÃ¢m sÃ ng trÃªn Visit pháº£i Ä‘Æ°á»£c cáº­p nháº­t");

        PatientProfile updatedPatient = patientProfileRepository.findById(patient.getProfileId()).orElseThrow();
        assertTrue(updatedPatient.getHasAllergies(), "Cá» dá»‹ á»©ng cá»§a bá»‡nh nhÃ¢n pháº£i Ä‘Æ°á»£c lÆ°u TRUE");
        assertEquals("Dá»‹ á»©ng Penicillin", updatedPatient.getAllergyDetails(), "Chi tiáº¿t dá»‹ á»©ng pháº£i Ä‘Æ°á»£c lÆ°u");

        // =========================================================================
        // BÆ°á»›c 3: Chá»‰ Ä‘á»‹nh Labo ngoÃ i (FT13) vÃ  Ä‘Ã­nh kÃ¨m/Ä‘á»‘i chiáº¿u áº£nh X-ray (FT12)
        // =========================================================================
        // 3.1. Chá»‰ Ä‘á»‹nh Labo cháº¿ tÃ¡c rÄƒng sá»© ngoÃ i
        LaboOrderRequestDTO laboReq = LaboOrderRequestDTO.builder()
                .visitId(visit.getVisitId())
                .profileId(patient.getProfileId())
                .branchId(branch.getBranchId())
                .doctorId(doctorStaff.getStaffId())
                .supplierId(laboSupplier.getSupplierId())
                .customerName(patient.getFullName())
                .customerPhone(patient.getPhoneNumber())
                .orderType(LaboOrderType.NEW)
                .materialType("MÃ£o toÃ n sá»© Cercon HT")
                .toothPositions("46")
                .shadeColor("A2")
                .quantity(1)
                .costToClinic(new BigDecimal("1200000.00"))
                .sentDate(LocalDate.now())
                .expectedDate(LocalDate.now().plusDays(3))
                .status(LaboOrderStatus.SENT)
                .notes("Láº¥y dáº¥u phá»¥c hÃ¬nh rÄƒng 46, bá» phá»¥c hÃ¬nh dÆ°á»›i nÆ°á»›u 0.5mm")
                .build();

        LaboOrderResponseDTO laboOrder = laboOrderService.createOrder(laboReq, null);
        assertNotNull(laboOrder.getLaboId(), "Phiáº¿u gá»­i xÆ°á»Ÿng Labo pháº£i Ä‘Æ°á»£c táº¡o thÃ nh cÃ´ng vá»›i ID há»£p lá»‡");
        assertEquals("46", laboOrder.getToothPositions());
        assertEquals("MÃ£o toÃ n sá»© Cercon HT", laboOrder.getMaterialType());

        // 3.2. ÄÃ­nh kÃ¨m vÃ  Ä‘á»‘i chiáº¿u káº¿t quáº£ phim chá»¥p X-quang NanoPix
        NanopixXraySyncLog xRayLog = new NanopixXraySyncLog();
        xRayLog.setExternalStudyUid("STUDY-XRAY-" + System.currentTimeMillis());
        xRayLog.setRawPatientName(patient.getFullName());
        xRayLog.setImageUrl("/uploads/xray/2026/periapical_46.png");
        xRayLog.setSyncStatus(NanopixSyncStatus.UNMATCHED);
        xRayLog.setDeviceSerialNumber("EN1E0408A032");
        xRayLog.setDeviceModel("NanoPix1");
        xRayLog.setToothNumbers("46");
        xRayLog.setCapturedAt(LocalDateTime.now());
        xRayLog.setSyncTime(LocalDateTime.now());
        xRayLog = nanopixXraySyncLogRepository.save(xRayLog);

        NanopixReconciliationRequestDTO reconcileReq = NanopixReconciliationRequestDTO.builder()
                .profileId(patient.getProfileId())
                .visitId(visit.getVisitId())
                .toothNumbers("46")
                .notes("Phim cáº­n chÃ³p rÄƒng 46: Tháº¥u quang ngÃ  sÃ¢u sÃ¡t tá»§y, vÃ¹ng cuá»‘ng chÆ°a cÃ³ tá»•n thÆ°Æ¡ng quanh chÃ³p")
                .build();

        NanopixXrayResponseDTO reconciledXRay = nanopixXrayService.reconcileXray(
                xRayLog.getSyncLogId(), reconcileReq, doctorUser.getUserId());
        assertNotNull(reconciledXRay);
        assertEquals(NanopixSyncStatus.MATCHED.name(), reconciledXRay.getSyncStatus(),
                "Phim X-quang pháº£i Ä‘Æ°á»£c Ä‘á»•i tráº¡ng thÃ¡i MATCHED sau khi Ä‘á»‘i chiáº¿u");
        assertEquals(visit.getVisitId(), reconciledXRay.getVisitId(),
                "Phim X-quang pháº£i Ä‘Æ°á»£c liÃªn káº¿t Ä‘Ãºng vá»›i ca khÃ¡m hiá»‡n táº¡i");

        // =========================================================================
        // BÆ°á»›c 4: LÆ°u cháº©n Ä‘oÃ¡n ICD-10 vÃ  Odontogram sÆ¡ Ä‘á»“ rÄƒng (FT14)
        // =========================================================================
        // Cháº©n Ä‘oÃ¡n chÃ­nh: K02.1 - SÃ¢u ngÃ  rÄƒng, vá»‹ trÃ­ RÄƒng 46 (Máº·t O - Nhai)
        DiagnosisRequestDTO diagMainReq = DiagnosisRequestDTO.builder()
                .visitId(visit.getVisitId())
                .icd10Code(diseaseK021.getIcd10Code())
                .diagnosisType(DiagnosisType.MAIN)
                .toothNumbers("46 (Máº·t O)")
                .notes("SÃ¢u ngÃ  sÃ¢u sÃ¡t tá»§y rÄƒng 46, tá»§y cÃ²n sá»‘ng")
                .build();

        DiagnosisResponseDTO mainDiag = diagnosisService.addDiagnosis(diagMainReq);
        assertNotNull(mainDiag.getDiagnosisId(), "Cháº©n Ä‘oÃ¡n chÃ­nh pháº£i Ä‘Æ°á»£c lÆ°u vá»›i ID há»£p lá»‡");
        assertEquals("K02.1", mainDiag.getIcd10Code());
        assertEquals("46 (Máº·t O)", mainDiag.getToothNumbers());
        assertEquals(DiagnosisType.MAIN, mainDiag.getDiagnosisType());

        // Cháº©n Ä‘oÃ¡n phá»¥: K05.0 - ViÃªm nÆ°á»›u cáº¥p
        DiagnosisRequestDTO diagSecReq = DiagnosisRequestDTO.builder()
                .visitId(visit.getVisitId())
                .icd10Code(diseaseK050.getIcd10Code())
                .diagnosisType(DiagnosisType.SECONDARY)
                .toothNumbers("ToÃ n hÃ m")
                .notes("NÆ°á»›u viá»n Ä‘á» nháº¹, cÃ³ máº£ng bÃ¡m vÃ´i rÄƒng")
                .build();

        DiagnosisResponseDTO secDiag = diagnosisService.addDiagnosis(diagSecReq);
        assertNotNull(secDiag.getDiagnosisId());
        assertEquals(DiagnosisType.SECONDARY, secDiag.getDiagnosisType());

        List<DiagnosisResponseDTO> visitDiagnoses = diagnosisService.getDiagnosesByVisitId(visit.getVisitId());
        assertEquals(2, visitDiagnoses.size(), "LÆ°á»£t khÃ¡m pháº£i cÃ³ Ä‘á»§ 2 cháº©n Ä‘oÃ¡n (01 chÃ­nh, 01 phá»¥)");

        // =========================================================================
        // BÆ°á»›c 5: LÆ°u dá»‹ch vá»¥ thá»±c táº¿ Ä‘Ã£ lÃ m (FT16) -> Dá»¯ liá»‡u sáºµn sÃ ng cho HÃ³a Ä‘Æ¡n (FT21)
        // =========================================================================
        PerformedServiceRequestDTO psReq = PerformedServiceRequestDTO.builder()
                .visitId(visit.getVisitId())
                .serviceId(compositeService.getServiceId())
                .doctorId(doctorStaff.getStaffId())
                .toothNumbers("46")
                .quantity(BigDecimal.ONE)
                .unitPrice(compositeService.getBasePrice())
                .discountAmount(BigDecimal.ZERO)
                .finalPrice(compositeService.getBasePrice())
                .clinicalNotes("TrÃ¡m Composite xoang I rÄƒng 46, Ä‘Ã¡nh bÃ³ng hoÃ n táº¥t, khá»›p cáº¯n chuáº©n")
                .build();

        PerformedServiceResponseDTO performedService = performedServiceService.createPerformedService(psReq);
        assertNotNull(performedService.getPerformedId(), "Dá»‹ch vá»¥ thá»±c hiá»‡n pháº£i Ä‘Æ°á»£c lÆ°u thÃ nh cÃ´ng");
        assertEquals("COMPLETED", performedService.getStatus(), "Tráº¡ng thÃ¡i thá»§ thuáº­t pháº£i lÃ  COMPLETED");
        assertEquals("46", performedService.getToothNumbers());
        assertEquals(0, new BigDecimal("350000.00").compareTo(performedService.getFinalPrice()));

        // FT21: XÃ¡c nháº­n dá»¯ liá»‡u Ä‘Ã£ sáºµn sÃ ng Ä‘á»ƒ bá»™ pháº­n Thu ngÃ¢n/Káº¿ toÃ¡n xuáº¥t hÃ³a Ä‘Æ¡n viá»‡n phÃ­
        List<PerformedService> unbilledServices = invoiceService.getUnbilledPerformedServices(visit.getVisitId());
        assertFalse(unbilledServices.isEmpty(), "Dá»¯ liá»‡u dá»‹ch vá»¥ thá»±c hiá»‡n pháº£i sáºµn sÃ ng cho phÃ¢n há»‡ HÃ³a Ä‘Æ¡n (FT21)");
        assertEquals(performedService.getPerformedId(), unbilledServices.get(0).getPerformedId());
        assertEquals(PerformedServiceStatus.COMPLETED, unbilledServices.get(0).getStatus());

        // =========================================================================
        // BÆ°á»›c 6: KÃª Ä‘Æ¡n thuá»‘c vÃ  lÆ°u lá»‹ch háº¹n tÃ¡i khÃ¡m (FT18)
        // =========================================================================
        // 6.1. KÃª Ä‘Æ¡n thuá»‘c
        PrescriptionItemRequestDTO itemAmox = PrescriptionItemRequestDTO.builder()
                .medicationId(amoxMed.getMedicationId())
                .drugName(amoxMed.getDrugName())
                .dosage("1 viÃªn x 3 láº§n/ngÃ y")
                .durationDays(5)
                .quantity(new BigDecimal("15"))
                .unit("ViÃªn")
                .usageInstruction("Uá»‘ng sau khi Äƒn no")
                .unitPrice(amoxMed.getSalePrice())
                .build();

        PrescriptionItemRequestDTO itemPara = PrescriptionItemRequestDTO.builder()
                .medicationId(paraMed.getMedicationId())
                .drugName(paraMed.getDrugName())
                .dosage("1 viÃªn khi Ä‘au")
                .durationDays(3)
                .quantity(new BigDecimal("6"))
                .unit("ViÃªn")
                .usageInstruction("Uá»‘ng cÃ¡ch nhau tá»‘i thiá»ƒu 4-6 tiáº¿ng khi Ä‘au buá»‘t")
                .unitPrice(paraMed.getSalePrice())
                .build();

        PrescriptionRequestDTO prescReq = PrescriptionRequestDTO.builder()
                .visitId(visit.getVisitId())
                .doctorId(doctorStaff.getStaffId())
                .prescriptionTitle("ÄÆ¡n thuá»‘c sau trÃ¡m rÄƒng 46")
                .diagnosisText("SÃ¢u ngÃ  rÄƒng 46 (K02.1)")
                .adviceNotes("KiÃªng nhai Ä‘á»“ quÃ¡ cá»©ng hoáº·c quÃ¡ nÃ³ng/láº¡nh táº¡i vá»‹ trÃ­ rÄƒng 46 trong 24 giá» Ä‘áº§u")
                .followupDays(7)
                .items(List.of(itemAmox, itemPara))
                .build();

        PrescriptionResponseDTO prescription = prescriptionService.createPrescription(prescReq);
        assertNotNull(prescription.getPrescriptionId(), "ÄÆ¡n thuá»‘c pháº£i Ä‘Æ°á»£c táº¡o thÃ nh cÃ´ng vá»›i ID há»£p lá»‡");
        assertEquals(2, prescription.getItems().size(), "ÄÆ¡n thuá»‘c pháº£i cÃ³ Ä‘á»§ 2 loáº¡i thuá»‘c");

        // 6.2. LÆ°u lá»‹ch háº¹n tÃ¡i khÃ¡m
        ReExaminationRequestDTO reExamReq = ReExaminationRequestDTO.builder()
                .patientProfileId(patient.getProfileId())
                .visitId(visit.getVisitId())
                .branchId(branch.getBranchId())
                .doctorId(doctorStaff.getStaffId())
                .appointmentDate(LocalDate.now().plusDays(7))
                .preferredSession(PreferredSession.MORNING)
                .expectedTime("09:00")
                .followupReason("TÃ¡i khÃ¡m kiá»ƒm tra cháº¥t hÃ n Composite vÃ  láº¯p mÃ£o sá»© rÄƒng 46")
                .createCareTask(true)
                .careTaskNotes("Nháº¯c bá»‡nh nhÃ¢n trÆ°á»›c 24 giá»")
                .build();

        ReExaminationResponseDTO reExamResponse = reExaminationService.createReExamination(reExamReq);
        assertNotNull(reExamResponse.getAppointmentId(), "Lá»‹ch háº¹n tÃ¡i khÃ¡m pháº£i Ä‘Æ°á»£c táº¡o thÃ nh cÃ´ng");
        assertEquals(LocalDate.now().plusDays(7), reExamResponse.getAppointmentDate());

        // =========================================================================
        // BÆ°á»›c 7: HoÃ n táº¥t lÆ°á»£t khÃ¡m & BÃ n giao EMR (KhÃ³a báº¥t biáº¿n BR-EMR-02)
        // =========================================================================
        // 7.1. Kiá»ƒm tra Ä‘iá»u kiá»‡n bÃ n giao (Pre-condition check)
        ClinicalCompletionCheckDTO readiness = diagnosisService.checkClinicalCompletionReadiness(visit.getVisitId());
        assertTrue(readiness.isReady(), "Ca khÃ¡m pháº£i Ä‘Ã¡p á»©ng Ä‘áº§y Ä‘á»§ Ä‘iá»u kiá»‡n Ä‘á»ƒ chuyá»ƒn Káº¿ toÃ¡n");
        assertTrue(readiness.isHasDiagnosis(), "ÄÃ£ cÃ³ cháº©n Ä‘oÃ¡n");
        assertTrue(readiness.isHasCompletedServices(), "ÄÃ£ cÃ³ dá»‹ch vá»¥ COMPLETED");
        assertTrue(readiness.isHasPrescription(), "ÄÃ£ cÃ³ Ä‘Æ¡n thuá»‘c");
        assertTrue(readiness.isHasReExamination(), "ÄÃ£ cÃ³ lá»‹ch háº¹n tÃ¡i khÃ¡m");

        // 7.2. BÃ¡c sÄ© báº¥m hoÃ n táº¥t ca khÃ¡m -> Chuyá»ƒn sang WAITING_PAYMENT
        Visit completedVisit = diagnosisService.completeClinicalExamination(
                visit.getVisitId(), "BÃ¡c sÄ© hoÃ n táº¥t Ä‘iá»u trá»‹ táº¡i gháº¿, chuyá»ƒn Káº¿ toÃ¡n thanh toÃ¡n");
        assertEquals(VisitStatus.WAITING_PAYMENT, completedVisit.getStatus(),
                "Tráº¡ng thÃ¡i ca khÃ¡m pháº£i chuyá»ƒn sang WAITING_PAYMENT Ä‘á»ƒ bÃ n giao Káº¿ toÃ¡n thu tiá»n");

        // 7.3. Táº¡o bá»‡nh Ã¡n Ä‘iá»‡n tá»­ EMR tá»« lÆ°á»£t khÃ¡m vá»«a hoÃ n táº¥t
        MockHttpSession doctorSession = new MockHttpSession();
        doctorSession.setAttribute("loggedInUser", doctorUser);

        Long emrRecordId = emrModuleService.create(doctorSession, visit.getVisitId());
        assertNotNull(emrRecordId, "Bá»‡nh Ã¡n Ä‘iá»‡n tá»­ EMR pháº£i Ä‘Æ°á»£c táº¡o thÃ nh cÃ´ng");

        EmrRecord emrRecord = emrRecordRepository.findById(emrRecordId).orElseThrow();
        assertEquals(EmrRecordStatus.DRAFT, emrRecord.getStatus());
        assertEquals(visit.getVisitId(), emrRecord.getVisit().getVisitId());
        assertEquals(patient.getProfileId(), emrRecord.getPatientProfile().getProfileId());

        // 7.4. Kiá»ƒm tra tÃ­nh báº¥t biáº¿n (BR-EMR-02): KhÃ³a toÃ n bá»™ chá»‰nh sá»­a lÃ¢m sÃ ng sau khi hoÃ n táº¥t
        assertThrows(IllegalStateException.class, () -> {
            diagnosisService.addDiagnosis(diagMainReq);
        }, "Quy táº¯c BR-EMR-02: KhÃ´ng thá»ƒ thÃªm cháº©n Ä‘oÃ¡n khi ca khÃ¡m Ä‘Ã£ chuyá»ƒn WAITING_PAYMENT");

        assertThrows(IllegalStateException.class, () -> {
            prescriptionService.createPrescription(prescReq);
        }, "Quy táº¯c BR-EMR-02: KhÃ´ng thá»ƒ táº¡o Ä‘Æ¡n thuá»‘c khi ca khÃ¡m Ä‘Ã£ chuyá»ƒn WAITING_PAYMENT");

        assertThrows(IllegalStateException.class, () -> {
            performedServiceService.createPerformedService(psReq);
        }, "Quy táº¯c BR-EMR-02: KhÃ´ng thá»ƒ thÃªm dá»‹ch vá»¥ thá»±c hiá»‡n khi ca khÃ¡m Ä‘Ã£ chuyá»ƒn WAITING_PAYMENT");
    }

    @Test
    @DisplayName("Ká»‹ch báº£n 3b: Transaction Rollback khi KÃª Ä‘Æ¡n thuá»‘c gáº·p lá»—i báº¥t ngá» (Sá»‘ lÆ°á»£ng <= 0)")
    void transactionRollbackOnPrescriptionFailure() {
        Visit visit = createInitialWaitingVisit();
        visit.setStatus(VisitStatus.IN_EXAM);
        visit = visitRepository.save(visit);

        long prescriptionCountBefore = prescriptionRepository.findByVisit_VisitIdOrderByCreatedAtDesc(visit.getVisitId()).size();

        // Táº¡o DTO Ä‘Æ¡n thuá»‘c vá»›i sá»‘ lÆ°á»£ng thuá»‘c khÃ´ng há»£p lá»‡ (<= 0)
        PrescriptionItemRequestDTO invalidItem = PrescriptionItemRequestDTO.builder()
                .medicationId(amoxMed.getMedicationId())
                .drugName(amoxMed.getDrugName())
                .quantity(BigDecimal.ZERO) // Vi pháº¡m nghiá»‡p vá»¥: Sá»‘ lÆ°á»£ng pháº£i > 0
                .unit("ViÃªn")
                .unitPrice(amoxMed.getSalePrice())
                .build();

        PrescriptionRequestDTO invalidPrescReq = PrescriptionRequestDTO.builder()
                .visitId(visit.getVisitId())
                .doctorId(doctorStaff.getStaffId())
                .prescriptionTitle("ÄÆ¡n thuá»‘c thá»­ nghiá»‡m Rollback")
                .items(List.of(invalidItem))
                .build();

        // HÃ nh Ä‘á»™ng nÃ©m ngoáº¡i lá»‡ IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> {
            prescriptionService.createPrescription(invalidPrescReq);
        }, "Pháº£i nÃ©m ngoáº¡i lá»‡ IllegalArgumentException do sá»‘ lÆ°á»£ng thuá»‘c khÃ´ng há»£p lá»‡");

        // Kiá»ƒm tra tÃ­nh toÃ n váº¹n Transaction: Giao dá»‹ch pháº£i rollback sáº¡ch sáº½
        long prescriptionCountAfter = prescriptionRepository.findByVisit_VisitIdOrderByCreatedAtDesc(visit.getVisitId()).size();
        assertEquals(prescriptionCountBefore, prescriptionCountAfter,
                "Transaction pháº£i rollback sáº¡ch sáº½: KhÃ´ng Ä‘Æ°á»£c tá»“n táº¡i Ä‘Æ¡n thuá»‘c rÃ¡c hoáº·c má»“ cÃ´i trong CSDL");
    }

    @Test
    @DisplayName("Ká»‹ch báº£n 3b: Transaction Rollback khi Ghi nháº­n dá»‹ch vá»¥ gáº·p lá»—i ngoáº¡i lá»‡ báº¥t ngá»")
    void transactionRollbackOnPerformedServiceFailure() {
        Visit visit = createInitialWaitingVisit();
        visit.setStatus(VisitStatus.IN_EXAM);
        visit = visitRepository.save(visit);

        long performedCountBefore = performedServiceRepository.findByVisitIdWithDetails(visit.getVisitId()).size();

        // Ghi dá»‹ch vá»¥ vá»›i Service ID khÃ´ng tá»“n táº¡i
        PerformedServiceRequestDTO invalidPsReq = PerformedServiceRequestDTO.builder()
                .visitId(visit.getVisitId())
                .serviceId(999999L) // Dá»‹ch vá»¥ khÃ´ng tá»“n táº¡i
                .doctorId(doctorStaff.getStaffId())
                .quantity(BigDecimal.ONE)
                .build();

        assertThrows(IllegalArgumentException.class, () -> {
            performedServiceService.createPerformedService(invalidPsReq);
        }, "Pháº£i nÃ©m ngoáº¡i lá»‡ khi ID dá»‹ch vá»¥ khÃ´ng tá»“n táº¡i");

        // Kiá»ƒm tra dá»¯ liá»‡u khÃ´ng bá»‹ biáº¿n Ä‘á»•i
        long performedCountAfter = performedServiceRepository.findByVisitIdWithDetails(visit.getVisitId()).size();
        assertEquals(performedCountBefore, performedCountAfter,
                "Transaction pháº£i rollback sáº¡ch sáº½: KhÃ´ng cÃ³ dá»‹ch vá»¥ thá»±c hiá»‡n nÃ o Ä‘Æ°á»£c lÆ°u trong CSDL");
    }
}

