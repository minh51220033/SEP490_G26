package com.cvdcms.controller;

import com.cvdcms.DTO.StockReceiptCreateDTO;
import com.cvdcms.DTO.StockReceiptResponseDTO;
import com.cvdcms.entity.User;
import com.cvdcms.service.StockReceiptService;
import com.cvdcms.util.InventoryFileStorageUtil;
import com.cvdcms.util.InventoryPdfGenerator;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * REST Controller quản lý Phiếu nhập kho (Stock Receipt):
 * - Tìm kiếm/lọc lịch sử phiếu nhập kho theo chi nhánh, nhà cung cấp, từ khóa và khoảng ngày.
 * - Xem chi tiết phiếu nhập kho kèm danh sách lô vật tư và hóa đơn đính kèm.
 * - Tải lên file ảnh/chứng từ hóa đơn (MultipartFile) và tạo phiếu nhập kho.
 * - Xuất phiếu nhập kho ra file PDF chuẩn A4.
 */
@RestController
@RequestMapping("/api/inventory/receipts")
public class StockReceiptController {

    /** Danh sách whitelist các trường được phép sắp xếp lịch sử phiếu nhập kho. */
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "receiptDate",
            "receiptId",
            "receiptCode",
            "totalAmount"
    );

    private final StockReceiptService service;

    /**
     * Khởi tạo controller với service nghiệp vụ phiếu nhập kho.
     *
     * @param service service xử lý phiếu nhập kho
     */
    public StockReceiptController(StockReceiptService service) {
        this.service = service;
    }

    /**
     * Tìm kiếm và phân trang lịch sử phiếu nhập kho theo chi nhánh, nhà cung cấp, từ khóa và khoảng ngày.
     * <p>Endpoint: {@code GET /api/inventory/receipts}</p>
     * <p>Xử lý: xác thực người dùng → kiểm tra tham số phân trang → chuẩn hóa trường sắp xếp (kèm tie-breaker {@code receiptId})
     * → ủy quyền cho {@link StockReceiptService#search}.</p>
     * <p>Được gọi từ: {@code loadReceiptHistory()} trong {@code inventory.js} (Tab Nhập kho).</p>
     *
     * @param branchId   mã chi nhánh cần xem lịch sử nhập
     * @param supplierId mã nhà cung cấp cần lọc (tùy chọn)
     * @param keyword    từ khóa tìm kiếm mã phiếu/hóa đơn/NCC/ghi chú (tùy chọn)
     * @param fromDate   ngày bắt đầu lọc theo {@code receiptDate} (tùy chọn)
     * @param toDate     ngày kết thúc lọc theo {@code receiptDate} (tùy chọn)
     * @param page       chỉ số trang (mặc định 0)
     * @param size       kích thước trang (1–100)
     * @param sortBy     trường sắp xếp hợp lệ trong {@link #ALLOWED_SORT_FIELDS}
     * @param direction  chiều sắp xếp ({@code asc}/{@code desc})
     * @param session    phiên đăng nhập hiện tại
     * @return trang kết quả {@link StockReceiptResponseDTO}
     */
    @GetMapping
    public Page<StockReceiptResponseDTO> list(
            @RequestParam Long branchId,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "receiptDate") String sortBy,
            @RequestParam(defaultValue = "desc") String direction,
            HttpSession session) {

        User user = requireUser(session);
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tham số phân trang không hợp lệ.");
        }
        String sortField = sortBy != null && ALLOWED_SORT_FIELDS.contains(sortBy.trim())
                ? sortBy.trim()
                : "receiptDate";
        Sort.Direction dir = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(dir, sortField).and(Sort.by(dir, "receiptId")));

        return service.search(branchId, supplierId, keyword, fromDate, toDate, pageable, user.getUserId());
    }

    /**
     * Lấy chi tiết đầy đủ của một phiếu nhập kho kèm danh sách dòng vật tư/lô đã nhập.
     * <p>Endpoint: {@code GET /api/inventory/receipts/{receiptId}}</p>
     * <p>Được gọi từ: {@code viewReceiptDetail()} trong {@code inventory.js} (Modal chi tiết phiếu nhập).</p>
     *
     * @param receiptId mã định danh phiếu nhập kho
     * @param session   phiên đăng nhập hiện tại
     * @return {@link StockReceiptResponseDTO} kèm danh sách {@code items}
     */
    @GetMapping("/{receiptId}")
    public StockReceiptResponseDTO detail(
            @PathVariable Long receiptId,
            HttpSession session) {
        User user = requireUser(session);
        return service.getById(receiptId, user.getUserId());
    }

    /**
     * Tải lên file ảnh hoặc PDF hóa đơn nhà cung cấp trực tiếp từ máy tính.
     * <p>Endpoint: {@code POST /api/inventory/receipts/upload-invoice} ({@code multipart/form-data})</p>
     * <p>Xử lý: xác thực người dùng → gọi {@link InventoryFileStorageUtil#saveInvoiceFile(MultipartFile)}
     * để kiểm tra định dạng/dung lượng và lưu vào thư mục {@code uploads/inventory/invoices/}.</p>
     * <p>Được gọi từ: sự kiện upload hóa đơn trong form Nhập kho của {@code inventory.js}.</p>
     *
     * @param file    tệp hóa đơn tải lên (JPG, PNG, WEBP, PDF)
     * @param session phiên đăng nhập hiện tại
     * @return map chứa {@code attachmentUrl} (đường dẫn công khai) và {@code fileName} (tên tệp gốc)
     */
    @PostMapping(value = "/upload-invoice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> uploadInvoice(
            @RequestParam("file") MultipartFile file,
            HttpSession session) {
        requireUser(session);
        String savedUrl = InventoryFileStorageUtil.saveInvoiceFile(file);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("attachmentUrl", savedUrl);
        result.put("fileName", file.getOriginalFilename() != null ? file.getOriginalFilename() : "");
        return result;
    }

    /**
     * Tạo mới phiếu nhập kho từ payload JSON.
     * <p>Endpoint: {@code POST /api/inventory/receipts} ({@code application/json})</p>
     * <p>Xử lý: validate DTO theo Bean Validation → gọi {@link StockReceiptService#create(StockReceiptCreateDTO, Long)}
     * để sinh mã phiếu, tạo lô vật tư, cộng tồn kho chi nhánh và ghi nhận thanh toán ngay (nếu có).</p>
     * <p>Được gọi từ: {@code submitReceipt()} trong {@code inventory.js}.</p>
     *
     * @param dto     dữ liệu phiếu nhập kho và các dòng vật tư
     * @param session phiên đăng nhập hiện tại
     * @return {@link StockReceiptResponseDTO} của phiếu vừa tạo (HTTP 201 Created)
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public StockReceiptResponseDTO create(
            @Valid @RequestBody StockReceiptCreateDTO dto,
            HttpSession session) {
        User user = requireUser(session);
        return service.create(dto, user.getUserId());
    }

    /**
     * Tạo mới phiếu nhập kho đồng thời đính kèm tệp hóa đơn trong cùng một request {@code multipart/form-data}.
     * <p>Endpoint: {@code POST /api/inventory/receipts} ({@code multipart/form-data})</p>
     * <p>Xử lý: nếu có {@code invoiceFile}, lưu tệp qua {@link InventoryFileStorageUtil#saveInvoiceFile(MultipartFile)}
     * và gán URL vào {@code dto.attachmentUrl}, sau đó gọi {@link StockReceiptService#create(StockReceiptCreateDTO, Long)}.</p>
     *
     * @param dto         phần JSON {@code receipt} chứa thông tin phiếu nhập
     * @param invoiceFile phần tệp {@code invoiceFile} hóa đơn đính kèm (tùy chọn)
     * @param session     phiên đăng nhập hiện tại
     * @return {@link StockReceiptResponseDTO} của phiếu vừa tạo (HTTP 201 Created)
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public StockReceiptResponseDTO createMultipart(
            @RequestPart("receipt") StockReceiptCreateDTO dto,
            @RequestPart(value = "invoiceFile", required = false) MultipartFile invoiceFile,
            HttpSession session) {
        User user = requireUser(session);
        if (invoiceFile != null && !invoiceFile.isEmpty()) {
            String savedUrl = InventoryFileStorageUtil.saveInvoiceFile(invoiceFile);
            dto.setAttachmentUrl(savedUrl);
        }
        return service.create(dto, user.getUserId());
    }

    /**
     * Xuất phiếu nhập kho ra tệp PDF chuẩn A4.
     * <p>Endpoint: {@code GET /api/inventory/receipts/{receiptId}/pdf}</p>
     * <p>Xử lý: truy vấn chi tiết phiếu (kèm kiểm tra quyền chi nhánh) → sinh mảng byte PDF qua
     * {@link InventoryPdfGenerator#generateReceiptPdf(StockReceiptResponseDTO)} → trả về với header
     * {@code Content-Disposition} dạng {@code inline} (xem trực tiếp) hoặc {@code attachment} (tải xuống).</p>
     * <p>Được gọi từ: nút Xuất PDF trong modal chi tiết phiếu nhập và trang {@code print-receipt.html}.</p>
     *
     * @param receiptId mã phiếu nhập kho
     * @param download  {@code true} để ép tải file xuống, {@code false} để mở inline trên trình duyệt
     * @param session   phiên đăng nhập hiện tại
     * @return {@link ResponseEntity} chứa nội dung nhị phân PDF
     */
    @GetMapping(value = "/{receiptId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(
            @PathVariable Long receiptId,
            @RequestParam(defaultValue = "false") boolean download,
            HttpSession session) {
        User user = requireUser(session);
        StockReceiptResponseDTO receipt = service.getById(receiptId, user.getUserId());
        byte[] pdfBytes = InventoryPdfGenerator.generateReceiptPdf(receipt);

        String filename = "PhieuNhapKho_" + (receipt.getReceiptCode() != null ? receipt.getReceiptCode() : receiptId) + ".pdf";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(
                ContentDisposition.builder(download ? "attachment" : "inline")
                        .filename(filename, StandardCharsets.UTF_8)
                        .build()
        );
        return new ResponseEntity<>(pdfBytes, headers, HttpStatus.OK);
    }

    /**
     * Trích xuất và xác thực tài khoản người dùng đang đăng nhập từ {@link HttpSession}.
     * Ném lỗi HTTP 401 nếu chưa đăng nhập hoặc phiên hết hạn.
     *
     * @param session phiên HTTP hiện tại
     * @return thực thể {@link User} đang đăng nhập
     */
    private User requireUser(HttpSession session) {
        Object value = session != null ? session.getAttribute("loggedInUser") : null;
        if (!(value instanceof User user) || user.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập để sử dụng chức năng kho.");
        }
        return user;
    }

    /**
     * Xử lý lỗi Bean Validation ({@code @Valid}) và gom lỗi theo từng trường về JSON {@code { "message", "errors" }}.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Dữ liệu phiếu nhập không hợp lệ.");
        body.put("errors", fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Xử lý lỗi nghiệp vụ tham số không hợp lệ và trả về HTTP 400 kèm JSON {@code { "message": "..." }}.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }

    /**
     * Xử lý các ngoại lệ {@link ResponseStatusException} (401/403/404/400) và trả về JSON {@code { "message": "..." }}.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        String msg = ex.getReason() != null ? ex.getReason() : "Không thể xử lý yêu cầu phiếu nhập.";
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", msg));
    }
}
