package com.cvdcms.patientportal;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record PortalProfileForm(
        @NotBlank @Size(max = 100) String fullName,
        @NotNull @PastOrPresent LocalDate dob,
        @NotBlank @Pattern(regexp = "Nam|Nữ|Khác") String gender,
        @NotBlank @Pattern(regexp = "^0\\d{9,10}$") String phoneNumber,
        @NotBlank @Size(max = 255) String address,
        @NotNull Long branchId,
        @Size(max = 1000) String allergyDetails,
        @Size(max = 1000) String underlyingDiseaseDetails,
        @Size(max = 100) String guardianName,
        @Size(max = 20) String guardianPhone,
        @Size(max = 50) String guardianRelationship,
        @Size(max = 20) String idCardNumber,
        @Size(max = 20) String healthInsuranceNumber,
        boolean confirmDuplicate
) {}
