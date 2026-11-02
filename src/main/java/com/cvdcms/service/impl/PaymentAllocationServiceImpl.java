package com.cvdcms.service.impl;

import com.cvdcms.DTO.PaymentAllocationDTO;
import com.cvdcms.DTO.PaymentAllocationPreviewDTO;
import com.cvdcms.entity.Invoice;
import com.cvdcms.entity.InvoiceItem;
import com.cvdcms.entity.Payment;
import com.cvdcms.entity.PaymentAllocation;
import com.cvdcms.entity.enums.InvoiceItemPaymentStatus;
import com.cvdcms.entity.enums.InvoiceItemType;
import com.cvdcms.entity.enums.PaymentMethod;
import com.cvdcms.repository.InvoiceItemRepository;
import com.cvdcms.repository.InvoiceRepository;
import com.cvdcms.repository.PaymentAllocationRepository;
import com.cvdcms.service.PaymentAllocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PaymentAllocationServiceImpl implements PaymentAllocationService {

    private final PaymentAllocationRepository paymentAllocationRepository;
    private final InvoiceItemRepository invoiceItemRepository;
    private final InvoiceRepository invoiceRepository;

    @Override
    public void initializeItemizedDebtOnInvoiceCreation(Invoice invoice) {
        if (invoice == null || invoice.getItems() == null || invoice.getItems().isEmpty()) {
            return;
        }

        List<InvoiceItem> items = invoice.getItems();
        List<InvoiceItem> services = items.stream()
                .filter(it -> it.getItemType() == null || it.getItemType() == InvoiceItemType.SERVICE)
                .toList();
        List<InvoiceItem> medicines = items.stream()
                .filter(it -> it.getItemType() != null && it.getItemType() == InvoiceItemType.MEDICINE)
                .toList();

        int order = 1;

        // 1. Phân bổ giảm trừ chung (nếu có) theo tỷ lệ pro-rata cho nhóm dịch vụ
        BigDecimal totalDiscount = invoice.getTotalDiscount() != null ? invoice.getTotalDiscount() : BigDecimal.ZERO;
        BigDecimal totalService = services.stream()
                .map(it -> it.getFinalAmount() != null ? it.getFinalAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalDiscount.compareTo(totalService) > 0) {
            totalDiscount = totalService;
        }

        BigDecimal allocatedDiscountSum = BigDecimal.ZERO;

        for (int i = 0; i < services.size(); i++) {
            InvoiceItem item = services.get(i);
            item.setLineOrder(order++);
            BigDecimal lineAmount = item.getFinalAmount() != null ? item.getFinalAmount() : BigDecimal.ZERO;

            BigDecimal itemDiscount = BigDecimal.ZERO;
            if (totalService.compareTo(BigDecimal.ZERO) > 0 && totalDiscount.compareTo(BigDecimal.ZERO) > 0) {
                if (i == services.size() - 1) {
                    // Dòng dịch vụ cuối nhận phần chênh lệch làm tròn để khớp chính xác 100% totalDiscount
                    itemDiscount = totalDiscount.subtract(allocatedDiscountSum);
                } else {
                    itemDiscount = totalDiscount.multiply(lineAmount)
                            .divide(totalService, 2, RoundingMode.HALF_UP);
                    allocatedDiscountSum = allocatedDiscountSum.add(itemDiscount);
                }
            }

            if (itemDiscount.compareTo(lineAmount) > 0) {
                itemDiscount = lineAmount;
            }

            item.setDiscountAmount(itemDiscount);
            BigDecimal payable = lineAmount.subtract(itemDiscount);
            item.setPayableAmount(payable);
            item.setPaidAmount(BigDecimal.ZERO);
            item.setRemainingDebt(payable);
            item.setPaymentStatus(payable.compareTo(BigDecimal.ZERO) == 0 ? InvoiceItemPaymentStatus.PAID : InvoiceItemPaymentStatus.UNPAID);
        }

        // 2. Với thuốc: Không áp giảm giá dịch vụ, giữ nguyên thành tiền
        for (InvoiceItem med : medicines) {
            med.setLineOrder(order++);
            med.setDiscountAmount(BigDecimal.ZERO);
            BigDecimal payable = med.getFinalAmount() != null ? med.getFinalAmount() : BigDecimal.ZERO;
            med.setPayableAmount(payable);
            med.setPaidAmount(BigDecimal.ZERO);
            med.setRemainingDebt(payable);
            med.setPaymentStatus(payable.compareTo(BigDecimal.ZERO) == 0 ? InvoiceItemPaymentStatus.PAID : InvoiceItemPaymentStatus.UNPAID);
        }

        // 3. Với phụ thu
        BigDecimal surcharge = invoice.getSurchargeAmount() != null ? invoice.getSurchargeAmount() : BigDecimal.ZERO;
        invoice.setSurchargePaidAmount(BigDecimal.ZERO);
        invoice.setSurchargeRemainingDebt(surcharge);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentAllocationPreviewDTO previewPaymentAllocation(Long invoiceId, BigDecimal paymentAmount) {
        if (invoiceId == null) {
            throw new IllegalArgumentException("invoiceId không được để trống.");
        }
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn ID: " + invoiceId));

        BigDecimal payAmount = paymentAmount != null ? paymentAmount : BigDecimal.ZERO;
        if (payAmount.compareTo(BigDecimal.ZERO) < 0) {
            payAmount = BigDecimal.ZERO;
        }

        List<InvoiceItem> items = invoiceItemRepository.findByInvoice_InvoiceIdOrderByLineOrderAscItemIdAsc(invoiceId);
        List<PaymentAllocationPreviewDTO.ItemAllocationPreview> previewItems = new ArrayList<>();

        BigDecimal unallocated = payAmount;
        int orderCount = 1;

        for (InvoiceItem item : items) {
            BigDecimal payable = item.getPayableAmount() != null ? item.getPayableAmount() : (item.getFinalAmount() != null ? item.getFinalAmount() : BigDecimal.ZERO);
            BigDecimal prevPaid = item.getPaidAmount() != null ? item.getPaidAmount() : BigDecimal.ZERO;
            BigDecimal currentDebt = item.getRemainingDebt() != null ? item.getRemainingDebt() : payable.subtract(prevPaid).max(BigDecimal.ZERO);

            BigDecimal alloc = BigDecimal.ZERO;
            if (unallocated.compareTo(BigDecimal.ZERO) > 0 && currentDebt.compareTo(BigDecimal.ZERO) > 0) {
                alloc = unallocated.min(currentDebt);
                unallocated = unallocated.subtract(alloc);
            }

            BigDecimal remainingAfter = currentDebt.subtract(alloc);
            String statusAfter = remainingAfter.compareTo(BigDecimal.ZERO) == 0 ? "PAID" : ((prevPaid.add(alloc)).compareTo(BigDecimal.ZERO) > 0 ? "PARTIAL" : "UNPAID");

            previewItems.add(PaymentAllocationPreviewDTO.ItemAllocationPreview.builder()
                    .lineOrder(item.getLineOrder() != null ? item.getLineOrder() : orderCount++)
                    .itemId(item.getItemId())
                    .itemType(item.getItemType() != null ? item.getItemType().name() : "SERVICE")
                    .itemTitle(item.getItemName())
                    .originalAmount(item.getFinalAmount())
                    .discountAmount(item.getDiscountAmount() != null ? item.getDiscountAmount() : BigDecimal.ZERO)
                    .payableAmount(payable)
                    .previouslyPaidAmount(prevPaid)
                    .currentDebt(currentDebt)
                    .allocatedAmount(alloc)
                    .remainingDebtAfter(remainingAfter)
                    .statusAfter(statusAfter)
                    .build());
        }

        // Nếu hóa đơn có phụ thu
        if (invoice.getSurchargeAmount() != null && invoice.getSurchargeAmount().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal surchPayable = invoice.getSurchargeAmount();
            BigDecimal surchPrevPaid = invoice.getSurchargePaidAmount() != null ? invoice.getSurchargePaidAmount() : BigDecimal.ZERO;
            BigDecimal surchDebt = invoice.getSurchargeRemainingDebt() != null ? invoice.getSurchargeRemainingDebt() : surchPayable.subtract(surchPrevPaid).max(BigDecimal.ZERO);

            BigDecimal surchAlloc = BigDecimal.ZERO;
            if (unallocated.compareTo(BigDecimal.ZERO) > 0 && surchDebt.compareTo(BigDecimal.ZERO) > 0) {
                surchAlloc = unallocated.min(surchDebt);
                unallocated = unallocated.subtract(surchAlloc);
            }

            BigDecimal surchRemainingAfter = surchDebt.subtract(surchAlloc);
            String surchStatusAfter = surchRemainingAfter.compareTo(BigDecimal.ZERO) == 0 ? "PAID" : ((surchPrevPaid.add(surchAlloc)).compareTo(BigDecimal.ZERO) > 0 ? "PARTIAL" : "UNPAID");

            String desc = invoice.getSurchargeDescription() != null && !invoice.getSurchargeDescription().isBlank()
                    ? invoice.getSurchargeDescription() : "Phí phát sinh ngoài danh mục";

            previewItems.add(PaymentAllocationPreviewDTO.ItemAllocationPreview.builder()
                    .lineOrder(999)
                    .itemId(null)
                    .itemType("SURCHARGE")
                    .itemTitle("Phụ thu: " + desc)
                    .originalAmount(surchPayable)
                    .discountAmount(BigDecimal.ZERO)
                    .payableAmount(surchPayable)
                    .previouslyPaidAmount(surchPrevPaid)
                    .currentDebt(surchDebt)
                    .allocatedAmount(surchAlloc)
                    .remainingDebtAfter(surchRemainingAfter)
                    .statusAfter(surchStatusAfter)
                    .build());
        }

        BigDecimal currentTotalDebt = invoice.getRemainingDebt() != null ? invoice.getRemainingDebt() : BigDecimal.ZERO;
        BigDecimal debtAfter = currentTotalDebt.subtract(payAmount).max(BigDecimal.ZERO);

        return PaymentAllocationPreviewDTO.builder()
                .invoiceId(invoice.getInvoiceId())
                .invoiceCode(invoice.getInvoiceCode())
                .paymentAmount(payAmount)
                .invoiceTotalAmount(invoice.getTotalAmount())
                .invoicePaidAmount(invoice.getPaidAmount())
                .invoiceRemainingDebt(currentTotalDebt)
                .remainingDebtAfterPayment(debtAfter)
                .items(previewItems)
                .build();
    }

    @Override
    public List<PaymentAllocation> allocatePayment(Payment payment, BigDecimal amount) {
        if (payment == null || payment.getInvoice() == null) {
            throw new IllegalArgumentException("Khoản thanh toán phải liên kết với hóa đơn hợp lệ.");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Số tiền phân bổ phải lớn hơn 0.");
        }

        Invoice invoice = payment.getInvoice();
        Long invoiceId = invoice.getInvoiceId();
        List<InvoiceItem> items = invoiceItemRepository.findByInvoice_InvoiceIdOrderByLineOrderAscItemIdAsc(invoiceId);

        List<PaymentAllocation> allocations = new ArrayList<>();
        BigDecimal unallocated = amount;

        for (InvoiceItem item : items) {
            if (unallocated.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }

            BigDecimal currentDebt = item.getRemainingDebt();
            if (currentDebt == null) {
                BigDecimal payable = item.getPayableAmount() != null ? item.getPayableAmount() : item.getFinalAmount();
                BigDecimal paid = item.getPaidAmount() != null ? item.getPaidAmount() : BigDecimal.ZERO;
                currentDebt = payable.subtract(paid).max(BigDecimal.ZERO);
                item.setRemainingDebt(currentDebt);
            }

            if (currentDebt.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal alloc = unallocated.min(currentDebt);

                BigDecimal oldPaid = item.getPaidAmount() != null ? item.getPaidAmount() : BigDecimal.ZERO;
                BigDecimal newPaid = oldPaid.add(alloc);
                BigDecimal newDebt = currentDebt.subtract(alloc);

                item.setPaidAmount(newPaid);
                item.setRemainingDebt(newDebt);
                item.setPaymentStatus(newDebt.compareTo(BigDecimal.ZERO) == 0 ? InvoiceItemPaymentStatus.PAID : InvoiceItemPaymentStatus.PARTIAL);
                invoiceItemRepository.save(item);

                PaymentAllocation pa = new PaymentAllocation();
                pa.setPayment(payment);
                pa.setInvoiceItem(item);
                pa.setInvoice(invoice);
                pa.setAllocatedAmount(alloc);
                pa.setItemType(item.getItemType() != null ? item.getItemType().name() : "SERVICE");
                pa.setItemTitle(item.getItemName());
                pa.setLineOrder(item.getLineOrder() != null ? item.getLineOrder() : 1);
                pa.setNotes("Phân bổ từ phiếu thu " + payment.getPaymentCode());
                paymentAllocationRepository.save(pa);
                allocations.add(pa);

                unallocated = unallocated.subtract(alloc);
            }
        }

        // Nếu còn tiền và hóa đơn có phụ thu
        if (unallocated.compareTo(BigDecimal.ZERO) > 0 && invoice.getSurchargeAmount() != null && invoice.getSurchargeAmount().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal surchDebt = invoice.getSurchargeRemainingDebt();
            if (surchDebt == null) {
                BigDecimal paid = invoice.getSurchargePaidAmount() != null ? invoice.getSurchargePaidAmount() : BigDecimal.ZERO;
                surchDebt = invoice.getSurchargeAmount().subtract(paid).max(BigDecimal.ZERO);
            }

            if (surchDebt.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal alloc = unallocated.min(surchDebt);

                BigDecimal oldSurchPaid = invoice.getSurchargePaidAmount() != null ? invoice.getSurchargePaidAmount() : BigDecimal.ZERO;
                invoice.setSurchargePaidAmount(oldSurchPaid.add(alloc));
                invoice.setSurchargeRemainingDebt(surchDebt.subtract(alloc));
                invoiceRepository.save(invoice);

                PaymentAllocation pa = new PaymentAllocation();
                pa.setPayment(payment);
                pa.setInvoiceItem(null);
                pa.setInvoice(invoice);
                pa.setAllocatedAmount(alloc);
                pa.setItemType("SURCHARGE");
                String desc = invoice.getSurchargeDescription() != null && !invoice.getSurchargeDescription().isBlank()
                        ? invoice.getSurchargeDescription() : "Phí phát sinh";
                pa.setItemTitle("Phụ thu: " + desc);
                pa.setLineOrder(999);
                pa.setNotes("Phân bổ phụ thu từ " + payment.getPaymentCode());
                paymentAllocationRepository.save(pa);
                allocations.add(pa);

                unallocated = unallocated.subtract(alloc);
            }
        }

        BigDecimal totalAllocated = allocations.stream()
                .map(PaymentAllocation::getAllocatedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalAllocated.compareTo(amount) != 0) {
            log.warn("Tổng tiền phân bổ ({} đ) không khớp với số tiền thanh toán ({} đ).", totalAllocated, amount);
        }

        return allocations;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentAllocationDTO> getAllocationsByPaymentId(Long paymentId) {
        if (paymentId == null) {
            return List.of();
        }
        List<PaymentAllocation> list = paymentAllocationRepository.findByPayment_PaymentIdOrderByLineOrderAsc(paymentId);
        return list.stream().map(this::toDTO).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentAllocationDTO> getAllocationsByInvoiceId(Long invoiceId) {
        if (invoiceId == null) {
            return List.of();
        }
        List<PaymentAllocation> list = paymentAllocationRepository.findByInvoice_InvoiceIdOrderByLineOrderAscCreatedAtAsc(invoiceId);
        return list.stream().map(this::toDTO).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasAllocations(Long invoiceId) {
        if (invoiceId == null) {
            return false;
        }
        return paymentAllocationRepository.existsByInvoice_InvoiceId(invoiceId);
    }

    private PaymentAllocationDTO toDTO(PaymentAllocation entity) {
        Payment p = entity.getPayment();
        String methodName = "Tiền mặt";
        String methodCode = "CASH";
        if (p != null && p.getPaymentMethod() != null) {
            methodCode = p.getPaymentMethod().name();
            methodName = switch (p.getPaymentMethod()) {
                case CASH -> "Tiền mặt";
                case BANK_TRANSFER -> "Chuyển khoản";
                case POS_CARD -> "Thẻ POS";
                case DEPOSIT_WALLET -> "Ví tạm ứng";
            };
        }

        String cashier = (p != null && p.getCashier() != null) ? p.getCashier().getFullName() : "Thu ngân";

        BigDecimal remainingDebt = null;
        String status = null;
        if (entity.getInvoiceItem() != null) {
            InvoiceItem it = entity.getInvoiceItem();
            remainingDebt = it.getRemainingDebt();
            status = it.getPaymentStatus() != null ? it.getPaymentStatus().name() : "PAID";
        } else if ("SURCHARGE".equalsIgnoreCase(entity.getItemType()) && entity.getInvoice() != null) {
            Invoice inv = entity.getInvoice();
            remainingDebt = inv.getSurchargeRemainingDebt();
            if (remainingDebt != null && remainingDebt.compareTo(BigDecimal.ZERO) == 0) {
                status = "PAID";
            } else if (remainingDebt != null && inv.getSurchargeAmount() != null && remainingDebt.compareTo(inv.getSurchargeAmount()) < 0) {
                status = "PARTIAL";
            } else {
                status = "UNPAID";
            }
        }

        return PaymentAllocationDTO.builder()
                .allocationId(entity.getAllocationId())
                .paymentId(p != null ? p.getPaymentId() : null)
                .paymentCode(p != null ? p.getPaymentCode() : "")
                .itemId(entity.getInvoiceItem() != null ? entity.getInvoiceItem().getItemId() : null)
                .itemTitle(entity.getItemTitle())
                .itemType(entity.getItemType())
                .lineOrder(entity.getLineOrder())
                .allocatedAmount(entity.getAllocatedAmount())
                .remainingDebtAfter(remainingDebt)
                .statusAfter(status)
                .paidAt(entity.getCreatedAt())
                .paymentMethod(methodCode)
                .paymentMethodName(methodName)
                .cashierName(cashier)
                .notes(entity.getNotes())
                .build();
    }
}

