package com.cvdcms.repository;

import com.cvdcms.entity.OperatingExpense;
import com.cvdcms.entity.enums.ExpenseSourceType;
import com.cvdcms.entity.enums.RefundMethod;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;

@Repository
public interface OperatingExpenseRepository extends JpaRepository<OperatingExpense, Long> {

    Optional<OperatingExpense> findByExpenseCode(String expenseCode);

    boolean existsByPayrollPeriod_PeriodId(Long periodId);

    boolean existsBySalaryAdvance_AdvanceId(Long advanceId);

    Optional<OperatingExpense> findByPayrollPeriod_PeriodId(Long periodId);

    Optional<OperatingExpense> findBySalaryAdvance_AdvanceId(Long advanceId);

    @Query("""
        SELECT e FROM OperatingExpense e
        JOIN FETCH e.branch b
        JOIN FETCH e.createdBy u
        LEFT JOIN FETCH e.cancelledBy cb
        LEFT JOIN FETCH e.payrollPeriod pp
        LEFT JOIN FETCH e.salaryAdvance sa
        WHERE e.expenseId = :expenseId
    """)
    Optional<OperatingExpense> findWithDetailsById(@Param("expenseId") Long expenseId);

    @Query("""
        SELECT e FROM OperatingExpense e
        WHERE (:branchIds IS NULL OR e.branch.branchId IN :branchIds)
          AND (:category IS NULL OR :category = '' OR e.category = :category)
          AND (:sourceType IS NULL OR e.sourceType = :sourceType)
          AND (:paymentMethod IS NULL OR e.paymentMethod = :paymentMethod)
          AND (:startDate IS NULL OR e.expenseDate >= :startDate)
          AND (:endDate IS NULL OR e.expenseDate <= :endDate)
          AND (:isCancelled IS NULL OR e.isCancelled = :isCancelled)
          AND (
              :keyword IS NULL OR :keyword = ''
              OR LOWER(e.expenseCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
              OR LOWER(e.recipientName) LIKE LOWER(CONCAT('%', :keyword, '%'))
              OR LOWER(e.description) LIKE LOWER(CONCAT('%', :keyword, '%'))
          )
    """)
    Page<OperatingExpense> findFilteredExpenses(
            @Param("branchIds") Collection<Long> branchIds,
            @Param("category") String category,
            @Param("sourceType") ExpenseSourceType sourceType,
            @Param("paymentMethod") RefundMethod paymentMethod,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("isCancelled") Boolean isCancelled,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Query("""
        SELECT COALESCE(SUM(e.amount), 0) FROM OperatingExpense e
        WHERE (:branchIds IS NULL OR e.branch.branchId IN :branchIds)
          AND (:startDate IS NULL OR e.expenseDate >= :startDate)
          AND (:endDate IS NULL OR e.expenseDate <= :endDate)
          AND e.isCancelled = false
    """)
    BigDecimal sumActiveExpenses(
            @Param("branchIds") Collection<Long> branchIds,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    @Query("""
        SELECT COALESCE(SUM(e.amount), 0) FROM OperatingExpense e
        WHERE (:branchIds IS NULL OR e.branch.branchId IN :branchIds)
          AND (:category IS NULL OR :category = '' OR e.category = :category)
          AND (:sourceType IS NULL OR e.sourceType = :sourceType)
          AND (:paymentMethod IS NULL OR e.paymentMethod = :paymentMethod)
          AND (:startDate IS NULL OR e.expenseDate >= :startDate)
          AND (:endDate IS NULL OR e.expenseDate <= :endDate)
          AND (
              :keyword IS NULL OR :keyword = ''
              OR LOWER(e.expenseCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
              OR LOWER(e.recipientName) LIKE LOWER(CONCAT('%', :keyword, '%'))
              OR LOWER(e.description) LIKE LOWER(CONCAT('%', :keyword, '%'))
          )
          AND e.isCancelled = false
    """)
    BigDecimal sumFilteredActiveExpenses(
            @Param("branchIds") Collection<Long> branchIds,
            @Param("category") String category,
            @Param("sourceType") ExpenseSourceType sourceType,
            @Param("paymentMethod") RefundMethod paymentMethod,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("keyword") String keyword
    );
}
