package com.cvdcms.entity;

import com.cvdcms.entity.enums.RefundMethod;
import com.cvdcms.entity.enums.SalaryAdvanceStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "salary_advances",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_adv_code", columnNames = "advance_code")
        },
        indexes = {
                @Index(name = "idx_adv_staff_status", columnList = "staff_id, status"),
                @Index(name = "idx_adv_branch_date", columnList = "branch_id, paid_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class SalaryAdvance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "advance_id")
    private Long advanceId;

    @Column(name = "advance_code", nullable = false, length = 30, unique = true)
    private String advanceCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_id", nullable = false)
    private StaffProfile staff;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 30)
    private RefundMethod paymentMethod = RefundMethod.BANK_TRANSFER;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SalaryAdvanceStatus status = SalaryAdvanceStatus.PENDING;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "allocated_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal allocatedAmount = BigDecimal.ZERO;

    @Column(name = "remaining_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal remainingAmount = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private StaffProfile approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "notes", length = 500)
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.advanceCode == null || this.advanceCode.isEmpty()) {
            this.advanceCode = "TU" + System.currentTimeMillis();
        }
        if (this.remainingAmount == null) {
            this.remainingAmount = this.amount != null ? this.amount : BigDecimal.ZERO;
        }
    }
}
