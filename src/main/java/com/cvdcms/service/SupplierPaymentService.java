package com.cvdcms.service;

import com.cvdcms.DTO.StockReceiptResponseDTO;
import com.cvdcms.DTO.SupplierDebtSummaryDTO;
import com.cvdcms.DTO.SupplierPaymentCreateDTO;
import com.cvdcms.DTO.SupplierPaymentResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * Interface định nghĩa nghiệp vụ Công nợ &amp; Thanh toán Nhà cung cấp cho cả phân hệ Kho và phân hệ Tài chính.
 *
 * <p><b>Phân biệt 2 nhóm phương thức:</b>
 * <ul>
 *   <li>Nhóm chuẩn ({@code getDebtSummaries}, {@code searchPayments}, {@code createPayment}): kiểm tra quyền chi nhánh
 *   qua {@code BranchAccessService}, phục vụ {@link com.cvdcms.controller.SupplierPaymentController} ({@code /api/inventory/supplier-payments}).</li>
 *   <li>Nhóm {@code *ForFinance}: mở rộng kiểm tra quyền cho nhân viên tài chính ({@code FINANCE_EXPENSES},
 *   {@code FINANCE_DASHBOARD}, {@code SUPPLIER_MANAGE}), phục vụ {@link com.cvdcms.controller.SupplierPaymentPageController} ({@code /supplier-payments}).</li>
 * </ul>
 * </p>
 *
 * <p><b>Triển khai bởi:</b> {@link com.cvdcms.service.impl.SupplierPaymentServiceImpl}.</p>
 */
public interface SupplierPaymentService {

    /**
     * Tổng hợp công nợ động ({@code totalImport - totalPaid}) của các nhà cung cấp tại một chi nhánh (phân hệ Kho).
     *
     * @param branchId ID chi nhánh
     * @param keyword  từ khóa tìm kiếm nhà cung cấp (tùy chọn)
     * @param userId   ID người dùng hiện tại để kiểm tra quyền truy cập chi nhánh
     * @return danh sách {@link SupplierDebtSummaryDTO}
     */
    List<SupplierDebtSummaryDTO> getDebtSummaries(
            Long branchId,
            String keyword,
            Long userId
    );

    /**
     * Tìm kiếm và phân trang lịch sử thanh toán nhà cung cấp (phân hệ Kho).
     *
     * @param branchId   ID chi nhánh
     * @param supplierId ID nhà cung cấp (tùy chọn)
     * @param keyword    từ khóa tìm kiếm (tùy chọn)
     * @param fromDate   từ ngày thanh toán (tùy chọn)
     * @param toDate     đến ngày thanh toán (tùy chọn)
     * @param pageable   thông tin phân trang và sắp xếp
     * @param userId     ID người dùng hiện tại
     * @return trang kết quả {@link Page}&lt;{@link SupplierPaymentResponseDTO}&gt;
     */
    Page<SupplierPaymentResponseDTO> searchPayments(
            Long branchId,
            Long supplierId,
            String keyword,
            LocalDate fromDate,
            LocalDate toDate,
            Pageable pageable,
            Long userId
    );

    /**
     * Tạo mới phiếu thanh toán công nợ nhà cung cấp (phân hệ Kho), có khóa bi quan và chống double-submit.
     *
     * @param dto    dữ liệu thanh toán
     * @param userId ID người dùng thực hiện thanh toán
     * @return {@link SupplierPaymentResponseDTO} vừa tạo
     */
    SupplierPaymentResponseDTO createPayment(
            SupplierPaymentCreateDTO dto,
            Long userId
    );

    /**
     * Tổng hợp công nợ động của các nhà cung cấp tại chi nhánh cho phân hệ Tài chính.
     *
     * @param branchId ID chi nhánh
     * @param keyword  từ khóa tìm kiếm nhà cung cấp (tùy chọn)
     * @param userId   ID người dùng tài chính hiện tại
     * @return danh sách {@link SupplierDebtSummaryDTO}
     */
    default List<SupplierDebtSummaryDTO> getDebtSummariesForFinance(
            Long branchId,
            String keyword,
            Long userId
    ) {
        return getDebtSummaries(branchId, keyword, userId);
    }

    /**
     * Tìm kiếm và phân trang lịch sử thanh toán nhà cung cấp cho phân hệ Tài chính.
     *
     * @param branchId   ID chi nhánh
     * @param supplierId ID nhà cung cấp (tùy chọn)
     * @param keyword    từ khóa tìm kiếm (tùy chọn)
     * @param fromDate   từ ngày thanh toán (tùy chọn)
     * @param toDate     đến ngày thanh toán (tùy chọn)
     * @param pageable   thông tin phân trang và sắp xếp
     * @param userId     ID người dùng tài chính hiện tại
     * @return trang kết quả {@link Page}&lt;{@link SupplierPaymentResponseDTO}&gt;
     */
    default Page<SupplierPaymentResponseDTO> searchPaymentsForFinance(
            Long branchId,
            Long supplierId,
            String keyword,
            LocalDate fromDate,
            LocalDate toDate,
            Pageable pageable,
            Long userId
    ) {
        return searchPayments(branchId, supplierId, keyword, fromDate, toDate, pageable, userId);
    }

    /**
     * Tạo mới phiếu thanh toán công nợ nhà cung cấp từ phân hệ Tài chính.
     *
     * @param dto    dữ liệu thanh toán
     * @param userId ID người dùng tài chính thực hiện thanh toán
     * @return {@link SupplierPaymentResponseDTO} vừa tạo
     */
    default SupplierPaymentResponseDTO createPaymentForFinance(
            SupplierPaymentCreateDTO dto,
            Long userId
    ) {
        return createPayment(dto, userId);
    }

    /**
     * Lấy danh sách phiếu nhập kho ({@code StockReceipt}) còn công nợ ({@code totalAmount - paidAmount > 0})
     * của một nhà cung cấp tại chi nhánh (phân hệ Tài chính).
     *
     * @param branchId   ID chi nhánh
     * @param supplierId ID nhà cung cấp
     * @param userId     ID người dùng hiện tại
     * @return danh sách {@link StockReceiptResponseDTO} chưa thanh toán đủ
     */
    default List<StockReceiptResponseDTO> getUnpaidReceiptsForFinance(
            Long branchId,
            Long supplierId,
            Long userId
    ) {
        return Collections.emptyList();
    }

    /**
     * Lấy chi tiết tổng hợp công nợ của một nhà cung cấp cụ thể tại chi nhánh (phân hệ Tài chính).
     *
     * @param branchId   ID chi nhánh
     * @param supplierId ID nhà cung cấp
     * @param userId     ID người dùng hiện tại
     * @return {@link SupplierDebtSummaryDTO} của nhà cung cấp
     */
    default SupplierDebtSummaryDTO getSupplierDebtDetailForFinance(
            Long branchId,
            Long supplierId,
            Long userId
    ) {
        return null;
    }

    /**
     * Tra cứu phân trang danh sách phiếu nhập kho kèm số tiền đã trả và còn nợ của từng phiếu (phân hệ Tài chính).
     *
     * @param branchId   ID chi nhánh
     * @param supplierId ID nhà cung cấp (tùy chọn)
     * @param keyword    từ khóa tìm kiếm phiếu nhập (tùy chọn)
     * @param fromDate   từ ngày nhập kho (tùy chọn)
     * @param toDate     đến ngày nhập kho (tùy chọn)
     * @param pageable   thông tin phân trang và sắp xếp
     * @param userId     ID người dùng hiện tại
     * @return trang kết quả {@link Page}&lt;{@link StockReceiptResponseDTO}&gt;
     */
    default Page<StockReceiptResponseDTO> getReceiptsForFinance(
            Long branchId,
            Long supplierId,
            String keyword,
            LocalDate fromDate,
            LocalDate toDate,
            Pageable pageable,
            Long userId
    ) {
        return Page.empty(pageable);
    }
}
