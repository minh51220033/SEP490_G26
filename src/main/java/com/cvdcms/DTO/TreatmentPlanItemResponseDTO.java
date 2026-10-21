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
public class TreatmentPlanItemResponseDTO {
    private Long planItemId;
    private Long serviceId;
    private String serviceCode;
    private String serviceName;
    private String categoryName;
    private String unit;
    private String toothNumbers;
    private BigDecimal quantity;
    private BigDecimal estimatedPrice;
    private BigDecimal totalPrice;
    private String status; // PLANNED, IN_PROGRESS, COMPLETED, CANCELLED
    private String statusBadgeClass;
    private String statusDisplayName;
}
