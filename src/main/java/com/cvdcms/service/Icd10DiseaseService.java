package com.cvdcms.service;

import com.cvdcms.DTO.Icd10DiseaseDTO;
import com.cvdcms.entity.Icd10Disease;

import java.util.List;

public interface Icd10DiseaseService {

    List<Icd10DiseaseDTO> searchDiseases(String keyword, int limit);

    List<Icd10DiseaseDTO> getCommonDentalDiseases();

    Icd10Disease getByCode(String icd10Code);
}
