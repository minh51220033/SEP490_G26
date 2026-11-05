package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLogDetailDTO {

    private Long logId;
    private Long userId;
    private String username;
    private String userFullName;
    private String userRoleName;
    private String userEmail;

    private Long branchId;
    private String branchName;

    private String action;
    private String actionBadgeClass;
    private String actionIcon;

    private String entityTable;
    private String entityTableLabel;
    private Long entityId;

    private String ipAddress;
    private String formattedCreatedAt;

    private String oldValuesJsonRaw;
    private String newValuesJsonRaw;
    private String oldValuesJsonPretty;
    private String newValuesJsonPretty;

    @Builder.Default
    private List<AuditFieldDiffDTO> fieldDiffs = new ArrayList<>();

    private boolean isCreate;
    private boolean isDelete;
    private boolean isUpdate;
}
