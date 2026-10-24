package com.cvdcms.repository;

import com.cvdcms.entity.TreatmentPlanStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TreatmentPlanStageRepository extends JpaRepository<TreatmentPlanStage, Long> {
    List<TreatmentPlanStage> findByPlan_PlanIdOrderByStageOrderAsc(Long planId);
}
