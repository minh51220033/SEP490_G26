package com.cvdcms.repository;

import com.cvdcms.entity.DoctorShiftReplacementLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface DoctorShiftReplacementLogRepository extends JpaRepository<DoctorShiftReplacementLog, Long> {

    @Query("""
        SELECT l FROM DoctorShiftReplacementLog l
        JOIN FETCH l.branch b
        JOIN FETCH l.shift sh
        JOIN FETCH l.room r
        JOIN FETCH l.replacedForStaff os
        JOIN FETCH l.replacementStaff rs
        JOIN FETCH l.replacedByUser u
        WHERE l.branch.branchId = :branchId
          AND l.workDate BETWEEN :fromDate AND :toDate
        ORDER BY l.replacedAt DESC, l.replacementLogId DESC
    """)
    List<DoctorShiftReplacementLog> findByBranchAndDateRangeOrderByReplacedAtDesc(
            @Param("branchId") Long branchId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);
}
