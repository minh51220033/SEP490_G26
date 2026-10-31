package com.cvdcms.DTO;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
public class SupplierPaymentCreateDTO {
    private Long branchId;
    private Long supplierId;
    private Long receiptId;
    private LocalDate paymentDate;
    private BigDecimal amount;
    private String paymentMethod; // CASH, BANK_TRANSFER
    private String referenceNo;
    private String notes;
    private String clientRequestId;
}
