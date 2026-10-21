package com.cvdcms.repository;

import com.cvdcms.entity.WorkflowExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecution, Long> {

    List<WorkflowExecution> findByVisit_VisitId(Long visitId);

    Optional<WorkflowExecution> findFirstByVisit_VisitIdAndVersion_Template_Service_ServiceId(Long visitId, Long serviceId);
}
