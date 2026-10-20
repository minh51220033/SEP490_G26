package com.cvdcms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "clinical_workflow_transition_rules")
@Getter
@Setter
@NoArgsConstructor
public class ClinicalWorkflowTransitionRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rule_id")
    private Long ruleId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false)
    private ClinicalWorkflowVersion version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_step_id", nullable = false)
    private ClinicalWorkflowStep fromStep;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_step_id")
    private ClinicalWorkflowStep toStep;

    @Column(name = "condition_expression", columnDefinition = "json")
    private String conditionExpression;

    @Column(name = "priority_order", nullable = false)
    private Integer priorityOrder = 0;

    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;
}
