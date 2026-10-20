package com.cvdcms.entity;

import com.cvdcms.entity.enums.PromotionType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "promotions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_promo_code", columnNames = "promo_code")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class Promotion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "promotion_id")
    private Long promotionId;

    @Column(name = "promo_code", nullable = false, length = 30, unique = true)
    private String promoCode;

    @Column(name = "promo_name", nullable = false, length = 200)
    private String promoName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @Enumerated(EnumType.STRING)
    @Column(name = "promo_type", nullable = false, length = 20)
    private PromotionType promoType = PromotionType.PERCENTAGE;

    @Column(name = "discount_value", nullable = false, precision = 15, scale = 2)
    private BigDecimal discountValue;

    @Column(name = "max_discount_amount", precision = 15, scale = 2)
    private BigDecimal maxDiscountAmount;

    @Column(name = "min_bill_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal minBillAmount = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "applicable_tier_id")
    private MembershipTier applicableTier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "applicable_service_id")
    private Service applicableService;

    @Column(name = "start_date", nullable = false)
    private LocalDateTime startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDateTime endDate;

    @Column(name = "usage_limit")
    private Integer usageLimit;

    @Column(name = "used_count", nullable = false)
    private Integer usedCount = 0;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
}
