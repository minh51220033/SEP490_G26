package com.cvdcms.patient;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PatientSearchRepository extends Repository<ReceptionPatientRecord, Long> {
    @Query(value = """
            SELECT p FROM ReceptionPatientRecord p JOIN FETCH p.preferredBranch b
            WHERE (:branchId IS NULL OR b.branchId = :branchId)
              AND (:query IS NULL OR LOWER(p.patientCode) LIKE LOWER(CONCAT('%', :query, '%'))
                   OR LOWER(p.fullName) LIKE LOWER(CONCAT('%', :query, '%'))
                   OR p.phoneNumber LIKE CONCAT('%', :query, '%')
                   OR (p.idCardNumber IS NOT NULL AND p.idCardNumber LIKE CONCAT('%', :query, '%'))
                   OR (p.healthInsuranceNumber IS NOT NULL AND p.healthInsuranceNumber LIKE CONCAT('%', :query, '%')))
              AND (:dob IS NULL OR p.dob = :dob)
              AND (:status IS NULL OR p.status = :status)
            """, countQuery = """
            SELECT COUNT(p) FROM ReceptionPatientRecord p
            WHERE (:branchId IS NULL OR p.preferredBranch.branchId = :branchId)
              AND (:query IS NULL OR LOWER(p.patientCode) LIKE LOWER(CONCAT('%', :query, '%'))
                   OR LOWER(p.fullName) LIKE LOWER(CONCAT('%', :query, '%'))
                   OR p.phoneNumber LIKE CONCAT('%', :query, '%')
                   OR (p.idCardNumber IS NOT NULL AND p.idCardNumber LIKE CONCAT('%', :query, '%'))
                   OR (p.healthInsuranceNumber IS NOT NULL AND p.healthInsuranceNumber LIKE CONCAT('%', :query, '%')))
              AND (:dob IS NULL OR p.dob = :dob)
              AND (:status IS NULL OR p.status = :status)
            """)
    Page<ReceptionPatientRecord> search(@Param("branchId") Long branchId, @Param("query") String query,
                                @Param("dob") LocalDate dob, @Param("status") String status,
                                Pageable pageable);

    @Query(value = """
            SELECT p FROM ReceptionPatientRecord p JOIN FETCH p.preferredBranch b
            WHERE (:branchId IS NULL OR b.branchId = :branchId)
              AND (:query IS NULL OR LOWER(p.patientCode) LIKE LOWER(CONCAT('%', :query, '%'))
                   OR LOWER(p.fullName) LIKE LOWER(CONCAT('%', :query, '%'))
                   OR p.phoneNumber LIKE CONCAT('%', :query, '%')
                   OR (p.idCardNumber IS NOT NULL AND p.idCardNumber LIKE CONCAT('%', :query, '%'))
                   OR (p.healthInsuranceNumber IS NOT NULL AND p.healthInsuranceNumber LIKE CONCAT('%', :query, '%')))
              AND (:dob IS NULL OR p.dob = :dob)
              AND (:status IS NULL OR p.status = :status)
              AND (:customerSource IS NULL OR p.customerSource = :customerSource)
            """, countQuery = """
            SELECT COUNT(p) FROM ReceptionPatientRecord p
            WHERE (:branchId IS NULL OR p.preferredBranch.branchId = :branchId)
              AND (:query IS NULL OR LOWER(p.patientCode) LIKE LOWER(CONCAT('%', :query, '%'))
                   OR LOWER(p.fullName) LIKE LOWER(CONCAT('%', :query, '%'))
                   OR p.phoneNumber LIKE CONCAT('%', :query, '%')
                   OR (p.idCardNumber IS NOT NULL AND p.idCardNumber LIKE CONCAT('%', :query, '%'))
                   OR (p.healthInsuranceNumber IS NOT NULL AND p.healthInsuranceNumber LIKE CONCAT('%', :query, '%')))
              AND (:dob IS NULL OR p.dob = :dob)
              AND (:status IS NULL OR p.status = :status)
              AND (:customerSource IS NULL OR p.customerSource = :customerSource)
            """)
    Page<ReceptionPatientRecord> searchWithSource(@Param("branchId") Long branchId, @Param("query") String query,
                                @Param("dob") LocalDate dob, @Param("status") String status,
                                @Param("customerSource") String customerSource,
                                Pageable pageable);

    Optional<ReceptionPatientRecord> findById(Long id);

    @Modifying
    @Transactional
    @Query("UPDATE ReceptionPatientRecord p SET p.status = :status, p.updatedAt = CURRENT_TIMESTAMP WHERE p.profileId = :id")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    @Query("""
            SELECT p FROM ReceptionPatientRecord p JOIN FETCH p.preferredBranch b
            WHERE (:branchId IS NULL OR b.branchId = :branchId)
              AND (
                (p.phoneNumber IS NOT NULL AND p.phoneNumber = :phone)
                OR (p.dob IS NOT NULL AND p.dob = :dob AND LOWER(p.fullName) = :name)
                OR (:idCardNumber IS NOT NULL AND :idCardNumber != '' AND p.idCardNumber = :idCardNumber)
              )
            ORDER BY p.profileId DESC
            """)
    List<ReceptionPatientRecord> candidates(@Param("branchId") Long branchId, @Param("phone") String phone,
                                    @Param("dob") LocalDate dob, @Param("name") String normalizedName,
                                    @Param("idCardNumber") String idCardNumber,
                                    Pageable pageable);

    default List<ReceptionPatientRecord> candidates(Long branchId, String phone, LocalDate dob,
                                                    String normalizedName, Pageable pageable) {
        return candidates(branchId, phone, dob, normalizedName, null, pageable);
    }
}
