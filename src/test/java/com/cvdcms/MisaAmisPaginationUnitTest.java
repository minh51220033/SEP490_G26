package com.cvdcms;

import com.cvdcms.DTO.LaboOrderResponseDTO;
import com.cvdcms.DTO.WorkflowTemplateResponseDTO;
import com.cvdcms.controller.ClinicalWorkflowController;
import com.cvdcms.controller.LaboPageController;
import com.cvdcms.emr.EmrModuleService;
import com.cvdcms.emr.EmrPageController;
import com.cvdcms.patient.*;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.repository.SupplierRepository;
import com.cvdcms.service.ClinicalWorkflowService;
import com.cvdcms.service.LaboOrderService;
import com.cvdcms.service.PerformedServiceService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class MisaAmisPaginationUnitTest {

    @Mock
    private ClinicalWorkflowService workflowService;
    @Mock
    private PerformedServiceService performedServiceService;
    @Mock
    private LaboOrderService laboOrderService;
    @Mock
    private SupplierRepository supplierRepository;
    @Mock
    private PatientProfileRepository patientProfileRepository;
    @Mock
    private BranchRepository branchRepository;
    @Mock
    private DoctorRecordService doctorRecordService;
    @Mock
    private com.cvdcms.repository.InvoiceRepository invoiceRepository;
    @Mock
    private PatientService patientService;
    @Mock
    private PatientOverviewService patientOverviewService;
    @Mock
    private PatientDocumentStorageService patientDocumentStorageService;
    @Mock
    private EmrModuleService emrModuleService;
    @Mock
    private HttpSession session;

    // -------------------------------------------------------------
    // 1. ClinicalWorkflowController Pagination Test
    // -------------------------------------------------------------
    @Test
    @DisplayName("Workflows: Size hợp lệ và phân trang chuẩn MISA AMIS")
    void testWorkflowsPagination_Normal() {
        ClinicalWorkflowController controller = new ClinicalWorkflowController(workflowService,
                performedServiceService);

        List<WorkflowTemplateResponseDTO> mockList = new ArrayList<>();
        for (int i = 1; i <= 24; i++) {
            WorkflowTemplateResponseDTO dto = new WorkflowTemplateResponseDTO();
            dto.setTemplateId((long) i);
            dto.setTemplateName("Quy trình " + i);
            dto.setTemplateCode("QT-" + i);
            mockList.add(dto);
        }
        when(workflowService.getAllTemplates()).thenReturn(mockList);
        when(performedServiceService.getAllActiveServices()).thenReturn(List.of());

        Model model = new ConcurrentModel();
        // Request page 0, size 10
        String view = controller.getWorkflowsPage(0, 10, null, null, null, model);
        assertEquals("doctor/workflows", view);
        assertEquals(24L, model.getAttribute("totalItems"));
        assertEquals(0, model.getAttribute("currentPage"));
        assertEquals(10, model.getAttribute("pageSize"));
        assertEquals(3, model.getAttribute("totalPages"));
        assertEquals(1L, model.getAttribute("fromItem"));
        assertEquals(10L, model.getAttribute("toItem"));

        // Page 2 (last page, items 21-24)
        Model modelPage2 = new ConcurrentModel();
        controller.getWorkflowsPage(2, 10, null, null, null, modelPage2);
        assertEquals(2, modelPage2.getAttribute("currentPage"));
        assertEquals(21L, modelPage2.getAttribute("fromItem"));
        assertEquals(24L, modelPage2.getAttribute("toItem"));
    }

    @Test
    @DisplayName("Workflows: Dữ liệu rỗng (0 bản ghi) hiển thị 0 - 0")
    void testWorkflowsPagination_Empty() {
        ClinicalWorkflowController controller = new ClinicalWorkflowController(workflowService,
                performedServiceService);
        when(workflowService.getAllTemplates()).thenReturn(List.of());
        when(performedServiceService.getAllActiveServices()).thenReturn(List.of());

        Model model = new ConcurrentModel();
        controller.getWorkflowsPage(0, 10, null, null, null, model);
        assertEquals(0L, model.getAttribute("totalItems"));
        assertEquals(0, model.getAttribute("currentPage"));
        assertEquals(0, model.getAttribute("totalPages"));
        assertEquals(0L, model.getAttribute("fromItem"));
        assertEquals(0L, model.getAttribute("toItem"));
    }

    @Test
    @DisplayName("Workflows: Size không hợp lệ (không phải 10, 25, 50, 100) tự động reset về 10")
    void testWorkflowsPagination_InvalidSizeFallback() {
        ClinicalWorkflowController controller = new ClinicalWorkflowController(workflowService,
                performedServiceService);
        when(workflowService.getAllTemplates()).thenReturn(List.of());
        when(performedServiceService.getAllActiveServices()).thenReturn(List.of());

        Model model = new ConcurrentModel();
        controller.getWorkflowsPage(0, 999, null, null, null, model);
        assertEquals(10, model.getAttribute("pageSize"));
    }

    // -------------------------------------------------------------
    // 2. LaboPageController Pagination Test
    // -------------------------------------------------------------
    @Test
    @DisplayName("Labo: Size hợp lệ và phân trang chuẩn MISA AMIS")
    void testLaboPagination() {
        LaboPageController controller = new LaboPageController(laboOrderService, supplierRepository,
                patientProfileRepository, branchRepository);

        List<LaboOrderResponseDTO> mockOrders = new ArrayList<>();
        for (int i = 1; i <= 35; i++) {
            LaboOrderResponseDTO o = new LaboOrderResponseDTO();
            o.setLaboId((long) i);
            o.setLaboCode("LB-" + i);
            mockOrders.add(o);
        }
        when(laboOrderService.getAllOrders(any(), any(), any(), any(), any())).thenReturn(mockOrders);

        Model model = new ConcurrentModel();
        String view = controller.laboManagement("orders", null, null, null, 1, 25, model);
        assertEquals("doctor/labo-management", view);
        assertEquals(35L, model.getAttribute("totalItems"));
        assertEquals(1, model.getAttribute("currentPage"));
        assertEquals(25, model.getAttribute("pageSize"));
        assertEquals(2, model.getAttribute("totalPages"));
        assertEquals(26L, model.getAttribute("fromItem"));
        assertEquals(35L, model.getAttribute("toItem"));
    }

    // -------------------------------------------------------------
    // 3. DoctorRecordPageController Pagination Test
    // -------------------------------------------------------------
    @Test
    @DisplayName("DoctorRecord: Phân trang bệnh nhân với 6 thuộc tính bắt buộc")
    void testDoctorRecordPagination() {
        DoctorRecordPageController controller = new DoctorRecordPageController(doctorRecordService, invoiceRepository,
                patientOverviewService);

        List<PatientView> content = List.of();
        Page<PatientView> mockPage = new PageImpl<>(content, PageRequest.of(0, 10), 42);
        when(doctorRecordService.search(any(), any(), any(), any(), any(), eq(0), eq(10))).thenReturn(mockPage);
        when(doctorRecordService.allowedBranches(any())).thenReturn(List.of());

        Model model = new ConcurrentModel();
        String view = controller.list(session, model, "Nguyen", null, null, null, 0, 10);
        assertEquals("patient-records/list", view);
        assertEquals(42L, model.getAttribute("totalItems"));
        assertEquals(0, model.getAttribute("currentPage"));
        assertEquals(10, model.getAttribute("pageSize"));
        assertEquals(5, model.getAttribute("totalPages"));
        assertEquals(1L, model.getAttribute("fromItem"));
        assertEquals(10L, model.getAttribute("toItem"));
        assertEquals("Nguyen", model.getAttribute("keyword"));
    }

    // -------------------------------------------------------------
    // 4. PatientPageController Pagination Test
    // -------------------------------------------------------------
    @Test
    @DisplayName("Reception Patient: Phân trang bệnh nhân với 6 thuộc tính bắt buộc")
    void testPatientPageControllerPagination() {
        PatientPageController controller = new PatientPageController(patientService, invoiceRepository,
                patientOverviewService);

        List<PatientView> content = List.of();
        Page<PatientView> mockPage = new PageImpl<>(content, PageRequest.of(1, 50), 120);
        when(patientService.list(any(), any(), any(), any(), any(), eq(1), eq(50))).thenReturn(mockPage);
        when(patientService.branches(any())).thenReturn(List.of());

        Model model = new ConcurrentModel();
        String view = controller.list(null, null, null, null, 1, 50, model, session);
        assertEquals("patient-records/list", view);
        assertEquals(120L, model.getAttribute("totalItems"));
        assertEquals(1, model.getAttribute("currentPage"));
        assertEquals(50, model.getAttribute("pageSize"));
        assertEquals(3, model.getAttribute("totalPages"));
        assertEquals(51L, model.getAttribute("fromItem"));
        assertEquals(100L, model.getAttribute("toItem"));
    }

    // -------------------------------------------------------------
    // 5. AdminPatientPageController Pagination Test
    // -------------------------------------------------------------
    @Test
    @DisplayName("Admin Patient: Phân trang chuẩn MISA AMIS")
    void testAdminPatientPageControllerPagination() {
        AdminPatientPageController controller = new AdminPatientPageController(patientOverviewService,
                patientDocumentStorageService);

        List<PatientOverviewDTO> content = List.of();
        Page<PatientOverviewDTO> mockPage = new PageImpl<>(content, PageRequest.of(0, 10), 6);
        when(patientOverviewService.requireAdminOrManager(any())).thenReturn(new com.cvdcms.entity.User());
        when(patientOverviewService.searchPatients(any(), any(), any(), any(), eq(0), eq(10))).thenReturn(mockPage);
        when(patientOverviewService.getAllActiveBranches()).thenReturn(List.of());

        Model model = new ConcurrentModel();
        // Tổng số 6 < size 10 (Test case 2 in checklist)
        String view = controller.list(null, null, null, null, 0, 10, model, session);
        assertEquals("admin/patients/list", view);
        assertEquals(6L, model.getAttribute("totalItems"));
        assertEquals(0, model.getAttribute("currentPage"));
        assertEquals(10, model.getAttribute("pageSize"));
        assertEquals(1, model.getAttribute("totalPages"));
        assertEquals(1L, model.getAttribute("fromItem"));
        assertEquals(6L, model.getAttribute("toItem"));
    }

    // -------------------------------------------------------------
    // 6. EmrPageController Pagination Test
    // -------------------------------------------------------------
    @Test
    @DisplayName("EMR List: Phân trang chuẩn MISA AMIS")
    void testEmrPageControllerPagination() {
        EmrPageController controller = new EmrPageController(emrModuleService);

        List<EmrModuleService.RecordRow> mockRows = new ArrayList<>();
        for (int i = 1; i <= 14; i++) {
            mockRows.add(new EmrModuleService.RecordRow((long) i, "EMR-" + i, (long) i, "LK-" + i, (long) i, "BN-" + i,
                    "Bệnh nhân " + i, "Cơ sở 1", "BS. A", java.time.LocalDateTime.now(),
                    com.cvdcms.emr.EmrRecordStatus.DRAFT, 1, java.time.LocalDateTime.now()));
        }
        EmrModuleService.Dashboard dashboard = new EmrModuleService.Dashboard(mockRows, List.of(), 14, 0, 0);
        when(emrModuleService.dashboard(any(), any(), any(), any(), any(), any(), any())).thenReturn(dashboard);

        Model model = new ConcurrentModel();
        // 14 items, size 10, page 0 -> items 1 - 10
        String view = controller.listView(session, model, null, null, null, null, null, null, 0, 10);
        assertEquals("emr/list", view);
        assertEquals(14L, model.getAttribute("totalItems"));
        assertEquals(0, model.getAttribute("currentPage"));
        assertEquals(10, model.getAttribute("pageSize"));
        assertEquals(2, model.getAttribute("totalPages"));
        assertEquals(1L, model.getAttribute("fromItem"));
        assertEquals(10L, model.getAttribute("toItem"));

        // Page 1 -> items 11 - 14
        Model modelPage1 = new ConcurrentModel();
        controller.listView(session, modelPage1, null, null, null, null, null, null, 1, 10);
        assertEquals(1, modelPage1.getAttribute("currentPage"));
        assertEquals(11L, modelPage1.getAttribute("fromItem"));
        assertEquals(14L, modelPage1.getAttribute("toItem"));
    }

    // -------------------------------------------------------------
    // 7. Edge Cases: Out-of-Bounds Page Graceful Fallback
    // -------------------------------------------------------------
    @Test
    @DisplayName("DoctorRecord: Yêu cầu page vượt quá totalPages tự động lùi về trang cuối hợp lệ")
    void testDoctorRecord_OutOfBoundsPageFallback() {
        DoctorRecordPageController controller = new DoctorRecordPageController(doctorRecordService, invoiceRepository,
                patientOverviewService);

        Page<PatientView> outOfBoundsPage = new PageImpl<>(List.of(), PageRequest.of(5, 10), 15);
        Page<PatientView> lastValidPage = new PageImpl<>(List.of(), PageRequest.of(1, 10), 15);

        when(doctorRecordService.search(any(), any(), any(), any(), any(), eq(5), eq(10))).thenReturn(outOfBoundsPage);
        when(doctorRecordService.search(any(), any(), any(), any(), any(), eq(1), eq(10))).thenReturn(lastValidPage);
        when(doctorRecordService.allowedBranches(any())).thenReturn(List.of());

        Model model = new ConcurrentModel();
        controller.list(session, model, null, null, null, null, 5, 10);

        assertEquals(15L, model.getAttribute("totalItems"));
        assertEquals(1, model.getAttribute("currentPage"));
        assertEquals(2, model.getAttribute("totalPages"));
        assertEquals(11L, model.getAttribute("fromItem"));
        assertEquals(15L, model.getAttribute("toItem"));
    }

    @Test
    @DisplayName("AdminPatient: Yêu cầu page vượt quá totalPages tự động lùi về trang 0")
    void testAdminPatient_OutOfBoundsPageFallback() {
        AdminPatientPageController controller = new AdminPatientPageController(patientOverviewService,
                patientDocumentStorageService);

        Page<PatientOverviewDTO> outOfBoundsPage = new PageImpl<>(List.of(), PageRequest.of(10, 10), 6);
        Page<PatientOverviewDTO> lastValidPage = new PageImpl<>(List.of(), PageRequest.of(0, 10), 6);

        when(patientOverviewService.requireAdminOrManager(any())).thenReturn(new com.cvdcms.entity.User());
        when(patientOverviewService.searchPatients(any(), any(), any(), any(), eq(10), eq(10)))
                .thenReturn(outOfBoundsPage);
        when(patientOverviewService.searchPatients(any(), any(), any(), any(), eq(0), eq(10)))
                .thenReturn(lastValidPage);
        when(patientOverviewService.getAllActiveBranches()).thenReturn(List.of());

        Model model = new ConcurrentModel();
        controller.list(null, null, null, null, 10, 10, model, session);

        assertEquals(6L, model.getAttribute("totalItems"));
        assertEquals(0, model.getAttribute("currentPage"));
        assertEquals(1, model.getAttribute("totalPages"));
        assertEquals(1L, model.getAttribute("fromItem"));
        assertEquals(6L, model.getAttribute("toItem"));
    }

    @Test
    @DisplayName("PatientPageController: Yêu cầu page vượt quá totalPages tự động lùi về trang cuối")
    void testPatientPageController_OutOfBoundsPageFallback() {
        PatientPageController controller = new PatientPageController(patientService, invoiceRepository,
                patientOverviewService);

        Page<PatientView> outOfBoundsPage = new PageImpl<>(List.of(), PageRequest.of(8, 25), 35);
        Page<PatientView> lastValidPage = new PageImpl<>(List.of(), PageRequest.of(1, 25), 35);

        when(patientService.list(any(), any(), any(), any(), any(), eq(8), eq(25))).thenReturn(outOfBoundsPage);
        when(patientService.list(any(), any(), any(), any(), any(), eq(1), eq(25))).thenReturn(lastValidPage);
        when(patientService.branches(any())).thenReturn(List.of());

        Model model = new ConcurrentModel();
        controller.list(null, null, null, null, 8, 25, model, session);

        assertEquals(35L, model.getAttribute("totalItems"));
        assertEquals(1, model.getAttribute("currentPage"));
        assertEquals(2, model.getAttribute("totalPages"));
        assertEquals(26L, model.getAttribute("fromItem"));
        assertEquals(35L, model.getAttribute("toItem"));
    }
}
