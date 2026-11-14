package com.cvdcms.DTO;

import com.cvdcms.entity.enums.PaymentMethod;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class InstallmentPaymentRequestDTO {
    private Long planId;
    private Long installmentId;
    private BigDecimal amount = BigDecimal.ZERO;
    private PaymentMethod paymentMethod = PaymentMethod.CASH;
    private String notes;
}

