package com.cvdcms.patientportal;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PatientTransferRequestRepository extends JpaRepository<PatientTransferRequest, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PatientTransferRequest r WHERE r.requestId = :id")
    Optional<PatientTransferRequest> lockById(@Param("id") Long id);

    Optional<PatientTransferRequest> findFirstByProfile_ProfileIdAndConsumedAtIsNullOrderByRequestIdDesc(Long profileId);
}
