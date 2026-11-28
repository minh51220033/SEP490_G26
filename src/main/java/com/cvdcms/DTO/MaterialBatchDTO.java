
package com.cvdcms.DTO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * DTO trả về thông tin chi tiết một lô vật tư kèm số ngày còn lại đến hạn và mức cảnh báo hạn sử dụng.
 * <p>Sử dụng bởi {@link com.cvdcms.service.impl.MaterialBatchServiceImpl} và trả về tại
 * {@code GET /api/inventory/batches} và {@code GET /api/inventory/batches/available}.</p>
 */
public class MaterialBatchDTO {

    /** Mã định danh lô vật tư. */
    private Long batchId;
    /** Mã chi nhánh chứa lô. */
    private Long branchId;
    /** Tên chi nhánh chứa lô. */
    private String branchName;

    /** Mã định danh vật tư. */
    private Long materialId;
    /** Mã vật tư. */
    private String materialCode;
    /** Tên vật tư. */
    private String materialName;
    /** Đơn vị tính. */
    private String unit;

    /** Số lô nhà sản xuất/nhập kho. */
    private String batchNumber;
    /** Ngày hết hạn sử dụng của lô. */
    private LocalDate expiryDate;
    /** Số lượng còn tồn trong lô. */
    private BigDecimal quantityRemaining;
    /** Đơn giá nhập của lô (VNĐ). */
    private BigDecimal importPrice;

    /** Mã nhà cung cấp lô hàng (có thể null). */
    private Long supplierId;
    /** Tên nhà cung cấp lô hàng. */
    private String supplierName;

    /** Trạng thái lô lưu trong CSDL ({@code ACTIVE}, {@code EXPIRED}, {@code OUT_OF_STOCK}). */
    private String status;

    /** Số ngày còn lại đến hạn sử dụng (giá trị âm nghĩa là đã quá hạn). */
    private long daysToExpiry;

    /** Mức cảnh báo HSD tính toán theo ngày hiện tại: {@code NONE}, {@code EXPIRING_SOON} (&lt;= 30 ngày) hoặc {@code EXPIRED}. */
    private String expiryWarning;

    /** Thời điểm cập nhật lô gần nhất. */
    private LocalDateTime lastUpdated;

    public MaterialBatchDTO(
            Long batchId,
            Long branchId,
            String branchName,
            Long materialId,
            String materialCode,
            String materialName,
            String unit,
            String batchNumber,
            LocalDate expiryDate,
            BigDecimal quantityRemaining,
            BigDecimal importPrice,
            Long supplierId,
            String supplierName,
            String status,
            long daysToExpiry,
            String expiryWarning) {

        this.batchId = batchId;
        this.branchId = branchId;
        this.branchName = branchName;
        this.materialId = materialId;
        this.materialCode = materialCode;
        this.materialName = materialName;
        this.unit = unit;
        this.batchNumber = batchNumber;
        this.expiryDate = expiryDate;
        this.quantityRemaining = quantityRemaining;
        this.importPrice = importPrice;
        this.supplierId = supplierId;
        this.supplierName = supplierName;
        this.status = status;
        this.daysToExpiry = daysToExpiry;
        this.expiryWarning = expiryWarning;
    }

    public Long getBatchId() { return batchId; }
    public Long getBranchId() { return branchId; }
    public String getBranchName() { return branchName; }
    public Long getMaterialId() { return materialId; }
    public String getMaterialCode() { return materialCode; }
    public String getMaterialName() { return materialName; }
    public String getUnit() { return unit; }
    public String getBatchNumber() { return batchNumber; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public BigDecimal getQuantityRemaining() { return quantityRemaining; }
    public BigDecimal getImportPrice() { return importPrice; }
    public Long getSupplierId() { return supplierId; }
    public String getSupplierName() { return supplierName; }
    public String getStatus() { return status; }
    public long getDaysToExpiry() { return daysToExpiry; }
    public String getExpiryWarning() { return expiryWarning; }
    public LocalDateTime getLastUpdated() { return lastUpdated; }
}
