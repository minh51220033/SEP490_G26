package com.cvdcms.entity;

import com.cvdcms.entity.enums.TreatmentPlanItemStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "treatment_plan_items")
@Getter
@Setter
@NoArgsConstructor
public class TreatmentPlanItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "plan_item_id")
    private Long planItemId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stage_id", nullable = false)
    private TreatmentPlanStage stage;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private TreatmentPlan plan;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private Service service;

    @Column(name = "tooth_numbers", length = 50)
    private String toothNumbers;

    @Column(name = "quantity", nullable = false, precision = 5, scale = 2)
    private BigDecimal quantity = BigDecimal.ONE;

    @Column(name = "estimated_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal estimatedPrice = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TreatmentPlanItemStatus status = TreatmentPlanItemStatus.PLANNED;
}
