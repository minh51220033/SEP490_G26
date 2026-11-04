package com.cvdcms.DTO.report;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
public class ReportFilterDTO {

    private Long branchId;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;

    /**
     * Tab đang hiển thị hoặc loại báo cáo được yêu cầu:
     * overview, financial, operational, debts
     */
    private String tab = "overview";

    /**
     * Thuế suất TNDN (%) áp dụng: mặc định 20%
     */
    private BigDecimal taxRate = BigDecimal.valueOf(20);

    /**
     * Định dạng xuất Excel: xlsx
     */
    private String format = "xlsx";
}
