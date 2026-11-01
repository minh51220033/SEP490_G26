package com.cvdcms;

import com.cvdcms.DTO.BranchBankAccountDTO;
import com.cvdcms.DTO.BranchBankAccountOptionDTO;
import com.cvdcms.DTO.PaymentQrInfoDTO;
import com.cvdcms.DTO.PaymentRequestDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.InvoicePaymentStatus;
import com.cvdcms.entity.enums.InvoiceStatus;
import com.cvdcms.entity.enums.PaymentMethod;
import com.cvdcms.entity.enums.PaymentType;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.repository.*;
import com.cvdcms.service.impl.BranchBankAccountServiceImpl;
import com.cvdcms.service.impl.PaymentServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class BranchBankAccountAndVietQrUnitTest {

    @Mock
    private BranchBankAccountRepository branchBankAccountRepository;
    @Mock
    private BranchRepository branchRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private InvoiceAuditLogRepository invoiceAuditLogRepository;
    @Mock
    private PatientProfileRepository patientProfileRepository;
    @Mock
    private StaffProfileRepository staffProfileRepository;
    @Mock
    private StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    @Mock
    private PatientLoyaltyWalletRepository patientLoyaltyWalletRepository;
    @Mock
    private PointTransactionRepository pointTransactionRepository;
    @Mock
    private MembershipTierRepository membershipTierRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SystemAuditLogRepository systemAuditLogRepository;

    private BranchBankAccountServiceImpl branchBankAccountService;
    private PaymentServiceImpl paymentService;

    private Branch branch1;
    private Branch branch2;
    private BranchBankAccount accountBranch1Default;
    private BranchBankAccount accountBranch1Secondary;
    private BranchBankAccount accountBranch2Default;
    private BranchBankAccount accountBranch1Inactive;

    private User cashierUser;
    private StaffProfile cashierStaff;
    private PatientProfile patient;
    private Invoice invoiceBranch1;

    @BeforeEach
    void setUp() {
        branchBankAccountService = new BranchBankAccountServiceImpl(
                branchBankAccountRepository,
                branchRepository,
                paymentRepository,
                invoiceRepository,
                userRepository,
                systemAuditLogRepository,
                new ObjectMapper()
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

        // Branch 1
        branch1 = new Branch();
        branch1.setBranchId(1L);
        branch1.setBranchName("Cơ sở Quận 1");
        branch1.setBranchCode("CN01");

        // Branch 2
        branch2 = new Branch();
        branch2.setBranchId(2L);
        branch2.setBranchName("Cơ sở Bình Thạnh");
        branch2.setBranchCode("CN02");

        // Account Branch 1 Default: Vietcombank
        accountBranch1Default = new BranchBankAccount();
        accountBranch1Default.setAccountId(101L);
        accountBranch1Default.setBranch(branch1);
        accountBranch1Default.setBankBin("970436");
        accountBranch1Default.setBankName("Vietcombank");
        accountBranch1Default.setAccountNumber("0011004328888");
        accountBranch1Default.setAccountHolderName("NHA KHOA KY THUAT CAO");
        accountBranch1Default.setIsDefault(true);
        accountBranch1Default.setIsActive(true);

        // Account Branch 1 Secondary: MBBank
        accountBranch1Secondary = new BranchBankAccount();
        accountBranch1Secondary.setAccountId(102L);
        accountBranch1Secondary.setBranch(branch1);
        accountBranch1Secondary.setBankBin("970422");
        accountBranch1Secondary.setBankName("MBBank");
        accountBranch1Secondary.setAccountNumber("8888123456789");
        accountBranch1Secondary.setAccountHolderName("NHA KHOA KY THUAT CAO");
        accountBranch1Secondary.setIsDefault(false);
        accountBranch1Secondary.setIsActive(true);

        // Account Branch 2 Default: Techcombank
        accountBranch2Default = new BranchBankAccount();
        accountBranch2Default.setAccountId(201L);
        accountBranch2Default.setBranch(branch2);
        accountBranch2Default.setBankBin("970407");
        accountBranch2Default.setBankName("Techcombank");
        accountBranch2Default.setAccountNumber("19036789999018");
        accountBranch2Default.setAccountHolderName("NHA KHOA KY THUAT CAO");
        accountBranch2Default.setIsDefault(true);
        accountBranch2Default.setIsActive(true);

        // Account Branch 1 Inactive
        accountBranch1Inactive = new BranchBankAccount();
        accountBranch1Inactive.setAccountId(103L);
        accountBranch1Inactive.setBranch(branch1);
        accountBranch1Inactive.setBankBin("970415");
        accountBranch1Inactive.setBankName("VietinBank");
        accountBranch1Inactive.setAccountNumber("101888999000");
        accountBranch1Inactive.setAccountHolderName("NHA KHOA KY THUAT CAO");
        accountBranch1Inactive.setIsDefault(false);
        accountBranch1Inactive.setIsActive(false);

        // Cashier user & staff
        cashierUser = new User();
        cashierUser.setUserId(5L);
        cashierUser.setUsername("cashier01");
        cashierUser.setIsActive(true);
        cashierUser.setStatus("ACTIVE");

        cashierStaff = new StaffProfile();
        cashierStaff.setStaffId(55L);
        cashierStaff.setUser(cashierUser);
        cashierStaff.setFullName("Thu Ngân 01");
        cashierStaff.setStaffCode("NV055");
        cashierStaff.setStatus(StaffStatus.ACTIVE);

        when(userRepository.findById(5L)).thenReturn(Optional.of(cashierUser));
        when(staffProfileRepository.findByUser_UserId(5L)).thenReturn(Optional.of(cashierStaff));
        when(userRepository.hasActiveAdminRole(5L)).thenReturn(true);

        // Patient
        patient = new PatientProfile();
        patient.setProfileId(88L);
        patient.setPatientCode("BN088");
        patient.setFullName("Lê Văn Kiểm Thử");
        patient.setDepositBalance(BigDecimal.ZERO);

        when(patientProfileRepository.findById(88L)).thenReturn(Optional.of(patient));
        when(patientProfileRepository.findByIdForUpdate(88L)).thenReturn(Optional.of(patient));

        // Invoice on Branch 1
        invoiceBranch1 = new Invoice();
        invoiceBranch1.setInvoiceId(1001L);
        invoiceBranch1.setInvoiceCode("HD1001");
        invoiceBranch1.setBranch(branch1);
        invoiceBranch1.setPatientProfile(patient);
        invoiceBranch1.setTotalAmount(new BigDecimal("2000000.00"));
        invoiceBranch1.setPaidAmount(BigDecimal.ZERO);
        invoiceBranch1.setRemainingDebt(new BigDecimal("2000000.00"));
        invoiceBranch1.setPaymentStatus(InvoicePaymentStatus.UNPAID);
        invoiceBranch1.setStatus(InvoiceStatus.CONFIRMED);

        when(invoiceRepository.findById(1001L)).thenReturn(Optional.of(invoiceBranch1));
        when(invoiceRepository.findByIdForUpdate(1001L)).thenReturn(Optional.of(invoiceBranch1));
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch1));
        when(branchRepository.findById(2L)).thenReturn(Optional.of(branch2));

        // Mock bank account queries
        when(branchBankAccountRepository.findById(101L)).thenReturn(Optional.of(accountBranch1Default));
        when(branchBankAccountRepository.findById(102L)).thenReturn(Optional.of(accountBranch1Secondary));
        when(branchBankAccountRepository.findById(201L)).thenReturn(Optional.of(accountBranch2Default));
        when(branchBankAccountRepository.findById(103L)).thenReturn(Optional.of(accountBranch1Inactive));

        when(branchBankAccountRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(accountBranch1Default));
        when(branchBankAccountRepository.findByIdForUpdate(102L)).thenReturn(Optional.of(accountBranch1Secondary));
        when(branchBankAccountRepository.findByIdForUpdate(201L)).thenReturn(Optional.of(accountBranch2Default));
        when(branchBankAccountRepository.findByIdForUpdate(103L)).thenReturn(Optional.of(accountBranch1Inactive));

        when(branchBankAccountRepository.findByBranch_BranchIdAndIsDefaultTrueAndIsActiveTrue(1L))
                .thenReturn(Optional.of(accountBranch1Default));
        when(branchBankAccountRepository.findByBranch_BranchIdAndIsDefaultTrueAndIsActiveTrue(2L))
                .thenReturn(Optional.of(accountBranch2Default));

        when(branchBankAccountRepository.findByBranch_BranchIdAndIsActiveTrueOrderByIsDefaultDescCreatedAtDesc(1L))
                .thenReturn(List.of(accountBranch1Default, accountBranch1Secondary));

        when(branchRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(branch1));
        when(branchRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(branch2));
        when(branchBankAccountRepository.findDuplicates(anyLong(), anyString(), anyString())).thenReturn(Collections.emptyList());

        when(branchBankAccountRepository.save(any(BranchBankAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            if (p.getPaymentId() == null) {
                p.setPaymentId(999L);
            }
            return p;
        });
    }

    // =========================================================================
    // TEST 1: SAI CHI NHÁNH (Wrong branch validation)
    // =========================================================================
    @Test
    @DisplayName("Kiểm thử 1: Thanh toán hóa đơn chi nhánh 1 nhưng chọn tài khoản của chi nhánh 2 -> Bị từ chối")
    void test1_wrongBranchForInvoicePayment_throwsException() {
        PaymentRequestDTO request = new PaymentRequestDTO();
        request.setInvoiceId(1001L); // Hóa đơn của Branch 1
        request.setAmount(new BigDecimal("1000000.00"));
        request.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        request.setBankAccountId(201L); // Tài khoản của Branch 2!

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                paymentService.processPayment(request, 5L));

        assertTrue(ex.getMessage().contains("không thuộc chi nhánh"),
                "Thông báo lỗi phải nêu rõ tài khoản không thuộc chi nhánh của hóa đơn");
    }

    @Test
    @DisplayName("Kiểm thử 1b: Nộp tạm ứng tại chi nhánh 1 nhưng truyền tài khoản của chi nhánh 2 -> Bị từ chối")
    void test1b_wrongBranchForAdvanceDeposit_throwsException() {
        PaymentRequestDTO request = new PaymentRequestDTO();
        request.setProfileId(88L);
        request.setBranchId(1L); // Tạm ứng tại Branch 1
        request.setAmount(new BigDecimal("500000.00"));
        request.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        request.setBankAccountId(201L); // Tài khoản của Branch 2!

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                paymentService.processAdvanceDeposit(request, 5L));

        assertTrue(ex.getMessage().contains("không thuộc chi nhánh"),
                "Thông báo lỗi phải nêu rõ tài khoản không thuộc chi nhánh tiếp nhận tạm ứng");
    }

    // =========================================================================
    // TEST 2: TÀI KHOẢN NGỪNG HOẠT ĐỘNG (Deactivated account)
    // =========================================================================
    @Test
    @DisplayName("Kiểm thử 2a: Không cho phép tài khoản ngừng hoạt động làm mặc định")
    void test2a_deactivatedAccount_cannotBeSetAsDefault() {
        // accountBranch1Inactive có isActive = false
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                branchBankAccountService.setDefaultAccount(103L, 5L, "127.0.0.1"));

        assertTrue(ex.getMessage().contains("ngừng sử dụng"),
                "Phải báo lỗi tài khoản đang ngừng sử dụng không thể đặt làm mặc định");
    }

    @Test
    @DisplayName("Kiểm thử 2b: Khi vô hiệu hóa tài khoản mặc định, tài khoản tự động mất cờ mặc định")
    void test2b_deactivateDefaultAccount_removesDefaultFlag() {
        assertTrue(accountBranch1Default.getIsDefault());

        branchBankAccountService.toggleAccountStatus(101L, false, 5L, "127.0.0.1");

        assertFalse(accountBranch1Default.getIsActive(), "Tài khoản phải chuyển sang ngừng hoạt động");
        assertFalse(accountBranch1Default.getIsDefault(), "Tài khoản ngừng hoạt động không được tiếp tục giữ cờ mặc định");
        verify(branchBankAccountRepository, atLeastOnce()).save(accountBranch1Default);
    }

    @Test
    @DisplayName("Kiểm thử 2c: Cố tình thanh toán bằng tài khoản đã ngừng hoạt động -> Bị từ chối")
    void test2c_paymentWithInactiveAccount_throwsException() {
        PaymentRequestDTO request = new PaymentRequestDTO();
        request.setInvoiceId(1001L);
        request.setAmount(new BigDecimal("1000000.00"));
        request.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        request.setBankAccountId(103L); // Tài khoản ngừng hoạt động

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                paymentService.processPayment(request, 5L));

        assertTrue(ex.getMessage().contains("ngừng sử dụng"),
                "Phải báo lỗi tài khoản đã ngừng sử dụng, không thể nhận thanh toán");
    }

    // =========================================================================
    // TEST 3: ĐẶT MẶC ĐỊNH ĐỒNG THỜI (Concurrent default setting)
    // =========================================================================
    @Test
    @DisplayName("Kiểm thử 3: Đặt mặc định đồng thời giữa nhiều tài khoản trong cùng chi nhánh -> Chỉ duy nhất 1 mặc định")
    void test3_concurrentSetDefault_ensuresSingleDefault() throws InterruptedException {
        // Giả lập danh sách tài khoản trong chi nhánh 1
        List<BranchBankAccount> branchAccounts = new ArrayList<>(List.of(accountBranch1Default, accountBranch1Secondary));
        when(branchBankAccountRepository.findByBranchIdForUpdate(1L)).thenReturn(branchAccounts);

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final Long targetAccountId = (i % 2 == 0) ? 101L : 102L;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // Đồng bộ hóa cấp dịch vụ / database transaction lock
                    synchronized (branchAccounts) {
                        branchBankAccountService.setDefaultAccount(targetAccountId, 5L, "127.0.0.1");
                    }
                    successCount.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        // Kiểm tra tính nhất quán: trong branch 1, tối đa và chính xác 1 tài khoản là mặc định
        long defaultCount = branchAccounts.stream().filter(b -> Boolean.TRUE.equals(b.getIsDefault())).count();
        assertEquals(1, defaultCount, "Bắt buộc chỉ có duy nhất 1 tài khoản mặc định cho chi nhánh sau khi thực thi đồng thời");
    }

    // =========================================================================
    // TEST 4: VIETQR KHI NẠP TẠM ỨNG CHƯA CÓ HÓA ĐƠN
    // =========================================================================
    @Test
    @DisplayName("Kiểm thử 4a: Sinh VietQR khi nạp tạm ứng chưa có hóa đơn (invoiceId = null)")
    void test4a_advanceDeposit_generateQrInfo() {
        BigDecimal depositAmount = new BigDecimal("5000000.00");

        PaymentQrInfoDTO qrInfo = branchBankAccountService.getQrInfo(
                1L, // Branch 1
                null, // Không có invoiceId
                88L, // profileId bệnh nhân
                null, // Tự động lấy mặc định
                depositAmount,
                "REF8888"
        );

        assertTrue(qrInfo.isSuccess(), "Sinh QR tạm ứng phải thành công");
        assertEquals("970436", qrInfo.getBankBin(), "Phải chọn đúng BIN Vietcombank mặc định chi nhánh 1");
        assertEquals("0011004328888", qrInfo.getAccountNumber());
        assertEquals(depositAmount, qrInfo.getAmount(), "Số tiền QR phải là số tiền tạm ứng, không phải nợ");
        assertTrue(qrInfo.getTransferContent().contains("TU88"), "Nội dung chuyển khoản phải theo cấu trúc tạm ứng không lộ thông tin lâm sàng");
        assertTrue(qrInfo.getQrUrl().contains("img.vietqr.io"), "URL phải trỏ đến chuẩn VietQR Quick Link");
        assertTrue(qrInfo.getQrUrl().contains("5000000"), "URL phải chứa số tiền tạm ứng");
    }

    @Test
    @DisplayName("Kiểm thử 4b: Ghi nhận nộp tiền tạm ứng qua BANK_TRANSFER lưu đầy đủ snapshot ngân hàng")
    void test4b_advanceDeposit_savesBankSnapshot() {
        PaymentRequestDTO request = new PaymentRequestDTO();
        request.setProfileId(88L);
        request.setBranchId(1L);
        request.setAmount(new BigDecimal("3000000.00"));
        request.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        request.setBankAccountId(101L); // Chọn tài khoản Vietcombank
        request.setExpectedTransferContent("TU88 REF999");
        request.setTransactionRef("VCB-TX-999888"); // Mã thực tế kế toán ghi nhận
        request.setIdempotencyKey("IDEMP-DEP-001");

        Payment saved = paymentService.processAdvanceDeposit(request, 5L);

        assertNotNull(saved);
        assertEquals(PaymentType.ADVANCE_DEPOSIT, saved.getPaymentType());
        assertEquals(PaymentMethod.BANK_TRANSFER, saved.getPaymentMethod());
        assertEquals(new BigDecimal("3000000.00"), saved.getAmount());
        assertNull(saved.getInvoice(), "Giao dịch nộp tạm ứng không bắt buộc invoiceId");

        // Kiểm tra snapshot ngân hàng
        assertNotNull(saved.getBankAccount(), "Phải lưu liên kết tài khoản ngân hàng");
        assertEquals("970436", saved.getBankBin());
        assertEquals("Vietcombank", saved.getBankName());
        assertEquals("0011004328888", saved.getBankAccountNumber());
        assertEquals("NHA KHOA KY THUAT CAO", saved.getBankAccountHolder());
        assertEquals("TU88 REF999", saved.getExpectedTransferContent());
        assertEquals("VCB-TX-999888", saved.getTransactionRef());

        // Kiểm tra số dư ví tạm ứng của bệnh nhân tăng đúng số tiền
        assertEquals(new BigDecimal("3000000.00"), patient.getDepositBalance());
    }

    // =========================================================================
    // TEST 5: BẢO TOÀN LỊCH SỬ (Tài khoản đã có giao dịch không thể sửa STK/xóa)
    // =========================================================================
    @Test
    @DisplayName("Kiểm thử 5a: Tài khoản đã có giao dịch không thể bị xóa cứng")
    void test5a_referencedAccount_cannotBeDeleted() {
        when(paymentRepository.existsByBankAccount_AccountId(101L)).thenReturn(true);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                branchBankAccountService.deleteAccount(101L, 5L, "127.0.0.1"));

        assertTrue(ex.getMessage().contains("không thể xóa cứng"),
                "Tài khoản đã tham chiếu phải bị chặn xóa cứng");
        verify(branchBankAccountRepository, never()).delete(any());
    }

    @Test
    @DisplayName("Kiểm thử 5b: Tài khoản đã có giao dịch không thể thay đổi số tài khoản hoặc ngân hàng")
    void test5b_referencedAccount_cannotChangeAccountNumberOrBank() {
        when(paymentRepository.existsByBankAccount_AccountId(101L)).thenReturn(true);

        BranchBankAccountDTO updateDto = BranchBankAccountDTO.builder()
                .branchId(1L)
                .bankBin("970407") // Cố tình đổi sang Techcombank
                .bankName("Techcombank")
                .accountNumber("9999999999") // Cố tình đổi số tài khoản
                .accountHolderName("NHA KHOA KY THUAT CAO")
                .isDefault(true)
                .isActive(true)
                .build();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                branchBankAccountService.updateAccount(101L, updateDto, 5L, "127.0.0.1"));

        assertTrue(ex.getMessage().contains("Không được phép thay đổi số tài khoản"),
                "Phải thông báo không được phép thay đổi số tài khoản hoặc ngân hàng khi đã có giao dịch");
    }

    // =========================================================================
    // TEST 6: THIẾU CẤU HÌNH NGÂN HÀNG (Missing bank account config)
    // =========================================================================
    @Test
    @DisplayName("Kiểm thử 6: Chi nhánh chưa cấu hình tài khoản -> Báo cần Admin cấu hình, không sinh QR sai")
    void test6_missingBankAccount_throwsClearMessage() {
        Branch branch3 = new Branch();
        branch3.setBranchId(3L);
        branch3.setBranchName("Cơ sở Gò Vấp");
        when(branchRepository.findById(3L)).thenReturn(Optional.of(branch3));
        when(branchBankAccountRepository.findByBranch_BranchIdAndIsActiveTrueOrderByIsDefaultDescCreatedAtDesc(3L))
                .thenReturn(Collections.emptyList());

        // Kiểm tra sinh QR
        PaymentQrInfoDTO qrInfo = branchBankAccountService.getQrInfo(3L, null, null, null, new BigDecimal("1000000"), "TT001");
        assertFalse(qrInfo.isSuccess(), "Sinh QR phải trả về thất bại nếu chi nhánh chưa có tài khoản");
        assertTrue(qrInfo.getErrorMessage().contains("chưa có tài khoản ngân hàng hoạt động"),
                "Phải báo rõ cần liên hệ Admin cấu hình");

        // Kiểm tra thu tiền hóa đơn tại chi nhánh 3
        Invoice invBranch3 = new Invoice();
        invBranch3.setInvoiceId(3001L);
        invBranch3.setBranch(branch3);
        invBranch3.setPatientProfile(patient);
        invBranch3.setRemainingDebt(new BigDecimal("500000.00"));
        invBranch3.setTotalAmount(new BigDecimal("500000.00"));
        invBranch3.setPaidAmount(BigDecimal.ZERO);
        invBranch3.setStatus(InvoiceStatus.CONFIRMED);
        invBranch3.setPaymentStatus(InvoicePaymentStatus.UNPAID);
        when(invoiceRepository.findByIdForUpdate(3001L)).thenReturn(Optional.of(invBranch3));

        PaymentRequestDTO payReq = new PaymentRequestDTO();
        payReq.setInvoiceId(3001L);
        payReq.setAmount(new BigDecimal("500000.00"));
        payReq.setPaymentMethod(PaymentMethod.BANK_TRANSFER);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                paymentService.processPayment(payReq, 5L));

        assertTrue(ex.getMessage().contains("chưa có tài khoản ngân hàng hoạt động"),
                "Backend phải báo rõ chi nhánh chưa có tài khoản hoạt động");
    }

    // =========================================================================
    // TEST 7: GIAO DỊCH CŨ CHƯA CÓ SNAPSHOT VẪN XEM ĐƯỢC BÌNH THƯỜNG
    // =========================================================================
    @Test
    @DisplayName("Kiểm thử 7: Giao dịch chuyển khoản cũ không có snapshot vẫn tương thích và xem được")
    void test7_oldPaymentWithoutSnapshot_readable() {
        Payment oldPayment = new Payment();
        oldPayment.setPaymentId(501L);
        oldPayment.setPaymentCode("TT-OLD-001");
        oldPayment.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        oldPayment.setAmount(new BigDecimal("1500000.00"));
        oldPayment.setBankAccount(null);
        oldPayment.setBankBin(null);
        oldPayment.setBankName(null);
        oldPayment.setBankAccountNumber(null);
        oldPayment.setBankAccountHolder(null);
        oldPayment.setExpectedTransferContent(null);
        oldPayment.setTransactionRef("OLD-REF-99");

        when(paymentRepository.findWithDetailsById(501L)).thenReturn(Optional.of(oldPayment));

        Payment retrieved = paymentService.getPaymentById(501L);
        assertNotNull(retrieved);
        assertEquals(PaymentMethod.BANK_TRANSFER, retrieved.getPaymentMethod());
        assertNull(retrieved.getBankAccount());
        assertNull(retrieved.getBankBin());
        assertEquals("OLD-REF-99", retrieved.getTransactionRef());
    }

    // =========================================================================
    // TEST 9: HÓA ĐƠN KHÔNG TỒN TẠI KHI SINH QR -> BÁO LỖI, KHÔNG DỰNG MÃ GIẢ
    // =========================================================================
    @Test
    @DisplayName("Kiểm thử 9: Hóa đơn không tồn tại khi lấy QR -> Báo lỗi, không tự dựng mã giả")
    void test9_qrInfo_invoiceDoesNotExist_returnsError() {
        when(invoiceRepository.findById(9999L)).thenReturn(Optional.empty());

        PaymentQrInfoDTO qrInfo = branchBankAccountService.getQrInfo(1L, 9999L, null, null, new BigDecimal("500000"), "TT001");
        assertFalse(qrInfo.isSuccess(), "Phải trả về thất bại khi hóa đơn không tồn tại");
        assertTrue(qrInfo.getErrorMessage().contains("không tồn tại"),
                "Thông báo phải nêu rõ hóa đơn không tồn tại trong hệ thống");
    }

    // =========================================================================
    // TEST 10: CHI NHÁNH YÊU CẦU MÂU THUẪN VỚI HÓA ĐƠN -> BÁO LỖI RÕ RÀNG
    // =========================================================================
    @Test
    @DisplayName("Kiểm thử 10: Client truyền branchId mâu thuẫn với chi nhánh của hóa đơn -> Bị từ chối")
    void test10_qrInfo_requestedBranchConflictsWithInvoiceBranch_returnsError() {
        // Hóa đơn 1001 thuộc Branch 1, nhưng client truyền branchId = 2
        PaymentQrInfoDTO qrInfo = branchBankAccountService.getQrInfo(2L, 1001L, null, null, new BigDecimal("500000"), "TT001");
        assertFalse(qrInfo.isSuccess(), "Phải từ chối sinh QR khi branchId yêu cầu không khớp hóa đơn");
        assertTrue(qrInfo.getErrorMessage().contains("mâu thuẫn"),
                "Phải thông báo chi nhánh yêu cầu mâu thuẫn với chi nhánh của hóa đơn");
    }

    // =========================================================================
    // TEST 11: CHỈ ĐỊNH ACCOUNT ID KHÔNG HỢP LỆ HOẶC KHÁC CHI NHÁNH -> BÁO LỖI
    // =========================================================================
    @Test
    @DisplayName("Kiểm thử 11: Chỉ định accountId không thuộc chi nhánh hoặc không tồn tại -> Báo lỗi, không tự ý chọn TK khác")
    void test11_qrInfo_specifiedAccountIdInvalidOrWrongBranch_returnsError() {
        // Hóa đơn 1001 thuộc Branch 1, nhưng client truyền accountId = 201 (thuộc Branch 2)
        PaymentQrInfoDTO qrInfo = branchBankAccountService.getQrInfo(1L, 1001L, null, 201L, new BigDecimal("500000"), "TT001");
        assertFalse(qrInfo.isSuccess(), "Phải báo lỗi khi accountId không thuộc chi nhánh");
        assertTrue(qrInfo.getErrorMessage().contains("không hợp lệ") || qrInfo.getErrorMessage().contains("không thuộc chi nhánh"),
                "Phải báo lỗi rõ ràng thay vì âm thầm chọn tài khoản khác");
    }

    // =========================================================================
    // TEST 12: VALIDATION TÀI KHOẢN NGÂN HÀNG (BIN, STK, TÊN CHỦ TK, TRÙNG LẶP)
    // =========================================================================
    @Test
    @DisplayName("Kiểm thử 12a: BIN không đúng 6 số -> Bị từ chối")
    void test12a_invalidBin_throwsException() {
        BranchBankAccountDTO dto = BranchBankAccountDTO.builder()
                .branchId(1L)
                .bankBin("12345") // 5 chữ số
                .bankName("Vietcombank")
                .accountNumber("001100223344")
                .accountHolderName("NGUYEN VAN A")
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                branchBankAccountService.createAccount(dto, 5L, "127.0.0.1"));
        assertTrue(ex.getMessage().contains("6 chữ số"));
    }

    @Test
    @DisplayName("Kiểm thử 12b: Tên chủ tài khoản chuẩn hóa rỗng hoặc quá ngắn -> Bị từ chối")
    void test12b_invalidHolderName_throwsException() {
        BranchBankAccountDTO dto = BranchBankAccountDTO.builder()
                .branchId(1L)
                .bankBin("970436")
                .bankName("Vietcombank")
                .accountNumber("001100223344")
                .accountHolderName("   ") // Chỉ toàn dấu cách
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                branchBankAccountService.createAccount(dto, 5L, "127.0.0.1"));
        assertTrue(ex.getMessage().contains("Tên chủ tài khoản"));
    }

    @Test
    @DisplayName("Kiểm thử 12c: Trùng chi nhánh + ngân hàng + STK -> Bị từ chối")
    void test12c_duplicateAccount_throwsException() {
        BranchBankAccount existing = new BranchBankAccount();
        existing.setAccountId(888L);
        when(branchBankAccountRepository.findDuplicates(1L, "970436", "001100223344"))
                .thenReturn(List.of(existing));

        BranchBankAccountDTO dto = BranchBankAccountDTO.builder()
                .branchId(1L)
                .bankBin("970436")
                .bankName("Vietcombank")
                .accountNumber("001100223344")
                .accountHolderName("NGUYEN VAN A")
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                branchBankAccountService.createAccount(dto, 5L, "127.0.0.1"));
        assertTrue(ex.getMessage().contains("đã tồn tại trong chi nhánh này"));
    }

    // =========================================================================
    // TEST 13: BỎ CHỌN KÍCH HOẠT TRONG UPDATE -> TỰ ĐỘNG BỎ CỜ MẶC ĐỊNH
    // =========================================================================
    @Test
    @DisplayName("Kiểm thử 13: Cập nhật tài khoản bỏ chọn kích hoạt (isActive = false) -> Tự động bỏ cờ mặc định")
    void test13_updateAccount_uncheckingActive_removesDefault() {
        BranchBankAccountDTO updateDto = BranchBankAccountDTO.builder()
                .branchId(1L)
                .bankBin("970436")
                .bankName("Vietcombank")
                .accountNumber(accountBranch1Default.getAccountNumber())
                .accountHolderName(accountBranch1Default.getAccountHolderName())
                .isActive(false) // Bỏ kích hoạt
                .isDefault(false)
                .build();

        BranchBankAccountDTO result = branchBankAccountService.updateAccount(101L, updateDto, 5L, "127.0.0.1");
        assertFalse(result.getIsActive(), "Tài khoản phải ở trạng thái ngừng hoạt động");
        assertFalse(result.getIsDefault(), "Tài khoản ngừng hoạt động không được giữ cờ mặc định");
    }
}
