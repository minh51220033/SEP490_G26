package com.cvdcms.patient;

import com.cvdcms.entity.Visit;
import java.time.LocalDateTime;

public record ReceptionVisitView(Long id, String code, LocalDateTime checkInAt,
                                 String branchName, String doctorName, String status,
                                 String initialSymptoms, String notes) {
    public ReceptionVisitView(Long id, String code, LocalDateTime checkInAt,
                              String branchName, String doctorName, String status) {
        this(id, code, checkInAt, branchName, doctorName, status, null, null);
    }

    public static ReceptionVisitView from(Visit visit) {
        return new ReceptionVisitView(visit.getVisitId(), visit.getVisitCode(),
                visit.getCheckInAt(), visit.getBranch() != null ? visit.getBranch().getBranchName() : null,
                visit.getPrimaryDoctor() == null ? null : visit.getPrimaryDoctor().getFullName(),
                visit.getStatus() != null ? visit.getStatus().name() : null,
                visit.getInitialSymptoms(), visit.getNotes());
    }
}
