package com.cvdcms.DTO;

import com.cvdcms.entity.enums.DiagnosisType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosisRequestDTO {

    @NotNull(message = "Mã ca khám (visitId) không được để trống")
    private Long visitId;

    private Long examId;

    @NotBlank(message = "Vui lòng chọn mã bệnh ICD-10")
    private String icd10Code;

    private String diagnosisText;

    @NotNull(message = "Vui lòng chọn phân loại chẩn đoán")
    private DiagnosisType diagnosisType;

    private String toothNumbers;

    private String notes;
}
