package com.cvdcms.repository;

import com.cvdcms.entity.Promotion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PromotionRepository extends JpaRepository<Promotion, Long> {

    Optional<Promotion> findByPromoCode(String promoCode);

    @Query("""
        SELECT p FROM Promotion p
        WHERE p.isActive = true
          AND p.startDate <= :now
          AND p.endDate >= :now
          AND (p.branch IS NULL OR p.branch.branchId = :branchId)
          AND (p.usageLimit IS NULL OR p.usedCount < p.usageLimit)
        ORDER BY p.discountValue DESC
    """)
    List<Promotion> findAvailablePromotions(
            @Param("branchId") Long branchId,
            @Param("now") LocalDateTime now
    );
}
