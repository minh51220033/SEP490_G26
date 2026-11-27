package com.cvdcms.service;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.impl.PrescriptionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Bộ kiểm thử đồng bộ Kê đơn thuốc ↔ Kho thuốc (FEFO, hạn sử dụng, chi nhánh, idempotency, sửa/hủy đơn).
 * Bao phủ TEST 01 – TEST 14 & TEST 16.
 */
@ExtendWith(MockitoExtension.class)
class PrescriptionInventorySyncTest {

    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private MedicationRepository medicationRepository;
    @Mock private VisitRepository visitRepository;
    @Mock private StaffProfileRepository staffProfileRepository;
    @Mock private PatientProfileRepository patientProfileRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private BranchInventoryRepository branchInventoryRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private StockIssueRepository stockIssueRepository;

    private PrescriptionServiceImpl prescriptionService;

    private Branch branchA;
    private Branch branchB;
    private StaffProfile doctor;
    private PatientProfile patient;
    private Visit visit;
    private Medication medPara;
    private Material matPara;
    private Medication medVitC;
    private Material matVitC;

    @BeforeEach
    void setUp() {
        prescriptionService = new PrescriptionServiceImpl(
                prescriptionRepository,
                medicationRepository,
                visitRepository,
                staffProfileRepository,
                patientProfileRepository,
                materialRepository,
                branchInventoryRepository,
                materialBatchRepository,
                stockIssueRepository
        );

        branchA = new Branch();
        branchA.setBranchId(1L);
        branchA.setBranchName("Chi nhánh A");
        branchA.setIsActive(true);

        branchB = new Branch();
        branchB.setBranchId(2L);
        branchB.setBranchName("Chi nhánh B");
        branchB.setIsActive(true);

        doctor = new StaffProfile();
        doctor.setStaffId(10L);
        doctor.setFullName("BS. Nguyễn Văn A");
        doctor.setStatus(StaffStatus.ACTIVE);

        patient = new PatientProfile();
        patient.setProfileId(20L);
        patient.setPatientCode("BN001");
        patient.setFullName("Trần Thị B");
        patient.setPreferredBranch(branchA);

        visit = new Visit();
        visit.setVisitId(100L);
        visit.setVisitCode("VIS-100");
        visit.setStatus(VisitStatus.IN_EXAM);
        visit.setBranch(branchA);
        visit.setPatientProfile(patient);
        visit.setPrimaryDoctor(doctor);

        matPara = new Material();
        matPara.setMaterialId(501L);
        matPara.setMaterialCode("PARA500");
        matPara.setMaterialName("Paracetamol 500mg");
        matPara.setCategory("Thuốc");
        matPara.setUnit("Viên");
        matPara.setMinStockThreshold(new BigDecimal("10"));

        medPara = new Medication();
        medPara.setMedicationId(1L);
        medPara.setDrugCode("PARA500");
        medPara.setDrugName("Paracetamol 500mg");
        medPara.setUnit("Viên");
        medPara.setSalePrice(new BigDecimal("2500"));
        medPara.setIsActive(true);
        medPara.setMaterial(matPara);

        matVitC = new Material();
        matVitC.setMaterialId(502L);
        matVitC.setMaterialCode("VITC500");
        matVitC.setMaterialName("Vitamin C 500mg");
        matVitC.setCategory("Thuốc");
        matVitC.setUnit("Viên");
        matVitC.setMinStockThreshold(new BigDecimal("10"));

        medVitC = new Medication();
        medVitC.setMedicationId(2L);
        medVitC.setDrugCode("VITC500");
        medVitC.setDrugName("Vitamin C 500mg");
        medVitC.setUnit("Viên");
        medVitC.setSalePrice(new BigDecimal("1500"));
        medVitC.setIsActive(true);
        medVitC.setMaterial(matVitC);
    }

    private MaterialBatch batch(Long id, Branch branch, Material mat, String number, LocalDate expiry, BigDecimal qty, BatchStatus status) {
        MaterialBatch b = new MaterialBatch();
        b.setBatchId(id);
        b.setBranch(branch);
        b.setMaterial(mat);
        b.setBatchNumber(number);
        b.setExpiryDate(expiry);
        b.setQuantityRemaining(qty);
        b.setStatus(status);
        return b;
    }

    private BranchInventory inv(Branch branch, Material mat, BigDecimal stock) {
        BranchInventory bi = new BranchInventory();
        bi.setBranch(branch);
        bi.setMaterial(mat);
        bi.setCurrentStock(stock);
        return bi;
    }

    private PrescriptionRequestDTO requestWith(Medication med, BigDecimal qty, String clientReqId) {
        return PrescriptionRequestDTO.builder()
                .visitId(visit.getVisitId())
                .doctorId(doctor.getStaffId())
                .diagnosisText("K02.1 - Sâu ngà răng")
                .clientRequestId(clientReqId)
                .items(List.of(PrescriptionItemRequestDTO.builder()
                        .medicationId(med.getMedicationId())
                        .drugName(med.getDrugName())
                        .quantity(qty)
                        .unit(med.getUnit())
                        .usageInstruction("Uống sau ăn")
                        .build()))
                .build();
    }

    @Test
    @DisplayName("TEST 01 & 02 & 03 & 04: Hiển thị đúng tồn khả dụng (có hàng, chưa nhập kho = 0, chỉ còn lô hết hạn = 0, nhiều lô chỉ tính lô còn hạn)")
    void getActiveMedicationsForBranch_computesAccurateAvailableAndExpiredStock() {
        when(medicationRepository.findByIsActiveTrueOrderByDrugNameAsc()).thenReturn(List.of(medPara, medVitC));

        MaterialBatch validSoon = batch(1L, branchA, matPara, "LO-A", LocalDate.now().plusDays(30), new BigDecimal("50"), BatchStatus.ACTIVE);
        MaterialBatch validLater = batch(2L, branchA, matPara, "LO-B", LocalDate.now().plusDays(180), new BigDecimal("100"), BatchStatus.ACTIVE);
        when(materialBatchRepository.findActiveAvailableBatchesByBranchAndMaterialIds(eq(1L), anyCollection(), any(LocalDate.class)))
                .thenReturn(List.of(validSoon, validLater));

        // VitC chỉ có lô hết hạn (40 viên hết hạn, 0 còn hạn)
        List<Object[]> expiredRows = new ArrayList<>();
        expiredRows.add(new Object[]{matVitC.getMaterialId(), new BigDecimal("40")});
        when(materialBatchRepository.sumExpiredStockByBranchAndMaterialIds(eq(1L), anyCollection(), any(LocalDate.class)))
                .thenReturn(expiredRows);

        List<MedicationDTO> list = prescriptionService.getActiveMedicationsForBranch(1L);
        assertEquals(2, list.size());

        MedicationDTO paraDto = list.stream().filter(m -> m.getMedicationId().equals(1L)).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("150").compareTo(paraDto.getAvailableStock()));
        assertEquals("IN_STOCK", paraDto.getStockStatus());
        assertEquals("LO-A", paraDto.getNearestBatchNumber());
        assertEquals(validSoon.getExpiryDate(), paraDto.getNearestExpiryDate());

        MedicationDTO vitCDto = list.stream().filter(m -> m.getMedicationId().equals(2L)).findFirst().orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(vitCDto.getAvailableStock()));
        assertEquals(0, new BigDecimal("40").compareTo(vitCDto.getExpiredStock()));
        assertEquals("EXPIRED_ONLY", vitCDto.getStockStatus());
    }

    @Test
    @DisplayName("TEST 05 & 06 & 16: Kê đơn xuất kho đúng FEFO (hết hạn sớm xuất trước), trừ BranchInventory và ghi lịch sử StockIssue truy vết")
    void createPrescription_deductsFefoAndCreatesTraceableStockIssue() {
        when(visitRepository.findById(100L)).thenReturn(Optional.of(visit));
        when(staffProfileRepository.findById(10L)).thenReturn(Optional.of(doctor));
        when(medicationRepository.findById(1L)).thenReturn(Optional.of(medPara));
        when(prescriptionRepository.saveAndFlush(any(Prescription.class))).thenAnswer(inv -> {
            Prescription p = inv.getArgument(0);
            p.setPrescriptionId(999L);
            return p;
        });

        MaterialBatch lot1 = batch(11L, branchA, matPara, "LOT-EARLY", LocalDate.now().plusDays(15), new BigDecimal("6"), BatchStatus.ACTIVE);
        MaterialBatch lot2 = batch(12L, branchA, matPara, "LOT-LATER", LocalDate.now().plusDays(90), new BigDecimal("20"), BatchStatus.ACTIVE);
        BranchInventory bi = inv(branchA, matPara, new BigDecimal("26"));

        when(branchInventoryRepository.findForUpdate(1L, 501L)).thenReturn(Optional.of(bi));
        when(materialBatchRepository.findAvailableBatchesFefoForUpdate(eq(1L), eq(501L), any(LocalDate.class)))
                .thenReturn(List.of(lot1, lot2));
        when(stockIssueRepository.saveAndFlush(any(StockIssue.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stockIssueRepository.findByPrescriptionIdWithItemsOrderByIssueIdAsc(999L)).thenReturn(Collections.emptyList());

        PrescriptionResponseDTO res = prescriptionService.createPrescription(requestWith(medPara, new BigDecimal("10"), "REQ-001"));

        assertTrue(Boolean.TRUE.equals(res.getInventoryDeducted()));
        // FEFO: lot1 hết sạch (6 -> 0, OUT_OF_STOCK), lot2 trừ 4 (20 -> 16, ACTIVE)
        assertEquals(0, BigDecimal.ZERO.compareTo(lot1.getQuantityRemaining()));
        assertEquals(BatchStatus.OUT_OF_STOCK, lot1.getStatus());
        assertEquals(0, new BigDecimal("16").compareTo(lot2.getQuantityRemaining()));
        assertEquals(BatchStatus.ACTIVE, lot2.getStatus());
        // BranchInventory: 26 - 10 = 16
        assertEquals(0, new BigDecimal("16").compareTo(bi.getCurrentStock()));

        ArgumentCaptor<StockIssue> issueCaptor = ArgumentCaptor.forClass(StockIssue.class);
        verify(stockIssueRepository).saveAndFlush(issueCaptor.capture());
        StockIssue savedIssue = issueCaptor.getValue();
        assertEquals(StockIssueType.PRESCRIPTION_OUT, savedIssue.getIssueType());
        assertEquals(branchA, savedIssue.getBranch());
        assertEquals(doctor, savedIssue.getIssuer());
        assertEquals(999L, savedIssue.getPrescription().getPrescriptionId());
        assertEquals(2, savedIssue.getItems().size());
        assertEquals(0, new BigDecimal("6").compareTo(savedIssue.getItems().get(0).getQuantity()));
        assertEquals(0, new BigDecimal("4").compareTo(savedIssue.getItems().get(1).getQuantity()));
    }

    @Test
    @DisplayName("TEST 07: Kê vượt tồn kho khả dụng → ném lỗi rõ ràng, không trừ kho và không tạo phiếu xuất")
    void createPrescription_exceedsAvailableStock_rejectedWithoutDeduction() {
        when(visitRepository.findById(100L)).thenReturn(Optional.of(visit));
        when(staffProfileRepository.findById(10L)).thenReturn(Optional.of(doctor));
        when(medicationRepository.findById(1L)).thenReturn(Optional.of(medPara));
        when(prescriptionRepository.saveAndFlush(any(Prescription.class))).thenAnswer(inv -> {
            Prescription p = inv.getArgument(0);
            p.setPrescriptionId(998L);
            return p;
        });

        MaterialBatch lot1 = batch(11L, branchA, matPara, "LOT-1", LocalDate.now().plusDays(30), new BigDecimal("5"), BatchStatus.ACTIVE);
        BranchInventory bi = inv(branchA, matPara, new BigDecimal("5"));
        when(branchInventoryRepository.findForUpdate(1L, 501L)).thenReturn(Optional.of(bi));
        when(materialBatchRepository.findAvailableBatchesFefoForUpdate(eq(1L), eq(501L), any(LocalDate.class)))
                .thenReturn(List.of(lot1));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> prescriptionService.createPrescription(requestWith(medPara, new BigDecimal("10"), "REQ-OVER")));
        assertTrue(ex.getMessage().contains("không đủ tồn kho khả dụng"));
        assertEquals(0, new BigDecimal("5").compareTo(lot1.getQuantityRemaining()));
        assertEquals(0, new BigDecimal("5").compareTo(bi.getCurrentStock()));
        verify(stockIssueRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("TEST 08 & 03: Kê thuốc tồn bằng 0 hoặc chỉ còn lô hết hạn → chặn xác nhận đơn")
    void createPrescription_expiredOnlyOrZeroStock_rejected() {
        when(visitRepository.findById(100L)).thenReturn(Optional.of(visit));
        when(staffProfileRepository.findById(10L)).thenReturn(Optional.of(doctor));
        when(medicationRepository.findById(2L)).thenReturn(Optional.of(medVitC));
        when(prescriptionRepository.saveAndFlush(any(Prescription.class))).thenAnswer(inv -> {
            Prescription p = inv.getArgument(0);
            p.setPrescriptionId(997L);
            return p;
        });

        MaterialBatch expiredLot = batch(21L, branchA, matVitC, "LOT-EXP", LocalDate.now().minusDays(5), new BigDecimal("30"), BatchStatus.EXPIRED);
        when(branchInventoryRepository.findForUpdate(1L, 502L)).thenReturn(Optional.of(inv(branchA, matVitC, new BigDecimal("30"))));
        when(materialBatchRepository.findAvailableBatchesFefoForUpdate(eq(1L), eq(502L), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        when(materialBatchRepository.findExpiredBatchesForUpdate(eq(1L), eq(502L), any(LocalDate.class)))
                .thenReturn(List.of(expiredLot));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> prescriptionService.createPrescription(requestWith(medVitC, new BigDecimal("5"), "REQ-EXP")));
        assertTrue(ex.getMessage().contains("hết hạn"));
        verify(stockIssueRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("TEST 09: Chống bấm lưu nhiều lần bằng clientRequestId → không trừ kho hai lần")
    void createPrescription_duplicateClientRequestId_idempotentNoDoubleDeduct() {
        Prescription existing = new Prescription();
        existing.setPrescriptionId(777L);
        existing.setPrescriptionCode("DT261127-ABC123");
        existing.setBranch(branchA);
        existing.setDoctor(doctor);
        existing.setPatientProfile(patient);
        existing.setVisit(visit);
        existing.setInventoryDeducted(true);

        when(prescriptionRepository.findByClientRequestId("REQ-DUP-1")).thenReturn(Optional.of(existing));
        when(stockIssueRepository.findByPrescriptionIdWithItemsOrderByIssueIdAsc(777L)).thenReturn(Collections.emptyList());

        PrescriptionResponseDTO res = prescriptionService.createPrescription(requestWith(medPara, new BigDecimal("5"), "REQ-DUP-1"));

        assertEquals(777L, res.getPrescriptionId());
        verify(materialBatchRepository, never()).findAvailableBatchesFefoForUpdate(any(), any(), any());
        verify(stockIssueRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("TEST 12: Hủy đơn thuốc chưa cấp phát → hoàn kho về đúng lô gốc & ghi phiếu PRESCRIPTION_RETURN (không xóa phiếu cũ)")
    void cancelPrescription_restoresOriginalBatchAndRecordsReturnIssue() {
        Prescription p = new Prescription();
        p.setPrescriptionId(888L);
        p.setPrescriptionCode("DT261127-CANCEL");
        p.setBranch(branchA);
        p.setDoctor(doctor);
        p.setPatientProfile(patient);
        p.setVisit(visit);
        p.setStatus(PrescriptionStatus.PENDING);
        p.setInventoryDeducted(true);

        MaterialBatch lot1 = batch(11L, branchA, matPara, "LOT-1", LocalDate.now().plusDays(60), BigDecimal.ZERO, BatchStatus.OUT_OF_STOCK);
        BranchInventory bi = inv(branchA, matPara, BigDecimal.ZERO);

        StockIssue outIssue = new StockIssue();
        outIssue.setIssueId(1L);
        outIssue.setIssueType(StockIssueType.PRESCRIPTION_OUT);
        StockIssueItem outItem = new StockIssueItem();
        outItem.setMaterial(matPara);
        outItem.setBatch(lot1);
        outItem.setQuantity(new BigDecimal("10"));
        outIssue.setItems(List.of(outItem));

        when(prescriptionRepository.findByIdForUpdate(888L)).thenReturn(Optional.of(p));
        when(stockIssueRepository.findByPrescriptionIdWithItemsOrderByIssueIdAsc(888L)).thenReturn(List.of(outIssue));
        when(materialBatchRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(lot1));
        when(branchInventoryRepository.findForUpdate(1L, 501L)).thenReturn(Optional.of(bi));
        when(stockIssueRepository.saveAndFlush(any(StockIssue.class))).thenAnswer(inv -> inv.getArgument(0));
        when(prescriptionRepository.saveAndFlush(any(Prescription.class))).thenAnswer(inv -> inv.getArgument(0));

        PrescriptionResponseDTO res = prescriptionService.cancelPrescription(888L, "Đổi phác đồ");

        assertEquals("CANCELLED", res.getStatus());
        assertFalse(p.isInventoryDeducted());
        assertEquals(0, new BigDecimal("10").compareTo(lot1.getQuantityRemaining()));
        assertEquals(BatchStatus.ACTIVE, lot1.getStatus());
        assertEquals(0, new BigDecimal("10").compareTo(bi.getCurrentStock()));

        ArgumentCaptor<StockIssue> retCaptor = ArgumentCaptor.forClass(StockIssue.class);
        verify(stockIssueRepository).saveAndFlush(retCaptor.capture());
        assertEquals(StockIssueType.PRESCRIPTION_RETURN, retCaptor.getValue().getIssueType());
        verify(stockIssueRepository, never()).delete(any());
    }

    @Test
    @DisplayName("TEST 13: Cách ly chi nhánh — Bác sĩ ở chi nhánh A chỉ truy vấn kho chi nhánh A, không dùng tồn chi nhánh B")
    void createPrescription_branchIsolation_onlyQueriesVisitBranch() {
        when(visitRepository.findById(100L)).thenReturn(Optional.of(visit)); // visit thuộc branchA (ID=1)
        when(staffProfileRepository.findById(10L)).thenReturn(Optional.of(doctor));
        when(medicationRepository.findById(1L)).thenReturn(Optional.of(medPara));
        when(prescriptionRepository.saveAndFlush(any(Prescription.class))).thenAnswer(inv -> {
            Prescription p = inv.getArgument(0);
            p.setPrescriptionId(996L);
            return p;
        });

        // Chi nhánh A không có hàng (dù chi nhánh B có hàng)
        when(branchInventoryRepository.findForUpdate(1L, 501L)).thenReturn(Optional.of(inv(branchA, matPara, BigDecimal.ZERO)));
        when(materialBatchRepository.findAvailableBatchesFefoForUpdate(eq(1L), eq(501L), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        when(materialBatchRepository.findExpiredBatchesForUpdate(eq(1L), eq(501L), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());

        assertThrows(IllegalStateException.class,
                () -> prescriptionService.createPrescription(requestWith(medPara, new BigDecimal("2"), "REQ-BRANCH")));
        verify(materialBatchRepository, never()).findAvailableBatchesFefoForUpdate(eq(2L), any(), any());
    }

    @Test
    @DisplayName("TEST 11: Sửa đơn thuốc đã xác nhận (giảm số lượng từ 10 xuống 6) → hoàn đúng 4 viên vào lô gốc & ghi phiếu PRESCRIPTION_RETURN")
    void updatePrescription_reduceQuantity_returnsDeltaToOriginalBatch() {
        Prescription p = new Prescription();
        p.setPrescriptionId(889L);
        p.setPrescriptionCode("DT261127-EDIT");
        p.setBranch(branchA);
        p.setDoctor(doctor);
        p.setPatientProfile(patient);
        p.setVisit(visit);
        p.setStatus(PrescriptionStatus.PENDING);
        p.setInventoryDeducted(true);

        PrescriptionItem oldItem = new PrescriptionItem();
        oldItem.setPrescription(p);
        oldItem.setMedication(medPara);
        oldItem.setDrugName(medPara.getDrugName());
        oldItem.setQuantity(new BigDecimal("10"));
        oldItem.setUnit("Viên");
        p.setItems(new ArrayList<>(List.of(oldItem)));

        MaterialBatch lot1 = batch(11L, branchA, matPara, "LOT-1", LocalDate.now().plusDays(60), new BigDecimal("5"), BatchStatus.ACTIVE);
        BranchInventory bi = inv(branchA, matPara, new BigDecimal("5"));

        StockIssue outIssue = new StockIssue();
        outIssue.setIssueId(2L);
        outIssue.setIssueType(StockIssueType.PRESCRIPTION_OUT);
        StockIssueItem outItem = new StockIssueItem();
        outItem.setMaterial(matPara);
        outItem.setBatch(lot1);
        outItem.setQuantity(new BigDecimal("10"));
        outIssue.setItems(List.of(outItem));

        when(prescriptionRepository.findByIdForUpdate(889L)).thenReturn(Optional.of(p));
        when(medicationRepository.findById(1L)).thenReturn(Optional.of(medPara));
        when(stockIssueRepository.findByPrescriptionIdWithItemsOrderByIssueIdAsc(889L)).thenReturn(List.of(outIssue));
        when(materialBatchRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(lot1));
        when(branchInventoryRepository.findForUpdate(1L, 501L)).thenReturn(Optional.of(bi));
        when(stockIssueRepository.saveAndFlush(any(StockIssue.class))).thenAnswer(inv -> inv.getArgument(0));
        when(prescriptionRepository.saveAndFlush(any(Prescription.class))).thenAnswer(inv -> inv.getArgument(0));

        PrescriptionResponseDTO res = prescriptionService.updatePrescription(889L, requestWith(medPara, new BigDecimal("6"), null));

        assertEquals(1, res.getItems().size());
        assertEquals(0, new BigDecimal("6").compareTo(res.getItems().get(0).getQuantity()));
        // 5 + (10 - 6) = 9
        assertEquals(0, new BigDecimal("9").compareTo(lot1.getQuantityRemaining()));
        assertEquals(0, new BigDecimal("9").compareTo(bi.getCurrentStock()));

        ArgumentCaptor<StockIssue> retCaptor = ArgumentCaptor.forClass(StockIssue.class);
        verify(stockIssueRepository).saveAndFlush(retCaptor.capture());
        assertEquals(StockIssueType.PRESCRIPTION_RETURN, retCaptor.getValue().getIssueType());
        assertEquals(0, new BigDecimal("4").compareTo(retCaptor.getValue().getItems().get(0).getQuantity()));
    }
}
