package com.cvdcms.patient;

import com.cvdcms.entity.SystemAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PatientAuditRepository extends JpaRepository<SystemAuditLog, Long> {}
