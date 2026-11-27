package com.cvdcms.service;

import com.cvdcms.DTO.StockReceiptCreateDTO;
import com.cvdcms.DTO.StockReceiptResponseDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.BatchStatus;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.repository.*;
import com.cvdcms.service.impl.StockReceiptServiceImpl;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StockReceiptServiceTest {

    @Mock private BranchRepository branchRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private StockReceiptRepository receiptRepository;
    @Mock private MaterialBatchRepository batchRepository;
    @Mock private BranchInventoryRepository inventoryRepository;
    @Mock private StaffProfileRepository staffRepository;
    @Mock private UserRepository userRepository;
    @Mock private SupplierPaymentRepository supplierPaymentRepository;
    @Mock private BranchAccessService accessService;

    private StockReceiptServiceImpl receiptService;
    private final List<Path> createdFiles = new ArrayList<>();

    private Branch branch;
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

        branch = new Branch();
        branch.setBranchId(1L);
        branch.setBranchCode("CN01");
        branch.setBranchName("Cơ sở Quận 1");
        branch.setIsActive(true);

        supplier = new Supplier();
        supplier.setSupplierId(10L);
        supplier.setSupplierCode("NCC-3M");
        supplier.setSupplierName("Công ty 3M");
        supplier.setIsActive(true);

        material = new Material();
        material.setMaterialId(100L);
        material.setMaterialCode("VT-01");
        material.setMaterialName("Composite 3M");
        material.setUnit("Ống");
        material.setIsActive(true);

        staff = new StaffProfile();
        staff.setStaffId(5L);
        staff.setStaffCode("NV001");
        staff.setFullName("Nguyễn Văn Kho");
        staff.setStatus(StaffStatus.ACTIVE);
    }

    @AfterEach
    void cleanupFiles() throws Exception {
        for (Path p : createdFiles) {
            Files.deleteIfExists(p);
        }
    }

    @Test
    @DisplayName("create - Reject null DTO or invalid branchId")
    void create_nullDtoOrInvalidBranch_throws400() {
        ResponseStatusException ex1 = assertThrows(ResponseStatusException.class,
                () -> receiptService.create(null, 1L));
        assertEquals(HttpStatus.BAD_REQUEST, ex1.getStatusCode());

        StockReceiptCreateDTO dto = new StockReceiptCreateDTO();
        dto.setBranchId(0L);
        ResponseStatusException ex2 = assertThrows(ResponseStatusException.class,
                () -> receiptService.create(dto, 1L));
        assertEquals(HttpStatus.BAD_REQUEST, ex2.getStatusCode());
    }

    @Test
    @DisplayName("create - Block 403 when user lacks branch access")
    void create_noBranchAccess_throws403() {
        StockReceiptCreateDTO dto = validDto();
        when(accessService.canAccessBranch(99L, 1L)).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> receiptService.create(dto, 99L));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    @DisplayName("create - Reject when supplier is missing or item list is empty")
    void create_missingSupplierOrItems_throws400() {
        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);

        StockReceiptCreateDTO noSup = validDto();
        noSup.setSupplierId(null);
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> receiptService.create(noSup, 1L)).getStatusCode());

        StockReceiptCreateDTO noItems = validDto();
        noItems.setItems(List.of());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> receiptService.create(noItems, 1L)).getStatusCode());
    }

    @Test
    @DisplayName("create - Reject when invoice attachment is missing or receiptDate is in the future")
    void create_missingAttachmentOrFutureDate_throws400() {
        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);

        StockReceiptCreateDTO noAttachment = validDto();
        noAttachment.setAttachmentUrl("  ");
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> receiptService.create(noAttachment, 1L)).getStatusCode());

        StockReceiptCreateDTO futureDate = validDto();
        futureDate.setReceiptDate(LocalDate.now().plusDays(1));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> receiptService.create(futureDate, 1L)).getStatusCode());
    }

    @Test
    @DisplayName("create - Reject malformed supplier invoice number or notes > 255 characters")
    void create_invalidInvoiceNoOrLongNotes_throws400() {
        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);

        StockReceiptCreateDTO badInvoice = validDto();
        badInvoice.setSupplierInvoiceNo("HD 123 @@");
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> receiptService.create(badInvoice, 1L)).getStatusCode());

        StockReceiptCreateDTO longNotes = validDto();
        longNotes.setNotes("a".repeat(256));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> receiptService.create(longNotes, 1L)).getStatusCode());
    }

    @Test
    @DisplayName("create - Return 404/400 when branch or supplier does not exist or is inactive")
    void create_missingOrInactiveBranchOrSupplier_throwsError() {
        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);

        when(branchRepository.findById(1L)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> receiptService.create(validDto(), 1L)).getStatusCode());

        branch.setIsActive(false);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> receiptService.create(validDto(), 1L)).getStatusCode());

        branch.setIsActive(true);
        when(supplierRepository.findById(10L)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> receiptService.create(validDto(), 1L)).getStatusCode());

        supplier.setIsActive(false);
        when(supplierRepository.findById(10L)).thenReturn(Optional.of(supplier));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> receiptService.create(validDto(), 1L)).getStatusCode());
    }

    @Test
    @DisplayName("create - Reject normal staff without StaffProfile; auto-create StaffProfile for Admin")
    void create_missingStaffProfile_rejectedForStaffAndAutoCreatedForAdmin() {
        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch));
        when(supplierRepository.findById(10L)).thenReturn(Optional.of(supplier));
        when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.empty());
        when(accessService.isAdmin(1L)).thenReturn(false);

        ResponseStatusException nonAdminEx = assertThrows(ResponseStatusException.class,
                () -> receiptService.create(validDto(), 1L));
        assertEquals(HttpStatus.BAD_REQUEST, nonAdminEx.getStatusCode());

        // Admin tự động tạo hồ sơ nhân viên
        User admin = new User();
        admin.setUserId(1L);
        admin.setUsername("admin");
        when(accessService.isAdmin(1L)).thenReturn(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(staffRepository.save(any(StaffProfile.class))).thenReturn(staff);
        when(materialRepository.findById(100L)).thenReturn(Optional.of(material));
        when(batchRepository.findForUpdate(1L, 100L, "LO-01")).thenReturn(Optional.empty());
        when(inventoryRepository.findForUpdate(1L, 100L)).thenReturn(Optional.empty());
        when(receiptRepository.save(any(StockReceipt.class))).thenAnswer(inv -> inv.getArgument(0));

        StockReceiptResponseDTO res = receiptService.create(validDto(), 1L);
        assertNotNull(res);
        verify(staffRepository).save(any(StaffProfile.class));
    }

    @Test
    @DisplayName("create - Reject item with expiry before receipt date, quantity <= 0, or negative price")
    void create_invalidItemValues_throws400() {
        stubHeaderDeps();

        StockReceiptCreateDTO pastExpiry = validDto();
        pastExpiry.getItems().get(0).setExpiryDate(LocalDate.now().minusDays(1));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> receiptService.create(pastExpiry, 1L)).getStatusCode());

        StockReceiptCreateDTO zeroQty = validDto();
        zeroQty.getItems().get(0).setQuantity(BigDecimal.ZERO);
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> receiptService.create(zeroQty, 1L)).getStatusCode());

        StockReceiptCreateDTO negPrice = validDto();
        negPrice.getItems().get(0).setUnitPrice(new BigDecimal("-100"));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> receiptService.create(negPrice, 1L)).getStatusCode());
    }

    @Test
    @DisplayName("create - Reject inactive material or duplicate (materialId, batchNumber) in same voucher")
    void create_inactiveMaterialOrDuplicateBatchInVoucher_throws400() {
        stubHeaderDeps();

        material.setIsActive(false);
        when(materialRepository.findById(100L)).thenReturn(Optional.of(material));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> receiptService.create(validDto(), 1L)).getStatusCode());

        material.setIsActive(true);
        StockReceiptCreateDTO dupBatch = validDto();
        StockReceiptCreateDTO.Item i2 = new StockReceiptCreateDTO.Item();
        i2.setMaterialId(100L);
        i2.setBatchNumber("lo-01"); // trùng khi normalize in hoa
        i2.setExpiryDate(LocalDate.now().plusMonths(6));
        i2.setQuantity(new BigDecimal("2"));
        i2.setUnitPrice(new BigDecimal("10000"));
        dupBatch.setItems(List.of(dupBatch.getItems().get(0), i2));

        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> receiptService.create(dupBatch, 1L)).getStatusCode());
    }

    @Test
    @DisplayName("create - Accumulate existing batch and inventory when batch number & expiry match")
    void create_existingBatchMatchingExpiry_accumulatesStock() {
        stubHeaderDeps();
        when(materialRepository.findById(100L)).thenReturn(Optional.of(material));

        LocalDate exp = LocalDate.now().plusMonths(12);
        MaterialBatch existingBatch = new MaterialBatch();
        existingBatch.setBatchId(55L);
        existingBatch.setBranch(branch);
        existingBatch.setMaterial(material);
        existingBatch.setBatchNumber("LO-01");
        existingBatch.setExpiryDate(exp);
        existingBatch.setQuantityRemaining(new BigDecimal("5.000"));
        when(batchRepository.findForUpdate(1L, 100L, "LO-01")).thenReturn(Optional.of(existingBatch));

        BranchInventory existingInv = new BranchInventory();
        existingInv.setBranch(branch);
        existingInv.setMaterial(material);
        existingInv.setCurrentStock(new BigDecimal("5.000"));
        when(inventoryRepository.findForUpdate(1L, 100L)).thenReturn(Optional.of(existingInv));
        when(receiptRepository.save(any(StockReceipt.class))).thenAnswer(inv -> {
            StockReceipt r = inv.getArgument(0);
            r.setReceiptId(88L);
            return r;
        });

        StockReceiptCreateDTO dto = validDto();
        dto.getItems().get(0).setExpiryDate(exp);
        dto.getItems().get(0).setQuantity(new BigDecimal("10.000"));

        receiptService.create(dto, 1L);

        assertEquals(new BigDecimal("15.000"), existingBatch.getQuantityRemaining());
        assertEquals(new BigDecimal("15.000"), existingInv.getCurrentStock());
        assertEquals(BatchStatus.ACTIVE, existingBatch.getStatus());
    }

    @Test
    @DisplayName("create - Reject negative paidAmount, over-total amount, bad payment method, or invalid ref")
    void create_invalidPaymentParams_throws400() {
        stubHeaderDeps();
        when(materialRepository.findById(100L)).thenReturn(Optional.of(material));
        when(batchRepository.findForUpdate(1L, 100L, "LO-01")).thenReturn(Optional.empty());
        when(inventoryRepository.findForUpdate(1L, 100L)).thenReturn(Optional.empty());

        StockReceiptCreateDTO negPaid = validDto();
        negPaid.setPaidAmount(new BigDecimal("-10"));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> receiptService.create(negPaid, 1L)).getStatusCode());

        StockReceiptCreateDTO overPaid = validDto();
        overPaid.setPaidAmount(new BigDecimal("99999999"));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> receiptService.create(overPaid, 1L)).getStatusCode());

        StockReceiptCreateDTO badMethod = validDto();
        badMethod.setPaidAmount(new BigDecimal("500000"));
        badMethod.setPaymentMethod("CRYPTO");
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> receiptService.create(badMethod, 1L)).getStatusCode());

        StockReceiptCreateDTO badRef = validDto();
        badRef.setPaidAmount(new BigDecimal("500000"));
        badRef.setPaymentMethod("CASH");
        badRef.setPaymentReferenceNo("UNC @@");
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> receiptService.create(badRef, 1L)).getStatusCode());
    }

    @Test
    @DisplayName("create - Auto-create SupplierPayment when paidAmount > 0 and support e-signature on creation")
    void create_withInitialPaymentAndSignature_createsPaymentAndSigns() throws Exception {
        stubHeaderDeps();
        when(materialRepository.findById(100L)).thenReturn(Optional.of(material));
        when(batchRepository.findForUpdate(1L, 100L, "LO-01")).thenReturn(Optional.empty());
        when(inventoryRepository.findForUpdate(1L, 100L)).thenReturn(Optional.empty());
        when(receiptRepository.save(any(StockReceipt.class))).thenAnswer(inv -> {
            StockReceipt r = inv.getArgument(0);
            r.setReceiptId(77L);
            return r;
        });

        StockReceiptCreateDTO dto = validDto(); // 10 * 250000 = 2,500,000
        dto.setSupplierInvoiceNo("HD-001");
        dto.setPaidAmount(new BigDecimal("1000000"));
        dto.setPaymentMethod("CASH");
        dto.setSignatureData(buildValidSignatureDataUrl());

        StockReceiptResponseDTO response = receiptService.create(dto, 1L);
        trackSignatureFile(response.getSignatureUrl());

        assertEquals(new BigDecimal("2500000.00"), response.getTotalAmount());
        assertEquals(new BigDecimal("1000000.00"), response.getPaidAmount());
        assertEquals(new BigDecimal("1500000.00"), response.getRemainingDebt());
        assertNotNull(response.getSignatureUrl());
        assertEquals("Nguyễn Văn Kho", response.getSignedByName());

        ArgumentCaptor<SupplierPayment> payCaptor = ArgumentCaptor.forClass(SupplierPayment.class);
        verify(supplierPaymentRepository).save(payCaptor.capture());
        assertEquals(new BigDecimal("1000000.00"), payCaptor.getValue().getAmount());
        assertEquals("CASH", payCaptor.getValue().getPaymentMethod());
        assertEquals("HD-001", payCaptor.getValue().getReferenceNo());
    }

    @Test
    @DisplayName("search - Check branch access, date range, and compute remaining debt per receipt")
    void search_validatesAndComputesDebtCorrectly() {
        Pageable pageable = PageRequest.of(0, 10);
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,
                        () -> receiptService.search(0L, null, null, null, null, pageable, 1L)).getStatusCode());

        when(accessService.canAccessBranch(1L, 1L)).thenReturn(false);
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class,
                        () -> receiptService.search(1L, null, null, null, null, pageable, 1L)).getStatusCode());

        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,
                        () -> receiptService.search(1L, null, null, LocalDate.now(), LocalDate.now().minusDays(1), pageable, 1L)).getStatusCode());

        StockReceipt r1 = new StockReceipt();
        r1.setReceiptId(10L);
        r1.setReceiptCode("PN010");
        r1.setBranch(branch);
        r1.setSupplier(supplier);
        r1.setReceiver(staff);
        r1.setReceiptDate(LocalDate.now());
        r1.setTotalAmount(new BigDecimal("1000000.00"));

        when(receiptRepository.searchReceipts(1L, null, null, null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(r1), pageable, 1));
        when(supplierPaymentRepository.sumPaidAmountByReceipts(List.of(10L)))
                .thenReturn(List.<Object[]>of(new Object[]{10L, new BigDecimal("400000.00")}));

        Page<StockReceiptResponseDTO> res = receiptService.search(1L, null, "  ", null, null, pageable, 1L);
        assertEquals(1, res.getTotalElements());
        assertEquals(new BigDecimal("400000.00"), res.getContent().get(0).getPaidAmount());
        assertEquals(new BigDecimal("600000.00"), res.getContent().get(0).getRemainingDebt());
    }

    @Test
    @DisplayName("getById - Validate ID, 404/403 access, and map receipt item details")
    void getById_validatesAndReturnsDetail() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> receiptService.getById(null, 1L)).getStatusCode());

        when(receiptRepository.findByIdWithDetails(999L)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> receiptService.getById(999L, 1L)).getStatusCode());

        StockReceipt receipt = new StockReceipt();
        receipt.setReceiptId(50L);
        receipt.setReceiptCode("PN050");
        receipt.setBranch(branch);
        receipt.setSupplier(supplier);
        receipt.setReceiver(staff);
        receipt.setReceiptDate(LocalDate.now());
        receipt.setTotalAmount(new BigDecimal("500000.00"));

        StockReceiptItem item = new StockReceiptItem();
        item.setReceiptItemId(1L);
        item.setMaterial(material);
        item.setBatchNumber("LO-01");
        item.setExpiryDate(LocalDate.now().plusMonths(6));
        item.setQuantity(new BigDecimal("2.000"));
        item.setUnitPrice(new BigDecimal("250000.00"));
        receipt.setItems(List.of(item));

        when(receiptRepository.findByIdWithDetails(50L)).thenReturn(Optional.of(receipt));
        when(accessService.canAccessBranch(2L, 1L)).thenReturn(false);
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> receiptService.getById(50L, 2L)).getStatusCode());

        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
        when(supplierPaymentRepository.sumPaidAmountByReceipt(50L)).thenReturn(new BigDecimal("100000.00"));

        StockReceiptResponseDTO detail = receiptService.getById(50L, 1L);
        assertEquals(1, detail.getItemCount());
        assertEquals(new BigDecimal("500000.00"), detail.getItems().get(0).getLineTotal());
        assertEquals(new BigDecimal("400000.00"), detail.getRemainingDebt());
    }

    @Test
    @DisplayName("signReceipt - Block re-signing and cross-branch signing; persist new signature PNG")
    void signReceipt_validatesPermissionsImmutabilityAndSigns() throws Exception {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> receiptService.signReceipt(0L, "data", 1L)).getStatusCode());

        when(receiptRepository.findByIdWithDetails(404L)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> receiptService.signReceipt(404L, "data", 1L)).getStatusCode());

        StockReceipt receipt = new StockReceipt();
        receipt.setReceiptId(60L);
        receipt.setReceiptCode("PN060");
        receipt.setBranch(branch);
        receipt.setSupplier(supplier);
        receipt.setReceiver(staff);
        receipt.setReceiptDate(LocalDate.now());
        receipt.setTotalAmount(new BigDecimal("100000.00"));
        when(receiptRepository.findByIdWithDetails(60L)).thenReturn(Optional.of(receipt));

        when(accessService.canAccessBranch(9L, 1L)).thenReturn(false);
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> receiptService.signReceipt(60L, "data", 9L)).getStatusCode());

        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
        receipt.setSignatureUrl("/uploads/signatures/existing.png");
        receipt.setSignedByName("Thủ kho A");
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> receiptService.signReceipt(60L, "data", 1L)).getStatusCode());

        // Ký thành công khi chưa có chữ ký
        receipt.setSignatureUrl(null);
        when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(staff));
        when(receiptRepository.save(any(StockReceipt.class))).thenAnswer(inv -> inv.getArgument(0));

        StockReceiptResponseDTO signed = receiptService.signReceipt(60L, buildValidSignatureDataUrl(), 1L);
        trackSignatureFile(signed.getSignatureUrl());

        assertNotNull(signed.getSignatureUrl());
        assertTrue(signed.getSignatureUrl().startsWith("/uploads/signatures/receipt_60_"));
        assertEquals("Nguyễn Văn Kho", signed.getSignedByName());
        assertNotNull(signed.getSignedAt());
    }

    private void stubHeaderDeps() {
        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch));
        when(supplierRepository.findById(10L)).thenReturn(Optional.of(supplier));
        when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(staff));
    }

    private StockReceiptCreateDTO validDto() {
        StockReceiptCreateDTO dto = new StockReceiptCreateDTO();
        dto.setBranchId(1L);
        dto.setSupplierId(10L);
        dto.setReceiptDate(LocalDate.now());
        dto.setAttachmentUrl("/uploads/invoices/inv_sample.png");
        StockReceiptCreateDTO.Item item = new StockReceiptCreateDTO.Item();
        item.setMaterialId(100L);
        item.setBatchNumber("LO-01");
        item.setExpiryDate(LocalDate.now().plusMonths(12));
        item.setQuantity(new BigDecimal("10.000"));
        item.setUnitPrice(new BigDecimal("250000.00"));
        dto.setItems(List.of(item));
        return dto;
    }

    private String buildValidSignatureDataUrl() throws Exception {
        BufferedImage img = new BufferedImage(100, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 100, 40);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(3f));
        g.drawLine(10, 10, 80, 30);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private void trackSignatureFile(String url) {
        if (url != null && url.startsWith("/")) {
            createdFiles.add(Paths.get(url.substring(1)).toAbsolutePath().normalize());
        }
    }
}
