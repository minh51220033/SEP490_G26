package com.cvdcms.service.impl;

import com.cvdcms.DTO.FinancialMetricsDTO;
import com.cvdcms.DTO.InvoiceAdjustmentRequestDTO;
import com.cvdcms.DTO.InvoiceCalculationPreviewDTO;
import com.cvdcms.DTO.InvoiceCreateRequestDTO;
import com.cvdcms.DTO.InvoiceItemRequestDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.InvoiceService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Transactional
public class InvoiceServiceImpl implements InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceItemRepository invoiceItemRepository;
    private final InvoiceAuditLogRepository invoiceAuditLogRepository;
    private final VisitRepository visitRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final BranchRepository branchRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    private final ServiceRepository serviceRepository;
    private final PerformedServiceRepository performedServiceRepository;
    private final PromotionRepository promotionRepository;
    private final PatientVoucherRepository patientVoucherRepository;
    private final PatientLoyaltyWalletRepository patientLoyaltyWalletRepository;
    private final PointTransactionRepository pointTransactionRepository;
    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;

    public InvoiceServiceImpl(
            InvoiceRepository invoiceRepository,
            InvoiceItemRepository invoiceItemRepository,
            InvoiceAuditLogRepository invoiceAuditLogRepository,
            VisitRepository visitRepository,
            PatientProfileRepository patientProfileRepository,
            BranchRepository branchRepository,
            StaffProfileRepository staffProfileRepository,
            StaffBranchAssignmentRepository staffBranchAssignmentRepository,
            ServiceRepository serviceRepository,
            PerformedServiceRepository performedServiceRepository,
            PromotionRepository promotionRepository,
            PatientVoucherRepository patientVoucherRepository,
            PatientLoyaltyWalletRepository patientLoyaltyWalletRepository,
            PointTransactionRepository pointTransactionRepository,
            UserRepository userRepository,
            PaymentRepository paymentRepository,
            RefundRepository refundRepository
    ) {
        this.invoiceRepository = invoiceRepository;
        this.invoiceItemRepository = invoiceItemRepository;
        this.invoiceAuditLogRepository = invoiceAuditLogRepository;
        this.visitRepository = visitRepository;
        this.patientProfileRepository = patientProfileRepository;
        this.branchRepository = branchRepository;
        this.staffProfileRepository = staffProfileRepository;
        this.staffBranchAssignmentRepository = staffBranchAssignmentRepository;
        this.serviceRepository = serviceRepository;
        this.performedServiceRepository = performedServiceRepository;
        this.promotionRepository = promotionRepository;
        this.patientVoucherRepository = patientVoucherRepository;
        this.patientLoyaltyWalletRepository = patientLoyaltyWalletRepository;
        this.pointTransactionRepository = pointTransactionRepository;
        this.userRepository = userRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
    }

    /**
     * Lấy hồ sơ nhân sự hợp lệ của người đăng nhập.
     * Kiểm tra trạng thái hoạt động của User, StaffProfile và phân công chi nhánh (đối với non-admin).
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
    public Invoice createInvoice(InvoiceCreateRequestDTO request, Long cashierUserId) {
        if (request == null || request.getVisitId() == null) {
            throw new IllegalArgumentException("Thông tin lập hóa đơn không hợp lệ: thiếu visitId.");
        }

        // 1. Kiểm tra lượt khám & trạng thái bàn giao từ bác sĩ
        Visit visit = visitRepository.findById(request.getVisitId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca khám ID: " + request.getVisitId()));

        if (visit.getStatus() != VisitStatus.WAITING_PAYMENT) {
            throw new IllegalStateException("Chỉ có thể lập hóa đơn cho ca khám ở trạng thái Chờ thanh toán (WAITING_PAYMENT). Trạng thái hiện tại: " + visit.getStatus());
        }

        // 2. Suy ra bệnh nhân và chi nhánh từ lượt khám; từ chối nếu request gửi ID khác
        PatientProfile patient = visit.getPatientProfile();
        Branch branch = visit.getBranch();

        if (request.getProfileId() != null && !request.getProfileId().equals(patient.getProfileId())) {
            throw new IllegalArgumentException("Hồ sơ bệnh nhân không khớp với lượt khám.");
        }
        if (request.getBranchId() != null && !request.getBranchId().equals(branch.getBranchId())) {
            throw new IllegalArgumentException("Chi nhánh không khớp với lượt khám.");
        }

        // 3. Kiểm tra quyền và phân công chi nhánh của thu ngân
        StaffProfile cashier = getValidStaffProfile(cashierUserId, branch.getBranchId());

        // 4. Bắt buộc có ít nhất một dịch vụ hợp lệ
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("Hóa đơn phải có ít nhất 01 dịch vụ hợp lệ.");
        }

        // 5. Kiểm tra và khóa các dịch vụ thực hiện lâm sàng
        Set<Long> seenPerformedIds = new HashSet<>();
        List<InvoiceItem> invoiceItems = new ArrayList<>();
        BigDecimal totalServiceAmount = BigDecimal.ZERO;

        Invoice invoice = new Invoice();
        invoice.setVisit(visit);
        invoice.setPatientProfile(patient);
        invoice.setBranch(branch);
        invoice.setCashier(cashier);
        invoice.setNotes(request.getNotes());
        invoice.setStatus(InvoiceStatus.CONFIRMED);
        // Sinh mã hóa đơn duy nhất
        invoice.setInvoiceCode("HD" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());

        for (InvoiceItemRequestDTO itemDto : request.getItems()) {
            if (itemDto.getPerformedId() == null) {
                throw new IllegalArgumentException("Mỗi dòng dịch vụ trên hóa đơn bắt buộc phải gắn với dịch vụ thực hiện (performedId).");
            }

            // Chống trùng performedId trong cùng request
            if (!seenPerformedIds.add(itemDto.getPerformedId())) {
                throw new IllegalArgumentException("Dịch vụ thực hiện ID " + itemDto.getPerformedId() + " bị gửi trùng lặp trong yêu cầu lập hóa đơn.");
            }

            // Khóa bi quan dịch vụ thực hiện để chống xung đột đồng thời
            PerformedService ps = performedServiceRepository.findByIdForUpdate(itemDto.getPerformedId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dịch vụ thực hiện ID: " + itemDto.getPerformedId()));

            // Kiểm tra thuộc đúng lượt khám, bệnh nhân và chi nhánh
            if (!ps.getVisit().getVisitId().equals(visit.getVisitId())
                    || !ps.getPatientProfile().getProfileId().equals(patient.getProfileId())
                    || !ps.getBranch().getBranchId().equals(branch.getBranchId())) {
                throw new IllegalArgumentException("Dịch vụ thực hiện ID " + ps.getPerformedId() + " không thuộc ca khám/bệnh nhân/chi nhánh được chỉ định.");
            }

            // Trạng thái bắt buộc COMPLETED
            if (ps.getStatus() != PerformedServiceStatus.COMPLETED) {
                throw new IllegalStateException("Dịch vụ thực hiện ID " + ps.getPerformedId() + " chưa được bác sĩ hoàn thành (trạng thái: " + ps.getStatus() + ").");
            }

            // Service ID phải khớp
            if (itemDto.getServiceId() != null && !itemDto.getServiceId().equals(ps.getService().getServiceId())) {
                throw new IllegalArgumentException("Mã dịch vụ không khớp với dịch vụ đã thực hiện trên lâm sàng.");
            }

            // Chống lập hóa đơn trùng: Dịch vụ chưa nằm trong hóa đơn còn hiệu lực
            if (invoiceItemRepository.existsByPerformedIdAndInvoiceNotCancelled(ps.getPerformedId())) {
                throw new IllegalStateException("Dịch vụ '" + ps.getService().getServiceName() + "' (ID: " + ps.getPerformedId() + ") đã được lập hóa đơn còn hiệu lực trước đó.");
            }

            // Dùng dữ liệu lâm sàng làm căn cứ tính tiền, giữ đúng giảm giá bác sĩ đã ghi nhận
            BigDecimal unitPrice = ps.getUnitPrice() != null ? ps.getUnitPrice() : ps.getService().getBasePrice();
            BigDecimal quantity = ps.getQuantity() != null && ps.getQuantity().compareTo(BigDecimal.ZERO) > 0 ? ps.getQuantity() : BigDecimal.ONE;
            BigDecimal clinicalDiscount = ps.getDiscountAmount() != null ? ps.getDiscountAmount() : BigDecimal.ZERO;

            // Nếu kế toán điều chỉnh đơn giá hoặc số lượng, phải validate và có ghi chú lý do
            if ((itemDto.getUnitPrice() != null && itemDto.getUnitPrice().compareTo(unitPrice) != 0)
                    || (itemDto.getQuantity() != null && itemDto.getQuantity().compareTo(quantity) != 0)) {
                if (request.getNotes() == null || request.getNotes().trim().isEmpty()) {
                    throw new IllegalArgumentException("Vui lòng nhập lý do/ghi chú khi kế toán điều chỉnh đơn giá hoặc số lượng dịch vụ.");
                }
                if (itemDto.getUnitPrice() != null && itemDto.getUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
                    throw new IllegalArgumentException("Đơn giá dịch vụ không được âm.");
                }
                if (itemDto.getQuantity() != null && itemDto.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("Số lượng dịch vụ phải lớn hơn 0.");
                }
                unitPrice = itemDto.getUnitPrice();
                quantity = itemDto.getQuantity();
            }

            // Tính thành tiền dòng bằng BigDecimal (không sửa ngược dữ liệu lâm sàng trong performed_services)
            BigDecimal lineTotal = unitPrice.multiply(quantity).subtract(clinicalDiscount);
            if (lineTotal.compareTo(BigDecimal.ZERO) < 0) {
                lineTotal = BigDecimal.ZERO;
            }

            // Lưu snapshot vào InvoiceItem
            InvoiceItem item = new InvoiceItem();
            item.setInvoice(invoice);
            item.setService(ps.getService());
            item.setItemName(ps.getService().getServiceName());
            item.setQuantity(quantity);
            item.setUnitPrice(unitPrice);
            item.setFinalAmount(lineTotal);
            item.setPerformedService(ps);

            invoiceItems.add(item);
            totalServiceAmount = totalServiceAmount.add(lineTotal);
        }

        invoice.setTotalServiceAmount(totalServiceAmount);

        // 6. Giảm trừ thẻ thành viên
        BigDecimal memberDiscount = BigDecimal.ZERO;
        if (patient.getMembershipTier() != null && patient.getMembershipTier().getDiscountPercent() != null) {
            BigDecimal tierPercent = patient.getMembershipTier().getDiscountPercent();
            if (tierPercent.compareTo(BigDecimal.ZERO) > 0) {
                memberDiscount = totalServiceAmount.multiply(tierPercent).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            }
        }
        BigDecimal manualDiscount = (request.getDiscountAmount() != null && request.getDiscountAmount().compareTo(BigDecimal.ZERO) > 0)
                ? request.getDiscountAmount() : BigDecimal.ZERO;
        invoice.setDiscountAmount(memberDiscount.add(manualDiscount));

        // 7. Khuyến mãi chung
        BigDecimal promotionDiscount = BigDecimal.ZERO;
        if (request.getPromotionId() != null) {
            Optional<Promotion> promoOpt = promotionRepository.findById(request.getPromotionId());
            if (promoOpt.isPresent()) {
                Promotion promo = promoOpt.get();
                LocalDateTime now = LocalDateTime.now();
                boolean isActive = Boolean.TRUE.equals(promo.getIsActive())
                        && (promo.getStartDate() == null || !now.isBefore(promo.getStartDate()))
                        && (promo.getEndDate() == null || !now.isAfter(promo.getEndDate()))
                        && (promo.getBranch() == null || promo.getBranch().getBranchId().equals(branch.getBranchId()))
                        && (promo.getUsageLimit() == null || (promo.getUsedCount() != null ? promo.getUsedCount() : 0) < promo.getUsageLimit());
                boolean meetsMin = promo.getMinBillAmount() == null || totalServiceAmount.compareTo(promo.getMinBillAmount()) >= 0;

                if (isActive && meetsMin) {
                    if (promo.getPromoType() == PromotionType.PERCENTAGE) {
                        BigDecimal calculated = totalServiceAmount.multiply(promo.getDiscountValue()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                        if (promo.getMaxDiscountAmount() != null && calculated.compareTo(promo.getMaxDiscountAmount()) > 0) {
                            calculated = promo.getMaxDiscountAmount();
                        }
                        promotionDiscount = calculated;
                    } else {
                        promotionDiscount = promo.getDiscountValue().min(totalServiceAmount);
                    }
                    promo.setUsedCount((promo.getUsedCount() != null ? promo.getUsedCount() : 0) + 1);
                    promotionRepository.save(promo);
                    invoice.setPromotion(promo);
                }
            }
        }
        invoice.setPromotionDiscount(promotionDiscount);

        // 8. Voucher của bệnh nhân
        if (request.getVoucherId() != null) {
            Optional<PatientVoucher> voucherOpt = patientVoucherRepository.findById(request.getVoucherId());
            if (voucherOpt.isPresent()) {
                PatientVoucher voucher = voucherOpt.get();
                if (!voucher.getPatientProfile().getProfileId().equals(patient.getProfileId())) {
                    throw new IllegalArgumentException("Voucher được chọn không thuộc sở hữu của bệnh nhân này.");
                }
                LocalDateTime now = LocalDateTime.now();
                if (voucher.getStatus() == VoucherStatus.AVAILABLE && voucher.getExpiresAt() != null && voucher.getExpiresAt().isAfter(now)) {
                    Promotion vp = voucher.getPromotion();
                    boolean promoValid = Boolean.TRUE.equals(vp.getIsActive())
                            && (vp.getStartDate() == null || !now.isBefore(vp.getStartDate()))
                            && (vp.getEndDate() == null || !now.isAfter(vp.getEndDate()))
                            && (vp.getBranch() == null || vp.getBranch().getBranchId().equals(branch.getBranchId()));
                    boolean meetsMin = vp.getMinBillAmount() == null || totalServiceAmount.compareTo(vp.getMinBillAmount()) >= 0;
                    if (promoValid && meetsMin) {
                        BigDecimal vDiscount = BigDecimal.ZERO;
                        if (vp.getPromoType() == PromotionType.PERCENTAGE) {
                            vDiscount = totalServiceAmount.multiply(vp.getDiscountValue()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                            if (vp.getMaxDiscountAmount() != null && vDiscount.compareTo(vp.getMaxDiscountAmount()) > 0) {
                                vDiscount = vp.getMaxDiscountAmount();
                            }
                        } else {
                            vDiscount = vp.getDiscountValue().min(totalServiceAmount);
                        }
                        promotionDiscount = promotionDiscount.add(vDiscount);
                        invoice.setPromotionDiscount(promotionDiscount);

                        voucher.setStatus(VoucherStatus.USED);
                        voucher.setUsedAt(LocalDateTime.now());
                        patientVoucherRepository.save(voucher);
                        invoice.setVoucher(voucher);
                    }
                }
            }
        }

        // 9. Điểm tích lũy Loyalty
        BigDecimal pointDiscount = BigDecimal.ZERO;
        if (request.getPointsToRedeem() != null && request.getPointsToRedeem() > 0) {
            Optional<PatientLoyaltyWallet> walletOpt = patientLoyaltyWalletRepository.findByPatientProfile_ProfileId(patient.getProfileId());
            if (walletOpt.isPresent()) {
                PatientLoyaltyWallet wallet = walletOpt.get();
                int redeem = Math.min(request.getPointsToRedeem(), wallet.getCurrentPoints());
                if (redeem > 0) {
                    BigDecimal pointVal = (patient.getMembershipTier() != null && patient.getMembershipTier().getPointValue() != null)
                            ? patient.getMembershipTier().getPointValue() : new BigDecimal("1000.00");
                    pointDiscount = pointVal.multiply(new BigDecimal(redeem));
                    wallet.setCurrentPoints(wallet.getCurrentPoints() - redeem);
                    patientLoyaltyWalletRepository.save(wallet);

                    PointTransaction pt = new PointTransaction();
                    pt.setPatientProfile(patient);
                    pt.setInvoice(invoice);
                    pt.setTransactionType(PointTransactionType.REDEEM);
                    pt.setPoints(redeem);
                    pt.setBalanceAfter(wallet.getCurrentPoints());
                    pt.setReason("Đổi " + redeem + " điểm tích lũy thanh toán hóa đơn");
                    pointTransactionRepository.save(pt);
                }
            }
        }
        invoice.setPointDiscount(pointDiscount);

        // 10. Tính tổng tiền phải trả (Net Amount)
        BigDecimal netAmount = totalServiceAmount.subtract(invoice.getDiscountAmount())
                .subtract(invoice.getPromotionDiscount())
                .subtract(pointDiscount);
        if (netAmount.compareTo(BigDecimal.ZERO) < 0) {
            netAmount = BigDecimal.ZERO;
        }

        invoice.setTotalAmount(netAmount);
        invoice.setPaidAmount(BigDecimal.ZERO);
        invoice.setRemainingDebt(netAmount);
        invoice.setPaymentStatus(netAmount.compareTo(BigDecimal.ZERO) == 0 ? InvoicePaymentStatus.PAID : InvoicePaymentStatus.UNPAID);
        invoice.setItems(invoiceItems);

        Invoice saved = invoiceRepository.save(invoice);

        // Ghi Audit log
        InvoiceAuditLog log = new InvoiceAuditLog();
        log.setInvoice(saved);
        log.setActionType("CREATE");
        log.setNewTotalAmount(saved.getTotalAmount());
        log.setNewDiscountAmount(saved.getDiscountAmount());
        log.setNewPaidAmount(saved.getPaidAmount());
        log.setReason("Lập hóa đơn mới từ lượt khám " + visit.getVisitCode());
        log.setDetails("Tổng tiền dịch vụ: " + totalServiceAmount + " đ, Thực thu: " + netAmount + " đ");
        log.setPerformedBy(cashier);
        invoiceAuditLogRepository.save(log);

        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public InvoiceCalculationPreviewDTO calculatePreview(InvoiceCreateRequestDTO request) {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("Hóa đơn phải có ít nhất một dịch vụ/chi phí hợp lệ.");
        }

        InvoiceCalculationPreviewDTO preview = new InvoiceCalculationPreviewDTO();
        BigDecimal totalService = BigDecimal.ZERO;

        for (InvoiceItemRequestDTO it : request.getItems()) {
            BigDecimal qty = (it.getQuantity() != null && it.getQuantity().compareTo(BigDecimal.ZERO) > 0) ? it.getQuantity() : BigDecimal.ONE;
            BigDecimal price = it.getUnitPrice() != null ? it.getUnitPrice() : BigDecimal.ZERO;
            BigDecimal clinicalDiscount = BigDecimal.ZERO;

            if (it.getPerformedId() != null) {
                Optional<PerformedService> psOpt = performedServiceRepository.findById(it.getPerformedId());
                if (psOpt.isPresent()) {
                    PerformedService ps = psOpt.get();
                    if (it.getUnitPrice() == null) {
                        price = ps.getUnitPrice() != null ? ps.getUnitPrice() : (ps.getService() != null ? ps.getService().getBasePrice() : BigDecimal.ZERO);
                    }
                    if (it.getQuantity() == null && ps.getQuantity() != null) {
                        qty = ps.getQuantity();
                    }
                    if (ps.getDiscountAmount() != null) {
                        clinicalDiscount = ps.getDiscountAmount();
                    }
                }
            }

            BigDecimal lineTotal = price.multiply(qty).subtract(clinicalDiscount);
            if (lineTotal.compareTo(BigDecimal.ZERO) < 0) {
                lineTotal = BigDecimal.ZERO;
            }
            totalService = totalService.add(lineTotal);
        }
        preview.setTotalServiceAmount(totalService);

        Optional<PatientProfile> patientOpt = Optional.empty();
        if (request.getProfileId() != null) {
            patientOpt = patientProfileRepository.findById(request.getProfileId());
        }
        if (patientOpt.isEmpty() && request.getVisitId() != null) {
            patientOpt = visitRepository.findById(request.getVisitId()).map(Visit::getPatientProfile);
        }

        Long branchId = request.getBranchId();
        if (branchId == null && request.getVisitId() != null) {
            branchId = visitRepository.findById(request.getVisitId())
                    .map(v -> v.getBranch().getBranchId()).orElse(null);
        }

        BigDecimal memberDiscount = BigDecimal.ZERO;
        BigDecimal pointVal = new BigDecimal("1000.00");
        int maxPoints = 0;

        if (patientOpt.isPresent()) {
            PatientProfile patient = patientOpt.get();
            if (patient.getMembershipTier() != null) {
                MembershipTier tier = patient.getMembershipTier();
                preview.setTierName(tier.getTierName());
                if (tier.getDiscountPercent() != null && tier.getDiscountPercent().compareTo(BigDecimal.ZERO) > 0) {
                    preview.setMemberDiscountPercent(tier.getDiscountPercent());
                    memberDiscount = totalService.multiply(tier.getDiscountPercent())
                            .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                }
                if (tier.getPointValue() != null && tier.getPointValue().compareTo(BigDecimal.ZERO) > 0) {
                    pointVal = tier.getPointValue();
                }
            }

            Optional<PatientLoyaltyWallet> walletOpt = patientLoyaltyWalletRepository.findByPatientProfile_ProfileId(patient.getProfileId());
            if (walletOpt.isPresent()) {
                maxPoints = walletOpt.get().getCurrentPoints();
            }
        }
        preview.setMemberDiscountAmount(memberDiscount);

        BigDecimal promoDiscount = BigDecimal.ZERO;
        LocalDateTime now = LocalDateTime.now();

        if (request.getPromotionId() != null) {
            Optional<Promotion> promoOpt = promotionRepository.findById(request.getPromotionId());
            if (promoOpt.isPresent()) {
                Promotion promo = promoOpt.get();
                boolean isActive = Boolean.TRUE.equals(promo.getIsActive())
                        && (promo.getStartDate() == null || !now.isBefore(promo.getStartDate()))
                        && (promo.getEndDate() == null || !now.isAfter(promo.getEndDate()))
                        && (promo.getBranch() == null || promo.getBranch().getBranchId().equals(branchId))
                        && (promo.getUsageLimit() == null || (promo.getUsedCount() != null ? promo.getUsedCount() : 0) < promo.getUsageLimit());
                boolean meetsMin = promo.getMinBillAmount() == null || totalService.compareTo(promo.getMinBillAmount()) >= 0;

                if (isActive && meetsMin) {
                    preview.setPromoName(promo.getPromoName());
                    if (promo.getPromoType() == PromotionType.PERCENTAGE) {
                        BigDecimal calc = totalService.multiply(promo.getDiscountValue()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                        if (promo.getMaxDiscountAmount() != null && calc.compareTo(promo.getMaxDiscountAmount()) > 0) {
                            calc = promo.getMaxDiscountAmount();
                        }
                        promoDiscount = calc;
                    } else {
                        promoDiscount = promo.getDiscountValue().min(totalService);
                    }
                }
            }
        }

        if (request.getVoucherId() != null && patientOpt.isPresent()) {
            Optional<PatientVoucher> voucherOpt = patientVoucherRepository.findById(request.getVoucherId());
            if (voucherOpt.isPresent()) {
                PatientVoucher voucher = voucherOpt.get();
                if (voucher.getPatientProfile().getProfileId().equals(patientOpt.get().getProfileId())
                        && voucher.getStatus() == VoucherStatus.AVAILABLE
                        && voucher.getExpiresAt() != null
                        && voucher.getExpiresAt().isAfter(now)) {
                    Promotion vp = voucher.getPromotion();
                    boolean promoValid = Boolean.TRUE.equals(vp.getIsActive())
                            && (vp.getStartDate() == null || !now.isBefore(vp.getStartDate()))
                            && (vp.getEndDate() == null || !now.isAfter(vp.getEndDate()))
                            && (vp.getBranch() == null || vp.getBranch().getBranchId().equals(branchId));
                    boolean meetsMin = vp.getMinBillAmount() == null || totalService.compareTo(vp.getMinBillAmount()) >= 0;
                    if (promoValid && meetsMin) {
                        BigDecimal vDiscount = BigDecimal.ZERO;
                        if (vp.getPromoType() == PromotionType.PERCENTAGE) {
                            vDiscount = totalService.multiply(vp.getDiscountValue()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                            if (vp.getMaxDiscountAmount() != null && vDiscount.compareTo(vp.getMaxDiscountAmount()) > 0) {
                                vDiscount = vp.getMaxDiscountAmount();
                            }
                        } else {
                            vDiscount = vp.getDiscountValue().min(totalService);
                        }
                        promoDiscount = promoDiscount.add(vDiscount);
                    }
                }
            }
        }
        preview.setPromotionDiscountAmount(promoDiscount);

        BigDecimal pointDiscount = BigDecimal.ZERO;
        if (request.getPointsToRedeem() != null && request.getPointsToRedeem() > 0) {
            int redeem = Math.min(request.getPointsToRedeem(), maxPoints);
            if (redeem > 0) {
                pointDiscount = pointVal.multiply(new BigDecimal(redeem));
            }
        }
        preview.setPointDiscountAmount(pointDiscount);

        BigDecimal manualDiscount = (request.getDiscountAmount() != null && request.getDiscountAmount().compareTo(BigDecimal.ZERO) > 0)
                ? request.getDiscountAmount() : BigDecimal.ZERO;

        BigDecimal totalDiscount = memberDiscount
                .add(promoDiscount)
                .add(pointDiscount)
                .add(manualDiscount);
        preview.setTotalDiscountAmount(totalDiscount);

        BigDecimal finalPay = totalService.subtract(totalDiscount);
        if (finalPay.compareTo(BigDecimal.ZERO) < 0) {
            finalPay = BigDecimal.ZERO;
        }
        preview.setTotalAmount(finalPay);
        preview.setPointsEarnable(finalPay.divide(new BigDecimal("10000"), 0, RoundingMode.DOWN).intValue());

        return preview;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Invoice> getInvoices(
            Long branchId,
            InvoicePaymentStatus paymentStatus,
            InvoiceStatus status,
            String keyword,
            LocalDateTime startDate,
            LocalDateTime endDate,
            Pageable pageable
    ) {
        return invoiceRepository.findFilteredInvoices(branchId, paymentStatus, status, startDate, endDate, keyword, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Invoice getInvoiceById(Long invoiceId) {
        return invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn ID: " + invoiceId));
    }

    @Override
    @Transactional(readOnly = true)
    public Invoice getInvoiceWithDetails(Long invoiceId) {
        Invoice invoice = invoiceRepository.findWithDetailsById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn ID: " + invoiceId));
        invoice.getItems().size(); // initialize items
        return invoice;
    }

    @Override
    public Invoice adjustInvoice(Long invoiceId, InvoiceAdjustmentRequestDTO request, Long staffUserId) {
        if (request == null || request.getReason() == null || request.getReason().trim().isEmpty()) {
            throw new IllegalArgumentException("Lý do điều chỉnh hóa đơn không được để trống.");
        }

        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn ID: " + invoiceId));

        if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
            throw new IllegalStateException("Không thể điều chỉnh hóa đơn đã bị hủy.");
        }

        StaffProfile staff = getValidStaffProfile(staffUserId, invoice.getBranch().getBranchId());
        BigDecimal oldTotal = invoice.getTotalAmount();
        BigDecimal oldPaid = invoice.getPaidAmount();
        BigDecimal oldDiscount = invoice.getDiscountAmount() != null ? invoice.getDiscountAmount() : BigDecimal.ZERO;

        if (Boolean.TRUE.equals(request.getCancelInvoice())) {
            return cancelInvoice(invoiceId, request.getReason(), staffUserId);
        }

        // 1. Kiểm tra tổng tiền mới không được thấp hơn số tiền đã thanh toán
        if (request.getNewTotalAmount() != null) {
            if (request.getNewTotalAmount().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Tổng tiền mới không được âm.");
            }
            if (request.getNewTotalAmount().compareTo(invoice.getPaidAmount()) < 0) {
                throw new IllegalArgumentException("Tổng tiền mới (" + request.getNewTotalAmount() + " đ) không được nhỏ hơn số tiền đã thanh toán (" + invoice.getPaidAmount() + " đ). Vui lòng hoàn tiền trước khi giảm tiền hóa đơn.");
            }
        }

        BigDecimal totalService = invoice.getTotalServiceAmount() != null
                ? invoice.getTotalServiceAmount()
                : (invoice.getTotalAmount() != null ? invoice.getTotalAmount() : BigDecimal.ZERO);
        BigDecimal promoDiscount = invoice.getPromotionDiscount() != null ? invoice.getPromotionDiscount() : BigDecimal.ZERO;
        BigDecimal pointDiscount = invoice.getPointDiscount() != null ? invoice.getPointDiscount() : BigDecimal.ZERO;

        BigDecimal effectiveDiscount = request.getNewDiscountAmount() != null ? request.getNewDiscountAmount() : oldDiscount;
        if (effectiveDiscount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Số tiền giảm giá mới không được âm.");
        }

        BigDecimal calculatedTotal = totalService.subtract(effectiveDiscount).subtract(promoDiscount).subtract(pointDiscount);
        if (calculatedTotal.compareTo(BigDecimal.ZERO) < 0) {
            calculatedTotal = BigDecimal.ZERO;
        }

        BigDecimal effectiveTotal = request.getNewTotalAmount() != null ? request.getNewTotalAmount() : calculatedTotal;
        if (effectiveTotal.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Tổng tiền mới không được âm.");
        }

        // Kiểm tra tính nhất quán giữa dòng dịch vụ, giảm giá và tổng phải trả
        if (request.getNewTotalAmount() != null && request.getNewDiscountAmount() != null) {
            if (effectiveTotal.compareTo(calculatedTotal) != 0) {
                throw new IllegalArgumentException("Dữ liệu mâu thuẫn: Tổng dịch vụ (" + totalService + " đ) trừ các khoản giảm ("
                        + effectiveDiscount.add(promoDiscount).add(pointDiscount) + " đ) cho ra " + calculatedTotal + " đ, không khớp với tổng tiền yêu cầu (" + effectiveTotal + " đ).");
            }
        } else if (request.getNewDiscountAmount() != null) {
            effectiveTotal = calculatedTotal;
        } else if (request.getNewTotalAmount() != null) {
            BigDecimal derivedDiscount = totalService.subtract(promoDiscount).subtract(pointDiscount).subtract(effectiveTotal);
            if (derivedDiscount.compareTo(BigDecimal.ZERO) >= 0) {
                effectiveDiscount = derivedDiscount;
            } else {
                throw new IllegalArgumentException("Tổng tiền mới (" + effectiveTotal + " đ) vượt quá tổng giá trị dịch vụ (" + totalService + " đ).");
            }
        }

        if (effectiveTotal.compareTo(invoice.getPaidAmount()) < 0) {
            throw new IllegalArgumentException("Tổng tiền mới (" + effectiveTotal + " đ) không được nhỏ hơn số tiền đã thanh toán (" + invoice.getPaidAmount() + " đ). Vui lòng hoàn tiền trước khi giảm tiền hóa đơn.");
        }

        invoice.setDiscountAmount(effectiveDiscount);
        invoice.setTotalAmount(effectiveTotal);
        BigDecimal newRemaining = effectiveTotal.subtract(invoice.getPaidAmount());
        invoice.setRemainingDebt(newRemaining.compareTo(BigDecimal.ZERO) > 0 ? newRemaining : BigDecimal.ZERO);

        if (newRemaining.compareTo(BigDecimal.ZERO) <= 0) {
            invoice.setPaymentStatus(InvoicePaymentStatus.PAID);
        } else if (invoice.getPaidAmount().compareTo(BigDecimal.ZERO) > 0) {
            invoice.setPaymentStatus(InvoicePaymentStatus.PARTIAL);
        } else {
            invoice.setPaymentStatus(InvoicePaymentStatus.UNPAID);
        }

        invoice.setAdjustmentReason(request.getReason());
        invoice.setAdjustedBy(staff);
        Invoice saved = invoiceRepository.save(invoice);

        InvoiceAuditLog log = new InvoiceAuditLog();
        log.setInvoice(saved);
        log.setActionType("ADJUST");
        log.setOldTotalAmount(oldTotal);
        log.setNewTotalAmount(effectiveTotal);
        log.setOldDiscountAmount(oldDiscount);
        log.setNewDiscountAmount(effectiveDiscount);
        log.setOldPaidAmount(oldPaid);
        log.setNewPaidAmount(saved.getPaidAmount());
        log.setReason(request.getReason());
        log.setDetails(request.getNotes() != null ? request.getNotes() : "Điều chỉnh hóa đơn: Tổng tiền " + oldTotal + " -> " + effectiveTotal + " đ, Giảm giá " + oldDiscount + " -> " + effectiveDiscount + " đ");
        log.setPerformedBy(staff);
        invoiceAuditLogRepository.save(log);

        return saved;
    }

    @Override
    public Invoice cancelInvoice(Long invoiceId, String reason, Long staffUserId) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("Lý do hủy hóa đơn là bắt buộc.");
        }

        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn ID: " + invoiceId));

        if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
            return invoice;
        }

        // Chặn hủy nếu hóa đơn đã thu tiền
        if (invoice.getPaidAmount().compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalStateException("Hóa đơn đã ghi nhận thanh toán " + invoice.getPaidAmount() + " đ. Không thể hủy hóa đơn khi chưa xử lý hoàn tiền toàn bộ.");
        }

        StaffProfile staff = getValidStaffProfile(staffUserId, invoice.getBranch().getBranchId());
        BigDecimal oldTotal = invoice.getTotalAmount();
        BigDecimal oldPaid = invoice.getPaidAmount();

        invoice.setStatus(InvoiceStatus.CANCELLED);
        invoice.setPaymentStatus(InvoicePaymentStatus.CANCELLED);
        invoice.setAdjustmentReason(reason);
        invoice.setAdjustedBy(staff);

        // Hoàn nguyên điểm nếu đã dùng
        if (invoice.getPointDiscount() != null && invoice.getPointDiscount().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal pointVal = (invoice.getPatientProfile().getMembershipTier() != null && invoice.getPatientProfile().getMembershipTier().getPointValue() != null)
                    ? invoice.getPatientProfile().getMembershipTier().getPointValue() : new BigDecimal("1000.00");
            int points = invoice.getPointDiscount().divide(pointVal, 0, RoundingMode.HALF_UP).intValue();
            Optional<PatientLoyaltyWallet> walletOpt = patientLoyaltyWalletRepository.findByPatientProfile_ProfileId(invoice.getPatientProfile().getProfileId());
            walletOpt.ifPresent(wallet -> {
                wallet.setCurrentPoints(wallet.getCurrentPoints() + points);
                patientLoyaltyWalletRepository.save(wallet);

                PointTransaction pt = new PointTransaction();
                pt.setPatientProfile(invoice.getPatientProfile());
                pt.setInvoice(invoice);
                pt.setTransactionType(PointTransactionType.ADJUST);
                pt.setPoints(points);
                pt.setBalanceAfter(wallet.getCurrentPoints());
                pt.setReason("Hoàn trả " + points + " điểm do hủy hóa đơn " + invoice.getInvoiceCode());
                pointTransactionRepository.save(pt);
            });
        }

        // Hoàn nguyên voucher nếu đã dùng
        if (invoice.getVoucher() != null) {
            PatientVoucher voucher = invoice.getVoucher();
            voucher.setStatus(VoucherStatus.AVAILABLE);
            voucher.setUsedAt(null);
            patientVoucherRepository.save(voucher);
        }

        Invoice saved = invoiceRepository.save(invoice);

        InvoiceAuditLog log = new InvoiceAuditLog();
        log.setInvoice(saved);
        log.setActionType("CANCEL");
        log.setOldTotalAmount(oldTotal);
        log.setNewTotalAmount(BigDecimal.ZERO);
        log.setOldPaidAmount(oldPaid);
        log.setNewPaidAmount(oldPaid);
        log.setReason(reason);
        log.setDetails("Hóa đơn bị hủy, các ưu đãi và điểm tích lũy được hoàn nguyên an toàn.");
        log.setPerformedBy(staff);
        invoiceAuditLogRepository.save(log);

        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceAuditLog> getInvoiceAuditLogs(Long invoiceId) {
        return invoiceAuditLogRepository.findByInvoiceIdWithStaff(invoiceId);
    }

    @Override
    @Transactional(readOnly = true)
    public FinancialMetricsDTO getFinancialMetrics(Long branchId, LocalDateTime startDate, LocalDateTime endDate) {
        FinancialMetricsDTO metrics = new FinancialMetricsDTO();
        metrics.setTodayTotalBilled(invoiceRepository.sumTotalAmount(branchId, startDate, endDate));

        // Thực thu hôm nay = Tổng tiền thực nhận (tiền mặt / chuyển khoản) trừ Hoàn tiền (không ép về 0 nếu âm)
        BigDecimal realIntake = paymentRepository.sumRealRevenue(branchId, startDate, endDate);
        BigDecimal refunds = refundRepository.sumRefundAmount(branchId, startDate, endDate);
        BigDecimal netCollected = realIntake.subtract(refunds);

        metrics.setTodayTotalCollected(netCollected);
        metrics.setTodayTotalDebt(invoiceRepository.sumRemainingDebt(branchId, startDate, endDate));
        metrics.setTodayTotalRefunded(refunds);
        metrics.setTodayTotalInvoices(invoiceRepository.countInvoices(branchId, startDate, endDate));
        return metrics;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PerformedService> getUnbilledPerformedServices(Long visitId) {
        List<PerformedService> allServices = performedServiceRepository.findByVisitIdWithDetails(visitId);
        List<Long> billedIds = invoiceItemRepository.findBilledPerformedServiceIdsByVisitId(visitId);
        if (billedIds.isEmpty()) {
            return allServices;
        }
        return allServices.stream()
                .filter(ps -> !billedIds.contains(ps.getPerformedId()))
                .toList();
    }
}
