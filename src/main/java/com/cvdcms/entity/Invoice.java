package com.cvdcms.entity;

import com.cvdcms.entity.enums.InvoicePaymentStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "invoices",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_invoice_code", columnNames = "invoice_code")
        },
        indexes = {
                @Index(name = "idx_inv_profile", columnList = "profile_id"),
                @Index(name = "idx_inv_branch_date", columnList = "branch_id, issued_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "invoice_id")
    private Long invoiceId;

    @Column(name = "invoice_code", nullable = false, length = 20, unique = true)
    private String invoiceCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_type", nullable = false, length = 20)
    private com.cvdcms.entity.enums.InvoiceType invoiceType = com.cvdcms.entity.enums.InvoiceType.SERVICE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id")
    private Visit visit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prescription_id")
    private Prescription prescription;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private PatientProfile patientProfile;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cashier_id", nullable = false)
    private StaffProfile cashier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "promotion_id")
    private Promotion promotion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "voucher_id")
    private PatientVoucher voucher;

    @Column(name = "total_service_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalServiceAmount = BigDecimal.ZERO;

    @Column(name = "total_medicine_amount", nullable = false, precision = 15, scale = 2)
    @org.hibernate.annotations.ColumnDefault("0")
    private BigDecimal totalMedicineAmount = BigDecimal.ZERO;

    @Column(name = "surcharge_amount", nullable = false, precision = 15, scale = 2)
    @org.hibernate.annotations.ColumnDefault("0")
    private BigDecimal surchargeAmount = BigDecimal.ZERO;

    @Column(name = "surcharge_description", length = 500)
    private String surchargeDescription;

    @Column(name = "discount_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "promotion_discount", nullable = false, precision = 15, scale = 2)
    private BigDecimal promotionDiscount = BigDecimal.ZERO;

    @Column(name = "point_discount", nullable = false, precision = 15, scale = 2)
    private BigDecimal pointDiscount = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "paid_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Column(name = "remaining_debt", nullable = false, precision = 15, scale = 2)
    private BigDecimal remainingDebt = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private InvoicePaymentStatus paymentStatus = InvoicePaymentStatus.UNPAID;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private com.cvdcms.entity.enums.InvoiceStatus status = com.cvdcms.entity.enums.InvoiceStatus.CONFIRMED;

    @CreationTimestamp
    @Column(name = "issued_at", nullable = false, updatable = false)
    private LocalDateTime issuedAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "adjusted_by")
    private StaffProfile adjustedBy;

    @Column(name = "adjustment_reason", length = 500)
    private String adjustmentReason;

    @Column(name = "signature_hash", columnDefinition = "TEXT")
    private String signatureHash;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<InvoiceItem> items = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        if (this.invoiceCode == null || this.invoiceCode.isEmpty()) {
            this.invoiceCode = "HD" + System.currentTimeMillis();
        }
    }

    @Transient
    public BigDecimal getTotalDiscount() {
        BigDecimal d1 = discountAmount != null ? discountAmount : BigDecimal.ZERO;
        BigDecimal d2 = promotionDiscount != null ? promotionDiscount : BigDecimal.ZERO;
        BigDecimal d3 = pointDiscount != null ? pointDiscount : BigDecimal.ZERO;
        return d1.add(d2).add(d3);
    }
}
