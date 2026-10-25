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
            SELECT p FROM PatientProfile p WHERE p.preferredBranch.branchId IN :branches
            AND (:branchId IS NULL OR p.preferredBranch.branchId = :branchId)
            AND (:dob IS NULL OR p.dob = :dob)
            AND (:status IS NULL OR p.status = :status)
            AND (:query IS NULL OR LOWER(p.fullName) LIKE LOWER(CONCAT('%', :query, '%'))
                OR LOWER(p.patientCode) LIKE LOWER(CONCAT('%', :query, '%'))
                OR p.phoneNumber LIKE CONCAT('%', :query, '%'))
            """)
    Page<PatientProfile> search(@Param("branches") Collection<Long> branches,
                                @Param("branchId") Long branchId,
                                @Param("dob") LocalDate dob,
                                @Param("status") String status,
                                @Param("query") String query, Pageable pageable);
}
