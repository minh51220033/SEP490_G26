package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** FT43 / SCR-43.4: một dòng trong lịch sử thực hiện quy trình khám. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowHistoryEntryDTO {
    private String time;
    private String stepName;
    private String action;
    private String actionLabel;
    private String actorName;
    private String fromStatus;
    private String toStatus;
    private String note;
}