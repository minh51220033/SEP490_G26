package com.cvdcms.repository;

import com.cvdcms.entity.PatientLoyaltyWallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PatientLoyaltyWalletRepository extends JpaRepository<PatientLoyaltyWallet, Long> {

    Optional<PatientLoyaltyWallet> findByPatientProfile_ProfileId(Long profileId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT w FROM PatientLoyaltyWallet w WHERE w.patientProfile.profileId = :profileId")
    Optional<PatientLoyaltyWallet> findByProfileIdForUpdate(@org.springframework.data.repository.query.Param("profileId") Long profileId);
}
