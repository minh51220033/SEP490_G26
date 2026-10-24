package com.cvdcms.service.impl;

import com.cvdcms.DTO.ServiceCategoryDTO;
import com.cvdcms.DTO.ServiceCreateDTO;
import com.cvdcms.DTO.ServiceTechnicalConfigDTO;
import com.cvdcms.DTO.ServiceUpdateDTO;
import com.cvdcms.entity.Service;
import com.cvdcms.entity.ServiceCategory;
import com.cvdcms.entity.enums.ServiceStatus;
import com.cvdcms.entity.enums.ServiceType;
import com.cvdcms.repository.ServiceCategoryRepository;
import com.cvdcms.repository.ServiceRepository;
import com.cvdcms.service.ServiceCatalogService;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;

@org.springframework.stereotype.Service
@Transactional(readOnly = true)
public class ServiceCatalogServiceImpl
        implements ServiceCatalogService {

    private final ServiceRepository serviceRepository;
    private final ServiceCategoryRepository categoryRepository;
    private final Validator validator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ServiceCatalogServiceImpl(
            ServiceRepository serviceRepository,
            ServiceCategoryRepository categoryRepository,
            Validator validator
    ) {
        this.serviceRepository = serviceRepository;
        this.categoryRepository = categoryRepository;
        this.validator = validator;
    }

    // =====================================================
    // 1. NHÓM DANH MỤC DỊCH VỤ
    // =====================================================

    @Override
    public List<ServiceCategory> getAllCategories() {
        return categoryRepository
                .findAllByOrderByDisplayOrderAscCategoryNameAsc();
    }

    @Override
    public List<ServiceCategory> getActiveCategories() {
        return categoryRepository
                .findByIsActiveTrueOrderByDisplayOrderAscCategoryNameAsc();
    }

    @Override
    public ServiceCategory getCategoryById(Long categoryId) {
        return requireCategory(categoryId);
    }

    @Override
    @Transactional
    public void createCategory(ServiceCategoryDTO dto) {
        validateInput(dto);

        String categoryCode = normalizeCode(dto.getCategoryCode());

        if (categoryRepository.existsByCategoryCodeIgnoreCase(
                categoryCode
        )) {
            throw new IllegalArgumentException(
                    "Mã nhóm dịch vụ đã tồn tại."
            );
        }

        ServiceCategory category = new ServiceCategory();

        category.setCategoryCode(categoryCode);
        category.setCategoryName(dto.getCategoryName().trim());
        category.setCategoryType(dto.getCategoryType());
        category.setDisplayOrder(dto.getDisplayOrder());
        category.setIsActive(dto.getIsActive());

        categoryRepository.save(category);
    }

    @Override
    @Transactional
    public void updateCategory(
            Long categoryId,
            ServiceCategoryDTO dto
    ) {
        validateInput(dto);

        ServiceCategory category = requireCategory(categoryId);
        String categoryCode = normalizeCode(dto.getCategoryCode());

        boolean duplicateCode = categoryRepository
                .existsByCategoryCodeIgnoreCaseAndCategoryIdNot(
                        categoryCode,
                        categoryId
                );

        if (duplicateCode) {
            throw new IllegalArgumentException(
                    "Mã nhóm dịch vụ đã được nhóm khác sử dụng."
            );
        }

        // Không đổi loại nhóm khi nhóm đã có dịch vụ.
        // Nếu đổi sẽ khiến loại nhóm và loại dịch vụ không thống nhất.
        boolean categoryTypeChanged = !Objects.equals(
                category.getCategoryType(),
                dto.getCategoryType()
        );

        if (categoryTypeChanged) {
            List<Service> existingServices = serviceRepository
                    .findByCategory_CategoryIdOrderByServiceNameAsc(
                            categoryId
                    );

            if (!existingServices.isEmpty()) {
                throw new IllegalArgumentException(
                        "Không thể đổi loại nhóm khi nhóm đã có dịch vụ."
                );
            }
        }

        category.setCategoryCode(categoryCode);
        category.setCategoryName(dto.getCategoryName().trim());
        category.setCategoryType(dto.getCategoryType());
        category.setDisplayOrder(dto.getDisplayOrder());
        category.setIsActive(dto.getIsActive());

        categoryRepository.save(category);
    }

    @Override
    @Transactional
    public void toggleCategoryStatus(Long categoryId) {
        ServiceCategory category = requireCategory(categoryId);

        category.setIsActive(
                !Boolean.TRUE.equals(category.getIsActive())
        );

        categoryRepository.save(category);
    }

    @Override
    @Transactional
    public void deleteCategory(Long categoryId) {
        if (categoryId == null || categoryId <= 0) {
            throw new IllegalArgumentException("ID nhóm dịch vụ không hợp lệ.");
        }

        ServiceCategory category = categoryRepository.findForDeletion(categoryId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Không tìm thấy nhóm dịch vụ."
                ));

        if (serviceRepository.existsByCategory_CategoryId(categoryId)) {
            throw new IllegalArgumentException(
                    "Không thể xóa nhóm vì nhóm đang chứa dịch vụ. "
                            + "Vui lòng chuyển dịch vụ sang nhóm khác hoặc ngừng sử dụng nhóm."
            );
        }

        try {
            categoryRepository.delete(category);
            categoryRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException(
                    "Không thể xóa nhóm vì dữ liệu đang được sử dụng. "
                            + "Vui lòng ngừng sử dụng nhóm.", ex
            );
        }
    }

    // =====================================================
    // 2. TRA CỨU DỊCH VỤ
    // =====================================================

    @Override
    public List<Service> getAllServices() {
        return serviceRepository
                .findAllByOrderByServiceNameAsc();
    }

    @Override
    public List<Service> getServicesByCategory(Long categoryId) {
        requireCategory(categoryId);

        return serviceRepository
                .findByCategory_CategoryIdOrderByServiceNameAsc(
                        categoryId
                );
    }

    @Override
    public Service getServiceById(Long serviceId) {
        return requireService(serviceId);
    }

    @Override
    public ServiceUpdateDTO getServiceUpdateForm(Long serviceId) {
        Service service = requireService(serviceId);

        ServiceUpdateDTO dto = new ServiceUpdateDTO();

        dto.setCategoryId(service.getCategory().getCategoryId());
        dto.setServiceName(service.getServiceName());
        dto.setServiceType(service.getServiceType());
        dto.setUnit(service.getUnit());
        dto.setBasePrice(service.getBasePrice());
        dto.setSurchargeAmount(service.getSurchargeAmount());
        dto.setRequiresTooth(service.getRequiresTooth());
        dto.setIsBookableOnline(service.getIsBookableOnline());
        dto.setWarrantyMonths(service.getWarrantyMonths());
        dto.setDescription(service.getDescription());
        dto.setStatus(service.getStatus());

        return dto;
    }

    // =====================================================
    // 3. THÊM DỊCH VỤ
    // =====================================================

    @Override
    @Transactional
    public void createService(ServiceCreateDTO dto) {
        validateInput(dto);

        String serviceCode = normalizeCode(dto.getServiceCode());

        if (serviceRepository.existsByServiceCodeIgnoreCase(
                serviceCode
        )) {
            throw new IllegalArgumentException(
                    "Mã dịch vụ đã tồn tại."
            );
        }

        ServiceCategory category = requireCategory(
                dto.getCategoryId()
        );

        if (!Boolean.TRUE.equals(category.getIsActive())) {
            throw new IllegalArgumentException(
                    "Không thể thêm dịch vụ vào nhóm đã ngừng sử dụng."
            );
        }

        validateCategoryType(category, dto.getServiceType());

        Service service = new Service();

        service.setCategory(category);
        service.setServiceCode(serviceCode);
        service.setServiceName(dto.getServiceName().trim());
        service.setServiceType(dto.getServiceType());
        service.setUnit(dto.getUnit().trim());

        service.setBasePrice(dto.getBasePrice());
        service.setSurchargeAmount(dto.getSurchargeAmount());

        service.setRequiresTooth(dto.getRequiresTooth());
        service.setIsParaclinical(
                isParaclinicalType(dto.getServiceType())
        );

        service.setIsBookableOnline(dto.getIsBookableOnline());
        service.setWarrantyMonths(dto.getWarrantyMonths());
        service.setDescription(blankToNull(dto.getDescription()));
        service.setStatus(dto.getStatus());

        serviceRepository.save(service);
    }

    // =====================================================
    // 4. SỬA DỊCH VỤ VÀ GIÁ
    // =====================================================

    @Override
    @Transactional
    public void updateService(
            Long serviceId,
            ServiceUpdateDTO dto
    ) {
        validateInput(dto);

        Service service = requireService(serviceId);
        ServiceCategory category = requireCategory(
                dto.getCategoryId()
        );

        validateCategoryType(category, dto.getServiceType());

        boolean categoryChanged = !Objects.equals(
                service.getCategory().getCategoryId(),
                category.getCategoryId()
        );

        if (!Boolean.TRUE.equals(category.getIsActive())) {
            // Cho phép chỉnh sửa dịch vụ INACTIVE đang ở nhóm cũ.
            // Không cho chuyển sang nhóm đã ngừng sử dụng
            // hoặc để dịch vụ ACTIVE trong nhóm đó.
            if (categoryChanged
                    || dto.getStatus() == ServiceStatus.ACTIVE) {
                throw new IllegalArgumentException(
                        "Nhóm đã ngừng sử dụng. Vui lòng chọn nhóm đang hoạt động "
                                + "hoặc giữ dịch vụ ở trạng thái ngừng sử dụng."
                );
            }
        }

        boolean paraclinical = isParaclinicalType(
                dto.getServiceType()
        );

        if (!paraclinical
                && blankToNull(service.getResultTemplateJson()) != null) {
            throw new IllegalArgumentException(
                    "Vui lòng xóa mẫu kết quả cận lâm sàng tại phần cấu hình "
                            + "trước khi chuyển sang loại dịch vụ này."
            );
        }

        service.setCategory(category);

        // Không cập nhật serviceCode.
        service.setServiceName(dto.getServiceName().trim());
        service.setServiceType(dto.getServiceType());
        service.setUnit(dto.getUnit().trim());

        service.setBasePrice(dto.getBasePrice());
        service.setSurchargeAmount(dto.getSurchargeAmount());

        service.setRequiresTooth(dto.getRequiresTooth());
        service.setIsParaclinical(paraclinical);
        service.setIsBookableOnline(dto.getIsBookableOnline());

        service.setWarrantyMonths(dto.getWarrantyMonths());
        service.setDescription(blankToNull(dto.getDescription()));
        service.setStatus(dto.getStatus());

        // Giữ nguyên cấu hình kỹ thuật.
        // Cấu hình đó được cập nhật bằng method riêng.
        serviceRepository.save(service);
    }

    // =====================================================
    // 5. BẬT / TẮT DỊCH VỤ
    // =====================================================

    @Override
    @Transactional
    public void toggleServiceStatus(Long serviceId) {
        Service service = requireService(serviceId);

        if (service.getStatus() == ServiceStatus.ACTIVE) {
            service.setStatus(ServiceStatus.INACTIVE);
        } else {
            if (!Boolean.TRUE.equals(
                    service.getCategory().getIsActive()
            )) {
                throw new IllegalArgumentException(
                        "Không thể kích hoạt dịch vụ vì nhóm đã ngừng sử dụng."
                );
            }

            service.setStatus(ServiceStatus.ACTIVE);
        }

        serviceRepository.save(service);
    }

    @Override
    @Transactional
    public void deleteService(Long serviceId) {
        if (serviceId == null || serviceId <= 0) {
            throw new IllegalArgumentException("ID dịch vụ không hợp lệ.");
        }

        Service service = serviceRepository.findForDeletion(serviceId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Không tìm thấy dịch vụ."
                ));

        if (serviceRepository.hasUsageReferences(serviceId) != 0) {
            throw new IllegalArgumentException(
                    "Không thể xóa dịch vụ vì dữ liệu đang được sử dụng "
                            + "trong lịch hẹn, điều trị, hóa đơn hoặc cấu hình liên quan. "
                            + "Vui lòng ngừng sử dụng dịch vụ."
            );
        }

        try {
            serviceRepository.delete(service);
            serviceRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            // Khóa ngoại là lớp kiểm tra cuối cùng nếu xuất hiện liên kết khác.
            throw new IllegalArgumentException(
                    "Không thể xóa dịch vụ vì dữ liệu đang được sử dụng. "
                            + "Vui lòng ngừng sử dụng dịch vụ.", ex
            );
        }
    }

    // =====================================================
    // 6. CẤU HÌNH KẾT QUẢ VÀ YÊU CẦU KỸ THUẬT
    // =====================================================

    @Override
    public ServiceTechnicalConfigDTO getServiceTechnicalConfig(
            Long serviceId
    ) {
        Service service = requireService(serviceId);

        ServiceTechnicalConfigDTO dto =
                new ServiceTechnicalConfigDTO();

        dto.setResultTemplateJson(
                service.getResultTemplateJson()
        );

        dto.setTechnicalRequirements(
                service.getTechnicalRequirements()
        );

        return dto;
    }

    @Override
    @Transactional
    public void updateServiceTechnicalConfig(
            Long serviceId,
            ServiceTechnicalConfigDTO dto
    ) {
        validateInput(dto);

        Service service = requireService(serviceId);

        String resultTemplateJson = validateResultTemplateJson(
                dto.getResultTemplateJson()
        );

        if (resultTemplateJson != null
                && !isParaclinicalType(service.getServiceType())) {
            throw new IllegalArgumentException(
                    "Mẫu kết quả cận lâm sàng chỉ áp dụng cho xét nghiệm và X-Ray."
            );
        }

        service.setResultTemplateJson(resultTemplateJson);

        service.setTechnicalRequirements(
                blankToNull(dto.getTechnicalRequirements())
        );

        // Không cập nhật giá, tên hoặc trạng thái tại đây.
        serviceRepository.save(service);
    }

    // =====================================================
    // 7. BẢNG GIÁ CÔNG KHAI
    // =====================================================

    @Override
    public List<Service> getPublicServices() {
        return serviceRepository
                .findByStatusAndCategory_IsActiveTrueOrderByServiceNameAsc(
                        ServiceStatus.ACTIVE
                );
    }

    @Override
    public List<Service> getPublicServices(
            Long categoryId,
            ServiceType serviceType
    ) {
        return serviceRepository
                .findPublicServices(
                        ServiceStatus.ACTIVE,
                        categoryId,
                        serviceType
                );
    }

    @Override
    public List<Service> getPublicServices(
            Long categoryId,
            ServiceType serviceType,
            String keyword
    ) {
        String searchKeyword = keyword == null ? "" : keyword.trim();

        // Tìm ký tự % và _ như nội dung nhập, không coi là ký tự đại diện SQL.
        searchKeyword = searchKeyword.replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");

        return serviceRepository.searchPublicServices(
                ServiceStatus.ACTIVE,
                categoryId,
                serviceType,
                searchKeyword
        );
    }

    // =====================================================
    // 8. CÁC HÀM KIỂM TRA DÙNG CHUNG
    // =====================================================

    private ServiceCategory requireCategory(Long categoryId) {
        if (categoryId == null || categoryId <= 0) {
            throw new IllegalArgumentException(
                    "ID nhóm dịch vụ không hợp lệ."
            );
        }

        return categoryRepository.findById(categoryId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy nhóm dịch vụ."
                        )
                );
    }

    private Service requireService(Long serviceId) {
        if (serviceId == null || serviceId <= 0) {
            throw new IllegalArgumentException(
                    "ID dịch vụ không hợp lệ."
            );
        }

        return serviceRepository.findById(serviceId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy dịch vụ."
                        )
                );
    }

    private void validateInput(Object dto) {
        if (dto == null) {
            throw new IllegalArgumentException(
                    "Dữ liệu gửi lên không hợp lệ."
            );
        }

        Set<ConstraintViolation<Object>> violations =
                validator.validate(dto);

        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(
                    violations.iterator().next().getMessage()
            );
        }
    }

    private void validateCategoryType(
            ServiceCategory category,
            ServiceType serviceType
    ) {
        if (category.getCategoryType() != serviceType) {
            throw new IllegalArgumentException(
                    "Loại dịch vụ phải trùng với loại của nhóm danh mục."
            );
        }
    }

    private boolean isParaclinicalType(ServiceType serviceType) {
        return serviceType == ServiceType.LAB
                || serviceType == ServiceType.XRAY;
    }

    private String validateResultTemplateJson(String value) {
        String json = blankToNull(value);

        if (json == null) {
            return null;
        }

        Object parsed;

        try {
            parsed = objectMapper.readValue(json, Object.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Mẫu kết quả không đúng định dạng JSON."
            );
        }

        if (!(parsed instanceof Map<?, ?> root)
                || !(root.get("parameters") instanceof List<?> parameters)) {
            throw new IllegalArgumentException(
                    "Mẫu kết quả phải chứa danh sách thông số."
            );
        }

        Set<String> usedKeys = new HashSet<>();

        for (Object item : parameters) {
            if (!(item instanceof Map<?, ?> parameter)) {
                throw new IllegalArgumentException(
                        "Thông số kết quả không hợp lệ."
                );
            }

            if (!(parameter.get("key") instanceof String key)
                    || !key.matches("^[A-Za-z][A-Za-z0-9_]{0,49}$")) {
                throw new IllegalArgumentException(
                        "Mã thông số phải bắt đầu bằng chữ không dấu, "
                                + "chỉ chứa chữ, số hoặc dấu _, tối đa 50 ký tự."
                );
            }

            if (!usedKeys.add(key.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "Mã thông số bị trùng: " + key
                );
            }

            if (!(parameter.get("label") instanceof String label)
                    || label.isBlank()
                    || label.length() > 200) {
                throw new IllegalArgumentException(
                        "Tên thông số phải có nội dung và tối đa 200 ký tự."
                );
            }

            if (!(parameter.get("type") instanceof String type)
                    || !Set.of("TEXT", "NUMBER", "BOOLEAN").contains(type)) {
                throw new IllegalArgumentException(
                        "Kiểu kết quả không hợp lệ."
                );
            }

            if (!(parameter.get("required") instanceof Boolean)) {
                throw new IllegalArgumentException(
                        "Trạng thái bắt buộc của thông số không hợp lệ."
                );
            }

            if (parameter.get("unit") != null
                    && !(parameter.get("unit") instanceof String)) {
                throw new IllegalArgumentException(
                        "Đơn vị thông số phải là văn bản."
                );
            }

            if (parameter.get("referenceRange") != null
                    && !(parameter.get("referenceRange") instanceof String)) {
                throw new IllegalArgumentException(
                        "Giá trị tham chiếu phải là văn bản."
                );
            }
        }

        return parameters.isEmpty() ? null : json;
    }

    private String normalizeCode(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }


}