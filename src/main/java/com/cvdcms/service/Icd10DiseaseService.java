package com.cvdcms.service;

import com.cvdcms.DTO.Icd10DiseaseDTO;
import com.cvdcms.entity.Icd10Disease;

import java.util.List;

public interface Icd10DiseaseService {

    // ── Doctor / API (đã có) ────────────────────────────────
    List<Icd10DiseaseDTO> searchDiseases(String keyword, int limit);

    List<Icd10DiseaseDTO> getCommonDentalDiseases();

    Icd10Disease getByCode(String icd10Code);

    // ── Admin CRUD (SCR-02.4) ───────────────────────────────
    /** Lấy toàn bộ danh mục ICD-10 (kể cả ngừng sử dụng) */
    List<Icd10DiseaseDTO> getAllDiseases();

    /** Lấy 1 bản ghi theo ID */
    Icd10DiseaseDTO getDiseaseById(Long id);

    /** Thêm mới mã bệnh */
    Icd10DiseaseDTO createDisease(Icd10DiseaseDTO dto);

    /** Cập nhật mã bệnh */
    Icd10DiseaseDTO updateDisease(Long id, Icd10DiseaseDTO dto);

    /** Bật / Tắt trạng thái */
    void toggleDiseaseStatus(Long id);

    /** Xóa mã bệnh (chỉ khi chưa dùng trong chẩn đoán) */
    void deleteDisease(Long id);

    /** Tìm kiếm admin (bao gồm cả bản ghi ngừng sử dụng) */
    List<Icd10DiseaseDTO> adminSearch(String keyword);
}
