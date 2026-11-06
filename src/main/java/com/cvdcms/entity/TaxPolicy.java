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
        name = "tax_policies",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_tax_policy_code", columnNames = "policy_code")
        },
        indexes = {
                @Index(name = "idx_tp_dates", columnList = "effective_from, effective_to")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class TaxPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "policy_id")
    private Long policyId;

    @Column(name = "policy_code", nullable = false, length = 50, unique = true)
    private String policyCode;

    @Column(name = "policy_name", nullable = false, length = 150)
    private String policyName;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "personal_relief", nullable = false, precision = 15, scale = 2)
    private BigDecimal personalRelief = BigDecimal.valueOf(11000000);

    @Column(name = "dependent_relief", nullable = false, precision = 15, scale = 2)
    private BigDecimal dependentRelief = BigDecimal.valueOf(4400000);

    @Column(name = "casual_tax_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal casualTaxRate = BigDecimal.valueOf(10.0);

    @Column(name = "non_resident_tax_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal nonResidentTaxRate = BigDecimal.valueOf(20.0);

    @Column(name = "insurance_employee_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal insuranceEmployeeRate = BigDecimal.valueOf(10.5);

    @Column(name = "insurance_employer_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal insuranceEmployerRate = BigDecimal.valueOf(21.5);

    @Column(name = "legal_reference", nullable = false, length = 255)
    private String legalReference;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}

