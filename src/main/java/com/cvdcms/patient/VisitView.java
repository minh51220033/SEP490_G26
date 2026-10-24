package com.cvdcms.patient;

import com.cvdcms.entity.Visit;

import java.time.LocalDateTime;

public record VisitView(Long id, String code, LocalDateTime checkInAt,
                        String branchName, String doctorName, String status,
                        String initialSymptoms, String notes) {
    public static VisitView from(Visit visit) {
        return new VisitView(visit.getVisitId(), visit.getVisitCode(), visit.getCheckInAt(),
                visit.getBranch().getBranchName(),
                visit.getPrimaryDoctor() == null ? null : visit.getPrimaryDoctor().getFullName(),
                visit.getStatus().name(), visit.getInitialSymptoms(), visit.getNotes());
    }
}
