package com.cvdcms.repository;

import com.cvdcms.entity.MaterialConsumption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MaterialConsumptionRepository extends JpaRepository<MaterialConsumption, Long> {

    List<MaterialConsumption> findByPerformedService_PerformedId(Long performedId);

    List<MaterialConsumption> findByVisit_VisitIdOrderByConsumedAtDesc(Long visitId);
}
