package com.cvdcms.DTO;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class OwnerDistributionRequestDTO {

    private Long branchId;

    @NotNull(message = "Tháng không được để trống")
    @Min(value = 1, message = "Tháng từ 1 đến 12")
    @Max(value = 12, message = "Tháng từ 1 đến 12")
    private Integer month;

    @NotNull(message = "Năm không được để trống")
    @Min(value = 2020, message = "Năm không hợp lệ")
    private Integer year;

    @NotNull(message = "Lợi nhuận có thể chia không được để trống")
    @DecimalMin(value = "0", message = "Lợi nhuận phải >= 0")
    private BigDecimal distributableAmount = BigDecimal.ZERO;

    @NotNull(message = "Số tiền giữ lại tái đầu tư không được để trống")
    @DecimalMin(value = "0", message = "Số tiền giữ lại phải >= 0")
    private BigDecimal retainedAmount = BigDecimal.ZERO;

    @NotNull(message = "Tổng tiền chia không được để trống")
    @DecimalMin(value = "0", message = "Tổng tiền chia phải >= 0")
    private BigDecimal totalDistributedAmount = BigDecimal.ZERO;

    private String basisNotes;

    @NotEmpty(message = "Danh sách người nhận không được để trống")
    @Valid
    private List<OwnerDistributionItemDTO> items = new ArrayList<>();
}
