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

@RestController
@RequestMapping("/api/inventory/batches")
public class MaterialBatchController {

    private final MaterialBatchService batchService;

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "expiryDate",
            "batchNumber",
            "quantityRemaining",
            "batchId"
    );

    private final BranchAccessService branchAccessService;

    public MaterialBatchController(
            MaterialBatchService batchService,
            BranchAccessService branchAccessService) {
        this.batchService = batchService;
        this.branchAccessService = branchAccessService;
    }

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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        String msg = ex.getReason() != null ? ex.getReason() : "Không thể xử lý yêu cầu lô vật tư.";
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", msg));
    }
}
