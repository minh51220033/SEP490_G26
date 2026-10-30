package com.cvdcms.service.impl;

import com.cvdcms.DTO.MedicationDTO;
import com.cvdcms.entity.Material;
import com.cvdcms.entity.Medication;
import com.cvdcms.repository.MaterialRepository;
import com.cvdcms.repository.MedicationRepository;
import com.cvdcms.repository.PrescriptionItemRepository;
import com.cvdcms.service.MedicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Quản lý danh mục thuốc và đồng bộ một chiều sang danh mục kho vật tư (bảng materials).
 *
 * Quy tắc đồng bộ:
 * - Tạo thuốc  → tạo đúng 1 mặt hàng kho (material_code = drug_code, category = "Thuốc",
 *                liên kết qua materials.medication_id). KHÔNG tạo tồn kho / lô hàng / phiếu nhập.
 * - Sửa thuốc  → cập nhật mã, tên, đơn vị của mặt hàng kho đã liên kết.
 * - Bật/tắt    → trạng thái mặt hàng kho đi theo trạng thái thuốc (lịch sử nhập kho giữ nguyên).
 * - Xóa thuốc  → chỉ xóa khi thuốc chưa kê đơn VÀ mặt hàng kho chưa phát sinh giao dịch kho.
 * Mọi thao tác chạy trong một transaction: lỗi ở bước kho sẽ rollback cả bản ghi thuốc.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MedicationServiceImpl implements MedicationService {

    /** Nhóm vật tư dùng cho các mặt hàng kho được đồng bộ từ danh mục thuốc. */
    public static final String MEDICATION_MATERIAL_CATEGORY = "Thuốc";

    /** Ngưỡng tồn tối thiểu mặc định — trùng với mặc định của entity Material / MaterialService. */
    private static final BigDecimal DEFAULT_MIN_STOCK = new BigDecimal("10.000");

    private final MedicationRepository medicationRepository;
    private final PrescriptionItemRepository prescriptionItemRepository;
    private final MaterialRepository materialRepository;

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
    @Transactional(rollbackFor = Exception.class)
    public MedicationDTO createMedication(MedicationDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("Dữ liệu thuốc không được để trống.");
        }
        if (dto.getDrugCode() == null || dto.getDrugCode().isBlank()) {
            throw new IllegalArgumentException("Mã thuốc không được để trống.");
        }
        if (dto.getDrugName() == null || dto.getDrugName().isBlank()) {
            throw new IllegalArgumentException("Tên thuốc không được để trống.");
        }
        String code = normalizeCode(dto.getDrugCode());
        if (medicationRepository.existsByDrugCodeIgnoreCase(code)) {
            throw new IllegalArgumentException("Mã thuốc '" + code + "' đã tồn tại.");
        }
        // Mã thuốc trùng mã vật tư của một mặt hàng khác → không ghi đè, không liên kết nhầm.
        materialRepository.findByMaterialCodeIgnoreCase(code).ifPresent(existing -> {
            throw new IllegalArgumentException(materialConflictMessage(code, existing));
        });

        Medication entity = new Medication();
        mapFromDTO(dto, entity);
        entity.setIsActive(true);
        // saveAndFlush: phát hiện sớm vi phạm UNIQUE (gửi request lặp) ngay trong transaction.
        Medication saved = medicationRepository.saveAndFlush(entity);

        Material material = new Material();
        material.setMedication(saved);
        applyMedicationToMaterial(saved, material);
        material.setCategory(MEDICATION_MATERIAL_CATEGORY);
        material.setMinStockThreshold(DEFAULT_MIN_STOCK);
        materialRepository.saveAndFlush(material);

        log.info("Đã tạo thuốc mới {} - {} và đồng bộ mặt hàng kho (material_id={})",
                saved.getDrugCode(), saved.getDrugName(), material.getMaterialId());
        return toDTO(saved);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MedicationDTO updateMedication(Long id, MedicationDTO dto) {
        Medication entity = findOrThrow(id);

        if (dto == null || dto.getDrugName() == null || dto.getDrugName().isBlank()) {
            throw new IllegalArgumentException("Tên thuốc không được để trống.");
        }
        if (dto.getDrugCode() == null || dto.getDrugCode().isBlank()) {
            throw new IllegalArgumentException("Mã thuốc không được để trống.");
        }
        String code = normalizeCode(dto.getDrugCode());
        // Kiểm tra trùng code (loại trừ chính nó)
        medicationRepository.findByDrugCodeIgnoreCase(code)
                .filter(other -> !other.getMedicationId().equals(id))
                .ifPresent(other -> {
                    throw new IllegalArgumentException("Mã thuốc '" + code + "' đã tồn tại.");
                });

        Optional<Material> linked = materialRepository.findByMedication_MedicationId(id);
        // Nếu đổi mã, mã mới không được trùng mã của mặt hàng kho khác.
        materialRepository.findByMaterialCodeIgnoreCase(code)
                .filter(m -> linked.isEmpty() || !m.getMaterialId().equals(linked.get().getMaterialId()))
                .ifPresent(m -> {
                    throw new IllegalArgumentException(materialConflictMessage(code, m));
                });

        mapFromDTO(dto, entity);
        Medication saved = medicationRepository.save(entity);

        linked.ifPresent(material -> {
            applyMedicationToMaterial(saved, material);
            materialRepository.save(material);
        });
        log.info("Đã cập nhật thuốc ID={}: {} - {} (đồng bộ kho: {})",
                id, saved.getDrugCode(), saved.getDrugName(), linked.isPresent());
        return toDTO(saved);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void toggleMedicationStatus(Long id) {
        Medication entity = findOrThrow(id);
        entity.setIsActive(!Boolean.TRUE.equals(entity.getIsActive()));
        medicationRepository.save(entity);

        // Thuốc ngừng sử dụng → ẩn khỏi danh sách chọn khi nhập kho (listActive),
        // nhưng KHÔNG xóa lịch sử phiếu nhập, lô hàng hay tồn kho.
        materialRepository.findByMedication_MedicationId(id).ifPresent(material -> {
            material.setIsActive(entity.getIsActive());
            materialRepository.save(material);
        });
        log.info("Đã đổi trạng thái thuốc ID={} → isActive={}", id, entity.getIsActive());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteMedication(Long id) {
        Medication entity = findOrThrow(id);
        long usedCount = prescriptionItemRepository.countByMedication_MedicationId(id);
        if (usedCount > 0) {
            throw new IllegalArgumentException(
                    "Không thể xóa: thuốc '" + entity.getDrugName() + "' đang được dùng trong " + usedCount + " đơn thuốc. Hãy chuyển sang Ngừng sử dụng thay thế.");
        }

        Optional<Material> linked = materialRepository.findByMedication_MedicationId(id);
        if (linked.isPresent()) {
            Material material = linked.get();
            Long materialId = material.getMaterialId();
            long stockRefs = materialRepository.countReceiptItems(materialId)
                    + materialRepository.countIssueItems(materialId)
                    + materialRepository.countInventoryRows(materialId)
                    + materialRepository.countBatches(materialId)
                    + materialRepository.countConsumptions(materialId);
            if (stockRefs > 0) {
                throw new IllegalArgumentException(
                        "Không thể xóa: thuốc '" + entity.getDrugName() + "' đã phát sinh giao dịch trong kho vật tư. Hãy chuyển sang Ngừng sử dụng thay thế.");
            }
            // Mặt hàng kho chưa từng giao dịch → xóa cùng thuốc để không để lại bản ghi mồ côi.
            materialRepository.delete(material);
            materialRepository.flush();
        }

        medicationRepository.deleteById(id);
        log.info("Đã xóa thuốc ID={} (kèm mặt hàng kho liên kết: {})", id, linked.isPresent());
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

    private String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    /** Ánh xạ mã/tên/đơn vị/trạng thái thuốc sang mặt hàng kho (không đụng tới tồn kho). */
    private void applyMedicationToMaterial(Medication med, Material material) {
        material.setMaterialCode(med.getDrugCode());
        material.setMaterialName(med.getDrugName());
        material.setUnit(med.getUnit());
        material.setIsActive(Boolean.TRUE.equals(med.getIsActive()));
    }

    private String materialConflictMessage(String code, Material existing) {
        return "Mã thuốc '" + code + "' đang trùng với mã của mặt hàng kho '"
                + existing.getMaterialName() + "' (nhóm: " + existing.getCategory()
                + "). Hệ thống không tự động liên kết. Vui lòng dùng mã thuốc khác hoặc đối soát với Quản lý kho.";
    }

    private void mapFromDTO(MedicationDTO dto, Medication entity) {
        entity.setDrugCode(dto.getDrugCode() == null ? "" : normalizeCode(dto.getDrugCode()));
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
