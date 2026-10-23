package com.cvdcms.patient;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface PatientSearchRepository extends Repository<ReceptionPatientRecord, Long> {
    @Query(value = """
            SELECT p FROM ReceptionPatientRecord p JOIN FETCH p.preferredBranch b
            WHERE (:branchId IS NULL OR b.branchId = :branchId)
              AND (:query IS NULL OR LOWER(p.patientCode) LIKE LOWER(CONCAT('%', :query, '%'))
                   OR LOWER(p.fullName) LIKE LOWER(CONCAT('%', :query, '%'))
                   OR p.phoneNumber LIKE CONCAT('%', :query, '%'))
              AND (:dob IS NULL OR p.dob = :dob)
              AND (:status IS NULL OR p.status = :status)
            """, countQuery = """
            SELECT COUNT(p) FROM ReceptionPatientRecord p
            WHERE (:branchId IS NULL OR p.preferredBranch.branchId = :branchId)
              AND (:query IS NULL OR LOWER(p.patientCode) LIKE LOWER(CONCAT('%', :query, '%'))
                   OR LOWER(p.fullName) LIKE LOWER(CONCAT('%', :query, '%'))
                   OR p.phoneNumber LIKE CONCAT('%', :query, '%'))
              AND (:dob IS NULL OR p.dob = :dob)
              AND (:status IS NULL OR p.status = :status)
            """)
    Page<ReceptionPatientRecord> search(@Param("branchId") Long branchId, @Param("query") String query,
                                @Param("dob") LocalDate dob, @Param("status") String status,
                                Pageable pageable);

    @Query("""
            SELECT p FROM ReceptionPatientRecord p JOIN FETCH p.preferredBranch b
            WHERE (:branchId IS NULL OR b.branchId = :branchId)
              AND (p.phoneNumber = :phone OR (p.dob = :dob AND LOWER(p.fullName) = :name))
            ORDER BY p.profileId DESC
            """)
    List<ReceptionPatientRecord> candidates(@Param("branchId") Long branchId, @Param("phone") String phone,
                                    @Param("dob") LocalDate dob, @Param("name") String normalizedName,
                                    Pageable pageable);
}
