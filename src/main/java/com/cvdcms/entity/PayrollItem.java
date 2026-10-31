package com.cvdcms.entity;

import com.cvdcms.entity.enums.PayrollItemStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(
        name = "payroll_items",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_pi_staff", columnNames = {"period_id", "staff_id"}),
                @UniqueConstraint(name = "uk_payroll_item_staff_month_year", columnNames = {"staff_id", "period_month", "period_year"})
        }
)
@Getter
@Setter
@NoArgsConstructor
public class PayrollItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "item_id")
    private Long itemId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "period_id", nullable = false)
    private PayrollPeriod payrollPeriod;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_id", nullable = false)
    private StaffProfile staff;

    @Column(name = "period_month", nullable = false)
    private Integer periodMonth;

    @Column(name = "period_year", nullable = false)
    private Integer periodYear;

    @Column(name = "base_salary", nullable = false, precision = 15, scale = 2)
    private BigDecimal baseSalary = BigDecimal.ZERO;

    @Column(name = "actual_work_days", precision = 5, scale = 2)
    private BigDecimal actualWorkDays;

    @Column(name = "actual_base_salary", nullable = false, precision = 15, scale = 2)
    private BigDecimal actualBaseSalary = BigDecimal.ZERO;

    @Column(name = "total_allowance", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAllowance = BigDecimal.ZERO;

    @Column(name = "bonus_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal bonusAmount = BigDecimal.ZERO;

    @Column(name = "total_commission", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalCommission = BigDecimal.ZERO;

    @Column(name = "deduction_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal deductionAmount = BigDecimal.ZERO;

    @Column(name = "net_salary", nullable = false, precision = 15, scale = 2)
    private BigDecimal netSalary = BigDecimal.ZERO;

    @Column(name = "advance_allocated_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal advanceAllocatedAmount = BigDecimal.ZERO;

    @Column(name = "remaining_payable", nullable = false, precision = 15, scale = 2)
    private BigDecimal remainingPayable = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private PayrollItemStatus paymentStatus = PayrollItemStatus.UNPAID;

    @Column(name = "note", length = 500)
    private String note;
}
