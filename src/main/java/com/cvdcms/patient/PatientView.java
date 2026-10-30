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
        LocalDateTime updatedAt
) {
    public static PatientView from(ReceptionPatientRecord profile) {
        return new PatientView(
                profile.getProfileId(), profile.getPatientCode(), profile.getFullName(),
                profile.getDob(), profile.getGender(), profile.getPhoneNumber(),
                profile.getAddress(), profile.getPreferredBranch().getBranchId(),
                profile.getPreferredBranch().getBranchName(), profile.getStatus(),
                profile.getGuardianName(), profile.getGuardianPhone(), profile.getGuardianRelationship(),
                profile.getIdCardNumber(), profile.getHealthInsuranceNumber(), profile.getAvatarUrl(),
                profile.getAllergyDetails(), profile.getUnderlyingDiseaseDetails(),
                profile.getCreatedAt(), profile.getUpdatedAt()
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
                profile.getUnderlyingDiseaseDetails(), profile.getCreatedAt(), profile.getUpdatedAt());
    }
}
