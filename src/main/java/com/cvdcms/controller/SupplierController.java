package com.cvdcms.controller;

import com.cvdcms.DTO.SupplierCreateDTO;
import com.cvdcms.DTO.SupplierUpdateDTO;
import com.cvdcms.entity.Supplier;
import com.cvdcms.service.SupplierService;

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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/suppliers")
public class SupplierController {

    private final SupplierService supplierService;

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "supplierCode",
            "supplierName",
            "taxCode",
            "phoneNumber",
            "isActive",
            "supplierId"
    );

    public SupplierController(SupplierService supplierService) {
        this.supplierService = supplierService;
    }

    // GET /api/suppliers
    // Hỗ trợ tìm kiếm, lọc trạng thái, sắp xếp và phân trang.
    @GetMapping
    public Page<Supplier> list(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "supplierName") String sortBy,
            @RequestParam(defaultValue = "asc") String direction) {

        if (page < 0) {
            throw new IllegalArgumentException(
                    "Số trang không được nhỏ hơn 0.");
        }

        if (size < 1 || size > 100) {
            throw new IllegalArgumentException(
                    "Kích thước trang phải từ 1 đến 100.");
        }

        String normalizedSortBy =
                sortBy == null ? "" : sortBy.trim();

        if (!ALLOWED_SORT_FIELDS.contains(normalizedSortBy)) {
            throw new IllegalArgumentException(
                    "Trường sắp xếp không hợp lệ."
            );
        }

        Sort.Direction sortDirection;

        try {
            sortDirection = Sort.Direction.fromString(
                    direction == null ? "asc" : direction.trim()
            );
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Chiều sắp xếp chỉ chấp nhận asc hoặc desc."
            );
        }

        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(sortDirection, normalizedSortBy)
        );

        return supplierService.search(keyword, active, pageable);
    }

    // GET /api/suppliers/{id}
    @GetMapping("/{id}")
    public Supplier detail(@PathVariable Long id) {
        return supplierService.getById(id);
    }

    // POST /api/suppliers
    @PostMapping
    public ResponseEntity<Supplier> create(
            @Valid @RequestBody SupplierCreateDTO dto) {

        Supplier created = supplierService.create(dto);

        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // PUT /api/suppliers/{id}
    @PutMapping("/{id}")
    public Supplier update(
            @PathVariable Long id,
            @Valid @RequestBody SupplierUpdateDTO dto) {

        return supplierService.update(id, dto);
    }

    // POST /api/suppliers/{id}/deactivate
    @PostMapping("/{id}/deactivate")
    public Supplier deactivate(@PathVariable Long id) {
        return supplierService.deactivate(id);
    }

    // POST /api/suppliers/{id}/activate
    @PostMapping("/{id}/activate")
    public Supplier activate(@PathVariable Long id) {
        return supplierService.activate(id);
    }

    // Trả lỗi validation theo từng trường.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex) {

        Map<String, String> fieldErrors = new LinkedHashMap<>();

        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(
                    error.getField(), error.getDefaultMessage());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Dữ liệu đầu vào không hợp lệ.");
        body.put("errors", fieldErrors);

        return ResponseEntity.badRequest().body(body);
    }

    // Trả lỗi nghiệp vụ và lỗi tham số theo dạng JSON.
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(
            IllegalArgumentException ex) {

        return ResponseEntity.badRequest().body(
                Map.of("message", ex.getMessage()));
    }
}