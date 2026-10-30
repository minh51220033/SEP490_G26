package com.cvdcms.repository;

import com.cvdcms.entity.PayrollPeriod;
import com.cvdcms.entity.enums.PayrollStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;

@Repository
public interface PayrollPeriodRepository extends JpaRepository<PayrollPeriod, Long> {

    Optional<PayrollPeriod> findByBranch_BranchIdAndMonthAndYear(Long branchId, Integer month, Integer year);

    boolean existsByBranch_BranchIdAndMonthAndYear(Long branchId, Integer month, Integer year);

    @Query("""
        SELECT p FROM PayrollPeriod p
        JOIN FETCH p.branch b
        LEFT JOIN FETCH p.submittedBy sb
        LEFT JOIN FETCH p.approvedBy ab
        LEFT JOIN FETCH p.paidBy pb
        WHERE p.periodId = :periodId
    """)
    Optional<PayrollPeriod> findWithDetailsById(@Param("periodId") Long periodId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PayrollPeriod p WHERE p.periodId = :periodId")
    Optional<PayrollPeriod> findByIdForUpdate(@Param("periodId") Long periodId);

    @Query("""
        SELECT p FROM PayrollPeriod p
        WHERE (:branchIds IS NULL OR p.branch.branchId IN :branchIds)
          AND (:month IS NULL OR p.month = :month)
          AND (:year IS NULL OR p.year = :year)
          AND (:status IS NULL OR p.status = :status)
        ORDER BY p.year DESC, p.month DESC, p.periodId DESC
    """)
    Page<PayrollPeriod> findFilteredPeriods(
            @Param("branchIds") Collection<Long> branchIds,
            @Param("month") Integer month,
            @Param("year") Integer year,
            @Param("status") PayrollStatus status,
            Pageable pageable
    );
}
