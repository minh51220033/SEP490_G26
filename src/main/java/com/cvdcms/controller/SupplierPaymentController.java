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

    @GetMapping("/debts")
    public List<SupplierDebtSummaryDTO> getDebtSummaries(
            @RequestParam Long branchId,
            @RequestParam(required = false) String keyword,
            HttpSession session) {
        User user = requireUser(session);
        return paymentService.getDebtSummaries(branchId, keyword, user.getUserId());
    }

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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierPaymentResponseDTO createPayment(
            @RequestBody SupplierPaymentCreateDTO dto,
            HttpSession session) {
        User user = requireUser(session);
        return paymentService.createPayment(dto, user.getUserId());
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
        String msg = ex.getReason() != null ? ex.getReason() : "Không thể xử lý yêu cầu thanh toán nhà cung cấp.";
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", msg));
    }
}
