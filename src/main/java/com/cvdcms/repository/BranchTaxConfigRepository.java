package com.cvdcms.repository;

import com.cvdcms.entity.BranchTaxConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface BranchTaxConfigRepository extends JpaRepository<BranchTaxConfig, Long> {

    List<BranchTaxConfig> findByBranch_BranchIdOrderByEffectiveFromDesc(Long branchId);

    @Query("SELECT btc FROM BranchTaxConfig btc " +
           "WHERE btc.branch.branchId = :branchId " +
           "  AND btc.effectiveFrom <= :targetDate " +
           "  AND (btc.effectiveTo IS NULL OR btc.effectiveTo >= :targetDate) " +
           "ORDER BY btc.effectiveFrom DESC")
    Optional<BranchTaxConfig> findActiveConfigAtDate(@Param("branchId") Long branchId, @Param("targetDate") LocalDate targetDate);
}

