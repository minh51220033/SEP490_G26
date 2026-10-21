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
public class PrescriptionResponseDTO {
    private Long prescriptionId;
    private String prescriptionCode;
    private Long visitId;
    private String visitCode;
    private Long patientId;
    private String patientCode;
    private String patientName;
    private String gender;
    private String dob;
    private Integer age;
    private String phoneNumber;
    private String address;

    private Long doctorId;
    private String doctorName;
    private String doctorTitle;

    private String branchName;
    private String branchAddress;
    private String branchPhone;

    private String diagnosisText;
    private String adviceNotes;
    private String followupDate;
    private String createdAt;

    @Builder.Default
    private List<PrescriptionItemResponseDTO> items = new ArrayList<>();
}
