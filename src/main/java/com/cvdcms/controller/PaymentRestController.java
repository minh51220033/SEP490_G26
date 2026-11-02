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

    public PaymentRestController(
            PaymentService paymentService,
            RefundService refundService,
            @org.springframework.beans.factory.annotation.Autowired(required = false) PaymentAllocationService paymentAllocationService
    ) {
        this.paymentService = paymentService;
        this.refundService = refundService;
        this.paymentAllocationService = paymentAllocationService;
    }

    private Long getCurrentUserId(HttpSession session) {
        if (session != null && session.getAttribute("loggedInUser") instanceof User u && u.getUserId() != null) {
            return u.getUserId();
        }
        throw new AccessDeniedException("Chưa đăng nhập. Vui lòng đăng nhập để tiếp tục.");
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
        } catch (AccessDeniedException ade) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("success", false, "error", ade.getMessage()));
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
        } catch (AccessDeniedException ade) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("success", false, "error", ade.getMessage()));
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
            getCurrentUserId(session);
            if (paymentAllocationService == null) {
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("error", "Service phân bổ chưa sẵn sàng."));
            }
            PaymentAllocationPreviewDTO preview = paymentAllocationService.previewPaymentAllocation(invoiceId, amount);
            return ResponseEntity.ok(preview);
        } catch (AccessDeniedException ade) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", ade.getMessage()));
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
            getCurrentUserId(session);
            if (paymentAllocationService == null) {
                return ResponseEntity.ok(List.of());
            }
            List<PaymentAllocationDTO> list = paymentAllocationService.getAllocationsByPaymentId(paymentId);
            return ResponseEntity.ok(list);
        } catch (AccessDeniedException ade) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", ade.getMessage()));
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
            getCurrentUserId(session);
            if (paymentAllocationService == null) {
                return ResponseEntity.ok(List.of());
            }
            List<PaymentAllocationDTO> list = paymentAllocationService.getAllocationsByInvoiceId(invoiceId);
            return ResponseEntity.ok(list);
        } catch (AccessDeniedException ade) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", ade.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
