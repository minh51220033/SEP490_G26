package com.cvdcms.service;

import com.cvdcms.DTO.OperatingExpenseRequestDTO;
import com.cvdcms.entity.OperatingExpense;
import com.cvdcms.entity.enums.ExpenseSourceType;
import com.cvdcms.entity.enums.RefundMethod;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface OperatingExpenseService {

    Page<OperatingExpense> getExpenses(
            Long branchId,
            List<Long> allowedBranchIds,
            String category,
            ExpenseSourceType sourceType,
            RefundMethod paymentMethod,
            LocalDate startDate,
            LocalDate endDate,
            Boolean isCancelled,
            String keyword,
            Pageable pageable
    );

    OperatingExpense getExpenseDetail(Long expenseId, Long currentUserId);

    OperatingExpense createManualExpense(OperatingExpenseRequestDTO dto, Long currentUserId);

    OperatingExpense updateManualExpense(Long expenseId, OperatingExpenseRequestDTO dto, String editReason, Long currentUserId);

    OperatingExpense cancelExpense(Long expenseId, String reason, Long currentUserId);

    BigDecimal getTotalActiveExpenses(Long branchId, List<Long> allowedBranchIds, LocalDate startDate, LocalDate endDate);

    BigDecimal getTotalActiveExpenses(
            Long branchId,
            List<Long> allowedBranchIds,
            String category,
            ExpenseSourceType sourceType,
            RefundMethod paymentMethod,
            LocalDate startDate,
            LocalDate endDate,
            String keyword
    );

    List<String> getCommonExpenseCategories();
}
