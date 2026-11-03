package com.cvdcms.service;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.impl.PrescriptionServiceImpl;
import com.cvdcms.service.impl.ReExaminationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Test tầng Service cho FT18 - Kê đơn thuốc & Hẹn tái khám (Role Bác sĩ)
 * Services: PrescriptionServiceImpl, ReExaminationServiceImpl
 */
@ExtendWith(MockitoExtension.class)
class DoctorPrescriptionAndReExamServiceTest {

    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private MedicationRepository medicationRepository;
    @Mock private VisitRepository visitRepository;
    @Mock private StaffProfileRepository staffProfileRepository;

    private PrescriptionServiceImpl prescriptionService;

    @Mock private AppointmentRepository appointmentRepository;
    @Mock private CustomerCareTaskRepository customerCareTaskRepository;
    @Mock private PatientProfileRepository patientProfileRepository;
    @Mock private BranchRepository branchRepository;
    @Mock private StaffProfileRepository reExamStaffRepository;
    @Mock private ServiceRepository reExamServiceRepository;
    @Mock private VisitRepository reExamVisitRepository;

    private ReExaminationServiceImpl reExaminationService;

    private Visit mockVisit;
    private PatientProfile mockPatient;
    private Branch mockBranch;
    private StaffProfile mockDoctor;
    private Medication mockMedicationAmox;
    private Medication mockMedicationPara;
    private com.cvdcms.entity.Service mockServiceReExam;

    @BeforeEach
    void setUp() {
        prescriptionService = new PrescriptionServiceImpl(
                prescriptionRepository,
                medicationRepository,
                visitRepository,
                staffProfileRepository
        );

        reExaminationService = new ReExaminationServiceImpl(
                appointmentRepository,
                customerCareTaskRepository,
                patientProfileRepository,
                branchRepository,
                reExamStaffRepository,
                reExamServiceRepository,
                reExamVisitRepository
        );

        mockBranch = new Branch();
        mockBranch.setBranchId(1L);
        mockBranch.setBranchName("Nha khoa Quốc tế DCMS - CN Quận 1");
        mockBranch.setIsActive(true);

        mockPatient = new PatientProfile();
        mockPatient.setProfileId(10L);
        mockPatient.setPatientCode("BN20261103");
        mockPatient.setFullName("Trần Văn Khách");
        mockPatient.setPreferredBranch(mockBranch);

        mockDoctor = new StaffProfile();
        mockDoctor.setStaffId(200L);
        mockDoctor.setStaffCode("BS001");
        mockDoctor.setFullName("BS. CKI Lê Quốc Tuấn");
        mockDoctor.setStatus(StaffStatus.ACTIVE);

        mockVisit = new Visit();
        mockVisit.setVisitId(100L);
        mockVisit.setVisitCode("VIS20261103001");
        mockVisit.setStatus(VisitStatus.IN_EXAM);
        mockVisit.setPatientProfile(mockPatient);
        mockVisit.setBranch(mockBranch);
        mockVisit.setPrimaryDoctor(mockDoctor);

        mockMedicationAmox = new Medication();
        mockMedicationAmox.setMedicationId(1L);
        mockMedicationAmox.setDrugCode("TH001");
        mockMedicationAmox.setDrugName("Amoxicillin 500mg");
        mockMedicationAmox.setUnit("Viên");
        mockMedicationAmox.setIsActive(true);

        mockMedicationPara = new Medication();
        mockMedicationPara.setMedicationId(2L);
        mockMedicationPara.setDrugCode("TH002");
        mockMedicationPara.setDrugName("Paracetamol 500mg");
        mockMedicationPara.setUnit("Viên");
        mockMedicationPara.setIsActive(true);

        mockServiceReExam = new com.cvdcms.entity.Service();
        mockServiceReExam.setServiceId(70L);
        mockServiceReExam.setServiceName("Tái khám theo dõi niềng răng & cắt chỉ");
    }

    // =========================================================================
    // PHẦN A: KÊ ĐƠN THUỐC (PRESCRIPTION SERVICE)
    // =========================================================================

    @Test
    @DisplayName("FT18-PRESC-01: Kê đơn thuốc thành công với đầy đủ liều lượng, cách dùng, số ngày và lời dặn")
    void testCreatePrescription_SuccessWithValidDetails() {
        PrescriptionItemRequestDTO item1 = PrescriptionItemRequestDTO.builder()
                .medicationId(1L)
                .drugName("Amoxicillin 500mg")
                .dosage("Sáng 1 viên, chiều 1 viên")
                .quantity(BigDecimal.valueOf(14))
                .durationDays(7)
                .usageInstruction("Uống sau bữa ăn sáng và tối")
                .unitPrice(BigDecimal.valueOf(5_000))
                .build();

        PrescriptionItemRequestDTO item2 = PrescriptionItemRequestDTO.builder()
                .medicationId(2L)
                .drugName("Paracetamol 500mg")
                .dosage("Uống khi đau nhức nhiều")
                .quantity(BigDecimal.valueOf(10))
                .durationDays(5)
                .usageInstruction("Uống cách nhau 4-6 tiếng")
                .unitPrice(BigDecimal.valueOf(3_000))
                .build();

        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
                .visitId(100L)
                .doctorId(200L)
                .diagnosisText("Viêm tủy cấp răng hàm dưới, viêm nướu hoại tử")
                .adviceNotes("Bệnh nhân kiêng đồ cay nóng, không nhai bên hàm vừa làm thủ thuật")
                .items(List.of(item1, item2))
                .build();

        when(visitRepository.findById(100L)).thenReturn(Optional.of(mockVisit));
        when(staffProfileRepository.findById(200L)).thenReturn(Optional.of(mockDoctor));
        when(medicationRepository.findById(1L)).thenReturn(Optional.of(mockMedicationAmox));
        when(medicationRepository.findById(2L)).thenReturn(Optional.of(mockMedicationPara));

        ArgumentCaptor<Prescription> captor = ArgumentCaptor.forClass(Prescription.class);
        when(prescriptionRepository.save(captor.capture())).thenAnswer(inv -> {
            Prescription p = inv.getArgument(0);
            p.setPrescriptionId(501L);
            p.setPrescriptionCode("DT20261103001");
            return p;
        });

        PrescriptionResponseDTO response = prescriptionService.createPrescription(request);

        Prescription saved = captor.getValue();
        assertNotNull(saved);
        assertEquals(200L, saved.getDoctor().getStaffId());
        assertEquals("BS. CKI Lê Quốc Tuấn", saved.getDoctor().getFullName());
        assertEquals(2, saved.getItems().size());

        assertEquals(501L, response.getPrescriptionId());
        assertEquals(2, response.getItems().size());
    }

    @Test
    @DisplayName("FT18-PRESC-02: Ràng buộc số lượng thuốc kê đơn không được âm hoặc bằng 0")
    void testCreatePrescription_ZeroOrNegativeQuantity_ThrowsIllegalArgumentException() {
        PrescriptionItemRequestDTO invalidItem = PrescriptionItemRequestDTO.builder()
                .medicationId(1L)
                .drugName("Amoxicillin 500mg")
                .quantity(BigDecimal.ZERO)
                .durationDays(5)
                .build();

        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
                .visitId(100L)
                .doctorId(200L)
                .items(List.of(invalidItem))
                .build();

        when(visitRepository.findById(100L)).thenReturn(Optional.of(mockVisit));
        when(staffProfileRepository.findById(200L)).thenReturn(Optional.of(mockDoctor));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                prescriptionService.createPrescription(request));

        assertTrue(ex.getMessage().contains("Số lượng thuốc"));
        verify(prescriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("FT18-PRESC-03: Khóa bệnh án BR-EMR-02 - Chặn tạo đơn thuốc mới khi ca khám đã chốt thanh toán")
    void testCreatePrescription_BlockedWhenVisitLocked() {
        mockVisit.setStatus(VisitStatus.WAITING_PAYMENT);

        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
                .visitId(100L)
                .doctorId(200L)
                .items(List.of(PrescriptionItemRequestDTO.builder().medicationId(1L).drugName("Thuốc A").quantity(BigDecimal.valueOf(5)).build()))
                .build();

        when(visitRepository.findById(100L)).thenReturn(Optional.of(mockVisit));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                prescriptionService.createPrescription(request));

        assertTrue(ex.getMessage().contains("BR-EMR-02"));
        verify(prescriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("FT18-PRESC-04: Xóa đơn thuốc thành công khi ca khám đang diễn ra (IN_EXAM)")
    void testDeletePrescription_SuccessWhenActive() {
        Prescription p = new Prescription();
        p.setPrescriptionId(501L);
        p.setVisit(mockVisit);

        when(prescriptionRepository.findById(501L)).thenReturn(Optional.of(p));

        prescriptionService.deletePrescription(501L);

        verify(prescriptionRepository, times(1)).delete(p);
    }

    @Test
    @DisplayName("FT18-PRESC-05: Lấy danh mục thuốc khả dụng và đơn thuốc theo ca khám")
    void testGetActiveMedicationsAndPrescriptionsByVisit() {
        when(medicationRepository.findByIsActiveTrueOrderByDrugNameAsc())
                .thenReturn(List.of(mockMedicationAmox, mockMedicationPara));

        List<MedicationDTO> activeMeds = prescriptionService.getActiveMedications();
        assertEquals(2, activeMeds.size());

        Prescription p = new Prescription();
        p.setPrescriptionId(1L);
        p.setPrescriptionCode("DT001");
        p.setVisit(mockVisit);
        p.setPatientProfile(mockPatient);
        p.setDoctor(mockDoctor);

        when(prescriptionRepository.findByVisit_VisitIdOrderByCreatedAtDesc(100L))
                .thenReturn(List.of(p));

        List<PrescriptionResponseDTO> list = prescriptionService.getPrescriptionsByVisit(100L);
        assertEquals(1, list.size());
        assertEquals("DT001", list.get(0).getPrescriptionCode());
    }

    // =========================================================================
    // PHẦN B: HẸN TÁI KHÁM & NHẮC LỊCH CSKH (RE-EXAMINATION SERVICE)
    // =========================================================================

    @Test
    @DisplayName("FT18-REEXAM-01: Bác sĩ chỉ định ngày hẹn tái khám và ghi chú mục đích")
    void testCreateReExamination_DoctorAssignsDateAndFollowupReason() {
        LocalDate appointmentDate = LocalDate.now().plusDays(7);
        ReExaminationRequestDTO dto = ReExaminationRequestDTO.builder()
                .patientProfileId(10L)
                .visitId(100L)
                .branchId(1L)
                .doctorId(200L)
                .serviceId(70L)
                .appointmentDate(appointmentDate)
                .preferredSession(PreferredSession.MORNING)
                .expectedTime("09:30")
                .followupReason("Cắt chỉ vết thương nhổ răng khôn số 38")
                .createCareTask(false)
                .build();

        when(patientProfileRepository.findById(10L)).thenReturn(Optional.of(mockPatient));
        when(branchRepository.findById(1L)).thenReturn(Optional.of(mockBranch));
        when(reExamStaffRepository.findById(200L)).thenReturn(Optional.of(mockDoctor));
        when(reExamServiceRepository.findById(70L)).thenReturn(Optional.of(mockServiceReExam));

        ArgumentCaptor<Appointment> captor = ArgumentCaptor.forClass(Appointment.class);
        when(appointmentRepository.save(captor.capture())).thenAnswer(inv -> {
            Appointment app = inv.getArgument(0);
            app.setAppointmentId(888L);
            app.setAppointmentCode("LH20261110001");
            return app;
        });

        ReExaminationResponseDTO result = reExaminationService.createReExamination(dto);

        Appointment savedApp = captor.getValue();
        assertNotNull(savedApp);
        assertEquals(appointmentDate, savedApp.getAppointmentDate());
        assertEquals("Cắt chỉ vết thương nhổ răng khôn số 38", savedApp.getInitialSymptoms());

        assertEquals(888L, result.getAppointmentId());
        assertEquals(appointmentDate, result.getAppointmentDate());
    }

    @Test
    @DisplayName("FT18-REEXAM-02: Tự động sinh bản ghi trong danh sách cần nhắc lịch cho CSKH (FT44)")
    void testCreateReExamination_AutoGenerateCustomerCareTask_FT44() {
        LocalDate appointmentDate = LocalDate.now().plusDays(5);
        ReExaminationRequestDTO dto = ReExaminationRequestDTO.builder()
                .patientProfileId(10L)
                .branchId(1L)
                .doctorId(200L)
                .appointmentDate(appointmentDate)
                .followupReason("Tái khám chỉnh nha siết dây cung định kỳ")
                .createCareTask(true)
                .careTaskNotes("Nhắc bệnh nhân mang theo máng ngậm")
                .build();

        when(patientProfileRepository.findById(10L)).thenReturn(Optional.of(mockPatient));
        when(branchRepository.findById(1L)).thenReturn(Optional.of(mockBranch));
        when(reExamStaffRepository.findById(200L)).thenReturn(Optional.of(mockDoctor));

        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> {
            Appointment a = inv.getArgument(0);
            a.setAppointmentId(999L);
            a.setAppointmentCode("LH20261108001");
            return a;
        });

        ArgumentCaptor<CustomerCareTask> careCaptor = ArgumentCaptor.forClass(CustomerCareTask.class);
        when(customerCareTaskRepository.save(careCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        reExaminationService.createReExamination(dto);

        CustomerCareTask careTask = careCaptor.getValue();
        assertNotNull(careTask);
        assertEquals(CustomerCareTaskType.APPOINTMENT_REMINDER, careTask.getTaskType());
        assertEquals(appointmentDate.minusDays(1), careTask.getDueDate());
        assertEquals(CustomerCareTaskStatus.PENDING, careTask.getStatus());
        assertTrue(careTask.getNotes().contains("Nhắc bệnh nhân mang theo máng ngậm"));
    }

    @Test
    @DisplayName("FT18-REEXAM-03: Ràng buộc ngày hẹn tái khám không được nằm trong quá khứ")
    void testCreateReExamination_PastAppointmentDate_ThrowsException() {
        LocalDate pastDate = LocalDate.now().minusDays(1);
        ReExaminationRequestDTO dto = ReExaminationRequestDTO.builder()
                .patientProfileId(10L)
                .branchId(1L)
                .appointmentDate(pastDate)
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                reExaminationService.createReExamination(dto));

        assertTrue(ex.getMessage().contains("quá khứ"));
        verify(appointmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("FT18-REEXAM-04: Dời ngày hẹn tái khám (Reschedule) và tự động đồng bộ ngày nhắc lịch CSKH")
    void testUpdateAppointmentDate_RescheduleAndSyncCareTask() {
        Appointment app = new Appointment();
        app.setAppointmentId(888L);
        app.setAppointmentDate(LocalDate.now().plusDays(3));
        app.setStatus(AppointmentStatus.CONFIRMED);

        CustomerCareTask linkedTask = new CustomerCareTask();
        linkedTask.setTaskId(777L);
        linkedTask.setAppointment(app);
        linkedTask.setDueDate(LocalDate.now().plusDays(2));
        linkedTask.setStatus(CustomerCareTaskStatus.PENDING);

        when(appointmentRepository.findById(888L)).thenReturn(Optional.of(app));
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(customerCareTaskRepository.findFirstByAppointment_AppointmentId(888L)).thenReturn(Optional.of(linkedTask));

        LocalDate newDate = LocalDate.now().plusDays(10);
        ReExaminationResponseDTO result = reExaminationService.updateAppointmentDate(
                888L, newDate, "14:00", PreferredSession.AFTERNOON
        );

        assertNotNull(result);
        assertEquals(newDate, app.getAppointmentDate());
        assertEquals(newDate.minusDays(1), linkedTask.getDueDate());
        verify(customerCareTaskRepository).save(linkedTask);
    }

    @Test
    @DisplayName("FT18-REEXAM-05: Hủy lịch hẹn tái khám với lý do xác thực")
    void testCancelAppointment_Success() {
        Appointment app = new Appointment();
        app.setAppointmentId(888L);
        app.setAppointmentDate(LocalDate.now().plusDays(3));
        app.setPatientProfile(mockPatient);
        app.setStatus(AppointmentStatus.CONFIRMED);

        when(appointmentRepository.findById(888L)).thenReturn(Optional.of(app));
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));

        reExaminationService.cancelAppointment(888L, "Bệnh nhân đi công tác đột xuất");

        assertEquals(AppointmentStatus.CANCELLED, app.getStatus());
        assertEquals("Bệnh nhân đi công tác đột xuất", app.getCancelReason());
        verify(appointmentRepository).save(app);
    }

    @Test
    @DisplayName("FT18-REEXAM-06: Thống kê số lượng nhắc hẹn tái khám (getRecallsStatistics)")
    void testGetRecallsStatistics() {
        when(appointmentRepository.count()).thenReturn(150L);

        Map<String, Object> stats = reExaminationService.getRecallsStatistics();

        assertNotNull(stats);
        assertEquals(150L, stats.get("total"));
    }
}
