package com.cvdcms;

import com.cvdcms.DTO.PaymentAllocationDTO;
import com.cvdcms.DTO.PaymentAllocationPreviewDTO;
import com.cvdcms.DTO.PaymentRequestDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.impl.PaymentAllocationServiceImpl;
import com.cvdcms.service.impl.PaymentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ItemizedPaymentAllocationUnitTest {

    @Mock
    private PaymentAllocationRepository paymentAllocationRepository;
    @Mock
    private InvoiceItemRepository invoiceItemRepository;
    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PatientProfileRepository patientProfileRepository;
    @Mock
    private BranchRepository branchRepository;
    @Mock
    private StaffProfileRepository staffProfileRepository;
    @Mock
    private StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PatientLoyaltyWalletRepository patientLoyaltyWalletRepository;
    @Mock
    private PointTransactionRepository pointTransactionRepository;
    @Mock
    private MembershipTierRepository membershipTierRepository;
    @Mock
    private InvoiceAuditLogRepository invoiceAuditLogRepository;
    @Mock
    private BranchBankAccountRepository branchBankAccountRepository;

    private PaymentAllocationServiceImpl allocationService;
    private PaymentServiceImpl paymentService;

    private User adminUser;
    private StaffProfile cashierStaff;
    private PatientProfile patient;
    private Branch branch;

    @BeforeEach
    void setUp() {
        allocationService = new PaymentAllocationServiceImpl(
                paymentAllocationRepository,
                invoiceItemRepository,
                invoiceRepository
        );

        paymentService = new PaymentServiceImpl(
                paymentRepository,
                invoiceRepository,
                invoiceAuditLogRepository,
                patientProfileRepository,
                branchRepository,
                staffProfileRepository,
                staffBranchAssignmentRepository,
                patientLoyaltyWalletRepository,
                pointTransactionRepository,
                membershipTierRepository,
                userRepository,
                null,
                null,
                branchBankAccountRepository
        );
        paymentService.setPaymentAllocationService(allocationService);

        // Setup common admin and cashier
        adminUser = new User();
        adminUser.setUserId(1L);
        adminUser.setUsername("admin");
        adminUser.setIsActive(true);
        adminUser.setStatus("ACTIVE");

        cashierStaff = new StaffProfile();
        cashierStaff.setStaffId(10L);
        cashierStaff.setStaffCode("STF01");
        cashierStaff.setFullName("Thu Ngân Test");
        cashierStaff.setStatus(StaffStatus.ACTIVE);
        cashierStaff.setUser(adminUser);

        branch = new Branch();
        branch.setBranchId(100L);
        branch.setBranchName("Cơ sở Quận 1");

        patient = new PatientProfile();
        patient.setProfileId(200L);
        patient.setPatientCode("BN001");
        patient.setFullName("Nguyễn Văn A");
        patient.setDepositBalance(BigDecimal.ZERO);

        when(userRepository.findById(1L)).thenReturn(Optional.of(adminUser));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        when(staffProfileRepository.findByUser_UserId(1L)).thenReturn(Optional.of(cashierStaff));
        when(branchRepository.findById(100L)).thenReturn(Optional.of(branch));
        when(patientProfileRepository.findById(200L)).thenReturn(Optional.of(patient));
        when(patientProfileRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(patient));

        // Default mock saving to return self
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceItemRepository.save(any(InvoiceItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentAllocationRepository.save(any(PaymentAllocation.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /**
     * Ca 1: Khách nộp tạm ứng 500k, hóa đơn có chụp X-quang 300k và nhổ răng 300k
     * -> Kế toán xác nhận trừ ví 500k: X-quang trả hết 300k, nhổ răng trả 200k còn nợ 100k, ví còn 0đ.
     */
    @Test
    @DisplayName("Ca 1: Trừ ví tạm ứng 500k, phân bổ FIFO X-quang 300k (PAID), Nhổ răng 200k (PARTIAL nợ 100k), ví 0đ")
    void testScenario1_DepositWalletDeduction_FifoAllocationToServices() {
        patient.setDepositBalance(new BigDecimal("500000"));

        Invoice invoice = new Invoice();
        invoice.setInvoiceId(1L);
        invoice.setInvoiceCode("HD-TEST-01");
        invoice.setPatientProfile(patient);
        invoice.setBranch(branch);
        invoice.setTotalAmount(new BigDecimal("600000"));
        invoice.setPaidAmount(BigDecimal.ZERO);
        invoice.setRemainingDebt(new BigDecimal("600000"));
        invoice.setPaymentStatus(InvoicePaymentStatus.UNPAID);
        invoice.setStatus(InvoiceStatus.CONFIRMED);

        InvoiceItem item1 = new InvoiceItem();
        item1.setItemId(101L);
        item1.setInvoice(invoice);
        item1.setItemName("Chụp X-quang");
        item1.setItemType(InvoiceItemType.SERVICE);
        item1.setLineOrder(1);
        item1.setUnitPrice(new BigDecimal("300000"));
        item1.setFinalAmount(new BigDecimal("300000"));
        item1.setPayableAmount(new BigDecimal("300000"));
        item1.setPaidAmount(BigDecimal.ZERO);
        item1.setRemainingDebt(new BigDecimal("300000"));
        item1.setPaymentStatus(InvoiceItemPaymentStatus.UNPAID);

        InvoiceItem item2 = new InvoiceItem();
        item2.setItemId(102L);
        item2.setInvoice(invoice);
        item2.setItemName("Nhổ răng khôn");
        item2.setItemType(InvoiceItemType.SERVICE);
        item2.setLineOrder(2);
        item2.setUnitPrice(new BigDecimal("300000"));
        item2.setFinalAmount(new BigDecimal("300000"));
        item2.setPayableAmount(new BigDecimal("300000"));
        item2.setPaidAmount(BigDecimal.ZERO);
        item2.setRemainingDebt(new BigDecimal("300000"));
        item2.setPaymentStatus(InvoiceItemPaymentStatus.UNPAID);

        invoice.setItems(new ArrayList<>(List.of(item1, item2)));

        when(invoiceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(invoice));
        when(invoiceItemRepository.findByInvoice_InvoiceIdOrderByLineOrderAscItemIdAsc(1L))
                .thenReturn(List.of(item1, item2));

        PaymentRequestDTO request = new PaymentRequestDTO();
        request.setInvoiceId(1L);
        request.setAmount(new BigDecimal("500000"));
        request.setPaymentMethod(PaymentMethod.DEPOSIT_WALLET);

        Payment payment = paymentService.processPayment(request, 1L);

        assertNotNull(payment);
        assertEquals(new BigDecimal("500000"), payment.getAmount());
        assertEquals(PaymentMethod.DEPOSIT_WALLET, payment.getPaymentMethod());

        // Kiểm tra ví tạm ứng của bệnh nhân bị trừ đúng 500k về 0đ
        assertEquals(BigDecimal.ZERO, patient.getDepositBalance(), "Ví tạm ứng phải còn 0đ");

        // Kiểm tra phân bổ Item 1 (Chụp X-quang): Đã trả 300k, còn nợ 0đ, trạng thái PAID
        assertEquals(new BigDecimal("300000"), item1.getPaidAmount());
        assertEquals(BigDecimal.ZERO, item1.getRemainingDebt());
        assertEquals(InvoiceItemPaymentStatus.PAID, item1.getPaymentStatus());

        // Kiểm tra phân bổ Item 2 (Nhổ răng): Đã trả 200k, còn nợ 100k, trạng thái PARTIAL
        assertEquals(new BigDecimal("200000"), item2.getPaidAmount());
        assertEquals(new BigDecimal("100000"), item2.getRemainingDebt());
        assertEquals(InvoiceItemPaymentStatus.PARTIAL, item2.getPaymentStatus());

        // Kiểm tra hóa đơn tổng
        assertEquals(new BigDecimal("500000"), invoice.getPaidAmount());
        assertEquals(new BigDecimal("100000"), invoice.getRemainingDebt());
        assertEquals(InvoicePaymentStatus.PARTIAL, invoice.getPaymentStatus());
    }

    /**
     * Ca 2: Khách trả thêm 100k -> nhổ răng thanh toán đủ, hóa đơn hết nợ.
     */
    @Test
    @DisplayName("Ca 2: Khách trả nốt 100k -> Nhổ răng thanh toán đủ, hóa đơn hết nợ")
    void testScenario2_PayRemainingDebt_LineItemFullyPaid() {
        Invoice invoice = new Invoice();
        invoice.setInvoiceId(1L);
        invoice.setInvoiceCode("HD-TEST-02");
        invoice.setPatientProfile(patient);
        invoice.setBranch(branch);
        invoice.setTotalAmount(new BigDecimal("600000"));
        invoice.setPaidAmount(new BigDecimal("500000"));
        invoice.setRemainingDebt(new BigDecimal("100000"));
        invoice.setPaymentStatus(InvoicePaymentStatus.PARTIAL);
        invoice.setStatus(InvoiceStatus.CONFIRMED);

        InvoiceItem item1 = new InvoiceItem();
        item1.setItemId(101L);
        item1.setInvoice(invoice);
        item1.setItemName("Chụp X-quang");
        item1.setLineOrder(1);
        item1.setPayableAmount(new BigDecimal("300000"));
        item1.setPaidAmount(new BigDecimal("300000"));
        item1.setRemainingDebt(BigDecimal.ZERO);
        item1.setPaymentStatus(InvoiceItemPaymentStatus.PAID);

        InvoiceItem item2 = new InvoiceItem();
        item2.setItemId(102L);
        item2.setInvoice(invoice);
        item2.setItemName("Nhổ răng khôn");
        item2.setLineOrder(2);
        item2.setPayableAmount(new BigDecimal("300000"));
        item2.setPaidAmount(new BigDecimal("200000"));
        item2.setRemainingDebt(new BigDecimal("100000"));
        item2.setPaymentStatus(InvoiceItemPaymentStatus.PARTIAL);

        invoice.setItems(new ArrayList<>(List.of(item1, item2)));

        when(invoiceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(invoice));
        when(invoiceItemRepository.findByInvoice_InvoiceIdOrderByLineOrderAscItemIdAsc(1L))
                .thenReturn(List.of(item1, item2));

        PaymentRequestDTO request = new PaymentRequestDTO();
        request.setInvoiceId(1L);
        request.setAmount(new BigDecimal("100000"));
        request.setPaymentMethod(PaymentMethod.CASH);

        Payment payment = paymentService.processPayment(request, 1L);

        assertNotNull(payment);

        // Item 1 giữ nguyên không bị cộng dư
        assertEquals(new BigDecimal("300000"), item1.getPaidAmount());
        assertEquals(BigDecimal.ZERO, item1.getRemainingDebt());

        // Item 2 nhận đủ 100k -> trả hết 300k, còn nợ 0đ, PAID
        assertEquals(new BigDecimal("300000"), item2.getPaidAmount());
        assertEquals(BigDecimal.ZERO, item2.getRemainingDebt());
        assertEquals(InvoiceItemPaymentStatus.PAID, item2.getPaymentStatus());

        // Hóa đơn hoàn tất thanh toán
        assertEquals(new BigDecimal("600000"), invoice.getPaidAmount());
        assertEquals(BigDecimal.ZERO, invoice.getRemainingDebt());
        assertEquals(InvoicePaymentStatus.PAID, invoice.getPaymentStatus());
    }

    /**
     * Ca 3: Khách nộp tạm ứng 1.000k nhưng chưa dùng
     * -> Tiền vào ví tạm ứng, các dịch vụ chưa được ghi nhận đã thanh toán, không lưu phân bổ.
     */
    @Test
    @DisplayName("Ca 3: Nộp tạm ứng 1.000k -> Chỉ tăng số dư ví, không phân bổ vào dịch vụ hóa đơn")
    void testScenario3_AdvanceDepositOnly_DoesNotAllocateToItems() {
        patient.setDepositBalance(BigDecimal.ZERO);

        PaymentRequestDTO request = new PaymentRequestDTO();
        request.setProfileId(patient.getProfileId());
        request.setBranchId(branch.getBranchId());
        request.setAmount(new BigDecimal("1000000"));
        request.setPaymentMethod(PaymentMethod.CASH);

        Payment deposit = paymentService.processAdvanceDeposit(request, 1L);

        assertNotNull(deposit);
        assertEquals(PaymentType.ADVANCE_DEPOSIT, deposit.getPaymentType());
        assertEquals(new BigDecimal("1000000"), deposit.getAmount());

        // Ví bệnh nhân được cộng 1 triệu
        assertEquals(new BigDecimal("1000000"), patient.getDepositBalance());

        // Tuyệt đối không gọi phân bổ dịch vụ khi nạp ví
        verify(paymentAllocationRepository, never()).save(any(PaymentAllocation.class));
    }

    /**
     * Ca 4: Thanh toán bằng tiền mặt hoặc chuyển khoản một phần -> Phân bổ đúng thứ tự ưu tiên FIFO.
     */
    @Test
    @DisplayName("Ca 4: Thanh toán tiền mặt/chuyển khoản một phần -> Phân bổ FIFO dịch vụ trước")
    void testScenario4_CashAndBankTransferFifoAllocation() {
        Invoice invoice = new Invoice();
        invoice.setInvoiceId(4L);
        invoice.setTotalAmount(new BigDecimal("600000"));
        invoice.setPaidAmount(BigDecimal.ZERO);
        invoice.setRemainingDebt(new BigDecimal("600000"));

        InvoiceItem i1 = new InvoiceItem();
        i1.setItemId(401L);
        i1.setItemName("Lấy cao răng");
        i1.setLineOrder(1);
        i1.setPayableAmount(new BigDecimal("200000"));
        i1.setPaidAmount(BigDecimal.ZERO);
        i1.setRemainingDebt(new BigDecimal("200000"));

        InvoiceItem i2 = new InvoiceItem();
        i2.setItemId(402L);
        i2.setItemName("Hàn răng thẩm mỹ");
        i2.setLineOrder(2);
        i2.setPayableAmount(new BigDecimal("400000"));
        i2.setPaidAmount(BigDecimal.ZERO);
        i2.setRemainingDebt(new BigDecimal("400000"));

        when(invoiceItemRepository.findByInvoice_InvoiceIdOrderByLineOrderAscItemIdAsc(4L))
                .thenReturn(List.of(i1, i2));

        Payment payment = new Payment();
        payment.setPaymentId(40L);
        payment.setPaymentCode("TT-04");
        payment.setInvoice(invoice);

        List<PaymentAllocation> allocations = allocationService.allocatePayment(payment, new BigDecimal("350000"));

        assertEquals(2, allocations.size());
        assertEquals(new BigDecimal("200000"), allocations.get(0).getAllocatedAmount());
        assertEquals(new BigDecimal("150000"), allocations.get(1).getAllocatedAmount());

        assertEquals(new BigDecimal("200000"), i1.getPaidAmount());
        assertEquals(BigDecimal.ZERO, i1.getRemainingDebt());
        assertEquals(InvoiceItemPaymentStatus.PAID, i1.getPaymentStatus());

        assertEquals(new BigDecimal("150000"), i2.getPaidAmount());
        assertEquals(new BigDecimal("250000"), i2.getRemainingDebt());
        assertEquals(InvoiceItemPaymentStatus.PARTIAL, i2.getPaymentStatus());
    }

    /**
     * Ca 5: Hóa đơn có cả dịch vụ, thuốc và phụ thu; có giảm giá pro-rata;
     * Phân bổ đúng thứ tự dịch vụ -> thuốc -> phụ thu.
     */
    @Test
    @DisplayName("Ca 5: Hóa đơn gồm Dịch vụ + Thuốc + Phụ thu + Giảm giá pro-rata; phân bổ đúng thứ tự ưu tiên")
    void testScenario5_CombinedServicesMedicinesSurchargeProRataDiscount() {
        Invoice invoice = new Invoice();
        invoice.setInvoiceId(5L);

        // 2 dịch vụ 500k mỗi dịch vụ = 1.000.000đ
        InvoiceItem s1 = new InvoiceItem();
        s1.setItemId(501L);
        s1.setItemName("Dịch vụ A");
        s1.setItemType(InvoiceItemType.SERVICE);
        s1.setFinalAmount(new BigDecimal("500000"));

        InvoiceItem s2 = new InvoiceItem();
        s2.setItemId(502L);
        s2.setItemName("Dịch vụ B");
        s2.setItemType(InvoiceItemType.SERVICE);
        s2.setFinalAmount(new BigDecimal("500000"));

        // 1 thuốc 200.000đ
        InvoiceItem med = new InvoiceItem();
        med.setItemId(503L);
        med.setItemName("Thuốc kháng sinh");
        med.setItemType(InvoiceItemType.MEDICINE);
        med.setFinalAmount(new BigDecimal("200000"));

        invoice.setItems(new ArrayList<>(List.of(s1, s2, med)));
        invoice.setDiscountAmount(new BigDecimal("200000")); // Giảm giá 200k áp cho dịch vụ
        invoice.setSurchargeAmount(new BigDecimal("100000")); // Phụ thu 100k
        invoice.setSurchargeDescription("Dụng cụ dùng 1 lần");

        // Khởi tạo nợ và pro-rata discount
        allocationService.initializeItemizedDebtOnInvoiceCreation(invoice);

        // Kiểm tra pro-rata: s1 giảm 100k (còn 400k), s2 giảm 100k (còn 400k)
        assertEquals(new BigDecimal("100000.00"), s1.getDiscountAmount());
        assertEquals(new BigDecimal("400000.00"), s1.getPayableAmount());
        assertEquals(new BigDecimal("100000.00"), s2.getDiscountAmount());
        assertEquals(new BigDecimal("400000.00"), s2.getPayableAmount());

        // Thuốc không bị giảm
        assertEquals(BigDecimal.ZERO, med.getDiscountAmount());
        assertEquals(new BigDecimal("200000"), med.getPayableAmount());

        // Phụ thu khởi tạo nợ 100k
        assertEquals(0, new BigDecimal("100000").compareTo(invoice.getSurchargeRemainingDebt()));

        // Tiến hành thanh toán 900.000đ:
        // Cần thanh toán hết s1 (400k), hết s2 (400k), thuốc được 100k (còn nợ 100k), phụ thu được 0k (còn nợ 100k)
        when(invoiceItemRepository.findByInvoice_InvoiceIdOrderByLineOrderAscItemIdAsc(5L))
                .thenReturn(List.of(s1, s2, med));

        Payment payment = new Payment();
        payment.setPaymentId(50L);
        payment.setPaymentCode("TT-05");
        payment.setInvoice(invoice);

        List<PaymentAllocation> allocations = allocationService.allocatePayment(payment, new BigDecimal("900000"));

        assertEquals(3, allocations.size());
        assertEquals(new BigDecimal("400000.00"), allocations.get(0).getAllocatedAmount()); // s1
        assertEquals(new BigDecimal("400000.00"), allocations.get(1).getAllocatedAmount()); // s2
        assertEquals(0, new BigDecimal("100000").compareTo(allocations.get(2).getAllocatedAmount()));    // med

        assertEquals(InvoiceItemPaymentStatus.PAID, s1.getPaymentStatus());
        assertEquals(InvoiceItemPaymentStatus.PAID, s2.getPaymentStatus());
        assertEquals(0, new BigDecimal("100000").compareTo(med.getRemainingDebt()));
        assertEquals(InvoiceItemPaymentStatus.PARTIAL, med.getPaymentStatus());
        assertEquals(0, new BigDecimal("100000").compareTo(invoice.getSurchargeRemainingDebt()));
    }

    /**
     * Ca 6: Giảm giá lớn làm một số dịch vụ còn phải trả 0đ
     * -> Phân bổ tiền thu bỏ qua dịch vụ 0đ, chuyển ngay sang dịch vụ tiếp theo.
     */
    @Test
    @DisplayName("Ca 6: Dịch vụ có nợ phải trả 0đ sau giảm giá được bỏ qua, phân bổ ngay cho dịch vụ tiếp theo")
    void testScenario6_HighDiscountMakesServiceZero_SkippedAndAllocatesNext() {
        Invoice invoice = new Invoice();
        invoice.setInvoiceId(6L);

        InvoiceItem s1 = new InvoiceItem();
        s1.setItemId(601L);
        s1.setItemName("Khám tư vấn miễn phí");
        s1.setLineOrder(1);
        s1.setPayableAmount(BigDecimal.ZERO);
        s1.setPaidAmount(BigDecimal.ZERO);
        s1.setRemainingDebt(BigDecimal.ZERO);
        s1.setPaymentStatus(InvoiceItemPaymentStatus.PAID);

        InvoiceItem s2 = new InvoiceItem();
        s2.setItemId(602L);
        s2.setItemName("Tẩy trắng răng");
        s2.setLineOrder(2);
        s2.setPayableAmount(new BigDecimal("400000"));
        s2.setPaidAmount(BigDecimal.ZERO);
        s2.setRemainingDebt(new BigDecimal("400000"));
        s2.setPaymentStatus(InvoiceItemPaymentStatus.UNPAID);

        when(invoiceItemRepository.findByInvoice_InvoiceIdOrderByLineOrderAscItemIdAsc(6L))
                .thenReturn(List.of(s1, s2));

        Payment payment = new Payment();
        payment.setPaymentId(60L);
        payment.setInvoice(invoice);

        List<PaymentAllocation> allocations = allocationService.allocatePayment(payment, new BigDecimal("250000"));

        assertEquals(1, allocations.size());
        assertEquals(s2.getItemId(), allocations.get(0).getInvoiceItem().getItemId());
        assertEquals(new BigDecimal("250000"), allocations.get(0).getAllocatedAmount());

        assertEquals(new BigDecimal("250000"), s2.getPaidAmount());
        assertEquals(new BigDecimal("150000"), s2.getRemainingDebt());
    }

    /**
     * Ca 7: Không bị ảnh hưởng thứ tự phân bổ khi bảng trên giao diện bị sort/lọc
     * -> Phân bổ luôn dựa trên lineOrder cố định đã chốt trong cơ sở dữ liệu.
     */
    @Test
    @DisplayName("Ca 7: Thứ tự phân bổ luôn chốt theo lineOrder cơ sở dữ liệu, không phụ thuộc sắp xếp frontend")
    void testScenario7_LineOrderStability_RegardlessOfCollectionOrder() {
        InvoiceItem item1 = new InvoiceItem();
        item1.setItemId(701L);
        item1.setItemName("Dịch vụ theo thứ tự 1");
        item1.setLineOrder(1);
        item1.setPayableAmount(new BigDecimal("100000"));
        item1.setPaidAmount(BigDecimal.ZERO);
        item1.setRemainingDebt(new BigDecimal("100000"));

        InvoiceItem item2 = new InvoiceItem();
        item2.setItemId(702L);
        item2.setItemName("Dịch vụ theo thứ tự 2");
        item2.setLineOrder(2);
        item2.setPayableAmount(new BigDecimal("200000"));
        item2.setPaidAmount(BigDecimal.ZERO);
        item2.setRemainingDebt(new BigDecimal("200000"));

        // Khi repository truy vấn bằng OrderByLineOrderAsc, thứ tự luôn là item1 trước item2
        when(invoiceItemRepository.findByInvoice_InvoiceIdOrderByLineOrderAscItemIdAsc(7L))
                .thenReturn(List.of(item1, item2));

        Payment payment = new Payment();
        payment.setPaymentId(70L);
        Invoice inv = new Invoice();
        inv.setInvoiceId(7L);
        payment.setInvoice(inv);

        List<PaymentAllocation> allocations = allocationService.allocatePayment(payment, new BigDecimal("150000"));

        assertEquals(2, allocations.size());
        assertEquals(1, allocations.get(0).getLineOrder());
        assertEquals(new BigDecimal("100000"), allocations.get(0).getAllocatedAmount());

        assertEquals(2, allocations.get(1).getLineOrder());
        assertEquals(new BigDecimal("50000"), allocations.get(1).getAllocatedAmount());
    }

    /**
     * Ca 8: Không cho phép tạo khoản thanh toán vượt quá số nợ còn lại của hóa đơn.
     */
    @Test
    @DisplayName("Ca 8: Từ chối thanh toán số tiền vượt quá công nợ còn lại của hóa đơn")
    void testScenario8_PaymentExceedsRemainingDebt_Rejected() {
        Invoice invoice = new Invoice();
        invoice.setInvoiceId(8L);
        invoice.setTotalAmount(new BigDecimal("500000"));
        invoice.setPaidAmount(new BigDecimal("300000"));
        invoice.setRemainingDebt(new BigDecimal("200000"));
        invoice.setPaymentStatus(InvoicePaymentStatus.PARTIAL);
        invoice.setStatus(InvoiceStatus.CONFIRMED);
        invoice.setBranch(branch);
        invoice.setPatientProfile(patient);

        when(invoiceRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(invoice));

        PaymentRequestDTO request = new PaymentRequestDTO();
        request.setInvoiceId(8L);
        request.setAmount(new BigDecimal("250000")); // Vượt quá 200k nợ
        request.setPaymentMethod(PaymentMethod.CASH);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                paymentService.processPayment(request, 1L));

        assertTrue(ex.getMessage().contains("vượt quá số nợ còn lại"));
    }

    /**
     * Ca 9: Idempotency - gửi trùng request thanh toán không bị phân bổ hai lần hoặc làm sai lệch công nợ.
     */
    @Test
    @DisplayName("Ca 9: Idempotency chống thanh toán lặp, trả về kết quả cũ và không phân bổ lần 2")
    void testScenario9_IdempotencyKey_PreventsDuplicateAllocation() {
        Invoice invoice = new Invoice();
        invoice.setInvoiceId(9L);
        invoice.setTotalAmount(new BigDecimal("500000"));
        invoice.setPaidAmount(new BigDecimal("200000"));
        invoice.setRemainingDebt(new BigDecimal("300000"));
        invoice.setPaymentStatus(InvoicePaymentStatus.PARTIAL);
        invoice.setStatus(InvoiceStatus.CONFIRMED);
        invoice.setBranch(branch);
        invoice.setPatientProfile(patient);

        Payment existingPayment = new Payment();
        existingPayment.setPaymentId(999L);
        existingPayment.setPaymentCode("TT-EXISTING-999");
        existingPayment.setInvoice(invoice);
        existingPayment.setPatientProfile(patient);
        existingPayment.setBranch(branch);
        existingPayment.setAmount(new BigDecimal("200000"));
        existingPayment.setPaymentMethod(PaymentMethod.CASH);
        existingPayment.setPaymentType(PaymentType.PAYMENT);
        existingPayment.setIdempotencyKey("KEY-123456");

        when(invoiceRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(invoice));
        when(paymentRepository.findByIdempotencyKey("KEY-123456")).thenReturn(Optional.of(existingPayment));

        PaymentRequestDTO duplicateReq = new PaymentRequestDTO();
        duplicateReq.setInvoiceId(9L);
        duplicateReq.setAmount(new BigDecimal("200000"));
        duplicateReq.setPaymentMethod(PaymentMethod.CASH);
        duplicateReq.setIdempotencyKey("KEY-123456");

        Payment result = paymentService.processPayment(duplicateReq, 1L);

        assertEquals(999L, result.getPaymentId());
        // Không tạo mới payment
        verify(paymentRepository, never()).save(argThat(p -> p != existingPayment));
        // Không phân bổ lại
        verify(paymentAllocationRepository, never()).save(any(PaymentAllocation.class));
    }

    /**
     * Ca 10: Thử nghiệm kiểm soát an toàn phân bổ (tổng phân bổ khớp chính xác số tiền thu).
     */
    @Test
    @DisplayName("Ca 10: Tổng số tiền phân bổ luôn khớp chính xác 100% với số tiền giao dịch")
    void testScenario10_TotalAllocationMatchesPaymentAmountExactly() {
        Invoice invoice = new Invoice();
        invoice.setInvoiceId(10L);

        InvoiceItem i1 = new InvoiceItem();
        i1.setItemId(1001L);
        i1.setLineOrder(1);
        i1.setPayableAmount(new BigDecimal("333333"));
        i1.setPaidAmount(BigDecimal.ZERO);
        i1.setRemainingDebt(new BigDecimal("333333"));

        InvoiceItem i2 = new InvoiceItem();
        i2.setItemId(1002L);
        i2.setLineOrder(2);
        i2.setPayableAmount(new BigDecimal("666667"));
        i2.setPaidAmount(BigDecimal.ZERO);
        i2.setRemainingDebt(new BigDecimal("666667"));

        when(invoiceItemRepository.findByInvoice_InvoiceIdOrderByLineOrderAscItemIdAsc(10L))
                .thenReturn(List.of(i1, i2));

        Payment payment = new Payment();
        payment.setPaymentId(100L);
        payment.setInvoice(invoice);

        BigDecimal payAmount = new BigDecimal("500000");
        List<PaymentAllocation> allocations = allocationService.allocatePayment(payment, payAmount);

        BigDecimal sumAllocated = allocations.stream()
                .map(PaymentAllocation::getAllocatedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(payAmount, sumAllocated, "Tổng tiền phân bổ phải bằng đúng số tiền thu!");
    }

    /**
     * Ca 11: Hóa đơn cũ chưa có phân bổ hiển thị rõ 'Chưa phân bổ chi tiết', không phát sinh lỗi khi xem.
     */
    @Test
    @DisplayName("Ca 11: Hóa đơn cũ chưa có phân bổ nhận diện đúng hasAllocations = false và xem an toàn không lỗi")
    void testScenario11_LegacyInvoice_DisplaysChuaPhanBoChiTiet() {
        Long legacyInvoiceId = 999L;

        when(paymentAllocationRepository.existsByInvoice_InvoiceId(legacyInvoiceId)).thenReturn(false);
        when(paymentAllocationRepository.findByInvoice_InvoiceIdOrderByLineOrderAscCreatedAtAsc(legacyInvoiceId))
                .thenReturn(Collections.emptyList());

        boolean hasAlloc = allocationService.hasAllocations(legacyInvoiceId);
        List<PaymentAllocationDTO> allocations = allocationService.getAllocationsByInvoiceId(legacyInvoiceId);

        assertFalse(hasAlloc, "Hóa đơn cũ chưa có phân bổ chi tiết phải trả về false");
        assertNotNull(allocations);
        assertTrue(allocations.isEmpty(), "Danh sách phân bổ hóa đơn cũ phải là danh sách rỗng an toàn");
    }

    /**
     * Kiểm tra bổ sung: Preview phân bổ trước khi thu tiền (Requirement 2 & 10)
     */
    @Test
    @DisplayName("Kiểm tra bổ sung: Xem trước phân bổ (Preview) trước khi xác nhận thu")
    void testPreviewPaymentAllocation() {
        Invoice invoice = new Invoice();
        invoice.setInvoiceId(12L);
        invoice.setInvoiceCode("HD-PREVIEW-12");
        invoice.setTotalAmount(new BigDecimal("800000"));
        invoice.setPaidAmount(BigDecimal.ZERO);
        invoice.setRemainingDebt(new BigDecimal("800000"));

        InvoiceItem i1 = new InvoiceItem();
        i1.setItemId(1201L);
        i1.setItemName("Dịch vụ A");
        i1.setItemType(InvoiceItemType.SERVICE);
        i1.setLineOrder(1);
        i1.setFinalAmount(new BigDecimal("500000"));
        i1.setPayableAmount(new BigDecimal("500000"));
        i1.setPaidAmount(BigDecimal.ZERO);
        i1.setRemainingDebt(new BigDecimal("500000"));

        InvoiceItem i2 = new InvoiceItem();
        i2.setItemId(1202L);
        i2.setItemName("Thuốc B");
        i2.setItemType(InvoiceItemType.MEDICINE);
        i2.setLineOrder(2);
        i2.setFinalAmount(new BigDecimal("300000"));
        i2.setPayableAmount(new BigDecimal("300000"));
        i2.setPaidAmount(BigDecimal.ZERO);
        i2.setRemainingDebt(new BigDecimal("300000"));

        when(invoiceRepository.findById(12L)).thenReturn(Optional.of(invoice));
        when(invoiceItemRepository.findByInvoice_InvoiceIdOrderByLineOrderAscItemIdAsc(12L))
                .thenReturn(List.of(i1, i2));

        PaymentAllocationPreviewDTO preview = allocationService.previewPaymentAllocation(12L, new BigDecimal("600000"));

        assertNotNull(preview);
        assertEquals(new BigDecimal("600000"), preview.getPaymentAmount());
        assertEquals(new BigDecimal("200000"), preview.getRemainingDebtAfterPayment());
        assertEquals(2, preview.getItems().size());

        // Dòng 1 được phân bổ 500k -> trả hết
        assertEquals(new BigDecimal("500000"), preview.getItems().get(0).getAllocatedAmount());
        assertEquals(BigDecimal.ZERO, preview.getItems().get(0).getRemainingDebtAfter());
        assertEquals("PAID", preview.getItems().get(0).getStatusAfter());

        // Dòng 2 được phân bổ 100k -> còn nợ 200k
        assertEquals(new BigDecimal("100000"), preview.getItems().get(1).getAllocatedAmount());
        assertEquals(new BigDecimal("200000"), preview.getItems().get(1).getRemainingDebtAfter());
        assertEquals("PARTIAL", preview.getItems().get(1).getStatusAfter());
    }
}

