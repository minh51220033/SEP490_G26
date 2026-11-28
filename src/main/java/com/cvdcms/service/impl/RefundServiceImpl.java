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

/**
 * [Phân hệ]: Quản lý Hoàn tiền (Hóa đơn & Ví tạm ứng)
 * [Vai trò]: Service xử lý nghiệp vụ hoàn tiền có kiểm soát quyền `REFUND_MANAGE`, chống ghi trùng theo `idempotencyKey`, khóa bi quan bản ghi tiền tệ, hỗ trợ hai chế độ hoàn hóa đơn (`reduceInvoiceTotal = true/false`), rollback phân bổ LIFO, tự động mở lại lượt khám khi phát sinh nợ lại và điều chỉnh điểm thành viên.
 */
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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private InvoiceItemRepository invoiceItemRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private VisitRepository visitRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PaymentAllocationRepository paymentAllocationRepository;

    /**
     * [Hàm hỗ trợ]: Tiêm `InvoiceItemRepository` phục vụ rollback số tiền đã thanh toán/phải thu trên từng dòng hóa đơn khi hoàn tiền.
     */
    public void setInvoiceItemRepository(InvoiceItemRepository invoiceItemRepository) {
        this.invoiceItemRepository = invoiceItemRepository;
    }

    /**
     * [Hàm hỗ trợ]: Tiêm `VisitRepository` phục vụ mở lại lượt khám (`COMPLETED -> WAITING_PAYMENT`) nếu hoàn tiền làm hóa đơn phát sinh nợ lại.
     */
    public void setVisitRepository(VisitRepository visitRepository) {
        this.visitRepository = visitRepository;
    }

    /**
     * [Hàm hỗ trợ]: Tiêm `PaymentAllocationRepository` phục vụ hoàn tác các bản ghi phân bổ chi tiết theo thứ tự LIFO.
     */
    public void setPaymentAllocationRepository(PaymentAllocationRepository paymentAllocationRepository) {
        this.paymentAllocationRepository = paymentAllocationRepository;
    }

    /**
     * [Hàm hỗ trợ]: Constructor chính do Spring tiêm các repository hoàn tiền, hóa đơn, nhật ký hóa đơn, bệnh nhân, chi nhánh, nhân sự, người dùng và `LoyaltyService`.
     */
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

    /**
     * [Hàm hỗ trợ]: Constructor tương thích ngược (không truyền `LoyaltyService`) phục vụ các bài kiểm thử đơn vị.
     */
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
     * [Hàm hỗ trợ]: Xác thực hồ sơ nhân sự (`StaffProfile`) hợp lệ của người thực hiện hoàn tiền: kiểm tra tài khoản `User` và `StaffProfile` đang `ACTIVE`, bắt buộc có vai trò Admin hoặc quyền `REFUND_MANAGE`, và phải thuộc chi nhánh được phân công `ACTIVE`.
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
        if (!isAdmin && !userRepository.hasActivePermission(userId, "REFUND_MANAGE")) {
            throw new org.springframework.security.access.AccessDeniedException("Bạn không có quyền thực hiện hoàn tiền (REFUND_MANAGE).");
        }
        if (!isAdmin && branchId != null) {
            boolean hasAssignment = staffBranchAssignmentRepository.hasActiveAssignment(staff.getStaffId(), branchId, AssignmentStatus.ACTIVE);
            if (!hasAssignment) {
                boolean matchesUserBranch = staffBranchAssignmentRepository.findActiveBranchIdsByUserId(userId, AssignmentStatus.ACTIVE).isEmpty()
                        && user.getBranch() != null && branchId.equals(user.getBranch().getBranchId());
                if (!matchesUserBranch) {
                    throw new IllegalStateException("Nhân sự (" + staff.getFullName() + ") không có phân công hoạt động hợp lệ tại chi nhánh ID: " + branchId);
                }
            }
        }

        return staff;
    }

    /**
     * [Chức năng]: Xử lý lập phiếu hoàn tiền (`HT...`) từ một hóa đơn đã thu hoặc từ số dư ví tạm ứng của bệnh nhân.
     * [Mục đích]: Chống gửi trùng bằng `idempotencyKey` (kiểm tra quyền trước khi đối chiếu toàn bộ tham số nghiệp vụ để trả phiếu cũ hoặc báo xung đột); khóa bi quan `Invoice` (khi hoàn hóa đơn, chặn hoàn vượt `paidAmount` và chặn hóa đơn `CANCELLED`) hoặc khóa bi quan `PatientProfile` (khi hoàn từ ví tạm ứng, chặn hoàn vượt `depositBalance` và trừ ví trong cùng transaction); với hóa đơn hỗ trợ 2 chế độ:
     * - `reduceInvoiceTotal = true`: hoàn tiền kèm giảm nghĩa vụ hóa đơn (giảm phụ thu trước, sau đó giảm `payableAmount`/`paidAmount` và tăng `discountAmount` từng dòng theo thứ tự LIFO + rollback `PaymentAllocation`, giảm `totalAmount`, ghi đủ 2 audit log `REFUND` và `ADJUST`);
     * - `reduceInvoiceTotal = false`: hoàn tiền đơn thuần (giữ nguyên `totalAmount`, tăng lại `remainingDebt`, hoàn tác `paidAmount` phụ thu và từng dòng theo LIFO + rollback `PaymentAllocation`, ghi audit log `REFUND`);
     * ở cả hai chế độ nếu `remainingDebt > 0` và lượt khám đã `COMPLETED` thì tự động mở lại lượt khám về `WAITING_PAYMENT`, cuối cùng gọi `loyaltyService.recordRefundAdjustment` để điều chỉnh điểm tích lũy.
     */
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

            if (request.getProfileId() != null && patient != null
                    && !request.getProfileId().equals(patient.getProfileId())) {
                throw new IllegalArgumentException("Bệnh nhân được chọn không khớp với chủ sở hữu hóa đơn ID: " + invoice.getInvoiceId());
            }
            if (request.getBranchId() != null && branch != null
                    && !request.getBranchId().equals(branch.getBranchId())) {
                throw new IllegalArgumentException("Chi nhánh hoàn tiền không khớp với chi nhánh phát hành hóa đơn (" + branch.getBranchName() + ").");
            }
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
        if (request.getSignatureData() != null && !request.getSignatureData().isBlank()) {
            refund.setSignatureUrl(com.cvdcms.util.InventoryFileStorageUtil.saveSignatureDataUrl(
                    request.getSignatureData(), "refund"));
        }
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

                BigDecimal surchRoll = BigDecimal.ZERO;
                BigDecimal unrefunded = request.getAmount();

                // Hoàn phụ thu trước nếu có (giảm trực tiếp surchargeAmount, KHÔNG cộng surchRoll vào discountAmount)
                if (invoice.getSurchargePaidAmount() != null && invoice.getSurchargePaidAmount().compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal surchPaid = invoice.getSurchargePaidAmount();
                    surchRoll = unrefunded.min(surchPaid);
                    BigDecimal currentSurcharge = invoice.getSurchargeAmount() != null ? invoice.getSurchargeAmount() : BigDecimal.ZERO;
                    surchRoll = surchRoll.min(currentSurcharge);
                    invoice.setSurchargeAmount(currentSurcharge.subtract(surchRoll).max(BigDecimal.ZERO));
                    invoice.setSurchargePaidAmount(surchPaid.subtract(surchRoll).max(BigDecimal.ZERO));
                    invoice.setSurchargeRemainingDebt(invoice.getSurchargeAmount().subtract(invoice.getSurchargePaidAmount()).max(BigDecimal.ZERO));
                    rollbackSurchargeAllocations(invoice.getInvoiceId(), surchRoll, savedRefund.getRefundCode());
                    unrefunded = unrefunded.subtract(surchRoll);
                }

                // Cập nhật từng dòng chi tiết để đồng bộ item.payableAmount với discountAmount
                if (invoiceItemRepository != null && unrefunded.compareTo(BigDecimal.ZERO) > 0) {
                    List<InvoiceItem> items = invoiceItemRepository.findByInvoice_InvoiceIdOrderByLineOrderAscItemIdAsc(invoice.getInvoiceId());
                    if (items != null && !items.isEmpty()) {
                        for (int i = items.size() - 1; i >= 0 && unrefunded.compareTo(BigDecimal.ZERO) > 0; i--) {
                            InvoiceItem it = items.get(i);
                            BigDecimal itPaid = it.getPaidAmount() != null ? it.getPaidAmount() : BigDecimal.ZERO;
                            if (itPaid.compareTo(BigDecimal.ZERO) > 0) {
                                BigDecimal itRollback = unrefunded.min(itPaid);
                                BigDecimal payable = it.getPayableAmount() != null ? it.getPayableAmount() : (it.getFinalAmount() != null ? it.getFinalAmount() : BigDecimal.ZERO);
                                BigDecimal newPayable = payable.subtract(itRollback).max(BigDecimal.ZERO);
                                it.setPayableAmount(newPayable);
                                BigDecimal currentDiscount = it.getDiscountAmount() != null ? it.getDiscountAmount() : BigDecimal.ZERO;
                                it.setDiscountAmount(currentDiscount.add(itRollback));

                                BigDecimal newItPaid = itPaid.subtract(itRollback).max(BigDecimal.ZERO);
                                BigDecimal newItDebt = newPayable.subtract(newItPaid).max(BigDecimal.ZERO);
                                it.setPaidAmount(newItPaid);
                                it.setRemainingDebt(newItDebt);
                                it.setPaymentStatus(newItDebt.compareTo(BigDecimal.ZERO) == 0 ? com.cvdcms.entity.enums.InvoiceItemPaymentStatus.PAID : (newItPaid.compareTo(BigDecimal.ZERO) > 0 ? com.cvdcms.entity.enums.InvoiceItemPaymentStatus.PARTIAL : com.cvdcms.entity.enums.InvoiceItemPaymentStatus.UNPAID));
                                invoiceItemRepository.save(it);
                                rollbackItemAllocations(it.getItemId(), itRollback, savedRefund.getRefundCode());
                                unrefunded = unrefunded.subtract(itRollback);
                            }
                        }
                    }
                }

                BigDecimal discountIncrease = request.getAmount().subtract(surchRoll);
                BigDecimal newDiscount = oldDiscount.add(discountIncrease);
                invoice.setDiscountAmount(newDiscount);

                BigDecimal totalService = Optional.ofNullable(invoice.getTotalServiceAmount()).orElse(BigDecimal.ZERO);
                BigDecimal totalMedicine = Optional.ofNullable(invoice.getTotalMedicineAmount()).orElse(BigDecimal.ZERO);
                BigDecimal updatedSurcharge = Optional.ofNullable(invoice.getSurchargeAmount()).orElse(BigDecimal.ZERO);
                BigDecimal promoDiscount = Optional.ofNullable(invoice.getPromotionDiscount()).orElse(BigDecimal.ZERO);
                BigDecimal pointDiscount = Optional.ofNullable(invoice.getPointDiscount()).orElse(BigDecimal.ZERO);

                BigDecimal beforeDiscount = totalService.add(totalMedicine).add(updatedSurcharge);
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

                // Mở lại lượt khám nếu trước đó đã COMPLETED mà nay phát sinh nợ lại
                if (invoice.getRemainingDebt().compareTo(BigDecimal.ZERO) > 0 && invoice.getVisit() != null && visitRepository != null) {
                    Visit visit = invoice.getVisit();
                    if (visit.getStatus() == com.cvdcms.entity.enums.VisitStatus.COMPLETED) {
                        visit.setStatus(com.cvdcms.entity.enums.VisitStatus.WAITING_PAYMENT);
                        visit.setCompletedAt(null);
                        visitRepository.save(visit);
                    }
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

                // Cập nhật từng dòng chi tiết theo LIFO cho trường hợp hoàn tiền đơn thuần
                if (invoiceItemRepository != null) {
                    List<InvoiceItem> items = invoiceItemRepository.findByInvoice_InvoiceIdOrderByLineOrderAscItemIdAsc(invoice.getInvoiceId());
                    BigDecimal unrefunded = request.getAmount();

                    // Hoàn phụ thu trước nếu có
                    if (invoice.getSurchargePaidAmount() != null && invoice.getSurchargePaidAmount().compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal surchPaid = invoice.getSurchargePaidAmount();
                        BigDecimal surchRoll = unrefunded.min(surchPaid);
                        invoice.setSurchargePaidAmount(surchPaid.subtract(surchRoll));
                        BigDecimal surchDebt = invoice.getSurchargeRemainingDebt() != null ? invoice.getSurchargeRemainingDebt() : BigDecimal.ZERO;
                        invoice.setSurchargeRemainingDebt(surchDebt.add(surchRoll));
                        rollbackSurchargeAllocations(invoice.getInvoiceId(), surchRoll, savedRefund.getRefundCode());
                        unrefunded = unrefunded.subtract(surchRoll);
                    }

                    if (items != null && !items.isEmpty() && unrefunded.compareTo(BigDecimal.ZERO) > 0) {
                        for (int i = items.size() - 1; i >= 0 && unrefunded.compareTo(BigDecimal.ZERO) > 0; i--) {
                            InvoiceItem it = items.get(i);
                            BigDecimal itPaid = it.getPaidAmount() != null ? it.getPaidAmount() : BigDecimal.ZERO;
                            if (itPaid.compareTo(BigDecimal.ZERO) > 0) {
                                BigDecimal itRollback = unrefunded.min(itPaid);
                                BigDecimal newItPaid = itPaid.subtract(itRollback);
                                BigDecimal payable = it.getPayableAmount() != null ? it.getPayableAmount() : (it.getFinalAmount() != null ? it.getFinalAmount() : BigDecimal.ZERO);
                                BigDecimal newItDebt = payable.subtract(newItPaid).max(BigDecimal.ZERO);
                                it.setPaidAmount(newItPaid);
                                it.setRemainingDebt(newItDebt);
                                it.setPaymentStatus(newItDebt.compareTo(BigDecimal.ZERO) == 0 ? com.cvdcms.entity.enums.InvoiceItemPaymentStatus.PAID : (newItPaid.compareTo(BigDecimal.ZERO) > 0 ? com.cvdcms.entity.enums.InvoiceItemPaymentStatus.PARTIAL : com.cvdcms.entity.enums.InvoiceItemPaymentStatus.UNPAID));
                                invoiceItemRepository.save(it);
                                rollbackItemAllocations(it.getItemId(), itRollback, savedRefund.getRefundCode());
                                unrefunded = unrefunded.subtract(itRollback);
                            }
                        }
                    }
                }

                // Mở lại lượt khám nếu trước đó đã COMPLETED mà nay phát sinh nợ lại
                if (invoice.getRemainingDebt().compareTo(BigDecimal.ZERO) > 0 && invoice.getVisit() != null && visitRepository != null) {
                    Visit visit = invoice.getVisit();
                    if (visit.getStatus() == com.cvdcms.entity.enums.VisitStatus.COMPLETED) {
                        visit.setStatus(com.cvdcms.entity.enums.VisitStatus.WAITING_PAYMENT);
                        visit.setCompletedAt(null);
                        visitRepository.save(visit);
                    }
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

    /**
     * [Chức năng]: Truy vấn phân trang danh sách phiếu hoàn tiền theo bộ lọc chi nhánh, phương thức hoàn, từ khóa và khoảng ngày.
     * [Mục đích]: Phục vụ màn hình lịch sử hoàn tiền `/payments/refunds`.
     */
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

    /**
     * [Chức năng]: Lấy toàn bộ lịch sử phiếu hoàn tiền gắn với một hóa đơn cụ thể theo thời gian mới nhất giảm dần.
     * [Mục đích]: Hiển thị trên trang chi tiết hóa đơn để kế toán đối chiếu các khoản đã hoàn.
     */
    @Override
    @Transactional(readOnly = true)
    public List<Refund> getRefundsByInvoiceId(Long invoiceId) {
        return refundRepository.findByInvoice_InvoiceIdOrderByRefundedAtDesc(invoiceId);
    }

    /**
     * [Chức năng]: Lấy toàn bộ lịch sử phiếu hoàn tiền của một bệnh nhân trên tất cả chi nhánh theo thời gian giảm dần.
     * [Mục đích]: Hỗ trợ tra cứu lịch sử hoàn tiền toàn hệ thống của bệnh nhân (Admin/báo cáo tổng hợp).
     */
    @Override
    @Transactional(readOnly = true)
    public List<Refund> getRefundsByProfileId(Long profileId) {
        return refundRepository.findByPatientProfile_ProfileIdOrderByRefundedAtDesc(profileId);
    }

    /**
     * [Chức năng]: Lấy danh sách phiếu hoàn tiền của một bệnh nhân trong phạm vi `allowedBranchIds`.
     * [Mục đích]: Phục vụ Dashboard tài chính bệnh nhân đảm bảo nhân sự chi nhánh chỉ nhìn thấy phiếu hoàn thuộc chi nhánh mình quản lý.
     */
    @Override
    @Transactional(readOnly = true)
    public List<Refund> getRefundsByProfileId(Long profileId, List<Long> allowedBranchIds) {
        return refundRepository.findByPatientProfile_ProfileIdAndAllowedBranches(profileId, allowedBranchIds);
    }

    /**
     * [Chức năng]: Tải chi tiết một phiếu hoàn tiền theo `refundId`.
     * [Mục đích]: Phục vụ xem/in chi tiết phiếu hoàn tiền; ném `IllegalArgumentException` nếu không tồn tại.
     */
    @Override
    @Transactional(readOnly = true)
    public Refund getRefundById(Long refundId) {
        return refundRepository.findWithDetailsById(refundId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu hoàn tiền ID: " + refundId));
    }

    /**
     * [Hàm hỗ trợ]: Lọc các bản ghi `PaymentAllocation` loại `SURCHARGE` của hóa đơn và hoàn tác số tiền đã phân bổ phụ thu theo thứ tự LIFO.
     */
    private void rollbackSurchargeAllocations(Long invoiceId, BigDecimal amountToRollback, String refundCode) {
        if (paymentAllocationRepository == null || invoiceId == null
                || amountToRollback == null || amountToRollback.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        List<PaymentAllocation> invoiceAllocs = paymentAllocationRepository
                .findByInvoice_InvoiceIdOrderByLineOrderAscCreatedAtAsc(invoiceId);
        if (invoiceAllocs == null || invoiceAllocs.isEmpty()) {
            return;
        }
        List<PaymentAllocation> surchargeAllocs = invoiceAllocs.stream()
                .filter(pa -> "SURCHARGE".equalsIgnoreCase(pa.getItemType()))
                .toList();
        rollbackAllocationsList(surchargeAllocs, amountToRollback, refundCode);
    }

    /**
     * [Hàm hỗ trợ]: Tải các bản ghi `PaymentAllocation` của một dòng hóa đơn (`itemId`) và hoàn tác số tiền phân bổ theo thứ tự LIFO.
     */
    private void rollbackItemAllocations(Long itemId, BigDecimal amountToRollback, String refundCode) {
        if (paymentAllocationRepository == null || itemId == null
                || amountToRollback == null || amountToRollback.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        List<PaymentAllocation> itemAllocs = paymentAllocationRepository.findByInvoiceItem_ItemIdOrderByCreatedAtAsc(itemId);
        rollbackAllocationsList(itemAllocs, amountToRollback, refundCode);
    }

    /**
     * [Hàm hỗ trợ]: Sắp xếp danh sách `PaymentAllocation` ngược thời gian (LIFO) để trừ dần số tiền hoàn: xóa bản ghi nếu phân bổ về 0 hoặc giảm `allocatedAmount` kèm ghi chú mã phiếu hoàn nếu còn dư.
     */
    private void rollbackAllocationsList(List<PaymentAllocation> allocations, BigDecimal amountToRollback, String refundCode) {
        if (allocations == null || allocations.isEmpty()
                || amountToRollback == null || amountToRollback.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        List<PaymentAllocation> lifo = new java.util.ArrayList<>(allocations);
        lifo.sort(java.util.Comparator
                .comparing(PaymentAllocation::getCreatedAt, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                .thenComparing(pa -> pa.getAllocationId() != null ? pa.getAllocationId() : 0L)
                .reversed());

        BigDecimal remaining = amountToRollback;
        for (PaymentAllocation pa : lifo) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal currentAlloc = pa.getAllocatedAmount() != null ? pa.getAllocatedAmount() : BigDecimal.ZERO;
            if (currentAlloc.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal delta = remaining.min(currentAlloc);
            BigDecimal updated = currentAlloc.subtract(delta);
            if (updated.compareTo(BigDecimal.ZERO) <= 0) {
                paymentAllocationRepository.delete(pa);
            } else {
                pa.setAllocatedAmount(updated);
                appendRefundNote(pa, refundCode, delta);
                paymentAllocationRepository.save(pa);
            }
            remaining = remaining.subtract(delta);
        }
    }

    /**
     * [Hàm hỗ trợ]: Nối thêm vết hoàn tiền `[Hoàn <số tiền> đ - <mã phiếu hoàn>]` vào trường `notes` của `PaymentAllocation` và cắt gọn tối đa 255 ký tự.
     */
    private void appendRefundNote(PaymentAllocation pa, String refundCode, BigDecimal refundedAmount) {
        String addition = "[Hoàn " + refundedAmount.stripTrailingZeros().toPlainString() + " đ - " + refundCode + "]";
        String existing = pa.getNotes();
        String merged = (existing == null || existing.isBlank()) ? addition : existing + " " + addition;
        pa.setNotes(merged.length() > 255 ? merged.substring(0, 255) : merged);
    }
}
