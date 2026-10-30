package com.cvdcms.repository;

import com.cvdcms.entity.OwnerDistributionItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OwnerDistributionItemRepository extends JpaRepository<OwnerDistributionItem, Long> {

    @Query("""
        SELECT item FROM OwnerDistributionItem item
        JOIN FETCH item.recipientStaff s
        WHERE item.distribution.distributionId = :distributionId
        ORDER BY item.itemId ASC
    """)
    List<OwnerDistributionItem> findByDistributionIdWithStaff(@Param("distributionId") Long distributionId);
}
