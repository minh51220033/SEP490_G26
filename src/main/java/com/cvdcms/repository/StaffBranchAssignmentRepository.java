package com.cvdcms.repository;

import com.cvdcms.entity.StaffBranchAssignment;
import com.cvdcms.entity.enums.AssignmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StaffBranchAssignmentRepository extends JpaRepository<StaffBranchAssignment, Long> {

    @Query("""
        SELECT COUNT(sba) > 0 FROM StaffBranchAssignment sba
        WHERE sba.staff.staffId = :staffId
          AND sba.branch.branchId = :branchId
          AND sba.status = :status
          AND sba.startDate <= CURRENT_DATE
          AND (sba.endDate IS NULL OR sba.endDate >= CURRENT_DATE)
    """)
    boolean hasActiveAssignment(
            @Param("staffId") Long staffId,
            @Param("branchId") Long branchId,
            @Param("status") AssignmentStatus status
    );

    @Query("""
        SELECT COUNT(sba) > 0 FROM StaffBranchAssignment sba
        WHERE sba.staff.user.userId = :userId
          AND sba.branch.branchId = :branchId
          AND sba.status = :status
          AND sba.startDate <= CURRENT_DATE
          AND (sba.endDate IS NULL OR sba.endDate >= CURRENT_DATE)
    """)
    boolean hasActiveUserAssignment(
            @Param("userId") Long userId,
            @Param("branchId") Long branchId,
            @Param("status") AssignmentStatus status
    );

    @Query("""
        SELECT sba.branch.branchId FROM StaffBranchAssignment sba
        WHERE sba.staff.staffId = :staffId
          AND sba.status = :status
          AND sba.startDate <= CURRENT_DATE
          AND (sba.endDate IS NULL OR sba.endDate >= CURRENT_DATE)
    """)
    List<Long> findActiveBranchIdsByStaffId(
            @Param("staffId") Long staffId,
            @Param("status") AssignmentStatus status
    );

    @Query("""
        SELECT sba.branch.branchId FROM StaffBranchAssignment sba
        WHERE sba.staff.user.userId = :userId
          AND sba.status = :status
          AND sba.startDate <= CURRENT_DATE
          AND (sba.endDate IS NULL OR sba.endDate >= CURRENT_DATE)
    """)
    List<Long> findActiveBranchIdsByUserId(
            @Param("userId") Long userId,
            @Param("status") AssignmentStatus status
    );

    List<StaffBranchAssignment> findByStaff_StaffIdAndStatus(Long staffId, AssignmentStatus status);
}
