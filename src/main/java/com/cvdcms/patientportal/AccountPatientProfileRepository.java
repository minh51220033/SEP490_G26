package com.cvdcms.patientportal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountPatientProfileRepository extends JpaRepository<AccountPatientProfile, Long> {
    List<AccountPatientProfile> findByAccount_UserIdOrderByProfile_ProfileIdDesc(Long accountId);
    Optional<AccountPatientProfile> findByProfile_ProfileId(Long profileId);
    Optional<AccountPatientProfile> findByAccount_UserIdAndProfile_ProfileId(Long accountId, Long profileId);
}
