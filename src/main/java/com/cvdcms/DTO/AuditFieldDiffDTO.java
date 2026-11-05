package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditFieldDiffDTO {

    private String fieldName;
    private String fieldLabel;
    private String oldValue;
    private String newValue;
    private String diffType; // ADDED, MODIFIED, REMOVED, UNCHANGED
}
