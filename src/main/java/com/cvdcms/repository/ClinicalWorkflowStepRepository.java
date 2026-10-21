package com.cvdcms.repository;

import com.cvdcms.entity.ClinicalWorkflowStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClinicalWorkflowStepRepository extends JpaRepository<ClinicalWorkflowStep, Long> {

    List<ClinicalWorkflowStep> findByVersion_VersionIdOrderBySequenceOrderAsc(Long versionId);

    void deleteByVersion_VersionId(Long versionId);
}
