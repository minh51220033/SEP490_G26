package com.cvdcms.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierCreateDTO {

    @NotBlank(message = "Mã nhà cung cấp không được để trống.")
    @Size(max = 30, message = "Mã nhà cung cấp tối đa 30 ký tự.")
    @Pattern(
            regexp = "^[A-Za-z0-9_-]{2,30}$",
            message = "Mã chỉ được chứa chữ, số, dấu gạch ngang và gạch dưới."
    )
    private String supplierCode;

    @NotBlank(message = "Tên nhà cung cấp không được để trống.")
    @Size(max = 200, message = "Tên nhà cung cấp tối đa 200 ký tự.")
    private String supplierName;

    @Size(max = 20, message = "Mã số thuế tối đa 20 ký tự.")
    @Pattern(
            regexp = "^$|^[0-9]{10}(-[0-9]{3})?$",
            message = "Mã số thuế không đúng định dạng được hỗ trợ."
    )
    private String taxCode;

    @Size(max = 20, message = "Số điện thoại tối đa 20 ký tự.")
    @Pattern(
            regexp = "^$|^0[0-9]{9,10}$",
            message = "Số điện thoại phải bắt đầu bằng 0 và có 10 hoặc 11 chữ số."
    )
    private String phoneNumber;

    @Size(max = 255, message = "Địa chỉ tối đa 255 ký tự.")
    private String address;
}