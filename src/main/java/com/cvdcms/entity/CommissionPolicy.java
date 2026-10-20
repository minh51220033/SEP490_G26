package com.cvdcms.entity;

import com.cvdcms.entity.enums.CommissionType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "commission_policies")
@Getter
@Setter
@NoArgsConstructor
public class CommissionPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "policy_id")
    private Long policyId;

    @Column(name = "policy_name", nullable = false, length = 150)
    private String policyName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id")
    private Service service;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id")
    private Role role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_id")
    private StaffProfile staff;

    @Enumerated(EnumType.STRING)
    @Column(name = "commission_type", nullable = false, length = 20)
    private CommissionType commissionType = CommissionType.PERCENTAGE;

    @Column(name = "commission_value", nullable = false, precision = 15, scale = 2)
    private BigDecimal commissionValue;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
}
