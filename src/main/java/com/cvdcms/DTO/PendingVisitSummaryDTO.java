package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PendingVisitSummaryDTO {
    private Long visitId;
    private String visitCode;
    private LocalDateTime checkInAt;
    private Long branchId;
    private String branchName;
    private String doctorName;
    private int unbilledServiceCount;
    private int unbilledPrescriptionCount;
}

