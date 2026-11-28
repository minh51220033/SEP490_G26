package com.cvdcms.service;

import com.cvdcms.DTO.SupplierDebtSummaryDTO;
import com.cvdcms.DTO.SupplierPaymentCreateDTO;
import com.cvdcms.DTO.SupplierPaymentResponseDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.repository.*;
import com.cvdcms.service.impl.SupplierPaymentServiceImpl;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupplierPaymentServiceTest {

    @Mock private SupplierRepository supplierRepository;
    @Mock private BranchRepository branchRepository;
    @Mock private StockReceiptRepository receiptRepository;
    @Mock private SupplierPaymentRepository paymentRepository;
    @Mock private StaffProfileRepository staffRepository;
    @Mock private UserRepository userRepository;
    @Mock private BranchAccessService accessService;

    private SupplierPaymentServiceImpl paymentService;

    private Branch branch;
    private Branch otherBranch;
    private Supplier supplier;
    private StaffProfile staff;

    @BeforeEach
    void setUp() {
        paymentService = new SupplierPaymentServiceImpl(
                supplierRepository, branchRepository, receiptRepository,
                paymentRepository, staffRepository, userRepository, accessService
        );

        branch = new Branch();
        branch.setBranchId(1L);
        branch.setBranchName("Cơ sở Quận 1");

        otherBranch = new Branch();
        otherBranch.setBranchId(2L);
        otherBranch.setBranchName("Cơ sở Đống Đa");

        supplier = new Supplier();
        supplier.setSupplierId(10L);
        supplier.setSupplierCode("NCC-3M");
        supplier.setSupplierName("Công ty 3M");
        supplier.setTaxCode("0101234567");
        supplier.setPhoneNumber("0901234567");
        supplier.setIsActive(true);

        staff = new StaffProfile();
        staff.setStaffId(5L);
        staff.setFullName("Kế toán Kho");
        staff.setStatus(StaffStatus.ACTIVE);
    }

    @Test
    @DisplayName("getDebtSummaries - Reject invalid branchId or unauthorized branch access")
    void getDebtSummaries_invalidBranchOrUnauthorized_throws() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,
                        () -> paymentService.getDebtSummaries(0L, null, 1L)).getStatusCode());

        when(accessService.canAccessBranch(9L, 1L)).thenReturn(false);
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class,
                        () -> paymentService.getDebtSummaries(1L, null, 9L)).getStatusCode());
    }

    @Test
    @DisplayName("getDebtSummaries - Aggregate supplier debt via batch query and clamp overpaid debt to 0")
    void getDebtSummaries_batchAggregation_computesAndClampsAtZero() {
        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
        Supplier s2 = new Supplier();
        s2.setSupplierId(11L);
        s2.setSupplierCode("NCC-02");
        s2.setSupplierName("NCC Overpaid");
        s2.setIsActive(true);

        when(supplierRepository.searchSuppliers(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(supplier, s2)));
        when(receiptRepository.aggregateReceiptsByBranchAndSuppliers(eq(1L), anyList()))
                .thenReturn(List.<Object[]>of(
                        new Object[]{10L, 3, new BigDecimal("5000000.00")},
                        new Object[]{11L, 1, new BigDecimal("1000000.00")}
                ));
        when(paymentRepository.aggregatePaidByBranchAndSuppliers(eq(1L), anyList()))
                .thenReturn(List.<Object[]>of(
                        new Object[]{10L, new BigDecimal("2000000.00")},
                        new Object[]{11L, new BigDecimal("1200000.00")}
                ));

        List<SupplierDebtSummaryDTO> summaries = paymentService.getDebtSummaries(1L, " ", 1L);

        assertEquals(2, summaries.size());
        assertEquals(3, summaries.get(0).getReceiptCount());
        assertEquals(new BigDecimal("3000000.00"), summaries.get(0).getRemainingDebt());
        assertEquals(BigDecimal.ZERO, summaries.get(1).getRemainingDebt());
    }

    @Test
    @DisplayName("searchPayments - Validate branch access, date range, and map to SupplierPaymentResponseDTO")
    void searchPayments_validatesAndMaps() {
        Pageable pageable = PageRequest.of(0, 10);
        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);

        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,
                        () -> paymentService.searchPayments(1L, null, null, LocalDate.now(), LocalDate.now().minusDays(1), pageable, 1L)).getStatusCode());

        SupplierPayment sp = new SupplierPayment();
        sp.setSupplierPaymentId(100L);
        sp.setPaymentCode("PC100");
        sp.setBranch(branch);
        sp.setSupplier(supplier);
        sp.setPaymentDate(LocalDate.now());
        sp.setAmount(new BigDecimal("500000.00"));
        sp.setPaymentMethod("CASH");
        sp.setPaidBy(staff);

        when(paymentRepository.searchPayments(1L, 10L, "PC", null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(sp), pageable, 1));

        Page<SupplierPaymentResponseDTO> res = paymentService.searchPayments(1L, 10L, " PC ", null, null, pageable, 1L);
        assertEquals(1, res.getTotalElements());
        assertEquals("PC100", res.getContent().get(0).getPaymentCode());
        assertEquals("Kế toán Kho", res.getContent().get(0).getPaidByName());
    }

    @Test
    @DisplayName("createPayment - Validate basic inputs (branch, supplier, amount <= 0, future date, method, reference)")
    void createPayment_inputValidation_throws400() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> paymentService.createPayment(null, 1L)).getStatusCode());

        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);

        SupplierPaymentCreateDTO zeroAmt = validDto();
        zeroAmt.setAmount(BigDecimal.ZERO);
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> paymentService.createPayment(zeroAmt, 1L)).getStatusCode());

        SupplierPaymentCreateDTO futureDate = validDto();
        futureDate.setPaymentDate(LocalDate.now().plusDays(1));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> paymentService.createPayment(futureDate, 1L)).getStatusCode());

        SupplierPaymentCreateDTO badMethod = validDto();
        badMethod.setPaymentMethod("BITCOIN");
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> paymentService.createPayment(badMethod, 1L)).getStatusCode());

        SupplierPaymentCreateDTO badRef = validDto();
        badRef.setReferenceNo("UNC @@");
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> paymentService.createPayment(badRef, 1L)).getStatusCode());
    }

    @Test
    @DisplayName("createPayment - Block double-submit via clientRequestId (30s) and duplicate payment within 5s")
    void createPayment_idempotencyAndDuplicateWithin5s_throws400() {
        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch));
        when(supplierRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(supplier));
        when(receiptRepository.sumTotalAmountByBranchAndSupplier(1L, 10L)).thenReturn(new BigDecimal("1000000.00"));
        when(paymentRepository.sumPaidAmountByBranchAndSupplier(1L, 10L)).thenReturn(BigDecimal.ZERO);
        when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(staff));
        when(paymentRepository.save(any(SupplierPayment.class))).thenAnswer(i -> i.getArgument(0));

        // Lần 1 với clientRequestId thành công
        SupplierPaymentCreateDTO first = validDto();
        first.setClientRequestId("REQ-UNIQ-1");
        assertNotNull(paymentService.createPayment(first, 1L));

        // Lần 2 trùng clientRequestId -> chặn
        SupplierPaymentCreateDTO dupReq = validDto();
        dupReq.setClientRequestId("REQ-UNIQ-1");
        ResponseStatusException dupEx = assertThrows(ResponseStatusException.class,
                () -> paymentService.createPayment(dupReq, 1L));
        assertEquals(HttpStatus.BAD_REQUEST, dupEx.getStatusCode());
        assertTrue(dupEx.getReason().contains("nhiều lần"));

        // Trùng giao dịch 5 giây từ DB
        SupplierPaymentCreateDTO dupDb = validDto();
        dupDb.setClientRequestId("REQ-UNIQ-2");
        when(paymentRepository.existsRecentDuplicatePayment(eq(1L), eq(10L), isNull(), eq(new BigDecimal("200000.00")), any(LocalDateTime.class)))
                .thenReturn(true);
        ResponseStatusException dbEx = assertThrows(ResponseStatusException.class,
                () -> paymentService.createPayment(dupDb, 1L));
        assertEquals(HttpStatus.BAD_REQUEST, dbEx.getStatusCode());
    }

    @Test
    @DisplayName("createPayment - Verify linked receipt belongs to same branch/supplier and does not exceed remaining debt")
    void createPayment_linkedReceiptValidation_throws400() {
        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch));
        when(supplierRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(supplier));

        StockReceipt wrongBranchReceipt = new StockReceipt();
        wrongBranchReceipt.setReceiptId(50L);
        wrongBranchReceipt.setReceiptCode("PN050");
        wrongBranchReceipt.setBranch(otherBranch);
        wrongBranchReceipt.setSupplier(supplier);
        wrongBranchReceipt.setTotalAmount(new BigDecimal("500000.00"));
        when(receiptRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(wrongBranchReceipt));

        SupplierPaymentCreateDTO dto = validDto();
        dto.setReceiptId(50L);
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> paymentService.createPayment(dto, 1L)).getStatusCode());

        // Đúng chi nhánh nhưng phiếu đã trả đủ
        wrongBranchReceipt.setBranch(branch);
        when(paymentRepository.sumPaidAmountByReceipt(50L)).thenReturn(new BigDecimal("500000.00"));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> paymentService.createPayment(dto, 1L)).getStatusCode());

        // Phiếu còn nợ 100.000 nhưng trả 200.000
        when(paymentRepository.sumPaidAmountByReceipt(50L)).thenReturn(new BigDecimal("400000.00"));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> paymentService.createPayment(dto, 1L)).getStatusCode());
    }

    @Test
    @DisplayName("createPayment - Pay against linked receipt successfully and return complete DTO")
    void createPayment_linkedReceiptSuccess_returnsDto() {
        when(accessService.canAccessBranch(1L, 1L)).thenReturn(true);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch));
        when(supplierRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(supplier));

        StockReceipt receipt = new StockReceipt();
        receipt.setReceiptId(50L);
        receipt.setReceiptCode("PN050");
        receipt.setBranch(branch);
        receipt.setSupplier(supplier);
        receipt.setTotalAmount(new BigDecimal("1000000.00"));
        when(receiptRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(receipt));
        when(paymentRepository.sumPaidAmountByReceipt(50L)).thenReturn(new BigDecimal("200000.00"));
        when(receiptRepository.sumTotalAmountByBranchAndSupplier(1L, 10L)).thenReturn(new BigDecimal("1000000.00"));
        when(paymentRepository.sumPaidAmountByBranchAndSupplier(1L, 10L)).thenReturn(new BigDecimal("200000.00"));
        when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(staff));
        when(paymentRepository.save(any(SupplierPayment.class))).thenAnswer(inv -> {
            SupplierPayment p = inv.getArgument(0);
            p.setSupplierPaymentId(88L);
            return p;
        });

        SupplierPaymentCreateDTO dto = validDto();
        dto.setReceiptId(50L);
        dto.setAmount(new BigDecimal("500000.00"));
        dto.setPaymentMethod("CASH");
        dto.setReferenceNo("PC-001");

        SupplierPaymentResponseDTO res = paymentService.createPayment(dto, 1L);

        assertEquals(88L, res.getSupplierPaymentId());
        assertEquals(50L, res.getReceiptId());
        assertEquals("PN050", res.getReceiptCode());
        assertEquals(new BigDecimal("500000.00"), res.getAmount());
        assertEquals("CASH", res.getPaymentMethod());
        assertEquals("Kế toán Kho", res.getPaidByName());
    }

    private SupplierPaymentCreateDTO validDto() {
        SupplierPaymentCreateDTO dto = new SupplierPaymentCreateDTO();
        dto.setBranchId(1L);
        dto.setSupplierId(10L);
        dto.setPaymentDate(LocalDate.now());
        dto.setAmount(new BigDecimal("200000.00"));
        dto.setPaymentMethod("BANK_TRANSFER");
        return dto;
    }
}
