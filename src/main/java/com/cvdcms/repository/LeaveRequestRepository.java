package com.cvdcms.repository;

import com.cvdcms.entity.LeaveRequest;
import com.cvdcms.entity.enums.LeaveStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {
    @Query("SELECT l FROM LeaveRequest l LEFT JOIN FETCH l.shift WHERE l.branch.branchId = :branchId AND l.startDate <= :date AND l.endDate >= :date AND l.status = :status")
    List<LeaveRequest> findApprovedOn(@Param("branchId") Long branchId, @Param("date") LocalDate date,
                                      @Param("status") LeaveStatus status);
}
