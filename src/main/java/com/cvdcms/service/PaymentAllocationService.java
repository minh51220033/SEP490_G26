package com.cvdcms.service;

import com.cvdcms.DTO.PaymentAllocationDTO;
import com.cvdcms.DTO.PaymentAllocationPreviewDTO;
import com.cvdcms.entity.Invoice;
import com.cvdcms.entity.Payment;
import com.cvdcms.entity.PaymentAllocation;

import java.math.BigDecimal;
import java.util.List;

public interface PaymentAllocationService {

    /**
     * Khởi tạo thứ tự phân bổ (lineOrder), phân bổ giảm giá chung (discountAmount),
     * tính thành tiền phải trả (payableAmount) và công nợ ban đầu cho từng dòng hóa đơn khi phát hành.
     */
    void initializeItemizedDebtOnInvoiceCreation(Invoice invoice);

    /**
     * Xem trước số tiền sẽ được phân bổ vào từng dòng hóa đơn trước khi thu ngân bấm xác nhận thu tiền.
     * Backend tính toán độc lập theo quy tắc FIFO (dịch vụ trước, thuốc sau, phụ thu sau cùng).
     */
    PaymentAllocationPreviewDTO previewPaymentAllocation(Long invoiceId, BigDecimal paymentAmount);

    /**
     * Thực hiện phân bổ số tiền thanh toán vào từng dòng chi tiết hóa đơn trong một transaction an toàn.
     * Cập nhật paidAmount, remainingDebt, paymentStatus của từng dòng và lưu các bản ghi PaymentAllocation.
     */
    List<PaymentAllocation> allocatePayment(Payment payment, BigDecimal amount);

    /**
     * Lấy danh sách phân bổ chi tiết của một phiếu thu (Payment).
     */
    List<PaymentAllocationDTO> getAllocationsByPaymentId(Long paymentId);

    /**
     * Lấy toàn bộ lịch sử phân bổ của một hóa đơn.
     */
    List<PaymentAllocationDTO> getAllocationsByInvoiceId(Long invoiceId);

    /**
     * Kiểm tra xem hóa đơn đã có dữ liệu phân bổ chi tiết hay là hóa đơn cũ chưa phân bổ.
     */
    boolean hasAllocations(Long invoiceId);
}

