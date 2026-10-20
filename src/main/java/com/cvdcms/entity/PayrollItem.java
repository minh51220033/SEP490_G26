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
                @UniqueConstraint(name = "uk_pi_staff", columnNames = {"period_id", "staff_id"})
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

    @Column(name = "base_salary", nullable = false, precision = 15, scale = 2)
    private BigDecimal baseSalary = BigDecimal.ZERO;

    @Column(name = "actual_work_days", nullable = false, precision = 5, scale = 2)
    private BigDecimal actualWorkDays = BigDecimal.ZERO;

    @Column(name = "actual_base_salary", nullable = false, precision = 15, scale = 2)
    private BigDecimal actualBaseSalary = BigDecimal.ZERO;

    @Column(name = "total_commission", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalCommission = BigDecimal.ZERO;

    @Column(name = "total_allowance", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAllowance = BigDecimal.ZERO;

    @Column(name = "net_salary", nullable = false, precision = 15, scale = 2)
    private BigDecimal netSalary = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private PayrollItemStatus paymentStatus = PayrollItemStatus.UNPAID;
}
