package com.cvdcms.DTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * DTO hiển thị lịch sử tương tác chăm sóc khách hàng (SCR-44.4 & SCR-44.6 Timeline).
 */
@Getter
@Setter
@NoArgsConstructor
public class CustomerCareInteractionDTO {
    private Long interactionId;
    private Long taskId;
    private String taskCode;
    private String taskType;
    private String careGroupLabel;
    private String taskStatus;

    private Long profileId;
    private String patientCode;
    private String patientName;
    private String patientPhone;

    private Long branchId;
    private String branchName;

    private Long staffId;
    private String staffCode;
    private String staffName;
    private String staffPosition;

    private String channel;
    private String channelLabel;

    private String contactResult;
    private String contactResultLabel;

    private String notes;
    private LocalDateTime nextRetryAt;
    private LocalDateTime createdAt;
}
