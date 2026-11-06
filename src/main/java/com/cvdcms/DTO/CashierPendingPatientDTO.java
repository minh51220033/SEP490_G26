package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CashierPendingPatientDTO {
    private Long profileId;
    private String patientCode;
    private String fullName;
    private String phoneNumber;
    private String branchNames;

    @Builder.Default
    private int unbilledVisitCount = 0;

    @Builder.Default
    private int unpaidInvoiceCount = 0;

    @Builder.Default
    private BigDecimal totalBilled = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal totalPaid = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal remainingDebt = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal depositBalance = BigDecimal.ZERO;

    private String processingStatus;
    private String statusDisplay;

    private boolean hasUnbilledItems;
    private boolean hasUnpaidInvoices;

    private LocalDateTime latestActivityTime;

    @Builder.Default
    private List<PendingVisitSummaryDTO> pendingVisits = new ArrayList<>();

    @Builder.Default
    private List<UnpaidInvoiceSummaryDTO> unpaidInvoices = new ArrayList<>();
}

