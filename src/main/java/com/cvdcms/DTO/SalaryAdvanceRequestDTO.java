package com.cvdcms.DTO;

import com.cvdcms.entity.enums.RefundMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class SalaryAdvanceRequestDTO {

    @NotNull(message = "Nhân viên không được để trống")
    private Long staffId;

    @NotNull(message = "Chi nhánh không được để trống")
    private Long branchId;

    @NotNull(message = "Số tiền tạm ứng không được để trống")
    @DecimalMin(value = "1000", message = "Số tiền tạm ứng tối thiểu là 1.000 VNĐ")
    private BigDecimal amount;

    @NotBlank(message = "Lý do tạm ứng không được để trống")
    private String reason;

    @NotNull(message = "Phương thức chi trả không được để trống")
    private RefundMethod paymentMethod = RefundMethod.BANK_TRANSFER;

    private String notes;
}
