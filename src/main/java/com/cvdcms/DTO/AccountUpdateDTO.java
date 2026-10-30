package com.cvdcms.DTO;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class AccountUpdateDTO {

    private String email;

    private String phoneNumber;

    private String fullName;

    // Chi nhánh của tài khoản (để trống = không gán chi nhánh)
    private Long branchId;

    // Vai trò và quyền hạn được phân quyền
    private Integer roleId;

    private List<Long> permissionIds;

    private Boolean customPermissionsSubmitted = false;

}
