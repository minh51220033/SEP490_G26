package com.cvdcms.patientportal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record TransferConfirmForm(
        @NotBlank @Pattern(regexp = "\\d{6}") String ownerCode,
        @NotBlank @Pattern(regexp = "\\d{6}") String targetCode,
        String username,
        String phoneNumber,
        String password
) {}
