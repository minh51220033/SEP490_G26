package com.cvdcms;

import com.cvdcms.DTO.WorkflowExecutionDTO;
import com.cvdcms.DTO.WorkflowHistoryEntryDTO;
import com.cvdcms.DTO.WorkflowVisitOverviewDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.GeminiAiService;
import com.cvdcms.service.SystemAuditLogService;
import com.cvdcms.service.impl.ClinicalWorkflowServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClinicalWorkflowExecutionUnitTest {

    @Mock private ClinicalWorkflowTemplateRepository templateRepo;
    @Mock private ClinicalWorkflowVersionRepository versionRepo;
    @Mock private ClinicalWorkflowStepRepository stepRepo;
    @Mock private WorkflowExecutionRepository executionRepo;
    @Mock private WorkflowStepExecutionRepository stepExecutionRepo;
    @Mock private ServiceRepository serviceRepo;
    @Mock private BranchRepository branchRepo;
    @Mock private VisitRepository visitRepo;
    @Mock private StaffProfileRepository staffProfileRepo;
    @Mock private GeminiAiService geminiAiService;
    @Mock private UserRepository userRepo;
    @Mock private PerformedServiceRepository performedServiceRepo;
    @Mock private ParaclinicalOrderItemRepository paraclinicalItemRepo;
    @Mock private SystemAuditLogRepository auditRepo;
    @Mock private SystemAuditLogService auditService;

    private ClinicalWorkflowServiceImpl service;

    private Branch branch;
    private PatientProfile patient;
    private StaffProfile doctor;
    private User doctorUser;
    private Visit visit;
    private com.cvdcms.entity.Service dentalService;
    private ClinicalWorkflowTemplate template;
    private ClinicalWorkflowVersion version;
    private ClinicalWorkflowStep step1;
    private ClinicalWorkflowStep step2OptionalParaclinical;
    private ClinicalWorkflowStep step3Treatment;

    @BeforeEach
    void setUp() {
        service = new ClinicalWorkflowServiceImpl(
                templateRepo, versionRepo, stepRepo, executionRepo, stepExecutionRepo,
                serviceRepo, branchRepo, visitRepo, staffProfileRepo, geminiAiService,
                userRepo, performedServiceRepo, paraclinicalItemRepo, auditRepo, auditService
        );

        branch = new Branch();
        branch.setBranchId(1L);
        branch.setBranchName("Cơ sở Cầu Giấy");

        patient = new PatientProfile();
        patient.setProfileId(10L);
        patient.setPatientCode("BN0010");
        patient.setFullName("Nguyễn Văn Bệnh Nhân");

        doctorUser = new User();
        doctorUser.setUserId(100L);
        doctorUser.setUsername("bs.minh");

        doctor = new StaffProfile();
        doctor.setStaffId(50L);
        doctor.setFullName("BS. Nguyễn Minh");
        doctor.setUser(doctorUser);

        visit = new Visit();
        visit.setVisitId(500L);
        visit.setBranch(branch);
        visit.setPatientProfile(patient);
        visit.setPrimaryDoctor(doctor);
        visit.setStatus(VisitStatus.IN_EXAM);
        visit.setCheckInAt(LocalDateTime.now().minusMinutes(20));

        dentalService = new com.cvdcms.entity.Service();
        dentalService.setServiceId(200L);
        dentalService.setServiceCode("SRV-WISDOM");
        dentalService.setServiceName("Nhổ răng khôn hàm dưới");

        template = new ClinicalWorkflowTemplate();
        template.setTemplateId(300L);
        template.setTemplateCode("WF-WISDOM-01");
        template.setTemplateName("Quy trình nhổ răng khôn chuẩn Bộ Y Tế");
        template.setService(dentalService);
        template.setStatus(WorkflowTemplateStatus.ACTIVE);

        version = new ClinicalWorkflowVersion();
        version.setVersionId(400L);
        version.setTemplate(template);
        version.setVersionNumber(1);
        version.setStatus(WorkflowVersionStatus.PUBLISHED);
        version.setFlowMode(FlowMode.INTERRUPTIBLE);

        step1 = new ClinicalWorkflowStep();
        step1.setStepId(1001L);
        step1.setVersion(version);
        step1.setSequenceOrder(1);
        step1.setStepCode("S1");
        step1.setStepName("Khám lâm sàng & Đo huyết áp");
        step1.setStepType(StepType.CLINICAL);
        step1.setIsOptional(false);
        step1.setCompletionCriteria("Huyết áp ổn định dưới 140/90 mmHg");
        step1.setTargetQueueType(QueueType.EXAM);

        step2OptionalParaclinical = new ClinicalWorkflowStep();
        step2OptionalParaclinical.setStepId(1002L);
        step2OptionalParaclinical.setVersion(version);
        step2OptionalParaclinical.setSequenceOrder(2);
        step2OptionalParaclinical.setStepCode("S2");
        step2OptionalParaclinical.setStepName("Chụp X-Quang Panorama");
        step2OptionalParaclinical.setStepType(StepType.PARACLINICAL);
        step2OptionalParaclinical.setIsOptional(true);
        step2OptionalParaclinical.setTargetQueueType(QueueType.PARACLINICAL);

        step3Treatment = new ClinicalWorkflowStep();
        step3Treatment.setStepId(1003L);
        step3Treatment.setVersion(version);
        step3Treatment.setSequenceOrder(3);
        step3Treatment.setStepCode("S3");
        step3Treatment.setStepName("Gây tê & Tiểu phẫu nhổ răng khôn");
        step3Treatment.setStepType(StepType.TREATMENT);
        step3Treatment.setIsOptional(false);
        step3Treatment.setRequiresClinicalApproval(true);
        step3Treatment.setTargetQueueType(QueueType.TREATMENT);
    }

    private WorkflowExecution buildExecutionWithSteps(List<WorkflowStepExecution> stepExecs) {
        WorkflowExecution ex = new WorkflowExecution();
        ex.setExecutionId(900L);
        ex.setVersion(version);
        ex.setVisit(visit);
        ex.setPatientProfile(patient);
        ex.setBranch(branch);
        ex.setCurrentStep(step1);
        ex.setStatus(WorkflowExecutionStatus.IN_PROGRESS);
        ex.setStartedAt(LocalDateTime.now().minusMinutes(10));

        WorkflowStepExecution se1 = new WorkflowStepExecution();
        se1.setStepExecutionId(2001L);
        se1.setExecution(ex);
        se1.setStep(step1);
        se1.setStatus(WorkflowStepExecutionStatus.PENDING);

        WorkflowStepExecution se2 = new WorkflowStepExecution();
        se2.setStepExecutionId(2002L);
        se2.setExecution(ex);
        se2.setStep(step2OptionalParaclinical);
        se2.setStatus(WorkflowStepExecutionStatus.PENDING);

        WorkflowStepExecution se3 = new WorkflowStepExecution();
        se3.setStepExecutionId(2003L);
        se3.setExecution(ex);
        se3.setStep(step3Treatment);
        se3.setStatus(WorkflowStepExecutionStatus.PENDING);

        stepExecs.add(se1);
        stepExecs.add(se2);
        stepExecs.add(se3);
        return ex;
    }

    @Test
    @DisplayName("FT43-01: Khởi tạo quy trình mới cho lượt khám theo phiên bản mẫu đang áp dụng")
    void startExecution_createsExecutionAndSteps() {
        List<WorkflowStepExecution> savedSteps = new ArrayList<>();
        when(visitRepo.findById(500L)).thenReturn(Optional.of(visit));
        when(executionRepo.findFirstByVisit_VisitIdAndVersion_Template_Service_ServiceId(500L, 200L))
                .thenReturn(Optional.empty());
        when(templateRepo.findFirstByService_ServiceIdAndStatus(200L, WorkflowTemplateStatus.ACTIVE))
                .thenReturn(Optional.of(template));
        when(versionRepo.findFirstByTemplate_TemplateIdAndStatus(300L, WorkflowVersionStatus.PUBLISHED))
                .thenReturn(Optional.of(version));
        when(stepRepo.findByVersion_VersionIdOrderBySequenceOrderAsc(400L))
                .thenReturn(List.of(step1, step2OptionalParaclinical, step3Treatment));

        when(executionRepo.save(any(WorkflowExecution.class))).thenAnswer(inv -> {
            WorkflowExecution e = inv.getArgument(0);
            e.setExecutionId(900L);
            return e;
        });
        when(stepExecutionRepo.save(any(WorkflowStepExecution.class))).thenAnswer(inv -> {
            WorkflowStepExecution se = inv.getArgument(0);
            se.setStepExecutionId(2000L + savedSteps.size() + 1);
            savedSteps.add(se);
            return se;
        });
        when(stepExecutionRepo.findByExecution_ExecutionIdOrderByStep_SequenceOrderAsc(900L))
                .thenReturn(savedSteps);

        WorkflowExecutionDTO dto = service.startExecution(500L, 200L, 100L);

        assertNotNull(dto);
        assertEquals(900L, dto.getExecutionId());
        assertEquals("IN_PROGRESS", dto.getStatus());
        assertEquals(3, dto.getTotalSteps());
        assertEquals(0, dto.getCompletedSteps());
        assertEquals(1001L, dto.getCurrentStepId());
        assertEquals(List.of("START"), dto.getSteps().get(0).getAllowedActions());
        verify(auditService, times(1)).logAction(any(), eq(branch), eq("WORKFLOW_CREATE"),
                eq("workflow_executions"), eq(900L), any(), isNull(), any());
    }

    @Test
    @DisplayName("FT43-02: Chặn thao tác sai thứ tự bước và kiểm tra xác nhận điều kiện hoàn tất (SCR-43.2)")
    void performStepAction_enforcesOrderAndCompletionCriteria() {
        List<WorkflowStepExecution> stepExecs = new ArrayList<>();
        WorkflowExecution ex = buildExecutionWithSteps(stepExecs);

        when(executionRepo.findForUpdate(900L)).thenReturn(Optional.of(ex));
        when(stepExecutionRepo.findByExecution_ExecutionIdOrderByStep_SequenceOrderAsc(900L)).thenReturn(stepExecs);
        when(staffProfileRepo.findByUserUserId(100L)).thenReturn(Optional.of(doctor));

        // 1. Thử bắt đầu Bước 2 khi Bước 1 chưa hoàn tất -> phải báo lỗi
        IllegalStateException orderEx = assertThrows(IllegalStateException.class, () ->
                service.performStepAction(900L, 1002L, "START", null, false, 100L));
        assertTrue(orderEx.getMessage().contains("Phải thực hiện theo đúng thứ tự"));

        // 2. Bắt đầu Bước 1 hợp lệ
        service.performStepAction(900L, 1001L, "START", "Bắt đầu đo huyết áp", false, 100L);
        assertEquals(WorkflowStepExecutionStatus.IN_PROGRESS, stepExecs.get(0).getStatus());

        // 3. Hoàn tất Bước 1 nhưng chưa tích xác nhận điều kiện hoàn tất -> phải báo lỗi
        IllegalStateException critEx = assertThrows(IllegalStateException.class, () ->
                service.performStepAction(900L, 1001L, "COMPLETE", "120/80 mmHg", false, 100L));
        assertTrue(critEx.getMessage().contains("điều kiện hoàn tất"));

        // 4. Tích xác nhận điều kiện hoàn tất -> Bước 1 COMPLETED, chuyển sang Bước 2
        WorkflowExecutionDTO afterStep1 = service.performStepAction(
                900L, 1001L, "COMPLETE", "Huyết áp 120/80 mmHg", true, 100L);
        assertEquals(WorkflowStepExecutionStatus.COMPLETED, stepExecs.get(0).getStatus());
        assertEquals(1002L, afterStep1.getCurrentStepId());
    }

    @Test
    @DisplayName("FT43-03: Tạm dừng (INTERRUPTIBLE), Tiếp tục, Bỏ qua bước tùy chọn và hoàn tất toàn bộ quy trình (SCR-43.3)")
    void performStepAction_pauseResumeSkipAndCompleteWorkflow() {
        List<WorkflowStepExecution> stepExecs = new ArrayList<>();
        WorkflowExecution ex = buildExecutionWithSteps(stepExecs);
        stepExecs.get(0).setStatus(WorkflowStepExecutionStatus.COMPLETED); // Bước 1 đã xong

        when(executionRepo.findForUpdate(900L)).thenReturn(Optional.of(ex));
        when(stepExecutionRepo.findByExecution_ExecutionIdOrderByStep_SequenceOrderAsc(900L)).thenReturn(stepExecs);
        when(staffProfileRepo.findByUserUserId(100L)).thenReturn(Optional.of(doctor));
        when(userRepo.hasActiveAdminRole(100L)).thenReturn(false);
        when(userRepo.hasActivePermission(100L, "CLINICAL_DAILY_VISITS")).thenReturn(true);

        // Bước 2 là tùy chọn -> có thể SKIP ngay khi đang PENDING
        WorkflowExecutionDTO afterSkip = service.performStepAction(
                900L, 1002L, "SKIP", "Bệnh nhân đã có phim X-Quang mới chụp", false, 100L);
        assertEquals(WorkflowStepExecutionStatus.SKIPPED, stepExecs.get(1).getStatus());
        assertEquals(1003L, afterSkip.getCurrentStepId());

        // Bước 3 là bắt buộc -> thử SKIP phải bị chặn
        assertThrows(IllegalStateException.class, () ->
                service.performStepAction(900L, 1003L, "SKIP", "Thử bỏ qua bước bắt buộc", false, 100L));

        // Bắt đầu Bước 3 -> Tạm dừng (không nhập lý do phải báo lỗi, có lý do thì PAUSED)
        service.performStepAction(900L, 1003L, "START", null, false, 100L);
        assertThrows(IllegalArgumentException.class, () ->
                service.performStepAction(900L, 1003L, "PAUSE", "   ", false, 100L));

        WorkflowExecutionDTO paused = service.performStepAction(
                900L, 1003L, "PAUSE", "Chờ thuốc tê ngấm 10 phút", false, 100L);
        assertEquals("PAUSED", paused.getStatus());

        // Tiếp tục Bước 3 và Hoàn tất
        service.performStepAction(900L, 1003L, "RESUME", "Tiếp tục tiểu phẫu", false, 100L);
        WorkflowExecutionDTO completed = service.performStepAction(
                900L, 1003L, "COMPLETE", "Nhổ răng 48 an toàn, khâu 2 mũi", true, 100L);

        assertEquals("COMPLETED", completed.getStatus());
        assertEquals(100, completed.getProgressPercent());
        assertNull(completed.getCurrentStepId());
    }

    @Test
    @DisplayName("FT43-04: Bước cận lâm sàng chặn hoàn tất khi chỉ định chưa có kết quả & Khóa quy trình khi ca khám đã khóa (BR-EMR-02)")
    void performStepAction_checksParaclinicalAndVisitLock() {
        List<WorkflowStepExecution> stepExecs = new ArrayList<>();
        WorkflowExecution ex = buildExecutionWithSteps(stepExecs);
        stepExecs.get(0).setStatus(WorkflowStepExecutionStatus.COMPLETED);
        stepExecs.get(1).setStatus(WorkflowStepExecutionStatus.IN_PROGRESS);

        when(executionRepo.findForUpdate(900L)).thenReturn(Optional.of(ex));
        when(stepExecutionRepo.findByExecution_ExecutionIdOrderByStep_SequenceOrderAsc(900L)).thenReturn(stepExecs);

        ParaclinicalOrderItem pendingItem = new ParaclinicalOrderItem();
        pendingItem.setStatus(ParaclinicalOrderItemStatus.WAITING);
        when(paraclinicalItemRepo.findByOrder_Visit_VisitIdOrderByOrder_OrderIdDescOrderItemIdDesc(500L))
                .thenReturn(List.of(pendingItem));

        // Chỉ định còn WAITING -> chặn hoàn tất bước PARACLINICAL
        IllegalStateException paraEx = assertThrows(IllegalStateException.class, () ->
                service.performStepAction(900L, 1002L, "COMPLETE", "Đã chụp", true, 100L));
        assertTrue(paraEx.getMessage().contains("chỉ định cận lâm sàng chưa có kết quả"));

        // Khi ca khám chuyển sang WAITING_PAYMENT -> khóa mọi thao tác
        visit.setStatus(VisitStatus.WAITING_PAYMENT);
        assertThrows(IllegalStateException.class, () ->
                service.performStepAction(900L, 1002L, "COMPLETE", "Đã chụp", true, 100L));

        when(visitRepo.findById(500L)).thenReturn(Optional.of(visit));
        when(executionRepo.findByVisit_VisitId(500L)).thenReturn(List.of(ex));
        when(performedServiceRepo.findByVisit_VisitIdOrderByCompletedAtDesc(500L)).thenReturn(List.of());
        WorkflowVisitOverviewDTO overview = service.getVisitOverview(500L);
        assertTrue(overview.getLocked());
        assertNotNull(overview.getLockedReason());
    }
}

