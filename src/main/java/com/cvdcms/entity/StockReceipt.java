package com.cvdcms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "stock_receipts",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_sr_code", columnNames = "receipt_code")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class StockReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "receipt_id")
    private Long receiptId;

    @Column(name = "receipt_code", nullable = false, length = 20, unique = true)
    private String receiptCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Column(name = "supplier_invoice_no", length = 50)
    private String supplierInvoiceNo;

    @Column(name = "receipt_date", nullable = false)
    private LocalDate receiptDate;

    @Column(name = "total_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receiver_id", nullable = false)
    private StaffProfile receiver;

    @Column(name = "notes", length = 255)
    private String notes;

    @Column(name = "attachment_url", length = 500)
    private String attachmentUrl;

    @OneToMany(mappedBy = "receipt", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<StockReceiptItem> items = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        if (this.receiptCode == null || this.receiptCode.isEmpty()) {
            this.receiptCode = "PN" + System.currentTimeMillis();
        }
    }
}
