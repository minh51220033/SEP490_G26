package com.cvdcms.entity;

import com.cvdcms.entity.enums.DiagnosisType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "examination_diagnoses")
@Getter
@Setter
@NoArgsConstructor
public class ExaminationDiagnosis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "diagnosis_id")
    private Long diagnosisId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_id", nullable = false)
    private ClinicalExamination clinicalExamination;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "icd10_code", referencedColumnName = "icd10_code")
    private Icd10Disease icd10Disease;

    @Column(name = "diagnosis_text", nullable = false, length = 255)
    private String diagnosisText;

    @Enumerated(EnumType.STRING)
    @Column(name = "diagnosis_type", nullable = false, length = 20)
    private DiagnosisType diagnosisType = DiagnosisType.MAIN;

    @Column(name = "tooth_numbers", length = 100)
    private String toothNumbers;

    @Column(name = "notes", length = 255)
    private String notes;
}
