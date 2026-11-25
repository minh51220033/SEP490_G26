package com.cvdcms.repository;

import com.cvdcms.entity.DoctorShiftAssignment;
import com.cvdcms.entity.enums.DoctorShiftAssignmentStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface DoctorShiftAssignmentRepository extends JpaRepository<DoctorShiftAssignment, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")})
    @Query("SELECT d FROM DoctorShiftAssignment d WHERE d.shiftAssignmentId = :id")
    Optional<DoctorShiftAssignment> lockById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")})
    @Query("""
        SELECT d FROM DoctorShiftAssignment d
        WHERE d.staff.staffId = :staffId
          AND d.workDate = :workDate
        ORDER BY d.shiftAssignmentId ASC
    """)
    List<DoctorShiftAssignment> lockByStaffAndDateOrderByIdAsc(
            @Param("staffId") Long staffId,
            @Param("workDate") LocalDate workDate);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")})
    @Query("""
        SELECT d FROM DoctorShiftAssignment d
        WHERE d.staff.staffId = :staffId
          AND d.workDate BETWEEN :fromDate AND :toDate
          AND d.status = :status
        ORDER BY d.shiftAssignmentId ASC
    """)
    List<DoctorShiftAssignment> lockByStaffAndDateRangeAndStatusOrderByIdAsc(
            @Param("staffId") Long staffId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("status") DoctorShiftAssignmentStatus status);

    @Query("""
        SELECT d FROM DoctorShiftAssignment d
        JOIN FETCH d.staff st
        JOIN FETCH d.shift sh
        JOIN FETCH d.room rm
        JOIN FETCH d.branch br
        LEFT JOIN FETCH d.chair ch
        LEFT JOIN FETCH d.replacedForStaff rf
        LEFT JOIN FETCH d.replacedByUser rb
        WHERE d.branch.branchId = :branchId
          AND d.workDate BETWEEN :fromDate AND :toDate
          AND d.status <> com.cvdcms.entity.enums.DoctorShiftAssignmentStatus.CANCELLED
        ORDER BY d.workDate ASC, sh.startTime ASC, rm.roomCode ASC, d.assignmentRole ASC, st.fullName ASC
    """)
    List<DoctorShiftAssignment> findByBranchAndDateRangeWithDetails(
            @Param("branchId") Long branchId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    @Query("""
        SELECT d FROM DoctorShiftAssignment d
        JOIN FETCH d.staff st
        JOIN FETCH d.shift sh
        JOIN FETCH d.room rm
        JOIN FETCH d.branch br
        LEFT JOIN FETCH d.chair ch
        WHERE d.staff.staffId = :staffId
          AND d.workDate = :workDate
          AND d.status = com.cvdcms.entity.enums.DoctorShiftAssignmentStatus.ACTIVE
        ORDER BY sh.startTime ASC, d.shiftAssignmentId ASC
    """)
    List<DoctorShiftAssignment> findActiveByStaffAndDateWithDetails(
            @Param("staffId") Long staffId,
            @Param("workDate") LocalDate workDate);

    List<DoctorShiftAssignment> findByStaffSchedule_ScheduleIdAndStatusInOrderByShiftAssignmentIdAsc(
            Long scheduleId,
            Collection<DoctorShiftAssignmentStatus> statuses);

    List<DoctorShiftAssignment> findByBranch_BranchIdAndWorkDateAndShift_ShiftIdAndRoom_RoomIdAndStatusOrderByShiftAssignmentIdAsc(
            Long branchId,
            LocalDate workDate,
            Long shiftId,
            Long roomId,
            DoctorShiftAssignmentStatus status);

    @Query("""
        SELECT d FROM DoctorShiftAssignment d
        JOIN FETCH d.staff st
        JOIN FETCH d.shift sh
        JOIN FETCH d.room rm
        JOIN FETCH d.branch br
        LEFT JOIN FETCH d.chair ch
        LEFT JOIN FETCH d.staffSchedule sc
        WHERE d.branch.branchId = :branchId
          AND d.workDate = :workDate
          AND d.status = com.cvdcms.entity.enums.DoctorShiftAssignmentStatus.ACTIVE
        ORDER BY sh.startTime ASC, rm.roomCode ASC, st.fullName ASC
    """)
    List<DoctorShiftAssignment> findActiveByBranchAndDateWithDetails(
            @Param("branchId") Long branchId,
            @Param("workDate") LocalDate workDate);
}
