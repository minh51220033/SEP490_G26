package com.cvdcms.util;

import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Tiện ích lưu trữ và kiểm tra tính hợp lệ của chứng từ hóa đơn nhập kho (uploads/invoices/)
 * và chữ ký điện tử (uploads/signatures/).
 */
public final class InventoryFileStorageUtil {

    public static final long MAX_INVOICE_FILE_SIZE = 5L * 1024L * 1024L; // 5MB
    public static final long MAX_SIGNATURE_BYTES = 2L * 1024L * 1024L;   // 2MB

    private static final Set<String> ALLOWED_INVOICE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "webp", "pdf"
    );

    private static final Set<String> ALLOWED_INVOICE_MIMES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/webp", "application/pdf"
    );

    private InventoryFileStorageUtil() {
    }

    /**
     * Kiểm tra định dạng, MIME type, magic bytes, kích thước và lưu file hóa đơn vào uploads/invoices/.
     */
    public static String saveInvoiceFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            bad("Vui lòng chọn file ảnh hoặc chứng từ hóa đơn để tải lên.");
        }
        if (file.getSize() <= 0 || file.getSize() > MAX_INVOICE_FILE_SIZE) {
            bad("Kích thước file hóa đơn không hợp lệ (tối đa 5MB).");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            bad("Tên file hóa đơn không hợp lệ.");
        }
        String ext = extractExtension(originalName);
        if (!ALLOWED_INVOICE_EXTENSIONS.contains(ext)) {
            bad("Định dạng file hóa đơn không hỗ trợ. Chỉ chấp nhận .png, .jpg, .jpeg, .webp, .pdf.");
        }

        String contentType = file.getContentType() != null
                ? file.getContentType().trim().toLowerCase(Locale.ROOT)
                : "";
        if (!contentType.isEmpty() && !ALLOWED_INVOICE_MIMES.contains(contentType)) {
            bad("Loại nội dung (MIME type) của file hóa đơn không hợp lệ: " + contentType);
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không thể đọc dữ liệu file hóa đơn.");
        }

        if (!matchesMagicBytes(bytes, ext)) {
            bad("Nội dung file hóa đơn không khớp với định dạng ." + ext + " (file bị hỏng hoặc giả mạo).");
        }

        try {
            Path invoiceDir = Paths.get("uploads", "invoices").toAbsolutePath().normalize();
            Files.createDirectories(invoiceDir);

            String safeFileName = "inv_" + System.currentTimeMillis() + "_"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 8)
                    + "." + ext;
            Path targetPath = invoiceDir.resolve(safeFileName).normalize();
            if (!targetPath.startsWith(invoiceDir)) {
                bad("Đường dẫn lưu file hóa đơn không an toàn.");
            }
            Files.write(targetPath, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return "/uploads/invoices/" + safeFileName;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Lỗi lưu file hóa đơn lên hệ thống: " + ex.getMessage());
        }
    }

    /**
     * Kiểm tra đường dẫn file hóa đơn đã tải lên khi lưu phiếu nhập kho.
     */
    public static String validateRequiredInvoiceUrl(String attachmentUrl) {
        if (attachmentUrl == null || attachmentUrl.isBlank()) {
            bad("Bắt buộc phải tải lên ảnh hoặc chứng từ hóa đơn hợp lệ khi tạo phiếu nhập kho.");
        }
        String trimmed = attachmentUrl.trim();
        if (trimmed.length() > 500) {
            bad("Đường dẫn chứng từ đính kèm tối đa 500 ký tự.");
        }
        if (trimmed.contains("..") || trimmed.contains("\\")) {
            bad("Đường dẫn chứng từ hóa đơn không hợp lệ.");
        }
        if (!trimmed.startsWith("/uploads/invoices/")) {
            bad("Chứng từ hóa đơn phải được tải lên trực tiếp từ máy tính vào hệ thống (/uploads/invoices/...).");
        }
        String ext = extractExtension(trimmed);
        if (!ALLOWED_INVOICE_EXTENSIONS.contains(ext)) {
            bad("Định dạng file hóa đơn đính kèm không hợp lệ (chỉ chấp nhận .png, .jpg, .jpeg, .webp, .pdf).");
        }
        return trimmed;
    }

    /**
     * Giải mã dữ liệu chữ ký Base64 PNG từ HTML5 Canvas, kiểm tra canvas không trắng/rỗng,
     * và lưu thành file PNG trong uploads/signatures/.
     */
    public static String saveSignatureDataUrl(String signatureDataUrl, String prefix) {
        if (signatureDataUrl == null || signatureDataUrl.isBlank()) {
            bad("Vui lòng ký tên lên vùng ký trước khi xác nhận.");
        }
        String trimmed = signatureDataUrl.trim();
        String base64Part = trimmed;
        if (trimmed.startsWith("data:image/")) {
            int commaIdx = trimmed.indexOf(',');
            if (commaIdx < 0 || commaIdx + 1 >= trimmed.length()) {
                bad("Dữ liệu chữ ký điện tử không đúng định dạng ảnh Base64.");
            }
            String header = trimmed.substring(0, commaIdx).toLowerCase(Locale.ROOT);
            if (!header.contains("image/png") && !header.contains("base64")) {
                bad("Chữ ký điện tử phải ở định dạng ảnh PNG.");
            }
            base64Part = trimmed.substring(commaIdx + 1).trim();
        }

        byte[] imageBytes;
        try {
            imageBytes = Base64.getDecoder().decode(base64Part);
        } catch (IllegalArgumentException ex) {
            bad("Mã hóa Base64 của chữ ký điện tử không hợp lệ.");
            return null;
        }

        if (imageBytes == null || imageBytes.length < 64) {
            bad("Dữ liệu ảnh chữ ký điện tử quá ngắn hoặc rỗng.");
        }
        if (imageBytes.length > MAX_SIGNATURE_BYTES) {
            bad("Dung lượng ảnh chữ ký vượt quá giới hạn cho phép (2MB).");
        }
        if (!matchesMagicBytes(imageBytes, "png")) {
            bad("Ảnh chữ ký điện tử không phải định dạng PNG hợp lệ.");
        }

        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (IOException ex) {
            bad("Không thể đọc ảnh chữ ký điện tử.");
            return null;
        }
        if (img == null || img.getWidth() < 20 || img.getHeight() < 15) {
            bad("Kích thước vùng chữ ký điện tử không hợp lệ.");
        }

        if (!hasDrawnStrokes(img)) {
            bad("Chữ ký điện tử đang trống. Vui lòng vẽ chữ ký rõ ràng trước khi lưu.");
        }

        try {
            Path sigDir = Paths.get("uploads", "signatures").toAbsolutePath().normalize();
            Files.createDirectories(sigDir);

            String safePrefix = (prefix == null || prefix.isBlank())
                    ? "sig"
                    : prefix.replaceAll("[^A-Za-z0-9_-]", "").toLowerCase(Locale.ROOT);
            String fileName = safePrefix + "_" + System.currentTimeMillis() + "_"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 8)
                    + ".png";
            Path targetPath = sigDir.resolve(fileName).normalize();
            if (!targetPath.startsWith(sigDir)) {
                bad("Đường dẫn lưu chữ ký không an toàn.");
            }
            Files.write(targetPath, imageBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return "/uploads/signatures/" + fileName;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Không thể lưu file chữ ký điện tử: " + ex.getMessage());
        }
    }

    /**
     * Kiểm tra ảnh chữ ký có nét vẽ thực sự hay chỉ là nền trong suốt / nền trắng đồng nhất.
     */
    private static boolean hasDrawnStrokes(BufferedImage img) {
        int width = img.getWidth();
        int height = img.getHeight();
        int inkPixels = 0;
        for (int y = 0; y < height; y += 2) {
            for (int x = 0; x < width; x += 2) {
                int argb = img.getRGB(x, y);
                int alpha = (argb >> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                // Nét vẽ là pixel có độ đục (alpha > 35) và không phải màu trắng tinh (r,g,b < 240)
                if (alpha > 35 && (r < 240 || g < 240 || b < 240)) {
                    inkPixels++;
                    if (inkPixels >= 15) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String extractExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).trim().toLowerCase(Locale.ROOT);
    }

    private static boolean matchesMagicBytes(byte[] bytes, String ext) {
        if (bytes == null || bytes.length < 4) {
            return false;
        }
        return switch (ext) {
            case "png" -> bytes.length >= 8
                    && (bytes[0] & 0xFF) == 0x89
                    && bytes[1] == 0x50
                    && bytes[2] == 0x4E
                    && bytes[3] == 0x47;
            case "jpg", "jpeg" -> (bytes[0] & 0xFF) == 0xFF
                    && (bytes[1] & 0xFF) == 0xD8
                    && (bytes[2] & 0xFF) == 0xFF;
            case "webp" -> bytes.length >= 12
                    && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            case "pdf" -> bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
            default -> false;
        };
    }

    private static void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
