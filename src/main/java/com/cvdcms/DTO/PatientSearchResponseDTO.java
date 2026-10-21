package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatientSearchResponseDTO {
    private Long profileId;
    private String patientCode;
    private String fullName;
    private String phoneNumber;
    private String gender;
    private String dob;
    private Integer age;
    private String address;
    private String avatarUrl;

    // Thông tin ca khám gần nhất hoặc ca khám đang mở
    private Long latestVisitId;
    private String latestVisitCode;
    private String latestVisitStatus; // IN_EXAM, WAITING, COMPLETED
    private String latestVisitStatusDisplay;
    private String latestVisitDoctor;
    private String latestVisitDate;
    private Boolean hasActiveVisit;
}
