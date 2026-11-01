
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

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Branch b WHERE b.branchId = :branchId")
    java.util.Optional<Branch> findByIdForUpdate(@Param("branchId") Long branchId);

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

    /**
     * Đếm tổng số bản ghi nghiệp vụ liên quan đến chi nhánh.
     * Dùng để kiểm tra xem chi nhánh có thể xóa an toàn khi tạo nhầm hay không.
     */
    @Query(value = """
        SELECT (
            (SELECT COUNT(*) FROM rooms WHERE branch_id = :branchId) +
            (SELECT COUNT(*) FROM dental_chairs WHERE branch_id = :branchId) +
            (SELECT COUNT(*) FROM staff_branch_assignments WHERE branch_id = :branchId) +
            (SELECT COUNT(*) FROM staff_schedules WHERE branch_id = :branchId) +
            (SELECT COUNT(*) FROM appointments WHERE branch_id = :branchId) +
            (SELECT COUNT(*) FROM visits WHERE branch_id = :branchId) +
            (SELECT COUNT(*) FROM invoices WHERE branch_id = :branchId) +
            (SELECT COUNT(*) FROM payments WHERE branch_id = :branchId) +
            (SELECT COUNT(*) FROM prescriptions WHERE branch_id = :branchId) +
            (SELECT COUNT(*) FROM treatment_plans WHERE branch_id = :branchId) +
            (SELECT COUNT(*) FROM queue_tickets WHERE branch_id = :branchId) +
            (SELECT COUNT(*) FROM attendance_records WHERE branch_id = :branchId) +
            (SELECT COUNT(*) FROM branch_inventory WHERE branch_id = :branchId) +
            (SELECT COUNT(*) FROM stock_receipts WHERE branch_id = :branchId) +
            (SELECT COUNT(*) FROM stock_issues WHERE branch_id = :branchId OR destination_branch_id = :branchId) +
            (SELECT COUNT(*) FROM users WHERE branch_id = :branchId)
        )
    """, nativeQuery = true)
    long countOperationalReferences(@Param("branchId") Long branchId);
}
