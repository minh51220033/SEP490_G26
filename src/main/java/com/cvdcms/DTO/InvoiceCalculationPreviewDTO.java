package com.cvdcms.DTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class InvoiceCalculationPreviewDTO {
    private BigDecimal totalServiceAmount = BigDecimal.ZERO;
    private BigDecimal memberDiscountPercent = BigDecimal.ZERO;
    private BigDecimal memberDiscountAmount = BigDecimal.ZERO;
    private BigDecimal promotionDiscountAmount = BigDecimal.ZERO;
    private BigDecimal pointDiscountAmount = BigDecimal.ZERO;
    private BigDecimal totalDiscountAmount = BigDecimal.ZERO;
    private BigDecimal totalAmount = BigDecimal.ZERO;
    private Integer pointsEarnable = 0;
    private String promoName;
    private String tierName;
}
