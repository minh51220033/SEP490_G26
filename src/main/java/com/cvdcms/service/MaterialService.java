package com.cvdcms.service;

import com.cvdcms.DTO.MaterialCategoryDTO;
import com.cvdcms.DTO.MaterialCreateDTO;
import com.cvdcms.DTO.MaterialUpdateDTO;
import com.cvdcms.entity.Material;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Giao diện Service quản lý Danh mục vật tư nha khoa dùng chung ({@link Material}).
 * <p>Được gọi từ {@link com.cvdcms.controller.MaterialController} và triển khai bởi
 * {@link com.cvdcms.service.impl.MaterialServiceImpl}.</p>
 */
public interface MaterialService {

    /**
     * Tìm kiếm và phân trang danh mục vật tư theo từ khóa, nhóm vật tư và trạng thái hoạt động.
     *
     * @param keyword  từ khóa tìm kiếm mã/tên/nhóm/đơn vị (tùy chọn)
     * @param category tên nhóm vật tư cần lọc (tùy chọn)
     * @param active   trạng thái hoạt động ({@code true}/{@code false}/{@code null})
     * @param pageable cấu hình phân trang và sắp xếp
     * @return trang dữ liệu {@link Material}
     */
    Page<Material> search(
            String keyword,
            String category,
            Boolean active,
            Pageable pageable
    );

    /**
     * Lấy toàn bộ vật tư đang hoạt động ({@code isActive = true}) sắp xếp theo tên tăng dần.
     *
     * @return danh sách {@link Material} đang hoạt động
     */
    List<Material> listActive();

    /**
     * Thống kê danh sách các nhóm vật tư kèm tổng số lượng vật tư và số vật tư đang hoạt động trong mỗi nhóm.
     *
     * @return danh sách {@link MaterialCategoryDTO} (bao gồm cả các nhóm mặc định chưa có vật tư)
     */
    List<MaterialCategoryDTO> getCategories();

    /**
     * Đổi tên đồng loạt một nhóm vật tư cho toàn bộ bản ghi đang mang tên nhóm cũ.
     *
     * @param oldCategory tên nhóm hiện tại
     * @param newCategory tên nhóm mới
     * @return số lượng bản ghi vật tư đã được cập nhật
     */
    int renameCategory(String oldCategory, String newCategory);

    /**
     * Lấy chi tiết một vật tư theo mã định danh; ném {@link IllegalArgumentException} nếu không tồn tại.
     *
     * @param id mã vật tư
     * @return thực thể {@link Material}
     */
    Material getById(Long id);

    /**
     * Tạo mới một vật tư trong danh mục dùng chung (kiểm tra trùng mã không phân biệt hoa thường).
     *
     * @param dto dữ liệu vật tư mới
     * @return thực thể {@link Material} vừa lưu
     */
    Material create(MaterialCreateDTO dto);

    /**
     * Cập nhật thông tin một vật tư theo {@code id} (bảo vệ mã/tên/đơn vị nếu vật tư đồng bộ từ danh mục thuốc).
     *
     * @param id  mã vật tư cần cập nhật
     * @param dto dữ liệu cập nhật
     * @return thực thể {@link Material} sau khi lưu
     */
    Material update(Long id, MaterialUpdateDTO dto);

    /**
     * Ngừng sử dụng (soft-deactivate) một vật tư ({@code isActive = false}).
     *
     * @param id mã vật tư
     * @return thực thể {@link Material} sau khi ngừng sử dụng
     */
    Material deactivate(Long id);

    /**
     * Kích hoạt lại một vật tư đang ngừng sử dụng ({@code isActive = true}).
     *
     * @param id mã vật tư
     * @return thực thể {@link Material} sau khi kích hoạt lại
     */
    Material activate(Long id);
}
