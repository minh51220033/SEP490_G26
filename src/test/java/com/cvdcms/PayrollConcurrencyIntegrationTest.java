package com.cvdcms;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.StaffBranchAssignmentRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.PayrollService;
import com.cvdcms.service.SalaryAdvanceService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Explicit reset of tagged financial DEMO data, followed by past-dated fixtures.
 * Replaces the old concurrency test at the user's request.
 * No TRUNCATE, no disabled foreign keys, no deletion by year alone.
 * All cleanup and inserts share one transaction. Failure rolls them back.
 * Not executed by normal test runs. Run only with -Ddemo.finance.reset=true.
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "demo.finance.reset", matches = "true")
public class PayrollConcurrencyIntegrationTest {
    @Autowired private EntityManager em;
    @Autowired private PayrollService payrollService;
    @Autowired private SalaryAdvanceService advanceService;
    @Autowired private UserRepository users;
    @Autowired private StaffBranchAssignmentRepository assignments;
    @Autowired private PlatformTransactionManager transactionManager;

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal SALARY = new BigDecimal("8000000.00");
    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000.00");
    private static final String PREFIX = "DEMO FT32 PAST | ";
    private long branchId;
    private long adminId;
    private String tag;
    private Branch branch;
    private User admin;
    private StaffProfile staff;

    @Test
    void resetTaggedDemoAndCreatePastDatedFixtures() {
        branchId = Long.parseLong(System.getProperty("demo.branchId", "1"));
        adminId = Long.parseLong(System.getProperty("demo.adminUserId", "1"));
        String database = System.getProperty("demo.database", "cv_dcms_db");
        tag = "D26B" + branchId + "-";
        assertTrue(tag.length() < 16, "branchId is too long for fixture codes");
        assertTrue(LocalDate.of(2026, 9, 30).isBefore(LocalDate.now()),
                "This fixture requires a date after 2026-09-30");
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            assertEquals(database, String.valueOf(em.createNativeQuery("SELECT DATABASE()").getSingleResult()),
                    "Wrong database. demo.database checks the name; it does not change datasource URL");
            branch = em.find(Branch.class, branchId);
            admin = em.find(User.class, adminId);
            assertNotNull(branch, "Missing branch. Correct demo.branchId");
            assertNotNull(admin, "Missing Admin user. Correct demo.adminUserId");
            assertTrue(users.hasActiveAdminRole(adminId), "The chosen user must have an active Admin role");
            assertTrue(Boolean.TRUE.equals(admin.getIsActive()) && "ACTIVE".equalsIgnoreCase(admin.getStatus()),
                    "Admin account must be active");

            cleanupTaggedData();
            em.clear();
            branch = em.find(Branch.class, branchId);
            admin = em.find(User.class, adminId);
            assertEquals(0L, number("SELECT COUNT(*) FROM payroll_periods WHERE branch_id=" + branchId
                            + " AND year=2026 AND month BETWEEN 6 AND 9"),
                    "Months 6-9/2026 contain non-demo payrolls. Reset rolled back; no data was removed");
            prepareDemoStaff();

            // June advance is actually paid, allocated and settled by June payroll.
            SalaryAdvance settled = advance("SETTLED", "Tạm ứng tháng 6 - sẽ được cấn đủ vào lương",
                    ONE_MILLION, SalaryAdvanceStatus.PAID, LocalDate.of(2026, 6, 10));
            payroll(6, PayrollStatus.PAID, settled);
            payroll(7, PayrollStatus.APPROVED, null);
            payroll(8, PayrollStatus.SUBMITTED, null);
            payroll(9, PayrollStatus.DRAFT, null);

            advance("PENDING", "Đề nghị tạm ứng để chi sinh hoạt", new BigDecimal("500000"),
                    SalaryAdvanceStatus.PENDING, LocalDate.of(2026, 9, 10));
            advance("APPROVED", "Tạm ứng đã duyệt, chờ kế toán chi", new BigDecimal("800000"),
                    SalaryAdvanceStatus.APPROVED, LocalDate.of(2026, 9, 11));
            advance("PAID", "Tạm ứng đã nhận, chờ cấn vào kỳ lương tiếp theo", ONE_MILLION,
                    SalaryAdvanceStatus.PAID, LocalDate.of(2026, 9, 12));
            advance("REJECTED", "Yêu cầu tạm ứng chưa đủ thông tin", new BigDecimal("300000"),
                    SalaryAdvanceStatus.REJECTED, LocalDate.of(2026, 9, 13));
            manualExpense(false);
            manualExpense(true);
            em.flush();
            em.clear();

            assertEquals(4L, number("SELECT COUNT(*) FROM payroll_periods WHERE branch_id=" + branchId
                    + " AND period_name LIKE 'DEMO FT32 PAST | %'"));
            assertEquals(5L, number("SELECT COUNT(*) FROM salary_advances WHERE advance_code LIKE '" + tag + "TU-%'"));
            assertEquals(1L, number("SELECT COUNT(*) FROM salary_advances WHERE advance_code='" + tag
                    + "TU-SETTLED' AND status='SETTLED' AND remaining_amount=0 AND allocated_amount=1000000"));
            assertEquals(5L, number("SELECT COUNT(*) FROM operating_expenses WHERE expense_code LIKE '" + tag + "CP-%'"));
            BigDecimal expenseTotal = (BigDecimal) em.createNativeQuery(
                    "SELECT SUM(amount) FROM operating_expenses WHERE expense_code LIKE '" + tag
                            + "CP-%' AND is_cancelled=0").getSingleResult();
            assertEquals(0, new BigDecimal("9500000").compareTo(expenseTotal));
            assertEquals(0L, number("SELECT COUNT(*) FROM salary_advances WHERE advance_code LIKE '" + tag
                    + "TU-%' AND (created_at>=CURRENT_DATE OR approved_at>=CURRENT_DATE OR paid_at>=CURRENT_DATE)"));
            assertEquals(0L, number("SELECT COUNT(*) FROM operating_expenses WHERE expense_code LIKE '" + tag
                    + "CP-%' AND (expense_date>=CURRENT_DATE OR created_at>=CURRENT_DATE OR cancelled_at>=CURRENT_DATE)"));
            assertEquals(0L, number("SELECT COUNT(*) FROM payroll_periods WHERE branch_id=" + branchId
                    + " AND period_name LIKE 'DEMO FT32 PAST | %' AND (created_at>=CURRENT_DATE"
                    + " OR updated_at>=CURRENT_DATE OR submitted_at>=CURRENT_DATE OR approved_at>=CURRENT_DATE OR paid_at>=CURRENT_DATE)"));
        });
        System.out.println("RESET COMMITTED: " + database + ", branch " + branchId);
        System.out.println("Payroll: 06/2026 PAID (7m + advance 1m), 07 APPROVED (8m), 08 SUBMITTED (8m), 09 DRAFT (8m).");
        System.out.println("Advances: PENDING 500k, APPROVED 800k, PAID 1m, SETTLED 1m, REJECTED 300k.");
        System.out.println("Expenses: 5 tagged records; 4 active total 9.5m, 1 cancelled 200k. Filter by year 2026 / code " + tag);
    }

    private void cleanupTaggedData() {
        List<Long> periods = ids("SELECT period_id FROM payroll_periods WHERE branch_id=" + branchId
                + " AND (period_name LIKE 'Kỳ lương Concurrency %' OR period_name LIKE 'Kỳ lương Race Condition %'"
                + " OR period_name LIKE 'DEMO FT32 %')");
        List<Long> items = ids("SELECT item_id FROM payroll_items WHERE period_id IN " + in(periods));
        List<Long> advances = ids("SELECT advance_id FROM salary_advances WHERE branch_id=" + branchId
                + " AND (advance_code LIKE 'ADV-CONC-%' OR advance_code LIKE '" + tag + "TU-%')");
        List<Long> relatedAdvances = ids("SELECT DISTINCT advance_id FROM salary_advance_allocations WHERE item_id IN " + in(items));
        assertTrue(new HashSet<>(advances).containsAll(relatedAdvances),
                "A target payroll uses an untagged advance. No changes; inspect this link first");
        assertEquals(0L, number("SELECT COUNT(*) FROM salary_advance_allocations WHERE advance_id IN "
                        + in(advances) + " AND item_id NOT IN " + in(items)),
                "A test advance is linked to a payroll outside this reset. No changes");
        assertEquals(0L, number("SELECT COUNT(*) FROM service_commissions WHERE payroll_item_id IN " + in(items)),
                "A target payroll has service commissions. No changes; inspect before deleting");
        List<Long> expenses = ids("SELECT expense_id FROM operating_expenses WHERE payroll_period_id IN " + in(periods)
                + " OR salary_advance_id IN " + in(advances)
                + " OR (branch_id=" + branchId + " AND (expense_code IN ('EXP_TEST_CANCELLED','EXP_TEST_SPECIAL_CHARS')"
                + " OR expense_code LIKE '" + tag + "CP-%'))");
        assertEquals(0L, number("SELECT COUNT(*) FROM operating_expenses WHERE expense_id IN " + in(expenses)
                        + " AND ((payroll_period_id IS NOT NULL AND payroll_period_id NOT IN " + in(periods) + ")"
                        + " OR (salary_advance_id IS NOT NULL AND salary_advance_id NOT IN " + in(advances) + "))"),
                "A test expense references financial data outside the reset. No changes");
        System.out.println("Reset scope: payrolls=" + periods + "; advances=" + advances + "; expenses=" + expenses);
        deleteAudit("operating_expenses", expenses);
        deleteAudit("payroll_periods", periods);
        deleteAudit("payroll_items", items);
        deleteAudit("salary_advances", advances);
        exec("DELETE FROM operating_expenses WHERE expense_id IN " + in(expenses));
        exec("DELETE FROM salary_advance_allocations WHERE item_id IN " + in(items));
        exec("DELETE FROM payroll_items WHERE item_id IN " + in(items));
        exec("DELETE FROM payroll_periods WHERE period_id IN " + in(periods));
        exec("DELETE FROM salary_advances WHERE advance_id IN " + in(advances));
        // Keep staff/roles/accounts: other modules can reference these records.
    }

    private void prepareDemoStaff() {
        String code = tag + "NV";
        List<StaffProfile> existing = em.createQuery("SELECT s FROM StaffProfile s WHERE s.staffCode=:code", StaffProfile.class)
                .setParameter("code", code).getResultList();
        if (!existing.isEmpty()) {
            staff = existing.get(0);
            assertEquals(0L, number("SELECT COUNT(*) FROM payroll_items WHERE staff_id=" + staff.getStaffId()),
                    "Demo employee has payrolls outside reset. Transaction rolled back");
            assertEquals(0L, number("SELECT COUNT(*) FROM salary_advances WHERE staff_id=" + staff.getStaffId()),
                    "Demo employee has manually created advances. Transaction rolled back");
            assertTrue(assignments.hasActiveAssignment(staff.getStaffId(), branchId, AssignmentStatus.ACTIVE),
                    "Demo employee needs an active assignment in the selected branch");
            assertEquals(StaffStatus.ACTIVE, staff.getStatus());
            assertEquals(0, SALARY.compareTo(staff.getBaseSalary()));
            return;
        }
        LocalDate today = LocalDate.now();
        StaffBranchAssignment template = assignments.findByBranchAndStatus(branchId, AssignmentStatus.ACTIVE).stream()
                .filter(a -> a.getStaff().getStatus() == StaffStatus.ACTIVE)
                .filter(a -> a.getStartDate() != null && !a.getStartDate().isAfter(today))
                .filter(a -> a.getEndDate() == null || !a.getEndDate().isBefore(today))
                .findFirst().orElseThrow(() -> new IllegalStateException("Branch needs an active staff assignment"));
        staff = new StaffProfile();
        staff.setStaffCode(code);
        staff.setFullName("Nguyễn Minh An (DEMO tài chính)");
        staff.setBaseSalary(SALARY);
        staff.setAllowanceAmount(ZERO);
        staff.setInsuranceSalary(ZERO);
        staff.setStatus(StaffStatus.ACTIVE);
        staff.setHireDate(LocalDate.of(2026, 1, 5));
        em.persist(staff);
        StaffBranchAssignment assignment = new StaffBranchAssignment();
        assignment.setStaff(staff);
        assignment.setBranch(branch);
        assignment.setRole(template.getRole());
        assignment.setIsPrimary(true);
        assignment.setStatus(AssignmentStatus.ACTIVE);
        assignment.setStartDate(LocalDate.of(2026, 1, 5));
        em.persist(assignment);
        em.flush();
        exec("UPDATE staff_profiles SET created_at='2026-01-05 08:00:00', updated_at='2026-01-05 08:00:00' WHERE staff_id=" + staff.getStaffId());
        exec("UPDATE staff_branch_assignments SET created_at='2026-01-05 08:00:00' WHERE assignment_id=" + assignment.getAssignmentId());
    }

    private SalaryAdvance advance(String code, String reason, BigDecimal amount, SalaryAdvanceStatus target, LocalDate date) {
        SalaryAdvance a = new SalaryAdvance();
        a.setAdvanceCode(tag + "TU-" + code);
        a.setStaff(staff); a.setBranch(branch); a.setAmount(amount);
        a.setReason(reason); a.setNotes("Dữ liệu DEMO tài chính 2026");
        a.setPaymentMethod(RefundMethod.BANK_TRANSFER);
        a.setStatus(SalaryAdvanceStatus.PENDING);
        a.setAllocatedAmount(ZERO); a.setRemainingAmount(amount); a.setCreatedBy(admin);
        em.persist(a); em.flush();
        if (target == SalaryAdvanceStatus.APPROVED || target == SalaryAdvanceStatus.PAID) {
            advanceService.approveAdvance(a.getAdvanceId(), adminId);
        }
        if (target == SalaryAdvanceStatus.PAID) {
            advanceService.payAdvance(a.getAdvanceId(), RefundMethod.BANK_TRANSFER, adminId);
        }
        if (target == SalaryAdvanceStatus.REJECTED) {
            advanceService.rejectAdvance(a.getAdvanceId(), "Cần bổ sung thông tin trước khi duyệt", adminId);
        }
        em.flush(); em.refresh(a);
        assertEquals(target, a.getStatus());
        String approved = target == SalaryAdvanceStatus.APPROVED || target == SalaryAdvanceStatus.PAID
                ? stamp(date.plusDays(1), "09:00:00") : "NULL";
        String paid = target == SalaryAdvanceStatus.PAID ? stamp(date.plusDays(2), "10:00:00") : "NULL";
        exec("UPDATE salary_advances SET created_at=" + stamp(date, "08:00:00")
                + ", approved_at=" + approved + ", paid_at=" + paid + " WHERE advance_id=" + a.getAdvanceId());
        if (target == SalaryAdvanceStatus.PAID) {
            backdateExpense("salary_advance_id", a.getAdvanceId(), tag + "CP-TU-" + code, date.plusDays(2));
        }
        return a;
    }

    private void payroll(int month, PayrollStatus target, SalaryAdvance advance) {
        BigDecimal allocated = advance == null ? ZERO : advance.getAmount();
        BigDecimal remaining = SALARY.subtract(allocated);
        LocalDate created = LocalDate.of(2026, month, 25);
        PayrollPeriod p = new PayrollPeriod();
        p.setPeriodName(PREFIX + "Lương tháng " + month + "/2026");
        p.setBranch(branch); p.setMonth(month); p.setYear(2026); p.setStatus(PayrollStatus.DRAFT);
        p.setTotalBaseSalary(SALARY); p.setTotalSalary(SALARY);
        p.setTotalAllowance(ZERO); p.setTotalBonus(ZERO); p.setTotalCommission(ZERO); p.setTotalDeduction(ZERO);
        p.setTotalAdvanceAllocated(allocated); p.setTotalRemainingPayable(remaining);
        em.persist(p);
        PayrollItem i = new PayrollItem();
        i.setPayrollPeriod(p); i.setStaff(staff); i.setPeriodMonth(month); i.setPeriodYear(2026);
        i.setBaseSalary(SALARY); i.setActualBaseSalary(SALARY); i.setActualWorkDays(ZERO);
        i.setTotalAllowance(ZERO); i.setBonusAmount(ZERO); i.setTotalCommission(ZERO); i.setDeductionAmount(ZERO);
        i.setNetSalary(SALARY); i.setAdvanceAllocatedAmount(allocated); i.setRemainingPayable(remaining);
        i.setPaymentStatus(PayrollItemStatus.UNPAID);
        i.setNote("DEMO: lương tháng cố định 8 triệu; ngày công 0 là dữ liệu mẫu, không giảm lương.");
        em.persist(i);
        SalaryAdvanceAllocation allocation = null;
        if (advance != null) {
            allocation = new SalaryAdvanceAllocation();
            allocation.setAdvance(advance); allocation.setPayrollItem(i); allocation.setAllocatedAmount(allocated);
            allocation.setStatus(AdvanceAllocationStatus.RESERVED); em.persist(allocation);
        }
        em.flush();
        if (target != PayrollStatus.DRAFT) payrollService.submitPeriod(p.getPeriodId(), adminId);
        if (target == PayrollStatus.APPROVED || target == PayrollStatus.PAID) payrollService.approvePeriod(p.getPeriodId(), adminId);
        if (target == PayrollStatus.PAID) payrollService.payPeriod(p.getPeriodId(), RefundMethod.BANK_TRANSFER,
                tag + "LUONG-" + month, adminId);
        em.flush(); em.refresh(p); em.refresh(i);
        assertEquals(target, p.getStatus());
        assertEquals(target == PayrollStatus.PAID ? PayrollItemStatus.PAID : PayrollItemStatus.UNPAID, i.getPaymentStatus());
        String submitted = target != PayrollStatus.DRAFT ? stamp(created.plusDays(1), "08:00:00") : "NULL";
        String approved = target == PayrollStatus.APPROVED || target == PayrollStatus.PAID ? stamp(created.plusDays(2), "09:00:00") : "NULL";
        String paid = target == PayrollStatus.PAID ? stamp(created.plusDays(3), "10:00:00") : "NULL";
        LocalDate last = target == PayrollStatus.PAID ? created.plusDays(3) : target == PayrollStatus.APPROVED
                ? created.plusDays(2) : target == PayrollStatus.SUBMITTED ? created.plusDays(1) : created;
        exec("UPDATE payroll_periods SET created_at=" + stamp(created, "08:00:00") + ", updated_at=" + stamp(last, "10:00:00")
                + ", submitted_at=" + submitted + ", approved_at=" + approved + ", paid_at=" + paid + " WHERE period_id=" + p.getPeriodId());
        if (target == PayrollStatus.PAID) backdateExpense("payroll_period_id", p.getPeriodId(), tag + "CP-LUONG-" + month, last);
        if (allocation != null) {
            exec("UPDATE salary_advance_allocations SET created_at=" + stamp(created, "08:00:00")
                    + ", updated_at=" + stamp(last, "10:00:00") + " WHERE allocation_id=" + allocation.getAllocationId());
            // Settlement dirties the advance: restore its original historical dates after service flush.
            exec("UPDATE salary_advances SET created_at='2026-06-10 08:00:00', approved_at='2026-06-11 09:00:00',"
                    + " paid_at='2026-06-12 10:00:00' WHERE advance_id=" + advance.getAdvanceId());
        }
    }

    private void manualExpense(boolean cancelled) {
        OperatingExpense e = new OperatingExpense();
        e.setExpenseCode(tag + (cancelled ? "CP-HUY" : "CP-THUCONG"));
        e.setBranch(branch); e.setCategory(cancelled ? "Văn phòng phẩm" : "Điện nước");
        e.setAmount(new BigDecimal(cancelled ? "200000" : "500000"));
        e.setExpenseDate(LocalDate.of(2026, 9, 15)); e.setRecipientName("Nhà cung cấp DEMO");
        e.setPaymentMethod(RefundMethod.CASH); e.setSourceType(ExpenseSourceType.MANUAL);
        e.setDescription(cancelled ? "DEMO: phiếu nhập nhầm, đã hủy" : "DEMO: chi phí điện nước tháng 9/2026");
        e.setIsCancelled(cancelled); e.setCreatedBy(admin);
        if (cancelled) {
            e.setCancelledBy(admin); e.setCancelledReason("Nhập trùng phiếu chi mẫu");
            e.setCancelledAt(LocalDate.of(2026, 9, 16).atTime(9, 0));
        }
        em.persist(e); em.flush();
        exec("UPDATE operating_expenses SET created_at='2026-09-15 08:00:00' WHERE expense_id=" + e.getExpenseId());
    }

    private void backdateExpense(String foreignKey, long id, String code, LocalDate date) {
        int count = exec("UPDATE operating_expenses SET expense_code='" + code + "', expense_date='" + date
                + "', created_at=" + stamp(date, "10:00:00") + " WHERE " + foreignKey + "=" + id);
        assertEquals(1, count, "Expected one linked expense");
    }
    private void deleteAudit(String table, List<Long> ids) {
        exec("DELETE FROM system_audit_logs WHERE entity_table='" + table + "' AND entity_id IN " + in(ids));
    }
    private int exec(String sql) { return em.createNativeQuery(sql).executeUpdate(); }
    private long number(String sql) { return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue(); }
    private List<Long> ids(String sql) {
        List<?> rows = em.createNativeQuery(sql).getResultList();
        return rows.stream().map(x -> ((Number) x).longValue()).collect(Collectors.toList());
    }
    // Only database-returned numeric IDs are interpolated. Empty sets never match.
    private String in(List<Long> ids) {
        return ids.isEmpty() ? "(-1)" : ids.stream().map(String::valueOf).collect(Collectors.joining(",", "(", ")"));
    }
    private String stamp(LocalDate date, String time) { return "'" + date + " " + time + "'"; }
}
