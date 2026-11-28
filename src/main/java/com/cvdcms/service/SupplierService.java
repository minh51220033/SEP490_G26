
package com.cvdcms.service;

import com.cvdcms.DTO.SupplierCreateDTO;
import com.cvdcms.DTO.SupplierUpdateDTO;
import com.cvdcms.entity.Supplier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Interface định nghĩa nghiệp vụ quản lý danh mục Nhà cung cấp ({@link Supplier}).
 *
 * <p><b>Triển khai bởi:</b> {@link com.cvdcms.service.impl.SupplierServiceImpl}</p>
 * <p><b>Được gọi từ:</b> {@link com.cvdcms.controller.SupplierController} ({@code /api/suppliers}).</p>
 */
public interface SupplierService {

    /**
     * Tìm kiếm, lọc theo trạng thái hoạt động và phân trang danh sách nhà cung cấp.
     *
     * @param keyword  từ khóa tìm theo mã NCC, tên, SĐT hoặc MST (có thể rỗng/{@code null})
     * @param active   trạng thái hoạt động ({@code true}/{@code false}/{@code null} để lấy tất cả)
     * @param pageable thông tin phân trang và sắp xếp
     * @return trang dữ liệu {@link Page}&lt;{@link Supplier}&gt;
     */
    Page<Supplier> search(
            String keyword,
            Boolean active,
            Pageable pageable
    );

    /**
     * Lấy thông tin chi tiết một nhà cung cấp theo ID.
     *
     * @param id ID nhà cung cấp
     * @return đối tượng {@link Supplier} tìm thấy
     */
    Supplier getById(Long id);

    /**
     * Thêm mới nhà cung cấp (chuẩn hóa dữ liệu, kiểm tra trùng mã NCC và mã số thuế, mặc định {@code isActive = true}).
     *
     * @param dto dữ liệu đầu vào tạo nhà cung cấp
     * @return bản ghi {@link Supplier} vừa lưu
     */
    Supplier create(SupplierCreateDTO dto);

    /**
     * Cập nhật thông tin nhà cung cấp hiện có (không thay đổi trạng thái {@code isActive}).
     *
     * @param id  ID nhà cung cấp cần cập nhật
     * @param dto dữ liệu cập nhật
     * @return bản ghi {@link Supplier} sau khi lưu
     */
    Supplier update(Long id, SupplierUpdateDTO dto);

    /**
     * Ngừng hoạt động nhà cung cấp ({@code isActive = false}), bảo toàn lịch sử phiếu nhập và thanh toán.
     *
     * @param id ID nhà cung cấp
     * @return bản ghi {@link Supplier} sau khi ngừng hoạt động
     */
    Supplier deactivate(Long id);

    /**
     * Kích hoạt lại nhà cung cấp đang ngừng hoạt động ({@code isActive = true}).
     *
     * @param id ID nhà cung cấp
     * @return bản ghi {@link Supplier} sau khi kích hoạt lại
     */
    Supplier activate(Long id);
}
