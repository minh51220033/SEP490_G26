package com.cvdcms.patientportal;

import com.cvdcms.entity.Branch;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PortalBookingBranchRepository extends JpaRepository<Branch, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Branch b WHERE b.branchId = :branchId")
    Optional<Branch> findByIdForBooking(@Param("branchId") Long branchId);
}
