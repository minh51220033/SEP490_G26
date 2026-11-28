package com.cvdcms.controller;

import com.cvdcms.DTO.InventoryItemDTO;
import com.cvdcms.DTO.SupplierDebtSummaryDTO;
import com.cvdcms.entity.BranchInventory;
import com.cvdcms.entity.Material;
import com.cvdcms.entity.MaterialBatch;
import com.cvdcms.entity.User;
import com.cvdcms.repository.BranchInventoryRepository;
import com.cvdcms.repository.MaterialBatchRepository;
import com.cvdcms.repository.MaterialRepository;
import com.cvdcms.service.BranchAccessService;
import com.cvdcms.service.BranchInventoryService;
import com.cvdcms.service.SupplierPaymentService;

import jakarta.servlet.http.HttpSession;

import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * REST Controller cung cấp các API tra cứu tồn kho chi nhánh ({@code /api/inventory}):
 * <ul>
 *   <li>{@code GET /api/inventory}: Danh sách tồn kho phân trang, tìm kiếm và lọc sắp hết hàng.</li>
 *   <li>{@code GET /api/inventory/picker-materials}: Danh sách vật tư phục vụ Modal chọn nhiều vật tư (Material Picker).</li>
 *   <li>{@code GET /api/inventory/summary}: Tổng hợp 4 chỉ số KPI đầu trang Quản lý kho.</li>
 * </ul>
 * Được gọi từ các hàm {@code loadInventory()}, {@code loadPickerMaterials()} và {@code updateSummaryStats()} trong {@code inventory.js}.
 */
@RestController
@RequestMapping("/api/inventory")
public class BranchInventoryController {

    private final BranchInventoryService inventoryService;
    private final BranchAccessService branchAccessService;
    private final BranchInventoryRepository inventoryRepository;
    private final MaterialBatchRepository batchRepository;
    private final SupplierPaymentService supplierPaymentService;

    @Autowired(required = false)
    private MaterialRepository materialRepository;

    /** Danh sách whitelist các trường được phép dùng để sắp xếp kết quả tồn kho. */
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "inventoryId",
            "currentStock",
            "lastUpdated"
    );

    /**
     * Khởi tạo controller với các service/repository tồn kho, phân quyền cơ sở, lô vật tư và công nợ nhà cung cấp.
     */
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
     * Lấy danh sách tồn kho phân trang của một cơ sở.
     * <p>Endpoint: {@code GET /api/inventory}</p>
     * <p>Xử lý: xác thực phiên đăng nhập → kiểm tra quyền truy cập chi nhánh qua {@link BranchAccessService}
     * → kiểm tra hợp lệ tham số phân trang ({@code page >= 0}, {@code 1 <= size <= 100}), whitelist trường sắp xếp và chiều sắp xếp
     * → gọi {@link BranchInventoryService#search(Long, String, Boolean, Pageable)}.</p>
     * <p>Được gọi từ: {@code loadInventory()} trong {@code inventory.js} (Tab Tồn kho hiện tại).</p>
     *
     * @param branchId  mã cơ sở cần xem tồn kho (bắt buộc)
     * @param keyword   từ khóa tìm kiếm theo mã/tên/nhóm vật tư (tùy chọn)
     * @param lowStock  {@code true} để chỉ lấy mặt hàng chạm ngưỡng tối thiểu (tùy chọn)
     * @param page      chỉ số trang (bắt đầu từ 0)
     * @param size      số bản ghi mỗi trang (1–100)
     * @param sortBy    trường sắp xếp ({@code inventoryId}, {@code currentStock}, {@code lastUpdated})
     * @param direction chiều sắp xếp ({@code asc} hoặc {@code desc})
     * @param session   phiên đăng nhập hiện tại
     * @return trang dữ liệu {@link Page} chứa {@link InventoryItemDTO}
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
     * Cung cấp dữ liệu vật tư kèm tồn kho thực tế, tồn khả dụng (chưa hết hạn), tồn hết hạn và hạn sử dụng gần nhất
     * phục vụ Modal chọn nhiều vật tư (Material Picker Modal) cho Phiếu nhập kho, Phiếu xuất kho và Điều chuyển kho.
     * <p>Endpoint: {@code GET /api/inventory/picker-materials}</p>
     * <p>Xử lý: xác thực phiên đăng nhập và quyền chi nhánh → tải toàn bộ vật tư đang hoạt động, tồn kho chi nhánh và các lô còn tồn
     * → phân tách tồn khả dụng/hết hạn và hạn gần nhất theo từng vật tư → lọc theo {@code category}, {@code keyword}, {@code stockFilter}
     * → cắt trang thủ công và trả kèm danh sách {@code categories} không trùng lặp.</p>
     * <p>Được gọi từ: {@code loadPickerMaterials()} trong {@code inventory.js}.</p>
     *
     * @param branchId    mã chi nhánh cần tra cứu tồn kho
     * @param keyword     từ khóa tìm kiếm mã/tên/nhóm/đơn vị vật tư (tùy chọn)
     * @param category    nhóm vật tư cần lọc hoặc {@code ALL} (tùy chọn)
     * @param stockFilter chế độ lọc tồn kho ({@code ALL}, {@code IN_STOCK}, {@code AVAILABLE_STOCK}, {@code LOW_STOCK}, {@code EXPIRED_ONLY})
     * @param page        chỉ số trang (mặc định 0)
     * @param size        số bản ghi mỗi trang (1–100, mặc định 20)
     * @param session     phiên đăng nhập hiện tại
     * @return map gồm {@code content}, {@code page}, {@code size}, {@code totalElements}, {@code totalPages}, {@code hasMore}, {@code categories}
     */
    @GetMapping("/picker-materials")
    public Map<String, Object> getPickerMaterials(
            @RequestParam Long branchId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "ALL") String stockFilter,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpSession session) {

        Object loggedIn = session != null ? session.getAttribute("loggedInUser") : null;
        if (!(loggedIn instanceof User user) || user.getUserId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Phiên đăng nhập không hợp lệ. Vui lòng đăng nhập lại."
            );
        }
        if (branchId == null || branchId <= 0) {
            throw new IllegalArgumentException("Chi nhánh không hợp lệ.");
        }
        if (!branchAccessService.canAccessBranch(user.getUserId(), branchId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Bạn không có quyền truy cập cơ sở này."
            );
        }

        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        String kw = keyword != null && !keyword.isBlank() ? keyword.trim().toLowerCase(Locale.ROOT) : null;
        String cat = category != null && !category.isBlank() && !"ALL".equalsIgnoreCase(category.trim())
                ? category.trim()
                : null;
        String filterMode = stockFilter != null ? stockFilter.trim().toUpperCase(Locale.ROOT) : "ALL";

        List<Material> activeMaterials = materialRepository != null
                ? materialRepository.findByIsActiveTrueOrderByMaterialNameAsc()
                : List.of();

        List<BranchInventory> branchInventories = inventoryRepository.findAllActiveByBranch(branchId);
        Map<Long, BigDecimal> stockByMatId = new HashMap<>();
        for (BranchInventory bi : branchInventories) {
            if (bi.getMaterial() != null && bi.getMaterial().getMaterialId() != null) {
                stockByMatId.put(bi.getMaterial().getMaterialId(),
                        bi.getCurrentStock() != null ? bi.getCurrentStock() : BigDecimal.ZERO);
            }
        }

        LocalDate today = LocalDate.now();
        List<MaterialBatch> batches = batchRepository.findBatchesWithStockByBranch(branchId, null);
        Map<Long, BigDecimal> availableStockByMatId = new HashMap<>();
        Map<Long, BigDecimal> expiredStockByMatId = new HashMap<>();
        Map<Long, LocalDate> nearestExpiryByMatId = new HashMap<>();
        Map<Long, Integer> activeBatchCountByMatId = new HashMap<>();
        Map<Long, Integer> expiredBatchCountByMatId = new HashMap<>();

        for (MaterialBatch b : batches) {
            if (b.getMaterial() == null || b.getMaterial().getMaterialId() == null) {
                continue;
            }
            Long mid = b.getMaterial().getMaterialId();
            BigDecimal rem = b.getQuantityRemaining() != null ? b.getQuantityRemaining() : BigDecimal.ZERO;
            if (rem.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if (b.getExpiryDate() != null && b.getExpiryDate().isBefore(today)) {
                expiredStockByMatId.merge(mid, rem, BigDecimal::add);
                expiredBatchCountByMatId.merge(mid, 1, Integer::sum);
            } else {
                availableStockByMatId.merge(mid, rem, BigDecimal::add);
                activeBatchCountByMatId.merge(mid, 1, Integer::sum);
                if (b.getExpiryDate() != null) {
                    LocalDate prev = nearestExpiryByMatId.get(mid);
                    if (prev == null || b.getExpiryDate().isBefore(prev)) {
                        nearestExpiryByMatId.put(mid, b.getExpiryDate());
                    }
                }
            }
        }

        Set<String> distinctCategories = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        List<Map<String, Object>> filtered = new ArrayList<>();

        for (Material m : activeMaterials) {
            if (m.getCategory() != null && !m.getCategory().isBlank()) {
                distinctCategories.add(m.getCategory().trim());
            }

            if (cat != null && (m.getCategory() == null || !m.getCategory().trim().equalsIgnoreCase(cat))) {
                continue;
            }

            if (kw != null) {
                String mCode = m.getMaterialCode() != null ? m.getMaterialCode().toLowerCase(Locale.ROOT) : "";
                String mName = m.getMaterialName() != null ? m.getMaterialName().toLowerCase(Locale.ROOT) : "";
                String mCat = m.getCategory() != null ? m.getCategory().toLowerCase(Locale.ROOT) : "";
                String mUnit = m.getUnit() != null ? m.getUnit().toLowerCase(Locale.ROOT) : "";
                if (!mCode.contains(kw) && !mName.contains(kw) && !mCat.contains(kw) && !mUnit.contains(kw)) {
                    continue;
                }
            }

            Long mid = m.getMaterialId();
            BigDecimal currentStock = stockByMatId.getOrDefault(mid, BigDecimal.ZERO);
            BigDecimal availableStock = availableStockByMatId.getOrDefault(mid, BigDecimal.ZERO);
            BigDecimal expiredStock = expiredStockByMatId.getOrDefault(mid, BigDecimal.ZERO);
            BigDecimal minThreshold = m.getMinStockThreshold() != null ? m.getMinStockThreshold() : BigDecimal.ZERO;

            if ("IN_STOCK".equals(filterMode) && currentStock.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if ("AVAILABLE_STOCK".equals(filterMode) && availableStock.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if ("LOW_STOCK".equals(filterMode) && currentStock.compareTo(minThreshold) > 0) {
                continue;
            }
            if ("EXPIRED_ONLY".equals(filterMode) && expiredStock.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("materialId", mid);
            item.put("materialCode", m.getMaterialCode());
            item.put("materialName", m.getMaterialName());
            item.put("category", m.getCategory());
            item.put("unit", m.getUnit());
            item.put("minStockThreshold", minThreshold);
            item.put("currentStock", currentStock);
            item.put("availableStock", availableStock);
            item.put("expiredStock", expiredStock);
            item.put("nearestExpiryDate", nearestExpiryByMatId.get(mid));
            item.put("activeBatchCount", activeBatchCountByMatId.getOrDefault(mid, 0));
            item.put("expiredBatchCount", expiredBatchCountByMatId.getOrDefault(mid, 0));
            filtered.add(item);
        }

        int totalElements = filtered.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalElements / safeSize));
        int fromIndex = Math.min(safePage * safeSize, totalElements);
        int toIndex = Math.min(fromIndex + safeSize, totalElements);
        List<Map<String, Object>> pageContent = filtered.subList(fromIndex, toIndex);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", pageContent);
        response.put("page", safePage);
        response.put("size", safeSize);
        response.put("totalElements", totalElements);
        response.put("totalPages", totalPages);
        response.put("hasMore", toIndex < totalElements);
        response.put("categories", new ArrayList<>(distinctCategories));
        return response;
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
