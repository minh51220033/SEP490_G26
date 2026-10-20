package com.cvdcms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "clinical_examinations",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_exam_visit", columnNames = "visit_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class ClinicalExamination {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "exam_id")
    private Long examId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "visit_id", nullable = false, unique = true)
    private Visit visit;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private PatientProfile patientProfile;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "doctor_id", nullable = false)
    private StaffProfile doctor;

    @Column(name = "chief_complaint", nullable = false, length = 500)
    private String chiefComplaint;

    @Column(name = "history_illness", columnDefinition = "TEXT")
    private String historyIllness;

    @Column(name = "vital_signs_json", columnDefinition = "json")
    private String vitalSignsJson;

    @Column(name = "extraoral_exam", columnDefinition = "TEXT")
    private String extraoralExam;

    @Column(name = "intraoral_exam", columnDefinition = "TEXT")
    private String intraoralExam;

    @Column(name = "periodontal_status", columnDefinition = "TEXT")
    private String periodontalStatus;

    @Column(name = "odontogram_chart_json", columnDefinition = "json")
    private String odontogramChartJson;

    @Column(name = "summary_assessment", columnDefinition = "TEXT")
    private String summaryAssessment;

    @CreationTimestamp
    @Column(name = "exam_time", nullable = false, updatable = false)
    private LocalDateTime examTime;

    @OneToMany(mappedBy = "clinicalExamination", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ExaminationDiagnosis> examinationDiagnoses = new ArrayList<>();
}
