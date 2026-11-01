package com.cvdcms.service.impl;

import com.cvdcms.DTO.PaymentRequestDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.AppointmentLifecycleService;
import com.cvdcms.service.PaymentService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceAuditLogRepository invoiceAuditLogRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final BranchRepository branchRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    private final PatientLoyaltyWalletRepository patientLoyaltyWalletRepository;
    private final PointTransactionRepository pointTransactionRepository;
    private final MembershipTierRepository membershipTierRepository;
    private final UserRepository userRepository;
    private final com.cvdcms.service.LoyaltyService loyaltyService;
    private final com.cvdcms.service.InvoiceService invoiceService;
    private final VisitRepository visitRepository;
    private final AppointmentLifecycleService appointmentLifecycleService;

    @org.springframework.beans.factory.annotation.Autowired
    public PaymentServiceImpl(
            PaymentRepository paymentRepository,
            InvoiceRepository invoiceRepository,
            InvoiceAuditLogRepository invoiceAuditLogRepository,
            PatientProfileRepository patientProfileRepository,
            BranchRepository branchRepository,
            StaffProfileRepository staffProfileRepository,
            StaffBranchAssignmentRepository staffBranchAssignmentRepository,
            PatientLoyaltyWalletRepository patientLoyaltyWalletRepository,
            PointTransactionRepository pointTransactionRepository,
            MembershipTierRepository membershipTierRepository,
            UserRepository userRepository,
            com.cvdcms.service.LoyaltyService loyaltyService,
            @org.springframework.context.annotation.Lazy com.cvdcms.service.InvoiceService invoiceService,
            VisitRepository visitRepository,
            AppointmentLifecycleService appointmentLifecycleService
    ) {
        this.paymentRepository = paymentRepository;
        this.invoiceRepository = invoiceRepository;
        this.invoiceAuditLogRepository = invoiceAuditLogRepository;
        this.patientProfileRepository = patientProfileRepository;
        this.branchRepository = branchRepository;
        this.staffProfileRepository = staffProfileRepository;
        this.staffBranchAssignmentRepository = staffBranchAssignmentRepository;
        this.patientLoyaltyWalletRepository = patientLoyaltyWalletRepository;
        this.pointTransactionRepository = pointTransactionRepository;
        this.membershipTierRepository = membershipTierRepository;
        this.userRepository = userRepository;
        this.loyaltyService = loyaltyService;
        this.invoiceService = invoiceService;
        this.visitRepository = visitRepository;
        this.appointmentLifecycleService = appointmentLifecycleService;
    }

    public PaymentServiceImpl(
            PaymentRepository paymentRepository,
            InvoiceRepository invoiceRepository,
            InvoiceAuditLogRepository invoiceAuditLogRepository,
            PatientProfileRepository patientProfileRepository,
            BranchRepository branchRepository,
            StaffProfileRepository staffProfileRepository,
            StaffBranchAssignmentRepository staffBranchAssignmentRepository,
            PatientLoyaltyWalletRepository patientLoyaltyWalletRepository,
            PointTransactionRepository pointTransactionRepository,
            MembershipTierRepository membershipTierRepository,
            UserRepository userRepository,
            com.cvdcms.service.LoyaltyService loyaltyService
    ) {
        this(paymentRepository, invoiceRepository, invoiceAuditLogRepository,
                patientProfileRepository, branchRepository, staffProfileRepository,
                staffBranchAssignmentRepository, patientLoyaltyWalletRepository,
                pointTransactionRepository, membershipTierRepository, userRepository, loyaltyService,
                null, null, null);
    }

    public PaymentServiceImpl(
            PaymentRepository paymentRepository,
            InvoiceRepository invoiceRepository,
            InvoiceAuditLogRepository invoiceAuditLogRepository,
            PatientProfileRepository patientProfileRepository,
            BranchRepository branchRepository,
            StaffProfileRepository staffProfileRepository,
            StaffBranchAssignmentRepository staffBranchAssignmentRepository,
            PatientLoyaltyWalletRepository patientLoyaltyWalletRepository,
            PointTransactionRepository pointTransactionRepository,
            MembershipTierRepository membershipTierRepository,
            UserRepository userRepository
    ) {
        this(paymentRepository, invoiceRepository, invoiceAuditLogRepository,
                patientProfileRepository, branchRepository, staffProfileRepository,
                staffBranchAssignmentRepository, patientLoyaltyWalletRepository,
                pointTransactionRepository, membershipTierRepository, userRepository, null,
                null, null, null);
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
    public Payment processPayment(PaymentRequestDTO request, Long cashierUserId) {
        if (request == null) {
            throw new IllegalArgumentException("Dữ liệu thanh toán không hợp lệ.");
        }

        // 1. Bắt buộc có invoiceId hợp lệ
        if (request.getInvoiceId() == null) {
            throw new IllegalArgumentException("Giao dịch thanh toán bắt buộc phải gắn với mã hóa đơn (invoiceId).");
        }

        // 2. Kiểm tra số tiền thanh toán > 0
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Số tiền thanh toán phải lớn hơn 0.");
        }

        // 3. Khóa bi quan Invoice để cập nhật số nợ an toàn và chống race condition
        Invoice invoice = invoiceRepository.findByIdForUpdate(request.getInvoiceId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn ID: " + request.getInvoiceId()));

        if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
            throw new IllegalStateException("Hóa đơn đã bị hủy, không thể tiếp nhận thanh toán.");
        }

        Branch branch = invoice.getBranch();
        PatientProfile patient = invoice.getPatientProfile();

        // 4. KIỂM TRA QUYỀN THU NGÂN TRƯỚC TIÊN (không trả dữ liệu cũ khi chưa xác thực quyền)
        StaffProfile cashier = getValidStaffProfile(cashierUserId, branch != null ? branch.getBranchId() : null);

        // 5. KIỂM TRA IDEMPOTENCY KEY RIÊNG BIỆT (Áp dụng cả tiền mặt và chuyển khoản)
        String idempotencyKey = request.getIdempotencyKey();
        if (idempotencyKey != null && !idempotencyKey.trim().isEmpty()) {
            idempotencyKey = idempotencyKey.trim();
            Optional<Payment> existingOpt = paymentRepository.findByIdempotencyKey(idempotencyKey);
            if (existingOpt.isPresent()) {
                Payment existing = existingOpt.get();
                // Đối chiếu toàn bộ nội dung: invoice, patient, branch, amount, paymentMethod, paymentType
                boolean matches = existing.getInvoice() != null && existing.getInvoice().getInvoiceId().equals(invoice.getInvoiceId())
                        && (patient == null || (existing.getPatientProfile() != null && existing.getPatientProfile().getProfileId().equals(patient.getProfileId())))
                        && (branch == null || (existing.getBranch() != null && existing.getBranch().getBranchId().equals(branch.getBranchId())))
                        && existing.getAmount().compareTo(request.getAmount()) == 0
                        && (request.getPaymentMethod() == null || existing.getPaymentMethod() == request.getPaymentMethod())
                        && existing.getPaymentType() == PaymentType.PAYMENT;

                if (matches) {
                    return existing; // Trả lại kết quả cũ, không ghi tiền lần hai (kể cả khi nợ đã về 0)
                } else {
                    throw new IllegalStateException("Khóa giao dịch (idempotencyKey: " + idempotencyKey + ") đã được sử dụng cho giao dịch khác.");
                }
            }
        }

        // Kiểm tra thêm transactionRef ngân hàng nếu có
        if (request.getTransactionRef() != null && !request.getTransactionRef().trim().isEmpty()) {
            Optional<Payment> existingRefOpt = paymentRepository.findByTransactionRef(request.getTransactionRef().trim());
            if (existingRefOpt.isPresent()) {
                Payment existingRef = existingRefOpt.get();
                boolean matches = existingRef.getInvoice() != null && existingRef.getInvoice().getInvoiceId().equals(invoice.getInvoiceId())
                        && existingRef.getAmount().compareTo(request.getAmount()) == 0;
                if (matches) {
                    return existingRef;
                } else {
                    throw new IllegalStateException("Mã tham chiếu giao dịch (" + request.getTransactionRef() + ") đã được sử dụng với nội dung khác.");
                }
            }
        }

        // 6. Kiểm tra trạng thái nợ của hóa đơn
        if (invoice.getPaymentStatus() == InvoicePaymentStatus.PAID || invoice.getRemainingDebt().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Hóa đơn này đã được thanh toán toàn bộ. Số nợ hiện tại là 0 đ.");
        }
        if (request.getAmount().compareTo(invoice.getRemainingDebt()) > 0) {
            throw new IllegalArgumentException("Số tiền thanh toán (" + request.getAmount() + " đ) vượt quá số nợ còn lại (" + invoice.getRemainingDebt() + " đ).");
        }

        // 7. Nếu thanh toán bằng DEPOSIT_WALLET (Ví tạm ứng)
        Long patientProfileId = patient.getProfileId();
        if (request.getPaymentMethod() == PaymentMethod.DEPOSIT_WALLET) {
            PatientProfile lockedPatient = patientProfileRepository.findByIdForUpdate(patientProfileId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hồ sơ bệnh nhân ID: " + patientProfileId));

            if (lockedPatient.getDepositBalance().compareTo(request.getAmount()) < 0) {
                throw new IllegalStateException("Số dư tạm ứng (" + lockedPatient.getDepositBalance() + " đ) không đủ để thanh toán số tiền (" + request.getAmount() + " đ).");
            }

            lockedPatient.setDepositBalance(lockedPatient.getDepositBalance().subtract(request.getAmount()));
            patientProfileRepository.save(lockedPatient);
            patient = lockedPatient;
        }

        // 8. Tạo bản ghi Payment với idempotencyKey
        Payment payment = new Payment();
        payment.setPaymentCode("TT" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        payment.setInvoice(invoice);
        payment.setPatientProfile(patient);
        payment.setBranch(branch);
        payment.setCashier(cashier);
        payment.setPaymentType(PaymentType.PAYMENT);
        payment.setPaymentMethod(request.getPaymentMethod() != null ? request.getPaymentMethod() : PaymentMethod.CASH);
        payment.setAmount(request.getAmount());
        payment.setTransactionRef(request.getTransactionRef());
        payment.setIdempotencyKey(idempotencyKey);
        payment.setNotes(request.getNotes());

        Payment savedPayment = paymentRepository.save(payment);

        // 9. Cập nhật hóa đơn
        BigDecimal oldPaid = invoice.getPaidAmount();
        BigDecimal newPaid = oldPaid.add(request.getAmount());
        BigDecimal newDebt = invoice.getTotalAmount().subtract(newPaid);

        invoice.setPaidAmount(newPaid);
        invoice.setRemainingDebt(newDebt);

        if (newDebt.compareTo(BigDecimal.ZERO) <= 0) {
            invoice.setRemainingDebt(BigDecimal.ZERO);
            invoice.setPaymentStatus(InvoicePaymentStatus.PAID);
            if (invoiceService != null) {
                invoiceService.finalizeInvoiceBenefits(invoice);
            }
            completeVisit(invoice, cashierUserId);
        } else {
            invoice.setPaymentStatus(InvoicePaymentStatus.PARTIAL);
        }
        invoiceRepository.save(invoice);

        // 10. Ghi Audit Log
        InvoiceAuditLog log = new InvoiceAuditLog();
        log.setInvoice(invoice);
        log.setActionType("PAYMENT");
        log.setOldPaidAmount(oldPaid);
        log.setNewPaidAmount(newPaid);
        log.setReason("Thanh toán hóa đơn (" + savedPayment.getPaymentCode() + ")");
        log.setDetails("Phương thức: " + savedPayment.getPaymentMethod() + ", Số tiền: " + savedPayment.getAmount() + " đ, Nợ còn lại: " + invoice.getRemainingDebt() + " đ");
        log.setPerformedBy(cashier);
        invoiceAuditLogRepository.save(log);

        // 11. Tích điểm và nâng hạng thành viên (chỉ khi không dùng ví tạm ứng để tránh cộng trùng điểm)
        if (request.getPaymentMethod() != PaymentMethod.DEPOSIT_WALLET) {
            if (loyaltyService != null) {
                loyaltyService.recordPaymentPoints(savedPayment);
            } else {
                updateLoyaltyAndTier(patient, request.getAmount(), invoice);
            }
        }

        return savedPayment;
    }

    private void completeVisit(Invoice invoice, Long actorUserId) {
        if (invoice.getVisit() == null || visitRepository == null || appointmentLifecycleService == null) {
            return;
        }
        Visit visit = visitRepository.lockById(invoice.getVisit().getVisitId())
                .orElseThrow(() -> new IllegalStateException("Không tìm thấy lượt khám của hóa đơn."));
        if (visit.getStatus() == VisitStatus.COMPLETED) {
            appointmentLifecycleService.completeByVisit(visit.getVisitId(), actorUserId);
            return;
        }
        if (visit.getStatus() != VisitStatus.WAITING_PAYMENT) {
            throw new IllegalStateException("Chỉ hoàn tất lượt khám đang chờ thanh toán.");
        }
        visit.setStatus(VisitStatus.COMPLETED);
        visit.setCompletedAt(LocalDateTime.now());
        visitRepository.saveAndFlush(visit);
        appointmentLifecycleService.completeByVisit(visit.getVisitId(), actorUserId);
    }

    private void updateLoyaltyAndTier(PatientProfile patient, BigDecimal amount, Invoice invoice) {
        PatientLoyaltyWallet wallet = patientLoyaltyWalletRepository.findByPatientProfile_ProfileId(patient.getProfileId())
                .orElseGet(() -> {
                    PatientLoyaltyWallet w = new PatientLoyaltyWallet();
                    w.setPatientProfile(patient);
                    w.setCurrentPoints(0);
                    PatientLoyaltyWallet saved = patientLoyaltyWalletRepository.save(w);
                    return saved != null ? saved : w;
                });

        BigDecimal spendPerPoint = new BigDecimal("10000.00");
        if (patient.getMembershipTier() != null && patient.getMembershipTier().getSpendPerPoint() != null) {
            spendPerPoint = patient.getMembershipTier().getSpendPerPoint();
        }

        int earnedPoints = amount.divide(spendPerPoint, 0, RoundingMode.DOWN).intValue();
        if (earnedPoints > 0) {
            wallet.setCurrentPoints(wallet.getCurrentPoints() + earnedPoints);
        }
        wallet.setTotalSpentAmount(wallet.getTotalSpentAmount().add(amount));
        patientLoyaltyWalletRepository.save(wallet);

        if (earnedPoints > 0) {
            PointTransaction pt = new PointTransaction();
            pt.setPatientProfile(patient);
            pt.setInvoice(invoice);
            pt.setTransactionType(PointTransactionType.EARN);
            pt.setPoints(earnedPoints);
            pt.setBalanceAfter(wallet.getCurrentPoints());
            pt.setReason("Tích " + earnedPoints + " điểm từ thanh toán " + amount + " đ");
            pointTransactionRepository.save(pt);
        }

        // Kiểm tra nâng hạng thành viên
        List<MembershipTier> tiers = membershipTierRepository.findByIsActiveTrueOrderByMinTotalSpentAsc();
        MembershipTier highestQualifying = null;
        for (MembershipTier tier : tiers) {
            if (wallet.getTotalSpentAmount().compareTo(tier.getMinTotalSpent()) >= 0) {
                highestQualifying = tier;
            }
        }
        if (highestQualifying != null && (patient.getMembershipTier() == null || !highestQualifying.getTierId().equals(patient.getMembershipTier().getTierId()))) {
            patient.setMembershipTier(highestQualifying);
            patientProfileRepository.save(patient);
        }
    }

    @Override
    public Payment processAdvanceDeposit(PaymentRequestDTO request, Long cashierUserId) {
        if (request == null) {
            throw new IllegalArgumentException("Dữ liệu nộp tạm ứng không hợp lệ.");
        }

        // 1. Số tiền phải lớn hơn 0
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Số tiền tạm ứng phải lớn hơn 0.");
        }

        // 2. Không cho nạp tạm ứng bằng chính DEPOSIT_WALLET
        if (request.getPaymentMethod() == PaymentMethod.DEPOSIT_WALLET) {
            throw new IllegalArgumentException("Không thể nạp tiền tạm ứng bằng nguồn Ví tạm ứng (DEPOSIT_WALLET). Chỉ chấp nhận tiền mặt hoặc chuyển khoản.");
        }

        // 3. Validate bệnh nhân và chi nhánh
        if (request.getProfileId() == null) {
            throw new IllegalArgumentException("Vui lòng chọn hồ sơ bệnh nhân để nộp tạm ứng.");
        }
        if (request.getBranchId() == null) {
            throw new IllegalArgumentException("Vui lòng chọn chi nhánh tiếp nhận tạm ứng.");
        }

        // 3. Khóa bi quan PatientProfile để kiểm tra và cộng số dư an toàn
        PatientProfile patient = patientProfileRepository.findByIdForUpdate(request.getProfileId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hồ sơ bệnh nhân ID: " + request.getProfileId()));

        Branch branch = branchRepository.findById(request.getBranchId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh ID: " + request.getBranchId()));

        // 4. KIỂM TRA QUYỀN THU NGÂN TRƯỚC TIÊN
        StaffProfile cashier = getValidStaffProfile(cashierUserId, branch.getBranchId());

        // 5. KIỂM TRA IDEMPOTENCY KEY RIÊNG BIỆT (cả tiền mặt và chuyển khoản)
        String idempotencyKey = request.getIdempotencyKey();
        if (idempotencyKey != null && !idempotencyKey.trim().isEmpty()) {
            idempotencyKey = idempotencyKey.trim();
            Optional<Payment> existingOpt = paymentRepository.findByIdempotencyKey(idempotencyKey);
            if (existingOpt.isPresent()) {
                Payment existing = existingOpt.get();
                // Đối chiếu toàn bộ nội dung: patient, branch, amount, paymentMethod, paymentType
                boolean matches = existing.getPatientProfile() != null && existing.getPatientProfile().getProfileId().equals(patient.getProfileId())
                        && existing.getBranch() != null && existing.getBranch().getBranchId().equals(branch.getBranchId())
                        && existing.getAmount().compareTo(request.getAmount()) == 0
                        && (request.getPaymentMethod() == null || existing.getPaymentMethod() == request.getPaymentMethod())
                        && existing.getPaymentType() == PaymentType.ADVANCE_DEPOSIT;

                if (matches) {
                    return existing;
                } else {
                    throw new IllegalStateException("Khóa giao dịch (idempotencyKey: " + idempotencyKey + ") đã được sử dụng cho giao dịch khác.");
                }
            }
        }

        if (request.getTransactionRef() != null && !request.getTransactionRef().trim().isEmpty()) {
            Optional<Payment> existingRefOpt = paymentRepository.findByTransactionRef(request.getTransactionRef().trim());
            if (existingRefOpt.isPresent()) {
                Payment existingRef = existingRefOpt.get();
                boolean matches = existingRef.getPatientProfile().getProfileId().equals(patient.getProfileId())
                        && existingRef.getAmount().compareTo(request.getAmount()) == 0
                        && existingRef.getPaymentType() == PaymentType.ADVANCE_DEPOSIT;
                if (matches) {
                    return existingRef;
                } else {
                    throw new IllegalStateException("Mã tham chiếu tạm ứng (" + request.getTransactionRef() + ") đã được sử dụng với nội dung khác.");
                }
            }
        }

        // 6. Cộng tiền vào số dư tạm ứng
        BigDecimal currentBalance = patient.getDepositBalance() != null ? patient.getDepositBalance() : BigDecimal.ZERO;
        patient.setDepositBalance(currentBalance.add(request.getAmount()));
        patientProfileRepository.save(patient);

        // 7. Tạo bản ghi Payment loại ADVANCE_DEPOSIT với idempotencyKey
        Payment payment = new Payment();
        payment.setPaymentCode("TU" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        payment.setPatientProfile(patient);
        payment.setBranch(branch);
        payment.setCashier(cashier);
        payment.setPaymentType(PaymentType.ADVANCE_DEPOSIT);
        payment.setPaymentMethod(request.getPaymentMethod() != null ? request.getPaymentMethod() : PaymentMethod.CASH);
        payment.setAmount(request.getAmount());
        payment.setTransactionRef(request.getTransactionRef());
        payment.setIdempotencyKey(idempotencyKey);
        payment.setNotes(request.getNotes() != null ? request.getNotes() : "Nạp tiền tạm ứng điều trị");

        Payment saved = paymentRepository.save(payment);

        // Khi nạp tạm ứng, cộng điểm tích lũy ngay
        if (loyaltyService != null) {
            loyaltyService.recordPaymentPoints(saved);
        } else {
            updateLoyaltyAndTier(patient, request.getAmount(), null);
        }

        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Payment> getPayments(
            Long branchId,
            PaymentType paymentType,
            PaymentMethod paymentMethod,
            String keyword,
            LocalDateTime startDate,
            LocalDateTime endDate,
            Pageable pageable
    ) {
        return paymentRepository.findFilteredPayments(branchId, paymentType, paymentMethod, startDate, endDate, keyword, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Payment> getPaymentsByInvoiceId(Long invoiceId) {
        return paymentRepository.findByInvoice_InvoiceIdOrderByPaidAtDesc(invoiceId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Payment> getPatientDepositHistory(Long profileId) {
        return paymentRepository.findByPatientProfile_ProfileIdAndPaymentTypeOrderByPaidAtDesc(profileId, PaymentType.ADVANCE_DEPOSIT);
    }

    @Override
    @Transactional(readOnly = true)
    public Payment getPaymentById(Long paymentId) {
        return paymentRepository.findWithDetailsById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu thu ID: " + paymentId));
    }
}
