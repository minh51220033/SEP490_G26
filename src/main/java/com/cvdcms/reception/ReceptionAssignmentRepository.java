package com.cvdcms.reception;
import com.cvdcms.entity.StaffBranchAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
public interface ReceptionAssignmentRepository extends JpaRepository<StaffBranchAssignment, Long> {
    @Query("""
            SELECT DISTINCT a.branch.branchId FROM StaffBranchAssignment a
            WHERE a.staff.user.userId = :userId AND a.staff.status = com.cvdcms.entity.enums.StaffStatus.ACTIVE
            AND a.status = com.cvdcms.entity.enums.AssignmentStatus.ACTIVE AND a.role.isActive = true
            AND UPPER(a.role.roleName) IN ('LE_TAN', 'ROLE_LE_TAN')
            AND a.startDate <= :day AND (a.endDate IS NULL OR a.endDate >= :day) ORDER BY a.branch.branchId""")
    List<Long> activeReceptionBranchIds(@Param("userId") Long userId, @Param("day") java.time.LocalDate day);
}
