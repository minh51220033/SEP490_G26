package com.cvdcms.repository;

import com.cvdcms.entity.TaxPolicy;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface TaxPolicyRepository extends JpaRepository<TaxPolicy, Long> {

    Optional<TaxPolicy> findByPolicyCode(String policyCode);

    @Query("SELECT tp FROM TaxPolicy tp " +
           "WHERE tp.isActive = true " +
           "  AND tp.effectiveFrom <= :targetDate " +
           "  AND (tp.effectiveTo IS NULL OR tp.effectiveTo >= :targetDate) " +
           "ORDER BY tp.effectiveFrom DESC, tp.policyId DESC")
    List<TaxPolicy> findActivePoliciesAtDate(@Param("targetDate") LocalDate targetDate, Pageable pageable);

    /**
     * Truy vấn đối soát (SELECT thuần) các chính sách thuế đang hoạt động bị chồng khoảng hiệu lực.
     */
    @Query("SELECT tp FROM TaxPolicy tp " +
           "WHERE tp.isActive = true " +
           "  AND EXISTS (" +
           "      SELECT 1 FROM TaxPolicy tp2 " +
           "      WHERE tp2.isActive = true " +
           "        AND tp2.policyId <> tp.policyId " +
           "        AND (tp.effectiveTo IS NULL OR tp2.effectiveFrom <= tp.effectiveTo) " +
           "        AND (tp2.effectiveTo IS NULL OR tp2.effectiveTo >= tp.effectiveFrom)" +
           "  ) " +
           "ORDER BY tp.effectiveFrom DESC, tp.policyId DESC")
    List<TaxPolicy> findOverlappingActivePolicies();
}

