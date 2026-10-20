package com.cvdcms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "clinical_workflow_step_roles",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_cwsr", columnNames = {"step_id", "role_id"})
        }
)
@Getter
@Setter
@NoArgsConstructor
public class ClinicalWorkflowStepRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "step_role_id")
    private Long stepRoleId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "step_id", nullable = false)
    private ClinicalWorkflowStep step;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @Column(name = "is_primary", nullable = false)
    private Boolean isPrimary = false;
}
