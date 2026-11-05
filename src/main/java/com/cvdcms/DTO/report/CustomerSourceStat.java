package com.cvdcms.DTO.report;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CustomerSourceStat {
    private String sourceName;
    private long patientCount;
    private double percentage;
}

