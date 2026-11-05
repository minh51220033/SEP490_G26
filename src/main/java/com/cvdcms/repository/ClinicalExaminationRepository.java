package com.cvdcms.repository;

import com.cvdcms.entity.ClinicalExamination;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ClinicalExaminationRepository extends JpaRepository<ClinicalExamination, Long> {

    Optional<ClinicalExamination> findByVisit_VisitId(Long visitId);

    @org.springframework.data.jpa.repository.Query("""
        SELECT ce FROM ClinicalExamination ce
        JOIN FETCH ce.visit v
        WHERE v.patientProfile.profileId = :profileId
        ORDER BY v.checkInAt DESC
    """)
    java.util.List<ClinicalExamination> findByProfileIdWithDetails(@org.springframework.data.repository.query.Param("profileId") Long profileId);

    boolean existsByVisit_VisitId(Long visitId);
}
