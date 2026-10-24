package com.cvdcms.repository;

import com.cvdcms.entity.ParaclinicalOrderItem;
import com.cvdcms.entity.enums.ParaclinicalOrderItemStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ParaclinicalOrderItemRepository extends JpaRepository<ParaclinicalOrderItem, Long> {
    List<ParaclinicalOrderItem> findByOrder_Visit_VisitIdOrderByOrder_OrderIdDescOrderItemIdDesc(Long visitId);
    List<ParaclinicalOrderItem> findByOrder_OrderId(Long orderId);
    long countByOrder_Visit_VisitIdAndStatus(Long visitId, ParaclinicalOrderItemStatus status);
}
