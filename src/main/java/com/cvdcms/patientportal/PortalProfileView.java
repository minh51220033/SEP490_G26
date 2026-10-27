package com.cvdcms.patientportal;

import com.cvdcms.entity.PatientProfile;
import java.time.LocalDate;

public record PortalProfileView(Long id, String code, String avatarUrl, String fullName, LocalDate dob,
                                String gender, String phoneNumber, String address,
                                Long branchId, String branchName, String allergyDetails,
                                String underlyingDiseaseDetails, String guardianName,
                                String guardianPhone, String guardianRelationship,
                                String idCardNumber, String healthInsuranceNumber,
                                boolean self, boolean defaultProfile, String status) {
    static PortalProfileView from(PatientProfile p, boolean self) {
        return new PortalProfileView(p.getProfileId(), p.getPatientCode(), p.getAvatarUrl(), p.getFullName(),
                p.getDob(), p.getGender(), p.getPhoneNumber(), p.getAddress(),
                p.getPreferredBranch().getBranchId(), p.getPreferredBranch().getBranchName(),
                p.getAllergyDetails(), p.getUnderlyingDiseaseDetails(), p.getGuardianName(),
                p.getGuardianPhone(), p.getGuardianRelationship(), p.getIdCardNumber(),
                p.getHealthInsuranceNumber(), self, Boolean.TRUE.equals(p.getIsDefault()), p.getStatus());
    }
}
