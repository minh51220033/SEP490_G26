package com.cvdcms.service;

import com.cvdcms.DTO.StockIssueCreateDTO;
import com.cvdcms.DTO.StockIssueResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;

/**
 * Giao diện Service xử lý nghiệp vụ Phiếu xuất kho (Stock Issue) và Điều chuyển vật tư liên chi nhánh (Stock Transfer).
 * <p>Được gọi từ {@link com.cvdcms.controller.StockIssueController} và
 * {@link com.cvdcms.controller.InventoryPageController}; triển khai bởi
 * {@link com.cvdcms.service.impl.StockIssueServiceImpl}.</p>
 */
public interface StockIssueService {

    /**
     * Tạo mới phiếu xuất kho hoặc phiếu điều chuyển kho, khấu trừ tồn kho nguồn theo lô chỉ định hoặc tự động FEFO,
     * đồng thời cộng lô/tồn kho đích nếu là điều chuyển ({@code TRANSFER}).
     *
     * @param dto    dữ liệu phiếu xuất / điều chuyển
     * @param userId mã người dùng thực hiện lập phiếu
     * @return {@link StockIssueResponseDTO} đầy đủ chi tiết phiếu vừa tạo
     */
    StockIssueResponseDTO create(StockIssueCreateDTO dto, Long userId);

    /**
     * Tìm kiếm và phân trang lịch sử phiếu xuất kho của một chi nhánh.
     *
     * @param branchId        mã chi nhánh xuất kho
     * @param issueType       loại phiếu xuất cần lọc (tùy chọn)
     * @param excludeTransfer {@code true} để loại trừ phiếu điều chuyển ({@code TRANSFER})
     * @param keyword         từ khóa tìm kiếm mã phiếu/lý do/ghi chú (tùy chọn)
     * @param fromDate        ngày lập phiếu từ ngày (tùy chọn)
     * @param toDate          ngày lập phiếu đến ngày (tùy chọn)
     * @param pageable        cấu hình phân trang và sắp xếp
     * @param userId          mã người dùng yêu cầu (dùng để kiểm tra quyền chi nhánh)
     * @return trang dữ liệu {@link StockIssueResponseDTO}
     */
    Page<StockIssueResponseDTO> searchIssues(
            Long branchId,
            String issueType,
            boolean excludeTransfer,
            String keyword,
            LocalDate fromDate,
            LocalDate toDate,
            Pageable pageable,
            Long userId
    );

    /**
     * Tìm kiếm và phân trang lịch sử phiếu điều chuyển kho liên quan tới một chi nhánh (cả chiều xuất đi lẫn chiều nhận về).
     *
     * @param branchId            mã chi nhánh đang xem
     * @param destinationBranchId mã chi nhánh đích cần lọc thêm (tùy chọn)
     * @param keyword             từ khóa tìm kiếm mã phiếu/ghi chú (tùy chọn)
     * @param fromDate            ngày điều chuyển từ ngày (tùy chọn)
     * @param toDate              ngày điều chuyển đến ngày (tùy chọn)
     * @param pageable            cấu hình phân trang và sắp xếp
     * @param userId              mã người dùng yêu cầu
     * @return trang dữ liệu {@link StockIssueResponseDTO} loại điều chuyển
     */
    Page<StockIssueResponseDTO> searchTransfers(
            Long branchId,
            Long destinationBranchId,
            String keyword,
            LocalDate fromDate,
            LocalDate toDate,
            Pageable pageable,
            Long userId
    );

    /**
     * Lấy chi tiết đầy đủ một phiếu xuất kho hoặc phiếu điều chuyển kèm danh sách lô đã xuất.
     *
     * @param issueId mã phiếu xuất / điều chuyển
     * @param userId  mã người dùng yêu cầu (có quyền ở chi nhánh nguồn hoặc chi nhánh đích)
     * @return {@link StockIssueResponseDTO} chi tiết
     */
    StockIssueResponseDTO getById(Long issueId, Long userId);
}
