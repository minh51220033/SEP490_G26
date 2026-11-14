package com.cvdcms.entity;

import com.cvdcms.entity.enums.TreatmentPlanInstallmentStatus;
import com.cvdcms.entity.enums.TreatmentPlanStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "treatment_plans",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_plan_code", columnNames = "plan_code")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class TreatmentPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "plan_id")
    private Long planId;

    @Column(name = "plan_code", nullable = false, length = 20, unique = true)
    private String planCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private PatientProfile patientProfile;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "doctor_id", nullable = false)
    private StaffProfile doctor;

    @Column(name = "plan_name", nullable = false, columnDefinition = "TEXT")
    private String planName;

    @Column(name = "total_estimated_cost", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalEstimatedCost = BigDecimal.ZERO;

    @Column(name = "final_estimated_cost", nullable = false, precision = 15, scale = 2)
    private BigDecimal finalEstimatedCost = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TreatmentPlanStatus status = TreatmentPlanStatus.DRAFT;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "expected_end_date")
    private LocalDate expectedEndDate;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "sent_to_accounting")
    private Boolean sentToAccounting = false;

    @Column(name = "sent_to_accounting_at")
    private LocalDateTime sentToAccountingAt;

    @Column(name = "is_installment")
    private Boolean isInstallment = false;

    public Boolean getIsInstallment() {
        return Boolean.TRUE.equals(this.isInstallment);
    }

    @Column(name = "total_paid_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalPaidAmount = BigDecimal.ZERO;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    @org.hibernate.annotations.BatchSize(size = 50)
    private List<TreatmentPlanStage> stages = new ArrayList<>();

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("installmentOrder ASC")
    @org.hibernate.annotations.BatchSize(size = 50)
    private List<TreatmentPlanInstallment> installments = new ArrayList<>();

    @Transient
    public BigDecimal getRemainingCost() {
        BigDecimal total = getEstimatedCost();
        BigDecimal paid = this.totalPaidAmount != null ? this.totalPaidAmount : BigDecimal.ZERO;
        BigDecimal diff = total.subtract(paid);
        return diff.compareTo(BigDecimal.ZERO) > 0 ? diff : BigDecimal.ZERO;
    }

    @Transient
    public BigDecimal getEstimatedCost() {
        if (this.finalEstimatedCost != null && this.finalEstimatedCost.compareTo(BigDecimal.ZERO) > 0) {
            return this.finalEstimatedCost;
        }
        return this.totalEstimatedCost != null ? this.totalEstimatedCost : BigDecimal.ZERO;
    }

    @Transient
    public boolean isFullyPaid() {
        return getEstimatedCost().compareTo(BigDecimal.ZERO) > 0
                && getRemainingCost().compareTo(BigDecimal.ZERO) <= 0;
    }

    @Transient
    public boolean isScheduleLocked() {
        BigDecimal paid = this.totalPaidAmount != null ? this.totalPaidAmount : BigDecimal.ZERO;
        return paid.compareTo(BigDecimal.ZERO) > 0 || isFullyPaid();
    }

    @Transient
    public TreatmentPlanInstallment getNextUnpaidInstallment() {
        if (this.installments == null || this.installments.isEmpty()) {
            return null;
        }
        for (TreatmentPlanInstallment inst : this.installments) {
            if (inst.getStatus() != TreatmentPlanInstallmentStatus.PAID) {
                return inst;
            }
        }
        return null;
    }

    @PrePersist
    public void prePersist() {
        if (this.planCode == null || this.planCode.isEmpty()) {
            this.planCode = "KHDT" + System.currentTimeMillis();
        }
    }
}

