package com.cvdcms.repository;

import com.cvdcms.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoleRepository extends JpaRepository<Role, Integer> {

    Optional<Role> findByRoleName(String roleName);

    Optional<Role> findByRoleNameIgnoreCase(String roleName);

    boolean existsByRoleNameIgnoreCase(String roleName);

    @Query("SELECT DISTINCT r FROM Role r LEFT JOIN FETCH r.permissions ORDER BY r.roleId ASC")
    List<Role> findAllWithPermissions();

    @Query("SELECT r FROM Role r LEFT JOIN FETCH r.permissions WHERE r.roleId = :roleId")
    Optional<Role> findByIdWithPermissions(@Param("roleId") Long roleId);

}