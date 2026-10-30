package com.cvdcms.service;

import com.cvdcms.DTO.SalaryAdvanceRequestDTO;
import com.cvdcms.entity.SalaryAdvance;
import com.cvdcms.entity.enums.RefundMethod;
import com.cvdcms.entity.enums.SalaryAdvanceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

public interface SalaryAdvanceService {

    Page<SalaryAdvance> getAdvances(
            Long branchId,
            List<Long> allowedBranchIds,
            SalaryAdvanceStatus status,
            Long staffId,
            LocalDateTime startDate,
            LocalDateTime endDate,
            String keyword,
            Pageable pageable
    );

    SalaryAdvance getAdvanceDetail(Long advanceId, Long currentUserId);

    SalaryAdvance createAdvanceRequest(SalaryAdvanceRequestDTO dto, Long currentUserId);

    SalaryAdvance approveAdvance(Long advanceId, Long approverUserId);

    SalaryAdvance payAdvance(Long advanceId, RefundMethod paymentMethod, Long payerUserId);

    SalaryAdvance rejectAdvance(Long advanceId, String reason, Long rejecterUserId);

    List<SalaryAdvance> getActiveAdvancesForStaff(Long staffId);
}
