package com.cvdcms.service;

import com.cvdcms.DTO.GeneralExaminationRequestDTO;
import com.cvdcms.DTO.ParaclinicalOrderCreateDTO;
import com.cvdcms.DTO.ParaclinicalOrderItemResponseDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.impl.DiagnosisServiceImpl;
import com.cvdcms.service.impl.ParaclinicalOrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Test tầng Service cho FT11 - Khám lâm sàng & Chỉ định cận lâm sàng (Role Bác sĩ)
 * Services: ParaclinicalOrderServiceImpl, DiagnosisServiceImpl
 */
@ExtendWith(MockitoExtension.class)
class DoctorExaminationServiceTest {

    @Mock private ParaclinicalOrderRepository paraclinicalOrderRepository;
    @Mock private ParaclinicalOrderItemRepository paraclinicalOrderItemRepository;
    @Mock private VisitRepository visitRepository;
    @Mock private ServiceRepository serviceRepository;
    @Mock private ServiceCategoryRepository serviceCategoryRepository;
    @Mock private StaffProfileRepository staffProfileRepository;

    private ParaclinicalOrderServiceImpl paraclinicalOrderServiceImpl;

    @Mock private ExaminationDiagnosisRepository diagnosisRepository;
    @Mock private Icd10DiseaseRepository icd10DiseaseRepository;
    @Mock private ClinicalExaminationRepository clinicalExamRepository;
    @Mock private PerformedServiceRepository performedServiceRepository;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private PatientProfileRepository patientProfileRepository;
    @Mock private InvoiceRepository invoiceRepository;

    private DiagnosisServiceImpl diagnosisServiceImpl;

    private Visit mockVisit;
    private PatientProfile mockPatient;
    private StaffProfile mockDoctor;
    private com.cvdcms.entity.Service mockService;

    @BeforeEach
    void setUp() {
        paraclinicalOrderServiceImpl = new ParaclinicalOrderServiceImpl(
                paraclinicalOrderRepository,
                paraclinicalOrderItemRepository,
                visitRepository,
                serviceRepository,
                serviceCategoryRepository,
                staffProfileRepository
        );

        diagnosisServiceImpl = new DiagnosisServiceImpl(
                diagnosisRepository,
                icd10DiseaseRepository,
                visitRepository,
                clinicalExamRepository,
                staffProfileRepository,
                performedServiceRepository,
                prescriptionRepository,
                appointmentRepository,
                patientProfileRepository,
                invoiceRepository
        );

        mockPatient = new PatientProfile();
        mockPatient.setProfileId(10L);
        mockPatient.setPatientCode("BN-00010");
        mockPatient.setFullName("Nguyen Van A");

        mockDoctor = new StaffProfile();
        mockDoctor.setStaffId(20L);
        mockDoctor.setFullName("BS. Le Thi B");

        mockVisit = new Visit();
        mockVisit.setVisitId(100L);
        mockVisit.setVisitCode("KB-20261103-100");
        mockVisit.setStatus(VisitStatus.IN_EXAM);
        mockVisit.setPatientProfile(mockPatient);
        mockVisit.setPrimaryDoctor(mockDoctor);

        mockService = new com.cvdcms.entity.Service();
        mockService.setServiceId(50L);
        mockService.setServiceCode("CLS-XQ-CHOP");
        mockService.setServiceName("Chụp X-quang quanh chóp");
        mockService.setBasePrice(BigDecimal.valueOf(150000));
        mockService.setIsParaclinical(true);
    }

    @Nested
    @DisplayName("Tests for ParaclinicalOrderServiceImpl")
    class ParaclinicalOrderServiceTests {

        @Test
        @DisplayName("createOrder - Tạo chỉ định CLS thành công, gán đúng Visit và Bác sĩ phụ trách")
        void createOrder_Success() {
            ParaclinicalOrderCreateDTO dto = ParaclinicalOrderCreateDTO.builder()
                    .serviceId(50L)
                    .toothNumbers("R46")
                    .notes("Kiểm tra tủy răng")
                    .build();

            when(visitRepository.findById(100L)).thenReturn(Optional.of(mockVisit));
            when(serviceRepository.findByServiceCode("CLS-XQ-CHOP")).thenReturn(Optional.of(mockService));
            when(serviceRepository.findById(50L)).thenReturn(Optional.of(mockService));
            when(paraclinicalOrderRepository.findByVisit_VisitIdOrderByOrderTimeDesc(100L)).thenReturn(Collections.emptyList());

            ParaclinicalOrder savedOrder = new ParaclinicalOrder();
            savedOrder.setOrderId(1L);
            savedOrder.setOrderCode("CĐ1001");
            savedOrder.setVisit(mockVisit);
            savedOrder.setStatus(ParaclinicalOrderStatus.ORDERED);
            when(paraclinicalOrderRepository.save(any(ParaclinicalOrder.class))).thenReturn(savedOrder);

            ParaclinicalOrderItem savedItem = new ParaclinicalOrderItem();
            savedItem.setOrderItemId(10L);
            savedItem.setOrder(savedOrder);
            savedItem.setService(mockService);
            savedItem.setToothNumbers("R46");
            savedItem.setStatus(ParaclinicalOrderItemStatus.WAITING);
            savedItem.setResultSummary("Kiểm tra tủy răng");
            when(paraclinicalOrderItemRepository.save(any(ParaclinicalOrderItem.class))).thenReturn(savedItem);

            ParaclinicalOrderItemResponseDTO result = paraclinicalOrderServiceImpl.createOrder(100L, dto, 20L);

            assertNotNull(result);
            assertEquals(10L, result.getOrderItemId());
            assertEquals("R46", result.getToothNumbers());
            assertEquals("WAITING", result.getStatus());
            assertEquals("Kiểm tra tủy răng", result.getResultSummary());
            verify(paraclinicalOrderRepository, times(1)).save(any(ParaclinicalOrder.class));
            verify(paraclinicalOrderItemRepository, times(1)).save(any(ParaclinicalOrderItem.class));
        }

        @Test
        @DisplayName("createOrder - Ném IllegalStateException khi ca khám đã ở trạng thái WAITING_PAYMENT (BR-EMR-02)")
        void createOrder_VisitWaitingPayment_ThrowsIllegalStateException() {
            mockVisit.setStatus(VisitStatus.WAITING_PAYMENT);
            when(visitRepository.findById(100L)).thenReturn(Optional.of(mockVisit));

            ParaclinicalOrderCreateDTO dto = ParaclinicalOrderCreateDTO.builder().serviceId(50L).build();

            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                    paraclinicalOrderServiceImpl.createOrder(100L, dto, null));

            assertTrue(ex.getMessage().contains("BR-EMR-02"));
            verify(paraclinicalOrderItemRepository, never()).save(any());
        }

        @Test
        @DisplayName("createOrder - Ném IllegalStateException khi ca khám đã COMPLETED")
        void createOrder_VisitCompleted_ThrowsIllegalStateException() {
            mockVisit.setStatus(VisitStatus.COMPLETED);
            when(visitRepository.findById(100L)).thenReturn(Optional.of(mockVisit));

            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                    paraclinicalOrderServiceImpl.createOrder(100L, new ParaclinicalOrderCreateDTO(), null));

            assertTrue(ex.getMessage().contains("khóa bất biến"));
        }

        @Test
        @DisplayName("createOrder - Ném IllegalArgumentException khi không tìm thấy lượt khám")
        void createOrder_VisitNotFound_ThrowsIllegalArgumentException() {
            when(visitRepository.findById(999L)).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class, () ->
                    paraclinicalOrderServiceImpl.createOrder(999L, new ParaclinicalOrderCreateDTO(), null));
        }

        @Test
        @DisplayName("updateStatus - Cập nhật trạng thái item sang COMPLETED và tự động hoàn thành Order cha")
        void updateStatus_CompleteItemAndParentOrder() {
            ParaclinicalOrder parentOrder = new ParaclinicalOrder();
            parentOrder.setOrderId(1L);
            parentOrder.setVisit(mockVisit);
            parentOrder.setStatus(ParaclinicalOrderStatus.ORDERED);

            ParaclinicalOrderItem item = new ParaclinicalOrderItem();
            item.setOrderItemId(10L);
            item.setOrder(parentOrder);
            item.setStatus(ParaclinicalOrderItemStatus.WAITING);
            item.setService(mockService);

            when(paraclinicalOrderItemRepository.findById(10L)).thenReturn(Optional.of(item));
            when(paraclinicalOrderItemRepository.save(any(ParaclinicalOrderItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(paraclinicalOrderItemRepository.findByOrder_OrderId(1L)).thenReturn(List.of(item));

            ParaclinicalOrderItemResponseDTO result = paraclinicalOrderServiceImpl.updateStatus(
                    10L, ParaclinicalOrderItemStatus.COMPLETED, "Kết quả phim rõ nét"
            );

            assertNotNull(result);
            assertEquals("COMPLETED", result.getStatus());
            assertEquals("Kết quả phim rõ nét", result.getResultSummary());
            assertEquals(ParaclinicalOrderStatus.COMPLETED, parentOrder.getStatus());
            verify(paraclinicalOrderRepository, times(1)).save(parentOrder);
        }

        @Test
        @DisplayName("updateStatus - Ném IllegalStateException khi cập nhật chỉ định thuộc ca khám đã khóa")
        void updateStatus_VisitLocked_ThrowsIllegalStateException() {
            mockVisit.setStatus(VisitStatus.COMPLETED);
            ParaclinicalOrder parentOrder = new ParaclinicalOrder();
            parentOrder.setVisit(mockVisit);

            ParaclinicalOrderItem item = new ParaclinicalOrderItem();
            item.setOrderItemId(10L);
            item.setOrder(parentOrder);

            when(paraclinicalOrderItemRepository.findById(10L)).thenReturn(Optional.of(item));

            assertThrows(IllegalStateException.class, () ->
                    paraclinicalOrderServiceImpl.updateStatus(10L, ParaclinicalOrderItemStatus.COMPLETED, null));
        }

        @Test
        @DisplayName("deleteOrder - Xóa chỉ định CLS thành công khi ca khám đang diễn ra")
        void deleteOrder_Success() {
            ParaclinicalOrder parentOrder = new ParaclinicalOrder();
            parentOrder.setVisit(mockVisit);

            ParaclinicalOrderItem item = new ParaclinicalOrderItem();
            item.setOrderItemId(10L);
            item.setOrder(parentOrder);

            when(paraclinicalOrderItemRepository.findById(10L)).thenReturn(Optional.of(item));

            paraclinicalOrderServiceImpl.deleteOrder(10L);

            verify(paraclinicalOrderItemRepository, times(1)).delete(item);
        }

        @Test
        @DisplayName("deleteOrder - Ném IllegalStateException khi xóa chỉ định trong ca khám đã khóa")
        void deleteOrder_VisitLocked_ThrowsIllegalStateException() {
            mockVisit.setStatus(VisitStatus.WAITING_PAYMENT);
            ParaclinicalOrder parentOrder = new ParaclinicalOrder();
            parentOrder.setVisit(mockVisit);

            ParaclinicalOrderItem item = new ParaclinicalOrderItem();
            item.setOrderItemId(10L);
            item.setOrder(parentOrder);

            when(paraclinicalOrderItemRepository.findById(10L)).thenReturn(Optional.of(item));

            assertThrows(IllegalStateException.class, () -> paraclinicalOrderServiceImpl.deleteOrder(10L));
            verify(paraclinicalOrderItemRepository, never()).delete(any());
        }
    }

    @Nested
    @DisplayName("Tests for DiagnosisServiceImpl (Clinical Examination & Doctor Instructions)")
    class DiagnosisServiceClinicalTests {

        @Test
        @DisplayName("saveGeneralExamination - Lưu thành công triệu chứng, tiền sử bệnh và tình trạng mô mềm")
        void saveGeneralExamination_Success() {
            when(visitRepository.findWithDetailsById(100L)).thenReturn(Optional.of(mockVisit));

            ClinicalExamination exam = new ClinicalExamination();
            exam.setVisit(mockVisit);
            when(clinicalExamRepository.findByVisit_VisitId(100L)).thenReturn(Optional.of(exam));

            GeneralExaminationRequestDTO request = GeneralExaminationRequestDTO.builder()
                    .visitId(100L)
                    .initialSymptoms("Sưng đau vùng nướu răng 36")
                    .hasAllergies(true)
                    .allergyDetails("Dị ứng Aspirin")
                    .hasDiabetes(true)
                    .tmjStatus("CLICKING")
                    .hygieneStatus("MEDIUM")
                    .gingivaStatus("GINGIVITIS")
                    .build();

            diagnosisServiceImpl.saveGeneralExamination(request);

            assertEquals("Sưng đau vùng nướu răng 36", mockVisit.getInitialSymptoms());
            assertTrue(mockPatient.getHasAllergies());
            assertEquals("Dị ứng Aspirin", mockPatient.getAllergyDetails());
            assertTrue(mockPatient.getHasUnderlyingDisease());
            assertTrue(mockPatient.getUnderlyingDiseaseDetails().contains("Đái tháo đường"));

            assertEquals("Sưng đau vùng nướu răng 36", exam.getChiefComplaint());
            assertEquals("CLICKING", exam.getExtraoralExam());
            assertEquals("MEDIUM", exam.getIntraoralExam());
            assertEquals("GINGIVITIS", exam.getPeriodontalStatus());

            verify(visitRepository, times(1)).save(mockVisit);
            verify(patientProfileRepository, times(1)).save(mockPatient);
            verify(clinicalExamRepository, times(1)).save(exam);
        }

        @Test
        @DisplayName("saveGeneralExamination - Ném IllegalStateException khi ca khám đã hoàn tất và khóa bệnh án")
        void saveGeneralExamination_VisitLocked_ThrowsIllegalStateException() {
            mockVisit.setStatus(VisitStatus.WAITING_PAYMENT);
            when(visitRepository.findWithDetailsById(100L)).thenReturn(Optional.of(mockVisit));

            GeneralExaminationRequestDTO request = GeneralExaminationRequestDTO.builder()
                    .visitId(100L)
                    .initialSymptoms("Thử chỉnh sửa khi đã khóa")
                    .build();

            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                    diagnosisServiceImpl.saveGeneralExamination(request));

            assertTrue(ex.getMessage().contains("BR-EMR-02"));
            verify(clinicalExamRepository, never()).save(any());
        }

        @Test
        @DisplayName("saveDoctorInstructions - Lưu lời dặn vào Visit và đồng bộ sang Prescription")
        void saveDoctorInstructions_Success() {
            when(visitRepository.findWithDetailsById(100L)).thenReturn(Optional.of(mockVisit));

            Prescription prescription = new Prescription();
            prescription.setPrescriptionId(501L);
            prescription.setVisit(mockVisit);
            when(prescriptionRepository.findByVisit_VisitIdOrderByCreatedAtDesc(100L))
                    .thenReturn(List.of(prescription));

            diagnosisServiceImpl.saveDoctorInstructions(100L, "Không nhai bên hàm vừa nhổ răng, uống thuốc đúng giờ");

            assertEquals("Không nhai bên hàm vừa nhổ răng, uống thuốc đúng giờ", mockVisit.getNotes());
            assertEquals("Không nhai bên hàm vừa nhổ răng, uống thuốc đúng giờ", prescription.getAdviceNotes());

            verify(visitRepository, times(1)).save(mockVisit);
            verify(prescriptionRepository, times(1)).save(prescription);
        }

        @Test
        @DisplayName("saveDoctorInstructions - Ném IllegalArgumentException khi visitId là null")
        void saveDoctorInstructions_NullVisitId_ThrowsIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class, () ->
                    diagnosisServiceImpl.saveDoctorInstructions(null, "Notes"));
        }

        @Test
        @DisplayName("completeClinicalExamination - Khi không có dịch vụ/thuốc phát sinh chi phí -> Chuyển trực tiếp sang COMPLETED")
        void completeClinicalExamination_NoPayableItems_DirectlyCompleted() {
            when(visitRepository.findWithDetailsById(100L)).thenReturn(Optional.of(mockVisit));
            when(visitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Có 1 chẩn đoán chính
            ExaminationDiagnosis diag = new ExaminationDiagnosis();
            diag.setDiagnosisType(com.cvdcms.entity.enums.DiagnosisType.MAIN);
            diag.setDiagnosisText("Viêm lợi nhẹ");
            when(diagnosisRepository.findByVisitIdWithDetails(100L)).thenReturn(List.of(diag));

            // Không có dịch vụ thực hiện & không có thuốc
            when(performedServiceRepository.findByVisitIdWithDetails(100L)).thenReturn(Collections.emptyList());
            when(prescriptionRepository.findByVisit_VisitIdOrderByCreatedAtDesc(100L)).thenReturn(Collections.emptyList());

            Visit completed = diagnosisServiceImpl.completeClinicalExamination(100L, "Tư vấn vệ sinh răng miệng");
            assertEquals(VisitStatus.COMPLETED, completed.getStatus());
        }

        @Test
        @DisplayName("completeClinicalExamination - Khi có dịch vụ thực hiện phát sinh chi phí -> Chuyển sang WAITING_PAYMENT")
        void completeClinicalExamination_HasPayableItems_SetsWaitingPayment() {
            when(visitRepository.findWithDetailsById(100L)).thenReturn(Optional.of(mockVisit));
            when(visitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ExaminationDiagnosis diag = new ExaminationDiagnosis();
            diag.setDiagnosisType(com.cvdcms.entity.enums.DiagnosisType.MAIN);
            diag.setDiagnosisText("Sâu răng");
            when(diagnosisRepository.findByVisitIdWithDetails(100L)).thenReturn(List.of(diag));

            PerformedService ps = new PerformedService();
            ps.setStatus(com.cvdcms.entity.enums.PerformedServiceStatus.COMPLETED);
            ps.setFinalPrice(BigDecimal.valueOf(200000));
            when(performedServiceRepository.findByVisitIdWithDetails(100L)).thenReturn(List.of(ps));
            when(prescriptionRepository.findByVisit_VisitIdOrderByCreatedAtDesc(100L)).thenReturn(Collections.emptyList());

            Visit completed = diagnosisServiceImpl.completeClinicalExamination(100L, "Đã trám răng");
            assertEquals(VisitStatus.WAITING_PAYMENT, completed.getStatus());
        }
    }
}

