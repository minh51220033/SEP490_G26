package com.cvdcms.emr;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmrAuditEventRepository extends JpaRepository<EmrAuditEvent, Long> {
    List<EmrAuditEvent> findByRecord_RecordIdOrderByCreatedAtDesc(Long recordId);
}
