package com.cvdcms.service;

import com.cvdcms.DTO.StockIssueCreateDTO;
import com.cvdcms.DTO.StockIssueResponseDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.BatchStatus;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.entity.enums.StockIssueType;
import com.cvdcms.repository.*;
import com.cvdcms.service.impl.StockIssueServiceImpl;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StockIssueServiceTest {

    @Mock private BranchRepository branchRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private MaterialBatchRepository batchRepository;
    @Mock private BranchInventoryRepository inventoryRepository;
    @Mock private StockIssueRepository issueRepository;
    @Mock private StaffProfileRepository staffRepository;
    @Mock private UserRepository userRepository;
    @Mock private BranchAccessService accessService;

    private StockIssueServiceImpl issueService;

    private Branch branch1;
    private Branch branch2;
    private Material material;
    private StaffProfile staff;

    @BeforeEach
    void setUp() {
        issueService = new StockIssueServiceImpl(
                branchRepository, materialRepository, batchRepository,
                inventoryRepository, issueRepository, staffRepository,
                userRepository, accessService
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

    @Test
    @DisplayName("create - Reject null DTO, invalid branchId, empty item list, or unknown issue type")
    void create_basicHeaderValidation_throws400() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> issueService.create(null, 1L)).getStatusCode());

        StockIssueCreateDTO badBranch = new StockIssueCreateDTO();
        badBranch.setBranchId(0L);
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> issueService.create(badBranch, 1L)).getStatusCode());

        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);

        StockIssueCreateDTO emptyItems = validIssueDto("INTERNAL_USE");
        emptyItems.setItems(List.of());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> issueService.create(emptyItems, 1L)).getStatusCode());

        StockIssueCreateDTO badType = validIssueDto("UNKNOWN_TYPE");
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> issueService.create(badType, 1L)).getStatusCode());

        StockIssueCreateDTO futureDate = validIssueDto("INTERNAL_USE");
        futureDate.setIssueDate(LocalDate.now().plusDays(1));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> issueService.create(futureDate, 1L)).getStatusCode());
    }

    @Test
    @DisplayName("create - OTHER issue type requires non-blank issueReason <= 255 characters")
    void create_otherTypeValidation_requiresReason() {
        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);

        StockIssueCreateDTO missingReason = validIssueDto("OTHER");
        missingReason.setIssueReason("   ");
        ResponseStatusException ex1 = assertThrows(ResponseStatusException.class,
                () -> issueService.create(missingReason, 1L));
        assertEquals(HttpStatus.BAD_REQUEST, ex1.getStatusCode());
        assertTrue(ex1.getReason().contains("lý do"));

        StockIssueCreateDTO longReason = validIssueDto("OTHER");
        longReason.setIssueReason("a".repeat(256));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> issueService.create(longReason, 1L)).getStatusCode());
    }

    @Test
    @DisplayName("create - TRANSFER validates required destination branch, different from source, and active")
    void create_transferDestinationValidation_throwsError() {
        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch1));

        StockIssueCreateDTO noDest = validIssueDto("TRANSFER");
        noDest.setDestinationBranchId(null);
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> issueService.create(noDest, 1L)).getStatusCode());

        StockIssueCreateDTO sameDest = validIssueDto("TRANSFER");
        sameDest.setDestinationBranchId(1L);
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> issueService.create(sameDest, 1L)).getStatusCode());

        StockIssueCreateDTO missingDest = validIssueDto("TRANSFER");
        missingDest.setDestinationBranchId(999L);
        when(branchRepository.findById(999L)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> issueService.create(missingDest, 1L)).getStatusCode());

        branch2.setIsActive(false);
        StockIssueCreateDTO inactiveDest = validIssueDto("TRANSFER");
        inactiveDest.setDestinationBranchId(2L);
        when(branchRepository.findById(2L)).thenReturn(Optional.of(branch2));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> issueService.create(inactiveDest, 1L)).getStatusCode());
    }

    @Test
    @DisplayName("create - Auto-allocate FEFO across multiple batches by earliest expiry and mark depleted batch OUT_OF_STOCK")
    void create_autoFefoMultipleBatches_deductsInExpiryOrder() {
        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch1));
        when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(staff));
        when(materialRepository.findById(100L)).thenReturn(Optional.of(material));

        BranchInventory inv = new BranchInventory();
        inv.setBranch(branch1);
        inv.setMaterial(material);
        inv.setCurrentStock(new BigDecimal("15.000"));
        when(inventoryRepository.findForUpdate(1L, 100L)).thenReturn(Optional.of(inv));

        MaterialBatch soonest = buildBatch(1L, "LO-SOON", LocalDate.now().plusDays(15), "4.000", "100000");
        MaterialBatch later = buildBatch(2L, "LO-LATER", LocalDate.now().plusMonths(6), "11.000", "120000");
        when(batchRepository.findAvailableBatchesFefoForUpdate(eq(1L), eq(100L), any(LocalDate.class)))
                .thenReturn(List.of(soonest, later));
        when(issueRepository.save(any(StockIssue.class))).thenAnswer(i -> i.getArgument(0));

        StockIssueCreateDTO dto = validIssueDto("INTERNAL_USE");
        dto.getItems().get(0).setQuantity(new BigDecimal("7.000")); // 4 từ soonest + 3 từ later

        StockIssueResponseDTO res = issueService.create(dto, 1L);

        assertEquals(BatchStatus.OUT_OF_STOCK, soonest.getStatus());
        assertEquals(new BigDecimal("0.000"), soonest.getQuantityRemaining());
        assertEquals(BatchStatus.ACTIVE, later.getStatus());
        assertEquals(new BigDecimal("8.000"), later.getQuantityRemaining());
        assertEquals(new BigDecimal("8.000"), inv.getCurrentStock());
        assertEquals(2, res.getItemCount());
        assertEquals(new BigDecimal("7.000"), res.getTotalQuantity());
        // 4*100,000 + 3*120,000 = 760,000
        assertEquals(new BigDecimal("760000.00"), res.getTotalEstimatedValue());
    }

    @Test
    @DisplayName("create - Block expired batch selection for INTERNAL_USE/OTHER/TRANSFER, allow for DISCARD_EXPIRED")
    void create_expiredBatchRule_blockedForUseAndAllowedForDiscard() {
        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch1));
        when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(staff));
        when(materialRepository.findById(100L)).thenReturn(Optional.of(material));

        BranchInventory inv = new BranchInventory();
        inv.setBranch(branch1);
        inv.setMaterial(material);
        inv.setCurrentStock(new BigDecimal("10.000"));
        when(inventoryRepository.findForUpdate(1L, 100L)).thenReturn(Optional.of(inv));

        MaterialBatch expired = buildBatch(9L, "LO-EXP", LocalDate.now().minusDays(2), "10.000", "50000");
        when(batchRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(expired));

        // INTERNAL_USE bị chặn
        StockIssueCreateDTO useExpired = validIssueDto("INTERNAL_USE");
        useExpired.getItems().get(0).setBatchId(9L);
        useExpired.getItems().get(0).setQuantity(new BigDecimal("2.000"));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> issueService.create(useExpired, 1L));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("đã hết hạn"));

        // DISCARD_EXPIRED thành công và giữ status EXPIRED khi còn tồn
        when(issueRepository.save(any(StockIssue.class))).thenAnswer(i -> i.getArgument(0));
        StockIssueCreateDTO discard = validIssueDto("DISCARD_EXPIRED");
        discard.getItems().get(0).setBatchId(9L);
        discard.getItems().get(0).setQuantity(new BigDecimal("3.000"));

        StockIssueResponseDTO res = issueService.create(discard, 1L);
        assertEquals("DISCARD_EXPIRED", res.getIssueType());
        assertEquals(new BigDecimal("7.000"), expired.getQuantityRemaining());
        assertEquals(BatchStatus.EXPIRED, expired.getStatus());
        assertEquals(new BigDecimal("7.000"), inv.getCurrentStock());
    }

    @Test
    @DisplayName("create - DISCARD_EXPIRED auto-draws from expired batches and rejects insufficient expired stock")
    void create_autoDiscardExpired_validatesExpiredStock() {
        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch1));
        when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(staff));
        when(materialRepository.findById(100L)).thenReturn(Optional.of(material));

        BranchInventory inv = new BranchInventory();
        inv.setBranch(branch1);
        inv.setMaterial(material);
        inv.setCurrentStock(new BigDecimal("20.000"));
        when(inventoryRepository.findForUpdate(1L, 100L)).thenReturn(Optional.of(inv));

        MaterialBatch expired = buildBatch(9L, "LO-EXP", LocalDate.now().minusDays(2), "2.000", "50000");
        when(batchRepository.findExpiredBatchesForUpdate(eq(1L), eq(100L), any(LocalDate.class)))
                .thenReturn(List.of(expired));

        StockIssueCreateDTO dto = validIssueDto("DISCARD_EXPIRED");
        dto.getItems().get(0).setQuantity(new BigDecimal("5.000")); // chỉ có 2 hết hạn

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> issueService.create(dto, 1L));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("hết hạn"));
    }

    @Test
    @DisplayName("create - Reject duplicate auto-FEFO materials or mixing FEFO and explicit batch in same issue")
    void create_duplicateMaterialRowsValidation_throws400() {
        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch1));
        when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(staff));
        when(materialRepository.findById(100L)).thenReturn(Optional.of(material));

        BranchInventory inv = new BranchInventory();
        inv.setBranch(branch1);
        inv.setMaterial(material);
        inv.setCurrentStock(new BigDecimal("20.000"));
        when(inventoryRepository.findForUpdate(1L, 100L)).thenReturn(Optional.of(inv));
        when(batchRepository.findAvailableBatchesFefoForUpdate(eq(1L), eq(100L), any(LocalDate.class)))
                .thenReturn(List.of(buildBatch(1L, "LO-1", LocalDate.now().plusMonths(3), "20.000", "10000")));

        StockIssueCreateDTO.Item r1 = new StockIssueCreateDTO.Item();
        r1.setMaterialId(100L);
        r1.setQuantity(new BigDecimal("1.000"));

        StockIssueCreateDTO.Item r2 = new StockIssueCreateDTO.Item();
        r2.setMaterialId(100L);
        r2.setQuantity(new BigDecimal("2.000"));

        StockIssueCreateDTO dupAuto = validIssueDto("INTERNAL_USE");
        dupAuto.setItems(List.of(r1, r2));

        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> issueService.create(dupAuto, 1L)).getStatusCode());
    }

    @Test
    @DisplayName("create - Reject batch from another branch or quantity exceeding batch stock")
    void create_batchWrongBranchOrInsufficientBatchQty_throws400() {
        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch1));
        when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(staff));
        when(materialRepository.findById(100L)).thenReturn(Optional.of(material));

        BranchInventory inv = new BranchInventory();
        inv.setBranch(branch1);
        inv.setMaterial(material);
        inv.setCurrentStock(new BigDecimal("20.000"));
        when(inventoryRepository.findForUpdate(1L, 100L)).thenReturn(Optional.of(inv));

        MaterialBatch otherBranchBatch = buildBatch(77L, "LO-CN2", LocalDate.now().plusMonths(3), "10.000", "50000");
        otherBranchBatch.setBranch(branch2);
        when(batchRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(otherBranchBatch));

        StockIssueCreateDTO wrongBranch = validIssueDto("INTERNAL_USE");
        wrongBranch.getItems().get(0).setBatchId(77L);
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> issueService.create(wrongBranch, 1L)).getStatusCode());

        otherBranchBatch.setBranch(branch1);
        otherBranchBatch.setQuantityRemaining(new BigDecimal("1.000"));
        StockIssueCreateDTO tooMuch = validIssueDto("INTERNAL_USE");
        tooMuch.getItems().get(0).setBatchId(77L);
        tooMuch.getItems().get(0).setQuantity(new BigDecimal("5.000"));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> issueService.create(tooMuch, 1L)).getStatusCode());
    }

    @Test
    @DisplayName("searchIssues & searchTransfers - Check permissions, date range, and map DTOs")
    void searchIssuesAndTransfers_validatesAndMaps() {
        Pageable pageable = PageRequest.of(0, 10);
        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);

        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,
                        () -> issueService.searchIssues(1L, null, true, null, LocalDate.now(), LocalDate.now().minusDays(1), pageable, 1L)).getStatusCode());

        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,
                        () -> issueService.searchIssues(1L, "BAD_TYPE", true, null, null, null, pageable, 1L)).getStatusCode());

        StockIssue si = new StockIssue();
        si.setIssueId(20L);
        si.setIssueCode("PX020");
        si.setBranch(branch1);
        si.setIssueType(StockIssueType.INTERNAL_USE);
        si.setIssueDate(LocalDate.now());
        si.setIssuer(staff);
        when(issueRepository.searchIssues(1L, StockIssueType.INTERNAL_USE, true, null, null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(si), pageable, 1));

        Page<StockIssueResponseDTO> issues = issueService.searchIssues(1L, "INTERNAL_USE", true, " ", null, null, pageable, 1L);
        assertEquals(1, issues.getTotalElements());
        assertEquals("PX020", issues.getContent().get(0).getIssueCode());

        StockIssue tr = new StockIssue();
        tr.setIssueId(21L);
        tr.setIssueCode("DC021");
        tr.setBranch(branch1);
        tr.setDestinationBranch(branch2);
        tr.setIssueType(StockIssueType.TRANSFER);
        tr.setIssueDate(LocalDate.now());
        tr.setIssuer(staff);
        when(issueRepository.searchTransfers(1L, 2L, "DC", null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(tr), pageable, 1));

        Page<StockIssueResponseDTO> transfers = issueService.searchTransfers(1L, 2L, " DC ", null, null, pageable, 1L);
        assertEquals(1, transfers.getTotalElements());
        assertEquals(2L, transfers.getContent().get(0).getDestinationBranchId());
    }

    @Test
    @DisplayName("getById - Allow source or destination branch to view transfer; block unauthorized users")
    void getById_checksSourceOrDestinationAccess() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> issueService.getById(0L, 1L)).getStatusCode());

        StockIssue transfer = new StockIssue();
        transfer.setIssueId(300L);
        transfer.setIssueCode("DC300");
        transfer.setBranch(branch1);
        transfer.setDestinationBranch(branch2);
        transfer.setIssueType(StockIssueType.TRANSFER);
        transfer.setIssueDate(LocalDate.now());
        transfer.setIssuer(staff);

        when(issueRepository.findByIdWithDetails(300L)).thenReturn(Optional.of(transfer));
        when(accessService.canAccessBranch(20L, 1L)).thenReturn(false);
        when(accessService.canAccessBranch(20L, 2L)).thenReturn(true);

        // User chi nhánh đích được phép xem
        StockIssueResponseDTO viewed = issueService.getById(300L, 20L);
        assertEquals("DC300", viewed.getIssueCode());

        // Chặn user ngoài cả 2 chi nhánh
        when(accessService.canAccessBranch(99L, 1L)).thenReturn(false);
        when(accessService.canAccessBranch(99L, 2L)).thenReturn(false);
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> issueService.getById(300L, 99L)).getStatusCode());
    }

    private StockIssueCreateDTO validIssueDto(String issueType) {
        StockIssueCreateDTO dto = new StockIssueCreateDTO();
        dto.setBranchId(1L);
        dto.setIssueType(issueType);
        dto.setIssueDate(LocalDate.now());
        if ("TRANSFER".equals(issueType)) {
            dto.setDestinationBranchId(2L);
        } else if ("OTHER".equals(issueType)) {
            dto.setIssueReason("Hỗ trợ hội thảo");
        }
        StockIssueCreateDTO.Item item = new StockIssueCreateDTO.Item();
        item.setMaterialId(100L);
        item.setQuantity(new BigDecimal("2.000"));
        dto.setItems(List.of(item));
        return dto;
    }

    private MaterialBatch buildBatch(Long id, String code, LocalDate expiry, String qty, String price) {
        MaterialBatch b = new MaterialBatch();
        b.setBatchId(id);
        b.setBranch(branch1);
        b.setMaterial(material);
        b.setBatchNumber(code);
        b.setExpiryDate(expiry);
        b.setQuantityRemaining(new BigDecimal(qty));
        b.setImportPrice(new BigDecimal(price));
        b.setStatus(BatchStatus.ACTIVE);
        return b;
    }
}
