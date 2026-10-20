package com.cvdcms.entity;

import com.cvdcms.entity.enums.RefundMethod;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "refunds",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_ref_code", columnNames = "refund_code")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "refund_id")
    private Long refundId;

    @Column(name = "refund_code", nullable = false, length = 20, unique = true)
    private String refundCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id")
    private Invoice invoice;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private PatientProfile patientProfile;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_method", nullable = false, length = 20)
    private RefundMethod refundMethod = RefundMethod.CASH;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "refunded_by", nullable = false)
    private StaffProfile refundedBy;

    @CreationTimestamp
    @Column(name = "refunded_at", nullable = false, updatable = false)
    private LocalDateTime refundedAt;

    @PrePersist
    public void prePersist() {
        if (this.refundCode == null || this.refundCode.isEmpty()) {
            this.refundCode = "HT" + System.currentTimeMillis();
        }
    }
}
