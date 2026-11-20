package com.cvdcms.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InventoryFileStorageUtilTest {

    private final List<Path> createdFiles = new ArrayList<>();

    @AfterEach
    void cleanupCreatedFiles() throws Exception {
        for (Path p : createdFiles) {
            Files.deleteIfExists(p);
        }
    }

    @Test
    @DisplayName("validateRequiredInvoiceUrl - Chặn rỗng, path traversal, sai prefix và sai định dạng")
    void validateRequiredInvoiceUrl_rejectsInvalidUrls() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,
                        () -> InventoryFileStorageUtil.validateRequiredInvoiceUrl(null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,
                        () -> InventoryFileStorageUtil.validateRequiredInvoiceUrl("   ")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,
                        () -> InventoryFileStorageUtil.validateRequiredInvoiceUrl("/uploads/invoices/" + "a".repeat(500) + ".png")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,
                        () -> InventoryFileStorageUtil.validateRequiredInvoiceUrl("/uploads/invoices/../secret.png")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,
                        () -> InventoryFileStorageUtil.validateRequiredInvoiceUrl("/uploads/invoices\\inv.png")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,
                        () -> InventoryFileStorageUtil.validateRequiredInvoiceUrl("https://evil.com/inv.png")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,
                        () -> InventoryFileStorageUtil.validateRequiredInvoiceUrl("/uploads/invoices/inv.exe")).getStatusCode());
    }

    @Test
    @DisplayName("validateRequiredInvoiceUrl - Chấp nhận đường dẫn hóa đơn hợp lệ và trim khoảng trắng")
    void validateRequiredInvoiceUrl_acceptsValidUrl() {
        String valid = InventoryFileStorageUtil.validateRequiredInvoiceUrl("  /uploads/invoices/inv_123.pdf  ");
        assertEquals("/uploads/invoices/inv_123.pdf", valid);
    }

    @Test
    @DisplayName("saveInvoiceFile - Chặn file rỗng, quá 5MB, sai phần mở rộng, sai MIME hoặc giả mạo magic bytes")
    void saveInvoiceFile_rejectsInvalidFiles() {
        MockMultipartFile empty = new MockMultipartFile("file", "inv.png", "image/png", new byte[0]);
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> InventoryFileStorageUtil.saveInvoiceFile(empty)).getStatusCode());

        byte[] over5Mb = new byte[(int) InventoryFileStorageUtil.MAX_INVOICE_FILE_SIZE + 1];
        MockMultipartFile tooBig = new MockMultipartFile("file", "inv.png", "image/png", over5Mb);
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> InventoryFileStorageUtil.saveInvoiceFile(tooBig)).getStatusCode());

        MockMultipartFile badExt = new MockMultipartFile("file", "inv.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> InventoryFileStorageUtil.saveInvoiceFile(badExt)).getStatusCode());

        MockMultipartFile badMime = new MockMultipartFile("file", "inv.png", "text/html", new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0});
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> InventoryFileStorageUtil.saveInvoiceFile(badMime)).getStatusCode());

        MockMultipartFile spoofedPng = new MockMultipartFile("file", "inv.png", "image/png", "not a png".getBytes(StandardCharsets.UTF_8));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> InventoryFileStorageUtil.saveInvoiceFile(spoofedPng)).getStatusCode());
    }

    @Test
    @DisplayName("saveInvoiceFile - Lưu thành công file PDF hợp lệ vào uploads/invoices/")
    void saveInvoiceFile_savesValidPdf() {
        byte[] pdfBytes = "%PDF-1.4\n%test\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        MockMultipartFile pdfFile = new MockMultipartFile("file", "hoa_don.pdf", "application/pdf", pdfBytes);

        String url = InventoryFileStorageUtil.saveInvoiceFile(pdfFile);
        Path saved = Paths.get(url.substring(1)).toAbsolutePath().normalize();
        createdFiles.add(saved);

        assertTrue(url.startsWith("/uploads/invoices/inv_"));
        assertTrue(url.endsWith(".pdf"));
        assertTrue(Files.exists(saved));
    }

    @Test
    @DisplayName("saveSignatureDataUrl - Chặn rỗng, Base64 lỗi, không phải PNG, kích thước nhỏ và canvas trắng")
    void saveSignatureDataUrl_rejectsInvalidOrBlankSignatures() throws Exception {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,
                        () -> InventoryFileStorageUtil.saveSignatureDataUrl(" ", "sig")).getStatusCode());

        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,
                        () -> InventoryFileStorageUtil.saveSignatureDataUrl("data:image/jpeg;base64,abc", "sig")).getStatusCode());

        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,
                        () -> InventoryFileStorageUtil.saveSignatureDataUrl("data:image/png;base64,***not-base64***", "sig")).getStatusCode());

        // Canvas trắng hoàn toàn không có nét vẽ
        BufferedImage blankImg = new BufferedImage(100, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = blankImg.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 100, 40);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(blankImg, "png", baos);
        String blankDataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());

        ResponseStatusException blankEx = assertThrows(ResponseStatusException.class,
                () -> InventoryFileStorageUtil.saveSignatureDataUrl(blankDataUrl, "sig"));
        assertEquals(HttpStatus.BAD_REQUEST, blankEx.getStatusCode());
        assertTrue(blankEx.getReason().contains("trống"));
    }

    @Test
    @DisplayName("saveSignatureDataUrl - Lưu thành công chữ ký PNG có nét vẽ vào uploads/signatures/")
    void saveSignatureDataUrl_savesValidDrawnSignature() throws Exception {
        BufferedImage drawnImg = new BufferedImage(120, 50, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = drawnImg.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 120, 50);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(3f));
        g.drawLine(10, 10, 100, 40);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(drawnImg, "png", baos);
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());

        String url = InventoryFileStorageUtil.saveSignatureDataUrl(dataUrl, "Receipt-01");
        Path saved = Paths.get(url.substring(1)).toAbsolutePath().normalize();
        createdFiles.add(saved);

        assertTrue(url.startsWith("/uploads/signatures/receipt-01_"));
        assertTrue(url.endsWith(".png"));
        assertTrue(Files.exists(saved));
    }
}
