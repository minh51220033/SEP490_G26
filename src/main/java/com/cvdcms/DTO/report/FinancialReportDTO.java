package com.cvdcms.DTO.report;

import com.cvdcms.DTO.PatientDebtSummaryDTO;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class FinancialReportDTO {

    // 1. Phân tích giá trị hóa đơn & doanh số
    private BigDecimal totalInvoiceAmount = BigDecimal.ZERO;
    private BigDecimal totalServiceAmount = BigDecimal.ZERO;
    private BigDecimal totalMedicineAmount = BigDecimal.ZERO;
    private BigDecimal surchargeAmount = BigDecimal.ZERO;

    private BigDecimal discountAmount = BigDecimal.ZERO;
    private BigDecimal promotionDiscount = BigDecimal.ZERO;
    private BigDecimal pointDiscount = BigDecimal.ZERO;
    private BigDecimal totalDiscount = BigDecimal.ZERO;

    // 2. Dòng tiền thực tế theo phương thức thanh toán
    private BigDecimal cashAmount = BigDecimal.ZERO;
    private BigDecimal bankTransferAmount = BigDecimal.ZERO;
    private BigDecimal posCardAmount = BigDecimal.ZERO;
    private BigDecimal depositWalletAmount = BigDecimal.ZERO; // Trừ từ ví cọc (không tính vào dòng tiền mới)
    private BigDecimal newDepositAmount = BigDecimal.ZERO;    // Nạp cọc mới (ADVANCE_DEPOSIT)
    private BigDecimal totalCollected = BigDecimal.ZERO;       // Tiền thực thu từ khách (CASH + BANK + POS + newDeposit)

    // 3. Hoàn tiền & Chi phí
    private BigDecimal totalRefundAmount = BigDecimal.ZERO;
    private BigDecimal netRevenue = BigDecimal.ZERO;           // Thực thu thuần = totalCollected - totalRefundAmount
    private BigDecimal operatingExpenses = BigDecimal.ZERO;    // Chi phí vận hành
    private BigDecimal operatingMargin = BigDecimal.ZERO;      // Thặng dư thu chi vận hành = netRevenue - operatingExpenses

    // 4. Thuế & Lợi nhuận ròng sau thuế
    private BigDecimal taxRate = BigDecimal.valueOf(20);       // Thuế suất TNDN (%)
    private BigDecimal estimatedTaxAmount = BigDecimal.ZERO;   // Nghĩa vụ thuế TNDN tạm tính
    private BigDecimal netProfitAfterTax = BigDecimal.ZERO;    // Lợi nhuận ròng sau thuế

    // Thông tin chế độ thuế & Căn cứ pháp lý
    private String taxRegimeName = "Doanh nghiệp (TNDN tạm tính quản trị)";
    private boolean isTaxConfigured = true;
    private String taxCalculationBasis = "Căn cứ Luật Thuế TNDN 14/2008/QH12 (Tạm tính phục vụ quản trị nội bộ)";
    private BigDecimal totalPitWithheld = BigDecimal.ZERO;
    private BigDecimal totalVatAmount = BigDecimal.ZERO;

    // 5. Công nợ
    private BigDecimal periodDebt = BigDecimal.ZERO;           // Nợ phát sinh của hóa đơn trong kỳ
    private BigDecimal currentTotalOutstandingDebt = BigDecimal.ZERO; // Nợ lũy kế hiện tại
    private List<PatientDebtSummaryDTO> patientDebts = new ArrayList<>();
}
