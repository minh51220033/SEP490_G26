package com.cvdcms.DTO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO trả về thông tin tóm tắt hoặc chi tiết đầy đủ của một Phiếu nhập kho ({@code StockReceipt}).
 * <p>Sử dụng cho API danh sách ({@code GET /api/inventory/receipts}), chi tiết ({@code GET /api/inventory/receipts/{id}}),
 * xuất PDF ({@link com.cvdcms.util.InventoryPdfGenerator#generateReceiptPdf}) và trang in {@code inventory/print-receipt.html}.</p>
 */
public class StockReceiptResponseDTO {
    /** Mã định danh phiếu nhập kho. */
    private Long receiptId;
    /** Mã phiếu nhập kho duy nhất (tiền tố {@code PN}). */
    private String receiptCode;
    /** Mã chi nhánh nhập kho. */
    private Long branchId;
    /** Tên chi nhánh nhập kho. */
    private String branchName;
    /** Mã nhà cung cấp. */
    private Long supplierId;
    /** Mã code nhà cung cấp. */
    private String supplierCode;
    /** Tên nhà cung cấp. */
    private String supplierName;
    /** Số hóa đơn VAT / chứng từ nhà cung cấp. */
    private String supplierInvoiceNo;
    /** Ngày lập phiếu nhập kho. */
    private LocalDate receiptDate;
    /** Tổng giá trị phiếu nhập kho (VNĐ). */
    private BigDecimal totalAmount;
    /** Tổng số tiền đã thanh toán cho phiếu nhập này (VNĐ). */
    private BigDecimal paidAmount;
    /** Công nợ còn lại của phiếu nhập ({@code max(0, totalAmount - paidAmount)}). */
    private BigDecimal remainingDebt;
    /** Họ tên nhân viên lập phiếu / tiếp nhận kho. */
    private String receiverName;
    /** Ghi chú phiếu nhập. */
    private String notes;
    /** Đường dẫn URL chứng từ hóa đơn đính kèm ({@code /uploads/invoices/...}). */
    private String attachmentUrl;
    /** Tổng số dòng vật tư trong phiếu nhập. */
    private int itemCount;
    /** Danh sách chi tiết từng dòng vật tư/lô đã nhập (chỉ có dữ liệu đầy đủ khi xem chi tiết hoặc in phiếu). */
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

    /**
     * DTO chi tiết một dòng vật tư &amp; lô hàng trong phiếu nhập kho.
     */
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
    public BigDecimal getTotalQuantity() {
        if (items == null || items.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return items.stream()
                .map(ItemDetail::getQuantity)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    public int getItemCount() { return itemCount; }
    public void setItemCount(int itemCount) { this.itemCount = itemCount; }
    public List<ItemDetail> getItems() { return items; }
    public void setItems(List<ItemDetail> items) { this.items = items; }
}
