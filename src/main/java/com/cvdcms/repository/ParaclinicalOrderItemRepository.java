package com.cvdcms.repository;

import com.cvdcms.entity.ParaclinicalOrderItem;
import com.cvdcms.entity.enums.ParaclinicalOrderItemStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ParaclinicalOrderItemRepository extends JpaRepository<ParaclinicalOrderItem, Long> {
    List<ParaclinicalOrderItem> findByOrder_Visit_VisitIdOrderByOrder_OrderIdDescOrderItemIdDesc(Long visitId);
    List<ParaclinicalOrderItem> findByOrder_OrderId(Long orderId);
    long countByOrder_Visit_VisitIdAndStatus(Long visitId, ParaclinicalOrderItemStatus status);
    Optional<ParaclinicalOrderItem> findByExternalRefIdIgnoreCase(String externalRefId);
    List<ParaclinicalOrderItem> findByOrder_OrderCodeIgnoreCase(String orderCode);
    boolean existsByPerformedService_PerformedId(Long performedId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM ParaclinicalOrderItem i WHERE i.orderItemId = :orderItemId")
    Optional<ParaclinicalOrderItem> findByIdForUpdate(@Param("orderItemId") Long orderItemId);

    @Query("SELECT i.orderItemId FROM ParaclinicalOrderItem i WHERE i.status = com.cvdcms.entity.enums.ParaclinicalOrderItemStatus.COMPLETED AND i.performedService IS NULL ORDER BY i.orderItemId ASC")
    List<Long> findCompletedOrderItemIdsMissingPerformedService();
}
