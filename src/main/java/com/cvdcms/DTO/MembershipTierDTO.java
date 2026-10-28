package com.cvdcms.DTO;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class MembershipTierDTO {

    private Long tierId;

    @NotBlank(message = "Mã hạng không được để trống")
    @Size(max = 20, message = "Mã hạng không được vượt quá 20 ký tự")
    @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "Mã hạng chỉ được chứa chữ cái, số và dấu gạch dưới")
    private String tierCode;

    @NotBlank(message = "Tên hạng không được để trống")
    @Size(max = 50, message = "Tên hạng không được vượt quá 50 ký tự")
    private String tierName;

    @NotNull(message = "Ngưỡng chi tiêu tối thiểu không được để trống")
    @DecimalMin(value = "0.00", message = "Ngưỡng chi tiêu tối thiểu phải lớn hơn hoặc bằng 0")
    private BigDecimal minTotalSpent = BigDecimal.ZERO;

    @NotNull(message = "Tỷ lệ chiết khấu không được để trống")
    @DecimalMin(value = "0.00", message = "Chiết khấu phải lớn hơn hoặc bằng 0%")
    @DecimalMax(value = "100.00", message = "Chiết khấu không được vượt quá 100%")
    private BigDecimal discountPercent = BigDecimal.ZERO;

    @NotNull(message = "Mức chi tiêu đổi 1 điểm không được để trống")
    @DecimalMin(value = "1000.00", message = "Mức chi tiêu quy đổi điểm tối thiểu là 1.000 đ")
    private BigDecimal spendPerPoint = new BigDecimal("10000.00");

    @NotNull(message = "Giá trị quy đổi 1 điểm không được để trống")
    @DecimalMin(value = "0.00", message = "Giá trị quy đổi của 1 điểm phải >= 0 đ")
    private BigDecimal pointValue = new BigDecimal("1000.00");

    @NotNull(message = "Thứ tự hiển thị không được để trống")
    @Min(value = 1, message = "Thứ tự hiển thị phải lớn hơn 0")
    private Integer displayOrder = 1;

    private Boolean isActive = true;
}
