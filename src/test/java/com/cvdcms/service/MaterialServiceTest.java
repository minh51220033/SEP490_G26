package com.cvdcms.service;

import com.cvdcms.DTO.MaterialCategoryDTO;
import com.cvdcms.DTO.MaterialCreateDTO;
import com.cvdcms.DTO.MaterialUpdateDTO;
import com.cvdcms.entity.Material;
import com.cvdcms.entity.Medication;
import com.cvdcms.repository.MaterialRepository;
import com.cvdcms.service.impl.MaterialServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MaterialServiceTest {

    @Mock
    private MaterialRepository materialRepository;

    private MaterialServiceImpl materialService;

    private Material sampleMaterial;

    @BeforeEach
    void setUp() {
        materialService = new MaterialServiceImpl(materialRepository);
        sampleMaterial = new Material();
        sampleMaterial.setMaterialId(10L);
        sampleMaterial.setMaterialCode("VT-COMP-01");
        sampleMaterial.setMaterialName("Composite 3M");
        sampleMaterial.setCategory("Trám răng");
        sampleMaterial.setUnit("Ống");
        sampleMaterial.setMinStockThreshold(new BigDecimal("10.000"));
        sampleMaterial.setIsActive(true);
    }

    @Test
    @DisplayName("search - Chuẩn hóa keyword và category rỗng thành null khi tìm kiếm")
    void search_normalizesBlankFilters() {
        Pageable pageable = PageRequest.of(0, 10);
        when(materialRepository.searchMaterials(null, null, Boolean.TRUE, pageable))
                .thenReturn(new PageImpl<>(List.of(sampleMaterial)));

        Page<Material> result = materialService.search("   ", " ", Boolean.TRUE, pageable);

        assertEquals(1, result.getTotalElements());
        verify(materialRepository).searchMaterials(null, null, Boolean.TRUE, pageable);
    }

    @Test
    @DisplayName("search - Trim keyword và category hợp lệ")
    void search_trimsNonBlankFilters() {
        Pageable pageable = PageRequest.of(0, 10);
        when(materialRepository.searchMaterials("3M", "Trám răng", null, pageable))
                .thenReturn(new PageImpl<>(List.of(sampleMaterial)));

        Page<Material> result = materialService.search(" 3M ", " Trám răng ", null, pageable);

        assertEquals(1, result.getTotalElements());
        verify(materialRepository).searchMaterials("3M", "Trám răng", null, pageable);
    }

    @Test
    @DisplayName("listActive - Trả về danh sách vật tư đang hoạt động")
    void listActive_returnsRepositoryList() {
        when(materialRepository.findByIsActiveTrueOrderByMaterialNameAsc()).thenReturn(List.of(sampleMaterial));

        List<Material> active = materialService.listActive();

        assertEquals(1, active.size());
        assertEquals("VT-COMP-01", active.get(0).getMaterialCode());
    }

    @Test
    @DisplayName("getCategories - Gộp số liệu từ DB và bổ sung đủ nhóm mặc định")
    void getCategories_mergesDbCountsAndDefaults() {
        Object[] tramRangRow = new Object[]{"Trám răng", 5L, 4L};
        Object[] customRow = new Object[]{"Vật tư Labo", 2L, 1L};
        when(materialRepository.countMaterialsByCategory()).thenReturn(List.of(tramRangRow, customRow));

        List<MaterialCategoryDTO> categories = materialService.getCategories();

        assertTrue(categories.size() >= 9);
        MaterialCategoryDTO tramRang = categories.stream()
                .filter(c -> "Trám răng".equals(c.getCategoryName()))
                .findFirst().orElseThrow();
        assertEquals(5L, tramRang.getTotalMaterials());
        assertEquals(4L, tramRang.getActiveMaterials());

        MaterialCategoryDTO defaultUnset = categories.stream()
                .filter(c -> "Chỉnh nha".equals(c.getCategoryName()))
                .findFirst().orElseThrow();
        assertEquals(0L, defaultUnset.getTotalMaterials());
    }

    @Test
    @DisplayName("renameCategory - Đổi tên nhóm vật tư không phân biệt hoa thường và trả về số lượng cập nhật")
    void renameCategory_updatesMatchingMaterials() {
        Material m2 = new Material();
        m2.setMaterialId(11L);
        m2.setCategory("trám răng");
        Material m3 = new Material();
        m3.setMaterialId(12L);
        m3.setCategory("Chỉnh nha");
        when(materialRepository.findAll()).thenReturn(List.of(sampleMaterial, m2, m3));

        int updated = materialService.renameCategory("  Trám răng ", "  Phục hồi   thẩm mỹ ");

        assertEquals(2, updated);
        assertEquals("Phục hồi thẩm mỹ", sampleMaterial.getCategory());
        assertEquals("Phục hồi thẩm mỹ", m2.getCategory());
        assertEquals("Chỉnh nha", m3.getCategory());
        verify(materialRepository, times(2)).save(any(Material.class));
    }

    @Test
    @DisplayName("renameCategory - Báo lỗi khi tên nhóm cũ hoặc mới bị trống")
    void renameCategory_blankName_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> materialService.renameCategory(" ", "Mới"));
        assertThrows(IllegalArgumentException.class, () -> materialService.renameCategory("Cũ", "  "));
        verifyNoInteractions(materialRepository);
    }

    @Test
    @DisplayName("getById - Từ chối ID null/<=0 và không tìm thấy vật tư")
    void getById_invalidOrMissing_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> materialService.getById(null));
        assertThrows(IllegalArgumentException.class, () -> materialService.getById(0L));

        when(materialRepository.findById(999L)).thenReturn(Optional.empty());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> materialService.getById(999L));
        assertTrue(ex.getMessage().contains("Không tìm thấy"));
    }

    @Test
    @DisplayName("create - Từ chối DTO null, mã trống hoặc mã trùng lặp")
    void create_invalidOrDuplicate_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> materialService.create(null));

        MaterialCreateDTO blankCode = new MaterialCreateDTO();
        blankCode.setMaterialCode("  ");
        assertThrows(IllegalArgumentException.class, () -> materialService.create(blankCode));

        MaterialCreateDTO dupDto = validCreateDto();
        when(materialRepository.existsByMaterialCodeIgnoreCase("VT-NEW-01")).thenReturn(true);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> materialService.create(dupDto));
        assertTrue(ex.getMessage().contains("đã tồn tại"));
        verify(materialRepository, never()).save(any());
    }

    @Test
    @DisplayName("create - Từ chối tên/nhóm/đơn vị trống hoặc ngưỡng tồn tối thiểu âm")
    void create_missingFieldsOrNegativeThreshold_throwsException() {
        MaterialCreateDTO dto = validCreateDto();
        dto.setMinStockThreshold(new BigDecimal("-1.000"));
        when(materialRepository.existsByMaterialCodeIgnoreCase("VT-NEW-01")).thenReturn(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> materialService.create(dto));
        assertTrue(ex.getMessage().contains("không được âm"));

        MaterialCreateDTO blankName = validCreateDto();
        blankName.setMaterialName("   ");
        assertThrows(IllegalArgumentException.class, () -> materialService.create(blankName));
        verify(materialRepository, never()).save(any());
    }

    @Test
    @DisplayName("create - Chuẩn hóa mã in hoa, gộp khoảng trắng và mặc định ngưỡng tồn 10.000 khi null")
    void create_validDto_normalizesAndSaves() {
        MaterialCreateDTO dto = new MaterialCreateDTO();
        dto.setMaterialCode("  vt-new-02 ");
        dto.setMaterialName("  Găng tay   Nitrile  ");
        dto.setCategory(" Tiêu   hao ");
        dto.setUnit(" Hộp ");
        dto.setMinStockThreshold(null);

        when(materialRepository.existsByMaterialCodeIgnoreCase("VT-NEW-02")).thenReturn(false);
        when(materialRepository.save(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));

        Material saved = materialService.create(dto);

        assertEquals("VT-NEW-02", saved.getMaterialCode());
        assertEquals("Găng tay Nitrile", saved.getMaterialName());
        assertEquals("Tiêu hao", saved.getCategory());
        assertEquals("Hộp", saved.getUnit());
        assertEquals(new BigDecimal("10.000"), saved.getMinStockThreshold());
        assertTrue(saved.getIsActive());
    }

    @Test
    @DisplayName("update - Cho phép sửa nhóm vật tư và ngưỡng tồn của mặt hàng đồng bộ thuốc khi giữ nguyên mã/tên/ĐVT")
    void update_linkedMedication_allowsCategoryAndThresholdOnly() {
        Medication med = new Medication();
        med.setMedicationId(7L);
        sampleMaterial.setMaterialCode("AMOX500");
        sampleMaterial.setMaterialName("Amoxicillin 500mg");
        sampleMaterial.setUnit("Viên");
        sampleMaterial.setMedication(med);

        MaterialUpdateDTO upd = new MaterialUpdateDTO();
        upd.setMaterialCode("amox500");
        upd.setMaterialName("Amoxicillin 500mg");
        upd.setCategory("Thuốc kê đơn");
        upd.setUnit("Viên");
        upd.setMinStockThreshold(new BigDecimal("25"));

        when(materialRepository.findById(10L)).thenReturn(Optional.of(sampleMaterial));
        when(materialRepository.existsByMaterialCodeIgnoreCaseAndMaterialIdNot("AMOX500", 10L)).thenReturn(false);
        when(materialRepository.save(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));

        Material updated = materialService.update(10L, upd);

        assertEquals("Thuốc kê đơn", updated.getCategory());
        assertEquals(new BigDecimal("25.000"), updated.getMinStockThreshold());
    }

    @Test
    @DisplayName("update - Chặn đổi mã, tên hoặc đơn vị mặt hàng đồng bộ từ danh mục thuốc")
    void update_linkedMedicationChangingCodeOrUnit_rejected() {
        Medication med = new Medication();
        med.setMedicationId(7L);
        sampleMaterial.setMaterialCode("AMOX500");
        sampleMaterial.setMaterialName("Amoxicillin 500mg");
        sampleMaterial.setUnit("Viên");
        sampleMaterial.setMedication(med);

        MaterialUpdateDTO upd = new MaterialUpdateDTO();
        upd.setMaterialCode("AMOX500-EDIT");
        upd.setMaterialName("Amoxicillin 500mg");
        upd.setCategory("Thuốc");
        upd.setUnit("Viên");

        when(materialRepository.findById(10L)).thenReturn(Optional.of(sampleMaterial));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> materialService.update(10L, upd));
        assertTrue(ex.getMessage().contains("đồng bộ từ danh mục thuốc"));
        verify(materialRepository, never()).save(any());
    }

    @Test
    @DisplayName("update - Từ chối khi DTO null hoặc mã vật tư trùng với mặt hàng khác")
    void update_nullOrDuplicateCode_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> materialService.update(10L, null));

        MaterialUpdateDTO upd = new MaterialUpdateDTO();
        upd.setMaterialCode("VT-DUP");
        upd.setMaterialName("Composite");
        upd.setCategory("Trám răng");
        upd.setUnit("Ống");
        when(materialRepository.findById(10L)).thenReturn(Optional.of(sampleMaterial));
        when(materialRepository.existsByMaterialCodeIgnoreCaseAndMaterialIdNot("VT-DUP", 10L)).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> materialService.update(10L, upd));
        assertTrue(ex.getMessage().contains("đã được sử dụng"));
        verify(materialRepository, never()).save(any());
    }

    @Test
    @DisplayName("deactivate - Ngừng sử dụng vật tư đang hoạt động và chặn nếu đã ngừng")
    void deactivate_togglesOrRejectsAlreadyInactive() {
        when(materialRepository.findById(10L)).thenReturn(Optional.of(sampleMaterial));
        when(materialRepository.save(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));

        Material deactivated = materialService.deactivate(10L);
        assertFalse(deactivated.getIsActive());

        assertThrows(IllegalArgumentException.class, () -> materialService.deactivate(10L));
    }

    @Test
    @DisplayName("activate - Chặn kích hoạt nếu đang hoạt động hoặc thuốc gốc đã ngừng sử dụng")
    void activate_checksCurrentStatusAndLinkedMedicationStatus() {
        when(materialRepository.findById(10L)).thenReturn(Optional.of(sampleMaterial));

        // Đang active -> từ chối
        assertThrows(IllegalArgumentException.class, () -> materialService.activate(10L));

        // Ngưng hoạt động + thuốc gốc ngưng -> từ chối
        Medication inactiveMed = new Medication();
        inactiveMed.setMedicationId(8L);
        inactiveMed.setIsActive(false);
        sampleMaterial.setIsActive(false);
        sampleMaterial.setMedication(inactiveMed);

        IllegalArgumentException medEx = assertThrows(IllegalArgumentException.class,
                () -> materialService.activate(10L));
        assertTrue(medEx.getMessage().contains("Thuốc gốc đang ngừng sử dụng"));

        // Vật tư thường đang inactive -> kích hoạt thành công
        sampleMaterial.setMedication(null);
        when(materialRepository.save(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));
        Material activated = materialService.activate(10L);
        assertTrue(activated.getIsActive());
        ArgumentCaptor<Material> captor = ArgumentCaptor.forClass(Material.class);
        verify(materialRepository).save(captor.capture());
        assertTrue(captor.getValue().getIsActive());
    }

    private MaterialCreateDTO validCreateDto() {
        MaterialCreateDTO dto = new MaterialCreateDTO();
        dto.setMaterialCode("vt-new-01");
        dto.setMaterialName("Composite Z350");
        dto.setCategory("Trám răng");
        dto.setUnit("Ống");
        dto.setMinStockThreshold(new BigDecimal("5.000"));
        return dto;
    }
}
