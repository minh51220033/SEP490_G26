package com.cvdcms.repository;

import com.cvdcms.entity.SalaryAdvanceAllocation;
import com.cvdcms.entity.enums.AdvanceAllocationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface SalaryAdvanceAllocationRepository extends JpaRepository<SalaryAdvanceAllocation, Long> {

    List<SalaryAdvanceAllocation> findByPayrollItem_ItemId(Long itemId);

    List<SalaryAdvanceAllocation> findByPayrollItem_ItemIdAndStatus(Long itemId, AdvanceAllocationStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM SalaryAdvanceAllocation a WHERE a.payrollItem.itemId = :itemId AND a.status = :status ORDER BY a.allocationId ASC")
    List<SalaryAdvanceAllocation> findByPayrollItem_ItemIdAndStatusForUpdate(
            @Param("itemId") Long itemId,
            @Param("status") AdvanceAllocationStatus status
    );

    List<SalaryAdvanceAllocation> findByAdvance_AdvanceIdAndStatus(Long advanceId, AdvanceAllocationStatus status);

    @Query("""
        SELECT COALESCE(SUM(a.allocatedAmount), 0) FROM SalaryAdvanceAllocation a
        WHERE a.advance.advanceId = :advanceId
          AND a.status = :status
          AND (:excludeItemId IS NULL OR a.payrollItem.itemId != :excludeItemId)
    """)
    BigDecimal sumAllocatedByAdvanceAndStatusExcludingItem(
            @Param("advanceId") Long advanceId,
            @Param("status") AdvanceAllocationStatus status,
            @Param("excludeItemId") Long excludeItemId
    );

    @Modifying
    @Query("DELETE FROM SalaryAdvanceAllocation a WHERE a.payrollItem.payrollPeriod.periodId = :periodId")
    void deleteByPeriodId(@Param("periodId") Long periodId);
}
