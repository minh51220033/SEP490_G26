package com.cvdcms.repository;

import com.cvdcms.entity.ParaclinicalOrder;
import com.cvdcms.entity.enums.ParaclinicalOrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ParaclinicalOrderRepository extends JpaRepository<ParaclinicalOrder, Long> {
    List<ParaclinicalOrder> findByVisit_VisitIdOrderByOrderTimeDesc(Long visitId);
    Optional<ParaclinicalOrder> findFirstByVisit_VisitId(Long visitId);
    long countByVisit_VisitIdAndStatus(Long visitId, ParaclinicalOrderStatus status);
}
