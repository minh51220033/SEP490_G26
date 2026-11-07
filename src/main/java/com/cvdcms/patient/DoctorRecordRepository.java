package com.cvdcms.patient;

import com.cvdcms.entity.PatientProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.time.LocalDate;

public interface DoctorRecordRepository extends JpaRepository<PatientProfile, Long> {
    @Query("""
            SELECT p FROM PatientProfile p LEFT JOIN p.preferredBranch b
            WHERE (b IS NULL OR b.branchId IN :branches)
            AND (:branchId IS NULL OR b.branchId = :branchId)
            AND (:dob IS NULL OR p.dob = :dob)
            AND (:status IS NULL OR p.status = :status)
            AND (:query IS NULL OR LOWER(p.fullName) LIKE LOWER(CONCAT('%', :query, '%'))
                OR LOWER(p.patientCode) LIKE LOWER(CONCAT('%', :query, '%'))
                OR p.phoneNumber LIKE CONCAT('%', :query, '%')
                OR LOWER(COALESCE(p.idCardNumber, '')) LIKE LOWER(CONCAT('%', :query, '%')))
            """)
    Page<PatientProfile> search(@Param("branches") Collection<Long> branches,
                                @Param("branchId") Long branchId,
                                @Param("dob") LocalDate dob,
                                @Param("status") String status,
                                @Param("query") String query, Pageable pageable);

    @Query("""
            SELECT p FROM PatientProfile p
            LEFT JOIN FETCH p.preferredBranch
            LEFT JOIN FETCH p.user
            WHERE p.profileId = :profileId
            """)
    java.util.Optional<PatientProfile> findDetailsById(@Param("profileId") Long profileId);
}
