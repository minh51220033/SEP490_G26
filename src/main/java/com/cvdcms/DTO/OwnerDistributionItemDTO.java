package com.cvdcms.DTO;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class OwnerDistributionItemDTO {

    private Long itemId;

    @NotNull(message = "Người nhận không được để trống")
    private Long recipientStaffId;

    private BigDecimal percentage;

    @NotNull(message = "Số tiền chia không được để trống")
    @DecimalMin(value = "0", message = "Số tiền phải >= 0")
    private BigDecimal amount = BigDecimal.ZERO;

    private String note;
}
