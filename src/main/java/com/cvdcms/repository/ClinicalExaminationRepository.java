package com.cvdcms.repository;

import com.cvdcms.entity.ClinicalExamination;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ClinicalExaminationRepository extends JpaRepository<ClinicalExamination, Long> {

    Optional<ClinicalExamination> findByVisit_VisitId(Long visitId);

    boolean existsByVisit_VisitId(Long visitId);
}
