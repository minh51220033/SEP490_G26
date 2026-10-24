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
public class TreatmentPlanItemRequestDTO {
    private Long serviceId;
    private String customServiceName;
    private String toothNumbers;
    private BigDecimal quantity;
    private BigDecimal estimatedPrice;
    private String status; // PLANNED, IN_PROGRESS, COMPLETED, CANCELLED
    private String notes;
}
