package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentAllocationDTO {
    private Long allocationId;
    private Long paymentId;
    private String paymentCode;
    private Long itemId;
    private String itemTitle;
    private String itemType;
    private Integer lineOrder;
    private BigDecimal allocatedAmount;
    private BigDecimal remainingDebtAfter;
    private String statusAfter;
    private LocalDateTime paidAt;
    private String paymentMethod;
    private String paymentMethodName;
    private String cashierName;
    private String notes;

    public String getItemName() {
        return itemTitle;
    }

    public void setItemName(String itemName) {
        this.itemTitle = itemName;
    }

    public BigDecimal getAmount() {
        return allocatedAmount;
    }

    public void setAmount(BigDecimal amount) {
        this.allocatedAmount = amount;
    }
}
