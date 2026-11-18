package com.cvdcms.DTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class ParaclinicalBackfillReportDTO {
    private boolean dryRun;
    private int totalCandidates;
    private int processedCount;
    private int skippedCount;
    private int errorCount;
    private List<Long> affectedOrderItemIds = new ArrayList<>();
    private List<Long> createdPerformedIds = new ArrayList<>();
    private List<String> errors = new ArrayList<>();
}
