package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParaclinicalOrderCreateDTO {
    private Long serviceId;
    private String customServiceName;
    private String toothNumbers;
    private String notes;
}
