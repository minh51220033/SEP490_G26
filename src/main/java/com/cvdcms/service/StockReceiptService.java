package com.cvdcms.service;

import com.cvdcms.DTO.StockReceiptCreateDTO;
import com.cvdcms.DTO.StockReceiptResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;

/**
 * Giao diện Service xử lý nghiệp vụ Phiếu nhập kho (Stock Receipt).
 * <p>Được gọi từ {@link com.cvdcms.controller.StockReceiptController} và
 * {@link com.cvdcms.controller.InventoryPageController}; triển khai bởi
 * {@link com.cvdcms.service.impl.StockReceiptServiceImpl}.</p>
 */
public interface StockReceiptService {

    /**
     * Tạo mới phiếu nhập kho, cập nhật/tạo lô vật tư, cộng tồn kho chi nhánh và ghi nhận thanh toán ban đầu (nếu có).
     *
     * @param dto    dữ liệu phiếu nhập kho từ client
     * @param userId mã người dùng thực hiện lập phiếu
     * @return {@link StockReceiptResponseDTO} đầy đủ chi tiết phiếu vừa tạo
     */
    StockReceiptResponseDTO create(StockReceiptCreateDTO dto, Long userId);

    /**
     * Tìm kiếm và phân trang lịch sử phiếu nhập kho theo chi nhánh, nhà cung cấp, từ khóa và khoảng thời gian.
     *
     * @param branchId   mã chi nhánh cần tra cứu
     * @param supplierId mã nhà cung cấp cần lọc (tùy chọn)
     * @param keyword    từ khóa tìm kiếm mã phiếu/hóa đơn/NCC/ghi chú (tùy chọn)
     * @param fromDate   ngày nhập từ ngày (tùy chọn)
     * @param toDate     ngày nhập đến ngày (tùy chọn)
     * @param pageable   cấu hình phân trang và sắp xếp
     * @param userId     mã người dùng yêu cầu (dùng để kiểm tra quyền chi nhánh)
     * @return trang dữ liệu {@link StockReceiptResponseDTO} kèm số tiền đã trả và công nợ còn lại
     */
    Page<StockReceiptResponseDTO> search(
            Long branchId,
            Long supplierId,
            String keyword,
            LocalDate fromDate,
            LocalDate toDate,
            Pageable pageable,
            Long userId
    );

    /**
     * Lấy chi tiết đầy đủ một phiếu nhập kho kèm danh sách các dòng vật tư/lô đã nhập.
     *
     * @param receiptId mã định danh phiếu nhập kho
     * @param userId    mã người dùng yêu cầu (dùng để kiểm tra quyền truy cập chi nhánh)
     * @return {@link StockReceiptResponseDTO} chi tiết
     */
    StockReceiptResponseDTO getById(Long receiptId, Long userId);
}
