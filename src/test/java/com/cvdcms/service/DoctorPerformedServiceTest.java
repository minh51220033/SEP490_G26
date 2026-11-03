package com.cvdcms.service;

import com.cvdcms.DTO.PerformedServiceRequestDTO;
import com.cvdcms.DTO.PerformedServiceResponseDTO;
import com.cvdcms.DTO.ServiceDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.impl.PerformedServiceServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Unit Test tầng Service cho FT16 - Ghi nhận dịch vụ can thiệp lâm sàng thực tế của Bác sĩ.
 * Service: PerformedServiceServiceImpl
 */
@ExtendWith(MockitoExtension.class)
class DoctorPerformedServiceTest {

    @Mock private PerformedServiceRepository performedServiceRepository;
    @Mock private ServiceRepository serviceRepository;
    @Mock private StaffProfileRepository staffProfileRepository;
    @Mock private VisitRepository visitRepository;
    @Mock private TreatmentPlanItemRepository treatmentPlanItemRepository;
    @Mock private TreatmentPlanStageRepository treatmentPlanStageRepository;
    @Mock private TreatmentPlanRepository treatmentPlanRepository;
    @Mock private ClinicalWorkflowTemplateRepository workflowTemplateRepo;
    @Mock private ClinicalWorkflowVersionRepository workflowVersionRepo;
    @Mock private ClinicalWorkflowStepRepository workflowStepRepo;

    @InjectMocks
    private PerformedServiceServiceImpl performedServiceService;

    private Visit mockVisit;
    private PatientProfile mockPatient;
    private Branch mockBranch;
    private com.cvdcms.entity.Service mockService;
    private ServiceCategory mockCategory;
    private StaffProfile mockDoctor;
    private StaffProfile mockAssistant;
    private TreatmentPlan mockPlan;
    private TreatmentPlanStage mockStage;
    private TreatmentPlanItem mockPlanItem;

    @BeforeEach
    void setUp() {
        mockBranch = new Branch();
        mockBranch.setBranchId(1L);
        mockBranch.setBranchName("Chi nhánh Quận 1 - Hồ Chí Minh");

        mockPatient = new PatientProfile();
        mockPatient.setProfileId(10L);
        mockPatient.setPatientCode("BN10001");
        mockPatient.setFullName("Nguyễn Văn Bệnh Nhân");

        mockVisit = new Visit();
        mockVisit.setVisitId(100L);
        mockVisit.setVisitCode("VIS20261103001");
        mockVisit.setStatus(VisitStatus.IN_EXAM);
        mockVisit.setPatientProfile(mockPatient);
        mockVisit.setBranch(mockBranch);

        mockCategory = new ServiceCategory();
        mockCategory.setCategoryId(5L);
        mockCategory.setCategoryName("Nha chu & Điều trị tổng quát");
        mockCategory.setDisplayOrder(1);

        mockService = new com.cvdcms.entity.Service();
        mockService.setServiceId(50L);
        mockService.setServiceCode("DV-TRAM-01");
        mockService.setServiceName("Trám răng thẩm mỹ Composite");
        mockService.setCategory(mockCategory);
        mockService.setBasePrice(BigDecimal.valueOf(450_000));
        mockService.setUnit("Răng");
        mockService.setStatus(ServiceStatus.ACTIVE);
        mockService.setRequiresTooth(true);

        mockDoctor = new StaffProfile();
        mockDoctor.setStaffId(200L);
        mockDoctor.setStaffCode("BS001");
        mockDoctor.setFullName("BS. CKI Lê Quốc Tuấn");
        mockDoctor.setPositionTitle("Bác sĩ Răng Hàm Mặt");
        mockDoctor.setStatus(StaffStatus.ACTIVE);

        mockAssistant = new StaffProfile();
        mockAssistant.setStaffId(300L);
        mockAssistant.setStaffCode("PT001");
        mockAssistant.setFullName("Điều dưỡng Trần Thị Mai");
        mockAssistant.setPositionTitle("Trợ thủ nha khoa");
        mockAssistant.setStatus(StaffStatus.ACTIVE);

        mockPlan = new TreatmentPlan();
        mockPlan.setPlanId(80L);
        mockPlan.setStatus(TreatmentPlanStatus.IN_PROGRESS);

        mockStage = new TreatmentPlanStage();
        mockStage.setStageId(801L);
        mockStage.setStatus(TreatmentPlanStageStatus.IN_PROGRESS);
        mockStage.setPlan(mockPlan);

        mockPlanItem = new TreatmentPlanItem();
        mockPlanItem.setPlanItemId(8001L);
        mockPlanItem.setStage(mockStage);
        mockPlanItem.setPlan(mockPlan);
        mockPlanItem.setService(mockService);
        mockPlanItem.setStatus(TreatmentPlanItemStatus.PLANNED);

        List<TreatmentPlanItem> items = new ArrayList<>();
        items.add(mockPlanItem);
        mockStage.setItems(items);

        List<TreatmentPlanStage> stages = new ArrayList<>();
        stages.add(mockStage);
        mockPlan.setStages(stages);
    }

    @Test
    @DisplayName("FT16-SVC-01: Chốt dịch vụ đã hoàn thành và snapshot đúng đơn giá gốc tại thời điểm thực hiện")
    void testCreatePerformedService_PriceSnapshottingFromBasePrice() {
        PerformedServiceRequestDTO request = PerformedServiceRequestDTO.builder()
                .visitId(100L)
                .serviceId(50L)
                .doctorId(200L)
                .toothNumbers("Răng 16, Răng 17")
                .quantity(BigDecimal.valueOf(2))
                .unitPrice(null)
                .discountAmount(BigDecimal.valueOf(50_000))
                .clinicalNotes("Trám xoang loại II composite hạt mịn")
                .build();

        when(visitRepository.findById(100L)).thenReturn(Optional.of(mockVisit));
        when(serviceRepository.findById(50L)).thenReturn(Optional.of(mockService));
        when(staffProfileRepository.findById(200L)).thenReturn(Optional.of(mockDoctor));

        ArgumentCaptor<PerformedService> captor = ArgumentCaptor.forClass(PerformedService.class);
        when(performedServiceRepository.save(captor.capture())).thenAnswer(inv -> {
            PerformedService ps = inv.getArgument(0);
            ps.setPerformedId(999L);
            return ps;
        });

        when(workflowTemplateRepo.findFirstByService_ServiceIdAndStatus(50L, WorkflowTemplateStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(workflowTemplateRepo.findByService_ServiceId(50L)).thenReturn(Collections.emptyList());

        PerformedServiceResponseDTO response = performedServiceService.createPerformedService(request);

        PerformedService captured = captor.getValue();
        assertNotNull(captured);
        assertEquals(BigDecimal.valueOf(450_000), captured.getUnitPrice());
        assertEquals(BigDecimal.valueOf(2), captured.getQuantity());
        assertEquals(BigDecimal.valueOf(50_000), captured.getDiscountAmount());
        assertEquals(BigDecimal.valueOf(850_000), captured.getFinalPrice());
        assertEquals(PerformedServiceStatus.COMPLETED, captured.getStatus());
        assertNotNull(captured.getCompletedAt());

        // Kiểm tra DTO
        assertEquals(999L, response.getPerformedId());
        assertEquals("Đã hoàn thành", response.getStatusDisplay());
    }

    @Test
    @DisplayName("FT16-SVC-02: Bác sĩ chỉ định đơn giá tùy chỉnh và chiết khấu hợp lệ")
    void testCreatePerformedService_CustomUnitPriceAndDiscount() {
        PerformedServiceRequestDTO request = PerformedServiceRequestDTO.builder()
                .visitId(100L)
                .serviceId(50L)
                .doctorId(200L)
                .quantity(BigDecimal.ONE)
                .unitPrice(BigDecimal.valueOf(500_000))
                .discountAmount(BigDecimal.valueOf(100_000))
                .build();

        when(visitRepository.findById(100L)).thenReturn(Optional.of(mockVisit));
        when(serviceRepository.findById(50L)).thenReturn(Optional.of(mockService));
        when(staffProfileRepository.findById(200L)).thenReturn(Optional.of(mockDoctor));

        when(performedServiceRepository.save(any(PerformedService.class))).thenAnswer(inv -> inv.getArgument(0));
        when(workflowTemplateRepo.findFirstByService_ServiceIdAndStatus(anyLong(), any())).thenReturn(Optional.empty());
        when(workflowTemplateRepo.findByService_ServiceId(anyLong())).thenReturn(Collections.emptyList());

        PerformedServiceResponseDTO response = performedServiceService.createPerformedService(request);

        assertNotNull(response);
        assertEquals(BigDecimal.valueOf(500_000), response.getUnitPrice());
        assertEquals(BigDecimal.valueOf(100_000), response.getDiscountAmount());
        assertEquals(BigDecimal.valueOf(400_000), response.getFinalPrice());
    }

    @Test
    @DisplayName("FT16-SVC-03: Gán chính xác Bác sĩ thực hiện để phục vụ tính hoa hồng FT32")
    void testCreatePerformedService_AssignCorrectDoctorForCommission() {
        PerformedServiceRequestDTO request = PerformedServiceRequestDTO.builder()
                .visitId(100L)
                .serviceId(50L)
                .doctorId(200L)
                .assistantId(300L)
                .quantity(BigDecimal.ONE)
                .build();

        when(visitRepository.findById(100L)).thenReturn(Optional.of(mockVisit));
        when(serviceRepository.findById(50L)).thenReturn(Optional.of(mockService));
        when(staffProfileRepository.findById(200L)).thenReturn(Optional.of(mockDoctor));
        when(staffProfileRepository.findById(300L)).thenReturn(Optional.of(mockAssistant));

        ArgumentCaptor<PerformedService> captor = ArgumentCaptor.forClass(PerformedService.class);
        when(performedServiceRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        performedServiceService.createPerformedService(request);

        PerformedService saved = captor.getValue();
        assertNotNull(saved.getDoctor());
        assertEquals(200L, saved.getDoctor().getStaffId());
        assertEquals("BS001", saved.getDoctor().getStaffCode());
        assertNotNull(saved.getAssistant());
        assertEquals(300L, saved.getAssistant().getStaffId());
    }

    @Test
    @DisplayName("FT16-SVC-04: Báo lỗi khi không tìm thấy Bác sĩ thực hiện theo doctorId")
    void testCreatePerformedService_DoctorNotFoundThrowsException() {
        PerformedServiceRequestDTO request = PerformedServiceRequestDTO.builder()
                .visitId(100L)
                .serviceId(50L)
                .doctorId(9999L)
                .build();

        when(visitRepository.findById(100L)).thenReturn(Optional.of(mockVisit));
        when(serviceRepository.findById(50L)).thenReturn(Optional.of(mockService));
        when(staffProfileRepository.findById(9999L)).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                performedServiceService.createPerformedService(request));

        assertTrue(ex.getMessage().contains("Không tìm thấy bác sĩ với ID: 9999"));
        verify(performedServiceRepository, never()).save(any());
    }

    @Test
    @DisplayName("FT16-SVC-05: Chặn thêm dịch vụ khi ca khám đã chuyển sang thanh toán (WAITING_PAYMENT) theo BR-EMR-02")
    void testCreatePerformedService_BlockedWhenWaitingPayment() {
        mockVisit.setStatus(VisitStatus.WAITING_PAYMENT);

        PerformedServiceRequestDTO request = PerformedServiceRequestDTO.builder()
                .visitId(100L)
                .serviceId(50L)
                .doctorId(200L)
                .build();

        when(visitRepository.findById(100L)).thenReturn(Optional.of(mockVisit));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                performedServiceService.createPerformedService(request));

        assertTrue(ex.getMessage().contains("BR-EMR-02"));
        verify(performedServiceRepository, never()).save(any());
    }

    @Test
    @DisplayName("FT16-SVC-06: Chặn thêm dịch vụ khi ca khám đã hoàn tất (COMPLETED) theo BR-EMR-02")
    void testCreatePerformedService_BlockedWhenVisitCompleted() {
        mockVisit.setStatus(VisitStatus.COMPLETED);

        PerformedServiceRequestDTO request = PerformedServiceRequestDTO.builder()
                .visitId(100L)
                .serviceId(50L)
                .doctorId(200L)
                .build();

        when(visitRepository.findById(100L)).thenReturn(Optional.of(mockVisit));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                performedServiceService.createPerformedService(request));

        assertTrue(ex.getMessage().contains("BR-EMR-02"));
        verify(performedServiceRepository, never()).save(any());
    }

    @Test
    @DisplayName("FT16-SVC-07: Chặn hủy/xóa dịch vụ đã ghi nhận khi ca khám đã khóa (BR-EMR-02)")
    void testCancelPerformedService_BlockedWhenVisitLocked() {
        mockVisit.setStatus(VisitStatus.WAITING_PAYMENT);

        PerformedService existing = new PerformedService();
        existing.setPerformedId(555L);
        existing.setVisit(mockVisit);

        when(performedServiceRepository.findById(555L)).thenReturn(Optional.of(existing));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                performedServiceService.cancelPerformedService(555L));

        assertTrue(ex.getMessage().contains("BR-EMR-02"));
        verify(performedServiceRepository, never()).delete(any());
    }

    @Test
    @DisplayName("FT16-SVC-08: Hủy dịch vụ thực hiện thành công khi ca khám đang diễn ra (IN_EXAM)")
    void testCancelPerformedService_SuccessWhenVisitActive() {
        mockVisit.setStatus(VisitStatus.IN_EXAM);

        PerformedService existing = new PerformedService();
        existing.setPerformedId(555L);
        existing.setVisit(mockVisit);

        when(performedServiceRepository.findById(555L)).thenReturn(Optional.of(existing));

        performedServiceService.cancelPerformedService(555L);

        verify(performedServiceRepository, times(1)).delete(existing);
    }

    @Test
    @DisplayName("FT16-SVC-09: Tự động cập nhật hoàn thành bước điều trị (PlanItem) và Kế hoạch điều trị")
    void testCreatePerformedService_AutoAdvanceTreatmentPlanProgress() {
        PerformedServiceRequestDTO request = PerformedServiceRequestDTO.builder()
                .visitId(100L)
                .serviceId(50L)
                .doctorId(200L)
                .planItemId(8001L)
                .build();

        when(visitRepository.findById(100L)).thenReturn(Optional.of(mockVisit));
        when(serviceRepository.findById(50L)).thenReturn(Optional.of(mockService));
        when(staffProfileRepository.findById(200L)).thenReturn(Optional.of(mockDoctor));
        when(treatmentPlanItemRepository.findById(8001L)).thenReturn(Optional.of(mockPlanItem));

        when(performedServiceRepository.save(any(PerformedService.class))).thenAnswer(inv -> inv.getArgument(0));
        when(workflowTemplateRepo.findFirstByService_ServiceIdAndStatus(anyLong(), any())).thenReturn(Optional.empty());
        when(workflowTemplateRepo.findByService_ServiceId(anyLong())).thenReturn(Collections.emptyList());

        performedServiceService.createPerformedService(request);

        assertEquals(TreatmentPlanItemStatus.COMPLETED, mockPlanItem.getStatus());
        assertNotNull(mockPlanItem.getCompletedAt());
        assertEquals(TreatmentPlanStageStatus.COMPLETED, mockStage.getStatus());
        assertEquals(TreatmentPlanStatus.COMPLETED, mockPlan.getStatus());
    }

    @Test
    @DisplayName("FT16-SVC-10: Hoàn tác trạng thái bước điều trị về PLANNED khi hủy dịch vụ thực hiện")
    void testCancelPerformedService_RollbackTreatmentPlanItem() {
        mockVisit.setStatus(VisitStatus.IN_EXAM);

        mockPlanItem.setStatus(TreatmentPlanItemStatus.COMPLETED);
        mockPlanItem.setCompletedAt(LocalDateTime.now());

        PerformedService existing = new PerformedService();
        existing.setPerformedId(555L);
        existing.setVisit(mockVisit);
        existing.setPlanItem(mockPlanItem);

        when(performedServiceRepository.findById(555L)).thenReturn(Optional.of(existing));

        performedServiceService.cancelPerformedService(555L);

        assertEquals(TreatmentPlanItemStatus.PLANNED, mockPlanItem.getStatus());
        assertNull(mockPlanItem.getCompletedAt());
        assertEquals(TreatmentPlanStageStatus.PENDING, mockStage.getStatus());
        assertEquals(TreatmentPlanStatus.ACCEPTED, mockPlan.getStatus());
        verify(performedServiceRepository).delete(existing);
    }

    @Test
    @DisplayName("FT16-SVC-11: Lấy danh mục dịch vụ hoạt động và lọc danh sách Bác sĩ / Phụ tá")
    void testGetCatalogsAndAvailableStaff() {
        when(serviceRepository.findByStatusOrderByCategory_DisplayOrderAscServiceNameAsc(ServiceStatus.ACTIVE))
                .thenReturn(List.of(mockService));

        List<ServiceDTO> services = performedServiceService.getAllActiveServices();
        assertEquals(1, services.size());
        assertEquals("DV-TRAM-01", services.get(0).getServiceCode());

        StaffProfile receptionist = new StaffProfile();
        receptionist.setStaffCode("LT001");
        receptionist.setPositionTitle("Lễ tân");
        receptionist.setStatus(StaffStatus.ACTIVE);

        when(staffProfileRepository.findByStatus(StaffStatus.ACTIVE))
                .thenReturn(List.of(mockDoctor, mockAssistant, receptionist));

        List<StaffProfile> doctors = performedServiceService.getAvailableDoctors();
        assertEquals(1, doctors.size());
        assertEquals("BS001", doctors.get(0).getStaffCode());

        List<StaffProfile> assistants = performedServiceService.getAvailableAssistants();
        assertEquals(1, assistants.size());
        assertEquals("PT001", assistants.get(0).getStaffCode());
    }
}

