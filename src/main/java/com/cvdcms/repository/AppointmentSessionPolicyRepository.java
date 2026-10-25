package com.cvdcms.repository;

import com.cvdcms.entity.AppointmentSessionPolicy;
import com.cvdcms.entity.enums.PreferredSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface AppointmentSessionPolicyRepository extends JpaRepository<AppointmentSessionPolicy, Long> {
    Optional<AppointmentSessionPolicy> findByBranch_BranchIdAndAppointmentDateAndPreferredSession(
            Long branchId, LocalDate date, PreferredSession session);
}
