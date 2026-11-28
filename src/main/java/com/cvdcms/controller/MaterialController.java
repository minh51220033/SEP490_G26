package com.cvdcms.controller;

import com.cvdcms.DTO.MaterialCategoryDTO;
import com.cvdcms.DTO.MaterialCreateDTO;
import com.cvdcms.DTO.MaterialUpdateDTO;
import com.cvdcms.entity.Material;
import com.cvdcms.entity.User;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.MaterialService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST Controller quản lý Danh mục vật tư nha khoa dùng chung toàn hệ thống ({@code /api/materials}).
 * <p>Hỗ trợ CRUD danh mục vật tư, bật/tắt trạng thái hoạt động, thống kê nhóm vật tư và đổi tên nhóm hàng loạt.
 * Yêu cầu vai trò Quản lý kho hoặc Quản trị viên ({@code hasActiveSupplierManagementRole}).</p>
 * <p>Được gọi từ: {@code loadMaterialsTable()}, {@code submitMaterialForm()} và các hàm quản lý nhóm vật tư trong {@code inventory.js}.</p>
 */
@RestController
@RequestMapping("/api/materials")
public class MaterialController {

    /** Danh sách whitelist các trường được phép sắp xếp trong bảng danh mục vật tư. */
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "materialCode",
            "materialName",
            "category",
            "unit",
            "minStockThreshold",
            "isActive",
            "materialId"
    );

    private final MaterialService materialService;
    private final UserRepository userRepository;

    /**
     * Khởi tạo controller với service danh mục vật tư và repository kiểm tra quyền người dùng.
     */
    public MaterialController(MaterialService materialService, UserRepository userRepository) {
        this.materialService = materialService;
        this.userRepository = userRepository;
    }

    /**
     * Tìm kiếm và phân trang danh mục vật tư theo từ khóa, nhóm vật tư và trạng thái hoạt động.
     * <p>Endpoint: {@code GET /api/materials}</p>
     * <p>Xử lý: kiểm tra quyền quản lý vật tư → kiểm tra {@code page >= 0}, {@code 1 <= size <= 200},
     * whitelist {@code sortBy} và {@code direction} → gọi {@link MaterialService#search}.</p>
     * <p>Được gọi từ: {@code loadMaterialsTable()} trong {@code inventory.js} (Tab Danh mục vật tư).</p>
     *
     * @param keyword   từ khóa tìm kiếm mã/tên/nhóm/đơn vị tính
     * @param category  tên nhóm vật tư cần lọc (tùy chọn)
     * @param active    {@code true} = đang hoạt động, {@code false} = ngừng sử dụng, {@code null} = tất cả
     * @param page      chỉ số trang (mặc định 0)
     * @param size      số dòng mỗi trang (1–200)
     * @param sortBy    trường sắp xếp hợp lệ trong {@link #ALLOWED_SORT_FIELDS}
     * @param direction chiều sắp xếp ({@code asc}/{@code desc})
     * @param session   phiên đăng nhập hiện tại
     * @return trang dữ liệu {@link Material}
     */
    @GetMapping
    public Page<Material> list(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "materialName") String sortBy,
            @RequestParam(defaultValue = "asc") String direction,
            HttpSession session) {

        requireWarehouseOrAdmin(session);

        if (page < 0) {
            throw new IllegalArgumentException("Số trang không được nhỏ hơn 0.");
        }
        if (size < 1 || size > 200) {
            throw new IllegalArgumentException("Kích thước trang phải từ 1 đến 200.");
        }

        String normalizedSortBy = sortBy == null ? "" : sortBy.trim();
        if (!ALLOWED_SORT_FIELDS.contains(normalizedSortBy)) {
            throw new IllegalArgumentException("Trường sắp xếp không hợp lệ.");
        }

        Sort.Direction sortDirection;
        try {
            sortDirection = Sort.Direction.fromString(direction == null ? "asc" : direction.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Chiều sắp xếp chỉ chấp nhận asc hoặc desc.");
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(sortDirection, normalizedSortBy));
        return materialService.search(keyword, category, active, pageable);
    }

    /**
     * Lấy toàn bộ danh sách vật tư đang hoạt động ({@code isActive = true}) sắp xếp theo tên tăng dần.
     * <p>Endpoint: {@code GET /api/materials/active}</p>
     * <p>Được gọi từ: các dropdown chọn vật tư trong {@code inventory.js}.</p>
     *
     * @param session phiên đăng nhập hiện tại
     * @return danh sách {@link Material} đang hoạt động
     */
    @GetMapping("/active")
    public List<Material> listActive(HttpSession session) {
        requireWarehouseOrAdmin(session);
        return materialService.listActive();
    }

    /**
     * Lấy danh sách các nhóm vật tư kèm thống kê tổng số vật tư và số vật tư đang hoạt động trong từng nhóm.
     * <p>Endpoint: {@code GET /api/materials/categories}</p>
     * <p>Được gọi từ: bộ lọc nhóm vật tư và modal quản lý nhóm trong {@code inventory.js}.</p>
     *
     * @param session phiên đăng nhập hiện tại
     * @return danh sách {@link MaterialCategoryDTO}
     */
    @GetMapping("/categories")
    public List<MaterialCategoryDTO> getCategories(HttpSession session) {
        requireWarehouseOrAdmin(session);
        return materialService.getCategories();
    }

    /**
     * Đổi tên đồng loạt một nhóm vật tư trên tất cả các bản ghi {@link Material} thuộc nhóm đó.
     * <p>Endpoint: {@code PUT /api/materials/categories/rename}</p>
     * <p>Đầu vào JSON: {@code { "oldCategory": "...", "newCategory": "..." }}.</p>
     *
     * @param payload map chứa tên nhóm cũ và tên nhóm mới
     * @param session phiên đăng nhập hiện tại
     * @return map gồm {@code oldCategory}, {@code newCategory} và số bản ghi {@code updatedMaterials} đã cập nhật
     */
    @PutMapping("/categories/rename")
    public Map<String, Object> renameCategory(
            @RequestBody Map<String, String> payload,
            HttpSession session) {
        requireWarehouseOrAdmin(session);
        String oldName = payload != null ? payload.get("oldCategory") : null;
        String newName = payload != null ? payload.get("newCategory") : null;
        int updated = materialService.renameCategory(oldName, newName);
        return Map.of(
                "oldCategory", oldName == null ? "" : oldName,
                "newCategory", newName == null ? "" : newName,
                "updatedMaterials", updated
        );
    }

    /**
     * Lấy thông tin chi tiết một vật tư theo mã định danh.
     * <p>Endpoint: {@code GET /api/materials/{id}}</p>
     *
     * @param id      mã định danh vật tư
     * @param session phiên đăng nhập hiện tại
     * @return thực thể {@link Material} tương ứng
     */
    @GetMapping("/{id}")
    public Material detail(@PathVariable Long id, HttpSession session) {
        requireWarehouseOrAdmin(session);
        return materialService.getById(id);
    }

    /**
     * Thêm mới một vật tư vào danh mục dùng chung.
     * <p>Endpoint: {@code POST /api/materials}</p>
     * <p>Được gọi từ: {@code submitMaterialForm()} trong {@code inventory.js}.</p>
     *
     * @param dto     dữ liệu tạo mới vật tư đã qua Bean Validation
     * @param session phiên đăng nhập hiện tại
     * @return HTTP 201 Created kèm thực thể {@link Material} vừa tạo
     */
    @PostMapping
    public ResponseEntity<Material> create(
            @Valid @RequestBody MaterialCreateDTO dto,
            HttpSession session) {
        requireWarehouseOrAdmin(session);
        Material created = materialService.create(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Cập nhật thông tin một vật tư hiện có theo {@code id}.
     * <p>Endpoint: {@code PUT /api/materials/{id}}</p>
     * <p>Được gọi từ: {@code submitMaterialForm()} trong {@code inventory.js} khi ở chế độ chỉnh sửa.</p>
     *
     * @param id      mã vật tư cần cập nhật
     * @param dto     dữ liệu cập nhật đã qua Bean Validation
     * @param session phiên đăng nhập hiện tại
     * @return thực thể {@link Material} sau khi cập nhật
     */
    @PutMapping("/{id}")
    public Material update(
            @PathVariable Long id,
            @Valid @RequestBody MaterialUpdateDTO dto,
            HttpSession session) {
        requireWarehouseOrAdmin(session);
        return materialService.update(id, dto);
    }

    /**
     * Ngừng sử dụng (soft-deactivate, {@code isActive = false}) một vật tư trong danh mục.
     * <p>Endpoint: {@code POST /api/materials/{id}/deactivate}</p>
     *
     * @param id      mã vật tư cần ngừng sử dụng
     * @param session phiên đăng nhập hiện tại
     * @return thực thể {@link Material} sau khi cập nhật trạng thái
     */
    @PostMapping("/{id}/deactivate")
    public Material deactivate(@PathVariable Long id, HttpSession session) {
        requireWarehouseOrAdmin(session);
        return materialService.deactivate(id);
    }

    /**
     * Kích hoạt lại ({@code isActive = true}) một vật tư đã bị ngừng sử dụng.
     * <p>Endpoint: {@code POST /api/materials/{id}/activate}</p>
     *
     * @param id      mã vật tư cần kích hoạt lại
     * @param session phiên đăng nhập hiện tại
     * @return thực thể {@link Material} sau khi kích hoạt lại
     */
    @PostMapping("/{id}/activate")
    public Material activate(@PathVariable Long id, HttpSession session) {
        requireWarehouseOrAdmin(session);
        return materialService.activate(id);
    }

    /**
     * Xác thực người dùng đã đăng nhập và có quyền quản lý kho/vật tư/quản trị viên.
     * Ném HTTP 401 nếu chưa đăng nhập hoặc HTTP 403 nếu không có quyền.
     */
    private void requireWarehouseOrAdmin(HttpSession session) {
        Object loggedIn = session != null ? session.getAttribute("loggedInUser") : null;
        if (!(loggedIn instanceof User user) || user.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập để sử dụng chức năng này.");
        }
        if (!userRepository.hasActiveSupplierManagementRole(user.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền quản lý danh mục vật tư.");
        }
    }

    /**
     * Xử lý lỗi Bean Validation và trả về HTTP 400 kèm danh sách lỗi chi tiết từng trường.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Dữ liệu đầu vào không hợp lệ.");
        body.put("errors", fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Xử lý lỗi tham số/nghiệp vụ không hợp lệ và trả về HTTP 400 kèm JSON {@code { "message": "..." }}.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }
}
