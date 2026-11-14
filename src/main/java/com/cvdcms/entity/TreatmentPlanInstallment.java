package com.cvdcms.entity;

import com.cvdcms.entity.enums.TreatmentPlanInstallmentStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "treatment_plan_installments",
        indexes = {
                @Index(name = "idx_tpi_plan", columnList = "plan_id"),
                @Index(name = "idx_tpi_date_status", columnList = "expected_date, status")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class TreatmentPlanInstallment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "installment_id")
    private Long installmentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private TreatmentPlan plan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stage_id")
    private TreatmentPlanStage stage;

    @Column(name = "installment_order", nullable = false)
    private Integer installmentOrder = 1;

    @Column(name = "phase_name", nullable = false, length = 150)
    private String phaseName;

    @Column(name = "expected_date")
    private LocalDate expectedDate;

    @Column(name = "expected_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal expectedAmount = BigDecimal.ZERO;

    @Column(name = "paid_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TreatmentPlanInstallmentStatus status = TreatmentPlanInstallmentStatus.PENDING;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "reminded")
    private Boolean reminded = false;

    @Column(name = "reminded_at")
    private LocalDateTime remindedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Transient
    public BigDecimal getRemainingAmount() {
        BigDecimal exp = expectedAmount != null ? expectedAmount : BigDecimal.ZERO;
        BigDecimal paid = paidAmount != null ? paidAmount : BigDecimal.ZERO;
        BigDecimal diff = exp.subtract(paid);
        return diff.compareTo(BigDecimal.ZERO) > 0 ? diff : BigDecimal.ZERO;
    }
}

