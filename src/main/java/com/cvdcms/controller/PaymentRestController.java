package com.cvdcms.controller;

import com.cvdcms.DTO.PaymentAllocationDTO;
import com.cvdcms.DTO.PaymentAllocationPreviewDTO;
import com.cvdcms.DTO.PaymentRequestDTO;
import com.cvdcms.DTO.RefundRequestDTO;
import com.cvdcms.entity.Payment;
import com.cvdcms.entity.Refund;
import com.cvdcms.entity.User;
import com.cvdcms.service.PaymentAllocationService;
import com.cvdcms.service.PaymentService;
import com.cvdcms.service.RefundService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * [Phân hệ]: Thanh toán, Ví tạm ứng, Hoàn tiền & Phân bổ thanh toán (REST API)
 * [Vai trò]: REST Controller (`/api/payments`) xử lý các yêu cầu AJAX nạp cọc tạm ứng, hoàn tiền, xem trước phân bổ FIFO từng dòng hóa đơn và tra cứu lịch sử phân bổ thanh toán.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentRestController {

    private final PaymentService paymentService;
    private final RefundService refundService;
    private final PaymentAllocationService paymentAllocationService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.InvoiceRepository invoiceRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.PaymentRepository paymentRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.UserRepository userRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.StaffBranchAssignmentRepository staffBranchAssignmentRepository;

    /**
     * [Hàm hỗ trợ]: Khởi tạo REST controller thanh toán với các service thanh toán, hoàn tiền và phân bổ thanh toán.
     */
    public PaymentRestController(
            PaymentService paymentService,
            RefundService refundService,
            @org.springframework.beans.factory.annotation.Autowired(required = false) PaymentAllocationService paymentAllocationService
    ) {
        this.paymentService = paymentService;
        this.refundService = refundService;
        this.paymentAllocationService = paymentAllocationService;
    }

    /**
     * [Hàm hỗ trợ]: Ngoại lệ nội bộ đánh dấu yêu cầu API chưa đăng nhập để trả về mã HTTP 401.
     */
    private static final class UnauthenticatedException extends RuntimeException {
        UnauthenticatedException(String message) {
            super(message);
        }
    }

    /**
     * [Hàm hỗ trợ]: Lấy `userId` của nhân sự đang đăng nhập từ `HttpSession` hoặc ném `UnauthenticatedException` (HTTP 401).
     */
    private Long getCurrentUserId(HttpSession session) {
        if (session != null && session.getAttribute("loggedInUser") instanceof User u && u.getUserId() != null) {
            return u.getUserId();
        }
        throw new UnauthenticatedException("Chưa đăng nhập. Vui lòng đăng nhập để tiếp tục.");
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra người dùng hiện tại có quyền truy cập dữ liệu tại `branchId` hay không (Admin hoặc nhân sự có phân công `ACTIVE` / chi nhánh gốc tương ứng).
     */
    private void verifyBranchAccess(Long userId, Long branchId) {
        if (branchId == null || userRepository == null || staffBranchAssignmentRepository == null) {
            return;
        }
        if (userRepository.hasActiveAdminRole(userId)) {
            return;
        }
        boolean hasAssignment = staffBranchAssignmentRepository.hasActiveUserAssignment(
                userId, branchId, com.cvdcms.entity.enums.AssignmentStatus.ACTIVE);
        if (!hasAssignment) {
            boolean matchesUserBranch = staffBranchAssignmentRepository
                    .findActiveBranchIdsByUserId(userId, com.cvdcms.entity.enums.AssignmentStatus.ACTIVE).isEmpty()
                    && userRepository.findById(userId)
                            .map(u -> u.getBranch() != null && branchId.equals(u.getBranch().getBranchId()))
                            .orElse(false);
            if (!matchesUserBranch) {
                throw new AccessDeniedException("Bạn không có quyền truy cập dữ liệu tại chi nhánh ID: " + branchId);
            }
        }
    }

    /**
     * [Hàm hỗ trợ]: Tải hóa đơn theo `invoiceId` và kiểm tra quyền truy cập chi nhánh phát hành hóa đơn đó.
     */
    private void verifyInvoiceBranchAccess(Long userId, Long invoiceId) {
        if (invoiceId == null || invoiceRepository == null) {
            return;
        }
        com.cvdcms.entity.Invoice inv = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn ID: " + invoiceId));
        if (inv.getBranch() != null) {
            verifyBranchAccess(userId, inv.getBranch().getBranchId());
        }
    }

    /**
     * [Hàm hỗ trợ]: Tải phiếu thu theo `paymentId` và kiểm tra quyền truy cập chi nhánh của phiếu thu đó.
     */
    private void verifyPaymentBranchAccess(Long userId, Long paymentId) {
        if (paymentId == null || paymentRepository == null) {
            return;
        }
        Payment p = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu thu ID: " + paymentId));
        if (p.getBranch() != null) {
            verifyBranchAccess(userId, p.getBranch().getBranchId());
        }
    }

    /**
     * [Chức năng]: Tiếp nhận nạp tiền tạm ứng vào ví cọc bệnh nhân qua API (`POST /api/payments/deposit`).
     * [Mục đích]: Xác thực thu ngân đăng nhập, ủy quyền cho `paymentService.processAdvanceDeposit` để cộng số dư ví tạm ứng và tích điểm ngay, sau đó trả mã phiếu `TU...` cùng số dư ví mới nhất (HTTP 401/403/400 nếu lỗi).
     */
    @PostMapping("/deposit")
    public ResponseEntity<?> deposit(@RequestBody PaymentRequestDTO request, HttpSession session) {
        try {
            Long cashierUserId = getCurrentUserId(session);
            Payment payment = paymentService.processAdvanceDeposit(request, cashierUserId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "paymentCode", payment.getPaymentCode(),
                    "amount", payment.getAmount(),
                    "patientCode", payment.getPatientProfile().getPatientCode(),
                    "depositBalance", payment.getPatientProfile().getDepositBalance()
            ));
        } catch (UnauthenticatedException ue) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("success", false, "error", ue.getMessage()));
        } catch (AccessDeniedException ade) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("success", false, "error", ade.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * [Chức năng]: Xử lý yêu cầu hoàn tiền cho bệnh nhân qua API (`POST /api/payments/refund`).
     * [Mục đích]: Xác thực nhân sự đăng nhập, gọi `refundService.processRefund` (hỗ trợ hoàn từ hóa đơn kèm/không kèm giảm tổng hóa đơn `reduceInvoiceTotal` hoặc hoàn từ ví tạm ứng) và trả về mã phiếu hoàn `HT...` cùng số tiền hoàn.
     */
    @PostMapping("/refund")
    public ResponseEntity<?> refund(@RequestBody RefundRequestDTO request, HttpSession session) {
        try {
            Long staffUserId = getCurrentUserId(session);
            Refund refund = refundService.processRefund(request, staffUserId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "refundCode", refund.getRefundCode(),
                    "amount", refund.getAmount()
            ));
        } catch (UnauthenticatedException ue) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("success", false, "error", ue.getMessage()));
        } catch (AccessDeniedException ade) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("success", false, "error", ade.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * [Chức năng]: Xem trước kết quả phân bổ FIFO một khoản thanh toán vào từng dòng chi tiết hóa đơn (`GET /api/payments/allocation-preview`).
     * [Mục đích]: Giúp thu ngân kiểm tra trước khi bấm thu tiền (thu một phần hay thu đủ) xem số tiền nhập vào sẽ trừ vào từng dòng Dịch vụ -> Thuốc -> Phụ thu như thế nào mà chưa ghi vào cơ sở dữ liệu; có kiểm tra quyền truy cập chi nhánh của hóa đơn.
     */
    @GetMapping("/allocation-preview")
    public ResponseEntity<?> previewAllocation(
            @RequestParam Long invoiceId,
            @RequestParam BigDecimal amount,
            HttpSession session
    ) {
        try {
            Long userId = getCurrentUserId(session);
            verifyInvoiceBranchAccess(userId, invoiceId);
            if (paymentAllocationService == null) {
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("error", "Service phân bổ chưa sẵn sàng."));
            }
            PaymentAllocationPreviewDTO preview = paymentAllocationService.previewPaymentAllocation(invoiceId, amount);
            return ResponseEntity.ok(preview);
        } catch (UnauthenticatedException ue) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", ue.getMessage()));
        } catch (AccessDeniedException ade) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", ade.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * [Chức năng]: Lấy danh sách chi tiết phân bổ từng dòng dịch vụ/thuốc/phụ thu của một phiếu thu (`GET /api/payments/{paymentId}/allocations`).
     * [Mục đích]: Phục vụ modal/phiếu thu hiển thị rõ phiếu thu đó đã chi trả bao nhiêu tiền cho từng dòng hạng mục và số nợ còn lại ngay tại thời điểm thu sau khi xác thực quyền chi nhánh.
     */
    @GetMapping("/{paymentId}/allocations")
    public ResponseEntity<?> getPaymentAllocations(
            @PathVariable Long paymentId,
            HttpSession session
    ) {
        try {
            Long userId = getCurrentUserId(session);
            verifyPaymentBranchAccess(userId, paymentId);
            if (paymentAllocationService == null) {
                return ResponseEntity.ok(List.of());
            }
            List<PaymentAllocationDTO> list = paymentAllocationService.getAllocationsByPaymentId(paymentId);
            return ResponseEntity.ok(list);
        } catch (UnauthenticatedException ue) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", ue.getMessage()));
        } catch (AccessDeniedException ade) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", ade.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * [Chức năng]: Lấy toàn bộ lịch sử phân bổ của tất cả các đợt thanh toán thuộc một hóa đơn (`GET /api/payments/invoice/{invoiceId}/allocations`).
     * [Mục đích]: Hỗ trợ truy vết lịch sử thanh toán nhiều đợt (trả góp/thu một phần) trên màn hình chi tiết hóa đơn sau khi xác thực quyền truy cập chi nhánh phát hành hóa đơn.
     */
    @GetMapping("/invoice/{invoiceId}/allocations")
    public ResponseEntity<?> getInvoiceAllocations(
            @PathVariable Long invoiceId,
            HttpSession session
    ) {
        try {
            Long userId = getCurrentUserId(session);
            verifyInvoiceBranchAccess(userId, invoiceId);
            if (paymentAllocationService == null) {
                return ResponseEntity.ok(List.of());
            }
            List<PaymentAllocationDTO> list = paymentAllocationService.getAllocationsByInvoiceId(invoiceId);
            return ResponseEntity.ok(list);
        } catch (UnauthenticatedException ue) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", ue.getMessage()));
        } catch (AccessDeniedException ade) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", ade.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
