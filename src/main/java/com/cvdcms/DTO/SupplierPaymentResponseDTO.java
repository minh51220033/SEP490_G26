package com.cvdcms.DTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * DTO đầu ra biểu diễn chi tiết một phiếu thanh toán công nợ Nhà cung cấp ({@code SupplierPayment}).
 *
 * <p><b>Được trả về bởi:</b> {@link com.cvdcms.service.SupplierPaymentService#searchPayments},
 * {@link com.cvdcms.service.SupplierPaymentService#createPayment} và các phương thức {@code *ForFinance};
 * dùng cho REST API {@code /api/inventory/supplier-payments} và các trang Tài chính ({@code /supplier-payments}).</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class SupplierPaymentResponseDTO {
    /** ID bản ghi thanh toán nhà cung cấp. */
    private Long supplierPaymentId;
    /** Mã phiếu chi / phiếu thanh toán duy nhất (định dạng {@code PC<timestamp/seq>}). */
    private String paymentCode;
    /** ID chi nhánh thực hiện thanh toán. */
    private Long branchId;
    /** Tên chi nhánh thực hiện thanh toán. */
    private String branchName;
    /** ID nhà cung cấp nhận thanh toán. */
    private Long supplierId;
    /** Mã nhà cung cấp. */
    private String supplierCode;
    /** Tên nhà cung cấp. */
    private String supplierName;
    /** ID phiếu nhập kho đối ứng ({@code null} nếu thanh toán công nợ chung). */
    private Long receiptId;
    /** Mã phiếu nhập kho đối ứng ({@code null} nếu thanh toán công nợ chung). */
    private String receiptCode;
    /** Ngày thực hiện thanh toán. */
    private LocalDate paymentDate;
    /** Số tiền đã thanh toán (VNĐ). */
    private BigDecimal amount;
    /** Hình thức thanh toán ({@code CASH} hoặc {@code BANK_TRANSFER}). */
    private String paymentMethod;
    /** Số chứng từ / mã giao dịch tham chiếu. */
    private String referenceNo;
    /** Họ tên nhân viên lập phiếu thanh toán. */
    private String paidByName;
    /** Ghi chú thanh toán. */
    private String notes;
    /** Đường dẫn file ảnh chữ ký xác nhận (nếu có). */
    private String signatureUrl;
    /** Thời điểm tạo bản ghi thanh toán trong hệ thống. */
    private LocalDateTime createdAt;
}
