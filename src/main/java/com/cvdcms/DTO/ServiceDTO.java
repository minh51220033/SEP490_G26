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
public class ServiceDTO {
    private Long serviceId;
    private Long categoryId;
    private String categoryName;
    private String serviceCode;
    private String serviceName;
    private String serviceType;
    private String unit;
    private BigDecimal basePrice;
    private Boolean requiresTooth;
    private Boolean isParaclinical;
    private String description;
}
