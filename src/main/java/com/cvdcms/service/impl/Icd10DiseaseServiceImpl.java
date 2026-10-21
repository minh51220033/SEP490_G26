package com.cvdcms.service.impl;

import com.cvdcms.DTO.Icd10DiseaseDTO;
import com.cvdcms.entity.Icd10Disease;
import com.cvdcms.repository.Icd10DiseaseRepository;
import com.cvdcms.service.Icd10DiseaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class Icd10DiseaseServiceImpl implements Icd10DiseaseService {

    private final Icd10DiseaseRepository icd10DiseaseRepository;

    @Override
    public List<Icd10DiseaseDTO> searchDiseases(String keyword, int limit) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return getCommonDentalDiseases();
        }
        int maxResults = (limit <= 0 || limit > 50) ? 20 : limit;
        return icd10DiseaseRepository.searchDiseases(keyword.trim(), PageRequest.of(0, maxResults))
                .stream()
                .map(this::mapToDTO)
                .toList();
    }

    @Override
    public List<Icd10DiseaseDTO> getCommonDentalDiseases() {
        return icd10DiseaseRepository.findByIsDentalCommonTrueAndIsActiveTrueOrderByIcd10CodeAsc()
                .stream()
                .map(this::mapToDTO)
                .toList();
    }

    @Override
    public Icd10Disease getByCode(String icd10Code) {
        if (icd10Code == null || icd10Code.trim().isEmpty()) {
            return null;
        }
        return icd10DiseaseRepository.findByIcd10Code(icd10Code.trim())
                .orElse(null);
    }

    private Icd10DiseaseDTO mapToDTO(Icd10Disease disease) {
        return Icd10DiseaseDTO.builder()
                .icd10Id(disease.getIcd10Id())
                .icd10Code(disease.getIcd10Code())
                .diseaseNameVi(disease.getDiseaseNameVi())
                .diseaseNameEn(disease.getDiseaseNameEn())
                .isDentalCommon(disease.getIsDentalCommon())
                .build();
    }
}
