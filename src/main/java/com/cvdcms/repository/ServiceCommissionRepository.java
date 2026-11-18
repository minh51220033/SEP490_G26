package com.cvdcms.repository;

import com.cvdcms.entity.ServiceCommission;
import com.cvdcms.entity.enums.CommissionRoleType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ServiceCommissionRepository extends JpaRepository<ServiceCommission, Long> {

    boolean existsByPerformedService_PerformedIdAndStaff_StaffIdAndRoleType(
            Long performedId,
            Long staffId,
            CommissionRoleType roleType
    );

    List<ServiceCommission> findByPerformedService_PerformedId(Long performedId);

    List<ServiceCommission> findByPayrollItem_ItemId(Long itemId);

    List<ServiceCommission> findByPayrollItem_PayrollPeriod_PeriodId(Long periodId);

    /**
     * Khóa và tải duy nhất một danh sách hoa hồng PENDING chưa gắn kỳ lương, thuộc dịch vụ COMPLETED
     * trong nửa khoảng [startDate, endDateExclusive) để vừa tính tổng vừa gắn vào PayrollItem trong cùng transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT sc FROM ServiceCommission sc
        JOIN sc.performedService ps
        JOIN ps.service s
        WHERE sc.staff.staffId = :staffId
          AND sc.payrollItem IS NULL
          AND sc.status = com.cvdcms.entity.enums.CommissionStatus.PENDING
          AND ps.status = com.cvdcms.entity.enums.PerformedServiceStatus.COMPLETED
          AND sc.calculatedAt >= :startDate
          AND sc.calculatedAt < :endDateExclusive
        ORDER BY sc.commissionId ASC
    """)
    List<ServiceCommission> findPendingUnboundCommissionsForStaffInPeriodForUpdate(
            @Param("staffId") Long staffId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDateExclusive") LocalDateTime endDateExclusive
    );

    /**
     * Truy vấn đối soát các hoa hồng phát sinh muộn chưa gắn kỳ lương cho tập nhân sự của một kỳ [startDate, endDateExclusive).
     */
    @Query("""
        SELECT sc FROM ServiceCommission sc
        JOIN FETCH sc.performedService ps
        JOIN FETCH ps.service s
        JOIN FETCH sc.staff st
        WHERE sc.payrollItem IS NULL
          AND sc.status = com.cvdcms.entity.enums.CommissionStatus.PENDING
          AND ps.status = com.cvdcms.entity.enums.PerformedServiceStatus.COMPLETED
          AND sc.staff.staffId IN :staffIds
          AND sc.calculatedAt >= :startDate
          AND sc.calculatedAt < :endDateExclusive
        ORDER BY sc.calculatedAt ASC, sc.commissionId ASC
    """)
    List<ServiceCommission> findUnboundPendingCommissionsForStaffListInPeriod(
            @Param("staffIds") List<Long> staffIds,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDateExclusive") LocalDateTime endDateExclusive
    );

    @Modifying(flushAutomatically = true)
    @Query("UPDATE ServiceCommission sc SET sc.payrollItem = null WHERE sc.payrollItem.payrollPeriod.periodId = :periodId")
    void unbindCommissionsFromPeriod(@Param("periodId") Long periodId);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE ServiceCommission sc SET sc.status = com.cvdcms.entity.enums.CommissionStatus.PAID WHERE sc.payrollItem.payrollPeriod.periodId = :periodId")
    void markCommissionsAsPaidForPeriod(@Param("periodId") Long periodId);
}
