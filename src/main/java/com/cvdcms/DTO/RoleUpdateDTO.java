package com.cvdcms.DTO;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class RoleUpdateDTO {

    private Integer roleId;

    // Danh sách ID quyền hạn được tích chọn riêng cho tài khoản này (trong phạm vi tập quyền của Role)
    private List<Long> permissionIds;

    // Đánh dấu form phân quyền có gửi kèm cấu hình checkbox quyền hạn
    private Boolean customPermissionsSubmitted = false;

}