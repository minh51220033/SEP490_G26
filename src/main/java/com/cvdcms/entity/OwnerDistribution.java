package com.cvdcms.entity;

import com.cvdcms.entity.enums.OwnerDistributionStatus;
import com.cvdcms.entity.enums.RefundMethod;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "owner_distributions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_dist_code", columnNames = "distribution_code")
        },
        indexes = {
                @Index(name = "idx_dist_month_year", columnList = "month, year")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class OwnerDistribution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "distribution_id")
    private Long distributionId;

    @Column(name = "distribution_code", nullable = false, length = 30, unique = true)
    private String distributionCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @Column(name = "month", nullable = false)
    private Integer month;

    @Column(name = "year", nullable = false)
    private Integer year;

    @Column(name = "distributable_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal distributableAmount = BigDecimal.ZERO;

    @Column(name = "retained_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal retainedAmount = BigDecimal.ZERO;

    @Column(name = "total_distributed_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalDistributedAmount = BigDecimal.ZERO;

    @Column(name = "basis_notes", columnDefinition = "TEXT")
    private String basisNotes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OwnerDistributionStatus status = OwnerDistributionStatus.DRAFT;

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

    @OneToMany(mappedBy = "distribution", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OwnerDistributionItem> items = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.distributionCode == null || this.distributionCode.isEmpty()) {
            this.distributionCode = "PP" + System.currentTimeMillis();
        }
    }
}
