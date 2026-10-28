package com.cvdcms.reception;
import com.cvdcms.entity.SystemAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ReceptionAuditRepository extends JpaRepository<SystemAuditLog, Long> { }
