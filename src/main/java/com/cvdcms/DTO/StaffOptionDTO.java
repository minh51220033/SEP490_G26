package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class StaffOptionDTO {
    private Long staffId;
    private String staffCode;
    private String fullName;
}
