package com.cvdcms.controller;

import com.cvdcms.DTO.StockIssueCreateDTO;
import com.cvdcms.DTO.StockIssueResponseDTO;
import com.cvdcms.entity.User;
import com.cvdcms.service.StockIssueService;
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
import java.util.Map;
import java.util.Set;

/**
 * REST Controller quản lý Phiếu xuất kho (Stock Issue) và Điều chuyển vật tư giữa các chi nhánh (Transfer):
 * - Danh sách lịch sử phiếu xuất nội bộ / hủy hết hạn (/api/inventory/issues).
 * - Danh sách lịch sử điều chuyển kho liên chi nhánh (/api/inventory/transfers).
 * - Xem chi tiết phiếu xuất / điều chuyển và tạo mới giao dịch.
 */
@RestController
@RequestMapping("/api/inventory")
public class StockIssueController {

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "issueDate",
            "issueId",
            "issueCode"
    );

    private final StockIssueService service;

    public StockIssueController(StockIssueService service) {
        this.service = service;
    }

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

    @GetMapping("/issues/{issueId}")
    public StockIssueResponseDTO detail(
            @PathVariable Long issueId,
            HttpSession session) {
        User user = requireUser(session);
        return service.getById(issueId, user.getUserId());
    }

    @PostMapping("/issues")
    @ResponseStatus(HttpStatus.CREATED)
    public StockIssueResponseDTO createIssue(
            @RequestBody StockIssueCreateDTO dto,
            HttpSession session) {
        User user = requireUser(session);
        return service.create(dto, user.getUserId());
    }

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

    private User requireUser(HttpSession session) {
        Object value = session != null ? session.getAttribute("loggedInUser") : null;
        if (!(value instanceof User user) || user.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập để sử dụng chức năng kho.");
        }
        return user;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        String msg = ex.getReason() != null ? ex.getReason() : "Không thể xử lý yêu cầu phiếu xuất / điều chuyển.";
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", msg));
    }
}
