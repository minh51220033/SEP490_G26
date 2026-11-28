package com.cvdcms.DTO;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * DTO nhận dữ liệu đầu vào khi cập nhật thông tin vật tư hiện có ({@code PUT /api/materials/{id}}).
 * <p>Với vật tư đồng bộ từ danh mục thuốc, {@link com.cvdcms.service.impl.MaterialServiceImpl#update} chỉ cho phép đổi nhóm và ngưỡng tồn.</p>
 */
@Getter
@Setter
public class MaterialUpdateDTO {

    /** Mã vật tư (2–30 ký tự chữ, số, {@code _}, {@code -}). */
    @NotBlank(message = "Mã vật tư không được để trống.")
    @Size(max = 30, message = "Mã vật tư tối đa 30 ký tự.")
    @Pattern(
            regexp = "^[A-Za-z0-9_-]{2,30}$",
            message = "Mã vật tư chỉ được chứa chữ, số, dấu gạch ngang và gạch dưới (2-30 ký tự)."
    )
    private String materialCode;

    /** Tên vật tư (tối đa 200 ký tự). */
    @NotBlank(message = "Tên vật tư không được để trống.")
    @Size(max = 200, message = "Tên vật tư tối đa 200 ký tự.")
    private String materialName;

    /** Nhóm vật tư (tối đa 100 ký tự). */
    @NotBlank(message = "Nhóm vật tư không được để trống.")
    @Size(max = 100, message = "Nhóm vật tư tối đa 100 ký tự.")
    private String category;

    /** Đơn vị tính (tối đa 30 ký tự). */
    @NotBlank(message = "Đơn vị tính không được để trống.")
    @Size(max = 30, message = "Đơn vị tính tối đa 30 ký tự.")
    private String unit;

    /** Ngưỡng tồn kho tối thiểu (mặc định {@code 10.000}, không âm). */
    @NotNull(message = "Ngưỡng tồn tối thiểu không được để trống.")
    @DecimalMin(value = "0.000", message = "Ngưỡng tồn tối thiểu không được âm.")
    private BigDecimal minStockThreshold = new BigDecimal("10.000");
}
