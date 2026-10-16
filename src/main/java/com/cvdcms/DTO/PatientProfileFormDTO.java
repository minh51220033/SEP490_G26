
package com.cvdcms.DTO;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.multipart.MultipartFile;

public class PatientProfileFormDTO {

    private String fullName;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dob;

    private String gender;
    private String address;
    private Long branchId;

    private Boolean hasUnderlyingDisease = false;
    private String underlyingDiseaseDetails;

    private MultipartFile avatar;

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public LocalDate getDob() { return dob; }
    public void setDob(LocalDate dob) { this.dob = dob; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public Long getBranchId() { return branchId; }
    public void setBranchId(Long branchId) { this.branchId = branchId; }

    public Boolean getHasUnderlyingDisease() {
        return hasUnderlyingDisease;
    }

    public void setHasUnderlyingDisease(Boolean hasUnderlyingDisease) {
        this.hasUnderlyingDisease = hasUnderlyingDisease;
    }

    public String getUnderlyingDiseaseDetails() {
        return underlyingDiseaseDetails;
    }

    public void setUnderlyingDiseaseDetails(String details) {
        this.underlyingDiseaseDetails = details;
    }

    public MultipartFile getAvatar() { return avatar; }
    public void setAvatar(MultipartFile avatar) { this.avatar = avatar; }
}
