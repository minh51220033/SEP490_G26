package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrescriptionRequestDTO {
    private Long visitId;
    private Long doctorId;
    private String prescriptionTitle;
    private String diagnosisText;
    private String adviceNotes;
    private Integer followupDays;
    private String followupDate;
    private Double patientWeight;
    private String guardianInfo;

    @Builder.Default
    private List<PrescriptionItemRequestDTO> items = new ArrayList<>();
}
