package com.cvdcms.repository;

import com.cvdcms.entity.StaffBranchAssignment;
import com.cvdcms.entity.enums.AssignmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StaffBranchAssignmentRepository extends JpaRepository<StaffBranchAssignment, Long> {

    // ==========================================
    // CÁC HÀM TỪ NHÁNH CỦA FT21 - FT22
    // ==========================================
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

    // ==========================================
    // CÁC HÀM TỪ NHÁNH DEVELOP (FT41)
    // ==========================================
    
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

    // Đã gộp chung hàm findByStaff_StaffIdAndStatus và findByStaffStaffIdAndStatus
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

    // Tìm phân công theo đúng bộ khóa duy nhất uk_sba (staff_id, branch_id, role_id, start_date).
    Optional<StaffBranchAssignment> findByStaffStaffIdAndBranchBranchIdAndRoleRoleIdAndStartDate(
            Long staffId, Long branchId, Long roleId, LocalDate startDate);
}