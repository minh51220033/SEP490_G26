package com.cvdcms.DTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class FinancialMetricsDTO {
    private BigDecimal todayTotalBilled = BigDecimal.ZERO;
    private BigDecimal todayTotalCollected = BigDecimal.ZERO;
    private BigDecimal todayTotalDebt = BigDecimal.ZERO;
    private BigDecimal todayTotalRefunded = BigDecimal.ZERO;
    private long todayTotalInvoices = 0;
    private long unpaidInvoicesCount = 0;
}
