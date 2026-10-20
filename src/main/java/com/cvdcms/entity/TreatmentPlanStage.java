package com.cvdcms.entity;

import com.cvdcms.entity.enums.TreatmentPlanStageStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "treatment_plan_stages",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_tps", columnNames = {"plan_id", "stage_order"})
        }
)
@Getter
@Setter
@NoArgsConstructor
public class TreatmentPlanStage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "stage_id")
    private Long stageId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private TreatmentPlan plan;

    @Column(name = "stage_order", nullable = false)
    private Integer stageOrder;

    @Column(name = "stage_name", nullable = false, length = 150)
    private String stageName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TreatmentPlanStageStatus status = TreatmentPlanStageStatus.PENDING;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @OneToMany(mappedBy = "stage", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TreatmentPlanItem> items = new ArrayList<>();
}
