package com.cvdcms.DTO.report;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class ReportDashboardDTO {

    private String selectedBranchName = "Tất cả chi nhánh";
    private LocalDate filterStartDate;
    private LocalDate filterEndDate;

    // 4 Thẻ KPI chính
    private BigDecimal totalRealRevenue = BigDecimal.ZERO;
    private BigDecimal totalInvoiceAmount = BigDecimal.ZERO;
    private BigDecimal totalDebtAmount = BigDecimal.ZERO;
    private Long totalVisits = 0L;

    // Thống kê bổ sung
    private Long completedVisits = 0L;
    private Long cancelledVisits = 0L;
    private Long inProgressVisits = 0L;
    private Double completionRate = 0.0;
    private Long newPatientsCount = 0L;

    private BigDecimal totalExpenseAmount = BigDecimal.ZERO;
    private BigDecimal totalRefundAmount = BigDecimal.ZERO;
    private BigDecimal operatingMargin = BigDecimal.ZERO; // Thặng dư vận hành trước thuế

    // Thuế & Lợi nhuận ròng sau thuế
    private BigDecimal taxRate = BigDecimal.valueOf(20); // Thuế suất TNDN (%)
    private BigDecimal estimatedTaxAmount = BigDecimal.ZERO; // Thuế TNDN tạm tính
    private BigDecimal netProfitAfterTax = BigDecimal.ZERO; // Lợi nhuận sau thuế

    // Thông tin chế độ thuế & Căn cứ pháp lý
    private String taxRegimeName = "Doanh nghiệp (TNDN tạm tính quản trị)";
    private boolean isTaxConfigured = true;
    private String taxCalculationBasis = "Căn cứ Luật Thuế TNDN 14/2008/QH12 (Tạm tính phục vụ quản trị nội bộ)";
    private BigDecimal totalPitWithheld = BigDecimal.ZERO;
    private BigDecimal totalVatAmount = BigDecimal.ZERO;

    // Dữ liệu xu hướng theo ngày phục vụ vẽ biểu đồ
    private List<DailyRevenueStat> dailyStats = new ArrayList<>();
}
