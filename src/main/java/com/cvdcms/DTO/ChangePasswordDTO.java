package com.cvdcms.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChangePasswordDTO {

    @NotBlank(message = "Mật khẩu hiện tại không được để trống.")
    private String currentPassword;


    @NotBlank(message = "Mật khẩu mới không được để trống.")
    @Size(
            min = 8,
            max = 64,
            message = "Mật khẩu phải từ 8 đến 64 ký tự."
    )
    private String newPassword;


    @NotBlank(message = "Vui lòng xác nhận mật khẩu mới.")
    private String confirmPassword;
}