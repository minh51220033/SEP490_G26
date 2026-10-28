package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ManagerOptionDTO {
    private Long userId;
    private String username;
    private String fullName;
    private String roleDisplayName;
    private String displayName;
}
