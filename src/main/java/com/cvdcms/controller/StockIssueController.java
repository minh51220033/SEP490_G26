package com.cvdcms.controller;

import com.cvdcms.DTO.StockIssueCreateDTO;
import com.cvdcms.DTO.StockIssueResponseDTO;
import com.cvdcms.entity.User;
import com.cvdcms.service.StockIssueService;
import com.cvdcms.util.InventoryPdfGenerator;
import jakarta.servlet.http.HttpSession;
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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * REST Controller quản lý Phiếu xuất kho (Stock Issue) và Điều chuyển vật tư giữa các chi nhánh (Transfer):
 * - Danh sách lịch sử phiếu xuất nội bộ / hủy hết hạn (/api/inventory/issues).
 * - Danh sách lịch sử điều chuyển kho liên chi nhánh (/api/inventory/transfers).
 * - Xem chi tiết phiếu xuất / điều chuyển, tạo mới giao dịch và xuất file PDF chuẩn A4.
 */
@RestController
@RequestMapping("/api/inventory")
public class StockIssueController {

    /** Danh sách whitelist các trường được phép sắp xếp lịch sử phiếu xuất / điều chuyển. */
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "issueDate",
            "issueId",
            "issueCode"
    );

    private final StockIssueService service;

    /**
     * Khởi tạo controller với service nghiệp vụ xuất kho và điều chuyển liên chi nhánh.
     *
     * @param service service xử lý phiếu xuất kho / điều chuyển
     */
    public StockIssueController(StockIssueService service) {
        this.service = service;
    }

    /**
     * Tìm kiếm và phân trang lịch sử phiếu xuất kho (nội bộ / hủy hết hạn / điều chỉnh).
     * <p>Endpoint: {@code GET /api/inventory/issues}</p>
     * <p>Xử lý: xác thực phiên đăng nhập → xây dựng {@link Pageable} hợp lệ → gọi
     * {@link StockIssueService#searchIssues} (mặc định loại trừ loại {@code TRANSFER} khi {@code excludeTransfer=true}).</p>
     * <p>Được gọi từ: {@code loadIssueHistory()} trong {@code inventory.js} (Tab Xuất kho).</p>
     *
     * @param branchId        mã chi nhánh xuất kho
     * @param issueType       loại phiếu xuất cần lọc (tùy chọn: {@code INTERNAL_USE}, {@code EXPIRED_DISPOSAL}, ...)
     * @param excludeTransfer {@code true} để ẩn phiếu điều chuyển khỏi bảng lịch sử xuất kho
     * @param keyword         từ khóa tìm kiếm mã phiếu, lý do, ghi chú (tùy chọn)
     * @param fromDate        ngày bắt đầu lọc (tùy chọn)
     * @param toDate          ngày kết thúc lọc (tùy chọn)
     * @param page            chỉ số trang (mặc định 0)
     * @param size            số dòng mỗi trang (1–100)
     * @param sortBy          trường sắp xếp ({@code issueDate}, {@code issueId}, {@code issueCode})
     * @param direction       chiều sắp xếp ({@code asc}/{@code desc})
     * @param session         phiên đăng nhập hiện tại
     * @return trang dữ liệu {@link StockIssueResponseDTO}
     */
    @GetMapping("/issues")
    public Page<StockIssueResponseDTO> listIssues(
            @RequestParam Long branchId,
            @RequestParam(required = false) String issueType,
            @RequestParam(defaultValue = "true") boolean excludeTransfer,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "issueDate") String sortBy,
            @RequestParam(defaultValue = "desc") String direction,
            HttpSession session) {

        User user = requireUser(session);
        Pageable pageable = buildPageable(page, size, sortBy, direction);
        return service.searchIssues(branchId, issueType, excludeTransfer, keyword, fromDate, toDate, pageable, user.getUserId());
    }

    /**
     * Tìm kiếm và phân trang lịch sử điều chuyển vật tư liên chi nhánh ({@code issueType = TRANSFER}).
     * <p>Endpoint: {@code GET /api/inventory/transfers}</p>
     * <p>Xử lý: xác thực người dùng → tạo {@link Pageable} → gọi {@link StockIssueService#searchTransfers}
     * (bao gồm các phiếu mà chi nhánh hiện tại là chi nhánh xuất hoặc chi nhánh nhận).</p>
     * <p>Được gọi từ: {@code loadTransferHistory()} trong {@code inventory.js} (Tab Điều chuyển).</p>
     *
     * @param branchId            mã chi nhánh đang xem
     * @param destinationBranchId mã chi nhánh nhận cần lọc thêm (tùy chọn)
     * @param keyword             từ khóa tìm kiếm mã phiếu/lý do/ghi chú (tùy chọn)
     * @param fromDate            ngày bắt đầu lọc (tùy chọn)
     * @param toDate              ngày kết thúc lọc (tùy chọn)
     * @param page                chỉ số trang
     * @param size                số bản ghi mỗi trang
     * @param sortBy              trường sắp xếp
     * @param direction           chiều sắp xếp
     * @param session             phiên đăng nhập hiện tại
     * @return trang dữ liệu {@link StockIssueResponseDTO} loại điều chuyển
     */
    @GetMapping("/transfers")
    public Page<StockIssueResponseDTO> listTransfers(
            @RequestParam Long branchId,
            @RequestParam(required = false) Long destinationBranchId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "issueDate") String sortBy,
            @RequestParam(defaultValue = "desc") String direction,
            HttpSession session) {

        User user = requireUser(session);
        Pageable pageable = buildPageable(page, size, sortBy, direction);
        return service.searchTransfers(branchId, destinationBranchId, keyword, fromDate, toDate, pageable, user.getUserId());
    }

    /**
     * Lấy chi tiết đầy đủ của một phiếu xuất kho hoặc phiếu điều chuyển kèm các dòng lô đã xuất.
     * <p>Endpoint: {@code GET /api/inventory/issues/{issueId}}</p>
     * <p>Được gọi từ: {@code viewIssueDetail()} trong {@code inventory.js}.</p>
     *
     * @param issueId mã định danh phiếu xuất / điều chuyển
     * @param session phiên đăng nhập hiện tại
     * @return {@link StockIssueResponseDTO} kèm danh sách {@code items}
     */
    @GetMapping("/issues/{issueId}")
    public StockIssueResponseDTO detail(
            @PathVariable Long issueId,
            HttpSession session) {
        User user = requireUser(session);
        return service.getById(issueId, user.getUserId());
    }

    /**
     * Tạo mới phiếu xuất kho (nội bộ, xuất hủy hết hạn, v.v.) và tự động khấu trừ tồn kho theo FEFO/lô chỉ định.
     * <p>Endpoint: {@code POST /api/inventory/issues}</p>
     * <p>Được gọi từ: {@code submitIssue()} trong {@code inventory.js}.</p>
     *
     * @param dto     dữ liệu phiếu xuất kho và danh sách vật tư cần xuất
     * @param session phiên đăng nhập hiện tại
     * @return {@link StockIssueResponseDTO} của phiếu vừa tạo (HTTP 201 Created)
     */
    @PostMapping("/issues")
    @ResponseStatus(HttpStatus.CREATED)
    public StockIssueResponseDTO createIssue(
            @RequestBody StockIssueCreateDTO dto,
            HttpSession session) {
        User user = requireUser(session);
        return service.create(dto, user.getUserId());
    }

    /**
     * Tạo mới phiếu điều chuyển vật tư giữa hai chi nhánh.
     * <p>Endpoint: {@code POST /api/inventory/transfers}</p>
     * <p>Xử lý: ép {@code dto.issueType = "TRANSFER"} rồi gọi {@link StockIssueService#create(StockIssueCreateDTO, Long)}
     * để trừ kho nguồn theo FEFO và tự động cộng lô/tồn kho tương ứng vào kho đích.</p>
     * <p>Được gọi từ: {@code submitTransfer()} trong {@code inventory.js}.</p>
     *
     * @param dto     dữ liệu phiếu điều chuyển (gồm chi nhánh nguồn, chi nhánh đích và danh sách vật tư)
     * @param session phiên đăng nhập hiện tại
     * @return {@link StockIssueResponseDTO} của phiếu điều chuyển vừa tạo (HTTP 201 Created)
     */
    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public StockIssueResponseDTO createTransfer(
            @RequestBody StockIssueCreateDTO dto,
            HttpSession session) {
        User user = requireUser(session);
        if (dto != null) {
            dto.setIssueType("TRANSFER");
        }
        return service.create(dto, user.getUserId());
    }

    /**
     * Xuất phiếu xuất kho hoặc phiếu điều chuyển kho ra tệp PDF chuẩn A4.
     * <p>Endpoint: {@code GET /api/inventory/issues/{issueId}/pdf} và {@code GET /api/inventory/transfers/{issueId}/pdf}</p>
     * <p>Xử lý: truy vấn chi tiết phiếu → sinh PDF qua {@link InventoryPdfGenerator#generateIssuePdf(StockIssueResponseDTO)}
     * → đặt tên file theo tiền tố {@code PhieuDieuChuyen_} hoặc {@code PhieuXuatKho_} và trả về luồng PDF.</p>
     * <p>Được gọi từ: trang {@code print-issue.html}, {@code print-transfer.html} và modal chi tiết trong {@code inventory.js}.</p>
     *
     * @param issueId  mã phiếu xuất / điều chuyển
     * @param download {@code true} để tải về máy, {@code false} để xem inline
     * @param session  phiên đăng nhập hiện tại
     * @return {@link ResponseEntity} chứa mảng byte PDF
     */
    @GetMapping(value = {"/issues/{issueId}/pdf", "/transfers/{issueId}/pdf"}, produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(
            @PathVariable Long issueId,
            @RequestParam(defaultValue = "false") boolean download,
            HttpSession session) {
        User user = requireUser(session);
        StockIssueResponseDTO issue = service.getById(issueId, user.getUserId());
        byte[] pdfBytes = InventoryPdfGenerator.generateIssuePdf(issue);

        String prefix = "TRANSFER".equalsIgnoreCase(issue.getIssueType()) ? "PhieuDieuChuyen_" : "PhieuXuatKho_";
        String filename = prefix + (issue.getIssueCode() != null ? issue.getIssueCode() : issueId) + ".pdf";
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
     * Kiểm tra tham số phân trang và xây dựng đối tượng {@link Pageable} với whitelist trường sắp xếp và tie-breaker {@code issueId}.
     */
    private Pageable buildPageable(int page, int size, String sortBy, String direction) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tham số phân trang không hợp lệ.");
        }
        String sortField = sortBy != null && ALLOWED_SORT_FIELDS.contains(sortBy.trim())
                ? sortBy.trim()
                : "issueDate";
        Sort.Direction dir = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return PageRequest.of(page, size, Sort.by(dir, sortField).and(Sort.by(dir, "issueId")));
    }

    /**
     * Lấy tài khoản người dùng đang đăng nhập từ {@link HttpSession}; ném HTTP 401 nếu chưa đăng nhập.
     */
    private User requireUser(HttpSession session) {
        Object value = session != null ? session.getAttribute("loggedInUser") : null;
        if (!(value instanceof User user) || user.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập để sử dụng chức năng kho.");
        }
        return user;
    }

    /**
     * Xử lý lỗi tham số/nghiệp vụ không hợp lệ và trả về HTTP 400 kèm JSON {@code { "message": "..." }}.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }

    /**
     * Xử lý các ngoại lệ {@link ResponseStatusException} và trả về JSON {@code { "message": "..." }}.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        String msg = ex.getReason() != null ? ex.getReason() : "Không thể xử lý yêu cầu phiếu xuất / điều chuyển.";
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", msg));
    }
}
