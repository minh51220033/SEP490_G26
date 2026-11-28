package com.cvdcms.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO đầu vào dùng để cập nhật thông tin Nhà cung cấp vật tư hiện có.
 *
 * <p><b>Được gọi từ:</b> {@code PUT /api/suppliers/{id}} ({@link com.cvdcms.controller.SupplierController#update})
 * và màn hình Quản lý Nhà cung cấp ({@code suppliers.js} khi submit form {@code #supplierForm} ở chế độ chỉnh sửa).</p>
 *
 * <p><b>Lưu ý:</b> DTO này không chứa trường {@code isActive}; việc kích hoạt/ngừng hoạt động được xử lý riêng
 * qua các endpoint {@code /activate} và {@code /deactivate}.</p>
 */
@Getter
@Setter
public class SupplierUpdateDTO {

    /** Mã nhà cung cấp mới (2–30 ký tự, chỉ gồm chữ, số, {@code -}, {@code _}; không trùng với NCC khác). */
    @NotBlank(message = "Mã nhà cung cấp không được để trống.")
    @Size(max = 30, message = "Mã nhà cung cấp tối đa 30 ký tự.")
    @Pattern(
            regexp = "^[A-Za-z0-9_-]{2,30}$",
            message = "Mã chỉ được chứa chữ, số, dấu gạch ngang và gạch dưới."
    )
    private String supplierCode;

    /** Tên nhà cung cấp (bắt buộc, tối đa 200 ký tự). */
    @NotBlank(message = "Tên nhà cung cấp không được để trống.")
    @Size(max = 200, message = "Tên nhà cung cấp tối đa 200 ký tự.")
    private String supplierName;

    /** Mã số thuế (tùy chọn ở tầng DTO, tối đa 20 ký tự; định dạng 10 số hoặc {@code 10số-3số}; không trùng NCC khác). */
    @Size(max = 20, message = "Mã số thuế tối đa 20 ký tự.")
    @Pattern(
            regexp = "^$|^[0-9]{10}(-[0-9]{3})?$",
            message = "Mã số thuế không đúng định dạng được hỗ trợ."
    )
    private String taxCode;

    /** Số điện thoại liên hệ (tùy chọn ở tầng DTO, bắt đầu bằng {@code 0} hoặc {@code +84}, theo sau 9–10 chữ số). */
    @Size(max = 20, message = "Số điện thoại tối đa 20 ký tự.")
    @Pattern(
            regexp = "^$|^(0|\\+84)[0-9]{9,10}$",
            message = "Số điện thoại phải bắt đầu bằng 0 hoặc +84, theo sau 9 hoặc 10 chữ số."
    )
    private String phoneNumber;

    /** Địa chỉ trụ sở hoặc kho của nhà cung cấp (tối đa 255 ký tự). */
    @Size(max = 255, message = "Địa chỉ tối đa 255 ký tự.")
    private String address;
}