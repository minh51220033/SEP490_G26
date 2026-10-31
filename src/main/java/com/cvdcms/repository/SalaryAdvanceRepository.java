package com.cvdcms.repository;

import com.cvdcms.entity.SalaryAdvance;
import com.cvdcms.entity.enums.SalaryAdvanceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface SalaryAdvanceRepository extends JpaRepository<SalaryAdvance, Long> {

    Optional<SalaryAdvance> findByAdvanceCode(String advanceCode);

    @Query("""
        SELECT a FROM SalaryAdvance a
        JOIN FETCH a.staff s
        JOIN FETCH a.branch b
        JOIN FETCH a.createdBy u
        LEFT JOIN FETCH a.approvedBy ab
        WHERE a.advanceId = :advanceId
    """)
    Optional<SalaryAdvance> findWithDetailsById(@Param("advanceId") Long advanceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM SalaryAdvance a WHERE a.advanceId = :advanceId")
    Optional<SalaryAdvance> findByIdForUpdate(@Param("advanceId") Long advanceId);

    @Query("""
        SELECT a FROM SalaryAdvance a
        WHERE a.staff.staffId = :staffId
          AND a.status = :status
          AND a.remainingAmount > :minRemaining
        ORDER BY a.paidAt ASC, a.createdAt ASC
    """)
    List<SalaryAdvance> findActiveAdvancesForSettlement(
            @Param("staffId") Long staffId,
            @Param("status") SalaryAdvanceStatus status,
            @Param("minRemaining") BigDecimal minRemaining
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT a FROM SalaryAdvance a
        WHERE a.staff.staffId = :staffId
          AND a.status = :status
          AND a.remainingAmount > :minRemaining
        ORDER BY a.advanceId ASC
    """)
    List<SalaryAdvance> findActiveAdvancesForSettlementForUpdate(
            @Param("staffId") Long staffId,
            @Param("status") SalaryAdvanceStatus status,
            @Param("minRemaining") BigDecimal minRemaining
    );

    @Query("""
        SELECT a FROM SalaryAdvance a
        WHERE (:branchIds IS NULL OR a.branch.branchId IN :branchIds)
          AND (:status IS NULL OR a.status = :status)
          AND (:staffId IS NULL OR a.staff.staffId = :staffId)
          AND (:startDate IS NULL OR a.createdAt >= :startDate)
          AND (:endDate IS NULL OR a.createdAt <= :endDate)
          AND (
              :keyword IS NULL OR :keyword = ''
              OR LOWER(a.advanceCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
              OR LOWER(a.staff.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
              OR LOWER(a.reason) LIKE LOWER(CONCAT('%', :keyword, '%'))
          )
        ORDER BY a.createdAt DESC
    """)
    Page<SalaryAdvance> findFilteredAdvances(
            @Param("branchIds") Collection<Long> branchIds,
            @Param("status") SalaryAdvanceStatus status,
            @Param("staffId") Long staffId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("keyword") String keyword,
            Pageable pageable
    );
}
