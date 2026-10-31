package com.cvdcms.repository;

import com.cvdcms.entity.PayrollItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PayrollItemRepository extends JpaRepository<PayrollItem, Long> {

    List<PayrollItem> findByPayrollPeriod_PeriodId(Long periodId);

    Optional<PayrollItem> findByPayrollPeriod_PeriodIdAndStaff_StaffId(Long periodId, Long staffId);

    @Query("""
        SELECT i FROM PayrollItem i
        JOIN FETCH i.staff s
        LEFT JOIN FETCH s.user u
        WHERE i.payrollPeriod.periodId = :periodId
        ORDER BY s.fullName ASC
    """)
    List<PayrollItem> findByPeriodIdWithStaff(@Param("periodId") Long periodId);

    @Query("""
        SELECT i FROM PayrollItem i
        JOIN FETCH i.payrollPeriod p
        JOIN FETCH i.staff s
        WHERE i.itemId = :itemId
    """)
    Optional<PayrollItem> findWithDetailsById(@Param("itemId") Long itemId);

    @Modifying
    @Query("DELETE FROM PayrollItem i WHERE i.payrollPeriod.periodId = :periodId")
    void deleteByPayrollPeriod_PeriodId(@Param("periodId") Long periodId);

    boolean existsByStaff_StaffIdAndPeriodMonthAndPeriodYear(Long staffId, Integer periodMonth, Integer periodYear);

    Optional<PayrollItem> findByStaff_StaffIdAndPeriodMonthAndPeriodYear(Long staffId, Integer periodMonth, Integer periodYear);
}
