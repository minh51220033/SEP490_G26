package com.cvdcms.DTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO trả về thông tin tóm tắt hoặc chi tiết đầy đủ của Phiếu xuất kho và Phiếu điều chuyển kho ({@code StockIssue}).
 * <p>Sử dụng cho API danh sách/chi tiết ({@code /api/inventory/issues}, {@code /api/inventory/transfers}),
 * xuất PDF ({@link com.cvdcms.util.InventoryPdfGenerator#generateIssuePdf}) và trang in {@code print-issue.html}/{@code print-transfer.html}.</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class StockIssueResponseDTO {
    /** Mã định danh phiếu xuất / điều chuyển. */
    private Long issueId;
    /** Mã phiếu duy nhất (tiền tố {@code PX} cho phiếu xuất, {@code DC} cho phiếu điều chuyển). */
    private String issueCode;
    /** Mã chi nhánh nguồn xuất kho. */
    private Long branchId;
    /** Tên chi nhánh nguồn xuất kho. */
    private String branchName;
    /** Loại phiếu xuất ({@code INTERNAL_USE}, {@code DISCARD_EXPIRED}, {@code TRANSFER}, {@code OTHER}, ...). */
    private String issueType;
    /** Lý do xuất kho (khi chọn loại {@code OTHER}). */
    private String issueReason;
    /** Ngày lập phiếu. */
    private LocalDate issueDate;
    /** Mã chi nhánh đích (chỉ có khi {@code issueType == "TRANSFER"}). */
    private Long destinationBranchId;
    /** Tên chi nhánh đích (chỉ có khi {@code issueType == "TRANSFER"}). */
    private String destinationBranchName;
    /** Họ tên nhân viên lập phiếu xuất. */
    private String issuerName;
    /** Ghi chú phiếu xuất / điều chuyển. */
    private String notes;
    /** Mã định danh đơn thuốc liên kết (nếu phiếu sinh ra từ luồng cấp phát thuốc). */
    private Long prescriptionId;
    /** Mã đơn thuốc liên kết. */
    private String prescriptionCode;
    /** Tổng số dòng vật tư/lô đã xuất. */
    private int itemCount;
    /** Tổng số lượng xuất cộng dồn tất cả các dòng. */
    private BigDecimal totalQuantity = BigDecimal.ZERO;
    /** Tổng giá trị xuất ước tính tính theo đơn giá nhập của từng lô (VNĐ). */
    private BigDecimal totalEstimatedValue = BigDecimal.ZERO;
    /** Danh sách chi tiết từng dòng vật tư và lô đã khấu trừ. */
    private List<ItemDetail> items = new ArrayList<>();

    /**
     * DTO chi tiết một dòng vật tư/lô trong phiếu xuất kho hoặc điều chuyển.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class ItemDetail {
        private Long issueItemId;
        private Long materialId;
        private String materialCode;
        private String materialName;
        private String unit;
        private Long batchId;
        private String batchNumber;
        private LocalDate expiryDate;
        private BigDecimal quantity;
        private BigDecimal importPrice;
        private BigDecimal lineValue;
        private String notes;
    }
}
