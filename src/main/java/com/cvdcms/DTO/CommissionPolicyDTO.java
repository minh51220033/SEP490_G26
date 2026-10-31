package com.cvdcms.DTO;

import com.cvdcms.entity.enums.CommissionType;
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
public class CommissionPolicyDTO {

    private Long policyId;

    @NotBlank(message = "Tên chính sách không được để trống")
    private String policyName;

    private Long serviceId;

    private Long roleId;

    private Long staffId;

    @NotNull(message = "Loại hoa hồng không được để trống")
    private CommissionType commissionType = CommissionType.PERCENTAGE;

    @NotNull(message = "Giá trị hoa hồng không được để trống")
    @DecimalMin(value = "0", message = "Giá trị hoa hồng phải >= 0")
    private BigDecimal commissionValue;

    private Boolean isActive = true;
}
