package com.cvdcms.DTO;

import com.cvdcms.entity.enums.RefundMethod;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class RefundRequestDTO {
    private Long invoiceId;
    private Long profileId;
    private Long branchId;
    private BigDecimal amount = BigDecimal.ZERO;
    private RefundMethod refundMethod = RefundMethod.CASH;
    private String reason;
}
