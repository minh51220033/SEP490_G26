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
    // Tong phu thu sau dieu chinh, khong phai so tien cong them moi lan luu.
    private BigDecimal newSurchargeAmount;
    private String newSurchargeDescription;
}
