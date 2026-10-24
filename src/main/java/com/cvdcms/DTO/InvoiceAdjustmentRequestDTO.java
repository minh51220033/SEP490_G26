package com.cvdcms.DTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class InvoiceAdjustmentRequestDTO {
    private String reason;
    private String notes;
    private BigDecimal newTotalAmount;
    private BigDecimal newDiscountAmount;
    private Boolean cancelInvoice = false;
}
