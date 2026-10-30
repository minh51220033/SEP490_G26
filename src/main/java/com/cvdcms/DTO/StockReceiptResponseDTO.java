package com.cvdcms.DTO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class StockReceiptResponseDTO {
    private Long receiptId;
    private String receiptCode;
    private Long branchId;
    private String branchName;
    private Long supplierId;
    private String supplierCode;
    private String supplierName;
    private String supplierInvoiceNo;
    private LocalDate receiptDate;
    private BigDecimal totalAmount;
    private BigDecimal paidAmount;
    private BigDecimal remainingDebt;
    private String receiverName;
    private String notes;
    private String attachmentUrl;
    private String signatureUrl;
    private Long signedByUserId;
    private String signedByName;
    private LocalDateTime signedAt;
    private int itemCount;
    private List<ItemDetail> items = new ArrayList<>();

    public StockReceiptResponseDTO() {
    }

    public StockReceiptResponseDTO(Long id, String code, Long branchId, Long supplierId, LocalDate date, BigDecimal total) {
        this.receiptId = id;
        this.receiptCode = code;
        this.branchId = branchId;
        this.supplierId = supplierId;
        this.receiptDate = date;
        this.totalAmount = total;
    }

    public static class ItemDetail {
        private Long receiptItemId;
        private Long materialId;
        private String materialCode;
        private String materialName;
        private String unit;
        private String batchNumber;
        private LocalDate expiryDate;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal lineTotal;

        public ItemDetail() {
        }

        public ItemDetail(Long receiptItemId, Long materialId, String materialCode, String materialName,
                          String unit, String batchNumber, LocalDate expiryDate,
                          BigDecimal quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
            this.receiptItemId = receiptItemId;
            this.materialId = materialId;
            this.materialCode = materialCode;
            this.materialName = materialName;
            this.unit = unit;
            this.batchNumber = batchNumber;
            this.expiryDate = expiryDate;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
            this.lineTotal = lineTotal;
        }

        public Long getReceiptItemId() { return receiptItemId; }
        public void setReceiptItemId(Long receiptItemId) { this.receiptItemId = receiptItemId; }
        public Long getMaterialId() { return materialId; }
        public void setMaterialId(Long materialId) { this.materialId = materialId; }
        public String getMaterialCode() { return materialCode; }
        public void setMaterialCode(String materialCode) { this.materialCode = materialCode; }
        public String getMaterialName() { return materialName; }
        public void setMaterialName(String materialName) { this.materialName = materialName; }
        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }
        public String getBatchNumber() { return batchNumber; }
        public void setBatchNumber(String batchNumber) { this.batchNumber = batchNumber; }
        public LocalDate getExpiryDate() { return expiryDate; }
        public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }
        public BigDecimal getQuantity() { return quantity; }
        public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
        public BigDecimal getUnitPrice() { return unitPrice; }
        public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
        public BigDecimal getLineTotal() { return lineTotal; }
        public void setLineTotal(BigDecimal lineTotal) { this.lineTotal = lineTotal; }
    }

    public Long getReceiptId() { return receiptId; }
    public void setReceiptId(Long receiptId) { this.receiptId = receiptId; }
    public String getReceiptCode() { return receiptCode; }
    public void setReceiptCode(String receiptCode) { this.receiptCode = receiptCode; }
    public Long getBranchId() { return branchId; }
    public void setBranchId(Long branchId) { this.branchId = branchId; }
    public String getBranchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }
    public Long getSupplierId() { return supplierId; }
    public void setSupplierId(Long supplierId) { this.supplierId = supplierId; }
    public String getSupplierCode() { return supplierCode; }
    public void setSupplierCode(String supplierCode) { this.supplierCode = supplierCode; }
    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
    public String getSupplierInvoiceNo() { return supplierInvoiceNo; }
    public void setSupplierInvoiceNo(String supplierInvoiceNo) { this.supplierInvoiceNo = supplierInvoiceNo; }
    public LocalDate getReceiptDate() { return receiptDate; }
    public void setReceiptDate(LocalDate receiptDate) { this.receiptDate = receiptDate; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }
    public BigDecimal getRemainingDebt() { return remainingDebt; }
    public void setRemainingDebt(BigDecimal remainingDebt) { this.remainingDebt = remainingDebt; }
    public String getReceiverName() { return receiverName; }
    public void setReceiverName(String receiverName) { this.receiverName = receiverName; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getAttachmentUrl() { return attachmentUrl; }
    public void setAttachmentUrl(String attachmentUrl) { this.attachmentUrl = attachmentUrl; }
    public String getSignatureUrl() { return signatureUrl; }
    public void setSignatureUrl(String signatureUrl) { this.signatureUrl = signatureUrl; }
    public Long getSignedByUserId() { return signedByUserId; }
    public void setSignedByUserId(Long signedByUserId) { this.signedByUserId = signedByUserId; }
    public String getSignedByName() { return signedByName; }
    public void setSignedByName(String signedByName) { this.signedByName = signedByName; }
    public LocalDateTime getSignedAt() { return signedAt; }
    public void setSignedAt(LocalDateTime signedAt) { this.signedAt = signedAt; }
    public int getItemCount() { return itemCount; }
    public void setItemCount(int itemCount) { this.itemCount = itemCount; }
    public List<ItemDetail> getItems() { return items; }
    public void setItems(List<ItemDetail> items) { this.items = items; }
}
