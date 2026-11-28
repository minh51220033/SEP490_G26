package com.cvdcms.DTO;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * DTO đầu vào dùng để tạo mới phiếu thanh toán công nợ Nhà cung cấp ({@code SupplierPayment}).
 *
 * <p><b>Được gọi từ:</b>
 * <ul>
 *   <li>{@code POST /api/inventory/supplier-payments} ({@link com.cvdcms.controller.SupplierPaymentController#createPayment}) — từ trang Kho và trang {@code /suppliers} ({@code suppliers.js}).</li>
 *   <li>{@code POST /supplier-payments/pay} ({@link com.cvdcms.controller.SupplierPaymentPageController#createSupplierPayment}) — từ phân hệ Tài chính.</li>
 * </ul>
 * </p>
 *
 * <p><b>Lưu ý nghiệp vụ:</b> Hỗ trợ thanh toán gắn với một phiếu nhập cụ thể ({@code receiptId != null})
 * hoặc thanh toán công nợ chung ({@code receiptId == null}), kèm {@code clientRequestId} để chống double-submit.</p>
 */
@Getter
@Setter
public class SupplierPaymentCreateDTO {
    /** ID chi nhánh thực hiện thanh toán (bắt buộc). */
    private Long branchId;
    /** ID nhà cung cấp được thanh toán (bắt buộc). */
    private Long supplierId;
    /** ID phiếu nhập kho đối ứng ({@code StockReceipt}); để {@code null} nếu thanh toán công nợ chung. */
    private Long receiptId;
    /** Ngày thanh toán (không được ở tương lai; nếu {@code null} sẽ mặc định là ngày hiện tại). */
    private LocalDate paymentDate;
    /** Số tiền thanh toán (VNĐ, bắt buộc {@code > 0} và không vượt quá công nợ còn lại). */
    private BigDecimal amount;
    /** Hình thức thanh toán: {@code CASH} (Tiền mặt) hoặc {@code BANK_TRANSFER} (Chuyển khoản, mặc định). */
    private String paymentMethod; // CASH, BANK_TRANSFER
    /** Số chứng từ / mã giao dịch ngân hàng (tùy chọn, tối đa 100 ký tự, chỉ chứa chữ, số, {@code . _ / -}). */
    private String referenceNo;
    /** Ghi chú nội dung thanh toán (tùy chọn, tối đa 255 ký tự). */
    private String notes;
    /** Mã định danh duy nhất phía client (UUID) nhằm chống gửi trùng (idempotency trong cửa sổ 30 giây). */
    private String clientRequestId;
    /** Dữ liệu ảnh chữ ký điện tử dạng Data URL (tùy chọn, được lưu file và gán vào {@code signatureUrl}). */
    private String signatureData;
}
