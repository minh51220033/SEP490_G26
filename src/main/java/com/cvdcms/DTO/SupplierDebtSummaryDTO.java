package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * DTO tổng hợp công nợ động của một Nhà cung cấp tại một chi nhánh cụ thể.
 *
 * <p><b>Công thức tính công nợ động:</b> {@code remainingDebt = max(0, totalImportAmount - totalPaidAmount)},
 * trong đó {@code totalImportAmount} là tổng giá trị các phiếu nhập kho ({@code StockReceipt}) và
 * {@code totalPaidAmount} là tổng tiền đã thanh toán ({@code SupplierPayment}) của NCC tại chi nhánh đó.</p>
 *
 * <p><b>Được trả về bởi:</b> {@link com.cvdcms.service.SupplierPaymentService#getDebtSummaries},
 * {@link com.cvdcms.service.SupplierPaymentService#getDebtSummariesForFinance},
 * {@link com.cvdcms.service.SupplierPaymentService#getSupplierDebtDetailForFinance};
 * dùng cho {@code GET /api/inventory/supplier-payments/debts} ({@code suppliers.js}) và các trang
 * Tài chính ({@code /supplier-payments}, {@code /supplier-payments/{supplierId}}).</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SupplierDebtSummaryDTO {
    /** ID nhà cung cấp. */
    private Long supplierId;
    /** Mã nhà cung cấp. */
    private String supplierCode;
    /** Tên nhà cung cấp. */
    private String supplierName;
    /** Mã số thuế nhà cung cấp. */
    private String taxCode;
    /** Số điện thoại nhà cung cấp. */
    private String phoneNumber;
    /** Trạng thái hoạt động ({@code true}: đang hoạt động, {@code false}: ngừng hoạt động). */
    private Boolean isActive;
    /** Tổng số phiếu nhập kho của nhà cung cấp tại chi nhánh đang xét. */
    private int receiptCount;
    /** Tổng giá trị hàng đã nhập từ nhà cung cấp tại chi nhánh (VNĐ). */
    private BigDecimal totalImportAmount;
    /** Tổng số tiền đã thanh toán cho nhà cung cấp tại chi nhánh (VNĐ). */
    private BigDecimal totalPaidAmount;
    /** Công nợ còn lại phải trả ({@code totalImportAmount - totalPaidAmount}, không âm, VNĐ). */
    private BigDecimal remainingDebt;
}
