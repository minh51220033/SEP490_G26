package com.cvdcms;

import com.cvdcms.DTO.DiagnosisRequestDTO;
import com.cvdcms.DTO.DiagnosisResponseDTO;
import com.cvdcms.controller.Icd10RestController;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.DiagnosisType;
import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.repository.*;
import com.cvdcms.service.Icd10DiseaseService;
import com.cvdcms.service.impl.DiagnosisServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DiagnosisOdontogramUnitTest {

    @Mock private ExaminationDiagnosisRepository diagnosisRepository;
    @Mock private Icd10DiseaseRepository icd10DiseaseRepository;
    @Mock private VisitRepository visitRepository;
    @Mock private ClinicalExaminationRepository clinicalExamRepository;
    @Mock private StaffProfileRepository staffProfileRepository;
    @Mock private PerformedServiceRepository performedServiceRepository;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private PatientProfileRepository patientProfileRepository;
    @Mock private Icd10DiseaseService icd10DiseaseService;

    private DiagnosisServiceImpl diagnosisService;
    private Icd10RestController icd10RestController;

    private Visit mockVisit;
    private Icd10Disease mockDisease;
    private ClinicalExamination mockExam;

    @BeforeEach
    void setUp() {
        diagnosisService = new DiagnosisServiceImpl(
                diagnosisRepository,
                icd10DiseaseRepository,
                visitRepository,
                clinicalExamRepository,
                staffProfileRepository,
                performedServiceRepository,
                prescriptionRepository,
                appointmentRepository,
                patientProfileRepository
        );
        icd10RestController = new Icd10RestController(icd10DiseaseService, diagnosisService);

        mockVisit = new Visit();
        mockVisit.setVisitId(100L);
        mockVisit.setVisitCode("VISIT-100");
        mockVisit.setStatus(VisitStatus.IN_EXAM);

        PatientProfile patient = new PatientProfile();
        patient.setFullName("Nguyen Van A");
        mockVisit.setPatientProfile(patient);

        mockDisease = new Icd10Disease();
        mockDisease.setIcd10Code("K02.1");
        mockDisease.setDiseaseNameVi("Sâu ngà răng");
        mockDisease.setDiseaseNameEn("Caries of dentine");

        mockExam = new ClinicalExamination();
        mockExam.setExamId(50L);
        mockExam.setVisit(mockVisit);
    }

    @Test
    @DisplayName("Thêm chẩn đoán cho răng 16 với mặt nhai (Mặt MO) thành công")
    void testAddDiagnosisWithToothAndSurfaces() {
        DiagnosisRequestDTO request = DiagnosisRequestDTO.builder()
                .visitId(100L)
                .icd10Code("K02.1")
                .diagnosisType(DiagnosisType.MAIN)
                .toothNumbers("16 (Mặt MO)")
                .notes("Chỉ định trám Composite")
                .build();

        when(visitRepository.findWithDetailsById(100L)).thenReturn(Optional.of(mockVisit));
        when(icd10DiseaseRepository.findByIcd10Code("K02.1")).thenReturn(Optional.of(mockDisease));
        when(clinicalExamRepository.findByVisit_VisitId(100L)).thenReturn(Optional.of(mockExam));
        when(diagnosisRepository.findByVisit_VisitId(100L)).thenReturn(new ArrayList<>());

        when(diagnosisRepository.save(any(ExaminationDiagnosis.class))).thenAnswer(invocation -> {
            ExaminationDiagnosis ed = invocation.getArgument(0);
            ed.setDiagnosisId(1L);
            return ed;
        });

        DiagnosisResponseDTO response = diagnosisService.addDiagnosis(request);

        assertNotNull(response);
        assertEquals("K02.1", response.getIcd10Code());
        assertEquals("16 (Mặt MO)", response.getToothNumbers());
        assertEquals(DiagnosisType.MAIN, response.getDiagnosisType());
        assertEquals("Chẩn đoán chính", response.getDiagnosisTypeDisplayName());
        assertEquals("Chỉ định trám Composite", response.getNotes());
        verify(diagnosisRepository, times(1)).save(any(ExaminationDiagnosis.class));
    }

    @Test
    @DisplayName("Khi thêm chẩn đoán MAIN mới, chẩn đoán MAIN cũ phải tự động chuyển thành SECONDARY")
    void testMainDiagnosisDemotion() {
        ExaminationDiagnosis oldMain = new ExaminationDiagnosis();
        oldMain.setDiagnosisId(10L);
        oldMain.setDiagnosisType(DiagnosisType.MAIN);
        oldMain.setToothNumbers("26 (Mặt O)");

        DiagnosisRequestDTO request = DiagnosisRequestDTO.builder()
                .visitId(100L)
                .icd10Code("K02.1")
                .diagnosisType(DiagnosisType.MAIN)
                .toothNumbers("16 (Mặt MOD)")
                .build();

        when(visitRepository.findWithDetailsById(100L)).thenReturn(Optional.of(mockVisit));
        when(icd10DiseaseRepository.findByIcd10Code("K02.1")).thenReturn(Optional.of(mockDisease));
        when(clinicalExamRepository.findByVisit_VisitId(100L)).thenReturn(Optional.of(mockExam));
        when(diagnosisRepository.findByVisit_VisitId(100L)).thenReturn(List.of(oldMain));

        when(diagnosisRepository.save(any(ExaminationDiagnosis.class))).thenAnswer(invocation -> {
            ExaminationDiagnosis ed = invocation.getArgument(0);
            if (ed.getDiagnosisId() == null) {
                ed.setDiagnosisId(20L);
            }
            return ed;
        });

        DiagnosisResponseDTO response = diagnosisService.addDiagnosis(request);

        assertNotNull(response);
        assertEquals(DiagnosisType.MAIN, response.getDiagnosisType());
        // Verify that old main was demoted to SECONDARY
        assertEquals(DiagnosisType.SECONDARY, oldMain.getDiagnosisType());
        verify(diagnosisRepository, times(2)).save(any(ExaminationDiagnosis.class));
    }

    @Test
    @DisplayName("REST API POST /api/v1/diagnoses trả về HttpStatus.CREATED và DTO chuẩn")
    void testRestControllerAddDiagnosis() {
        DiagnosisRequestDTO request = DiagnosisRequestDTO.builder()
                .visitId(100L)
                .icd10Code("K02.1")
                .diagnosisType(DiagnosisType.MAIN)
                .toothNumbers("46, 47")
                .notes("Hàn răng")
                .build();

        when(visitRepository.findWithDetailsById(100L)).thenReturn(Optional.of(mockVisit));
        when(icd10DiseaseRepository.findByIcd10Code("K02.1")).thenReturn(Optional.of(mockDisease));
        when(clinicalExamRepository.findByVisit_VisitId(100L)).thenReturn(Optional.of(mockExam));
        when(diagnosisRepository.findByVisit_VisitId(100L)).thenReturn(new ArrayList<>());
        when(diagnosisRepository.save(any(ExaminationDiagnosis.class))).thenAnswer(invocation -> {
            ExaminationDiagnosis ed = invocation.getArgument(0);
            ed.setDiagnosisId(99L);
            return ed;
        });

        ResponseEntity<?> responseEntity = icd10RestController.addDiagnosis(request);

        assertEquals(HttpStatus.CREATED, responseEntity.getStatusCode());
        assertTrue(responseEntity.getBody() instanceof DiagnosisResponseDTO);
        DiagnosisResponseDTO dto = (DiagnosisResponseDTO) responseEntity.getBody();
        assertEquals(99L, dto.getDiagnosisId());
        assertEquals("46, 47", dto.getToothNumbers());
    }

    @Test
    @DisplayName("REST API DELETE /api/v1/diagnoses/{id} xóa thành công")
    void testRestControllerDeleteDiagnosis() {
        ExaminationDiagnosis existing = new ExaminationDiagnosis();
        existing.setDiagnosisId(5L);
        existing.setVisit(mockVisit);

        when(diagnosisRepository.findById(5L)).thenReturn(Optional.of(existing));
        doNothing().when(diagnosisRepository).delete(existing);

        ResponseEntity<?> res = icd10RestController.deleteDiagnosis(5L);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        verify(diagnosisRepository, times(1)).delete(existing);
    }
}
