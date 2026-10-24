
package com.cvdcms.repository;

import com.cvdcms.entity.Role;
import com.cvdcms.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    // =========================================================
    // 1. CÁC HÀM PHỤC VỤ ĐĂNG NHẬP VÀ XÁC THỰC TÀI KHOẢN
    // =========================================================

    // Tìm tài khoản theo username khi người dùng đăng nhập.
    Optional<User> findByUsername(String username);

    // Tìm tài khoản theo email để xử lý OTP hoặc quên mật khẩu.
    Optional<User> findByEmail(String email);

    // Kiểm tra email đã được tài khoản khác sử dụng hay chưa.
    boolean existsByEmail(String email);

    // Kiểm tra số điện thoại đã được tài khoản khác sử dụng hay chưa.
    boolean existsByPhoneNumber(String phoneNumber);

    // Kiểm tra username đã tồn tại hay chưa.
    boolean existsByUsername(String username);

    Optional<User> findByPhoneNumber(String phoneNumber);

    // =========================================================
    // 2. TÌM TÀI KHOẢN THEO VAI TRÒ
    // =========================================================

    // Một tài khoản có thể có nhiều vai trò.
    // DISTINCT giúp tránh trả về tài khoản trùng lặp khi truy vấn quan hệ.
    @Query("""
        SELECT DISTINCT u
        FROM User u
        JOIN u.roles r
        WHERE r = :role
        """)
    List<User> findByRole(@Param("role") Role role);

    // Tìm các tài khoản có một vai trò cụ thể theo ID.
    @Query("""
        SELECT DISTINCT u
        FROM User u
        JOIN u.roles r
        WHERE r.roleId = :roleId
        """)
    List<User> findByRole_RoleId(@Param("roleId") Integer roleId);

    // Tìm các tài khoản không được gán vai trò có ID đã chỉ định.
    // Ví dụ: tìm tài khoản không có vai trò ADMIN.
    @Query("""
        SELECT DISTINCT u
        FROM User u
        WHERE NOT EXISTS (
            SELECT r
            FROM User u2
            JOIN u2.roles r
            WHERE u2 = u
              AND r.roleId = :roleId
        )
        """)
    List<User> findByRole_RoleIdNot(@Param("roleId") Integer roleId);

    // Tìm tài khoản theo một phần email và vai trò.
    @Query("""
        SELECT DISTINCT u
        FROM User u
        JOIN u.roles r
        WHERE LOWER(u.email) LIKE LOWER(CONCAT('%', :email, '%'))
          AND r.roleId = :roleId
        """)
    List<User> findByEmailContainingAndRole_RoleId(
            @Param("email") String email,
            @Param("roleId") Integer roleId
    );

    // Tìm tài khoản theo một phần email nhưng không có vai trò chỉ định.
    @Query("""
        SELECT DISTINCT u
        FROM User u
        WHERE LOWER(u.email) LIKE LOWER(CONCAT('%', :email, '%'))
          AND NOT EXISTS (
              SELECT r
              FROM User u2
              JOIN u2.roles r
              WHERE u2 = u
                AND r.roleId = :roleId
          )
        """)
    List<User> findByEmailContainingAndRole_RoleIdNot(
            @Param("email") String email,
            @Param("roleId") Integer roleId
    );


    // =========================================================
    // 3. TÌM KIẾM, LỌC VÀ PHÂN TRANG TÀI KHOẢN
    // =========================================================

    /*
     * Tìm tài khoản theo:
     * - Username
     * - Họ tên trong hồ sơ bệnh nhân
     * - Email
     * - Số điện thoại tài khoản
     *
     * Có thể lọc thêm theo roleId và trạng thái isActive.
     *
     * Lưu ý:
     * fullName không còn nằm trong User.
     * Vì vậy, muốn tìm theo họ tên phải JOIN PatientProfile.
     */
    @Query(
            value = """
            SELECT DISTINCT u
            FROM User u
            LEFT JOIN u.roles r
            LEFT JOIN u.patientProfiles p
            WHERE
                (
                    :keyword = ''
                    OR LOWER(u.username)
                        LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(COALESCE(p.fullName, ''))
                        LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(u.email)
                        LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR u.phoneNumber
                        LIKE CONCAT('%', :keyword, '%')
                )
                AND (:roleId IS NULL OR r.roleId = :roleId)
                AND (:active IS NULL OR u.isActive = :active)
            """,
            countQuery = """
            SELECT COUNT(DISTINCT u.userId)
            FROM User u
            LEFT JOIN u.roles r
            LEFT JOIN u.patientProfiles p
            WHERE
                (
                    :keyword = ''
                    OR LOWER(u.username)
                        LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(COALESCE(p.fullName, ''))
                        LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(u.email)
                        LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR u.phoneNumber
                        LIKE CONCAT('%', :keyword, '%')
                )
                AND (:roleId IS NULL OR r.roleId = :roleId)
                AND (:active IS NULL OR u.isActive = :active)
            """
    )
    Page<User> searchUsers(
            @Param("keyword") String keyword,
            @Param("roleId") Integer roleId,
            @Param("active") Boolean active,
            Pageable pageable
    );


    // =========================================================
    // 4. TÌM TÀI KHOẢN ĐẦU TIÊN THEO TÊN VAI TRÒ
    // =========================================================

    // Giữ tên phương thức cũ để giảm thay đổi tại các Service đang gọi.
    // Truy vấn mới tìm trong tập hợp roles thay vì u.role.
    @Query("""
        SELECT u
        FROM User u
        JOIN u.roles r
        WHERE r.roleName = :roleName
          AND u.isActive = :isActive
        ORDER BY u.userId ASC
        """)
    User findFirstByRole_RoleNameAndIsActiveOrderByUserIdAsc(
            @Param("roleName") String roleName,
            @Param("isActive") Boolean isActive
    );

    // Phương án dự phòng: tìm tài khoản đầu tiên theo tên vai trò.
    @Query("""
        SELECT u
        FROM User u
        JOIN u.roles r
        WHERE r.roleName = :roleName
        ORDER BY u.userId ASC
        """)
    User findFirstByRole_RoleName(
            @Param("roleName") String roleName
    );


// ...

    /**
     * Kiểm tra tài khoản có quyền Admin đang hoạt động hay không.
     *
     * Điều kiện:
     * 1. Tài khoản đúng với userId truyền vào.
     * 2. Tài khoản đang hoạt động.
     * 3. Tài khoản có role ADMIN hoặc ROLE_ADMIN.
     * 4. Role đó cũng đang hoạt động.
     *
     * Truy vấn dùng quan hệ User.roles đã khai báo trong entity User.
     */
    @Query("""
    SELECT CASE WHEN COUNT(u) > 0 THEN TRUE ELSE FALSE END
    FROM User u
    JOIN u.roles r
    WHERE u.userId = :userId
      AND u.isActive = TRUE
      AND r.isActive = TRUE
      AND UPPER(r.roleName) IN ('ADMIN', 'ROLE_ADMIN')
    """)
    boolean hasActiveAdminRole(@Param("userId") Long userId);


    /**
     * Kiểm tra tài khoản có quyền quản lý tài khoản hay không.
     *
     * Điều kiện:
     * 1. User tồn tại và đang hoạt động.
     * 2. Có vai trò ADMIN hoặc LE_TAN.
     * 3. Vai trò tương ứng cũng đang hoạt động.
     *
     * Truy vấn dữ liệu hiện tại trong database thay vì chỉ tin vào
     * thông tin vai trò được lưu trong session khi đăng nhập.
     */
    @Query("""
    SELECT CASE WHEN COUNT(u) > 0 THEN TRUE ELSE FALSE END
    FROM User u
    JOIN u.roles r
    WHERE u.userId = :userId
      AND u.isActive = TRUE
      AND r.isActive = TRUE
      AND UPPER(r.roleName) IN (
          'ADMIN',
          'ROLE_ADMIN',
          'LE_TAN',
          'ROLE_LE_TAN'
      )
    """)
    boolean hasActiveAccountManagementRole(
            @Param("userId") Long userId
    );


    @Query("""
    SELECT CASE WHEN COUNT(DISTINCT u) > 0
        THEN TRUE ELSE FALSE END
    FROM User u
    JOIN u.roles r
    WHERE u.userId = :userId
      AND u.isActive = TRUE
      AND r.isActive = TRUE
      AND UPPER(r.roleName) IN ('ADMIN', 'QUAN_LY_KHO')
""")
    boolean hasActiveSupplierManagementRole(
            @Param("userId") Long userId
    );


    @Query(value = """
    SELECT EXISTS (
        SELECT 1
        FROM users u
        JOIN user_roles ur
            ON ur.user_id = u.user_id
        JOIN roles r
            ON r.role_id = ur.role_id
        JOIN staff_profiles sp
            ON sp.user_id = u.user_id
        JOIN staff_branch_assignments sba
            ON sba.staff_id = sp.staff_id
           AND sba.role_id = r.role_id
        JOIN branches b
            ON b.branch_id = sba.branch_id
        WHERE u.user_id = :userId
          AND sba.branch_id = :branchId
          AND UPPER(r.role_name) = 'QUAN_LY_KHO'
          AND r.is_active = 1
          AND u.is_active = 1
          AND UPPER(u.status) = 'ACTIVE'
          AND UPPER(sp.status) = 'ACTIVE'
          AND sba.status = 'ACTIVE'
          AND sba.start_date <= CURDATE()
          AND (
              sba.end_date IS NULL
              OR sba.end_date >= CURDATE()
          )
          AND b.is_active = 1
    )
    """, nativeQuery = true)
    boolean hasActiveWarehouseAssignment(
            @Param("userId") Long userId,
            @Param("branchId") Long branchId
    );


    @Query(value = """
    SELECT COUNT(*)
    FROM users u
    JOIN user_roles ur ON ur.user_id = u.user_id
    JOIN roles r ON r.role_id = ur.role_id
    JOIN staff_profiles sp ON sp.user_id = u.user_id
    JOIN staff_branch_assignments sba
      ON sba.staff_id = sp.staff_id
     AND sba.role_id = r.role_id
    JOIN branches b ON b.branch_id = sba.branch_id
    WHERE u.user_id = :userId
      AND sba.branch_id = :branchId
      AND UPPER(r.role_name) = 'QUAN_LY_KHO'
      AND r.is_active = 1
      AND u.is_active = 1
      AND UPPER(u.status) = 'ACTIVE'
      AND UPPER(sp.status) = 'ACTIVE'
      AND UPPER(sba.status) = 'ACTIVE'
      AND sba.start_date <= CURDATE()
      AND (sba.end_date IS NULL OR sba.end_date >= CURDATE())
      AND b.is_active = 1
    """, nativeQuery = true)
    Long countActiveWarehouseAssignments(
            @Param("userId") Long userId,
            @Param("branchId") Long branchId
    );

}


