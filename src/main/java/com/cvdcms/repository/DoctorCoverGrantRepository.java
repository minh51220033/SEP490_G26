package com.cvdcms.repository;

import com.cvdcms.entity.DoctorCoverGrant;
import java.time.LocalDateTime;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DoctorCoverGrantRepository extends JpaRepository<DoctorCoverGrant, Long> {
    boolean existsByVisitVisitIdAndCoveringDoctorStaffIdAndRevokedAtIsNullAndExpiresAtAfter(
            Long visitId, Long doctorId, LocalDateTime now);

    @Query("SELECT g.visit.visitId FROM DoctorCoverGrant g WHERE g.coveringDoctor.staffId = :doctorId AND g.revokedAt IS NULL AND g.expiresAt > :now")
    Set<Long> findActiveCoveredVisitIds(@Param("doctorId") Long doctorId, @Param("now") LocalDateTime now);
}
