package com.cvdcms.service;

import com.cvdcms.DTO.CashierPendingPatientDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CashierOverviewService {

    Optional<CashierPendingPatientDTO> findDebtDetail(Long profileId, List<Long> allowedBranchIds);

    /**
     * Tra cứu danh sách bệnh nhân trên tab Tổng quan Thu ngân (phiên bản tương thích cũ).
     */
    default Page<CashierPendingPatientDTO> getPendingCashierPatients(
            String keyword,
            List<Long> allowedBranchIds,
            Pageable pageable
    ) {
        return getPendingCashierPatients(keyword, null, null, null, null, allowedBranchIds, pageable);
    }

    /**
     * Tra cứu và lọc danh sách bệnh nhân trên tab Tổng quan Thu ngân hỗ trợ đa tiêu chí:
     * từ khóa (Tên, Mã BN, SĐT, Mã HĐ, Mã ca khám), cơ sở, khoảng ngày phát sinh và trạng thái xử lý.
     */
    Page<CashierPendingPatientDTO> getPendingCashierPatients(
            String keyword,
            Long branchId,
            LocalDate fromDate,
            LocalDate toDate,
            String status,
            List<Long> allowedBranchIds,
            Pageable pageable
    );
}

