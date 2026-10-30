package com.cvdcms.util;

import com.cvdcms.DTO.StockIssueResponseDTO;
import com.cvdcms.DTO.StockReceiptResponseDTO;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Bộ tạo file PDF chuẩn A4 (%PDF-1.4) cho Phiếu nhập kho và Phiếu xuất kho:
 * - Hỗ trợ đầy đủ tiếng Việt UTF-8 và nhúng hình ảnh chữ ký điện tử + họ tên người ký.
 * - Không phụ thuộc thư viện ngoài, hoạt động ổn định trong chế độ offline.
 */
public final class InventoryPdfGenerator {

    private static final int CANVAS_WIDTH = 1240;
    private static final int CANVAS_HEIGHT = 1754;
    private static final int PDF_PAGE_WIDTH = 595;
    private static final int PDF_PAGE_HEIGHT = 842;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private InventoryPdfGenerator() {
    }

    public static byte[] generateReceiptPdf(StockReceiptResponseDTO r) {
        BufferedImage page = new BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = page.createGraphics();
        configureGraphics(g);

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);

        int marginX = 70;
        int contentWidth = CANVAS_WIDTH - (marginX * 2);
        int y = 65;

        // 1. Header phòng khám / chi nhánh
        y = drawClinicHeader(g, r.getBranchName(), r.getReceiptCode(), y, marginX, contentWidth);

        // 2. Tiêu đề phiếu
        y += 20;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
        g.setColor(new Color(15, 23, 42));
        drawCenteredString(g, "PHIẾU NHẬP KHO VẬT TƯ", CANVAS_WIDTH / 2, y);

        y += 28;
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        g.setColor(new Color(71, 85, 105));
        String dateStr = r.getReceiptDate() != null ? r.getReceiptDate().format(DATE_FMT) : "—";
        drawCenteredString(g, "Mã phiếu: " + safe(r.getReceiptCode()) + "   |   Ngày nhập: " + dateStr, CANVAS_WIDTH / 2, y);

        // 3. Khung thông tin chung
        y += 28;
        int infoBoxHeight = 140;
        g.setColor(new Color(248, 250, 252));
        g.fillRoundRect(marginX, y, contentWidth, infoBoxHeight, 12, 12);
        g.setColor(new Color(203, 213, 225));
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(marginX, y, contentWidth, infoBoxHeight, 12, 12);

        int col1X = marginX + 24;
        int col2X = marginX + (contentWidth / 2) + 20;
        int infoY = y + 34;

        drawLabelValue(g, "Nhà cung cấp:", safe(r.getSupplierName())
                + (r.getSupplierCode() != null ? " (" + r.getSupplierCode() + ")" : ""), col1X, infoY);
        drawLabelValue(g, "Số hóa đơn NCC:", safe(r.getSupplierInvoiceNo()), col2X, infoY);

        infoY += 36;
        drawLabelValue(g, "Chi nhánh nhập:", safe(r.getBranchName()), col1X, infoY);
        drawLabelValue(g, "Người lập phiếu:", safe(r.getReceiverName()), col2X, infoY);

        infoY += 36;
        drawLabelValue(g, "Ghi chú:", safe(r.getNotes()), col1X, infoY);

        y += infoBoxHeight + 28;

        // 4. Bảng danh sách hàng hóa
        int[] colWidths = {55, 130, 295, 85, 125, 120, 95, 135, 160};
        String[] headers = {"STT", "Mã VT", "Tên vật tư / hàng hóa", "ĐVT", "Số lô", "Hạn dùng", "SL", "Đơn giá", "Thành tiền"};

        y = drawTableHeader(g, marginX, y, colWidths, headers);

        List<StockReceiptResponseDTO.ItemDetail> items = r.getItems() != null ? r.getItems() : List.of();
        int rowIdx = 1;
        for (StockReceiptResponseDTO.ItemDetail item : items) {
            if (y > CANVAS_HEIGHT - 440) {
                break;
            }
            String[] rowValues = {
                    String.valueOf(rowIdx++),
                    safe(item.getMaterialCode()),
                    truncate(safe(item.getMaterialName()), 32),
                    safe(item.getUnit()),
                    safe(item.getBatchNumber()),
                    item.getExpiryDate() != null ? item.getExpiryDate().format(DATE_FMT) : "—",
                    formatQty(item.getQuantity()),
                    formatMoney(item.getUnitPrice()),
                    formatMoney(item.getLineTotal())
            };
            y = drawTableRow(g, marginX, y, colWidths, rowValues, rowIdx % 2 == 0);
        }

        // 5. Tổng hợp thanh toán & công nợ (Chỉ áp dụng cho Phiếu nhập kho)
        y += 22;
        int summaryWidth = 460;
        int summaryX = marginX + contentWidth - summaryWidth;
        int summaryHeight = 122;

        g.setColor(new Color(248, 250, 252));
        g.fillRoundRect(summaryX, y, summaryWidth, summaryHeight, 10, 10);
        g.setColor(new Color(203, 213, 225));
        g.drawRoundRect(summaryX, y, summaryWidth, summaryHeight, 10, 10);

        int sumRowY = y + 32;
        drawSummaryRow(g, "Tổng tiền phiếu nhập:", formatMoney(r.getTotalAmount()) + " đ", summaryX + 20, summaryWidth - 40, sumRowY, true, new Color(15, 23, 42));
        sumRowY += 34;
        drawSummaryRow(g, "Số tiền đã thanh toán:", formatMoney(r.getPaidAmount()) + " đ", summaryX + 20, summaryWidth - 40, sumRowY, false, new Color(22, 101, 52));
        sumRowY += 34;
        drawSummaryRow(g, "Công nợ còn lại:", formatMoney(r.getRemainingDebt()) + " đ", summaryX + 20, summaryWidth - 40, sumRowY, true, new Color(185, 28, 28));

        y += summaryHeight + 36;

        // 6. Khu vực chữ ký điện tử
        String signerName = r.getSignedByName() != null && !r.getSignedByName().isBlank()
                ? r.getSignedByName()
                : safe(r.getReceiverName());
        drawSignatureSection(
                g,
                marginX,
                y,
                contentWidth,
                "NGƯỜI GIAO HÀNG / NCC",
                "",
                null,
                null,
                "NGƯỜI LẬP PHIẾU / THỦ KHO",
                signerName,
                r.getSignatureUrl(),
                r.getSignedAt()
        );

        g.dispose();
        return encodeBufferedImageAsPdf(page);
    }

    public static byte[] generateIssuePdf(StockIssueResponseDTO issue) {
        BufferedImage page = new BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = page.createGraphics();
        configureGraphics(g);

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);

        int marginX = 70;
        int contentWidth = CANVAS_WIDTH - (marginX * 2);
        int y = 65;

        // 1. Header phòng khám / chi nhánh
        y = drawClinicHeader(g, issue.getBranchName(), issue.getIssueCode(), y, marginX, contentWidth);

        // 2. Tiêu đề phiếu
        boolean isTransfer = "TRANSFER".equalsIgnoreCase(issue.getIssueType());
        String title = isTransfer ? "PHIẾU ĐIỀU CHUYỂN KHO LIÊN CHI NHÁNH" : "PHIẾU XUẤT KHO VẬT TƯ";

        y += 20;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
        g.setColor(new Color(15, 23, 42));
        drawCenteredString(g, title, CANVAS_WIDTH / 2, y);

        y += 28;
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        g.setColor(new Color(71, 85, 105));
        String dateStr = issue.getIssueDate() != null ? issue.getIssueDate().format(DATE_FMT) : "—";
        drawCenteredString(g, "Mã phiếu: " + safe(issue.getIssueCode()) + "   |   Ngày xuất: " + dateStr, CANVAS_WIDTH / 2, y);

        // 3. Khung thông tin chung (Không hiển thị Nhà cung cấp hay Công nợ trên Phiếu xuất)
        y += 28;
        int infoBoxHeight = isTransfer ? 140 : 110;
        g.setColor(new Color(248, 250, 252));
        g.fillRoundRect(marginX, y, contentWidth, infoBoxHeight, 12, 12);
        g.setColor(new Color(203, 213, 225));
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(marginX, y, contentWidth, infoBoxHeight, 12, 12);

        int col1X = marginX + 24;
        int col2X = marginX + (contentWidth / 2) + 20;
        int infoY = y + 34;

        drawLabelValue(g, "Chi nhánh xuất:", safe(issue.getBranchName()), col1X, infoY);
        drawLabelValue(g, "Loại phiếu xuất:", formatIssueTypeLabel(issue.getIssueType()), col2X, infoY);

        infoY += 36;
        drawLabelValue(g, "Người lập phiếu:", safe(issue.getIssuerName()), col1X, infoY);
        if (isTransfer) {
            drawLabelValue(g, "Chi nhánh nhận:", safe(issue.getDestinationBranchName()), col2X, infoY);
            infoY += 36;
            drawLabelValue(g, "Ghi chú:", safe(issue.getNotes()), col1X, infoY);
        } else {
            drawLabelValue(g, "Ghi chú:", safe(issue.getNotes()), col2X, infoY);
        }

        y += infoBoxHeight + 28;

        // 4. Bảng danh sách hàng hóa xuất
        int[] colWidths = {55, 130, 305, 85, 125, 120, 95, 130, 155};
        String[] headers = {"STT", "Mã VT", "Tên vật tư / hàng hóa", "ĐVT", "Số lô", "Hạn dùng", "SL xuất", "Giá vốn", "Thành tiền"};

        y = drawTableHeader(g, marginX, y, colWidths, headers);

        List<StockIssueResponseDTO.ItemDetail> items = issue.getItems() != null ? issue.getItems() : List.of();
        int rowIdx = 1;
        for (StockIssueResponseDTO.ItemDetail item : items) {
            if (y > CANVAS_HEIGHT - 420) {
                break;
            }
            String[] rowValues = {
                    String.valueOf(rowIdx++),
                    safe(item.getMaterialCode()),
                    truncate(safe(item.getMaterialName()), 33),
                    safe(item.getUnit()),
                    safe(item.getBatchNumber()),
                    item.getExpiryDate() != null ? item.getExpiryDate().format(DATE_FMT) : "—",
                    formatQty(item.getQuantity()),
                    formatMoney(item.getImportPrice()),
                    formatMoney(item.getLineValue())
            };
            y = drawTableRow(g, marginX, y, colWidths, rowValues, rowIdx % 2 == 0);
        }

        // 5. Tổng hợp số lượng & giá trị xuất
        y += 22;
        int summaryWidth = 460;
        int summaryX = marginX + contentWidth - summaryWidth;
        int summaryHeight = 88;

        g.setColor(new Color(248, 250, 252));
        g.fillRoundRect(summaryX, y, summaryWidth, summaryHeight, 10, 10);
        g.setColor(new Color(203, 213, 225));
        g.drawRoundRect(summaryX, y, summaryWidth, summaryHeight, 10, 10);

        int sumRowY = y + 32;
        drawSummaryRow(g, "Tổng số lượng xuất:", formatQty(issue.getTotalQuantity()), summaryX + 20, summaryWidth - 40, sumRowY, false, new Color(15, 23, 42));
        sumRowY += 34;
        drawSummaryRow(g, "Tổng giá trị xuất:", formatMoney(issue.getTotalEstimatedValue()) + " đ", summaryX + 20, summaryWidth - 40, sumRowY, true, new Color(15, 23, 42));

        y += summaryHeight + 36;

        // 6. Khu vực chữ ký điện tử
        String signerName = issue.getSignedByName() != null && !issue.getSignedByName().isBlank()
                ? issue.getSignedByName()
                : safe(issue.getIssuerName());
        drawSignatureSection(
                g,
                marginX,
                y,
                contentWidth,
                isTransfer ? "NGƯỜI NHẬN ĐIỀU CHUYỂN" : "NGƯỜI NHẬN VẬT TƯ",
                "",
                null,
                null,
                "NGƯỜI LẬP PHIẾU XUẤT",
                signerName,
                issue.getSignatureUrl(),
                issue.getSignedAt()
        );

        g.dispose();
        return encodeBufferedImageAsPdf(page);
    }

    private static void configureGraphics(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private static int drawClinicHeader(Graphics2D g, String branchName, String docCode, int y, int marginX, int contentWidth) {
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        g.setColor(new Color(14, 116, 144));
        g.drawString("PHÒNG KHÁM NHA KHOA CƯỜNG VIỆT (CV-DCMS)", marginX, y);

        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        g.setColor(new Color(71, 85, 105));
        String rightText = "Ngày in: " + LocalDateTime.now().format(DATETIME_FMT);
        int rightW = g.getFontMetrics().stringWidth(rightText);
        g.drawString(rightText, marginX + contentWidth - rightW, y);

        y += 24;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        g.setColor(new Color(51, 65, 85));
        g.drawString("Cơ sở: " + safe(branchName), marginX, y);

        y += 16;
        g.setColor(new Color(203, 213, 225));
        g.setStroke(new BasicStroke(1.5f));
        g.drawLine(marginX, y, marginX + contentWidth, y);
        return y + 18;
    }

    private static void drawLabelValue(Graphics2D g, String label, String value, int x, int y) {
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        g.setColor(new Color(71, 85, 105));
        g.drawString(label, x, y);
        int labelW = g.getFontMetrics().stringWidth(label);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        g.setColor(new Color(15, 23, 42));
        g.drawString(value != null && !value.isBlank() ? value : "—", x + labelW + 10, y);
    }

    private static int drawTableHeader(Graphics2D g, int startX, int y, int[] colWidths, String[] headers) {
        int rowHeight = 42;
        int totalW = 0;
        for (int w : colWidths) totalW += w;

        g.setColor(new Color(241, 245, 249));
        g.fillRect(startX, y, totalW, rowHeight);
        g.setColor(new Color(148, 163, 184));
        g.setStroke(new BasicStroke(1.2f));
        g.drawRect(startX, y, totalW, rowHeight);

        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.setColor(new Color(15, 23, 42));

        int curX = startX;
        for (int i = 0; i < headers.length; i++) {
            if (i > 0) {
                g.setColor(new Color(203, 213, 225));
                g.drawLine(curX, y, curX, y + rowHeight);
            }
            g.setColor(new Color(15, 23, 42));
            g.drawString(headers[i], curX + 10, y + 26);
            curX += colWidths[i];
        }
        return y + rowHeight;
    }

    private static int drawTableRow(Graphics2D g, int startX, int y, int[] colWidths, String[] values, boolean striped) {
        int rowHeight = 38;
        int totalW = 0;
        for (int w : colWidths) totalW += w;

        if (striped) {
            g.setColor(new Color(248, 250, 252));
            g.fillRect(startX, y, totalW, rowHeight);
        }
        g.setColor(new Color(203, 213, 225));
        g.setStroke(new BasicStroke(1.0f));
        g.drawRect(startX, y, totalW, rowHeight);

        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        int curX = startX;
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                g.setColor(new Color(226, 232, 240));
                g.drawLine(curX, y, curX, y + rowHeight);
            }
            g.setColor(new Color(30, 41, 59));
            String val = values[i] != null ? values[i] : "";
            if (i >= 6) {
                int textW = g.getFontMetrics().stringWidth(val);
                g.drawString(val, curX + colWidths[i] - textW - 10, y + 24);
            } else {
                g.drawString(val, curX + 10, y + 24);
            }
            curX += colWidths[i];
        }
        return y + rowHeight;
    }

    private static void drawSummaryRow(Graphics2D g, String label, String value, int x, int width, int y, boolean bold, Color valueColor) {
        g.setFont(new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, 16));
        g.setColor(new Color(51, 65, 85));
        g.drawString(label, x, y);

        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
        g.setColor(valueColor);
        int valW = g.getFontMetrics().stringWidth(value);
        g.drawString(value, x + width - valW, y);
    }

    private static void drawSignatureSection(
            Graphics2D g,
            int marginX,
            int y,
            int contentWidth,
            String leftTitle,
            String leftSignerName,
            String leftSignatureUrl,
            LocalDateTime leftSignedAt,
            String rightTitle,
            String rightSignerName,
            String rightSignatureUrl,
            LocalDateTime rightSignedAt) {

        int halfWidth = contentWidth / 2;
        int leftCenterX = marginX + (halfWidth / 2);
        int rightCenterX = marginX + halfWidth + (halfWidth / 2);

        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        g.setColor(new Color(15, 23, 42));
        drawCenteredString(g, leftTitle, leftCenterX, y);
        drawCenteredString(g, rightTitle, rightCenterX, y);

        g.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 13));
        g.setColor(new Color(100, 116, 139));
        drawCenteredString(g, "(Ký và ghi rõ họ tên)", leftCenterX, y + 22);
        drawCenteredString(g, "(Chữ ký điện tử & họ tên)", rightCenterX, y + 22);

        int sigBoxY = y + 36;
        int sigBoxW = 240;
        int sigBoxH = 110;

        BufferedImage rightSigImg = loadSignatureImage(rightSignatureUrl);
        if (rightSigImg != null) {
            int drawX = rightCenterX - (sigBoxW / 2);
            g.drawImage(rightSigImg, drawX, sigBoxY, sigBoxW, sigBoxH, null);
        } else {
            g.setColor(new Color(226, 232, 240));
            g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{4, 4}, 0));
            g.drawRoundRect(rightCenterX - (sigBoxW / 2), sigBoxY, sigBoxW, sigBoxH, 8, 8);
        }

        int nameY = sigBoxY + sigBoxH + 28;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        g.setColor(new Color(15, 23, 42));
        if (leftSignerName != null && !leftSignerName.isBlank()) {
            drawCenteredString(g, leftSignerName, leftCenterX, nameY);
        }
        if (rightSignerName != null && !rightSignerName.isBlank()) {
            drawCenteredString(g, rightSignerName, rightCenterX, nameY);
        }

        if (rightSignedAt != null) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            g.setColor(new Color(71, 85, 105));
            drawCenteredString(g, "Đã ký điện tử: " + rightSignedAt.format(DATETIME_FMT), rightCenterX, nameY + 22);
        }
    }

    private static BufferedImage loadSignatureImage(String signatureUrl) {
        if (signatureUrl == null || signatureUrl.isBlank()) {
            return null;
        }
        try {
            String rel = signatureUrl.trim();
            if (rel.startsWith("/")) {
                rel = rel.substring(1);
            }
            Path p = Paths.get(rel).toAbsolutePath().normalize();
            if (Files.exists(p) && Files.isRegularFile(p)) {
                return ImageIO.read(p.toFile());
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void drawCenteredString(Graphics2D g, String text, int centerX, int y) {
        if (text == null) return;
        int w = g.getFontMetrics().stringWidth(text);
        g.drawString(text, centerX - (w / 2), y);
    }

    /**
     * Đóng gói ảnh trang A4 độ phân giải cao thành file PDF 1.4 chuẩn nhị phân.
     */
    private static byte[] encodeBufferedImageAsPdf(BufferedImage image) {
        try {
            ByteArrayOutputStream jpegOut = new ByteArrayOutputStream();
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (writers.hasNext()) {
                ImageWriter writer = writers.next();
                ImageWriteParam param = writer.getDefaultWriteParam();
                if (param.canWriteCompressed()) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(0.92f);
                }
                try (ImageOutputStream ios = ImageIO.createImageOutputStream(jpegOut)) {
                    writer.setOutput(ios);
                    writer.write(null, new IIOImage(image, null, null), param);
                } finally {
                    writer.dispose();
                }
            } else {
                ImageIO.write(image, "jpg", jpegOut);
            }
            byte[] jpegBytes = jpegOut.toByteArray();

            ByteArrayOutputStream pdf = new ByteArrayOutputStream(jpegBytes.length + 2048);
            List<Integer> offsets = new ArrayList<>();

            writeAscii(pdf, "%PDF-1.4\n%\u00E2\u00E3\u00CF\u00D3\n");

            // Object 1: Catalog
            offsets.add(pdf.size());
            writeAscii(pdf, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

            // Object 2: Pages
            offsets.add(pdf.size());
            writeAscii(pdf, "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");

            // Object 3: Page (A4: 595 x 842 pt)
            offsets.add(pdf.size());
            writeAscii(pdf, "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 "
                    + PDF_PAGE_WIDTH + " " + PDF_PAGE_HEIGHT
                    + "] /Resources << /XObject << /Im0 4 0 R >> >> /Contents 5 0 R >>\nendobj\n");

            // Object 4: XObject Image (DCTDecode JPEG)
            offsets.add(pdf.size());
            writeAscii(pdf, "4 0 obj\n<< /Type /XObject /Subtype /Image /Width "
                    + image.getWidth() + " /Height " + image.getHeight()
                    + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length "
                    + jpegBytes.length + " >>\nstream\n");
            pdf.write(jpegBytes);
            writeAscii(pdf, "\nendstream\nendobj\n");

            // Object 5: Content stream
            String contentStream = "q\n" + PDF_PAGE_WIDTH + " 0 0 " + PDF_PAGE_HEIGHT + " 0 0 cm\n/Im0 Do\nQ\n";
            byte[] contentBytes = contentStream.getBytes(StandardCharsets.US_ASCII);
            offsets.add(pdf.size());
            writeAscii(pdf, "5 0 obj\n<< /Length " + contentBytes.length + " >>\nstream\n");
            pdf.write(contentBytes);
            writeAscii(pdf, "endstream\nendobj\n");

            int xrefOffset = pdf.size();
            writeAscii(pdf, "xref\n0 " + (offsets.size() + 1) + "\n");
            writeAscii(pdf, "0000000000 65535 f \n");
            for (int off : offsets) {
                writeAscii(pdf, String.format(Locale.ROOT, "%010d 00000 n \n", off));
            }
            writeAscii(pdf, "trailer\n<< /Size " + (offsets.size() + 1) + " /Root 1 0 R >>\n");
            writeAscii(pdf, "startxref\n" + xrefOffset + "\n%%EOF");

            return pdf.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Không thể khởi tạo file PDF phiếu kho: " + ex.getMessage(), ex);
        }
    }

    private static void writeAscii(ByteArrayOutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String formatMoney(BigDecimal val) {
        if (val == null) return "0";
        NumberFormat nf = NumberFormat.getNumberInstance(new Locale("vi", "VN"));
        nf.setMaximumFractionDigits(0);
        return nf.format(val);
    }

    private static String formatQty(BigDecimal val) {
        if (val == null) return "0";
        return val.stripTrailingZeros().toPlainString();
    }

    private static String formatIssueTypeLabel(String issueType) {
        if (issueType == null) return "Xuất sử dụng nội bộ";
        return switch (issueType.toUpperCase(Locale.ROOT)) {
            case "TRANSFER" -> "Điều chuyển liên chi nhánh";
            case "DISCARD_EXPIRED" -> "Xuất hủy hàng hết hạn";
            default -> "Xuất sử dụng nội bộ";
        };
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "—" : s.trim();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 1) + "…";
    }
}
