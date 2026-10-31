package com.cvdcms.repository;

import com.cvdcms.entity.SystemAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SystemAuditLogRepository extends JpaRepository<SystemAuditLog, Long> {

    List<SystemAuditLog> findByEntityTableAndEntityIdOrderByCreatedAtDesc(String entityTable, Long entityId);
}
