package com.cvdcms.service;

import com.cvdcms.DTO.MedicationDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface MedicationService {

    /** Lấy toàn bộ danh mục thuốc (bao gồm cả ngừng hoạt động) */
    List<MedicationDTO> getAllMedications();

    /** Lấy thuốc theo ID */
    MedicationDTO getMedicationById(Long id);

    /** Tạo mới thuốc */
    MedicationDTO createMedication(MedicationDTO dto);

    /** Cập nhật thông tin thuốc */
    MedicationDTO updateMedication(Long id, MedicationDTO dto);

    /** Bật / Tắt trạng thái hoạt động */
    void toggleMedicationStatus(Long id);

    /** Xóa thuốc (chỉ khi chưa dùng trong đơn nào) */
    void deleteMedication(Long id);

    /** Tìm kiếm theo tên hoặc mã */
    List<MedicationDTO> searchMedications(String keyword);

    /** Tìm kiếm, lọc trạng thái và phân trang server-side cho danh mục thuốc */
    Page<MedicationDTO> searchMedicationsPage(String keyword, Boolean active, Pageable pageable);
}
