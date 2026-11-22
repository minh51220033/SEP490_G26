package com.cvdcms.DTO;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OdontogramResponseDTO {
    private Long odontogramId;
    private Long patientId;
    private Long visitId;
    private String visitCode;
    private String chartDate;
    private String chartDateDisplay;
    private String chartType;
    private String note;
    private Integer statusCount;
    private Integer planCount;
    private Integer affectedTeethCount;
    private String createdByName;
    private String createdAt;
    private String createdAtIso;
    private String updatedByName;
    private String updatedAt;
    private String payloadJson;
}
