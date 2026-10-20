package com.cvdcms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "patient_profiles",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_patient_code",
                        columnNames = "patient_code"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
public class PatientProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "profile_id")
    private Long profileId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "previous_user_id")
    private User previousUser;

    @Column(name = "separated_at")
    private LocalDateTime separatedAt;

    @Column(name = "patient_code", unique = true, length = 20, nullable = false)
    private String patientCode;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Column(name = "dob")
    private LocalDate dob;

    @Column(name = "gender", nullable = false, length = 20)
    private String gender;

    @Column(name = "phone_number", nullable = false, length = 20)
    private String phoneNumber;

    @Column(name = "address", nullable = false, length = 255)
    private String address;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(name = "id_card_number", length = 20)
    private String idCardNumber;

    @Column(name = "health_insurance_number", length = 20)
    private String healthInsuranceNumber;

    @Column(name = "guardian_name", length = 100)
    private String guardianName;

    @Column(name = "guardian_phone", length = 20)
    private String guardianPhone;

    @Column(name = "guardian_relationship", length = 50)
    private String guardianRelationship;

    @Column(name = "has_allergies")
    private Boolean hasAllergies;

    @Column(name = "allergy_details", length = 1000)
    private String allergyDetails;

    @Column(name = "has_cardiovascular_disease")
    private Boolean hasCardiovascularDisease;

    @Column(name = "cardiovascular_details", length = 1000)
    private String cardiovascularDetails;

    @Column(name = "has_underlying_disease")
    private Boolean hasUnderlyingDisease;

    @Column(name = "underlying_disease_details", length = 1000)
    private String underlyingDiseaseDetails;

    @Column(name = "medical_history_completed", nullable = false)
    private Boolean medicalHistoryCompleted = false;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "preferred_branch_id", nullable = false)
    private Branch preferredBranch;

    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "membership_tier_id")
    private MembershipTier membershipTier;

    @Column(name = "deposit_balance", nullable = false, precision = 15, scale = 2)
    private BigDecimal depositBalance = BigDecimal.ZERO;

    @Column(name = "customer_source", length = 100)
    private String customerSource;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "ACTIVE";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (this.patientCode == null || this.patientCode.isEmpty()) {
            this.patientCode = "BN" + System.currentTimeMillis();
        }
    }
}