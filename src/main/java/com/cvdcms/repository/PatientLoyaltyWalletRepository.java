package com.cvdcms.repository;

import com.cvdcms.entity.PatientLoyaltyWallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PatientLoyaltyWalletRepository extends JpaRepository<PatientLoyaltyWallet, Long> {

    Optional<PatientLoyaltyWallet> findByPatientProfile_ProfileId(Long profileId);
}
