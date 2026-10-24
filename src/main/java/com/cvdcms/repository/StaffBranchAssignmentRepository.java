package com.cvdcms.repository;

import com.cvdcms.entity.StaffBranchAssignment;
import com.cvdcms.entity.enums.AssignmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

// Truy vấn phân công nhân sự theo chi nhánh (staff_branch_assignments) - FT41.
@Repository
public interface StaffBranchAssignmentRepository extends JpaRepository<StaffBranchAssignment, Long> {

    // Lịch sử phân công của một nhân viên: chi nhánh chính lên đầu, mới nhất trước.
    @Query("SELECT a FROM StaffBranchAssignment a "
            + "JOIN FETCH a.branch JOIN FETCH a.role "
            + "WHERE a.staff.staffId = :staffId "
            + "ORDER BY a.status ASC, a.isPrimary DESC, a.startDate DESC")
    List<StaffBranchAssignment> findByStaffId(@Param("staffId") Long staffId);

    // Toàn bộ phân công đang hiệu lực (dùng để hiển thị chi nhánh trong danh sách nhân viên).
    @Query("SELECT a FROM StaffBranchAssignment a "
            + "JOIN FETCH a.branch JOIN FETCH a.role JOIN FETCH a.staff "
            + "WHERE a.status = :status")
    List<StaffBranchAssignment> findAllByStatus(@Param("status") AssignmentStatus status);

    List<StaffBranchAssignment> findByStaffStaffIdAndStatus(Long staffId, AssignmentStatus status);

    // Bác sĩ/nhân viên đang làm việc tại một chi nhánh (phục vụ các phân hệ khác chọn bác sĩ).
    @Query("SELECT a FROM StaffBranchAssignment a "
            + "JOIN FETCH a.staff JOIN FETCH a.role "
            + "WHERE a.branch.branchId = :branchId AND a.status = :status "
            + "ORDER BY a.staff.fullName ASC")
    List<StaffBranchAssignment> findByBranchAndStatus(@Param("branchId") Long branchId,
                                                      @Param("status") AssignmentStatus status);

    // Đã có phân công đang hiệu lực cùng chi nhánh + vai trò cho nhân viên này chưa.
    boolean existsByStaffStaffIdAndBranchBranchIdAndRoleRoleIdAndStatus(
            Long staffId, Long branchId, Long roleId, AssignmentStatus status);
}
