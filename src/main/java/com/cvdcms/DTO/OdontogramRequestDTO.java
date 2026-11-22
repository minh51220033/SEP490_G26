package com.cvdcms.DTO;

import lombok.Data;

import java.time.LocalDate;

@Data
public class OdontogramRequestDTO {
    private Long visitId;
    private LocalDate chartDate;
    private String chartType;
    private String note;
    private String payloadJson;
    private Integer statusCount;
    private Integer planCount;
    private Integer affectedTeethCount;
}
