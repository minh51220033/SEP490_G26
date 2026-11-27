package com.cvdcms;

import com.cvdcms.DTO.*;
import com.cvdcms.config.ChatStompChannelInterceptor;
import com.cvdcms.config.ChatStompPrincipal;
import com.cvdcms.config.ChatWebSocketHandshakeInterceptor;
import com.cvdcms.controller.BranchInventoryController;
import com.cvdcms.controller.ChatWebSocketController;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.event.CustomerCareDomainEvents.CustomerCareRealtimeBroadcastEvent;
import com.cvdcms.repository.*;
import com.cvdcms.service.*;
import com.cvdcms.service.impl.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("HieuUtReport")
class HieuUtReportTest {

    private static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static Branch branch(Long id, String code, String name, boolean active) {
        Branch b = new Branch();
        b.setBranchId(id);
        b.setBranchCode(code);
        b.setBranchName(name);
        b.setIsActive(active);
        return b;
    }

    private static Material activeMaterial(Long id, String code, String name) {
        Material m = new Material();
        m.setMaterialId(id);
        m.setMaterialCode(code);
        m.setMaterialName(name);
        m.setCategory("Trám răng");
        m.setUnit("Ống");
        m.setMinStockThreshold(new BigDecimal("10.000"));
        m.setIsActive(true);
        return m;
    }

    private static Supplier activeSupplier(Long id, String code, String name) {
        Supplier s = new Supplier();
        s.setSupplierId(id);
        s.setSupplierCode(code);
        s.setSupplierName(name);
        s.setTaxCode("0101234567");
        s.setPhoneNumber("0901234567");
        s.setAddress("Quận 1, TP.HCM");
        s.setIsActive(true);
        return s;
    }

    private static StaffProfile activeStaff(Long id, String code, String name, String position, User user) {
        StaffProfile sp = new StaffProfile();
        sp.setStaffId(id);
        sp.setStaffCode(code);
        sp.setFullName(name);
        sp.setPositionTitle(position);
        sp.setStatus(StaffStatus.ACTIVE);
        sp.setUser(user);
        return sp;
    }

    private static User activeUser(Long id, String username, UserType type, Branch b) {
        User u = new User();
        u.setUserId(id);
        u.setUsername(username);
        u.setUserType(type);
        u.setBranch(b);
        u.setIsActive(true);
        u.setStatus("ACTIVE");
        return u;
    }

    private static String buildValidSignatureDataUrl() throws Exception {
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

    // =========================================================================
    // 33. ManageMaterialSupplier (10 cases)
    // =========================================================================
    @Nested
    @DisplayName("ManageMaterialSupplier")
    class ManageMaterialSupplier {
        @Mock MaterialRepository materialRepository;
        @Mock SupplierRepository supplierRepository;
        @Mock MedicationRepository medicationRepository;
        @Mock PrescriptionItemRepository prescriptionItemRepository;

        MaterialServiceImpl materialService;
        SupplierServiceImpl supplierService;
        MedicationServiceImpl medicationService;

        @BeforeEach
        void setUp() {
            materialService = new MaterialServiceImpl(materialRepository);
            supplierService = new SupplierServiceImpl(supplierRepository);
            medicationService = new MedicationServiceImpl(medicationRepository, prescriptionItemRepository, materialRepository);
        }

        @Test
        @DisplayName("UTCID01 (B) - Create material normalizes code, trims text, and defaults minStockThreshold to 10.000")
        void utcid01() {
            MaterialCreateDTO dto = new MaterialCreateDTO();
            dto.setMaterialCode(" vt-new-02 ");
            dto.setMaterialName(" Găng tay ");
            dto.setCategory(" Tiêu hao ");
            dto.setUnit(" Hộp ");
            dto.setMinStockThreshold(null);

            when(materialRepository.existsByMaterialCodeIgnoreCase("VT-NEW-02")).thenReturn(false);
            when(materialRepository.save(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));

            Material saved = materialService.create(dto);
            assertEquals("VT-NEW-02", saved.getMaterialCode());
            assertEquals("Găng tay", saved.getMaterialName());
            assertEquals(new BigDecimal("10.000"), saved.getMinStockThreshold());
            verify(materialRepository, times(1)).save(any(Material.class));
        }

        @Test
        @DisplayName("UTCID02 (A) - Create material rejects null DTO, blank code, or duplicate code VT-NEW-01")
        void utcid02() {
            assertThrows(IllegalArgumentException.class, () -> materialService.create(null));

            MaterialCreateDTO blankCode = new MaterialCreateDTO();
            blankCode.setMaterialCode("  ");
            assertThrows(IllegalArgumentException.class, () -> materialService.create(blankCode));

            MaterialCreateDTO dup = new MaterialCreateDTO();
            dup.setMaterialCode("VT-NEW-01");
            dup.setMaterialName("Gloves");
            dup.setCategory("Tiêu hao");
            dup.setUnit("Hộp");
            when(materialRepository.existsByMaterialCodeIgnoreCase("VT-NEW-01")).thenReturn(true);
            assertThrows(IllegalArgumentException.class, () -> materialService.create(dup));
            verify(materialRepository, never()).save(any());
        }

        @Test
        @DisplayName("UTCID03 (N) - Update medication-synced material AMOX500 allows category & threshold 25.000 change")
        void utcid03() {
            Medication med = new Medication();
            med.setMedicationId(7L);
            Material existing = activeMaterial(10L, "AMOX500", "Amoxicillin 500mg");
            existing.setUnit("Viên");
            existing.setMedication(med);

            MaterialUpdateDTO upd = new MaterialUpdateDTO();
            upd.setMaterialCode("AMOX500");
            upd.setMaterialName("Amoxicillin 500mg");
            upd.setCategory("Thuốc kê đơn");
            upd.setUnit("Viên");
            upd.setMinStockThreshold(new BigDecimal("25"));

            when(materialRepository.findById(10L)).thenReturn(Optional.of(existing));
            when(materialRepository.existsByMaterialCodeIgnoreCaseAndMaterialIdNot("AMOX500", 10L)).thenReturn(false);
            when(materialRepository.save(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));

            Material updated = materialService.update(10L, upd);
            assertEquals("Thuốc kê đơn", updated.getCategory());
            assertEquals(new BigDecimal("25.000"), updated.getMinStockThreshold());
            verify(materialRepository, times(1)).save(any(Material.class));
        }

        @Test
        @DisplayName("UTCID04 (A) - Update medication-synced material rejects changing materialCode to AMOX500-EDIT")
        void utcid04() {
            Medication med = new Medication();
            med.setMedicationId(7L);
            Material existing = activeMaterial(10L, "AMOX500", "Amoxicillin 500mg");
            existing.setUnit("Viên");
            existing.setMedication(med);

            MaterialUpdateDTO upd = new MaterialUpdateDTO();
            upd.setMaterialCode("AMOX500-EDIT");
            upd.setMaterialName("Amoxicillin 500mg");
            upd.setCategory("Thuốc");
            upd.setUnit("Viên");

            when(materialRepository.findById(10L)).thenReturn(Optional.of(existing));
            assertThrows(IllegalArgumentException.class, () -> materialService.update(10L, upd));
            verify(materialRepository, never()).save(any());
        }

        @Test
        @DisplayName("UTCID05 (A) - Activate material blocks already active or inactive linked medication; activates inactive regular")
        void utcid05() {
            Material mat = activeMaterial(10L, "VT-10", "Gauzes");
            when(materialRepository.findById(10L)).thenReturn(Optional.of(mat));
            assertThrows(IllegalArgumentException.class, () -> materialService.activate(10L));

            Medication inactiveMed = new Medication();
            inactiveMed.setMedicationId(8L);
            inactiveMed.setIsActive(false);
            mat.setIsActive(false);
            mat.setMedication(inactiveMed);
            assertThrows(IllegalArgumentException.class, () -> materialService.activate(10L));

            mat.setMedication(null);
            when(materialRepository.save(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));
            Material activated = materialService.activate(10L);
            assertTrue(activated.getIsActive());
            verify(materialRepository, times(1)).save(mat);
        }

        @Test
        @DisplayName("UTCID06 (N) - Create supplier normalizes code, converts blank optional fields to null, and sets active")
        void utcid06() {
            SupplierCreateDTO dto = new SupplierCreateDTO();
            dto.setSupplierCode(" ncc-dentsply ");
            dto.setSupplierName("  Công ty   Dentsply  ");
            dto.setTaxCode("   ");
            dto.setPhoneNumber("");
            dto.setAddress("  Hà Nội ");

            when(supplierRepository.existsBySupplierCodeIgnoreCase("NCC-DENTSPLY")).thenReturn(false);
            when(supplierRepository.save(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));

            Supplier saved = supplierService.create(dto);
            assertEquals("NCC-DENTSPLY", saved.getSupplierCode());
            assertNull(saved.getTaxCode());
            assertNull(saved.getPhoneNumber());
            assertTrue(saved.getIsActive());
            verify(supplierRepository, times(1)).save(any(Supplier.class));
        }

        @Test
        @DisplayName("UTCID07 (A) - Create supplier rejects null DTO, blank code/name, or duplicate code NCC-3M")
        void utcid07() {
            assertThrows(IllegalArgumentException.class, () -> supplierService.create(null));

            SupplierCreateDTO dup = new SupplierCreateDTO();
            dup.setSupplierCode("NCC-3M");
            dup.setSupplierName("3M");
            when(supplierRepository.existsBySupplierCodeIgnoreCase("NCC-3M")).thenReturn(true);
            assertThrows(IllegalArgumentException.class, () -> supplierService.create(dup));

            SupplierCreateDTO blankName = new SupplierCreateDTO();
            blankName.setSupplierCode("NCC-OK");
            blankName.setSupplierName("   ");
            when(supplierRepository.existsBySupplierCodeIgnoreCase("NCC-OK")).thenReturn(false);
            assertThrows(IllegalArgumentException.class, () -> supplierService.create(blankName));
            verify(supplierRepository, never()).save(any());
        }

        @Test
        @DisplayName("UTCID08 (A) - Toggle supplier status succeeds for valid transitions and blocks duplicate transitions")
        void utcid08() {
            Supplier s = activeSupplier(10L, "NCC-3M", "Công ty 3M");
            when(supplierRepository.findById(10L)).thenReturn(Optional.of(s));
            when(supplierRepository.save(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));

            assertThrows(IllegalArgumentException.class, () -> supplierService.activate(10L));
            Supplier dec = supplierService.deactivate(10L);
            assertFalse(dec.getIsActive());
            assertThrows(IllegalArgumentException.class, () -> supplierService.deactivate(10L));
            Supplier act = supplierService.activate(10L);
            assertTrue(act.getIsActive());
            verify(supplierRepository, times(2)).save(s);
        }

        @Test
        @DisplayName("UTCID09 (N) - Create medication saves Medication 7L and syncs one linked Material AMOX500")
        void utcid09() {
            MedicationDTO dto = MedicationDTO.builder()
                    .drugCode(" amox500 ")
                    .drugName("Amoxicillin 500mg")
                    .activeIngredient("Amoxicillin")
                    .unit("Viên")
                    .usageInstruction("Uống sau ăn")
                    .build();

            when(medicationRepository.existsByDrugCodeIgnoreCase("AMOX500")).thenReturn(false);
            when(materialRepository.findByMaterialCodeIgnoreCase("AMOX500")).thenReturn(Optional.empty());
            when(medicationRepository.saveAndFlush(any(Medication.class))).thenAnswer(inv -> {
                Medication m = inv.getArgument(0);
                m.setMedicationId(7L);
                return m;
            });
            when(materialRepository.saveAndFlush(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));

            MedicationDTO res = medicationService.createMedication(dto);
            assertEquals(7L, res.getMedicationId());
            verify(medicationRepository, times(1)).saveAndFlush(any(Medication.class));
            verify(materialRepository, times(1)).saveAndFlush(argThat(mat ->
                    "AMOX500".equals(mat.getMaterialCode()) && Long.valueOf(7L).equals(mat.getMedicationId())));
        }

        @Test
        @DisplayName("UTCID10 (A) - Delete medication blocked when linked Material 200L already has stock receipt history")
        void utcid10() {
            Medication med = new Medication();
            med.setMedicationId(7L);
            med.setDrugName("Amoxicillin");
            Material linked = activeMaterial(200L, "AMOX500", "Amoxicillin");
            linked.setMedication(med);

            when(medicationRepository.findById(7L)).thenReturn(Optional.of(med));
            when(prescriptionItemRepository.countByMedication_MedicationId(7L)).thenReturn(0L);
            when(materialRepository.findByMedication_MedicationId(7L)).thenReturn(Optional.of(linked));
            when(materialRepository.countReceiptItems(200L)).thenReturn(1L);

            assertThrows(IllegalArgumentException.class, () -> medicationService.deleteMedication(7L));
            verify(materialRepository, never()).delete(any());
            verify(medicationRepository, never()).delete(any());
            verify(medicationRepository, never()).deleteById(any());
        }
    }

    // =========================================================================
    // 34. StockReceiptProcess (11 cases)
    // =========================================================================
    @Nested
    @DisplayName("StockReceiptProcess")
    class StockReceiptProcess {
        @Mock BranchRepository branchRepository;
        @Mock SupplierRepository supplierRepository;
        @Mock MaterialRepository materialRepository;
        @Mock StockReceiptRepository receiptRepository;
        @Mock MaterialBatchRepository batchRepository;
        @Mock BranchInventoryRepository inventoryRepository;
        @Mock StaffProfileRepository staffRepository;
        @Mock UserRepository userRepository;
        @Mock SupplierPaymentRepository supplierPaymentRepository;
        @Mock BranchAccessService accessService;

        StockReceiptServiceImpl receiptService;
        Branch cn1;
        Supplier ncc3m;
        Material composite;
        StaffProfile khoStaff;
        final List<Path> createdFiles = new ArrayList<>();

        @BeforeEach
        void setUp() {
            receiptService = new StockReceiptServiceImpl(
                    branchRepository, supplierRepository, materialRepository,
                    receiptRepository, batchRepository, inventoryRepository,
                    staffRepository, userRepository, supplierPaymentRepository, accessService);
            cn1 = branch(1L, "CN01", "Cơ sở Quận 1", true);
            ncc3m = activeSupplier(10L, "NCC-3M", "Công ty 3M");
            composite = activeMaterial(100L, "VT-01", "Composite 3M");
            khoStaff = activeStaff(5L, "NV001", "Nguyễn Văn Kho", "Quản lý kho", null);
        }

        @AfterEach
        void cleanup() throws Exception {
            for (Path p : createdFiles) Files.deleteIfExists(p);
        }

        private StockReceiptCreateDTO baseDto() {
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

        private void stubHeader() {
            when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
            when(branchRepository.findById(1L)).thenReturn(Optional.of(cn1));
            when(supplierRepository.findById(10L)).thenReturn(Optional.of(ncc3m));
            when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(khoStaff));
        }

        @Test
        @DisplayName("UTCID01 (A) - Create receipt rejects null DTO or invalid branchId with 400 BAD_REQUEST")
        void utcid01() {
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> receiptService.create(null, 1L)).getStatusCode());
            StockReceiptCreateDTO badBranch = new StockReceiptCreateDTO();
            badBranch.setBranchId(0L);
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> receiptService.create(badBranch, 1L)).getStatusCode());
            verifyNoInteractions(receiptRepository, batchRepository, inventoryRepository);
        }

        @Test
        @DisplayName("UTCID02 (A) - Create receipt blocks unauthorized branch user 99L with 403 FORBIDDEN")
        void utcid02() {
            when(accessService.canAccessBranch(99L, 1L)).thenReturn(false);
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> receiptService.create(baseDto(), 99L));
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
            verifyNoInteractions(receiptRepository);
        }

        @Test
        @DisplayName("UTCID03 (B) - Create receipt rejects blank attachmentUrl or future receiptDate with 400 BAD_REQUEST")
        void utcid03() {
            when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);

            StockReceiptCreateDTO noAttach = baseDto();
            noAttach.setAttachmentUrl("  ");
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> receiptService.create(noAttach, 1L)).getStatusCode());

            StockReceiptCreateDTO future = baseDto();
            future.setReceiptDate(LocalDate.now().plusDays(1));
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> receiptService.create(future, 1L)).getStatusCode());
            verify(receiptRepository, never()).save(any());
        }

        @Test
        @DisplayName("UTCID04 (A) - Create receipt returns 404/400 for missing or inactive branch/supplier")
        void utcid04() {
            when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);

            when(branchRepository.findById(1L)).thenReturn(Optional.empty());
            assertEquals(HttpStatus.NOT_FOUND,
                    assertThrows(ResponseStatusException.class, () -> receiptService.create(baseDto(), 1L)).getStatusCode());

            cn1.setIsActive(false);
            when(branchRepository.findById(1L)).thenReturn(Optional.of(cn1));
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> receiptService.create(baseDto(), 1L)).getStatusCode());

            cn1.setIsActive(true);
            when(supplierRepository.findById(10L)).thenReturn(Optional.empty());
            assertEquals(HttpStatus.NOT_FOUND,
                    assertThrows(ResponseStatusException.class, () -> receiptService.create(baseDto(), 1L)).getStatusCode());

            ncc3m.setIsActive(false);
            when(supplierRepository.findById(10L)).thenReturn(Optional.of(ncc3m));
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> receiptService.create(baseDto(), 1L)).getStatusCode());
        }

        @Test
        @DisplayName("UTCID05 (N) - Missing StaffProfile rejects regular staff with 400 and auto-creates profile for Admin")
        void utcid05() {
            when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
            when(branchRepository.findById(1L)).thenReturn(Optional.of(cn1));
            when(supplierRepository.findById(10L)).thenReturn(Optional.of(ncc3m));
            when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.empty());
            when(accessService.isAdmin(1L)).thenReturn(false);

            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> receiptService.create(baseDto(), 1L)).getStatusCode());

            User admin = activeUser(1L, "admin", UserType.STAFF, cn1);
            when(accessService.isAdmin(1L)).thenReturn(true);
            when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
            when(staffRepository.save(any(StaffProfile.class))).thenReturn(khoStaff);
            when(materialRepository.findById(100L)).thenReturn(Optional.of(composite));
            when(batchRepository.findForUpdate(1L, 100L, "LO-01")).thenReturn(Optional.empty());
            when(inventoryRepository.findForUpdate(1L, 100L)).thenReturn(Optional.empty());
            when(receiptRepository.save(any(StockReceipt.class))).thenAnswer(inv -> inv.getArgument(0));

            assertNotNull(receiptService.create(baseDto(), 1L));
            verify(staffRepository).save(any(StaffProfile.class));
            verify(receiptRepository).save(any(StockReceipt.class));
        }

        @Test
        @DisplayName("UTCID06 (B) - Create receipt rejects expiryDate before receiptDate, qty = 0, or negative unitPrice")
        void utcid06() {
            stubHeader();

            StockReceiptCreateDTO pastExp = baseDto();
            pastExp.getItems().get(0).setExpiryDate(LocalDate.now().minusDays(1));
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> receiptService.create(pastExp, 1L)).getStatusCode());

            StockReceiptCreateDTO zeroQty = baseDto();
            zeroQty.getItems().get(0).setQuantity(BigDecimal.ZERO);
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> receiptService.create(zeroQty, 1L)).getStatusCode());

            StockReceiptCreateDTO negPrice = baseDto();
            negPrice.getItems().get(0).setUnitPrice(new BigDecimal("-100"));
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> receiptService.create(negPrice, 1L)).getStatusCode());
            verify(receiptRepository, never()).save(any());
        }

        @Test
        @DisplayName("UTCID07 (A) - Create receipt rejects inactive material or duplicate (materialId, batchNumber) case-insensitively")
        void utcid07() {
            stubHeader();

            composite.setIsActive(false);
            when(materialRepository.findById(100L)).thenReturn(Optional.of(composite));
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> receiptService.create(baseDto(), 1L)).getStatusCode());

            composite.setIsActive(true);
            StockReceiptCreateDTO dup = baseDto();
            StockReceiptCreateDTO.Item i2 = new StockReceiptCreateDTO.Item();
            i2.setMaterialId(100L);
            i2.setBatchNumber("lo-01");
            i2.setExpiryDate(LocalDate.now().plusMonths(6));
            i2.setQuantity(new BigDecimal("2"));
            i2.setUnitPrice(new BigDecimal("10000"));
            dup.setItems(List.of(dup.getItems().get(0), i2));
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> receiptService.create(dup, 1L)).getStatusCode());
        }

        @Test
        @DisplayName("UTCID08 (N) - Create receipt accumulates batch LO-01 and CN1 inventory to 15.000 when expiry matches")
        void utcid08() {
            stubHeader();
            when(materialRepository.findById(100L)).thenReturn(Optional.of(composite));
            LocalDate exp = LocalDate.now().plusMonths(12);

            MaterialBatch existingBatch = new MaterialBatch();
            existingBatch.setBatchId(55L);
            existingBatch.setBranch(cn1);
            existingBatch.setMaterial(composite);
            existingBatch.setBatchNumber("LO-01");
            existingBatch.setExpiryDate(exp);
            existingBatch.setQuantityRemaining(new BigDecimal("5.000"));
            when(batchRepository.findForUpdate(1L, 100L, "LO-01")).thenReturn(Optional.of(existingBatch));

            BranchInventory existingInv = new BranchInventory();
            existingInv.setBranch(cn1);
            existingInv.setMaterial(composite);
            existingInv.setCurrentStock(new BigDecimal("5.000"));
            when(inventoryRepository.findForUpdate(1L, 100L)).thenReturn(Optional.of(existingInv));
            when(receiptRepository.save(any(StockReceipt.class))).thenAnswer(inv -> {
                StockReceipt r = inv.getArgument(0);
                r.setReceiptId(88L);
                return r;
            });

            StockReceiptCreateDTO dto = baseDto();
            dto.getItems().get(0).setExpiryDate(exp);
            receiptService.create(dto, 1L);

            assertEquals(new BigDecimal("15.000"), existingBatch.getQuantityRemaining());
            assertEquals(new BigDecimal("15.000"), existingInv.getCurrentStock());
            assertEquals(BatchStatus.ACTIVE, existingBatch.getStatus());
            verify(receiptRepository).save(any(StockReceipt.class));
        }

        @Test
        @DisplayName("UTCID09 (A) - Create receipt rejects mismatched expiryDate (+12m vs +6m) on existing batch LO-01")
        void utcid09() {
            stubHeader();
            when(materialRepository.findById(100L)).thenReturn(Optional.of(composite));

            MaterialBatch existingBatch = new MaterialBatch();
            existingBatch.setBranch(cn1);
            existingBatch.setMaterial(composite);
            existingBatch.setBatchNumber("LO-01");
            existingBatch.setExpiryDate(LocalDate.now().plusMonths(6));
            existingBatch.setQuantityRemaining(new BigDecimal("5.000"));
            when(batchRepository.findForUpdate(1L, 100L, "LO-01")).thenReturn(Optional.of(existingBatch));

            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> receiptService.create(baseDto(), 1L)).getStatusCode());
            verify(receiptRepository, never()).save(any());
        }

        @Test
        @DisplayName("UTCID10 (N) - Create receipt saves SupplierPayment 1M CASH, signature PNG, and remaining debt 1.5M")
        void utcid10() throws Exception {
            stubHeader();
            when(materialRepository.findById(100L)).thenReturn(Optional.of(composite));
            when(batchRepository.findForUpdate(1L, 100L, "LO-01")).thenReturn(Optional.empty());
            when(inventoryRepository.findForUpdate(1L, 100L)).thenReturn(Optional.empty());
            when(receiptRepository.save(any(StockReceipt.class))).thenAnswer(inv -> {
                StockReceipt r = inv.getArgument(0);
                r.setReceiptId(77L);
                return r;
            });

            StockReceiptCreateDTO dto = baseDto();
            dto.setPaidAmount(new BigDecimal("1000000"));
            dto.setPaymentMethod("CASH");
            dto.setSignatureData(buildValidSignatureDataUrl());

            StockReceiptResponseDTO res = receiptService.create(dto, 1L);
            if (res.getSignatureUrl() != null && res.getSignatureUrl().startsWith("/")) {
                createdFiles.add(Paths.get(res.getSignatureUrl().substring(1)).toAbsolutePath().normalize());
            }

            assertEquals(new BigDecimal("1500000.00"), res.getRemainingDebt());
            assertNotNull(res.getSignatureUrl());
            assertEquals("Nguyễn Văn Kho", res.getSignedByName());
            verify(supplierPaymentRepository).save(any(SupplierPayment.class));
        }

        @Test
        @DisplayName("UTCID11 (N) - Sign receipt enforces branch access, blocks re-signing, and saves new PNG signature")
        void utcid11() throws Exception {
            StockReceipt receipt = new StockReceipt();
            receipt.setReceiptId(60L);
            receipt.setReceiptCode("PN060");
            receipt.setBranch(cn1);
            receipt.setSupplier(ncc3m);
            receipt.setReceiver(khoStaff);
            receipt.setReceiptDate(LocalDate.now());
            receipt.setTotalAmount(new BigDecimal("100000.00"));
            when(receiptRepository.findByIdWithDetails(60L)).thenReturn(Optional.of(receipt));

            when(accessService.canAccessBranch(9L, 1L)).thenReturn(false);
            assertEquals(HttpStatus.FORBIDDEN,
                    assertThrows(ResponseStatusException.class, () -> receiptService.signReceipt(60L, "data", 9L)).getStatusCode());

            when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
            receipt.setSignatureUrl("/uploads/signatures/existing.png");
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> receiptService.signReceipt(60L, "data", 1L)).getStatusCode());

            receipt.setSignatureUrl(null);
            when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(khoStaff));
            when(receiptRepository.save(any(StockReceipt.class))).thenAnswer(inv -> inv.getArgument(0));

            StockReceiptResponseDTO signed = receiptService.signReceipt(60L, buildValidSignatureDataUrl(), 1L);
            if (signed.getSignatureUrl() != null && signed.getSignatureUrl().startsWith("/")) {
                createdFiles.add(Paths.get(signed.getSignatureUrl().substring(1)).toAbsolutePath().normalize());
            }
            assertNotNull(signed.getSignatureUrl());
            assertEquals("Nguyễn Văn Kho", signed.getSignedByName());
        }
    }

    // =========================================================================
    // 35. StockIssueAndTransfer (11 cases)
    // =========================================================================
    @Nested
    @DisplayName("StockIssueAndTransfer")
    class StockIssueAndTransfer {
        @Mock BranchRepository branchRepository;
        @Mock MaterialRepository materialRepository;
        @Mock MaterialBatchRepository batchRepository;
        @Mock BranchInventoryRepository inventoryRepository;
        @Mock StockIssueRepository issueRepository;
        @Mock StaffProfileRepository staffRepository;
        @Mock UserRepository userRepository;
        @Mock BranchAccessService accessService;

        StockIssueServiceImpl issueService;
        Branch cn1;
        Branch cn2;
        Material composite;
        StaffProfile khoStaff;
        final List<Path> createdFiles = new ArrayList<>();

        @BeforeEach
        void setUp() {
            issueService = new StockIssueServiceImpl(
                    branchRepository, materialRepository, batchRepository,
                    inventoryRepository, issueRepository, staffRepository,
                    userRepository, accessService);
            cn1 = branch(1L, "CN01", "Cơ sở Quận 1", true);
            cn2 = branch(2L, "CN02", "Cơ sở Đống Đa", true);
            composite = activeMaterial(100L, "VT-01", "Composite 3M");
            khoStaff = activeStaff(5L, "NV001", "Nguyễn Văn Kho", "Quản lý kho", null);
        }

        @AfterEach
        void cleanup() throws Exception {
            for (Path p : createdFiles) Files.deleteIfExists(p);
        }

        private StockIssueCreateDTO baseDto(String type) {
            StockIssueCreateDTO dto = new StockIssueCreateDTO();
            dto.setBranchId(1L);
            dto.setIssueType(type);
            dto.setIssueDate(LocalDate.now());
            if ("TRANSFER".equals(type)) dto.setDestinationBranchId(2L);
            if ("OTHER".equals(type)) dto.setIssueReason("Hỗ trợ hội thảo");
            StockIssueCreateDTO.Item item = new StockIssueCreateDTO.Item();
            item.setMaterialId(100L);
            item.setQuantity(new BigDecimal("2.000"));
            dto.setItems(List.of(item));
            return dto;
        }

        private MaterialBatch batch(Long id, String code, LocalDate exp, String qty, String price) {
            MaterialBatch b = new MaterialBatch();
            b.setBatchId(id);
            b.setBranch(cn1);
            b.setMaterial(composite);
            b.setBatchNumber(code);
            b.setExpiryDate(exp);
            b.setQuantityRemaining(new BigDecimal(qty));
            b.setImportPrice(new BigDecimal(price));
            b.setStatus(BatchStatus.ACTIVE);
            return b;
        }

        @Test
        @DisplayName("UTCID01 (A) - Create issue rejects null DTO, branchId 0, empty items, unknown type, and future date")
        void utcid01() {
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> issueService.create(null, 1L)).getStatusCode());

            StockIssueCreateDTO badBranch = new StockIssueCreateDTO();
            badBranch.setBranchId(0L);
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> issueService.create(badBranch, 1L)).getStatusCode());

            when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
            StockIssueCreateDTO empty = baseDto("INTERNAL_USE");
            empty.setItems(List.of());
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> issueService.create(empty, 1L)).getStatusCode());

            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> issueService.create(baseDto("UNKNOWN"), 1L)).getStatusCode());

            StockIssueCreateDTO future = baseDto("INTERNAL_USE");
            future.setIssueDate(LocalDate.now().plusDays(1));
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> issueService.create(future, 1L)).getStatusCode());
        }

        @Test
        @DisplayName("UTCID02 (B) - OTHER issue type requires non-blank issueReason within 255 characters")
        void utcid02() {
            when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);

            StockIssueCreateDTO blankReason = baseDto("OTHER");
            blankReason.setIssueReason("   ");
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> issueService.create(blankReason, 1L)).getStatusCode());

            StockIssueCreateDTO longReason = baseDto("OTHER");
            longReason.setIssueReason("a".repeat(256));
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> issueService.create(longReason, 1L)).getStatusCode());
        }

        @Test
        @DisplayName("UTCID03 (A) - TRANSFER validates required destination branch, different from source, and active")
        void utcid03() {
            when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
            when(branchRepository.findById(1L)).thenReturn(Optional.of(cn1));

            StockIssueCreateDTO noDest = baseDto("TRANSFER");
            noDest.setDestinationBranchId(null);
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> issueService.create(noDest, 1L)).getStatusCode());

            StockIssueCreateDTO sameDest = baseDto("TRANSFER");
            sameDest.setDestinationBranchId(1L);
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> issueService.create(sameDest, 1L)).getStatusCode());

            StockIssueCreateDTO missing = baseDto("TRANSFER");
            missing.setDestinationBranchId(999L);
            when(branchRepository.findById(999L)).thenReturn(Optional.empty());
            assertEquals(HttpStatus.NOT_FOUND,
                    assertThrows(ResponseStatusException.class, () -> issueService.create(missing, 1L)).getStatusCode());

            cn2.setIsActive(false);
            StockIssueCreateDTO inactive = baseDto("TRANSFER");
            inactive.setDestinationBranchId(2L);
            when(branchRepository.findById(2L)).thenReturn(Optional.of(cn2));
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> issueService.create(inactive, 1L)).getStatusCode());
        }

        @Test
        @DisplayName("UTCID04 (A) - Unauthorized branch user 99L is blocked with 403 FORBIDDEN")
        void utcid04() {
            when(accessService.canAccessBranch(99L, 1L)).thenReturn(false);
            assertEquals(HttpStatus.FORBIDDEN,
                    assertThrows(ResponseStatusException.class, () -> issueService.create(baseDto("INTERNAL_USE"), 99L)).getStatusCode());
            verifyNoInteractions(issueRepository);
        }

        @Test
        @DisplayName("UTCID05 (A) - Reject issue exceeding CN1 current stock (5.000 > 3.000) without modifying inventory")
        void utcid05() {
            when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
            when(branchRepository.findById(1L)).thenReturn(Optional.of(cn1));
            when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(khoStaff));
            when(materialRepository.findById(100L)).thenReturn(Optional.of(composite));

            BranchInventory inv = new BranchInventory();
            inv.setBranch(cn1);
            inv.setMaterial(composite);
            inv.setCurrentStock(new BigDecimal("3.000"));
            when(inventoryRepository.findForUpdate(1L, 100L)).thenReturn(Optional.of(inv));

            StockIssueCreateDTO dto = baseDto("INTERNAL_USE");
            dto.getItems().get(0).setQuantity(new BigDecimal("5.000"));
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> issueService.create(dto, 1L)).getStatusCode());
            assertEquals(new BigDecimal("3.000"), inv.getCurrentStock());
            verify(issueRepository, never()).save(any());
        }

        @Test
        @DisplayName("UTCID06 (N) - Auto FEFO deducts LO-SOON (+15d, 4) then LO-LATER (+6m, 3) leaving inv = 8")
        void utcid06() {
            when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
            when(branchRepository.findById(1L)).thenReturn(Optional.of(cn1));
            when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(khoStaff));
            when(materialRepository.findById(100L)).thenReturn(Optional.of(composite));

            BranchInventory inv = new BranchInventory();
            inv.setBranch(cn1);
            inv.setMaterial(composite);
            inv.setCurrentStock(new BigDecimal("15.000"));
            when(inventoryRepository.findForUpdate(1L, 100L)).thenReturn(Optional.of(inv));

            MaterialBatch soon = batch(1L, "LO-SOON", LocalDate.now().plusDays(15), "4.000", "100000");
            MaterialBatch later = batch(2L, "LO-LATER", LocalDate.now().plusMonths(6), "11.000", "120000");
            when(batchRepository.findAvailableBatchesFefoForUpdate(eq(1L), eq(100L), any(LocalDate.class)))
                    .thenReturn(List.of(soon, later));
            when(issueRepository.save(any(StockIssue.class))).thenAnswer(i -> i.getArgument(0));

            StockIssueCreateDTO dto = baseDto("INTERNAL_USE");
            dto.getItems().get(0).setQuantity(new BigDecimal("7.000"));
            StockIssueResponseDTO res = issueService.create(dto, 1L);

            assertEquals(BatchStatus.OUT_OF_STOCK, soon.getStatus());
            assertEquals(new BigDecimal("8.000"), later.getQuantityRemaining());
            assertEquals(new BigDecimal("8.000"), inv.getCurrentStock());
            assertEquals(2, res.getItemCount());
            verify(issueRepository).save(any(StockIssue.class));
        }

        @Test
        @DisplayName("UTCID07 (N) - Transfer CN1 -> CN2 (8.000) deducts CN1 stock to 12.000 and credits CN2 with 8.000")
        void utcid07() {
            when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
            when(branchRepository.findById(1L)).thenReturn(Optional.of(cn1));
            when(branchRepository.findById(2L)).thenReturn(Optional.of(cn2));
            when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(khoStaff));
            when(materialRepository.findById(100L)).thenReturn(Optional.of(composite));

            BranchInventory srcInv = new BranchInventory();
            srcInv.setBranch(cn1);
            srcInv.setMaterial(composite);
            srcInv.setCurrentStock(new BigDecimal("20.000"));
            when(inventoryRepository.findForUpdate(1L, 100L)).thenReturn(Optional.of(srcInv));

            MaterialBatch srcBatch = batch(50L, "LO-CHUYEN-01", LocalDate.now().plusMonths(8), "20.000", "100000");
            when(batchRepository.findAvailableBatchesFefoForUpdate(eq(1L), eq(100L), any(LocalDate.class)))
                    .thenReturn(List.of(srcBatch));
            when(batchRepository.findForUpdate(2L, 100L, "LO-CHUYEN-01")).thenReturn(Optional.empty());
            when(inventoryRepository.findForUpdate(2L, 100L)).thenReturn(Optional.empty());
            when(issueRepository.save(any(StockIssue.class))).thenAnswer(i -> i.getArgument(0));

            StockIssueCreateDTO dto = baseDto("TRANSFER");
            dto.getItems().get(0).setQuantity(new BigDecimal("8.000"));
            issueService.create(dto, 1L);

            assertEquals(new BigDecimal("12.000"), srcBatch.getQuantityRemaining());
            assertEquals(new BigDecimal("12.000"), srcInv.getCurrentStock());
            verify(inventoryRepository).save(argThat(bi ->
                    bi.getBranch().getBranchId().equals(2L) && bi.getCurrentStock().compareTo(new BigDecimal("8.000")) == 0));
        }

        @Test
        @DisplayName("UTCID08 (N) - Expired batch 9L blocked for INTERNAL_USE (400) and allowed for DISCARD_EXPIRED (leaves 7.000)")
        void utcid08() {
            when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
            when(branchRepository.findById(1L)).thenReturn(Optional.of(cn1));
            when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(khoStaff));
            when(materialRepository.findById(100L)).thenReturn(Optional.of(composite));

            BranchInventory inv = new BranchInventory();
            inv.setBranch(cn1);
            inv.setMaterial(composite);
            inv.setCurrentStock(new BigDecimal("10.000"));
            when(inventoryRepository.findForUpdate(1L, 100L)).thenReturn(Optional.of(inv));

            MaterialBatch expired = batch(9L, "LO-EXP", LocalDate.now().minusDays(2), "10.000", "50000");
            when(batchRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(expired));

            StockIssueCreateDTO useDto = baseDto("INTERNAL_USE");
            useDto.getItems().get(0).setBatchId(9L);
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> issueService.create(useDto, 1L)).getStatusCode());

            when(issueRepository.save(any(StockIssue.class))).thenAnswer(i -> i.getArgument(0));
            StockIssueCreateDTO discardDto = baseDto("DISCARD_EXPIRED");
            discardDto.getItems().get(0).setBatchId(9L);
            discardDto.getItems().get(0).setQuantity(new BigDecimal("3.000"));
            StockIssueResponseDTO res = issueService.create(discardDto, 1L);

            assertEquals("DISCARD_EXPIRED", res.getIssueType());
            assertEquals(new BigDecimal("7.000"), expired.getQuantityRemaining());
            assertEquals(new BigDecimal("7.000"), inv.getCurrentStock());
        }

        @Test
        @DisplayName("UTCID09 (A) - DISCARD_EXPIRED rejects when expired batch stock (2.000) is insufficient for requested 5.000")
        void utcid09() {
            when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
            when(branchRepository.findById(1L)).thenReturn(Optional.of(cn1));
            when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(khoStaff));
            when(materialRepository.findById(100L)).thenReturn(Optional.of(composite));

            BranchInventory inv = new BranchInventory();
            inv.setBranch(cn1);
            inv.setMaterial(composite);
            inv.setCurrentStock(new BigDecimal("20.000"));
            when(inventoryRepository.findForUpdate(1L, 100L)).thenReturn(Optional.of(inv));

            MaterialBatch expired = batch(9L, "LO-EXP", LocalDate.now().minusDays(2), "2.000", "50000");
            when(batchRepository.findExpiredBatchesForUpdate(eq(1L), eq(100L), any(LocalDate.class)))
                    .thenReturn(List.of(expired));

            StockIssueCreateDTO dto = baseDto("DISCARD_EXPIRED");
            dto.getItems().get(0).setQuantity(new BigDecimal("5.000"));
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> issueService.create(dto, 1L)).getStatusCode());
            verify(issueRepository, never()).save(any());
        }

        @Test
        @DisplayName("UTCID10 (A) - Reject batch 77L from other branch CN2 or insufficient batch stock (5.000 > 1.000)")
        void utcid10() {
            when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
            when(branchRepository.findById(1L)).thenReturn(Optional.of(cn1));
            when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(khoStaff));
            when(materialRepository.findById(100L)).thenReturn(Optional.of(composite));

            BranchInventory inv = new BranchInventory();
            inv.setBranch(cn1);
            inv.setMaterial(composite);
            inv.setCurrentStock(new BigDecimal("20.000"));
            when(inventoryRepository.findForUpdate(1L, 100L)).thenReturn(Optional.of(inv));

            MaterialBatch batch77 = batch(77L, "LO-77", LocalDate.now().plusMonths(3), "10.000", "50000");
            batch77.setBranch(cn2);
            when(batchRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(batch77));

            StockIssueCreateDTO wrongBranch = baseDto("INTERNAL_USE");
            wrongBranch.getItems().get(0).setBatchId(77L);
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> issueService.create(wrongBranch, 1L)).getStatusCode());

            batch77.setBranch(cn1);
            batch77.setQuantityRemaining(new BigDecimal("1.000"));
            StockIssueCreateDTO tooMuch = baseDto("INTERNAL_USE");
            tooMuch.getItems().get(0).setBatchId(77L);
            tooMuch.getItems().get(0).setQuantity(new BigDecimal("5.000"));
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> issueService.create(tooMuch, 1L)).getStatusCode());
            verify(issueRepository, never()).save(any());
        }

        @Test
        @DisplayName("UTCID11 (N) - Destination branch CN2 views/signs transfer DC300; blocks re-signing (400) and outsider (403)")
        void utcid11() throws Exception {
            StockIssue dc = new StockIssue();
            dc.setIssueId(300L);
            dc.setIssueCode("DC300");
            dc.setBranch(cn1);
            dc.setDestinationBranch(cn2);
            dc.setIssueType(StockIssueType.TRANSFER);
            dc.setIssueDate(LocalDate.now());
            dc.setIssuer(khoStaff);

            when(issueRepository.findByIdWithDetails(300L)).thenReturn(Optional.of(dc));
            when(accessService.canAccessBranch(20L, 1L)).thenReturn(false);
            when(accessService.canAccessBranch(20L, 2L)).thenReturn(true);

            assertEquals("DC300", issueService.getById(300L, 20L).getIssueCode());

            when(staffRepository.findByUser_UserId(20L)).thenReturn(Optional.of(khoStaff));
            when(issueRepository.save(any(StockIssue.class))).thenAnswer(i -> i.getArgument(0));
            StockIssueResponseDTO signed = issueService.signIssue(300L, buildValidSignatureDataUrl(), 20L);
            if (signed.getSignatureUrl() != null && signed.getSignatureUrl().startsWith("/")) {
                createdFiles.add(Paths.get(signed.getSignatureUrl().substring(1)).toAbsolutePath().normalize());
            }
            assertNotNull(signed.getSignatureUrl());

            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class,
                            () -> issueService.signIssue(300L, buildValidSignatureDataUrl(), 20L)).getStatusCode());

            when(accessService.canAccessBranch(99L, 1L)).thenReturn(false);
            when(accessService.canAccessBranch(99L, 2L)).thenReturn(false);
            assertEquals(HttpStatus.FORBIDDEN,
                    assertThrows(ResponseStatusException.class, () -> issueService.getById(300L, 99L)).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN,
                    assertThrows(ResponseStatusException.class, () -> issueService.signIssue(300L, "data", 99L)).getStatusCode());
        }
    }

    // =========================================================================
    // 36. BranchStockAndDebt (10 cases)
    // =========================================================================
    @Nested
    @DisplayName("BranchStockAndDebt")
    class BranchStockAndDebt {
        @Mock UserRepository userRepository;
        @Mock BranchService branchService;
        @Mock BranchInventoryRepository inventoryRepository;
        @Mock MaterialBatchRepository batchRepository;
        @Mock MaterialRepository materialRepository;
        @Mock SupplierRepository supplierRepository;
        @Mock BranchRepository branchRepository;
        @Mock StockReceiptRepository receiptRepository;
        @Mock SupplierPaymentRepository paymentRepository;
        @Mock StaffProfileRepository staffRepository;
        @Mock BranchAccessService branchAccessService;
        @Mock SupplierPaymentService supplierPaymentServiceMock;

        BranchAccessServiceImpl accessService;
        BranchInventoryServiceImpl inventoryService;
        MaterialBatchServiceImpl batchService;
        SupplierPaymentServiceImpl paymentService;
        BranchInventoryController controller;

        Branch cn1;
        Branch cn2;
        Supplier ncc10;
        StaffProfile accStaff;

        @BeforeEach
        void setUp() {
            accessService = new BranchAccessServiceImpl(userRepository);
            accessService.setBranchService(branchService);
            inventoryService = new BranchInventoryServiceImpl(inventoryRepository);
            batchService = new MaterialBatchServiceImpl(batchRepository);
            paymentService = new SupplierPaymentServiceImpl(
                    supplierRepository, branchRepository, receiptRepository,
                    paymentRepository, staffRepository, userRepository, branchAccessService);
            controller = new BranchInventoryController(
                    inventoryService, branchAccessService, inventoryRepository, batchRepository, supplierPaymentServiceMock);
            ReflectionTestUtils.setField(controller, "materialRepository", materialRepository);

            cn1 = branch(1L, "CN01", "Cơ sở Quận 1", true);
            cn2 = branch(2L, "CN02", "Cơ sở Đống Đa", true);
            ncc10 = activeSupplier(10L, "NCC-3M", "Công ty 3M");
            accStaff = activeStaff(5L, "KT01", "Kế toán Kho", "Kế toán", null);
        }

        @Test
        @DisplayName("UTCID01 (N) - User 10L with active warehouse assignment at CN02 is granted branch access")
        void utcid01() {
            when(userRepository.hasActiveAdminRole(10L)).thenReturn(false);
            when(userRepository.countActiveWarehouseAssignments(10L, 2L)).thenReturn(1L);
            assertTrue(accessService.canAccessBranch(10L, 2L));
        }

        @Test
        @DisplayName("UTCID02 (A) - User 13L without admin role, assignment, or inventory permission is denied branch 3L")
        void utcid02() {
            when(userRepository.hasActiveAdminRole(13L)).thenReturn(false);
            when(userRepository.countActiveWarehouseAssignments(13L, 3L)).thenReturn(0L);
            when(userRepository.countActiveInventoryPermissionForBranch(13L, 3L)).thenReturn(0L);
            when(userRepository.hasActiveSupplierManagementRole(13L)).thenReturn(false);
            assertFalse(accessService.canAccessBranch(13L, 3L));
        }

        @Test
        @DisplayName("UTCID03 (N) - Search branch inventory trims keyword '  composite  ' and maps full InventoryItemDTO")
        void utcid03() {
            Material m = activeMaterial(100L, "VT-01", "Composite 3M");
            m.setMinStockThreshold(new BigDecimal("5.000"));
            BranchInventory bi = new BranchInventory();
            bi.setInventoryId(55L);
            bi.setBranch(cn1);
            bi.setMaterial(m);
            bi.setCurrentStock(new BigDecimal("18.500"));
            Pageable pageable = PageRequest.of(0, 10);
            when(inventoryRepository.searchByBranch(1L, "composite", null, pageable))
                    .thenReturn(new PageImpl<>(List.of(bi), pageable, 1));

            Page<InventoryItemDTO> page = inventoryService.search(1L, "  composite  ", null, pageable);
            assertEquals(1, page.getTotalElements());
            assertEquals(new BigDecimal("18.500"), page.getContent().get(0).getCurrentStock());
            assertEquals(new BigDecimal("5.000"), page.getContent().get(0).getMinStockThreshold());
        }

        @Test
        @DisplayName("UTCID04 (B) - Search batches classifies EXPIRED (-1d), EXPIRING_SOON (+0d, +30d), and NONE (+31d)")
        void utcid04() {
            LocalDate today = LocalDate.now();
            Material mat = activeMaterial(100L, "VT-01", "Lidocaine");
            MaterialBatch b1 = new MaterialBatch(); b1.setBatchId(1L); b1.setBranch(cn1); b1.setMaterial(mat); b1.setBatchNumber("L1"); b1.setExpiryDate(today.minusDays(1)); b1.setSupplier(ncc10); b1.setQuantityRemaining(BigDecimal.ONE); b1.setImportPrice(BigDecimal.TEN); b1.setStatus(BatchStatus.EXPIRED);
            MaterialBatch b2 = new MaterialBatch(); b2.setBatchId(2L); b2.setBranch(cn1); b2.setMaterial(mat); b2.setBatchNumber("L2"); b2.setExpiryDate(today); b2.setSupplier(ncc10); b2.setQuantityRemaining(BigDecimal.ONE); b2.setImportPrice(BigDecimal.TEN); b2.setStatus(BatchStatus.ACTIVE);
            MaterialBatch b3 = new MaterialBatch(); b3.setBatchId(3L); b3.setBranch(cn1); b3.setMaterial(mat); b3.setBatchNumber("L3"); b3.setExpiryDate(today.plusDays(30)); b3.setSupplier(null); b3.setQuantityRemaining(BigDecimal.ONE); b3.setImportPrice(BigDecimal.TEN); b3.setStatus(BatchStatus.ACTIVE);
            MaterialBatch b4 = new MaterialBatch(); b4.setBatchId(4L); b4.setBranch(cn1); b4.setMaterial(mat); b4.setBatchNumber("L4"); b4.setExpiryDate(today.plusDays(31)); b4.setSupplier(ncc10); b4.setQuantityRemaining(BigDecimal.ONE); b4.setImportPrice(BigDecimal.TEN); b4.setStatus(BatchStatus.ACTIVE);

            Pageable pageable = PageRequest.of(0, 10);
            when(batchRepository.searchBatches(1L, null, null, null, pageable))
                    .thenReturn(new PageImpl<>(List.of(b1, b2, b3, b4), pageable, 4));

            Page<MaterialBatchDTO> page = batchService.searchBatches(1L, "   ", null, null, pageable);
            assertEquals("EXPIRED", page.getContent().get(0).getExpiryWarning());
            assertEquals("EXPIRING_SOON", page.getContent().get(1).getExpiryWarning());
            assertEquals("EXPIRING_SOON", page.getContent().get(2).getExpiryWarning());
            assertNull(page.getContent().get(2).getSupplierName());
            assertEquals("NONE", page.getContent().get(3).getExpiryWarning());
        }

        @Test
        @DisplayName("UTCID05 (A) - Search batches rejects expiryFrom after expiryTo with IllegalArgumentException")
        void utcid05() {
            assertThrows(IllegalArgumentException.class, () -> batchService.searchBatches(
                    1L, null, LocalDate.now().plusDays(10), LocalDate.now().plusDays(5), PageRequest.of(0, 10)));
            verifyNoInteractions(batchRepository);
        }

        @Test
        @DisplayName("UTCID06 (N) - Inventory summary KPI maps low=4, out=2, expiringOrExpired=4, and supplierDebt=2000000")
        void utcid06() {
            User u = activeUser(10L, "kho", UserType.STAFF, cn1);
            MockHttpSession session = new MockHttpSession();
            session.setAttribute("loggedInUser", u);

            when(branchAccessService.canAccessBranch(10L, 1L)).thenReturn(true);
            when(inventoryRepository.countByBranch_BranchId(1L)).thenReturn(25L);
            when(inventoryRepository.countLowStockByBranch(1L)).thenReturn(4L);
            when(inventoryRepository.countOutOfStockByBranch(1L)).thenReturn(2L);
            when(batchRepository.countExpiredBatchesWithStock(eq(1L), any(LocalDate.class))).thenReturn(1L);
            when(batchRepository.countExpiringSoonBatchesWithStock(eq(1L), any(LocalDate.class), any(LocalDate.class))).thenReturn(3L);

            SupplierDebtSummaryDTO d1 = new SupplierDebtSummaryDTO(); d1.setRemainingDebt(new BigDecimal("1200000"));
            SupplierDebtSummaryDTO d2 = new SupplierDebtSummaryDTO(); d2.setRemainingDebt(new BigDecimal("800000"));
            when(supplierPaymentServiceMock.getDebtSummaries(1L, null, 10L)).thenReturn(List.of(d1, d2));

            Map<String, Object> summary = controller.getInventorySummary(1L, session);
            assertEquals(25L, summary.get("totalItems"));
            assertEquals(4L, summary.get("lowStockItems"));
            assertEquals(4L, summary.get("expiringOrExpiredBatches"));
            assertEquals(new BigDecimal("2000000"), summary.get("totalSupplierDebt"));
        }

        @Test
        @DisplayName("UTCID07 (B) - Debt summaries compute NCC10=3M and clamp overpaid NCC11 debt at zero")
        void utcid07() {
            when(branchAccessService.canAccessBranch(1L, 1L)).thenReturn(true);
            Supplier ncc11 = activeSupplier(11L, "NCC-11", "NCC Overpaid");
            when(supplierRepository.searchSuppliers(isNull(), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(ncc10, ncc11)));
            when(receiptRepository.aggregateReceiptsByBranchAndSuppliers(eq(1L), anyList()))
                    .thenReturn(List.<Object[]>of(
                            new Object[]{10L, 3, new BigDecimal("5000000.00")},
                            new Object[]{11L, 1, new BigDecimal("1000000.00")}));
            when(paymentRepository.aggregatePaidByBranchAndSuppliers(eq(1L), anyList()))
                    .thenReturn(List.<Object[]>of(
                            new Object[]{10L, new BigDecimal("2000000.00")},
                            new Object[]{11L, new BigDecimal("1200000.00")}));

            List<SupplierDebtSummaryDTO> list = paymentService.getDebtSummaries(1L, " ", 1L);
            assertEquals(new BigDecimal("3000000.00"), list.get(0).getRemainingDebt());
            assertEquals(BigDecimal.ZERO, list.get(1).getRemainingDebt());
        }

        @Test
        @DisplayName("UTCID08 (B) - Payment double-submit blocked by clientRequestId and 5-second duplicate guard")
        void utcid08() {
            when(branchAccessService.canAccessBranch(1L, 1L)).thenReturn(true);
            when(branchRepository.findById(1L)).thenReturn(Optional.of(cn1));
            when(supplierRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(ncc10));
            when(receiptRepository.sumTotalAmountByBranchAndSupplier(1L, 10L)).thenReturn(new BigDecimal("1000000.00"));
            when(paymentRepository.sumPaidAmountByBranchAndSupplier(1L, 10L)).thenReturn(BigDecimal.ZERO);
            when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(accStaff));
            when(paymentRepository.save(any(SupplierPayment.class))).thenAnswer(i -> i.getArgument(0));

            SupplierPaymentCreateDTO p1 = new SupplierPaymentCreateDTO();
            p1.setBranchId(1L); p1.setSupplierId(10L); p1.setPaymentDate(LocalDate.now());
            p1.setAmount(new BigDecimal("200000.00")); p1.setPaymentMethod("BANK_TRANSFER"); p1.setClientRequestId("REQ-UNIQ-1");
            assertNotNull(paymentService.createPayment(p1, 1L));

            SupplierPaymentCreateDTO dupReq = new SupplierPaymentCreateDTO();
            dupReq.setBranchId(1L); dupReq.setSupplierId(10L); dupReq.setPaymentDate(LocalDate.now());
            dupReq.setAmount(new BigDecimal("200000.00")); dupReq.setPaymentMethod("BANK_TRANSFER"); dupReq.setClientRequestId("REQ-UNIQ-1");
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> paymentService.createPayment(dupReq, 1L)).getStatusCode());

            SupplierPaymentCreateDTO dup5s = new SupplierPaymentCreateDTO();
            dup5s.setBranchId(1L); dup5s.setSupplierId(10L); dup5s.setPaymentDate(LocalDate.now());
            dup5s.setAmount(new BigDecimal("200000.00")); dup5s.setPaymentMethod("BANK_TRANSFER"); dup5s.setClientRequestId("REQ-UNIQ-2");
            when(paymentRepository.existsRecentDuplicatePayment(eq(1L), eq(10L), isNull(), eq(new BigDecimal("200000.00")), any(LocalDateTime.class)))
                    .thenReturn(true);
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> paymentService.createPayment(dup5s, 1L)).getStatusCode());
            verify(paymentRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("UTCID09 (A) - Linked receipt payment rejects cross-branch, paid-full, or overpayment (>100k debt)")
        void utcid09() {
            when(branchAccessService.canAccessBranch(1L, 1L)).thenReturn(true);
            when(branchRepository.findById(1L)).thenReturn(Optional.of(cn1));
            when(supplierRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(ncc10));

            StockReceipt r = new StockReceipt();
            r.setReceiptId(50L); r.setReceiptCode("PN050"); r.setBranch(cn2); r.setSupplier(ncc10); r.setTotalAmount(new BigDecimal("500000.00"));
            when(receiptRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(r));

            SupplierPaymentCreateDTO dto = new SupplierPaymentCreateDTO();
            dto.setBranchId(1L); dto.setSupplierId(10L); dto.setReceiptId(50L);
            dto.setPaymentDate(LocalDate.now()); dto.setAmount(new BigDecimal("200000.00")); dto.setPaymentMethod("CASH");

            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> paymentService.createPayment(dto, 1L)).getStatusCode());

            r.setBranch(cn1);
            when(paymentRepository.sumPaidAmountByReceipt(50L)).thenReturn(new BigDecimal("500000.00"));
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> paymentService.createPayment(dto, 1L)).getStatusCode());

            when(paymentRepository.sumPaidAmountByReceipt(50L)).thenReturn(new BigDecimal("400000.00"));
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> paymentService.createPayment(dto, 1L)).getStatusCode());
            verify(paymentRepository, never()).save(any());
        }

        @Test
        @DisplayName("UTCID10 (N) - Linked receipt payment 500k CASH ref PC-001 succeeds and returns DTO id=88L")
        void utcid10() {
            when(branchAccessService.canAccessBranch(1L, 1L)).thenReturn(true);
            when(branchRepository.findById(1L)).thenReturn(Optional.of(cn1));
            when(supplierRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(ncc10));

            StockReceipt r = new StockReceipt();
            r.setReceiptId(50L); r.setReceiptCode("PN050"); r.setBranch(cn1); r.setSupplier(ncc10); r.setTotalAmount(new BigDecimal("1000000.00"));
            when(receiptRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(r));
            when(paymentRepository.sumPaidAmountByReceipt(50L)).thenReturn(new BigDecimal("200000.00"));
            when(receiptRepository.sumTotalAmountByBranchAndSupplier(1L, 10L)).thenReturn(new BigDecimal("1000000.00"));
            when(paymentRepository.sumPaidAmountByBranchAndSupplier(1L, 10L)).thenReturn(new BigDecimal("200000.00"));
            when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(accStaff));
            when(paymentRepository.save(any(SupplierPayment.class))).thenAnswer(i -> {
                SupplierPayment p = i.getArgument(0); p.setSupplierPaymentId(88L); return p;
            });

            SupplierPaymentCreateDTO dto = new SupplierPaymentCreateDTO();
            dto.setBranchId(1L); dto.setSupplierId(10L); dto.setReceiptId(50L);
            dto.setPaymentDate(LocalDate.now()); dto.setAmount(new BigDecimal("500000.00"));
            dto.setPaymentMethod("CASH"); dto.setReferenceNo("PC-001");

            SupplierPaymentResponseDTO res = paymentService.createPayment(dto, 1L);
            assertEquals(88L, res.getSupplierPaymentId());
            assertEquals("Kế toán Kho", res.getPaidByName());
            verify(paymentRepository).save(any(SupplierPayment.class));
        }
    }

    // =========================================================================
    // 37. CareTaskAndAccess (11 cases)
    // =========================================================================
    @Nested
    @DisplayName("CareTaskAndAccess")
    class CareTaskAndAccess {
        @Mock CustomerCareTaskRepository taskRepository;
        @Mock CustomerCareInteractionRepository interactionRepository;
        @Mock PatientProfileRepository patientRepository;
        @Mock BranchRepository branchRepository;
        @Mock StaffProfileRepository staffRepository;
        @Mock VisitRepository visitRepository;
        @Mock AppointmentRepository appointmentRepository;
        @Mock PrescriptionRepository prescriptionRepository;
        @Mock UserRepository userRepository;
        @Mock ClinicalExaminationRepository clinicalExaminationRepository;
        @Mock ExaminationDiagnosisRepository examinationDiagnosisRepository;
        @Mock CustomerCareNotificationService notificationService;
        @Mock SystemAuditLogService systemAuditLogService;

        CustomerCareServiceImpl service;
        Branch cn1;
        Branch cn2;
        User adminUser;
        User recUser;
        User docUser;
        User whUser;
        StaffProfile recStaff;
        StaffProfile docStaff;
        PatientProfile patient;

        @BeforeEach
        void setUp() {
            service = new CustomerCareServiceImpl(
                    taskRepository, interactionRepository, patientRepository, branchRepository,
                    staffRepository, visitRepository, appointmentRepository, prescriptionRepository,
                    userRepository, clinicalExaminationRepository, examinationDiagnosisRepository);
            ReflectionTestUtils.setField(service, "notificationService", notificationService);
            ReflectionTestUtils.setField(service, "systemAuditLogService", systemAuditLogService);

            cn1 = branch(1L, "CN01", "Cơ sở 1", true);
            cn2 = branch(2L, "CN02", "Cơ sở 2", true);
            adminUser = activeUser(1L, "admin", UserType.STAFF, null);
            recUser = activeUser(10L, "letan01", UserType.STAFF, cn1);
            docUser = activeUser(20L, "bs01", UserType.STAFF, cn1);
            whUser = activeUser(30L, "kho01", UserType.STAFF, cn1);
            recStaff = activeStaff(1L, "LT01", "Nguyễn Lễ Tân", "Lễ tân CSKH", recUser);
            docStaff = activeStaff(2L, "BS01", "BS. Trần Minh", "Bác sĩ RHM", docUser);
            patient = new PatientProfile();
            patient.setProfileId(1000L);
            patient.setPatientCode("BN001");
            patient.setFullName("Phạm Thị Hương");
            patient.setPreferredBranch(cn1);

            lenient().when(taskRepository.save(any(CustomerCareTask.class))).thenAnswer(inv -> {
                CustomerCareTask t = inv.getArgument(0);
                if (t.getTaskId() == null) t.setTaskId(99L);
                return t;
            });
            lenient().when(taskRepository.existsByTaskCode(anyString())).thenReturn(false);
        }

        private void stubAdmin() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(adminUser));
            when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        }

        private void stubRec(Long branchId) {
            when(userRepository.findById(10L)).thenReturn(Optional.of(recUser));
            lenient().when(userRepository.hasActiveAdminRole(10L)).thenReturn(false);
            lenient().when(userRepository.hasActivePermission(10L, "CUSTOMER_CARE_MANAGE")).thenReturn(true);
            lenient().when(taskRepository.findActiveRoleNamesByUserId(10L)).thenReturn(List.of("LE_TAN"));
            if (branchId != null) {
                lenient().when(branchRepository.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(cn1, cn2));
                lenient().when(taskRepository.findExplicitBranchIdsByUserId(10L)).thenReturn(List.of(branchId));
            }
        }

        private CustomerCareTask task(Long id, CustomerCareTaskStatus status, LocalDate due) {
            CustomerCareTask t = new CustomerCareTask();
            t.setTaskId(id);
            t.setTaskCode("CSKH-" + id);
            t.setBranch(cn1);
            t.setPatientProfile(patient);
            t.setTaskType(CustomerCareTaskType.POST_TREATMENT_FOLLOWUP);
            t.setStatus(status);
            t.setDueDate(due);
            t.setAssignedStaff(recStaff);
            return t;
        }

        @Test
        @DisplayName("UTCID01 (A) - Reject non-care role QUAN_LY_KHO (user 30L) with 403 FORBIDDEN")
        void utcid01() {
            when(userRepository.findById(30L)).thenReturn(Optional.of(whUser));
            when(userRepository.hasActiveAdminRole(30L)).thenReturn(false);
            when(taskRepository.findActiveRoleNamesByUserId(30L)).thenReturn(List.of("QUAN_LY_KHO"));
            assertEquals(HttpStatus.FORBIDDEN,
                    assertThrows(ResponseStatusException.class, () -> service.requireCareRole(30L)).getStatusCode());
            verifyNoInteractions(interactionRepository);
        }

        @Test
        @DisplayName("UTCID02 (A) - Block receptionist 10L at CN01 from accessing CN02 summary with 403 FORBIDDEN")
        void utcid02() {
            stubRec(1L);
            assertEquals(HttpStatus.FORBIDDEN,
                    assertThrows(ResponseStatusException.class, () -> service.getSummary(2L, 10L)).getStatusCode());
        }

        @Test
        @DisplayName("UTCID03 (N) - Accessible branches return explicit assignments for staff and block unassigned with 403")
        void utcid03() {
            stubAdmin();
            when(branchRepository.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(cn1, cn2));
            when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.empty());
            assertEquals(2, ((List<?>) service.getAccessibleBranches(1L).get("branches")).size());

            stubRec(null);
            when(taskRepository.findExplicitBranchIdsByUserId(10L)).thenReturn(List.of(1L, 2L));
            when(staffRepository.findByUser_UserId(10L)).thenReturn(Optional.of(recStaff));
            Map<String, Object> staffRes = service.getAccessibleBranches(10L);
            assertEquals(2, ((List<?>) staffRes.get("branches")).size());
            assertTrue((Boolean) staffRes.get("branchSelectionAllowed"));

            when(taskRepository.findExplicitBranchIdsByUserId(10L)).thenReturn(List.of());
            assertEquals(HttpStatus.FORBIDDEN,
                    assertThrows(ResponseStatusException.class, () -> service.getAccessibleBranches(10L)).getStatusCode());
            verify(taskRepository, never()).findInferredBranchIdsByStaffId(any());
        }

        @Test
        @DisplayName("UTCID04 (N) - Calculate KPI summary: total=5, overdue=1, retry=1, waitDoc=1, respDoc=1, done=20%")
        void utcid04() {
            stubRec(1L);
            LocalDate today = LocalDate.now(CLINIC_ZONE);
            CustomerCareTask t1 = task(1L, CustomerCareTaskStatus.PENDING, today.minusDays(2));
            CustomerCareTask t2 = task(2L, CustomerCareTaskStatus.IN_PROGRESS, today);
            CustomerCareTask t3 = task(3L, CustomerCareTaskStatus.ESCALATED_TO_DOCTOR, today.plusDays(1));
            CustomerCareTask t4 = task(4L, CustomerCareTaskStatus.ESCALATED_TO_DOCTOR, today.plusDays(2));
            t4.setDoctorResponse("Prescribed analgesic");
            CustomerCareTask t5 = task(5L, CustomerCareTaskStatus.COMPLETED, today);
            CustomerCareInteraction retry = new CustomerCareInteraction();
            retry.setTask(t2); retry.setNextRetryAt(LocalDateTime.now().plusHours(2));

            when(staffRepository.findByUser_UserId(10L)).thenReturn(Optional.of(recStaff));
            when(taskRepository.findAllByBranchWithDetails(1L)).thenReturn(List.of(t1, t2, t3, t4, t5));
            when(interactionRepository.findAllByBranchOrderByCreatedAtDesc(1L)).thenReturn(List.of(retry));

            Map<String, Object> summary = service.getSummary(1L, 10L);
            assertEquals(5L, summary.get("totalTasks"));
            assertEquals(1L, summary.get("overdueTasks"));
            assertEquals(1L, summary.get("retryPendingCount"));
            assertEquals(1L, summary.get("waitingDoctorResponseCount"));
            assertEquals(1L, summary.get("doctorRespondedCount"));
            assertEquals(20, summary.get("completionRate"));
        }

        @Test
        @DisplayName("UTCID05 (N) - Search tasks validates invalid dates/branch (400), prioritizes overdue, and filters keywords")
        void utcid05() {
            stubAdmin();
            LocalDate today = LocalDate.now(CLINIC_ZONE);
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () ->
                            service.searchTasks(1L, "ALL", null, null, null, today.plusDays(1), today, null, PageRequest.of(0, 10), 1L))
                            .getStatusCode());
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () ->
                            service.searchTasks(-1L, "ALL", null, null, null, null, null, null, PageRequest.of(0, 10), 1L))
                            .getStatusCode());

            CustomerCareTask overdue = task(1L, CustomerCareTaskStatus.PENDING, today.minusDays(1));
            overdue.setNotes("[PHAN_ANH] Swelling");
            CustomerCareTask upcoming = task(2L, CustomerCareTaskStatus.PENDING, today.plusDays(3));
            CustomerCareTask completed = task(3L, CustomerCareTaskStatus.COMPLETED, today.minusDays(2));
            when(taskRepository.findAllByBranchWithDetails(1L)).thenReturn(List.of(completed, upcoming, overdue));
            when(interactionRepository.findAllByBranchOrderByCreatedAtDesc(1L)).thenReturn(List.of());

            Page<CustomerCareTaskDTO> actionable = service.searchTasks(
                    1L, "ACTIONABLE", null, null, null, null, null, null, PageRequest.of(0, 10), 1L);
            assertEquals(2, actionable.getTotalElements());
            assertEquals(1L, actionable.getContent().get(0).getTaskId());
            Page<CustomerCareTaskDTO> emptyPage = service.searchTasks(
                    1L, "ALL", null, null, null, null, null, null, PageRequest.of(5, 10), 1L);
            assertTrue(emptyPage.getContent().isEmpty());
        }

        @Test
        @DisplayName("UTCID06 (N) - Search tab MY_TASKS for doctor 20L includes tasks escalated to that doctor")
        void utcid06() {
            when(userRepository.findById(20L)).thenReturn(Optional.of(docUser));
            when(userRepository.hasActiveAdminRole(20L)).thenReturn(false);
            when(userRepository.hasActivePermission(20L, "CUSTOMER_CARE_MANAGE")).thenReturn(false);
            when(taskRepository.findActiveRoleNamesByUserId(20L)).thenReturn(List.of("BAC_SI"));
            when(branchRepository.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(cn1));
            when(taskRepository.findExplicitBranchIdsByUserId(20L)).thenReturn(List.of(1L));

            CustomerCareTask escalated = task(1L, CustomerCareTaskStatus.ESCALATED_TO_DOCTOR, LocalDate.now(CLINIC_ZONE));
            escalated.setEscalatedToDoctor(docStaff);
            when(staffRepository.findByUser_UserId(20L)).thenReturn(Optional.of(docStaff));
            when(taskRepository.findAllByBranchWithDetails(1L)).thenReturn(List.of(escalated));
            when(interactionRepository.findAllByBranchOrderByCreatedAtDesc(1L)).thenReturn(List.of());

            Page<CustomerCareTaskDTO> page = service.searchTasks(1L, "MY_TASKS", null, null, null, null, null, null, null, null, null, false, PageRequest.of(0, 10), 20L);
            assertEquals(1, page.getTotalElements());
            assertEquals(1L, page.getContent().get(0).getTaskId());
        }

        @Test
        @DisplayName("UTCID07 (N) - Create task saves extended group INSTALLMENT_REMINDER (id=99L) without ENUM error")
        void utcid07() {
            stubRec(1L);
            when(branchRepository.findById(1L)).thenReturn(Optional.of(cn1));
            when(patientRepository.findById(1000L)).thenReturn(Optional.of(patient));
            when(staffRepository.findById(1L)).thenReturn(Optional.of(recStaff));

            CustomerCareCreateDTO dto = new CustomerCareCreateDTO();
            dto.setBranchId(1L); dto.setProfileId(1000L); dto.setAssignedStaffId(1L);
            dto.setCareGroup("INSTALLMENT_REMINDER"); dto.setDueDate(LocalDate.now().plusDays(1));
            dto.setNotes("Nhắc thanh toán đợt 2");

            CustomerCareTaskDTO res = service.createTask(dto, 10L);
            assertEquals(99L, res.getTaskId());
            assertEquals("INSTALLMENT_REMINDER", res.getCareGroup());
            verify(taskRepository).save(any(CustomerCareTask.class));
        }

        @Test
        @DisplayName("UTCID08 (N) - Create task blocks active visit duplicate and allows FEEDBACK_COMPLAINT after completion")
        void utcid08() {
            stubAdmin();
            when(branchRepository.findById(1L)).thenReturn(Optional.of(cn1));
            when(patientRepository.findById(1000L)).thenReturn(Optional.of(patient));

            Visit v = new Visit(); v.setVisitId(300L); v.setPatientProfile(patient);
            CustomerCareTask existing = task(50L, CustomerCareTaskStatus.PENDING, LocalDate.now());
            existing.setVisit(v);
            when(taskRepository.findByPatientProfileIdWithDetails(1000L)).thenReturn(List.of(existing));

            CustomerCareCreateDTO dto = new CustomerCareCreateDTO();
            dto.setBranchId(1L); dto.setProfileId(1000L); dto.setVisitId(300L);
            dto.setDueDate(LocalDate.of(2026, 6, 15)); dto.setCareGroup("POST_TREATMENT_FOLLOWUP");
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> service.createTask(dto, 1L)).getStatusCode());

            existing.setStatus(CustomerCareTaskStatus.COMPLETED);
            dto.setCareGroup("FEEDBACK_COMPLAINT");
            when(visitRepository.findWithDetailsById(300L)).thenReturn(Optional.of(v));
            CustomerCareTaskDTO res = service.createTask(dto, 1L);
            assertEquals("FEEDBACK_COMPLAINT", res.getCareGroup());
            verify(notificationService).notifyTaskCreated(any(CustomerCareTask.class), eq(1L), anyString(), eq(false));
        }

        @Test
        @DisplayName("UTCID09 (A) - Create task rejects duplicate active task for Appointment 50L with 400 BAD_REQUEST")
        void utcid09() {
            stubAdmin();
            when(branchRepository.findById(1L)).thenReturn(Optional.of(cn1));
            when(patientRepository.findById(1000L)).thenReturn(Optional.of(patient));

            Appointment appt = new Appointment(); appt.setAppointmentId(50L);
            CustomerCareTask active = task(1L, CustomerCareTaskStatus.PENDING, LocalDate.now());
            active.setTaskType(CustomerCareTaskType.APPOINTMENT_REMINDER); active.setAppointment(appt);
            when(taskRepository.findByPatientProfileIdWithDetails(1000L)).thenReturn(List.of(active));

            CustomerCareCreateDTO dto = new CustomerCareCreateDTO();
            dto.setBranchId(1L); dto.setProfileId(1000L); dto.setAppointmentId(50L);
            dto.setCareGroup("APPOINTMENT_REMINDER"); dto.setDueDate(LocalDate.now());
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> service.createTask(dto, 1L)).getStatusCode());
            verify(taskRepository, never()).save(any());
        }

        @Test
        @DisplayName("UTCID10 (A) - Create task rejects duplicate active task for Prescription 60L with 400 BAD_REQUEST")
        void utcid10() {
            stubAdmin();
            when(branchRepository.findById(1L)).thenReturn(Optional.of(cn1));
            when(patientRepository.findById(1000L)).thenReturn(Optional.of(patient));

            Prescription pr = new Prescription(); pr.setPrescriptionId(60L);
            CustomerCareTask active = task(1L, CustomerCareTaskStatus.IN_PROGRESS, LocalDate.now());
            active.setTaskType(CustomerCareTaskType.MEDICATION_REMINDER); active.setPrescription(pr);
            when(taskRepository.findByPatientProfileIdWithDetails(1000L)).thenReturn(List.of(active));

            CustomerCareCreateDTO dto = new CustomerCareCreateDTO();
            dto.setBranchId(1L); dto.setProfileId(1000L); dto.setPrescriptionId(60L);
            dto.setCareGroup("MEDICATION_REMINDER"); dto.setDueDate(LocalDate.now());
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> service.createTask(dto, 1L)).getStatusCode());
            verify(taskRepository, never()).save(any());
        }

        @Test
        @DisplayName("UTCID11 (N) - Update task blocks closed task (409), updates open task, clears staff when 0L, and notifies")
        void utcid11() {
            stubAdmin();
            when(taskRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(task(1L, CustomerCareTaskStatus.CANCELLED, LocalDate.now())));
            assertEquals(HttpStatus.CONFLICT,
                    assertThrows(ResponseStatusException.class, () -> service.updateTask(1L, new CustomerCareCreateDTO(), 1L)).getStatusCode());

            CustomerCareTask open = task(2L, CustomerCareTaskStatus.PENDING, LocalDate.now());
            when(taskRepository.findByIdWithDetails(2L)).thenReturn(Optional.of(open));
            when(interactionRepository.findByTaskIdWithDetails(2L)).thenReturn(List.of());

            CustomerCareCreateDTO dto = new CustomerCareCreateDTO();
            dto.setCareGroup("NO_SERVICE_FOLLOWUP"); dto.setDueDate(LocalDate.of(2026, 8, 1));
            dto.setNotes("Follow up"); dto.setAssignedStaffId(0L);

            CustomerCareTaskDTO updated = service.updateTask(2L, dto, 1L);
            assertNull(open.getAssignedStaff());
            assertEquals(LocalDate.of(2026, 8, 1), updated.getDueDate());
            verify(notificationService).notifyTaskUpdated(open, 1L, "admin");
        }
    }

    // =========================================================================
    // 38. CareInteractEscalate (11 cases)
    // =========================================================================
    @Nested
    @DisplayName("CareInteractEscalate")
    class CareInteractEscalate {
        @Mock CustomerCareTaskRepository taskRepository;
        @Mock CustomerCareInteractionRepository interactionRepository;
        @Mock PatientProfileRepository patientRepository;
        @Mock BranchRepository branchRepository;
        @Mock StaffProfileRepository staffRepository;
        @Mock VisitRepository visitRepository;
        @Mock AppointmentRepository appointmentRepository;
        @Mock PrescriptionRepository prescriptionRepository;
        @Mock UserRepository userRepository;
        @Mock ClinicalExaminationRepository clinicalExaminationRepository;
        @Mock ExaminationDiagnosisRepository examinationDiagnosisRepository;
        @Mock CustomerCareNotificationService notificationService;
        @Mock SystemAuditLogService systemAuditLogService;

        CustomerCareServiceImpl service;
        Branch cn1;
        Branch cn2;
        User adminUser;
        User recUser;
        User docUser;
        StaffProfile recStaff;
        StaffProfile docStaff;
        PatientProfile patient;

        @BeforeEach
        void setUp() {
            service = new CustomerCareServiceImpl(
                    taskRepository, interactionRepository, patientRepository, branchRepository,
                    staffRepository, visitRepository, appointmentRepository, prescriptionRepository,
                    userRepository, clinicalExaminationRepository, examinationDiagnosisRepository);
            ReflectionTestUtils.setField(service, "notificationService", notificationService);
            ReflectionTestUtils.setField(service, "systemAuditLogService", systemAuditLogService);

            cn1 = branch(1L, "CN01", "Cơ sở 1", true);
            cn2 = branch(2L, "CN02", "Cơ sở 2", true);
            adminUser = activeUser(1L, "admin", UserType.STAFF, null);
            recUser = activeUser(10L, "letan01", UserType.STAFF, cn1);
            docUser = activeUser(20L, "bs01", UserType.STAFF, cn1);
            recStaff = activeStaff(100L, "LT01", "Nguyễn Lễ Tân", "Lễ tân CSKH", recUser);
            docStaff = activeStaff(200L, "BS01", "BS. Trần Minh", "Bác sĩ RHM", docUser);
            patient = new PatientProfile();
            patient.setProfileId(1000L);
            patient.setPatientCode("BN001");
            patient.setFullName("Phạm Thị Hương");
            patient.setPreferredBranch(cn1);

            lenient().when(taskRepository.save(any(CustomerCareTask.class))).thenAnswer(inv -> {
                CustomerCareTask t = inv.getArgument(0);
                if (t.getTaskId() == null) t.setTaskId(999L);
                return t;
            });
            lenient().when(interactionRepository.save(any(CustomerCareInteraction.class))).thenAnswer(inv -> inv.getArgument(0));
            lenient().when(taskRepository.existsByTaskCode(anyString())).thenReturn(false);
        }

        private void stubAdmin() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(adminUser));
            when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        }

        private void stubRec() {
            when(userRepository.findById(10L)).thenReturn(Optional.of(recUser));
            lenient().when(userRepository.hasActiveAdminRole(10L)).thenReturn(false);
            lenient().when(userRepository.hasActivePermission(10L, "CUSTOMER_CARE_MANAGE")).thenReturn(true);
            lenient().when(taskRepository.findActiveRoleNamesByUserId(10L)).thenReturn(List.of("LE_TAN"));
            lenient().when(branchRepository.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(cn1, cn2));
            lenient().when(taskRepository.findExplicitBranchIdsByUserId(10L)).thenReturn(List.of(1L));
        }

        private void stubDoc() {
            when(userRepository.findById(20L)).thenReturn(Optional.of(docUser));
            lenient().when(userRepository.hasActiveAdminRole(20L)).thenReturn(false);
            lenient().when(taskRepository.findActiveRoleNamesByUserId(20L)).thenReturn(List.of("BAC_SI"));
            lenient().when(branchRepository.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(cn1, cn2));
            lenient().when(taskRepository.findExplicitBranchIdsByUserId(20L)).thenReturn(List.of(1L));
        }

        private CustomerCareTask task(Long id, CustomerCareTaskStatus status) {
            CustomerCareTask t = new CustomerCareTask();
            t.setTaskId(id); t.setTaskCode("CSKH-" + id); t.setBranch(cn1); t.setPatientProfile(patient);
            t.setTaskType(CustomerCareTaskType.POST_TREATMENT_FOLLOWUP); t.setStatus(status);
            t.setDueDate(LocalDate.now()); t.setAssignedStaff(recStaff);
            return t;
        }

        @Test
        @DisplayName("UTCID01 (A) - Record interaction NO_ANSWER without nextRetryAt fails with 400 BAD_REQUEST")
        void utcid01() {
            stubRec();
            when(taskRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(task(1L, CustomerCareTaskStatus.PENDING)));
            CustomerCareInteractionCreateDTO dto = new CustomerCareInteractionCreateDTO();
            dto.setChannel("PHONE_CALL"); dto.setContactResult("NO_ANSWER"); dto.setNotes("No answer twice");
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> service.recordInteraction(1L, dto, 10L)).getStatusCode());
            verifyNoInteractions(interactionRepository);
        }

        @Test
        @DisplayName("UTCID02 (N) - Record interaction blocks closed (409), transitions BUSY->IN_PROGRESS and CONNECTED->COMPLETED")
        void utcid02() {
            stubRec();
            when(taskRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(task(1L, CustomerCareTaskStatus.COMPLETED)));
            CustomerCareInteractionCreateDTO shortDto = new CustomerCareInteractionCreateDTO();
            shortDto.setChannel("PHONE_CALL"); shortDto.setContactResult("CONNECTED"); shortDto.setNotes("ab");
            assertEquals(HttpStatus.CONFLICT,
                    assertThrows(ResponseStatusException.class, () -> service.recordInteraction(1L, shortDto, 10L)).getStatusCode());

            CustomerCareTask open = task(2L, CustomerCareTaskStatus.PENDING);
            when(taskRepository.findByIdWithDetails(2L)).thenReturn(Optional.of(open));
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> service.recordInteraction(2L, shortDto, 10L)).getStatusCode());

            CustomerCareInteractionCreateDTO busy = new CustomerCareInteractionCreateDTO();
            busy.setChannel("ZALO"); busy.setContactResult("BUSY"); busy.setNotes("Busy until afternoon");
            busy.setNextRetryAt(LocalDateTime.of(2026, 6, 20, 15, 30));
            when(staffRepository.findByUser_UserId(10L)).thenReturn(Optional.of(recStaff));
            when(interactionRepository.findByTaskIdWithDetails(2L)).thenReturn(List.of());
            service.recordInteraction(2L, busy, 10L);
            assertEquals(CustomerCareTaskStatus.IN_PROGRESS, open.getStatus());

            CustomerCareInteractionCreateDTO ok = new CustomerCareInteractionCreateDTO();
            ok.setChannel("PHONE_CALL"); ok.setContactResult("CONNECTED"); ok.setNotes("Patient recovered cleanly");
            service.recordInteraction(2L, ok, 10L);
            assertEquals(CustomerCareTaskStatus.COMPLETED, open.getStatus());
            verify(interactionRepository, times(2)).save(any());
        }

        @Test
        @DisplayName("UTCID03 (B) - Record interaction rejects taskId=0, null DTO, and notes > 2000 characters")
        void utcid03() {
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> service.recordInteraction(0L, new CustomerCareInteractionCreateDTO(), 10L)).getStatusCode());
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> service.recordInteraction(2L, null, 10L)).getStatusCode());
            stubRec();
            when(taskRepository.findByIdWithDetails(2L)).thenReturn(Optional.of(task(2L, CustomerCareTaskStatus.PENDING)));
            CustomerCareInteractionCreateDTO longDto = new CustomerCareInteractionCreateDTO();
            longDto.setChannel("PHONE_CALL"); longDto.setContactResult("CONNECTED"); longDto.setNotes("a".repeat(2001));
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> service.recordInteraction(2L, longDto, 10L)).getStatusCode());
        }

        @Test
        @DisplayName("UTCID04 (A) - Record interaction rejects invalid channel FAX, result UNKNOWN, or nextStatus INVALID_STATUS")
        void utcid04() {
            stubRec();
            when(taskRepository.findByIdWithDetails(2L)).thenReturn(Optional.of(task(2L, CustomerCareTaskStatus.PENDING)));
            CustomerCareInteractionCreateDTO d1 = new CustomerCareInteractionCreateDTO(); d1.setChannel("FAX"); d1.setContactResult("CONNECTED"); d1.setNotes("Valid notes");
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class, () -> service.recordInteraction(2L, d1, 10L)).getStatusCode());

            CustomerCareInteractionCreateDTO d2 = new CustomerCareInteractionCreateDTO(); d2.setChannel("SMS"); d2.setContactResult("UNKNOWN"); d2.setNotes("Valid notes");
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class, () -> service.recordInteraction(2L, d2, 10L)).getStatusCode());

            CustomerCareInteractionCreateDTO d3 = new CustomerCareInteractionCreateDTO(); d3.setChannel("IN_PERSON"); d3.setContactResult("CONNECTED"); d3.setNotes("Valid notes"); d3.setNextStatus("INVALID_STATUS");
            when(staffRepository.findByUser_UserId(10L)).thenReturn(Optional.of(recStaff));
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class, () -> service.recordInteraction(2L, d3, 10L)).getStatusCode());
        }

        @Test
        @DisplayName("UTCID05 (N) - Escalate rejects INACTIVE doctor (400), preserves initial notes for ACTIVE doctor, and notifies")
        void utcid05() {
            stubRec();
            CustomerCareTask t3 = task(3L, CustomerCareTaskStatus.PENDING);
            t3.setNotes("Ghi chú ban đầu");
            when(taskRepository.findByIdWithDetails(3L)).thenReturn(Optional.of(t3));

            CustomerCareEscalateDTO dto = new CustomerCareEscalateDTO();
            dto.setEscalatedToDoctorId(200L); dto.setEscalationReason("Đau nhức vùng răng hàm dưới");

            docStaff.setStatus(StaffStatus.INACTIVE);
            when(staffRepository.findById(200L)).thenReturn(Optional.of(docStaff));
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> service.escalateToDoctor(3L, dto, 10L)).getStatusCode());

            docStaff.setStatus(StaffStatus.ACTIVE);
            when(staffRepository.findByUser_UserId(10L)).thenReturn(Optional.of(recStaff));
            when(interactionRepository.findByTaskIdWithDetails(3L)).thenReturn(List.of());
            CustomerCareTaskDTO res = service.escalateToDoctor(3L, dto, 10L);
            assertEquals("ESCALATED_TO_DOCTOR", res.getStatus());
            assertEquals("Ghi chú ban đầu", t3.getNotes());
            verify(notificationService).notifyEscalatedToDoctor(t3, docStaff, 10L, "Nguyễn Lễ Tân");
        }

        @Test
        @DisplayName("UTCID06 (A) - Escalate validates args/reason length (400), closed task (409), and missing doctor (404)")
        void utcid06() {
            CustomerCareEscalateDTO shortR = new CustomerCareEscalateDTO(); shortR.setEscalatedToDoctorId(200L); shortR.setEscalationReason("abc");
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class, () -> service.escalateToDoctor(1L, shortR, 10L)).getStatusCode());

            stubRec();
            when(taskRepository.findByIdWithDetails(3L)).thenReturn(Optional.of(task(3L, CustomerCareTaskStatus.COMPLETED)));
            CustomerCareEscalateDTO ok = new CustomerCareEscalateDTO(); ok.setEscalatedToDoctorId(200L); ok.setEscalationReason("Gum swelling symptom");
            assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> service.escalateToDoctor(3L, ok, 10L)).getStatusCode());

            when(taskRepository.findByIdWithDetails(4L)).thenReturn(Optional.of(task(4L, CustomerCareTaskStatus.PENDING)));
            when(staffRepository.findById(200L)).thenReturn(Optional.empty());
            assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, () -> service.escalateToDoctor(4L, ok, 10L)).getStatusCode());
        }

        @Test
        @DisplayName("UTCID07 (N) - Escalation blocks receptionist from submitting clinical response (403) while Doctor completes it")
        void utcid07() {
            stubRec();
            CustomerCareTask t1 = task(1L, CustomerCareTaskStatus.PENDING);
            when(taskRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(t1));
            when(staffRepository.findById(200L)).thenReturn(Optional.of(docStaff));
            when(staffRepository.findByUser_UserId(10L)).thenReturn(Optional.of(recStaff));
            when(interactionRepository.findByTaskIdWithDetails(1L)).thenReturn(List.of());

            CustomerCareEscalateDTO esc = new CustomerCareEscalateDTO();
            esc.setEscalatedToDoctorId(200L); esc.setEscalationReason("Suspected antibiotic allergy");
            service.escalateToDoctor(1L, esc, 10L);

            CustomerCareEscalateDTO resp = new CustomerCareEscalateDTO();
            resp.setDoctorResponse("Switch to Paracetamol 500mg"); resp.setNextStatus("COMPLETED");
            assertEquals(HttpStatus.FORBIDDEN,
                    assertThrows(ResponseStatusException.class, () -> service.submitDoctorResponse(1L, resp, 10L)).getStatusCode());

            stubDoc();
            when(staffRepository.findByUser_UserId(20L)).thenReturn(Optional.of(docStaff));
            CustomerCareTaskDTO done = service.submitDoctorResponse(1L, resp, 20L);
            assertEquals("COMPLETED", done.getStatus());
        }

        @Test
        @DisplayName("UTCID08 (N) - Doctor response rejects short text 'ok' (400) and allows Admin override assigning doctor 200L")
        void utcid08() {
            CustomerCareEscalateDTO shortR = new CustomerCareEscalateDTO(); shortR.setDoctorResponse("ok");
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> service.submitDoctorResponse(1L, shortR, 1L)).getStatusCode());

            stubAdmin();
            CustomerCareTask t4 = task(4L, CustomerCareTaskStatus.ESCALATED_TO_DOCTOR);
            when(taskRepository.findByIdWithDetails(4L)).thenReturn(Optional.of(t4));
            when(staffRepository.findById(200L)).thenReturn(Optional.of(docStaff));
            when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.empty());
            when(interactionRepository.findByTaskIdWithDetails(4L)).thenReturn(List.of());

            CustomerCareEscalateDTO dto = new CustomerCareEscalateDTO();
            dto.setDoctorResponse("Reviewed patient chart and instructed care");
            dto.setEscalatedToDoctorId(200L); dto.setNextStatus("COMPLETED");

            CustomerCareTaskDTO res = service.submitDoctorResponse(4L, dto, 1L);
            assertEquals("COMPLETED", res.getStatus());
            assertEquals(200L, res.getEscalatedToDoctorId());
        }

        @Test
        @DisplayName("UTCID09 (N) - Reopen task blocks receptionist (403) and non-completed task (400); Admin reopens & audits")
        void utcid09() {
            stubRec();
            when(taskRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(task(10L, CustomerCareTaskStatus.COMPLETED)));
            assertEquals(HttpStatus.FORBIDDEN,
                    assertThrows(ResponseStatusException.class, () -> service.reopenTask(10L, "Reopen check", 10L, "127.0.0.1")).getStatusCode());

            stubAdmin();
            when(taskRepository.findByIdWithDetails(11L)).thenReturn(Optional.of(task(11L, CustomerCareTaskStatus.PENDING)));
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> service.reopenTask(11L, "Reopen check", 1L, "127.0.0.1")).getStatusCode());

            CustomerCareTask doneTask = task(10L, CustomerCareTaskStatus.COMPLETED);
            when(taskRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(doneTask));
            when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.empty());
            when(interactionRepository.findByTaskIdWithDetails(10L)).thenReturn(List.of());

            service.reopenTask(10L, "Patient reports recurring pain", 1L, "192.168.1.2");
            assertEquals(CustomerCareTaskStatus.IN_PROGRESS, doneTask.getStatus());
            verify(systemAuditLogService).logAction(eq(adminUser), eq(cn1),
                    eq("CUSTOMER_CARE_TASK_REOPEN"), eq("customer_care_tasks"), eq(10L), eq("192.168.1.2"), anyMap(), anyMap());
            verify(notificationService).notifyTaskReopened(doneTask, "Patient reports recurring pain", 1L, "admin");
        }

        @Test
        @DisplayName("UTCID10 (N) - Patient care history validates profileId (400), enforces branch isolation (403), and returns timeline")
        void utcid10() {
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> service.getPatientCareHistory(0L, 1L, 10L)).getStatusCode());

            stubRec();
            PatientProfile p2 = new PatientProfile(); p2.setProfileId(200L); p2.setPreferredBranch(cn2);
            when(patientRepository.findById(200L)).thenReturn(Optional.of(p2));
            assertEquals(HttpStatus.FORBIDDEN,
                    assertThrows(ResponseStatusException.class, () -> service.getPatientCareHistory(200L, 1L, 10L)).getStatusCode());

            when(patientRepository.findById(1000L)).thenReturn(Optional.of(patient));
            CustomerCareTask t1 = task(1L, CustomerCareTaskStatus.PENDING);
            CustomerCareInteraction i1 = new CustomerCareInteraction();
            i1.setInteractionId(5L); i1.setTask(t1); i1.setPatientProfile(patient); i1.setStaff(recStaff);
            i1.setChannel(InteractionChannel.ZALO); i1.setContactResult(ContactResult.CONNECTED); i1.setNotes("Sent care guide");
            when(taskRepository.findByPatientProfileIdWithDetails(1000L)).thenReturn(List.of(t1));
            when(interactionRepository.findByProfileIdWithDetails(1000L)).thenReturn(List.of(i1));

            Map<String, Object> history = service.getPatientCareHistory(1000L, 1L, 10L);
            assertEquals(1, history.get("totalTasks"));
            assertEquals(1, history.get("totalInteractions"));
        }

        @Test
        @DisplayName("UTCID11 (N) - Record visit interaction auto-creates task 999L when Visit 77L has no task yet")
        void utcid11() {
            stubDoc();
            Visit v = new Visit(); v.setVisitId(77L); v.setVisitCode("LK-77");
            v.setBranch(cn1); v.setPatientProfile(patient); v.setPrimaryDoctor(docStaff);

            when(visitRepository.findWithDetailsById(77L)).thenReturn(Optional.of(v));
            when(taskRepository.findByVisit_VisitId(77L)).thenReturn(List.of());
            when(taskRepository.findByPatientProfileIdWithDetails(1000L)).thenReturn(List.of());
            when(taskRepository.findByIdWithDetails(999L)).thenReturn(Optional.of(task(999L, CustomerCareTaskStatus.IN_PROGRESS)));
            when(staffRepository.findByUser_UserId(20L)).thenReturn(Optional.of(docStaff));
            when(interactionRepository.findByTaskIdWithDetails(999L)).thenReturn(List.of());

            CustomerCareInteractionCreateDTO dto = new CustomerCareInteractionCreateDTO();
            dto.setChannel("PHONE_CALL"); dto.setContactResult("CONNECTED"); dto.setNotes("Doctor follow-up call");
            CustomerCareTaskDTO res = service.recordVisitInteraction(77L, dto, 20L);
            assertEquals(999L, res.getTaskId());
            verify(taskRepository, times(2)).save(any(CustomerCareTask.class));
            verify(interactionRepository).save(any(CustomerCareInteraction.class));
        }
    }

    // =========================================================================
    // 39. CareAutoAndNotify (10 cases)
    // =========================================================================
    @Nested
    @DisplayName("CareAutoAndNotify")
    class CareAutoAndNotify {
        @Mock CustomerCareTaskRepository taskRepository;
        @Mock VisitRepository visitRepository;
        @Mock PrescriptionRepository prescriptionRepository;
        @Mock AppointmentRepository appointmentRepository;
        @Mock BranchRepository branchRepository;
        @Mock StaffProfileRepository staffRepository;
        @Mock CustomerCareNotificationService notificationServiceMock;
        @Mock LeaveRequestRepository leaveRequestRepository;
        @Mock TreatmentPlanInstallmentRepository installmentRepository;

        @Mock CustomerCareInteractionRepository interactionRepository;
        @Mock PatientProfileRepository patientRepository;
        @Mock UserRepository userRepository;
        @Mock ClinicalExaminationRepository clinicalExaminationRepository;
        @Mock ExaminationDiagnosisRepository examinationDiagnosisRepository;
        @Mock SystemNotificationRepository systemNotificationRepository;
        @Mock ApplicationEventPublisher eventPublisher;
        @Mock SimpMessagingTemplate messagingTemplate;

        CustomerCareAutomationService automationService;
        CustomerCareNotificationService notificationService;
        CustomerCareServiceImpl careService;

        Branch cn1;
        Branch cn2;
        PatientProfile patient;
        StaffProfile recStaff;
        StaffProfile docStaff;

        @BeforeEach
        void setUp() {
            automationService = new CustomerCareAutomationService(
                    taskRepository, visitRepository, prescriptionRepository, appointmentRepository,
                    branchRepository, staffRepository, notificationServiceMock,
                    leaveRequestRepository, installmentRepository);
            notificationService = new CustomerCareNotificationService(
                    systemNotificationRepository, taskRepository, userRepository, eventPublisher, messagingTemplate);
            careService = new CustomerCareServiceImpl(
                    taskRepository, interactionRepository, patientRepository, branchRepository,
                    staffRepository, visitRepository, appointmentRepository, prescriptionRepository,
                    userRepository, clinicalExaminationRepository, examinationDiagnosisRepository);
            ReflectionTestUtils.setField(careService, "notificationService", notificationServiceMock);

            cn1 = branch(1L, "CN01", "Cơ sở 1", true);
            cn2 = branch(2L, "CN02", "Cơ sở 2", true);
            patient = new PatientProfile(); patient.setProfileId(100L); patient.setFullName("Trần Thị B");
            recStaff = activeStaff(20L, "LT01", "Lễ Tân", "Lễ tân CSKH", null);
            docStaff = activeStaff(21L, "BS01", "Bác Sĩ", "Bác sĩ RHM", null);

            lenient().when(taskRepository.existsByTaskCode(anyString())).thenReturn(false);
            lenient().when(taskRepository.save(any(CustomerCareTask.class))).thenAnswer(inv -> {
                CustomerCareTask t = inv.getArgument(0);
                if (t.getTaskId() == null) t.setTaskId(99L);
                return t;
            });
        }

        @Test
        @DisplayName("UTCID01 (B) - Create POST_TREATMENT_FOLLOWUP assigns non-doctor staff and clamps past dueDate to today")
        void utcid01() {
            Visit v = new Visit(); v.setVisitId(10L); v.setVisitCode("LK-10"); v.setStatus(VisitStatus.COMPLETED);
            v.setPatientProfile(patient); v.setBranch(cn1); v.setCheckInAt(LocalDateTime.now(CLINIC_ZONE).minusDays(5));
            when(visitRepository.findWithDetailsById(10L)).thenReturn(Optional.of(v));
            when(taskRepository.existsByVisit_VisitIdAndTaskType(10L, CustomerCareTaskType.POST_TREATMENT_FOLLOWUP)).thenReturn(false);
            when(taskRepository.findActiveStaffIdsByBranch(1L)).thenReturn(List.of(21L, 20L));
            when(staffRepository.findById(21L)).thenReturn(Optional.of(docStaff));
            when(staffRepository.findById(20L)).thenReturn(Optional.of(recStaff));

            assertTrue(automationService.createPostTreatmentTaskIfMissing(10L));
            ArgumentCaptor<CustomerCareTask> cap = ArgumentCaptor.forClass(CustomerCareTask.class);
            verify(taskRepository).save(cap.capture());
            assertEquals(recStaff, cap.getValue().getAssignedStaff());
            assertEquals(LocalDate.now(CLINIC_ZONE), cap.getValue().getDueDate());
            verify(notificationServiceMock).notifyTaskCreated(cap.getValue(), null, "Hệ thống tự động", true);
        }

        @Test
        @DisplayName("UTCID02 (N) - Medication reminder skips CANCELLED visit (false) and creates task due 2026-06-01")
        void utcid02() {
            Visit cancelled = new Visit(); cancelled.setStatus(VisitStatus.CANCELLED);
            Prescription pr1 = new Prescription(); pr1.setPrescriptionId(1L); pr1.setVisit(cancelled);
            when(prescriptionRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(pr1));
            assertFalse(automationService.createMedicationReminderIfMissing(1L));

            Visit okVisit = new Visit(); okVisit.setStatus(VisitStatus.COMPLETED);
            Prescription pr2 = new Prescription(); pr2.setPrescriptionId(2L); pr2.setPrescriptionCode("DT-2");
            pr2.setPatientProfile(patient); pr2.setBranch(cn1); pr2.setVisit(okVisit); pr2.setFollowupDate(LocalDate.of(2026, 6, 1));
            when(prescriptionRepository.findByIdWithDetails(2L)).thenReturn(Optional.of(pr2));
            when(taskRepository.findActiveStaffIdsByBranch(1L)).thenReturn(List.of());
            when(staffRepository.findByStatus(StaffStatus.ACTIVE)).thenReturn(List.of(recStaff));

            assertTrue(automationService.createMedicationReminderIfMissing(2L));
            verify(taskRepository, times(1)).save(argThat(t -> LocalDate.of(2026, 6, 1).equals(t.getDueDate())));
        }

        @Test
        @DisplayName("UTCID03 (N) - Appointment reminder skips COMPLETED (false) and schedules CONFIRMED reminder 1 day prior")
        void utcid03() {
            Appointment a1 = new Appointment(); a1.setAppointmentId(1L); a1.setStatus(AppointmentStatus.COMPLETED);
            when(appointmentRepository.findById(1L)).thenReturn(Optional.of(a1));
            assertFalse(automationService.createAppointmentReminderIfMissing(1L));

            Appointment a2 = new Appointment(); a2.setAppointmentId(2L); a2.setAppointmentCode("LH-2");
            a2.setPatientProfile(patient); a2.setBranch(cn1); a2.setStatus(AppointmentStatus.CONFIRMED);
            a2.setAppointmentDate(LocalDate.of(2026, 7, 15));
            when(appointmentRepository.findById(2L)).thenReturn(Optional.of(a2));
            when(taskRepository.findActiveStaffIdsByBranch(1L)).thenReturn(List.of(20L));
            when(staffRepository.findById(20L)).thenReturn(Optional.of(recStaff));

            assertTrue(automationService.createAppointmentReminderIfMissing(2L));
            verify(taskRepository, times(1)).save(argThat(t -> LocalDate.of(2026, 7, 14).equals(t.getDueDate())));
        }

        @Test
        @DisplayName("UTCID04 (N) - No-show recall ignores CONFIRMED (false) and creates NO_SHOW_RECALL due today")
        void utcid04() {
            Appointment a1 = new Appointment(); a1.setAppointmentId(1L); a1.setStatus(AppointmentStatus.CONFIRMED);
            when(appointmentRepository.findById(1L)).thenReturn(Optional.of(a1));
            assertFalse(automationService.createNoShowRecallIfMissing(1L));

            Appointment a2 = new Appointment(); a2.setAppointmentId(2L); a2.setAppointmentCode("LH-NS");
            a2.setPatientProfile(patient); a2.setBranch(cn1); a2.setStatus(AppointmentStatus.NO_SHOW);
            when(appointmentRepository.findById(2L)).thenReturn(Optional.of(a2));
            when(taskRepository.findActiveStaffIdsByBranch(1L)).thenReturn(List.of(20L));
            when(staffRepository.findById(20L)).thenReturn(Optional.of(recStaff));

            assertTrue(automationService.createNoShowRecallIfMissing(2L));
            verify(taskRepository, times(1)).save(argThat(t -> t.getTaskType() == CustomerCareTaskType.NO_SHOW_RECALL));
        }

        @Test
        @DisplayName("UTCID05 (N) - Catch-up job isolates DB error on CN2 and completes creating 2 tasks for CN1")
        void utcid05() {
            when(branchRepository.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(cn2, cn1));
            when(taskRepository.findCompletedVisitsByBranchForCare(2L)).thenThrow(new RuntimeException("CN2 error"));

            Visit v = new Visit(); v.setVisitId(100L); v.setStatus(VisitStatus.COMPLETED); v.setPatientProfile(patient); v.setBranch(cn1);
            Appointment ns = new Appointment(); ns.setAppointmentId(200L); ns.setStatus(AppointmentStatus.NO_SHOW); ns.setPatientProfile(patient); ns.setBranch(cn1);
            when(taskRepository.findCompletedVisitsByBranchForCare(1L)).thenReturn(List.of(v));
            when(taskRepository.findValidPrescriptionsByBranchForCare(1L)).thenReturn(List.of());
            when(appointmentRepository.findByBranchWithDetails(1L)).thenReturn(List.of(ns));
            when(visitRepository.findWithDetailsById(100L)).thenReturn(Optional.of(v));
            when(appointmentRepository.findById(200L)).thenReturn(Optional.of(ns));
            when(taskRepository.findActiveStaffIdsByBranch(1L)).thenReturn(List.of(20L));
            when(staffRepository.findById(20L)).thenReturn(Optional.of(recStaff));

            automationService.catchUpMissedTasks();
            verify(taskRepository, times(2)).save(any(CustomerCareTask.class));
        }

        @Test
        @DisplayName("UTCID06 (N) - Auto-generate tasks requires branchId for Admin (400) and generates 4 missing types for CN1")
        void utcid06() {
            User admin = activeUser(1L, "admin", UserType.STAFF, null);
            when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
            when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class, () -> careService.autoGenerateTasks(null, 1L)).getStatusCode());

            when(branchRepository.findById(1L)).thenReturn(Optional.of(cn1));
            when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(recStaff));
            Visit v = new Visit(); v.setVisitId(1L); v.setPatientProfile(patient); v.setBranch(cn1);
            Prescription pr = new Prescription(); pr.setPrescriptionId(2L); pr.setPatientProfile(patient); pr.setBranch(cn1);
            Appointment aUp = new Appointment(); aUp.setAppointmentId(3L); aUp.setStatus(AppointmentStatus.CONFIRMED); aUp.setAppointmentDate(LocalDate.now().plusDays(2)); aUp.setPatientProfile(patient); aUp.setBranch(cn1);
            Appointment aNs = new Appointment(); aNs.setAppointmentId(4L); aNs.setStatus(AppointmentStatus.NO_SHOW); aNs.setPatientProfile(patient); aNs.setBranch(cn1);

            when(taskRepository.findCompletedVisitsByBranchForCare(1L)).thenReturn(List.of(v));
            when(taskRepository.findValidPrescriptionsByBranchForCare(1L)).thenReturn(List.of(pr));
            when(appointmentRepository.findByBranchWithDetails(1L)).thenReturn(List.of(aUp, aNs));

            Map<String, Object> res = careService.autoGenerateTasks(1L, 1L);
            assertEquals(4, res.get("totalCreated"));
            verify(taskRepository, times(4)).save(any(CustomerCareTask.class));
        }

        @Test
        @DisplayName("UTCID07 (N) - Notify task created filters CN1 users, excludes manual actor 99L, and sets actionUrl")
        void utcid07() {
            User cn1User = activeUser(10L, "letan01", UserType.STAFF, cn1);
            User cn2User = activeUser(20L, "letan02", UserType.STAFF, cn2);
            CustomerCareTask t = new CustomerCareTask(); t.setTaskId(500L); t.setTaskCode("CSKH-500"); t.setBranch(cn1); t.setPatientProfile(patient);

            when(userRepository.findActiveReceptionistsAndAdminsByBranch(1L)).thenReturn(List.of(cn1User, cn2User));
            when(userRepository.findById(10L)).thenReturn(Optional.of(cn1User));

            notificationService.notifyTaskCreated(t, 99L, "Admin", false);
            verify(systemNotificationRepository, times(1)).save(argThat(n ->
                    "/customer-care?taskId=500".equals(n.getActionUrl()) && Long.valueOf(10L).equals(n.getUser().getUserId())));
            verify(eventPublisher).publishEvent(any(CustomerCareRealtimeBroadcastEvent.class));
        }

        @Test
        @DisplayName("UTCID08 (N) - Notify escalation ignores null doctor and persists HIGH priority notification for active doctor 300L")
        void utcid08() {
            CustomerCareTask t = new CustomerCareTask(); t.setTaskId(500L); t.setTaskCode("CSKH-500"); t.setBranch(cn1); t.setPatientProfile(patient);
            notificationService.notifyEscalatedToDoctor(t, null, 10L, "Receptionist");
            verifyNoInteractions(systemNotificationRepository);

            User du = activeUser(30L, "bs30", UserType.STAFF, cn1);
            StaffProfile dp = activeStaff(300L, "BS300", "Doctor", "Doctor", du);
            when(userRepository.findById(30L)).thenReturn(Optional.of(du));
            notificationService.notifyEscalatedToDoctor(t, dp, 10L, "Receptionist");

            verify(systemNotificationRepository).save(argThat(n -> "HIGH".equals(n.getPriorityLevel())));
            verify(eventPublisher).publishEvent(any(CustomerCareRealtimeBroadcastEvent.class));
        }

        @Test
        @DisplayName("UTCID09 (A) - Mark notification as read throws 404 for 99L and persists read state once for 1L")
        void utcid09() {
            when(systemNotificationRepository.findByNotificationIdAndUser_UserId(99L, 10L)).thenReturn(Optional.empty());
            assertEquals(HttpStatus.NOT_FOUND,
                    assertThrows(ResponseStatusException.class, () -> notificationService.markAsRead(99L, 10L)).getStatusCode());

            SystemNotification n = new SystemNotification(); n.setNotificationId(1L); n.setTitle("New CSKH"); n.setTaskId(500L); n.setIsRead(false);
            when(systemNotificationRepository.findByNotificationIdAndUser_UserId(1L, 10L)).thenReturn(Optional.of(n));
            when(taskRepository.findById(500L)).thenReturn(Optional.empty());

            notificationService.markAsRead(1L, 10L);
            n.setReadAt(LocalDateTime.now());
            notificationService.markAsRead(1L, 10L);
            verify(systemNotificationRepository, times(1)).save(n);
        }

        @Test
        @DisplayName("UTCID10 (N) - Realtime STOMP broadcast pushes to branch, admin, user queue and swallows broker failures")
        void utcid10() {
            User u = activeUser(10L, "letan01", UserType.STAFF, cn1);
            when(userRepository.findById(10L)).thenReturn(Optional.of(u));
            CustomerCareRealtimeEventDTO payload = CustomerCareRealtimeEventDTO.builder()
                    .eventType("CUSTOMER_CARE_TASK_CREATED").branchId(1L).taskId(500L).build();

            notificationService.onRealtimeBroadcast(new CustomerCareRealtimeBroadcastEvent(payload, List.of(10L), true));
            verify(messagingTemplate).convertAndSend("/topic/customer-care/branch/1", payload);
            verify(messagingTemplate).convertAndSend("/topic/customer-care/admin", payload);
            verify(messagingTemplate).convertAndSendToUser(eq("letan01"), eq("/queue/customer-care/notifications"), any());

            doThrow(new RuntimeException("Broker down")).when(messagingTemplate).convertAndSend(eq("/topic/customer-care/branch/1"), eq(payload));
            assertDoesNotThrow(() -> notificationService.onRealtimeBroadcast(new CustomerCareRealtimeBroadcastEvent(payload, List.of(), true)));
        }
    }

    // =========================================================================
    // 40. ChatAccessAndRooms (9 cases)
    // =========================================================================
    @Nested
    @DisplayName("ChatAccessAndRooms")
    class ChatAccessAndRooms {
        @Mock ChatConversationRepository conversationRepository;
        @Mock ChatMessageRepository messageRepository;
        @Mock ChatMessageUserStateRepository messageUserStateRepository;
        @Mock ChatConversationUserStateRepository conversationUserStateRepository;
        @Mock UserRepository userRepository;
        @Mock BranchRepository branchRepository;
        @Mock StaffProfileRepository staffProfileRepository;
        @Mock PatientProfileRepository patientProfileRepository;
        @Mock CustomerCareTaskRepository customerCareTaskRepository;

        ChatServiceImpl chatService;
        Branch cn1; Branch cn2;
        User adminUser; User recUser; User docUser; User whUser; User patUser;
        StaffProfile recStaff; StaffProfile docStaff; StaffProfile whStaff;
        PatientProfile patProfile;

        @BeforeEach
        void setUp() {
            chatService = new ChatServiceImpl(
                    conversationRepository, messageRepository, messageUserStateRepository,
                    conversationUserStateRepository, userRepository, branchRepository,
                    staffProfileRepository, patientProfileRepository, customerCareTaskRepository);
            cn1 = branch(1L, "CN01", "Cơ sở 1", true);
            cn2 = branch(2L, "CN02", "Cơ sở 2", true);
            adminUser = activeUser(1L, "admin", UserType.STAFF, null);
            recUser = activeUser(10L, "letan01", UserType.STAFF, cn1);
            docUser = activeUser(20L, "bs01", UserType.STAFF, cn1);
            whUser = activeUser(30L, "kho01", UserType.STAFF, cn1);
            patUser = activeUser(40L, "bn01", UserType.PATIENT, cn1);
            recStaff = activeStaff(100L, "LT01", "Nguyễn Lễ Tân", "Lễ tân", recUser);
            docStaff = activeStaff(200L, "BS01", "BS. Trần Minh", "Bác sĩ RHM", docUser);
            whStaff = activeStaff(300L, "KHO01", "Lê Quản Lý Kho", "Quản lý kho", whUser);
            patProfile = new PatientProfile(); patProfile.setProfileId(400L); patProfile.setPatientCode("BN-400");
            patProfile.setFullName("Phạm Bệnh Nhân"); patProfile.setPreferredBranch(cn1);

            lenient().when(branchRepository.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(cn1, cn2));
            lenient().when(branchRepository.findById(1L)).thenReturn(Optional.of(cn1));
            lenient().when(conversationRepository.save(any(ChatConversation.class))).thenAnswer(inv -> inv.getArgument(0));
        }

        private void stubRole(User u, String role, StaffProfile sp, PatientProfile pp) {
            lenient().when(userRepository.findById(u.getUserId())).thenReturn(Optional.of(u));
            lenient().when(userRepository.hasActiveAdminRole(u.getUserId())).thenReturn("ADMIN".equals(role));
            lenient().when(conversationRepository.findActiveRoleNamesByUserId(u.getUserId())).thenReturn(List.of(role));
            lenient().when(staffProfileRepository.findByUser_UserId(u.getUserId())).thenReturn(Optional.ofNullable(sp));
            lenient().when(patientProfileRepository.findByUser_UserIdOrderByIsDefaultDescProfileIdDesc(u.getUserId()))
                    .thenReturn(pp != null ? List.of(pp) : List.of());
        }

        @Test
        @DisplayName("UTCID01 (A) - Require inbox access blocks patient 40L (403) and returns QUAN_LY_KHO for warehouse 30L")
        void utcid01() {
            stubRole(patUser, "BENH_NHAN", null, patProfile);
            assertEquals(HttpStatus.FORBIDDEN,
                    assertThrows(ResponseStatusException.class, () -> chatService.requireInboxAccess(40L)).getStatusCode());

            stubRole(whUser, "QUAN_LY_KHO", whStaff, null);
            assertEquals("QUAN_LY_KHO", chatService.requireInboxAccess(30L));
        }

        @Test
        @DisplayName("UTCID02 (A) - Require inbox access rejects missing (401), inactive (403), and LOCKED (403) accounts")
        void utcid02() {
            when(userRepository.findById(999L)).thenReturn(Optional.empty());
            assertEquals(HttpStatus.UNAUTHORIZED,
                    assertThrows(ResponseStatusException.class, () -> chatService.requireInboxAccess(999L)).getStatusCode());

            User inactive = activeUser(998L, "inact", UserType.STAFF, cn1); inactive.setIsActive(false);
            when(userRepository.findById(998L)).thenReturn(Optional.of(inactive));
            assertEquals(HttpStatus.FORBIDDEN,
                    assertThrows(ResponseStatusException.class, () -> chatService.requireInboxAccess(998L)).getStatusCode());

            User locked = activeUser(997L, "lock", UserType.STAFF, cn1); locked.setStatus("LOCKED");
            when(userRepository.findById(997L)).thenReturn(Optional.of(locked));
            assertEquals(HttpStatus.FORBIDDEN,
                    assertThrows(ResponseStatusException.class, () -> chatService.requireInboxAccess(997L)).getStatusCode());
        }

        @Test
        @DisplayName("UTCID03 (N) - Branch topic access allows Admin & assigned Receptionist CN1; denies Warehouse & CN2")
        void utcid03() {
            stubRole(adminUser, "ADMIN", null, null);
            assertTrue(chatService.canAccessBranchTopic(1L, 2L));

            stubRole(whUser, "QUAN_LY_KHO", whStaff, null);
            assertFalse(chatService.canAccessBranchTopic(30L, 1L));

            stubRole(recUser, "LE_TAN", recStaff, null);
            assertTrue(chatService.canAccessBranchTopic(10L, 1L));
            assertFalse(chatService.canAccessBranchTopic(10L, 2L));
        }

        @Test
        @DisplayName("UTCID04 (A) - Search recipients blocks patient (403) and cross-branch query (403)")
        void utcid04() {
            stubRole(patUser, "BENH_NHAN", null, patProfile);
            assertEquals(HttpStatus.FORBIDDEN,
                    assertThrows(ResponseStatusException.class, () -> chatService.searchRecipientUsers(40L, null, null, null)).getStatusCode());

            stubRole(recUser, "LE_TAN", recStaff, null);
            assertEquals(HttpStatus.FORBIDDEN,
                    assertThrows(ResponseStatusException.class, () -> chatService.searchRecipientUsers(10L, null, null, 2L)).getStatusCode());
        }

        @Test
        @DisplayName("UTCID05 (N) - Recipient search hides patients from Warehouse; Receptionist matches child profile BN-CON")
        void utcid05() {
            stubRole(whUser, "QUAN_LY_KHO", whStaff, null);
            stubRole(docUser, "BAC_SI", docStaff, null);
            stubRole(patUser, "BENH_NHAN", null, patProfile);
            when(userRepository.findAll()).thenReturn(List.of(whUser, docUser, patUser));
            when(conversationRepository.findAllConversationsWithUserAndBranch()).thenReturn(List.of());
            assertTrue(chatService.searchRecipientUsers(30L, null, "BENH_NHAN", null).isEmpty());

            stubRole(recUser, "LE_TAN", recStaff, null);
            PatientProfile child = new PatientProfile(); child.setProfileId(401L); child.setPatientCode("BN-CON");
            child.setFullName("Bé Tí"); child.setPreferredBranch(cn1);
            when(patientProfileRepository.findByUser_UserIdOrderByIsDefaultDescProfileIdDesc(40L))
                    .thenReturn(List.of(patProfile, child));
            ChatConversation conv = new ChatConversation(); conv.setConversationId(55L); conv.setUser(patUser); conv.setBranch(cn1);
            conv.setStatus(ChatStatus.STAFF_HANDLING);
            when(conversationRepository.findAllConversationsWithUserAndBranch()).thenReturn(List.of(conv));

            List<ChatUserSearchItemDTO> res = chatService.searchRecipientUsers(10L, "BN-CON", null, 1L);
            assertEquals(1, res.size());
            assertEquals(2, res.get(0).getPatientProfileCount());
            assertEquals(55L, res.get(0).getExistingConversationId());
        }

        @Test
        @DisplayName("UTCID06 (N) - Recipient search excludes inactive & self users and sorts Staff A-Z before Patient")
        void utcid06() {
            stubRole(recUser, "LE_TAN", recStaff, null);
            stubRole(docUser, "BAC_SI", docStaff, null);
            stubRole(whUser, "QUAN_LY_KHO", whStaff, null);
            stubRole(patUser, "BENH_NHAN", null, patProfile);
            User inactive = activeUser(90L, "off", UserType.STAFF, cn1); inactive.setIsActive(false);

            when(userRepository.findAll()).thenReturn(List.of(patUser, inactive, whUser, docUser, recUser));
            when(conversationRepository.findAllConversationsWithUserAndBranch()).thenReturn(List.of());

            List<ChatUserSearchItemDTO> list = chatService.searchRecipientUsers(10L, null, "ALL", null);
            assertEquals(3, list.size());
            assertEquals("BS. Trần Minh", list.get(0).getFullName());
            assertEquals("Lê Quản Lý Kho", list.get(1).getFullName());
            assertEquals("BENH_NHAN", list.get(2).getRoleCode());
        }

        @Test
        @DisplayName("UTCID07 (A) - Start conversation validates null target/self-chat (400), Warehouse->Patient (403), cross-branch (403)")
        void utcid07() {
            stubRole(whUser, "QUAN_LY_KHO", whStaff, null);
            stubRole(patUser, "BENH_NHAN", null, patProfile);
            User cn2Staff = activeUser(60L, "lt2", UserType.STAFF, cn2);
            stubRole(cn2Staff, "LE_TAN", null, null);

            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class, () -> chatService.startOrGetConversationWithRecipient(30L, null, null)).getStatusCode());
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class, () -> chatService.startOrGetConversationWithRecipient(30L, 30L, null)).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> chatService.startOrGetConversationWithRecipient(30L, 40L, 1L)).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> chatService.startOrGetConversationWithRecipient(30L, 60L, null)).getStatusCode());
        }

        @Test
        @DisplayName("UTCID08 (N) - Start conversation reuses active direct conversation 88L and switches status to STAFF_HANDLING")
        void utcid08() {
            stubRole(recUser, "LE_TAN", recStaff, null);
            stubRole(docUser, "BAC_SI", docStaff, null);
            ChatConversation c = new ChatConversation(); c.setConversationId(88L); c.setUser(recUser); c.setRecipientUser(docUser);
            c.setBranch(cn1); c.setStatus(ChatStatus.BOT_HANDLING);
            when(conversationRepository.findActiveDirectConversationsBetweenUsers(10L, 20L)).thenReturn(List.of(c));
            when(conversationRepository.findByIdWithDetails(88L)).thenReturn(Optional.of(c));
            when(messageRepository.findByConversationIdOrderBySentAtAsc(88L)).thenReturn(List.of());

            Map<String, Object> res = chatService.startOrGetConversationWithRecipient(10L, 20L, 1L);
            assertEquals(88L, res.get("conversationId"));
            assertEquals(true, res.get("directConversation"));
            assertEquals(ChatStatus.STAFF_HANDLING, c.getStatus());
            verify(conversationRepository).save(c);
        }

        @Test
        @DisplayName("UTCID09 (N) - Widget context backfills branch CN1 on legacy patient conversation 12L and disables Warehouse")
        void utcid09() {
            stubRole(patUser, "BENH_NHAN", null, patProfile);
            ChatConversation legacy = new ChatConversation(); legacy.setConversationId(12L); legacy.setUser(patUser);
            legacy.setBranch(null); legacy.setStatus(ChatStatus.BOT_HANDLING);
            when(conversationRepository.findActiveByUserIdAndBranchId(40L, 1L)).thenReturn(List.of());
            when(conversationRepository.findActiveByUserIdWithNullBranch(40L)).thenReturn(List.of(legacy));
            when(messageRepository.findByConversationIdOrderBySentAtAsc(12L)).thenReturn(List.of());

            assertEquals(12L, chatService.getOrInitMyWidgetConversation(40L).get("conversationId"));
            assertEquals(cn1, legacy.getBranch());
            assertEquals(ChatStatus.STAFF_HANDLING, legacy.getStatus());

            stubRole(whUser, "QUAN_LY_KHO", whStaff, null);
            when(conversationRepository.findAllConversationsWithUserAndBranch()).thenReturn(List.of());
            assertEquals(false, chatService.getOrInitMyWidgetConversation(30L).get("canHandlePatients"));
        }
    }

    // =========================================================================
    // 41. ChatMessagingAndHistory (11 cases)
    // =========================================================================
    @Nested
    @DisplayName("ChatMessagingAndHistory")
    class ChatMessagingAndHistory {
        @Mock ChatConversationRepository conversationRepository;
        @Mock ChatMessageRepository messageRepository;
        @Mock ChatMessageUserStateRepository messageUserStateRepository;
        @Mock ChatConversationUserStateRepository conversationUserStateRepository;
        @Mock UserRepository userRepository;
        @Mock BranchRepository branchRepository;
        @Mock StaffProfileRepository staffProfileRepository;
        @Mock PatientProfileRepository patientProfileRepository;
        @Mock CustomerCareTaskRepository customerCareTaskRepository;

        ChatServiceImpl chatService;
        Branch cn1; Branch cn2;
        User adminUser; User recUser; User docUser; User whUser; User patUser;
        StaffProfile recStaff; StaffProfile docStaff; StaffProfile whStaff;
        PatientProfile patProfile;

        @BeforeEach
        void setUp() {
            chatService = new ChatServiceImpl(
                    conversationRepository, messageRepository, messageUserStateRepository,
                    conversationUserStateRepository, userRepository, branchRepository,
                    staffProfileRepository, patientProfileRepository, customerCareTaskRepository);
            cn1 = branch(1L, "CN01", "Cơ sở 1", true);
            cn2 = branch(2L, "CN02", "Cơ sở 2", true);
            adminUser = activeUser(1L, "admin", UserType.STAFF, null);
            recUser = activeUser(10L, "letan01", UserType.STAFF, cn1);
            docUser = activeUser(20L, "bs01", UserType.STAFF, cn1);
            whUser = activeUser(30L, "kho01", UserType.STAFF, cn1);
            patUser = activeUser(40L, "bn01", UserType.PATIENT, cn1);
            recStaff = activeStaff(100L, "LT01", "Nguyễn Lễ Tân", "Lễ tân", recUser);
            docStaff = activeStaff(200L, "BS01", "BS. Trần Minh", "Bác sĩ", docUser);
            whStaff = activeStaff(300L, "KHO01", "Lê Quản Lý Kho", "Quản lý kho", whUser);
            patProfile = new PatientProfile(); patProfile.setProfileId(400L); patProfile.setPatientCode("BN-400"); patProfile.setPreferredBranch(cn1);

            lenient().when(branchRepository.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(cn1, cn2));
            lenient().when(branchRepository.findById(1L)).thenReturn(Optional.of(cn1));
            lenient().when(conversationRepository.save(any(ChatConversation.class))).thenAnswer(i -> i.getArgument(0));
            lenient().when(messageRepository.save(any(ChatMessage.class))).thenAnswer(i -> {
                ChatMessage m = i.getArgument(0);
                if (m.getMessageId() == null) m.setMessageId(900L);
                return m;
            });
        }

        private void stubRole(User u, String role, StaffProfile sp, PatientProfile pp) {
            lenient().when(userRepository.findById(u.getUserId())).thenReturn(Optional.of(u));
            lenient().when(userRepository.hasActiveAdminRole(u.getUserId())).thenReturn("ADMIN".equals(role));
            lenient().when(conversationRepository.findActiveRoleNamesByUserId(u.getUserId())).thenReturn(List.of(role));
            lenient().when(staffProfileRepository.findByUser_UserId(u.getUserId())).thenReturn(Optional.ofNullable(sp));
            lenient().when(patientProfileRepository.findByUser_UserIdOrderByIsDefaultDescProfileIdDesc(u.getUserId()))
                    .thenReturn(pp != null ? List.of(pp) : List.of());
        }

        private ChatConversation conv(Long id, User init, User rec, Branch b) {
            ChatConversation c = new ChatConversation();
            c.setConversationId(id); c.setUser(init); c.setRecipientUser(rec); c.setBranch(b);
            c.setStatus(ChatStatus.STAFF_HANDLING); c.setLastMessageAt(LocalDateTime.now());
            return c;
        }

        private ChatMessage msg(Long id, ChatConversation c, Long senderId, SenderType st, String text, LocalDateTime at) {
            ChatMessage m = new ChatMessage();
            m.setMessageId(id); m.setConversation(c); m.setSenderId(senderId); m.setSenderType(st);
            m.setMessageText(text); m.setSentAt(at); m.setIsRead(false); m.setIsEdited(false); m.setIsRecalled(false);
            return m;
        }

        @Test
        @DisplayName("UTCID01 (B) - Send message rejects blank text (400), >2000 chars (400), and patient targeting staff (403)")
        void utcid01() {
            stubRole(patUser, "BENH_NHAN", null, patProfile);
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                    () -> chatService.sendMessage(40L, ChatSendRequestDTO.builder().messageText("   ").build())).getStatusCode());
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                    () -> chatService.sendMessage(40L, ChatSendRequestDTO.builder().messageText("a".repeat(2001)).build())).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                    () -> chatService.sendMessage(40L, ChatSendRequestDTO.builder().recipientUserId(10L).messageText("Hi").build())).getStatusCode());
            verify(messageRepository, never()).save(any());
        }

        @Test
        @DisplayName("UTCID02 (B) - Send message populates opening systemNotice and deduplicates repeated clientMessageId")
        void utcid02() {
            stubRole(patUser, "BENH_NHAN", null, patProfile);
            ChatConversation c = conv(15L, patUser, null, cn1);
            when(conversationRepository.findActiveByUserIdAndBranchId(40L, 1L)).thenReturn(List.of(c));
            when(messageRepository.countByConversation_ConversationId(15L)).thenReturn(0L);

            ChatSendRequestDTO req = ChatSendRequestDTO.builder().messageText("Wisdom tooth fee?").clientMessageId("cli-123").build();
            ChatMessageDTO first = chatService.sendMessage(40L, req);
            ChatMessageDTO second = chatService.sendMessage(40L, req);

            assertNotNull(first.getSystemNotice());
            assertSame(first, second);
            verify(messageRepository, times(1)).save(any(ChatMessage.class));
        }

        @Test
        @DisplayName("UTCID03 (N) - Warehouse missing target ID gets 400; Receptionist reply reopens CLOSED conv 1L")
        void utcid03() {
            stubRole(whUser, "QUAN_LY_KHO", whStaff, null);
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                    () -> chatService.sendMessage(30L, ChatSendRequestDTO.builder().messageText("Hi").build())).getStatusCode());

            stubRole(recUser, "LE_TAN", recStaff, null);
            stubRole(patUser, "BENH_NHAN", null, patProfile);
            ChatConversation closed = conv(1L, patUser, null, null);
            closed.setStatus(ChatStatus.CLOSED);
            when(conversationRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(closed));
            when(messageRepository.countByConversation_ConversationId(1L)).thenReturn(1L);

            chatService.sendMessage(10L, ChatSendRequestDTO.builder().conversationId(1L).messageText("Clinic reply").build());
            assertEquals(ChatStatus.STAFF_HANDLING, closed.getStatus());
            assertEquals(recStaff, closed.getAssignedStaff());
            verify(messageRepository).save(any(ChatMessage.class));
        }

        @Test
        @DisplayName("UTCID04 (N) - Search inbox enforces branch scope (403) and AND-filters keyword, role, branch, unreadOnly")
        void utcid04() {
            stubRole(recUser, "LE_TAN", recStaff, null);
            stubRole(patUser, "BENH_NHAN", null, patProfile);
            assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                    () -> chatService.searchInboxConversations(10L, null, null, 2L, null, PageRequest.of(0, 10))).getStatusCode());

            ChatConversation c1 = conv(1L, patUser, null, cn1);
            ChatConversation c2 = conv(2L, patUser, null, cn2);
            ChatMessage m1 = msg(10L, c1, 40L, SenderType.PATIENT, "Tư vấn niềng răng", LocalDateTime.now().minusMinutes(2));
            when(conversationRepository.findAllConversationsWithUserAndBranch()).thenReturn(List.of(c1, c2));
            when(conversationUserStateRepository.findByUserIdAndConversationIdIn(eq(10L), anyList())).thenReturn(List.of());
            when(messageUserStateRepository.findByUserIdAndConversationIdIn(eq(10L), anyList())).thenReturn(List.of());
            when(messageRepository.findAllByConversationIdsOrderBySentAtAsc(anyList())).thenReturn(List.of(m1));

            Page<ChatConversationDTO> page = chatService.searchInboxConversations(10L, "niềng răng", "BENH_NHAN", 1L, true, PageRequest.of(0, 10));
            assertEquals(1, page.getTotalElements());
            assertEquals(1L, page.getContent().get(0).getUnreadCount());
        }

        @Test
        @DisplayName("UTCID05 (B) - Cleared conversation 10L reappears when newer message arrives after clearedAt timestamp")
        void utcid05() {
            stubRole(adminUser, "ADMIN", null, null);
            stubRole(docUser, "BAC_SI", docStaff, null);
            stubRole(recUser, "LE_TAN", recStaff, null);
            ChatConversation direct = conv(10L, docUser, recUser, cn2);
            LocalDateTime now = LocalDateTime.now();
            ChatMessage oldM = msg(1L, direct, 20L, SenderType.STAFF, "Old msg", now.minusMinutes(20));
            ChatMessage newM = msg(2L, direct, 10L, SenderType.STAFF, "Tin mới sau khi xóa", now.minusMinutes(1));
            ChatConversationUserState cs = new ChatConversationUserState(); cs.setConversationId(10L); cs.setUserId(1L); cs.setClearedAt(now.minusMinutes(10));

            when(conversationRepository.findAllConversationsWithUserAndBranch()).thenReturn(List.of(direct));
            when(conversationUserStateRepository.findByUserIdAndConversationIdIn(1L, List.of(10L))).thenReturn(List.of(cs));
            when(messageUserStateRepository.findByUserIdAndConversationIdIn(1L, List.of(10L))).thenReturn(List.of());
            when(messageRepository.findAllByConversationIdsOrderBySentAtAsc(List.of(10L))).thenReturn(List.of(oldM, newM));

            Page<ChatConversationDTO> page = chatService.searchInboxConversations(1L, null, null, null, null, PageRequest.of(0, 10));
            assertEquals(1, page.getTotalElements());
            assertEquals("Tin mới sau khi xóa", page.getContent().get(0).getLastMessageText());
        }

        @Test
        @DisplayName("UTCID06 (N) - Get messages blocks other patients & Warehouse (403) and masks recalled text for owner")
        void utcid06() {
            stubRole(patUser, "BENH_NHAN", null, patProfile);
            User otherP = activeUser(41L, "bn41", UserType.PATIENT, cn1);
            when(conversationRepository.findByIdWithDetails(9L)).thenReturn(Optional.of(conv(9L, otherP, null, cn1)));
            assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> chatService.getConversationMessages(40L, 9L)).getStatusCode());

            stubRole(whUser, "QUAN_LY_KHO", whStaff, null);
            ChatConversation c1 = conv(1L, patUser, null, cn1);
            when(conversationRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(c1));
            assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> chatService.getConversationMessages(30L, 1L)).getStatusCode());

            ChatMessage m1 = msg(1L, c1, 40L, SenderType.PATIENT, "Hi", LocalDateTime.now().minusMinutes(5));
            ChatMessage m2 = msg(2L, c1, 40L, SenderType.PATIENT, "Secret", LocalDateTime.now().minusMinutes(2)); m2.setIsRecalled(true);
            when(messageRepository.findByConversationIdOrderBySentAtAsc(1L)).thenReturn(List.of(m1, m2));
            @SuppressWarnings("unchecked")
            List<ChatMessageDTO> dtos = (List<ChatMessageDTO>) chatService.getConversationMessages(40L, 1L).get("messages");
            assertEquals("Tin nhắn đã được thu hồi", dtos.get(1).getMessageText());
        }

        @Test
        @DisplayName("UTCID07 (A) - Get messages throws 404 for 999L and 403 for third-party direct or CN2 conversation")
        void utcid07() {
            stubRole(recUser, "LE_TAN", recStaff, null);
            when(conversationRepository.findByIdWithDetails(999L)).thenReturn(Optional.empty());
            assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, () -> chatService.getConversationMessages(10L, 999L)).getStatusCode());

            User rec2 = activeUser(11L, "lt11", UserType.STAFF, cn1);
            when(conversationRepository.findByIdWithDetails(5L)).thenReturn(Optional.of(conv(5L, docUser, rec2, cn1)));
            assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> chatService.getConversationMessages(10L, 5L)).getStatusCode());

            when(conversationRepository.findByIdWithDetails(6L)).thenReturn(Optional.of(conv(6L, patUser, null, cn2)));
            assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> chatService.getConversationMessages(10L, 6L)).getStatusCode());
        }

        @Test
        @DisplayName("UTCID08 (B) - Get messages filters cleared & single-deleted items per user keeping newer msg id=3L")
        void utcid08() {
            stubRole(patUser, "BENH_NHAN", null, patProfile);
            ChatConversation c1 = conv(1L, patUser, null, cn1);
            LocalDateTime now = LocalDateTime.now();
            ChatMessage m1 = msg(1L, c1, 40L, SenderType.PATIENT, "Old", now.minusMinutes(10));
            ChatMessage m2 = msg(2L, c1, 10L, SenderType.STAFF, "Deleted", now.minusMinutes(3));
            ChatMessage m3 = msg(3L, c1, 10L, SenderType.STAFF, "Visible", now.minusMinutes(1));
            ChatConversationUserState cs = new ChatConversationUserState(); cs.setClearedAt(now.minusMinutes(5));
            ChatMessageUserState ms = new ChatMessageUserState(); ms.setMessageId(2L); ms.setIsDeleted(true);

            when(conversationRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(c1));
            when(messageRepository.findByConversationIdOrderBySentAtAsc(1L)).thenReturn(List.of(m1, m2, m3));
            when(conversationUserStateRepository.findByConversationIdAndUserId(1L, 40L)).thenReturn(Optional.of(cs));
            when(messageUserStateRepository.findByConversationIdAndUserId(1L, 40L)).thenReturn(List.of(ms));

            @SuppressWarnings("unchecked")
            List<ChatMessageDTO> dtos = (List<ChatMessageDTO>) chatService.getConversationMessages(40L, 1L).get("messages");
            assertEquals(1, dtos.size());
            assertEquals(3L, dtos.get(0).getMessageId());
        }

        @Test
        @DisplayName("UTCID09 (B) - Edit message blocks non-owner (403) and >10-min expired (400); edits within 10 mins")
        void utcid09() {
            stubRole(patUser, "BENH_NHAN", null, patProfile);
            stubRole(recUser, "LE_TAN", recStaff, null);
            ChatConversation c1 = conv(1L, patUser, null, cn1);
            ChatMessage m = msg(10L, c1, 40L, SenderType.PATIENT, "Original", LocalDateTime.now().minusMinutes(2));
            when(messageRepository.findByIdWithConversation(10L)).thenReturn(Optional.of(m));
            when(conversationRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(c1));

            assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> chatService.editMessage(10L, 10L, "Edit")).getStatusCode());
            m.setSentAt(LocalDateTime.now().minusMinutes(12));
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class, () -> chatService.editMessage(40L, 10L, "Late")).getStatusCode());

            m.setSentAt(LocalDateTime.now().minusMinutes(3));
            ChatMessageDTO res = chatService.editMessage(40L, 10L, "Edited text");
            assertEquals("EDIT", res.getEventType());
            assertTrue(res.isEdited());
            verify(messageRepository).save(m);
        }

        @Test
        @DisplayName("UTCID10 (A) - Recall message blocks non-owner (403) and double recall (400); masks text when recalled")
        void utcid10() {
            stubRole(patUser, "BENH_NHAN", null, patProfile);
            stubRole(recUser, "LE_TAN", recStaff, null);
            ChatConversation c1 = conv(1L, patUser, null, cn1);
            ChatMessage m = msg(20L, c1, 40L, SenderType.PATIENT, "Typo", LocalDateTime.now().minusMinutes(1));
            when(messageRepository.findByIdWithConversation(20L)).thenReturn(Optional.of(m));
            when(conversationRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(c1));

            assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> chatService.recallMessage(10L, 20L)).getStatusCode());
            ChatMessageDTO res = chatService.recallMessage(40L, 20L);
            assertEquals("RECALL", res.getEventType());
            assertEquals("Tin nhắn đã được thu hồi", res.getMessageText());
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class, () -> chatService.recallMessage(40L, 20L)).getStatusCode());
        }

        @Test
        @DisplayName("UTCID11 (N) - Self-delete message 30L and clear conversation 1L update only user 40L state")
        void utcid11() {
            stubRole(patUser, "BENH_NHAN", null, patProfile);
            ChatConversation c1 = conv(1L, patUser, null, cn1);
            ChatMessage m = msg(30L, c1, 10L, SenderType.STAFF, "Hi", LocalDateTime.now().minusMinutes(1));
            when(messageRepository.findByIdWithConversation(30L)).thenReturn(Optional.of(m));
            when(conversationRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(c1));
            when(messageUserStateRepository.findByMessageIdAndUserId(30L, 40L)).thenReturn(Optional.empty());
            when(conversationRepository.findActiveByUserIdAndBranchId(40L, 1L)).thenReturn(List.of());

            assertEquals(40L, chatService.deleteMessageForUser(40L, 30L).get("deletedForUserId"));
            verify(messageUserStateRepository).save(argThat(s -> Boolean.TRUE.equals(s.getIsDeleted()) && Long.valueOf(40L).equals(s.getUserId())));

            when(messageRepository.findByConversationIdOrderBySentAtAsc(1L)).thenReturn(List.of(m));
            when(conversationUserStateRepository.findByConversationIdAndUserId(1L, 40L)).thenReturn(Optional.empty());
            assertEquals(40L, chatService.deleteConversationForUser(40L, 1L).get("deletedForUserId"));
            verify(conversationUserStateRepository).save(argThat(s -> s.getClearedAt() != null && Long.valueOf(40L).equals(s.getUserId())));
        }
    }

    // =========================================================================
    // 42. ChatUnreadAndRealtime (9 cases)
    // =========================================================================
    @Nested
    @DisplayName("ChatUnreadAndRealtime")
    class ChatUnreadAndRealtime {
        @Mock ChatConversationRepository conversationRepository;
        @Mock ChatMessageRepository messageRepository;
        @Mock ChatMessageUserStateRepository messageUserStateRepository;
        @Mock ChatConversationUserStateRepository conversationUserStateRepository;
        @Mock UserRepository userRepository;
        @Mock BranchRepository branchRepository;
        @Mock StaffProfileRepository staffProfileRepository;
        @Mock PatientProfileRepository patientProfileRepository;
        @Mock CustomerCareTaskRepository customerCareTaskRepository;
        @Mock ChatService chatServiceMock;
        @Mock SimpMessagingTemplate messagingTemplate;
        @Mock CustomerCareService customerCareServiceMock;

        ChatServiceImpl chatService;
        ChatWebSocketHandshakeInterceptor handshakeInterceptor;
        ChatStompChannelInterceptor stompInterceptor;
        ChatWebSocketController wsController;

        Branch cn1;
        User adminUser; User recUser; User docUser; User whUser; User patUser;
        StaffProfile recStaff; StaffProfile docStaff; StaffProfile whStaff;
        PatientProfile patProfile;

        @BeforeEach
        void setUp() {
            chatService = new ChatServiceImpl(
                    conversationRepository, messageRepository, messageUserStateRepository,
                    conversationUserStateRepository, userRepository, branchRepository,
                    staffProfileRepository, patientProfileRepository, customerCareTaskRepository);
            handshakeInterceptor = new ChatWebSocketHandshakeInterceptor();
            stompInterceptor = new ChatStompChannelInterceptor(chatServiceMock);
            stompInterceptor.setCustomerCareService(customerCareServiceMock);
            wsController = new ChatWebSocketController(chatServiceMock, messagingTemplate);

            cn1 = branch(1L, "CN01", "Cơ sở 1", true);
            adminUser = activeUser(1L, "admin", UserType.STAFF, null);
            recUser = activeUser(10L, "letan01", UserType.STAFF, cn1);
            docUser = activeUser(20L, "bs01", UserType.STAFF, cn1);
            whUser = activeUser(30L, "kho01", UserType.STAFF, cn1);
            patUser = activeUser(40L, "bn01", UserType.PATIENT, cn1);
            recStaff = activeStaff(100L, "LT01", "Nguyễn Lễ Tân", "Lễ tân", recUser);
            docStaff = activeStaff(200L, "BS01", "BS. Trần Minh", "Bác sĩ", docUser);
            whStaff = activeStaff(300L, "KHO01", "Lê Quản Lý Kho", "Quản lý kho", whUser);
            patProfile = new PatientProfile(); patProfile.setProfileId(400L); patProfile.setPreferredBranch(cn1);

            lenient().when(branchRepository.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(cn1));
            lenient().when(branchRepository.findById(1L)).thenReturn(Optional.of(cn1));
        }

        private void stubRole(User u, String role, StaffProfile sp, PatientProfile pp) {
            lenient().when(userRepository.findById(u.getUserId())).thenReturn(Optional.of(u));
            lenient().when(userRepository.hasActiveAdminRole(u.getUserId())).thenReturn("ADMIN".equals(role));
            lenient().when(conversationRepository.findActiveRoleNamesByUserId(u.getUserId())).thenReturn(List.of(role));
            lenient().when(staffProfileRepository.findByUser_UserId(u.getUserId())).thenReturn(Optional.ofNullable(sp));
            lenient().when(patientProfileRepository.findByUser_UserIdOrderByIsDefaultDescProfileIdDesc(u.getUserId()))
                    .thenReturn(pp != null ? List.of(pp) : List.of());
        }

        private ChatConversation conv(Long id, User init, User rec) {
            ChatConversation c = new ChatConversation();
            c.setConversationId(id); c.setUser(init); c.setRecipientUser(rec); c.setBranch(cn1);
            c.setStatus(ChatStatus.STAFF_HANDLING);
            return c;
        }

        private ChatMessage msg(Long id, ChatConversation c, Long senderId, SenderType st, String text, LocalDateTime at) {
            ChatMessage m = new ChatMessage();
            m.setMessageId(id); m.setConversation(c); m.setSenderId(senderId); m.setSenderType(st);
            m.setMessageText(text); m.setSentAt(at); m.setIsRead(false); m.setIsEdited(false); m.setIsRecalled(false);
            return m;
        }

        @Test
        @DisplayName("UTCID01 (N) - Unread count tracks independent read states: Receptionist 10L=0L while Doctor 20L=1L")
        void utcid01() {
            stubRole(recUser, "LE_TAN", recStaff, null);
            stubRole(docUser, "BAC_SI", docStaff, null);
            stubRole(patUser, "BENH_NHAN", null, patProfile);

            ChatConversation c = conv(1L, patUser, null);
            LocalDateTime t1 = LocalDateTime.now().minusMinutes(10);
            ChatMessage mPat = msg(10L, c, 40L, SenderType.PATIENT, "Pain", t1);
            ChatMessage mBot = msg(11L, c, null, SenderType.BOT, "Bot", t1);
            when(conversationRepository.findAllConversationsWithUserAndBranch()).thenReturn(List.of(c));
            when(messageRepository.findAllByConversationIdsOrderBySentAtAsc(List.of(1L))).thenReturn(List.of(mBot, mPat));

            ChatConversationUserState recState = new ChatConversationUserState(); recState.setConversationId(1L); recState.setUserId(10L); recState.setLastReadAt(LocalDateTime.now());
            when(conversationUserStateRepository.findByUserIdAndConversationIdIn(10L, List.of(1L))).thenReturn(List.of(recState));
            when(messageUserStateRepository.findByUserIdAndConversationIdIn(10L, List.of(1L))).thenReturn(List.of());
            when(conversationUserStateRepository.findByUserIdAndConversationIdIn(20L, List.of(1L))).thenReturn(List.of());
            when(messageUserStateRepository.findByUserIdAndConversationIdIn(20L, List.of(1L))).thenReturn(List.of());

            assertEquals(0L, chatService.getTotalUnreadCount(10L));
            assertEquals(1L, chatService.getTotalUnreadCount(20L));
        }

        @Test
        @DisplayName("UTCID02 (B) - Patient unread count excludes BOT, own, recalled, self-deleted and returns 1L")
        void utcid02() {
            stubRole(patUser, "BENH_NHAN", null, patProfile);
            ChatConversation c = conv(1L, patUser, null);
            ChatMessage m1 = msg(1L, c, null, SenderType.BOT, "Hi", LocalDateTime.now().minusMinutes(5));
            ChatMessage m2 = msg(2L, c, 40L, SenderType.PATIENT, "Q", LocalDateTime.now().minusMinutes(4));
            ChatMessage m3 = msg(3L, c, 10L, SenderType.STAFF, "Rec", LocalDateTime.now().minusMinutes(3)); m3.setIsRecalled(true);
            ChatMessage m4 = msg(4L, c, 10L, SenderType.STAFF, "Del", LocalDateTime.now().minusMinutes(2));
            ChatMessage m5 = msg(5L, c, 10L, SenderType.STAFF, "Unread", LocalDateTime.now().minusMinutes(1));
            ChatMessageUserState del = new ChatMessageUserState(); del.setMessageId(4L); del.setIsDeleted(true);

            when(conversationRepository.findActiveByUserIdAndBranchId(40L, 1L)).thenReturn(List.of(c));
            when(messageRepository.findByConversationIdOrderBySentAtAsc(1L)).thenReturn(List.of(m1, m2, m3, m4, m5));
            when(conversationUserStateRepository.findByConversationIdAndUserId(1L, 40L)).thenReturn(Optional.empty());
            when(messageUserStateRepository.findByConversationIdAndUserId(1L, 40L)).thenReturn(List.of(del));

            assertEquals(1L, chatService.getTotalUnreadCount(40L));
        }

        @Test
        @DisplayName("UTCID03 (N) - Warehouse unread count ignores patient chats and counts 1L direct staff message")
        void utcid03() {
            stubRole(whUser, "QUAN_LY_KHO", whStaff, null);
            stubRole(patUser, "BENH_NHAN", null, patProfile);
            stubRole(docUser, "BAC_SI", docStaff, null);
            ChatConversation patConv = conv(1L, patUser, null);
            ChatConversation directConv = conv(2L, docUser, whUser);
            ChatMessage mDoc = msg(2L, directConv, 20L, SenderType.STAFF, "Check gloves", LocalDateTime.now().minusMinutes(1));

            when(conversationRepository.findAllConversationsWithUserAndBranch()).thenReturn(List.of(patConv, directConv));
            when(conversationUserStateRepository.findByUserIdAndConversationIdIn(30L, List.of(2L))).thenReturn(List.of());
            when(messageUserStateRepository.findByUserIdAndConversationIdIn(30L, List.of(2L))).thenReturn(List.of());
            when(messageRepository.findAllByConversationIdsOrderBySentAtAsc(List.of(2L))).thenReturn(List.of(mDoc));

            assertEquals(1L, chatService.getTotalUnreadCount(30L));
        }

        @Test
        @DisplayName("UTCID04 (N) - Mark conversation as read marks incoming patient message for Doctor 20L only (updatedCount=1)")
        void utcid04() {
            stubRole(docUser, "BAC_SI", docStaff, null);
            stubRole(patUser, "BENH_NHAN", null, patProfile);
            ChatConversation c = conv(1L, patUser, null);
            ChatMessage patMsg = msg(1L, c, 40L, SenderType.PATIENT, "Hi", LocalDateTime.now().minusMinutes(2));
            ChatMessage ownMsg = msg(2L, c, 20L, SenderType.STAFF, "Hello", LocalDateTime.now().minusMinutes(1));

            when(conversationRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(c));
            when(messageRepository.findByConversationIdOrderBySentAtAsc(1L)).thenReturn(List.of(patMsg, ownMsg));
            when(conversationUserStateRepository.findByConversationIdAndUserId(1L, 20L)).thenReturn(Optional.empty());
            when(messageUserStateRepository.findByConversationIdAndUserId(1L, 20L)).thenReturn(List.of());
            when(conversationRepository.findAllConversationsWithUserAndBranch()).thenReturn(List.of());

            Map<String, Object> res = chatService.markConversationAsRead(20L, 1L);
            assertEquals(1, res.get("updatedCount"));
            assertEquals(0, res.get("unreadCount"));
            verify(messageUserStateRepository).saveAll(any());
            verify(conversationUserStateRepository).save(any(ChatConversationUserState.class));
        }

        @Test
        @DisplayName("UTCID05 (A) - WebSocket handshake rejects inactive or LOCKED users without populating ATTR_USER_ID")
        void utcid05() {
            User inactive = activeUser(10L, "u10", UserType.STAFF, cn1); inactive.setIsActive(false);
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.getSession(true).setAttribute("loggedInUser", inactive);
            Map<String, Object> attrs1 = new HashMap<>();
            assertFalse(handshakeInterceptor.beforeHandshake(
                    new org.springframework.http.server.ServletServerHttpRequest(req),
                    mock(org.springframework.http.server.ServerHttpResponse.class),
                    mock(org.springframework.web.socket.WebSocketHandler.class), attrs1));
            assertFalse(attrs1.containsKey(ChatWebSocketHandshakeInterceptor.ATTR_USER_ID));

            User locked = activeUser(11L, "u11", UserType.STAFF, cn1); locked.setStatus("LOCKED");
            req.getSession().setAttribute("loggedInUser", locked);
            Map<String, Object> attrs2 = new HashMap<>();
            assertFalse(handshakeInterceptor.beforeHandshake(
                    new org.springframework.http.server.ServletServerHttpRequest(req),
                    mock(org.springframework.http.server.ServerHttpResponse.class),
                    mock(org.springframework.web.socket.WebSocketHandler.class), attrs2));
            assertFalse(attrs2.containsKey(ChatWebSocketHandshakeInterceptor.ATTR_USER_ID));
        }

        @Test
        @DisplayName("UTCID06 (N) - STOMP subscription restricts admin chat and customer-care admin topics to Admin only")
        void utcid06() {
            when(chatServiceMock.isAdminUser(1L)).thenReturn(true);
            when(chatServiceMock.isAdminUser(2L)).thenReturn(false);

            assertDoesNotThrow(() -> stompInterceptor.validateSubscription(1L, "/topic/chat/admin"));
            assertDoesNotThrow(() -> stompInterceptor.validateSubscription(1L, "/topic/customer-care/admin"));
            assertThrows(AccessDeniedException.class, () -> stompInterceptor.validateSubscription(2L, "/topic/chat/admin"));
            assertThrows(AccessDeniedException.class, () -> stompInterceptor.validateSubscription(2L, "/topic/customer-care/admin"));
        }

        @Test
        @DisplayName("UTCID07 (N) - STOMP branch topic subscription allows assigned branch 1 and denies branch 2 or malformed ID")
        void utcid07() {
            when(chatServiceMock.canAccessBranchTopic(10L, 1L)).thenReturn(true);
            when(chatServiceMock.canAccessBranchTopic(10L, 2L)).thenReturn(false);

            assertDoesNotThrow(() -> stompInterceptor.validateSubscription(10L, "/topic/chat/branch/1"));
            assertThrows(AccessDeniedException.class, () -> stompInterceptor.validateSubscription(10L, "/topic/chat/branch/2"));
            assertThrows(AccessDeniedException.class, () -> stompInterceptor.validateSubscription(10L, "/topic/chat/branch/abc"));
        }

        @Test
        @DisplayName("UTCID08 (N) - WebSocket SEND persists message via sendMessage before broadcasting to queue & branch topic")
        void utcid08() {
            ChatSendRequestDTO req = ChatSendRequestDTO.builder().messageText("Xin chào").build();
            ChatMessageDTO saved = ChatMessageDTO.builder().messageId(1L).branchId(1L)
                    .directConversation(false).initiatorUsername("bn01").senderUsername("bn01").build();
            when(chatServiceMock.sendMessage(10L, req)).thenReturn(saved);

            wsController.handleSendMessage(req, new ChatStompPrincipal(10L, "bn01"), null);
            InOrder order = inOrder(chatServiceMock, messagingTemplate);
            order.verify(chatServiceMock).sendMessage(10L, req);
            order.verify(messagingTemplate).convertAndSendToUser("bn01", "/queue/chat/messages", saved);
            order.verify(messagingTemplate).convertAndSend("/topic/chat/branch/1", saved);
            order.verify(messagingTemplate).convertAndSend("/topic/chat/admin", saved);
        }

        @Test
        @DisplayName("UTCID09 (N) - Direct 1-on-1 message broadcasts only to user queues & admin topic without branch leak")
        void utcid09() {
            ChatMessageDTO direct = ChatMessageDTO.builder().messageId(20L).branchId(1L)
                    .directConversation(true).initiatorUsername("lt01").recipientUsername("bs01").senderUsername("lt01").build();

            wsController.broadcastSavedMessage(direct);
            verify(messagingTemplate).convertAndSendToUser("lt01", "/queue/chat/messages", direct);
            verify(messagingTemplate).convertAndSendToUser("bs01", "/queue/chat/messages", direct);
            verify(messagingTemplate).convertAndSend("/topic/chat/admin", direct);
            verify(messagingTemplate, never()).convertAndSend(startsWith("/topic/chat/branch/"), eq(direct));
        }
    }
}
