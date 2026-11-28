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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [Phân hệ]: Phân bổ Thanh toán Chi tiết (FIFO từng dòng hóa đơn)
 * [Vai trò]: Service khởi tạo công nợ từng dòng khi lập hóa đơn, xem trước (preview) và thực thi phân bổ FIFO (Dịch vụ -> Thuốc -> Phụ thu) cho mỗi đợt thu một phần hoặc thu đủ, đồng thời tái dựng lịch sử số dư nợ sau từng lần thanh toán.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PaymentAllocationServiceImpl implements PaymentAllocationService {

    private final PaymentAllocationRepository paymentAllocationRepository;
    private final InvoiceItemRepository invoiceItemRepository;
    private final InvoiceRepository invoiceRepository;

    /**
     * [Chức năng]: Khởi tạo thứ tự ưu tiên (`lineOrder`), phân bổ giảm giá và công nợ ban đầu cho từng dòng dịch vụ, thuốc và phụ thu khi tạo hóa đơn mới.
     * [Mục đích]: Chuẩn hóa dữ liệu đầu vào cho thuật toán phân bổ FIFO: xếp nhóm `SERVICE` trước rồi đến `MEDICINE`, chia đều `totalDiscount` theo tỷ lệ pro-rata cho các dòng dịch vụ (dòng dịch vụ cuối gánh phần chênh lệch làm tròn để tổng khớp 100%), giữ nguyên thành tiền của thuốc, đặt `paidAmount = 0`, `remainingDebt = payableAmount` và khởi tạo công nợ phụ thu trên `Invoice`.
     */
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

    /**
     * [Chức năng]: Tính toán xem trước (không ghi DB) cách phân bổ một số tiền dự kiến thanh toán vào từng dòng hạng mục của hóa đơn theo thứ tự FIFO.
     * [Mục đích]: Phục vụ API `/api/payments/allocation-preview` giúp thu ngân và bệnh nhân nhìn rõ khi thu một phần hoặc thu đủ thì số tiền sẽ thanh toán cho dòng Dịch vụ -> Thuốc -> Phụ thu (`lineOrder = 999`) bao nhiêu và trạng thái/nợ còn lại sau đợt thu là gì.
     */
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

    /**
     * [Chức năng]: Thực thi phân bổ thật số tiền của một phiếu thu (`Payment`) vào từng dòng hóa đơn và phụ thu theo thứ tự FIFO (`lineOrder ASC, itemId ASC`).
     * [Mục đích]: Được gọi trong transaction của `PaymentServiceImpl.processPayment`; trừ dần công nợ từng dòng dịch vụ -> thuốc -> phụ thu, cập nhật `paidAmount`, `remainingDebt`, `paymentStatus` (`PARTIAL`/`PAID`) trên `InvoiceItem` và `Invoice`, ghi bản ghi `PaymentAllocation` và đối chiếu nghiêm ngặt `totalAllocated == amount` (ném `IllegalStateException` để rollback nếu lệch tiền).
     */
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
            throw new IllegalStateException("Tổng tiền phân bổ chi tiết (" + totalAllocated + " đ) không khớp với số tiền thanh toán (" + amount + " đ). Không thể ghi nhận thanh toán thành công.");
        }

        return allocations;
    }

    /**
     * [Chức năng]: Lấy danh sách chi tiết phân bổ (`PaymentAllocationDTO`) của một phiếu thu kèm số nợ và trạng thái từng dòng ngay sau thời điểm phiếu thu đó được lập.
     * [Mục đích]: Phục vụ màn hình in biên lai và API `/api/payments/{paymentId}/allocations`; tải toàn bộ lịch sử phân bổ của hóa đơn để tính `SnapshotAfter` theo dòng thời gian, tránh hiển thị nhầm số nợ hiện tại thay vì số nợ tại thời điểm thu.
     */
    @Override
    @Transactional(readOnly = true)
    public List<PaymentAllocationDTO> getAllocationsByPaymentId(Long paymentId) {
        if (paymentId == null) {
            return List.of();
        }
        List<PaymentAllocation> list = paymentAllocationRepository.findByPayment_PaymentIdOrderByLineOrderAsc(paymentId);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        Long invoiceId = list.stream()
                .filter(a -> a.getInvoice() != null)
                .map(a -> a.getInvoice().getInvoiceId())
                .findFirst().orElse(null);
        List<PaymentAllocation> chronology = invoiceId != null
                ? paymentAllocationRepository.findByInvoice_InvoiceIdOrderByLineOrderAscCreatedAtAsc(invoiceId)
                : List.of();
        if (chronology == null || chronology.isEmpty()) {
            chronology = list;
        }
        Map<Long, SnapshotAfter> snapshots = computeSnapshots(chronology);
        return list.stream().map(a -> toDTO(a, snapshots.get(a.getAllocationId()))).toList();
    }

    /**
     * [Chức năng]: Lấy toàn bộ lịch sử phân bổ của tất cả các đợt thanh toán thuộc một hóa đơn theo thứ tự dòng và thời gian tăng dần.
     * [Mục đích]: Phục vụ tra cứu lịch sử trả góp/thu nhiều đợt trên trang chi tiết hóa đơn và API `/api/payments/invoice/{invoiceId}/allocations`, gắn kèm snapshot nợ còn lại sau từng bản ghi phân bổ.
     */
    @Override
    @Transactional(readOnly = true)
    public List<PaymentAllocationDTO> getAllocationsByInvoiceId(Long invoiceId) {
        if (invoiceId == null) {
            return List.of();
        }
        List<PaymentAllocation> list = paymentAllocationRepository.findByInvoice_InvoiceIdOrderByLineOrderAscCreatedAtAsc(invoiceId);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        Map<Long, SnapshotAfter> snapshots = computeSnapshots(list);
        return list.stream().map(a -> toDTO(a, snapshots.get(a.getAllocationId()))).toList();
    }

    /**
     * [Hàm hỗ trợ]: Record nội bộ lưu nhanh số nợ còn lại và trạng thái (`UNPAID`/`PARTIAL`/`PAID`) của một dòng hạng mục ngay sau một bản ghi phân bổ cụ thể.
     */
    private record SnapshotAfter(BigDecimal remainingDebt, String status) {}

    /**
     * [Hàm hỗ trợ]: Sắp xếp toàn bộ bản ghi phân bổ theo trình tự thời gian (`createdAt`, `paymentId`, `allocationId`) và cộng dồn số tiền đã trả theo từng khóa dòng (`ITEM:<id>` hoặc `SURCHARGE:<invoiceId>`) để tái dựng snapshot nợ còn lại sau mỗi đợt thu.
     */
    private Map<Long, SnapshotAfter> computeSnapshots(List<PaymentAllocation> allocations) {
        List<PaymentAllocation> ordered = new ArrayList<>(allocations);
        ordered.sort(Comparator
                .comparing((PaymentAllocation a) -> a.getCreatedAt(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(a -> a.getPayment() != null && a.getPayment().getPaymentId() != null ? a.getPayment().getPaymentId() : 0L)
                .thenComparing(a -> a.getAllocationId() != null ? a.getAllocationId() : 0L));

        Map<String, BigDecimal> cumulativePaidByKey = new HashMap<>();
        Map<Long, SnapshotAfter> snapshots = new HashMap<>();

        for (PaymentAllocation a : ordered) {
            String key;
            BigDecimal targetTotal = null;
            if (a.getInvoiceItem() != null) {
                InvoiceItem it = a.getInvoiceItem();
                key = "ITEM:" + it.getItemId();
                targetTotal = it.getPayableAmount() != null ? it.getPayableAmount()
                        : (it.getFinalAmount() != null ? it.getFinalAmount() : BigDecimal.ZERO);
            } else if ("SURCHARGE".equalsIgnoreCase(a.getItemType()) && a.getInvoice() != null) {
                key = "SURCHARGE:" + a.getInvoice().getInvoiceId();
                targetTotal = a.getInvoice().getSurchargeAmount() != null ? a.getInvoice().getSurchargeAmount() : BigDecimal.ZERO;
            } else {
                continue;
            }

            BigDecimal allocAmt = a.getAllocatedAmount() != null ? a.getAllocatedAmount() : BigDecimal.ZERO;
            BigDecimal newCumPaid = cumulativePaidByKey.getOrDefault(key, BigDecimal.ZERO).add(allocAmt);
            cumulativePaidByKey.put(key, newCumPaid);

            BigDecimal rem = targetTotal.subtract(newCumPaid).max(BigDecimal.ZERO);
            String status = rem.compareTo(BigDecimal.ZERO) == 0 ? "PAID"
                    : (newCumPaid.compareTo(BigDecimal.ZERO) > 0 ? "PARTIAL" : "UNPAID");
            if (a.getAllocationId() != null) {
                snapshots.put(a.getAllocationId(), new SnapshotAfter(rem, status));
            }
        }
        return snapshots;
    }

    /**
     * [Chức năng]: Kiểm tra một hóa đơn đã phát sinh ít nhất một bản ghi phân bổ thanh toán hay chưa.
     * [Mục đích]: Hỗ trợ tầng giao diện/nghiệp vụ xác định hóa đơn có dữ liệu phân bổ chi tiết để hiển thị bảng phân bổ hay bảo vệ chỉnh sửa.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean hasAllocations(Long invoiceId) {
        if (invoiceId == null) {
            return false;
        }
        return paymentAllocationRepository.existsByInvoice_InvoiceId(invoiceId);
    }

    /**
     * [Hàm hỗ trợ]: Ánh xạ entity `PaymentAllocation` cùng `SnapshotAfter` sang `PaymentAllocationDTO` kèm tên phương thức thanh toán tiếng Việt và tên thu ngân.
     */
    private PaymentAllocationDTO toDTO(PaymentAllocation entity, SnapshotAfter snapshot) {
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

        BigDecimal remainingDebt = snapshot != null ? snapshot.remainingDebt() : null;
        String status = snapshot != null ? snapshot.status() : null;
        if (snapshot == null) {
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

