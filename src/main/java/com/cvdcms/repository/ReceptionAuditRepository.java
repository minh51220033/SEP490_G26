package com.cvdcms.repository;
import com.cvdcms.entity.SystemAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ReceptionAuditRepository extends JpaRepository<SystemAuditLog, Long> { }
