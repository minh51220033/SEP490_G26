package com.cvdcms.service;

import com.cvdcms.DTO.ServiceCreateDTO;
import com.cvdcms.entity.Service;
import com.cvdcms.entity.ServiceCategory;
import com.cvdcms.entity.enums.ServiceStatus;
import com.cvdcms.entity.enums.ServiceType;
import com.cvdcms.repository.ServiceCategoryRepository;
import com.cvdcms.repository.ServiceRepository;
import com.cvdcms.service.impl.ServiceCatalogServiceImpl;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Examples designed against DB(8).zip. Execution must be verified locally. */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreateService")
class ServiceCatalogCreateReportTest {
    @Mock ServiceRepository serviceRepository;
    @Mock ServiceCategoryRepository categoryRepository;
    private ValidatorFactory validatorFactory;
    private ServiceCatalogServiceImpl service;

    @BeforeEach
    void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        service = new ServiceCatalogServiceImpl(serviceRepository,
                categoryRepository, validatorFactory.getValidator());
    }

    @AfterEach
    void tearDown() { validatorFactory.close(); }

    private ServiceCreateDTO validInput() {
        ServiceCreateDTO dto = new ServiceCreateDTO();
        dto.setCategoryId(1L);
        dto.setServiceCode("dv01");
        dto.setServiceName("Cạo vôi răng");
        dto.setServiceType(ServiceType.PROCEDURE);
        dto.setUnit("Lần");
        dto.setBasePrice(new BigDecimal("250000.00"));
        dto.setSurchargeAmount(BigDecimal.ZERO);
        dto.setRequiresTooth(false);
        dto.setIsBookableOnline(true);
        dto.setWarrantyMonths(0);
        dto.setEstimatedDurationMinutes(30);
        dto.setStatus(ServiceStatus.ACTIVE);
        return dto;
    }

    private void existingCategory(boolean active, ServiceType type) {
        ServiceCategory category = new ServiceCategory();
        category.setCategoryId(1L);
        category.setCategoryCode("NHOM01");
        category.setCategoryName("Nhóm điều trị");
        category.setCategoryType(type);
        category.setIsActive(active);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
    }

    private Service savedService() {
        ArgumentCaptor<Service> captor = ArgumentCaptor.forClass(Service.class);
        verify(serviceRepository).save(captor.capture());
        return captor.getValue();
    }

    private void assertRejected(ServiceCreateDTO dto) {
        assertThrows(IllegalArgumentException.class, () -> service.createService(dto));
        verify(serviceRepository, never()).save(any(Service.class));
    }

    @Test @DisplayName("UTCID01 - N - Tạo hợp lệ và chuẩn hóa mã")
    void UTC_FT02_CS01_validInput_normalizesCodeAndSaves() {
        existingCategory(true, ServiceType.PROCEDURE);
        service.createService(validInput());
        Service saved = savedService();
        assertEquals("DV01", saved.getServiceCode());
        assertEquals("Cạo vôi răng", saved.getServiceName());
        assertEquals(0, new BigDecimal("250000.00").compareTo(saved.getBasePrice()));
        assertEquals(1L, saved.getCategory().getCategoryId());
    }

    @Test @DisplayName("UTC_FT02_CS02 - A - Mã dịch vụ trùng")
    void UTC_FT02_CS02_duplicateCode_rejected() {
        when(serviceRepository.existsByServiceCodeIgnoreCase("DV01")).thenReturn(true);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createService(validInput()));
        assertEquals("Mã dịch vụ đã tồn tại.", error.getMessage());
        verify(serviceRepository, never()).save(any(Service.class));
    }

    @Test @DisplayName("UTC_FT02_CS03 - A - Nhóm không tồn tại")
    void UTC_FT02_CS03_categoryMissing_rejected() {
        when(categoryRepository.findById(1L)).thenReturn(Optional.empty());
        assertRejected(validInput());
    }

    @Test @DisplayName("UTC_FT02_CS04 - A - Nhóm ngừng hoạt động")
    void UTC_FT02_CS04_inactiveCategory_rejected() {
        existingCategory(false, ServiceType.PROCEDURE);
        assertRejected(validInput());
    }

    @Test @DisplayName("UTC_FT02_CS05 - A - Loại dịch vụ khác loại nhóm")
    void UTC_FT02_CS05_differentCategoryType_rejected() {
        existingCategory(true, ServiceType.LAB);
        assertRejected(validInput());
    }

    @Test @DisplayName("UTC_FT02_CS06 - B - Đơn giá -0.01")
    void UTC_FT02_CS06_priceBelowZero_rejected() {
        ServiceCreateDTO dto = validInput();
        dto.setBasePrice(new BigDecimal("-0.01"));
        assertRejected(dto);
    }

    @Test @DisplayName("UTC_FT02_CS07 - B - Đơn giá bằng 0")
    void UTC_FT02_CS07_zeroPrice_accepted() {
        existingCategory(true, ServiceType.PROCEDURE);
        ServiceCreateDTO dto = validInput();
        dto.setBasePrice(BigDecimal.ZERO);
        service.createService(dto);
        assertEquals(0, savedService().getBasePrice().compareTo(BigDecimal.ZERO));
    }

    @Test @DisplayName("UTC_FT02_CS08 - B - Tên dài 200 ký tự")
    void UTC_FT02_CS08_nameAtMaximum_accepted() {
        existingCategory(true, ServiceType.PROCEDURE);
        ServiceCreateDTO dto = validInput();
        dto.setServiceName("A".repeat(200));
        service.createService(dto);
        assertEquals("A".repeat(200), savedService().getServiceName());
    }

    @Test @DisplayName("UTC_FT02_CS09 - B - Tên dài 201 ký tự")
    void UTC_FT02_CS09_nameAboveMaximum_rejected() {
        ServiceCreateDTO dto = validInput();
        dto.setServiceName("A".repeat(201));
        assertRejected(dto);
    }

    @Test @DisplayName("UTC_FT02_CS10 - B - Thời lượng 15 phút")
    void UTC_FT02_CS10_durationAtMinimum_accepted() {
        existingCategory(true, ServiceType.PROCEDURE);
        ServiceCreateDTO dto = validInput();
        dto.setEstimatedDurationMinutes(15);
        service.createService(dto);
        assertEquals(15, savedService().getEstimatedDurationMinutes());
    }

    @Test @DisplayName("UTC_FT02_CS11 - B - Thời lượng 240 phút")
    void UTC_FT02_CS11_durationAtMaximum_accepted() {
        existingCategory(true, ServiceType.PROCEDURE);
        ServiceCreateDTO dto = validInput();
        dto.setEstimatedDurationMinutes(240);
        service.createService(dto);
        assertEquals(240, savedService().getEstimatedDurationMinutes());
    }

    @Test @DisplayName("UTC_FT02_CS12 - B - Thời lượng 14 phút")
    void UTC_FT02_CS12_durationBelowMinimum_rejected() {
        ServiceCreateDTO dto = validInput();
        dto.setEstimatedDurationMinutes(14);
        assertRejected(dto);
    }
}
