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

    public PatientDebtSummaryDTO(Long profileId, String patientCode, String fullName, String phoneNumber,
                                 BigDecimal depositBalance, Long unpaidInvoicesCount,
                                 BigDecimal totalBilled, BigDecimal totalPaid, BigDecimal totalDebt,
                                 LocalDateTime lastInvoiceDate) {
        this.profileId = profileId;
        this.patientCode = patientCode;
        this.fullName = fullName;
        this.phoneNumber = phoneNumber;
        this.depositBalance = depositBalance != null ? depositBalance : BigDecimal.ZERO;
        this.unpaidInvoicesCount = unpaidInvoicesCount != null ? unpaidInvoicesCount.intValue() : 0;
        this.totalBilled = totalBilled != null ? totalBilled : BigDecimal.ZERO;
        this.totalPaid = totalPaid != null ? totalPaid : BigDecimal.ZERO;
        this.totalDebt = totalDebt != null ? totalDebt : BigDecimal.ZERO;
        this.lastInvoiceDate = lastInvoiceDate;
    }

    public PatientDebtSummaryDTO(Long profileId, String patientCode, String fullName, String phoneNumber,
                                 BigDecimal depositBalance, Integer unpaidInvoicesCount,
                                 BigDecimal totalBilled, BigDecimal totalPaid, BigDecimal totalDebt,
                                 LocalDateTime lastInvoiceDate) {
        this(profileId, patientCode, fullName, phoneNumber, depositBalance,
                unpaidInvoicesCount != null ? unpaidInvoicesCount.longValue() : 0L,
                totalBilled, totalPaid, totalDebt, lastInvoiceDate);
    }
}
