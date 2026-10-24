package com.cvdcms.service.impl;

import com.cvdcms.DTO.InventoryItemDTO;
import com.cvdcms.entity.BranchInventory;
import com.cvdcms.repository.BranchInventoryRepository;
import com.cvdcms.service.BranchInventoryService;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Xử lý nghiệp vụ tìm kiếm tồn kho theo cơ sở.
 */
@Service
public class BranchInventoryServiceImpl
        implements BranchInventoryService {

    private final BranchInventoryRepository inventoryRepository;

    public BranchInventoryServiceImpl(
            BranchInventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    /**
     * Tìm kiếm tồn kho theo cơ sở, từ khóa và trạng thái tồn kho thấp.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<InventoryItemDTO> search(
            Long branchId,
            String keyword,
            Boolean lowStock,
            Pageable pageable) {

        // Kiểm tra ID cơ sở.
        if (branchId == null || branchId <= 0) {
            throw new IllegalArgumentException(
                    "Chi nhánh không hợp lệ.");
        }

        // Kiểm tra thông tin phân trang.
        if (pageable == null) {
            throw new IllegalArgumentException(
                    "Thông tin phân trang không hợp lệ.");
        }

        // Chuẩn hóa từ khóa tìm kiếm.
        String normalizedKeyword =
                keyword == null || keyword.isBlank()
                        ? null
                        : keyword.trim();

        // Truy vấn tồn kho theo cơ sở và các bộ lọc.
        Page<BranchInventory> inventoryPage =
                inventoryRepository.searchByBranch(
                        branchId,
                        normalizedKeyword,
                        lowStock,
                        pageable
                );

        // Chuyển Entity thành DTO để trả về API.
        return inventoryPage.map(item ->
                new InventoryItemDTO(
                        item.getInventoryId(),
                        item.getBranch().getBranchId(),
                        item.getBranch().getBranchName(),
                        item.getMaterial().getMaterialId(),
                        item.getMaterial().getMaterialCode(),
                        item.getMaterial().getMaterialName(),
                        item.getMaterial().getCategory(),
                        item.getMaterial().getUnit(),
                        item.getCurrentStock(),
                        item.getMaterial().getMinStockThreshold(),
                        item.getLastUpdated()
                )
        );
    }
}
