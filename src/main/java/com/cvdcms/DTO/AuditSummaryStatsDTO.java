package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditSummaryStatsDTO {

    private long totalLogs;
    private long todayLogs;
    private long createLogs;
    private long updateLogs;
    private long sensitiveLogs;
}
