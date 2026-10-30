package com.cvdcms.service.impl;

import com.cvdcms.DTO.FinancialMetricsDTO;
import com.cvdcms.DTO.InvoiceAdjustmentRequestDTO;
import com.cvdcms.DTO.InvoiceCalculationPreviewDTO;
import com.cvdcms.DTO.InvoiceCreateRequestDTO;
import com.cvdcms.DTO.InvoiceItemRequestDTO;
import com.cvdcms.DTO.MedicineItemRequestDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.InvoiceService;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
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
    private final PrescriptionRepository prescriptionRepository;
    private final PrescriptionItemRepository prescriptionItemRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final BranchInventoryRepository branchInventoryRepository;
    private final MedicationRepository medicationRepository;
    private final StockIssueRepository stockIssueRepository;

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
            RefundRepository refundRepository,
            PrescriptionRepository prescriptionRepository,
            PrescriptionItemRepository prescriptionItemRepository,
            MaterialBatchRepository materialBatchRepository,
            BranchInventoryRepository branchInventoryRepository,
            MedicationRepository medicationRepository,
            StockIssueRepository stockIssueRepository
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
        this.prescriptionRepository = prescriptionRepository;
        this.prescriptionItemRepository = prescriptionItemRepository;
        this.materialBatchRepository = materialBatchRepository;
        this.branchInventoryRepository = branchInventoryRepository;
        this.medicationRepository = medicationRepository;
        this.stockIssueRepository = stockIssueRepository;
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

        // 4. Bắt buộc có ít nhất một dịch vụ hợp lệ hoặc một dòng thuốc hợp lệ
        boolean hasServices = request.getItems() != null && !request.getItems().isEmpty();
        boolean hasMedicines = Boolean.TRUE.equals(request.getBuyMedicineAtClinic())
                && request.getMedicineItems() != null && !request.getMedicineItems().isEmpty();

        if (!hasServices && !hasMedicines) {
            throw new IllegalArgumentException("Hóa đơn phải có ít nhất 01 dịch vụ hoặc 01 dòng thuốc mua tại phòng khám.");
        }

        // 5. Khởi tạo hóa đơn và cấu trúc chi tiết
        Set<Long> seenPerformedIds = new HashSet<>();
        List<InvoiceItem> invoiceItems = new ArrayList<>();
        BigDecimal totalServiceAmount = BigDecimal.ZERO;
        BigDecimal totalMedicineAmount = BigDecimal.ZERO;

        Invoice invoice = new Invoice();
        invoice.setVisit(visit);
        invoice.setPatientProfile(patient);
        invoice.setBranch(branch);
        invoice.setCashier(cashier);
        invoice.setNotes(request.getNotes());
        invoice.setStatus(InvoiceStatus.CONFIRMED);
        invoice.setInvoiceCode("HD" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());

        // A. Xử lý các dịch vụ thực hiện lâm sàng (nếu có)
        if (hasServices) {
            for (InvoiceItemRequestDTO itemDto : request.getItems()) {
                if (itemDto.getPerformedId() == null) {
                    throw new IllegalArgumentException("Mỗi dòng dịch vụ trên hóa đơn bắt buộc phải gắn với dịch vụ thực hiện (performedId).");
                }

                if (!seenPerformedIds.add(itemDto.getPerformedId())) {
                    throw new IllegalArgumentException("Dịch vụ thực hiện ID " + itemDto.getPerformedId() + " bị gửi trùng lặp trong yêu cầu lập hóa đơn.");
                }

                PerformedService ps = performedServiceRepository.findByIdForUpdate(itemDto.getPerformedId())
                        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dịch vụ thực hiện ID: " + itemDto.getPerformedId()));

                if (!ps.getVisit().getVisitId().equals(visit.getVisitId())
                        || !ps.getPatientProfile().getProfileId().equals(patient.getProfileId())
                        || !ps.getBranch().getBranchId().equals(branch.getBranchId())) {
                    throw new IllegalArgumentException("Dịch vụ thực hiện ID " + ps.getPerformedId() + " không thuộc ca khám/bệnh nhân/chi nhánh được chỉ định.");
                }

                if (ps.getStatus() != PerformedServiceStatus.COMPLETED) {
                    throw new IllegalStateException("Dịch vụ thực hiện ID " + ps.getPerformedId() + " chưa được bác sĩ hoàn thành (trạng thái: " + ps.getStatus() + ").");
                }

                if (itemDto.getServiceId() != null && !itemDto.getServiceId().equals(ps.getService().getServiceId())) {
                    throw new IllegalArgumentException("Mã dịch vụ không khớp với dịch vụ đã thực hiện trên lâm sàng.");
                }

                if (invoiceItemRepository.existsByPerformedIdAndInvoiceNotCancelled(ps.getPerformedId())) {
                    throw new IllegalStateException("Dịch vụ '" + ps.getService().getServiceName() + "' (ID: " + ps.getPerformedId() + ") đã được lập hóa đơn còn hiệu lực trước đó.");
                }

                BigDecimal unitPrice = ps.getUnitPrice() != null ? ps.getUnitPrice() : ps.getService().getBasePrice();
                BigDecimal quantity = ps.getQuantity() != null && ps.getQuantity().compareTo(BigDecimal.ZERO) > 0 ? ps.getQuantity() : BigDecimal.ONE;
                BigDecimal clinicalDiscount = ps.getDiscountAmount() != null ? ps.getDiscountAmount() : BigDecimal.ZERO;

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

                BigDecimal lineTotal = unitPrice.multiply(quantity).subtract(clinicalDiscount);
                if (lineTotal.compareTo(BigDecimal.ZERO) < 0) {
                    lineTotal = BigDecimal.ZERO;
                }

                InvoiceItem item = new InvoiceItem();
                item.setInvoice(invoice);
                item.setItemType(InvoiceItemType.SERVICE);
                item.setService(ps.getService());
                item.setItemName(ps.getService().getServiceName());
                item.setQuantity(quantity);
                item.setUnitPrice(unitPrice);
                item.setFinalAmount(lineTotal);
                item.setPerformedService(ps);

                invoiceItems.add(item);
                totalServiceAmount = totalServiceAmount.add(lineTotal);
            }
        }

        // B. Xử lý thuốc mua tại phòng khám (nếu có)
        Prescription prescription = null;
        if (hasMedicines) {
            if (request.getPrescriptionId() == null) {
                throw new IllegalArgumentException("Thiếu prescriptionId khi chọn mua thuốc tại phòng khám.");
            }
            prescription = prescriptionRepository.findByIdForUpdate(request.getPrescriptionId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn thuốc ID: " + request.getPrescriptionId()));

            // Cách ly ca khám: Kiểm tra đơn thuốc thuộc đúng ca khám
            if (prescription.getVisit() == null || !prescription.getVisit().getVisitId().equals(visit.getVisitId())) {
                throw new IllegalArgumentException("Đơn thuốc không thuộc ca khám được chọn.");
            }
            if (prescription.getPatientProfile() != null && !prescription.getPatientProfile().getProfileId().equals(patient.getProfileId())) {
                throw new IllegalArgumentException("Đơn thuốc không thuộc bệnh nhân của ca khám.");
            }
            if (prescription.getBranch() != null && !prescription.getBranch().getBranchId().equals(branch.getBranchId())) {
                throw new IllegalArgumentException("Đơn thuốc không thuộc chi nhánh của ca khám.");
            }

            if (prescription.getStatus() == PrescriptionStatus.CANCELLED) {
                throw new IllegalStateException("Đơn thuốc này đã bị hủy, không thể lập hóa đơn.");
            }
            if (prescription.getStatus() == PrescriptionStatus.EXTERNAL_PURCHASE) {
                throw new IllegalStateException("Đơn thuốc đang ghi nhận tự mua ngoài. Vui lòng chuyển sang mua tại phòng khám trước khi phát hành.");
            }

            Set<Long> seenPrescItemIds = new HashSet<>();
            List<StockIssueItem> stockIssueItems = new ArrayList<>();
            StockIssue stockIssue = new StockIssue();
            stockIssue.setBranch(branch);
            stockIssue.setIssueType(StockIssueType.INTERNAL_USE);
            stockIssue.setIssueDate(LocalDate.now());
            stockIssue.setIssuer(cashier);
            stockIssue.setIssueCode("PX" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd")) + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            stockIssue.setNotes("Xuất bán thuốc theo Hóa đơn chung " + invoice.getInvoiceCode() + " - Đơn thuốc: " + prescription.getPrescriptionCode());

            for (MedicineItemRequestDTO medDto : request.getMedicineItems()) {
                if (medDto.getPrescriptionItemId() == null) {
                    throw new IllegalArgumentException("Mỗi dòng thuốc bắt buộc phải có prescriptionItemId.");
                }
                if (!seenPrescItemIds.add(medDto.getPrescriptionItemId())) {
                    throw new IllegalArgumentException("Dòng thuốc ID " + medDto.getPrescriptionItemId() + " bị gửi trùng lặp trong yêu cầu lập hóa đơn.");
                }

                PrescriptionItem pItem = prescriptionItemRepository.findByIdForUpdate(medDto.getPrescriptionItemId())
                        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dòng đơn thuốc ID: " + medDto.getPrescriptionItemId()));

                if (!pItem.getPrescription().getPrescriptionId().equals(prescription.getPrescriptionId())) {
                    throw new IllegalArgumentException("Dòng thuốc ID " + pItem.getItemId() + " không thuộc đơn thuốc chỉ định.");
                }

                BigDecimal requestedQty = medDto.getQuantity();
                if (requestedQty == null || requestedQty.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("Số lượng mua thuốc phải lớn hơn 0.");
                }

                BigDecimal prescribedQty = pItem.getQuantity() != null ? pItem.getQuantity() : BigDecimal.ZERO;
                BigDecimal boughtQty = pItem.getBoughtQuantity() != null ? pItem.getBoughtQuantity() : BigDecimal.ZERO;
                BigDecimal remainingQty = prescribedQty.subtract(boughtQty);
                if (remainingQty.compareTo(BigDecimal.ZERO) < 0) remainingQty = BigDecimal.ZERO;

                if (requestedQty.compareTo(remainingQty) > 0) {
                    throw new IllegalArgumentException("Số lượng mua thuốc '" + pItem.getDrugName() + "' (" + requestedQty + ") vượt quá số lượng còn lại theo đơn (" + remainingQty + ").");
                }

                // Không đoán giá: Thuốc phải có giá bán hợp lệ trong cấu hình
                BigDecimal unitPrice = pItem.getUnitPrice();
                if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    if (pItem.getMedication() != null && pItem.getMedication().getSalePrice() != null) {
                        unitPrice = pItem.getMedication().getSalePrice();
                    }
                }
                if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalStateException("Thuốc '" + pItem.getDrugName() + "' chưa có giá bán hợp lệ trong cấu hình danh mục. Không thể phát hành hóa đơn.");
                }

                // Tích hợp kho: Kiểm tra tồn kho và xuất kho theo FEFO
                Medication med = pItem.getMedication();
                if (med != null && med.getMaterial() != null) {
                    Long matId = med.getMaterial().getMaterialId();
                    BranchInventory inventory = branchInventoryRepository.findForUpdate(branch.getBranchId(), matId)
                            .orElseThrow(() -> new IllegalStateException("Chi nhánh không có dữ liệu tồn kho cho vật tư/thuốc: " + pItem.getDrugName()));

                    BigDecimal currentStock = inventory.getCurrentStock() != null ? inventory.getCurrentStock() : BigDecimal.ZERO;
                    if (currentStock.compareTo(requestedQty) < 0) {
                        throw new IllegalStateException("Kho chi nhánh không đủ số lượng thuốc '" + pItem.getDrugName() + "' để xuất bán (Tồn kho: " + currentStock + ", Yêu cầu: " + requestedQty + ").");
                    }

                    List<MaterialBatch> availableBatches = materialBatchRepository.findAvailableBatchesFefoForUpdate(
                            branch.getBranchId(), matId, LocalDate.now());

                    BigDecimal totalBatchStock = availableBatches.stream()
                            .map(b -> b.getQuantityRemaining() != null ? b.getQuantityRemaining() : BigDecimal.ZERO)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    if (totalBatchStock.compareTo(requestedQty) < 0) {
                        throw new IllegalStateException("Tổng tồn các lô còn hạn của thuốc '" + pItem.getDrugName() + "' không đủ để xuất bán (" + totalBatchStock + " < " + requestedQty + ").");
                    }

                    BigDecimal qtyToAllocate = requestedQty;
                    for (MaterialBatch batch : availableBatches) {
                        if (qtyToAllocate.compareTo(BigDecimal.ZERO) <= 0) break;
                        BigDecimal batchRemaining = batch.getQuantityRemaining() != null ? batch.getQuantityRemaining() : BigDecimal.ZERO;
                        if (batchRemaining.compareTo(BigDecimal.ZERO) <= 0) continue;

                        BigDecimal takeFromBatch = batchRemaining.min(qtyToAllocate);
                        batch.setQuantityRemaining(batchRemaining.subtract(takeFromBatch));
                        materialBatchRepository.save(batch);

                        qtyToAllocate = qtyToAllocate.subtract(takeFromBatch);

                        InvoiceItem medItem = new InvoiceItem();
                        medItem.setInvoice(invoice);
                        medItem.setItemType(InvoiceItemType.MEDICINE);
                        medItem.setPrescriptionItem(pItem);
                        medItem.setMedication(med);
                        medItem.setBatch(batch);
                        medItem.setItemName(pItem.getDrugName());
                        medItem.setUnit(pItem.getUnit());
                        medItem.setUsageInstruction(pItem.getUsageInstruction());
                        medItem.setQuantity(takeFromBatch);
                        medItem.setUnitPrice(unitPrice);
                        BigDecimal lineTotal = unitPrice.multiply(takeFromBatch).setScale(2, RoundingMode.HALF_UP);
                        medItem.setFinalAmount(lineTotal);

                        invoiceItems.add(medItem);
                        totalMedicineAmount = totalMedicineAmount.add(lineTotal);

                        StockIssueItem issueItem = new StockIssueItem();
                        issueItem.setIssue(stockIssue);
                        issueItem.setMaterial(med.getMaterial());
                        issueItem.setBatch(batch);
                        issueItem.setQuantity(takeFromBatch);
                        issueItem.setNotes("Xuất bán thuốc theo hóa đơn chung từ đơn " + prescription.getPrescriptionCode() + " (Thuốc: " + pItem.getDrugName() + " - Lô: " + batch.getBatchNumber() + ")");
                        stockIssueItems.add(issueItem);
                    }

                    inventory.setCurrentStock(currentStock.subtract(requestedQty));
                    branchInventoryRepository.save(inventory);
                } else {
                    InvoiceItem medItem = new InvoiceItem();
                    medItem.setInvoice(invoice);
                    medItem.setItemType(InvoiceItemType.MEDICINE);
                    medItem.setPrescriptionItem(pItem);
                    medItem.setMedication(med);
                    medItem.setItemName(pItem.getDrugName());
                    medItem.setUnit(pItem.getUnit());
                    medItem.setUsageInstruction(pItem.getUsageInstruction());
                    medItem.setQuantity(requestedQty);
                    medItem.setUnitPrice(unitPrice);
                    BigDecimal lineTotal = unitPrice.multiply(requestedQty).setScale(2, RoundingMode.HALF_UP);
                    medItem.setFinalAmount(lineTotal);

                    invoiceItems.add(medItem);
                    totalMedicineAmount = totalMedicineAmount.add(lineTotal);
                }

                pItem.setBoughtQuantity(boughtQty.add(requestedQty));
                prescriptionItemRepository.save(pItem);
            }

            if (!stockIssueItems.isEmpty()) {
                stockIssue.setItems(stockIssueItems);
                stockIssueRepository.save(stockIssue);
            }

            boolean allCompleted = true;
            for (PrescriptionItem pi : prescription.getItems()) {
                BigDecimal pQty = pi.getQuantity() != null ? pi.getQuantity() : BigDecimal.ZERO;
                BigDecimal bQty = pi.getBoughtQuantity() != null ? pi.getBoughtQuantity() : BigDecimal.ZERO;
                if (bQty.compareTo(pQty) < 0) {
                    allCompleted = false;
                    break;
                }
            }
            prescription.setStatus(allCompleted ? PrescriptionStatus.CLINIC_PURCHASE_COMPLETED : PrescriptionStatus.CLINIC_PURCHASE_PARTIAL);
            prescription.setPurchaseChoice(PrescriptionPurchaseChoice.CLINIC);
            prescriptionRepository.save(prescription);

            invoice.setPrescription(prescription);
        }

        // C. Phân loại hóa đơn
        if (hasServices && hasMedicines) {
            invoice.setInvoiceType(InvoiceType.MIXED);
        } else if (hasMedicines) {
            invoice.setInvoiceType(InvoiceType.MEDICINE);
        } else {
            invoice.setInvoiceType(InvoiceType.SERVICE);
        }

        invoice.setTotalServiceAmount(totalServiceAmount.setScale(2, RoundingMode.HALF_UP));
        invoice.setTotalMedicineAmount(totalMedicineAmount.setScale(2, RoundingMode.HALF_UP));

        // 6. Tính toán và áp dụng quyền lợi / ưu đãi: Chỉ áp dụng trên phần DỊCH VỤ
        List<Long> serviceIds = invoiceItems.stream()
                .filter(it -> it.getService() != null)
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

        // Tổng phải thanh toán = (Tiền dịch vụ - Các khoản giảm) + Tiền thuốc
        BigDecimal netPayable = benefit.netAmount.add(totalMedicineAmount).setScale(2, RoundingMode.HALF_UP);
        invoice.setTotalAmount(netPayable);
        invoice.setPaidAmount(BigDecimal.ZERO);
        invoice.setRemainingDebt(netPayable);
        invoice.setPaymentStatus(netPayable.compareTo(BigDecimal.ZERO) == 0 ? InvoicePaymentStatus.PAID : InvoicePaymentStatus.UNPAID);
        invoice.setItems(invoiceItems);

        Invoice saved = invoiceRepository.save(invoice);

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
        log.setDetails("Loại: " + saved.getInvoiceType() + ", Dịch vụ: " + totalServiceAmount + " đ, Thuốc: " + totalMedicineAmount + " đ, Thực thu: " + saved.getTotalAmount() + " đ");
        log.setPerformedBy(cashier);
        invoiceAuditLogRepository.save(log);

        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public InvoiceCalculationPreviewDTO calculatePreview(InvoiceCreateRequestDTO request) {
        boolean hasServices = request != null && request.getItems() != null && !request.getItems().isEmpty();
        boolean hasMedicines = request != null && Boolean.TRUE.equals(request.getBuyMedicineAtClinic())
                && request.getMedicineItems() != null && !request.getMedicineItems().isEmpty();

        if (!hasServices && !hasMedicines) {
            throw new IllegalArgumentException("Hóa đơn phải có ít nhất 01 dịch vụ hoặc 01 dòng thuốc hợp lệ.");
        }

        InvoiceCalculationPreviewDTO preview = new InvoiceCalculationPreviewDTO();
        BigDecimal totalService = BigDecimal.ZERO;

        if (hasServices) {
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
        }
        preview.setTotalServiceAmount(totalService);

        BigDecimal totalMedicine = BigDecimal.ZERO;
        if (hasMedicines) {
            for (MedicineItemRequestDTO mReq : request.getMedicineItems()) {
                if (mReq.getPrescriptionItemId() == null) continue;
                BigDecimal requestedQty = mReq.getQuantity() != null ? mReq.getQuantity() : BigDecimal.ZERO;
                if (requestedQty.compareTo(BigDecimal.ZERO) <= 0) continue;

                PrescriptionItem pItem = prescriptionItemRepository.findById(mReq.getPrescriptionItemId())
                        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dòng thuốc ID: " + mReq.getPrescriptionItemId()));

                BigDecimal unitPrice = pItem.getUnitPrice();
                if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    if (pItem.getMedication() != null && pItem.getMedication().getSalePrice() != null) {
                        unitPrice = pItem.getMedication().getSalePrice();
                    }
                }
                if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalStateException("Thuốc '" + pItem.getDrugName() + "' chưa có giá bán hợp lệ trong cấu hình danh mục.");
                }

                BigDecimal lineTotal = unitPrice.multiply(requestedQty).setScale(2, RoundingMode.HALF_UP);
                totalMedicine = totalMedicine.add(lineTotal);
            }
        }
        preview.setTotalMedicineAmount(totalMedicine.setScale(2, RoundingMode.HALF_UP));

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
        if (hasServices) {
            for (InvoiceItemRequestDTO it : request.getItems()) {
                if (it.getServiceId() != null) {
                    serviceIds.add(it.getServiceId());
                } else if (it.getPerformedId() != null) {
                    performedServiceRepository.findById(it.getPerformedId())
                            .ifPresent(ps -> serviceIds.add(ps.getService().getServiceId()));
                }
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
        preview.setTotalAmount(benefit.netAmount.add(totalMedicine).setScale(2, RoundingMode.HALF_UP));
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
        BigDecimal totalMedicine = Optional.ofNullable(invoice.getTotalMedicineAmount()).orElse(BigDecimal.ZERO);
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
        BigDecimal beforeDiscount = totalService.add(totalMedicine).add(surcharge);
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
                List<PointTransaction> txList = pointTransactionRepository.findByInvoice_InvoiceId(invoice.getInvoiceId());
                int points = txList.stream()
                        .filter(pt -> pt.getTransactionType() == PointTransactionType.REDEEM && pt.getPoints() != null)
                        .mapToInt(PointTransaction::getPoints)
                        .findFirst()
                        .orElseGet(() -> {
                            BigDecimal pointVal = (invoice.getPatientProfile().getMembershipTier() != null && invoice.getPatientProfile().getMembershipTier().getPointValue() != null)
                                    ? invoice.getPatientProfile().getMembershipTier().getPointValue() : new BigDecimal("100");
                            return invoice.getPointDiscount().divide(pointVal, 0, RoundingMode.HALF_UP).intValue();
                        });
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

        // Nếu là hóa đơn thuốc: Đây là thao tác hủy chứng từ tài chính.
        // Tuyệt đối KHÔNG tự ý giảm/khôi phục boughtQuantity của đơn thuốc và KHÔNG tự ý nhập lại kho.
        // Thuốc đã được xuất kho và giao cho người bệnh; việc hủy hóa đơn tài chính không được phép
        // làm sai lệch số lượng thuốc đã xuất trên lâm sàng, tránh nguy cơ xuất trùng hoặc mua vượt đơn.
        // Bất kỳ thao tác trả hàng vật lý và nhập lại kho nào phải được hạch toán qua phiếu nhập kho riêng.
        if (invoice.getInvoiceType() == InvoiceType.MEDICINE || invoice.getInvoiceType() == InvoiceType.MIXED) {
            log.info("Hủy hóa đơn ID {} (loại {}): Bảo toàn boughtQuantity và tồn kho vật lý (không tự động khôi phục).", invoice.getInvoiceId(), invoice.getInvoiceType());
        }

        Invoice saved = invoiceRepository.save(invoice);

        InvoiceAuditLog cancelAudit = new InvoiceAuditLog();
        cancelAudit.setInvoice(saved);
        cancelAudit.setActionType("CANCEL");
        cancelAudit.setOldTotalAmount(oldTotal);
        cancelAudit.setNewTotalAmount(BigDecimal.ZERO);
        cancelAudit.setOldPaidAmount(oldPaid);
        cancelAudit.setNewPaidAmount(oldPaid);
        cancelAudit.setReason(reason);
        cancelAudit.setDetails(invoice.getInvoiceType() == InvoiceType.MEDICINE || invoice.getInvoiceType() == InvoiceType.MIXED
                ? "Hủy hóa đơn tài chính. Giữ nguyên số lượng thuốc đã xuất (boughtQuantity) để bảo vệ đơn thuốc lâm sàng và dữ liệu kho."
                : "Hóa đơn dịch vụ bị hủy, các ưu đãi và điểm tích lũy được hoàn nguyên an toàn.");
        cancelAudit.setPerformedBy(staff);
        invoiceAuditLogRepository.save(cancelAudit);

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
                        ? patient.getMembershipTier().getPointValue() : new BigDecimal("10.00");
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
                            ? patient.getMembershipTier().getPointValue() : new BigDecimal("10.00");
                    BigDecimal ptDiscount = pointVal.multiply(new BigDecimal(redeem)).setScale(2, RoundingMode.HALF_UP);
                    result.pointDiscount = ptDiscount.min(remaining).setScale(2, RoundingMode.HALF_UP);
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

    @Override
    public Invoice createMedicineInvoice(com.cvdcms.DTO.MedicineInvoiceCreateRequestDTO request, Long cashierUserId) {
        if (request == null || request.getPrescriptionId() == null) {
            throw new IllegalArgumentException("Thông tin lập hóa đơn thuốc không hợp lệ: thiếu mã đơn thuốc (prescriptionId).");
        }

        // 1. Khóa và tải đơn thuốc nguồn
        Prescription prescription = prescriptionRepository.findByIdForUpdate(request.getPrescriptionId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn thuốc ID: " + request.getPrescriptionId()));

        if (prescription.getStatus() == PrescriptionStatus.CANCELLED) {
            throw new IllegalStateException("Đơn thuốc đã bị hủy, không thể lập hóa đơn thuốc.");
        }
        if (prescription.getStatus() == PrescriptionStatus.EXTERNAL_PURCHASE) {
            throw new IllegalStateException("Đơn thuốc đã được ghi nhận bệnh nhân tự mua ngoài. Vui lòng chuyển trạng thái sang mua tại phòng khám nếu bệnh nhân muốn mua.");
        }
        if (prescription.getStatus() == PrescriptionStatus.CLINIC_PURCHASE_COMPLETED) {
            throw new IllegalStateException("Đơn thuốc này đã được mua đủ toàn bộ tại phòng khám trước đó.");
        }

        Branch branch = prescription.getBranch();
        PatientProfile patient = prescription.getPatientProfile();
        Visit visit = prescription.getVisit();

        // 2. Kiểm tra nguồn bác sĩ & trạng thái bàn giao của ca khám (nếu đơn thuốc gắn với ca khám)
        if (visit != null) {
            if (visit.getStatus() == VisitStatus.IN_EXAM || visit.getStatus() == VisitStatus.WAITING
                    || visit.getStatus() == VisitStatus.WAITING_PARACLINICAL || visit.getStatus() == VisitStatus.WAITING_RESULT
                    || visit.getStatus() == VisitStatus.IN_TREATMENT) {
                throw new IllegalStateException("Đơn thuốc chưa được bác sĩ xác nhận bàn giao (ca khám đang trong tiến trình lâm sàng: " + visit.getStatus() + ").");
            }
            if (visit.getStatus() == VisitStatus.CANCELLED) {
                throw new IllegalStateException("Ca khám liên quan đã bị hủy, không thể lập hóa đơn cho đơn thuốc này.");
            }
        }

        // 3. Kiểm tra quyền và phân công chi nhánh của thu ngân/kế toán
        StaffProfile cashier = getValidStaffProfile(cashierUserId, branch.getBranchId());

        // 4. Kiểm tra danh sách thuốc yêu cầu mua
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("Vui lòng chọn ít nhất 01 loại thuốc cần mua tại phòng khám.");
        }

        Set<Long> seenItemIds = new HashSet<>();
        for (com.cvdcms.DTO.MedicineInvoiceCreateRequestDTO.Item reqItem : request.getItems()) {
            if (reqItem != null && reqItem.getPrescriptionItemId() != null) {
                if (!seenItemIds.add(reqItem.getPrescriptionItemId())) {
                    throw new IllegalArgumentException("Thuốc có ID dòng " + reqItem.getPrescriptionItemId() + " bị gửi trùng lặp trong yêu cầu.");
                }
            }
        }

        // Sắp xếp theo ID dòng để loại trừ triệt để nguy cơ Deadlock khi nhiều đơn thuốc mua đồng thời
        List<com.cvdcms.DTO.MedicineInvoiceCreateRequestDTO.Item> sortedReqItems = new ArrayList<>(request.getItems());
        sortedReqItems.sort(Comparator.comparing(com.cvdcms.DTO.MedicineInvoiceCreateRequestDTO.Item::getPrescriptionItemId, Comparator.nullsLast(Comparator.naturalOrder())));

        List<InvoiceItem> invoiceItems = new ArrayList<>();
        List<StockIssueItem> stockIssueItems = new ArrayList<>();
        BigDecimal totalMedicineAmount = BigDecimal.ZERO;

        Invoice invoice = new Invoice();
        invoice.setInvoiceType(InvoiceType.MEDICINE);
        invoice.setPrescription(prescription);
        invoice.setVisit(visit);
        invoice.setPatientProfile(patient);
        invoice.setBranch(branch);
        invoice.setCashier(cashier);
        invoice.setNotes(request.getNotes());
        invoice.setStatus(InvoiceStatus.CONFIRMED);
        invoice.setInvoiceCode("HDT" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());

        StockIssue stockIssue = new StockIssue();
        stockIssue.setBranch(branch);
        stockIssue.setIssueType(StockIssueType.INTERNAL_USE);
        stockIssue.setIssueDate(LocalDate.now());
        stockIssue.setIssuer(cashier);
        stockIssue.setIssueCode("PX" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd")) + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        stockIssue.setNotes("Xuất bán thuốc theo Hóa đơn " + invoice.getInvoiceCode() + " - Đơn thuốc: " + prescription.getPrescriptionCode());

        for (com.cvdcms.DTO.MedicineInvoiceCreateRequestDTO.Item reqItem : sortedReqItems) {
            if (reqItem.getPrescriptionItemId() == null) {
                continue;
            }

            // Khóa bi quan dòng đơn thuốc để chống mua trùng lặp / race condition
            PrescriptionItem prescItem = prescriptionItemRepository.findByIdForUpdate(reqItem.getPrescriptionItemId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dòng thuốc ID: " + reqItem.getPrescriptionItemId()));

            // Backend kiểm tra thuộc đúng prescription, bệnh nhân, ca khám và chi nhánh
            if (!prescItem.getPrescription().getPrescriptionId().equals(prescription.getPrescriptionId())) {
                throw new IllegalArgumentException("Dòng thuốc ID " + prescItem.getItemId() + " không thuộc đơn thuốc này.");
            }
            if (prescription.getPatientProfile() == null || !prescription.getPatientProfile().getProfileId().equals(patient.getProfileId())) {
                throw new IllegalArgumentException("Đơn thuốc không khớp với hồ sơ bệnh nhân.");
            }
            if (prescription.getBranch() == null || !prescription.getBranch().getBranchId().equals(branch.getBranchId())) {
                throw new IllegalArgumentException("Đơn thuốc không thuộc chi nhánh được chỉ định.");
            }
            if (prescription.getVisit() != null && visit != null && !prescription.getVisit().getVisitId().equals(visit.getVisitId())) {
                throw new IllegalArgumentException("Đơn thuốc không khớp với ca khám liên quan.");
            }

            BigDecimal remaining = prescItem.getRemainingQuantity();
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalStateException("Thuốc '" + prescItem.getDrugName() + "' đã được mua đủ, không còn số lượng để mua thêm.");
            }

            BigDecimal buyQty = reqItem.getQuantity();
            if (buyQty == null || buyQty.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Số lượng mua cho thuốc '" + prescItem.getDrugName() + "' phải lớn hơn 0.");
            }
            if (buyQty.compareTo(remaining) > 0) {
                throw new IllegalStateException("Số lượng mua (" + buyQty + ") vượt quá số lượng còn lại của đơn bác sĩ kê (" + remaining + ") cho thuốc '" + prescItem.getDrugName() + "'.");
            }

            // 5. Kiểm tra liên kết nguồn tồn kho hợp lệ - KHÔNG âm thầm bỏ qua
            Medication med = prescItem.getMedication();
            if (med == null) {
                throw new IllegalStateException("Thuốc '" + prescItem.getDrugName() + "' (ID dòng: " + prescItem.getItemId() + ") chưa được liên kết với danh mục dược phẩm (Medication). Thiếu cấu hình nguồn bán, chặn bán tại phòng khám.");
            }
            if (med.getMaterial() == null || med.getMaterial().getMaterialId() == null) {
                throw new IllegalStateException("Dược phẩm '" + med.getDrugName() + "' (Mã: " + med.getDrugCode() + ") chưa được liên kết với vật tư/kho (Material). Thiếu cấu hình nguồn tồn kho, chặn bán tại phòng khám.");
            }
            Material material = med.getMaterial();

            // Kiểm tra tương thích đơn vị bán và đơn vị kho
            if (prescItem.getUnit() != null && material.getUnit() != null) {
                if (!prescItem.getUnit().trim().equalsIgnoreCase(material.getUnit().trim())
                        && (med.getUnit() == null || !prescItem.getUnit().trim().equalsIgnoreCase(med.getUnit().trim()))) {
                    throw new IllegalStateException("Đơn vị bán của thuốc ('" + prescItem.getUnit() + "') không tương thích với đơn vị quản lý kho của vật tư ('" + material.getUnit() + "'). Thiếu cấu hình quy đổi đơn vị.");
                }
            }

            // Kiểm tra và khóa tồn kho chi nhánh
            BranchInventory inv = branchInventoryRepository.findForUpdate(branch.getBranchId(), material.getMaterialId())
                    .orElse(null);
            if (inv == null || inv.getCurrentStock().compareTo(buyQty) < 0) {
                BigDecimal curr = inv == null ? BigDecimal.ZERO : inv.getCurrentStock();
                throw new IllegalStateException("Thuốc '" + prescItem.getDrugName() + "' không đủ tồn kho tại chi nhánh " + branch.getBranchName() + " (Tồn kho hiện có: " + curr + " " + material.getUnit() + ", Yêu cầu mua: " + buyQty + " " + prescItem.getUnit() + ").");
            }

            // Khóa các lô còn hạn dùng theo FEFO (First Expired, First Out)
            List<MaterialBatch> availableBatches = materialBatchRepository.findAvailableBatchesFefoForUpdate(branch.getBranchId(), material.getMaterialId(), LocalDate.now());
            if (availableBatches.isEmpty()) {
                throw new IllegalStateException("Không tìm thấy lô thuốc hợp lệ còn hạn sử dụng cho thuốc '" + prescItem.getDrugName() + "' tại chi nhánh " + branch.getBranchName() + ".");
            }

            BigDecimal totalBatchStock = availableBatches.stream()
                    .map(MaterialBatch::getQuantityRemaining)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (totalBatchStock.compareTo(buyQty) < 0) {
                throw new IllegalStateException("Tổng số lượng thuốc còn hạn sử dụng trong các lô (" + totalBatchStock + " " + material.getUnit() + ") không đủ đáp ứng số lượng mua (" + buyQty + " " + prescItem.getUnit() + ") cho thuốc '" + prescItem.getDrugName() + "'.");
            }

            // Đơn giá bán tại thời điểm lập hóa đơn lấy từ DB (không tin client)
            BigDecimal unitPrice = null;
            if (med.getSalePrice() != null && med.getSalePrice().compareTo(BigDecimal.ZERO) > 0) {
                unitPrice = med.getSalePrice();
            } else if (prescItem.getUnitPrice() != null && prescItem.getUnitPrice().compareTo(BigDecimal.ZERO) > 0) {
                unitPrice = prescItem.getUnitPrice();
            } else {
                throw new IllegalStateException("Thuốc '" + prescItem.getDrugName() + "' chưa có giá bán hợp lệ trong hệ thống. Vui lòng cấu hình giá bán tại danh mục dược phẩm trước khi phát hành hóa đơn.");
            }

            // Trừ kho theo từng lô FEFO - Lưu đầy đủ từng lô và số lượng xuất
            BigDecimal needDeduct = buyQty;
            for (MaterialBatch b : availableBatches) {
                if (needDeduct.compareTo(BigDecimal.ZERO) <= 0) break;
                BigDecimal remInBatch = b.getQuantityRemaining();
                if (remInBatch.compareTo(BigDecimal.ZERO) <= 0) continue;

                BigDecimal takeFromBatch = remInBatch.min(needDeduct);
                b.setQuantityRemaining(remInBatch.subtract(takeFromBatch));
                if (b.getQuantityRemaining().compareTo(BigDecimal.ZERO) == 0) {
                    b.setStatus(BatchStatus.OUT_OF_STOCK);
                }
                materialBatchRepository.save(b);
                needDeduct = needDeduct.subtract(takeFromBatch);

                // Dòng hóa đơn cho từng lô
                BigDecimal batchLineTotal = unitPrice.multiply(takeFromBatch).setScale(2, RoundingMode.HALF_UP);
                InvoiceItem invoiceItem = new InvoiceItem();
                invoiceItem.setInvoice(invoice);
                invoiceItem.setItemType(InvoiceItemType.MEDICINE);
                invoiceItem.setItemName(prescItem.getDrugName());
                invoiceItem.setUnit(prescItem.getUnit());
                invoiceItem.setUsageInstruction(prescItem.getUsageInstruction());
                invoiceItem.setQuantity(takeFromBatch);
                invoiceItem.setUnitPrice(unitPrice);
                invoiceItem.setFinalAmount(batchLineTotal);
                invoiceItem.setPrescriptionItem(prescItem);
                invoiceItem.setMedication(med);
                invoiceItem.setBatch(b);

                invoiceItems.add(invoiceItem);
                totalMedicineAmount = totalMedicineAmount.add(batchLineTotal);

                // Dòng chứng từ xuất kho cho từng lô
                StockIssueItem issueItem = new StockIssueItem();
                issueItem.setIssue(stockIssue);
                issueItem.setMaterial(material);
                issueItem.setBatch(b);
                issueItem.setQuantity(takeFromBatch);
                issueItem.setNotes("Xuất bán thuốc theo đơn " + prescription.getPrescriptionCode() + " (Thuốc: " + prescItem.getDrugName() + " - Lô: " + b.getBatchNumber() + ")");
                stockIssueItems.add(issueItem);
            }

            inv.setCurrentStock(inv.getCurrentStock().subtract(buyQty));
            branchInventoryRepository.save(inv);

            // Cập nhật số lượng đã mua của dòng đơn thuốc
            BigDecimal newBought = (prescItem.getBoughtQuantity() != null ? prescItem.getBoughtQuantity() : BigDecimal.ZERO).add(buyQty);
            prescItem.setBoughtQuantity(newBought);
            prescriptionItemRepository.save(prescItem);
        }

        if (invoiceItems.isEmpty()) {
            throw new IllegalArgumentException("Không có dòng thuốc nào hợp lệ để tạo hóa đơn.");
        }

        // Lưu chứng từ xuất kho
        stockIssue.setItems(stockIssueItems);
        stockIssueRepository.save(stockIssue);

        // Cập nhật trạng thái đơn thuốc
        boolean allFinished = prescription.getItems().stream()
                .allMatch(PrescriptionItem::isFullyBought);
        prescription.setStatus(allFinished ? PrescriptionStatus.CLINIC_PURCHASE_COMPLETED : PrescriptionStatus.CLINIC_PURCHASE_PARTIAL);
        prescription.setPurchaseChoice(PrescriptionPurchaseChoice.CLINIC);
        prescription.setHandledBy(cashier);
        prescription.setHandledAt(LocalDateTime.now());
        prescriptionRepository.save(prescription);

        // Lưu Hóa đơn thuốc độc lập (Không tự động áp ưu đãi dịch vụ cho thuốc)
        invoice.setTotalServiceAmount(BigDecimal.ZERO);
        invoice.setTotalMedicineAmount(totalMedicineAmount);
        invoice.setDiscountAmount(BigDecimal.ZERO);
        invoice.setPromotionDiscount(BigDecimal.ZERO);
        invoice.setPointDiscount(BigDecimal.ZERO);
        invoice.setTotalAmount(totalMedicineAmount);
        invoice.setPaidAmount(BigDecimal.ZERO);
        invoice.setRemainingDebt(totalMedicineAmount);
        invoice.setPaymentStatus(totalMedicineAmount.compareTo(BigDecimal.ZERO) == 0 ? InvoicePaymentStatus.PAID : InvoicePaymentStatus.UNPAID);
        invoice.setItems(invoiceItems);

        Invoice saved = invoiceRepository.save(invoice);

        // Audit log
        InvoiceAuditLog audit = new InvoiceAuditLog();
        audit.setInvoice(saved);
        audit.setActionType("CREATE_MEDICINE");
        audit.setNewTotalAmount(saved.getTotalAmount());
        audit.setNewDiscountAmount(BigDecimal.ZERO);
        audit.setNewPaidAmount(BigDecimal.ZERO);
        audit.setReason("Lập hóa đơn thuốc riêng & Xác nhận xuất kho từ đơn thuốc " + prescription.getPrescriptionCode());
        audit.setDetails("Tổng tiền thuốc: " + totalMedicineAmount + " đ, Số dòng xuất (theo lô): " + invoiceItems.size() + ", Mã phiếu xuất kho: " + stockIssue.getIssueCode());
        audit.setPerformedBy(cashier);
        invoiceAuditLogRepository.save(audit);

        return saved;
    }

    @Override
    public void recordPrescriptionChoice(Long prescriptionId, String choice, String note, Long cashierUserId) {
        if (prescriptionId == null) {
            throw new IllegalArgumentException("Mã đơn thuốc không hợp lệ.");
        }
        Prescription prescription = prescriptionRepository.findByIdForUpdate(prescriptionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn thuốc ID: " + prescriptionId));

        if (prescription.getStatus() == PrescriptionStatus.CANCELLED) {
            throw new IllegalStateException("Đơn thuốc đã bị hủy, không thể thay đổi lựa chọn.");
        }

        StaffProfile staff = getValidStaffProfile(cashierUserId, prescription.getBranch().getBranchId());

        boolean anyBought = prescription.getItems() != null && prescription.getItems().stream()
                .anyMatch(it -> it.getBoughtQuantity() != null && it.getBoughtQuantity().compareTo(BigDecimal.ZERO) > 0);
        boolean allBought = prescription.getItems() != null && !prescription.getItems().isEmpty()
                && prescription.getItems().stream().allMatch(PrescriptionItem::isFullyBought);

        if ("EXTERNAL".equalsIgnoreCase(choice)) {
            if (allBought) {
                throw new IllegalStateException("Đơn thuốc này đã được mua đủ toàn bộ tại phòng khám, không thể chuyển sang tự mua ngoài.");
            }
            prescription.setStatus(PrescriptionStatus.EXTERNAL_PURCHASE);
            prescription.setPurchaseChoice(PrescriptionPurchaseChoice.EXTERNAL);
            String defaultNote = anyBought
                    ? "Đã mua một phần tại phòng khám. Số lượng còn lại bệnh nhân tự mua ngoài."
                    : "Bệnh nhân tự mua ngoài tại nhà thuốc tư nhân";
            prescription.setChoiceNote(note != null && !note.trim().isEmpty() ? note.trim() : defaultNote);
        } else {
            // Mua tại phòng khám
            prescription.setPurchaseChoice(PrescriptionPurchaseChoice.CLINIC);
            if (allBought) {
                prescription.setStatus(PrescriptionStatus.CLINIC_PURCHASE_COMPLETED);
            } else if (anyBought) {
                prescription.setStatus(PrescriptionStatus.CLINIC_PURCHASE_PARTIAL);
            } else {
                prescription.setStatus(PrescriptionStatus.PENDING);
            }
            prescription.setChoiceNote(note);
        }
        prescription.setHandledBy(staff);
        prescription.setHandledAt(LocalDateTime.now());
        prescriptionRepository.save(prescription);
    }

    @Override
    @Transactional(readOnly = true)
    public List<com.cvdcms.DTO.PrescriptionResponseDTO> getHandedOverPrescriptions(Long branchId, Long visitId, Long profileId) {
        List<Prescription> list;
        if (visitId != null) {
            list = prescriptionRepository.findByVisit_VisitIdOrderByCreatedAtDesc(visitId);
        } else if (profileId != null) {
            list = prescriptionRepository.findByPatientProfile_ProfileIdOrderByCreatedAtDesc(profileId);
        } else {
            list = prescriptionRepository.findByBranchOrderByCreatedAtDesc(branchId);
        }

        return list.stream()
                .filter(p -> p.getStatus() != PrescriptionStatus.CANCELLED)
                .filter(p -> {
                    Visit v = p.getVisit();
                    if (v == null) return true;
                    // Bác sĩ đã xác nhận bàn giao (WAITING_PAYMENT hoặc COMPLETED)
                    return v.getStatus() != VisitStatus.IN_EXAM
                            && v.getStatus() != VisitStatus.WAITING
                            && v.getStatus() != VisitStatus.WAITING_PARACLINICAL
                            && v.getStatus() != VisitStatus.WAITING_RESULT
                            && v.getStatus() != VisitStatus.IN_TREATMENT
                            && v.getStatus() != VisitStatus.CANCELLED;
                }).map(p -> {
            PatientProfile patient = p.getPatientProfile();
            StaffProfile doctor = p.getDoctor();
            Branch branch = p.getBranch();
            Visit visit = p.getVisit();

            List<com.cvdcms.DTO.PrescriptionItemResponseDTO> items = new ArrayList<>();
            BigDecimal totalAmount = BigDecimal.ZERO;
            if (p.getItems() != null) {
                for (PrescriptionItem it : p.getItems()) {
                    BigDecimal uPrice = (it.getMedication() != null && it.getMedication().getSalePrice() != null && it.getMedication().getSalePrice().compareTo(BigDecimal.ZERO) > 0)
                            ? it.getMedication().getSalePrice()
                            : (it.getUnitPrice() != null && it.getUnitPrice().compareTo(BigDecimal.ZERO) > 0 ? it.getUnitPrice() : BigDecimal.ZERO);
                    BigDecimal qty = it.getQuantity() != null ? it.getQuantity() : BigDecimal.ONE;
                    BigDecimal fPrice = uPrice.multiply(qty);
                    totalAmount = totalAmount.add(fPrice);

                    BigDecimal bought = it.getBoughtQuantity() != null ? it.getBoughtQuantity() : BigDecimal.ZERO;
                    BigDecimal rem = it.getRemainingQuantity();

                    items.add(com.cvdcms.DTO.PrescriptionItemResponseDTO.builder()
                            .itemId(it.getItemId())
                            .medicationId(it.getMedication() != null ? it.getMedication().getMedicationId() : null)
                            .drugName(it.getDrugName())
                            .dosage(it.getDosage())
                            .durationDays(it.getDurationDays())
                            .quantity(it.getQuantity())
                            .boughtQuantity(bought)
                            .remainingQuantity(rem)
                            .unit(it.getUnit())
                            .usageInstruction(it.getUsageInstruction())
                            .unitPrice(uPrice)
                            .finalPrice(fPrice)
                            .build());
                }
            }

            String statusName = p.getStatus() != null ? p.getStatus().name() : "PENDING";
            String statusDisplay = switch (statusName) {
                case "EXTERNAL_PURCHASE" -> "Tự mua ngoài";
                case "CLINIC_PURCHASE_PARTIAL" -> "Đã mua 1 phần";
                case "CLINIC_PURCHASE_COMPLETED" -> "Đã mua đủ tại PK";
                case "CANCELLED" -> "Đã hủy";
                default -> "Chờ tiếp nhận";
            };

            return com.cvdcms.DTO.PrescriptionResponseDTO.builder()
                    .prescriptionId(p.getPrescriptionId())
                    .prescriptionCode(p.getPrescriptionCode())
                    .visitId(visit != null ? visit.getVisitId() : null)
                    .visitCode(visit != null ? visit.getVisitCode() : null)
                    .patientId(patient != null ? patient.getProfileId() : null)
                    .patientCode(patient != null ? patient.getPatientCode() : null)
                    .patientName(patient != null ? patient.getFullName() : null)
                    .gender(patient != null ? patient.getGender() : null)
                    .phoneNumber(patient != null ? patient.getPhoneNumber() : null)
                    .doctorId(doctor != null ? doctor.getStaffId() : null)
                    .doctorName(doctor != null ? doctor.getFullName() : null)
                    .doctorTitle(doctor != null ? doctor.getPositionTitle() : "Bác sĩ điều trị")
                    .branchName(branch != null ? branch.getBranchName() : null)
                    .diagnosisText(p.getDiagnosisText())
                    .adviceNotes(p.getAdviceNotes())
                    .createdAt(p.getCreatedAt() != null ? p.getCreatedAt().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) : null)
                    .totalAmount(totalAmount)
                    .status(statusName)
                    .statusDisplay(statusDisplay)
                    .purchaseChoice(p.getPurchaseChoice() != null ? p.getPurchaseChoice().name() : null)
                    .choiceNote(p.getChoiceNote())
                    .handledByName(p.getHandledBy() != null ? p.getHandledBy().getFullName() : null)
                    .handledAt(p.getHandledAt() != null ? p.getHandledAt().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) : null)
                    .items(items)
                    .build();
        }).toList();
    }

    private BigDecimal getEstimatedPriceForDrug(String drugName) {
        if (drugName == null || drugName.trim().isEmpty()) {
            return BigDecimal.valueOf(5000);
        }
        String lower = drugName.toLowerCase();
        if (lower.contains("augmentin") || lower.contains("amoxicillin clavulanate")) {
            return BigDecimal.valueOf(22000);
        } else if (lower.contains("kin") || lower.contains("súc miệng") || lower.contains("suc mieng")) {
            return BigDecimal.valueOf(130000);
        } else if (lower.contains("ibuprofen")) {
            return BigDecimal.valueOf(3500);
        } else if (lower.contains("alphachymotrypsin") || lower.contains("chymo")) {
            return BigDecimal.valueOf(3500);
        } else if (lower.contains("paracetamol") || lower.contains("panadol") || lower.contains("efferalgan")) {
            return BigDecimal.valueOf(2500);
        } else if (lower.contains("rodogyl") || lower.contains("spiramycin")) {
            return BigDecimal.valueOf(7000);
        } else if (lower.contains("amoxicillin")) {
            return BigDecimal.valueOf(3000);
        } else if (lower.contains("cefuroxim") || lower.contains("zinnat")) {
            return BigDecimal.valueOf(18000);
        } else if (lower.contains("prednisolon") || lower.contains("medrol")) {
            return BigDecimal.valueOf(4000);
        } else if (lower.contains("gaviscon")) {
            return BigDecimal.valueOf(12000);
        }
        return BigDecimal.valueOf(5000);
    }
}