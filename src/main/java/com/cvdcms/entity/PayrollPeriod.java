package com.cvdcms.entity;

import com.cvdcms.entity.enums.PayrollStatus;
import com.cvdcms.entity.enums.RefundMethod;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "payroll_periods",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payroll_period", columnNames = {"branch_id", "month", "year"})
        }
)
@Getter
@Setter
@NoArgsConstructor
public class PayrollPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "period_id")
    private Long periodId;

    @Column(name = "period_name", nullable = false, length = 100)
    private String periodName;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Column(name = "month", nullable = false)
    private Integer month;

    @Column(name = "year", nullable = false)
    private Integer year;

    @Column(name = "total_salary", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalSalary = BigDecimal.ZERO;

    @Column(name = "total_base_salary", precision = 15, scale = 2)
    private BigDecimal totalBaseSalary = BigDecimal.ZERO;

    @Column(name = "total_allowance", precision = 15, scale = 2)
    private BigDecimal totalAllowance = BigDecimal.ZERO;

    @Column(name = "total_bonus", precision = 15, scale = 2)
    private BigDecimal totalBonus = BigDecimal.ZERO;

    @Column(name = "total_commission", precision = 15, scale = 2)
    private BigDecimal totalCommission = BigDecimal.ZERO;

    @Column(name = "total_deduction", precision = 15, scale = 2)
    private BigDecimal totalDeduction = BigDecimal.ZERO;

    @Column(name = "total_advance_allocated", precision = 15, scale = 2)
    private BigDecimal totalAdvanceAllocated = BigDecimal.ZERO;

    @Column(name = "total_remaining_payable", precision = 15, scale = 2)
    private BigDecimal totalRemainingPayable = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PayrollStatus status = PayrollStatus.DRAFT;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submitted_by")
    private StaffProfile submittedBy;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private StaffProfile approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paid_by")
    private StaffProfile paidBy;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 30)
    private RefundMethod paymentMethod;

    @Column(name = "payment_reference", length = 100)
    private String paymentReference;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
