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

/**
 * [Phân hệ]: Thanh toán & Hóa đơn (REST API)
 * [Vai trò]: Cung cấp các REST API dưới tiền tố `/api/invoices` phục vụ tính tạm tính hóa đơn thời gian thực (preview chiết khấu/khuyến mãi/voucher/điểm), lấy danh sách dịch vụ lâm sàng chưa lập hóa đơn theo lượt khám và thu tiền nhanh (quick-pay) ngay trên màn hình hóa đơn.
 */
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

    /**
     * [Hàm hỗ trợ]: Lấy `userId` của nhân viên đang đăng nhập từ `HttpSession`; ném `AccessDeniedException` nếu chưa đăng nhập.
     */
    private Long getCurrentUserId(HttpSession session) {
        if (session != null && session.getAttribute("loggedInUser") instanceof User u && u.getUserId() != null) {
            return u.getUserId();
        }
        throw new AccessDeniedException("Chưa đăng nhập. Vui lòng đăng nhập để tiếp tục.");
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra quyền truy cập dữ liệu theo chi nhánh (`branchId`) của nhân viên; cho qua nếu là Admin đang hoạt động, ngược lại kiểm tra phân công `ACTIVE` trong `StaffBranchAssignment` hoặc chi nhánh mặc định trên `User`.
     */
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
            boolean isFallbackBranch = staffBranchAssignmentRepository.findActiveBranchIdsByUserId(userId, AssignmentStatus.ACTIVE).isEmpty()
                    && userRepository.findById(userId).map(u -> u.getBranch() != null && branchId.equals(u.getBranch().getBranchId())).orElse(false);
            if (!isFallbackBranch) {
                throw new AccessDeniedException("Bạn không có quyền truy cập dữ liệu của chi nhánh ID: " + branchId);
            }
        }
    }

    /**
     * [Chức năng]: API `POST /api/invoices/preview` tính toán tạm tính tổng tiền hóa đơn (dịch vụ, thuốc, chiết khấu hạng thẻ, khuyến mãi/voucher, quy đổi điểm Loyalty và số điểm dự kiến tích thêm).
     * [Mục đích]: Phục vụ giao diện lập hóa đơn cập nhật số tiền real-time khi thu ngân thay đổi dịch vụ/thuốc/ưu đãi; kiểm tra quyền chi nhánh (suy từ `branchId` hoặc `visitId`) nếu có phiên đăng nhập rồi ủy quyền tính toán sang `InvoiceService.calculatePreview`, trả 403 khi sai quyền chi nhánh và 400 khi dữ liệu không hợp lệ.
     */
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

    /**
     * [Chức năng]: API `GET /api/invoices/visits/{visitId}/unbilled-services` lấy danh sách dịch vụ lâm sàng đã hoàn thành nhưng chưa lập hóa đơn của một lượt khám.
     * [Mục đích]: Phục vụ tải động các dòng dịch vụ đủ điều kiện tính tiền cho lượt khám `visitId`; kiểm tra đăng nhập, kiểm tra quyền chi nhánh của ca khám qua `verifyBranchAccess`, sau đó gọi `InvoiceService.getUnbilledPerformedServices`.
     */
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

    /**
     * [Chức năng]: API `POST /api/invoices/{id}/quick-pay` thực hiện thu tiền nhanh cho hóa đơn theo `id` và trả về kết quả công nợ/trạng thái thanh toán mới nhất.
     * [Mục đích]: Hỗ trợ thu ngân ghi nhận thanh toán trực tiếp qua AJAX mà không phải chuyển trang; gán `invoiceId` vào request, lấy `cashierUserId` từ session, gọi `PaymentService.processPayment` rồi đọc lại `Invoice` qua `InvoiceService.getInvoiceById` để trả `paymentCode`, `paidAmount`, `remainingDebt` và `paymentStatus`.
     */
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
