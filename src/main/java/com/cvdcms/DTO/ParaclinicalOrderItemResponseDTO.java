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
public class ParaclinicalOrderItemResponseDTO {
    private Long orderItemId;
    private Long orderId;
    private String orderCode;
    private Long serviceId;
    private String serviceCode;
    private String serviceName;
    private String serviceType;
    private BigDecimal price;
    private String toothNumbers;
    private String status;
    private String statusLabel;
    private String doctorName;
    private String orderTime;
    private String performedAt;
    private String resultSummary;
    private int attachmentsCount;
}
