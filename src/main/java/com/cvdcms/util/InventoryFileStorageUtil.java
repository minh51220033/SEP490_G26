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
 * Tiện ích lưu trữ và kiểm tra tính hợp lệ (extension, MIME type, magic bytes, chống path traversal) của:
 * <ul>
 *   <li>Chứng từ hóa đơn nhập kho ({@code uploads/invoices/}) — dùng cho module Quản lý kho.</li>
 *   <li>Chữ ký điện tử PNG từ HTML5 Canvas ({@code uploads/signatures/}) — <strong>TIỆN ÍCH DÙNG CHUNG (SHARED)</strong>
 *       được gọi bởi cả module <strong>EMR (Bệnh án điện tử / Phiếu cam kết)</strong> và module <strong>Finance (Tài chính / Hóa đơn)</strong>.</li>
 * </ul>
 */
public final class InventoryFileStorageUtil {

    /** Dung lượng tối đa cho phép của tệp chứng từ hóa đơn nhập kho (5 MB). */
    public static final long MAX_INVOICE_FILE_SIZE = 5L * 1024L * 1024L; // 5MB
    /** Dung lượng tối đa cho phép của ảnh chữ ký điện tử PNG (2 MB). */
    public static final long MAX_SIGNATURE_BYTES = 2L * 1024L * 1024L;   // 2MB

    /** Tập phần mở rộng tệp hóa đơn được phép tải lên. */
    private static final Set<String> ALLOWED_INVOICE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "webp", "pdf"
    );

    /** Tập MIME type hợp lệ cho tệp hóa đơn nhập kho. */
    private static final Set<String> ALLOWED_INVOICE_MIMES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/webp", "application/pdf"
    );

    private InventoryFileStorageUtil() {
    }

    /**
     * Kiểm tra định dạng, MIME type, magic bytes nhị phân, giới hạn dung lượng (5 MB) và lưu tệp hóa đơn vào {@code uploads/invoices/}.
     * <p>Xử lý: kiểm tra tệp không rỗng &amp; kích thước hợp lệ → trích xuất phần mở rộng và kiểm tra whitelist
     * → kiểm tra MIME type và chữ ký magic bytes chống giả mạo định dạng → sinh tên tệp an toàn ({@code inv_<ts>_<uuid>.<ext>})
     * → kiểm tra chống path traversal và ghi tệp xuống đĩa.</p>
     * <p>Được gọi từ: {@link com.cvdcms.controller.StockReceiptController#uploadInvoice} và {@code createMultipart}.</p>
     *
     * @param file tệp hóa đơn tải lên từ trình duyệt
     * @return đường dẫn công khai dạng {@code /uploads/invoices/<ten_file>}
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
     * Kiểm tra tính hợp lệ của đường dẫn tệp hóa đơn đính kèm khi tạo phiếu nhập kho.
     * <p>Xử lý: bắt buộc không rỗng, độ dài &lt;= 500, không chứa {@code ..} hoặc {@code \}, phải bắt đầu bằng
     * {@code /uploads/invoices/} và có phần mở rộng nằm trong {@link #ALLOWED_INVOICE_EXTENSIONS}.</p>
     * <p>Được gọi từ: {@link com.cvdcms.service.impl.StockReceiptServiceImpl#create}.</p>
     *
     * @param attachmentUrl đường dẫn URL chứng từ hóa đơn gửi từ client
     * @return đường dẫn đã cắt khoảng trắng và xác thực hợp lệ
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
     * <strong>[TIỆN ÍCH DÙNG CHUNG — SHARED UTILITY]</strong> Giải mã dữ liệu chữ ký Base64 PNG từ HTML5 Canvas,
     * kiểm tra chữ ký có nét vẽ thực sự (không trắng/trong suốt) và lưu thành tệp PNG trong {@code uploads/signatures/}.
     * <p><strong>Lưu ý phạm vi sử dụng:</strong> Mặc dù đặt trong {@code InventoryFileStorageUtil}, phương thức này là
     * <strong>tiện ích dùng chung (SHARED)</strong> được gọi bởi các module <strong>EMR (Bệnh án điện tử / Phiếu đồng thuận)</strong>
     * và <strong>Finance (Tài chính / Xác nhận thanh toán)</strong>; không được xóa hoặc thay đổi chữ ký hàm làm ảnh hưởng các module khác.</p>
     * <p>Xử lý: tách phần Base64 từ Data URL → giải mã nhị phân → kiểm tra dung lượng (64B–2MB) và PNG magic bytes
     * → đọc {@link BufferedImage} kiểm tra kích thước tối thiểu &amp; nét mực qua {@link #hasDrawnStrokes(BufferedImage)}
     * → lưu tệp an toàn chống path traversal vào {@code uploads/signatures/}.</p>
     *
     * @param signatureDataUrl chuỗi Data URL dạng {@code data:image/png;base64,...} hoặc chuỗi Base64 thuần
     * @param prefix           tiền tố tên tệp (ví dụ {@code consent}, {@code invoice}; mặc định {@code sig})
     * @return đường dẫn công khai dạng {@code /uploads/signatures/<prefix>_<ts>_<uuid>.png}
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
