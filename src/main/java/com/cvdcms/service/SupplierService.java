
package com.cvdcms.service;

import com.cvdcms.DTO.SupplierCreateDTO;
import com.cvdcms.DTO.SupplierUpdateDTO;
import com.cvdcms.entity.Supplier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface SupplierService {

    // Danh sách nhà cung cấp: tìm kiếm, lọc và phân trang.
    Page<Supplier> search(
            String keyword,
            Boolean active,
            Pageable pageable
    );

    // Xem chi tiết nhà cung cấp.
    Supplier getById(Long id);

    // Thêm nhà cung cấp mới.
    Supplier create(SupplierCreateDTO dto);

    // Cập nhật thông tin nhà cung cấp.
    Supplier update(Long id, SupplierUpdateDTO dto);

    // Ngừng hoạt động nhà cung cấp.
    Supplier deactivate(Long id);

    // Kích hoạt lại nhà cung cấp.
    Supplier activate(Long id);
}
