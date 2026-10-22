package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyVisitDTO {
    private Long visitId;
    private String visitCode;
    private Long profileId;
    private String patientCode;
    private String fullName;
    private String phoneNumber;
    private String gender;
    private String age;
    private String checkInTime;       // Ví dụ: "08:30"
    private String checkInDate;       // Ví dụ: "22/10/2026"
    private String checkInIso;        // Ví dụ: "2026-10-22"
    private String status;            // "WAITING", "IN_EXAM", ...
    private String statusDisplayName; // "Đang chờ khám", "Đang khám"...
    private String statusBadgeClass;  // "bg-warning", "bg-primary"...
    private String initialSymptoms;
    private String doctorName;
    private String branchName;
    private String chairName;
    private boolean isCurrentVisit;
}
