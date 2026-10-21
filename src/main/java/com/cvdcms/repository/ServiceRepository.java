package com.cvdcms.repository;

import com.cvdcms.entity.Service;
import com.cvdcms.entity.enums.ServiceStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.Optional;

import com.cvdcms.entity.enums.ServiceType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ServiceRepository extends JpaRepository<Service, Long> {
    // Kiểm tra mã dịch vụ đã tồn tại.
    boolean existsByServiceCodeIgnoreCase(String serviceCode);

    // Danh sách dịch vụ cho Admin, lấy kèm nhóm danh mục.
    @EntityGraph(attributePaths = "category")
    List<Service> findAllByOrderByServiceNameAsc();

    // Danh sách dịch vụ thuộc một nhóm.
    @EntityGraph(attributePaths = "category")
    List<Service> findByCategory_CategoryIdOrderByServiceNameAsc(
            Long categoryId
    );

    // Dịch vụ có trạng thái chỉ định và thuộc nhóm đang hoạt động.
    // Khi lấy bảng giá công khai, truyền ServiceStatus.ACTIVE.
    @EntityGraph(attributePaths = "category")
    List<Service>
    findByStatusAndCategory_IsActiveTrueOrderByServiceNameAsc(
            ServiceStatus status
    );

    //guest có thể xem không khai
    @EntityGraph(attributePaths = "category")
    @Query("""
    SELECT s
    FROM Service s
    JOIN s.category c
    WHERE s.status = :status
      AND c.isActive = TRUE
      AND (:categoryId IS NULL OR c.categoryId = :categoryId)
      AND (:serviceType IS NULL OR s.serviceType = :serviceType)
    ORDER BY c.displayOrder ASC,
             c.categoryName ASC,
             s.serviceName ASC
    """)
    List<Service> findPublicServices(
            @Param("status") ServiceStatus status,
            @Param("categoryId") Long categoryId,
            @Param("serviceType") ServiceType serviceType
    );

    // Khóa bản ghi trong transaction xóa để kiểm tra liên kết và xóa nhất quán.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT item FROM Service item WHERE item.serviceId = :id")
    Optional<Service> findForDeletion(@Param("id") Long id);

    boolean existsByCategory_CategoryId(Long categoryId);

    // Kiểm tra cả dữ liệu nghiệp vụ và cấu hình, không bỏ qua bản ghi đã hủy/ngừng.
    @Query(value = """
        SELECT CASE WHEN
            EXISTS (SELECT 1 FROM appointments WHERE service_id = :serviceId)
            OR EXISTS (SELECT 1 FROM treatment_plan_items WHERE service_id = :serviceId)
            OR EXISTS (SELECT 1 FROM performed_services WHERE service_id = :serviceId)
            OR EXISTS (SELECT 1 FROM paraclinical_order_items WHERE service_id = :serviceId)
            OR EXISTS (SELECT 1 FROM invoice_items WHERE service_id = :serviceId)
            OR EXISTS (SELECT 1 FROM clinical_workflow_templates WHERE service_id = :serviceId)
            OR EXISTS (SELECT 1 FROM clinical_workflow_step_paraclinical WHERE service_id = :serviceId)
            OR EXISTS (SELECT 1 FROM promotions WHERE applicable_service_id = :serviceId)
            OR EXISTS (SELECT 1 FROM commission_policies WHERE service_id = :serviceId)
        THEN 1 ELSE 0 END
        """, nativeQuery = true)
    int hasUsageReferences(@Param("serviceId") Long serviceId);

    @EntityGraph(attributePaths = "category")
    @Query("""
        SELECT s
        FROM Service s
        JOIN s.category c
        WHERE s.status = :status
          AND c.isActive = TRUE
          AND (:categoryId IS NULL OR c.categoryId = :categoryId)
          AND (:serviceType IS NULL OR s.serviceType = :serviceType)
          AND (
              :keyword = ''
              OR LOWER(s.serviceName) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '!'
              OR LOWER(s.serviceCode) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '!'
          )
        ORDER BY c.displayOrder ASC,
                 c.categoryName ASC,
                 s.serviceName ASC
        """)
    List<Service> searchPublicServices(
            @Param("status") ServiceStatus status,
            @Param("categoryId") Long categoryId,
            @Param("serviceType") ServiceType serviceType,
            @Param("keyword") String keyword
    );
}
