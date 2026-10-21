package com.cvdcms.repository;

import com.cvdcms.entity.ClinicalWorkflowVersion;
import com.cvdcms.entity.enums.WorkflowVersionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClinicalWorkflowVersionRepository extends JpaRepository<ClinicalWorkflowVersion, Long> {

    List<ClinicalWorkflowVersion> findByTemplate_TemplateIdOrderByVersionNumberDesc(Long templateId);

    Optional<ClinicalWorkflowVersion> findFirstByTemplate_TemplateIdAndStatus(Long templateId, WorkflowVersionStatus status);

    Optional<ClinicalWorkflowVersion> findFirstByTemplate_TemplateIdOrderByVersionNumberDesc(Long templateId);
}
