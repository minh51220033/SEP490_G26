package com.cvdcms.repository;

import com.cvdcms.entity.EmrAttachment;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface EmrAttachmentRepository extends JpaRepository<EmrAttachment, Long> {
    List<EmrAttachment> findByVersion_VersionIdOrderByIdAsc(Long versionId);
}
