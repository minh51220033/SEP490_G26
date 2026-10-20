package com.cvdcms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(
        name = "membership_tiers",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_tier_code", columnNames = "tier_code")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class MembershipTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "tier_id")
    private Long tierId;

    @Column(name = "tier_code", nullable = false, length = 20, unique = true)
    private String tierCode;

    @Column(name = "tier_name", nullable = false, length = 50)
    private String tierName;

    @Column(name = "min_total_spent", nullable = false, precision = 15, scale = 2)
    private BigDecimal minTotalSpent = BigDecimal.ZERO;

    @Column(name = "discount_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPercent = BigDecimal.ZERO;

    @Column(name = "spend_per_point", nullable = false, precision = 15, scale = 2)
    private BigDecimal spendPerPoint = new BigDecimal("10000.00");

    @Column(name = "point_value", nullable = false, precision = 15, scale = 2)
    private BigDecimal pointValue = new BigDecimal("1000.00");

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
}
