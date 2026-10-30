package com.cvdcms.DTO;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GeneralExaminationRequestDTO {
    private Long visitId;

    // Lý do khám & Triệu chứng ban đầu
    private String initialSymptoms;

    // Tiền sử y khoa & Cảnh báo dị ứng
    private Boolean hasAllergies;
    private String allergyDetails;
    private Boolean hasCardiovascularDisease;
    private Boolean hasDiabetes;
    private Boolean hasBleedingDisorder;
    private Boolean isPregnant;
    private String underlyingDiseaseDetails;

    // Khám mô mềm & Khớp thái dương hàm (TMJ)
    private String tmjStatus;      // NORMAL, CLICKING, LIMITED, PAIN
    private String hygieneStatus;  // GOOD, MEDIUM, POOR
    private String gingivaStatus;  // HEALTHY, GINGIVITIS, PERIODONTITIS
}
