package com.cvdcms.DTO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * DTO nhận dữ liệu đầu vào khi lập mới Phiếu nhập kho ({@code POST /api/inventory/receipts}).
 * <p>Bao gồm thông tin chung của phiếu, chứng từ hóa đơn bắt buộc, thông tin thanh toán ngay (nếu có) và danh sách dòng vật tư/lô.</p>
 * <p>Được gửi từ hàm {@code submitReceipt()} trong {@code inventory.js}.</p>
 */
public class StockReceiptCreateDTO {
    /** Mã chi nhánh nhập kho (bắt buộc). */
    private Long branchId;
    /** Mã nhà cung cấp giao hàng (bắt buộc). */
    private Long supplierId;
    /** Số hóa đơn VAT / chứng từ của nhà cung cấp (tùy chọn, tối đa 50 ký tự). */
    private String supplierInvoiceNo;
    /** Ngày nhập kho (mặc định ngày hiện tại nếu để trống, không được ở tương lai). */
    private LocalDate receiptDate;
    /** Ghi chú phiếu nhập (tối đa 255 ký tự). */
    private String notes;
    /** Đường dẫn file ảnh/PDF hóa đơn đã tải lên qua {@code /api/inventory/receipts/upload-invoice} (bắt buộc). */
    private String attachmentUrl;
    /** Số tiền thanh toán ngay cho nhà cung cấp khi nhập kho (0 &lt;= paidAmount &lt;= totalAmount). */
    private BigDecimal paidAmount;
    /** Hình thức thanh toán ngay: {@code CASH} (Tiền mặt) hoặc {@code BANK_TRANSFER} (Chuyển khoản). */
    private String paymentMethod;
    /** Số chứng từ / mã giao dịch thanh toán (tùy chọn, tối đa 100 ký tự). */
    private String paymentReferenceNo;
    /** Danh sách các dòng vật tư &amp; lô hàng cần nhập (bắt buộc ít nhất 1 dòng). */
    private List<Item> items;

    /**
     * Đại diện một dòng vật tư nhập kho theo số lô và hạn sử dụng cụ thể.
     */
    public static class Item {
        /** Mã định danh vật tư. */
        private Long materialId;
        /** Số lô nhập (bắt buộc, 1–50 ký tự, không trùng lặp cho cùng vật tư trong một phiếu). */
        private String batchNumber;
        /** Ngày hết hạn sử dụng của lô (bắt buộc, không trước ngày nhập). */
        private LocalDate expiryDate;
        /** Số lượng nhập (&gt; 0, hỗ trợ tối đa 3 chữ số thập phân). */
        private BigDecimal quantity;
        /** Đơn giá nhập của 1 đơn vị (&gt;= 0, VNĐ). */
        private BigDecimal unitPrice;
        public Long getMaterialId(){return materialId;}
        public void setMaterialId(Long v){materialId=v;}
        public String getBatchNumber(){return batchNumber;}
        public void setBatchNumber(String v){batchNumber=v;}
        public LocalDate getExpiryDate(){return expiryDate;}
        public void setExpiryDate(LocalDate v){expiryDate=v;}
        public BigDecimal getQuantity(){return quantity;}
        public void setQuantity(BigDecimal v){quantity=v;}
        public BigDecimal getUnitPrice(){return unitPrice;}
        public void setUnitPrice(BigDecimal v){unitPrice=v;}
    }
    public Long getBranchId(){return branchId;}
    public void setBranchId(Long v){branchId=v;}
    public Long getSupplierId(){return supplierId;}
    public void setSupplierId(Long v){supplierId=v;}
    public String getSupplierInvoiceNo(){return supplierInvoiceNo;}
    public void setSupplierInvoiceNo(String v){supplierInvoiceNo=v;}
    public LocalDate getReceiptDate(){return receiptDate;}
    public void setReceiptDate(LocalDate v){receiptDate=v;}
    public String getNotes(){return notes;}
    public void setNotes(String v){notes=v;}
    public String getAttachmentUrl(){return attachmentUrl;}
    public void setAttachmentUrl(String v){attachmentUrl=v;}
    public BigDecimal getPaidAmount(){return paidAmount;}
    public void setPaidAmount(BigDecimal v){paidAmount=v;}
    public String getPaymentMethod(){return paymentMethod;}
    public void setPaymentMethod(String v){paymentMethod=v;}
    public String getPaymentReferenceNo(){return paymentReferenceNo;}
    public void setPaymentReferenceNo(String v){paymentReferenceNo=v;}
    public List<Item> getItems(){return items;}
    public void setItems(List<Item> v){items=v;}
}
