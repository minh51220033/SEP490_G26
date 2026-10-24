package com.cvdcms.repository;

import com.cvdcms.entity.PatientVoucher;
import com.cvdcms.entity.enums.VoucherStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PatientVoucherRepository extends JpaRepository<PatientVoucher, Long> {

    Optional<PatientVoucher> findByVoucherCode(String voucherCode);

    @Query("""
        SELECT pv FROM PatientVoucher pv
        JOIN FETCH pv.promotion p
        WHERE pv.patientProfile.profileId = :profileId
          AND pv.status = :status
          AND pv.expiresAt >= :now
        ORDER BY pv.expiresAt ASC
    """)
    List<PatientVoucher> findAvailableVouchersForPatient(
            @Param("profileId") Long profileId,
            @Param("status") VoucherStatus status,
            @Param("now") LocalDateTime now
    );
}
