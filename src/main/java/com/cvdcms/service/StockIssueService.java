package com.cvdcms.service;

import com.cvdcms.DTO.StockIssueCreateDTO;
import com.cvdcms.DTO.StockIssueResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;

public interface StockIssueService {

    StockIssueResponseDTO create(StockIssueCreateDTO dto, Long userId);

    Page<StockIssueResponseDTO> searchIssues(
            Long branchId,
            String issueType,
            boolean excludeTransfer,
            String keyword,
            LocalDate fromDate,
            LocalDate toDate,
            Pageable pageable,
            Long userId
    );

    Page<StockIssueResponseDTO> searchTransfers(
            Long branchId,
            Long destinationBranchId,
            String keyword,
            LocalDate fromDate,
            LocalDate toDate,
            Pageable pageable,
            Long userId
    );

    StockIssueResponseDTO getById(Long issueId, Long userId);
}
