package com.cvdcms.repository;

import com.cvdcms.entity.OwnerDistribution;
import com.cvdcms.entity.enums.OwnerDistributionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OwnerDistributionRepository extends JpaRepository<OwnerDistribution, Long> {

    Optional<OwnerDistribution> findByDistributionCode(String distributionCode);

    @Query("""
        SELECT d FROM OwnerDistribution d
        LEFT JOIN FETCH d.branch b
        LEFT JOIN FETCH d.submittedBy sb
        LEFT JOIN FETCH d.approvedBy ab
        LEFT JOIN FETCH d.paidBy pb
        WHERE d.distributionId = :distributionId
    """)
    Optional<OwnerDistribution> findWithDetailsById(@Param("distributionId") Long distributionId);

    @Query("""
        SELECT d FROM OwnerDistribution d
        WHERE (:branchIds IS NULL OR d.branch.branchId IN :branchIds)
          AND (:month IS NULL OR d.month = :month)
          AND (:year IS NULL OR d.year = :year)
          AND (:status IS NULL OR d.status = :status)
        ORDER BY d.year DESC, d.month DESC, d.distributionId DESC
    """)
    Page<OwnerDistribution> findFilteredDistributions(
            @Param("branchIds") java.util.Collection<Long> branchIds,
            @Param("month") Integer month,
            @Param("year") Integer year,
            @Param("status") OwnerDistributionStatus status,
            Pageable pageable
    );
}
