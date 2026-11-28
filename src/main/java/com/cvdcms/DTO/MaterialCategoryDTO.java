package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO thống kê một nhóm vật tư trong danh mục dùng chung.
 * <p>Trả về bởi {@code GET /api/materials/categories} để hiển thị bộ lọc nhóm và modal quản lý nhóm vật tư.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MaterialCategoryDTO {
    /** Tên nhóm vật tư (ví dụ: Trám răng, Chỉnh nha, Tiêu hao). */
    private String categoryName;
    /** Tổng số vật tư thuộc nhóm này (cả đang hoạt động và ngừng sử dụng). */
    private long totalMaterials;
    /** Số lượng vật tư đang hoạt động ({@code isActive = true}) trong nhóm. */
    private long activeMaterials;
}
