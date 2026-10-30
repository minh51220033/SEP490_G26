package com.cvdcms;

import com.cvdcms.DTO.TreatmentPlanItemRequestDTO;
import com.cvdcms.DTO.TreatmentPlanRequestDTO;
import com.cvdcms.DTO.TreatmentPlanResponseDTO;
import com.cvdcms.DTO.TreatmentPlanStageRequestDTO;
import com.cvdcms.controller.DiagnosisController;
import com.cvdcms.entity.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.*;
import com.cvdcms.service.impl.TreatmentPlanServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TreatmentPlanLogicUnitTest {

    @Mock private TreatmentPlanRepository planRepository;
    @Mock private TreatmentPlanStageRepository stageRepository;
    @Mock private TreatmentPlanItemRepository itemRepository;
    @Mock private PatientProfileRepository patientProfileRepository;
    @Mock private BranchRepository branchRepository;
    @Mock private StaffProfileRepository staffProfileRepository;
    @Mock private ServiceRepository serviceRepository;
    @Mock private GeminiAiService geminiAiService;

    @Mock private DiagnosisService diagnosisService;
    @Mock private Icd10DiseaseService icd10DiseaseService;
    @Mock private PerformedServiceService performedServiceService;
    @Mock private PrescriptionService prescriptionService;
    @Mock private TreatmentPlanService treatmentPlanService;
    @Mock private ClinicalPhotoService clinicalPhotoService;
    @Mock private ReExaminationService reExaminationService;
    @Mock private ParaclinicalOrderService paraclinicalOrderService;
    @Mock private LaboOrderService laboOrderService;
    @Mock private SupplierRepository supplierRepository;
    @Mock private VisitRepository visitRepository;
    @Mock private UserRepository userRepository;

    private TreatmentPlanServiceImpl treatmentPlanServiceImpl;
    private DiagnosisController diagnosisController;

    private PatientProfile mockPatient;
    private Branch mockBranch;
    private StaffProfile mockDoctor;
    private Visit mockVisit;
    private com.cvdcms.entity.Service mockService;

    @BeforeEach
    void setUp() {
        treatmentPlanServiceImpl = new TreatmentPlanServiceImpl(
                planRepository,
                stageRepository,
                itemRepository,
                patientProfileRepository,
                branchRepository,
                staffProfileRepository,
                serviceRepository,
                geminiAiService
        );

        diagnosisController = new DiagnosisController(
                diagnosisService,
                icd10DiseaseService,
                performedServiceService,
                prescriptionService,
                treatmentPlanService,
                clinicalPhotoService,
                reExaminationService,
                paraclinicalOrderService,
                laboOrderService,
                supplierRepository,
                visitRepository,
                patientProfileRepository,
                branchRepository,
                staffProfileRepository,
                userRepository
        );

        mockPatient = new PatientProfile();
        mockPatient.setProfileId(8L);
        mockPatient.setFullName("Nguyễn Văn An");

        mockBranch = new Branch();
        mockBranch.setBranchId(1L);
        mockBranch.setBranchName("Cơ sở DentalFlow");

        mockDoctor = new StaffProfile();
        mockDoctor.setStaffId(2L);
        mockDoctor.setFullName("BS. CK1 Lê Hoàng Nam");

        mockVisit = new Visit();
        mockVisit.setVisitId(8L);
        mockVisit.setPatientProfile(mockPatient);
        mockVisit.setBranch(mockBranch);
        mockVisit.setPrimaryDoctor(mockDoctor);

        mockService = new com.cvdcms.entity.Service();
        mockService.setServiceId(10L);
        mockService.setServiceName("Trám răng thẩm mỹ Composite");
        mockService.setBasePrice(new BigDecimal("300000"));
    }

    @Test
    @DisplayName("Kiểm tra Controller showCreateTreatmentPlanPage trả về đúng view và nạp model đầy đủ")
    void testShowCreateTreatmentPlanPage() {
        when(diagnosisService.getVisitOrThrow(8L)).thenReturn(mockVisit);
        when(diagnosisService.getDiagnosesByVisitId(8L)).thenReturn(Collections.emptyList());
        when(performedServiceService.getAllActiveServices()).thenReturn(Collections.emptyList());
        when(performedServiceService.getAvailableDoctors()).thenReturn(List.of(mockDoctor));
        when(visitRepository.findAll()).thenReturn(List.of(mockVisit));

        Model model = new ConcurrentModel();
        String view = diagnosisController.showCreateTreatmentPlanPage(8L, null, model);

        assertEquals("doctor/treatment-plan-create", view);
        assertTrue(model.containsAttribute("visit"));
        assertTrue(model.containsAttribute("patient"));
        assertTrue(model.containsAttribute("diagnoses"));
        assertTrue(model.containsAttribute("activeServices"));
        assertTrue(model.containsAttribute("activeServicesJson"));
        assertTrue(model.containsAttribute("doctors"));
    }

    @Test
    @DisplayName("Kiểm tra Lưu Phác đồ đa chẩn đoán: Tính đúng tổng tiền theo công thức Đơn giá x Số răng")
    void testCreateTreatmentPlanWithMultipleDiagnosesAndServices() {
        when(patientProfileRepository.findById(8L)).thenReturn(Optional.of(mockPatient));
        when(branchRepository.findById(1L)).thenReturn(Optional.of(mockBranch));
        when(staffProfileRepository.findById(2L)).thenReturn(Optional.of(mockDoctor));
        when(serviceRepository.findAll()).thenReturn(List.of(mockService));

        when(planRepository.save(any(TreatmentPlan.class))).thenAnswer(invocation -> {
            TreatmentPlan p = invocation.getArgument(0);
            p.setPlanId(101L);
            return p;
        });

        TreatmentPlanItemRequestDTO item1 = TreatmentPlanItemRequestDTO.builder()
                .serviceId(10L)
                .customServiceName("Trám răng Composite Xoang I Mặt O")
                .toothNumbers("41, 42")
                .quantity(new BigDecimal("2"))
                .estimatedPrice(new BigDecimal("600000"))
                .status("PLANNED")
                .notes("Mặt O")
                .build();

        TreatmentPlanItemRequestDTO item2 = TreatmentPlanItemRequestDTO.builder()
                .serviceId(10L)
                .customServiceName("Phẫu thuật nhổ răng khôn Piezotome")
                .toothNumbers("38")
                .quantity(new BigDecimal("1"))
                .estimatedPrice(new BigDecimal("2500000"))
                .status("PLANNED")
                .notes("Răng khôn mọc kẹt")
                .build();

        TreatmentPlanStageRequestDTO stage1 = TreatmentPlanStageRequestDTO.builder()
                .stageOrder(1)
                .stageName("Giai đoạn 1: Sửa soạn & Trám phục hồi + Nhổ răng")
                .estimatedDaysFromStart(0)
                .description("2 thủ thuật")
                .status("PENDING")
                .items(List.of(item1, item2))
                .build();

        TreatmentPlanRequestDTO request = TreatmentPlanRequestDTO.builder()
                .patientId(8L)
                .branchId(1L)
                .doctorId(2L)
                .planName("Phác đồ điều trị: Sâu men răng (R-41, 42) & Răng khôn (R-38)")
                .diagnosisText("[K02.0] Sâu men răng (R-41, 42) | [K01.1] Răng khôn mọc lệch (R-38)")
                .totalEstimatedCost(new BigDecimal("3100000"))
                .finalEstimatedCost(new BigDecimal("3100000"))
                .status("ACCEPTED")
                .startDate("2026-10-30")
                .expectedEndDate("2027-01-30")
                .notes("Lộ trình chuẩn hóa theo chẩn đoán")
                .stages(List.of(stage1))
                .build();

        TreatmentPlanResponseDTO response = treatmentPlanServiceImpl.createPlan(request);

        assertNotNull(response);
        assertEquals(101L, response.getPlanId());
        assertEquals("Phác đồ điều trị: Sâu men răng (R-41, 42) & Răng khôn (R-38)", response.getPlanName());
        assertEquals("ACCEPTED", response.getStatus());

        // Tổng tiền được tính từ 600,000 + 2,500,000 = 3,100,000 đ
        assertEquals(0, new BigDecimal("3100000").compareTo(response.getTotalEstimatedCost()));

        // Kiểm tra lưu cascade
        verify(planRepository, times(1)).save(any(TreatmentPlan.class));
    }
}
