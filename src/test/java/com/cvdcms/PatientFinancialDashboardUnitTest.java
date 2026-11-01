package com.cvdcms;

import com.cvdcms.DTO.PatientDebtSummaryDTO;
import com.cvdcms.controller.PaymentPageController;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.entity.enums.InvoiceStatus;
import com.cvdcms.repository.*;
import com.cvdcms.service.DebtService;
import com.cvdcms.service.PaymentService;
import com.cvdcms.service.RefundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class PatientFinancialDashboardUnitTest {

    @Mock
    private PaymentService paymentService;
    @Mock
    private RefundService refundService;
    @Mock
    private DebtService debtService;
    @Mock
    private BranchRepository branchRepository;
    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private PatientProfileRepository patientProfileRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private StaffBranchAssignmentRepository staffBranchAssignmentRepository;

    private PaymentPageController controller;
    private MockHttpSession session;
    private User adminUser;
    private PatientProfile mockPatient;
    private Branch mockBranch;

    @BeforeEach
    void setUp() {
        controller = new PaymentPageController(
                paymentService,
                refundService,
                debtService,
                branchRepository,
                invoiceRepository,
                patientProfileRepository,
                paymentRepository,
                userRepository,
                staffBranchAssignmentRepository
        );

        adminUser = new User();
        adminUser.setUserId(1L);
        adminUser.setUsername("admin");

        session = new MockHttpSession();
        session.setAttribute("loggedInUser", adminUser);

        mockBranch = new Branch();
        mockBranch.setBranchId(10L);
        mockBranch.setBranchName("Chi nhánh Trung tâm");

        mockPatient = new PatientProfile();
        mockPatient.setProfileId(100L);
        mockPatient.setFullName("Trần Văn B");
        mockPatient.setPatientCode("BN100");

        lenient().when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        lenient().when(branchRepository.findAll()).thenReturn(List.of(mockBranch));
        lenient().when(patientProfileRepository.findById(100L)).thenReturn(Optional.of(mockPatient));

        PatientDebtSummaryDTO summary = new PatientDebtSummaryDTO();
        summary.setTotalBilled(BigDecimal.valueOf(10000000));
        summary.setTotalPaid(BigDecimal.valueOf(4000000));
        summary.setTotalDebt(BigDecimal.valueOf(6000000));
        summary.setDepositBalance(BigDecimal.valueOf(2000000));
        summary.setUnpaidInvoicesCount(3);
        summary.setLastInvoiceDate(LocalDateTime.now());
        lenient().when(debtService.getPatientDebtDetail(eq(100L), any())).thenReturn(summary);
    }

    @Test
    @DisplayName("1. Lọc hóa đơn và giao dịch khi không nhập ngày: lấy tất cả theo phân trang")
    void testDashboardWithoutDates() {
        Model model = new ConcurrentModel();

        when(invoiceRepository.findByPatientProfile_ProfileIdAndStatusNotOrderByIssuedAtDesc(100L, InvoiceStatus.CANCELLED))
                .thenReturn(Collections.emptyList());

        Page<Invoice> invoicePage = new PageImpl<>(Collections.emptyList());
        when(invoiceRepository.findInvoicesByPatientAndDates(eq(100L), eq(InvoiceStatus.CANCELLED), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(invoicePage);

        Page<Payment> paymentPage = new PageImpl<>(Collections.emptyList());
        when(paymentRepository.findPaymentsByPatientAndDates(eq(100L), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(paymentPage);

        String view = controller.patientFinancialDashboard(
                null, 100L,
                0, 0,
                null, null,
                null, null,
                model, session
        );

        assertEquals("finance/payments/dashboard", view);
        assertNull(model.getAttribute("invoiceFromDate"));
        assertNull(model.getAttribute("invoiceToDate"));
        assertNull(model.getAttribute("paymentFromDate"));
        assertNull(model.getAttribute("paymentToDate"));
        assertNull(model.getAttribute("invoiceDateError"));
        assertNull(model.getAttribute("paymentDateError"));

        verify(invoiceRepository).findInvoicesByPatientAndDates(eq(100L), eq(InvoiceStatus.CANCELLED), isNull(), isNull(), any(Pageable.class));
        verify(paymentRepository).findPaymentsByPatientAndDates(eq(100L), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    @DisplayName("2. Lọc hóa đơn: Chỉ nhập từ ngày -> issuedAt >= startDate 00:00:00")
    void testInvoiceFilter_OnlyFromDate() {
        Model model = new ConcurrentModel();
        LocalDate fromDate = LocalDate.of(2026, 10, 15);

        when(invoiceRepository.findByPatientProfile_ProfileIdAndStatusNotOrderByIssuedAtDesc(100L, InvoiceStatus.CANCELLED))
                .thenReturn(Collections.emptyList());
        when(invoiceRepository.findInvoicesByPatientAndDates(eq(100L), eq(InvoiceStatus.CANCELLED), eq(fromDate.atStartOfDay()), isNull(), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(paymentRepository.findPaymentsByPatientAndDates(eq(100L), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(Page.empty());

        controller.patientFinancialDashboard(
                null, 100L,
                0, 0,
                fromDate, null,
                null, null,
                model, session
        );

        assertEquals(fromDate, model.getAttribute("invoiceFromDate"));
        assertNull(model.getAttribute("invoiceToDate"));
        assertNull(model.getAttribute("invoiceDateError"));

        verify(invoiceRepository).findInvoicesByPatientAndDates(
                eq(100L),
                eq(InvoiceStatus.CANCELLED),
                eq(LocalDateTime.of(2026, 10, 15, 0, 0, 0)),
                isNull(),
                any(Pageable.class)
        );
    }

    @Test
    @DisplayName("3. Lọc hóa đơn: Chỉ nhập đến ngày -> issuedAt < (toDate + 1) 00:00:00")
    void testInvoiceFilter_OnlyToDate() {
        Model model = new ConcurrentModel();
        LocalDate toDate = LocalDate.of(2026, 10, 20);

        when(invoiceRepository.findByPatientProfile_ProfileIdAndStatusNotOrderByIssuedAtDesc(100L, InvoiceStatus.CANCELLED))
                .thenReturn(Collections.emptyList());
        when(invoiceRepository.findInvoicesByPatientAndDates(eq(100L), eq(InvoiceStatus.CANCELLED), isNull(), eq(toDate.plusDays(1).atStartOfDay()), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(paymentRepository.findPaymentsByPatientAndDates(eq(100L), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(Page.empty());

        controller.patientFinancialDashboard(
                null, 100L,
                0, 0,
                null, toDate,
                null, null,
                model, session
        );

        assertNull(model.getAttribute("invoiceFromDate"));
        assertEquals(toDate, model.getAttribute("invoiceToDate"));
        assertNull(model.getAttribute("invoiceDateError"));

        verify(invoiceRepository).findInvoicesByPatientAndDates(
                eq(100L),
                eq(InvoiceStatus.CANCELLED),
                isNull(),
                eq(LocalDateTime.of(2026, 10, 21, 0, 0, 0)),
                any(Pageable.class)
        );
    }

    @Test
    @DisplayName("4. Lọc hóa đơn: Nhập cả Từ ngày và Đến ngày hợp lệ")
    void testInvoiceFilter_BothDates() {
        Model model = new ConcurrentModel();
        LocalDate fromDate = LocalDate.of(2026, 10, 10);
        LocalDate toDate = LocalDate.of(2026, 10, 20);

        when(invoiceRepository.findByPatientProfile_ProfileIdAndStatusNotOrderByIssuedAtDesc(100L, InvoiceStatus.CANCELLED))
                .thenReturn(Collections.emptyList());
        when(invoiceRepository.findInvoicesByPatientAndDates(eq(100L), eq(InvoiceStatus.CANCELLED), eq(fromDate.atStartOfDay()), eq(toDate.plusDays(1).atStartOfDay()), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(paymentRepository.findPaymentsByPatientAndDates(eq(100L), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(Page.empty());

        controller.patientFinancialDashboard(
                null, 100L,
                0, 0,
                fromDate, toDate,
                null, null,
                model, session
        );

        verify(invoiceRepository).findInvoicesByPatientAndDates(
                eq(100L),
                eq(InvoiceStatus.CANCELLED),
                eq(LocalDateTime.of(2026, 10, 10, 0, 0, 0)),
                eq(LocalDateTime.of(2026, 10, 21, 0, 0, 0)),
                any(Pageable.class)
        );
    }

    @Test
    @DisplayName("5. Lọc hóa đơn: Cùng một ngày (fromDate == toDate)")
    void testInvoiceFilter_SameDate() {
        Model model = new ConcurrentModel();
        LocalDate sameDate = LocalDate.of(2026, 10, 28);

        when(invoiceRepository.findByPatientProfile_ProfileIdAndStatusNotOrderByIssuedAtDesc(100L, InvoiceStatus.CANCELLED))
                .thenReturn(Collections.emptyList());
        when(invoiceRepository.findInvoicesByPatientAndDates(eq(100L), eq(InvoiceStatus.CANCELLED), eq(sameDate.atStartOfDay()), eq(sameDate.plusDays(1).atStartOfDay()), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(paymentRepository.findPaymentsByPatientAndDates(eq(100L), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(Page.empty());

        controller.patientFinancialDashboard(
                null, 100L,
                0, 0,
                sameDate, sameDate,
                null, null,
                model, session
        );

        // Khoảng [2026-10-28 00:00:00, 2026-10-29 00:00:00) bao trọn toàn bộ ngày 28/10
        verify(invoiceRepository).findInvoicesByPatientAndDates(
                eq(100L),
                eq(InvoiceStatus.CANCELLED),
                eq(LocalDateTime.of(2026, 10, 28, 0, 0, 0)),
                eq(LocalDateTime.of(2026, 10, 29, 0, 0, 0)),
                any(Pageable.class)
        );
    }

    @Test
    @DisplayName("6. Lọc hóa đơn: Từ ngày > Đến ngày -> Báo lỗi, giữ giá trị nhập, trả về trang rỗng")
    void testInvoiceFilter_InvalidDateRange() {
        Model model = new ConcurrentModel();
        LocalDate fromDate = LocalDate.of(2026, 10, 25);
        LocalDate toDate = LocalDate.of(2026, 10, 20);

        when(invoiceRepository.findByPatientProfile_ProfileIdAndStatusNotOrderByIssuedAtDesc(100L, InvoiceStatus.CANCELLED))
                .thenReturn(Collections.emptyList());
        when(paymentRepository.findPaymentsByPatientAndDates(eq(100L), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(Page.empty());

        controller.patientFinancialDashboard(
                null, 100L,
                0, 0,
                fromDate, toDate,
                null, null,
                model, session
        );

        // Giữ dữ liệu người dùng nhập
        assertEquals(fromDate, model.getAttribute("invoiceFromDate"));
        assertEquals(toDate, model.getAttribute("invoiceToDate"));

        // Hiển thị thông báo lỗi rõ ràng
        assertNotNull(model.getAttribute("invoiceDateError"));
        assertTrue(model.getAttribute("invoiceDateError").toString().contains("không được lớn hơn"));

        // Không gọi query ngày không hợp lệ, trả trang rỗng
        verify(invoiceRepository, never()).findInvoicesByPatientAndDates(any(), any(), any(), any(), any());
        Page<?> invoicePage = (Page<?>) model.getAttribute("invoicePage");
        assertNotNull(invoicePage);
        assertTrue(invoicePage.isEmpty());
    }

    @Test
    @DisplayName("7. Lọc giao dịch thanh toán: Nhập Từ ngày và Đến ngày")
    void testPaymentFilter_ValidDates() {
        Model model = new ConcurrentModel();
        LocalDate fromDate = LocalDate.of(2026, 10, 1);
        LocalDate toDate = LocalDate.of(2026, 10, 15);

        when(invoiceRepository.findByPatientProfile_ProfileIdAndStatusNotOrderByIssuedAtDesc(100L, InvoiceStatus.CANCELLED))
                .thenReturn(Collections.emptyList());
        when(invoiceRepository.findInvoicesByPatientAndDates(eq(100L), eq(InvoiceStatus.CANCELLED), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(paymentRepository.findPaymentsByPatientAndDates(eq(100L), eq(fromDate.atStartOfDay()), eq(toDate.plusDays(1).atStartOfDay()), any(Pageable.class)))
                .thenReturn(Page.empty());

        controller.patientFinancialDashboard(
                null, 100L,
                0, 0,
                null, null,
                fromDate, toDate,
                model, session
        );

        assertEquals(fromDate, model.getAttribute("paymentFromDate"));
        assertEquals(toDate, model.getAttribute("paymentToDate"));
        assertNull(model.getAttribute("paymentDateError"));

        verify(paymentRepository).findPaymentsByPatientAndDates(
                eq(100L),
                eq(LocalDateTime.of(2026, 10, 1, 0, 0, 0)),
                eq(LocalDateTime.of(2026, 10, 16, 0, 0, 0)),
                any(Pageable.class)
        );
    }

    @Test
    @DisplayName("8. Lọc giao dịch thanh toán: Từ ngày > Đến ngày -> Báo lỗi, giữ giá trị nhập")
    void testPaymentFilter_InvalidDateRange() {
        Model model = new ConcurrentModel();
        LocalDate fromDate = LocalDate.of(2026, 10, 20);
        LocalDate toDate = LocalDate.of(2026, 10, 10);

        when(invoiceRepository.findByPatientProfile_ProfileIdAndStatusNotOrderByIssuedAtDesc(100L, InvoiceStatus.CANCELLED))
                .thenReturn(Collections.emptyList());
        when(invoiceRepository.findInvoicesByPatientAndDates(eq(100L), eq(InvoiceStatus.CANCELLED), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(Page.empty());

        controller.patientFinancialDashboard(
                null, 100L,
                0, 0,
                null, null,
                fromDate, toDate,
                model, session
        );

        assertEquals(fromDate, model.getAttribute("paymentFromDate"));
        assertEquals(toDate, model.getAttribute("paymentToDate"));
        assertNotNull(model.getAttribute("paymentDateError"));
        assertTrue(model.getAttribute("paymentDateError").toString().contains("không được lớn hơn"));

        verify(paymentRepository, never()).findPaymentsByPatientAndDates(any(), any(), any(), any());
        Page<?> paymentPage = (Page<?>) model.getAttribute("paymentPage");
        assertNotNull(paymentPage);
        assertTrue(paymentPage.isEmpty());
    }

    @Test
    @DisplayName("9. Hai bộ lọc hoạt động độc lập và giữ phân trang / tham số của nhau")
    void testIndependentFiltersAndPagination() {
        Model model = new ConcurrentModel();
        LocalDate invFrom = LocalDate.of(2026, 10, 5);
        LocalDate invTo = LocalDate.of(2026, 10, 12);
        LocalDate payFrom = LocalDate.of(2026, 10, 1);
        LocalDate payTo = LocalDate.of(2026, 10, 15);

        when(invoiceRepository.findByPatientProfile_ProfileIdAndStatusNotOrderByIssuedAtDesc(100L, InvoiceStatus.CANCELLED))
                .thenReturn(Collections.emptyList());
        when(invoiceRepository.findInvoicesByPatientAndDates(eq(100L), eq(InvoiceStatus.CANCELLED), eq(invFrom.atStartOfDay()), eq(invTo.plusDays(1).atStartOfDay()), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(paymentRepository.findPaymentsByPatientAndDates(eq(100L), eq(payFrom.atStartOfDay()), eq(payTo.plusDays(1).atStartOfDay()), any(Pageable.class)))
                .thenReturn(Page.empty());

        controller.patientFinancialDashboard(
                null, 100L,
                2, 1,
                invFrom, invTo,
                payFrom, payTo,
                model, session
        );

        assertEquals(invFrom, model.getAttribute("invoiceFromDate"));
        assertEquals(invTo, model.getAttribute("invoiceToDate"));
        assertEquals(payFrom, model.getAttribute("paymentFromDate"));
        assertEquals(payTo, model.getAttribute("paymentToDate"));

        ArgumentCaptor<Pageable> invPageCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(invoiceRepository).findInvoicesByPatientAndDates(any(), any(), any(), any(), invPageCaptor.capture());
        assertEquals(1, invPageCaptor.getValue().getPageNumber());
        assertEquals(10, invPageCaptor.getValue().getPageSize());

        ArgumentCaptor<Pageable> payPageCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(paymentRepository).findPaymentsByPatientAndDates(any(), any(), any(), payPageCaptor.capture());
        assertEquals(2, payPageCaptor.getValue().getPageNumber());
        assertEquals(10, payPageCaptor.getValue().getPageSize());
    }

    @Test
    @DisplayName("10. Nhân viên giới hạn chi nhánh (Non-Admin): lọc theo chi nhánh được phép")
    void testBranchRestrictedUser() {
        Model model = new ConcurrentModel();
        User staffUser = new User();
        staffUser.setUserId(2L);
        session.setAttribute("loggedInUser", staffUser);

        when(userRepository.hasActiveAdminRole(2L)).thenReturn(false);
        when(staffBranchAssignmentRepository.findActiveBranchIdsByUserId(2L, AssignmentStatus.ACTIVE))
                .thenReturn(List.of(10L));
        when(branchRepository.findAllById(List.of(10L))).thenReturn(List.of(mockBranch));

        when(invoiceRepository.findInvoicesByPatientAndBranches(100L, List.of(10L)))
                .thenReturn(Collections.emptyList());
        when(invoiceRepository.findInvoicesByPatientAndBranchesAndDates(eq(100L), eq(InvoiceStatus.CANCELLED), eq(List.of(10L)), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(paymentRepository.findPaymentsByPatientAndBranchesAndDates(eq(100L), eq(List.of(10L)), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(Page.empty());

        controller.patientFinancialDashboard(
                null, 100L,
                0, 0,
                null, null,
                null, null,
                model, session
        );

        verify(invoiceRepository).findInvoicesByPatientAndBranchesAndDates(
                eq(100L),
                eq(InvoiceStatus.CANCELLED),
                eq(List.of(10L)),
                isNull(),
                isNull(),
                any(Pageable.class)
        );
        verify(paymentRepository).findPaymentsByPatientAndBranchesAndDates(
                eq(100L),
                eq(List.of(10L)),
                isNull(),
                isNull(),
                any(Pageable.class)
        );
    }

    @Test
    @DisplayName("11. Vào dashboard không truyền ID bệnh nhân: Trả về trang dashboard mà không tự động chọn bệnh nhân ngẫu nhiên")
    void testDashboard_WhenNoPatientSpecified_ReturnsDashboardWithNullPatient() {
        Model model = new ConcurrentModel();

        String view = controller.patientFinancialDashboard(
                null, null,
                0, 0,
                null, null,
                null, null,
                model, session
        );

        assertEquals("finance/payments/dashboard", view);
        assertNull(model.getAttribute("patient"));
        assertNotNull(model.getAttribute("branches"));
    }
}
