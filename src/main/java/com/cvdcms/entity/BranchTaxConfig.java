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
        name = "branch_tax_configs",
        indexes = {
                @Index(name = "idx_btc_branch_dates", columnList = "branch_id, effective_from, effective_to")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class BranchTaxConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "config_id")
    private Long configId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Column(name = "entity_type", nullable = false, length = 30)
    private String entityType = "ENTERPRISE"; // ENTERPRISE, HOUSEHOLD

    @Column(name = "tax_regime", nullable = false, length = 30)
    private String taxRegime = "CIT_STANDARD"; // CIT_STANDARD, REVENUE_PERCENT

    @Column(name = "cit_rate", precision = 5, scale = 2)
    private BigDecimal citRate = BigDecimal.valueOf(20.0);

    @Column(name = "service_tax_rate", precision = 5, scale = 2)
    private BigDecimal serviceTaxRate = BigDecimal.valueOf(2.0);

    @Column(name = "medicine_tax_rate", precision = 5, scale = 2)
    private BigDecimal medicineTaxRate = BigDecimal.valueOf(0.5);

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "legal_reference", length = 255)
    private String legalReference;

    @Column(name = "notes", length = 500)
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}

