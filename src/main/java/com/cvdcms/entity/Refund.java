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
                @UniqueConstraint(name = "uk_ref_code", columnNames = "refund_code"),
                @UniqueConstraint(name = "uk_ref_idempotency", columnNames = "idempotency_key")
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

    @Column(name = "refund_code", nullable = false, length = 30, unique = true)
    private String refundCode;

    @Column(name = "idempotency_key", length = 100, unique = true)
    private String idempotencyKey;

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

    @Column(name = "reduce_invoice_total", nullable = false)
    private Boolean reduceInvoiceTotal = false;

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
