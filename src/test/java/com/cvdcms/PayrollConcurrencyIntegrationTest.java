package com.cvdcms;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.PayrollService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class PayrollConcurrencyIntegrationTest {

    @Autowired
    private PayrollService payrollService;

    @Autowired
    private PayrollPeriodRepository payrollPeriodRepository;

    @Autowired
    private PayrollItemRepository payrollItemRepository;

    @Autowired
    private SalaryAdvanceRepository salaryAdvanceRepository;

    @Autowired
    private SalaryAdvanceAllocationRepository salaryAdvanceAllocationRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private StaffProfileRepository staffProfileRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("Kiểm thử đồng thời: Hai giao dịch chi trả hai kỳ cùng lúc từ 1 khoản tạm ứng (Chống Lost Update)")
    void testConcurrentPayPeriodSharingSameAdvanceNoLostUpdate() throws Exception {
        Branch branch = branchRepository.findById(1L).orElseThrow();
        StaffProfile staff = staffProfileRepository.findById(1L).orElseThrow();
        User adminUser = userRepository.findById(1L).orElseThrow();

        // 1. Tạo khoản tạm ứng 10 triệu đã chi (status = PAID), còn lại 10 triệu
        SalaryAdvance advance = new SalaryAdvance();
        advance.setAdvanceCode("ADV-CONC-" + UUID.randomUUID().toString().substring(0, 8));
        advance.setStaff(staff);
        advance.setBranch(branch);
        advance.setAmount(new BigDecimal("10000000.00"));
        advance.setAllocatedAmount(BigDecimal.ZERO);
        advance.setRemainingAmount(new BigDecimal("10000000.00"));
        advance.setStatus(SalaryAdvanceStatus.PAID);
        advance.setPaymentMethod(RefundMethod.BANK_TRANSFER);
        advance.setReason("Tạm ứng chi phí nha khoa");
        advance.setCreatedBy(adminUser);
        advance.setApprovedBy(staff);
        advance.setApprovedAt(LocalDateTime.now());
        advance.setPaidAt(LocalDateTime.now());
        final SalaryAdvance savedAdvance = salaryAdvanceRepository.save(advance);

        // 2. Tạo Kỳ lương 1 (APPROVED, lương 15tr, ứng 5tr, thực chi 10tr)
        PayrollPeriod period1 = new PayrollPeriod();
        period1.setPeriodName("Kỳ lương Concurrency 1 - " + UUID.randomUUID().toString().substring(0, 5));
        period1.setBranch(branch);
        period1.setMonth((int) (System.currentTimeMillis() % 12 + 1));
        period1.setYear(2030);
        period1.setStatus(PayrollStatus.APPROVED);
        period1.setTotalBaseSalary(new BigDecimal("15000000.00"));
        period1.setTotalAllowance(BigDecimal.ZERO);
        period1.setTotalBonus(BigDecimal.ZERO);
        period1.setTotalCommission(BigDecimal.ZERO);
        period1.setTotalDeduction(BigDecimal.ZERO);
        period1.setTotalSalary(new BigDecimal("15000000.00"));
        period1.setTotalAdvanceAllocated(new BigDecimal("5000000.00"));
        period1.setTotalRemainingPayable(new BigDecimal("10000000.00"));
        period1.setApprovedBy(staff);
        period1.setApprovedAt(LocalDateTime.now());
        PayrollPeriod savedP1 = payrollPeriodRepository.save(period1);

        PayrollItem item1 = new PayrollItem();
        item1.setPayrollPeriod(savedP1);
        item1.setStaff(staff);
        item1.setBaseSalary(new BigDecimal("15000000.00"));
        item1.setActualWorkDays(new BigDecimal("26.00"));
        item1.setActualBaseSalary(new BigDecimal("15000000.00"));
        item1.setTotalAllowance(BigDecimal.ZERO);
        item1.setBonusAmount(BigDecimal.ZERO);
        item1.setTotalCommission(BigDecimal.ZERO);
        item1.setDeductionAmount(BigDecimal.ZERO);
        item1.setNetSalary(new BigDecimal("15000000.00"));
        item1.setAdvanceAllocatedAmount(new BigDecimal("5000000.00"));
        item1.setRemainingPayable(new BigDecimal("10000000.00"));
        item1.setPaymentStatus(PayrollItemStatus.UNPAID);
        item1.setPeriodMonth(savedP1.getMonth());
        item1.setPeriodYear(savedP1.getYear());
        PayrollItem savedItem1 = payrollItemRepository.save(item1);

        SalaryAdvanceAllocation alloc1 = new SalaryAdvanceAllocation();
        alloc1.setAdvance(savedAdvance);
        alloc1.setPayrollItem(savedItem1);
        alloc1.setAllocatedAmount(new BigDecimal("5000000.00"));
        alloc1.setStatus(AdvanceAllocationStatus.RESERVED);
        salaryAdvanceAllocationRepository.save(alloc1);

        // 3. Tạo Kỳ lương 2 (APPROVED, lương 15tr, ứng 5tr, thực chi 10tr)
        PayrollPeriod period2 = new PayrollPeriod();
        period2.setPeriodName("Kỳ lương Concurrency 2 - " + UUID.randomUUID().toString().substring(0, 5));
        period2.setBranch(branch);
        period2.setMonth((int) ((System.currentTimeMillis() + 1) % 12 + 1));
        period2.setYear(2031);
        period2.setStatus(PayrollStatus.APPROVED);
        period2.setTotalBaseSalary(new BigDecimal("15000000.00"));
        period2.setTotalAllowance(BigDecimal.ZERO);
        period2.setTotalBonus(BigDecimal.ZERO);
        period2.setTotalCommission(BigDecimal.ZERO);
        period2.setTotalDeduction(BigDecimal.ZERO);
        period2.setTotalSalary(new BigDecimal("15000000.00"));
        period2.setTotalAdvanceAllocated(new BigDecimal("5000000.00"));
        period2.setTotalRemainingPayable(new BigDecimal("10000000.00"));
        period2.setApprovedBy(staff);
        period2.setApprovedAt(LocalDateTime.now());
        PayrollPeriod savedP2 = payrollPeriodRepository.save(period2);

        PayrollItem item2 = new PayrollItem();
        item2.setPayrollPeriod(savedP2);
        item2.setStaff(staff);
        item2.setBaseSalary(new BigDecimal("15000000.00"));
        item2.setActualWorkDays(new BigDecimal("26.00"));
        item2.setActualBaseSalary(new BigDecimal("15000000.00"));
        item2.setTotalAllowance(BigDecimal.ZERO);
        item2.setBonusAmount(BigDecimal.ZERO);
        item2.setTotalCommission(BigDecimal.ZERO);
        item2.setDeductionAmount(BigDecimal.ZERO);
        item2.setNetSalary(new BigDecimal("15000000.00"));
        item2.setAdvanceAllocatedAmount(new BigDecimal("5000000.00"));
        item2.setRemainingPayable(new BigDecimal("10000000.00"));
        item2.setPaymentStatus(PayrollItemStatus.UNPAID);
        item2.setPeriodMonth(savedP2.getMonth());
        item2.setPeriodYear(savedP2.getYear());
        PayrollItem savedItem2 = payrollItemRepository.save(item2);

        SalaryAdvanceAllocation alloc2 = new SalaryAdvanceAllocation();
        alloc2.setAdvance(savedAdvance);
        alloc2.setPayrollItem(savedItem2);
        alloc2.setAllocatedAmount(new BigDecimal("5000000.00"));
        alloc2.setStatus(AdvanceAllocationStatus.RESERVED);
        salaryAdvanceAllocationRepository.save(alloc2);

        // 4. Kích hoạt hai luồng thực thi đồng thời chi trả hai kỳ
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicReference<Throwable> err1 = new AtomicReference<>();
        AtomicReference<Throwable> err2 = new AtomicReference<>();

        executor.submit(() -> {
            try {
                startLatch.await();
                payrollService.payPeriod(savedP1.getPeriodId(), RefundMethod.BANK_TRANSFER, "REF-T1", adminUser.getUserId());
            } catch (Throwable t) {
                err1.set(t);
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                payrollService.payPeriod(savedP2.getPeriodId(), RefundMethod.BANK_TRANSFER, "REF-T2", adminUser.getUserId());
            } catch (Throwable t) {
                err2.set(t);
            } finally {
                doneLatch.countDown();
            }
        });

        // Nhả chốt để 2 luồng cùng lao vào
        startLatch.countDown();
        boolean finished = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(finished, "Cả hai luồng phải hoàn thành trong 15s");
        assertNull(err1.get(), "Luồng 1 không được có lỗi: " + (err1.get() != null ? err1.get().getMessage() : ""));
        assertNull(err2.get(), "Luồng 2 không được có lỗi: " + (err2.get() != null ? err2.get().getMessage() : ""));

        // 5. Kiểm tra kết quả sau hai giao dịch đồng thời
        SalaryAdvance finalAdvance = salaryAdvanceRepository.findById(savedAdvance.getAdvanceId()).orElseThrow();
        assertEquals(0, new BigDecimal("10000000.00").compareTo(finalAdvance.getAllocatedAmount()),
                "Tổng tiền đã cấn trừ phải là đúng 10.000.000đ (5tr + 5tr)");
        assertEquals(0, BigDecimal.ZERO.compareTo(finalAdvance.getRemainingAmount()),
                "Số dư còn lại của phiếu tạm ứng phải là đúng 0đ, không được mất cập nhật thành 5tr");
        assertEquals(SalaryAdvanceStatus.SETTLED, finalAdvance.getStatus(),
                "Phiếu tạm ứng phải được đổi trạng thái thành SETTLED");

        PayrollPeriod finalP1 = payrollPeriodRepository.findById(savedP1.getPeriodId()).orElseThrow();
        PayrollPeriod finalP2 = payrollPeriodRepository.findById(savedP2.getPeriodId()).orElseThrow();
        assertEquals(PayrollStatus.PAID, finalP1.getStatus());
        assertEquals(PayrollStatus.PAID, finalP2.getStatus());
    }

    @Test
    @DisplayName("Kiểm thử đồng thời: Chi trả (payPeriod) cạnh tranh với Trả về sửa đổi (rejectPeriod)")
    void testConcurrentPayPeriodVsRejectPeriodEnforcesStateConsistency() throws Exception {
        Branch branch = branchRepository.findById(1L).orElseThrow();
        StaffProfile staff = staffProfileRepository.findById(1L).orElseThrow();
        User adminUser = userRepository.findById(1L).orElseThrow();

        // Tạo 1 kỳ APPROVED
        PayrollPeriod period = new PayrollPeriod();
        period.setPeriodName("Kỳ lương Race Condition - " + UUID.randomUUID().toString().substring(0, 5));
        period.setBranch(branch);
        period.setMonth((int) ((System.currentTimeMillis() + 2) % 12 + 1));
        period.setYear(2032);
        period.setStatus(PayrollStatus.APPROVED);
        period.setTotalBaseSalary(new BigDecimal("10000000.00"));
        period.setTotalAllowance(BigDecimal.ZERO);
        period.setTotalBonus(BigDecimal.ZERO);
        period.setTotalCommission(BigDecimal.ZERO);
        period.setTotalDeduction(BigDecimal.ZERO);
        period.setTotalSalary(new BigDecimal("10000000.00"));
        period.setTotalAdvanceAllocated(BigDecimal.ZERO);
        period.setTotalRemainingPayable(new BigDecimal("10000000.00"));
        period.setApprovedBy(staff);
        period.setApprovedAt(LocalDateTime.now());
        final PayrollPeriod savedP = payrollPeriodRepository.save(period);

        PayrollItem item = new PayrollItem();
        item.setPayrollPeriod(savedP);
        item.setStaff(staff);
        item.setBaseSalary(new BigDecimal("10000000.00"));
        item.setActualWorkDays(new BigDecimal("26.00"));
        item.setActualBaseSalary(new BigDecimal("10000000.00"));
        item.setTotalAllowance(BigDecimal.ZERO);
        item.setBonusAmount(BigDecimal.ZERO);
        item.setTotalCommission(BigDecimal.ZERO);
        item.setDeductionAmount(BigDecimal.ZERO);
        item.setNetSalary(new BigDecimal("10000000.00"));
        item.setAdvanceAllocatedAmount(BigDecimal.ZERO);
        item.setRemainingPayable(new BigDecimal("10000000.00"));
        item.setPaymentStatus(PayrollItemStatus.UNPAID);
        item.setPeriodMonth(savedP.getMonth());
        item.setPeriodYear(savedP.getYear());
        payrollItemRepository.save(item);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicReference<String> payResult = new AtomicReference<>();
        AtomicReference<String> rejectResult = new AtomicReference<>();

        // Luồng 1: Chi trả lương
        executor.submit(() -> {
            try {
                startLatch.await();
                payrollService.payPeriod(savedP.getPeriodId(), RefundMethod.BANK_TRANSFER, "REF-RACE", adminUser.getUserId());
                successCount.incrementAndGet();
                payResult.set("SUCCESS");
            } catch (Throwable t) {
                failureCount.incrementAndGet();
                payResult.set("ERROR: " + t.getMessage());
            } finally {
                doneLatch.countDown();
            }
        });

        // Luồng 2: Trả về DRAFT
        executor.submit(() -> {
            try {
                startLatch.await();
                payrollService.rejectPeriod(savedP.getPeriodId(), "Admin từ chối tái duyệt", adminUser.getUserId());
                successCount.incrementAndGet();
                rejectResult.set("SUCCESS");
            } catch (Throwable t) {
                failureCount.incrementAndGet();
                rejectResult.set("ERROR: " + t.getMessage());
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        boolean finished = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(finished, "Cả hai luồng phải hoàn thành trong 15s");

        // Một luồng phải thành công và luồng kia phải thất bại do trạng thái đã đổi sau khi nhận khóa
        assertEquals(1, successCount.get(), "Chính xác 1 luồng phải thành công");
        assertEquals(1, failureCount.get(), "Chính xác 1 luồng phải bị từ chối do xung đột trạng thái");

        PayrollPeriod finalPeriod = payrollPeriodRepository.findById(savedP.getPeriodId()).orElseThrow();
        if ("SUCCESS".equals(payResult.get())) {
            assertEquals(PayrollStatus.PAID, finalPeriod.getStatus(), "Nếu payPeriod thắng, kỳ phải là PAID");
            assertTrue(rejectResult.get().contains("PAID") || rejectResult.get().contains("chi trả"),
                    "rejectPeriod phải báo lỗi không được sửa kỳ đã PAID");
        } else {
            assertEquals(PayrollStatus.DRAFT, finalPeriod.getStatus(), "Nếu rejectPeriod thắng, kỳ phải là DRAFT");
            assertTrue(payResult.get().contains("APPROVED") || payResult.get().contains("phê duyệt"),
                    "payPeriod phải báo lỗi kỳ không còn ở trạng thái APPROVED");
        }
    }
}
