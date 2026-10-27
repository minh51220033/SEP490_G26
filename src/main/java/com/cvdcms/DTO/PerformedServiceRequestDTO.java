package com.cvdcms.DTO;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PerformedServiceRequestDTO {

    @NotNull(message = "ID ca khám không được để trống")
    private Long visitId;

    @NotNull(message = "Dịch vụ / Thủ thuật không được để trống")
    private Long serviceId;

    private String toothNumbers;

    private BigDecimal quantity;

    private BigDecimal unitPrice;

    private BigDecimal discountAmount;

    private BigDecimal finalPrice;

    @NotNull(message = "Bác sĩ thực hiện không được để trống")
    private Long doctorId;

    private Long assistantId;

    private String clinicalNotes;

    private Long planItemId;
}
