package com.cvdcms.service;

import com.cvdcms.DTO.InventoryItemDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Giao diện Service xử lý nghiệp vụ tra cứu tồn kho theo cơ sở.
 * <p>Được gọi từ {@link com.cvdcms.controller.BranchInventoryController} và triển khai bởi
 * {@link com.cvdcms.service.impl.BranchInventoryServiceImpl}.</p>
 */
public interface BranchInventoryService {

    /**
     * Tìm kiếm và phân trang tồn kho vật tư của một cơ sở theo từ khóa và trạng thái sắp hết hàng.
     *
     * @param branchId mã cơ sở cần tra cứu (bắt buộc)
     * @param keyword  từ khóa tìm kiếm mã/tên/nhóm vật tư (tùy chọn)
     * @param lowStock {@code true} để chỉ lấy vật tư có tồn kho nhỏ hơn hoặc bằng ngưỡng tối thiểu (tùy chọn)
     * @param pageable thông tin phân trang và sắp xếp
     * @return trang dữ liệu {@link InventoryItemDTO}
     */
    Page<InventoryItemDTO> search(
            Long branchId,
            String keyword,
            Boolean lowStock,
            Pageable pageable
    );
}
