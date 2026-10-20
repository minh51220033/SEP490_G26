package com.cvdcms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "clinical_workflow_step_paraclinical",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_cwsp", columnNames = {"step_id", "service_id"})
        }
)
@Getter
@Setter
@NoArgsConstructor
public class ClinicalWorkflowStepParaclinical {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "step_paraclinical_id")
    private Long stepParaclinicalId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "step_id", nullable = false)
    private ClinicalWorkflowStep step;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private Service service;

    @Column(name = "is_required", nullable = false)
    private Boolean isRequired = true;

    @Column(name = "result_required", nullable = false)
    private Boolean resultRequired = true;
}
