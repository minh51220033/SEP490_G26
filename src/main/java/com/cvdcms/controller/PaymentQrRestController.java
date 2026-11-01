package com.cvdcms.controller;

import com.cvdcms.DTO.BranchBankAccountOptionDTO;
import com.cvdcms.DTO.PaymentQrInfoDTO;
import com.cvdcms.entity.Invoice;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.InvoiceRepository;
import com.cvdcms.repository.StaffBranchAssignmentRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.BranchBankAccountService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/finance")
public class PaymentQrRestController {

    private final BranchBankAccountService branchBankAccountService;
    private final UserRepository userRepository;
    private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    private final InvoiceRepository invoiceRepository;
    private final BranchRepository branchRepository;

    @Autowired
    public PaymentQrRestController(
            BranchBankAccountService branchBankAccountService,
            UserRepository userRepository,
            StaffBranchAssignmentRepository staffBranchAssignmentRepository,
            InvoiceRepository invoiceRepository,
            BranchRepository branchRepository
    ) {
        this.branchBankAccountService = branchBankAccountService;
        this.userRepository = userRepository;
        this.staffBranchAssignmentRepository = staffBranchAssignmentRepository;
        this.invoiceRepository = invoiceRepository;
        this.branchRepository = branchRepository;
    }

    private void validateUserBranchAccess(HttpSession session, Long targetBranchId) {
        if (session == null || !(session.getAttribute("loggedInUser") instanceof User sessionUser)) {
            throw new AccessDeniedException("Vui lòng đăng nhập để tiếp tục.");
        }

        Long userId = sessionUser.getUserId();
        if (userId == null) {
            throw new AccessDeniedException("Thông tin tài khoản không hợp lệ.");
        }

        if (userRepository.hasActiveAdminRole(userId)) {
            return; // Admin có quyền truy cập toàn bộ chi nhánh
        }

        if (targetBranchId == null) {
            return;
        }

        List<Long> activeBranchIds = staffBranchAssignmentRepository.findActiveBranchIdsByUserId(userId, AssignmentStatus.ACTIVE);
        if (activeBranchIds == null || activeBranchIds.isEmpty()) {
            User dbUser = userRepository.findById(userId).orElse(null);
            if (dbUser != null && dbUser.getBranch() != null && dbUser.getBranch().getBranchId() != null) {
                activeBranchIds = List.of(dbUser.getBranch().getBranchId());
            }
        }

        if (activeBranchIds == null || activeBranchIds.isEmpty() || !activeBranchIds.contains(targetBranchId)) {
            throw new AccessDeniedException("Bạn không có quyền truy cập dữ liệu tài chính tại chi nhánh ID: " + targetBranchId);
        }
    }

    @GetMapping("/branches/{branchId}/bank-accounts")
    public ResponseEntity<?> getBranchBankAccounts(@PathVariable Long branchId, HttpSession session) {
        try {
            validateUserBranchAccess(session, branchId);
        } catch (AccessDeniedException ade) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", ade.getMessage()));
        }

        List<BranchBankAccountOptionDTO> accounts = branchBankAccountService.getActiveAccountOptions(branchId);
        return ResponseEntity.ok(accounts);
    }

    @GetMapping("/qr-info")
    public ResponseEntity<PaymentQrInfoDTO> getQrInfo(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long invoiceId,
            @RequestParam(required = false) Long profileId,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false, defaultValue = "0") BigDecimal amount,
            @RequestParam(required = false) String sessionRef,
            HttpSession session
    ) {
        Long effectiveBranchId = branchId;

        // 1. Khi có invoiceId, luôn lấy chi nhánh từ hóa đơn đã xác thực trong Database
        if (invoiceId != null) {
            Invoice inv = invoiceRepository.findById(invoiceId).orElse(null);
            if (inv == null) {
                return ResponseEntity.ok(PaymentQrInfoDTO.builder()
                        .success(false)
                        .errorMessage("Hóa đơn ID " + invoiceId + " không tồn tại trong hệ thống.")
                        .build());
            }
            Long invBranchId = inv.getBranch() != null ? inv.getBranch().getBranchId() : null;
            if (invBranchId == null) {
                return ResponseEntity.ok(PaymentQrInfoDTO.builder()
                        .success(false)
                        .errorMessage("Hóa đơn ID " + invoiceId + " chưa được liên kết với chi nhánh hợp lệ.")
                        .build());
            }
            // Nếu client gửi branchId mâu thuẫn với hóa đơn -> trả lỗi rõ ràng, không tạo QR cho chi nhánh khác
            if (branchId != null && !branchId.equals(invBranchId)) {
                return ResponseEntity.ok(PaymentQrInfoDTO.builder()
                        .success(false)
                        .errorMessage("Chi nhánh yêu cầu (ID: " + branchId + ") mâu thuẫn với chi nhánh của hóa đơn (ID: " + invBranchId + ").")
                        .build());
            }
            effectiveBranchId = invBranchId;
        } else {
            // Không có invoiceId (nạp tạm ứng): bắt buộc có branchId
            if (effectiveBranchId == null) {
                return ResponseEntity.ok(PaymentQrInfoDTO.builder()
                        .success(false)
                        .errorMessage("Không xác định được chi nhánh nhận tiền chuyển khoản.")
                        .build());
            }
            if (!branchRepository.existsById(effectiveBranchId)) {
                return ResponseEntity.ok(PaymentQrInfoDTO.builder()
                        .success(false)
                        .errorMessage("Chi nhánh ID " + effectiveBranchId + " không tồn tại trong hệ thống.")
                        .build());
            }
        }

        // 2. Kiểm tra quyền truy cập chi nhánh của người đăng nhập
        try {
            validateUserBranchAccess(session, effectiveBranchId);
        } catch (AccessDeniedException ade) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(PaymentQrInfoDTO.builder()
                            .success(false)
                            .errorMessage(ade.getMessage())
                            .build());
        }

        // 3. Lấy thông tin VietQR từ Service
        PaymentQrInfoDTO qrInfo = branchBankAccountService.getQrInfo(
                effectiveBranchId, invoiceId, profileId, accountId, amount, sessionRef
        );
        return ResponseEntity.ok(qrInfo);
    }
}
