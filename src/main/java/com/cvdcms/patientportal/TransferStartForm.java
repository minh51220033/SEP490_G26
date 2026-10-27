package com.cvdcms.patientportal;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TransferStartForm(@NotBlank @Email @Size(max = 100) String targetEmail) {}
