package com.cvdcms.repository;

import com.cvdcms.entity.Branch;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DispatchBranchRepository extends JpaRepository<Branch, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Branch b where b.branchId = :id")
    Optional<Branch> lock(@Param("id") Long id);
}