package com.cvdcms.repository;

import com.cvdcms.entity.ClinicalWorkflowTemplate;
import com.cvdcms.entity.enums.WorkflowTemplateStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClinicalWorkflowTemplateRepository extends JpaRepository<ClinicalWorkflowTemplate, Long> {

    List<ClinicalWorkflowTemplate> findAllByOrderByCreatedAtDesc();

    Optional<ClinicalWorkflowTemplate> findByTemplateCode(String templateCode);

    List<ClinicalWorkflowTemplate> findByService_ServiceId(Long serviceId);

    Optional<ClinicalWorkflowTemplate> findFirstByService_ServiceIdAndStatus(Long serviceId, WorkflowTemplateStatus status);

    boolean existsByTemplateCode(String templateCode);
}
