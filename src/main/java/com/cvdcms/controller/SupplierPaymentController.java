package com.cvdcms.controller;

import com.cvdcms.DTO.SupplierDebtSummaryDTO;
import com.cvdcms.DTO.SupplierPaymentCreateDTO;
import com.cvdcms.DTO.SupplierPaymentResponseDTO;
import com.cvdcms.entity.User;
import com.cvdcms.service.SupplierPaymentService;
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
 * [Phân hệ]: Tài chính & Kho - Công nợ và thanh toán nhà cung cấp
 * [Vai trò]: REST Controller cung cấp API tra cứu công nợ, tìm kiếm lịch sử thanh toán và tạo phiếu thanh toán nhà cung cấp (/api/inventory/supplier-payments).
 */
@RestController
@RequestMapping("/api/inventory/supplier-payments")
public class SupplierPaymentController {

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "paymentDate",
            "supplierPaymentId",
            "paymentCode",
            "amount"
    );

    private final SupplierPaymentService paymentService;

    public SupplierPaymentController(SupplierPaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * [Chức năng]: Lấy danh sách tổng hợp công nợ nhà cung cấp theo chi nhánh và từ khóa (GET /api/inventory/supplier-payments/debts).
     * [Mục đích]: Xác thực người dùng từ session và gọi SupplierPaymentService.getDebtSummaries trả về tổng tiền nhập, đã trả và còn nợ của từng nhà cung cấp.
     */
    @GetMapping("/debts")
    public List<SupplierDebtSummaryDTO> getDebtSummaries(
            @RequestParam Long branchId,
            @RequestParam(required = false) String keyword,
            HttpSession session) {
        User user = requireUser(session);
        return paymentService.getDebtSummaries(branchId, keyword, user.getUserId());
    }

    /**
     * [Chức năng]: Tra cứu danh sách phiếu thanh toán nhà cung cấp có phân trang, sắp xếp và lọc theo ngày/nhà cung cấp (GET /api/inventory/supplier-payments).
     * [Mục đích]: Kiểm tra hợp lệ tham số phân trang và trường sắp xếp an toàn (ALLOWED_SORT_FIELDS), sau đó gọi SupplierPaymentService.searchPayments.
     */
    @GetMapping
    public Page<SupplierPaymentResponseDTO> listPayments(
            @RequestParam Long branchId,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "paymentDate") String sortBy,
            @RequestParam(defaultValue = "desc") String direction,
            HttpSession session) {

        User user = requireUser(session);
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tham số phân trang không hợp lệ.");
        }
        String sortField = sortBy != null && ALLOWED_SORT_FIELDS.contains(sortBy.trim())
                ? sortBy.trim()
                : "paymentDate";
        Sort.Direction dir = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(dir, sortField).and(Sort.by(dir, "supplierPaymentId")));

        return paymentService.searchPayments(branchId, supplierId, keyword, fromDate, toDate, pageable, user.getUserId());
    }

    /**
     * [Chức năng]: Tạo mới phiếu thanh toán công nợ nhà cung cấp (POST /api/inventory/supplier-payments).
     * [Mục đích]: Xác thực người dùng từ session và chuyển DTO xuống SupplierPaymentService.createPayment để kiểm tra công nợ, khóa bản ghi và lưu phiếu chi trả NCC.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierPaymentResponseDTO createPayment(
            @RequestBody SupplierPaymentCreateDTO dto,
            HttpSession session) {
        User user = requireUser(session);
        return paymentService.createPayment(dto, user.getUserId());
    }

    /**
     * [Hàm hỗ trợ]: Lấy thông tin người dùng đăng nhập từ HttpSession, ném lỗi 401 UNAUTHORIZED nếu phiên không hợp lệ.
     */
    private User requireUser(HttpSession session) {
        Object value = session != null ? session.getAttribute("loggedInUser") : null;
        if (!(value instanceof User user) || user.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập để sử dụng chức năng kho.");
        }
        return user;
    }

    /**
     * [Chức năng]: Xử lý ngoại lệ IllegalArgumentException và trả về phản hồi JSON lỗi 400 Bad Request.
     * [Mục đích]: Chuẩn hóa thông điệp lỗi đầu vào cho các client gọi REST API thanh toán nhà cung cấp.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }

    /**
     * [Chức năng]: Xử lý ngoại lệ ResponseStatusException và trả về JSON kèm mã HTTP status tương ứng.
     * [Mục đích]: Đảm bảo mọi lỗi phân quyền, không tìm thấy hoặc lỗi nghiệp vụ từ service đều trả về cấu trúc JSON thống nhất có trường message.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        String msg = ex.getReason() != null ? ex.getReason() : "Không thể xử lý yêu cầu thanh toán nhà cung cấp.";
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", msg));
    }
}
