package com.cvdcms.repository;

import com.cvdcms.entity.BranchTaxConfig;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface BranchTaxConfigRepository extends JpaRepository<BranchTaxConfig, Long> {

    List<BranchTaxConfig> findByBranch_BranchIdOrderByEffectiveFromDesc(Long branchId);

    @Query("SELECT btc FROM BranchTaxConfig btc " +
           "WHERE btc.branch.branchId = :branchId " +
           "  AND btc.effectiveFrom <= :targetDate " +
           "  AND (btc.effectiveTo IS NULL OR btc.effectiveTo >= :targetDate) " +
           "ORDER BY btc.effectiveFrom DESC, btc.configId DESC")
    List<BranchTaxConfig> findActiveConfigsAtDate(
            @Param("branchId") Long branchId,
            @Param("targetDate") LocalDate targetDate,
            Pageable pageable
    );

    /**
     * Truy vấn đối soát (SELECT thuần) các cấu hình thuế chi nhánh bị chồng khoảng hiệu lực trong cùng chi nhánh.
     */
    @Query("SELECT btc FROM BranchTaxConfig btc " +
           "WHERE EXISTS (" +
           "      SELECT 1 FROM BranchTaxConfig btc2 " +
           "      WHERE btc2.branch.branchId = btc.branch.branchId " +
           "        AND btc2.configId <> btc.configId " +
           "        AND (btc.effectiveTo IS NULL OR btc2.effectiveFrom <= btc.effectiveTo) " +
           "        AND (btc2.effectiveTo IS NULL OR btc2.effectiveTo >= btc.effectiveFrom)" +
           "  ) " +
           "ORDER BY btc.branch.branchId ASC, btc.effectiveFrom DESC, btc.configId DESC")
    List<BranchTaxConfig> findOverlappingBranchConfigs();
}

