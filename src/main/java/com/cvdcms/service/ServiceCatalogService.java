package com.cvdcms.service;

import com.cvdcms.DTO.ServiceCategoryDTO;
import com.cvdcms.DTO.ServiceCreateDTO;
import com.cvdcms.DTO.ServiceTechnicalConfigDTO;
import com.cvdcms.DTO.ServiceUpdateDTO;
import com.cvdcms.entity.Service;
import com.cvdcms.entity.ServiceCategory;
import com.cvdcms.entity.enums.ServiceType;

import java.util.List;

public interface ServiceCatalogService {

    // =====================================================
    // NHÓM DANH MỤC DỊCH VỤ
    // =====================================================

    List<ServiceCategory> getAllCategories();

    List<ServiceCategory> getActiveCategories();

    ServiceCategory getCategoryById(Long categoryId);

    void createCategory(ServiceCategoryDTO dto);

    void updateCategory(
            Long categoryId,
            ServiceCategoryDTO dto
    );

    void toggleCategoryStatus(Long categoryId);

    void deleteCategory(Long categoryId);

    // =====================================================
    // DỊCH VỤ VÀ BẢNG GIÁ
    // =====================================================

    List<Service> getAllServices();

    List<Service> getServicesByCategory(Long categoryId);

    Service getServiceById(Long serviceId);

    ServiceUpdateDTO getServiceUpdateForm(Long serviceId);

    void createService(ServiceCreateDTO dto);

    void updateService(
            Long serviceId,
            ServiceUpdateDTO dto
    );

    void toggleServiceStatus(Long serviceId);

    void deleteService(Long serviceId);

    // =====================================================
    // CẤU HÌNH KẾT QUẢ VÀ YÊU CẦU KỸ THUẬT
    // =====================================================

    ServiceTechnicalConfigDTO getServiceTechnicalConfig(
            Long serviceId
    );

    void updateServiceTechnicalConfig(
            Long serviceId,
            ServiceTechnicalConfigDTO dto
    );

    // =====================================================
    // BẢNG GIÁ CÔNG KHAI
    // =====================================================
    // Lấy tất cả dịch vụ công khai — đã có.
    List<Service> getPublicServices();

    // Lấy dịch vụ công khai theo bộ lọc — thêm mới.
    List<Service> getPublicServices(
            Long categoryId,
            ServiceType serviceType
    );
    // Bảng giá công khai: tìm theo tên/mã và kết hợp bộ lọc.
    List<Service> getPublicServices(
            Long categoryId,
            ServiceType serviceType,
            String keyword
    );
}