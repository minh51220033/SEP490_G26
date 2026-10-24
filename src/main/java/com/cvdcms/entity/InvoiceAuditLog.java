package com.cvdcms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "invoice_audit_logs",
        indexes = {
                @Index(name = "idx_inv_audit_invoice", columnList = "invoice_id"),
                @Index(name = "idx_inv_audit_date", columnList = "performed_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class InvoiceAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Column(name = "action_type", nullable = false, length = 50)
    private String actionType; // CREATE, ADJUST, CANCEL, PAYMENT, REFUND

    @Column(name = "old_total_amount", precision = 15, scale = 2)
    private BigDecimal oldTotalAmount;

    @Column(name = "new_total_amount", precision = 15, scale = 2)
    private BigDecimal newTotalAmount;

    @Column(name = "old_discount_amount", precision = 15, scale = 2)
    private BigDecimal oldDiscountAmount;

    @Column(name = "new_discount_amount", precision = 15, scale = 2)
    private BigDecimal newDiscountAmount;

    @Column(name = "old_paid_amount", precision = 15, scale = 2)
    private BigDecimal oldPaidAmount;

    @Column(name = "new_paid_amount", precision = 15, scale = 2)
    private BigDecimal newPaidAmount;


    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performed_by")
    private StaffProfile performedBy;

    @CreationTimestamp
    @Column(name = "performed_at", nullable = false, updatable = false)
    private LocalDateTime performedAt;
}
