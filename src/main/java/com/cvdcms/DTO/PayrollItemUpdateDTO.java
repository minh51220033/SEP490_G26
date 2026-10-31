package com.cvdcms.DTO;

import jakarta.validation.constraints.DecimalMin;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class PayrollItemUpdateDTO {

    @DecimalMin(value = "0", message = "Tiền thưởng không được âm")
    private BigDecimal bonusAmount = BigDecimal.ZERO;

    @DecimalMin(value = "0", message = "Khoản giảm trừ không được âm")
    private BigDecimal deductionAmount = BigDecimal.ZERO;

    private String note;
}
