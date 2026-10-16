
package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserRegisterDTO {

    // Tên đăng nhập, phải duy nhất trong hệ thống.
    private String username;

    // Mật khẩu người dùng nhập khi đăng ký.
    // Không lưu trực tiếp giá trị này vào database.
    private String password;

    // Mật khẩu xác nhận, chỉ dùng để đối chiếu.
    private String confirmPassword;

    // Email của tài khoản, phải duy nhất.
    private String email;

    // Số điện thoại của tài khoản, phải duy nhất.
    private String phoneNumber;
}
