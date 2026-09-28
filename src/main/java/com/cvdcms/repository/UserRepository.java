package com.cvdcms.repository;

import com.cvdcms.entity.Role;
import com.cvdcms.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    // === HÀM PHỤC VỤ LUỒNG ĐĂNG NHẬP / OTP  ===
    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByPhoneNumber(String phoneNumber);

    List<User> findByRole(Role role);

    List<User> findByRole_RoleId(Integer roleId);

    List<User> findByRole_RoleIdNot(Integer roleId);

    List<User> findByEmailContainingAndRole_RoleId(String email, Integer roleId);

    List<User> findByEmailContainingAndRole_RoleIdNot(String email, Integer roleId);




    boolean existsByUsername(String username);

    // TÌM KIẾM + LỌC + PHÂN TRANG
    @Query("""
        SELECT u
        FROM User u
        LEFT JOIN u.role r
        WHERE
            (
                :keyword = ''
                OR LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(u.fullName, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR u.phoneNumber LIKE CONCAT('%', :keyword, '%')
            )
            AND (:roleId IS NULL OR r.roleId = :roleId)
            AND (:active IS NULL OR u.isActive = :active)
        """)
    Page<User> searchUsers(
            @Param("keyword") String keyword,
            @Param("roleId") Integer roleId,
            @Param("active") Boolean active,
            Pageable pageable
    );


    /**
     * Tìm người dùng theo tên quyền (RoleName) và trạng thái (isActive) đầu tiên
     */
    @Query("SELECT u FROM User u WHERE u.role.roleName = :roleName AND u.isActive = :isActive ORDER BY u.userId ASC")
    User findFirstByRole_RoleNameAndIsActiveOrderByUserIdAsc(@Param("roleName") String roleName, @Param("isActive") Boolean isActive);

    /**
     * Fallback: Tìm theo tên quyền đầu tiên
     */
    @Query("SELECT u FROM User u WHERE u.role.roleName = :roleName ORDER BY u.userId ASC")
    User findFirstByRole_RoleName(@Param("roleName") String roleName);
}