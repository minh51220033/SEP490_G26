package com.cvdcms.controller;

import com.cvdcms.DTO.StockReceiptCreateDTO;
import com.cvdcms.DTO.StockReceiptResponseDTO;
import com.cvdcms.entity.User;
import com.cvdcms.service.StockReceiptService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * REST Controller quản lý Phiếu nhập kho (Stock Receipt):
 * - Tìm kiếm/lọc lịch sử phiếu nhập kho theo chi nhánh, nhà cung cấp, từ khóa và khoảng ngày.
 * - Xem chi tiết phiếu nhập kho kèm danh sách lô vật tư.
 * - Tạo phiếu nhập kho mới và cập nhật tồn kho + lô hàng.
 */
@RestController
@RequestMapping("/api/inventory/receipts")
public class StockReceiptController {

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "receiptDate",
            "receiptId",
            "receiptCode",
            "totalAmount"
    );

    private final StockReceiptService service;

    public StockReceiptController(StockReceiptService service) {
        this.service = service;
    }

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

    @GetMapping("/{receiptId}")
    public StockReceiptResponseDTO detail(
            @PathVariable Long receiptId,
            HttpSession session) {
        User user = requireUser(session);
        return service.getById(receiptId, user.getUserId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StockReceiptResponseDTO create(
            @Valid @RequestBody StockReceiptCreateDTO dto,
            HttpSession session) {
        User user = requireUser(session);
        return service.create(dto, user.getUserId());
    }

    private User requireUser(HttpSession session) {
        Object value = session != null ? session.getAttribute("loggedInUser") : null;
        if (!(value instanceof User user) || user.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập để sử dụng chức năng kho.");
        }
        return user;
    }

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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        String msg = ex.getReason() != null ? ex.getReason() : "Không thể xử lý yêu cầu phiếu nhập.";
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", msg));
    }
}
