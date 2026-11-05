package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLogResponseDTO {

    private Long logId;
    private Long userId;
    private String username;
    private String userFullName;
    private String userRoleName;
    private String userAvatarText;

    private Long branchId;
    private String branchName;

    private String action;
    private String actionBadgeClass;
    private String actionIcon;

    private String entityTable;
    private String entityTableLabel;
    private Long entityId;

    private String oldValuesJson;
    private String newValuesJson;
    private boolean hasChanges;

    private String ipAddress;
    private LocalDateTime createdAt;
    private String formattedCreatedAt;
    private String timeAgo;
}
