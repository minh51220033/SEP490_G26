package com.cvdcms.service;

import com.cvdcms.DTO.SupplierCreateDTO;
import com.cvdcms.DTO.SupplierUpdateDTO;
import com.cvdcms.entity.Supplier;
import com.cvdcms.repository.SupplierRepository;
import com.cvdcms.service.impl.SupplierServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupplierServiceTest {

    @Mock
    private SupplierRepository supplierRepository;

    private SupplierServiceImpl supplierService;
    private Supplier sampleSupplier;

    @BeforeEach
    void setUp() {
        supplierService = new SupplierServiceImpl(supplierRepository);
        sampleSupplier = new Supplier();
        sampleSupplier.setSupplierId(10L);
        sampleSupplier.setSupplierCode("NCC-3M");
        sampleSupplier.setSupplierName("Công ty 3M Việt Nam");
        sampleSupplier.setTaxCode("0101234567");
        sampleSupplier.setPhoneNumber("0901234567");
        sampleSupplier.setAddress("Quận 1, TP.HCM");
        sampleSupplier.setIsActive(true);
    }

    @Test
    @DisplayName("search - Chuẩn hóa keyword rỗng thành null và trim keyword có giá trị")
    void search_normalizesKeyword() {
        Pageable pageable = PageRequest.of(0, 10);
        when(supplierRepository.searchSuppliers(null, Boolean.TRUE, pageable))
                .thenReturn(new PageImpl<>(List.of(sampleSupplier)));
        when(supplierRepository.searchSuppliers("3M", null, pageable))
                .thenReturn(new PageImpl<>(List.of(sampleSupplier)));

        Page<Supplier> blankRes = supplierService.search("   ", Boolean.TRUE, pageable);
        Page<Supplier> trimRes = supplierService.search("  3M ", null, pageable);

        assertEquals(1, blankRes.getTotalElements());
        assertEquals(1, trimRes.getTotalElements());
        verify(supplierRepository).searchSuppliers(null, Boolean.TRUE, pageable);
        verify(supplierRepository).searchSuppliers("3M", null, pageable);
    }

    @Test
    @DisplayName("getById - Từ chối ID không hợp lệ hoặc không tìm thấy nhà cung cấp")
    void getById_invalidOrNotFound_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> supplierService.getById(null));
        assertThrows(IllegalArgumentException.class, () -> supplierService.getById(0L));

        when(supplierRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> supplierService.getById(99L));

        when(supplierRepository.findById(10L)).thenReturn(Optional.of(sampleSupplier));
        assertEquals("NCC-3M", supplierService.getById(10L).getSupplierCode());
    }

    @Test
    @DisplayName("create - Từ chối DTO null, mã/tên trống hoặc mã nhà cung cấp đã tồn tại")
    void create_invalidOrDuplicateCode_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> supplierService.create(null));

        SupplierCreateDTO blankCode = new SupplierCreateDTO();
        blankCode.setSupplierCode("  ");
        blankCode.setSupplierName("NCC");
        assertThrows(IllegalArgumentException.class, () -> supplierService.create(blankCode));

        SupplierCreateDTO dupDto = new SupplierCreateDTO();
        dupDto.setSupplierCode("ncc-3m");
        dupDto.setSupplierName("3M");
        when(supplierRepository.existsBySupplierCodeIgnoreCase("NCC-3M")).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> supplierService.create(dupDto));

        SupplierCreateDTO blankName = new SupplierCreateDTO();
        blankName.setSupplierCode("NCC-NEW");
        blankName.setSupplierName("  ");
        when(supplierRepository.existsBySupplierCodeIgnoreCase("NCC-NEW")).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> supplierService.create(blankName));

        verify(supplierRepository, never()).save(any());
    }

    @Test
    @DisplayName("create - Chuẩn hóa mã in hoa, tên gọn khoảng trắng và các trường tùy chọn rỗng thành null")
    void create_validDto_normalizesFieldsAndSetsActive() {
        SupplierCreateDTO dto = new SupplierCreateDTO();
        dto.setSupplierCode("  ncc-dentsply ");
        dto.setSupplierName("  Công ty   Dentsply  ");
        dto.setTaxCode("   ");
        dto.setPhoneNumber("");
        dto.setAddress("  Hà Nội ");

        when(supplierRepository.existsBySupplierCodeIgnoreCase("NCC-DENTSPLY")).thenReturn(false);
        when(supplierRepository.save(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));

        Supplier saved = supplierService.create(dto);

        assertEquals("NCC-DENTSPLY", saved.getSupplierCode());
        assertEquals("Công ty Dentsply", saved.getSupplierName());
        assertNull(saved.getTaxCode());
        assertNull(saved.getPhoneNumber());
        assertEquals("Hà Nội", saved.getAddress());
        assertTrue(saved.getIsActive());
    }

    @Test
    @DisplayName("update - Từ chối DTO null hoặc trùng mã với nhà cung cấp khác")
    void update_nullOrDuplicateCode_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> supplierService.update(10L, null));

        SupplierUpdateDTO dto = new SupplierUpdateDTO();
        dto.setSupplierCode("NCC-OTHER");
        dto.setSupplierName("Tên mới");

        when(supplierRepository.findById(10L)).thenReturn(Optional.of(sampleSupplier));
        when(supplierRepository.existsBySupplierCodeIgnoreCaseAndSupplierIdNot("NCC-OTHER", 10L)).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> supplierService.update(10L, dto));
        verify(supplierRepository, never()).save(any());
    }

    @Test
    @DisplayName("update - Cập nhật thông tin thành công mà không làm thay đổi trạng thái isActive")
    void update_validDto_preservesIsActive() {
        sampleSupplier.setIsActive(false);
        SupplierUpdateDTO dto = new SupplierUpdateDTO();
        dto.setSupplierCode(" ncc-3m-v2 ");
        dto.setSupplierName(" Công ty 3M  Cập nhật ");
        dto.setTaxCode("0101234567");
        dto.setPhoneNumber("  ");
        dto.setAddress("Đà Nẵng");

        when(supplierRepository.findById(10L)).thenReturn(Optional.of(sampleSupplier));
        when(supplierRepository.existsBySupplierCodeIgnoreCaseAndSupplierIdNot("NCC-3M-V2", 10L)).thenReturn(false);
        when(supplierRepository.save(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));

        Supplier updated = supplierService.update(10L, dto);

        assertEquals("NCC-3M-V2", updated.getSupplierCode());
        assertEquals("Công ty 3M Cập nhật", updated.getSupplierName());
        assertNull(updated.getPhoneNumber());
        assertEquals("Đà Nẵng", updated.getAddress());
        assertFalse(updated.getIsActive());
    }

    @Test
    @DisplayName("deactivate & activate - Chuyển đổi trạng thái hoạt động và chặn chuyển trùng trạng thái")
    void deactivateAndActivate_validAndDuplicateTransitions() {
        when(supplierRepository.findById(10L)).thenReturn(Optional.of(sampleSupplier));
        when(supplierRepository.save(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));

        // Đang active -> không thể activate lại
        assertThrows(IllegalArgumentException.class, () -> supplierService.activate(10L));

        // Deactivate thành công
        Supplier deactivated = supplierService.deactivate(10L);
        assertFalse(deactivated.getIsActive());

        // Đã inactive -> không thể deactivate lại
        assertThrows(IllegalArgumentException.class, () -> supplierService.deactivate(10L));

        // Activate lại thành công
        Supplier activated = supplierService.activate(10L);
        assertTrue(activated.getIsActive());
    }

    @Test
    @DisplayName("create & update - Từ chối mã số thuế trùng lặp với nhà cung cấp khác")
    void createAndUpdate_duplicateTaxCode_throwsException() {
        SupplierCreateDTO createDto = new SupplierCreateDTO();
        createDto.setSupplierCode("NCC-NEW");
        createDto.setSupplierName("Công ty Mới");
        createDto.setTaxCode(" 0101234567 ");

        when(supplierRepository.existsBySupplierCodeIgnoreCase("NCC-NEW")).thenReturn(false);
        when(supplierRepository.existsByTaxCodeIgnoreCase("0101234567")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> supplierService.create(createDto));

        SupplierUpdateDTO updateDto = new SupplierUpdateDTO();
        updateDto.setSupplierCode("NCC-3M");
        updateDto.setSupplierName("Công ty 3M");
        updateDto.setTaxCode("0109999999");

        when(supplierRepository.findById(10L)).thenReturn(Optional.of(sampleSupplier));
        when(supplierRepository.existsBySupplierCodeIgnoreCaseAndSupplierIdNot("NCC-3M", 10L)).thenReturn(false);
        when(supplierRepository.existsByTaxCodeIgnoreCaseAndSupplierIdNot("0109999999", 10L)).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> supplierService.update(10L, updateDto));
        verify(supplierRepository, never()).save(any());
    }
}
