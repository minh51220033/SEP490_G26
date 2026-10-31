package com.cvdcms.repository;

import com.cvdcms.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PermissionRepository extends JpaRepository<Permission, Long> {

    Optional<Permission> findByPermissionCode(String permissionCode);

    Optional<Permission> findByPermissionCodeIgnoreCase(String permissionCode);

    List<Permission> findAllByOrderByModuleAscPermissionCodeAsc();
}

