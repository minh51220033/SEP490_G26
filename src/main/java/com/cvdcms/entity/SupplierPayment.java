package com.cvdcms.entity;

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
        name = "supplier_payments",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_sp_payment_code", columnNames = "payment_code")
        },
        indexes = {
                @Index(name = "idx_sp_branch_supplier", columnList = "branch_id, supplier_id, payment_date")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class SupplierPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "supplier_payment_id")
    private Long supplierPaymentId;

    @Column(name = "payment_code", nullable = false, length = 30, unique = true)
    private String paymentCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_id")
    private StockReceipt receipt;

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "payment_method", nullable = false, length = 30)
    private String paymentMethod = "BANK_TRANSFER";

    @Column(name = "reference_no", length = 100)
    private String referenceNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "paid_by", nullable = false)
    private StaffProfile paidBy;

    @Column(name = "notes", length = 255)
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.paymentCode == null || this.paymentCode.isEmpty()) {
            this.paymentCode = "PC" + System.currentTimeMillis();
        }
    }
}
