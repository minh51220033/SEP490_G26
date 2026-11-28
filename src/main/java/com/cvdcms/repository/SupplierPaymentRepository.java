package com.cvdcms.repository;

import com.cvdcms.entity.SupplierPayment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Repository truy xuất dữ liệu thanh toán công nợ Nhà cung cấp ({@link SupplierPayment}).
 *
 * <p><b>Chức năng chính:</b> Tra cứu lịch sử thanh toán (có phân trang + {@code JOIN FETCH} tránh N+1),
 * tổng hợp tổng tiền đã trả theo chi nhánh/nhà cung cấp/phiếu nhập để tính công nợ động
 * ({@code totalImport - totalPaid}), và phát hiện giao dịch trùng lặp gần đây.</p>
 *
 * <p><b>Được gọi từ:</b> {@link com.cvdcms.service.impl.SupplierPaymentServiceImpl} và các service báo cáo tài chính.</p>
 */
public interface SupplierPaymentRepository extends JpaRepository<SupplierPayment, Long> {

    /**
     * Kiểm tra mã phiếu thanh toán ({@code paymentCode}) đã tồn tại trong CSDL hay chưa.
     * Dùng trong vòng lặp sinh mã duy nhất {@code PC<seq>}.
     *
     * @param paymentCode mã phiếu thanh toán
     * @return {@code true} nếu đã tồn tại
     */
    boolean existsByPaymentCode(String paymentCode);

    /**
     * Tìm kiếm và phân trang lịch sử thanh toán nhà cung cấp theo chi nhánh, nhà cung cấp, từ khóa và khoảng ngày.
     *
     * <p><b>Xử lý truy vấn:</b> Sử dụng {@code JOIN FETCH} cho {@code branch}, {@code supplier}, {@code paidBy}
     * và {@code LEFT JOIN FETCH} cho {@code receipt} (do phiếu nhập đối ứng có thể {@code null} khi trả công nợ chung)
     * kèm {@code countQuery} riêng để phân trang chính xác và tránh lỗi N+1 query.</p>
     *
     * @param branchId   ID chi nhánh (bắt buộc)
     * @param supplierId ID nhà cung cấp (tùy chọn)
     * @param keyword    từ khóa tìm theo mã phiếu chi, tên/mã NCC, số chứng từ, mã phiếu nhập (tùy chọn)
     * @param fromDate   từ ngày thanh toán (tùy chọn)
     * @param toDate     đến ngày thanh toán (tùy chọn)
     * @param pageable   thông tin phân trang và sắp xếp
     * @return trang kết quả {@link Page}&lt;{@link SupplierPayment}&gt;
     */
    @Query(
            value = """
                SELECT sp
                FROM SupplierPayment sp
                JOIN FETCH sp.branch b
                JOIN FETCH sp.supplier s
                LEFT JOIN FETCH sp.receipt r
                JOIN FETCH sp.paidBy pb
                WHERE b.branchId = :branchId
                  AND (:supplierId IS NULL OR s.supplierId = :supplierId)
                  AND (:keyword IS NULL
                       OR LOWER(sp.paymentCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(s.supplierName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(s.supplierCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(COALESCE(sp.referenceNo, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(COALESCE(r.receiptCode, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
                  AND (:fromDate IS NULL OR sp.paymentDate >= :fromDate)
                  AND (:toDate IS NULL OR sp.paymentDate <= :toDate)
                """,
            countQuery = """
                SELECT COUNT(sp)
                FROM SupplierPayment sp
                JOIN sp.branch b
                JOIN sp.supplier s
                LEFT JOIN sp.receipt r
                WHERE b.branchId = :branchId
                  AND (:supplierId IS NULL OR s.supplierId = :supplierId)
                  AND (:keyword IS NULL
                       OR LOWER(sp.paymentCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(s.supplierName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(s.supplierCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(COALESCE(sp.referenceNo, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(COALESCE(r.receiptCode, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
                  AND (:fromDate IS NULL OR sp.paymentDate >= :fromDate)
                  AND (:toDate IS NULL OR sp.paymentDate <= :toDate)
                """
    )
    Page<SupplierPayment> searchPayments(
            @Param("branchId") Long branchId,
            @Param("supplierId") Long supplierId,
            @Param("keyword") String keyword,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            Pageable pageable
    );

    /**
     * Tính tổng số tiền đã chi trả nhà cung cấp theo danh sách chi nhánh và khoảng thời gian.
     * Thường dùng cho thống kê/báo cáo dòng tiền chi của phân hệ Tài chính.
     *
     * @param branchIds danh sách ID chi nhánh ({@code null} để tính tất cả chi nhánh)
     * @param startDate từ ngày thanh toán (có thể {@code null})
     * @param endDate   đến ngày thanh toán (có thể {@code null})
     * @return tổng tiền đã thanh toán (trả về {@code 0} nếu không có bản ghi)
     */
    @Query("""
        SELECT COALESCE(SUM(sp.amount), 0)
        FROM SupplierPayment sp
        WHERE (:branchIds IS NULL OR sp.branch.branchId IN :branchIds)
          AND (:startDate IS NULL OR sp.paymentDate >= :startDate)
          AND (:endDate IS NULL OR sp.paymentDate <= :endDate)
        """)
    BigDecimal sumPaidAmountByBranchesAndDates(
            @Param("branchIds") Collection<Long> branchIds,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /**
     * Tính tổng số tiền đã thanh toán cho một nhà cung cấp cụ thể tại một chi nhánh.
     * Dùng để tính công nợ động {@code totalImport - totalPaid} khi xem chi tiết hoặc kiểm tra trước lúc lưu thanh toán.
     *
     * @param branchId   ID chi nhánh
     * @param supplierId ID nhà cung cấp
     * @return tổng số tiền đã thanh toán (mặc định {@code 0})
     */
    @Query("""
        SELECT COALESCE(SUM(sp.amount), 0)
        FROM SupplierPayment sp
        WHERE sp.branch.branchId = :branchId
          AND sp.supplier.supplierId = :supplierId
        """)
    BigDecimal sumPaidAmountByBranchAndSupplier(
            @Param("branchId") Long branchId,
            @Param("supplierId") Long supplierId
    );

    /**
     * Tổng hợp theo lô (batch {@code GROUP BY}) tổng số tiền đã thanh toán cho nhiều nhà cung cấp tại một chi nhánh.
     *
     * <p><b>Mục đích:</b> Tối ưu hiệu năng chống N+1 query trong {@code getDebtSummariesInternal}.</p>
     *
     * @param branchId    ID chi nhánh
     * @param supplierIds danh sách ID nhà cung cấp cần tổng hợp
     * @return danh sách mảng {@code [supplierId, totalPaidAmount]}
     */
    @Query("""
        SELECT sp.supplier.supplierId, COALESCE(SUM(sp.amount), 0)
        FROM SupplierPayment sp
        WHERE sp.branch.branchId = :branchId
          AND sp.supplier.supplierId IN :supplierIds
        GROUP BY sp.supplier.supplierId
        """)
    List<Object[]> aggregatePaidByBranchAndSuppliers(
            @Param("branchId") Long branchId,
            @Param("supplierIds") Collection<Long> supplierIds
    );

    /**
     * Tính tổng số tiền đã thanh toán gắn trực tiếp với một phiếu nhập kho ({@code StockReceipt}).
     * Dùng để kiểm tra số tiền thanh toán không vượt quá công nợ còn lại của phiếu nhập đó.
     *
     * @param receiptId ID phiếu nhập kho
     * @return tổng số tiền đã trả cho phiếu nhập (mặc định {@code 0})
     */
    @Query("""
        SELECT COALESCE(SUM(sp.amount), 0)
        FROM SupplierPayment sp
        WHERE sp.receipt.receiptId = :receiptId
        """)
    BigDecimal sumPaidAmountByReceipt(@Param("receiptId") Long receiptId);

    /**
     * Tổng hợp theo lô (batch {@code GROUP BY}) tổng số tiền đã thanh toán cho danh sách phiếu nhập kho.
     * Dùng để tính {@code paidAmount} và {@code remainingDebt} cho từng phiếu nhập mà không gây N+1 query.
     *
     * @param receiptIds danh sách ID phiếu nhập kho
     * @return danh sách mảng {@code [receiptId, totalPaidAmount]}
     */
    @Query("""
        SELECT sp.receipt.receiptId, COALESCE(SUM(sp.amount), 0)
        FROM SupplierPayment sp
        WHERE sp.receipt.receiptId IN :receiptIds
        GROUP BY sp.receipt.receiptId
        """)
    List<Object[]> sumPaidAmountByReceipts(@Param("receiptIds") Collection<Long> receiptIds);

    /**
     * Kiểm tra có tồn tại giao dịch thanh toán giống hệt vừa được tạo gần đây hay không
     * (cùng chi nhánh, nhà cung cấp, phiếu nhập đối ứng, số tiền và thời điểm {@code createdAt >= since}).
     *
     * <p><b>Mục đích:</b> Lớp bảo vệ chống double-submit ở tầng CSDL (cửa sổ 5 giây), bổ sung cho
     * cơ chế {@code clientRequestId} trên bộ nhớ.</p>
     *
     * @param branchId   ID chi nhánh
     * @param supplierId ID nhà cung cấp
     * @param receiptId  ID phiếu nhập kho (có thể {@code null} nếu thanh toán công nợ chung)
     * @param amount     số tiền thanh toán
     * @param since      mốc thời gian bắt đầu cửa sổ kiểm tra
     * @return {@code true} nếu đã có giao dịch trùng khớp gần đây
     */
    @Query("""
        SELECT COUNT(sp) > 0
        FROM SupplierPayment sp
        WHERE sp.branch.branchId = :branchId
          AND sp.supplier.supplierId = :supplierId
          AND ((:receiptId IS NULL AND sp.receipt IS NULL) OR sp.receipt.receiptId = :receiptId)
          AND sp.amount = :amount
          AND sp.createdAt >= :since
        """)
    boolean existsRecentDuplicatePayment(
            @Param("branchId") Long branchId,
            @Param("supplierId") Long supplierId,
            @Param("receiptId") Long receiptId,
            @Param("amount") BigDecimal amount,
            @Param("since") LocalDateTime since
    );
}
