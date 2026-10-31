package com.cvdcms.DTO;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class AccountCreateDTO {

    private String username;

    private String password;

    private String confirmPassword;

    private String email;

    private String phoneNumber;

    private String fullName;

    private Integer roleId;

    // Chi nhánh của tài khoản (không bắt buộc)
    private Long branchId;

    // Danh sách ID quyền hạn được tích chọn cho tài khoản này (trong phạm vi tập quyền của Role)
    private List<Long> permissionIds;

    // Đánh dấu form tạo tài khoản có gửi kèm cấu hình checkbox quyền hạn
    private Boolean customPermissionsSubmitted = false;
}