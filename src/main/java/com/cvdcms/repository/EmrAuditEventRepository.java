package com.cvdcms.repository;

import com.cvdcms.entity.EmrAuditEvent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmrAuditEventRepository extends JpaRepository<EmrAuditEvent, Long> {
    List<EmrAuditEvent> findByRecord_RecordIdOrderByCreatedAtDesc(Long recordId);
}
