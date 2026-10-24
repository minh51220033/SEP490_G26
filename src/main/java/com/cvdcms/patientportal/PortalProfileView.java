package com.cvdcms.patientportal;

import com.cvdcms.entity.PatientProfile;
import java.time.LocalDate;

public record PortalProfileView(Long id, String code, String fullName, LocalDate dob,
                                String gender, String phoneNumber, String address,
                                Long branchId, String branchName, String allergyDetails,
                                String underlyingDiseaseDetails, String guardianName,
                                String guardianPhone, boolean self, String status) {
    static PortalProfileView from(PatientProfile p) {
        return new PortalProfileView(p.getProfileId(), p.getPatientCode(), p.getFullName(),
                p.getDob(), p.getGender(), p.getPhoneNumber(), p.getAddress(),
                p.getPreferredBranch().getBranchId(), p.getPreferredBranch().getBranchName(),
                p.getAllergyDetails(), p.getUnderlyingDiseaseDetails(), p.getGuardianName(),
                p.getGuardianPhone(), Boolean.TRUE.equals(p.getIsDefault()), p.getStatus());
    }
}
