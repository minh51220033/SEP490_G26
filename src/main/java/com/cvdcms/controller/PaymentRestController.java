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

    public PaymentRestController(
            PaymentService paymentService,
            RefundService refundService,
            @org.springframework.beans.factory.annotation.Autowired(required = false) PaymentAllocationService paymentAllocationService
    ) {
        this.paymentService = paymentService;
        this.refundService = refundService;
        this.paymentAllocationService = paymentAllocationService;
    }

    private static final class UnauthenticatedException extends RuntimeException {
        UnauthenticatedException(String message) {
            super(message);
        }
    }

    private Long getCurrentUserId(HttpSession session) {
        if (session != null && session.getAttribute("loggedInUser") instanceof User u && u.getUserId() != null) {
            return u.getUserId();
        }
        throw new UnauthenticatedException("Chưa đăng nhập. Vui lòng đăng nhập để tiếp tục.");
    }

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
     * API xem trước phân bổ số tiền thanh toán vào từng dòng chi tiết hóa đơn (FIFO: Dịch vụ -> Thuốc -> Phụ thu).
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
     * API lấy chi tiết phân bổ của một phiếu thu (Payment).
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
     * API lấy toàn bộ lịch sử phân bổ của một hóa đơn.
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
