package com.cvdcms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "patient_profiles")
@Getter
@Setter
@NoArgsConstructor
public class PatientProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "profile_id")
    private Long profileId;

    @Column(name = "patient_code", unique = true, length = 20, nullable = false)
    private String patientCode; // Thêm trường này

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

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

    @Column(name = "status", nullable = false, length = 20)
    private String status = "ACTIVE";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Tự động gán mã bệnh nhân trước khi Hibernate thực hiện câu lệnh Insert
    @PrePersist
    public void prePersist() {
        if (this.patientCode == null || this.patientCode.isEmpty()) {
            this.patientCode = "BN" + System.currentTimeMillis();
        }
    }
}