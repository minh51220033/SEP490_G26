package com.cvdcms.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO đầu vào dùng để tạo mới Nhà cung cấp vật tư.
 *
 * <p><b>Được gọi từ:</b> {@code POST /api/suppliers} ({@link com.cvdcms.controller.SupplierController#create})
 * và màn hình Quản lý Nhà cung cấp ({@code suppliers.js} khi submit form {@code #supplierForm} ở chế độ thêm mới).</p>
 *
 * <p><b>Validation:</b> Kiểm tra bắt buộc, độ dài và định dạng regex qua Bean Validation ({@code @Valid});
 * kiểm tra trùng lặp mã NCC và mã số thuế được thực hiện ở {@link com.cvdcms.service.impl.SupplierServiceImpl#create}.</p>
 */
@Getter
@Setter
public class SupplierCreateDTO {

    /** Mã nhà cung cấp (2–30 ký tự, chỉ gồm chữ, số, dấu {@code -} và {@code _}; sẽ được chuẩn hóa chữ hoa). */
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

    /** Mã số thuế (tùy chọn ở tầng DTO, tối đa 20 ký tự; định dạng 10 chữ số hoặc {@code 10số-3số}). */
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