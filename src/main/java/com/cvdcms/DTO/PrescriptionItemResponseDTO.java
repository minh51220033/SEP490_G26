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
public class PrescriptionItemResponseDTO {
    private Long itemId;
    private Long medicationId;
    private String drugName;
    private String dosage;
    private Integer durationDays;
    private BigDecimal quantity;
    private BigDecimal boughtQuantity;
    private BigDecimal remainingQuantity;
    private String unit;
    private String usageInstruction;
    private BigDecimal unitPrice;
    private BigDecimal finalPrice;

    public Long getPrescriptionItemId() {
        return itemId;
    }
}
