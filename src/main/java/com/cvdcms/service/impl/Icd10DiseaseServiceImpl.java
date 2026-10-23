package com.cvdcms.service.impl;

import com.cvdcms.DTO.Icd10DiseaseDTO;
import com.cvdcms.entity.Icd10Disease;
import com.cvdcms.repository.ExaminationDiagnosisRepository;
import com.cvdcms.repository.Icd10DiseaseRepository;
import com.cvdcms.service.Icd10DiseaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class Icd10DiseaseServiceImpl implements Icd10DiseaseService {

    private final Icd10DiseaseRepository icd10DiseaseRepository;
    private final ExaminationDiagnosisRepository examinationDiagnosisRepository;

    // ── DOCTOR / API ─────────────────────────────────────────────────────────

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

    // ── ADMIN CRUD (SCR-02.4) ────────────────────────────────────────────────

    @Override
    public List<Icd10DiseaseDTO> getAllDiseases() {
        return icd10DiseaseRepository.findAllByOrderByIcd10CodeAsc()
                .stream()
                .map(this::mapToDTO)
                .toList();
    }

    @Override
    public Icd10DiseaseDTO getDiseaseById(Long id) {
        Icd10Disease disease = icd10DiseaseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy mã ICD-10 với ID: " + id));
        return mapToDTO(disease);
    }

    @Override
    @Transactional
    public Icd10DiseaseDTO createDisease(Icd10DiseaseDTO dto) {
        validateDTO(dto);

        String code = dto.getIcd10Code().trim().toUpperCase();
        if (icd10DiseaseRepository.existsByIcd10Code(code)) {
            throw new IllegalArgumentException("Mã ICD-10 '" + code + "' đã tồn tại trong hệ thống.");
        }

        Icd10Disease disease = new Icd10Disease();
        disease.setIcd10Code(code);
        disease.setDiseaseNameVi(dto.getDiseaseNameVi().trim());
        disease.setDiseaseNameEn(dto.getDiseaseNameEn() != null ? dto.getDiseaseNameEn().trim() : null);
        disease.setIsDentalCommon(Boolean.TRUE.equals(dto.getIsDentalCommon()));
        disease.setIsActive(dto.getIsActive() != null ? dto.getIsActive() : true);

        return mapToDTO(icd10DiseaseRepository.save(disease));
    }

    @Override
    @Transactional
    public Icd10DiseaseDTO updateDisease(Long id, Icd10DiseaseDTO dto) {
        validateDTO(dto);

        Icd10Disease disease = icd10DiseaseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy mã ICD-10 với ID: " + id));

        String code = dto.getIcd10Code().trim().toUpperCase();
        if (icd10DiseaseRepository.existsByIcd10CodeAndIcd10IdNot(code, id)) {
            throw new IllegalArgumentException("Mã ICD-10 '" + code + "' đã được sử dụng bởi bản ghi khác.");
        }

        disease.setIcd10Code(code);
        disease.setDiseaseNameVi(dto.getDiseaseNameVi().trim());
        disease.setDiseaseNameEn(dto.getDiseaseNameEn() != null ? dto.getDiseaseNameEn().trim() : null);
        disease.setIsDentalCommon(Boolean.TRUE.equals(dto.getIsDentalCommon()));
        if (dto.getIsActive() != null) {
            disease.setIsActive(dto.getIsActive());
        }

        return mapToDTO(icd10DiseaseRepository.save(disease));
    }

    @Override
    @Transactional
    public void toggleDiseaseStatus(Long id) {
        Icd10Disease disease = icd10DiseaseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy mã ICD-10 với ID: " + id));
        disease.setIsActive(!Boolean.TRUE.equals(disease.getIsActive()));
        icd10DiseaseRepository.save(disease);
    }

    @Override
    @Transactional
    public void deleteDisease(Long id) {
        Icd10Disease disease = icd10DiseaseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy mã ICD-10 với ID: " + id));

        if (examinationDiagnosisRepository.existsByIcd10Disease_Icd10Id(id)) {
            throw new IllegalArgumentException("Không thể xóa mã ICD-10 '" + disease.getIcd10Code()
                    + "' vì đã được ghi nhận trong hồ sơ khám/chẩn đoán. Bạn có thể chuyển sang trạng thái 'Ngừng dùng'.");
        }

        icd10DiseaseRepository.delete(disease);
    }

    @Override
    public List<Icd10DiseaseDTO> adminSearch(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return getAllDiseases();
        }
        return icd10DiseaseRepository.adminSearch(keyword.trim())
                .stream()
                .map(this::mapToDTO)
                .toList();
    }

    // ── HELPERS ──────────────────────────────────────────────────────────────

    private void validateDTO(Icd10DiseaseDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("Dữ liệu không được để trống.");
        }
        if (dto.getIcd10Code() == null || dto.getIcd10Code().trim().isEmpty()) {
            throw new IllegalArgumentException("Mã ICD-10 không được để trống.");
        }
        if (dto.getDiseaseNameVi() == null || dto.getDiseaseNameVi().trim().isEmpty()) {
            throw new IllegalArgumentException("Tên bệnh (tiếng Việt) không được để trống.");
        }
    }

    private Icd10DiseaseDTO mapToDTO(Icd10Disease disease) {
        return Icd10DiseaseDTO.builder()
                .icd10Id(disease.getIcd10Id())
                .icd10Code(disease.getIcd10Code())
                .diseaseNameVi(disease.getDiseaseNameVi())
                .diseaseNameEn(disease.getDiseaseNameEn())
                .isDentalCommon(disease.getIsDentalCommon())
                .isActive(disease.getIsActive())
                .build();
    }
}
