package com.cvdcms.DTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
public class SupplierPaymentResponseDTO {
    private Long supplierPaymentId;
    private String paymentCode;
    private Long branchId;
    private String branchName;
    private Long supplierId;
    private String supplierCode;
    private String supplierName;
    private Long receiptId;
    private String receiptCode;
    private LocalDate paymentDate;
    private BigDecimal amount;
    private String paymentMethod;
    private String referenceNo;
    private String paidByName;
    private String notes;
    private LocalDateTime createdAt;
}
