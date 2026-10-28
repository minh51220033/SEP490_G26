package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LinkableUserDTO {
    private Long userId;
    private String username;
    private String email;
    private String roleIds;     // Danh sách role_id ngăn cách bởi dấu phẩy, ví dụ: "1,2"
    private String roleNames;   // Danh sách role_name ngăn cách bởi dấu phẩy, ví dụ: "BAC_SI,ADMIN"
    private String displayName; // username (email) - Tên vai trò
}
