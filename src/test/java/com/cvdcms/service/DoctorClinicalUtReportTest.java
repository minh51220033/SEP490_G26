package com.cvdcms.service;

import com.cvdcms.DTO.*;
import com.cvdcms.emr.*;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.impl.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DoctorClinicalUtReport")
class DoctorClinicalUtReportTest {

    // Shared helper factories
    private static Branch branch(Long id) {
        Branch b = new Branch();
        b.setBranchId(id);
        b.setBranchCode("CN01");
        b.setBranchName("Cơ sở 1");
        return b;
    }

    private static PatientProfile patient(Long id, Branch b) {
        PatientProfile p = new PatientProfile();
        p.setProfileId(id);
        p.setPatientCode("BN001");
        p.setFullName("Nguyễn Văn A");
        p.setPhoneNumber("0901234567");
        p.setPreferredBranch(b);
        p.setDepositBalance(new BigDecimal("1000000"));
        return p;
    }

    private static StaffProfile doctor(Long id, String name, Branch b) {
        StaffProfile s = new StaffProfile();
        s.setStaffId(id);
        s.setFullName(name);
        s.setStatus(StaffStatus.ACTIVE);
        s.setPracticeLicenseNo("CCHN-001");
        User u = new User();
        u.setUserId(id + 100);
        u.setUsername("bs_" + id);
        u.setBranch(b);
        u.setIsActive(true);
        u.setStatus("ACTIVE");
        s.setUser(u);
        return s;
    }

    private static Visit visit(Long id, VisitStatus status, PatientProfile p, StaffProfile d, Branch b) {
        Visit v = new Visit();
        v.setVisitId(id);
        v.setVisitCode("VK00" + id);
        v.setStatus(status);
        v.setPatientProfile(p);
        v.setPrimaryDoctor(d);
        v.setBranch(b);
        return v;
    }

    private static com.cvdcms.entity.Service activeService(Long id, BigDecimal price) {
        ServiceCategory cat = new ServiceCategory();
        cat.setCategoryId(1L);
        cat.setIsActive(true);
        com.cvdcms.entity.Service s = new com.cvdcms.entity.Service();
        s.setServiceId(id);
        s.setServiceCode("DV0" + id);
        s.setServiceName("Nhổ răng khôn");
        s.setBasePrice(price);
        s.setStatus(ServiceStatus.ACTIVE);
        s.setCategory(cat);
        return s;
    }

    // =========================================================================
    // 1. SaveGeneralExam (10 cases)
    // =========================================================================
    @Nested
    @DisplayName("SaveGeneralExam")
    class SaveGeneralExam {
        @Mock ExaminationDiagnosisRepository diagnosisRepo;
        @Mock Icd10DiseaseRepository icdRepo;
        @Mock VisitRepository visitRepo;
        @Mock ClinicalExaminationRepository examRepo;
        @Mock StaffProfileRepository staffRepo;
        @Mock PerformedServiceRepository performedRepo;
        @Mock PrescriptionRepository prescriptionRepo;
        @Mock TreatmentPlanRepository planRepo;
        @Mock AppointmentRepository appointmentRepo;
        @Mock PatientProfileRepository patientRepo;
        @Mock InvoiceRepository invoiceRepo;
        @Mock ClinicalExaminationBootstrapService bootstrap;
        @Mock EmrRecordRepository emrRecordRepo;

        DiagnosisServiceImpl service;
        Branch b;
        PatientProfile p;
        StaffProfile d;
        Visit v;

        @BeforeEach
        void setUp() {
            service = new DiagnosisServiceImpl(
                    diagnosisRepo, icdRepo, visitRepo, examRepo, staffRepo,
                    performedRepo, prescriptionRepo, planRepo, appointmentRepo,
                    patientRepo, invoiceRepo, bootstrap);
            ReflectionTestUtils.setField(service, "emrRecordRepository", emrRecordRepo);
            b = branch(1L);
            p = patient(1L, b);
            d = doctor(10L, "BS. Sơn", b);
            v = visit(100L, VisitStatus.IN_EXAM, p, d, b);
        }

        private GeneralExaminationRequestDTO baseReq() {
            GeneralExaminationRequestDTO dto = new GeneralExaminationRequestDTO();
            dto.setVisitId(100L);
            dto.setInitialSymptoms("Đau răng hàm dưới");
            dto.setTmjStatus("NORMAL");
            dto.setHygieneStatus("GOOD");
            dto.setGingivaStatus("HEALTHY");
            dto.setSummaryAssessment("Viêm quanh thân răng 48");
            return dto;
        }

        @Test @DisplayName("UTCID01 (N) - Lưu khám tổng quát hợp lệ kèm kết luận summaryAssessment")
        void utc01_validGeneralExamWithSummary() {
            when(visitRepo.findWithDetailsById(100L)).thenReturn(Optional.of(v));
            when(examRepo.findByVisit_VisitId(100L)).thenReturn(Optional.of(new ClinicalExamination()));
            service.saveGeneralExamination(baseReq());
            verify(examRepo).save(argThat(e -> "Viêm quanh thân răng 48".equals(e.getSummaryAssessment())));
        }

        @Test @DisplayName("UTCID02 (N) - Lưu tiền sử dị ứng và bệnh nền đa yếu tố")
        void utc02_savesAllergiesAndUnderlyingDiseases() {
            when(visitRepo.findWithDetailsById(100L)).thenReturn(Optional.of(v));
            when(examRepo.findByVisit_VisitId(100L)).thenReturn(Optional.of(new ClinicalExamination()));
            GeneralExaminationRequestDTO dto = baseReq();
            dto.setHasAllergies(true);
            dto.setAllergyDetails("Dị ứng Lidocaine");
            dto.setHasDiabetes(true);
            dto.setHasBleedingDisorder(true);
            service.saveGeneralExamination(dto);
            verify(patientRepo).save(argThat(pt -> Boolean.TRUE.equals(pt.getHasAllergies())
                    && "Dị ứng Lidocaine".equals(pt.getAllergyDetails())
                    && Boolean.TRUE.equals(pt.getHasUnderlyingDisease())
                    && pt.getUnderlyingDiseaseDetails() != null
                    && pt.getUnderlyingDiseaseDetails().contains("Đái tháo đường")
                    && pt.getUnderlyingDiseaseDetails().contains("Rối loạn đông máu")));
            verify(examRepo, times(1)).save(any(ClinicalExamination.class));
        }

        @Test @DisplayName("UTCID03 (N) - Khởi tạo mới ClinicalExamination khi chưa có bản ghi")
        void utc03_createsExamWhenMissing() {
            when(visitRepo.findWithDetailsById(100L)).thenReturn(Optional.of(v));
            when(examRepo.findByVisit_VisitId(100L)).thenReturn(Optional.empty());
            when(bootstrap.ensureForAssignedVisit(100L)).thenReturn(new ClinicalExamination());
            service.saveGeneralExamination(baseReq());
            verify(bootstrap).ensureForAssignedVisit(100L);
            verify(examRepo, times(1)).save(any(ClinicalExamination.class));
        }

        @Test @DisplayName("UTCID04 (A) - Từ chối khi request null")
        void utc04_nullRequestThrows() {
            assertThrows(IllegalArgumentException.class, () -> service.saveGeneralExamination(null));
            verify(examRepo, never()).save(any());
            verify(visitRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID05 (A) - Từ chối khi visitId null")
        void utc05_nullVisitIdThrows() {
            GeneralExaminationRequestDTO dto = baseReq();
            dto.setVisitId(null);
            assertThrows(IllegalArgumentException.class, () -> service.saveGeneralExamination(dto));
            verify(examRepo, never()).save(any());
            verify(visitRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID06 (A) - Từ chối khi không tìm thấy ca khám")
        void utc06_visitNotFoundThrows() {
            when(visitRepo.findWithDetailsById(100L)).thenReturn(Optional.empty());
            assertThrows(IllegalArgumentException.class, () -> service.saveGeneralExamination(baseReq()));
            verify(examRepo, never()).save(any());
            verify(visitRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID07 (A) - Chặn sửa khi Visit ở trạng thái COMPLETED")
        void utc07_completedVisitBlocked() {
            v.setStatus(VisitStatus.COMPLETED);
            when(visitRepo.findWithDetailsById(100L)).thenReturn(Optional.of(v));
            assertThrows(IllegalStateException.class, () -> service.saveGeneralExamination(baseReq()));
            verify(examRepo, never()).save(any());
            verify(visitRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID08 (A) - Chặn sửa khi EmrRecord đã SIGNED")
        void utc08_signedEmrBlocked() {
            EmrRecord rec = new EmrRecord();
            rec.setStatus(EmrRecordStatus.SIGNED);
            when(visitRepo.findWithDetailsById(100L)).thenReturn(Optional.of(v));
            when(emrRecordRepo.findByVisit_VisitId(100L)).thenReturn(Optional.of(rec));
            assertThrows(IllegalStateException.class, () -> service.saveGeneralExamination(baseReq()));
            verify(examRepo, never()).save(any());
            verify(visitRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID09 (B) - SummaryAssessment toàn khoảng trắng được chuẩn hóa thành null")
        void utc09_blankSummaryNormalizedToNull() {
            when(visitRepo.findWithDetailsById(100L)).thenReturn(Optional.of(v));
            when(examRepo.findByVisit_VisitId(100L)).thenReturn(Optional.of(new ClinicalExamination()));
            GeneralExaminationRequestDTO dto = baseReq();
            dto.setSummaryAssessment("    ");
            service.saveGeneralExamination(dto);
            verify(examRepo, times(1)).save(argThat(e -> e.getSummaryAssessment() == null));
        }

        @Test @DisplayName("UTCID10 (B) - InitialSymptoms có khoảng trắng đầu cuối được trim sạch")
        void utc10_initialSymptomsTrimmed() {
            when(visitRepo.findWithDetailsById(100L)).thenReturn(Optional.of(v));
            when(examRepo.findByVisit_VisitId(100L)).thenReturn(Optional.of(new ClinicalExamination()));
            GeneralExaminationRequestDTO dto = baseReq();
            dto.setInitialSymptoms("  Ê buốt răng 36  ");
            service.saveGeneralExamination(dto);
            verify(visitRepo).save(argThat(vis -> "Ê buốt răng 36".equals(vis.getInitialSymptoms())));
            verify(examRepo, times(1)).save(argThat(e -> "Ê buốt răng 36".equals(e.getChiefComplaint())));
        }
    }

    // =========================================================================
    // 2. CompleteExam (10 cases)
    // =========================================================================
    @Nested
    @DisplayName("CompleteExam")
    class CompleteExam {
        @Mock ExaminationDiagnosisRepository diagnosisRepo;
        @Mock Icd10DiseaseRepository icdRepo;
        @Mock VisitRepository visitRepo;
        @Mock ClinicalExaminationRepository examRepo;
        @Mock StaffProfileRepository staffRepo;
        @Mock PerformedServiceRepository performedRepo;
        @Mock PrescriptionRepository prescriptionRepo;
        @Mock TreatmentPlanRepository planRepo;
        @Mock AppointmentRepository appointmentRepo;
        @Mock PatientProfileRepository patientRepo;
        @Mock InvoiceRepository invoiceRepo;
        @Mock ClinicalExaminationBootstrapService bootstrap;
        @Mock EmrRecordRepository emrRecordRepo;

        DiagnosisServiceImpl service;
        Branch b;
        PatientProfile p;
        StaffProfile d;
        Visit v;

        @BeforeEach
        void setUp() {
            service = new DiagnosisServiceImpl(
                    diagnosisRepo, icdRepo, visitRepo, examRepo, staffRepo,
                    performedRepo, prescriptionRepo, planRepo, appointmentRepo,
                    patientRepo, invoiceRepo, bootstrap);
            ReflectionTestUtils.setField(service, "emrRecordRepository", emrRecordRepo);
            b = branch(1L);
            p = patient(1L, b);
            d = doctor(10L, "BS. Sơn", b);
            v = visit(100L, VisitStatus.IN_EXAM, p, d, b);
            lenient().when(visitRepo.findWithDetailsById(100L)).thenReturn(Optional.of(v));
            lenient().when(visitRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            lenient().when(examRepo.findByVisit_VisitId(100L)).thenReturn(Optional.of(new ClinicalExamination()));
        }

        @Test @DisplayName("UTCID01 (N) - Hoàn tất khám không phát sinh chi phí chuyển COMPLETED")
        void utc01_noPayableMovesToCompleted() {
            when(diagnosisRepo.findByVisitIdWithDetails(100L))
                    .thenReturn(List.of(new ExaminationDiagnosis()));
            when(performedRepo.findByVisitIdWithDetails(100L)).thenReturn(List.of());
            when(prescriptionRepo.findByVisit_VisitIdOrderByCreatedAtDesc(100L)).thenReturn(List.of());

            Visit result = service.completeClinicalExamination(100L, "Theo dõi thêm");
            assertEquals(VisitStatus.COMPLETED, result.getStatus());
            verify(examRepo).save(any(ClinicalExamination.class));
            verify(visitRepo).save(result);
        }

        @Test @DisplayName("UTCID02 (N) - Hoàn tất khám có thủ thuật thu phí chuyển WAITING_PAYMENT")
        void utc02_billableServiceMovesToWaitingPayment() {
            PerformedService ps = new PerformedService();
            ps.setStatus(PerformedServiceStatus.COMPLETED);
            ps.setFinalPrice(new BigDecimal("500000"));
            when(diagnosisRepo.findByVisitIdWithDetails(100L))
                    .thenReturn(List.of(new ExaminationDiagnosis()));
            when(performedRepo.findByVisitIdWithDetails(100L)).thenReturn(List.of(ps));
            when(prescriptionRepo.findByVisit_VisitIdOrderByCreatedAtDesc(100L)).thenReturn(List.of());

            Visit result = service.completeClinicalExamination(100L, "Đã nhổ răng");
            assertEquals(VisitStatus.WAITING_PAYMENT, result.getStatus());
        }

        @Test @DisplayName("UTCID03 (N) - Hoàn tất khám có đơn thuốc chuyển WAITING_PAYMENT")
        void utc03_prescriptionMovesToWaitingPayment() {
            Prescription rx = new Prescription();
            rx.setItems(List.of());
            when(diagnosisRepo.findByVisitIdWithDetails(100L))
                    .thenReturn(List.of(new ExaminationDiagnosis()));
            when(performedRepo.findByVisitIdWithDetails(100L)).thenReturn(List.of());
            when(prescriptionRepo.findByVisit_VisitIdOrderByCreatedAtDesc(100L)).thenReturn(List.of(rx));

            Visit result = service.completeClinicalExamination(100L, "Uống thuốc theo đơn");
            assertEquals(VisitStatus.WAITING_PAYMENT, result.getStatus());
        }

        @Test @DisplayName("UTCID04 (N) - Tự động lưu doctorNotes vào summaryAssessment khi hoàn tất")
        void utc04_populatesSummaryAssessmentFromNotes() {
            ClinicalExamination exam = new ClinicalExamination();
            when(examRepo.findByVisit_VisitId(100L)).thenReturn(Optional.of(exam));
            when(diagnosisRepo.findByVisitIdWithDetails(100L))
                    .thenReturn(List.of(new ExaminationDiagnosis()));
            when(performedRepo.findByVisitIdWithDetails(100L)).thenReturn(List.of());
            when(prescriptionRepo.findByVisit_VisitIdOrderByCreatedAtDesc(100L)).thenReturn(List.of());

            service.completeClinicalExamination(100L, "Ổn định sau điều trị");
            assertEquals("Ổn định sau điều trị", exam.getSummaryAssessment());
        }

        @Test @DisplayName("UTCID05 (A) - Chặn hoàn tất khi ca khám đã ở trạng thái WAITING_PAYMENT (chưa sẵn sàng)")
        void utc05_notReadyThrowsIllegalState() {
            v.setStatus(VisitStatus.WAITING_PAYMENT);
            when(diagnosisRepo.findByVisitIdWithDetails(100L)).thenReturn(List.of());
            when(performedRepo.findByVisitIdWithDetails(100L)).thenReturn(List.of());
            when(prescriptionRepo.findByVisit_VisitIdOrderByCreatedAtDesc(100L)).thenReturn(List.of());

            assertThrows(IllegalStateException.class,
                    () -> service.completeClinicalExamination(100L, "Note"));
            verify(visitRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID06 (A) - Từ chối khi không tìm thấy ca khám")
        void utc06_visitNotFoundThrows() {
            when(visitRepo.findWithDetailsById(999L)).thenReturn(Optional.empty());
            assertThrows(IllegalArgumentException.class,
                    () -> service.completeClinicalExamination(999L, "Note"));
            verify(visitRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID07 (A) - Chặn hoàn tất khi Visit đã CANCELLED")
        void utc07_cancelledVisitBlocked() {
            v.setStatus(VisitStatus.CANCELLED);
            when(diagnosisRepo.findByVisitIdWithDetails(100L))
                    .thenReturn(List.of(new ExaminationDiagnosis()));
            when(performedRepo.findByVisitIdWithDetails(100L)).thenReturn(List.of());
            assertThrows(IllegalStateException.class,
                    () -> service.completeClinicalExamination(100L, "Note"));
            verify(visitRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID08 (A) - Chặn hoàn tất khi EmrRecord đã khóa chờ ký")
        void utc08_lockedEmrBlocked() {
            EmrRecord rec = new EmrRecord();
            rec.setStatus(EmrRecordStatus.DOCTOR_SIGNATURE_PENDING);
            when(emrRecordRepo.findByVisit_VisitId(100L)).thenReturn(Optional.of(rec));
            when(diagnosisRepo.findByVisitIdWithDetails(100L))
                    .thenReturn(List.of(new ExaminationDiagnosis()));
            when(performedRepo.findByVisitIdWithDetails(100L)).thenReturn(List.of());
            assertThrows(IllegalStateException.class,
                    () -> service.completeClinicalExamination(100L, "Note"));
            verify(visitRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID09 (B) - Chỉ có dịch vụ thuộc KHDT trả góp không tạo hóa đơn lẻ -> COMPLETED")
        void utc09_installmentOnlyCompletedDirectly() {
            TreatmentPlan plan = new TreatmentPlan();
            plan.setIsInstallment(true);
            TreatmentPlanStage stage = new TreatmentPlanStage();
            stage.setPlan(plan);
            TreatmentPlanItem item = new TreatmentPlanItem();
            item.setStage(stage);
            item.setPlan(plan);
            PerformedService ps = new PerformedService();
            ps.setStatus(PerformedServiceStatus.COMPLETED);
            ps.setFinalPrice(new BigDecimal("2000000"));
            ps.setPlanItem(item);

            when(diagnosisRepo.findByVisitIdWithDetails(100L))
                    .thenReturn(List.of(new ExaminationDiagnosis()));
            when(performedRepo.findByVisitIdWithDetails(100L)).thenReturn(List.of(ps));
            when(prescriptionRepo.findByVisit_VisitIdOrderByCreatedAtDesc(100L)).thenReturn(List.of());

            Visit result = service.completeClinicalExamination(100L, null);
            assertEquals(VisitStatus.COMPLETED, result.getStatus());
        }

        @Test @DisplayName("UTCID10 (B) - doctorNotes null vẫn tự tổng hợp summaryAssessment từ chẩn đoán")
        void utc10_nullNotesSynthesizesSummary() {
            ClinicalExamination exam = new ClinicalExamination();
            ExaminationDiagnosis diag = new ExaminationDiagnosis();
            diag.setDiagnosisText("Sâu răng");
            diag.setDiagnosisType(DiagnosisType.MAIN);
            when(examRepo.findByVisit_VisitId(100L)).thenReturn(Optional.of(exam));
            when(diagnosisRepo.findByVisitIdWithDetails(100L)).thenReturn(List.of(diag));
            when(diagnosisRepo.findByVisit_VisitId(100L)).thenReturn(List.of(diag));
            when(performedRepo.findByVisitIdWithDetails(100L)).thenReturn(List.of());
            when(prescriptionRepo.findByVisit_VisitIdOrderByCreatedAtDesc(100L)).thenReturn(List.of());

            Visit result = service.completeClinicalExamination(100L, null);
            assertEquals(VisitStatus.COMPLETED, result.getStatus());
            assertNotNull(exam.getSummaryAssessment());
            assertTrue(exam.getSummaryAssessment().contains("Sâu răng"));
            verify(visitRepo).save(result);
        }
    }

    // =========================================================================
    // 3. CreatePerformedSvc (10 cases)
    // =========================================================================
    @Nested
    @DisplayName("CreatePerformedSvc")
    class CreatePerformedSvc {
        @Mock PerformedServiceRepository performedRepo;
        @Mock ServiceRepository serviceRepo;
        @Mock StaffProfileRepository staffRepo;
        @Mock VisitRepository visitRepo;
        @Mock TreatmentPlanItemRepository planItemRepo;
        @Mock TreatmentPlanStageRepository stageRepo;
        @Mock TreatmentPlanRepository planRepo;
        @Mock ClinicalWorkflowTemplateRepository wfTemplateRepo;
        @Mock ClinicalWorkflowVersionRepository wfVersionRepo;
        @Mock ClinicalWorkflowStepRepository wfStepRepo;
        @Mock EmrRecordRepository emrRecordRepo;

        PerformedServiceServiceImpl service;
        Branch b;
        PatientProfile p;
        StaffProfile d;
        Visit v;
        com.cvdcms.entity.Service svc;

        @BeforeEach
        void setUp() {
            service = new PerformedServiceServiceImpl(
                    performedRepo, serviceRepo, staffRepo, visitRepo,
                    planItemRepo, stageRepo, planRepo,
                    wfTemplateRepo, wfVersionRepo, wfStepRepo);
            ReflectionTestUtils.setField(service, "emrRecordRepository", emrRecordRepo);
            b = branch(1L);
            p = patient(1L, b);
            d = doctor(10L, "BS. Sơn", b);
            v = visit(100L, VisitStatus.IN_EXAM, p, d, b);
            svc = activeService(5L, new BigDecimal("500000"));
            lenient().when(visitRepo.findById(100L)).thenReturn(Optional.of(v));
            lenient().when(serviceRepo.findById(5L)).thenReturn(Optional.of(svc));
            lenient().when(staffRepo.findById(10L)).thenReturn(Optional.of(d));
            lenient().when(performedRepo.save(any())).thenAnswer(inv -> {
                PerformedService ps = inv.getArgument(0);
                ps.setPerformedId(1L);
                return ps;
            });
        }

        private PerformedServiceRequestDTO validReq() {
            PerformedServiceRequestDTO dto = new PerformedServiceRequestDTO();
            dto.setVisitId(100L);
            dto.setServiceId(5L);
            dto.setDoctorId(10L);
            dto.setQuantity(new BigDecimal("2"));
            dto.setUnitPrice(new BigDecimal("500000"));
            dto.setDiscountAmount(new BigDecimal("100000"));
            return dto;
        }

        @Test @DisplayName("UTCID01 (N) - Tạo dịch vụ thực hiện hợp lệ, tính đúng finalPrice = qty*unit - discount")
        void utc01_validCreateComputesFinalPrice() {
            PerformedServiceResponseDTO res = service.createPerformedService(validReq());
            assertEquals(0, new BigDecimal("900000").compareTo(res.getFinalPrice()));
            assertEquals("COMPLETED", res.getStatus());
        }

        @Test @DisplayName("UTCID02 (N) - Liên kết planItemId tự động cập nhật trạng thái COMPLETED cho bước")
        void utc02_linkedPlanItemUpdatedToCompleted() {
            TreatmentPlan plan = new TreatmentPlan();
            TreatmentPlanStage stage = new TreatmentPlanStage();
            stage.setPlan(plan);
            TreatmentPlanItem item = new TreatmentPlanItem();
            item.setPlanItemId(50L);
            item.setStage(stage);
            item.setPlan(plan);
            stage.setItems(List.of(item));
            plan.setStages(List.of(stage));
            when(planItemRepo.findById(50L)).thenReturn(Optional.of(item));

            PerformedServiceRequestDTO dto = validReq();
            dto.setPlanItemId(50L);
            PerformedServiceResponseDTO res = service.createPerformedService(dto);
            assertEquals(0, new BigDecimal("900000").compareTo(res.getFinalPrice()));
            assertEquals(TreatmentPlanItemStatus.COMPLETED, item.getStatus());
            assertEquals(TreatmentPlanStageStatus.COMPLETED, stage.getStatus());
            assertEquals(TreatmentPlanStatus.COMPLETED, plan.getStatus());
            verify(performedRepo).save(any(PerformedService.class));
        }

        @Test @DisplayName("UTCID03 (N) - Tự lấy basePrice khi unitPrice không truyền")
        void utc03_defaultsToServiceBasePrice() {
            PerformedServiceRequestDTO dto = validReq();
            dto.setUnitPrice(null);
            dto.setDiscountAmount(null);
            dto.setQuantity(BigDecimal.ONE);
            PerformedServiceResponseDTO res = service.createPerformedService(dto);
            assertEquals(0, new BigDecimal("500000").compareTo(res.getFinalPrice()));
        }

        @Test @DisplayName("UTCID04 (A) - Từ chối khi ca khám không tồn tại")
        void utc04_visitNotFoundThrows() {
            when(visitRepo.findById(999L)).thenReturn(Optional.empty());
            PerformedServiceRequestDTO dto = validReq();
            dto.setVisitId(999L);
            assertThrows(IllegalArgumentException.class, () -> service.createPerformedService(dto));
            verify(performedRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID05 (A) - Chặn thêm dịch vụ khi Visit đang WAITING_PAYMENT")
        void utc05_waitingPaymentVisitBlocked() {
            v.setStatus(VisitStatus.WAITING_PAYMENT);
            assertThrows(IllegalStateException.class, () -> service.createPerformedService(validReq()));
            verify(performedRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID06 (A) - Chặn thêm dịch vụ khi dịch vụ đang INACTIVE")
        void utc06_inactiveServiceRejected() {
            svc.setStatus(ServiceStatus.INACTIVE);
            assertThrows(IllegalStateException.class, () -> service.createPerformedService(validReq()));
            verify(performedRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID07 (A) - Chặn khi bác sĩ không ở trạng thái ACTIVE")
        void utc07_inactiveDoctorRejected() {
            d.setStatus(StaffStatus.INACTIVE);
            assertThrows(IllegalArgumentException.class, () -> service.createPerformedService(validReq()));
            verify(performedRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID08 (B) - Chiết khấu vượt tổng tiền được chặn về 0 (không âm)")
        void utc08_discountExceedingTotalClampedToZero() {
            PerformedServiceRequestDTO dto = validReq();
            dto.setQuantity(BigDecimal.ONE);
            dto.setUnitPrice(new BigDecimal("100000"));
            dto.setDiscountAmount(new BigDecimal("500000"));
            PerformedServiceResponseDTO res = service.createPerformedService(dto);
            assertEquals(0, BigDecimal.ZERO.compareTo(res.getFinalPrice()));
        }

        @Test @DisplayName("UTCID09 (B) - Số lượng <= 0 tự động chuẩn hóa về 1")
        void utc09_nonPositiveQuantityDefaultsToOne() {
            PerformedServiceRequestDTO dto = validReq();
            dto.setQuantity(BigDecimal.ZERO);
            dto.setDiscountAmount(BigDecimal.ZERO);
            PerformedServiceResponseDTO res = service.createPerformedService(dto);
            assertEquals(0, BigDecimal.ONE.compareTo(res.getQuantity()));
            assertEquals(0, new BigDecimal("500000").compareTo(res.getFinalPrice()));
        }

        @Test @DisplayName("UTCID10 (B) - Bỏ qua finalPrice gửi từ client, luôn tính lại chuẩn backend")
        void utc10_ignoresClientFinalPrice() {
            PerformedServiceRequestDTO dto = validReq();
            dto.setFinalPrice(new BigDecimal("1"));
            PerformedServiceResponseDTO res = service.createPerformedService(dto);
            assertEquals(0, new BigDecimal("900000").compareTo(res.getFinalPrice()));
        }
    }

    // =========================================================================
    // 4. CancelPerformedSvc (10 cases)
    // =========================================================================
    @Nested
    @DisplayName("CancelPerformedSvc")
    class CancelPerformedSvc {
        @Mock PerformedServiceRepository performedRepo;
        @Mock ServiceRepository serviceRepo;
        @Mock StaffProfileRepository staffRepo;
        @Mock VisitRepository visitRepo;
        @Mock TreatmentPlanItemRepository planItemRepo;
        @Mock TreatmentPlanStageRepository stageRepo;
        @Mock TreatmentPlanRepository planRepo;
        @Mock ClinicalWorkflowTemplateRepository wfTemplateRepo;
        @Mock ClinicalWorkflowVersionRepository wfVersionRepo;
        @Mock ClinicalWorkflowStepRepository wfStepRepo;
        @Mock InvoiceItemRepository invoiceItemRepo;
        @Mock EmrRecordRepository emrRecordRepo;
        @Mock MaterialConsumptionRepository consumptionRepo;
        @Mock MaterialBatchRepository batchRepo;
        @Mock BranchInventoryRepository branchInvRepo;

        PerformedServiceServiceImpl service;
        Branch b;
        Visit v;
        PerformedService ps;

        @BeforeEach
        void setUp() {
            service = new PerformedServiceServiceImpl(
                    performedRepo, serviceRepo, staffRepo, visitRepo,
                    planItemRepo, stageRepo, planRepo,
                    wfTemplateRepo, wfVersionRepo, wfStepRepo);
            ReflectionTestUtils.setField(service, "invoiceItemRepository", invoiceItemRepo);
            ReflectionTestUtils.setField(service, "emrRecordRepository", emrRecordRepo);
            ReflectionTestUtils.setField(service, "materialConsumptionRepository", consumptionRepo);
            ReflectionTestUtils.setField(service, "materialBatchRepository", batchRepo);
            ReflectionTestUtils.setField(service, "branchInventoryRepository", branchInvRepo);
            b = branch(1L);
            PatientProfile p = patient(1L, b);
            StaffProfile d = doctor(10L, "BS. Sơn", b);
            v = visit(100L, VisitStatus.IN_EXAM, p, d, b);
            ps = new PerformedService();
            ps.setPerformedId(20L);
            ps.setVisit(v);
            ps.setBranch(b);
            ps.setStatus(PerformedServiceStatus.COMPLETED);
            lenient().when(performedRepo.findById(20L)).thenReturn(Optional.of(ps));
        }

        @Test @DisplayName("UTCID01 (N) - Hủy dịch vụ COMPLETED chưa lập hóa đơn thành công")
        void utc01_cancelCompletedUnbilledSucceeds() {
            when(invoiceItemRepo.existsByPerformedIdAndInvoiceNotCancelled(20L)).thenReturn(false);
            when(consumptionRepo.findByPerformedService_PerformedId(20L)).thenReturn(List.of());
            service.cancelPerformedService(20L);
            verify(performedRepo).delete(ps);
        }

        @Test @DisplayName("UTCID02 (N) - Hủy dịch vụ tự động hoàn lại vật tư tiêu hao vào kho")
        void utc02_restoresMaterialStockOnCancel() {
            Material m = new Material();
            m.setMaterialId(3L);
            MaterialBatch batch = new MaterialBatch();
            batch.setBatchId(7L);
            batch.setQuantityRemaining(new BigDecimal("5"));
            batch.setStatus(BatchStatus.OUT_OF_STOCK);
            MaterialConsumption mc = new MaterialConsumption();
            mc.setBranch(b);
            mc.setMaterial(m);
            mc.setBatch(batch);
            mc.setQuantity(new BigDecimal("2"));
            BranchInventory inv = new BranchInventory();
            inv.setCurrentStock(new BigDecimal("10"));

            when(invoiceItemRepo.existsByPerformedIdAndInvoiceNotCancelled(20L)).thenReturn(false);
            when(consumptionRepo.findByPerformedService_PerformedId(20L)).thenReturn(List.of(mc));
            when(batchRepo.findByIdForUpdate(7L)).thenReturn(Optional.of(batch));
            when(branchInvRepo.findForUpdate(1L, 3L)).thenReturn(Optional.of(inv));

            service.cancelPerformedService(20L);
            assertEquals(0, new BigDecimal("7").compareTo(batch.getQuantityRemaining()));
            assertEquals(BatchStatus.ACTIVE, batch.getStatus());
            assertEquals(0, new BigDecimal("12").compareTo(inv.getCurrentStock()));
            verify(batchRepo).save(batch);
            verify(branchInvRepo).save(inv);
            verify(consumptionRepo).deleteAll(List.of(mc));
            verify(performedRepo).delete(ps);
        }

        @Test @DisplayName("UTCID03 (N) - Hủy dịch vụ thuộc KHDT hoàn tác planItem về PLANNED")
        void utc03_revertsLinkedPlanItemToPlanned() {
            TreatmentPlan plan = new TreatmentPlan();
            TreatmentPlanStage stage = new TreatmentPlanStage();
            stage.setPlan(plan);
            TreatmentPlanItem item = new TreatmentPlanItem();
            item.setPlanItemId(50L);
            item.setStatus(TreatmentPlanItemStatus.COMPLETED);
            item.setStage(stage);
            stage.setItems(List.of(item));
            plan.setStages(List.of(stage));
            ps.setPlanItem(item);

            when(invoiceItemRepo.existsByPerformedIdAndInvoiceNotCancelled(20L)).thenReturn(false);
            when(consumptionRepo.findByPerformedService_PerformedId(20L)).thenReturn(List.of());
            when(performedRepo.findByPlanItem_PlanItemIdAndStatusNot(50L, PerformedServiceStatus.CANCELLED))
                    .thenReturn(List.of(ps));

            service.cancelPerformedService(20L);
            assertEquals(TreatmentPlanItemStatus.PLANNED, item.getStatus());
            assertEquals(TreatmentPlanStageStatus.PENDING, stage.getStatus());
            assertEquals(TreatmentPlanStatus.ACCEPTED, plan.getStatus());
            verify(performedRepo).delete(ps);
        }

        @Test @DisplayName("UTCID04 (A) - Từ chối khi không tìm thấy performedId")
        void utc04_notFoundThrows() {
            when(performedRepo.findById(999L)).thenReturn(Optional.empty());
            assertThrows(IllegalArgumentException.class, () -> service.cancelPerformedService(999L));
            verify(performedRepo, never()).delete(any());
        }

        @Test @DisplayName("UTCID05 (A) - Chặn hủy dịch vụ đã xuất hóa đơn còn hiệu lực")
        void utc05_invoicedServiceBlocked() {
            when(invoiceItemRepo.existsByPerformedIdAndInvoiceNotCancelled(20L)).thenReturn(true);
            assertThrows(IllegalStateException.class, () -> service.cancelPerformedService(20L));
            verify(performedRepo, never()).delete(any());
        }

        @Test @DisplayName("UTCID06 (A) - Chặn hủy khi EmrRecord đã SIGNED/khóa")
        void utc06_lockedEmrBlocked() {
            EmrRecord rec = new EmrRecord();
            rec.setStatus(EmrRecordStatus.SIGNED);
            when(emrRecordRepo.findByVisit_VisitId(100L)).thenReturn(Optional.of(rec));
            assertThrows(IllegalStateException.class, () -> service.cancelPerformedService(20L));
            verify(performedRepo, never()).delete(any());
        }

        @Test @DisplayName("UTCID07 (A) - Chặn hủy khi Visit ở trạng thái COMPLETED")
        void utc07_completedVisitBlocked() {
            v.setStatus(VisitStatus.COMPLETED);
            assertThrows(IllegalStateException.class, () -> service.cancelPerformedService(20L));
            verify(performedRepo, never()).delete(any());
        }

        @Test @DisplayName("UTCID08 (A) - Chặn hủy khi Visit ở trạng thái CANCELLED")
        void utc08_cancelledVisitBlocked() {
            v.setStatus(VisitStatus.CANCELLED);
            assertThrows(IllegalStateException.class, () -> service.cancelPerformedService(20L));
            verify(performedRepo, never()).delete(any());
        }

        @Test @DisplayName("UTCID09 (B) - Còn dịch vụ khác đang dùng chung planItem -> giữ nguyên planItem")
        void utc09_keepsPlanItemWhenOtherServiceExists() {
            TreatmentPlanItem item = new TreatmentPlanItem();
            item.setPlanItemId(50L);
            item.setStatus(TreatmentPlanItemStatus.COMPLETED);
            ps.setPlanItem(item);
            PerformedService other = new PerformedService();
            other.setPerformedId(21L);

            when(invoiceItemRepo.existsByPerformedIdAndInvoiceNotCancelled(20L)).thenReturn(false);
            when(consumptionRepo.findByPerformedService_PerformedId(20L)).thenReturn(List.of());
            when(performedRepo.findByPlanItem_PlanItemIdAndStatusNot(50L, PerformedServiceStatus.CANCELLED))
                    .thenReturn(List.of(ps, other));

            service.cancelPerformedService(20L);
            assertEquals(TreatmentPlanItemStatus.COMPLETED, item.getStatus());
            verify(performedRepo).delete(ps);
        }

        @Test @DisplayName("UTCID10 (B) - Không lỗi khi invoiceItemRepository chưa inject (null)")
        void utc10_worksWhenOptionalInvoiceRepoNull() {
            ReflectionTestUtils.setField(service, "invoiceItemRepository", null);
            when(consumptionRepo.findByPerformedService_PerformedId(20L)).thenReturn(List.of());
            assertDoesNotThrow(() -> service.cancelPerformedService(20L));
            verify(performedRepo).delete(ps);
        }
    }

    // =========================================================================
    // 5. CreatePlan (10 cases)
    // =========================================================================
    @Nested
    @DisplayName("CreatePlan")
    class CreatePlan {
        @Mock TreatmentPlanRepository planRepo;
        @Mock TreatmentPlanStageRepository stageRepo;
        @Mock TreatmentPlanItemRepository itemRepo;
        @Mock PatientProfileRepository patientRepo;
        @Mock BranchRepository branchRepo;
        @Mock StaffProfileRepository staffRepo;
        @Mock ServiceRepository serviceRepo;
        @Mock PerformedServiceRepository performedRepo;
        @Mock InvoiceItemRepository invoiceItemRepo;
        @Mock TreatmentPlanInstallmentRepository installmentRepo;

        TreatmentPlanServiceImpl service;
        Branch b;
        PatientProfile p;
        StaffProfile d;

        @BeforeEach
        void setUp() {
            service = new TreatmentPlanServiceImpl(
                    planRepo, stageRepo, itemRepo, patientRepo, branchRepo,
                    staffRepo, serviceRepo, performedRepo, invoiceItemRepo, installmentRepo);
            b = branch(1L);
            p = patient(1L, b);
            d = doctor(10L, "BS. Sơn", b);
            lenient().when(patientRepo.findById(1L)).thenReturn(Optional.of(p));
            lenient().when(branchRepo.findById(1L)).thenReturn(Optional.of(b));
            lenient().when(staffRepo.findById(10L)).thenReturn(Optional.of(d));
            com.cvdcms.entity.Service svc = activeService(5L, new BigDecimal("2000000"));
            lenient().when(serviceRepo.findById(5L)).thenReturn(Optional.of(svc));
            lenient().when(serviceRepo.findAll()).thenReturn(List.of(svc));
            lenient().when(planRepo.save(any())).thenAnswer(inv -> {
                TreatmentPlan tp = inv.getArgument(0);
                tp.setPlanId(1L);
                return tp;
            });
        }

        private TreatmentPlanRequestDTO validReq() {
            TreatmentPlanRequestDTO dto = new TreatmentPlanRequestDTO();
            dto.setPatientId(1L);
            dto.setBranchId(1L);
            dto.setDoctorId(10L);
            dto.setPlanName("Niềng răng mắc cài");
            dto.setStatus("PROPOSED");
            dto.setStartDate("2026-11-17");
            dto.setExpectedEndDate("2027-05-17");
            TreatmentPlanStageRequestDTO st = new TreatmentPlanStageRequestDTO();
            st.setStageName("Giai đoạn 1");
            TreatmentPlanItemRequestDTO it = new TreatmentPlanItemRequestDTO();
            it.setServiceId(5L);
            it.setQuantity(BigDecimal.ONE);
            it.setEstimatedPrice(new BigDecimal("2000000"));
            st.setItems(List.of(it));
            dto.setStages(List.of(st));
            return dto;
        }

        @Test @DisplayName("UTCID01 (N) - Tạo kế hoạch điều trị hợp lệ kèm giai đoạn và chi phí")
        void utc01_validPlanCreated() {
            TreatmentPlanResponseDTO res = service.createPlan(validReq());
            assertNotNull(res);
            assertEquals("Niềng răng mắc cài", res.getPlanName());
            assertEquals(0, new BigDecimal("2000000").compareTo(res.getTotalEstimatedCost()));
        }

        @Test @DisplayName("UTCID02 (N) - Tự dùng preferredBranch của bệnh nhân khi branchId null")
        void utc02_fallsBackToPreferredBranch() {
            TreatmentPlanRequestDTO dto = validReq();
            dto.setBranchId(null);
            TreatmentPlanResponseDTO res = service.createPlan(dto);
            assertNotNull(res);
            verify(planRepo).save(argThat(pl -> pl.getBranch().equals(b)));
        }

        @Test @DisplayName("UTCID03 (N) - Lưu kế hoạch trả góp với danh sách đợt installments")
        void utc03_installmentPlanCreated() {
            TreatmentPlanRequestDTO dto = validReq();
            dto.setIsInstallment(true);
            TreatmentPlanInstallmentDTO inst = new TreatmentPlanInstallmentDTO();
            inst.setInstallmentOrder(1);
            inst.setExpectedAmount(new BigDecimal("1000000"));
            inst.setExpectedDateRaw(LocalDate.now().plusDays(30).toString());
            dto.setInstallments(List.of(inst));
            TreatmentPlanResponseDTO res = service.createPlan(dto);
            assertTrue(Boolean.TRUE.equals(res.getIsInstallment()));
            assertEquals(1, res.getInstallments().size());
            assertEquals(0, new BigDecimal("1000000").compareTo(res.getInstallments().get(0).getExpectedAmount()));
        }

        @Test @DisplayName("UTCID04 (A) - Từ chối khi patientId null")
        void utc04_nullPatientIdThrows() {
            TreatmentPlanRequestDTO dto = validReq();
            dto.setPatientId(null);
            assertThrows(IllegalArgumentException.class, () -> service.createPlan(dto));
            verify(planRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID05 (A) - Từ chối khi không tìm thấy hồ sơ bệnh nhân")
        void utc05_patientNotFoundThrows() {
            when(patientRepo.findById(999L)).thenReturn(Optional.empty());
            TreatmentPlanRequestDTO dto = validReq();
            dto.setPatientId(999L);
            assertThrows(IllegalArgumentException.class, () -> service.createPlan(dto));
            verify(planRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID06 (A) - Từ chối khi hệ thống chưa có chi nhánh nào")
        void utc06_noBranchThrows() {
            p.setPreferredBranch(null);
            TreatmentPlanRequestDTO dto = validReq();
            dto.setBranchId(null);
            when(branchRepo.findAll()).thenReturn(List.of());
            assertThrows(IllegalStateException.class, () -> service.createPlan(dto));
            verify(planRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID07 (B) - Tên kế hoạch rỗng tự gán tên mặc định")
        void utc07_blankPlanNameDefaults() {
            TreatmentPlanRequestDTO dto = validReq();
            dto.setPlanName("   ");
            TreatmentPlanResponseDTO res = service.createPlan(dto);
            assertEquals("Kế hoạch điều trị nha khoa", res.getPlanName());
        }

        @Test @DisplayName("UTCID08 (B) - Status không hợp lệ tự fallback về PROPOSED")
        void utc08_invalidStatusFallsBackToProposed() {
            TreatmentPlanRequestDTO dto = validReq();
            dto.setStatus("INVALID_XYZ");
            TreatmentPlanResponseDTO res = service.createPlan(dto);
            assertEquals("PROPOSED", res.getStatus());
        }

        @Test @DisplayName("UTCID09 (B) - Ngày expectedEndDate sai định dạng tự fallback +90 ngày")
        void utc09_invalidDateFallsBack() {
            TreatmentPlanRequestDTO dto = validReq();
            dto.setExpectedEndDate("not-a-date");
            TreatmentPlanResponseDTO res = service.createPlan(dto);
            assertEquals("15/02/2027", res.getExpectedEndDate());
        }

        @Test @DisplayName("UTCID10 (B) - Kế hoạch không có giai đoạn nào vẫn tạo thành công với tổng 0")
        void utc10_emptyStagesAllowed() {
            TreatmentPlanRequestDTO dto = validReq();
            dto.setStages(List.of());
            TreatmentPlanResponseDTO res = service.createPlan(dto);
            assertNotNull(res);
            assertEquals(0, BigDecimal.ZERO.compareTo(res.getTotalEstimatedCost()));
        }
    }

    // =========================================================================
    // 6. UpdateStageStatus (10 cases)
    // =========================================================================
    @Nested
    @DisplayName("UpdateStageStatus")
    class UpdateStageStatus {
        @Mock TreatmentPlanRepository planRepo;
        @Mock TreatmentPlanStageRepository stageRepo;
        @Mock TreatmentPlanItemRepository itemRepo;
        @Mock PatientProfileRepository patientRepo;
        @Mock BranchRepository branchRepo;
        @Mock StaffProfileRepository staffRepo;
        @Mock ServiceRepository serviceRepo;
        @Mock PerformedServiceRepository performedRepo;
        @Mock InvoiceItemRepository invoiceItemRepo;
        @Mock TreatmentPlanInstallmentRepository installmentRepo;
        @Mock EmrRecordRepository emrRecordRepo;

        TreatmentPlanServiceImpl service;
        TreatmentPlan plan;
        TreatmentPlanStage stage;
        TreatmentPlanItem item;

        @BeforeEach
        void setUp() {
            service = new TreatmentPlanServiceImpl(
                    planRepo, stageRepo, itemRepo, patientRepo, branchRepo,
                    staffRepo, serviceRepo, performedRepo, invoiceItemRepo, installmentRepo);
            ReflectionTestUtils.setField(service, "emrRecordRepository", emrRecordRepo);
            Branch b = branch(1L);
            PatientProfile p = patient(1L, b);
            plan = new TreatmentPlan();
            plan.setPlanId(1L);
            plan.setPatientProfile(p);
            plan.setBranch(b);
            plan.setStatus(TreatmentPlanStatus.IN_PROGRESS);
            stage = new TreatmentPlanStage();
            stage.setStageId(10L);
            stage.setPlan(plan);
            stage.setStatus(TreatmentPlanStageStatus.IN_PROGRESS);
            item = new TreatmentPlanItem();
            item.setPlanItemId(100L);
            item.setStage(stage);
            item.setStatus(TreatmentPlanItemStatus.COMPLETED);
            stage.setItems(List.of(item));
            plan.setStages(List.of(stage));
            lenient().when(stageRepo.findById(10L)).thenReturn(Optional.of(stage));
            lenient().when(stageRepo.findByPlan_PlanIdOrderByStageOrderAsc(1L)).thenReturn(List.of(stage));
            lenient().when(itemRepo.findByStage_StageId(10L))
                    .thenAnswer(inv -> stage.getItems() != null ? stage.getItems() : List.of());
            lenient().when(planRepo.findById(1L)).thenReturn(Optional.of(plan));
            lenient().when(planRepo.findByIdWithDetails(1L)).thenReturn(Optional.of(plan));
            lenient().when(planRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test @DisplayName("UTCID01 (N) - Chuyển giai đoạn sang COMPLETED đánh dấu toàn bộ bước COMPLETED")
        void utc01_markCompletedUpdatesItems() {
            item.setStatus(TreatmentPlanItemStatus.PLANNED);
            TreatmentPlanResponseDTO res = service.updateStageStatus(10L, "COMPLETED");
            assertEquals(TreatmentPlanStageStatus.COMPLETED, stage.getStatus());
            assertEquals(TreatmentPlanItemStatus.COMPLETED, item.getStatus());
            assertEquals(TreatmentPlanStatus.COMPLETED, plan.getStatus());
            assertNotNull(res);
            verify(stageRepo, atLeastOnce()).save(stage);
        }

        @Test @DisplayName("UTCID02 (N) - Chuyển giai đoạn sang IN_PROGRESS chuyển bước PLANNED -> IN_PROGRESS")
        void utc02_markInProgress() {
            item.setStatus(TreatmentPlanItemStatus.PLANNED);
            service.updateStageStatus(10L, "IN_PROGRESS");
            assertEquals(TreatmentPlanStageStatus.IN_PROGRESS, stage.getStatus());
            assertEquals(TreatmentPlanItemStatus.IN_PROGRESS, item.getStatus());
            verify(stageRepo, atLeastOnce()).save(stage);
        }

        @Test @DisplayName("UTCID03 (N) - Hoàn tác về PENDING khi chưa có dịch vụ xuất hóa đơn")
        void utc03_resetToPendingWhenUnbilled() {
            PerformedService ps = new PerformedService();
            ps.setPerformedId(5L);
            ps.setVisit(visit(1L, VisitStatus.IN_EXAM, plan.getPatientProfile(), null, plan.getBranch()));
            when(performedRepo.findByPlanItem_PlanItemIdAndStatusNot(100L, PerformedServiceStatus.CANCELLED))
                    .thenReturn(List.of(ps));
            when(invoiceItemRepo.existsByPerformedIdAndInvoiceNotCancelled(5L)).thenReturn(false);

            service.updateStageStatus(10L, "PENDING");
            assertEquals(TreatmentPlanItemStatus.PLANNED, item.getStatus());
            assertEquals(TreatmentPlanStageStatus.PENDING, stage.getStatus());
            assertEquals(TreatmentPlanStatus.ACCEPTED, plan.getStatus());
            assertEquals(PerformedServiceStatus.CANCELLED, ps.getStatus());
            verify(stageRepo, atLeastOnce()).save(stage);
        }

        @Test @DisplayName("UTCID04 (A) - Chặn hoàn tác về PENDING khi bước đã lập hóa đơn viện phí")
        void utc04_resetPendingBlockedWhenInvoiced() {
            PerformedService ps = new PerformedService();
            ps.setPerformedId(5L);
            ps.setVisit(visit(1L, VisitStatus.IN_EXAM, plan.getPatientProfile(), null, plan.getBranch()));
            when(performedRepo.findByPlanItem_PlanItemIdAndStatusNot(100L, PerformedServiceStatus.CANCELLED))
                    .thenReturn(List.of(ps));
            when(invoiceItemRepo.existsByPerformedIdAndInvoiceNotCancelled(5L)).thenReturn(true);

            assertThrows(IllegalStateException.class, () -> service.updateStageStatus(10L, "PENDING"));
            verify(stageRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID05 (A) - Chặn hoàn tác về PENDING khi EMR đã khóa bất biến")
        void utc05_resetPendingBlockedWhenEmrLocked() {
            PerformedService ps = new PerformedService();
            ps.setPerformedId(5L);
            ps.setVisit(visit(1L, VisitStatus.IN_EXAM, plan.getPatientProfile(), null, plan.getBranch()));
            EmrRecord rec = new EmrRecord();
            rec.setStatus(EmrRecordStatus.SIGNED);
            when(performedRepo.findByPlanItem_PlanItemIdAndStatusNot(100L, PerformedServiceStatus.CANCELLED))
                    .thenReturn(List.of(ps));
            when(emrRecordRepo.findByVisit_VisitId(1L)).thenReturn(Optional.of(rec));

            assertThrows(IllegalStateException.class, () -> service.updateStageStatus(10L, "PENDING"));
            verify(stageRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID06 (A) - Từ chối khi stageId không tồn tại")
        void utc06_stageNotFoundThrows() {
            when(stageRepo.findById(999L)).thenReturn(Optional.empty());
            assertThrows(IllegalArgumentException.class, () -> service.updateStageStatus(999L, "COMPLETED"));
            verify(stageRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID07 (A) - Từ chối khi statusStr không hợp lệ")
        void utc07_invalidStatusThrows() {
            assertThrows(IllegalArgumentException.class, () -> service.updateStageStatus(10L, "UNKNOWN"));
            verify(stageRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID08 (B) - Chuỗi status chữ thường 'completed' vẫn parse đúng")
        void utc08_lowercaseStatusAccepted() {
            service.updateStageStatus(10L, "completed");
            assertEquals(TreatmentPlanStageStatus.COMPLETED, stage.getStatus());
        }

        @Test @DisplayName("UTCID09 (B) - Stage không có items vẫn chuyển PENDING an toàn")
        void utc09_emptyItemsStageResetSafely() {
            stage.setItems(null);
            assertDoesNotThrow(() -> service.updateStageStatus(10L, "PENDING"));
            assertEquals(TreatmentPlanStageStatus.PENDING, stage.getStatus());
        }

        @Test @DisplayName("UTCID10 (B) - Stage không gắn plan trả về null không gây NullPointerException")
        void utc10_detachedStageReturnsNull() {
            stage.setPlan(null);
            assertNull(service.updateStageStatus(10L, "COMPLETED"));
        }
    }

    // =========================================================================
    // 7. RecordInstallment (10 cases)
    // =========================================================================
    @Nested
    @DisplayName("RecordInstallment")
    class RecordInstallment {
        @Mock TreatmentPlanRepository planRepo;
        @Mock TreatmentPlanStageRepository stageRepo;
        @Mock TreatmentPlanItemRepository itemRepo;
        @Mock PatientProfileRepository patientRepo;
        @Mock BranchRepository branchRepo;
        @Mock StaffProfileRepository staffRepo;
        @Mock ServiceRepository serviceRepo;
        @Mock PerformedServiceRepository performedRepo;
        @Mock InvoiceItemRepository invoiceItemRepo;
        @Mock TreatmentPlanInstallmentRepository installmentRepo;
        @Mock PaymentRepository paymentRepo;
        @Mock UserRepository userRepo;
        @Mock StaffBranchAssignmentRepository staffBranchRepo;
        @Mock LoyaltyService loyaltyService;
        @Mock BranchBankAccountRepository bankRepo;

        TreatmentPlanServiceImpl service;
        Branch b;
        PatientProfile p;
        TreatmentPlan plan;
        TreatmentPlanInstallment inst;
        User cashier;

        @BeforeEach
        void setUp() {
            service = new TreatmentPlanServiceImpl(
                    planRepo, stageRepo, itemRepo, patientRepo, branchRepo,
                    staffRepo, serviceRepo, performedRepo, invoiceItemRepo, installmentRepo);
            ReflectionTestUtils.setField(service, "paymentRepository", paymentRepo);
            ReflectionTestUtils.setField(service, "userRepository", userRepo);
            ReflectionTestUtils.setField(service, "staffBranchAssignmentRepository", staffBranchRepo);
            ReflectionTestUtils.setField(service, "loyaltyService", loyaltyService);
            ReflectionTestUtils.setField(service, "branchBankAccountRepository", bankRepo);

            b = branch(1L);
            p = patient(1L, b);
            plan = new TreatmentPlan();
            plan.setPlanId(1L);
            plan.setPlanCode("KHDT01");
            plan.setPatientProfile(p);
            plan.setBranch(b);
            plan.setStatus(TreatmentPlanStatus.IN_PROGRESS);
            plan.setFinalEstimatedCost(new BigDecimal("5000000"));
            plan.setTotalPaidAmount(BigDecimal.ZERO);

            inst = new TreatmentPlanInstallment();
            inst.setInstallmentId(11L);
            inst.setPlan(plan);
            inst.setInstallmentOrder(1);
            inst.setExpectedAmount(new BigDecimal("2000000"));
            inst.setPaidAmount(BigDecimal.ZERO);
            inst.setStatus(TreatmentPlanInstallmentStatus.PENDING);
            plan.setInstallments(new ArrayList<>(List.of(inst)));

            cashier = new User();
            cashier.setUserId(50L);
            cashier.setUsername("ketoan");
            cashier.setIsActive(true);
            cashier.setStatus("ACTIVE");
            StaffProfile cashierStaff = doctor(50L, "Thu ngân A", b);

            lenient().when(planRepo.findByIdForUpdate(1L)).thenReturn(Optional.of(plan));
            lenient().when(planRepo.findById(1L)).thenReturn(Optional.of(plan));
            lenient().when(userRepo.findById(50L)).thenReturn(Optional.of(cashier));
            lenient().when(userRepo.hasActivePermission(50L, "PAYMENT_COLLECT")).thenReturn(true);
            lenient().when(staffRepo.findByUser_UserId(50L)).thenReturn(Optional.of(cashierStaff));
            lenient().when(staffBranchRepo.hasActiveAssignment(50L, 1L, AssignmentStatus.ACTIVE)).thenReturn(true);
            lenient().when(installmentRepo.findByPlan_PlanIdOrderByInstallmentOrderAsc(1L)).thenReturn(List.of(inst));
            lenient().when(planRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            lenient().when(paymentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        private InstallmentPaymentRequestDTO req(BigDecimal amt, PaymentMethod method) {
            InstallmentPaymentRequestDTO dto = new InstallmentPaymentRequestDTO();
            dto.setPlanId(1L);
            dto.setAmount(amt);
            dto.setPaymentMethod(method);
            return dto;
        }

        @Test @DisplayName("UTCID01 (N) - Thu tiền mặt đúng đợt tạo Payment và cộng điểm Loyalty")
        void utc01_cashPaymentCreatesLedgerAndLoyalty() {
            TreatmentPlanResponseDTO res = service.recordInstallmentPayment(1L, req(new BigDecimal("2000000"), PaymentMethod.CASH), 50L);
            assertEquals(0, new BigDecimal("2000000").compareTo(res.getTotalPaidAmount()));
            verify(paymentRepo).save(argThat(pay -> pay.getInvoice() == null && pay.getPaymentMethod() == PaymentMethod.CASH));
            verify(loyaltyService).recordPaymentPoints(any(Payment.class));
        }

        @Test @DisplayName("UTCID02 (N) - Thanh toán bằng ví DEPOSIT_WALLET trừ số dư và không cộng Loyalty")
        void utc02_depositWalletDeductsAndSkipsLoyalty() {
            when(patientRepo.findByIdForUpdate(1L)).thenReturn(Optional.of(p));
            TreatmentPlanResponseDTO res = service.recordInstallmentPayment(1L, req(new BigDecimal("500000"), PaymentMethod.DEPOSIT_WALLET), 50L);
            assertEquals(0, new BigDecimal("500000").compareTo(p.getDepositBalance()));
            assertEquals(0, new BigDecimal("500000").compareTo(res.getTotalPaidAmount()));
            verify(patientRepo).save(p);
            verify(paymentRepo).save(argThat(pay -> pay.getInvoice() == null && pay.getPaymentMethod() == PaymentMethod.DEPOSIT_WALLET));
            verify(loyaltyService, never()).recordPaymentPoints(any());
        }

        @Test @DisplayName("UTCID03 (N) - Chống ghi nhận trùng theo idempotencyKey")
        void utc03_idempotencyKeyDeduplicates() {
            InstallmentPaymentRequestDTO dto = req(new BigDecimal("500000"), PaymentMethod.CASH);
            dto.setIdempotencyKey("KEY-123");
            Payment existing = new Payment();
            existing.setPaymentType(PaymentType.PAYMENT);
            existing.setPaymentMethod(PaymentMethod.CASH);
            existing.setAmount(new BigDecimal("500000"));
            existing.setPatientProfile(p);
            existing.setBranch(b);
            existing.setNotes("[KHDT: KHDT01]");
            when(paymentRepo.findByIdempotencyKey("KEY-123")).thenReturn(Optional.of(existing));
            TreatmentPlanResponseDTO res = service.recordInstallmentPayment(1L, dto, 50L);
            assertNotNull(res);
            assertEquals("KHDT01", res.getPlanCode());
            assertEquals(0, BigDecimal.ZERO.compareTo(res.getTotalPaidAmount()));
            verify(paymentRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID04 (A) - Từ chối khi số tiền <= 0")
        void utc04_nonPositiveAmountThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.recordInstallmentPayment(1L, req(BigDecimal.ZERO, PaymentMethod.CASH), 50L));
            verify(paymentRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID05 (A) - Từ chối khi số dư ví DEPOSIT_WALLET không đủ")
        void utc05_insufficientWalletThrows() {
            when(patientRepo.findByIdForUpdate(1L)).thenReturn(Optional.of(p));
            assertThrows(IllegalStateException.class,
                    () -> service.recordInstallmentPayment(1L, req(new BigDecimal("2000000"), PaymentMethod.DEPOSIT_WALLET), 50L));
            verify(paymentRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID06 (A) - Từ chối khi kế hoạch đã CANCELLED")
        void utc06_cancelledPlanThrows() {
            plan.setStatus(TreatmentPlanStatus.CANCELLED);
            assertThrows(IllegalStateException.class,
                    () -> service.recordInstallmentPayment(1L, req(new BigDecimal("500000"), PaymentMethod.CASH), 50L));
            verify(paymentRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID07 (A) - Từ chối khi không tìm thấy kế hoạch")
        void utc07_planNotFoundThrows() {
            when(planRepo.findByIdForUpdate(999L)).thenReturn(Optional.empty());
            assertThrows(IllegalArgumentException.class,
                    () -> service.recordInstallmentPayment(999L, req(new BigDecimal("500000"), PaymentMethod.CASH), 50L));
            verify(paymentRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID08 (B) - Thu vượt tổng giá trị kế hoạch bị chặn")
        void utc08_overpaymentRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.recordInstallmentPayment(1L, req(new BigDecimal("5000001"), PaymentMethod.CASH), 50L));
            verify(paymentRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID09 (B) - Thu vừa khít phần còn lại của đợt chuyển trạng thái PAID")
        void utc09_exactInstallmentMarksPaid() {
            TreatmentPlanResponseDTO res = service.recordInstallmentPayment(1L, req(new BigDecimal("2000000"), PaymentMethod.CASH), 50L);
            assertEquals(TreatmentPlanInstallmentStatus.PAID, inst.getStatus());
            assertEquals(0, new BigDecimal("2000000").compareTo(res.getTotalPaidAmount()));
        }

        @Test @DisplayName("UTCID10 (B) - Thu một phần đợt chuyển trạng thái PARTIAL")
        void utc10_partialInstallmentMarksPartial() {
            TreatmentPlanResponseDTO res = service.recordInstallmentPayment(1L, req(new BigDecimal("1000000"), PaymentMethod.CASH), 50L);
            assertEquals(TreatmentPlanInstallmentStatus.PARTIAL, inst.getStatus());
            assertEquals(0, new BigDecimal("1000000").compareTo(res.getTotalPaidAmount()));
            verify(paymentRepo).save(any(Payment.class));
        }
    }

    // =========================================================================
    // 8. CreatePrescription (10 cases)
    // =========================================================================
    @Nested
    @DisplayName("CreatePrescription")
    class CreatePrescription {
        @Mock PrescriptionRepository prescriptionRepo;
        @Mock MedicationRepository medRepo;
        @Mock VisitRepository visitRepo;
        @Mock StaffProfileRepository staffRepo;
        @Mock PatientProfileRepository patientRepo;
        @Mock EmrRecordRepository emrRecordRepo;

        PrescriptionServiceImpl service;
        Branch b;
        PatientProfile p;
        StaffProfile d;
        Visit v;
        Medication med;

        @BeforeEach
        void setUp() {
            service = new PrescriptionServiceImpl(
                    prescriptionRepo, medRepo, visitRepo, staffRepo, patientRepo);
            ReflectionTestUtils.setField(service, "emrRecordRepository", emrRecordRepo);
            b = branch(1L);
            p = patient(1L, b);
            d = doctor(10L, "BS. Sơn", b);
            v = visit(100L, VisitStatus.IN_EXAM, p, d, b);
            med = new Medication();
            med.setMedicationId(7L);
            med.setDrugName("Amoxicillin 500mg");
            med.setIsActive(true);
            med.setSalePrice(new BigDecimal("5000"));
            med.setUnit("Viên");

            lenient().when(visitRepo.findById(100L)).thenReturn(Optional.of(v));
            lenient().when(staffRepo.findById(10L)).thenReturn(Optional.of(d));
            lenient().when(medRepo.findById(7L)).thenReturn(Optional.of(med));
            lenient().when(prescriptionRepo.save(any())).thenAnswer(inv -> {
                Prescription pr = inv.getArgument(0);
                pr.setPrescriptionId(1L);
                return pr;
            });
        }

        private PrescriptionRequestDTO validReq() {
            PrescriptionRequestDTO dto = new PrescriptionRequestDTO();
            dto.setVisitId(100L);
            dto.setDoctorId(10L);
            dto.setDiagnosisText("Viêm nướu");
            PrescriptionItemRequestDTO it = new PrescriptionItemRequestDTO();
            it.setMedicationId(7L);
            it.setDrugName("Amoxicillin 500mg");
            it.setQuantity(new BigDecimal("10"));
            it.setDosage("2 viên/ngày");
            it.setDurationDays(5);
            dto.setItems(List.of(it));
            return dto;
        }

        @Test @DisplayName("UTCID01 (N) - Kê đơn thuốc danh mục thành công, lấy đúng salePrice từ Medication")
        void utc01_validPrescriptionUsesMedicationSalePrice() {
            PrescriptionResponseDTO res = service.createPrescription(validReq());
            assertNotNull(res);
            verify(prescriptionRepo).save(argThat(pr ->
                    new BigDecimal("5000").compareTo(pr.getItems().get(0).getUnitPrice()) == 0));
        }

        @Test @DisplayName("UTCID02 (N) - Tự tính followupDate theo followupDays")
        void utc02_calculatesFollowupDateFromDays() {
            PrescriptionRequestDTO dto = validReq();
            dto.setFollowupDays(7);
            service.createPrescription(dto);
            verify(prescriptionRepo).save(argThat(pr ->
                    LocalDate.now().plusDays(7).equals(pr.getFollowupDate())));
        }

        @Test @DisplayName("UTCID03 (N) - Tự lấy bác sĩ phụ trách của Visit khi doctorId null")
        void utc03_defaultsToVisitPrimaryDoctor() {
            PrescriptionRequestDTO dto = validReq();
            dto.setDoctorId(null);
            PrescriptionResponseDTO res = service.createPrescription(dto);
            assertNotNull(res);
            verify(prescriptionRepo).save(argThat(pr -> d.equals(pr.getDoctor())));
        }

        @Test @DisplayName("UTCID04 (A) - Chặn kê thuốc đã ngừng sử dụng (isActive = false)")
        void utc04_inactiveMedicationRejected() {
            med.setIsActive(false);
            assertThrows(IllegalStateException.class, () -> service.createPrescription(validReq()));
            verify(prescriptionRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID05 (A) - Từ chối khi danh sách thuốc rỗng")
        void utc05_emptyItemsThrows() {
            PrescriptionRequestDTO dto = validReq();
            dto.setItems(List.of());
            assertThrows(IllegalArgumentException.class, () -> service.createPrescription(dto));
            verify(prescriptionRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID06 (A) - Từ chối khi số lượng thuốc <= 0")
        void utc06_nonPositiveQuantityThrows() {
            PrescriptionRequestDTO dto = validReq();
            dto.getItems().get(0).setQuantity(BigDecimal.ZERO);
            assertThrows(IllegalArgumentException.class, () -> service.createPrescription(dto));
            verify(prescriptionRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID07 (A) - Chặn kê đơn khi EMR đã khóa/ký xác thực")
        void utc07_lockedEmrBlocked() {
            EmrRecord rec = new EmrRecord();
            rec.setStatus(EmrRecordStatus.DOCTOR_SIGNATURE_PENDING);
            when(emrRecordRepo.findByVisit_VisitId(100L)).thenReturn(Optional.of(rec));
            assertThrows(IllegalStateException.class, () -> service.createPrescription(validReq()));
            verify(prescriptionRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID08 (A) - Chặn kê đơn khi bệnh nhân có cảnh báo dị ứng nhưng chưa xác nhận")
        void utc08_allergySafetyBlocksWithoutConfirmation() {
            p.setHasAllergies(true);
            p.setAllergyDetails("Dị ứng Amoxicillin");
            assertThrows(IllegalArgumentException.class, () -> service.createPrescription(validReq()));
            verify(prescriptionRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID09 (B) - Xác nhận confirmAllergyWarning=true cho phép kê đơn dù có ghi chú dị ứng")
        void utc09_allergyConfirmedAllowsPrescription() {
            p.setHasAllergies(true);
            p.setAllergyDetails("Dị ứng Amoxicillin");
            PrescriptionRequestDTO dto = validReq();
            dto.setConfirmAllergyWarning(true);
            assertNotNull(service.createPrescription(dto));
            verify(prescriptionRepo).save(any(Prescription.class));
        }

        @Test @DisplayName("UTCID10 (B) - quantity null tự chuẩn hóa về 1 viên")
        void utc10_nullQuantityDefaultsToOne() {
            PrescriptionRequestDTO dto = validReq();
            dto.getItems().get(0).setQuantity(null);
            service.createPrescription(dto);
            verify(prescriptionRepo).save(argThat(pr ->
                    BigDecimal.ONE.compareTo(pr.getItems().get(0).getQuantity()) == 0));
        }
    }

    // =========================================================================
    // 9. SaveWorkflowTemplate (10 cases)
    // =========================================================================
    @Nested
    @DisplayName("SaveWorkflowTemplate")
    class SaveWorkflowTemplate {
        @Mock ClinicalWorkflowTemplateRepository templateRepo;
        @Mock ClinicalWorkflowVersionRepository versionRepo;
        @Mock ClinicalWorkflowStepRepository stepRepo;
        @Mock WorkflowExecutionRepository executionRepo;
        @Mock WorkflowStepExecutionRepository stepExecRepo;
        @Mock ServiceRepository serviceRepo;
        @Mock BranchRepository branchRepo;
        @Mock VisitRepository visitRepo;
        @Mock StaffProfileRepository staffRepo;
        @Mock GeminiAiService geminiAiService;
        @Mock UserRepository userRepo;
        @Mock PerformedServiceRepository performedRepo;
        @Mock ParaclinicalOrderItemRepository paraclinicalRepo;
        @Mock SystemAuditLogRepository auditRepo;
        @Mock SystemAuditLogService auditService;

        ClinicalWorkflowServiceImpl service;

        @BeforeEach
        void setUp() {
            service = new ClinicalWorkflowServiceImpl(
                    templateRepo, versionRepo, stepRepo, executionRepo, stepExecRepo,
                    serviceRepo, branchRepo, visitRepo, staffRepo, geminiAiService,
                    userRepo, performedRepo, paraclinicalRepo, auditRepo, auditService);
            lenient().when(templateRepo.save(any())).thenAnswer(inv -> {
                ClinicalWorkflowTemplate t = inv.getArgument(0);
                if (t.getTemplateId() == null) t.setTemplateId(1L);
                return t;
            });
            lenient().when(versionRepo.save(any())).thenAnswer(inv -> {
                ClinicalWorkflowVersion v = inv.getArgument(0);
                if (v.getVersionId() == null) v.setVersionId(10L);
                return v;
            });
        }

        private WorkflowTemplateRequestDTO validDto() {
            WorkflowTemplateRequestDTO dto = new WorkflowTemplateRequestDTO();
            dto.setTemplateCode("qt01");
            dto.setTemplateName("Quy trình nhổ răng 8");
            dto.setStatus("ACTIVE");
            WorkflowStepDTO step = new WorkflowStepDTO();
            step.setStepName("Gây tê");
            step.setSequenceOrder(1);
            dto.setSteps(List.of(step));
            return dto;
        }

        @Test @DisplayName("UTCID01 (N) - Tạo mới template sinh version 1 PUBLISHED và lưu các bước")
        void utc01_createNewTemplatePublishesV1() {
            when(versionRepo.findFirstByTemplate_TemplateIdOrderByVersionNumberDesc(1L)).thenReturn(Optional.empty());
            WorkflowTemplateResponseDTO res = service.saveTemplate(validDto(), 99L);
            assertNotNull(res);
            verify(versionRepo).save(argThat(ver -> ver.getVersionNumber() == 1 && ver.getStatus() == WorkflowVersionStatus.PUBLISHED));
            verify(stepRepo, times(1)).save(any(ClinicalWorkflowStep.class));
        }

        @Test @DisplayName("UTCID02 (N) - Cập nhật template đang PUBLISHED tự RETIRE bản cũ và tạo v2")
        void utc02_updatePublishedCreatesV2AndRetiresV1() {
            ClinicalWorkflowTemplate existing = new ClinicalWorkflowTemplate();
            existing.setTemplateId(1L);
            ClinicalWorkflowVersion v1 = new ClinicalWorkflowVersion();
            v1.setVersionId(10L);
            v1.setVersionNumber(1);
            v1.setStatus(WorkflowVersionStatus.PUBLISHED);

            when(templateRepo.findById(1L)).thenReturn(Optional.of(existing));
            when(versionRepo.findFirstByTemplate_TemplateIdOrderByVersionNumberDesc(1L)).thenReturn(Optional.of(v1));

            WorkflowTemplateRequestDTO dto = validDto();
            dto.setTemplateId(1L);
            service.saveTemplate(dto, 99L);

            assertEquals(WorkflowVersionStatus.RETIRED, v1.getStatus());
            verify(versionRepo).save(argThat(ver -> ver.getVersionNumber() == 2 && ver.getStatus() == WorkflowVersionStatus.PUBLISHED));
            verify(stepRepo, never()).deleteByVersion_VersionId(10L);
        }

        @Test @DisplayName("UTCID03 (N) - Gắn Service vào template khi có serviceId")
        void utc03_linksServiceWhenProvided() {
            com.cvdcms.entity.Service svc = activeService(5L, new BigDecimal("500000"));
            when(serviceRepo.findById(5L)).thenReturn(Optional.of(svc));
            when(versionRepo.findFirstByTemplate_TemplateIdOrderByVersionNumberDesc(1L)).thenReturn(Optional.empty());
            WorkflowTemplateRequestDTO dto = validDto();
            dto.setServiceId(5L);
            service.saveTemplate(dto, 99L);
            verify(templateRepo).save(argThat(t -> svc.equals(t.getService())));
        }

        @Test @DisplayName("UTCID04 (N) - Cập nhật template đang DRAFT ghi đè bước ngay trên bản DRAFT")
        void utc04_updateDraftReplacesStepsInPlace() {
            ClinicalWorkflowTemplate existing = new ClinicalWorkflowTemplate();
            existing.setTemplateId(1L);
            ClinicalWorkflowVersion draft = new ClinicalWorkflowVersion();
            draft.setVersionId(10L);
            draft.setVersionNumber(1);
            draft.setStatus(WorkflowVersionStatus.DRAFT);

            when(templateRepo.findById(1L)).thenReturn(Optional.of(existing));
            when(versionRepo.findFirstByTemplate_TemplateIdOrderByVersionNumberDesc(1L)).thenReturn(Optional.of(draft));

            WorkflowTemplateRequestDTO dto = validDto();
            dto.setTemplateId(1L);
            service.saveTemplate(dto, 99L);
            verify(stepRepo).deleteByVersion_VersionId(10L);
        }

        @Test @DisplayName("UTCID05 (A) - Từ chối khi templateId không tồn tại")
        void utc05_templateNotFoundThrows() {
            when(templateRepo.findById(999L)).thenReturn(Optional.empty());
            WorkflowTemplateRequestDTO dto = validDto();
            dto.setTemplateId(999L);
            assertThrows(IllegalArgumentException.class, () -> service.saveTemplate(dto, 99L));
            verify(versionRepo, never()).save(any());
        }

        @Test @DisplayName("UTCID06 (B) - templateCode rỗng tự sinh mã QTKT-...")
        void utc06_blankCodeAutoGenerated() {
            when(versionRepo.findFirstByTemplate_TemplateIdOrderByVersionNumberDesc(1L)).thenReturn(Optional.empty());
            WorkflowTemplateRequestDTO dto = validDto();
            dto.setTemplateCode("  ");
            service.saveTemplate(dto, 99L);
            verify(templateRepo).save(argThat(t -> t.getTemplateCode().startsWith("QTKT-")));
        }

        @Test @DisplayName("UTCID07 (B) - templateCode chữ thường tự chuẩn hóa viết hoa")
        void utc07_lowercaseCodeUppercased() {
            when(versionRepo.findFirstByTemplate_TemplateIdOrderByVersionNumberDesc(1L)).thenReturn(Optional.empty());
            service.saveTemplate(validDto(), 99L);
            verify(templateRepo).save(argThat(t -> "QT01".equals(t.getTemplateCode())));
        }

        @Test @DisplayName("UTCID08 (B) - status không hợp lệ tự fallback về ACTIVE")
        void utc08_invalidStatusFallsBackToActive() {
            when(versionRepo.findFirstByTemplate_TemplateIdOrderByVersionNumberDesc(1L)).thenReturn(Optional.empty());
            WorkflowTemplateRequestDTO dto = validDto();
            dto.setStatus("BOGUS");
            service.saveTemplate(dto, 99L);
            verify(templateRepo).save(argThat(t -> t.getStatus() == WorkflowTemplateStatus.ACTIVE));
        }

        @Test @DisplayName("UTCID09 (B) - stepType không hợp lệ tự fallback về CLINICAL")
        void utc09_invalidStepTypeFallsBackToClinical() {
            when(versionRepo.findFirstByTemplate_TemplateIdOrderByVersionNumberDesc(1L)).thenReturn(Optional.empty());
            WorkflowTemplateRequestDTO dto = validDto();
            dto.getSteps().get(0).setStepType("UNKNOWN");
            service.saveTemplate(dto, 99L);
            verify(stepRepo).save(argThat(st -> st.getStepType() == StepType.CLINICAL));
        }

        @Test @DisplayName("UTCID10 (B) - steps null khi sửa template PUBLISHED không tạo version mới thừa")
        void utc10_nullStepsDoesNotRetirePublishedVersion() {
            ClinicalWorkflowTemplate existing = new ClinicalWorkflowTemplate();
            existing.setTemplateId(1L);
            ClinicalWorkflowVersion v1 = new ClinicalWorkflowVersion();
            v1.setVersionId(10L);
            v1.setStatus(WorkflowVersionStatus.PUBLISHED);
            when(templateRepo.findById(1L)).thenReturn(Optional.of(existing));
            when(versionRepo.findFirstByTemplate_TemplateIdOrderByVersionNumberDesc(1L)).thenReturn(Optional.of(v1));

            WorkflowTemplateRequestDTO dto = validDto();
            dto.setTemplateId(1L);
            dto.setSteps(null);
            service.saveTemplate(dto, 99L);
            assertEquals(WorkflowVersionStatus.PUBLISHED, v1.getStatus());
        }
    }

    // =========================================================================
    // 10. SignEmrRecord (10 cases)
    // =========================================================================
    @Nested
    @DisplayName("SignEmrRecord")
    class SignEmrRecord {
        @Mock EmrAccess access;
        @Mock EmrRecordRepository records;
        @Mock EmrRecordVersionRepository versions;
        @Mock EmrAuditEventRepository audits;
        @Mock VisitRepository visits;
        @Mock ClinicalExaminationRepository examinations;
        @Mock ExaminationDiagnosisRepository diagnoses;
        @Mock PerformedServiceRepository performedServices;
        @Mock PrescriptionRepository prescriptions;
        @Mock ClinicalPhotoRepository photos;
        @Mock NanopixXraySyncLogRepository xrays;
        @Mock LaboOrderRepository laboOrders;
        @Mock TreatmentPlanRepository treatmentPlans;
        @Mock EmrAttachmentRepository attachments;
        @Mock EmrAttachmentStorage attachmentStorage;
        @Mock StaffProfileRepository staffProfiles;
        @Mock PatientProfileRepository patientProfiles;
        @Mock DoctorCoverGrantRepository coverGrants;

        EmrModuleService service;
        HttpSession session;
        Branch b;
        PatientProfile p;
        StaffProfile primaryDoc;
        StaffProfile coverDoc;
        Visit v;
        EmrRecord rec;
        EmrRecordVersion ver;

        @BeforeEach
        void setUp() {
            service = new EmrModuleService(
                    access, records, versions, audits, visits, examinations,
                    diagnoses, performedServices, prescriptions, photos,
                    xrays, laboOrders, treatmentPlans, attachments, attachmentStorage,
                    staffProfiles, patientProfiles, new ObjectMapper());
            ReflectionTestUtils.setField(service, "coverGrants", coverGrants);

            session = new MockHttpSession();
            b = branch(1L);
            p = patient(1L, b);
            primaryDoc = doctor(10L, "BS. Chính", b);
            coverDoc = doctor(20L, "BS. Khám thay", b);
            v = visit(100L, VisitStatus.COMPLETED, p, primaryDoc, b);

            ver = new EmrRecordVersion();
            ver.setVersionId(1L);
            ver.setVersionNumber(1);
            ver.setStatus(EmrVersionStatus.LOCKED);

            rec = new EmrRecord();
            rec.setRecordId(50L);
            rec.setVisit(v);
            rec.setPatientProfile(p);
            rec.setBranch(b);
            rec.setStatus(EmrRecordStatus.DOCTOR_SIGNATURE_PENDING);
            rec.setCurrentVersionNumber(1);
            ver.setRecord(rec);

            lenient().when(records.findDetailsById(50L)).thenReturn(Optional.of(rec));
            lenient().when(versions.findByRecord_RecordIdAndVersionNumber(50L, 1)).thenReturn(Optional.of(ver));
            lenient().when(versions.findAllDetailsByRecordId(50L)).thenReturn(List.of(ver));
        }

        private void mockActor(StaffProfile doc, String roleName) {
            Role r = new Role();
            r.setRoleName(roleName);
            r.setIsActive(true);
            doc.getUser().setRoles(Set.of(r));
            EmrAccess.Scope scope = new EmrAccess.Scope(doc.getUser(), false, true, Set.of(b.getBranchId()));
            lenient().when(access.require(session)).thenReturn(scope);
            lenient().when(staffProfiles.findByUser_UserId(doc.getUser().getUserId())).thenReturn(Optional.of(doc));
        }

        @Test @DisplayName("UTCID01 (N) - Bác sĩ phụ trách có CCHN ký số thành công chuyển SIGNED")
        void utc01_primaryDoctorSignsSuccessfully() {
            mockActor(primaryDoc, "BAC_SI");
            service.doctorSignForTest(session, 50L, true);
            assertEquals(EmrRecordStatus.SIGNED, rec.getStatus());
            assertEquals(EmrVersionStatus.SIGNED, ver.getStatus());
            assertEquals(1, ver.getSignatures().size());
            verify(audits).save(argThat(a -> "DOCTOR_DIGITAL_SIGN".equals(a.getAction())));
        }

        @Test @DisplayName("UTCID02 (N) - Bác sĩ được ủy quyền DoctorCoverGrant có CCHN ký số thành công")
        void utc02_authorizedCoverDoctorSignsSuccessfully() {
            mockActor(coverDoc, "BAC_SI");
            when(coverGrants.existsByVisitVisitIdAndCoveringDoctorStaffIdAndRevokedAtIsNullAndExpiresAtAfter(
                    eq(100L), eq(20L), any(LocalDateTime.class))).thenReturn(true);
            service.doctorSignForTest(session, 50L, true);
            assertEquals(EmrRecordStatus.SIGNED, rec.getStatus());
            assertEquals(EmrVersionStatus.SIGNED, ver.getStatus());
        }

        @Test @DisplayName("UTCID03 (N) - Ký bản sửa đổi v2 tự động đánh dấu v1 cũ thành SUPERSEDED")
        void utc03_signingV2SupersedesV1() {
            mockActor(primaryDoc, "BAC_SI");
            EmrRecordVersion v1 = new EmrRecordVersion();
            v1.setVersionNumber(1);
            v1.setStatus(EmrVersionStatus.SIGNED);
            ver.setVersionNumber(2);
            rec.setCurrentVersionNumber(2);
            when(versions.findByRecord_RecordIdAndVersionNumber(50L, 2)).thenReturn(Optional.of(ver));
            when(versions.findAllDetailsByRecordId(50L)).thenReturn(List.of(v1, ver));

            service.doctorSignForTest(session, 50L, true);
            assertEquals(EmrVersionStatus.SUPERSEDED, v1.getStatus());
            assertEquals(EmrVersionStatus.SIGNED, ver.getStatus());
            assertEquals(EmrRecordStatus.SIGNED, rec.getStatus());
        }

        @Test @DisplayName("UTCID04 (A) - Từ chối khi confirmed = false (chưa tích xác nhận)")
        void utc04_unconfirmedThrows400() {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.doctorSignForTest(session, 50L, false));
            assertEquals(400, ex.getStatusCode().value());
            assertEquals(EmrRecordStatus.DOCTOR_SIGNATURE_PENDING, rec.getStatus());
        }

        @Test @DisplayName("UTCID05 (A) - Từ chối khi bệnh án chưa ở bước chờ bác sĩ ký (DRAFT)")
        void utc05_draftRecordCannotBeSigned() {
            mockActor(primaryDoc, "BAC_SI");
            rec.setStatus(EmrRecordStatus.DRAFT);
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.doctorSignForTest(session, 50L, true));
            assertEquals(409, ex.getStatusCode().value());
            assertEquals(EmrRecordStatus.DRAFT, rec.getStatus());
        }

        @Test @DisplayName("UTCID06 (A) - Chặn bác sĩ khác không có ủy quyền ký thay hồ sơ")
        void utc06_unauthorizedDoctorForbidden() {
            mockActor(coverDoc, "BAC_SI");
            when(coverGrants.existsByVisitVisitIdAndCoveringDoctorStaffIdAndRevokedAtIsNullAndExpiresAtAfter(
                    eq(100L), eq(20L), any(LocalDateTime.class))).thenReturn(false);
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.doctorSignForTest(session, 50L, true));
            assertEquals(403, ex.getStatusCode().value());
            assertEquals(EmrRecordStatus.DOCTOR_SIGNATURE_PENDING, rec.getStatus());
        }

        @Test @DisplayName("UTCID07 (A) - Chặn ký khi bác sĩ chưa có số giấy phép hành nghề (CCHN)")
        void utc07_missingLicenseRejected() {
            primaryDoc.setPracticeLicenseNo("   ");
            mockActor(primaryDoc, "BAC_SI");
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.doctorSignForTest(session, 50L, true));
            assertEquals(409, ex.getStatusCode().value());
            assertEquals(EmrRecordStatus.DOCTOR_SIGNATURE_PENDING, rec.getStatus());
        }

        @Test @DisplayName("UTCID08 (A) - Chặn thao tác trên hồ sơ nháp đã xóa mềm")
        void utc08_softDeletedRecordRejected() {
            mockActor(primaryDoc, "BAC_SI");
            EmrSourceSnapshot snap = new EmrSourceSnapshot();
            snap.setIsDeleted(true);
            ver.setSnapshot(snap);
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.doctorSignForTest(session, 50L, true));
            assertEquals(404, ex.getStatusCode().value());
        }

        @Test @DisplayName("UTCID09 (B) - Hồ sơ đã SIGNED không cho ký đè lại")
        void utc09_alreadySignedRejected() {
            mockActor(primaryDoc, "BAC_SI");
            rec.setStatus(EmrRecordStatus.SIGNED);
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.doctorSignForTest(session, 50L, true));
            assertEquals(409, ex.getStatusCode().value());
        }

        @Test @DisplayName("UTCID10 (B) - Ca khám chưa gắn primaryDoctor kiểm soát ủy quyền an toàn, chỉ cho phép bác sĩ có DoctorCoverGrant ký")
        void utc10_nullPrimaryDoctorRequiresCoverGrant() {
            v.setPrimaryDoctor(null);
            mockActor(coverDoc, "BAC_SI");
            when(coverGrants.existsByVisitVisitIdAndCoveringDoctorStaffIdAndRevokedAtIsNullAndExpiresAtAfter(
                    eq(100L), eq(20L), any(LocalDateTime.class))).thenReturn(false);
            ResponseStatusException forbidden = assertThrows(ResponseStatusException.class,
                    () -> service.doctorSignForTest(session, 50L, true));
            assertEquals(403, forbidden.getStatusCode().value());

            when(coverGrants.existsByVisitVisitIdAndCoveringDoctorStaffIdAndRevokedAtIsNullAndExpiresAtAfter(
                    eq(100L), eq(20L), any(LocalDateTime.class))).thenReturn(true);
            service.doctorSignForTest(session, 50L, true);
            assertEquals(EmrRecordStatus.SIGNED, rec.getStatus());
            assertEquals(EmrVersionStatus.SIGNED, ver.getStatus());
        }
    }
}
