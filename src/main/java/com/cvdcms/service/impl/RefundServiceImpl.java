package com.cvdcms.service.impl;

import com.cvdcms.DTO.RefundRequestDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.entity.enums.InvoicePaymentStatus;
import com.cvdcms.entity.enums.RefundMethod;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.repository.*;
import com.cvdcms.service.RefundService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class RefundServiceImpl implements RefundService {

    private final RefundRepository refundRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceAuditLogRepository invoiceAuditLogRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final BranchRepository branchRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    private final UserRepository userRepository;
    private final com.cvdcms.service.LoyaltyService loyaltyService;

    @org.springframework.beans.factory.annotation.Autowired
    public RefundServiceImpl(
            RefundRepository refundRepository,
            InvoiceRepository invoiceRepository,
            InvoiceAuditLogRepository invoiceAuditLogRepository,
            PatientProfileRepository patientProfileRepository,
            BranchRepository branchRepository,
            StaffProfileRepository staffProfileRepository,
            StaffBranchAssignmentRepository staffBranchAssignmentRepository,
            UserRepository userRepository,
            com.cvdcms.service.LoyaltyService loyaltyService
    ) {
        this.refundRepository = refundRepository;
        this.invoiceRepository = invoiceRepository;
        this.invoiceAuditLogRepository = invoiceAuditLogRepository;
        this.patientProfileRepository = patientProfileRepository;
        this.branchRepository = branchRepository;
        this.staffProfileRepository = staffProfileRepository;
        this.staffBranchAssignmentRepository = staffBranchAssignmentRepository;
        this.userRepository = userRepository;
        this.loyaltyService = loyaltyService;
    }

    public RefundServiceImpl(
            RefundRepository refundRepository,
            InvoiceRepository invoiceRepository,
            InvoiceAuditLogRepository invoiceAuditLogRepository,
            PatientProfileRepository patientProfileRepository,
            BranchRepository branchRepository,
            StaffProfileRepository staffProfileRepository,
            StaffBranchAssignmentRepository staffBranchAssignmentRepository,
            UserRepository userRepository
    ) {
        this(refundRepository, invoiceRepository, invoiceAuditLogRepository,
                patientProfileRepository, branchRepository, staffProfileRepository,
                staffBranchAssignmentRepository, userRepository, null);
    }

    /**
     * Lấy hồ sơ nhân sự hợp lệ của người đăng nhập.
     * Kiểm tra trạng thái hoạt động của User, StaffProfile và phân công chi nhánh.
     * Tuyệt đối không lấy nhân viên đầu tiên, không tạo dummy profile.
     */
    private StaffProfile getValidStaffProfile(Long userId, Long branchId) {
        if (userId == null) {
            throw new IllegalArgumentException("Không xác định được danh tính người dùng thực hiện (userId is null).");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản người dùng ID: " + userId));

        if (!Boolean.TRUE.equals(user.getIsActive()) || !"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new IllegalStateException("Tài khoản người dùng đang bị khóa hoặc ngưng hoạt động.");
        }

        StaffProfile staff = staffProfileRepository.findByUser_UserId(userId)
                .orElseThrow(() -> new IllegalStateException("Tài khoản chưa được liên kết với hồ sơ nhân sự (StaffProfile). Vui lòng liên hệ Quản trị viên."));

        if (staff.getStatus() != StaffStatus.ACTIVE) {
            throw new IllegalStateException("Hồ sơ nhân viên (" + staff.getStaffCode() + " - " + staff.getFullName() + ") không ở trạng thái hoạt động (ACTIVE).");
        }

        boolean isAdmin = userRepository.hasActiveAdminRole(userId);
        if (!isAdmin && branchId != null) {
            boolean hasAssignment = staffBranchAssignmentRepository.hasActiveAssignment(staff.getStaffId(), branchId, AssignmentStatus.ACTIVE);
            if (!hasAssignment) {
                throw new IllegalStateException("Nhân sự (" + staff.getFullName() + ") không có phân công hoạt động hợp lệ tại chi nhánh ID: " + branchId);
            }
        }

        return staff;
    }

    @Override
    public Refund processRefund(RefundRequestDTO request, Long staffUserId) {
        if (request == null) {
            throw new IllegalArgumentException("Dữ liệu hoàn tiền không hợp lệ.");
        }
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Số tiền hoàn trả phải lớn hơn 0.");
        }
        if (request.getReason() == null || request.getReason().trim().isEmpty()) {
            throw new IllegalArgumentException("Lý do hoàn tiền không được để trống.");
        }

        Invoice invoice = null;
        PatientProfile patient;
        Branch branch;

        if (request.getInvoiceId() != null) {
            invoice = invoiceRepository.findByIdForUpdate(request.getInvoiceId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn ID: " + request.getInvoiceId()));

            // Sửa để không hoàn vượt số được hoàn; Không ép paidAmount về 0 để che việc hoàn vượt
            if (request.getAmount().compareTo(invoice.getPaidAmount()) > 0) {
                throw new IllegalArgumentException("Số tiền hoàn (" + request.getAmount() + " đ) vượt quá số tiền đã thực thu của hóa đơn (" + invoice.getPaidAmount() + " đ).");
            }

            patient = invoice.getPatientProfile();
            branch = invoice.getBranch();
        } else {
            if (request.getProfileId() == null || request.getBranchId() == null) {
                throw new IllegalArgumentException("Thiếu thông tin bệnh nhân hoặc chi nhánh hoàn tiền.");
            }
            // Khóa bi quan PatientProfile để kiểm tra nguồn và trừ số dư tạm ứng an toàn
            patient = patientProfileRepository.findByIdForUpdate(request.getProfileId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hồ sơ bệnh nhân ID: " + request.getProfileId()));
            branch = branchRepository.findById(request.getBranchId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh ID: " + request.getBranchId()));

            BigDecimal currentDeposit = patient.getDepositBalance() != null ? patient.getDepositBalance() : BigDecimal.ZERO;
            if (request.getAmount().compareTo(currentDeposit) > 0) {
                throw new IllegalArgumentException("Số tiền hoàn (" + request.getAmount() + " đ) vượt quá số dư ví tạm ứng hiện tại (" + currentDeposit + " đ).");
            }

            // Trừ số dư tạm ứng trong cùng transaction
            patient.setDepositBalance(currentDeposit.subtract(request.getAmount()));
            patientProfileRepository.save(patient);
        }

        StaffProfile staff = getValidStaffProfile(staffUserId, branch.getBranchId());

        Refund refund = new Refund();
        refund.setRefundCode("HT" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        refund.setInvoice(invoice);
        refund.setPatientProfile(patient);
        refund.setBranch(branch);
        refund.setAmount(request.getAmount());
        refund.setRefundMethod(request.getRefundMethod() != null ? request.getRefundMethod() : RefundMethod.CASH);
        refund.setReason(request.getReason() + (invoice == null ? " (Hoàn tiền từ số dư ví tạm ứng)" : ""));
        refund.setRefundedBy(staff);

        Refund savedRefund = refundRepository.save(refund);

        if (invoice != null) {
            BigDecimal oldPaid = invoice.getPaidAmount();
            BigDecimal newPaid = oldPaid.subtract(request.getAmount());
            invoice.setPaidAmount(newPaid);
            BigDecimal newDebt = invoice.getTotalAmount().subtract(newPaid);
            invoice.setRemainingDebt(newDebt);

            if (newDebt.compareTo(BigDecimal.ZERO) > 0) {
                invoice.setPaymentStatus(newPaid.compareTo(BigDecimal.ZERO) > 0 ? InvoicePaymentStatus.PARTIAL : InvoicePaymentStatus.UNPAID);
            } else {
                invoice.setPaymentStatus(InvoicePaymentStatus.PAID);
            }
            invoiceRepository.save(invoice);

            InvoiceAuditLog log = new InvoiceAuditLog();
            log.setInvoice(invoice);
            log.setActionType("REFUND");
            log.setOldPaidAmount(oldPaid);
            log.setNewPaidAmount(newPaid);
            log.setReason("Hoàn tiền (" + refund.getRefundCode() + "): " + request.getReason());
            log.setDetails("Số tiền hoàn: " + request.getAmount() + " đ qua " + refund.getRefundMethod() + ". Nợ cập nhật lại: " + invoice.getRemainingDebt() + " đ");
            log.setPerformedBy(staff);
            invoiceAuditLogRepository.save(log);
        }

        if (loyaltyService != null) {
            loyaltyService.recordRefundAdjustment(savedRefund);
        }

        return savedRefund;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Refund> getRefunds(
            Long branchId,
            RefundMethod refundMethod,
            String keyword,
            LocalDateTime startDate,
            LocalDateTime endDate,
            Pageable pageable
    ) {
        return refundRepository.findFilteredRefunds(branchId, refundMethod, startDate, endDate, keyword, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Refund> getRefundsByInvoiceId(Long invoiceId) {
        return refundRepository.findByInvoice_InvoiceIdOrderByRefundedAtDesc(invoiceId);
    }

    @Override
    @Transactional(readOnly = true)
    public Refund getRefundById(Long refundId) {
        return refundRepository.findWithDetailsById(refundId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu hoàn tiền ID: " + refundId));
    }
}
