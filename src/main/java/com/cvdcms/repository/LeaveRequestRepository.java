package com.cvdcms.repository;

import com.cvdcms.entity.LeaveRequest;
import com.cvdcms.entity.enums.LeaveStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

// FT42: Quản lý yêu cầu nghỉ phép & Khóa lịch bác sĩ (SCR-42.1 -> SCR-42.5)
@Repository
public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {

    @Query("""
        SELECT l FROM LeaveRequest l LEFT JOIN FETCH l.shift 
        WHERE (l.branch.branchId = :branchId OR l.shift IS NULL) 
          AND l.startDate <= :date AND l.endDate >= :date 
          AND l.status = :status
    """)
    List<LeaveRequest> findApprovedOn(@Param("branchId") Long branchId, @Param("date") LocalDate date,
                                      @Param("status") LeaveStatus status);

    @Query("SELECT l FROM LeaveRequest l JOIN FETCH l.branch LEFT JOIN FETCH l.shift LEFT JOIN FETCH l.reviewedBy WHERE l.staff.staffId = :staffId ORDER BY l.submittedAt DESC")
    List<LeaveRequest> findByStaffStaffIdOrderBySubmittedAtDesc(@Param("staffId") Long staffId);

    long countByStatus(LeaveStatus status);

    long countByStaff_StaffIdAndStatus(Long staffId, LeaveStatus status);

    @Query("""
        SELECT COUNT(l) > 0 FROM LeaveRequest l
        WHERE l.staff.staffId = :staffId
          AND l.status IN :statuses
          AND l.startDate <= :endDate
          AND l.endDate >= :startDate
          AND (:shiftId IS NULL OR l.shift IS NULL OR l.shift.shiftId = :shiftId)
    """)
    boolean existsOverlapping(
            @Param("staffId") Long staffId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("shiftId") Long shiftId,
            @Param("statuses") List<LeaveStatus> statuses
    );

    @Query("""
        SELECT l FROM LeaveRequest l
        JOIN FETCH l.staff s
        JOIN FETCH l.branch b
        LEFT JOIN FETCH l.shift sh
        LEFT JOIN FETCH l.reviewedBy rb
        WHERE (:branchId IS NULL OR b.branchId = :branchId)
          AND (:status IS NULL OR l.status = :status)
          AND (:staffId IS NULL OR s.staffId = :staffId)
          AND (:startDate IS NULL OR l.endDate >= :startDate)
          AND (:endDate IS NULL OR l.startDate <= :endDate)
          AND (:keyword IS NULL OR LOWER(s.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(s.staffCode) LIKE LOWER(CONCAT('%', :keyword, '%')))
        ORDER BY l.submittedAt DESC
    """)
    List<LeaveRequest> filterAdmin(
            @Param("branchId") Long branchId,
            @Param("status") LeaveStatus status,
            @Param("staffId") Long staffId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("keyword") String keyword
    );

    @Query("""
        SELECT l FROM LeaveRequest l
        JOIN FETCH l.staff s
        JOIN FETCH l.branch b
        LEFT JOIN FETCH l.shift sh
        LEFT JOIN FETCH l.reviewedBy rb
        WHERE l.leaveId = :leaveId
    """)
    Optional<LeaveRequest> findByIdWithDetails(@Param("leaveId") Long leaveId);
}
