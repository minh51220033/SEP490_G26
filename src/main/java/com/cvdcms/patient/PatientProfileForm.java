package com.cvdcms.patient;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record PatientProfileForm(
        @NotBlank @Size(max = 100) String fullName,
        @NotNull @PastOrPresent LocalDate dob,
        @NotBlank @Pattern(regexp = "Nam|Nữ|Khác", message = "Giới tính không hợp lệ") String gender,
        @NotBlank @Pattern(regexp = "^0\\d{9,10}$", message = "Số điện thoại phải có 10 hoặc 11 chữ số và bắt đầu bằng 0") String phoneNumber,
        @Size(max = 255) String address,
        @NotNull Long branchId,
        @Size(max = 1000) String allergyDetails,
        @Size(max = 1000) String underlyingDiseaseDetails,
        @Size(max = 100) String guardianName,
        @Size(max = 20) String guardianPhone,
        boolean confirmDuplicate
) {}
