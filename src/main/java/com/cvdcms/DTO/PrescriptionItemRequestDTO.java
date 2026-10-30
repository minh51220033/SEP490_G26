package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrescriptionItemRequestDTO {
    private Long medicationId;
    private String drugName;
    private String dosage;
    private Integer durationDays;
    private BigDecimal quantity;
    private String unit;
    private String usageInstruction;
    private BigDecimal unitPrice;
}
