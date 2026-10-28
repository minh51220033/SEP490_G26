package com.cvdcms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "prescription_items")
@Getter
@Setter
@NoArgsConstructor
public class PrescriptionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "item_id")
    private Long itemId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prescription_id", nullable = false)
    private Prescription prescription;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "medication_id")
    private Medication medication;

    @Column(name = "drug_name", nullable = false, length = 200)
    private String drugName;

    @Column(name = "dosage", nullable = false, length = 100)
    private String dosage;

    @Column(name = "duration_days", nullable = false)
    private Integer durationDays = 5;

    @Column(name = "quantity", nullable = false, precision = 6, scale = 2)
    private BigDecimal quantity;

    @Column(name = "bought_quantity", nullable = false, precision = 6, scale = 2)
    private BigDecimal boughtQuantity = BigDecimal.ZERO;

    @Column(name = "unit", nullable = false, length = 30)
    private String unit = "Viên";

    @Column(name = "usage_instruction", nullable = false, length = 255)
    private String usageInstruction;

    @Column(name = "unit_price", precision = 15, scale = 2)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Transient
    public BigDecimal getRemainingQuantity() {
        BigDecimal q = quantity != null ? quantity : BigDecimal.ZERO;
        BigDecimal b = boughtQuantity != null ? boughtQuantity : BigDecimal.ZERO;
        BigDecimal rem = q.subtract(b);
        return rem.compareTo(BigDecimal.ZERO) > 0 ? rem : BigDecimal.ZERO;
    }

    @Transient
    public boolean isFullyBought() {
        return getRemainingQuantity().compareTo(BigDecimal.ZERO) <= 0;
    }
}
