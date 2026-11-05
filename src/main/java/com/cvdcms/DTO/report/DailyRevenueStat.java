package com.cvdcms.DTO.report;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DailyRevenueStat {
    private LocalDate date;
    private BigDecimal invoiceAmount = BigDecimal.ZERO;
    private BigDecimal realRevenue = BigDecimal.ZERO;
    private Long visitCount = 0L;
}

