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
 * Triển khai nghiệp vụ tìm kiếm và tổng hợp tồn kho vật tư theo cơ sở ({@link BranchInventoryService}).
 * <p>Được gọi từ {@link com.cvdcms.controller.BranchInventoryController#getInventory}.</p>
 */
@Service
public class BranchInventoryServiceImpl
        implements BranchInventoryService {

    private final BranchInventoryRepository inventoryRepository;

    /**
     * Khởi tạo service với repository tồn kho cơ sở.
     *
     * @param inventoryRepository repository truy vấn bảng {@code branch_inventory}
     */
    public BranchInventoryServiceImpl(
            BranchInventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    /**
     * Tìm kiếm tồn kho phân trang theo cơ sở, từ khóa và trạng thái tồn kho thấp.
     * <p>Xử lý: kiểm tra hợp lệ {@code branchId} và {@code pageable} → chuẩn hóa từ khóa tìm kiếm
     * → truy vấn {@link BranchInventoryRepository#searchByBranch} → ánh xạ sang {@link InventoryItemDTO}.</p>
     *
     * @param branchId mã cơ sở (&gt; 0)
     * @param keyword  từ khóa tìm kiếm mã/tên/nhóm vật tư (tùy chọn)
     * @param lowStock {@code true} để chỉ lọc vật tư chạm ngưỡng tối thiểu (tùy chọn)
     * @param pageable cấu hình phân trang và sắp xếp
     * @return trang {@link InventoryItemDTO} phục vụ bảng tồn kho
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
