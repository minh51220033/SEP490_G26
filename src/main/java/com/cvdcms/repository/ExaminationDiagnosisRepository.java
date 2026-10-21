package com.cvdcms.repository;

import com.cvdcms.entity.ExaminationDiagnosis;
import com.cvdcms.entity.enums.DiagnosisType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExaminationDiagnosisRepository extends JpaRepository<ExaminationDiagnosis, Long> {

    @Query("""
        SELECT ed FROM ExaminationDiagnosis ed
        LEFT JOIN FETCH ed.icd10Disease
        LEFT JOIN FETCH ed.visit v
        LEFT JOIN FETCH v.patientProfile
        WHERE ed.visit.visitId = :visitId
        ORDER BY CASE ed.diagnosisType
            WHEN com.cvdcms.entity.enums.DiagnosisType.MAIN THEN 1
            WHEN com.cvdcms.entity.enums.DiagnosisType.SECONDARY THEN 2
            WHEN com.cvdcms.entity.enums.DiagnosisType.DIFFERENTIAL THEN 3
            ELSE 4 END, ed.diagnosisId ASC
    """)
    List<ExaminationDiagnosis> findByVisitIdWithDetails(@Param("visitId") Long visitId);

    List<ExaminationDiagnosis> findByVisit_VisitId(Long visitId);

    List<ExaminationDiagnosis> findByClinicalExamination_ExamId(Long examId);

    boolean existsByVisit_VisitIdAndDiagnosisType(Long visitId, DiagnosisType diagnosisType);

    long countByVisit_VisitId(Long visitId);
}
