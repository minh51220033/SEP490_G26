package com.cvdcms.DTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
public class PatientDebtSummaryDTO {
    private Long profileId;
    private String patientCode;
    private String fullName;
    private String phoneNumber;
    private BigDecimal depositBalance = BigDecimal.ZERO;
    private BigDecimal totalBilled = BigDecimal.ZERO;
    private BigDecimal totalPaid = BigDecimal.ZERO;
    private BigDecimal totalDebt = BigDecimal.ZERO;
    private int unpaidInvoicesCount = 0;
    private LocalDateTime lastInvoiceDate;
}
