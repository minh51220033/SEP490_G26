package com.cvdcms.entity;

import com.cvdcms.entity.enums.RefundMethod;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "operating_expenses",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_exp_code", columnNames = "expense_code")
        },
        indexes = {
                @Index(name = "idx_exp_branch_date", columnList = "branch_id, expense_date")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class OperatingExpense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "expense_id")
    private Long expenseId;

    @Column(name = "expense_code", nullable = false, length = 20, unique = true)
    private String expenseCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Column(name = "category", nullable = false, length = 100)
    private String category;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @Column(name = "recipient_name", length = 150)
    private String recipientName;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private RefundMethod paymentMethod = RefundMethod.BANK_TRANSFER;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payroll_period_id")
    private PayrollPeriod payrollPeriod;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.expenseCode == null || this.expenseCode.isEmpty()) {
            this.expenseCode = "CP" + System.currentTimeMillis();
        }
    }
}
