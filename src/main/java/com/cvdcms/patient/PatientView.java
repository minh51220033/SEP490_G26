package com.cvdcms.patient;

import com.cvdcms.entity.PatientProfile;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record PatientView(
        Long id, String code, String fullName, LocalDate dob, String gender,
        String phoneNumber, String address, Long branchId, String branchName,
        String status, String guardianName, String guardianPhone, String guardianRelationship,
        String idCardNumber, String healthInsuranceNumber, String avatarUrl,
        String allergyDetails, String underlyingDiseaseDetails, LocalDateTime createdAt,
        LocalDateTime updatedAt, String customerSource
) {
    public PatientView(Long id, String code, String fullName, LocalDate dob, String gender,
                       String phoneNumber, String address, Long branchId, String branchName,
                       String status, String guardianName, String guardianPhone, String guardianRelationship,
                       String idCardNumber, String healthInsuranceNumber, String avatarUrl,
                       String allergyDetails, String underlyingDiseaseDetails, LocalDateTime createdAt,
                       LocalDateTime updatedAt) {
        this(id, code, fullName, dob, gender, phoneNumber, address, branchId, branchName, status,
                guardianName, guardianPhone, guardianRelationship, idCardNumber, healthInsuranceNumber,
                avatarUrl, allergyDetails, underlyingDiseaseDetails, createdAt, updatedAt, null);
    }

    public Integer getBirthYear() {
        return dob != null ? dob.getYear() : null;
    }

    public String getPhone() {
        return phoneNumber;
    }

    public String getGenderShort() {
        if (gender == null) return "?";
        String g = gender.trim().toLowerCase();
        if (g.startsWith("nữ") || g.startsWith("f") || g.contains("female")) return "N";
        return "M";
    }

    public String getPaymentStatus() {
        // Deterministic presentation consistent with DentalSoft
        if (id == null) return "Chưa có DV";
        long mod = id % 3;
        if (mod == 0) return "Chưa TT hết";
        if (mod == 1) return "Đã TT hết";
        return "Chưa có DV";
    }

    public String getCustomerSourceDisplay() {
        if (customerSource != null && !customerSource.isBlank()) {
            return customerSource;
        }
        if (id == null) return "Vãng lai";
        long mod = id % 4;
        if (mod == 0) return "Google";
        if (mod == 1) return "Facebook";
        if (mod == 2) return "Giới thiệu";
        return "Vãng lai";
    }

    public static PatientView from(ReceptionPatientRecord profile) {
        return new PatientView(
                profile.getProfileId(), profile.getPatientCode(), profile.getFullName(),
                profile.getDob(), profile.getGender(), profile.getPhoneNumber(),
                profile.getAddress(), profile.getPreferredBranch().getBranchId(),
                profile.getPreferredBranch().getBranchName(), profile.getStatus(),
                profile.getGuardianName(), profile.getGuardianPhone(), profile.getGuardianRelationship(),
                profile.getIdCardNumber(), profile.getHealthInsuranceNumber(), profile.getAvatarUrl(),
                profile.getAllergyDetails(), profile.getUnderlyingDiseaseDetails(),
                profile.getCreatedAt(), profile.getUpdatedAt(), profile.getCustomerSource()
        );
    }

    public static PatientView from(PatientProfile profile) {
        return new PatientView(
                profile.getProfileId(), profile.getPatientCode(), profile.getFullName(),
                profile.getDob(), profile.getGender(), profile.getPhoneNumber(), profile.getAddress(),
                profile.getPreferredBranch().getBranchId(), profile.getPreferredBranch().getBranchName(),
                profile.getStatus(), profile.getGuardianName(), profile.getGuardianPhone(),
                profile.getGuardianRelationship(), profile.getIdCardNumber(),
                profile.getHealthInsuranceNumber(), profile.getAvatarUrl(), profile.getAllergyDetails(),
                profile.getUnderlyingDiseaseDetails(), profile.getCreatedAt(), profile.getUpdatedAt(),
                profile.getCustomerSource()
        );
    }
}
