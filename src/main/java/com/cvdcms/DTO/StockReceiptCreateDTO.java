package com.cvdcms.DTO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class StockReceiptCreateDTO {
    private Long branchId;
    private Long supplierId;
    private String supplierInvoiceNo;
    private LocalDate receiptDate;
    private String notes;
    private String attachmentUrl;
    private List<Item> items;

    public static class Item {
        private Long materialId;
        private String batchNumber;
        private LocalDate expiryDate;
        private BigDecimal quantity;
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
    public List<Item> getItems(){return items;}
    public void setItems(List<Item> v){items=v;}
}