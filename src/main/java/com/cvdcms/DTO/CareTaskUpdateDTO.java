package com.cvdcms.DTO;

import com.cvdcms.entity.enums.CustomerCareTaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CareTaskUpdateDTO {
    private Long taskId;
    private CustomerCareTaskStatus status;
    private String contactResult; // "CONFIRMED", "RESCHEDULE", "NO_ANSWER", "CANCELLED"
    private String notes;
}
