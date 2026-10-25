package com.cvdcms.patient;

import com.cvdcms.entity.Visit;
import java.time.LocalDateTime;

public record ReceptionVisitView(Long id, String code, LocalDateTime checkInAt,
                                 String branchName, String doctorName, String status) {
    public static ReceptionVisitView from(Visit visit) {
        return new ReceptionVisitView(visit.getVisitId(), visit.getVisitCode(),
                visit.getCheckInAt(), visit.getBranch().getBranchName(),
                visit.getPrimaryDoctor() == null ? null : visit.getPrimaryDoctor().getFullName(),
                visit.getStatus().name());
    }
}
