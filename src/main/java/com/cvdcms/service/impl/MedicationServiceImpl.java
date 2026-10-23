package com.cvdcms.service.impl;

import com.cvdcms.DTO.MedicationDTO;
import com.cvdcms.entity.Medication;
import com.cvdcms.repository.MedicationRepository;
import com.cvdcms.repository.PrescriptionItemRepository;
import com.cvdcms.service.MedicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MedicationServiceImpl implements MedicationService {

    private final MedicationRepository medicationRepository;
    private final PrescriptionItemRepository prescriptionItemRepository;

    @Override
    @Transactional(readOnly = true)
    public List<MedicationDTO> getAllMedications() {
        return medicationRepository.findAllByOrderByDrugNameAsc().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public MedicationDTO getMedicationById(Long id) {
        Medication m = findOrThrow(id);
        return toDTO(m);
    }

    @Override
    @Transactional
    public MedicationDTO createMedication(MedicationDTO dto) {
        if (dto.getDrugCode() == null || dto.getDrugCode().isBlank()) {
            throw new IllegalArgumentException("Mã thuốc không được để trống.");
        }
        if (dto.getDrugName() == null || dto.getDrugName().isBlank()) {
            throw new IllegalArgumentException("Tên thuốc không được để trống.");
        }
        if (medicationRepository.existsByDrugCodeIgnoreCase(dto.getDrugCode().trim())) {
            throw new IllegalArgumentException("Mã thuốc '" + dto.getDrugCode().trim() + "' đã tồn tại.");
        }

        Medication entity = new Medication();
        mapFromDTO(dto, entity);
        entity.setIsActive(true);
        Medication saved = medicationRepository.save(entity);
        log.info("Đã tạo thuốc mới: {} - {}", saved.getDrugCode(), saved.getDrugName());
        return toDTO(saved);
    }

    @Override
    @Transactional
    public MedicationDTO updateMedication(Long id, MedicationDTO dto) {
        Medication entity = findOrThrow(id);

        if (dto.getDrugName() == null || dto.getDrugName().isBlank()) {
            throw new IllegalArgumentException("Tên thuốc không được để trống.");
        }
        // Kiểm tra trùng code (loại trừ chính nó)
        medicationRepository.findByDrugCodeIgnoreCase(dto.getDrugCode().trim())
                .filter(other -> !other.getMedicationId().equals(id))
                .ifPresent(other -> {
                    throw new IllegalArgumentException("Mã thuốc '" + dto.getDrugCode().trim() + "' đã tồn tại.");
                });

        mapFromDTO(dto, entity);
        Medication saved = medicationRepository.save(entity);
        log.info("Đã cập nhật thuốc ID={}: {} - {}", id, saved.getDrugCode(), saved.getDrugName());
        return toDTO(saved);
    }

    @Override
    @Transactional
    public void toggleMedicationStatus(Long id) {
        Medication entity = findOrThrow(id);
        entity.setIsActive(!entity.getIsActive());
        medicationRepository.save(entity);
        log.info("Đã đổi trạng thái thuốc ID={} → isActive={}", id, entity.getIsActive());
    }

    @Override
    @Transactional
    public void deleteMedication(Long id) {
        Medication entity = findOrThrow(id);
        long usedCount = prescriptionItemRepository.countByMedication_MedicationId(id);
        if (usedCount > 0) {
            throw new IllegalArgumentException(
                    "Không thể xóa: thuốc '" + entity.getDrugName() + "' đang được dùng trong " + usedCount + " đơn thuốc. Hãy chuyển sang Ngừng sử dụng thay thế.");
        }
        medicationRepository.deleteById(id);
        log.info("Đã xóa thuốc ID={}", id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MedicationDTO> searchMedications(String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        return medicationRepository.searchByKeyword(kw).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private Medication findOrThrow(Long id) {
        return medicationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thuốc ID: " + id));
    }

    private void mapFromDTO(MedicationDTO dto, Medication entity) {
        entity.setDrugCode(dto.getDrugCode() == null ? "" : dto.getDrugCode().trim().toUpperCase());
        entity.setDrugName(dto.getDrugName().trim());
        entity.setActiveIngredient(dto.getActiveIngredient() != null ? dto.getActiveIngredient().trim() : null);
        entity.setUnit(dto.getUnit() != null && !dto.getUnit().isBlank() ? dto.getUnit().trim() : "Viên");
        entity.setUsageInstruction(dto.getUsageInstruction() != null ? dto.getUsageInstruction().trim() : null);
    }

    private MedicationDTO toDTO(Medication e) {
        return MedicationDTO.builder()
                .medicationId(e.getMedicationId())
                .drugCode(e.getDrugCode())
                .drugName(e.getDrugName())
                .activeIngredient(e.getActiveIngredient())
                .unit(e.getUnit())
                .usageInstruction(e.getUsageInstruction())
                .isActive(e.getIsActive())
                .build();
    }
}
