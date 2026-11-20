package com.cvdcms.service;

import com.cvdcms.DTO.InventoryItemDTO;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.BranchInventory;
import com.cvdcms.entity.Material;
import com.cvdcms.repository.BranchInventoryRepository;
import com.cvdcms.service.impl.BranchInventoryServiceImpl;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BranchInventoryServiceTest {

    @Mock
    private BranchInventoryRepository inventoryRepository;

    private BranchInventoryServiceImpl inventoryService;

    @BeforeEach
    void setUp() {
        inventoryService = new BranchInventoryServiceImpl(inventoryRepository);
    }

    @Test
    @DisplayName("search - Báo lỗi khi branchId null hoặc <= 0")
    void search_invalidBranchId_throwsException() {
        Pageable pageable = PageRequest.of(0, 10);

        IllegalArgumentException exNull = assertThrows(IllegalArgumentException.class,
                () -> inventoryService.search(null, "comp", false, pageable));
        assertTrue(exNull.getMessage().contains("Chi nhánh"));

        assertThrows(IllegalArgumentException.class,
                () -> inventoryService.search(0L, "comp", false, pageable));
        verifyNoInteractions(inventoryRepository);
    }

    @Test
    @DisplayName("search - Báo lỗi khi pageable null")
    void search_nullPageable_throwsException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> inventoryService.search(1L, "comp", false, null));
        assertTrue(ex.getMessage().contains("phân trang"));
        verifyNoInteractions(inventoryRepository);
    }

    @Test
    @DisplayName("search - Chuẩn hóa keyword rỗng/khoảng trắng thành null trước khi truy vấn")
    void search_blankKeyword_normalizedToNull() {
        Pageable pageable = PageRequest.of(0, 10);
        when(inventoryRepository.searchByBranch(1L, null, Boolean.TRUE, pageable))
                .thenReturn(Page.empty(pageable));

        Page<InventoryItemDTO> result = inventoryService.search(1L, "   ", Boolean.TRUE, pageable);

        assertNotNull(result);
        assertEquals(0, result.getTotalElements());
        verify(inventoryRepository).searchByBranch(1L, null, Boolean.TRUE, pageable);
    }

    @Test
    @DisplayName("search - Trim keyword và ánh xạ đầy đủ BranchInventory sang InventoryItemDTO")
    void search_validParams_mapsEntityToDto() {
        Pageable pageable = PageRequest.of(0, 10);
        Branch branch = new Branch();
        branch.setBranchId(1L);
        branch.setBranchName("Cơ sở Quận 1");

        Material material = new Material();
        material.setMaterialId(100L);
        material.setMaterialCode("VT-01");
        material.setMaterialName("Composite 3M");
        material.setCategory("Trám răng");
        material.setUnit("Ống");
        material.setMinStockThreshold(new BigDecimal("5.000"));

        LocalDateTime updatedAt = LocalDateTime.of(2026, 11, 10, 14, 30);
        BranchInventory entity = new BranchInventory();
        entity.setInventoryId(55L);
        entity.setBranch(branch);
        entity.setMaterial(material);
        entity.setCurrentStock(new BigDecimal("18.500"));
        entity.setLastUpdated(updatedAt);

        when(inventoryRepository.searchByBranch(1L, "composite", null, pageable))
                .thenReturn(new PageImpl<>(List.of(entity), pageable, 1));

        Page<InventoryItemDTO> page = inventoryService.search(1L, "  composite  ", null, pageable);

        assertEquals(1, page.getTotalElements());
        InventoryItemDTO dto = page.getContent().get(0);
        assertEquals(55L, dto.getInventoryId());
        assertEquals(1L, dto.getBranchId());
        assertEquals("Cơ sở Quận 1", dto.getBranchName());
        assertEquals(100L, dto.getMaterialId());
        assertEquals("VT-01", dto.getMaterialCode());
        assertEquals("Composite 3M", dto.getMaterialName());
        assertEquals("Trám răng", dto.getCategory());
        assertEquals("Ống", dto.getUnit());
        assertEquals(new BigDecimal("18.500"), dto.getCurrentStock());
        assertEquals(new BigDecimal("5.000"), dto.getMinStockThreshold());
        assertEquals(updatedAt, dto.getLastUpdated());
    }
}
