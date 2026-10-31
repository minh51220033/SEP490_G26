package com.cvdcms.repository;

import com.cvdcms.entity.CommissionPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CommissionPolicyRepository extends JpaRepository<CommissionPolicy, Long> {

    List<CommissionPolicy> findByIsActiveTrue();

    @Query("""
        SELECT cp FROM CommissionPolicy cp
        LEFT JOIN FETCH cp.service s
        LEFT JOIN FETCH cp.role r
        LEFT JOIN FETCH cp.staff st
        ORDER BY cp.policyId DESC
    """)
    List<CommissionPolicy> findAllWithDetails();

    @Query("""
        SELECT cp FROM CommissionPolicy cp
        WHERE cp.isActive = true
          AND (cp.staff.staffId = :staffId OR cp.staff IS NULL)
          AND (cp.role.roleId = :roleId OR cp.role IS NULL)
          AND (cp.service.serviceId = :serviceId OR cp.service IS NULL)
        ORDER BY
          CASE WHEN cp.staff IS NOT NULL THEN 1 ELSE 2 END,
          CASE WHEN cp.service IS NOT NULL THEN 1 ELSE 2 END,
          CASE WHEN cp.role IS NOT NULL THEN 1 ELSE 2 END
    """)
    List<CommissionPolicy> findApplicablePolicies(
            @Param("staffId") Long staffId,
            @Param("roleId") Long roleId,
            @Param("serviceId") Long serviceId
    );
}
