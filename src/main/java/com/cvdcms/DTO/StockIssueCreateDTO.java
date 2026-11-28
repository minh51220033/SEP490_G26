package com.cvdcms.DTO;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * DTO nhận dữ liệu đầu vào khi lập mới Phiếu xuất kho ({@code POST /api/inventory/issues})
 * hoặc Phiếu điều chuyển liên chi nhánh ({@code POST /api/inventory/transfers}).
 * <p>Được gửi từ các hàm {@code submitIssue()} và {@code submitTransfer()} trong {@code inventory.js}.</p>
 */
@Getter
@Setter
public class StockIssueCreateDTO {
    /** Mã chi nhánh nguồn thực hiện xuất kho. */
    private Long branchId;
    /** Loại phiếu xuất: {@code INTERNAL_USE}, {@code DISCARD_EXPIRED}, {@code TRANSFER} hoặc {@code OTHER}. */
    private String issueType; // INTERNAL_USE, DISCARD_EXPIRED, TRANSFER, OTHER
    /** Lý do xuất kho (bắt buộc nhập khi {@code issueType == "OTHER"}, tối đa 255 ký tự). */
    private String issueReason;
    /** Mã chi nhánh đích nhận hàng (bắt buộc và phải khác {@code branchId} khi {@code issueType == "TRANSFER"}). */
    private Long destinationBranchId;
    /** Ngày lập phiếu xuất/điều chuyển (mặc định ngày hiện tại nếu null, không ở tương lai). */
    private LocalDate issueDate;
    /** Ghi chú chung của phiếu (tối đa 255 ký tự). */
    private String notes;
    /** Danh sách các dòng vật tư cần xuất (bắt buộc ít nhất 1 dòng). */
    private List<Item> items;

    /**
     * Đại diện một dòng vật tư trong phiếu xuất kho / điều chuyển.
     */
    @Getter
    @Setter
    public static class Item {
        /** Mã định danh vật tư cần xuất. */
        private Long materialId;
        /** Mã lô chỉ định (tùy chọn: nếu {@code null} hệ thống sẽ tự động phân bổ theo FEFO hoặc lô hết hạn). */
        private Long batchId; // Optional: nếu null thì tự động phân bổ theo FEFO
        /** Số lượng xuất (&gt; 0, tối đa 3 chữ số thập phân). */
        private BigDecimal quantity;
        /** Ghi chú riêng cho dòng vật tư (tùy chọn, tối đa 255 ký tự). */
        private String notes;
    }
}
