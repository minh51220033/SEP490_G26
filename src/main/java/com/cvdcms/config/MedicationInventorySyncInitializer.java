package com.cvdcms.config;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.BranchInventory;
import com.cvdcms.entity.Material;
import com.cvdcms.entity.Medication;
import com.cvdcms.repository.BranchInventoryRepository;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.MaterialRepository;
import com.cvdcms.repository.MedicationRepository;
import com.cvdcms.service.impl.MedicationServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Tự động đồng bộ các thuốc hiện có trong danh mục thuốc (medications) sang kho vật tư
 * (materials + branch_inventory với tồn kho khởi tạo bằng 0 nếu chưa có bản ghi tồn kho).
 * Không tạo tồn kho giả, không thay đổi tồn kho hiện có.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MedicationInventorySyncInitializer implements ApplicationRunner {

    private final MedicationRepository medicationRepository;
    private final MaterialRepository materialRepository;
    private final BranchRepository branchRepository;
    private final BranchInventoryRepository branchInventoryRepository;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments args) {
        try {
            int synced = syncAllMedicationsToInventory();
            log.info("MedicationInventorySyncInitializer hoàn tất (đã cập nhật {} liên kết).", synced);
        } catch (Exception e) {
            log.warn("Không thể chạy đồng bộ thuốc-kho lúc khởi động: {}", e.getMessage());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public int syncAllMedicationsToInventory() {
        List<Medication> medications = medicationRepository.findAllByOrderByDrugNameAsc();
        List<Branch> activeBranches = branchRepository.findByIsActiveTrueOrderByBranchNameAsc();
        int syncedCount = 0;

        for (Medication med : medications) {
            Material material = materialRepository.findByMedication_MedicationId(med.getMedicationId())
                    .orElse(med.getMaterial());

            if (material == null) {
                Optional<Material> byCode = materialRepository.findByMaterialCodeIgnoreCase(med.getDrugCode());
                if (byCode.isPresent() && byCode.get().getMedication() == null) {
                    material = byCode.get();
                    material.setMedication(med);
                    if (material.getCategory() == null || material.getCategory().isBlank()) {
                        material.setCategory(MedicationServiceImpl.MEDICATION_MATERIAL_CATEGORY);
                    }
                    material = materialRepository.saveAndFlush(material);
                    syncedCount++;
                } else if (byCode.isEmpty()) {
                    material = new Material();
                    material.setMedication(med);
                    material.setMaterialCode(med.getDrugCode());
                    material.setMaterialName(med.getDrugName());
                    material.setUnit(med.getUnit() != null ? med.getUnit() : "Viên");
                    material.setIsActive(Boolean.TRUE.equals(med.getIsActive()));
                    material.setCategory(MedicationServiceImpl.MEDICATION_MATERIAL_CATEGORY);
                    material.setMinStockThreshold(new BigDecimal("10.000"));
                    material = materialRepository.saveAndFlush(material);
                    syncedCount++;
                } else {
                    continue;
                }
            } else if (material.getMedication() == null) {
                material.setMedication(med);
                material = materialRepository.saveAndFlush(material);
                syncedCount++;
            }

            if (med.getMaterial() == null || !material.getMaterialId().equals(med.getMaterial().getMaterialId())) {
                med.setMaterial(material);
                medicationRepository.saveAndFlush(med);
                syncedCount++;
            }

            for (Branch branch : activeBranches) {
                if (branchInventoryRepository.findByBranch_BranchIdAndMaterial_MaterialId(
                        branch.getBranchId(), material.getMaterialId()).isEmpty()) {
                    BranchInventory inv = new BranchInventory();
                    inv.setBranch(branch);
                    inv.setMaterial(material);
                    inv.setCurrentStock(BigDecimal.ZERO);
                    branchInventoryRepository.save(inv);
                }
            }
        }
        return syncedCount;
    }
}
