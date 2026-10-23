package com.cvdcms.patient;

import java.time.LocalDate;
import java.util.List;

public record DuplicateCandidate(Long id, String code, String fullName, LocalDate dob,
                                 String phoneNumber, String branchName, List<String> matchedFields) {}
