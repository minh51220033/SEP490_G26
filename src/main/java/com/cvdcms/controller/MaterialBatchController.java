package com.cvdcms.controller;

import com.cvdcms.DTO.MaterialBatchDTO;
import com.cvdcms.entity.User;
import com.cvdcms.service.BranchAccessService;
import com.cvdcms.service.MaterialBatchService;

import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST Controller quản lý tra cứu Lô vật tư &amp; Hạn sử dụng theo chi nhánh ({@code /api/inventory/batches}).
 * <ul>
 *   <li>{@code GET /api/inventory/batches}: Tìm kiếm, lọc theo khoảng hạn sử dụng và phân trang lô vật tư.</li>
 *   <li>{@code GET /api/inventory/batches/available}: Lấy danh sách lô còn tồn ({@code quantityRemaining > 0}) sắp theo FEFO.</li>
 * </ul>
 * Được gọi từ {@code loadBatches()} và các luồng chọn lô khi xuất/điều chuyển trong {@code inventory.js}.
 */
@RestController
@RequestMapping("/api/inventory/batches")
public class MaterialBatchController {

    private final MaterialBatchService batchService;

    /** Danh sách whitelist các trường hợp lệ dùng để sắp xếp danh sách lô vật tư. */
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "expiryDate",
            "batchNumber",
            "quantityRemaining",
            "batchId"
    );

    private final BranchAccessService branchAccessService;

    /**
     * Khởi tạo controller với service lô vật tư và service kiểm tra quyền chi nhánh.
     *
     * @param batchService        service truy vấn lô vật tư
     * @param branchAccessService service kiểm tra quyền truy cập chi nhánh
     */
    public MaterialBatchController(
            MaterialBatchService batchService,
            BranchAccessService branchAccessService) {
        this.batchService = batchService;
        this.branchAccessService = branchAccessService;
    }

    /**
     * Tìm kiếm và phân trang danh sách lô vật tư theo chi nhánh và khoảng hạn sử dụng.
     * <p>Endpoint: {@code GET /api/inventory/batches}</p>
     * <p>Xử lý: kiểm tra hợp lệ {@code branchId}, {@code page}, {@code size} → xác thực phiên đăng nhập
     * → kiểm tra quyền truy cập cơ sở qua {@link BranchAccessService#canAccessBranch(Long, Long)}
     * → kiểm tra whitelist {@code sortBy} và {@code direction} → gọi {@link MaterialBatchService#searchBatches}.</p>
     * <p>Được gọi từ: {@code loadBatches()} trong {@code inventory.js} (Tab Lô &amp; Hạn sử dụng).</p>
     *
     * @param branchId   mã cơ sở cần tra cứu (bắt buộc, &gt; 0)
     * @param keyword    từ khóa tìm kiếm theo tên/mã vật tư hoặc số lô (tùy chọn)
     * @param expiryFrom ngày bắt đầu khoảng hạn sử dụng (định dạng ISO yyyy-MM-dd, tùy chọn)
     * @param expiryTo   ngày kết thúc khoảng hạn sử dụng (định dạng ISO yyyy-MM-dd, tùy chọn)
     * @param page       chỉ số trang (mặc định 0)
     * @param size       kích thước trang (1–100, mặc định 10)
     * @param sortBy     trường sắp xếp ({@code expiryDate}, {@code batchNumber}, {@code quantityRemaining}, {@code batchId})
     * @param direction  chiều sắp xếp ({@code asc} hoặc {@code desc})
     * @param session    phiên đăng nhập hiện tại
     * @return trang dữ liệu {@link Page} chứa {@link MaterialBatchDTO}
     */
    @GetMapping
    public Page<MaterialBatchDTO> getBatches(
            @RequestParam Long branchId,
            @RequestParam(required = false) String keyword,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate expiryFrom,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate expiryTo,

            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "expiryDate") String sortBy,
            @RequestParam(defaultValue = "asc") String direction,
            HttpSession session) {

        if (branchId == null || branchId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Chi nhánh không hợp lệ.");
        }

        if (page < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Số trang không được nhỏ hơn 0.");
        }

        if (size < 1 || size > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Kích thước trang phải từ 1 đến 100.");
        }

        Object loggedInUser = session != null ? session.getAttribute("loggedInUser") : null;

        if (!(loggedInUser instanceof User user) || user.getUserId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Vui lòng đăng nhập để tiếp tục.");
        }

        boolean canAccess = branchAccessService.canAccessBranch(
                user.getUserId(),
                branchId
        );

        if (!canAccess) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Bạn không có quyền truy cập kho của chi nhánh này.");
        }

        String normalizedSortBy =
                sortBy == null ? "" : sortBy.trim();

        if (!ALLOWED_SORT_FIELDS.contains(normalizedSortBy)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Trường sắp xếp không hợp lệ.");
        }

        Sort.Direction sortDirection;

        try {
            sortDirection = Sort.Direction.fromString(
                    direction == null ? "asc" : direction.trim()
            );
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Chiều sắp xếp chỉ chấp nhận asc hoặc desc.");
        }

        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(sortDirection, normalizedSortBy)
        );

        return batchService.searchBatches(
                branchId,
                keyword,
                expiryFrom,
                expiryTo,
                pageable
        );
    }

    /**
     * Lấy danh sách các lô vật tư còn tồn kho ({@code quantityRemaining > 0}) tại chi nhánh, sắp xếp theo hạn sử dụng tăng dần (FEFO).
     * <p>Endpoint: {@code GET /api/inventory/batches/available}</p>
     * <p>Xử lý: kiểm tra {@code branchId}, xác thực người dùng, kiểm tra quyền chi nhánh và gọi {@link MaterialBatchService#getBatchesWithStock(Long, Long)}.</p>
     * <p>Được gọi từ: form Xuất kho / Điều chuyển trong {@code inventory.js} khi người dùng chọn lô cụ thể.</p>
     *
     * @param branchId   mã cơ sở (bắt buộc)
     * @param materialId mã vật tư cần lọc (tùy chọn; để {@code null} để lấy toàn bộ lô còn tồn của chi nhánh)
     * @param session    phiên đăng nhập hiện tại
     * @return danh sách {@link MaterialBatchDTO} còn tồn kho
     */
    @GetMapping("/available")
    public List<MaterialBatchDTO> getBatchesWithStock(
            @RequestParam Long branchId,
            @RequestParam(required = false) Long materialId,
            HttpSession session) {

        if (branchId == null || branchId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chi nhánh không hợp lệ.");
        }
        Object loggedInUser = session != null ? session.getAttribute("loggedInUser") : null;
        if (!(loggedInUser instanceof User user) || user.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập để tiếp tục.");
        }
        if (!branchAccessService.canAccessBranch(user.getUserId(), branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền truy cập kho của chi nhánh này.");
        }
        return batchService.getBatchesWithStock(branchId, materialId);
    }

    /**
     * Xử lý ngoại lệ tham số không hợp lệ và trả về HTTP 400 kèm JSON {@code { "message": "..." }}.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }

    /**
     * Xử lý các ngoại lệ phân quyền/HTTP status và trả về JSON {@code { "message": "..." }} đúng mã lỗi.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        String msg = ex.getReason() != null ? ex.getReason() : "Không thể xử lý yêu cầu lô vật tư.";
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", msg));
    }
}
