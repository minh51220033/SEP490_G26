package com.cvdcms.DTO;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class ProfileUpdateDTO {

    @NotBlank(message = "Họ tên không được để trống.")
    @Size(
            max = 100,
            message = "Họ tên không được vượt quá 100 ký tự."
    )
    @Pattern(
            regexp = "^[\\p{L}]+(?:\\s[\\p{L}]+)*$",
            message = "Họ tên chỉ được chứa chữ cái và khoảng trắng."
    )
    private String fullName;


    @NotBlank(message = "Email không được để trống.")
    @Size(
            max = 100,
            message = "Email không được vượt quá 100 ký tự."
    )
    @Email(
            message = "Email không đúng định dạng."
    )
    private String email;


    @NotBlank(message = "Số điện thoại không được để trống.")
    @Pattern(
            regexp = "^(0|\\+84)(3|5|7|8|9)[0-9]{8}$",
            message = "Số điện thoại không hợp lệ. Ví dụ: 0987654321."
    )
    private String phoneNumber;


    @PastOrPresent(
            message = "Ngày sinh không được lớn hơn ngày hiện tại."
    )
    private LocalDate dob;


    @Pattern(
            regexp = "^$|^(Nam|Nữ|Khác)$",
            message = "Giới tính không hợp lệ."
    )
    private String gender;


    @Size(
            max = 255,
            message = "Địa chỉ không được vượt quá 255 ký tự."
    )
    private String address;
}