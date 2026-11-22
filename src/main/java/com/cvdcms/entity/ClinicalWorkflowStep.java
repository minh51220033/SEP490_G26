package com.cvdcms.entity;

import com.cvdcms.entity.enums.QueueType;
import com.cvdcms.entity.enums.StepStatus;
import com.cvdcms.entity.enums.StepType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "clinical_workflow_steps",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_cwst_code", columnNames = {"version_id", "step_code"})
        }
)
@Getter
@Setter
@NoArgsConstructor
public class ClinicalWorkflowStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "step_id")
    private Long stepId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false)
    private ClinicalWorkflowVersion version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_step_id")
    private ClinicalWorkflowStep parentStep;

    @Column(name = "step_code", nullable = false, length = 30)
    private String stepCode;

    @Column(name = "step_name", nullable = false, length = 200)
    private String stepName;

    @Column(name = "step_description", columnDefinition = "TEXT")
    private String stepDescription;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", nullable = false, length = 30)
    private StepType stepType = StepType.CLINICAL;

    /** Khung nhập liệu chuyên môn khi vào khám: CLINICAL_EXAM, XRAY, ENDODONTICS, PROCEDURE, PROSTHETICS_LABO. */
    @Column(name = "frame_key", length = 40)
    private String frameKey;

    @Column(name = "sequence_order", nullable = false)
    private Integer sequenceOrder;

    @Column(name = "is_optional", nullable = false)
    private Boolean isOptional = false;

    @Column(name = "completion_criteria", columnDefinition = "TEXT")
    private String completionCriteria;

    /** Danh sách trường cấu hình riêng cho bước (JSON array). */
    @Column(name = "custom_fields_json", columnDefinition = "TEXT")
    private String customFieldsJson;

    /** Khác null nếu là bước phát sinh riêng cho 1 WorkflowExecution cụ thể, không thuộc template chuẩn. */
    @Column(name = "adhoc_execution_id")
    private Long adhocExecutionId;

    @Column(name = "requires_clinical_approval", nullable = false)
    private Boolean requiresClinicalApproval = false;

    @Column(name = "estimated_duration_minutes")
    private Integer estimatedDurationMinutes;

    // Chi phí (tiền) của từng bước kỹ thuật
    @Column(name = "step_price", precision = 15, scale = 2)
    private java.math.BigDecimal stepPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_queue_type", length = 30)
    private QueueType targetQueueType;

    @Column(name = "queue_priority", nullable = false)
    private Integer queuePriority = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StepStatus status = StepStatus.ACTIVE;
}
