package com.cvdcms.repository;

import com.cvdcms.entity.Prescription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PrescriptionRepository extends JpaRepository<Prescription, Long> {

    List<Prescription> findByVisit_VisitIdOrderByCreatedAtDesc(Long visitId);

    List<Prescription> findByPatientProfile_ProfileIdOrderByCreatedAtDesc(Long profileId);

    @org.springframework.data.jpa.repository.Query("""
        SELECT p FROM Prescription p
        LEFT JOIN FETCH p.patientProfile
        LEFT JOIN FETCH p.visit
        LEFT JOIN FETCH p.branch
        LEFT JOIN FETCH p.doctor
        LEFT JOIN FETCH p.items i
        LEFT JOIN FETCH i.medication
        WHERE p.prescriptionId = :prescriptionId
    """)
    java.util.Optional<Prescription> findByIdWithDetails(@org.springframework.data.repository.query.Param("prescriptionId") Long prescriptionId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT p FROM Prescription p WHERE p.prescriptionId = :prescriptionId")
    java.util.Optional<Prescription> findByIdForUpdate(@org.springframework.data.repository.query.Param("prescriptionId") Long prescriptionId);

    @org.springframework.data.jpa.repository.Query("""
        SELECT DISTINCT p FROM Prescription p
        LEFT JOIN FETCH p.patientProfile
        LEFT JOIN FETCH p.visit
        LEFT JOIN FETCH p.doctor
        WHERE (:branchId IS NULL OR p.branch.branchId = :branchId)
        ORDER BY p.createdAt DESC
    """)
    List<Prescription> findByBranchOrderByCreatedAtDesc(@org.springframework.data.repository.query.Param("branchId") Long branchId);
}
