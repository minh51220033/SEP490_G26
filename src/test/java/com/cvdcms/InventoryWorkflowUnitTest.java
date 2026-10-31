package com.cvdcms;

import com.cvdcms.DTO.*;
import com.cvdcms.controller.BranchInventoryController;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.BatchStatus;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.repository.*;
import com.cvdcms.service.BranchAccessService;
import com.cvdcms.service.BranchInventoryService;
import com.cvdcms.service.impl.StockIssueServiceImpl;
import com.cvdcms.service.impl.StockReceiptServiceImpl;
import com.cvdcms.service.impl.SupplierPaymentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryWorkflowUnitTest {

    @Mock private BranchRepository branchRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private StockReceiptRepository receiptRepository;
    @Mock private MaterialBatchRepository batchRepository;
    @Mock private BranchInventoryRepository inventoryRepository;
    @Mock private StaffProfileRepository staffRepository;
    @Mock private UserRepository userRepository;
    @Mock private SupplierPaymentRepository supplierPaymentRepository;
    @Mock private StockIssueRepository issueRepository;
    @Mock private BranchAccessService accessService;

    private StockReceiptServiceImpl receiptService;
    private StockIssueServiceImpl issueService;
    private SupplierPaymentServiceImpl paymentService;

    private Branch branch1;
    private Branch branch2;
    private Supplier supplier;
    private Material material;
    private StaffProfile staff;

    @BeforeEach
    void setUp() {
        receiptService = new StockReceiptServiceImpl(
                branchRepository, supplierRepository, materialRepository,
                receiptRepository, batchRepository, inventoryRepository,
                staffRepository, userRepository, supplierPaymentRepository, accessService
        );
        issueService = new StockIssueServiceImpl(
                branchRepository, materialRepository, batchRepository,
                inventoryRepository, issueRepository, staffRepository,
                userRepository, accessService
        );
        paymentService = new SupplierPaymentServiceImpl(
                supplierRepository, branchRepository, receiptRepository,
                supplierPaymentRepository, staffRepository, userRepository, accessService
        );

        branch1 = new Branch();
        branch1.setBranchId(1L);
        branch1.setBranchCode("CN01");
        branch1.setBranchName("Cơ sở Quận 1");
        branch1.setIsActive(true);

        branch2 = new Branch();
        branch2.setBranchId(2L);
        branch2.setBranchCode("CN02");
        branch2.setBranchName("Cơ sở Đống Đa");
        branch2.setIsActive(true);

        supplier = new Supplier();
        supplier.setSupplierId(10L);
        supplier.setSupplierCode("NCC-3M");
        supplier.setSupplierName("Công ty 3M");
        supplier.setIsActive(true);

        material = new Material();
        material.setMaterialId(100L);
        material.setMaterialCode("VT-COMP-01");
        material.setMaterialName("Composite 3M");
        material.setCategory("Trám răng");
        material.setUnit("Ống");
        material.setIsActive(true);

        staff = new StaffProfile();
        staff.setStaffId(5L);
        staff.setStaffCode("NV001");
        staff.setFullName("Nguyễn Văn Kho");
        staff.setStatus(StaffStatus.ACTIVE);
    }

    @Test
    void testReceiptNewBatch_UpdatesInventoryAndBatchBy10() {
        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch1));
        when(supplierRepository.findById(10L)).thenReturn(Optional.of(supplier));
        when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(staff));
        when(materialRepository.findById(100L)).thenReturn(Optional.of(material));
        when(batchRepository.findForUpdate(1L, 100L, "LO-01")).thenReturn(Optional.empty());
        when(inventoryRepository.findForUpdate(1L, 100L)).thenReturn(Optional.empty());
        when(batchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inventoryRepository.save(any(BranchInventory.class))).thenAnswer(inv -> inv.getArgument(0));
        when(receiptRepository.save(any(StockReceipt.class))).thenAnswer(inv -> {
            StockReceipt r = inv.getArgument(0);
            r.setReceiptId(99L);
            r.setReceiptCode("PN001");
            return r;
        });

        StockReceiptCreateDTO dto = new StockReceiptCreateDTO();
        dto.setBranchId(1L);
        dto.setSupplierId(10L);
        dto.setReceiptDate(LocalDate.now());
        StockReceiptCreateDTO.Item item = new StockReceiptCreateDTO.Item();
        item.setMaterialId(100L);
        item.setBatchNumber("LO-01");
        item.setExpiryDate(LocalDate.now().plusMonths(12));
        item.setQuantity(new BigDecimal("10.000"));
        item.setUnitPrice(new BigDecimal("250000.00"));
        dto.setItems(List.of(item));

        StockReceiptResponseDTO response = receiptService.create(dto, 1L);

        assertNotNull(response);
        assertEquals(new BigDecimal("2500000.00"), response.getTotalAmount());
        verify(batchRepository).save(argThat(b ->
                b.getQuantityRemaining().compareTo(new BigDecimal("10.000")) == 0
                        && b.getStatus() == BatchStatus.ACTIVE
        ));
        verify(inventoryRepository).save(argThat(inv ->
                inv.getCurrentStock().compareTo(new BigDecimal("10.000")) == 0
        ));
    }

    @Test
    void testReceiptExistingBatch_RejectsMismatchedExpiryDate() {
        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch1));
        when(supplierRepository.findById(10L)).thenReturn(Optional.of(supplier));
        when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(staff));
        when(materialRepository.findById(100L)).thenReturn(Optional.of(material));

        MaterialBatch existingBatch = new MaterialBatch();
        existingBatch.setBranch(branch1);
        existingBatch.setMaterial(material);
        existingBatch.setBatchNumber("LO-01");
        existingBatch.setExpiryDate(LocalDate.now().plusMonths(6));
        existingBatch.setQuantityRemaining(new BigDecimal("5.000"));
        when(batchRepository.findForUpdate(1L, 100L, "LO-01")).thenReturn(Optional.of(existingBatch));

        StockReceiptCreateDTO dto = new StockReceiptCreateDTO();
        dto.setBranchId(1L);
        dto.setSupplierId(10L);
        dto.setReceiptDate(LocalDate.now());
        StockReceiptCreateDTO.Item item = new StockReceiptCreateDTO.Item();
        item.setMaterialId(100L);
        item.setBatchNumber("LO-01");
        item.setExpiryDate(LocalDate.now().plusMonths(12)); // Lệch hạn dùng so với lô đã có
        item.setQuantity(new BigDecimal("5.000"));
        item.setUnitPrice(new BigDecimal("250000.00"));
        dto.setItems(List.of(item));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> receiptService.create(dto, 1L));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void testIssueExceedingAvailableStock_Rejected() {
        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch1));
        when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(staff));
        when(materialRepository.findById(100L)).thenReturn(Optional.of(material));

        BranchInventory inv = new BranchInventory();
        inv.setBranch(branch1);
        inv.setMaterial(material);
        inv.setCurrentStock(new BigDecimal("3.000")); // Chỉ còn 3
        when(inventoryRepository.findForUpdate(1L, 100L)).thenReturn(Optional.of(inv));

        StockIssueCreateDTO dto = new StockIssueCreateDTO();
        dto.setBranchId(1L);
        dto.setIssueType("INTERNAL_USE");
        dto.setIssueDate(LocalDate.now());
        StockIssueCreateDTO.Item item = new StockIssueCreateDTO.Item();
        item.setMaterialId(100L);
        item.setQuantity(new BigDecimal("5.000")); // Yêu cầu xuất 5
        dto.setItems(List.of(item));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> issueService.create(dto, 1L));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals(new BigDecimal("3.000"), inv.getCurrentStock());
        verify(issueRepository, never()).save(any());
    }

    @Test
    void testTransferBetweenBranches_DeductsSourceAndCreditsDestination() {
        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch1));
        when(branchRepository.findById(2L)).thenReturn(Optional.of(branch2));
        when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(staff));
        when(materialRepository.findById(100L)).thenReturn(Optional.of(material));

        BranchInventory sourceInv = new BranchInventory();
        sourceInv.setBranch(branch1);
        sourceInv.setMaterial(material);
        sourceInv.setCurrentStock(new BigDecimal("20.000"));
        when(inventoryRepository.findForUpdate(1L, 100L)).thenReturn(Optional.of(sourceInv));

        MaterialBatch sourceBatch = new MaterialBatch();
        sourceBatch.setBatchId(50L);
        sourceBatch.setBranch(branch1);
        sourceBatch.setMaterial(material);
        sourceBatch.setBatchNumber("LO-CHUYEN-01");
        sourceBatch.setExpiryDate(LocalDate.now().plusMonths(8));
        sourceBatch.setQuantityRemaining(new BigDecimal("20.000"));
        sourceBatch.setImportPrice(new BigDecimal("100000.00"));
        sourceBatch.setSupplier(supplier);
        sourceBatch.setStatus(BatchStatus.ACTIVE);

        when(batchRepository.findAvailableBatchesFefoForUpdate(eq(1L), eq(100L), any(LocalDate.class)))
                .thenReturn(List.of(sourceBatch));
        when(batchRepository.findForUpdate(2L, 100L, "LO-CHUYEN-01")).thenReturn(Optional.empty());
        when(inventoryRepository.findForUpdate(2L, 100L)).thenReturn(Optional.empty());
        when(issueRepository.save(any(StockIssue.class))).thenAnswer(i -> i.getArgument(0));

        StockIssueCreateDTO dto = new StockIssueCreateDTO();
        dto.setBranchId(1L);
        dto.setIssueType("TRANSFER");
        dto.setDestinationBranchId(2L);
        dto.setIssueDate(LocalDate.now());
        StockIssueCreateDTO.Item item = new StockIssueCreateDTO.Item();
        item.setMaterialId(100L);
        item.setQuantity(new BigDecimal("8.000"));
        dto.setItems(List.of(item));

        StockIssueResponseDTO res = issueService.create(dto, 1L);

        assertNotNull(res);
        // Nguồn giảm từ 20 xuống 12
        assertEquals(new BigDecimal("12.000"), sourceBatch.getQuantityRemaining());
        assertEquals(new BigDecimal("12.000"), sourceInv.getCurrentStock());
        // Đích tăng 8
        verify(inventoryRepository).save(argThat(bi ->
                bi.getBranch().getBranchId().equals(2L)
                        && bi.getCurrentStock().compareTo(new BigDecimal("8.000")) == 0
        ));
    }

    @Test
    void testUnauthorizedBranchAccess_ThrowsForbidden() {
        when(accessService.canAccessBranch(99L, 1L)).thenReturn(false);

        StockIssueCreateDTO dto = new StockIssueCreateDTO();
        dto.setBranchId(1L);
        dto.setIssueType("INTERNAL_USE");
        dto.setItems(List.of(new StockIssueCreateDTO.Item()));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> issueService.create(dto, 99L));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void testSupplierPaymentExceedingRemainingDebt_Rejected() {
        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch1));
        when(supplierRepository.findById(10L)).thenReturn(Optional.of(supplier));
        when(receiptRepository.sumTotalAmountByBranchAndSupplier(1L, 10L))
                .thenReturn(new BigDecimal("1000000.00"));
        when(supplierPaymentRepository.sumPaidAmountByBranchAndSupplier(1L, 10L))
                .thenReturn(new BigDecimal("700000.00")); // Còn nợ 300.000

        SupplierPaymentCreateDTO dto = new SupplierPaymentCreateDTO();
        dto.setBranchId(1L);
        dto.setSupplierId(10L);
        dto.setPaymentDate(LocalDate.now());
        dto.setPaymentMethod("BANK_TRANSFER");
        dto.setAmount(new BigDecimal("500000.00")); // Trả 500.000 > 300.000

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> paymentService.createPayment(dto, 1L));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(supplierPaymentRepository, never()).save(any());
    }

    /**
     * Kiểm thử xuất kho nhiều dòng cùng 1 vật tư trong 1 phiếu:
     * Đảm bảo bộ nhớ đệm (in-transaction cache) cộng dồn số lượng trừ tồn kho chính xác.
     */
    @Test
    void testMultiRowIssueSameMaterial_AccumulatesDeductionCorrectly() {
        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch1));
        when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(staff));
        when(materialRepository.findById(100L)).thenReturn(Optional.of(material));

        BranchInventory inv = new BranchInventory();
        inv.setBranch(branch1);
        inv.setMaterial(material);
        inv.setCurrentStock(new BigDecimal("10.000"));
        when(inventoryRepository.findForUpdate(1L, 100L)).thenReturn(Optional.of(inv));

        MaterialBatch batch1 = new MaterialBatch();
        batch1.setBatchId(101L);
        batch1.setBranch(branch1);
        batch1.setMaterial(material);
        batch1.setBatchNumber("LO-A");
        batch1.setExpiryDate(LocalDate.now().plusMonths(3));
        batch1.setQuantityRemaining(new BigDecimal("6.000"));
        batch1.setImportPrice(new BigDecimal("50000.00"));
        batch1.setStatus(BatchStatus.ACTIVE);

        MaterialBatch batch2 = new MaterialBatch();
        batch2.setBatchId(102L);
        batch2.setBranch(branch1);
        batch2.setMaterial(material);
        batch2.setBatchNumber("LO-B");
        batch2.setExpiryDate(LocalDate.now().plusMonths(6));
        batch2.setQuantityRemaining(new BigDecimal("4.000"));
        batch2.setImportPrice(new BigDecimal("55000.00"));
        batch2.setStatus(BatchStatus.ACTIVE);

        when(batchRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(batch1));
        when(batchRepository.findByIdForUpdate(102L)).thenReturn(Optional.of(batch2));
        when(issueRepository.save(any(StockIssue.class))).thenAnswer(i -> i.getArgument(0));

        StockIssueCreateDTO dto = new StockIssueCreateDTO();
        dto.setBranchId(1L);
        dto.setIssueType("INTERNAL_USE");
        dto.setIssueDate(LocalDate.now());

        StockIssueCreateDTO.Item row1 = new StockIssueCreateDTO.Item();
        row1.setMaterialId(100L);
        row1.setBatchId(101L);
        row1.setQuantity(new BigDecimal("4.000"));

        StockIssueCreateDTO.Item row2 = new StockIssueCreateDTO.Item();
        row2.setMaterialId(100L);
        row2.setBatchId(102L);
        row2.setQuantity(new BigDecimal("3.000"));

        dto.setItems(List.of(row1, row2));

        StockIssueResponseDTO res = issueService.create(dto, 1L);
        assertNotNull(res);
        assertEquals(new BigDecimal("3.000"), inv.getCurrentStock());
        assertEquals(new BigDecimal("2.000"), batch1.getQuantityRemaining());
        assertEquals(new BigDecimal("1.000"), batch2.getQuantityRemaining());
        verify(inventoryRepository, times(1)).findForUpdate(1L, 100L);
    }

    /**
     * Kiểm thử kiểm tra định dạng Số lô (regex) khi tạo phiếu nhập kho:
     * Chặn số lô chứa khoảng trắng hoặc ký tự đặc biệt không hợp lệ.
     */
    @Test
    void testReceiptInvalidBatchNumberFormat_Rejected() {
        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch1));
        when(supplierRepository.findById(10L)).thenReturn(Optional.of(supplier));
        when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(staff));

        StockReceiptCreateDTO dto = new StockReceiptCreateDTO();
        dto.setBranchId(1L);
        dto.setSupplierId(10L);
        dto.setReceiptDate(LocalDate.now());
        StockReceiptCreateDTO.Item item = new StockReceiptCreateDTO.Item();
        item.setMaterialId(100L);
        item.setBatchNumber("LO CO KHOANG TRANG @@");
        item.setExpiryDate(LocalDate.now().plusMonths(12));
        item.setQuantity(new BigDecimal("5.000"));
        item.setUnitPrice(new BigDecimal("100000.00"));
        dto.setItems(List.of(item));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> receiptService.create(dto, 1L));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    /**
     * Kiểm thử endpoint GET /api/inventory/summary tính toán chính xác 4 chỉ số KPI Cards:
     * 1. Mặt hàng trong kho
     * 2. Cảnh báo sắp hết / chạm ngưỡng
     * 3. Lô hết hạn / sắp hết hạn còn tồn
     * 4. Tổng công nợ NCC tại chi nhánh
     */
    @Test
    void testBranchSummaryEndpoint_CalculatesKpisAccurately() {
        BranchInventoryService mockInvService = mock(BranchInventoryService.class);
        BranchInventoryController controller = new BranchInventoryController(
                mockInvService, accessService, inventoryRepository, batchRepository, paymentService
        );

        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
        when(inventoryRepository.countByBranch_BranchId(1L)).thenReturn(15L);
        when(inventoryRepository.countLowStockByBranch(1L)).thenReturn(3L);
        when(inventoryRepository.countOutOfStockByBranch(1L)).thenReturn(1L);
        when(batchRepository.countExpiredBatchesWithStock(eq(1L), any(LocalDate.class))).thenReturn(2L);
        when(batchRepository.countExpiringSoonBatchesWithStock(eq(1L), any(LocalDate.class), any(LocalDate.class))).thenReturn(4L);

        when(supplierRepository.findAll()).thenReturn(List.of(supplier));
        when(receiptRepository.countReceiptsByBranchAndSupplier(1L, 10L)).thenReturn(2L);
        when(receiptRepository.sumTotalAmountByBranchAndSupplier(1L, 10L)).thenReturn(new BigDecimal("5000000.00"));
        when(supplierPaymentRepository.sumPaidAmountByBranchAndSupplier(1L, 10L)).thenReturn(new BigDecimal("2000000.00"));

        User user = new User();
        user.setUserId(1L);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", user);

        Map<String, Object> summary = controller.getInventorySummary(1L, session);

        assertEquals(15L, summary.get("totalItems"));
        assertEquals(3L, summary.get("lowStockItems"));
        assertEquals(1L, summary.get("outOfStockItems"));
        assertEquals(6L, summary.get("expiringOrExpiredBatches"));
        assertEquals(2L, summary.get("expiredBatches"));
        assertEquals(4L, summary.get("expiringSoonBatches"));
        assertEquals(new BigDecimal("3000000.00"), summary.get("totalSupplierDebt"));
        assertEquals(1L, summary.get("suppliersWithDebtCount"));
    }

    @Test
    void testStockReceiptTotalQuantityHelper() {
        StockReceiptResponseDTO dto = new StockReceiptResponseDTO();
        assertEquals(BigDecimal.ZERO, dto.getTotalQuantity());

        StockReceiptResponseDTO.ItemDetail it1 = new StockReceiptResponseDTO.ItemDetail();
        it1.setQuantity(new BigDecimal("12.500"));
        StockReceiptResponseDTO.ItemDetail it2 = new StockReceiptResponseDTO.ItemDetail();
        it2.setQuantity(new BigDecimal("7.500"));

        dto.setItems(List.of(it1, it2));
        assertEquals(new BigDecimal("20.000"), dto.getTotalQuantity());
    }

    @Test
    void testPrintPreviewEndpoints_AllThreeOperations() {
        com.cvdcms.controller.InventoryPageController pageController =
                new com.cvdcms.controller.InventoryPageController(receiptService, issueService);

        User user = new User();
        user.setUserId(1L);
        user.setUsername("kho_user");

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", user);
        session.setAttribute("roleNames", List.of("QUAN_LY_KHO"));

        org.springframework.ui.ExtendedModelMap model = new org.springframework.ui.ExtendedModelMap();

        // 1. Nhập kho
        StockReceipt receipt = new StockReceipt();
        receipt.setReceiptId(100L);
        receipt.setReceiptCode("PNK-2026-001");
        receipt.setBranch(branch1);
        receipt.setSupplier(supplier);
        receipt.setReceiver(staff);
        receipt.setReceiptDate(LocalDate.now());
        receipt.setTotalAmount(new BigDecimal("1000000.00"));
        when(receiptRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(receipt));
        when(supplierPaymentRepository.sumPaidAmountByReceipt(100L)).thenReturn(new BigDecimal("1000000.00"));
        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);

        String receiptView = pageController.printReceiptPage(100L, session, model);
        assertEquals("inventory/print-receipt", receiptView);
        assertNotNull(model.get("receipt"));
        assertNotNull(model.get("printedAt"));
        assertEquals("inventory", model.get("activeMenu"));

        // 2. Xuất kho thông thường
        StockIssue issue = new StockIssue();
        issue.setIssueId(200L);
        issue.setIssueCode("PXK-2026-001");
        issue.setBranch(branch1);
        issue.setIssuer(staff);
        issue.setIssueType(com.cvdcms.entity.enums.StockIssueType.DISCARD_EXPIRED);
        issue.setIssueDate(LocalDate.now());
        when(issueRepository.findByIdWithDetails(200L)).thenReturn(Optional.of(issue));

        org.springframework.ui.ExtendedModelMap modelIssue = new org.springframework.ui.ExtendedModelMap();
        String issueView = pageController.printIssuePage(200L, session, modelIssue);
        assertEquals("inventory/print-issue", issueView);
        assertNotNull(modelIssue.get("issue"));

        // 3. Điều chuyển kho liên chi nhánh
        StockIssue transfer = new StockIssue();
        transfer.setIssueId(300L);
        transfer.setIssueCode("PDC-2026-001");
        transfer.setBranch(branch1);
        transfer.setDestinationBranch(branch2);
        transfer.setIssuer(staff);
        transfer.setIssueType(com.cvdcms.entity.enums.StockIssueType.TRANSFER);
        transfer.setIssueDate(LocalDate.now());
        when(issueRepository.findByIdWithDetails(300L)).thenReturn(Optional.of(transfer));

        // Gọi qua printIssuePage nhận diện TRANSFER -> print-transfer
        org.springframework.ui.ExtendedModelMap modelTrans1 = new org.springframework.ui.ExtendedModelMap();
        String transView1 = pageController.printIssuePage(300L, session, modelTrans1);
        assertEquals("inventory/print-transfer", transView1);

        // Gọi qua printTransferPage trực tiếp
        org.springframework.ui.ExtendedModelMap modelTrans2 = new org.springframework.ui.ExtendedModelMap();
        String transView2 = pageController.printTransferPage(300L, session, modelTrans2);
        assertEquals("inventory/print-transfer", transView2);
        assertNotNull(modelTrans2.get("issue"));
    }

    @Test
    void testPrintPreview_UnauthorizedBranchRejected() {
        com.cvdcms.controller.InventoryPageController pageController =
                new com.cvdcms.controller.InventoryPageController(receiptService, issueService);

        User user = new User();
        user.setUserId(99L);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", user);

        StockReceipt receipt = new StockReceipt();
        receipt.setReceiptId(101L);
        receipt.setBranch(branch1);
        when(receiptRepository.findByIdWithDetails(101L)).thenReturn(Optional.of(receipt));
        when(accessService.canAccessBranch(99L, 1L)).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> pageController.printReceiptPage(101L, session, new org.springframework.ui.ExtendedModelMap()));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }
}