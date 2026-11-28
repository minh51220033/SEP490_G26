
package com.cvdcms.DTO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO trả về thông tin tồn kho tổng hợp của một vật tư tại một cơ sở.
 * <p>Sử dụng bởi {@link com.cvdcms.service.impl.BranchInventoryServiceImpl#search} và trả về JSON tại
 * {@code GET /api/inventory} cho bảng Tồn kho hiện tại trong {@code inventory.js}.</p>
 */
public class InventoryItemDTO {

    /** Mã định danh bản ghi tồn kho cơ sở. */
    private Long inventoryId;
    /** Mã định danh chi nhánh. */
    private Long branchId;
    /** Tên chi nhánh hiển thị. */
    private String branchName;

    /** Mã định danh vật tư. */
    private Long materialId;
    /** Mã vật tư (SKU). */
    private String materialCode;
    /** Tên vật tư. */
    private String materialName;
    /** Nhóm vật tư. */
    private String category;
    /** Đơn vị tính (Hộp, Tuýp, Cái, ...). */
    private String unit;

    /** Số lượng tồn kho thực tế hiện tại tại chi nhánh. */
    private BigDecimal currentStock;
    /** Ngưỡng tồn kho tối thiểu dùng để cảnh báo sắp hết hàng. */
    private BigDecimal minStockThreshold;

    /** Thời điểm cập nhật tồn kho gần nhất. */
    private LocalDateTime lastUpdated;

    public InventoryItemDTO() {
    }

    public InventoryItemDTO(
            Long inventoryId,
            Long branchId,
            String branchName,
            Long materialId,
            String materialCode,
            String materialName,
            String category,
            String unit,
            BigDecimal currentStock,
            BigDecimal minStockThreshold,
            LocalDateTime lastUpdated) {

        this.inventoryId = inventoryId;
        this.branchId = branchId;
        this.branchName = branchName;
        this.materialId = materialId;
        this.materialCode = materialCode;
        this.materialName = materialName;
        this.category = category;
        this.unit = unit;
        this.currentStock = currentStock;
        this.minStockThreshold = minStockThreshold;
        this.lastUpdated = lastUpdated;
    }

    public Long getInventoryId() {
        return inventoryId;
    }

    public void setInventoryId(Long inventoryId) {
        this.inventoryId = inventoryId;
    }

    public Long getBranchId() {
        return branchId;
    }

    public void setBranchId(Long branchId) {
        this.branchId = branchId;
    }

    public String getBranchName() {
        return branchName;
    }

    public void setBranchName(String branchName) {
        this.branchName = branchName;
    }

    public Long getMaterialId() {
        return materialId;
    }

    public void setMaterialId(Long materialId) {
        this.materialId = materialId;
    }

    public String getMaterialCode() {
        return materialCode;
    }

    public void setMaterialCode(String materialCode) {
        this.materialCode = materialCode;
    }

    public String getMaterialName() {
        return materialName;
    }

    public void setMaterialName(String materialName) {
        this.materialName = materialName;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public BigDecimal getCurrentStock() {
        return currentStock;
    }

    public void setCurrentStock(BigDecimal currentStock) {
        this.currentStock = currentStock;
    }

    public BigDecimal getMinStockThreshold() {
        return minStockThreshold;
    }

    public void setMinStockThreshold(BigDecimal minStockThreshold) {
        this.minStockThreshold = minStockThreshold;
    }

    public LocalDateTime getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(LocalDateTime lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    /**
     * Xác định vật tư có tồn kho thấp hơn hoặc bằng ngưỡng tối thiểu.
     */
    public boolean isLowStock() {
        return currentStock != null
                && minStockThreshold != null
                && currentStock.compareTo(minStockThreshold) <= 0;
    }
}
