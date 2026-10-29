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

        // 6. Tính toán và áp dụng quyền lợi / ưu đãi qua phương thức dùng chung (áp dụng duy nhất Promotion HOẶC Voucher)
        List<Long> serviceIds = invoiceItems.stream()
                .map(it -> it.getService().getServiceId())
                .distinct()
                .toList();

        BenefitCalculationResult benefit = calculateAndValidateBenefits(
                totalServiceAmount,
                patient,
                branch.getBranchId(),
                serviceIds,
                request.getPromotionId(),
                request.getVoucherId(),
                request.getPointsToRedeem(),
                request.getDiscountAmount()
        );

        invoice.setDiscountAmount(benefit.memberDiscount.add(benefit.manualDiscount));
        invoice.setPromotionDiscount(benefit.promoOrVoucherDiscount);
        invoice.setPromotion(benefit.promotion);
        invoice.setVoucher(benefit.voucher);
        invoice.setPointDiscount(benefit.pointDiscount);
        invoice.setTotalAmount(benefit.netAmount);
        invoice.setPaidAmount(BigDecimal.ZERO);
        invoice.setRemainingDebt(benefit.netAmount);
        invoice.setPaymentStatus(benefit.netAmount.compareTo(BigDecimal.ZERO) == 0 ? InvoicePaymentStatus.PAID : InvoicePaymentStatus.UNPAID);
        invoice.setItems(invoiceItems);

        Invoice saved = invoiceRepository.save(invoice);

        // Chỉ khi hóa đơn có số tiền thanh toán thực = 0 đ (đã hoàn tất 100% ngay khi tạo) mới chốt sử dụng ưu đãi
        if (saved.getPaymentStatus() == InvoicePaymentStatus.PAID) {
            finalizeInvoiceBenefits(saved);
        }

        // Ghi Audit log
        InvoiceAuditLog log = new InvoiceAuditLog();
        log.setInvoice(saved);
        log.setActionType("CREATE");
        log.setNewTotalAmount(saved.getTotalAmount());
        log.setNewDiscountAmount(saved.getDiscountAmount());
        log.setNewPaidAmount(saved.getPaidAmount());
        log.setReason("Lập hóa đơn mới từ lượt khám " + visit.getVisitCode());
        log.setDetails("Tổng tiền dịch vụ: " + totalServiceAmount + " đ, Thực thu: " + saved.getTotalAmount() + " đ");
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

        List<Long> serviceIds = new ArrayList<>();
        for (InvoiceItemRequestDTO it : request.getItems()) {
            if (it.getServiceId() != null) {
                serviceIds.add(it.getServiceId());
            } else if (it.getPerformedId() != null) {
                performedServiceRepository.findById(it.getPerformedId())
                        .ifPresent(ps -> serviceIds.add(ps.getService().getServiceId()));
            }
        }

        BenefitCalculationResult benefit = calculateAndValidateBenefits(
                totalService,
                patientOpt.orElse(null),
                branchId,
                serviceIds,
                request.getPromotionId(),
                request.getVoucherId(),
                request.getPointsToRedeem(),
                request.getDiscountAmount()
        );

        preview.setMemberDiscountAmount(benefit.memberDiscount);
        if (patientOpt.isPresent() && patientOpt.get().getMembershipTier() != null) {
            preview.setTierName(patientOpt.get().getMembershipTier().getTierName());
            preview.setMemberDiscountPercent(patientOpt.get().getMembershipTier().getDiscountPercent());
        }
        preview.setPromotionDiscountAmount(benefit.promoOrVoucherDiscount);
        preview.setPromoName(benefit.promoOrVoucherName);
        preview.setPointDiscountAmount(benefit.pointDiscount);
        preview.setTotalDiscountAmount(benefit.totalDiscount);
        preview.setTotalAmount(benefit.netAmount);
        preview.setPointsEarnable(benefit.netAmount.divide(new BigDecimal("10000"), 0, RoundingMode.DOWN).intValue());

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
        if (request.getReason().trim().length() > 500) {
            throw new IllegalArgumentException("Lý do điều chỉnh tối đa 500 ký tự.");
        }
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn ID: " + invoiceId));
        if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
            throw new IllegalStateException("Không thể điều chỉnh hóa đơn đã bị hủy.");
        }
        StaffProfile staff = getValidStaffProfile(staffUserId, invoice.getBranch().getBranchId());
        if (Boolean.TRUE.equals(request.getCancelInvoice())) {
            return cancelInvoice(invoiceId, request.getReason(), staffUserId);
        }

        BigDecimal oldTotal = invoice.getTotalAmount();
        BigDecimal oldPaid = invoice.getPaidAmount();
        BigDecimal oldDiscount = Optional.ofNullable(invoice.getDiscountAmount()).orElse(BigDecimal.ZERO);
        BigDecimal oldSurcharge = Optional.ofNullable(invoice.getSurchargeAmount()).orElse(BigDecimal.ZERO);
        String oldDescription = Objects.toString(invoice.getSurchargeDescription(), "");
        BigDecimal totalService = Optional.ofNullable(invoice.getTotalServiceAmount()).orElse(BigDecimal.ZERO);
        BigDecimal promoDiscount = Optional.ofNullable(invoice.getPromotionDiscount()).orElse(BigDecimal.ZERO);
        BigDecimal pointDiscount = Optional.ofNullable(invoice.getPointDiscount()).orElse(BigDecimal.ZERO);

        BigDecimal surcharge = validateAdjustmentMoney(
                request.getNewSurchargeAmount() != null ? request.getNewSurchargeAmount() : oldSurcharge,
                "Phụ thu");
        String description = request.getNewSurchargeDescription() != null
                ? request.getNewSurchargeDescription().trim() : oldDescription;
        if (description.length() > 500) {
            throw new IllegalArgumentException("Nội dung phụ thu tối đa 500 ký tự.");
        }
        if (surcharge.signum() > 0 && description.isBlank()) {
            throw new IllegalArgumentException("Có phụ thu thì phải ghi rõ nội dung phụ thu.");
        }
        if (surcharge.signum() == 0) {
            description = null;
        }
        BigDecimal discount = validateAdjustmentMoney(
                request.getNewDiscountAmount() != null ? request.getNewDiscountAmount() : oldDiscount,
                "Giảm giá");
        BigDecimal beforeDiscount = totalService.add(surcharge);
        BigDecimal total = beforeDiscount.subtract(discount).subtract(promoDiscount)
                .subtract(pointDiscount).max(BigDecimal.ZERO);

        // Giu tuong thich API cu: chi gui tong moi thi suy ra giam gia.
        // Form moi khong gui tong; backend tu tinh tu cac khoan tien.
        if (request.getNewTotalAmount() != null) {
            BigDecimal requestedTotal = validateAdjustmentMoney(request.getNewTotalAmount(), "Tổng tiền mới");
            if (requestedTotal.compareTo(oldPaid) < 0) {
                throw new IllegalArgumentException("Tổng tiền mới không được nhỏ hơn số tiền đã thanh toán. Vui lòng hoàn tiền trước khi giảm tiền hóa đơn.");
            }
            if (request.getNewDiscountAmount() == null && request.getNewSurchargeAmount() == null) {
                discount = validateAdjustmentMoney(beforeDiscount.subtract(promoDiscount)
                        .subtract(pointDiscount).subtract(requestedTotal), "Giảm giá suy ra");
                total = requestedTotal;
            } else if (requestedTotal.compareTo(total) != 0) {
                throw new IllegalArgumentException("Dữ liệu mâu thuẫn: tổng tiền yêu cầu không khớp tổng dịch vụ + phụ thu - các khoản giảm = " + total + " đ.");
            }
        }
        total = validateAdjustmentMoney(total, "Tổng tiền hóa đơn");
        if (total.compareTo(oldPaid) < 0) {
            throw new IllegalArgumentException("Tổng tiền mới (" + total
                    + " đ) không được nhỏ hơn số tiền đã thanh toán (" + oldPaid
                    + " đ). Vui lòng hoàn tiền trước khi giảm tiền hóa đơn.");
        }

        invoice.setSurchargeAmount(surcharge);
        invoice.setSurchargeDescription(description);
        invoice.setDiscountAmount(discount);
        invoice.setTotalAmount(total);
        BigDecimal debt = total.subtract(oldPaid);
        invoice.setRemainingDebt(debt);
        invoice.setPaymentStatus(debt.signum() == 0 ? InvoicePaymentStatus.PAID
                : oldPaid.signum() > 0 ? InvoicePaymentStatus.PARTIAL : InvoicePaymentStatus.UNPAID);
        invoice.setAdjustmentReason(request.getReason().trim());
        invoice.setAdjustedBy(staff);
        Invoice saved = invoiceRepository.save(invoice);

        InvoiceAuditLog log = new InvoiceAuditLog();
        log.setInvoice(saved);
        log.setActionType("ADJUST");
        log.setOldTotalAmount(oldTotal);
        log.setNewTotalAmount(total);
        log.setOldDiscountAmount(oldDiscount);
        log.setNewDiscountAmount(discount);
        log.setOldPaidAmount(oldPaid);
        log.setNewPaidAmount(oldPaid);
        log.setReason(request.getReason().trim());
        log.setDetails("Tổng hóa đơn: " + oldTotal + " -> " + total
                + " đ; Giảm giá: " + oldDiscount + " -> " + discount
                + " đ; Phụ thu: " + oldSurcharge + " -> " + surcharge
                + " đ; Nội dung phụ thu cũ: [" + oldDescription
                + "]; Nội dung phụ thu mới: [" + Objects.toString(description, "")
                + "]; Ghi chú: " + Objects.toString(request.getNotes(), ""));
        log.setPerformedBy(staff);
        invoiceAuditLogRepository.save(log);
        return saved;
    }

    private BigDecimal validateAdjustmentMoney(BigDecimal value, String fieldName) {
        if (value == null || value.signum() < 0
                || value.compareTo(new BigDecimal("9999999999999.99")) > 0) {
            throw new IllegalArgumentException(fieldName + " phải từ 0 đến 9.999.999.999.999,99 đ.");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(fieldName + " chỉ được có tối đa 2 chữ số thập phân.");
        }
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

        // Hoàn nguyên điểm nếu đã dùng (chỉ khi ĐÃ TỪNG trừ điểm và CHƯA từng hoàn lại)
        if (invoice.getPointDiscount() != null && invoice.getPointDiscount().compareTo(BigDecimal.ZERO) > 0) {
            boolean wasRedeemed = pointTransactionRepository.existsByInvoice_InvoiceIdAndTransactionType(
                    invoice.getInvoiceId(), PointTransactionType.REDEEM);
            boolean alreadyReverted = pointTransactionRepository.existsByInvoice_InvoiceIdAndTransactionTypeAndPointsGreaterThan(
                    invoice.getInvoiceId(), PointTransactionType.ADJUST, 0);

            if (wasRedeemed && !alreadyReverted && invoice.getPatientProfile() != null) {
                BigDecimal pointVal = (invoice.getPatientProfile().getMembershipTier() != null && invoice.getPatientProfile().getMembershipTier().getPointValue() != null)
                        ? invoice.getPatientProfile().getMembershipTier().getPointValue() : new BigDecimal("1000.00");
                int points = invoice.getPointDiscount().divide(pointVal, 0, RoundingMode.HALF_UP).intValue();
                Optional<PatientLoyaltyWallet> walletOpt = patientLoyaltyWalletRepository.findByProfileIdForUpdate(invoice.getPatientProfile().getProfileId());
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
        }

        // Hoàn nguyên voucher nếu đã dùng
        if (invoice.getVoucher() != null) {
            PatientVoucher voucher = invoice.getVoucher();
            if (voucher.getStatus() == VoucherStatus.USED) {
                voucher.setStatus(VoucherStatus.AVAILABLE);
                voucher.setUsedAt(null);
                patientVoucherRepository.save(voucher);
            }
        }

        // Hoàn nguyên số lượt dùng khuyến mãi nếu đã từng tính
        if (invoice.getPromotion() != null) {
            Promotion promo = invoice.getPromotion();
            if (promo.getUsedCount() != null && promo.getUsedCount() > 0) {
                promo.setUsedCount(promo.getUsedCount() - 1);
                promotionRepository.save(promo);
            }
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

    @Override
    public void finalizeInvoiceBenefits(Invoice invoice) {
        if (invoice == null || invoice.getStatus() == InvoiceStatus.CANCELLED) {
            return;
        }

        // 1. Chốt sử dụng voucher nếu có
        if (invoice.getVoucher() != null) {
            PatientVoucher voucher = invoice.getVoucher();
            if (voucher.getStatus() == VoucherStatus.AVAILABLE) {
                voucher.setStatus(VoucherStatus.USED);
                voucher.setUsedAt(LocalDateTime.now());
                patientVoucherRepository.save(voucher);
            }
        }

        // 2. Chốt lượt sử dụng promotion chung nếu có
        if (invoice.getPromotion() != null) {
            Promotion promo = invoice.getPromotion();
            promo.setUsedCount((promo.getUsedCount() != null ? promo.getUsedCount() : 0) + 1);
            promotionRepository.save(promo);
        }

        // 3. Trừ điểm Loyalty và ghi nhận giao dịch REDEEM (đúng một lần duy nhất)
        if (invoice.getPointDiscount() != null && invoice.getPointDiscount().compareTo(BigDecimal.ZERO) > 0) {
            boolean alreadyRedeemed = pointTransactionRepository.existsByInvoice_InvoiceIdAndTransactionType(
                    invoice.getInvoiceId(), PointTransactionType.REDEEM);
            if (!alreadyRedeemed && invoice.getPatientProfile() != null) {
                PatientProfile patient = invoice.getPatientProfile();
                BigDecimal pointVal = (patient.getMembershipTier() != null && patient.getMembershipTier().getPointValue() != null)
                        ? patient.getMembershipTier().getPointValue() : new BigDecimal("1000.00");
                int pointsToDeduct = invoice.getPointDiscount().divide(pointVal, 0, RoundingMode.HALF_UP).intValue();
                if (pointsToDeduct > 0) {
                    Optional<PatientLoyaltyWallet> walletOpt = patientLoyaltyWalletRepository.findByProfileIdForUpdate(patient.getProfileId());
                    walletOpt.ifPresent(wallet -> {
                        int newBalance = Math.max(0, wallet.getCurrentPoints() - pointsToDeduct);
                        wallet.setCurrentPoints(newBalance);
                        patientLoyaltyWalletRepository.save(wallet);

                        PointTransaction pt = new PointTransaction();
                        pt.setPatientProfile(patient);
                        pt.setInvoice(invoice);
                        pt.setTransactionType(PointTransactionType.REDEEM);
                        pt.setPoints(pointsToDeduct);
                        pt.setBalanceAfter(newBalance);
                        pt.setReason("Đổi " + pointsToDeduct + " điểm thanh toán hóa đơn " + invoice.getInvoiceCode());
                        pointTransactionRepository.save(pt);
                    });
                }
            }
        }
    }

    private static class BenefitCalculationResult {
        BigDecimal memberDiscount = BigDecimal.ZERO;
        Promotion promotion = null;
        PatientVoucher voucher = null;
        BigDecimal promoOrVoucherDiscount = BigDecimal.ZERO;
        String promoOrVoucherName = null;
        int pointsToRedeem = 0;
        BigDecimal pointDiscount = BigDecimal.ZERO;
        BigDecimal manualDiscount = BigDecimal.ZERO;
        BigDecimal totalDiscount = BigDecimal.ZERO;
        BigDecimal netAmount = BigDecimal.ZERO;
    }

    private BenefitCalculationResult calculateAndValidateBenefits(
            BigDecimal totalServiceAmount,
            PatientProfile patient,
            Long branchId,
            List<Long> serviceIds,
            Long promotionId,
            Long voucherId,
            Integer pointsToRedeem,
            BigDecimal manualDiscountRequested
    ) {
        BenefitCalculationResult result = new BenefitCalculationResult();
        BigDecimal remaining = totalServiceAmount != null ? totalServiceAmount : BigDecimal.ZERO;

        // 1. Chính sách ưu đãi rõ ràng: Không cho phép cộng dồn đồng thời Promotion và Voucher
        if (promotionId != null && voucherId != null) {
            throw new IllegalArgumentException("Chính sách ưu đãi: Chỉ được áp dụng một trong hai hình thức: Chương trình khuyến mãi chung HOẶC Mã giảm giá (Voucher).");
        }

        // 2. Chiết khấu hạng thẻ thành viên
        if (patient != null && patient.getMembershipTier() != null && patient.getMembershipTier().getDiscountPercent() != null) {
            BigDecimal tierPercent = patient.getMembershipTier().getDiscountPercent();
            if (tierPercent.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal mDiscount = totalServiceAmount.multiply(tierPercent).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                result.memberDiscount = mDiscount.min(remaining);
                remaining = remaining.subtract(result.memberDiscount);
            }
        }

        // 3. Khuyến mãi chung HOẶC Voucher
        LocalDateTime now = LocalDateTime.now();
        if (promotionId != null) {
            Promotion promo = promotionRepository.findById(promotionId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chương trình khuyến mãi ID: " + promotionId));

            validatePromotionEligibility(promo, patient, branchId, totalServiceAmount, serviceIds, now);

            BigDecimal discount = calculatePromoDiscount(promo, totalServiceAmount);
            result.promoOrVoucherDiscount = discount.min(remaining);
            result.promotion = promo;
            result.promoOrVoucherName = promo.getPromoName();
            remaining = remaining.subtract(result.promoOrVoucherDiscount);
        } else if (voucherId != null) {
            PatientVoucher voucher = patientVoucherRepository.findById(voucherId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy voucher ID: " + voucherId));

            if (patient == null || !voucher.getPatientProfile().getProfileId().equals(patient.getProfileId())) {
                throw new IllegalArgumentException("Voucher được chọn không thuộc sở hữu của bệnh nhân này.");
            }
            if (voucher.getStatus() != VoucherStatus.AVAILABLE) {
                throw new IllegalStateException("Voucher không ở trạng thái khả dụng (trạng thái hiện tại: " + voucher.getStatus() + ").");
            }
            if (voucher.getExpiresAt() != null && !voucher.getExpiresAt().isAfter(now)) {
                throw new IllegalStateException("Voucher đã hết hạn sử dụng vào ngày " + voucher.getExpiresAt() + ".");
            }
            if (invoiceRepository.existsByVoucher_VoucherIdAndStatusNot(voucher.getVoucherId(), InvoiceStatus.CANCELLED)) {
                throw new IllegalStateException("Voucher này đang được áp dụng cho một hóa đơn khác chưa hủy.");
            }

            Promotion vp = voucher.getPromotion();
            if (vp == null) {
                throw new IllegalStateException("Voucher không gắn với chương trình khuyến mãi hợp lệ.");
            }
            validatePromotionEligibility(vp, patient, branchId, totalServiceAmount, serviceIds, now);

            BigDecimal discount = calculatePromoDiscount(vp, totalServiceAmount);
            result.promoOrVoucherDiscount = discount.min(remaining);
            result.voucher = voucher;
            result.promoOrVoucherName = vp.getPromoName() + " (" + voucher.getVoucherCode() + ")";
            remaining = remaining.subtract(result.promoOrVoucherDiscount);
        }

        // 4. Giảm giá thủ công (nếu có)
        BigDecimal manual = (manualDiscountRequested != null && manualDiscountRequested.compareTo(BigDecimal.ZERO) > 0)
                ? manualDiscountRequested : BigDecimal.ZERO;
        result.manualDiscount = manual.min(remaining);
        remaining = remaining.subtract(result.manualDiscount);

        // 5. Điểm tích lũy Loyalty
        if (pointsToRedeem != null && pointsToRedeem > 0 && patient != null) {
            Optional<PatientLoyaltyWallet> walletOpt = patientLoyaltyWalletRepository.findByPatientProfile_ProfileId(patient.getProfileId());
            if (walletOpt.isPresent()) {
                PatientLoyaltyWallet wallet = walletOpt.get();
                int maxPoints = wallet.getCurrentPoints();
                int redeem = Math.min(pointsToRedeem, maxPoints);
                if (redeem > 0) {
                    BigDecimal pointVal = (patient.getMembershipTier() != null && patient.getMembershipTier().getPointValue() != null)
                            ? patient.getMembershipTier().getPointValue() : new BigDecimal("1000.00");
                    BigDecimal ptDiscount = pointVal.multiply(new BigDecimal(redeem));
                    result.pointDiscount = ptDiscount.min(remaining);
                    result.pointsToRedeem = result.pointDiscount.divide(pointVal, 0, RoundingMode.HALF_UP).intValue();
                    remaining = remaining.subtract(result.pointDiscount);
                }
            }
        }

        result.totalDiscount = result.memberDiscount
                .add(result.promoOrVoucherDiscount)
                .add(result.manualDiscount)
                .add(result.pointDiscount);

        result.netAmount = totalServiceAmount.subtract(result.totalDiscount).max(BigDecimal.ZERO);

        return result;
    }

    private void validatePromotionEligibility(
            Promotion promo,
            PatientProfile patient,
            Long branchId,
            BigDecimal totalServiceAmount,
            List<Long> serviceIds,
            LocalDateTime now
    ) {
        if (!Boolean.TRUE.equals(promo.getIsActive())) {
            throw new IllegalStateException("Chương trình khuyến mãi '" + promo.getPromoName() + "' đang bị vô hiệu hóa.");
        }
        if (promo.getStartDate() != null && now.isBefore(promo.getStartDate())) {
            throw new IllegalStateException("Chương trình khuyến mãi chưa đến thời gian áp dụng.");
        }
        if (promo.getEndDate() != null && now.isAfter(promo.getEndDate())) {
            throw new IllegalStateException("Chương trình khuyến mãi đã kết thúc.");
        }
        if (promo.getBranch() != null && (branchId == null || !promo.getBranch().getBranchId().equals(branchId))) {
            throw new IllegalStateException("Chương trình khuyến mãi chỉ áp dụng tại chi nhánh: " + promo.getBranch().getBranchName());
        }
        if (promo.getUsageLimit() != null && (promo.getUsedCount() != null ? promo.getUsedCount() : 0) >= promo.getUsageLimit()) {
            throw new IllegalStateException("Chương trình khuyến mãi đã đạt giới hạn lượt sử dụng.");
        }
        if (promo.getMinBillAmount() != null && totalServiceAmount.compareTo(promo.getMinBillAmount()) < 0) {
            throw new IllegalStateException("Hóa đơn (" + totalServiceAmount + " đ) chưa đạt giá trị tối thiểu " + promo.getMinBillAmount() + " đ để áp dụng khuyến mãi.");
        }
        if (promo.getApplicableTier() != null) {
            if (patient == null || patient.getMembershipTier() == null
                    || patient.getMembershipTier().getDisplayOrder() < promo.getApplicableTier().getDisplayOrder()) {
                String currentTierName = (patient != null && patient.getMembershipTier() != null) ? patient.getMembershipTier().getTierName() : "Chưa có hạng";
                throw new IllegalStateException("Chương trình chỉ áp dụng cho bệnh nhân từ hạng " + promo.getApplicableTier().getTierName()
                        + " trở lên (Hạng hiện tại: " + currentTierName + ").");
            }
        }
        if (promo.getApplicableService() != null) {
            if (serviceIds == null || !serviceIds.contains(promo.getApplicableService().getServiceId())) {
                throw new IllegalStateException("Chương trình chỉ áp dụng khi hóa đơn có sử dụng dịch vụ: " + promo.getApplicableService().getServiceName());
            }
        }
    }

    private BigDecimal calculatePromoDiscount(Promotion promo, BigDecimal totalServiceAmount) {
        if (promo.getPromoType() == PromotionType.PERCENTAGE) {
            BigDecimal calc = totalServiceAmount.multiply(promo.getDiscountValue()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            if (promo.getMaxDiscountAmount() != null && calc.compareTo(promo.getMaxDiscountAmount()) > 0) {
                calc = promo.getMaxDiscountAmount();
            }
            return calc;
        } else {
            return promo.getDiscountValue().min(totalServiceAmount);
        }
    }
}