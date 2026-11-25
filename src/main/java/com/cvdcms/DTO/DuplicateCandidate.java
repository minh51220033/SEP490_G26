package com.cvdcms.DTO;

import java.time.LocalDate;
import java.util.List;

public record DuplicateCandidate(Long id, String code, String fullName, LocalDate dob,
                                 String phoneNumber, String branchName, List<String> matchedFields) {
    public String getPatientToken() {
        return com.cvdcms.security.UrlIdCodec.shared().encodePatient(id);
    }
}
