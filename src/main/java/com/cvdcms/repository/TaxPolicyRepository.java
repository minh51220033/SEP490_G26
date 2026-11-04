package com.cvdcms.repository;

import com.cvdcms.entity.TaxPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface TaxPolicyRepository extends JpaRepository<TaxPolicy, Long> {

    Optional<TaxPolicy> findByPolicyCode(String policyCode);

    @Query("SELECT tp FROM TaxPolicy tp " +
           "WHERE tp.isActive = true " +
           "  AND tp.effectiveFrom <= :targetDate " +
           "  AND (tp.effectiveTo IS NULL OR tp.effectiveTo >= :targetDate) " +
           "ORDER BY tp.effectiveFrom DESC")
    Optional<TaxPolicy> findActivePolicyAtDate(@Param("targetDate") LocalDate targetDate);
}

