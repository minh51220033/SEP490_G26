package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SupplierDebtSummaryDTO {
    private Long supplierId;
    private String supplierCode;
    private String supplierName;
    private String taxCode;
    private String phoneNumber;
    private Boolean isActive;
    private int receiptCount;
    private BigDecimal totalImportAmount;
    private BigDecimal totalPaidAmount;
    private BigDecimal remainingDebt;
}
