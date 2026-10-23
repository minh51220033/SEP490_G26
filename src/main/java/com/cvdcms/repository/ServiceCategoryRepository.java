package com.cvdcms.repository;

import com.cvdcms.entity.ServiceCategory;
import com.cvdcms.entity.enums.ServiceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.Optional;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ServiceCategoryRepository extends JpaRepository<ServiceCategory, Long> {

    // Danh sách tất cả nhóm, sắp xếp theo thứ tự hiển thị.
    List<ServiceCategory> findAllByOrderByDisplayOrderAscCategoryNameAsc();

    // Nhóm đang hoạt động, dùng cho form chọn nhóm và bảng giá công khai.
    List<ServiceCategory>
    findByIsActiveTrueOrderByDisplayOrderAscCategoryNameAsc();

    // Nhóm đang hoạt động thuộc một loại dịch vụ.
    List<ServiceCategory>
    findByCategoryTypeAndIsActiveTrueOrderByDisplayOrderAscCategoryNameAsc(
            ServiceType categoryType
    );
    // Kiểm tra mã nhóm đã tồn tại, không phân biệt chữ hoa/thường.
    boolean existsByCategoryCodeIgnoreCase(String categoryCode);

    // Kiểm tra trùng mã khi sửa, bỏ qua chính nhóm đang sửa.
    boolean existsByCategoryCodeIgnoreCaseAndCategoryIdNot(
            String categoryCode,
            Long categoryId
    );




    // Khóa bản ghi trong transaction xóa để kiểm tra liên kết và xóa nhất quán.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT item FROM ServiceCategory item WHERE item.categoryId = :id")
    Optional<ServiceCategory> findForDeletion(@Param("id") Long id);
}
