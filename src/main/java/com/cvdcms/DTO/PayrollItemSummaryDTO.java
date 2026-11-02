package com.cvdcms.DTO;

import java.math.BigDecimal;

public record PayrollItemSummaryDTO(
        Long itemId,
        String staffCode,
        String staffName,
        String positionTitle,
        BigDecimal actualWorkDays,
        BigDecimal actualBaseSalary,
        BigDecimal totalAllowance,
        BigDecimal bonusAmount,
        BigDecimal totalCommission,
        BigDecimal deductionAmount,
        BigDecimal netSalary,
        BigDecimal advanceAllocatedAmount,
        BigDecimal remainingPayable,
        String note
) {}

