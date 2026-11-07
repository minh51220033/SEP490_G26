package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UnpaidInvoiceSummaryDTO {
    private Long invoiceId;
    private String invoiceCode;
    private LocalDateTime issuedAt;
    private Long branchId;
    private String branchName;

    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal remainingDebt = BigDecimal.ZERO;
}
