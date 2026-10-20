package com.cvdcms.entity;

import com.cvdcms.entity.enums.FlowMode;
import com.cvdcms.entity.enums.WorkflowVersionStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "clinical_workflow_versions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_cwv", columnNames = {"template_id", "version_number"})
        }
)
@Getter
@Setter
@NoArgsConstructor
public class ClinicalWorkflowVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "version_id")
    private Long versionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_id", nullable = false)
    private ClinicalWorkflowTemplate template;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WorkflowVersionStatus status = WorkflowVersionStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "flow_mode", nullable = false, length = 20)
    private FlowMode flowMode = FlowMode.CONTINUOUS;

    @Column(name = "effective_from")
    private LocalDateTime effectiveFrom;

    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;

    @Column(name = "change_summary", length = 500)
    private String changeSummary;

    @Column(name = "created_by")
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;
}
