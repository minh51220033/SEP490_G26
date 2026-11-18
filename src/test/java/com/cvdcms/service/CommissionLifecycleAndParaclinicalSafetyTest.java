package com.cvdcms.service;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.impl.ParaclinicalOrderServiceImpl;
import com.cvdcms.service.impl.PerformedServiceServiceImpl;
import com.cvdcms.service.impl.ServiceCommissionServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CommissionLifecycleAndParaclinicalSafety")
class CommissionLifecycleAndParaclinicalSafetyTest {

    @Mock
    private CommissionPolicyRepository commissionPolicyRepository;
    @Mock
    private ServiceCommissionRepository serviceCommissionRepository;
    @Mock
    private InvoiceItemRepository invoiceItemRepository;
    @Mock
    private PayrollPeriodRepository payrollPeriodRepository;
    @Mock
    private PayrollItemRepository payrollItemRepository;
    @Mock
    private PayrollService payrollService;

    @Mock
    private ParaclinicalOrderRepository paraclinicalOrderRepository;
    @Mock
    private ParaclinicalOrderItemRepository paraclinicalOrderItemRepository;
    @Mock
    private VisitRepository visitRepository;
    @Mock
    private ServiceRepository serviceRepository;
    @Mock
    private ServiceCategoryRepository serviceCategoryRepository;
    @Mock
    private StaffProfileRepository staffProfileRepository;
    @Mock
    private PerformedServiceRepository performedServiceRepository;
    @Mock
    private TreatmentPlanItemRepository treatmentPlanItemRepository;
    @Mock
    private TreatmentPlanStageRepository treatmentPlanStageRepository;
    @Mock
    private TreatmentPlanRepository treatmentPlanRepository;
    @Mock
    private ClinicalWorkflowTemplateRepository workflowTemplateRepo;
    @Mock
    private ClinicalWorkflowVersionRepository workflowVersionRepo;
    @Mock
    private ClinicalWorkflowStepRepository workflowStepRepo;

    @InjectMocks
    private PerformedServiceServiceImpl performedServiceService;

    @Test
    @DisplayName("DB10-02: Đổi dịch vụ khi hoa hồng đang gắn DRAFT giữ đúng binding vào PayrollItem và tính lại sau khi gắn")
    void recalculateCommissions_RebindsDraftItemBeforeRecalculate() {
        ServiceCommissionServiceImpl svc = new ServiceCommissionServiceImpl(
                commissionPolicyRepository, serviceCommissionRepository, invoiceItemRepository
        );
        svc.setPayrollPeriodRepository(payrollPeriodRepository);
        svc.setPayrollItemRepository(payrollItemRepository);
        svc.setPayrollService(payrollService);

        Branch branch = new Branch();
        branch.setBranchId(1L);

        StaffProfile doctor = new StaffProfile();
        doctor.setStaffId(10L);

        com.cvdcms.entity.Service newService = new com.cvdcms.entity.Service();
        newService.setServiceId(20L);
        newService.setBasePrice(new BigDecimal("1500000"));

        PerformedService ps = new PerformedService();
        ps.setPerformedId(100L);
        ps.setBranch(branch);
        ps.setDoctor(doctor);
        ps.setService(newService);
        ps.setFinalPrice(new BigDecimal("1500000"));
        ps.setStatus(PerformedServiceStatus.COMPLETED);
        ps.setCompletedAt(LocalDateTime.of(2026, 11, 15, 10, 0));

        PayrollPeriod draftPeriod = new PayrollPeriod();
        draftPeriod.setPeriodId(5L);
        draftPeriod.setBranch(branch);
        draftPeriod.setMonth(11);
        draftPeriod.setYear(2026);
        draftPeriod.setStatus(PayrollStatus.DRAFT);

        PayrollItem draftItem = new PayrollItem();
        draftItem.setItemId(50L);
        draftItem.setPayrollPeriod(draftPeriod);
        draftItem.setStaff(doctor);

        ServiceCommission oldSc = new ServiceCommission();
        oldSc.setCommissionId(1L);
        oldSc.setPerformedService(ps);
        oldSc.setStaff(doctor);
        oldSc.setRoleType(CommissionRoleType.MAIN_DOCTOR);
        oldSc.setCommissionAmount(new BigDecimal("100000"));
        oldSc.setStatus(CommissionStatus.PENDING);
        oldSc.setPayrollItem(draftItem);

        CommissionPolicy policy = new CommissionPolicy();
        policy.setPolicyId(9L);
        policy.setIsActive(true);
        policy.setCommissionType(CommissionType.PERCENTAGE);
        policy.setCommissionValue(new BigDecimal("10.00"));
        policy.setStaff(doctor);
        policy.setService(newService);

        when(serviceCommissionRepository.findByPerformedService_PerformedId(100L)).thenReturn(List.of(oldSc));
        when(payrollPeriodRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(draftPeriod));
        when(serviceCommissionRepository.findByPerformedService_PerformedIdForUpdate(100L)).thenReturn(List.of(oldSc));
        when(serviceCommissionRepository.existsByPerformedService_PerformedIdAndStaff_StaffIdAndRoleType(100L, 10L, CommissionRoleType.MAIN_DOCTOR))
                .thenReturn(false);
        when(commissionPolicyRepository.findApplicablePolicies(10L, null, 20L)).thenReturn(List.of(policy));
        when(serviceCommissionRepository.save(any(ServiceCommission.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.recalculateCommissionsForPerformedService(ps);

        ArgumentCaptor<ServiceCommission> savedCaptor = ArgumentCaptor.forClass(ServiceCommission.class);
        verify(serviceCommissionRepository, atLeast(2)).save(savedCaptor.capture());
        ServiceCommission finalSaved = savedCaptor.getAllValues().get(savedCaptor.getAllValues().size() - 1);
        assertEquals(0, new BigDecimal("150000.00").compareTo(finalSaved.getCommissionAmount()));
        assertSame(draftItem, finalSaved.getPayrollItem());

        InOrder order = inOrder(serviceCommissionRepository, payrollService);
        order.verify(serviceCommissionRepository).delete(oldSc);
        order.verify(serviceCommissionRepository, atLeastOnce()).save(any(ServiceCommission.class));
        order.verify(payrollService, times(1)).recalculateDraftItemAfterCommissionChange(50L);
    }

    @Test
    @DisplayName("DB10-02: Chặn sửa hoa hồng khi kỳ lương đang SUBMITTED/APPROVED/PAID")
    void recalculateCommissions_BlockedWhenPeriodSubmitted() {
        ServiceCommissionServiceImpl svc = new ServiceCommissionServiceImpl(
                commissionPolicyRepository, serviceCommissionRepository, invoiceItemRepository
        );
        svc.setPayrollPeriodRepository(payrollPeriodRepository);
        svc.setPayrollService(payrollService);

        PayrollPeriod submittedPeriod = new PayrollPeriod();
        submittedPeriod.setPeriodId(6L);
        submittedPeriod.setStatus(PayrollStatus.SUBMITTED);

        PayrollItem item = new PayrollItem();
        item.setItemId(51L);
        item.setPayrollPeriod(submittedPeriod);

        ServiceCommission oldSc = new ServiceCommission();
        oldSc.setCommissionId(2L);
        oldSc.setStatus(CommissionStatus.PENDING);
        oldSc.setPayrollItem(item);

        PerformedService ps = new PerformedService();
        ps.setPerformedId(101L);

        when(serviceCommissionRepository.findByPerformedService_PerformedId(101L)).thenReturn(List.of(oldSc));
        when(payrollPeriodRepository.findByIdForUpdate(6L)).thenReturn(Optional.of(submittedPeriod));
        when(serviceCommissionRepository.findByPerformedService_PerformedIdForUpdate(101L)).thenReturn(List.of(oldSc));

        assertThrows(IllegalStateException.class, () -> svc.recalculateCommissionsForPerformedService(ps));
        verify(serviceCommissionRepository, never()).delete(any());
        verify(payrollService, never()).recalculateDraftItemAfterCommissionChange(any());
    }

    @Test
    @DisplayName("DB10-03: Hủy chỉ định WAITING có externalRefId nhưng chưa có ảnh vẫn hủy mềm giữ nguyên mã/ID")
    void deleteOrder_SoftCancelsWhenWaitingHasExternalRefId() {
        ParaclinicalOrderServiceImpl paraSvc = new ParaclinicalOrderServiceImpl(
                paraclinicalOrderRepository,
                paraclinicalOrderItemRepository,
                visitRepository,
                serviceRepository,
                serviceCategoryRepository,
                staffProfileRepository,
                performedServiceRepository,
                invoiceItemRepository
        );

        Visit visit = new Visit();
        visit.setStatus(VisitStatus.IN_EXAM);

        ParaclinicalOrder order = new ParaclinicalOrder();
        order.setVisit(visit);

        ParaclinicalOrderItem item = new ParaclinicalOrderItem();
        item.setOrderItemId(77L);
        item.setOrder(order);
        item.setStatus(ParaclinicalOrderItemStatus.WAITING);
        item.setExternalRefId("XQ900123");

        when(paraclinicalOrderItemRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(item));

        paraSvc.deleteOrder(77L);

        assertEquals(ParaclinicalOrderItemStatus.CANCELLED, item.getStatus());
        assertEquals("XQ900123", item.getExternalRefId());
        verify(paraclinicalOrderItemRepository).save(item);
        verify(paraclinicalOrderItemRepository, never()).delete(any());
    }

    @Test
    @DisplayName("DB10-03: Hủy dịch vụ từng có commission PENDING phải kiểm tra dấu vết trước khi hủy để không hard-delete mất lịch sử")
    void cancelPerformedService_SoftCancelsWhenHadPendingCommissionBeforeCancel() {
        Visit visit = new Visit();
        visit.setStatus(VisitStatus.IN_EXAM);

        PerformedService ps = new PerformedService();
        ps.setPerformedId(88L);
        ps.setVisit(visit);
        ps.setStatus(PerformedServiceStatus.COMPLETED);

        ServiceCommission pendingSc = new ServiceCommission();
        pendingSc.setCommissionId(33L);
        pendingSc.setStatus(CommissionStatus.PENDING);

        when(performedServiceRepository.findByIdForUpdate(88L)).thenReturn(Optional.of(ps));
        when(serviceCommissionRepository.findByPerformedService_PerformedId(88L)).thenReturn(List.of(pendingSc));

        performedServiceService.cancelPerformedService(88L);

        assertEquals(PerformedServiceStatus.CANCELLED, ps.getStatus());
        verify(performedServiceRepository).save(ps);
        verify(performedServiceRepository, never()).delete(any());
    }
}
