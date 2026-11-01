package com.cvdcms.DTO;

import com.cvdcms.entity.enums.PaymentMethod;
import com.cvdcms.entity.enums.PaymentType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class PaymentRequestDTO {
    private Long invoiceId;
    private Long profileId;
    private Long branchId;
    private PaymentType paymentType = PaymentType.PAYMENT;
    private PaymentMethod paymentMethod = PaymentMethod.CASH;
    private BigDecimal amount = BigDecimal.ZERO;
    private Long bankAccountId;
    private String expectedTransferContent;
    private String transactionRef;
    private String idempotencyKey;
    private String notes;
}
