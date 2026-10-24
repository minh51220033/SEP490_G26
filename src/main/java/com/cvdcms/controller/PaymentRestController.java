package com.cvdcms.controller;

import com.cvdcms.DTO.PaymentRequestDTO;
import com.cvdcms.DTO.RefundRequestDTO;
import com.cvdcms.entity.Payment;
import com.cvdcms.entity.Refund;
import com.cvdcms.entity.User;
import com.cvdcms.service.PaymentService;
import com.cvdcms.service.RefundService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/payments")
public class PaymentRestController {

    private final PaymentService paymentService;
    private final RefundService refundService;

    public PaymentRestController(PaymentService paymentService, RefundService refundService) {
        this.paymentService = paymentService;
        this.refundService = refundService;
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
}
