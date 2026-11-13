package com.cvdcms;

import com.cvdcms.DTO.PatientDebtSummaryDTO;
import com.cvdcms.DTO.report.*;
import com.cvdcms.controller.AdminReportController;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.entity.enums.PaymentMethod;
import com.cvdcms.repository.*;
import com.cvdcms.service.impl.ReportServiceImpl;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ReportAndExcelExportUnitTest {

    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private RefundRepository refundRepository;
    @Mock
    private OperatingExpenseRepository operatingExpenseRepository;
    @Mock
    private VisitRepository visitRepository;
    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private BranchRepository branchRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private StaffBranchAssignmentRepository staffBranchAssignmentRepository;

    private ReportServiceImpl reportService;
    private AdminReportController reportController;

    private Branch branch1;
    private Branch branch2;
    private User adminUser;
    private User accountantUser;

    @BeforeEach
    void setUp() {
        reportService = new ReportServiceImpl(
                invoiceRepository,
                paymentRepository,
                refundRepository,
                operatingExpenseRepository,
                visitRepository,
                appointmentRepository
        );

        reportController = new AdminReportController(
                reportService,
                branchRepository,
                userRepository,
                staffBranchAssignmentRepository
        );

        branch1 = new Branch();
        branch1.setBranchId(1L);
        branch1.setBranchName("Chi nhánh Quận 1");
        branch1.setIsActive(true);

        branch2 = new Branch();
        branch2.setBranchId(2L);
        branch2.setBranchName("Chi nhánh Cầu Giấy");
        branch2.setIsActive(true);

        adminUser = new User();
        adminUser.setUserId(100L);
        adminUser.setUsername("admin");

        accountantUser = new User();
        accountantUser.setUserId(200L);
        accountantUser.setUsername("accountant");
    }

    // =========================================================================
    // 1. KIỂM THỬ BẢO MẬT & PHÂN QUYỀN TRUY CẬP CHI NHÁNH (ADMIN vs ACCOUNTANT)
    // =========================================================================

    @Test
    @DisplayName("Admin có quyền xem toàn hệ thống (tất cả chi nhánh)")
    void testAdminAccessAllBranches_Allowed() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", adminUser);
        session.setAttribute("isAdmin", true);

        when(userRepository.hasActiveAdminRole(100L)).thenReturn(true);
        when(branchRepository.findAll()).thenReturn(List.of(branch1, branch2));

        List<Object[]> emptyBreakdown = Collections.singletonList(new Object[]{
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        });
        when(invoiceRepository.sumInvoiceBreakdownByBranches(any(), any(), any())).thenReturn(emptyBreakdown);
        when(paymentRepository.sumRealRevenueByBranches(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(paymentRepository.sumAdvanceDepositByBranches(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(paymentRepository.sumDepositWalletUsedByBranches(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(paymentRepository.findDailyRealPaymentsForPeriod(any(), any(), any())).thenReturn(new ArrayList<>());
        when(refundRepository.sumRefundAmountByBranches(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(operatingExpenseRepository.sumActiveExpenses(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(visitRepository.countVisitsByStatusAndBranches(any(), any(), any())).thenReturn(new ArrayList<>());

        Model model = new ConcurrentModel();
        String view = reportController.viewReports(null, null, null, new BigDecimal("20"), "overview", model, session);

        assertEquals("admin/reports/index", view);
        assertTrue((Boolean) model.getAttribute("isAdmin"));
        assertNull(model.getAttribute("selectedBranchId"));
    }

    @Test
    @DisplayName("Kế toán chỉ được xem chi nhánh được phân công hoạt động")
    void testAccountantAccessAssignedBranch_Allowed() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", accountantUser);
        session.setAttribute("isAdmin", false);

        when(userRepository.hasActiveAdminRole(200L)).thenReturn(false);
        when(staffBranchAssignmentRepository.findActiveBranchIdsByUserId(eq(200L), any(AssignmentStatus.class)))
                .thenReturn(List.of(1L));
        when(branchRepository.findAllById(List.of(1L))).thenReturn(List.of(branch1));

        List<Object[]> emptyBreakdown = Collections.singletonList(new Object[]{
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        });
        when(invoiceRepository.sumInvoiceBreakdownByBranches(any(), any(), any())).thenReturn(emptyBreakdown);
        when(paymentRepository.sumRealRevenueByBranches(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(paymentRepository.sumAdvanceDepositByBranches(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(paymentRepository.sumDepositWalletUsedByBranches(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(paymentRepository.findDailyRealPaymentsForPeriod(any(), any(), any())).thenReturn(new ArrayList<>());
        when(refundRepository.sumRefundAmountByBranches(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(operatingExpenseRepository.sumActiveExpenses(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(visitRepository.countVisitsByStatusAndBranches(any(), any(), any())).thenReturn(new ArrayList<>());

        Model model = new ConcurrentModel();
        String view = reportController.viewReports(1L, null, null, new BigDecimal("20"), "overview", model, session);

        assertEquals("admin/reports/index", view);
        assertFalse((Boolean) model.getAttribute("isAdmin"));
        assertEquals(1L, model.getAttribute("selectedBranchId"));
    }

    @Test
    @DisplayName("Kế toán cố tình thay branchId khác trên URL bị chặn với AccessDeniedException")
    void testAccountantAccessUnassignedBranch_ThrowsAccessDeniedException() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", accountantUser);
        session.setAttribute("isAdmin", false);

        when(userRepository.hasActiveAdminRole(200L)).thenReturn(false);
        when(staffBranchAssignmentRepository.findActiveBranchIdsByUserId(eq(200L), any(AssignmentStatus.class)))
                .thenReturn(List.of(1L));
        when(branchRepository.findAllById(List.of(1L))).thenReturn(List.of(branch1));

        Model model = new ConcurrentModel();
        // Cố tình truyền branchId = 2L (không được phân công cho kế toán này)
        assertThrows(AccessDeniedException.class, () -> {
            reportController.viewReports(2L, null, null, new BigDecimal("20"), "overview", model, session);
        });
    }

    @Test
    @DisplayName("Kế toán không được phép chọn xem toàn hệ thống (branchId = null)")
    void testAccountantAccessAllBranches_ThrowsAccessDeniedException() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", accountantUser);
        session.setAttribute("isAdmin", false);

        when(userRepository.hasActiveAdminRole(200L)).thenReturn(false);
        // Kế toán không có chi nhánh nào được phân công
        when(staffBranchAssignmentRepository.findActiveBranchIdsByUserId(eq(200L), any(AssignmentStatus.class)))
                .thenReturn(Collections.emptyList());
        when(userRepository.findById(200L)).thenReturn(Optional.of(accountantUser));

        Model model = new ConcurrentModel();
        assertThrows(AccessDeniedException.class, () -> {
            reportController.viewReports(null, null, null, new BigDecimal("20"), "overview", model, session);
        });
    }

    // =========================================================================
    // 2. KIỂM THỬ CÔNG THỨC SỐ LIỆU TÀI CHÍNH & DÒNG TIỀN (SCR-29.2)
    // =========================================================================

    @Test
    @DisplayName("Tính toán báo cáo tài chính chính xác: Không cộng trùng tiền cọc ví, trừ hoàn tiền, trừ chi phí")
    void testFinancialReportCalculation_AccurateFormulas() {
        LocalDate start = LocalDate.of(2026, 11, 1);
        LocalDate end = LocalDate.of(2026, 11, 30);
        List<Long> branchIds = List.of(1L);

        // 1. Hóa đơn: tổng tiền 10,000,000; giảm giá 500,000; nợ phát sinh 3,000,000
        List<Object[]> invoiceTotals = Collections.singletonList(new Object[]{
                new BigDecimal("8000000"), // Service
                new BigDecimal("2000000"), // Medicine
                new BigDecimal("500000"),  // Surcharge
                new BigDecimal("500000"),  // Discount
                BigDecimal.ZERO,           // Promo discount
                BigDecimal.ZERO,           // Point discount
                new BigDecimal("10000000"),// Total amount
                new BigDecimal("3000000")  // Remaining debt in period
        });
        when(invoiceRepository.sumInvoiceBreakdownByBranches(any(), any(), any())).thenReturn(invoiceTotals);

        // 2. Dòng tiền thực tế theo phương thức:
        when(paymentRepository.sumAmountByMethodAndBranches(any(), eq(PaymentMethod.CASH), any(), any(), any()))
                .thenReturn(new BigDecimal("3000000"));
        when(paymentRepository.sumAmountByMethodAndBranches(any(), eq(PaymentMethod.BANK_TRANSFER), any(), any(), any()))
                .thenReturn(new BigDecimal("3000000"));
        when(paymentRepository.sumAmountByMethodAndBranches(any(), eq(PaymentMethod.POS_CARD), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        // Tiền nạp cọc trước (ADVANCE_DEPOSIT): 2,000,000
        when(paymentRepository.sumAdvanceDepositByBranches(any(), any(), any()))
                .thenReturn(new BigDecimal("2000000"));
        // Tiền thanh toán bằng ví cọc: 1,000,000 (được ghi nhận riêng, KHÔNG cộng vào tổng thực thu)
        when(paymentRepository.sumDepositWalletUsedByBranches(any(), any(), any()))
                .thenReturn(new BigDecimal("1000000"));

        // Tiền hoàn lại: 500,000
        when(refundRepository.sumRefundAmountByBranches(any(), any(), any()))
                .thenReturn(new BigDecimal("500000"));

        // Chi phí vận hành: 2,500,000
        when(operatingExpenseRepository.sumActiveExpenses(any(), any(), any()))
                .thenReturn(new BigDecimal("2500000"));

        // Danh sách công nợ bệnh nhân: 1 bệnh nhân nợ 3,000,000
        PatientDebtSummaryDTO debtDto = new PatientDebtSummaryDTO(
                1L, "BN001", "Nguyễn Văn Test", "0901234567",
                BigDecimal.ZERO, 1L,
                new BigDecimal("10000000"), new BigDecimal("7000000"), new BigDecimal("3000000"),
                LocalDateTime.now()
        );
        when(invoiceRepository.findAllPatientDebtsByBranches(any())).thenReturn(List.of(debtDto));

        FinancialReportDTO report = reportService.getFinancialReport(branchIds, start, end);

        assertNotNull(report);
        assertEquals(new BigDecimal("10000000"), report.getTotalInvoiceAmount());
        assertEquals(new BigDecimal("3000000"), report.getPeriodDebt());

        // Tổng thực thu gộp = Cash(3tr) + Bank(3tr) + NewDeposit(2tr) = 8,000,000
        assertEquals(new BigDecimal("8000000"), report.getTotalCollected());
        // Tiền cọc sử dụng trừ từ ví được ghi nhận riêng
        assertEquals(new BigDecimal("1000000"), report.getDepositWalletAmount());
        assertEquals(new BigDecimal("500000"), report.getTotalRefundAmount());

        // Thực thu ròng = 8,000,000 - 500,000 = 7,500,000
        assertEquals(new BigDecimal("7500000"), report.getNetRevenue());

        // Chi phí vận hành = 2,500,000 -> Thặng dư vận hành = 7,500,000 - 2,500,000 = 5,000,000
        assertEquals(new BigDecimal("2500000"), report.getOperatingExpenses());
        assertEquals(new BigDecimal("5000000"), report.getOperatingMargin());

        // Thuế TNDN 20% trên thặng dư 5,000,000 = 1,000,000
        assertEquals(new BigDecimal("1000000"), report.getEstimatedTaxAmount());
        // Lợi nhuận ròng sau thuế = 5,000,000 - 1,000,000 = 4,000,000
        assertEquals(new BigDecimal("4000000"), report.getNetProfitAfterTax());

        // Công nợ lũy kế hiện tại
        assertEquals(new BigDecimal("3000000"), report.getCurrentTotalOutstandingDebt());
    }

    @Test
    @DisplayName("Báo cáo tài chính: Không cộng cọc 2 lần, truy vấn dòng tiền thực thu thanh toán hóa đơn với PaymentType.PAYMENT")
    void testFinancialReport_DoesNotDoubleCountAdvanceDeposits() {
        LocalDate start = LocalDate.of(2026, 11, 1);
        LocalDate end = LocalDate.of(2026, 11, 30);
        List<Long> branchIds = List.of(1L);

        when(invoiceRepository.sumInvoiceBreakdownByBranches(any(), any(), any())).thenReturn(Collections.singletonList(new Object[]{
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        }));
        when(refundRepository.sumRefundAmountByBranches(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(operatingExpenseRepository.sumActiveExpenses(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(invoiceRepository.findAllPatientDebtsByBranches(any())).thenReturn(Collections.emptyList());

        reportService.getFinancialReport(branchIds, start, end);

        verify(paymentRepository).sumAmountByMethodAndBranches(eq(branchIds), eq(PaymentMethod.CASH), eq(com.cvdcms.entity.enums.PaymentType.PAYMENT), any(), any());
        verify(paymentRepository).sumAmountByMethodAndBranches(eq(branchIds), eq(PaymentMethod.BANK_TRANSFER), eq(com.cvdcms.entity.enums.PaymentType.PAYMENT), any(), any());
        verify(paymentRepository).sumAmountByMethodAndBranches(eq(branchIds), eq(PaymentMethod.POS_CARD), eq(com.cvdcms.entity.enums.PaymentType.PAYMENT), any(), any());
    }

    // =========================================================================
    // 3. KIỂM THỬ SỐ LIỆU VẬN HÀNH & NGUỒN KHÁCH HÀNG (SCR-29.3)
    // =========================================================================

    @Test
    @DisplayName("Tỷ lệ hoàn tất xử lý an toàn khi không có lượt khám (chia cho 0)")
    void testOperationalReport_SafeDivisionWhenTotalVisitsIsZero() {
        when(visitRepository.countVisitsByStatusAndBranches(any(), any(), any())).thenReturn(Collections.emptyList());
        when(visitRepository.countVisitsByTypeAndBranches(any(), any(), any())).thenReturn(Collections.emptyList());
        when(appointmentRepository.countAppointmentsBySourceAndBranches(any(), any(), any())).thenReturn(Collections.emptyList());
        when(visitRepository.countPatientSourcesByVisitsAndBranches(any(), any(), any())).thenReturn(Collections.emptyList());

        OperationalReportDTO report = reportService.getOperationalReport(List.of(1L), LocalDate.now(), LocalDate.now());

        assertNotNull(report);
        assertEquals(0, report.getTotalVisits());
        assertEquals(0, report.getCompletedVisits());
        assertEquals(0.0, report.getCompletionRate()); // Không bị lỗi NaN hoặc ArithmeticException
    }

    @Test
    @DisplayName("Nguồn khách hàng null hoặc rỗng hiển thị 'Chưa xác định'")
    void testOperationalReport_NullCustomerSourceMappedToUnspecified() {
        when(visitRepository.countVisitsByStatusAndBranches(any(), any(), any())).thenReturn(Collections.emptyList());
        when(visitRepository.countVisitsByTypeAndBranches(any(), any(), any())).thenReturn(Collections.emptyList());
        when(appointmentRepository.countAppointmentsBySourceAndBranches(any(), any(), any())).thenReturn(Collections.emptyList());

        List<Object[]> sources = new ArrayList<>();
        sources.add(new Object[]{"Facebook Fanpage", 15L});
        sources.add(new Object[]{null, 10L});
        sources.add(new Object[]{"", 5L});

        when(visitRepository.countPatientSourcesByVisitsAndBranches(any(), any(), any())).thenReturn(sources);

        OperationalReportDTO report = reportService.getOperationalReport(List.of(1L), LocalDate.now(), LocalDate.now());

        assertNotNull(report.getCustomerSources());
        assertEquals(2, report.getCustomerSources().size());

        // Nguồn Facebook
        CustomerSourceStat fb = report.getCustomerSources().stream()
                .filter(s -> "Facebook Fanpage".equals(s.getSourceName()))
                .findFirst().orElse(null);
        assertNotNull(fb);
        assertEquals(15L, fb.getPatientCount());

        // Nguồn Chưa xác định được gộp từ null (10) và rỗng (5) = 15
        CustomerSourceStat unknown = report.getCustomerSources().stream()
                .filter(s -> "Chưa xác định".equals(s.getSourceName()))
                .findFirst().orElse(null);
        assertNotNull(unknown);
        assertEquals(15L, unknown.getPatientCount());
    }

    // =========================================================================
    // 4. KIỂM THỬ XUẤT FILE EXCEL (.XLSX CHUẨN & CHỐNG FORMULA INJECTION)
    // =========================================================================

    @Test
    @DisplayName("Xuất Excel các loại báo cáo tạo file .xlsx hợp lệ mở được bằng POI")
    void testExportExcel_GeneratesValidWorkbookForAllTypes() throws Exception {
        LocalDate start = LocalDate.of(2026, 11, 1);
        LocalDate end = LocalDate.of(2026, 11, 30);
        List<Long> branchIds = List.of(1L);

        // Mock dữ liệu tổng quan
        List<Object[]> emptyBreakdown = Collections.singletonList(new Object[]{
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("5000000"), BigDecimal.ZERO
        });
        when(invoiceRepository.sumInvoiceBreakdownByBranches(any(), any(), any())).thenReturn(emptyBreakdown);
        when(paymentRepository.sumRealRevenueByBranches(any(), any(), any())).thenReturn(new BigDecimal("5000000"));
        when(paymentRepository.sumAdvanceDepositByBranches(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(paymentRepository.sumDepositWalletUsedByBranches(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(paymentRepository.findDailyRealPaymentsForPeriod(any(), any(), any())).thenReturn(new ArrayList<>());
        when(refundRepository.sumRefundAmountByBranches(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(operatingExpenseRepository.sumActiveExpenses(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(visitRepository.countVisitsByStatusAndBranches(any(), any(), any())).thenReturn(new ArrayList<>());

        // Test xuất DASHBOARD
        byte[] dashboardExcel = reportService.exportReportToExcel("DASHBOARD", branchIds, start, end, "Chi nhánh Quận 1");
        assertNotNull(dashboardExcel);
        assertTrue(dashboardExcel.length > 0);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(dashboardExcel))) {
            Sheet sheet = wb.getSheetAt(0);
            assertNotNull(sheet);
            assertTrue(sheet.getLastRowNum() > 3);
        }

        // Test xuất DEBTS
        PatientDebtSummaryDTO pDebt = new PatientDebtSummaryDTO(
                1L, "BN123", "=cmd|' /C calc'!A0", "0988776655",
                BigDecimal.ZERO, 2L,
                new BigDecimal("2000000"), new BigDecimal("1000000"), new BigDecimal("1000000"),
                LocalDateTime.now()
        );
        when(invoiceRepository.findAllPatientDebtsByBranches(any())).thenReturn(List.of(pDebt));

        byte[] debtExcel = reportService.exportReportToExcel("DEBTS", branchIds, start, end, "Chi nhánh Quận 1");
        assertNotNull(debtExcel);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(debtExcel))) {
            Sheet sheet = wb.getSheetAt(0);
            assertNotNull(sheet);
            // Dòng 3 là dòng dữ liệu bệnh nhân đầu tiên sau dòng tiêu đề (0) và header (2)
            String sanitizedName = sheet.getRow(3).getCell(2).getStringCellValue();
            // Đảm bảo ký tự '=' đã được tiền tố dấu nháy đơn "'" chống injection công thức
            assertTrue(sanitizedName.startsWith("'="));
        }
    }
}
