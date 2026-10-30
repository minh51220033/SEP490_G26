package com.cvdcms.service;

import com.cvdcms.DTO.OwnerDistributionRequestDTO;
import com.cvdcms.entity.OwnerDistribution;
import com.cvdcms.entity.enums.OwnerDistributionStatus;
import com.cvdcms.entity.enums.RefundMethod;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;

public interface OwnerDistributionService {

    Page<OwnerDistribution> getDistributions(
            Integer month,
            Integer year,
            OwnerDistributionStatus status,
            Pageable pageable
    );

    Page<OwnerDistribution> getDistributions(
            List<Long> allowedBranchIds,
            Integer month,
            Integer year,
            OwnerDistributionStatus status,
            Pageable pageable
    );

    OwnerDistribution getDistributionDetail(Long distributionId);

    OwnerDistribution createProposal(OwnerDistributionRequestDTO dto, Long currentUserId);

    OwnerDistribution approveDistribution(Long distributionId, Long currentUserId);

    OwnerDistribution rejectDistribution(Long distributionId, String reason, Long currentUserId);

    OwnerDistribution payDistribution(Long distributionId, RefundMethod paymentMethod, String paymentReference, Long currentUserId);
}
