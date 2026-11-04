package com.cvdcms.service;

import com.cvdcms.DTO.AuditLogDetailDTO;
import com.cvdcms.DTO.AuditLogFilterDTO;
import com.cvdcms.DTO.AuditLogResponseDTO;
import com.cvdcms.DTO.AuditSummaryStatsDTO;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.User;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Map;

public interface SystemAuditLogService {

    Page<AuditLogResponseDTO> getAuditLogsPage(AuditLogFilterDTO filter);

    AuditLogDetailDTO getAuditLogDetail(Long logId);

    AuditSummaryStatsDTO getSummaryStats();

    List<String> getDistinctActions();

    List<String> getDistinctEntityTables();

    List<Branch> getAllBranches();

    Map<String, String> getEntityTableLabels();

    void logAction(User user, Branch branch, String action, String entityTable, Long entityId, String ipAddress, Object oldVal, Object newVal);
}
