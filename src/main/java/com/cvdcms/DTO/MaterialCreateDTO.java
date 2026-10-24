package com.cvdcms.DTO;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class MaterialCreateDTO {

    @NotBlank(message = "Mã vật tư không được để trống.")
    @Size(max = 30, message = "Mã vật tư tối đa 30 ký tự.")
    @Pattern(
            regexp = "^[A-Za-z0-9_-]{2,30}$",
            message = "Mã vật tư chỉ được chứa chữ, số, dấu gạch ngang và gạch dưới (2-30 ký tự)."
    )
    private String materialCode;

    @NotBlank(message = "Tên vật tư không được để trống.")
    @Size(max = 200, message = "Tên vật tư tối đa 200 ký tự.")
    private String materialName;

    @NotBlank(message = "Nhóm vật tư không được để trống.")
    @Size(max = 100, message = "Nhóm vật tư tối đa 100 ký tự.")
    private String category;

    @NotBlank(message = "Đơn vị tính không được để trống.")
    @Size(max = 30, message = "Đơn vị tính tối đa 30 ký tự.")
    private String unit;

    @NotNull(message = "Ngưỡng tồn tối thiểu không được để trống.")
    @DecimalMin(value = "0.000", message = "Ngưỡng tồn tối thiểu không được âm.")
    private BigDecimal minStockThreshold = new BigDecimal("10.000");
}
