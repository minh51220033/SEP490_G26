package com.cvdcms.patient;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record PatientView(
        Long id, String code, String fullName, LocalDate dob, String gender,
        String phoneNumber, String address, Long branchId, String branchName,
        String status, String allergyDetails, String underlyingDiseaseDetails,
        String guardianName, String guardianPhone, LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static PatientView from(ReceptionPatientRecord profile) {
        return new PatientView(
                profile.getProfileId(), profile.getPatientCode(), profile.getFullName(),
                profile.getDob(), profile.getGender(), profile.getPhoneNumber(),
                profile.getAddress(), profile.getPreferredBranch().getBranchId(),
                profile.getPreferredBranch().getBranchName(), profile.getStatus(),
                profile.getAllergyDetails(), profile.getUnderlyingDiseaseDetails(),
                profile.getGuardianName(), profile.getGuardianPhone(),
                profile.getCreatedAt(), profile.getUpdatedAt()
        );
    }
}
