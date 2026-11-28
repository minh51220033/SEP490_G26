package com.cvdcms.service;

import com.cvdcms.DTO.MaterialBatchDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;

/**
 * Giao diện Service xử lý nghiệp vụ tra cứu lô vật tư và cảnh báo hạn sử dụng theo chi nhánh.
 * <p>Được gọi từ {@link com.cvdcms.controller.MaterialBatchController} và triển khai bởi
 * {@link com.cvdcms.service.impl.MaterialBatchServiceImpl}.</p>
 */
public interface MaterialBatchService {

    /**
     * Tìm kiếm và phân trang danh sách lô vật tư theo chi nhánh, từ khóa và khoảng hạn sử dụng.
     *
     * @param branchId   mã chi nhánh cần tra cứu
     * @param keyword    từ khóa tìm kiếm theo tên/mã vật tư hoặc số lô (tùy chọn)
     * @param expiryFrom hạn sử dụng từ ngày (tùy chọn)
     * @param expiryTo   hạn sử dụng đến ngày (tùy chọn)
     * @param pageable   cấu hình phân trang và sắp xếp
     * @return trang dữ liệu {@link MaterialBatchDTO} đã tính sẵn số ngày còn lại và mức cảnh báo HSD
     */
    Page<MaterialBatchDTO> searchBatches(
            Long branchId,
            String keyword,
            LocalDate expiryFrom,
            LocalDate expiryTo,
            Pageable pageable
    );

    /**
     * Lấy danh sách các lô còn tồn kho ({@code quantityRemaining > 0}) tại một chi nhánh, sắp xếp theo FEFO (hạn gần nhất trước).
     *
     * @param branchId   mã chi nhánh
     * @param materialId mã vật tư cần lọc (tùy chọn; {@code null} để lấy toàn bộ vật tư)
     * @return danh sách {@link MaterialBatchDTO} còn tồn kho
     */
    List<MaterialBatchDTO> getBatchesWithStock(
            Long branchId,
            Long materialId
    );
}
