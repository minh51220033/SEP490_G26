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

/**
 * REST Controller cung cấp các API CRUD và quản lý trạng thái Nhà cung cấp ({@code /api/suppliers}).
 *
 * <p><b>Bảo mật:</b> Toàn bộ endpoint dưới {@code /api/suppliers/**} được kiểm soát phiên đăng nhập và quyền hạn
 * qua {@link com.cvdcms.config.SupplierManagementInterceptor} (yêu cầu role quản lý NCC hoặc quyền
 * {@code SUPPLIER_MANAGE} / {@code LABO_SUPPLIERS}).</p>
 *
 * <p><b>Được gọi từ:</b> Giao diện Quản lý Nhà cung cấp ({@code src/main/resources/static/js/suppliers.js})
 * và các màn hình chọn nhà cung cấp trong phân hệ Kho vật tư.</p>
 */
@RestController
@RequestMapping("/api/suppliers")
public class SupplierController {

    private final SupplierService supplierService;

    /** Danh sách whitelist các trường được phép sắp xếp nhằm chống lỗi/injection qua tham số {@code sortBy}. */
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

    /**
     * Lấy danh sách nhà cung cấp có hỗ trợ tìm kiếm, lọc trạng thái, sắp xếp và phân trang.
     *
     * <p><b>Endpoint:</b> {@code GET /api/suppliers}</p>
     * <p><b>Xử lý:</b> Kiểm tra hợp lệ {@code page >= 0}, {@code 1 <= size <= 100}, {@code sortBy} thuộc
     * {@link #ALLOWED_SORT_FIELDS}, {@code direction} là {@code asc}/{@code desc}; tạo {@link Pageable}
     * và ủy quyền cho {@link SupplierService#search}.</p>
     * <p><b>Được gọi từ:</b> {@code loadSuppliers()} trong {@code suppliers.js}.</p>
     *
     * @param keyword   từ khóa tìm theo mã, tên, SĐT, MST (mặc định rỗng)
     * @param active    lọc theo trạng thái hoạt động ({@code true}/{@code false}/{@code null})
     * @param page      chỉ số trang bắt đầu từ 0 (mặc định 0)
     * @param size      số bản ghi mỗi trang, từ 1 đến 100 (mặc định 10)
     * @param sortBy    tên trường sắp xếp thuộc whitelist (mặc định {@code supplierName})
     * @param direction chiều sắp xếp {@code asc} hoặc {@code desc} (mặc định {@code asc})
     * @return trang dữ liệu {@link Page}&lt;{@link Supplier}&gt;
     */
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

    /**
     * Lấy thông tin chi tiết một nhà cung cấp theo ID.
     *
     * <p><b>Endpoint:</b> {@code GET /api/suppliers/{id}}</p>
     * <p><b>Được gọi từ:</b> {@code suppliers.js} khi người dùng nhấn nút "Sửa" trên bảng danh sách.</p>
     *
     * @param id ID nhà cung cấp
     * @return bản ghi {@link Supplier} tương ứng
     */
    @GetMapping("/{id}")
    public Supplier detail(@PathVariable Long id) {
        return supplierService.getById(id);
    }

    /**
     * Tạo mới một nhà cung cấp.
     *
     * <p><b>Endpoint:</b> {@code POST /api/suppliers}</p>
     * <p><b>Đầu vào:</b> {@link SupplierCreateDTO} đã được validate bằng {@code @Valid}.</p>
     * <p><b>Kết quả:</b> HTTP {@code 201 Created} cùng đối tượng {@link Supplier} vừa lưu.</p>
     * <p><b>Được gọi từ:</b> {@code suppliers.js} khi submit {@code #supplierForm} ở chế độ thêm mới.</p>
     *
     * @param dto dữ liệu tạo nhà cung cấp
     * @return {@link ResponseEntity} chứa nhà cung cấp mới tạo
     */
    @PostMapping
    public ResponseEntity<Supplier> create(
            @Valid @RequestBody SupplierCreateDTO dto) {

        Supplier created = supplierService.create(dto);

        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Cập nhật thông tin nhà cung cấp theo ID (không thay đổi trạng thái {@code isActive}).
     *
     * <p><b>Endpoint:</b> {@code PUT /api/suppliers/{id}}</p>
     * <p><b>Đầu vào:</b> {@code id} và {@link SupplierUpdateDTO} đã validate qua {@code @Valid}.</p>
     * <p><b>Được gọi từ:</b> {@code suppliers.js} khi submit {@code #supplierForm} ở chế độ chỉnh sửa.</p>
     *
     * @param id  ID nhà cung cấp cần cập nhật
     * @param dto dữ liệu cập nhật
     * @return bản ghi {@link Supplier} sau khi cập nhật
     */
    @PutMapping("/{id}")
    public Supplier update(
            @PathVariable Long id,
            @Valid @RequestBody SupplierUpdateDTO dto) {

        return supplierService.update(id, dto);
    }

    /**
     * Ngừng hoạt động (soft-deactivate) một nhà cung cấp, vẫn giữ nguyên lịch sử nhập kho và thanh toán.
     *
     * <p><b>Endpoint:</b> {@code POST /api/suppliers/{id}/deactivate}</p>
     * <p><b>Được gọi từ:</b> {@code suppliers.js} khi người dùng nhấn nút "Ngừng" và xác nhận.</p>
     *
     * @param id ID nhà cung cấp
     * @return bản ghi {@link Supplier} với {@code isActive = false}
     */
    @PostMapping("/{id}/deactivate")
    public Supplier deactivate(@PathVariable Long id) {
        return supplierService.deactivate(id);
    }

    /**
     * Kích hoạt lại một nhà cung cấp đang ngừng hoạt động.
     *
     * <p><b>Endpoint:</b> {@code POST /api/suppliers/{id}/activate}</p>
     * <p><b>Được gọi từ:</b> {@code suppliers.js} khi người dùng nhấn nút "Kích hoạt" và xác nhận.</p>
     *
     * @param id ID nhà cung cấp
     * @return bản ghi {@link Supplier} với {@code isActive = true}
     */
    @PostMapping("/{id}/activate")
    public Supplier activate(@PathVariable Long id) {
        return supplierService.activate(id);
    }

    /**
     * Xử lý ngoại lệ Bean Validation ({@link MethodArgumentNotValidException}) và trả về HTTP {@code 400 Bad Request}
     * kèm map lỗi chi tiết theo từng trường ({@code message} và {@code errors: {field: msg}}) để frontend hiển thị inline.
     *
     * @param ex ngoại lệ validation
     * @return phản hồi JSON chứa thông báo chung và danh sách lỗi theo trường
     */
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

    /**
     * Xử lý các lỗi nghiệp vụ hoặc tham số không hợp lệ ({@link IllegalArgumentException})
     * và trả về HTTP {@code 400 Bad Request} dạng JSON {@code {"message": "..."}}.
     *
     * @param ex ngoại lệ nghiệp vụ / tham số
     * @return phản hồi JSON chứa thông điệp lỗi
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(
            IllegalArgumentException ex) {

        return ResponseEntity.badRequest().body(
                Map.of("message", ex.getMessage()));
    }
}