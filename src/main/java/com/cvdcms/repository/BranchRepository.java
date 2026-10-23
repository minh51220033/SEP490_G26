
package com.cvdcms.repository;

import com.cvdcms.entity.Branch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BranchRepository extends JpaRepository<Branch, Long> {

    // Kiểm tra mã chi nhánh đã tồn tại hay chưa.
    boolean existsByBranchCode(String branchCode);

    // Lấy danh sách chi nhánh theo thứ tự tên để hiển thị trên giao diện.
    List<Branch> findAllByOrderByBranchNameAsc();

    // Lấy các chi nhánh đang hoạt động để phục vụ đặt lịch.
    List<Branch> findByIsActiveTrueOrderByBranchNameAsc();

    /**
     * Lấy danh sách chi nhánh đang hoạt động mà tài khoản được phép truy cập.
     *
     * ADMIN:
     * - Xem tất cả chi nhánh đang hoạt động.
     *
     * QUAN_LY_KHO:
     * - Chỉ xem chi nhánh được phân công.
     * - Phân công phải còn hiệu lực theo ngày và trạng thái.
     */
    @Query(value = """
    SELECT DISTINCT b.*
    FROM branches b
    WHERE b.is_active = 1
      AND (
          EXISTS (
              SELECT 1
              FROM users u
              JOIN user_roles ur ON ur.user_id = u.user_id
              JOIN roles r ON r.role_id = ur.role_id
              WHERE u.user_id = :userId
                AND u.is_active = 1
                AND UPPER(u.status) = 'ACTIVE'
                AND r.is_active = 1
                AND UPPER(r.role_name) IN ('ADMIN', 'ROLE_ADMIN')
          )
          OR EXISTS (
              SELECT 1
              FROM users u
              JOIN user_roles ur ON ur.user_id = u.user_id
              JOIN roles r ON r.role_id = ur.role_id
              JOIN staff_profiles sp ON sp.user_id = u.user_id
              JOIN staff_branch_assignments sba
                ON sba.staff_id = sp.staff_id
               AND sba.role_id = r.role_id
              WHERE u.user_id = :userId
                AND u.is_active = 1
                AND UPPER(u.status) = 'ACTIVE'
                AND r.is_active = 1
                AND UPPER(r.role_name) = 'QUAN_LY_KHO'
                AND UPPER(sp.status) = 'ACTIVE'
                AND UPPER(sba.status) = 'ACTIVE'
                AND sba.start_date <= CURDATE()
                AND (
                    sba.end_date IS NULL
                    OR sba.end_date >= CURDATE()
                )
                AND sba.branch_id = b.branch_id
          )
      )
    ORDER BY b.branch_name ASC
    """, nativeQuery = true)
    List<Branch> findAccessibleBranchesByUserId(
            @Param("userId") Long userId
    );
}
