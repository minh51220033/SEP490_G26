package com.cvdcms.repository;

import com.cvdcms.entity.ServiceCommission;
import com.cvdcms.entity.enums.CommissionRoleType;
import com.cvdcms.entity.enums.CommissionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ServiceCommissionRepository extends JpaRepository<ServiceCommission, Long> {

    boolean existsByPerformedService_PerformedIdAndStaff_StaffIdAndRoleType(
            Long performedId,
            Long staffId,
            CommissionRoleType roleType
    );

    List<ServiceCommission> findByPayrollItem_ItemId(Long itemId);

    List<ServiceCommission> findByPayrollItem_PayrollPeriod_PeriodId(Long periodId);

    @Query("""
        SELECT sc FROM ServiceCommission sc
        JOIN FETCH sc.performedService ps
        JOIN FETCH ps.service s
        WHERE sc.staff.staffId = :staffId
          AND sc.payrollItem IS NULL
          AND sc.status = :status
          AND sc.calculatedAt >= :startDate
          AND sc.calculatedAt <= :endDate
    """)
    List<ServiceCommission> findUnpaidCommissionsForStaffInPeriod(
            @Param("staffId") Long staffId,
            @Param("status") CommissionStatus status,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    @Query("""
        SELECT COALESCE(SUM(sc.commissionAmount), 0) FROM ServiceCommission sc
        WHERE sc.staff.staffId = :staffId
          AND sc.payrollItem IS NULL
          AND sc.status != 'CANCELLED'
          AND sc.calculatedAt >= :startDate
          AND sc.calculatedAt <= :endDate
    """)
    BigDecimal sumUnpaidCommissionsForStaffInPeriod(
            @Param("staffId") Long staffId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );
}
