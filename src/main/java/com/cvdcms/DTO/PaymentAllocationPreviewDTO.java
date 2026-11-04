package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentAllocationPreviewDTO {

    private Long invoiceId;
    private String invoiceCode;
    private BigDecimal paymentAmount;
    private BigDecimal invoiceTotalAmount;
    private BigDecimal invoicePaidAmount;
    private BigDecimal invoiceRemainingDebt;
    private BigDecimal remainingDebtAfterPayment;
    private List<ItemAllocationPreview> items;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ItemAllocationPreview {
        private Integer lineOrder;
        private Long itemId;
        private String itemType; // SERVICE, MEDICINE, SURCHARGE
        private String itemTitle;
        private BigDecimal originalAmount;
        private BigDecimal discountAmount;
        private BigDecimal payableAmount;
        private BigDecimal previouslyPaidAmount;
        private BigDecimal currentDebt;
        private BigDecimal allocatedAmount;
        private BigDecimal remainingDebtAfter;
        private String statusAfter; // UNPAID, PARTIAL, PAID
    }
}

