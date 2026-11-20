package com.cvdcms.controller;

import com.cvdcms.DTO.InventoryItemDTO;
import com.cvdcms.DTO.SupplierDebtSummaryDTO;
import com.cvdcms.entity.BranchInventory;
import com.cvdcms.entity.Material;
import com.cvdcms.entity.MaterialBatch;
import com.cvdcms.entity.User;
import com.cvdcms.repository.BranchInventoryRepository;
import com.cvdcms.repository.MaterialBatchRepository;
import com.cvdcms.repository.MaterialRepository;
import com.cvdcms.service.BranchAccessService;
import com.cvdcms.service.BranchInventoryService;
import com.cvdcms.service.SupplierPaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BranchInventoryControllerTest {

    @Mock
    private BranchInventoryService inventoryService;

    @Mock
    private BranchAccessService branchAccessService;

    @Mock
    private BranchInventoryRepository inventoryRepository;

    @Mock
    private MaterialBatchRepository batchRepository;

    @Mock
    private SupplierPaymentService supplierPaymentService;

    @Mock
    private MaterialRepository materialRepository;

    private BranchInventoryController controller;
    private MockHttpSession session;
    private User loggedInUser;

    @BeforeEach
    void setUp() {
        controller = new BranchInventoryController(
                inventoryService,
                branchAccessService,
                inventoryRepository,
                batchRepository,
                supplierPaymentService
        );
        ReflectionTestUtils.setField(controller, "materialRepository", materialRepository);

        loggedInUser = new User();
        loggedInUser.setUserId(10L);
        loggedInUser.setUsername("warehouse");

        session = new MockHttpSession();
        session.setAttribute("loggedInUser", loggedInUser);
    }

    @Test
    void getInventory_ThrowsUnauthorized_WhenSessionHasNoUser() {
        MockHttpSession emptySession = new MockHttpSession();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getInventory(1L, null, null, 0, 10, "lastUpdated", "desc", emptySession));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
        verifyNoInteractions(inventoryService);
    }

    @Test
    void getInventory_ThrowsForbidden_WhenUserCannotAccessBranch() {
        when(branchAccessService.canAccessBranch(10L, 2L)).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getInventory(2L, null, null, 0, 10, "lastUpdated", "desc", session));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void getInventory_ThrowsIllegalArgument_WhenPageSizeSortOrDirectionInvalid() {
        when(branchAccessService.canAccessBranch(10L, 1L)).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> controller.getInventory(1L, null, null, -1, 10, "lastUpdated", "desc", session));
        assertThrows(IllegalArgumentException.class,
                () -> controller.getInventory(1L, null, null, 0, 0, "lastUpdated", "desc", session));
        assertThrows(IllegalArgumentException.class,
                () -> controller.getInventory(1L, null, null, 0, 101, "lastUpdated", "desc", session));
        assertThrows(IllegalArgumentException.class,
                () -> controller.getInventory(1L, null, null, 0, 10, "materialName", "desc", session));
        assertThrows(IllegalArgumentException.class,
                () -> controller.getInventory(1L, null, null, 0, 10, "lastUpdated", "invalid", session));
    }

    @Test
    void getInventory_DelegatesToServiceWithNormalizedSort() {
        when(branchAccessService.canAccessBranch(10L, 1L)).thenReturn(true);
        Page<InventoryItemDTO> expectedPage = new PageImpl<>(List.of());
        when(inventoryService.search(eq(1L), eq("composite"), eq(true), any(Pageable.class))).thenReturn(expectedPage);

        Page<InventoryItemDTO> result = controller.getInventory(
                1L, "composite", true, 1, 20, " currentStock ", "ASC", session);

        assertSame(expectedPage, result);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(inventoryService).search(eq(1L), eq("composite"), eq(true), pageableCaptor.capture());
        Pageable captured = pageableCaptor.getValue();
        assertEquals(1, captured.getPageNumber());
        assertEquals(20, captured.getPageSize());
        assertEquals(Sort.Direction.ASC, captured.getSort().getOrderFor("currentStock").getDirection());
    }

    @Test
    void getPickerMaterials_ThrowsBadRequest_WhenBranchIdInvalid() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.getPickerMaterials(0L, null, null, "ALL", 0, 20, session));
        assertThrows(IllegalArgumentException.class,
                () -> controller.getPickerMaterials(null, null, null, "ALL", 0, 20, session));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getPickerMaterials_FiltersByStockModeCategoryKeywordAndCalculatesExpiry() {
        when(branchAccessService.canAccessBranch(10L, 1L)).thenReturn(true);

        Material mat1 = new Material();
        mat1.setMaterialId(1L);
        mat1.setMaterialCode("VT01");
        mat1.setMaterialName("Composite A2");
        mat1.setCategory("Vật liệu trám");
        mat1.setUnit("Ống");
        mat1.setMinStockThreshold(new BigDecimal("10"));

        Material mat2 = new Material();
        mat2.setMaterialId(2L);
        mat2.setMaterialCode("VT02");
        mat2.setMaterialName("Thuốc tê Lidocaine");
        mat2.setCategory("Thuốc");
        mat2.setUnit("Hộp");
        mat2.setMinStockThreshold(new BigDecimal("5"));

        when(materialRepository.findByIsActiveTrueOrderByMaterialNameAsc()).thenReturn(List.of(mat1, mat2));

        BranchInventory inv1 = new BranchInventory();
        inv1.setMaterial(mat1);
        inv1.setCurrentStock(new BigDecimal("15"));
        BranchInventory inv2 = new BranchInventory();
        inv2.setMaterial(mat2);
        inv2.setCurrentStock(new BigDecimal("3"));
        when(inventoryRepository.findAllActiveByBranch(1L)).thenReturn(List.of(inv1, inv2));

        MaterialBatch expiredBatch = new MaterialBatch();
        expiredBatch.setMaterial(mat1);
        expiredBatch.setQuantityRemaining(new BigDecimal("4"));
        expiredBatch.setExpiryDate(LocalDate.now().minusDays(2));

        MaterialBatch validBatchLater = new MaterialBatch();
        validBatchLater.setMaterial(mat1);
        validBatchLater.setQuantityRemaining(new BigDecimal("6"));
        validBatchLater.setExpiryDate(LocalDate.now().plusDays(60));

        MaterialBatch validBatchEarlier = new MaterialBatch();
        validBatchEarlier.setMaterial(mat1);
        validBatchEarlier.setQuantityRemaining(new BigDecimal("5"));
        validBatchEarlier.setExpiryDate(LocalDate.now().plusDays(20));

        when(batchRepository.findBatchesWithStockByBranch(1L, null))
                .thenReturn(List.of(expiredBatch, validBatchLater, validBatchEarlier));

        Map<String, Object> result = controller.getPickerMaterials(
                1L, "composite", "Vật liệu trám", "AVAILABLE_STOCK", 0, 20, session);

        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertEquals(1, content.size());
        Map<String, Object> item = content.get(0);
        assertEquals(1L, item.get("materialId"));
        assertEquals(new BigDecimal("15"), item.get("currentStock"));
        assertEquals(new BigDecimal("11"), item.get("availableStock"));
        assertEquals(new BigDecimal("4"), item.get("expiredStock"));
        assertEquals(LocalDate.now().plusDays(20), item.get("nearestExpiryDate"));
        assertEquals(2, item.get("activeBatchCount"));
        assertEquals(1, item.get("expiredBatchCount"));
        assertEquals(1, result.get("totalElements"));
        assertFalse((Boolean) result.get("hasMore"));
        assertEquals(List.of("Thuốc", "Vật liệu trám"), result.get("categories"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getPickerMaterials_SupportsLowStockAndExpiredOnlyFiltersAndPagination() {
        when(branchAccessService.canAccessBranch(10L, 1L)).thenReturn(true);

        Material matLow = new Material();
        matLow.setMaterialId(1L);
        matLow.setMaterialCode("VT01");
        matLow.setMaterialName("Găng tay");
        matLow.setMinStockThreshold(new BigDecimal("10"));

        Material matNormal = new Material();
        matNormal.setMaterialId(2L);
        matNormal.setMaterialCode("VT02");
        matNormal.setMaterialName("Khẩu trang");
        matNormal.setMinStockThreshold(new BigDecimal("5"));

        when(materialRepository.findByIsActiveTrueOrderByMaterialNameAsc()).thenReturn(List.of(matLow, matNormal));

        BranchInventory invLow = new BranchInventory();
        invLow.setMaterial(matLow);
        invLow.setCurrentStock(new BigDecimal("8"));
        BranchInventory invNormal = new BranchInventory();
        invNormal.setMaterial(matNormal);
        invNormal.setCurrentStock(new BigDecimal("50"));
        when(inventoryRepository.findAllActiveByBranch(1L)).thenReturn(List.of(invLow, invNormal));
        when(batchRepository.findBatchesWithStockByBranch(1L, null)).thenReturn(List.of());

        Map<String, Object> lowStockResult = controller.getPickerMaterials(
                1L, null, "ALL", "LOW_STOCK", 0, 1, session);

        List<Map<String, Object>> lowItems = (List<Map<String, Object>>) lowStockResult.get("content");
        assertEquals(1, lowItems.size());
        assertEquals("VT01", lowItems.get(0).get("materialCode"));
    }

    @Test
    void getInventorySummary_AggregatesInventoryBatchAndSupplierDebtMetrics() {
        when(branchAccessService.canAccessBranch(10L, 1L)).thenReturn(true);
        when(inventoryRepository.countByBranch_BranchId(1L)).thenReturn(25L);
        when(inventoryRepository.countLowStockByBranch(1L)).thenReturn(4L);
        when(inventoryRepository.countOutOfStockByBranch(1L)).thenReturn(2L);
        when(batchRepository.countExpiredBatchesWithStock(eq(1L), any(LocalDate.class))).thenReturn(1L);
        when(batchRepository.countExpiringSoonBatchesWithStock(eq(1L), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(3L);

        SupplierDebtSummaryDTO d1 = new SupplierDebtSummaryDTO();
        d1.setRemainingDebt(new BigDecimal("1200000"));
        SupplierDebtSummaryDTO d2 = new SupplierDebtSummaryDTO();
        d2.setRemainingDebt(BigDecimal.ZERO);
        SupplierDebtSummaryDTO d3 = new SupplierDebtSummaryDTO();
        d3.setRemainingDebt(new BigDecimal("800000"));
        when(supplierPaymentService.getDebtSummaries(1L, null, 10L)).thenReturn(List.of(d1, d2, d3));

        Map<String, Object> summary = controller.getInventorySummary(1L, session);

        assertEquals(1L, summary.get("branchId"));
        assertEquals(25L, summary.get("totalItems"));
        assertEquals(4L, summary.get("lowStockItems"));
        assertEquals(2L, summary.get("outOfStockItems"));
        assertEquals(4L, summary.get("expiringOrExpiredBatches"));
        assertEquals(1L, summary.get("expiredBatches"));
        assertEquals(3L, summary.get("expiringSoonBatches"));
        assertEquals(new BigDecimal("2000000"), summary.get("totalSupplierDebt"));
        assertEquals(2L, summary.get("suppliersWithDebtCount"));
    }

    @Test
    void exceptionHandlers_FormatJsonErrorResponses() {
        ResponseEntity<Map<String, String>> badReq = controller.handleBadRequest(new IllegalArgumentException("Tham số sai"));
        assertEquals(HttpStatus.BAD_REQUEST, badReq.getStatusCode());
        assertEquals("Tham số sai", badReq.getBody().get("message"));

        ResponseEntity<Map<String, String>> forbidden = controller.handleResponseStatus(
                new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền"));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
        assertEquals("Không có quyền", forbidden.getBody().get("message"));
    }
}
