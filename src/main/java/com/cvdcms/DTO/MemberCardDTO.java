package com.cvdcms.DTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class MemberCardDTO {

    private Long profileId;
    private String patientCode;
    private String fullName;
    private String phoneNumber;
    private String gender;
    private LocalDate dob;
    private String address;
    private String preferredBranchName;
    private Long preferredBranchId;
    private String avatarUrl;

    // Membership Tier Info
    private Long tierId;
    private String tierCode;
    private String tierName;
    private BigDecimal discountPercent = BigDecimal.ZERO;
    private BigDecimal spendPerPoint = new BigDecimal("10000.00");
    private BigDecimal pointValue = new BigDecimal("10.00");

    // Loyalty Wallet Info
    private Integer currentPoints = 0;
    private BigDecimal totalSpentAmount = BigDecimal.ZERO;
    private LocalDateTime lastUpdated;

    // Next Tier Progression Info
    private String nextTierName;
    private BigDecimal nextTierMinSpent;
    private BigDecimal amountNeededForNextTier;
    private Integer progressPercent = 0;

    // List of benefits
    private List<String> benefits = new ArrayList<>();
}
