package com.cvdcms.service;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.DisplayName;

import com.cvdcms.DTO.PatientDebtSummaryDTO;
import com.cvdcms.DTO.report.*;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.PaymentMethod;
import com.cvdcms.repository.*;
import com.cvdcms.service.impl.ReportServiceImpl;
import com.cvdcms.entity.enums.PaymentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FinancialReport")
@TestMethodOrder(MethodOrderer.MethodName.class)
public class FinancialReportTest {

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

    private ReportServiceImpl reportService;

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
    }


    private final LocalDate reportStart=LocalDate.of(2026,11,1);
    private final LocalDate reportEnd=LocalDate.of(2026,11,30);
    private FinancialReportDTO financial(BigDecimal rate){return reportService.getFinancialReport(List.of(1L),reportStart,reportEnd,rate);}

    @Test
    @DisplayName("UTCID01 - N - Tính toán báo cáo tài chính chính xác: Không cộng trùng tiền cọc ví, trừ hoàn tiền, trừ chi phí")
    void UTCID01() throws Exception {
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
    @DisplayName("UTCID02 - N - Không có giao dịch thì doanh thu, chi phí và công nợ bằng 0")
    void UTCID02() throws Exception {
        FinancialReportDTO r=financial(new BigDecimal("20"));assertNotNull(r);assertEquals(0,r.getTotalCollected().compareTo(BigDecimal.ZERO));assertEquals(0,r.getNetRevenue().compareTo(BigDecimal.ZERO));assertEquals(0,r.getOperatingExpenses().compareTo(BigDecimal.ZERO));assertTrue(r.getPatientDebts().isEmpty());
    }

    @Test
    @DisplayName("UTCID03 - N - Tổng giảm giá bằng giảm lâm sàng cộng khuyến mãi và điểm")
    void UTCID03() throws Exception {
        when(invoiceRepository.sumInvoiceBreakdownByBranches(any(),any(),any())).thenReturn(Collections.singletonList(new Object[]{new BigDecimal("1000"),BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal("100"),new BigDecimal("50"),new BigDecimal("20"),new BigDecimal("830"),new BigDecimal("30")}));FinancialReportDTO r=financial(new BigDecimal("20"));assertEquals(0,r.getTotalDiscount().compareTo(new BigDecimal("170")));assertEquals(0,r.getTotalInvoiceAmount().compareTo(new BigDecimal("830")));assertEquals(0,r.getPeriodDebt().compareTo(new BigDecimal("30")));
    }

    @Test
    @DisplayName("UTCID04 - B - Hoàn tiền lớn hơn thực thu thì doanh thu thuần âm")
    void UTCID04() throws Exception {
        when(paymentRepository.sumAmountByMethodAndBranches(any(),eq(PaymentMethod.CASH),eq(PaymentType.PAYMENT),any(),any())).thenReturn(new BigDecimal("100"));when(refundRepository.sumRefundAmountByBranches(any(),any(),any())).thenReturn(new BigDecimal("200"));FinancialReportDTO r=financial(new BigDecimal("20"));assertEquals(0,r.getTotalCollected().compareTo(new BigDecimal("100")));assertEquals(0,
                r.getNetRevenue().compareTo(new BigDecimal("-100")));
    }

    @Test
    @DisplayName("UTCID05 - B - Chi phí lớn hơn doanh thu thì lợi nhuận âm và thuế tạm tính bằng 0")
    void UTCID05() throws Exception {
        when(operatingExpenseRepository.sumActiveExpenses(any(),any(),any())).thenReturn(new BigDecimal("100"));FinancialReportDTO r=financial(new BigDecimal("20"));assertEquals(0,r.getOperatingMargin().compareTo(new BigDecimal("-100")));assertEquals(0,r.getEstimatedTaxAmount().compareTo(BigDecimal.ZERO));assertEquals(0,r.getNetProfitAfterTax().compareTo(new BigDecimal("-100")));
    }

    @Test
    @DisplayName("UTCID06 - N - Thuế suất tùy chọn 10% tính đúng trên phần chênh lệch dương")
    void UTCID06() throws Exception {
        when(paymentRepository.sumAmountByMethodAndBranches(any(),eq(PaymentMethod.CASH),eq(PaymentType.PAYMENT),any(),any())).thenReturn(new BigDecimal("1000"));when(operatingExpenseRepository.sumActiveExpenses(any(),any(),any())).thenReturn(new BigDecimal("200"));FinancialReportDTO r=financial(new BigDecimal("10"));assertEquals(0,r.getEstimatedTaxAmount().compareTo(new BigDecimal("80")));assertEquals(0,r.getNetProfitAfterTax().compareTo(new BigDecimal("720")));
    }

    @Test
    @DisplayName("UTCID07 - A - Chặn thuế suất thủ công không hợp lệ (âm hoặc > 100%)")
    void UTCID07() {
        assertThrows(IllegalArgumentException.class, () -> financial(new BigDecimal("-1")));
        assertThrows(IllegalArgumentException.class, () -> financial(new BigDecimal("101")));
    }

    @Test
    @DisplayName("UTCID08 - N - Truyền đúng danh sách chi nhánh và khoảng ngày sang repository")
    void UTCID08() throws Exception {
        financial(BigDecimal.ZERO);verify(invoiceRepository).sumInvoiceBreakdownByBranches(eq(List.of(1L)),eq(reportStart.atStartOfDay()),eq(reportEnd.atTime(java.time.LocalTime.MAX)));verify(invoiceRepository).findAllPatientDebtsByBranches(List.of(1L));
    }

    @Test
    @DisplayName("UTCID09 - N - AUTO (null) và overload không truyền taxRate dùng cấu hình chi nhánh 15%")
    void UTCID09() {
        TaxCalculationService mockTaxService = mock(TaxCalculationService.class);
        BranchTaxConfig config = new BranchTaxConfig();
        config.setEntityType("ENTERPRISE");
        config.setCitRate(new BigDecimal("15"));
        when(mockTaxService.getBranchTaxConfig(1L, reportEnd)).thenReturn(Optional.of(config));

        ReportServiceImpl configuredService = new ReportServiceImpl(
                invoiceRepository,
                paymentRepository,
                refundRepository,
                operatingExpenseRepository,
                visitRepository,
                appointmentRepository,
                mockTaxService,
                null,
                null
        );
        when(paymentRepository.sumAmountByMethodAndBranches(any(), eq(PaymentMethod.CASH), eq(PaymentType.PAYMENT), any(), any()))
                .thenReturn(new BigDecimal("1000"));
        when(paymentRepository.sumRealRevenueByBranches(any(), any(), any()))
                .thenReturn(new BigDecimal("1000"));
        when(operatingExpenseRepository.sumActiveExpenses(any(), any(), any()))
                .thenReturn(new BigDecimal("200"));

        FinancialReportDTO autoFinancial = configuredService.getFinancialReport(List.of(1L), reportStart, reportEnd, null);
        FinancialReportDTO overloadFinancial = configuredService.getFinancialReport(List.of(1L), reportStart, reportEnd);
        ReportDashboardDTO overloadDashboard = configuredService.getDashboardReport(List.of(1L), reportStart, reportEnd, "CN 1");

        assertEquals(0, new BigDecimal("15").compareTo(autoFinancial.getTaxRate()));
        assertEquals(0, new BigDecimal("120").compareTo(autoFinancial.getEstimatedTaxAmount()));
        assertEquals(0, new BigDecimal("15").compareTo(overloadFinancial.getTaxRate()));
        assertEquals(0, new BigDecimal("15").compareTo(overloadDashboard.getTaxRate()));
    }

    @Test
    @DisplayName("UTCID10 - N - Chọn 0% không bị ép sang 20% hay cấu hình chi nhánh 15%")
    void UTCID10() {
        TaxCalculationService mockTaxService = mock(TaxCalculationService.class);
        BranchTaxConfig config = new BranchTaxConfig();
        config.setEntityType("ENTERPRISE");
        config.setCitRate(new BigDecimal("15"));
        when(mockTaxService.getBranchTaxConfig(1L, reportEnd)).thenReturn(Optional.of(config));

        ReportServiceImpl configuredService = new ReportServiceImpl(
                invoiceRepository,
                paymentRepository,
                refundRepository,
                operatingExpenseRepository,
                visitRepository,
                appointmentRepository,
                mockTaxService,
                null,
                null
        );
        when(paymentRepository.sumAmountByMethodAndBranches(any(), eq(PaymentMethod.CASH), eq(PaymentType.PAYMENT), any(), any()))
                .thenReturn(new BigDecimal("1000"));

        FinancialReportDTO zeroRate = configuredService.getFinancialReport(List.of(1L), reportStart, reportEnd, BigDecimal.ZERO);

        assertEquals(0, BigDecimal.ZERO.compareTo(zeroRate.getTaxRate()));
        assertEquals(0, BigDecimal.ZERO.compareTo(zeroRate.getEstimatedTaxAmount()));
        assertEquals(0, new BigDecimal("1000").compareTo(zeroRate.getNetProfitAfterTax()));
    }

    @Test
    @DisplayName("UTCID11 - N - HKD phân tách Dịch vụ/Thuốc theo PaymentAllocation, không đếm trùng nạp/cấn ví và tách hoàn ví")
    void UTCID11() throws Exception {
        TaxCalculationService mockTaxService = mock(TaxCalculationService.class);
        PaymentAllocationRepository mockAllocRepo = mock(PaymentAllocationRepository.class);
        BranchRepository mockBranchRepo = mock(BranchRepository.class);

        Branch b1 = new Branch();
        b1.setBranchId(1L);
        b1.setBranchName("CN Hộ KD");
        when(mockBranchRepo.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(b1));

        BranchTaxConfig hhConfig = new BranchTaxConfig();
        hhConfig.setEntityType("HOUSEHOLD");
        hhConfig.setTaxRegime("REVENUE_PERCENT");
        hhConfig.setServiceTaxRate(new BigDecimal("2.0"));
        hhConfig.setMedicineTaxRate(new BigDecimal("0.5"));
        when(mockTaxService.getBranchTaxConfig(1L, reportEnd)).thenReturn(Optional.of(hhConfig));

        // Dòng tiền: 8tr trả trực tiếp + 5tr nạp ví = 13tr; dùng ví 4tr (cấn vào DV+Thuốc) không cộng dòng tiền; hoàn ví 1tr
        when(paymentRepository.sumRealRevenueByBranches(any(), any(), any())).thenReturn(new BigDecimal("13000000"));
        when(paymentRepository.sumAmountByMethodAndBranches(any(), eq(PaymentMethod.CASH), eq(PaymentType.PAYMENT), any(), any()))
                .thenReturn(new BigDecimal("8000000"));
        when(paymentRepository.sumAdvanceDepositByBranches(any(), any(), any())).thenReturn(new BigDecimal("5000000"));
        when(paymentRepository.sumDepositWalletUsedByBranches(any(), any(), any())).thenReturn(new BigDecimal("4000000"));
        when(refundRepository.sumRefundAmountByBranches(any(), any(), any())).thenReturn(new BigDecimal("1000000"));

        when(paymentRepository.sumPaymentsByBranchTypeAndMethod(any(), any(), any())).thenReturn(List.of(
                new Object[]{1L, PaymentType.PAYMENT, PaymentMethod.CASH, new BigDecimal("8000000")},
                new Object[]{1L, PaymentType.ADVANCE_DEPOSIT, PaymentMethod.BANK_TRANSFER, new BigDecimal("5000000")},
                new Object[]{1L, PaymentType.PAYMENT, PaymentMethod.DEPOSIT_WALLET, new BigDecimal("4000000")}
        ));
        when(mockAllocRepo.sumAllocatedByBranchAndItemType(any(), any(), any())).thenReturn(List.of(
                new Object[]{1L, "SERVICE", new BigDecimal("8000000")},
                new Object[]{1L, "MEDICINE", new BigDecimal("4000000")}
        ));

        Refund walletRefund = new Refund();
        walletRefund.setBranch(b1);
        walletRefund.setInvoice(null); // Hoàn từ ví tạm ứng -> KHÔNG trừ vào doanh thu DV/Thuốc
        walletRefund.setAmount(new BigDecimal("1000000"));
        when(refundRepository.findAllRefundsForReport(any(), any(), any())).thenReturn(List.of(walletRefund));

        ReportServiceImpl configuredService = new ReportServiceImpl(
                invoiceRepository, paymentRepository, refundRepository,
                operatingExpenseRepository, visitRepository, appointmentRepository,
                mockTaxService, null, null, mockAllocRepo, mockBranchRepo
        );

        ReportDashboardDTO dash = configuredService.getDashboardReport(List.of(1L), reportStart, reportEnd, "CN Hộ KD");
        FinancialReportDTO fin = configuredService.getFinancialReport(List.of(1L), reportStart, reportEnd);
        byte[] excel = configuredService.exportReportToExcel("OVERVIEW", List.of(1L), reportStart, reportEnd, "CN Hộ KD");

        // Thuế = 8,000,000 * 2% (160,000) + 4,000,000 * 0.5% (20,000) = 180,000 (không nhân 2% lên dòng tiền 12tr)
        BigDecimal expectedTax = new BigDecimal("180000");
        assertTrue(dash.isTaxConfigured());
        assertEquals(0, expectedTax.compareTo(dash.getEstimatedTaxAmount()));
        assertEquals(0, expectedTax.compareTo(fin.getEstimatedTaxAmount()));
        assertEquals(0, new BigDecimal("8000000").compareTo(fin.getServiceTaxBase()));
        assertEquals(0, new BigDecimal("4000000").compareTo(fin.getMedicineTaxBase()));
        assertEquals(0, new BigDecimal("1000000").compareTo(fin.getWalletRefundAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(fin.getInvoiceRefundAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(fin.getUnsettledAdvanceDeposit()));
        assertNotNull(excel);
        assertTrue(excel.length > 0);
    }

    @Test
    @DisplayName("UTCID12 - N - Hai chi nhánh khác chế độ (HKD và Doanh nghiệp 15%) tính độc lập rồi tổng hợp")
    void UTCID12() {
        TaxCalculationService mockTaxService = mock(TaxCalculationService.class);
        PaymentAllocationRepository mockAllocRepo = mock(PaymentAllocationRepository.class);
        BranchRepository mockBranchRepo = mock(BranchRepository.class);

        Branch b1 = new Branch();
        b1.setBranchId(1L);
        b1.setBranchName("CN1 Hộ KD");
        Branch b2 = new Branch();
        b2.setBranchId(2L);
        b2.setBranchName("CN2 Công ty");
        when(mockBranchRepo.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(b1, b2));

        BranchTaxConfig hhConfig = new BranchTaxConfig();
        hhConfig.setEntityType("HOUSEHOLD");
        hhConfig.setTaxRegime("REVENUE_PERCENT");
        hhConfig.setServiceTaxRate(new BigDecimal("2.0"));
        hhConfig.setMedicineTaxRate(new BigDecimal("0.5"));
        when(mockTaxService.getBranchTaxConfig(1L, reportEnd)).thenReturn(Optional.of(hhConfig));

        BranchTaxConfig entConfig = new BranchTaxConfig();
        entConfig.setEntityType("ENTERPRISE");
        entConfig.setTaxRegime("CIT_STANDARD");
        entConfig.setCitRate(new BigDecimal("15.0"));
        when(mockTaxService.getBranchTaxConfig(2L, reportEnd)).thenReturn(Optional.of(entConfig));

        when(paymentRepository.sumRealRevenueByBranches(any(), any(), any())).thenReturn(new BigDecimal("14000000"));
        when(operatingExpenseRepository.sumActiveExpenses(any(), any(), any())).thenReturn(BigDecimal.ZERO);

        when(paymentRepository.sumPaymentsByBranchTypeAndMethod(any(), any(), any())).thenReturn(List.of(
                new Object[]{1L, PaymentType.PAYMENT, PaymentMethod.CASH, new BigDecimal("4000000")},
                new Object[]{2L, PaymentType.PAYMENT, PaymentMethod.BANK_TRANSFER, new BigDecimal("10000000")}
        ));
        when(mockAllocRepo.sumAllocatedByBranchAndItemType(any(), any(), any())).thenReturn(List.of(
                new Object[]{1L, "SERVICE", new BigDecimal("2000000")},
                new Object[]{1L, "MEDICINE", new BigDecimal("2000000")}
        ));

        ReportServiceImpl configuredService = new ReportServiceImpl(
                invoiceRepository, paymentRepository, refundRepository,
                operatingExpenseRepository, visitRepository, appointmentRepository,
                mockTaxService, null, null, mockAllocRepo, mockBranchRepo
        );

        FinancialReportDTO report = configuredService.getFinancialReport(List.of(1L, 2L), reportStart, reportEnd);

        // CN1 (HKD): 2M*2% (40k) + 2M*0.5% (10k) = 50,000
        // CN2 (DN 15%): 10M*15% = 1,500,000 -> Tổng = 1,550,000 (không bị ép chung 20%)
        assertTrue(report.isTaxConfigured());
        assertEquals(0, new BigDecimal("1550000").compareTo(report.getEstimatedTaxAmount()));
        assertTrue(report.getTaxRegimeName().contains("đa chế độ"));
    }

    @Test
    @DisplayName("UTCID13 - A - Thiếu cấu hình chi nhánh hoặc thiếu rate nhóm bị đánh dấu chưa đủ căn cứ")
    void UTCID13() {
        TaxCalculationService mockTaxService = mock(TaxCalculationService.class);
        PaymentAllocationRepository mockAllocRepo = mock(PaymentAllocationRepository.class);
        BranchRepository mockBranchRepo = mock(BranchRepository.class);

        Branch b1 = new Branch();
        b1.setBranchId(1L);
        b1.setBranchName("CN1 Hộ KD thiếu rate thuốc");
        Branch b2 = new Branch();
        b2.setBranchId(2L);
        b2.setBranchName("CN2 Chưa cấu hình");
        when(mockBranchRepo.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(b1, b2));

        BranchTaxConfig incompleteHh = new BranchTaxConfig();
        incompleteHh.setEntityType("HOUSEHOLD");
        incompleteHh.setTaxRegime("REVENUE_PERCENT");
        incompleteHh.setServiceTaxRate(new BigDecimal("2.0"));
        incompleteHh.setMedicineTaxRate(null); // thiếu rate thuốc trong khi có doanh thu thuốc
        when(mockTaxService.getBranchTaxConfig(1L, reportEnd)).thenReturn(Optional.of(incompleteHh));
        when(mockTaxService.getBranchTaxConfig(2L, reportEnd)).thenReturn(Optional.empty());

        when(paymentRepository.sumPaymentsByBranchTypeAndMethod(any(), any(), any())).thenReturn(List.of(
                new Object[]{1L, PaymentType.PAYMENT, PaymentMethod.CASH, new BigDecimal("1000000")},
                new Object[]{2L, PaymentType.PAYMENT, PaymentMethod.CASH, new BigDecimal("2000000")}
        ));
        when(mockAllocRepo.sumAllocatedByBranchAndItemType(any(), any(), any())).thenReturn(List.of(
                new Object[]{1L, "MEDICINE", new BigDecimal("1000000")}
        ));

        ReportServiceImpl configuredService = new ReportServiceImpl(
                invoiceRepository, paymentRepository, refundRepository,
                operatingExpenseRepository, visitRepository, appointmentRepository,
                mockTaxService, null, null, mockAllocRepo, mockBranchRepo
        );

        FinancialReportDTO report = configuredService.getFinancialReport(List.of(1L, 2L), reportStart, reportEnd);

        assertFalse(report.isTaxConfigured());
        assertTrue(report.getTaxCalculationBasis().contains("CHƯA ĐỦ CĂN CỨ"));
    }
}
