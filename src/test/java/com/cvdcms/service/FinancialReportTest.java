
package com.cvdcms.service;

import com.cvdcms.DTO.FinancialReportDTO;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.DisplayName;

import com.cvdcms.DTO.PatientDebtSummaryDTO;
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


    private final LocalDate reportStart = LocalDate.of(2026, 11, 1);
    private final LocalDate reportEnd = LocalDate.of(2026, 11, 30);

    private FinancialReportDTO financial() {
        return reportService.getFinancialReport(List.of(1L), reportStart, reportEnd);
    }

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

        // Chi phí vận hành = 2,500,000 -> Chênh lệch Thu - Chi = 7,500,000 - 2,500,000 = 5,000,000
        assertEquals(new BigDecimal("2500000"), report.getOperatingExpenses());
        assertEquals(new BigDecimal("5000000"), report.getOperatingMargin());

        // Công nợ lũy kế hiện tại
        assertEquals(new BigDecimal("3000000"), report.getCurrentTotalOutstandingDebt());
    }

    @Test
    @DisplayName("UTCID02 - N - Không có giao dịch thì doanh thu, chi phí và công nợ bằng 0")
    void UTCID02() throws Exception {
        FinancialReportDTO r = financial();
        assertNotNull(r);
        assertEquals(0, r.getTotalCollected().compareTo(BigDecimal.ZERO));
        assertEquals(0, r.getNetRevenue().compareTo(BigDecimal.ZERO));
        assertEquals(0, r.getOperatingExpenses().compareTo(BigDecimal.ZERO));
        assertTrue(r.getPatientDebts().isEmpty());
    }

    @Test
    @DisplayName("UTCID03 - N - Tổng giảm giá bằng giảm lâm sàng cộng khuyến mãi và điểm")
    void UTCID03() throws Exception {
        when(invoiceRepository.sumInvoiceBreakdownByBranches(any(), any(), any()))
                .thenReturn(Collections.singletonList(new Object[]{
                        new BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO,
                        new BigDecimal("100"), new BigDecimal("50"), new BigDecimal("20"),
                        new BigDecimal("830"), new BigDecimal("30")
                }));
        FinancialReportDTO r = financial();
        assertEquals(0, r.getTotalDiscount().compareTo(new BigDecimal("170")));
        assertEquals(0, r.getTotalInvoiceAmount().compareTo(new BigDecimal("830")));
        assertEquals(0, r.getPeriodDebt().compareTo(new BigDecimal("30")));
    }

    @Test
    @DisplayName("UTCID04 - B - Hoàn tiền lớn hơn thực thu thì doanh thu thuần âm")
    void UTCID04() throws Exception {
        when(paymentRepository.sumAmountByMethodAndBranches(any(), eq(PaymentMethod.CASH), eq(PaymentType.PAYMENT), any(), any()))
                .thenReturn(new BigDecimal("100"));
        when(refundRepository.sumRefundAmountByBranches(any(), any(), any()))
                .thenReturn(new BigDecimal("200"));
        FinancialReportDTO r = financial();
        assertEquals(0, r.getTotalCollected().compareTo(new BigDecimal("100")));
        assertEquals(0, r.getNetRevenue().compareTo(new BigDecimal("-100")));
    }

    @Test
    @DisplayName("UTCID05 - B - Chi phí lớn hơn doanh thu thì chênh lệch Thu - Chi vận hành âm")
    void UTCID05() throws Exception {
        when(operatingExpenseRepository.sumActiveExpenses(any(), any(), any()))
                .thenReturn(new BigDecimal("100"));
        FinancialReportDTO r = financial();
        assertEquals(0, r.getOperatingMargin().compareTo(new BigDecimal("-100")));
    }

    @Test
    @DisplayName("UTCID06 - N - Tách bạch hoàn tiền hóa đơn và hoàn tiền số dư ví tạm ứng")
    void UTCID06() {
        Invoice inv = new Invoice();
        inv.setInvoiceId(10L);

        Refund invRefund = new Refund();
        invRefund.setInvoice(inv);
        invRefund.setAmount(new BigDecimal("300000"));

        Refund walletRefund = new Refund();
        walletRefund.setInvoice(null);
        walletRefund.setAmount(new BigDecimal("200000"));

        when(refundRepository.sumRefundAmountByBranches(any(), any(), any())).thenReturn(new BigDecimal("500000"));
        when(refundRepository.findAllRefundsForReport(any(), any(), any())).thenReturn(List.of(invRefund, walletRefund));

        FinancialReportDTO r = financial();
        assertEquals(0, new BigDecimal("500000").compareTo(r.getTotalRefundAmount()));
        assertEquals(0, new BigDecimal("300000").compareTo(r.getInvoiceRefundAmount()));
        assertEquals(0, new BigDecimal("200000").compareTo(r.getWalletRefundAmount()));
    }

    @Test
    @DisplayName("UTCID08 - N - Truyền đúng danh sách chi nhánh và khoảng ngày sang repository")
    void UTCID08() throws Exception {
        financial();
        verify(invoiceRepository).sumInvoiceBreakdownByBranches(
                eq(List.of(1L)),
                eq(reportStart.atStartOfDay()),
                eq(reportEnd.atTime(java.time.LocalTime.MAX))
        );
        verify(invoiceRepository).findAllPatientDebtsByBranches(List.of(1L));
    }
}

