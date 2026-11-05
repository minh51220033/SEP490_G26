package com.cvdcms.repository;

import com.cvdcms.entity.WorkflowStepExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowStepExecutionRepository extends JpaRepository<WorkflowStepExecution, Long> {

    List<WorkflowStepExecution> findByExecution_ExecutionId(Long executionId);

    // FT43: danh sách bước thực thi theo đúng thứ tự bước của mẫu quy trình
    List<WorkflowStepExecution> findByExecution_ExecutionIdOrderByStep_SequenceOrderAsc(Long executionId);

    Optional<WorkflowStepExecution> findByExecution_ExecutionIdAndStep_StepId(Long executionId, Long stepId);
}
