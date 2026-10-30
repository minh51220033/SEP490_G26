package com.cvdcms.service;

import com.cvdcms.DTO.PayrollItemUpdateDTO;
import com.cvdcms.DTO.PayrollPeriodCreateDTO;
import com.cvdcms.entity.PayrollItem;
import com.cvdcms.entity.PayrollPeriod;
import com.cvdcms.entity.enums.PayrollStatus;
import com.cvdcms.entity.enums.RefundMethod;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface PayrollService {

    Page<PayrollPeriod> getPeriods(
            Long branchId,
            List<Long> allowedBranchIds,
            Integer month,
            Integer year,
            PayrollStatus status,
            Pageable pageable
    );

    PayrollPeriod getPeriodDetail(Long periodId, Long currentUserId);

    List<PayrollItem> getPeriodItems(Long periodId, Long currentUserId);

    PayrollPeriod createPeriod(PayrollPeriodCreateDTO dto, Long currentUserId);

    PayrollItem updateItem(Long itemId, PayrollItemUpdateDTO dto, Long currentUserId);

    PayrollPeriod recalculatePeriod(Long periodId, Long currentUserId);

    PayrollPeriod submitPeriod(Long periodId, Long currentUserId);

    PayrollPeriod approvePeriod(Long periodId, Long currentUserId);

    PayrollPeriod rejectPeriod(Long periodId, String reason, Long currentUserId);

    PayrollPeriod payPeriod(Long periodId, RefundMethod paymentMethod, String paymentReference, Long currentUserId);

    void deleteDraftPeriod(Long periodId, Long currentUserId);
}
