package com.cvdcms.service;

import com.cvdcms.DTO.MaterialBatchDTO;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.Material;
import com.cvdcms.entity.MaterialBatch;
import com.cvdcms.entity.Supplier;
import com.cvdcms.entity.enums.BatchStatus;
import com.cvdcms.repository.MaterialBatchRepository;
import com.cvdcms.service.impl.MaterialBatchServiceImpl;
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
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MaterialBatchServiceTest {

    @Mock
    private MaterialBatchRepository batchRepository;

    private MaterialBatchServiceImpl batchService;

    private Branch branch;
    private Material material;
    private Supplier supplier;

    @BeforeEach
    void setUp() {
        batchService = new MaterialBatchServiceImpl(batchRepository);

        branch = new Branch();
        branch.setBranchId(1L);
        branch.setBranchName("Cơ sở Quận 1");

        material = new Material();
        material.setMaterialId(100L);
        material.setMaterialCode("VT-01");
        material.setMaterialName("Thuốc tê Lidocaine");
        material.setUnit("Ống");

        supplier = new Supplier();
        supplier.setSupplierId(10L);
        supplier.setSupplierName("NCC Dược Phẩm");
    }

    @Test
    @DisplayName("searchBatches - Reject when branchId is null or <= 0")
    void searchBatches_invalidBranchId_throwsException() {
        Pageable pageable = PageRequest.of(0, 10);
        assertThrows(IllegalArgumentException.class,
                () -> batchService.searchBatches(null, null, null, null, pageable));
        assertThrows(IllegalArgumentException.class,
                () -> batchService.searchBatches(0L, null, null, null, pageable));
        verifyNoInteractions(batchRepository);
    }

    @Test
    @DisplayName("searchBatches - Reject when expiryFrom is after expiryTo")
    void searchBatches_invalidDateRange_throwsException() {
        Pageable pageable = PageRequest.of(0, 10);
        LocalDate from = LocalDate.now().plusDays(10);
        LocalDate to = LocalDate.now().plusDays(5);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> batchService.searchBatches(1L, "LO", from, to, pageable));
        assertTrue(ex.getMessage().contains("Ngày bắt đầu"));
        verifyNoInteractions(batchRepository);
    }

    @Test
    @DisplayName("searchBatches - Accurately classify EXPIRED, EXPIRING_SOON (<=30 days), and NONE (>30 days) warnings")
    void searchBatches_classifiesExpiryWarningsAndNullSupplier() {
        LocalDate today = LocalDate.now();
        Pageable pageable = PageRequest.of(0, 10);

        MaterialBatch expired = buildBatch(1L, "LO-EXP", today.minusDays(1), BatchStatus.EXPIRED, supplier);
        MaterialBatch boundary30 = buildBatch(2L, "LO-SOON-30", today.plusDays(30), BatchStatus.ACTIVE, null);
        MaterialBatch boundaryToday = buildBatch(3L, "LO-TODAY", today, BatchStatus.ACTIVE, supplier);
        MaterialBatch safe31 = buildBatch(4L, "LO-SAFE-31", today.plusDays(31), BatchStatus.ACTIVE, supplier);

        when(batchRepository.searchBatches(1L, null, null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(expired, boundary30, boundaryToday, safe31), pageable, 4));

        Page<MaterialBatchDTO> page = batchService.searchBatches(1L, "   ", null, null, pageable);

        assertEquals(4, page.getTotalElements());
        assertEquals("EXPIRED", page.getContent().get(0).getExpiryWarning());
        assertEquals(-1L, page.getContent().get(0).getDaysToExpiry());

        assertEquals("EXPIRING_SOON", page.getContent().get(1).getExpiryWarning());
        assertNull(page.getContent().get(1).getSupplierId());
        assertNull(page.getContent().get(1).getSupplierName());

        assertEquals("EXPIRING_SOON", page.getContent().get(2).getExpiryWarning());
        assertEquals(0L, page.getContent().get(2).getDaysToExpiry());

        assertEquals("NONE", page.getContent().get(3).getExpiryWarning());
        assertEquals(31L, page.getContent().get(3).getDaysToExpiry());
    }

    @Test
    @DisplayName("searchBatches - Trim valid keyword before querying")
    void searchBatches_trimsKeyword() {
        Pageable pageable = PageRequest.of(0, 10);
        when(batchRepository.searchBatches(1L, "LO-01", null, null, pageable))
                .thenReturn(Page.empty(pageable));

        batchService.searchBatches(1L, "  LO-01 ", null, null, pageable);

        verify(batchRepository).searchBatches(1L, "LO-01", null, null, pageable);
    }

    @Test
    @DisplayName("getBatchesWithStock - Reject when branchId is invalid")
    void getBatchesWithStock_invalidBranchId_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> batchService.getBatchesWithStock(null, 100L));
        assertThrows(IllegalArgumentException.class, () -> batchService.getBatchesWithStock(-1L, 100L));
        verifyNoInteractions(batchRepository);
    }

    @Test
    @DisplayName("getBatchesWithStock - Return DTO list with remaining stock by branch and material")
    void getBatchesWithStock_returnsMappedDtos() {
        MaterialBatch b = buildBatch(5L, "LO-STOCK", LocalDate.now().plusDays(15), BatchStatus.ACTIVE, supplier);
        when(batchRepository.findBatchesWithStockByBranch(1L, 100L)).thenReturn(List.of(b));

        List<MaterialBatchDTO> list = batchService.getBatchesWithStock(1L, 100L);

        assertEquals(1, list.size());
        MaterialBatchDTO dto = list.get(0);
        assertEquals(5L, dto.getBatchId());
        assertEquals("LO-STOCK", dto.getBatchNumber());
        assertEquals("EXPIRING_SOON", dto.getExpiryWarning());
        assertEquals("NCC Dược Phẩm", dto.getSupplierName());
        assertEquals("ACTIVE", dto.getStatus());
    }

    private MaterialBatch buildBatch(Long id, String batchNo, LocalDate expiry, BatchStatus status, Supplier sup) {
        MaterialBatch b = new MaterialBatch();
        b.setBatchId(id);
        b.setBranch(branch);
        b.setMaterial(material);
        b.setBatchNumber(batchNo);
        b.setExpiryDate(expiry);
        b.setQuantityRemaining(new BigDecimal("12.000"));
        b.setImportPrice(new BigDecimal("50000.00"));
        b.setSupplier(sup);
        b.setStatus(status);
        return b;
    }
}
