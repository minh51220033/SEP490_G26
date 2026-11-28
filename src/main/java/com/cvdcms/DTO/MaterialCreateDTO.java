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
 * DTO nhận dữ liệu đầu vào khi thêm mới vật tư vào danh mục dùng chung ({@code POST /api/materials}).
 * <p>Các ràng buộc Bean Validation được kiểm tra tự động tại {@link com.cvdcms.controller.MaterialController#create}.</p>
 */
@Getter
@Setter
public class MaterialCreateDTO {

    /** Mã vật tư duy nhất (2–30 ký tự chữ, số, {@code _}, {@code -}; sẽ được chuẩn hóa chữ hoa ở service). */
    @NotBlank(message = "Mã vật tư không được để trống.")
    @Size(max = 30, message = "Mã vật tư tối đa 30 ký tự.")
    @Pattern(
            regexp = "^[A-Za-z0-9_-]{2,30}$",
            message = "Mã vật tư chỉ được chứa chữ, số, dấu gạch ngang và gạch dưới (2-30 ký tự)."
    )
    private String materialCode;

    /** Tên hiển thị của vật tư (tối đa 200 ký tự). */
    @NotBlank(message = "Tên vật tư không được để trống.")
    @Size(max = 200, message = "Tên vật tư tối đa 200 ký tự.")
    private String materialName;

    /** Nhóm phân loại vật tư (tối đa 100 ký tự). */
    @NotBlank(message = "Nhóm vật tư không được để trống.")
    @Size(max = 100, message = "Nhóm vật tư tối đa 100 ký tự.")
    private String category;

    /** Đơn vị tính cơ bản (tối đa 30 ký tự, ví dụ: Hộp, Tuýp, Viên). */
    @NotBlank(message = "Đơn vị tính không được để trống.")
    @Size(max = 30, message = "Đơn vị tính tối đa 30 ký tự.")
    private String unit;

    /** Ngưỡng tồn kho tối thiểu cảnh báo sắp hết hàng (mặc định {@code 10.000}, không âm). */
    @NotNull(message = "Ngưỡng tồn tối thiểu không được để trống.")
    @DecimalMin(value = "0.000", message = "Ngưỡng tồn tối thiểu không được âm.")
    private BigDecimal minStockThreshold = new BigDecimal("10.000");
}
