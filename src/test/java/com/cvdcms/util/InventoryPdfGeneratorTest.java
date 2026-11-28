package com.cvdcms.util;

import com.cvdcms.DTO.StockIssueResponseDTO;
import com.cvdcms.DTO.StockReceiptResponseDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InventoryPdfGeneratorTest {

    @Test
    @DisplayName("generateReceiptPdf - Generate valid %PDF-1.4 receipt PDF with debt summary and %%EOF footer")
    void generateReceiptPdf_generatesValidPdfBytes() {
        StockReceiptResponseDTO receipt = new StockReceiptResponseDTO(
                1L, "PN-2026-001", 1L, 10L, LocalDate.of(2026, 11, 10), new BigDecimal("2500000")
        );
        receipt.setBranchName("Cơ sở Quận 1");
        receipt.setSupplierName("Công ty 3M Việt Nam");
        receipt.setSupplierCode("NCC-3M");
        receipt.setSupplierInvoiceNo("HD-9988");
        receipt.setReceiverName("Nguyễn Văn Kho");
        receipt.setNotes("Nhập đợt 1");
        receipt.setPaidAmount(new BigDecimal("1000000"));
        receipt.setRemainingDebt(new BigDecimal("1500000"));

        StockReceiptResponseDTO.ItemDetail item = new StockReceiptResponseDTO.ItemDetail(
                1L, 100L, "VT-01", "Composite 3M Z350 XT", "Ống", "LO-01",
                LocalDate.of(2028, 12, 31), new BigDecimal("10"), new BigDecimal("250000"), new BigDecimal("2500000")
        );
        receipt.setItems(List.of(item));

        byte[] pdf = InventoryPdfGenerator.generateReceiptPdf(receipt);

        assertNotNull(pdf);
        assertTrue(pdf.length > 1000);
        String header = new String(pdf, 0, 8, StandardCharsets.US_ASCII);
        String tail = new String(pdf, pdf.length - 10, 10, StandardCharsets.US_ASCII);
        assertTrue(header.startsWith("%PDF-1.4"));
        assertTrue(tail.contains("%%EOF"));
    }

    @Test
    @DisplayName("generateIssuePdf - Generate valid PDF for Internal Use and Branch Transfer even with optional fields missing")
    void generateIssuePdf_handlesInternalUseAndTransfer() {
        StockIssueResponseDTO internal = new StockIssueResponseDTO();
        internal.setIssueCode("PX-2026-001");
        internal.setBranchName("Cơ sở Quận 1");
        internal.setIssueType("INTERNAL_USE");
        internal.setIssueDate(LocalDate.of(2026, 11, 10));
        internal.setIssuerName("Thủ kho A");
        internal.setTotalQuantity(new BigDecimal("5"));
        internal.setTotalEstimatedValue(new BigDecimal("500000"));
        internal.setItems(List.of());

        byte[] internalPdf = InventoryPdfGenerator.generateIssuePdf(internal);
        assertTrue(new String(internalPdf, 0, 8, StandardCharsets.US_ASCII).startsWith("%PDF-1.4"));

        StockIssueResponseDTO transfer = new StockIssueResponseDTO();
        transfer.setIssueCode("DC-2026-002");
        transfer.setBranchName("Cơ sở Quận 1");
        transfer.setDestinationBranchName("Cơ sở Đống Đa");
        transfer.setIssueType("TRANSFER");
        transfer.setIssueDate(LocalDate.of(2026, 11, 10));
        transfer.setIssuerName("Thủ kho A");

        StockIssueResponseDTO.ItemDetail item = new StockIssueResponseDTO.ItemDetail();
        item.setMaterialCode("VT-01");
        item.setMaterialName("Thuốc tê Lidocaine");
        item.setUnit("Ống");
        item.setBatchNumber("LO-02");
        item.setExpiryDate(LocalDate.of(2027, 6, 30));
        item.setQuantity(new BigDecimal("3"));
        item.setImportPrice(new BigDecimal("15000"));
        item.setLineValue(new BigDecimal("45000"));
        transfer.setItems(List.of(item));

        byte[] transferPdf = InventoryPdfGenerator.generateIssuePdf(transfer);
        assertTrue(transferPdf.length > 1000);
        assertTrue(new String(transferPdf, transferPdf.length - 10, 10, StandardCharsets.US_ASCII).contains("%%EOF"));
    }
}
