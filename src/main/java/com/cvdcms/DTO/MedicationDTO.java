package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MedicationDTO {
    private Long medicationId;
    private String drugCode;
    private String drugName;
    private String activeIngredient;
    private String unit;
    private String usageInstruction;
    private Boolean isActive;
}
