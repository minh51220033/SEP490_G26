package com.cvdcms.repository;

import com.cvdcms.entity.TreatmentPlanItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TreatmentPlanItemRepository extends JpaRepository<TreatmentPlanItem, Long> {
    List<TreatmentPlanItem> findByStage_StageId(Long stageId);
}
