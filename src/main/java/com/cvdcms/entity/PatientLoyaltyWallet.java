package com.cvdcms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "patient_loyalty_wallets",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_plw_profile", columnNames = "profile_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class PatientLoyaltyWallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "wallet_id")
    private Long walletId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false, unique = true)
    private PatientProfile patientProfile;

    @Column(name = "current_points", nullable = false)
    private Integer currentPoints = 0;

    @Column(name = "total_spent_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalSpentAmount = BigDecimal.ZERO;

    @UpdateTimestamp
    @Column(name = "last_updated", nullable = false)
    private LocalDateTime lastUpdated;
}
