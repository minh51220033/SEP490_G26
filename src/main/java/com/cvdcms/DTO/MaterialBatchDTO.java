
package com.cvdcms.DTO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class MaterialBatchDTO {

    private Long batchId;
    private Long branchId;
    private String branchName;

    private Long materialId;
    private String materialCode;
    private String materialName;
    private String unit;

    private String batchNumber;
    private LocalDate expiryDate;
    private BigDecimal quantityRemaining;
    private BigDecimal importPrice;

    private Long supplierId;
    private String supplierName;

    // Trạng thái lưu trong database.
    private String status;

    // Số ngày đến hạn: âm nghĩa là đã quá hạn.
    private long daysToExpiry;

    // NONE, EXPIRING_SOON hoặc EXPIRED.
    private String expiryWarning;

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
