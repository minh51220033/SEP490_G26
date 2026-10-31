package com.cvdcms.service.impl;

import com.cvdcms.DTO.RefundRequestDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.entity.enums.InvoicePaymentStatus;
import com.cvdcms.entity.enums.InvoiceStatus;
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
import java.util.Optional;
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

        // Chống gửi trùng request bằng Idempotency Key
        String idempotencyKey = (request.getIdempotencyKey() != null && !request.getIdempotencyKey().trim().isEmpty())
                ? request.getIdempotencyKey().trim()
                : UUID.randomUUID().toString();

        if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().trim().isEmpty()) {
            Optional<Refund> existingOpt = refundRepository.findByIdempotencyKey(idempotencyKey);
            if (existingOpt.isPresent()) {
                Refund existing = existingOpt.get();
                // 1. Kiểm tra quyền actor đối với chi nhánh của giao dịch đã ghi nhận
                if (existing.getBranch() != null) {
                    getValidStaffProfile(staffUserId, existing.getBranch().getBranchId());
                } else if (staffUserId != null) {
                    getValidStaffProfile(staffUserId, null);
                }

                // 2. Đối chiếu toàn bộ thông tin nghiệp vụ
                boolean matchesAmount = existing.getAmount() != null && existing.getAmount().compareTo(request.getAmount()) == 0;
                boolean matchesPatient = request.getProfileId() == null || (existing.getPatientProfile() != null && existing.getPatientProfile().getProfileId().equals(request.getProfileId()));
                boolean matchesInvoice = (request.getInvoiceId() == null && existing.getInvoice() == null)
                        || (request.getInvoiceId() != null && existing.getInvoice() != null && existing.getInvoice().getInvoiceId().equals(request.getInvoiceId()));
                boolean matchesBranch = request.getBranchId() == null || (existing.getBranch() != null && existing.getBranch().getBranchId().equals(request.getBranchId()));
                boolean matchesMethod = request.getRefundMethod() == null || existing.getRefundMethod() == request.getRefundMethod();
                boolean reqReduce = Boolean.TRUE.equals(request.getReduceInvoiceTotal());
                boolean existReduce = Boolean.TRUE.equals(existing.getReduceInvoiceTotal());
                boolean matchesReduce = (reqReduce == existReduce);

                if (matchesAmount && matchesPatient && matchesInvoice && matchesBranch && matchesMethod && matchesReduce) {
                    return existing;
                } else {
                    throw new IllegalStateException("Xung đột khóa idempotency: Khóa hoàn tiền (" + request.getIdempotencyKey() + ") đã được sử dụng cho một giao dịch khác với thông tin không trùng khớp.");
                }
            }
        }

        Invoice invoice = null;
        PatientProfile patient;
        Branch branch;

        if (request.getInvoiceId() != null) {
            invoice = invoiceRepository.findByIdForUpdate(request.getInvoiceId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn ID: " + request.getInvoiceId()));

            if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
                throw new IllegalStateException("Không thể hoàn tiền cho hóa đơn đã bị hủy.");
            }

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
        refund.setIdempotencyKey(idempotencyKey);
        refund.setInvoice(invoice);
        refund.setPatientProfile(patient);
        refund.setBranch(branch);
        refund.setAmount(request.getAmount());
        refund.setRefundMethod(request.getRefundMethod() != null ? request.getRefundMethod() : RefundMethod.CASH);
        refund.setReason(request.getReason() + (invoice == null ? " (Hoàn tiền từ số dư ví tạm ứng)" : (Boolean.TRUE.equals(request.getReduceInvoiceTotal()) ? " (Kèm giảm nghĩa vụ hóa đơn)" : "")));
        refund.setReduceInvoiceTotal(Boolean.TRUE.equals(request.getReduceInvoiceTotal()));
        refund.setRefundedBy(staff);

        Refund savedRefund = refundRepository.save(refund);

        if (invoice != null) {
            BigDecimal oldPaid = invoice.getPaidAmount();
            BigDecimal newPaid = oldPaid.subtract(request.getAmount());
            invoice.setPaidAmount(newPaid);

            if (Boolean.TRUE.equals(request.getReduceInvoiceTotal())) {
                // Thao tác kết hợp: Hoàn tiền kèm giảm giá trị hóa đơn (giảm nghĩa vụ viện phí)
                BigDecimal oldTotal = invoice.getTotalAmount();
                BigDecimal oldDiscount = Optional.ofNullable(invoice.getDiscountAmount()).orElse(BigDecimal.ZERO);
                BigDecimal newDiscount = oldDiscount.add(request.getAmount());
                invoice.setDiscountAmount(newDiscount);

                BigDecimal totalService = Optional.ofNullable(invoice.getTotalServiceAmount()).orElse(BigDecimal.ZERO);
                BigDecimal totalMedicine = Optional.ofNullable(invoice.getTotalMedicineAmount()).orElse(BigDecimal.ZERO);
                BigDecimal surcharge = Optional.ofNullable(invoice.getSurchargeAmount()).orElse(BigDecimal.ZERO);
                BigDecimal promoDiscount = Optional.ofNullable(invoice.getPromotionDiscount()).orElse(BigDecimal.ZERO);
                BigDecimal pointDiscount = Optional.ofNullable(invoice.getPointDiscount()).orElse(BigDecimal.ZERO);

                BigDecimal beforeDiscount = totalService.add(totalMedicine).add(surcharge);
                BigDecimal newTotal;
                if (beforeDiscount.compareTo(BigDecimal.ZERO) > 0) {
                    newTotal = beforeDiscount.subtract(newDiscount).subtract(promoDiscount).subtract(pointDiscount).max(BigDecimal.ZERO);
                } else if (oldTotal != null) {
                    newTotal = oldTotal.subtract(request.getAmount()).max(BigDecimal.ZERO);
                } else {
                    newTotal = BigDecimal.ZERO;
                }

                invoice.setTotalAmount(newTotal);
                BigDecimal newDebt = newTotal.subtract(newPaid).max(BigDecimal.ZERO);
                invoice.setRemainingDebt(newDebt);

                if (newDebt.compareTo(BigDecimal.ZERO) > 0) {
                    invoice.setPaymentStatus(newPaid.compareTo(BigDecimal.ZERO) > 0 ? InvoicePaymentStatus.PARTIAL : InvoicePaymentStatus.UNPAID);
                } else {
                    invoice.setPaymentStatus(InvoicePaymentStatus.PAID);
                }
                invoiceRepository.save(invoice);

                // Lưu Audit 1: REFUND
                InvoiceAuditLog refundLog = new InvoiceAuditLog();
                refundLog.setInvoice(invoice);
                refundLog.setActionType("REFUND");
                refundLog.setOldPaidAmount(oldPaid);
                refundLog.setNewPaidAmount(newPaid);
                refundLog.setReason("Hoàn tiền (" + refund.getRefundCode() + "): " + request.getReason());
                refundLog.setDetails("Hoàn tiền thực thu: " + request.getAmount() + " đ qua " + refund.getRefundMethod() + ". Đã trả còn: " + newPaid + " đ");
                refundLog.setPerformedBy(staff);
                invoiceAuditLogRepository.save(refundLog);

                // Lưu Audit 2: ADJUST (Giảm giá trị hóa đơn)
                InvoiceAuditLog adjustLog = new InvoiceAuditLog();
                adjustLog.setInvoice(invoice);
                adjustLog.setActionType("ADJUST");
                adjustLog.setOldTotalAmount(oldTotal);
                adjustLog.setNewTotalAmount(newTotal);
                adjustLog.setReason("Giảm giá trị hóa đơn do hoàn hủy dịch vụ (" + refund.getRefundCode() + ")");
                adjustLog.setDetails("Tổng tiền điều chỉnh giảm từ " + oldTotal + " đ về " + newTotal + " đ. Nợ còn lại: " + invoice.getRemainingDebt() + " đ");
                adjustLog.setPerformedBy(staff);
                invoiceAuditLogRepository.save(adjustLog);
            } else {
                // Hoàn tiền đơn thuần: giữ nguyên totalAmount, tăng remainingDebt
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
    public List<Refund> getRefundsByProfileId(Long profileId) {
        return refundRepository.findByPatientProfile_ProfileIdOrderByRefundedAtDesc(profileId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Refund> getRefundsByProfileId(Long profileId, List<Long> allowedBranchIds) {
        return refundRepository.findByPatientProfile_ProfileIdAndAllowedBranches(profileId, allowedBranchIds);
    }

    @Override
    @Transactional(readOnly = true)
    public Refund getRefundById(Long refundId) {
        return refundRepository.findWithDetailsById(refundId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu hoàn tiền ID: " + refundId));
    }
}
