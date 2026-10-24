package com.cvdcms.controller;

import com.cvdcms.DTO.InvoiceCalculationPreviewDTO;
import com.cvdcms.DTO.InvoiceCreateRequestDTO;
import com.cvdcms.DTO.PaymentRequestDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.repository.StaffBranchAssignmentRepository;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.repository.VisitRepository;
import com.cvdcms.service.InvoiceService;
import com.cvdcms.service.PaymentService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceRestController {

    private final InvoiceService invoiceService;
    private final PaymentService paymentService;
    private final VisitRepository visitRepository;
    private final UserRepository userRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;

    public InvoiceRestController(
            InvoiceService invoiceService,
            PaymentService paymentService,
            VisitRepository visitRepository,
            UserRepository userRepository,
            StaffProfileRepository staffProfileRepository,
            StaffBranchAssignmentRepository staffBranchAssignmentRepository
    ) {
        this.invoiceService = invoiceService;
        this.paymentService = paymentService;
        this.visitRepository = visitRepository;
        this.userRepository = userRepository;
        this.staffProfileRepository = staffProfileRepository;
        this.staffBranchAssignmentRepository = staffBranchAssignmentRepository;
    }

    private Long getCurrentUserId(HttpSession session) {
        if (session != null && session.getAttribute("loggedInUser") instanceof User u && u.getUserId() != null) {
            return u.getUserId();
        }
        throw new AccessDeniedException("Chưa đăng nhập. Vui lòng đăng nhập để tiếp tục.");
    }

    private void verifyBranchAccess(Long userId, Long branchId) {
        if (userId == null) {
            throw new AccessDeniedException("Chưa đăng nhập. Vui lòng đăng nhập để tiếp tục.");
        }
        boolean isAdmin = userRepository.hasActiveAdminRole(userId);
        if (isAdmin || branchId == null) {
            return;
        }
        StaffProfile staff = staffProfileRepository.findByUser_UserId(userId)
                .orElseThrow(() -> new AccessDeniedException("Tài khoản chưa được liên kết với nhân sự."));
        boolean hasAssignment = staffBranchAssignmentRepository.hasActiveAssignment(staff.getStaffId(), branchId, AssignmentStatus.ACTIVE);
        if (!hasAssignment) {
            throw new AccessDeniedException("Bạn không có quyền truy cập dữ liệu của chi nhánh ID: " + branchId);
        }
    }

    @PostMapping("/preview")
    public ResponseEntity<?> calculatePreview(@RequestBody InvoiceCreateRequestDTO request, HttpSession session) {
        try {
            if (session != null && session.getAttribute("loggedInUser") instanceof User u && u.getUserId() != null) {
                Long branchId = request.getBranchId();
                if (branchId == null && request.getVisitId() != null) {
                    branchId = visitRepository.findById(request.getVisitId())
                            .map(v -> v.getBranch().getBranchId()).orElse(null);
                }
                if (branchId != null) {
                    verifyBranchAccess(u.getUserId(), branchId);
                }
            }
            InvoiceCalculationPreviewDTO preview = invoiceService.calculatePreview(request);
            return ResponseEntity.ok(preview);
        } catch (AccessDeniedException ade) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("success", false, "error", ade.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/visits/{visitId}/unbilled-services")
    public ResponseEntity<?> getUnbilledServices(@PathVariable Long visitId, HttpSession session) {
        try {
            Long currentUserId = getCurrentUserId(session);
            Visit visit = visitRepository.findById(visitId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca khám ID: " + visitId));
            verifyBranchAccess(currentUserId, visit.getBranch().getBranchId());
            List<PerformedService> services = invoiceService.getUnbilledPerformedServices(visitId);
            return ResponseEntity.ok(services);
        } catch (AccessDeniedException ade) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("success", false, "error", ade.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/quick-pay")
    public ResponseEntity<?> quickPay(
            @PathVariable Long id,
            @RequestBody PaymentRequestDTO request,
            HttpSession session
    ) {
        try {
            request.setInvoiceId(id);
            Long cashierUserId = getCurrentUserId(session);
            Payment payment = paymentService.processPayment(request, cashierUserId);
            Invoice updated = invoiceService.getInvoiceById(id);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "paymentCode", payment.getPaymentCode(),
                    "paidAmount", updated.getPaidAmount(),
                    "remainingDebt", updated.getRemainingDebt(),
                    "paymentStatus", updated.getPaymentStatus().name()
            ));
        } catch (AccessDeniedException ade) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("success", false, "error", ade.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        }
    }
}
