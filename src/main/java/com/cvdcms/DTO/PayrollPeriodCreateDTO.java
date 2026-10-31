package com.cvdcms.DTO;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class PayrollPeriodCreateDTO {

    @NotNull(message = "Chi nhánh không được để trống")
    private Long branchId;

    @NotNull(message = "Tháng không được để trống")
    @Min(value = 1, message = "Tháng từ 1 đến 12")
    @Max(value = 12, message = "Tháng từ 1 đến 12")
    private Integer month;

    @NotNull(message = "Năm không được để trống")
    @Min(value = 2020, message = "Năm không hợp lệ")
    private Integer year;

    private String periodName;
}
