
package com.cvdcms.repository;

import com.cvdcms.entity.Role;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.UserType;
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
     * Có thể lọc thêm theo roleId, trạng thái isActive, chi nhánh và loại tài khoản.
     *
     * Lưu ý:
     * fullName không còn nằm trong User.
     * Vì vậy, muốn tìm theo họ tên phải JOIN PatientProfile.
     */
    @Query(
            value = """
            SELECT DISTINCT u
            FROM User u
            LEFT JOIN FETCH u.branch b
            LEFT JOIN u.roles r
            LEFT JOIN u.patientProfiles p
            LEFT JOIN StaffProfile sp ON sp.user = u
            WHERE
                (
                    :keyword = ''
                    OR LOWER(u.username)
                        LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(COALESCE(p.fullName, ''))
                        LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(COALESCE(sp.fullName, ''))
                        LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(u.email)
                        LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR u.phoneNumber
                        LIKE CONCAT('%', :keyword, '%')
                )
                AND (:roleId IS NULL OR r.roleId = :roleId)
                AND (:active IS NULL OR u.isActive = :active)
                AND (:branchId IS NULL OR b.branchId = :branchId)
                AND (:userType IS NULL OR u.userType = :userType)
                AND NOT EXISTS (
                    SELECT 1
                    FROM User ua
                    JOIN ua.roles ra
                    WHERE ua = u
                      AND UPPER(ra.roleName) IN ('ADMIN', 'ROLE_ADMIN')
                )
            """,
            countQuery = """
            SELECT COUNT(DISTINCT u.userId)
            FROM User u
            LEFT JOIN u.roles r
            LEFT JOIN u.patientProfiles p
            LEFT JOIN StaffProfile sp ON sp.user = u
            LEFT JOIN u.branch b
            WHERE
                (
                    :keyword = ''
                    OR LOWER(u.username)
                        LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(COALESCE(p.fullName, ''))
                        LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(COALESCE(sp.fullName, ''))
                        LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(u.email)
                        LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR u.phoneNumber
                        LIKE CONCAT('%', :keyword, '%')
                )
                AND (:roleId IS NULL OR r.roleId = :roleId)
                AND (:active IS NULL OR u.isActive = :active)
                AND (:branchId IS NULL OR b.branchId = :branchId)
                AND (:userType IS NULL OR u.userType = :userType)
                AND NOT EXISTS (
                    SELECT 1
                    FROM User ua
                    JOIN ua.roles ra
                    WHERE ua = u
                      AND UPPER(ra.roleName) IN ('ADMIN', 'ROLE_ADMIN')
                )
            """
    )
    Page<User> searchUsers(
            @Param("keyword") String keyword,
            @Param("roleId") Integer roleId,
            @Param("active") Boolean active,
            @Param("branchId") Long branchId,
            @Param("userType") UserType userType,
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
     * Lấy danh sách tên vai trò đang hoạt động của tài khoản.
     */
    @Query("""
    SELECT DISTINCT r.roleName
    FROM User u
    JOIN u.roles r
    WHERE u.userId = :userId
      AND u.isActive = TRUE
      AND r.isActive = TRUE
    """)
    List<String> findActiveRoleNamesByUserId(@Param("userId") Long userId);

    /**
     * Kiểm tra tài khoản có vai trò Tài chính (ADMIN, KE_TOAN hoặc Role mở rộng có quyền FINANCE) đang hoạt động hay không.
     */
    @Query("""
    SELECT CASE WHEN COUNT(DISTINCT u) > 0 THEN TRUE ELSE FALSE END
    FROM User u
    JOIN u.roles r
    LEFT JOIN r.permissions p
    LEFT JOIN u.customPermissions cp
    WHERE u.userId = :userId
      AND u.isActive = TRUE
      AND r.isActive = TRUE
      AND (
          UPPER(r.roleName) IN ('ADMIN', 'ROLE_ADMIN')
          OR (
              (UPPER(COALESCE(p.module, '')) = 'FINANCE'
               OR UPPER(COALESCE(p.permissionCode, '')) IN ('INVOICE_VIEW', 'INVOICE_CREATE', 'PAYMENT_COLLECT', 'REFUND_MANAGE'))
              AND (COALESCE(u.customPermissionsEnabled, FALSE) = FALSE OR cp = p)
          )
      )
    """)
    boolean hasActiveFinanceRole(@Param("userId") Long userId);


    /**
     * Kiểm tra tài khoản có quyền quản lý tài khoản hay không.
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
    LEFT JOIN r.permissions p
    LEFT JOIN u.customPermissions cp
    WHERE u.userId = :userId
      AND u.isActive = TRUE
      AND r.isActive = TRUE
      AND (
          UPPER(r.roleName) IN ('ADMIN', 'ROLE_ADMIN')
          OR (
              (UPPER(COALESCE(p.module, '')) = 'INVENTORY'
               OR UPPER(COALESCE(p.permissionCode, '')) IN ('INVENTORY_VIEW', 'INVENTORY_MANAGE', 'SUPPLIER_MANAGE'))
              AND (COALESCE(u.customPermissionsEnabled, FALSE) = FALSE OR cp = p)
          )
      )
""")
    boolean hasActiveSupplierManagementRole(
            @Param("userId") Long userId
    );

    /**
     * Lấy tài khoản kèm tập vai trò, quyền của vai trò và quyền tùy chỉnh riêng của tài khoản.
     */
    @Query("""
    SELECT DISTINCT u
    FROM User u
    LEFT JOIN FETCH u.roles r
    LEFT JOIN FETCH r.permissions
    LEFT JOIN FETCH u.customPermissions
    WHERE u.userId = :userId
    """)
    Optional<User> findByIdWithRolesAndPermissions(@Param("userId") Long userId);

    /**
     * Lấy danh sách mã quyền tĩnh (permissionCode) đang hiệu lực của tài khoản:
     * - Nếu tài khoản bật tùy chỉnh quyền riêng (customPermissionsEnabled = true): chỉ lấy các quyền vừa thuộc Role vừa được tích chọn trong customPermissions.
     * - Nếu không bật tùy chỉnh riêng: kế thừa toàn bộ quyền của các Role đang hoạt động.
     */
    @Query("""
    SELECT DISTINCT p.permissionCode
    FROM User u
    JOIN u.roles r
    JOIN r.permissions p
    LEFT JOIN u.customPermissions cp
    WHERE u.userId = :userId
      AND u.isActive = TRUE
      AND r.isActive = TRUE
      AND (
          UPPER(r.roleName) IN ('ADMIN', 'ROLE_ADMIN')
          OR COALESCE(u.customPermissionsEnabled, FALSE) = FALSE
          OR cp = p
      )
    """)
    List<String> findActivePermissionCodesByUserId(@Param("userId") Long userId);

    /**
     * Kiểm tra tài khoản có quyền cụ thể (hoặc là ADMIN) đang hoạt động hay không.
     * Tôn trọng cấu hình tùy chỉnh quyền riêng của từng tài khoản (customPermissionsEnabled).
     */
    @Query("""
    SELECT CASE WHEN COUNT(DISTINCT u) > 0 THEN TRUE ELSE FALSE END
    FROM User u
    JOIN u.roles r
    LEFT JOIN r.permissions p
    LEFT JOIN u.customPermissions cp
    WHERE u.userId = :userId
      AND u.isActive = TRUE
      AND r.isActive = TRUE
      AND (
          UPPER(r.roleName) IN ('ADMIN', 'ROLE_ADMIN')
          OR (
              UPPER(COALESCE(p.permissionCode, '')) = UPPER(:permissionCode)
              AND (
                  COALESCE(u.customPermissionsEnabled, FALSE) = FALSE
                  OR cp = p
              )
          )
      )
    """)
    boolean hasActivePermission(
            @Param("userId") Long userId,
            @Param("permissionCode") String permissionCode
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


