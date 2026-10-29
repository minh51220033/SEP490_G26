package com.cvdcms;

import com.cvdcms.DTO.MedicationDTO;
import com.cvdcms.DTO.MaterialUpdateDTO;
import com.cvdcms.DTO.StockReceiptCreateDTO;
import com.cvdcms.DTO.StockReceiptResponseDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.repository.*;
import com.cvdcms.service.BranchAccessService;
import com.cvdcms.service.impl.MaterialServiceImpl;
import com.cvdcms.service.impl.MedicationServiceImpl;
import com.cvdcms.service.impl.StockReceiptServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Kiểm thử đồng bộ danh mục thuốc (medications) → danh mục kho vật tư (materials).
 * Mức kiểm thử: unit test (Mockito) ở Service layer, không kết nối database thật.
 */
@ExtendWith(MockitoExtension.class)
class MedicationInventorySyncUnitTest {

    @Mock private MedicationRepository medicationRepository;
    @Mock private PrescriptionItemRepository prescriptionItemRepository;
    @Mock private MaterialRepository materialRepository;

    // Phục vụ kiểm thử nhập kho (case 8)
    @Mock private BranchRepository branchRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private StockReceiptRepository receiptRepository;
    @Mock private MaterialBatchRepository batchRepository;
    @Mock private BranchInventoryRepository inventoryRepository;
    @Mock private StaffProfileRepository staffRepository;
    @Mock private UserRepository userRepository;
    @Mock private SupplierPaymentRepository supplierPaymentRepository;
    @Mock private BranchAccessService accessService;

    private MedicationServiceImpl medicationService;

    @BeforeEach
    void setUp() {
        medicationService = new MedicationServiceImpl(medicationRepository, prescriptionItemRepository, materialRepository);
    }

    private MedicationDTO dto(String code, String name, String unit) {
        return MedicationDTO.builder()
                .drugCode(code).drugName(name).activeIngredient("Amoxicillin").unit(unit)
                .usageInstruction("Uống sau ăn").build();
    }

    private void stubSaveMedicationWithId(long id) {
        when(medicationRepository.saveAndFlush(any(Medication.class))).thenAnswer(inv -> {
            Medication m = inv.getArgument(0);
            m.setMedicationId(id);
            return m;
        });
    }

    // 1. Tạo thuốc mới → tạo đúng một mặt hàng kho, đúng mã/tên/đơn vị/nhóm, có liên kết
    @Test
    void createMedication_createsExactlyOneLinkedMaterial() {
        when(medicationRepository.existsByDrugCodeIgnoreCase("AMOX500")).thenReturn(false);
        when(materialRepository.findByMaterialCodeIgnoreCase("AMOX500")).thenReturn(Optional.empty());
        stubSaveMedicationWithId(7L);
        when(materialRepository.saveAndFlush(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));

        MedicationDTO result = medicationService.createMedication(dto(" amox500 ", "Amoxicillin 500mg", "Viên"));

        assertEquals(7L, result.getMedicationId());
        ArgumentCaptor<Material> captor = ArgumentCaptor.forClass(Material.class);
        verify(materialRepository, times(1)).saveAndFlush(captor.capture());
        Material m = captor.getValue();
        assertEquals("AMOX500", m.getMaterialCode());
        assertEquals("Amoxicillin 500mg", m.getMaterialName());
        assertEquals("Viên", m.getUnit());
        assertEquals(MedicationServiceImpl.MEDICATION_MATERIAL_CATEGORY, m.getCategory());
        assertEquals(7L, m.getMedicationId());
        assertTrue(m.getIsActive());
        assertEquals(0, new BigDecimal("10.000").compareTo(m.getMinStockThreshold()));
    }

    // 2. Mã thuốc trùng → từ chối, không lưu thuốc, không tạo hàng kho
    @Test
    void createMedication_duplicateDrugCode_rejectedWithoutChanges() {
        when(medicationRepository.existsByDrugCodeIgnoreCase("AMOX500")).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> medicationService.createMedication(dto("AMOX500", "Amoxicillin", "Viên")));
        assertTrue(ex.getMessage().contains("đã tồn tại"));
        verify(medicationRepository, never()).saveAndFlush(any());
        verify(medicationRepository, never()).save(any());
        verify(materialRepository, never()).saveAndFlush(any());
        verify(materialRepository, never()).save(any());
    }

    // 3. Mã thuốc trùng mã vật tư không liên quan → báo xung đột, không liên kết nhầm
    @Test
    void createMedication_codeConflictsWithUnrelatedMaterial_rejected() {
        Material gloves = new Material();
        gloves.setMaterialId(50L);
        gloves.setMaterialCode("VT-TH-GANG");
        gloves.setMaterialName("Găng tay y tế");
        gloves.setCategory("Tiêu hao");
        when(medicationRepository.existsByDrugCodeIgnoreCase("VT-TH-GANG")).thenReturn(false);
        when(materialRepository.findByMaterialCodeIgnoreCase("VT-TH-GANG")).thenReturn(Optional.of(gloves));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> medicationService.createMedication(dto("VT-TH-GANG", "Thuốc X", "Viên")));
        assertTrue(ex.getMessage().contains("Găng tay y tế"));
        assertNull(gloves.getMedication(), "Không được liên kết thuốc vào vật tư không liên quan");
        verify(medicationRepository, never()).saveAndFlush(any());
        verify(materialRepository, never()).saveAndFlush(any());
    }

    // 4. Tạo hàng kho thất bại → exception được ném ra (không nuốt lỗi) để transaction rollback cả thuốc
    @Test
    void createMedication_materialSaveFails_exceptionPropagatesForRollback() throws Exception {
        when(medicationRepository.existsByDrugCodeIgnoreCase("AMOX500")).thenReturn(false);
        when(materialRepository.findByMaterialCodeIgnoreCase("AMOX500")).thenReturn(Optional.empty());
        stubSaveMedicationWithId(8L);
        when(materialRepository.saveAndFlush(any(Material.class)))
                .thenThrow(new DataIntegrityViolationException("uk_mat_code"));

        assertThrows(DataIntegrityViolationException.class,
                () -> medicationService.createMedication(dto("AMOX500", "Amoxicillin", "Viên")));

        Method m = MedicationServiceImpl.class.getMethod("createMedication", MedicationDTO.class);
        Transactional tx = m.getAnnotation(Transactional.class);
        assertNotNull(tx, "createMedication phải chạy trong transaction");
        assertFalse(tx.readOnly());
        assertTrue(List.of(tx.rollbackFor()).contains(Exception.class));
    }

    // 5. Gửi request lặp → lần 2 bị chặn (kiểm tra trước) hoặc bởi UNIQUE của DB; không sinh hàng kho thứ hai
    @Test
    void createMedication_repeatedSubmit_neverCreatesSecondMaterial() {
        when(medicationRepository.existsByDrugCodeIgnoreCase("AMOX500")).thenReturn(false, true);
        when(materialRepository.findByMaterialCodeIgnoreCase("AMOX500")).thenReturn(Optional.empty());
        stubSaveMedicationWithId(9L);
        when(materialRepository.saveAndFlush(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));

        medicationService.createMedication(dto("AMOX500", "Amoxicillin", "Viên"));
        assertThrows(IllegalArgumentException.class,
                () -> medicationService.createMedication(dto("AMOX500", "Amoxicillin", "Viên")));

        verify(materialRepository, times(1)).saveAndFlush(any(Material.class));
    }

    @Test
    void createMedication_concurrentSubmit_dbUniqueViolationStopsBeforeMaterial() {
        when(medicationRepository.existsByDrugCodeIgnoreCase("AMOX500")).thenReturn(false);
        when(materialRepository.findByMaterialCodeIgnoreCase("AMOX500")).thenReturn(Optional.empty());
        when(medicationRepository.saveAndFlush(any(Medication.class)))
                .thenThrow(new DataIntegrityViolationException("uk_drug_code"));

        assertThrows(DataIntegrityViolationException.class,
                () -> medicationService.createMedication(dto("AMOX500", "Amoxicillin", "Viên")));
        verify(materialRepository, never()).saveAndFlush(any());
    }

    // 7. Thuốc mới không tạo tồn kho: service đồng bộ không phụ thuộc repository tồn kho/lô/phiếu nhập
    @Test
    void medicationService_hasNoInventoryOrBatchDependencies() {
        for (var field : MedicationServiceImpl.class.getDeclaredFields()) {
            Class<?> t = field.getType();
            assertNotEquals(BranchInventoryRepository.class, t);
            assertNotEquals(MaterialBatchRepository.class, t);
            assertNotEquals(StockReceiptRepository.class, t);
        }
    }

    @Test
    void newMedicationMaterial_isListedForReceiptForm() {
        Medication med = new Medication();
        med.setMedicationId(7L);
        med.setIsActive(true);
        Material linked = new Material();
        linked.setMaterialId(200L);
        linked.setMaterialCode("AMOX500");
        linked.setMaterialName("Amoxicillin 500mg");
        linked.setUnit("Viên");
        linked.setIsActive(true);
        linked.setMedication(med);
        when(materialRepository.findByIsActiveTrueOrderByMaterialNameAsc()).thenReturn(List.of(linked));

        List<Material> active = new MaterialServiceImpl(materialRepository).listActive();
        assertEquals(1, active.stream().filter(x -> Long.valueOf(7L).equals(x.getMedicationId())).count());
    }

    // 8. Nhập 1 lô thuốc → tồn kho cập nhật đúng một lần theo nghiệp vụ nhập kho hiện có
    @Test
    void receiptOfMedicationMaterial_updatesInventoryOnce() {
        StockReceiptServiceImpl receiptService = new StockReceiptServiceImpl(
                branchRepository, supplierRepository, materialRepository,
                receiptRepository, batchRepository, inventoryRepository,
                staffRepository, userRepository, supplierPaymentRepository, accessService);

        Branch branch = new Branch();
        branch.setBranchId(1L);
        branch.setBranchCode("CN01");
        branch.setBranchName("Cơ sở Quận 1");
        branch.setIsActive(true);
        Supplier supplier = new Supplier();
        supplier.setSupplierId(10L);
        supplier.setSupplierCode("NCC-01");
        supplier.setSupplierName("NCC Dược");
        supplier.setIsActive(true);
        StaffProfile staff = new StaffProfile();
        staff.setStaffId(5L);
        staff.setStaffCode("NV001");
        staff.setFullName("Quản lý kho");
        staff.setStatus(StaffStatus.ACTIVE);
        Medication med = new Medication();
        med.setMedicationId(7L);
        med.setIsActive(true);
        Material drug = new Material();
        drug.setMaterialId(200L);
        drug.setMaterialCode("AMOX500");
        drug.setMaterialName("Amoxicillin 500mg");
        drug.setCategory("Thuốc");
        drug.setUnit("Viên");
        drug.setIsActive(true);
        drug.setMedication(med);

        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch));
        when(supplierRepository.findById(10L)).thenReturn(Optional.of(supplier));
        when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(staff));
        when(materialRepository.findById(200L)).thenReturn(Optional.of(drug));
        when(batchRepository.findForUpdate(1L, 200L, "LO-T01")).thenReturn(Optional.empty());
        when(inventoryRepository.findForUpdate(1L, 200L)).thenReturn(Optional.empty());
        when(batchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inventoryRepository.save(any(BranchInventory.class))).thenAnswer(inv -> inv.getArgument(0));
        when(receiptRepository.save(any(StockReceipt.class))).thenAnswer(inv -> {
            StockReceipt r = inv.getArgument(0);
            r.setReceiptId(99L);
            r.setReceiptCode("PN-T01");
            return r;
        });

        StockReceiptCreateDTO dto = new StockReceiptCreateDTO();
        dto.setBranchId(1L);
        dto.setSupplierId(10L);
        dto.setReceiptDate(LocalDate.now());
        StockReceiptCreateDTO.Item item = new StockReceiptCreateDTO.Item();
        item.setMaterialId(200L);
        item.setBatchNumber("LO-T01");
        item.setExpiryDate(LocalDate.now().plusMonths(18));
        item.setQuantity(new BigDecimal("100.000"));
        item.setUnitPrice(new BigDecimal("1500.00"));
        dto.setItems(List.of(item));

        StockReceiptResponseDTO response = receiptService.create(dto, 1L);

        assertNotNull(response);
        verify(inventoryRepository, times(1)).save(argThat(inv ->
                inv.getCurrentStock().compareTo(new BigDecimal("100.000")) == 0));
        verify(batchRepository, times(1)).save(any(MaterialBatch.class));
    }

    // Trạng thái: ngừng thuốc → ẩn mặt hàng kho khỏi form nhập, không xóa lịch sử
    @Test
    void toggleMedication_syncsMaterialStatusWithoutDeleting() {
        Medication med = new Medication();
        med.setMedicationId(7L);
        med.setIsActive(true);
        Material linked = new Material();
        linked.setMaterialId(200L);
        linked.setIsActive(true);
        linked.setMedication(med);
        when(medicationRepository.findById(7L)).thenReturn(Optional.of(med));
        when(materialRepository.findByMedication_MedicationId(7L)).thenReturn(Optional.of(linked));

        medicationService.toggleMedicationStatus(7L);

        assertFalse(med.getIsActive());
        assertFalse(linked.getIsActive());
        verify(materialRepository).save(linked);
        verify(materialRepository, never()).delete(any());
    }

    // Không xóa thuốc khi mặt hàng kho đã có lịch sử nhập kho
    @Test
    void deleteMedication_blockedWhenMaterialHasStockHistory() {
        Medication med = new Medication();
        med.setMedicationId(7L);
        med.setDrugName("Amoxicillin");
        Material linked = new Material();
        linked.setMaterialId(200L);
        linked.setMedication(med);
        when(medicationRepository.findById(7L)).thenReturn(Optional.of(med));
        when(prescriptionItemRepository.countByMedication_MedicationId(7L)).thenReturn(0L);
        when(materialRepository.findByMedication_MedicationId(7L)).thenReturn(Optional.of(linked));
        when(materialRepository.countReceiptItems(200L)).thenReturn(1L);

        assertThrows(IllegalArgumentException.class, () -> medicationService.deleteMedication(7L));
        verify(materialRepository, never()).delete(any());
        verify(medicationRepository, never()).deleteById(any());
    }

    // 9. Vật tư thông thường (không phải thuốc) vẫn sửa được như trước
    @Test
    void regularMaterial_updateStillWorks() {
        Material regular = new Material();
        regular.setMaterialId(100L);
        regular.setMaterialCode("VT-COMP-01");
        regular.setMaterialName("Composite 3M");
        regular.setCategory("Trám răng");
        regular.setUnit("Ống");
        regular.setIsActive(true);
        when(materialRepository.findById(100L)).thenReturn(Optional.of(regular));
        when(materialRepository.existsByMaterialCodeIgnoreCaseAndMaterialIdNot("VT-COMP-02", 100L)).thenReturn(false);
        when(materialRepository.save(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));

        MaterialUpdateDTO upd = new MaterialUpdateDTO();
        upd.setMaterialCode("VT-COMP-02");
        upd.setMaterialName("Composite 3M Z350");
        upd.setCategory("Trám răng");
        upd.setUnit("Ống");
        upd.setMinStockThreshold(new BigDecimal("5"));

        Material saved = new MaterialServiceImpl(materialRepository).update(100L, upd);
        assertEquals("VT-COMP-02", saved.getMaterialCode());
        assertNull(saved.getMedicationId());
    }

    @Test
    void linkedMaterial_cannotRenameFromInventoryModule() {
        Medication med = new Medication();
        med.setMedicationId(7L);
        Material linked = new Material();
        linked.setMaterialId(200L);
        linked.setMaterialCode("AMOX500");
        linked.setMaterialName("Amoxicillin 500mg");
        linked.setUnit("Viên");
        linked.setMedication(med);
        when(materialRepository.findById(200L)).thenReturn(Optional.of(linked));

        MaterialUpdateDTO upd = new MaterialUpdateDTO();
        upd.setMaterialCode("AMOX500");
        upd.setMaterialName("Tên khác");
        upd.setCategory("Thuốc");
        upd.setUnit("Viên");

        assertThrows(IllegalArgumentException.class,
                () -> new MaterialServiceImpl(materialRepository).update(200L, upd));
        verify(materialRepository, never()).save(any());
    }

    // 10. Kê đơn không bị ảnh hưởng: đồng bộ kho không ghi vào prescription_items
    @Test
    void createMedication_doesNotTouchPrescriptionItems() {
        when(medicationRepository.existsByDrugCodeIgnoreCase("AMOX500")).thenReturn(false);
        when(materialRepository.findByMaterialCodeIgnoreCase("AMOX500")).thenReturn(Optional.empty());
        stubSaveMedicationWithId(7L);
        when(materialRepository.saveAndFlush(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));

        medicationService.createMedication(dto("AMOX500", "Amoxicillin", "Viên"));

        verifyNoInteractions(prescriptionItemRepository);
    }
}
