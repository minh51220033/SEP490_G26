package com.cvdcms.repository;

import com.cvdcms.entity.DoctorCoverGrant;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DoctorCoverGrantRepository extends JpaRepository<DoctorCoverGrant, Long> {
    boolean existsByVisitVisitIdAndCoveringDoctorStaffIdAndRevokedAtIsNullAndExpiresAtAfter(
            Long visitId, Long doctorId, LocalDateTime now);
}
