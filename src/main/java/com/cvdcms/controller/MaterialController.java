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

@RestController
@RequestMapping("/api/materials")
public class MaterialController {

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

    public MaterialController(MaterialService materialService, UserRepository userRepository) {
        this.materialService = materialService;
        this.userRepository = userRepository;
    }

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

    @GetMapping("/active")
    public List<Material> listActive(HttpSession session) {
        requireWarehouseOrAdmin(session);
        return materialService.listActive();
    }

    @GetMapping("/categories")
    public List<MaterialCategoryDTO> getCategories(HttpSession session) {
        requireWarehouseOrAdmin(session);
        return materialService.getCategories();
    }

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

    @GetMapping("/{id}")
    public Material detail(@PathVariable Long id, HttpSession session) {
        requireWarehouseOrAdmin(session);
        return materialService.getById(id);
    }

    @PostMapping
    public ResponseEntity<Material> create(
            @Valid @RequestBody MaterialCreateDTO dto,
            HttpSession session) {
        requireWarehouseOrAdmin(session);
        Material created = materialService.create(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public Material update(
            @PathVariable Long id,
            @Valid @RequestBody MaterialUpdateDTO dto,
            HttpSession session) {
        requireWarehouseOrAdmin(session);
        return materialService.update(id, dto);
    }

    @PostMapping("/{id}/deactivate")
    public Material deactivate(@PathVariable Long id, HttpSession session) {
        requireWarehouseOrAdmin(session);
        return materialService.deactivate(id);
    }

    @PostMapping("/{id}/activate")
    public Material activate(@PathVariable Long id, HttpSession session) {
        requireWarehouseOrAdmin(session);
        return materialService.activate(id);
    }

    private void requireWarehouseOrAdmin(HttpSession session) {
        Object loggedIn = session != null ? session.getAttribute("loggedInUser") : null;
        if (!(loggedIn instanceof User user) || user.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập để sử dụng chức năng này.");
        }
        if (!userRepository.hasActiveSupplierManagementRole(user.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền quản lý danh mục vật tư.");
        }
    }

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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }
}
