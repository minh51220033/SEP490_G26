package com.cvdcms.patient;

import com.cvdcms.entity.Branch;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Bản ánh xạ riêng cho hồ sơ do lễ tân quản lý. Không thay đổi entity portal hiện hữu.
 * Cột user_id được để trống cho hồ sơ chưa nhận quyền tài khoản.
 */
@Entity(name = "ReceptionPatientRecord")
@Table(name = "patient_profiles")
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
public class ReceptionPatientRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "profile_id")
    private Long profileId;

    @Column(name = "user_id", insertable = false, updatable = false)
    private Long userId;

    @Column(name = "patient_code", nullable = false, length = 20)
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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "preferred_branch_id", nullable = false)
    private Branch preferredBranch;

    @Column(name = "allergy_details", length = 1000)
    private String allergyDetails;

    @Column(name = "has_allergies")
    private Boolean hasAllergies;

    @Column(name = "underlying_disease_details", length = 1000)
    private String underlyingDiseaseDetails;

    @Column(name = "has_underlying_disease")
    private Boolean hasUnderlyingDisease;

    @Column(name = "guardian_name", length = 100)
    private String guardianName;

    @Column(name = "guardian_phone", length = 20)
    private String guardianPhone;

    @Column(name = "guardian_relationship", length = 50)
    private String guardianRelationship;

    @Column(name = "id_card_number", length = 20)
    private String idCardNumber;

    @Column(name = "health_insurance_number", length = 20)
    private String healthInsuranceNumber;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;

    @Column(name = "medical_history_completed", nullable = false)
    private Boolean medicalHistoryCompleted = false;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "ACTIVE";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
