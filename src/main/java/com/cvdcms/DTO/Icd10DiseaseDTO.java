package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Icd10DiseaseDTO {
    private Long icd10Id;
    private String icd10Code;
    private String diseaseNameVi;
    private String diseaseNameEn;
    private Boolean isDentalCommon;
}
