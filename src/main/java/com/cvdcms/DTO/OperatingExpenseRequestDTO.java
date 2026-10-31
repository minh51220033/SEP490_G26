package com.cvdcms.DTO;

import com.cvdcms.entity.enums.RefundMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
public class OperatingExpenseRequestDTO {

    @NotNull(message = "Chi nhánh không được để trống")
    private Long branchId;

    @NotBlank(message = "Danh mục chi phí không được để trống")
    private String category;

    @NotNull(message = "Số tiền không được để trống")
    @DecimalMin(value = "1000", message = "Số tiền chi phí tối thiểu là 1.000 VNĐ")
    private BigDecimal amount;

    @NotNull(message = "Ngày chi không được để trống")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate expenseDate = LocalDate.now();

    private String recipientName;

    @NotNull(message = "Phương thức thanh toán không được để trống")
    private RefundMethod paymentMethod = RefundMethod.BANK_TRANSFER;

    @NotBlank(message = "Mô tả lý do chi không được để trống")
    private String description;
}
