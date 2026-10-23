package com.cvdcms.controller;

import com.cvdcms.DTO.InventoryItemDTO;
import com.cvdcms.DTO.SupplierDebtSummaryDTO;
import com.cvdcms.entity.User;
import com.cvdcms.repository.BranchInventoryRepository;
import com.cvdcms.repository.MaterialBatchRepository;
import com.cvdcms.service.BranchAccessService;
import com.cvdcms.service.BranchInventoryService;
import com.cvdcms.service.SupplierPaymentService;

import jakarta.servlet.http.HttpSession;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * API xem danh sách tồn kho và chỉ số KPI tổng quan kho theo cơ sở.
 */
@RestController
@RequestMapping("/api/inventory")
public class BranchInventoryController {

    private final BranchInventoryService inventoryService;
    private final BranchAccessService branchAccessService;
    private final BranchInventoryRepository inventoryRepository;
    private final MaterialBatchRepository batchRepository;
    private final SupplierPaymentService supplierPaymentService;

    // Chỉ cho phép sắp xếp theo các trường đã khai báo.
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "inventoryId",
            "currentStock",
            "lastUpdated"
    );

    public BranchInventoryController(
            BranchInventoryService inventoryService,
            BranchAccessService branchAccessService,
            BranchInventoryRepository inventoryRepository,
            MaterialBatchRepository batchRepository,
            SupplierPaymentService supplierPaymentService) {

        this.inventoryService = inventoryService;
        this.branchAccessService = branchAccessService;
        this.inventoryRepository = inventoryRepository;
        this.batchRepository = batchRepository;
        this.supplierPaymentService = supplierPaymentService;
    }

    /**
     * Lấy danh sách tồn kho của một cơ sở.
     * Chỉ tài khoản có quyền với cơ sở được yêu cầu mới được truy cập.
     */
    @GetMapping
    public Page<InventoryItemDTO> getInventory(
            @RequestParam Long branchId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean lowStock,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "lastUpdated") String sortBy,
            @RequestParam(defaultValue = "desc") String direction,
            HttpSession session) {

        // 1. Kiểm tra session đăng nhập.
        Object loggedIn = session.getAttribute("loggedInUser");

        if (!(loggedIn instanceof User user)
                || user.getUserId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Phiên đăng nhập không hợp lệ. Vui lòng đăng nhập lại."
            );
        }

        // 2. Kiểm tra quyền truy cập cơ sở từ database.
        if (!branchAccessService.canAccessBranch(
                user.getUserId(), branchId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Bạn không có quyền truy cập cơ sở này."
            );
        }

        // 3. Kiểm tra tham số phân trang.
        if (page < 0) {
            throw new IllegalArgumentException(
                    "Số trang không được nhỏ hơn 0."
            );
        }

        if (size < 1 || size > 100) {
            throw new IllegalArgumentException(
                    "Kích thước trang phải từ 1 đến 100."
            );
        }

        // 4. Kiểm tra trường sắp xếp.
        String normalizedSortBy =
                sortBy == null ? "" : sortBy.trim();

        if (!ALLOWED_SORT_FIELDS.contains(normalizedSortBy)) {
            throw new IllegalArgumentException(
                    "Trường sắp xếp không hợp lệ."
            );
        }

        // 5. Kiểm tra chiều sắp xếp.
        Sort.Direction sortDirection;

        try {
            sortDirection = Sort.Direction.fromString(
                    direction == null ? "desc" : direction.trim()
            );
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Chiều sắp xếp chỉ chấp nhận asc hoặc desc."
            );
        }

        // 6. Tạo thông tin phân trang và sắp xếp.
        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(sortDirection, normalizedSortBy)
        );

        // 7. Truy vấn tồn kho sau khi đã kiểm tra quyền.
        return inventoryService.search(
                branchId,
                keyword,
                lowStock,
                pageable
        );
    }

    /**
     * Lấy 4 chỉ số KPI tổng quan của kho chi nhánh (Dashboard Summary Cards):
     * 1. Mặt hàng trong kho (totalItems, outOfStockItems)
     * 2. Cảnh báo sắp hết / chạm ngưỡng (lowStockItems)
     * 3. Lô hết hạn / sắp hết hạn còn tồn (expiringOrExpiredBatches, expiredBatches, expiringSoonBatches)
     * 4. Tổng công nợ NCC tại chi nhánh (totalSupplierDebt, suppliersWithDebtCount)
     */
    @GetMapping("/summary")
    public Map<String, Object> getInventorySummary(
            @RequestParam Long branchId,
            HttpSession session) {

        // 1. Kiểm tra phiên đăng nhập hợp lệ từ HttpSession.
        Object loggedIn = session != null ? session.getAttribute("loggedInUser") : null;
        if (!(loggedIn instanceof User user) || user.getUserId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Phiên đăng nhập không hợp lệ. Vui lòng đăng nhập lại."
            );
        }

        // 2. Kiểm tra tham số chi nhánh và quyền truy cập kho chi nhánh.
        if (branchId == null || branchId <= 0) {
            throw new IllegalArgumentException("Chi nhánh không hợp lệ.");
        }

        if (!branchAccessService.canAccessBranch(user.getUserId(), branchId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Bạn không có quyền truy cập cơ sở này."
            );
        }

        // 3. Tính toán các chỉ số tồn kho và cảnh báo hạn sử dụng thực tế trong database.
        LocalDate today = LocalDate.now();
        LocalDate thresholdDate = today.plusDays(30);

        long totalItems = inventoryRepository.countByBranch_BranchId(branchId);
        long lowStockItems = inventoryRepository.countLowStockByBranch(branchId);
        long outOfStockItems = inventoryRepository.countOutOfStockByBranch(branchId);

        long expiredBatches = batchRepository.countExpiredBatchesWithStock(branchId, today);
        long expiringSoonBatches = batchRepository.countExpiringSoonBatchesWithStock(branchId, today, thresholdDate);
        long expiringOrExpiredBatches = expiredBatches + expiringSoonBatches;

        // 4. Tính tổng công nợ nhà cung cấp còn tồn đọng tại chi nhánh.
        List<SupplierDebtSummaryDTO> debts = supplierPaymentService.getDebtSummaries(branchId, null, user.getUserId());
        BigDecimal totalSupplierDebt = BigDecimal.ZERO;
        long suppliersWithDebtCount = 0L;
        for (SupplierDebtSummaryDTO d : debts) {
            BigDecimal rem = d.getRemainingDebt() != null ? d.getRemainingDebt() : BigDecimal.ZERO;
            if (rem.compareTo(BigDecimal.ZERO) > 0) {
                totalSupplierDebt = totalSupplierDebt.add(rem);
                suppliersWithDebtCount++;
            }
        }

        // 5. Đóng gói dữ liệu trả về cho 4 KPI Cards ở đầu trang Quản lý kho.
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("branchId", branchId);
        summary.put("totalItems", totalItems);
        summary.put("lowStockItems", lowStockItems);
        summary.put("outOfStockItems", outOfStockItems);
        summary.put("expiringOrExpiredBatches", expiringOrExpiredBatches);
        summary.put("expiredBatches", expiredBatches);
        summary.put("expiringSoonBatches", expiringSoonBatches);
        summary.put("totalSupplierDebt", totalSupplierDebt);
        summary.put("suppliersWithDebtCount", suppliersWithDebtCount);
        return summary;
    }

    /**
     * Trả về thông báo lỗi dạng JSON { "message": "..." } khi tham số đầu vào không hợp lệ.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }

    /**
     * Trả về thông báo lỗi dạng JSON { "message": "..." } cho các ngoại lệ phân quyền / nghiệp vụ.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        String msg = ex.getReason() != null ? ex.getReason() : "Yêu cầu không hợp lệ.";
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", msg));
    }
}
