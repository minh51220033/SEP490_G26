package com.cvdcms;

import com.cvdcms.DTO.CashierPendingPatientDTO;
import com.cvdcms.DTO.PrescriptionItemResponseDTO;
import com.cvdcms.DTO.PrescriptionResponseDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.InvoicePaymentStatus;
import com.cvdcms.entity.enums.InvoiceStatus;
import com.cvdcms.entity.enums.PerformedServiceStatus;
import com.cvdcms.entity.enums.PrescriptionStatus;
import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.repository.InvoiceRepository;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.repository.PrescriptionRepository;
import com.cvdcms.repository.VisitRepository;
import com.cvdcms.service.InvoiceService;
import com.cvdcms.service.impl.CashierOverviewServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class CashierOverviewUnitTest {

    @Mock
    private PatientProfileRepository patientProfileRepository;

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private VisitRepository visitRepository;

    @Mock
    private PrescriptionRepository prescriptionRepository;

    @Mock
    private InvoiceService invoiceService;

    private CashierOverviewServiceImpl cashierOverviewService;

    private Branch branch1;
    private Branch branch2;

    @BeforeEach
    void setUp() {
        cashierOverviewService = new CashierOverviewServiceImpl(
                patientProfileRepository,
                invoiceRepository,
                visitRepository,
                prescriptionRepository,
                invoiceService
        );

        branch1 = new Branch();
        branch1.setBranchId(1L);
        branch1.setBranchName("Chi nhánh Quận 1");

        branch2 = new Branch();
        branch2.setBranchId(2L);
        branch2.setBranchName("Chi nhánh Quận 3");
    }

    private PatientProfile createPatient(Long id, String code, String name, String phone, BigDecimal deposit) {
        PatientProfile p = new PatientProfile();
        p.setProfileId(id);
        p.setPatientCode(code);
        p.setFullName(name);
        p.setPhoneNumber(phone);
        p.setDepositBalance(deposit != null ? deposit : BigDecimal.ZERO);
        p.setCreatedAt(LocalDateTime.now().minusDays(5));
        return p;
    }

    private Invoice createInvoice(Long id, PatientProfile p, Branch b, BigDecimal total, BigDecimal paid, BigDecimal debt) {
        Invoice inv = new Invoice();
        inv.setInvoiceId(id);
        inv.setInvoiceCode("HD-" + id);
        inv.setPatientProfile(p);
        inv.setBranch(b);
        inv.setTotalAmount(total);
        inv.setPaidAmount(paid);
        inv.setRemainingDebt(debt);
        inv.setStatus(InvoiceStatus.CONFIRMED);
        inv.setPaymentStatus(debt.compareTo(BigDecimal.ZERO) == 0 ? InvoicePaymentStatus.PAID : (paid.compareTo(BigDecimal.ZERO) > 0 ? InvoicePaymentStatus.PARTIAL : InvoicePaymentStatus.UNPAID));
        inv.setIssuedAt(LocalDateTime.now().minusHours(2));
        return inv;
    }

    private Visit createVisit(Long id, PatientProfile p, Branch b, VisitStatus status) {
        Visit v = new Visit();
        v.setVisitId(id);
        v.setVisitCode("VK-" + id);
        v.setPatientProfile(p);
        v.setBranch(b);
        v.setStatus(status);
        v.setCheckInAt(LocalDateTime.now().minusHours(1));
        return v;
    }

    @Test
    @DisplayName("1. Bệnh nhân chỉ có ca khám chờ lập hóa đơn -> Trạng thái Chờ lập hóa đơn, có nút Lập HĐ")
    void testPatientWithOnlyUnbilledItems_StatusPendingInvoice() {
        PatientProfile p1 = createPatient(101L, "BN101", "Trần Văn A", "0901000101", BigDecimal.ZERO);
        Visit v1 = createVisit(201L, p1, branch1, VisitStatus.WAITING_PAYMENT);

        when(invoiceRepository.findPatientProfileIdsWithDebtInBranches(any())).thenReturn(Collections.emptyList());
        when(visitRepository.findPatientProfileIdsByStatusAndBranches(eq(VisitStatus.WAITING_PAYMENT), any())).thenReturn(List.of(101L));
        when(prescriptionRepository.findPatientProfileIdsByStatusNotInAndBranches(any(), any())).thenReturn(Collections.emptyList());
        when(patientProfileRepository.findAllById(any())).thenReturn(List.of(p1));

        when(invoiceRepository.findByPatientProfile_ProfileIdAndStatusNotOrderByIssuedAtDesc(eq(101L), eq(InvoiceStatus.CANCELLED)))
                .thenReturn(Collections.emptyList());
        when(visitRepository.findWaitingVisitsByProfileIdAndBranches(eq(101L), eq(VisitStatus.WAITING_PAYMENT), any()))
                .thenReturn(List.of(v1));

        PerformedService ps = new PerformedService();
        ps.setPerformedId(301L);
        ps.setStatus(PerformedServiceStatus.COMPLETED);
        when(invoiceService.getUnbilledPerformedServices(201L)).thenReturn(List.of(ps));
        when(invoiceService.getHandedOverPrescriptions(any(), any(), any())).thenReturn(Collections.emptyList());

        Page<CashierPendingPatientDTO> page = cashierOverviewService.getPendingCashierPatients(null, null, PageRequest.of(0, 10));

        assertEquals(1, page.getTotalElements());
        CashierPendingPatientDTO dto = page.getContent().get(0);
        assertEquals(101L, dto.getProfileId());
        assertTrue(dto.isHasUnbilledItems());
        assertFalse(dto.isHasUnpaidInvoices());
        assertEquals("PENDING_INVOICE", dto.getProcessingStatus());
        assertEquals("Chờ lập hóa đơn", dto.getStatusDisplay());
        assertEquals(1, dto.getUnbilledVisitCount());
        assertEquals(0, dto.getUnpaidInvoiceCount());
        assertEquals("Chi nhánh Quận 1", dto.getBranchNames());
    }

    @Test
    @DisplayName("2. Bệnh nhân chỉ có hóa đơn còn nợ -> Trạng thái Chờ thu tiền, có nút Thu tiền")
    void testPatientWithOnlyDebt_StatusPendingPayment() {
        PatientProfile p2 = createPatient(102L, "BN102", "Lê Thị B", "0901000102", BigDecimal.valueOf(100000));
        Invoice inv = createInvoice(401L, p2, branch1, BigDecimal.valueOf(500000), BigDecimal.valueOf(200000), BigDecimal.valueOf(300000));

        when(invoiceRepository.findPatientProfileIdsWithDebtInBranches(any())).thenReturn(List.of(102L));
        when(visitRepository.findPatientProfileIdsByStatusAndBranches(eq(VisitStatus.WAITING_PAYMENT), any())).thenReturn(Collections.emptyList());
        when(prescriptionRepository.findPatientProfileIdsByStatusNotInAndBranches(any(), any())).thenReturn(Collections.emptyList());
        when(patientProfileRepository.findAllById(any())).thenReturn(List.of(p2));

        when(invoiceRepository.findByPatientProfile_ProfileIdAndStatusNotOrderByIssuedAtDesc(eq(102L), eq(InvoiceStatus.CANCELLED)))
                .thenReturn(List.of(inv));
        when(visitRepository.findWaitingVisitsByProfileIdAndBranches(eq(102L), eq(VisitStatus.WAITING_PAYMENT), any()))
                .thenReturn(Collections.emptyList());
        when(invoiceService.getHandedOverPrescriptions(any(), any(), any())).thenReturn(Collections.emptyList());

        Page<CashierPendingPatientDTO> page = cashierOverviewService.getPendingCashierPatients(null, null, PageRequest.of(0, 10));

        assertEquals(1, page.getTotalElements());
        CashierPendingPatientDTO dto = page.getContent().get(0);
        assertEquals(102L, dto.getProfileId());
        assertFalse(dto.isHasUnbilledItems());
        assertTrue(dto.isHasUnpaidInvoices());
        assertEquals("PENDING_PAYMENT", dto.getProcessingStatus());
        assertEquals("Chờ thu tiền", dto.getStatusDisplay());
        assertEquals(0, dto.getUnbilledVisitCount());
        assertEquals(1, dto.getUnpaidInvoiceCount());
        assertEquals(BigDecimal.valueOf(300000), dto.getRemainingDebt());
        assertEquals(BigDecimal.valueOf(100000), dto.getDepositBalance());
    }

    @Test
    @DisplayName("3. Bệnh nhân có cả ca chờ lập HĐ và hóa đơn còn nợ -> Trạng thái Chờ lập HĐ & Thu tiền")
    void testPatientWithBoth_StatusPendingBoth() {
        PatientProfile p3 = createPatient(103L, "BN103", "Phạm Văn C", "0901000103", BigDecimal.ZERO);
        Visit v1 = createVisit(202L, p3, branch1, VisitStatus.WAITING_PAYMENT);
        Invoice inv = createInvoice(402L, p3, branch2, BigDecimal.valueOf(1000000), BigDecimal.ZERO, BigDecimal.valueOf(1000000));

        when(invoiceRepository.findPatientProfileIdsWithDebtInBranches(any())).thenReturn(List.of(103L));
        when(visitRepository.findPatientProfileIdsByStatusAndBranches(eq(VisitStatus.WAITING_PAYMENT), any())).thenReturn(List.of(103L));
        when(prescriptionRepository.findPatientProfileIdsByStatusNotInAndBranches(any(), any())).thenReturn(Collections.emptyList());
        when(patientProfileRepository.findAllById(any())).thenReturn(List.of(p3));

        when(invoiceRepository.findByPatientProfile_ProfileIdAndStatusNotOrderByIssuedAtDesc(eq(103L), eq(InvoiceStatus.CANCELLED)))
                .thenReturn(List.of(inv));
        when(visitRepository.findWaitingVisitsByProfileIdAndBranches(eq(103L), eq(VisitStatus.WAITING_PAYMENT), any()))
                .thenReturn(List.of(v1));

        PerformedService ps = new PerformedService();
        ps.setPerformedId(302L);
        ps.setStatus(PerformedServiceStatus.COMPLETED);
        when(invoiceService.getUnbilledPerformedServices(202L)).thenReturn(List.of(ps));
        when(invoiceService.getHandedOverPrescriptions(any(), any(), any())).thenReturn(Collections.emptyList());

        Page<CashierPendingPatientDTO> page = cashierOverviewService.getPendingCashierPatients(null, null, PageRequest.of(0, 10));

        assertEquals(1, page.getTotalElements());
        CashierPendingPatientDTO dto = page.getContent().get(0);
        assertTrue(dto.isHasUnbilledItems());
        assertTrue(dto.isHasUnpaidInvoices());
        assertEquals("PENDING_BOTH", dto.getProcessingStatus());
        assertEquals("Chờ lập HĐ & Thu tiền", dto.getStatusDisplay());
        // Cơ sở liên quan: cả 2 chi nhánh
        assertTrue(dto.getBranchNames().contains("Chi nhánh Quận 1"));
        assertTrue(dto.getBranchNames().contains("Chi nhánh Quận 3"));
    }

    @Test
    @DisplayName("4. Bệnh nhân đã thanh toán hết nợ & không có ca chờ lập -> Ẩn ở danh sách mặc định, hiển thị khi tìm kiếm")
    void testFullySettledPatient_HiddenByDefault_VisibleOnSearch() {
        PatientProfile p4 = createPatient(104L, "BN104", "Hoàng Thị D", "0901000104", BigDecimal.ZERO);
        Invoice settledInv = createInvoice(403L, p4, branch1, BigDecimal.valueOf(800000), BigDecimal.valueOf(800000), BigDecimal.ZERO);

        // Mặc định (keyword = null): không nằm trong candidate IDs
        when(invoiceRepository.findPatientProfileIdsWithDebtInBranches(any())).thenReturn(Collections.emptyList());
        when(visitRepository.findPatientProfileIdsByStatusAndBranches(any(), any())).thenReturn(Collections.emptyList());
        when(prescriptionRepository.findPatientProfileIdsByStatusNotInAndBranches(any(), any())).thenReturn(Collections.emptyList());

        Page<CashierPendingPatientDTO> defaultPage = cashierOverviewService.getPendingCashierPatients(null, null, PageRequest.of(0, 10));
        assertEquals(0, defaultPage.getTotalElements());

        // Khi tìm kiếm theo keyword "BN104"
        when(patientProfileRepository.searchPatients("BN104")).thenReturn(List.of(p4));
        when(invoiceRepository.findByPatientProfile_ProfileIdAndStatusNotOrderByIssuedAtDesc(eq(104L), eq(InvoiceStatus.CANCELLED)))
                .thenReturn(List.of(settledInv));
        when(visitRepository.findWaitingVisitsByProfileIdAndBranches(eq(104L), eq(VisitStatus.WAITING_PAYMENT), any()))
                .thenReturn(Collections.emptyList());
        when(invoiceService.getHandedOverPrescriptions(any(), any(), any())).thenReturn(Collections.emptyList());

        Page<CashierPendingPatientDTO> searchPage = cashierOverviewService.getPendingCashierPatients("BN104", null, PageRequest.of(0, 10));
        assertEquals(1, searchPage.getTotalElements());
        CashierPendingPatientDTO searchedDTO = searchPage.getContent().get(0);
        assertEquals("SETTLED", searchedDTO.getProcessingStatus());
        assertEquals("Không có khoản chờ xử lý", searchedDTO.getStatusDisplay());
        assertEquals(BigDecimal.ZERO, searchedDTO.getRemainingDebt());
        assertEquals(BigDecimal.valueOf(800000), searchedDTO.getTotalBilled());
    }

    @Test
    @DisplayName("5. Bệnh nhân mới chưa có hóa đơn & không có ca chờ lập -> Hiển thị 'Chưa có hóa đơn' khi tìm kiếm")
    void testPatientWithNoInvoice_VisibleOnSearch() {
        PatientProfile p5 = createPatient(105L, "BN105", "Đỗ Văn E", "0901000105", BigDecimal.ZERO);

        when(patientProfileRepository.searchPatients("0901000105")).thenReturn(List.of(p5));
        when(invoiceRepository.findByPatientProfile_ProfileIdAndStatusNotOrderByIssuedAtDesc(eq(105L), eq(InvoiceStatus.CANCELLED)))
                .thenReturn(Collections.emptyList());
        when(visitRepository.findWaitingVisitsByProfileIdAndBranches(eq(105L), eq(VisitStatus.WAITING_PAYMENT), any()))
                .thenReturn(Collections.emptyList());
        when(invoiceService.getHandedOverPrescriptions(any(), any(), any())).thenReturn(Collections.emptyList());

        Page<CashierPendingPatientDTO> searchPage = cashierOverviewService.getPendingCashierPatients("0901000105", null, PageRequest.of(0, 10));
        assertEquals(1, searchPage.getTotalElements());
        CashierPendingPatientDTO dto = searchPage.getContent().get(0);
        assertEquals("NO_INVOICE", dto.getProcessingStatus());
        assertEquals("Chưa có hóa đơn", dto.getStatusDisplay());
        assertEquals(BigDecimal.ZERO, dto.getTotalBilled());
        assertEquals(BigDecimal.ZERO, dto.getRemainingDebt());
    }

    @Test
    @DisplayName("6. Chống trùng lặp: Bệnh nhân có 2 ca khám và 2 hóa đơn còn nợ -> Chỉ hiển thị 1 dòng duy nhất, đủ danh sách con")
    void testDeduplication_MultipleVisitsAndInvoices_OnlyOneRowPerPatient() {
        PatientProfile p6 = createPatient(106L, "BN106", "Vũ Văn F", "0901000106", BigDecimal.ZERO);
        Visit v1 = createVisit(203L, p6, branch1, VisitStatus.WAITING_PAYMENT);
        Visit v2 = createVisit(204L, p6, branch1, VisitStatus.WAITING_PAYMENT);

        Invoice inv1 = createInvoice(404L, p6, branch1, BigDecimal.valueOf(400000), BigDecimal.ZERO, BigDecimal.valueOf(400000));
        Invoice inv2 = createInvoice(405L, p6, branch1, BigDecimal.valueOf(600000), BigDecimal.valueOf(100000), BigDecimal.valueOf(500000));

        when(invoiceRepository.findPatientProfileIdsWithDebtInBranches(any())).thenReturn(List.of(106L, 106L));
        when(visitRepository.findPatientProfileIdsByStatusAndBranches(eq(VisitStatus.WAITING_PAYMENT), any())).thenReturn(List.of(106L));
        when(prescriptionRepository.findPatientProfileIdsByStatusNotInAndBranches(any(), any())).thenReturn(Collections.emptyList());
        when(patientProfileRepository.findAllById(any())).thenReturn(List.of(p6));

        when(invoiceRepository.findByPatientProfile_ProfileIdAndStatusNotOrderByIssuedAtDesc(eq(106L), eq(InvoiceStatus.CANCELLED)))
                .thenReturn(List.of(inv1, inv2));
        when(visitRepository.findWaitingVisitsByProfileIdAndBranches(eq(106L), eq(VisitStatus.WAITING_PAYMENT), any()))
                .thenReturn(List.of(v1, v2));

        PerformedService ps = new PerformedService();
        ps.setPerformedId(303L);
        ps.setStatus(PerformedServiceStatus.COMPLETED);
        when(invoiceService.getUnbilledPerformedServices(anyLong())).thenReturn(List.of(ps));
        when(invoiceService.getHandedOverPrescriptions(any(), any(), any())).thenReturn(Collections.emptyList());

        Page<CashierPendingPatientDTO> page = cashierOverviewService.getPendingCashierPatients(null, null, PageRequest.of(0, 10));

        assertEquals(1, page.getTotalElements(), "Mỗi bệnh nhân chỉ được xuất hiện đúng 1 dòng");
        CashierPendingPatientDTO dto = page.getContent().get(0);
        assertEquals(2, dto.getUnbilledVisitCount());
        assertEquals(2, dto.getPendingVisits().size());
        assertEquals(2, dto.getUnpaidInvoiceCount());
        assertEquals(2, dto.getUnpaidInvoices().size());
        assertEquals(BigDecimal.valueOf(900000), dto.getRemainingDebt());
    }

    @Test
    @DisplayName("7. Kế hoạch dự kiến hoặc thuốc tự mua ngoài không được tính là khoản chờ lập hóa đơn")
    void testTreatmentPlanAndExternalPrescription_NotCountedAsUnbilled() {
        PatientProfile p7 = createPatient(107L, "BN107", "Ngô Văn G", "0901000107", BigDecimal.ZERO);
        Visit v1 = createVisit(205L, p7, branch1, VisitStatus.WAITING_PAYMENT);

        when(invoiceRepository.findPatientProfileIdsWithDebtInBranches(any())).thenReturn(Collections.emptyList());
        when(visitRepository.findPatientProfileIdsByStatusAndBranches(eq(VisitStatus.WAITING_PAYMENT), any())).thenReturn(List.of(107L));
        when(prescriptionRepository.findPatientProfileIdsByStatusNotInAndBranches(any(), any())).thenReturn(Collections.emptyList());
        when(patientProfileRepository.findAllById(any())).thenReturn(List.of(p7));

        when(invoiceRepository.findByPatientProfile_ProfileIdAndStatusNotOrderByIssuedAtDesc(eq(107L), eq(InvoiceStatus.CANCELLED)))
                .thenReturn(Collections.emptyList());
        when(visitRepository.findWaitingVisitsByProfileIdAndBranches(eq(107L), eq(VisitStatus.WAITING_PAYMENT), any()))
                .thenReturn(List.of(v1));

        // Dịch vụ chưa hoàn thành hoặc thuốc tự mua ngoài
        when(invoiceService.getUnbilledPerformedServices(205L)).thenReturn(Collections.emptyList());

        PrescriptionResponseDTO externalPresc = PrescriptionResponseDTO.builder()
                .prescriptionId(501L)
                .status("EXTERNAL_PURCHASE")
                .items(List.of(PrescriptionItemResponseDTO.builder().remainingQuantity(BigDecimal.valueOf(2)).build()))
                .build();
        when(invoiceService.getHandedOverPrescriptions(any(), any(), any())).thenReturn(List.of(externalPresc));

        Page<CashierPendingPatientDTO> page = cashierOverviewService.getPendingCashierPatients(null, null, PageRequest.of(0, 10));

        // Vì không có khoản chờ lập hợp lệ và không có nợ -> Ẩn khỏi danh sách mặc định
        assertEquals(0, page.getTotalElements());
    }

    @Test
    @DisplayName("8. Phân quyền chi nhánh: Kế toán chỉ xem cơ sở được phân công, không bị mở quyền xuyên cơ sở")
    void testBranchScoping_AccountantVsAdmin() {
        PatientProfile p8 = createPatient(108L, "BN108", "Mai Văn H", "0901000108", BigDecimal.ZERO);
        Invoice invBranch1 = createInvoice(406L, p8, branch1, BigDecimal.valueOf(300000), BigDecimal.ZERO, BigDecimal.valueOf(300000));

        // Phân quyền chi nhánh 1
        when(invoiceRepository.findPatientProfileIdsWithDebtInBranches(eq(List.of(1L)))).thenReturn(List.of(108L));
        when(visitRepository.findPatientProfileIdsByStatusAndBranches(any(), eq(List.of(1L)))).thenReturn(Collections.emptyList());
        when(prescriptionRepository.findPatientProfileIdsByStatusNotInAndBranches(any(), eq(List.of(1L)))).thenReturn(Collections.emptyList());
        when(patientProfileRepository.findAllById(any())).thenReturn(List.of(p8));

        when(invoiceRepository.findInvoicesByPatientAndBranches(eq(108L), eq(List.of(1L)))).thenReturn(List.of(invBranch1));
        when(visitRepository.findWaitingVisitsByProfileIdAndBranches(eq(108L), eq(VisitStatus.WAITING_PAYMENT), eq(List.of(1L))))
                .thenReturn(Collections.emptyList());
        when(invoiceService.getHandedOverPrescriptions(any(), any(), any())).thenReturn(Collections.emptyList());

        Page<CashierPendingPatientDTO> page = cashierOverviewService.getPendingCashierPatients(null, List.of(1L), PageRequest.of(0, 10));

        assertEquals(1, page.getTotalElements());
        assertEquals("Chi nhánh Quận 1", page.getContent().get(0).getBranchNames());

        // Kế toán không có chi nhánh nào được phân công -> trả về rỗng ngay
        Page<CashierPendingPatientDTO> emptyPage = cashierOverviewService.getPendingCashierPatients(null, Collections.emptyList(), PageRequest.of(0, 10));
        assertEquals(0, emptyPage.getTotalElements());
    }

    @Test
    @DisplayName("9. Phân trang 10 bệnh nhân / trang")
    void testPagination_TenPatientsPerPage() {
        List<Long> ids = new ArrayList<>();
        List<PatientProfile> profiles = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            long pid = 200L + i;
            ids.add(pid);
            profiles.add(createPatient(pid, "BN" + pid, "Bệnh nhân " + i, "09000000" + i, BigDecimal.ZERO));
        }

        when(invoiceRepository.findPatientProfileIdsWithDebtInBranches(any())).thenReturn(ids);
        when(visitRepository.findPatientProfileIdsByStatusAndBranches(any(), any())).thenReturn(Collections.emptyList());
        when(prescriptionRepository.findPatientProfileIdsByStatusNotInAndBranches(any(), any())).thenReturn(Collections.emptyList());
        when(patientProfileRepository.findAllById(any())).thenReturn(profiles);

        for (PatientProfile p : profiles) {
            Invoice inv = createInvoice(1000L + p.getProfileId(), p, branch1, BigDecimal.valueOf(100000), BigDecimal.ZERO, BigDecimal.valueOf(100000));
            when(invoiceRepository.findByPatientProfile_ProfileIdAndStatusNotOrderByIssuedAtDesc(eq(p.getProfileId()), eq(InvoiceStatus.CANCELLED)))
                    .thenReturn(List.of(inv));
            when(visitRepository.findWaitingVisitsByProfileIdAndBranches(eq(p.getProfileId()), any(), any())).thenReturn(Collections.emptyList());
        }

        // Trang 0: 10 phần tử
        Page<CashierPendingPatientDTO> page0 = cashierOverviewService.getPendingCashierPatients(null, null, PageRequest.of(0, 10));
        assertEquals(10, page0.getContent().size());
        assertEquals(15, page0.getTotalElements());
        assertEquals(2, page0.getTotalPages());

        // Trang 1: 5 phần tử
        Page<CashierPendingPatientDTO> page1 = cashierOverviewService.getPendingCashierPatients(null, null, PageRequest.of(1, 10));
        assertEquals(5, page1.getContent().size());
    }
}
