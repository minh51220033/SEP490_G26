package com.cvdcms.service.impl;

import com.cvdcms.DTO.MaterialBatchDTO;
import com.cvdcms.entity.MaterialBatch;
import com.cvdcms.repository.MaterialBatchRepository;
import com.cvdcms.service.MaterialBatchService;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Triển khai nghiệp vụ tra cứu Lô vật tư &amp; tính toán cảnh báo Hạn sử dụng ({@link MaterialBatchService}).
 * <p>Ngưỡng cảnh báo sắp hết hạn được quy định là {@value #EXPIRING_SOON_DAYS} ngày kể từ ngày hiện tại.</p>
 * <p>Được gọi từ {@link com.cvdcms.controller.MaterialBatchController}.</p>
 */
@Service
public class MaterialBatchServiceImpl implements MaterialBatchService {

    /** Số ngày trước hạn sử dụng để đánh dấu trạng thái cảnh báo {@code EXPIRING_SOON}. */
    private static final int EXPIRING_SOON_DAYS = 30;

    private final MaterialBatchRepository batchRepository;

    /**
     * Khởi tạo service với repository lô vật tư.
     */
    public MaterialBatchServiceImpl(
            MaterialBatchRepository batchRepository) {
        this.batchRepository = batchRepository;
    }

    /**
     * Tìm kiếm và phân trang danh sách lô vật tư tại chi nhánh theo từ khóa và khoảng hạn sử dụng.
     * <p>Xử lý: kiểm tra {@code branchId > 0} và {@code expiryFrom <= expiryTo} → chuẩn hóa từ khóa
     * → truy vấn {@link MaterialBatchRepository#searchBatches} → ánh xạ từng bản ghi qua {@link #toDTO(MaterialBatch, LocalDate)}.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public Page<MaterialBatchDTO> searchBatches(
            Long branchId,
            String keyword,
            LocalDate expiryFrom,
            LocalDate expiryTo,
            Pageable pageable) {

        if (branchId == null || branchId <= 0) {
            throw new IllegalArgumentException(
                    "Chi nhánh không hợp lệ.");
        }

        if (expiryFrom != null && expiryTo != null
                && expiryFrom.isAfter(expiryTo)) {
            throw new IllegalArgumentException(
                    "Ngày bắt đầu không được sau ngày kết thúc.");
        }

        String normalizedKeyword =
                keyword == null || keyword.isBlank()
                        ? null
                        : keyword.trim();

        LocalDate today = LocalDate.now();

        Page<MaterialBatch> batches =
                batchRepository.searchBatches(
                        branchId,
                        normalizedKeyword,
                        expiryFrom,
                        expiryTo,
                        pageable
                );

        return batches.map(batch -> toDTO(batch, today));
    }

    /**
     * Lấy danh sách các lô còn tồn kho ({@code quantityRemaining > 0}) của chi nhánh theo thứ tự FEFO.
     * <p>Xử lý: kiểm tra {@code branchId > 0} → gọi {@link MaterialBatchRepository#findBatchesWithStockByBranch(Long, Long)}
     * → ánh xạ sang {@link MaterialBatchDTO} kèm thông tin cảnh báo HSD.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public List<MaterialBatchDTO> getBatchesWithStock(Long branchId, Long materialId) {
        if (branchId == null || branchId <= 0) {
            throw new IllegalArgumentException("Chi nhánh không hợp lệ.");
        }
        LocalDate today = LocalDate.now();
        return batchRepository.findBatchesWithStockByBranch(branchId, materialId)
                .stream()
                .map(batch -> toDTO(batch, today))
                .collect(Collectors.toList());
    }

    /**
     * Chuyển đổi thực thể {@link MaterialBatch} sang {@link MaterialBatchDTO} và tính toán:
     * <ul>
     *   <li>{@code daysToExpiry}: số ngày còn lại tính từ {@code today} đến {@code expiryDate} (âm nếu đã hết hạn).</li>
     *   <li>{@code expiryWarning}: {@code EXPIRED} nếu đã quá hạn, {@code EXPIRING_SOON} nếu trong vòng 30 ngày, ngược lại {@code NONE}.</li>
     * </ul>
     */
    private MaterialBatchDTO toDTO(MaterialBatch batch, LocalDate today) {
        LocalDate expiryDate = batch.getExpiryDate();
        long daysToExpiry = ChronoUnit.DAYS.between(today, expiryDate);
        String warning;

        if (expiryDate.isBefore(today)) {
            warning = "EXPIRED";
        } else if (!expiryDate.isAfter(today.plusDays(EXPIRING_SOON_DAYS))) {
            warning = "EXPIRING_SOON";
        } else {
            warning = "NONE";
        }

        return new MaterialBatchDTO(
                batch.getBatchId(),
                batch.getBranch().getBranchId(),
                batch.getBranch().getBranchName(),
                batch.getMaterial().getMaterialId(),
                batch.getMaterial().getMaterialCode(),
                batch.getMaterial().getMaterialName(),
                batch.getMaterial().getUnit(),
                batch.getBatchNumber(),
                expiryDate,
                batch.getQuantityRemaining(),
                batch.getImportPrice(),
                batch.getSupplier() == null ? null : batch.getSupplier().getSupplierId(),
                batch.getSupplier() == null ? null : batch.getSupplier().getSupplierName(),
                batch.getStatus() == null ? null : batch.getStatus().name(),
                daysToExpiry,
                warning
        );
    }
}
